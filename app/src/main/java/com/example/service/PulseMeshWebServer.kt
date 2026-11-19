// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
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
import java.net.InetSocketAddress
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
    val allReachableUrls: List<String> = emptyList(),
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
    val dstNodeId: String,
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
 * Pure-Kotlin/Java Embedded HTTP/1.1 + SSE Web Server for PulseMesh v1.2.5.
 * - Binds wildcard dual-stack ServerSocket exclusively on Dispatchers.IO (never on Main Thread).
 * - Automatically detects dynamic Android 11-16 SoftAP / Wi-Fi Direct gateway IPs (p2p*, ap*, swlan*, softap*, wlan1/2)
 *   while strictly filtering out unreachable cellular (rmnet/ccmni) and VPN (tun) interfaces.
 * - Holds a Wi-Fi High-Performance / Low-Latency lock so incoming browser TCP connections never stall.
 * - Bridges Chat, E2EE DMs, Offline Map Tiles, Shared Waypoints, SOS, PTT Voice, and full 1-on-1 Voice Calls.
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
        private val PREFERRED_PORTS = intArrayOf(8080, 8081, 8082, 8083, 8084, 8085, 8888, 9090)
        private const val MAX_POST_BODY_BYTES = 512 * 1024 // 512 KB cap
        private const val CLIENT_STALE_MS = 90_000L
        private val TILE_PATH_REGEX = Regex("^/api/tiles/(\\d{1,2})/(\\d+)/(\\d+)\\.png$")
        private val EXCLUDED_IFACE_PREFIXES = listOf(
            "rmnet", "r_rmnet", "ccmni", "tun", "tap", "ppp", "dummy", "lo", "epdg", "iwlan", "v4-rmnet", "clat"
        )
    }

    private val _status = MutableStateFlow(WebPortalStatus())
    val status: StateFlow<WebPortalStatus> = _status.asStateFlow()

    private val running = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var boundPort: Int = 8080
    @Volatile
    private var preferredHostIp: String = ""

    private var startJob: Job? = null
    private var acceptJob: Job? = null
    private var maintenanceJob: Job? = null
    private val workerExecutor = Executors.newCachedThreadPool()

    private val activeClients = ConcurrentHashMap<String, Long>()
    private val sseStreams = CopyOnWriteArrayList<SseConnection>()

    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var wifiLock: WifiManager.WifiLock? = null

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
        }.getOrElse {
            """{"name":"PulseMesh","short_name":"PulseMesh","display":"standalone","start_url":"/"}""".toByteArray(Charsets.UTF_8)
        }
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
            scope.launch(Dispatchers.IO) { refreshPortalUrl() }
            return
        }
        if (!starting.compareAndSet(false, true)) return

        _status.value = _status.value.copy(
            statusMessage = "Starting Wi-Fi Direct Web Server..."
        )

        startJob?.cancel()
        startJob = scope.launch(Dispatchers.IO) {
            try {
                val ss = openServerSocket()
                if (ss == null) {
                    _status.value = WebPortalStatus(
                        isRunning = false,
                        statusMessage = "Web Portal failed to bind ports 8080-9090"
                    )
                    return@launch
                }

                serverSocket = ss
                boundPort = ss.localPort
                running.set(true)
                acquireWifiLock()
                refreshPortalUrl()
                registerNsdService(boundPort)

                acceptJob?.cancel()
                acceptJob = scope.launch(Dispatchers.IO) {
                    while (isActive && running.get()) {
                        try {
                            val client = ss.accept()
                            client.tcpNoDelay = true
                            workerExecutor.execute { handleClientSocket(client) }
                        } catch (_: Exception) {
                            if (!running.get()) break
                        }
                    }
                }

                // Rapid IP probe bursts while Android brings up SoftAP / Wi-Fi Direct IPv4 addresses, then steady 3s poll
                maintenanceJob?.cancel()
                maintenanceJob = scope.launch(Dispatchers.IO) {
                    val quickProbes = longArrayOf(350L, 900L, 1500L, 2500L)
                    for (ms in quickProbes) {
                        delay(ms)
                        if (!isActive || !running.get()) return@launch
                        refreshPortalUrl()
                    }
                    var tick = 0
                    while (isActive && running.get()) {
                        delay(3_000L)
                        tick++
                        refreshPortalUrl()
                        if (tick % 5 == 0) {
                            pruneStaleClients()
                            sendSseKeepAlive()
                        }
                    }
                }
            } finally {
                starting.set(false)
            }
        }
    }

    fun updateHostIpHint(hostIpHint: String) {
        if (hostIpHint.isNotBlank()) {
            preferredHostIp = hostIpHint.trim()
            if (running.get()) {
                scope.launch(Dispatchers.IO) { refreshPortalUrl() }
            }
        }
    }

    fun stop() {
        startJob?.cancel()
        startJob = null
        starting.set(false)
        if (!running.getAndSet(false)) {
            releaseWifiLock()
            return
        }
        acceptJob?.cancel()
        acceptJob = null
        maintenanceJob?.cancel()
        maintenanceJob = null
        unregisterNsdService()
        releaseWifiLock()
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

    fun notifyVoiceFrame(srcNodeId: String, srcCallsign: String, payloadBase64: String, isCallAudio: Boolean = false) {
        if (!running.get() || sseStreams.isEmpty() || payloadBase64.isBlank()) return
        val json = JSONObject().apply {
            put("event", "voice")
            put("srcNodeId", srcNodeId)
            put("srcCallsign", srcCallsign)
            put("payload", payloadBase64)
            put("isCallAudio", isCallAudio)
        }.toString()
        broadcastSseBytes("data: $json\n\n".toByteArray(Charsets.UTF_8))
    }

    fun notifyCallSignal(type: String, srcNodeId: String, srcCallsign: String, dstNodeId: String, payload: String = "") {
        if (!running.get() || sseStreams.isEmpty()) return
        val json = JSONObject().apply {
            put("event", "call")
            put("type", type)
            put("srcNodeId", srcNodeId)
            put("srcCallsign", srcCallsign)
            put("dstNodeId", dstNodeId)
            put("payload", payload)
            put("timestamp", System.currentTimeMillis())
        }.toString()
        broadcastSseBytes("data: $json\n\n".toByteArray(Charsets.UTF_8))
    }

    private fun openServerSocket(): ServerSocket? {
        for (port in PREFERRED_PORTS) {
            val sock = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    // Wildcard bind (no specific IP) listens on ALL active IPv4/IPv6 local interfaces (p2p, ap, wlan, usb)
                    bind(InetSocketAddress(port), 64)
                }
            }.getOrNull()
            if (sock != null) return sock
        }
        return null
    }

    private fun acquireWifiLock() {
        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            if (wifiLock == null) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "PulseMesh:WebPortalLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
        }
    }

    private fun releaseWifiLock() {
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun refreshPortalUrl() {
        if (!running.get()) return
        val rankedIps = resolveRankedLocalIpv4s()
        val bestIp = rankedIps.firstOrNull()
            ?: preferredHostIp.ifBlank { AutoHotspotNegotiator.P2P_GO_IP }
        val primaryUrl = "http://$bestIp:$boundPort"
        val allUrls = rankedIps.map { "http://$it:$boundPort" }.ifEmpty { listOf(primaryUrl) }
        val cur = _status.value
        if (cur.portalUrl != primaryUrl || cur.allReachableUrls != allUrls || cur.activeClientCount != activeClients.size || !cur.isRunning) {
            _status.value = WebPortalStatus(
                isRunning = true,
                port = boundPort,
                hostIp = bestIp,
                portalUrl = primaryUrl,
                allReachableUrls = allUrls,
                activeClientCount = activeClients.size,
                statusMessage = "Web Portal LIVE at $primaryUrl"
            )
        }
    }

    private fun resolveRankedLocalIpv4s(): List<String> {
        val candidates = mutableListOf<Pair<Int, String>>()
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (EXCLUDED_IFACE_PREFIXES.any { name.startsWith(it) }) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        val isPrivate = ip.startsWith("192.168.") || ip.startsWith("10.") ||
                            (ip.startsWith("172.") && (ip.substringAfter("172.").substringBefore('.').toIntOrNull() in 16..31))
                        if (!isPrivate) continue
                        val priority = when {
                            name.startsWith("p2p") || ip == "192.168.49.1" -> 130
                            name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") -> 125
                            name.startsWith("wlan") && name != "wlan0" -> 120
                            name.startsWith("wlan") && ip.endsWith(".1") -> 115
                            name.startsWith("wlan") -> 85
                            name.startsWith("usb") || name.startsWith("rndis") || name.startsWith("ncm") || name.startsWith("eth") -> 70
                            else -> 0
                        }
                        if (priority > 0) {
                            candidates.add(priority to ip)
                        }
                    }
                }
            }
        }
        return candidates
            .sortedByDescending { it.first }
            .map { it.second }
            .distinct()
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
            socket.soTimeout = 15_000
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

            val isHead = method == "HEAD"

            when {
                (method == "GET" || isHead) && (path == "/" || path == "/index.html" || path == "/web" || path == "/web/" || path == "/app" || path == "/app/") -> {
                    writeResponse(output, 200, "OK", "text/html; charset=utf-8", cachedHtmlBytes, headOnly = isHead)
                }

                (method == "GET" || isHead) && (path == "/manifest.json" || path == "/web/manifest.json") -> {
                    writeResponse(output, 200, "OK", "application/manifest+json; charset=utf-8", cachedManifestBytes, headOnly = isHead)
                }

                (method == "GET" || isHead) && (path == "/sw.js" || path == "/web/sw.js") -> {
                    writeResponse(output, 200, "OK", "application/javascript; charset=utf-8", cachedSwBytes, headOnly = isHead)
                }

                (method == "GET" || isHead) && (path == "/api/icon.png" || path == "/assets/pulsemesh-logo.png") -> {
                    if (cachedIconBytes.isNotEmpty()) {
                        writeResponse(output, 200, "OK", "image/png", cachedIconBytes, headOnly = isHead)
                    } else {
                        writeResponse(output, 404, "Not Found", "text/plain", ByteArray(0), headOnly = isHead)
                    }
                }

                (method == "GET" || isHead) && path == "/api/info" -> {
                    val info = buildInfoJson(_status.value.portalUrl, activeClients.size)
                    writeJson(output, 200, info.toString(), headOnly = isHead)
                }

                method == "GET" && path == "/api/state" -> {
                    val clientId = query["clientNodeId"].orEmpty()
                    if (clientId.isNotBlank()) {
                        activeClients[clientId] = System.currentTimeMillis()
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
                    }
                    val submitted = WebSubmittedPacket(
                        packetId = obj.optString("packetId").ifBlank { "web_${System.currentTimeMillis()}" },
                        clientNodeId = clientId.ifBlank { "0xWEB0" },
                        callsign = obj.optString("callsign").ifBlank { "WebNode" },
                        publicKeyBase64 = obj.optString("publicKeyBase64", ""),
                        threadId = obj.optString("threadId", "channel_primary"),
                        dstNodeId = obj.optString("dstNodeId", ""),
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
                    when (submitted.type) {
                        "VOICE_PTT", "VOICE", "CALL_AUDIO" -> {
                            notifyVoiceFrame(
                                submitted.clientNodeId,
                                submitted.callsign,
                                submitted.payload,
                                isCallAudio = submitted.type == "CALL_AUDIO"
                            )
                        }
                        "CALL_RING", "CALL_ACCEPT", "CALL_DECLINE", "CALL_END" -> {
                            notifyCallSignal(
                                submitted.type,
                                submitted.clientNodeId,
                                submitted.callsign,
                                submitted.dstNodeId.ifBlank { "ALL" },
                                submitted.payload
                            )
                        }
                        else -> notifyStateChanged()
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
                runCatching { socket.shutdownOutput() }
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

    private fun writeJson(output: OutputStream, code: Int, json: String, headOnly: Boolean = false) {
        val reason = if (code == 200) "OK" else "Bad Request"
        writeResponse(output, code, reason, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8), headOnly = headOnly)
    }

    private fun writeResponse(
        output: OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        cacheControl: String = "no-store",
        headOnly: Boolean = false
    ) {
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: $cacheControl\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, HEAD, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        if (!headOnly && body.isNotEmpty()) output.write(body)
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
