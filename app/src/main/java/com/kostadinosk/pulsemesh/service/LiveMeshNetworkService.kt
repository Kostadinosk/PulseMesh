// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

data class WireMeshPacket(
    val packetId: String,
    val srcNodeId: String,
    val srcCallsign: String,
    val dstNodeId: String, // "BROADCAST", "ALL", or specific nodeId or groupId
    val type: String,      // "CHAT", "E2EE_DM", "GROUP_INVITE", "GROUP_CHAT", "GPS_BEACON", "SOS", "VOICE_STREAM", "VOICE_PTT", "CALL_RING", "CALL_ACCEPT", "CALL_DECLINE", "CALL_END", "HOTSPOT_INFO", "HOTSPOT_AUTO_JOIN", "HEARTBEAT"
    val payload: String,
    val ttl: Int = 7,
    val hops: Int = 0,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracy: Float? = null,
    val altitude: Double? = null,
    val battery: Int? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val sourceTransport: String = "",
    val rssiDbm: Int? = null,
    val groupId: String = "",
    val groupName: String = "",
    val memberIdsCsv: String = "",
    val senderPubKey: String = "",
    val encryptedKeysJson: String = "",
    val srcUsername: String = "",
    val dstUsername: String = "",
    val isGateway: Boolean = false
)

/**
 * Live Hybrid Cross-Device Mesh Network Service using ntfy.sh JSON Pub/Sub.
 * Strictly guarded by isInternetEnabled(): when Off-Grid Mode is active (isInternetEnabled == false),
 * all HTTP connections are immediately disconnected and blocked at the very first line of every function.
 */
class LiveMeshNetworkService(
    private val scope: CoroutineScope,
    private val localNodeId: () -> String,
    private val localCallsign: () -> String,
    private val channelName: () -> String,
    private val getCurrentLocation: () -> Pair<Double?, Double?>,
    private val getBatteryPercent: () -> Int,
    private val isInternetEnabled: () -> Boolean,
    private val context: Context? = null,
    private val meshPassphrase: () -> String = { "pulsemesh-tactical" },
    private val localUsername: () -> String = { "" },
    private val usernameRelayBaseUrl: () -> String = { "" },
    private val myPrivateKeyBase64: () -> String = { "" },
    private val myAuthToken: () -> String = { "" },
    private val resolvePeerPublicKeyByUsername: (String) -> String = { "" },
    private val getInboxCursor: () -> Long = { 0L },
    private val setInboxCursor: (Long) -> Unit = {},
    private val onInternetRestored: (() -> Unit)? = null
) {
    private val _incomingPackets = MutableSharedFlow<WireMeshPacket>(extraBufferCapacity = 128)
    val incomingPackets: SharedFlow<WireMeshPacket> = _incomingPackets.asSharedFlow()

    var onRawJsonPacketReceived: ((String) -> Unit)? = null
    var onRealtimeSignalReceived: ((JSONObject) -> Unit)? = null
    var onRealtimeBinaryFrameReceived: ((ByteArray) -> Unit)? = null

    private val _relayStatus = MutableStateFlow("Relay: OFF (Pure BLE)")
    val relayStatus: StateFlow<String> = _relayStatus.asStateFlow()

    val realtimeClient: CloudflareRealtimeClient = CloudflareRealtimeClient(
        scope = scope,
        isInternetEnabled = isInternetEnabled,
        activeRelayUrl = { activeUsernameRelayBaseUrl() },
        localUsername = localUsername,
        authToken = myAuthToken,
        boundNetwork = { cellularNetwork },
        onTextMessage = { raw ->
            runCatching {
                val obj = JSONObject(raw)
                val msgType = obj.optString("type", "")
                if (msgType == "INBOX_WAKEUP") {
                    val base = activeUsernameRelayBaseUrl()
                    val uname = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.normalizeUsername(localUsername())
                    if (base.isNotEmpty() && uname.length >= 3 && isInternetEnabled()) {
                        pollCustomRelayInboxOnce(base, uname)
                    }
                } else if (msgType == "PM_CHANNEL_FRAME" && obj.has("envelope")) {
                    parseIncomingRelayLine(JSONObject().put("event", "message").put("message", obj.optString("envelope")).toString())
                } else {
                    onRealtimeSignalReceived?.invoke(obj)
                }
            }
        },
        onBinaryMessage = { bytes ->
            onRealtimeBinaryFrameReceived?.invoke(bytes)
        }
    )

    private fun deriveEncryptedInboxFallbackTopic(username: String): String {
        val norm = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.normalizeUsername(username)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(("PULSEMESH_USER_INBOX_V2:$norm").toByteArray(Charsets.UTF_8))
        return "pm_u2_" + digest.take(10).joinToString("") { "%02x".format(it) }
    }

    private val seenPacketIds = ConcurrentHashMap<String, Long>()
    private var ntfyStreamJob: Job? = null
    private var usernameInboxJob: Job? = null
    private var heartbeatJob: Job? = null
    private var isRunning = false

    @Volatile
    private var activeHttpConnection: HttpURLConnection? = null

    @Volatile
    private var activeInboxHttpConnection: HttpURLConnection? = null

    @Volatile
    private var cellularNetwork: Network? = null
    private var cellularNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var generalInternetCallback: ConnectivityManager.NetworkCallback? = null

    private fun isNetworkValidatedInternet(cm: ConnectivityManager, net: Network?): Boolean {
        if (net == null) return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isDefaultNetworkValidatedInternet(): Boolean {
        val ctx = context ?: return false
        return runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return@runCatching false
            isNetworkValidatedInternet(cm, cm.activeNetwork)
        }.getOrDefault(false)
    }

    @Synchronized
    private fun ensureCellularNetworkBinding() {
        val ctx = context ?: return
        if (!isInternetEnabled()) return
        if (cellularNetwork != null && cellularNetworkCallback != null) return
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return@runCatching
            if (cellularNetwork == null) {
                // Only bind TRANSPORT_CELLULAR when it actually has validated internet capability
                val existingCellular = cm.allNetworks.firstOrNull { net ->
                    val caps = cm.getNetworkCapabilities(net)
                    caps != null &&
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                        isNetworkValidatedInternet(cm, net)
                }
                if (existingCellular != null) {
                    cellularNetwork = existingCellular
                }
            }
            if (cellularNetworkCallback == null) {
                val req = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                            cellularNetwork = network
                        } else if (cellularNetwork == network) {
                            cellularNetwork = null
                        }
                    }
                    override fun onLost(network: Network) {
                        if (cellularNetwork == network) {
                            cellularNetwork = null
                        }
                    }
                }
                cellularNetworkCallback = cb
                cm.requestNetwork(req, cb)
            }
            if (generalInternetCallback == null && onInternetRestored != null) {
                val netReq = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                val netCb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        onInternetRestored.invoke()
                    }
                }
                generalInternetCallback = netCb
                cm.registerNetworkCallback(netReq, netCb)
            }
        }
    }

    private fun releaseCellularNetworkBinding() {
        val ctx = context ?: return
        val cb = cellularNetworkCallback
        val gcb = generalInternetCallback
        cellularNetworkCallback = null
        generalInternetCallback = null
        cellularNetwork = null
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cb != null) cm?.unregisterNetworkCallback(cb)
            if (gcb != null) cm?.unregisterNetworkCallback(gcb)
        }
    }

    private fun openBoundRelayConnection(url: URL, preferDefaultNetwork: Boolean = false): HttpURLConnection {
        if (!preferDefaultNetwork && isInternetEnabled() && !isDefaultNetworkValidatedInternet()) {
            ensureCellularNetworkBinding()
            val cellNet = cellularNetwork
            if (cellNet != null) {
                val bound = runCatching { cellNet.openConnection(url) as? HttpURLConnection }.getOrNull()
                if (bound != null) return bound
            }
        }
        return url.openConnection() as HttpURLConnection
    }

    private inline fun <T> withRelayConnectionFallback(
        url: URL,
        block: (HttpURLConnection) -> T
    ): T {
        val primaryConn = openBoundRelayConnection(url, preferDefaultNetwork = false)
        return try {
            block(primaryConn)
        } catch (e: Exception) {
            runCatching { primaryConn.disconnect() }
            if (cellularNetwork != null) {
                val fallbackConn = openBoundRelayConnection(url, preferDefaultNetwork = true)
                try {
                    block(fallbackConn)
                } finally {
                    runCatching { fallbackConn.disconnect() }
                }
            } else {
                throw e
            }
        } finally {
            runCatching { primaryConn.disconnect() }
        }
    }

    companion object {
        private const val RELAY_BASE_URL = "https://ntfy.sh"
        private const val TOPIC_PREFIX = "pulsemesh_tac_v3_"

        /**
         * Haversine formula to compute real distance in meters between two GPS coordinates
         */
        fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0 // Earth radius in meters
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2.0) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2).pow(2.0)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return r * c
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        if (!isInternetEnabled()) {
            ntfyStreamJob?.cancel()
            ntfyStreamJob = null
            usernameInboxJob?.cancel()
            usernameInboxJob = null
            runCatching { activeHttpConnection?.disconnect() }
            activeHttpConnection = null
            runCatching { activeInboxHttpConnection?.disconnect() }
            activeInboxHttpConnection = null
            realtimeClient.stop()
            _relayStatus.value = "Relay: OFF (Pure BLE)"
        } else {
            startStreamingListener()
            refreshUsernameInboxListener()
            realtimeClient.startOrReconnect()
        }
        startPeriodicHeartbeat()
    }

    fun stop() {
        isRunning = false
        ntfyStreamJob?.cancel()
        ntfyStreamJob = null
        usernameInboxJob?.cancel()
        usernameInboxJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        realtimeClient.stop()
        runCatching { activeHttpConnection?.disconnect() }
        activeHttpConnection = null
        runCatching { activeInboxHttpConnection?.disconnect() }
        activeInboxHttpConnection = null
        releaseCellularNetworkBinding()
        _relayStatus.value = "Relay: OFF (Pure BLE)"
    }

    fun refreshInternetState() {
        if (!isInternetEnabled()) {
            ntfyStreamJob?.cancel()
            ntfyStreamJob = null
            usernameInboxJob?.cancel()
            usernameInboxJob = null
            realtimeClient.stop()
            runCatching { activeHttpConnection?.disconnect() }
            activeHttpConnection = null
            runCatching { activeInboxHttpConnection?.disconnect() }
            activeInboxHttpConnection = null
            releaseCellularNetworkBinding()
            _relayStatus.value = "Relay: OFF (Pure BLE)"
        } else {
            ensureCellularNetworkBinding()
            if (ntfyStreamJob?.isActive != true) {
                startStreamingListener()
            }
            refreshUsernameInboxListener()
            if (!realtimeClient.isConnected.value) {
                realtimeClient.startOrReconnect()
            }
            pingMeshNow()
        }
    }

    fun reconnect() {
        if (!isInternetEnabled()) {
            ntfyStreamJob?.cancel()
            ntfyStreamJob = null
            usernameInboxJob?.cancel()
            usernameInboxJob = null
            realtimeClient.stop()
            runCatching { activeHttpConnection?.disconnect() }
            activeHttpConnection = null
            runCatching { activeInboxHttpConnection?.disconnect() }
            activeInboxHttpConnection = null
            _relayStatus.value = "Relay: OFF (Pure BLE)"
            return
        }
        ntfyStreamJob?.cancel()
        runCatching { activeHttpConnection?.disconnect() }
        activeHttpConnection = null
        usernameInboxJob?.cancel()
        runCatching { activeInboxHttpConnection?.disconnect() }
        activeInboxHttpConnection = null
        startStreamingListener()
        refreshUsernameInboxListener()
        realtimeClient.startOrReconnect()
        pingMeshNow()
    }

    fun refreshUsernameInboxListener() {
        if (!isRunning || !isInternetEnabled()) {
            usernameInboxJob?.cancel()
            usernameInboxJob = null
            runCatching { activeInboxHttpConnection?.disconnect() }
            activeInboxHttpConnection = null
            return
        }
        val myUsername = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.normalizeUsername(localUsername())
        if (myUsername.length < 3) {
            usernameInboxJob?.cancel()
            usernameInboxJob = null
            return
        }
        usernameInboxJob?.cancel()
        runCatching { activeInboxHttpConnection?.disconnect() }
        activeInboxHttpConnection = null
        startUsernameInboxListener(myUsername)
    }

    fun getSanitizedChannel(): String {
        val rawChannel = channelName().lowercase().replace(Regex("[^a-z0-9_]"), "")
        return if (rawChannel.isBlank()) "primary" else rawChannel
    }

    fun getRelayTopic(): String {
        val pass = meshPassphrase().trim().ifBlank { getSanitizedChannel() }
        return com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.deriveHashedNtfyTopic(pass)
    }

    private fun currentTopicUrl(): String {
        return "$RELAY_BASE_URL/${getRelayTopic()}"
    }

    /**
     * Broadcasts a real encrypted JSON mesh packet to all active devices on the channel
     */
    fun broadcastPacket(packet: WireMeshPacket) {
        if (!isInternetEnabled()) return
        seenPacketIds[packet.packetId] = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            if (!isInternetEnabled()) return@launch
            try {
                val json = JSONObject().apply {
                    put("packetId", packet.packetId)
                    put("srcNodeId", packet.srcNodeId)
                    put("srcCallsign", packet.srcCallsign)
                    put("dstNodeId", packet.dstNodeId)
                    put("type", packet.type)
                    put("payload", packet.payload)
                    put("ttl", packet.ttl)
                    put("hops", packet.hops)
                    packet.lat?.let { put("lat", it) }
                    packet.lng?.let { put("lng", it) }
                    packet.accuracy?.let { put("accuracy", it.toDouble()) }
                    packet.altitude?.let { put("altitude", it) }
                    packet.battery?.let { put("battery", it) }
                    if (packet.groupId.isNotBlank()) put("groupId", packet.groupId)
                    if (packet.groupName.isNotBlank()) put("groupName", packet.groupName)
                    if (packet.memberIdsCsv.isNotBlank()) put("memberIds", packet.memberIdsCsv)
                    if (packet.senderPubKey.isNotBlank()) put("pk", packet.senderPubKey)
                    if (packet.encryptedKeysJson.isNotBlank()) put("ekeys", packet.encryptedKeysJson)
                    if (packet.srcUsername.isNotBlank()) put("su", packet.srcUsername)
                    if (packet.dstUsername.isNotBlank()) put("du", packet.dstUsername)
                    if (packet.isGateway) put("gw", true)
                    put("timestamp", packet.timestamp)
                }

                val encryptedEnvelope = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.encryptChannelPayload(
                    json.toString(),
                    meshPassphrase()
                )

                if (!isInternetEnabled()) return@launch
                if (realtimeClient.isConnected.value) {
                    val wsFrame = JSONObject()
                        .put("type", "PM_CHANNEL_FRAME")
                        .put("envelope", encryptedEnvelope)
                        .toString()
                    // Send on realtime WS as a fast path, AND always continue to HTTP channel relay
                    // because realtimeClient may be connected to a private inbox:<user> room rather than the shared channel.
                    runCatching { realtimeClient.sendText(wsFrame) }
                }
                val url = URL(currentTopicUrl())
                var conn: HttpURLConnection? = null
                try {
                    conn = openBoundRelayConnection(url).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                        setRequestProperty("Cache-Control", "no-cache")
                    }

                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(encryptedEnvelope)
                        writer.flush()
                    }

                    val responseCode = conn.responseCode
                    if (responseCode in 200..299 && isInternetEnabled()) {
                        _relayStatus.value = if (cellularNetwork != null) {
                            "Relay: LIVE (4G/5G/eSIM Bound)"
                        } else {
                            "Relay: LIVE (Cross-Phone Ready)"
                        }
                    }
                } finally {
                    runCatching { conn?.disconnect() }
                }
            } catch (_: Exception) {
                // Silent catch for offline / pure RF environments
            }
        }
    }

    /**
     * Dedicated real-time audio frame broadcaster for CALL_AUDIO and VOICE_PTT chunks
     */
    fun broadcastAudioPacket(packet: WireMeshPacket) {
        if (!isInternetEnabled()) return
        seenPacketIds[packet.packetId] = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            if (!isInternetEnabled()) return@launch
            try {
                val json = JSONObject().apply {
                    put("packetId", packet.packetId)
                    put("srcNodeId", packet.srcNodeId)
                    put("srcCallsign", packet.srcCallsign)
                    put("dstNodeId", packet.dstNodeId)
                    put("type", packet.type)
                    put("payload", packet.payload)
                    put("ttl", packet.ttl)
                    put("hops", packet.hops)
                    put("timestamp", packet.timestamp)
                }

                val encryptedEnvelope = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.encryptChannelPayload(
                    json.toString(),
                    meshPassphrase()
                )

                if (!isInternetEnabled()) return@launch
                if (realtimeClient.isConnected.value) {
                    val wsFrame = JSONObject()
                        .put("type", "PM_CHANNEL_FRAME")
                        .put("envelope", encryptedEnvelope)
                        .toString()
                    // Send on realtime WS as a fast path, AND always continue to HTTP channel relay
                    // because realtimeClient may be connected to a private inbox:<user> room rather than the shared channel.
                    runCatching { realtimeClient.sendText(wsFrame) }
                }
                val url = URL(currentTopicUrl())
                var conn: HttpURLConnection? = null
                try {
                    conn = openBoundRelayConnection(url).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 4000
                        readTimeout = 4000
                        setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    }

                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(encryptedEnvelope)
                        writer.flush()
                    }
                    conn.responseCode
                } finally {
                    runCatching { conn?.disconnect() }
                }
            } catch (_: Exception) {
                // Ignore transient frame drop
            }
        }
    }

    /**
     * Sends an immediate presence & GPS beacon so peers discover this phone right away
     */
    fun pingMeshNow() {
        if (!isInternetEnabled()) return
        val myId = localNodeId()
        if (myId.isBlank()) return
        val (lat, lng) = getCurrentLocation()
        val packet = WireMeshPacket(
            packetId = "pkt_${System.currentTimeMillis()}_${(1000..9999).random()}",
            srcNodeId = myId,
            srcCallsign = localCallsign(),
            dstNodeId = "BROADCAST",
            type = if (lat != null && lng != null) "GPS_BEACON" else "HEARTBEAT",
            payload = "Active PulseMesh Node",
            lat = lat,
            lng = lng,
            battery = getBatteryPercent()
        )
        broadcastPacket(packet)
    }

    private fun startPeriodicHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            delay(1200)
            if (isInternetEnabled()) {
                pingMeshNow()
            }
            while (isActive && isRunning) {
                delay(18_000)
                if (isInternetEnabled()) {
                    pingMeshNow()
                }
            }
        }
    }

    private fun startStreamingListener() {
        if (!isInternetEnabled()) {
            _relayStatus.value = "Relay: OFF (Pure BLE)"
            return
        }
        ntfyStreamJob?.cancel()
        ntfyStreamJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {
                if (!isInternetEnabled()) {
                    _relayStatus.value = "Relay: OFF (Pure BLE)"
                    runCatching { activeHttpConnection?.disconnect() }
                    activeHttpConnection = null
                    break
                }

                var conn: HttpURLConnection? = null
                try {
                    _relayStatus.value = "Relay: Connecting..."
                    if (!isInternetEnabled()) break
                    val streamUrl = URL("${currentTopicUrl()}/json?since=5s")
                    conn = openBoundRelayConnection(streamUrl).apply {
                        requestMethod = "GET"
                        connectTimeout = 10_000
                        readTimeout = 65_000
                        setRequestProperty("Accept", "application/x-ndjson")
                        setRequestProperty("Cache-Control", "no-cache")
                    }
                    activeHttpConnection = conn

                    val code = conn.responseCode
                    if (code in 200..299 && isInternetEnabled()) {
                        _relayStatus.value = if (cellularNetwork != null) {
                            "Relay: LIVE (4G/5G/eSIM Bound)"
                        } else {
                            "Relay: LIVE (Cross-Phone Ready)"
                        }
                        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                            var line: String? = null
                            while (isActive && isRunning && isInternetEnabled() && reader.readLine().also { line = it } != null) {
                                val rawLine = line?.trim() ?: continue
                                if (rawLine.isNotEmpty()) {
                                    parseIncomingRelayLine(rawLine)
                                }
                            }
                        }
                    } else {
                        if (isInternetEnabled()) {
                            _relayStatus.value = "Relay: HTTP $code (Retrying)"
                        }
                    }
                } catch (_: Exception) {
                    if (isInternetEnabled()) {
                        _relayStatus.value = "Relay: Reconnecting..."
                    } else {
                        _relayStatus.value = "Relay: OFF (Pure BLE)"
                    }
                } finally {
                    try {
                        conn?.disconnect()
                    } catch (_: Exception) {}
                    if (activeHttpConnection === conn) {
                        activeHttpConnection = null
                    }
                }

                if (isActive && isRunning && isInternetEnabled()) {
                    delay(3000)
                }
            }
        }
    }

    private suspend fun parseIncomingRelayLine(rawLine: String) {
        if (!isInternetEnabled()) return
        try {
            val outer = JSONObject(rawLine)
            val eventType = outer.optString("event", "")
            if (eventType != "message") return

            val rawMessageStr = outer.optString("message", "")
            if (rawMessageStr.isBlank()) return

            val messageStr = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.decryptChannelPayload(
                rawMessageStr,
                meshPassphrase()
            ) ?: return
            if (!messageStr.startsWith("{")) return

            onRawJsonPacketReceived?.invoke(messageStr)

            val inner = JSONObject(messageStr)
            val packetId = inner.optString("id").ifBlank { inner.optString("packetId") }
            val srcNodeId = inner.optString("srcId")
                .ifBlank { inner.optString("srcNodeId") }
                .ifBlank { inner.optString("src") }

            if (packetId.isBlank() || srcNodeId.isBlank()) return
            if (OffGridBleMeshEngine.normalizeId(srcNodeId) == OffGridBleMeshEngine.normalizeId(localNodeId())) return

            val srcCallsign = inner.optString("srcName")
                .ifBlank { inner.optString("srcCallsign") }
                .ifBlank { inner.optString("cs", "") }
            if (OffGridBleMeshEngine.isBlockedFakeCallsign(srcCallsign)) return

            val type = inner.optString("type").ifBlank { inner.optString("t", "HEARTBEAT") }
            val payloadStr = inner.optString("payload").ifBlank { inner.optString("p", "") }
            if (type != "CALL_ACCEPT" &&
                type != "CALL_ACCEPT_ACK" &&
                type != "CALL_RING" &&
                type != "CALL_END" &&
                type != "HEARTBEAT" &&
                type != "GPS_BEACON"
            ) {
                if (seenPacketIds.putIfAbsent(packetId, System.currentTimeMillis()) != null) {
                    return
                }
            }

            val dstId = inner.optString("dstId")
                .ifBlank { inner.optString("dstNodeId") }
                .ifBlank { inner.optString("dst", "BROADCAST") }

            val packet = WireMeshPacket(
                packetId = packetId,
                srcNodeId = srcNodeId,
                srcCallsign = srcCallsign,
                dstNodeId = dstId,
                type = type,
                payload = payloadStr,
                ttl = inner.optInt("ttl", 7),
                hops = inner.optInt("hops", 0),
                lat = if (inner.has("lat")) inner.getDouble("lat") else null,
                lng = if (inner.has("lng")) inner.getDouble("lng") else null,
                accuracy = when {
                    inner.has("accuracy") -> inner.getDouble("accuracy").toFloat()
                    inner.has("acc") -> inner.getDouble("acc").toFloat()
                    else -> null
                },
                altitude = if (inner.has("altitude")) inner.getDouble("altitude") else null,
                battery = if (inner.has("battery")) inner.getInt("battery") else if (inner.has("bat")) inner.getInt("bat") else null,
                timestamp = inner.optLong("timestamp", inner.optLong("ts", System.currentTimeMillis())),
                sourceTransport = "HYBRID CELL",
                rssiDbm = null,
                groupId = inner.optString("groupId", ""),
                groupName = inner.optString("groupName", ""),
                memberIdsCsv = inner.optString("memberIds", ""),
                senderPubKey = inner.optString("pk", "").ifBlank { inner.optString("senderPubKey", "") },
                encryptedKeysJson = inner.optString("ekeys", "").ifBlank { inner.optString("encryptedKeys", "") },
                srcUsername = inner.optString("su", ""),
                dstUsername = inner.optString("du", ""),
                isGateway = inner.optBoolean("gw", false)
            )

            _incomingPackets.emit(packet)
        } catch (_: Exception) {
            // Ignore malformed line
        }
    }

    private fun activeUsernameRelayBaseUrl(): String {
        val custom = usernameRelayBaseUrl().trim().trimEnd('/')
        return if (custom.startsWith("https://") || custom.startsWith("http://")) custom else ""
    }

    data class UsernameClaimLookupResult(
        val reachable: Boolean,
        val claim: com.kostadinosk.pulsemesh.crypto.UsernameClaimRecord?
    )

    data class UsernameClaimPublishResult(
        val success: Boolean,
        val conflict: Boolean,
        val reachable: Boolean
    )

    data class TurnIceServer(
        val urls: List<String>,
        val username: String = "",
        val credential: String = ""
    )

    suspend fun fetchChallengeNonce(username: String): String? = withContext(Dispatchers.IO) {
        if (!isInternetEnabled()) return@withContext null
        val norm = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.normalizeUsername(username)
        val customBase = activeUsernameRelayBaseUrl()
        if (norm.length < 3 || customBase.isEmpty()) return@withContext null
        runCatching {
            val url = URL("$customBase/v1/users/$norm/challenge")
            withRelayConnectionFallback(url) { conn ->
                conn.requestMethod = "POST"
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                if (conn.responseCode !in 200..299) return@withRelayConnectionFallback null
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JSONObject(body).optString("nonce", "").ifBlank { null }
            }
        }.getOrNull()
    }

    suspend fun fetchVaultWithUnlockProof(
        username: String,
        unlockProof: String = "",
        nonce: String = "",
        ecdsaSig: String = ""
    ): com.kostadinosk.pulsemesh.crypto.UsernameClaimRecord? = withContext(Dispatchers.IO) {
        val norm = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.normalizeUsername(username)
        val customBase = activeUsernameRelayBaseUrl()
        if (norm.length < 3 || customBase.isEmpty()) return@withContext null
        val publicClaim = pollUsernameClaimWithStatus(norm, allowDirectLookup = true).claim ?: return@withContext null
        runCatching {
            val url = URL("$customBase/v1/users/$norm/vault")
            withRelayConnectionFallback(url) { conn ->
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (unlockProof.isNotBlank()) conn.setRequestProperty("X-PulseMesh-Unlock-Proof", unlockProof.trim())
                val reqBody = JSONObject().apply {
                    if (nonce.isNotBlank()) put("nonce", nonce)
                    if (ecdsaSig.isNotBlank()) put("sig", ecdsaSig)
                    if (unlockProof.isNotBlank()) put("unlockProof", unlockProof.trim())
                }.toString()
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write(reqBody)
                    it.flush()
                }
                if (conn.responseCode !in 200..299) return@withRelayConnectionFallback null
                val respObj = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                publicClaim.copy(
                    saltHex = respObj.optString("saltHex", publicClaim.saltHex),
                    pinVault = respObj.optString("pinVault", publicClaim.pinVault),
                    recoveryVault = respObj.optString("recoveryVault", publicClaim.recoveryVault)
                )
            }
        }.getOrNull()
    }

    suspend fun fetchTurnCredentials(allowDirectLookup: Boolean = false): List<TurnIceServer> =
        withContext(Dispatchers.IO) {
            if (!allowDirectLookup && !isInternetEnabled()) return@withContext emptyList()
            val customBase = activeUsernameRelayBaseUrl()
            val uname = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.normalizeUsername(localUsername())
            val token = myAuthToken().trim()
            if (customBase.isEmpty() || uname.length < 3 || token.isBlank()) {
                return@withContext listOf(
                    TurnIceServer(listOf("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"))
                )
            }
            runCatching {
                val url = URL("$customBase/v1/turn/credentials?username=$uname")
                withRelayConnectionFallback(url) { conn ->
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.setRequestProperty("X-PulseMesh-Auth", token)
                    conn.setRequestProperty("X-PM-Auth", token)
                    if (conn.responseCode !in 200..299) return@withRelayConnectionFallback emptyList()
                    val obj = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                    val arr = obj.optJSONArray("iceServers") ?: return@withRelayConnectionFallback emptyList()
                    val servers = mutableListOf<TurnIceServer>()
                    for (i in 0 until arr.length()) {
                        val sObj = arr.optJSONObject(i) ?: continue
                        val urlsList = mutableListOf<String>()
                        val urlsArr = sObj.optJSONArray("urls")
                        if (urlsArr != null) {
                            for (j in 0 until urlsArr.length()) {
                                urlsArr.optString(j).takeIf { it.isNotBlank() }?.let { urlsList.add(it) }
                            }
                        } else {
                            sObj.optString("urls", "").takeIf { it.isNotBlank() }?.let { urlsList.add(it) }
                        }
                        if (urlsList.isNotEmpty()) {
                            servers.add(
                                TurnIceServer(
                                    urls = urlsList,
                                    username = sObj.optString("username", ""),
                                    credential = sObj.optString("credential", "")
                                )
                            )
                        }
                    }
                    servers
                }
            }.getOrElse {
                listOf(TurnIceServer(listOf("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302")))
            }
        }

    suspend fun testUsernameRelayHealth(rawUrl: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val clean = rawUrl.trim().trimEnd('/')
            if (!clean.startsWith("https://") && !clean.startsWith("http://")) {
                return@withContext Pair(false, "URL must start with https:// (or http:// for local tests)")
            }
            var conn: HttpURLConnection? = null
            try {
                val url = URL("$clean/health")
                conn = openBoundRelayConnection(url).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                val code = conn.responseCode
                val body = runCatching {
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.getOrDefault("")
                if (code in 200..299 && body.contains("\"ok\":true")) {
                    Pair(true, "Connected to Free Encrypted Cloudflare Relay (v2 Atomic E2EE)")
                } else if (code in 200..299) {
                    Pair(true, "Relay reachable (HTTP $code)")
                } else {
                    Pair(false, "Relay returned HTTP $code")
                }
            } catch (e: Exception) {
                Pair(false, "Connection failed: ${e.message ?: "offline"}")
            } finally {
                runCatching { conn?.disconnect() }
            }
        }

    /**
     * Queries the global first-come-first-serve username claim registry.
     * - When custom Cloudflare D1 Relay URL is set: queries `GET /v1/users/:username` (permanent atomic DB).
     * - Otherwise: queries the signed v2 claim topic on `ntfy.sh` and verifies ECDSA signatures.
     */
    suspend fun pollUsernameClaimWithStatus(
        username: String,
        allowDirectLookup: Boolean = false
    ): UsernameClaimLookupResult =
        withContext(Dispatchers.IO) {
            if (!allowDirectLookup && !isInternetEnabled()) return@withContext UsernameClaimLookupResult(false, null)
            val norm = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.normalizeUsername(username)
            if (norm.length < 3) return@withContext UsernameClaimLookupResult(false, null)

            val customBase = activeUsernameRelayBaseUrl()
            if (customBase.isNotEmpty()) {
                return@withContext try {
                    val url = URL("$customBase/v1/users/$norm")
                    withRelayConnectionFallback(url) { conn ->
                        conn.apply {
                            requestMethod = "GET"
                            connectTimeout = 6000
                            readTimeout = 6000
                            setRequestProperty("Accept", "application/json")
                        }
                        when (conn.responseCode) {
                            404 -> UsernameClaimLookupResult(true, null)
                            in 200..299 -> {
                                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                                val rec = com.kostadinosk.pulsemesh.crypto.UsernameClaimRecord.fromJson(JSONObject(text))
                                if (rec != null &&
                                    rec.username == norm &&
                                    (rec.claimVersion < 2 || rec.signatureBase64.isBlank() ||
                                        com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.verifyUsernameClaimSignature(rec))
                                ) {
                                    UsernameClaimLookupResult(true, rec)
                                } else {
                                    UsernameClaimLookupResult(true, null)
                                }
                            }
                            else -> UsernameClaimLookupResult(false, null)
                        }
                    }
                } catch (_: Exception) {
                    UsernameClaimLookupResult(false, null)
                }
            }

            val topic = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.deriveUsernameClaimTopic(norm)
            try {
                val url = URL("$RELAY_BASE_URL/$topic/json?poll=1&since=all")
                withRelayConnectionFallback(url) { conn ->
                    conn.apply {
                        requestMethod = "GET"
                        connectTimeout = 6000
                        readTimeout = 6000
                        setRequestProperty("Accept", "application/x-ndjson")
                    }
                    if (conn.responseCode !in 200..299) {
                        return@withRelayConnectionFallback UsernameClaimLookupResult(false, null)
                    }
                    var authoritative: com.kostadinosk.pulsemesh.crypto.UsernameClaimRecord? = null
                    BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                        var line: String? = null
                        while (reader.readLine().also { line = it } != null) {
                            val trimmed = line?.trim().orEmpty()
                            if (trimmed.isEmpty()) continue
                            runCatching {
                                val outer = JSONObject(trimmed)
                                if (outer.optString("event") != "message") return@runCatching
                                val msg = outer.optString("message")
                                if (!msg.startsWith("{")) return@runCatching
                                val rec = com.kostadinosk.pulsemesh.crypto.UsernameClaimRecord.fromJson(JSONObject(msg))
                                    ?: return@runCatching
                                if (rec.username != norm) return@runCatching
                                if (rec.claimVersion >= 2 && rec.signatureBase64.isNotBlank() &&
                                    !com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.verifyUsernameClaimSignature(rec)
                                ) {
                                    return@runCatching
                                }
                                val currentAuth = authoritative
                                if (currentAuth == null) {
                                    authoritative = rec
                                } else {
                                    val sameOwnerProof =
                                        (currentAuth.authVerifierHash.isNotBlank() && currentAuth.authVerifierHash == rec.authVerifierHash) ||
                                            (currentAuth.publicKeyBase64.isNotBlank() && currentAuth.publicKeyBase64 == rec.publicKeyBase64) ||
                                            (currentAuth.pinHash.isNotBlank() && currentAuth.pinHash == rec.pinHash) ||
                                            (currentAuth.recoveryHash.isNotBlank() && currentAuth.recoveryHash == rec.recoveryHash)
                                    if (sameOwnerProof) {
                                        val useRecSig = rec.signatureBase64.isNotBlank()
                                        authoritative = currentAuth.copy(
                                            displayName = rec.displayName,
                                            publicKeyBase64 = rec.publicKeyBase64.ifBlank { currentAuth.publicKeyBase64 },
                                            pinVault = rec.pinVault.ifBlank { currentAuth.pinVault },
                                            recoveryVault = rec.recoveryVault.ifBlank { currentAuth.recoveryVault },
                                            claimedAt = if (useRecSig) rec.claimedAt else currentAuth.claimedAt,
                                            signatureBase64 = if (useRecSig) rec.signatureBase64 else currentAuth.signatureBase64
                                        )
                                    }
                                }
                            }
                        }
                    }
                    UsernameClaimLookupResult(true, authoritative)
                }
            } catch (_: Exception) {
                UsernameClaimLookupResult(false, null)
            }
        }

    suspend fun pollUsernameClaim(
        username: String,
        allowDirectLookup: Boolean = false
    ): com.kostadinosk.pulsemesh.crypto.UsernameClaimRecord? =
        pollUsernameClaimWithStatus(username, allowDirectLookup = allowDirectLookup).claim

    suspend fun publishUsernameClaimWithStatus(
        record: com.kostadinosk.pulsemesh.crypto.UsernameClaimRecord,
        isUpdate: Boolean = false,
        allowDirectLookup: Boolean = false
    ): UsernameClaimPublishResult = withContext(Dispatchers.IO) {
        if (!allowDirectLookup && !isInternetEnabled()) return@withContext UsernameClaimPublishResult(false, false, false)
        val norm = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.normalizeUsername(record.username)
        if (norm.length < 3) return@withContext UsernameClaimPublishResult(false, false, false)

        val wireJson = record.copy(username = norm).toPublicWireJson().toString()
        val customBase = activeUsernameRelayBaseUrl()
        if (customBase.isNotEmpty()) {
            return@withContext try {
                val url = if (isUpdate) URL("$customBase/v1/users/$norm") else URL("$customBase/v1/users/claim")
                withRelayConnectionFallback(url) { conn ->
                    conn.apply {
                        requestMethod = if (isUpdate) "PUT" else "POST"
                        doOutput = true
                        connectTimeout = 6000
                        readTimeout = 6000
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        val tok = myAuthToken()
                        if (isUpdate && tok.isNotBlank()) {
                            setRequestProperty("X-PM-Auth", tok)
                            setRequestProperty("X-PulseMesh-Auth", tok)
                        }
                    }
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(wireJson)
                        writer.flush()
                    }
                    val code = conn.responseCode
                    when {
                        code in 200..299 -> UsernameClaimPublishResult(true, false, true)
                        code == 409 -> UsernameClaimPublishResult(false, true, true)
                        else -> UsernameClaimPublishResult(false, false, false)
                    }
                }
            } catch (_: Exception) {
                UsernameClaimPublishResult(false, false, false)
            }
        }

        val topic = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.deriveUsernameClaimTopic(norm)
        try {
            val url = URL("$RELAY_BASE_URL/$topic")
            withRelayConnectionFallback(url) { conn ->
                conn.apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(wireJson)
                    writer.flush()
                }
                val ok = conn.responseCode in 200..299
                UsernameClaimPublishResult(ok, false, ok)
            }
        } catch (_: Exception) {
            UsernameClaimPublishResult(false, false, false)
        }
    }

    suspend fun publishUsernameClaim(
        record: com.kostadinosk.pulsemesh.crypto.UsernameClaimRecord,
        allowDirectLookup: Boolean = false
    ): Boolean =
        publishUsernameClaimWithStatus(record, isUpdate = false, allowDirectLookup = allowDirectLookup).success

    /**
     * Permanently deletes a claimed username and its queued inbox from the Cloudflare D1 relay.
     * Authenticated via `X-PM-Auth` token and/or ECDSA P-256 signature over `DELETE|<username>`.
     */
    suspend fun deleteUsernameFromRelay(
        username: String,
        authToken: String = "",
        privateKeyBase64: String = "",
        allowDirectLookup: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        if (!allowDirectLookup && !isInternetEnabled()) return@withContext false
        val norm = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.normalizeUsername(username)
        val customBase = activeUsernameRelayBaseUrl()
        if (norm.length < 3 || customBase.isEmpty()) return@withContext false
        runCatching {
            val url = URL("$customBase/v1/users/$norm")
            withRelayConnectionFallback(url) { conn ->
                conn.requestMethod = "DELETE"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (authToken.isNotBlank()) {
                    conn.setRequestProperty("X-PM-Auth", authToken.trim())
                    conn.setRequestProperty("X-PulseMesh-Auth", authToken.trim())
                }
                if (privateKeyBase64.isNotBlank()) {
                    val sig = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.signMessageWithEcKey(
                        privateKeyBase64 = privateKeyBase64,
                        message = "DELETE|$norm"
                    )
                    if (sig.isNotBlank()) conn.setRequestProperty("X-PM-Signature", sig)
                }
                conn.responseCode in 200..299
            }
        }.getOrDefault(false)
    }

    /**
     * Uploads an encrypted (GZIP + AES-256-GCM "PMB1:") full-account backup snapshot
     * (chats, contacts, trusted peer keys, mesh groups & settings) for cross-device migration.
     */
    suspend fun publishAccountBackup(
        username: String,
        encryptedBackup: String,
        authToken: String,
        authVerifierHash: String,
        publicKeyBase64: String,
        allowDirectLookup: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        if (!allowDirectLookup && !isInternetEnabled()) return@withContext false
        val norm = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.normalizeUsername(username)
        if (norm.length < 3 || !encryptedBackup.startsWith("PMB1:")) return@withContext false

        val customBase = activeUsernameRelayBaseUrl()
        if (customBase.isNotEmpty()) {
            return@withContext try {
                val url = URL("$customBase/v1/users/$norm/backup")
                withRelayConnectionFallback(url) { conn ->
                    conn.apply {
                        requestMethod = "PUT"
                        doOutput = true
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        if (authToken.isNotBlank()) {
                            setRequestProperty("X-PM-Auth", authToken)
                            setRequestProperty("X-PulseMesh-Auth", authToken)
                        }
                    }
                    val payload = JSONObject().put("backupBlob", encryptedBackup).toString()
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(payload)
                        writer.flush()
                    }
                    conn.responseCode in 200..299
                }
            } catch (_: Exception) {
                false
            }
        }

        val topic = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.deriveUsernameBackupTopic(
            norm,
            authVerifierHash,
            publicKeyBase64
        )
        val syncId = java.lang.Long.toHexString(System.currentTimeMillis())
        val chunks = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.splitBackupIntoChunks(encryptedBackup, syncId)
        if (chunks.isEmpty() || chunks.size > 48) return@withContext false

        for (chunk in chunks) {
            val ok = try {
                val url = URL("$RELAY_BASE_URL/$topic")
                withRelayConnectionFallback(url) { conn ->
                    conn.apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 7000
                        readTimeout = 7000
                        setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    }
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(chunk)
                        writer.flush()
                    }
                    conn.responseCode in 200..299
                }
            } catch (_: Exception) {
                false
            }
            if (!ok) return@withContext false
        }
        true
    }

    /**
     * Fetches the latest encrypted ("PMB1:") full-account backup snapshot for cross-device login.
     */
    suspend fun fetchAccountBackup(
        username: String,
        authToken: String,
        authVerifierHash: String,
        publicKeyBase64: String,
        allowDirectLookup: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        if (!allowDirectLookup && !isInternetEnabled()) return@withContext null
        val norm = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.normalizeUsername(username)
        if (norm.length < 3) return@withContext null

        val customBase = activeUsernameRelayBaseUrl()
        if (customBase.isNotEmpty()) {
            return@withContext try {
                val url = URL("$customBase/v1/users/$norm/backup")
                withRelayConnectionFallback(url) { conn ->
                    conn.apply {
                        requestMethod = "GET"
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("Accept", "application/json")
                        if (authToken.isNotBlank()) {
                            setRequestProperty("X-PM-Auth", authToken)
                            setRequestProperty("X-PulseMesh-Auth", authToken)
                        }
                    }
                    if (conn.responseCode !in 200..299) return@withRelayConnectionFallback null
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val blob = JSONObject(body).optString("backupBlob", "").trim()
                    blob.takeIf { it.startsWith("PMB1:") }
                }
            } catch (_: Exception) {
                null
            }
        }

        val topic = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.deriveUsernameBackupTopic(
            norm,
            authVerifierHash,
            publicKeyBase64
        )
        try {
            val url = URL("$RELAY_BASE_URL/$topic/json?poll=1&since=all")
            withRelayConnectionFallback(url) { conn ->
                conn.apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/x-ndjson")
                }
                if (conn.responseCode !in 200..299) return@withRelayConnectionFallback null
                val messages = mutableListOf<String>()
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String? = null
                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line?.trim().orEmpty()
                        if (trimmed.isEmpty()) continue
                        runCatching {
                            val outer = JSONObject(trimmed)
                            if (outer.optString("event") != "message") return@runCatching
                            val msg = outer.optString("message", "").trim()
                            if (msg.isNotEmpty()) messages.add(msg)
                        }
                    }
                }
                com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.reassembleBackupChunks(messages)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Sends a direct packet to a distant friend's personal @username inbox.
     * Uses Ephemeral-Static ECDH + AES-256-GCM ("PMU2:") strictly (fails closed without valid peer EC public key).
     */
    fun sendDirectPacketToUsername(
        recipientUsername: String,
        packet: WireMeshPacket,
        allowDirectSend: Boolean = false
    ) {
        if (!allowDirectSend && !isInternetEnabled()) return
        val normRecipient = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.normalizeUsername(recipientUsername)
        if (normRecipient.length < 3) return
        seenPacketIds[packet.packetId] = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            if (!allowDirectSend && !isInternetEnabled()) return@launch
            try {
                val myUname = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.normalizeUsername(localUsername())
                val json = JSONObject().apply {
                    put("packetId", packet.packetId)
                    put("srcNodeId", packet.srcNodeId)
                    put("srcCallsign", packet.srcCallsign)
                    put("dstNodeId", packet.dstNodeId)
                    put("type", packet.type)
                    put("payload", packet.payload)
                    put("ttl", packet.ttl)
                    put("hops", packet.hops)
                    if (packet.senderPubKey.isNotBlank()) put("pk", packet.senderPubKey)
                    if (myUname.isNotBlank()) put("su", myUname) else if (packet.srcUsername.isNotBlank()) put("su", packet.srcUsername)
                    put("du", normRecipient)
                    put("timestamp", packet.timestamp)
                }
                val recipientPubKey = resolvePeerPublicKeyByUsername(normRecipient)
                val encryptedEnvelope = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.encryptUsernameInboxEnvelope(
                    json.toString(),
                    recipientPubKey,
                    normRecipient
                )
                if (encryptedEnvelope.isBlank()) return@launch
                val customBase = activeUsernameRelayBaseUrl()
                val primaryUrl = if (customBase.isNotEmpty()) {
                    URL("$customBase/v1/inbox/$normRecipient")
                } else {
                    val topic = deriveEncryptedInboxFallbackTopic(normRecipient)
                    URL("$RELAY_BASE_URL/$topic")
                }
                val primaryCode = runCatching {
                    withRelayConnectionFallback(primaryUrl) { conn ->
                        conn.apply {
                            requestMethod = "POST"
                            doOutput = true
                            connectTimeout = 8000
                            readTimeout = 8000
                            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                            setRequestProperty("Cache-Control", "no-cache")
                        }
                        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                            writer.write(encryptedEnvelope)
                            writer.flush()
                        }
                        conn.responseCode
                    }
                }.getOrDefault(-1)

                // If the custom Cloudflare Worker relay failed or timed out, automatically fail over to encrypted ntfy.sh inbox
                if (customBase.isNotEmpty() && primaryCode !in 200..299) {
                    val fallbackTopic = deriveEncryptedInboxFallbackTopic(normRecipient)
                    val fallbackUrl = URL("$RELAY_BASE_URL/$fallbackTopic")
                    runCatching {
                        withRelayConnectionFallback(fallbackUrl) { conn ->
                            conn.apply {
                                requestMethod = "POST"
                                doOutput = true
                                connectTimeout = 8000
                                readTimeout = 8000
                                setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                                setRequestProperty("Cache-Control", "no-cache")
                            }
                            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                                writer.write(encryptedEnvelope)
                                writer.flush()
                            }
                            conn.responseCode
                        }
                    }
                }
            } catch (_: Exception) {
                // Silent catch for offline environments
            }
        }
    }

    private fun startUsernameInboxListener(myUsername: String) {
        val customBase = activeUsernameRelayBaseUrl()
        if (customBase.isNotEmpty()) {
            realtimeClient.startOrReconnect(room = "inbox:$myUsername")
            usernameInboxJob = scope.launch(Dispatchers.IO) {
                while (isActive && isRunning) {
                    if (!isInternetEnabled()) break
                    pollCustomRelayInboxOnce(customBase, myUsername)
                    if (isActive && isRunning && isInternetEnabled()) {
                        // When realtime WebSocket is connected, inbox wakeup notifications arrive instantaneously
                        delay(if (realtimeClient.isConnected.value) 25_000 else 3_500)
                    }
                }
            }
            return
        }

        val topic = deriveEncryptedInboxFallbackTopic(myUsername)
        usernameInboxJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {
                if (!isInternetEnabled()) break
                var conn: HttpURLConnection? = null
                try {
                    val streamUrl = URL("$RELAY_BASE_URL/$topic/json?since=12h")
                    conn = openBoundRelayConnection(streamUrl).apply {
                        requestMethod = "GET"
                        connectTimeout = 10_000
                        readTimeout = 65_000
                        setRequestProperty("Accept", "application/x-ndjson")
                        setRequestProperty("Cache-Control", "no-cache")
                    }
                    activeInboxHttpConnection = conn
                    if (conn.responseCode in 200..299 && isInternetEnabled()) {
                        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                            var line: String? = null
                            while (isActive && isRunning && isInternetEnabled() && reader.readLine().also { line = it } != null) {
                                val rawLine = line?.trim() ?: continue
                                if (rawLine.isNotEmpty()) {
                                    parseIncomingUsernameInboxLine(rawLine, myUsername)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { conn?.disconnect() }
                    if (activeInboxHttpConnection === conn) {
                        activeInboxHttpConnection = null
                    }
                }
                if (isActive && isRunning && isInternetEnabled()) {
                    delay(3500)
                }
            }
        }
    }

    suspend fun pollCustomRelayInboxOnce(customBase: String, myUsername: String) = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val tok = myAuthToken().trim()
            if (tok.isBlank()) return@withContext
            val afterCursor = getInboxCursor()
            val url = URL("$customBase/v1/inbox/$myUsername?after=$afterCursor")
            conn = openBoundRelayConnection(url).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-PulseMesh-Auth", tok)
                setRequestProperty("X-PM-Auth", tok)
            }
            activeInboxHttpConnection = conn
            if (conn.responseCode !in 200..299) return@withContext
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            val messages = json.optJSONArray("messages") ?: return@withContext
            var maxId = afterCursor
            for (i in 0 until messages.length()) {
                val item = messages.optJSONObject(i) ?: continue
                val id = item.optLong("id", 0L)
                val envelope = item.optString("envelope", "")
                if (envelope.isNotBlank()) {
                    processDecryptedUsernameEnvelope(envelope, myUsername)
                }
                if (id > maxId) maxId = id
            }
            if (maxId > afterCursor) {
                setInboxCursor(maxId)
                ackCustomRelayInbox(customBase, myUsername, maxId)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { conn?.disconnect() }
            if (activeInboxHttpConnection === conn) {
                activeInboxHttpConnection = null
            }
        }
    }

    private fun ackCustomRelayInbox(customBase: String, myUsername: String, upToId: Long) {
        val priv = myPrivateKeyBase64()
        val tok = myAuthToken().trim()
        val sig = if (priv.isNotBlank()) {
            com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.signMessageWithEcKey(priv, "ACK|$myUsername|$upToId")
        } else ""
        if (sig.isBlank() && tok.isBlank()) return
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$customBase/v1/inbox/$myUsername/ack")
            conn = openBoundRelayConnection(url).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (tok.isNotBlank()) {
                    setRequestProperty("X-PulseMesh-Auth", tok)
                    setRequestProperty("X-PM-Auth", tok)
                }
            }
            val payload = JSONObject().apply {
                put("upToId", upToId)
                put("sig", sig)
            }.toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(payload)
                it.flush()
            }
            conn.responseCode
        } catch (_: Exception) {
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private suspend fun parseIncomingUsernameInboxLine(rawLine: String, myUsername: String) {
        if (!isInternetEnabled()) return
        try {
            val outer = JSONObject(rawLine)
            if (outer.optString("event") != "message") return
            val rawMessageStr = outer.optString("message", "")
            if (rawMessageStr.isBlank()) return
            processDecryptedUsernameEnvelope(rawMessageStr, myUsername)
        } catch (_: Exception) {
        }
    }

    private suspend fun processDecryptedUsernameEnvelope(rawEnvelope: String, myUsername: String) {
        if (!isInternetEnabled()) return
        try {
            val messageStr = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.decryptUsernameInboxEnvelope(
                rawEnvelope,
                myPrivateKeyBase64(),
                myUsername
            ) ?: return
            if (!messageStr.startsWith("{")) return

            val inner = JSONObject(messageStr)
            val packetId = inner.optString("id").ifBlank { inner.optString("packetId") }
            val srcNodeId = inner.optString("srcId")
                .ifBlank { inner.optString("srcNodeId") }
                .ifBlank { inner.optString("src") }
            if (packetId.isBlank() || srcNodeId.isBlank()) return
            if (OffGridBleMeshEngine.normalizeId(srcNodeId) == OffGridBleMeshEngine.normalizeId(localNodeId())) return
            if (seenPacketIds.putIfAbsent(packetId, System.currentTimeMillis()) != null) return

            val srcCallsign = inner.optString("srcName")
                .ifBlank { inner.optString("srcCallsign") }
                .ifBlank { inner.optString("su", "Peer") }
            if (OffGridBleMeshEngine.isBlockedFakeCallsign(srcCallsign)) return

            val type = inner.optString("type").ifBlank { inner.optString("t", "E2EE_DM") }
            val payloadStr = inner.optString("payload").ifBlank { inner.optString("p", "") }
            val dstId = inner.optString("dstId")
                .ifBlank { inner.optString("dstNodeId") }
                .ifBlank { localNodeId() }

            val packet = WireMeshPacket(
                packetId = packetId,
                srcNodeId = srcNodeId,
                srcCallsign = srcCallsign,
                dstNodeId = dstId,
                type = type,
                payload = payloadStr,
                ttl = inner.optInt("ttl", 7),
                hops = inner.optInt("hops", 0),
                timestamp = inner.optLong("timestamp", inner.optLong("ts", System.currentTimeMillis())),
                sourceTransport = "HYBRID USERNAME",
                rssiDbm = null,
                senderPubKey = inner.optString("pk", "").ifBlank { inner.optString("senderPubKey", "") },
                srcUsername = inner.optString("su", ""),
                dstUsername = inner.optString("du", myUsername)
            )
            _incomingPackets.emit(packet)
        } catch (_: Exception) {
        }
    }
}
