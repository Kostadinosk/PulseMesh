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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream

data class WebPortalStatus(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val httpsPort: Int = 8443,
    val sslEnabled: Boolean = false,
    val httpsSelfTestPassed: Boolean = false,
    val sslError: String = "",
    val hostIp: String = "",
    val portalUrl: String = "",
    val httpsPortalUrl: String = "",
    val allReachableUrls: List<String> = emptyList(),
    val labeledReachableUrls: List<Pair<String, String>> = emptyList(),
    val activeClientCount: Int = 0,
    val wsClientCount: Int = 0,
    val selfTestPassed: Boolean = false,
    val lanSelfTestPassed: Boolean = false,
    val requestsServed: Int = 0,
    val sharedFilesCount: Int = 0,
    val bindError: String = "",
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
 * Pure-Kotlin/Java Embedded HTTP/1.1 + HTTPS SSL + SSE Web Server for PulseMesh.
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
    private val onWebPacketSubmitted: (WebSubmittedPacket) -> Unit,
    private val canFetchOnlineTiles: () -> Boolean = { false }
) {
    companion object {
        private val PREFERRED_PORTS = intArrayOf(8080, 8081, 8082, 8083, 8084, 8085, 8888, 9090)
        private val PREFERRED_HTTPS_PORTS = intArrayOf(8443, 8444, 8445, 8446, 9443)
        private const val MAX_POST_BODY_BYTES = 512 * 1024 // 512 KB cap for JSON API
        private const val MAX_FILE_UPLOAD_BYTES = 5 * 1024 * 1024 // 5 MB cap for offline file transfer
        private const val CLIENT_STALE_MS = 90_000L
        private const val WS_MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private val TILE_PATH_REGEX = Regex("^/api/tiles/(\\d{1,2})/(\\d+)/(\\d+)\\.png$")
        private val EXCLUDED_IFACE_PREFIXES = listOf(
            "rmnet", "r_rmnet", "ccmni", "tun", "tap", "ppp", "dummy", "lo", "epdg", "iwlan", "v4-rmnet", "clat"
        )

        /** RFC 6455 Sec-WebSocket-Accept derivation (API 24+ and JVM safe). */
        fun computeWebSocketAcceptKey(secWebSocketKey: String): String {
            val sha1 = java.security.MessageDigest.getInstance("SHA-1")
                .digest((secWebSocketKey.trim() + WS_MAGIC_GUID).toByteArray(Charsets.US_ASCII))
            return encodeBase64Standard(sha1)
        }

        private fun encodeBase64Standard(data: ByteArray): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val out = StringBuilder(((data.size + 2) / 3) * 4)
            var i = 0
            while (i < data.size) {
                val b0 = data[i].toInt() and 0xFF
                val has1 = i + 1 < data.size
                val has2 = i + 2 < data.size
                val b1 = if (has1) data[i + 1].toInt() and 0xFF else 0
                val b2 = if (has2) data[i + 2].toInt() and 0xFF else 0
                out.append(alphabet[b0 ushr 2])
                out.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                out.append(if (has1) alphabet[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else '=')
                out.append(if (has2) alphabet[b2 and 0x3F] else '=')
                i += 3
            }
            return out.toString()
        }
    }

    private val _status = MutableStateFlow(WebPortalStatus())
    val status: StateFlow<WebPortalStatus> = _status.asStateFlow()

    private val running = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private val selfTestPassed = AtomicBoolean(false)
    private val httpsSelfTestPassed = AtomicBoolean(false)
    private val lanSelfTestPassed = AtomicBoolean(false)
    private val requestsServed = AtomicInteger(0)
    @Volatile
    private var lastBindError: String = ""
    @Volatile
    private var lastSslError: String = ""
    @Volatile
    private var sslSanSignature: String = ""
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var sslServerSocket: ServerSocket? = null
    @Volatile
    private var boundPort: Int = 8080
    @Volatile
    private var boundHttpsPort: Int = 8443
    @Volatile
    private var preferredHostIp: String = ""

    private var startJob: Job? = null
    @Volatile
    private var acceptThread: Thread? = null
    @Volatile
    private var sslAcceptThread: Thread? = null
    private var maintenanceJob: Job? = null
    private val sharedFilesIndex = CopyOnWriteArrayList<com.example.model.SharedMeshFile>()
    private val sharedFilesDir: File by lazy {
        File(context.filesDir, "pulsemesh_shared_files").apply { mkdirs() }
    }
    private val workerExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "PulseMesh-HTTP-Worker").apply {
            isDaemon = true
        }
    }

    private data class CallSignalRecord(
        val seq: Long,
        val type: String,
        val srcNodeId: String,
        val srcCallsign: String,
        val dstNodeId: String,
        val payload: String,
        val timestamp: Long
    )

    private val callSignalSeq = AtomicLong(0L)
    private val recentCallSignals = CopyOnWriteArrayList<CallSignalRecord>()

    private val activeClients = ConcurrentHashMap<String, Long>()
    private val sseStreams = CopyOnWriteArrayList<SseConnection>()
    private val wsConnections = CopyOnWriteArrayList<WsConnection>()

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

    private val cachedHtmlGzipBytes: ByteArray by lazy {
        runCatching {
            val baos = ByteArrayOutputStream()
            GZIPOutputStream(baos).use { gz -> gz.write(cachedHtmlBytes) }
            baos.toByteArray()
        }.getOrElse { ByteArray(0) }
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

    private class WsConnection(
        val socket: Socket,
        val output: OutputStream,
        @Volatile var clientNodeId: String
    )

    fun start(hostIpHint: String = "") {
        if (hostIpHint.isNotBlank()) {
            preferredHostIp = hostIpHint.trim()
        }
        if (running.get() && serverSocket?.isClosed == false) {
            scope.launch(Dispatchers.IO) {
                refreshPortalUrl()
                runSelfTestsAsync()
            }
            return
        }
        if (running.get() && serverSocket?.isClosed != false) {
            running.set(false)
        }
        if (!starting.compareAndSet(false, true)) return

        _status.value = _status.value.copy(
            statusMessage = "Starting Local Hotspot Web Server..."
        )

        startJob?.cancel()
        startJob = scope.launch(Dispatchers.IO) {
            try {
                lastBindError = ""
                val ss = openServerSocket()
                if (ss == null) {
                    _status.value = WebPortalStatus(
                        isRunning = false,
                        bindError = lastBindError.ifBlank { "All ports 8080-9090 unavailable" },
                        statusMessage = "Web Portal failed to bind: ${lastBindError.ifBlank { "ports 8080-9090 busy" }}"
                    )
                    return@launch
                }

                serverSocket = ss
                boundPort = ss.localPort
                running.set(true)
                selfTestPassed.set(false)
                lanSelfTestPassed.set(false)
                requestsServed.set(0)
                acquireWifiLock()
                refreshPortalUrl()
                registerNsdService(boundPort)

                // Dedicated daemon Thread for accept() so coroutine/IO saturation never stalls incoming browser sockets
                runCatching { acceptThread?.interrupt() }
                acceptThread = Thread({
                    while (running.get() && !ss.isClosed) {
                        try {
                            val client = ss.accept()
                            runCatching {
                                client.tcpNoDelay = true
                                client.sendBufferSize = 65536
                                client.setSoLinger(true, 4)
                            }
                            workerExecutor.execute { handleClientSocket(client) }
                        } catch (_: Exception) {
                            if (!running.get() || ss.isClosed) break
                        }
                    }
                }, "PulseMesh-HTTP-Accept").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY + 1
                    start()
                }

                // Dual-stack SSL/HTTPS listener (:8443) with pure-Kotlin self-signed ECDSA P-256 cert
                // Enables WebCrypto ECDH & microphone getUserMedia on iPhone Safari / Chrome over local Hotspot!
                val initialIps = resolveRankedLabeledIpv4s().map { it.first }
                ensureSslListenerBound(initialIps, refreshAfter = true)

                // Loopback + real LAN/Hotspot IP HTTP 200 self-test right after bind
                scope.launch(Dispatchers.IO) {
                    delay(180L)
                    if (!running.get()) return@launch
                    runSelfTestsAsync()
                }

                // Rapid IP probe bursts while Android brings up SoftAP / Wi-Fi Direct IPv4 addresses, then steady 3s poll
                maintenanceJob?.cancel()
                maintenanceJob = scope.launch(Dispatchers.IO) {
                    val quickProbes = longArrayOf(400L, 1000L, 1800L, 3000L)
                    for (ms in quickProbes) {
                        delay(ms)
                        if (!isActive || !running.get()) return@launch
                        refreshPortalUrl()
                        if (!lanSelfTestPassed.get()) {
                            runSelfTestsAsync()
                        }
                    }
                    var tick = 0
                    while (isActive && running.get()) {
                        delay(3_000L)
                        tick++
                        refreshPortalUrl()
                        if (!selfTestPassed.get() || (!lanSelfTestPassed.get() && tick % 3 == 0)) {
                            runSelfTestsAsync()
                        }
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

    private fun runSelfTestsAsync() {
        if (!running.get()) return
        val port = boundPort
        val loopOk = runHttpSelfTest("127.0.0.1", port)
        selfTestPassed.set(loopOk)
        val httpsOk = if (sslServerSocket?.isClosed == false) {
            runHttpsSelfTest("127.0.0.1", boundHttpsPort)
        } else false
        httpsSelfTestPassed.set(httpsOk)
        val bestIp = _status.value.hostIp
        val lanOk = if (bestIp.isNotBlank() && bestIp != "127.0.0.1") {
            runHttpSelfTest(bestIp, port)
        } else false
        lanSelfTestPassed.set(lanOk)
        refreshPortalUrl()
    }

    fun updateHostIpHint(hostIpHint: String) {
        if (hostIpHint.isNotBlank()) {
            preferredHostIp = hostIpHint.trim()
            if (running.get()) {
                scope.launch(Dispatchers.IO) {
                    refreshPortalUrl()
                    runSelfTestsAsync()
                }
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
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { sslServerSocket?.close() }
        sslServerSocket = null
        runCatching { acceptThread?.interrupt() }
        acceptThread = null
        runCatching { sslAcceptThread?.interrupt() }
        sslAcceptThread = null
        maintenanceJob?.cancel()
        maintenanceJob = null
        unregisterNsdService()
        releaseWifiLock()
        for (sse in sseStreams) {
            runCatching { sse.socket.close() }
        }
        sseStreams.clear()
        for (ws in wsConnections) {
            runCatching { ws.socket.close() }
        }
        wsConnections.clear()
        activeClients.clear()
        recentCallSignals.clear()
        sslSanSignature = ""
        httpsSelfTestPassed.set(false)
        lastSslError = ""
        _status.value = WebPortalStatus(
            isRunning = false,
            port = boundPort,
            statusMessage = "Wi-Fi Direct Web Portal Stopped"
        )
    }

    fun isRunning(): Boolean = running.get() && serverSocket?.isClosed == false

    fun notifyStateChanged() {
        if (!running.get()) return
        if (sseStreams.isNotEmpty()) {
            val frame = "data: {\"event\":\"refresh\"}\n\n".toByteArray(Charsets.UTF_8)
            broadcastSseBytes(frame)
        }
        if (wsConnections.isNotEmpty()) {
            broadcastWsText("""{"event":"refresh"}""")
        }
    }

    fun notifyVoiceFrame(srcNodeId: String, srcCallsign: String, payloadBase64: String, isCallAudio: Boolean = false) {
        if (!running.get() || (sseStreams.isEmpty() && wsConnections.isEmpty()) || payloadBase64.isBlank()) return
        val json = JSONObject().apply {
            put("event", "voice")
            put("srcNodeId", srcNodeId)
            put("srcCallsign", srcCallsign)
            put("payload", payloadBase64)
            put("isCallAudio", isCallAudio)
        }.toString()
        if (sseStreams.isNotEmpty()) {
            broadcastSseBytes("data: $json\n\n".toByteArray(Charsets.UTF_8))
        }
        if (wsConnections.isNotEmpty()) {
            broadcastWsText(json)
        }
    }

    fun notifyCallSignal(type: String, srcNodeId: String, srcCallsign: String, dstNodeId: String, payload: String = "") {
        val seq = callSignalSeq.incrementAndGet()
        val nowMs = System.currentTimeMillis()
        recentCallSignals.add(
            CallSignalRecord(
                seq = seq,
                type = type,
                srcNodeId = srcNodeId,
                srcCallsign = srcCallsign,
                dstNodeId = dstNodeId,
                payload = payload,
                timestamp = nowMs
            )
        )
        while (recentCallSignals.size > 20) {
            runCatching { recentCallSignals.removeAt(0) }
        }
        if (!running.get()) return
        if (sseStreams.isNotEmpty() || wsConnections.isNotEmpty()) {
            val json = JSONObject().apply {
                put("event", "call")
                put("seq", seq)
                put("type", type)
                put("srcNodeId", srcNodeId)
                put("srcCallsign", srcCallsign)
                put("dstNodeId", dstNodeId)
                put("payload", payload)
                put("timestamp", nowMs)
            }.toString()
            if (sseStreams.isNotEmpty()) {
                broadcastSseBytes("data: $json\n\n".toByteArray(Charsets.UTF_8))
            }
            if (wsConnections.isNotEmpty()) {
                broadcastWsText(json)
            }
        }
        notifyStateChanged()
    }

    fun getRecentCallSignalsJson(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        val cutoff = System.currentTimeMillis() - 35_000L
        for (sig in recentCallSignals) {
            if (sig.timestamp < cutoff) continue
            arr.put(
                JSONObject().apply {
                    put("seq", sig.seq)
                    put("type", sig.type)
                    put("srcNodeId", sig.srcNodeId)
                    put("srcCallsign", sig.srcCallsign)
                    put("dstNodeId", sig.dstNodeId)
                    put("payload", sig.payload)
                    put("timestamp", sig.timestamp)
                }
            )
        }
        return arr
    }

    @Synchronized
    private fun ensureSslListenerBound(extraIps: List<String>, refreshAfter: Boolean = false) {
        val sortedSig = extraIps.distinct().sorted().joinToString(",")
        if (sslServerSocket?.isClosed == false && sslSanSignature == sortedSig) {
            return
        }
        val (sslFactory, factoryErr) = LocalSelfSignedTlsFactory.createSslServerSocketFactory(context, extraIps)
        if (sslFactory == null) {
            lastSslError = factoryErr.ifBlank { "SSL factory initialization failed" }
            if (refreshAfter) refreshPortalUrl()
            return
        }
        if (sslServerSocket?.isClosed == false && sslSanSignature.isNotBlank() && sslSanSignature != sortedSig) {
            // Re-bind only when new Hotspot/LAN IPs appear so SAN matches the live AP gateway
            runCatching { sslServerSocket?.close() }
            sslServerSocket = null
            runCatching { sslAcceptThread?.interrupt() }
            sslAcceptThread = null
        }
        val ipv4Any = runCatching { InetAddress.getByName("0.0.0.0") }.getOrNull()
        var boundSock: ServerSocket? = null
        for (port in PREFERRED_HTTPS_PORTS) {
            val sock = try {
                val ss = sslFactory.createServerSocket()
                ss.reuseAddress = true
                if (ipv4Any != null) {
                    ss.bind(InetSocketAddress(ipv4Any, port), 64)
                } else {
                    ss.bind(InetSocketAddress(port), 64)
                }
                if (ss is javax.net.ssl.SSLServerSocket) {
                    val supported = ss.supportedProtocols.toSet()
                    val preferred = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }.toTypedArray()
                    if (preferred.isNotEmpty()) ss.enabledProtocols = preferred
                }
                ss
            } catch (e: Exception) {
                lastSslError = "${e.javaClass.simpleName}: ${e.message}"
                null
            }
            if (sock != null) {
                boundSock = sock
                break
            }
        }
        if (boundSock == null) {
            if (lastSslError.isBlank()) lastSslError = "All HTTPS ports 8443-9443 busy"
            if (refreshAfter) refreshPortalUrl()
            return
        }
        lastSslError = ""
        sslSanSignature = sortedSig
        sslServerSocket = boundSock
        boundHttpsPort = boundSock.localPort
        sslAcceptThread = Thread({
            while (running.get() && !boundSock.isClosed) {
                try {
                    val tlsClient = boundSock.accept()
                    runCatching {
                        tlsClient.tcpNoDelay = true
                        tlsClient.sendBufferSize = 65536
                        tlsClient.setSoLinger(true, 4)
                    }
                    workerExecutor.execute { handleClientSocket(tlsClient) }
                } catch (_: Exception) {
                    if (!running.get() || boundSock.isClosed) break
                }
            }
        }, "PulseMesh-HTTPS-Accept").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        if (refreshAfter) refreshPortalUrl()
    }

    fun listSharedFiles(): List<com.example.model.SharedMeshFile> = sharedFilesIndex.toList()

    fun addLocalSharedFile(fileName: String, bytes: ByteArray, sender: String): com.example.model.SharedMeshFile? {
        if (bytes.isEmpty() || bytes.size > MAX_FILE_UPLOAD_BYTES) return null
        val safeName = fileName.trim().replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(80).ifBlank { "shared_file.bin" }
        val fileId = "f_${System.currentTimeMillis()}_${(bytes.hashCode() and 0xFFFF).toString(16)}"
        val sha256 = runCatching {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
        val dest = File(sharedFilesDir, fileId)
        return runCatching {
            dest.writeBytes(bytes)
            val meta = com.example.model.SharedMeshFile(
                id = fileId,
                name = safeName,
                sizeBytes = bytes.size.toLong(),
                sha256 = sha256,
                sender = sender.ifBlank { "Host" },
                timestamp = System.currentTimeMillis()
            )
            sharedFilesIndex.add(0, meta)
            while (sharedFilesIndex.size > 30) {
                val removed = sharedFilesIndex.removeAt(sharedFilesIndex.lastIndex)
                runCatching { File(sharedFilesDir, removed.id).delete() }
            }
            refreshPortalUrl()
            notifyStateChanged()
            meta
        }.getOrNull()
    }

    fun deleteSharedFile(fileId: String): Boolean {
        val removed = sharedFilesIndex.removeIf { it.id == fileId }
        runCatching { File(sharedFilesDir, fileId).delete() }
        if (removed) {
            refreshPortalUrl()
            notifyStateChanged()
        }
        return removed
    }

    private fun openServerSocket(): ServerSocket? {
        val ipv4Any = runCatching { InetAddress.getByName("0.0.0.0") }.getOrNull()
        for (port in PREFERRED_PORTS) {
            val sock = try {
                ServerSocket().apply {
                    reuseAddress = true
                    if (ipv4Any != null) {
                        bind(InetSocketAddress(ipv4Any, port), 64)
                    } else {
                        bind(InetSocketAddress(port), 64)
                    }
                }
            } catch (e: Exception) {
                lastBindError = e.message ?: e.javaClass.simpleName
                try {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(port), 64)
                    }
                } catch (e2: Exception) {
                    lastBindError = e2.message ?: e2.javaClass.simpleName
                    null
                }
            }
            if (sock != null) return sock
        }
        return null
    }

    private fun runHttpsSelfTest(host: String, port: Int): Boolean {
        return runCatching {
            val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            })
            val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, trustAll, java.security.SecureRandom())
            }
            val url = java.net.URL("https://$host:$port/api/info")
            val conn = (url.openConnection() as javax.net.ssl.HttpsURLConnection).apply {
                sslSocketFactory = sslCtx.socketFactory
                hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                connectTimeout = 1800
                readTimeout = 1800
                requestMethod = "GET"
            }
            try {
                val code = conn.responseCode
                val body = if (code in 200..299) conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else ""
                code == 200 && body.contains("pulsemeshHost")
            } finally {
                runCatching { conn.disconnect() }
            }
        }.getOrDefault(false)
    }

    private fun runHttpSelfTest(host: String, port: Int): Boolean {
        return runCatching {
            val url = java.net.URL("http://$host:$port/api/info")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 1500
                readTimeout = 1500
                requestMethod = "GET"
            }
            try {
                val code = conn.responseCode
                val body = if (code in 200..299) conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else ""
                code == 200 && body.contains("pulsemeshHost")
            } finally {
                runCatching { conn.disconnect() }
            }
        }.getOrDefault(false)
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
        val rankedLabeled = resolveRankedLabeledIpv4s()
        val liveIps = rankedLabeled.map { it.first }
        val newSanSig = liveIps.distinct().sorted().joinToString(",")
        if (liveIps.isNotEmpty() && (sslServerSocket?.isClosed != false || sslSanSignature != newSanSig)) {
            ensureSslListenerBound(liveIps, refreshAfter = false)
        }
        val bestPair = rankedLabeled.firstOrNull()
        val bestIp = bestPair?.first
            ?: preferredHostIp.ifBlank { AutoHotspotNegotiator.LOCAL_HOTSPOT_IP }
        val primaryUrl = "http://$bestIp:$boundPort"
        val sslActive = sslServerSocket?.isClosed == false
        val httpsUrl = if (sslActive) "https://$bestIp:$boundHttpsPort" else ""
        val labeledUrls = if (rankedLabeled.isNotEmpty()) {
            rankedLabeled.map { (ip, ifaceName) -> "http://$ip:$boundPort" to ifaceName }
        } else {
            listOf(primaryUrl to "fallback")
        }
        val allUrls = labeledUrls.map { it.first }
        val cur = _status.value
        val ok = selfTestPassed.get()
        val httpsOk = httpsSelfTestPassed.get()
        val lanOk = lanSelfTestPassed.get()
        val hits = requestsServed.get()
        val filesCount = sharedFilesIndex.size
        val wsCount = wsConnections.size
        if (cur.portalUrl != primaryUrl ||
            cur.httpsPortalUrl != httpsUrl ||
            cur.sslEnabled != sslActive ||
            cur.httpsSelfTestPassed != httpsOk ||
            cur.sslError != lastSslError ||
            cur.allReachableUrls != allUrls ||
            cur.labeledReachableUrls != labeledUrls ||
            cur.activeClientCount != activeClients.size ||
            cur.wsClientCount != wsCount ||
            cur.selfTestPassed != ok ||
            cur.lanSelfTestPassed != lanOk ||
            cur.requestsServed != hits ||
            cur.sharedFilesCount != filesCount ||
            !cur.isRunning
        ) {
            _status.value = WebPortalStatus(
                isRunning = true,
                port = boundPort,
                httpsPort = boundHttpsPort,
                sslEnabled = sslActive,
                httpsSelfTestPassed = httpsOk,
                sslError = lastSslError,
                hostIp = bestIp,
                portalUrl = primaryUrl,
                httpsPortalUrl = httpsUrl,
                allReachableUrls = allUrls,
                labeledReachableUrls = labeledUrls,
                activeClientCount = activeClients.size,
                wsClientCount = wsCount,
                selfTestPassed = ok,
                lanSelfTestPassed = lanOk,
                requestsServed = hits,
                sharedFilesCount = filesCount,
                bindError = "",
                statusMessage = when {
                    lanOk && sslActive && httpsOk -> "HTTP + SSL HTTPS Verified • LIVE at $httpsUrl"
                    lanOk && sslActive -> "LAN HTTP + SSL Ready • LIVE at $httpsUrl"
                    lanOk -> "LAN & Local HTTP 200 Verified • LIVE at $primaryUrl"
                    ok && sslActive -> "HTTP + SSL HTTPS Ready • LIVE at $httpsUrl"
                    ok -> "Local HTTP 200 Verified • LIVE at $primaryUrl"
                    else -> "Web Portal LIVE at $primaryUrl"
                }
            )
        }
    }

    private fun resolveRankedLabeledIpv4s(): List<Pair<String, String>> {
        val candidates = mutableListOf<Triple<Int, String, String>>()
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
                        val (priority, label) = when {
                            name.startsWith("ap_br") || name.startsWith("ap") || name.startsWith("swlan") ||
                                name.startsWith("softap") || name.startsWith("sap") || name.startsWith("tether") ||
                                name.startsWith("br") -> 140 to "Hotspot ($name)"
                            name.startsWith("wlan") && name != "wlan0" -> 136 to "Hotspot ($name)"
                            name.startsWith("p2p") || ip == "192.168.49.1" -> 132 to "Wi-Fi Direct ($name)"
                            name.startsWith("wlan") && ip.endsWith(".1") -> 124 to "Wi-Fi AP ($name)"
                            ip.startsWith("192.168.43.") || ip.startsWith("192.168.137.") -> 122 to "Hotspot ($name)"
                            ip.endsWith(".1") -> 112 to "Gateway ($name)"
                            name.startsWith("wlan") -> 88 to "Wi-Fi ($name)"
                            name.startsWith("usb") || name.startsWith("rndis") || name.startsWith("ncm") || name.startsWith("eth") -> 75 to "LAN ($name)"
                            else -> 50 to name
                        }
                        if (priority > 0) {
                            candidates.add(Triple(priority, ip, label))
                        }
                    }
                }
            }
        }
        val seen = HashSet<String>()
        return candidates
            .sortedByDescending { it.first }
            .filter { seen.add(it.second) }
            .map { it.second to it.third }
    }

    private fun pruneStaleClients() {
        val cutoff = System.currentTimeMillis() - CLIENT_STALE_MS
        activeClients.entries.removeIf { it.value < cutoff }
    }

    private fun sendSseKeepAlive() {
        if (sseStreams.isNotEmpty()) {
            broadcastSseBytes(": keepalive\n\n".toByteArray(Charsets.UTF_8))
        }
        if (wsConnections.isNotEmpty()) {
            broadcastWsFrame(0x9, "pm".toByteArray(Charsets.UTF_8))
        }
    }

    private fun broadcastWsText(text: String) {
        broadcastWsFrame(0x1, text.toByteArray(Charsets.UTF_8))
    }

    private fun broadcastWsFrame(opcode: Int, payload: ByteArray) {
        val frame = buildWsFrameBytes(opcode, payload)
        val dead = mutableListOf<WsConnection>()
        for (conn in wsConnections) {
            try {
                synchronized(conn.output) {
                    conn.output.write(frame)
                    conn.output.flush()
                }
            } catch (_: Exception) {
                dead.add(conn)
            }
        }
        for (d in dead) {
            wsConnections.remove(d)
            runCatching { d.socket.close() }
        }
        if (dead.isNotEmpty()) {
            _status.value = _status.value.copy(wsClientCount = wsConnections.size)
        }
    }

    private fun sendWsFrame(conn: WsConnection, opcode: Int, payload: ByteArray) {
        val frame = buildWsFrameBytes(opcode, payload)
        synchronized(conn.output) {
            conn.output.write(frame)
            conn.output.flush()
        }
    }

    private fun buildWsFrameBytes(opcode: Int, payload: ByteArray): ByteArray {
        val len = payload.size
        val headerSize = when {
            len <= 125 -> 2
            len <= 65535 -> 4
            else -> 10
        }
        val frame = ByteArray(headerSize + len)
        frame[0] = (0x80 or (opcode and 0x0F)).toByte() // FIN=1, RSV=0, opcode
        when {
            len <= 125 -> {
                frame[1] = len.toByte()
            }
            len <= 65535 -> {
                frame[1] = 126.toByte()
                frame[2] = ((len ushr 8) and 0xFF).toByte()
                frame[3] = (len and 0xFF).toByte()
            }
            else -> {
                frame[1] = 127.toByte()
                val l64 = len.toLong()
                for (i in 0..7) {
                    frame[2 + i] = ((l64 ushr ((7 - i) * 8)) and 0xFFL).toByte()
                }
            }
        }
        if (len > 0) {
            System.arraycopy(payload, 0, frame, headerSize, len)
        }
        return frame
    }

    private fun handleRegisterJson(obj: JSONObject): String {
        val nodeId = obj.optString("nodeId").trim()
        val callsign = obj.optString("callsign").trim().ifBlank { "WebNode" }
        if (nodeId.isNotBlank()) {
            activeClients[nodeId] = System.currentTimeMillis()
            _status.value = _status.value.copy(activeClientCount = activeClients.size)
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
        return nodeId
    }

    private fun handleSendJson(obj: JSONObject): WebSubmittedPacket {
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
        return submitted
    }

    private fun serveWebSocketLoop(conn: WsConnection, input: BufferedInputStream) {
        val fragBuffer = ByteArrayOutputStream()
        var fragOpcode = 0
        try {
            while (running.get() && !conn.socket.isClosed) {
                val b0 = input.read()
                if (b0 == -1) break
                val b1 = input.read()
                if (b1 == -1) break

                val fin = (b0 and 0x80) != 0
                val opcode = b0 and 0x0F
                val masked = (b1 and 0x80) != 0
                var payloadLen = (b1 and 0x7F).toLong()
                if (payloadLen == 126L) {
                    val hi = input.read()
                    val lo = input.read()
                    if (hi == -1 || lo == -1) break
                    payloadLen = ((hi shl 8) or lo).toLong()
                } else if (payloadLen == 127L) {
                    var l64 = 0L
                    var eof = false
                    for (i in 0..7) {
                        val b = input.read()
                        if (b == -1) {
                            eof = true
                            break
                        }
                        l64 = (l64 shl 8) or b.toLong()
                    }
                    if (eof) break
                    payloadLen = l64
                }
                if (payloadLen < 0 || payloadLen > MAX_POST_BODY_BYTES) {
                    runCatching { sendWsFrame(conn, 0x8, byteArrayOf(0x03, 0xF1.toByte())) } // 1009 Too Big
                    break
                }
                val maskKey = if (masked) {
                    val m = readExactBytes(input, 4)
                    if (m.size < 4) break
                    m
                } else null

                val payload = if (payloadLen > 0L) {
                    val raw = readExactBytes(input, payloadLen.toInt())
                    if (raw.size < payloadLen.toInt()) break
                    if (maskKey != null) {
                        for (i in raw.indices) {
                            raw[i] = (raw[i].toInt() xor maskKey[i and 3].toInt()).toByte()
                        }
                    }
                    raw
                } else {
                    ByteArray(0)
                }

                when (opcode) {
                    0x8 -> { // Close
                        runCatching { sendWsFrame(conn, 0x8, ByteArray(0)) }
                        break
                    }
                    0x9 -> { // Ping -> Pong
                        if (conn.clientNodeId.isNotBlank()) {
                            activeClients[conn.clientNodeId] = System.currentTimeMillis()
                        }
                        runCatching { sendWsFrame(conn, 0xA, payload) }
                    }
                    0xA -> { // Pong
                        if (conn.clientNodeId.isNotBlank()) {
                            activeClients[conn.clientNodeId] = System.currentTimeMillis()
                        }
                    }
                    0x1, 0x2, 0x0 -> {
                        if (opcode != 0x0) {
                            fragBuffer.reset()
                            fragOpcode = opcode
                        }
                        if (payload.isNotEmpty()) {
                            if (fragBuffer.size() + payload.size <= MAX_POST_BODY_BYTES) {
                                fragBuffer.write(payload)
                            }
                        }
                        if (fin && fragOpcode == 0x1) {
                            val text = fragBuffer.toString("UTF-8")
                            fragBuffer.reset()
                            handleWebSocketTextMessage(conn, text)
                        } else if (fin) {
                            fragBuffer.reset()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Client closed socket or transport reset
        } finally {
            wsConnections.remove(conn)
            _status.value = _status.value.copy(wsClientCount = wsConnections.size)
            runCatching { conn.socket.close() }
        }
    }

    private fun handleWebSocketTextMessage(conn: WsConnection, text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        val action = obj.optString("action").ifBlank { obj.optString("cmd") }.lowercase()
        when (action) {
            "ping" -> {
                val cid = obj.optString("clientNodeId", conn.clientNodeId).trim()
                if (cid.isNotBlank()) {
                    conn.clientNodeId = cid
                    activeClients[cid] = System.currentTimeMillis()
                }
                val pong = JSONObject().put("event", "pong").put("ts", System.currentTimeMillis()).toString()
                runCatching { sendWsFrame(conn, 0x1, pong.toByteArray(Charsets.UTF_8)) }
            }
            "register" -> {
                val payloadObj = obj.optJSONObject("data") ?: obj
                val registeredId = handleRegisterJson(payloadObj)
                if (registeredId.isNotBlank()) {
                    conn.clientNodeId = registeredId
                }
                val ack = JSONObject().put("event", "ack").put("action", "register").put("ok", true).toString()
                runCatching { sendWsFrame(conn, 0x1, ack.toByteArray(Charsets.UTF_8)) }
            }
            "send" -> {
                val payloadObj = obj.optJSONObject("data") ?: obj
                val submitted = handleSendJson(payloadObj)
                if (submitted.clientNodeId.isNotBlank()) {
                    conn.clientNodeId = submitted.clientNodeId
                }
                val ack = JSONObject()
                    .put("event", "ack")
                    .put("action", "send")
                    .put("packetId", submitted.packetId)
                    .put("ok", true)
                    .toString()
                runCatching { sendWsFrame(conn, 0x1, ack.toByteArray(Charsets.UTF_8)) }
            }
            "state" -> {
                val cid = obj.optString("clientNodeId", conn.clientNodeId).trim()
                if (cid.isNotBlank()) {
                    conn.clientNodeId = cid
                    activeClients[cid] = System.currentTimeMillis()
                }
                val snap = buildStateJson(cid)
                val envelope = JSONObject().put("event", "state").put("data", snap).toString()
                runCatching { sendWsFrame(conn, 0x1, envelope.toByteArray(Charsets.UTF_8)) }
            }
        }
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
            socket.soTimeout = 10_000
            if (socket is javax.net.ssl.SSLSocket) {
                try {
                    socket.startHandshake()
                } catch (_: Exception) {
                    return
                }
            }
            val input = BufferedInputStream(socket.getInputStream(), 16384)
            val output = socket.getOutputStream()
            val isLoopback = socket.inetAddress?.isLoopbackAddress == true

            // Clean HTTP/1.1 persistent keep-alive loop (eliminates Chrome/Safari TCP RST on multi-asset page loads)
            var requestsOnSocket = 0
            while (running.get() && requestsOnSocket < 24) {
                val requestLine = readLineCrLf(input) ?: break
                if (requestLine.isBlank()) continue
                requestsOnSocket++

                val parts = requestLine.trim().split(' ')
                if (parts.size < 2) break
                val method = parts[0].uppercase()
                val fullTarget = parts[1]
                val httpVersion = parts.getOrNull(2)?.uppercase() ?: "HTTP/1.1"

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

                val connHeader = headers["connection"]?.lowercase().orEmpty()
                val keepAlive = (httpVersion == "HTTP/1.1" && !connHeader.contains("close")) ||
                    (httpVersion == "HTTP/1.0" && connHeader.contains("keep-alive"))
                val acceptsGzip = headers["accept-encoding"]?.contains("gzip", ignoreCase = true) == true

                if (method == "OPTIONS") {
                    writeResponse(output, 204, "No Content", "text/plain", ByteArray(0), keepAlive = keepAlive)
                    if (!keepAlive) break else continue
                }

                val qIdx = fullTarget.indexOf('?')
                val path = if (qIdx >= 0) fullTarget.substring(0, qIdx) else fullTarget
                val query = if (qIdx >= 0) parseQuery(fullTarget.substring(qIdx + 1)) else emptyMap()

                val bodyBytes = if (method == "POST") {
                    val cap = if (path == "/api/files/upload") MAX_FILE_UPLOAD_BYTES else MAX_POST_BODY_BYTES
                    val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, cap) ?: 0
                    if (contentLength > 0) readExactBytes(input, contentLength) else ByteArray(0)
                } else {
                    ByteArray(0)
                }

                val isHead = method == "HEAD"
                if (path != "/api/events" && !isLoopback) {
                    val count = requestsServed.incrementAndGet()
                    _status.value = _status.value.copy(requestsServed = count)
                }

                when {
                    (method == "GET" || isHead) && (path == "/" || path == "/index.html" || path == "/index.htm" || path == "/web" || path == "/web/" || path == "/app" || path == "/app/" || path == "/pulsemesh") -> {
                        val useGzip = acceptsGzip && cachedHtmlGzipBytes.isNotEmpty()
                        writeResponse(
                            output = output,
                            code = 200,
                            reason = "OK",
                            contentType = "text/html; charset=utf-8",
                            body = if (useGzip) cachedHtmlGzipBytes else cachedHtmlBytes,
                            headOnly = isHead,
                            keepAlive = keepAlive,
                            contentEncoding = if (useGzip) "gzip" else null
                        )
                    }

                    (method == "GET" || isHead) && (path == "/manifest.json" || path == "/web/manifest.json") -> {
                        writeResponse(output, 200, "OK", "application/manifest+json; charset=utf-8", cachedManifestBytes, headOnly = isHead, keepAlive = keepAlive)
                    }

                    (method == "GET" || isHead) && (path == "/sw.js" || path == "/web/sw.js") -> {
                        writeResponse(output, 200, "OK", "application/javascript; charset=utf-8", cachedSwBytes, headOnly = isHead, keepAlive = keepAlive)
                    }

                    (method == "GET" || isHead) && (path == "/api/icon.png" || path == "/assets/pulsemesh-logo.png" || path == "/favicon.ico" || path == "/apple-touch-icon.png") -> {
                        if (cachedIconBytes.isNotEmpty()) {
                            writeResponse(output, 200, "OK", "image/png", cachedIconBytes, cacheControl = "public, max-age=86400", headOnly = isHead, keepAlive = keepAlive)
                        } else {
                            writeResponse(output, 404, "Not Found", "text/plain", ByteArray(0), headOnly = isHead, keepAlive = keepAlive)
                        }
                    }

                    (method == "GET" || isHead) && path == "/api/info" -> {
                        val info = buildInfoJson(_status.value.portalUrl, activeClients.size).apply {
                            put("httpsPortalUrl", _status.value.httpsPortalUrl)
                            put("httpsPort", _status.value.httpsPort)
                            put("sslEnabled", _status.value.sslEnabled)
                            put("caCertUrl", "/api/ca.crt")
                            put("iosProfileUrl", "/api/ca.mobileconfig")
                            put("wsSupported", true)
                            put("wsPath", "/ws")
                            put("wsClients", wsConnections.size)
                        }
                        writeJson(output, 200, info.toString(), headOnly = isHead, keepAlive = keepAlive)
                    }

                    (method == "GET" || isHead) && path == "/api/cert.der" -> {
                        val der = LocalSelfSignedTlsFactory.getCurrentCertificateDer(context)
                        if (der != null && der.isNotEmpty()) {
                            writeResponse(output, 200, "OK", "application/x-x509-ca-cert", der, headOnly = isHead, keepAlive = keepAlive)
                        } else {
                            writeResponse(output, 404, "Not Found", "text/plain", ByteArray(0), headOnly = isHead, keepAlive = keepAlive)
                        }
                    }

                    (method == "GET" || isHead) && (path == "/api/ca.crt" || path == "/api/ca.cer") -> {
                        val der = LocalSelfSignedTlsFactory.getRootCaCertificateDer(context)
                        if (der != null && der.isNotEmpty()) {
                            writeResponse(
                                output = output,
                                code = 200,
                                reason = "OK",
                                contentType = "application/x-x509-ca-cert",
                                body = der,
                                headOnly = isHead,
                                keepAlive = keepAlive,
                                contentDisposition = "attachment; filename=\"PulseMesh-Root-CA.crt\""
                            )
                        } else {
                            writeResponse(output, 404, "Not Found", "text/plain", ByteArray(0), headOnly = isHead, keepAlive = keepAlive)
                        }
                    }

                    (method == "GET" || isHead) && path == "/api/ca.pem" -> {
                        val pem = LocalSelfSignedTlsFactory.getRootCaCertificatePem(context)
                        if (!pem.isNullOrBlank()) {
                            writeResponse(
                                output = output,
                                code = 200,
                                reason = "OK",
                                contentType = "application/x-pem-file",
                                body = pem.toByteArray(Charsets.UTF_8),
                                headOnly = isHead,
                                keepAlive = keepAlive,
                                contentDisposition = "attachment; filename=\"PulseMesh-Root-CA.pem\""
                            )
                        } else {
                            writeResponse(output, 404, "Not Found", "text/plain", ByteArray(0), headOnly = isHead, keepAlive = keepAlive)
                        }
                    }

                    (method == "GET" || isHead) && path == "/api/ca.mobileconfig" -> {
                        val profileBytes = LocalSelfSignedTlsFactory.buildIosMobileConfigXml(
                            context,
                            _status.value.hostIp.ifBlank { "192.168.43.1" }
                        )
                        if (profileBytes != null && profileBytes.isNotEmpty()) {
                            writeResponse(
                                output = output,
                                code = 200,
                                reason = "OK",
                                contentType = "application/x-apple-aspen-config",
                                body = profileBytes,
                                headOnly = isHead,
                                keepAlive = keepAlive,
                                contentDisposition = "attachment; filename=\"PulseMesh-Root-CA.mobileconfig\""
                            )
                        } else {
                            writeResponse(output, 404, "Not Found", "text/plain", ByteArray(0), headOnly = isHead, keepAlive = keepAlive)
                        }
                    }

                    method == "GET" && path == "/api/state" -> {
                        val clientId = query["clientNodeId"].orEmpty()
                        if (clientId.isNotBlank()) {
                            activeClients[clientId] = System.currentTimeMillis()
                        }
                        val snap = buildStateJson(clientId)
                        writeJson(output, 200, snap.toString(), keepAlive = keepAlive)
                    }

                    method == "GET" && (path == "/ws" || path == "/api/ws") -> {
                        val wsKey = headers["sec-websocket-key"].orEmpty().trim()
                        if (wsKey.isBlank()) {
                            writeResponse(output, 400, "Bad Request", "text/plain", "Missing Sec-WebSocket-Key".toByteArray(Charsets.UTF_8), keepAlive = false)
                            break
                        }
                        val acceptKey = computeWebSocketAcceptKey(wsKey)
                        val clientId = query["clientNodeId"].orEmpty().trim()
                        if (clientId.isNotBlank()) {
                            activeClients[clientId] = System.currentTimeMillis()
                        }
                        socket.soTimeout = 0
                        val upgradeResp = buildString {
                            append("HTTP/1.1 101 Switching Protocols\r\n")
                            append("Upgrade: websocket\r\n")
                            append("Connection: Upgrade\r\n")
                            append("Sec-WebSocket-Accept: $acceptKey\r\n")
                            append("Access-Control-Allow-Origin: *\r\n\r\n")
                        }
                        output.write(upgradeResp.toByteArray(Charsets.US_ASCII))
                        output.flush()
                        val wsConn = WsConnection(socket, output, clientId)
                        wsConnections.add(wsConn)
                        _status.value = _status.value.copy(
                            activeClientCount = activeClients.size,
                            wsClientCount = wsConnections.size
                        )
                        runCatching {
                            sendWsFrame(wsConn, 0x1, """{"event":"connected","ws":true}""".toByteArray(Charsets.UTF_8))
                        }
                        serveWebSocketLoop(wsConn, input)
                        break
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
                            append("Access-Control-Allow-Origin: *\r\n")
                            append("Access-Control-Allow-Private-Network: true\r\n\r\n")
                            append(": connected\n\n")
                        }
                        output.write(header.toByteArray(Charsets.UTF_8))
                        output.flush()
                        sseStreams.add(SseConnection(socket, output, clientId))
                        keepOpenForSse = true
                        break
                    }

                    method == "POST" && path == "/api/register" -> {
                        val obj = runCatching { JSONObject(String(bodyBytes, Charsets.UTF_8)) }.getOrNull()
                        if (obj == null) {
                            writeJson(output, 400, """{"ok":false,"error":"invalid_json"}""", keepAlive = keepAlive)
                            if (!keepAlive) break else continue
                        }
                        handleRegisterJson(obj)
                        writeJson(output, 200, """{"ok":true}""", keepAlive = keepAlive)
                    }

                    method == "POST" && path == "/api/send" -> {
                        val obj = runCatching { JSONObject(String(bodyBytes, Charsets.UTF_8)) }.getOrNull()
                        if (obj == null) {
                            writeJson(output, 400, """{"ok":false,"error":"invalid_json"}""", keepAlive = keepAlive)
                            if (!keepAlive) break else continue
                        }
                        handleSendJson(obj)
                        writeJson(output, 200, """{"ok":true}""", keepAlive = keepAlive)
                    }

                    method == "GET" && path == "/api/files" -> {
                        val arr = org.json.JSONArray()
                        for (f in sharedFilesIndex) {
                            arr.put(JSONObject().apply {
                                put("id", f.id)
                                put("name", f.name)
                                put("sizeBytes", f.sizeBytes)
                                put("sha256", f.sha256)
                                put("sender", f.sender)
                                put("timestamp", f.timestamp)
                            })
                        }
                        writeJson(output, 200, JSONObject().put("files", arr).toString(), keepAlive = keepAlive)
                    }

                    method == "POST" && path == "/api/files/upload" -> {
                        val contentTypeHdr = headers["content-type"].orEmpty().lowercase(java.util.Locale.US)
                        var fname = query["name"]?.ifBlank { "upload.bin" } ?: "upload.bin"
                        var sender = query["sender"]?.ifBlank { "WebNode" } ?: "WebNode"
                        var payloadBytes = bodyBytes
                        if (contentTypeHdr.contains("application/json") || (bodyBytes.isNotEmpty() && bodyBytes[0] == '{'.code.toByte())) {
                            runCatching {
                                val j = JSONObject(String(bodyBytes, Charsets.UTF_8))
                                val jName = j.optString("fileName").ifBlank { j.optString("name") }
                                if (jName.isNotBlank()) fname = jName
                                val jSender = j.optString("senderCallsign").ifBlank { j.optString("sender") }
                                if (jSender.isNotBlank()) sender = jSender
                                val b64 = j.optString("base64Data")
                                if (b64.isNotBlank()) {
                                    payloadBytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                }
                            }
                        }
                        val saved = addLocalSharedFile(fname, payloadBytes, sender)
                        if (saved != null) {
                            val resp = JSONObject().apply {
                                put("ok", true)
                                put("id", saved.id)
                                put("name", saved.name)
                                put("fileName", saved.name)
                                put("sizeBytes", saved.sizeBytes)
                                put("sha256", saved.sha256)
                            }
                            writeJson(output, 200, resp.toString(), keepAlive = keepAlive)
                        } else {
                            writeJson(output, 400, """{"ok":false,"error":"empty_or_too_large"}""", keepAlive = keepAlive)
                        }
                    }

                    method == "GET" && path == "/api/files/download" -> {
                        val fid = query["id"].orEmpty().replace(Regex("[^a-zA-Z0-9_-]"), "")
                        val meta = sharedFilesIndex.firstOrNull { it.id == fid }
                        val targetFile = if (fid.isNotBlank()) File(sharedFilesDir, fid) else null
                        if (meta != null && targetFile != null && targetFile.exists()) {
                            val bytes = runCatching { targetFile.readBytes() }.getOrNull() ?: ByteArray(0)
                            val dlHeader = buildString {
                                append("HTTP/1.1 200 OK\r\n")
                                append("Content-Type: application/octet-stream\r\n")
                                append("Content-Disposition: attachment; filename=\"${meta.name.replace("\"", "")}\"\r\n")
                                append("X-SHA256: ${meta.sha256}\r\n")
                                append("Content-Length: ${bytes.size}\r\n")
                                append("Access-Control-Allow-Origin: *\r\n")
                                append("Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n\r\n")
                            }
                            output.write(dlHeader.toByteArray(Charsets.UTF_8))
                            output.write(bytes)
                            output.flush()
                        } else {
                            writeResponse(output, 404, "Not Found", "text/plain", ByteArray(0), keepAlive = keepAlive)
                        }
                    }

                    method == "GET" && path.startsWith("/api/tiles/") -> {
                        val match = TILE_PATH_REGEX.matchEntire(path)
                        if (match == null) {
                            writeResponse(output, 404, "Not Found", "text/plain", ByteArray(0), keepAlive = keepAlive)
                        } else {
                            val z = match.groupValues[1].toIntOrNull() ?: -1
                            val x = match.groupValues[2].toIntOrNull() ?: -1
                            val y = match.groupValues[3].toIntOrNull() ?: -1
                            val tileFile = findCachedTileFile(z, x, y)
                            val cachedBytes = if (tileFile != null && tileFile.exists() && tileFile.length() > 0L) {
                                runCatching { tileFile.readBytes() }.getOrNull()
                            } else null
                            if (cachedBytes != null && cachedBytes.isNotEmpty()) {
                                writeResponse(output, 200, "OK", "image/png", cachedBytes, cacheControl = "public, max-age=86400", keepAlive = keepAlive)
                            } else if (canFetchOnlineTiles()) {
                                val fetchedBytes = NativeTileMapManager.fetchTileBytesOnline(context, z, x, y)
                                if (fetchedBytes != null && fetchedBytes.isNotEmpty()) {
                                    writeResponse(output, 200, "OK", "image/png", fetchedBytes, cacheControl = "public, max-age=86400", keepAlive = keepAlive)
                                } else {
                                    writeResponse(output, 404, "Not Found", "text/plain", ByteArray(0), keepAlive = keepAlive)
                                }
                            } else {
                                writeResponse(output, 404, "Not Found", "text/plain", ByteArray(0), keepAlive = keepAlive)
                            }
                        }
                    }

                    else -> {
                        writeResponse(output, 404, "Not Found", "text/plain", "Not Found".toByteArray(Charsets.UTF_8), keepAlive = keepAlive)
                    }
                }

                if (!keepAlive) break
            }
        } catch (_: Exception) {
            // Connection idle-timed-out or dropped cleanly by client
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

    private fun writeJson(
        output: OutputStream,
        code: Int,
        json: String,
        headOnly: Boolean = false,
        keepAlive: Boolean = false
    ) {
        val reason = if (code == 200) "OK" else "Bad Request"
        writeResponse(
            output = output,
            code = code,
            reason = reason,
            contentType = "application/json; charset=utf-8",
            body = json.toByteArray(Charsets.UTF_8),
            headOnly = headOnly,
            keepAlive = keepAlive
        )
    }

    private fun writeResponse(
        output: OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        cacheControl: String = "no-store",
        headOnly: Boolean = false,
        keepAlive: Boolean = false,
        contentEncoding: String? = null,
        contentDisposition: String? = null
    ) {
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            if (!contentDisposition.isNullOrBlank()) {
                append("Content-Disposition: $contentDisposition\r\n")
            }
            if (!contentEncoding.isNullOrBlank()) {
                append("Content-Encoding: $contentEncoding\r\n")
                append("Vary: Accept-Encoding\r\n")
            }
            append("Cache-Control: $cacheControl\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, HEAD, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("Access-Control-Allow-Private-Network: true\r\n")
            if (keepAlive) {
                append("Connection: keep-alive\r\n")
                append("Keep-Alive: timeout=10, max=24\r\n\r\n")
            } else {
                append("Connection: close\r\n\r\n")
            }
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
