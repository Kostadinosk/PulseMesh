// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.example.util.NativeTileMapManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class WebPortalStatus(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val hostIp: String = "",
    val portalUrl: String = "",
    val activeClientCount: Int = 0,
    val statusMessage: String = "Wi-Fi Direct Web Portal Idle"
)

data class WebClientRegistration(
    val nodeId: String,
    val callsign: String,
    val publicKeyBase64: String = "",
    val lat: Double? = null,
    val lng: Double? = null
)

data class WebSubmittedPacket(
    val packetId: String,
    val clientNodeId: String,
    val callsign: String,
    val publicKeyBase64: String,
    val threadId: String,
    val type: String,
    val text: String,
    val payload: String,
    val lat: Double?,
    val lng: Double?,
    val waypointTitle: String,
    val waypointType: String,
    val waypointLat: Double?,
    val waypointLng: Double?
)

/**
 * Pure-Kotlin/Java Embedded HTTP/1.1 + SSE Web Server for PulseMesh v1.2.
 * Automatically hosts the full offline PulseMesh Web Node whenever Wi-Fi Direct (192.168.49.1)
 * or Local Hotspot (192.168.43.1) is active, so any nearby phone/laptop browser can join the
 * mesh (Chat, E2EE DMs, Offline Cached Map Tiles, Shared Waypoints, PTT Voice & SOS) with zero
 * internet and zero app installation.
 */
class PulseMeshWebServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val buildInfoJson: (portalUrl: String, webClientCount: Int) -> JSONObject,
    private val buildStateJson: (clientNodeId: String) -> JSONObject,
    private val onClientRegistered: (WebClientRegistration) -> Unit,
    private val onWebPacketSubmitted: (WebSubmittedPacket) -> Unit
) {
    companion object {
        private val PREFERRED_PORTS = intArrayOf(8080, 8081, 8082, 8083, 8084, 8085)
        private const val MAX_POST_BODY_BYTES = 512 * 1024 // 512 KB cap (fits 200px JPEG photos)
        private const val CLIENT_STALE_MS = 90_000L
        private val TILE_PATH_REGEX = Regex("^/api/tiles/(\\d{1,2})/(\\d+)/(\\d+)\\.png$")
    }

    private val _status = MutableStateFlow(WebPortalStatus())
    val status: StateFlow<WebPortalStatus> = _status.asStateFlow()

    private val running = AtomicBoolean(false)
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var boundPort: Int = 8080
    @Volatile
    private var preferredHostIp: String = ""

    private var acceptJob: Job? = null
    private var maintenanceJob: Job? = null
    private val workerExecutor = Executors.newCachedThreadPool()

    private val activeClients = ConcurrentHashMap<String, Long>()
    private val sseStreams = CopyOnWriteArrayList<SseConnection>()

    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    private val cachedHtmlBytes: ByteArray by lazy {
        runCatching {
            context.assets.open("pulsemesh_web.html").use { it.readBytes() }
        }.getOrElse {
            "<html><body><h1>PulseMesh Web Portal Asset Missing</h1></body></html>".toByteArray(Charsets.UTF_8)
        }
    }

    private val cachedIconBytes: ByteArray by lazy {
        runCatching {
            context.assets.open("pulsemesh_icon.png").use { it.readBytes() }
        }.getOrElse { ByteArray(0) }
    }

    private val cachedManifestBytes: ByteArray by lazy {
        runCatching {
            context.assets.open("manifest.json").use { it.readBytes() }
                .toString(Charsets.UTF_8)
                .replace("../assets/pulsemesh-logo.png", "/api/icon.png")
                .toByteArray(Charsets.UTF_8)
        }.getOrElse { """{"name":"PulseMesh","short_name":"PulseMesh","display":"standalone","start_url":"/"}""".toByteArray(Charsets.UTF_8) }
    }

    private val cachedSwBytes: ByteArray by lazy {
        runCatching {
            context.assets.open("sw.js").use { it.readBytes() }
        }.getOrElse { ByteArray(0) }
    }

    private data class SseConnection(
        val socket: Socket,
        val output: OutputStream,
        val clientNodeId: String
    )

    fun start(hostIpHint: String = "") {
        if (hostIpHint.isNotBlank()) {
            preferredHostIp = hostIpHint.trim()
        }
        if (running.get()) {
            refreshPortalUrl()
            return
        }

        val ss = openServerSocket()
        if (ss == null) {
            _status.value = WebPortalStatus(
                isRunning = false,
                statusMessage = "Web Portal failed to bind port 8080-8085"
            )
            return
        }

        serverSocket = ss
        boundPort = ss.localPort
        running.set(true)

        val ip = resolveBestHostIp()
        val url = "http://$ip:$boundPort"
        _status.value = WebPortalStatus(
            isRunning = true,
            port = boundPort,
            hostIp = ip,
            portalUrl = url,
            activeClientCount = activeClients.size,
            statusMessage = "Web Portal LIVE at $url"
        )

        registerNsdService(boundPort)

        acceptJob?.cancel()
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && running.get()) {
                try {
                    val client = ss.accept()
                    workerExecutor.execute { handleClientSocket(client) }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }

        maintenanceJob?.cancel()
        maintenanceJob = scope.launch(Dispatchers.IO) {
            while (isActive && running.get()) {
                delay(15_000L)
                pruneStaleClients()
                refreshPortalUrl()
                sendSseKeepAlive()
            }
        }
    }

    fun updateHostIpHint(hostIpHint: String) {
        if (hostIpHint.isNotBlank()) {
            preferredHostIp = hostIpHint.trim()
            if (running.get()) refreshPortalUrl()
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        acceptJob?.cancel()
        acceptJob = null
        maintenanceJob?.cancel()
        maintenanceJob = null
        unregisterNsdService()
        for (sse in sseStreams) {
            runCatching { sse.socket.close() }
        }
        sseStreams.clear()
        activeClients.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        _status.value = WebPortalStatus(
            isRunning = false,
            port = boundPort,
            statusMessage = "Wi-Fi Direct Web Portal Stopped"
        )
    }

    fun isRunning(): Boolean = running.get()

    fun notifyStateChanged() {
        if (!running.get() || sseStreams.isEmpty()) return
        val frame = "data: {\"event\":\"refresh\"}\n\n".toByteArray(Charsets.UTF_8)
        broadcastSseBytes(frame)
    }

    fun notifyVoiceFrame(srcNodeId: String, srcCallsign: String, payloadBase64: String) {
        if (!running.get() || sseStreams.isEmpty() || payloadBase64.isBlank()) return
        val json = JSONObject().apply {
            put("event", "voice")
            put("srcNodeId", srcNodeId)
            put("srcCallsign", srcCallsign)
            put("payload", payloadBase64)
        }.toString()
        broadcastSseBytes("data: $json\n\n".toByteArray(Charsets.UTF_8))
    }

    private fun openServerSocket(): ServerSocket? {
        for (port in PREFERRED_PORTS) {
            val sock = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress("0.0.0.0", port), 32)
                }
            }.getOrNull()
            if (sock != null) return sock
        }
        return null
    }

    private fun refreshPortalUrl() {
        if (!running.get()) return
        val ip = resolveBestHostIp()
        val url = "http://$ip:$boundPort"
        val cur = _status.value
        if (cur.portalUrl != url || cur.activeClientCount != activeClients.size) {
            _status.value = cur.copy(
                hostIp = ip,
                portalUrl = url,
                activeClientCount = activeClients.size,
                statusMessage = "Web Portal LIVE at $url"
            )
        }
    }

    private fun resolveBestHostIp(): String {
        val candidates = mutableListOf<Pair<Int, String>>()
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val priority = when {
                            name.startsWith("p2p") || ip == "192.168.49.1" -> 100
                            name.startsWith("ap") || name.startsWith("swlan") || ip == "192.168.43.1" -> 95
                            name.startsWith("wlan") -> 80
                            name.startsWith("usb") || name.startsWith("rndis") || name.startsWith("eth") -> 70
                            else -> 40
                        }
                        candidates.add(priority to ip)
                    }
                }
            }
        }
        val best = candidates.maxByOrNull { it.first }?.second
        return best ?: preferredHostIp.ifBlank { AutoHotspotNegotiator.P2P_GO_IP }
    }

    private fun pruneStaleClients() {
        val cutoff = System.currentTimeMillis() - CLIENT_STALE_MS
        activeClients.entries.removeIf { it.value < cutoff }
    }

    private fun sendSseKeepAlive() {
        if (sseStreams.isEmpty()) return
        broadcastSseBytes(": keepalive\n\n".toByteArray(Charsets.UTF_8))
    }

    private fun broadcastSseBytes(bytes: ByteArray) {
        val dead = mutableListOf<SseConnection>()
        for (conn in sseStreams) {
            try {
                synchronized(conn.output) {
                    conn.output.write(bytes)
                    conn.output.flush()
                }
            } catch (_: Exception) {
                dead.add(conn)
            }
        }
        for (d in dead) {
            sseStreams.remove(d)
            runCatching { d.socket.close() }
        }
    }

    private fun handleClientSocket(socket: Socket) {
        var keepOpenForSse = false
        try {
            socket.soTimeout = 12_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val requestLine = readLineCrLf(input) ?: return
            val parts = requestLine.trim().split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val fullTarget = parts[1]

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLineCrLf(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }

            if (method == "OPTIONS") {
                writeResponse(output, 204, "No Content", "text/plain", ByteArray(0))
                return
            }

            val qIdx = fullTarget.indexOf('?')
            val path = if (qIdx >= 0) fullTarget.substring(0, qIdx) else fullTarget
            val query = if (qIdx >= 0) parseQuery(fullTarget.substring(qIdx + 1)) else emptyMap()

            val bodyBytes = if (method == "POST") {
                val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_POST_BODY_BYTES) ?: 0
                if (contentLength > 0) readExactBytes(input, contentLength) else ByteArray(0)
            } else {
                ByteArray(0)
            }

            when {
                method == "GET" && (path == "/" || path == "/index.html" || path == "/web" || path == "/web/") -> {
                    writeResponse(output, 200, "OK", "text/html; charset=utf-8", cachedHtmlBytes)
                }

                method == "GET" && (path == "/manifest.json" || path == "/web/manifest.json") -> {
                    writeResponse(output, 200, "OK", "application/manifest+json; charset=utf-8", cachedManifestBytes)
                }

                method == "GET" && (path == "/sw.js" || path == "/web/sw.js") -> {
                    writeResponse(output, 200, "OK", "application/javascript; charset=utf-8", cachedSwBytes)
                }

                method == "GET" && (path == "/api/icon.png" || path == "/assets/pulsemesh-logo.png") -> {
                    if (cachedIconBytes.isNotEmpty()) {
                        writeResponse(output, 200, "OK", "image/png", cachedIconBytes)
                    } else {
                        writeResponse(output, 404, "Not Found", "text/plain", ByteArray(0))
                    }
                }

                method == "GET" && path == "/api/info" -> {
                    val info = buildInfoJson(_status.value.portalUrl, activeClients.size)
                    writeJson(output, 200, info.toString())
                }

                method == "GET" && path == "/api/state" -> {
                    val clientId = query["clientNodeId"].orEmpty()
                    if (clientId.isNotBlank()) {
                        activeClients[clientId] = System.currentTimeMillis()
                        refreshPortalUrl()
                    }
                    val snap = buildStateJson(clientId)
                    writeJson(output, 200, snap.toString())
                }

                method == "GET" && path == "/api/events" -> {
                    val clientId = query["clientNodeId"].orEmpty()
                    if (clientId.isNotBlank()) {
                        activeClients[clientId] = System.currentTimeMillis()
                    }
                    socket.soTimeout = 0
                    val header = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/event-stream\r\n")
                        append("Cache-Control: no-cache\r\n")
                        append("Connection: keep-alive\r\n")
                        append("Access-Control-Allow-Origin: *\r\n\r\n")
                        append(": connected\n\n")
                    }
                    output.write(header.toByteArray(Charsets.UTF_8))
                    output.flush()
                    sseStreams.add(SseConnection(socket, output, clientId))
                    keepOpenForSse = true
                }

                method == "POST" && path == "/api/register" -> {
                    val obj = runCatching { JSONObject(String(bodyBytes, Charsets.UTF_8)) }.getOrNull()
                    if (obj == null) {
                        writeJson(output, 400, """{"ok":false,"error":"invalid_json"}""")
                        return
                    }
                    val nodeId = obj.optString("nodeId").trim()
                    val callsign = obj.optString("callsign").trim().ifBlank { "WebNode" }
                    if (nodeId.isNotBlank()) {
                        activeClients[nodeId] = System.currentTimeMillis()
                        refreshPortalUrl()
                        onClientRegistered(
                            WebClientRegistration(
                                nodeId = nodeId,
                                callsign = callsign,
                                publicKeyBase64 = obj.optString("publicKeyBase64", ""),
                                lat = if (obj.has("lat") && !obj.isNull("lat")) obj.optDouble("lat") else null,
                                lng = if (obj.has("lng") && !obj.isNull("lng")) obj.optDouble("lng") else null
                            )
                        )
                        notifyStateChanged()
                    }
                    writeJson(output, 200, """{"ok":true}""")
                }

                method == "POST" && path == "/api/send" -> {
                    val obj = runCatching { JSONObject(String(bodyBytes, Charsets.UTF_8)) }.getOrNull()
                    if (obj == null) {
                        writeJson(output, 400, """{"ok":false,"error":"invalid_json"}""")
                        return
                    }
                    val clientId = obj.optString("clientNodeId").trim()
                    if (clientId.isNotBlank()) {
                        activeClients[clientId] = System.currentTimeMillis()
                        refreshPortalUrl()
                    }
                    val submitted = WebSubmittedPacket(
                        packetId = obj.optString("packetId").ifBlank { "web_${System.currentTimeMillis()}" },
                        clientNodeId = clientId.ifBlank { "0xWEB0" },
                        callsign = obj.optString("callsign").ifBlank { "WebNode" },
                        publicKeyBase64 = obj.optString("publicKeyBase64", ""),
                        threadId = obj.optString("threadId", "channel_primary"),
                        type = obj.optString("type", "CHAT").uppercase(),
                        text = obj.optString("text", ""),
                        payload = obj.optString("payload", ""),
                        lat = if (obj.has("lat") && !obj.isNull("lat")) obj.optDouble("lat") else null,
                        lng = if (obj.has("lng") && !obj.isNull("lng")) obj.optDouble("lng") else null,
                        waypointTitle = obj.optString("waypointTitle", ""),
                        waypointType = obj.optString("waypointType", "RALLY"),
                        waypointLat = if (obj.has("waypointLat") && !obj.isNull("waypointLat")) obj.optDouble("waypointLat") else null,
                        waypointLng = if (obj.has("waypointLng") && !obj.isNull("waypointLng")) obj.optDouble("waypointLng") else null
                    )
                    onWebPacketSubmitted(submitted)
                    if (submitted.type == "VOICE_PTT" || submitted.type == "VOICE") {
                        notifyVoiceFrame(submitted.clientNodeId, submitted.callsign, submitted.payload)
                    } else {
                        notifyStateChanged()
                    }
                    writeJson(output, 200, """{"ok":true}""")
                }

                method == "GET" && path.startsWith("/api/tiles/") -> {
                    val match = TILE_PATH_REGEX.matchEntire(path)
                    if (match == null) {
                        writeResponse(output, 404, "Not Found", "text/plain", ByteArray(0))
                        return
                    }
                    val z = match.groupValues[1].toIntOrNull() ?: -1
                    val x = match.groupValues[2].toIntOrNull() ?: -1
                    val y = match.groupValues[3].toIntOrNull() ?: -1
                    val tileFile = findCachedTileFile(z, x, y)
                    if (tileFile != null && tileFile.exists() && tileFile.length() > 0L) {
                        val pngBytes = runCatching { tileFile.readBytes() }.getOrNull()
                        if (pngBytes != null && pngBytes.isNotEmpty()) {
                            writeResponse(output, 200, "OK", "image/png", pngBytes, cacheControl = "public, max-age=86400")
                            return
                        }
                    }
                    writeResponse(output, 404, "Not Found", "text/plain", ByteArray(0))
                }

                else -> {
                    writeResponse(output, 404, "Not Found", "text/plain", "Not Found".toByteArray(Charsets.UTF_8))
                }
            }
        } catch (_: Exception) {
            // Connection dropped cleanly
        } finally {
            if (!keepOpenForSse) {
                runCatching { socket.close() }
            }
        }
    }

    private fun findCachedTileFile(z: Int, x: Int, y: Int): File? {
        if (z !in 0..19 || x < 0 || y < 0) return null
        val primary = NativeTileMapManager.getTileFile(context, z, x, y)
        if (primary.exists() && primary.length() > 0L) return primary
        val legacy = File(File(context.cacheDir, "osm_tiles"), "${z}_${x}_${y}.png")
        if (legacy.exists() && legacy.length() > 0L) return legacy
        return null
    }

    private fun writeJson(output: OutputStream, code: Int, json: String) {
        val reason = if (code == 200) "OK" else "Bad Request"
        writeResponse(output, code, reason, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
    }

    private fun writeResponse(
        output: OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        cacheControl: String = "no-store"
    ) {
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: $cacheControl\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun readLineCrLf(input: BufferedInputStream): String? {
        val baos = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (baos.size() == 0) null else baos.toString("UTF-8")
            }
            if (b == '\n'.code) {
                val bytes = baos.toByteArray()
                val len = if (prev == '\r'.code && bytes.isNotEmpty()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.UTF_8)
            }
            if (baos.size() < 8192) {
                baos.write(b)
            }
            prev = b
        }
    }

    private fun readExactBytes(input: BufferedInputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(buf, offset, length - offset)
            if (n <= 0) break
            offset += n
        }
        return if (offset == length) buf else buf.copyOf(offset)
    }

    private fun parseQuery(qs: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (pair in qs.split('&')) {
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = runCatching { URLDecoder.decode(pair.substring(0, eq), "UTF-8") }.getOrDefault(pair.substring(0, eq))
                val v = runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrDefault(pair.substring(eq + 1))
                out[k] = v
            }
        }
        return out
    }

    private fun registerNsdService(port: Int) {
        runCatching {
            val mgr = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            nsdManager = mgr
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "PulseMesh-Web"
                serviceType = "_http._tcp."
                setPort(port)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {}
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            nsdRegistrationListener = listener
            mgr.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private fun unregisterNsdService() {
        runCatching {
            val listener = nsdRegistrationListener
            nsdRegistrationListener = null
            if (listener != null) {
                nsdManager?.unregisterService(listener)
            }
        }
    }
}
