// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.os.PatternMatcher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class HotspotPromptInfo(
    val hostNodeId: String,
    val ssid: String,
    val passphrase: String,
    val hostIp: String
)

data class AutoHotspotStatus(
    val role: String = "IDLE",
    val isConnected: Boolean = false,
    val isHotspotHost: Boolean = false,
    val ssid: String = "",
    val passphrase: String = "",
    val hostIp: String = "",
    val peerIp: String = "",
    val connectedSocketCount: Int = 0,
    val buttonStateText: String = "HOTSPOT: OFF",
    val statusMessage: String = "Swarm Hotspot Ready (UDP 45888 / TCP 45889 / NSD)",
    val pendingHotspotPrompt: HotspotPromptInfo? = null
)

/**
 * Universal Multi-Device (N-Node) Swarm Hotspot Election, Star+Broadcast Hub Forwarding,
 * Dynamic Gateway/Subnet Discovery, and Android NsdManager Auto-Discovery Engine.
 */
class AutoHotspotNegotiator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val localNodeId: () -> String,
    private val onSendBleGattPacket: (String) -> Unit,
    private val onPacketReceived: (String) -> Unit,
    private val localCallsign: () -> String = { "PulseMesh" },
    private val getCurrentLocation: () -> Pair<Double?, Double?> = { Pair(null, null) },
    private val getBatteryPercent: () -> Int = { 100 },
    private val isRootedProvider: () -> Boolean = { false },
    private val getLocationShareMode: () -> com.example.model.LocationShareMode = { com.example.model.LocationShareMode.EXACT },
    var onPeerIpDiscovered: (nodeId: String, ipAddress: String) -> Unit = { _, _ -> }
) {
    companion object {
        const val P2P_UDP_PORT = 45888
        const val P2P_TCP_PORT = 45889
        const val P2P_GO_IP = "192.168.49.1"
        const val LOCAL_HOTSPOT_IP = "192.168.43.1"
        const val NSD_SERVICE_TYPE = "_pulsemesh._udp."
        const val WIFI_AWARE_SERVICE_NAME = "pulsemesh_nan"
        const val IPV6_LINK_LOCAL_MULTICAST = "ff02::1"
        const val IPV4_MESH_MULTICAST_GROUP = "239.255.77.88"
        private const val HUB_DEDUP_LIMIT = 4000

        val MESH_INTERFACE_PREFIXES = listOf(
            "wlan0", "ap0", "swlan0", "p2p", "usb0", "rndis0", "ncm0", "eth0"
        )

        fun isTargetMeshInterfaceName(ifaceName: String): Boolean {
            val lower = ifaceName.lowercase()
            return MESH_INTERFACE_PREFIXES.any { lower.startsWith(it) }
        }

        fun isUsbOrEthernetInterfaceName(ifaceName: String): Boolean {
            val lower = ifaceName.lowercase()
            return lower.startsWith("usb") ||
                lower.startsWith("rndis") ||
                lower.startsWith("ncm") ||
                lower.startsWith("eth")
        }

        fun isIsolatedHotspotSubnet(ip: String): Boolean {
            if (ip.startsWith("192.168.") || ip.startsWith("10.")) return true
            if (ip.startsWith("172.")) {
                val secondOctet = ip.substringAfter("172.").substringBefore('.').toIntOrNull() ?: return false
                return secondOctet in 16..31
            }
            return false
        }

        fun intIpToString(ipInt: Int): String {
            if (ipInt == 0) return ""
            return String.format(
                java.util.Locale.US,
                "%d.%d.%d.%d",
                ipInt and 0xff,
                (ipInt shr 8) and 0xff,
                (ipInt shr 16) and 0xff,
                (ipInt shr 24) and 0xff
            )
        }

        /**
         * Universal Swarm Hotspot Election Rule:
         * - If ANY discovered peer is already hosting (`peerHostMap.values.any { it }`), return `false` (join that host!).
         * - If this device already joined a hotspot, return `false`.
         * - Only if ZERO peers are hosting, elect the single node with the highest normalized `nodeId`
         *   among `(discoveredPeerIds + peerHostMap.keys + localNodeId)`.
         */
        fun shouldElectLocalAsHost(
            localNodeId: String,
            discoveredPeerIds: Collection<String>,
            peerHostMap: Map<String, Boolean>,
            alreadyJoinedSsid: String = ""
        ): Boolean {
            if (alreadyJoinedSsid.isNotBlank()) return false
            if (peerHostMap.values.any { it }) return false

            val myNorm = OffGridBleMeshEngine.normalizeId(localNodeId).toIntOrNull(16) ?: return false
            if (myNorm == 0) return false

            var maxPeerNorm = 0
            for (peerId in discoveredPeerIds) {
                val pNorm = OffGridBleMeshEngine.normalizeId(peerId).toIntOrNull(16) ?: 0
                if (pNorm != myNorm && pNorm > maxPeerNorm) maxPeerNorm = pNorm
            }
            for (peerId in peerHostMap.keys) {
                val pNorm = OffGridBleMeshEngine.normalizeId(peerId).toIntOrNull(16) ?: 0
                if (pNorm != myNorm && pNorm > maxPeerNorm) maxPeerNorm = pNorm
            }
            return myNorm >= maxPeerNorm
        }
    }

    private val _status = MutableStateFlow(AutoHotspotStatus())
    val status: StateFlow<AutoHotspotStatus> = _status.asStateFlow()

    private val activeTcpSockets = CopyOnWriteArrayList<Socket>()
    private val activePeerIps = CopyOnWriteArrayList<String>()
    private val verifiedReachablePeerIps = CopyOnWriteArrayList<String>()

    @Volatile
    private var lastPeerUdpRxMs: Long = 0L
    val peerIpByNodeId = ConcurrentHashMap<String, String>()
    val peerIsHotspotHost = ConcurrentHashMap<String, Boolean>()
    val peerHotspotCredentials = ConcurrentHashMap<String, Triple<String, String, String>>()
    private val lastUnicastReplyMs = ConcurrentHashMap<String, Long>()
    private val lastGatewayProbeMs = ConcurrentHashMap<String, Long>()
    private val hubRelayRateMap = ConcurrentHashMap<String, MutableList<Long>>()
    private val hubForwardedPacketIds: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    private var tcpServerSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var multicastSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiHighPerfLock: WifiManager.WifiLock? = null

    private var tcpServerJob: Job? = null
    private var udpListenerJob: Job? = null
    private var multicastListenerJob: Job? = null
    private val socketListenerDispatcher = java.util.concurrent.Executors
        .newCachedThreadPool { r -> Thread(r, "PulseMesh-HotspotSocket").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private var p2pGroupKeepaliveJob: Job? = null
    private var gatewayDiscoveryLoopJob: Job? = null
    private var hotspotAutoJoinBeaconJob: Job? = null

    private val wifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private val wifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }
    private val nsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }
    private val wifiAwareManager by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        } else null
    }
    private var wifiP2pChannel: WifiP2pManager.Channel? = null

    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null

    private var wifiAwareSession: WifiAwareSession? = null
    private var wifiAwarePublishSession: PublishDiscoverySession? = null
    private var wifiAwareSubscribeSession: SubscribeDiscoverySession? = null
    private val wifiAwarePeers = CopyOnWriteArrayList<PeerHandle>()

    @Volatile
    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var isSpecifierRequestInFlight: Boolean = false

    @Volatile
    private var inFlightPassphrase: String = ""

    @Volatile
    private var inFlightRequestStartedMs: Long = 0L

    @Volatile
    private var activeWifiMeshNetwork: Network? = null

    @Volatile
    private var joinedHotspotSsid: String = ""

    private val lastIpTrafficMsByPeerIp = ConcurrentHashMap<String, Long>()
    var onFallbackToLocalHotspotCallback: (() -> Unit)? = null

    var onAudioFrameReceived: ((ByteArray) -> Unit)? = null
    var onTcpOrIpPeerReady: ((nodeId: String, ipAddress: String) -> Unit)? = null
    var onPacketReceivedWithMeta: ((jsonLine: String, sourceTransport: String, realRssi: Int?) -> Unit)? = null
    var onHotspotButtonStateChanged: ((String) -> Unit)? = null
    var onImmediatePeerRegistered: ((
        nodeId: String,
        callsign: String,
        ipAddress: String,
        lat: Double?,
        lng: Double?,
        battery: Int?,
        transport: String
    ) -> Unit)? = null

    @Volatile
    var isWifiAwareEnabled: Boolean = false
        private set

    @Volatile
    var isAutoHotspotPopupEnabled: Boolean = true
        private set

    private val dismissedJoinKeys: MutableSet<String> =
        Collections.synchronizedSet(LinkedHashSet())

    fun setAutoHotspotPopupEnabled(enabled: Boolean) {
        isAutoHotspotPopupEnabled = enabled
        if (!enabled) {
            activeNetworkCallback?.let { oldCb ->
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                runCatching { cm?.unregisterNetworkCallback(oldCb) }
            }
            activeNetworkCallback = null
            isSpecifierRequestInFlight = false
            inFlightPassphrase = ""
            if (!hasHighSpeedLink()) {
                joinedHotspotSsid = ""
                _status.update {
                    it.copy(
                        role = if (it.role == "P2P_CLIENT_CONNECTING") "IDLE" else it.role,
                        buttonStateText = if (it.isHotspotHost) it.buttonStateText else "HOTSPOT: OFF",
                        statusMessage = "Hotspot Popups Disabled (Pure BLE / Manual Wi-Fi Mode)",
                        pendingHotspotPrompt = null
                    )
                }
                onHotspotButtonStateChanged?.invoke(_status.value.buttonStateText)
            } else {
                _status.update { it.copy(pendingHotspotPrompt = null) }
            }
        }
    }

    fun dismissHotspotPrompt() {
        val currentPrompt = _status.value.pendingHotspotPrompt
        if (currentPrompt != null) {
            val joinKey = "${currentPrompt.ssid.ifBlank { "DIRECT-*" }}:${currentPrompt.passphrase}"
            dismissedJoinKeys.add(joinKey)
            if (currentPrompt.passphrase.isNotBlank()) {
                dismissedJoinKeys.add("*:${currentPrompt.passphrase}")
            }
        }
        if (!hasHighSpeedLink() && !isSpecifierRequestInFlight) {
            joinedHotspotSsid = ""
            _status.update {
                it.copy(
                    role = if (it.role == "P2P_CLIENT_CONNECTING") "IDLE" else it.role,
                    buttonStateText = if (it.isHotspotHost) it.buttonStateText else "HOTSPOT: OFF",
                    statusMessage = "Hotspot Popup Dismissed",
                    pendingHotspotPrompt = null
                )
            }
            onHotspotButtonStateChanged?.invoke(_status.value.buttonStateText)
        } else {
            _status.update { it.copy(pendingHotspotPrompt = null) }
        }
    }

    fun confirmHotspotPromptJoin() {
        val prompt = _status.value.pendingHotspotPrompt ?: return
        _status.update { it.copy(pendingHotspotPrompt = null) }
        // Launch manual join after the Compose AlertDialog closes so Android's system
        // WifiNetworkSpecifier device chooser is never cancelled by window-focus or duplicate request transitions.
        handleHotspotAutoJoin(
            hostNodeId = prompt.hostNodeId,
            ssid = prompt.ssid,
            passphrase = prompt.passphrase,
            hostIp = prompt.hostIp,
            isManualJoin = true
        )
    }

    @Suppress("DEPRECATION")
    fun getRealWifiRssiDbm(): Int? {
        return runCatching {
            val rssi = wifiManager?.connectionInfo?.rssi
            if (rssi != null && rssi in -100..-15) rssi else null
        }.getOrNull()
    }

    private fun updateButtonState(text: String) {
        _status.update { it.copy(buttonStateText = text) }
        onHotspotButtonStateChanged?.invoke(text)
    }

    val myLocalIp: String
        get() = getBestLocalIpv4Address()

    fun isCurrentlyHotspotHost(): Boolean {
        val st = _status.value
        return st.isHotspotHost || st.role == "HOST_AP" || st.role == "P2P_GO"
    }

    fun registerPeerIp(peerNodeId: String, ipAddress: String) {
        if (ipAddress.isBlank() || ipAddress == "127.0.0.1") return
        val localIps = getLocalIpv4Addresses()
        if (localIps.contains(ipAddress)) return

        val isNewPeerIp = !activePeerIps.contains(ipAddress)
        if (isNewPeerIp) {
            activePeerIps.add(ipAddress)
        }
        if (peerNodeId.isNotBlank()) {
            val normId = OffGridBleMeshEngine.normalizeId(peerNodeId)
            val prevIp = peerIpByNodeId[normId]
            peerIpByNodeId[normId] = ipAddress
            peerIpByNodeId[peerNodeId] = ipAddress
            onPeerIpDiscovered(peerNodeId, ipAddress)
            if (isNewPeerIp || prevIp != ipAddress) {
                onTcpOrIpPeerReady?.invoke(peerNodeId, ipAddress)
            }
        } else if (isNewPeerIp) {
            onTcpOrIpPeerReady?.invoke("", ipAddress)
        }
        _status.update {
            val nextBtn = if (it.isHotspotHost && it.ssid.isNotBlank()) {
                "HOTSPOT: HOSTING (${it.ssid})"
            } else {
                "HOTSPOT: LINKED"
            }
            it.copy(
                isConnected = true,
                peerIp = ipAddress,
                connectedSocketCount = maxOf(activeTcpSockets.size, activePeerIps.size),
                buttonStateText = nextBtn,
                statusMessage = "Swarm Linked (${activePeerIps.size} IP peers • $ipAddress)"
            )
        }
        onHotspotButtonStateChanged?.invoke(_status.value.buttonStateText)
    }

    var onLocalHotspotCredentialsUpdated: ((ssid: String, passphrase: String) -> Unit)? = null
    private var p2pDnsSdDiscoveryJob: Job? = null

    init {
        startPassiveSubnetSockets()
        startGatewayDiscoveryLoop()
        startNsdMeshService()
        startWifiP2pDnsSdAutoDiscovery()
        // Keep WifiAwareManager OFF by default so it never resets Wi-Fi Direct or Personal Hotspot interfaces
    }

    fun setWifiAwareEnabled(enabled: Boolean) {
        isWifiAwareEnabled = enabled
        if (enabled) {
            startWifiAwareNanSession()
        } else {
            stopWifiAwareNanSession()
        }
    }

    private fun stopWifiAwareNanSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { wifiAwarePublishSession?.close() }
            runCatching { wifiAwareSubscribeSession?.close() }
            runCatching { wifiAwareSession?.close() }
        }
        wifiAwarePublishSession = null
        wifiAwareSubscribeSession = null
        wifiAwareSession = null
        wifiAwarePeers.clear()
    }

    fun hasHighSpeedLink(): Boolean {
        return activeTcpSockets.any { it.isConnected && !it.isClosed } || verifiedReachablePeerIps.isNotEmpty()
    }

    fun hasActiveUdpTraffic(withinMs: Long = 8_000L): Boolean {
        val last = lastPeerUdpRxMs
        return last > 0L && (System.currentTimeMillis() - last) < withinMs
    }

    fun hasActiveVoiceDeliveryPath(): Boolean {
        return activeTcpSockets.any { it.isConnected && !it.isClosed } || hasActiveUdpTraffic(8_000L)
    }

    fun markPeerIpVerifiedReachable(ipAddress: String) {
        if (ipAddress.isBlank() || ipAddress == "127.0.0.1") return
        if (getLocalIpv4Addresses().contains(ipAddress)) return
        lastIpTrafficMsByPeerIp[ipAddress] = System.currentTimeMillis()
        if (!verifiedReachablePeerIps.contains(ipAddress)) {
            verifiedReachablePeerIps.add(ipAddress)
        }
    }

    private fun pruneStaleReachablePeerIps(nowMs: Long = System.currentTimeMillis()) {
        val staleIps = mutableListOf<String>()
        for (ip in verifiedReachablePeerIps) {
            val lastHeard = lastIpTrafficMsByPeerIp[ip] ?: 0L
            val hasOpenTcp = activeTcpSockets.any {
                it.inetAddress?.hostAddress == ip && it.isConnected && !it.isClosed
            }
            // Keep peer IPs alive while an active WifiNetworkSpecifier mesh network or open TCP socket is present;
            // only prune after 40s of complete silence.
            if (!hasOpenTcp && activeWifiMeshNetwork == null && lastHeard > 0L && (nowMs - lastHeard) > 40_000L) {
                staleIps.add(ip)
            }
        }
        if (staleIps.isNotEmpty()) {
            verifiedReachablePeerIps.removeAll(staleIps.toSet())
            activePeerIps.removeAll(staleIps.toSet())
        }
    }

    fun onPeerDiscovered(
        peerId: String,
        peerIsHost: Boolean = false,
        allKnownPeerIds: Collection<String> = emptyList()
    ) {
        val normPeer = OffGridBleMeshEngine.normalizeId(peerId)
        val normSelf = OffGridBleMeshEngine.normalizeId(localNodeId())
        if (normPeer.isBlank() || normSelf.isBlank() || normPeer == normSelf) return

        peerIsHotspotHost[normPeer] = peerIsHost

        if (peerIsHost) {
            // Another node is already hosting the swarm cluster: do not launch a competing group
            return
        }

        // Only trigger automatic WifiP2pManager.createGroup() AFTER a peer is discovered over BLE
        // AND neither phone has a shared UDP/TCP IP connection yet (and auto-hotspot popups are enabled).
        if (isAutoHotspotPopupEnabled && !hasHighSpeedLink() && !isCurrentlyHotspotHost() && shouldElectLocalAsHost(
                localNodeId = localNodeId(),
                discoveredPeerIds = allKnownPeerIds + normPeer,
                peerHostMap = peerIsHotspotHost,
                alreadyJoinedSsid = joinedHotspotSsid
            )
        ) {
            startHostP2pGroup(manualTrigger = false)
        }
    }

    fun triggerAutoConnectHotspot(
        peerNodeId: String? = null,
        knownPeerIds: Collection<String> = emptyList()
    ) {
        startPassiveSubnetSockets()
        probePersonalHotspotGatewaysNow()
        startNsdMeshService()

        val combinedPeers = (knownPeerIds + listOfNotNull(peerNodeId)).filter { it.isNotBlank() }
        val anyPeerIsHost = peerIsHotspotHost.values.any { it }

        if (!anyPeerIsHost && joinedHotspotSsid.isBlank() && !isCurrentlyHotspotHost() &&
            (combinedPeers.isEmpty() || shouldElectLocalAsHost(
                localNodeId = localNodeId(),
                discoveredPeerIds = combinedPeers,
                peerHostMap = peerIsHotspotHost,
                alreadyJoinedSsid = joinedHotspotSsid
            ))
        ) {
            startHostP2pGroup(manualTrigger = true)
        } else {
            broadcastUdpHeartbeatNow()
        }
    }

    /**
     * Explicit Separate Control 1: Host Hotspot Explicitly (`hostHotspotExplicitly`)
     * Starts or refreshes our local Wi-Fi Direct P2P Group (with automatic fallback to LocalOnlyHotspot),
     * advertises credentials via Wi-Fi P2P DNS-SD + BLE 0xCAFF + BLE GATT, and sets state to `"HOTSPOT: HOSTING (...)"`.
     */
    @SuppressLint("MissingPermission")
    fun hostHotspotExplicitly(onFallbackToLocalHotspot: () -> Unit = {}): String {
        updateButtonState("HOTSPOT: STARTING...")
        startHostP2pGroup(manualTrigger = true, onFallbackToLocalHotspot = onFallbackToLocalHotspot)
        return _status.value.buttonStateText
    }

    /**
     * Explicit Separate Control 2: Join Peer Hotspot Explicitly (`joinPeerHotspotExplicitly`)
     * Connects to any discovered peer's hotspot (using exact SSID or `DIRECT-*` prefix + passphrase),
     * triggers Wi-Fi P2P DNS-SD discovery and subnet gateway probing, and binds sockets.
     */
    @SuppressLint("MissingPermission")
    fun joinPeerHotspotExplicitly(
        discoveredPeerHotspot: Triple<String, String, String>? = null,
        peerHostIpHint: String = P2P_GO_IP
    ): String {
        startPassiveSubnetSockets()
        probePersonalHotspotGatewaysNow()
        startNsdMeshService()
        startWifiP2pDnsSdAutoDiscovery()

        val peerCreds = discoveredPeerHotspot?.takeIf { it.third.isNotBlank() }
            ?: peerHotspotCredentials.entries.firstOrNull { it.value.second.isNotBlank() }?.let {
                Triple(it.key, it.value.first, it.value.second)
            }

        if (peerCreds != null) {
            val (peerId, ssid, pass) = peerCreds
            val hostIp = peerHotspotCredentials[OffGridBleMeshEngine.normalizeId(peerId)]?.third
                ?.ifBlank { peerHostIpHint.ifBlank { P2P_GO_IP } }
                ?: peerHostIpHint.ifBlank { P2P_GO_IP }
            updateButtonState("HOTSPOT: STARTING...")
            handleHotspotAutoJoin(peerId, ssid, pass, hostIp, isManualJoin = true)
            val resultState = if (hasHighSpeedLink()) "HOTSPOT: LINKED" else _status.value.buttonStateText
            return resultState
        }

        // Even if BLE credentials haven't arrived yet, probe active gateways and P2P DNS-SD
        broadcastUdpHeartbeatNow()
        val nextState = if (hasHighSpeedLink()) "HOTSPOT: LINKED" else "HOTSPOT: SCANNING PEERS..."
        updateButtonState(nextState)
        return nextState
    }

    /**
     * Explicit Separate Control 3: Stop Hotspot Explicitly (`stopHotspotExplicitly`)
     */
    fun stopHotspotExplicitly(onStopLocalHotspot: () -> Unit = {}): String {
        onStopLocalHotspot()
        stopHotspotGroup()
        return "HOTSPOT: OFF"
    }

    /**
     * Smart Hotspot Action (`toggleOrConnectHotspot`) preserved for compatibility:
     * - Case 3 (Already hosting or linked): stops the group and resets to `"HOTSPOT: OFF"`.
     * - Case 1 (A discovered peer is broadcasting `hotspotSsid` / `hotspotPass`): immediately connects to that peer's hotspot.
     * - Case 2 (No peer hotspot is active yet): creates a Wi-Fi Direct group (with fallback to `onStartLocalHotspotFallback()`).
     */
    @SuppressLint("MissingPermission")
    fun toggleOrConnectHotspot(
        discoveredPeerHotspot: Triple<String, String, String>? = null,
        peerHostIpHint: String = P2P_GO_IP,
        onStartLocalHotspotFallback: () -> Unit = {},
        onStopLocalHotspot: () -> Unit = {}
    ): String {
        val current = _status.value
        val isHostingOrLinked = current.isHotspotHost ||
            current.buttonStateText.startsWith("HOTSPOT: HOSTING") ||
            current.buttonStateText == "HOTSPOT: LINKED"

        if (isHostingOrLinked) {
            return stopHotspotExplicitly(onStopLocalHotspot)
        }

        val peerCreds = discoveredPeerHotspot?.takeIf { it.third.isNotBlank() }
            ?: peerHotspotCredentials.entries.firstOrNull { it.value.second.isNotBlank() }?.let {
                Triple(it.key, it.value.first, it.value.second)
            }

        if (peerCreds != null) {
            return joinPeerHotspotExplicitly(peerCreds, peerHostIpHint)
        }

        return hostHotspotExplicitly(onStartLocalHotspotFallback)
    }

    @SuppressLint("MissingPermission")
    fun startHostP2pGroup(
        manualTrigger: Boolean = false,
        onFallbackToLocalHotspot: () -> Unit = {}
    ) {
        if (!manualTrigger && peerIsHotspotHost.values.any { it }) {
            return
        }
        startPassiveSubnetSockets()
        var p2pAttempted = false
        runCatching {
            val mgr = wifiP2pManager
            val ch = if (mgr != null) {
                wifiP2pChannel ?: mgr.initialize(context, Looper.getMainLooper(), null).also {
                    wifiP2pChannel = it
                }
            } else null

            if (mgr == null || ch == null) {
                onFallbackToLocalHotspot()
                onFallbackToLocalHotspotCallback?.invoke()
                return@runCatching
            }
            p2pAttempted = true

            mgr.requestGroupInfo(ch) { existingGroup ->
                runCatching {
                    val actualIp = getBestHotspotHostIpv4Address("P2P_GO").ifBlank { P2P_GO_IP }
                    if (existingGroup != null && existingGroup.networkName.isNotBlank() && !existingGroup.passphrase.isNullOrBlank()) {
                        val ssid = existingGroup.networkName
                        val pass = existingGroup.passphrase.orEmpty()
                        onP2pGroupReady(ssid, pass, actualIp)
                    } else {
                        val createNewGroup = {
                            runCatching {
                                val actionListener = object : WifiP2pManager.ActionListener {
                                    override fun onSuccess() {
                                        pollP2pGroupInfo(mgr, ch, onFallbackToLocalHotspot)
                                    }

                                    override fun onFailure(reason: Int) {
                                        pollP2pGroupInfo(mgr, ch, onFallbackToLocalHotspot)
                                    }
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    val nodeShort = OffGridBleMeshEngine.normalizeId(localNodeId()).ifBlank { "4F2A" }
                                    val customConfig = runCatching {
                                        WifiP2pConfig.Builder()
                                            .setNetworkName("DIRECT-PM-$nodeShort")
                                            .setPassphrase("pulsemesh$nodeShort")
                                            .enablePersistentMode(true)
                                            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                                            .build()
                                    }.getOrNull()
                                    if (customConfig != null) {
                                        mgr.createGroup(ch, customConfig, object : WifiP2pManager.ActionListener {
                                            override fun onSuccess() {
                                                pollP2pGroupInfo(mgr, ch, onFallbackToLocalHotspot)
                                            }

                                            override fun onFailure(reason: Int) {
                                                runCatching { mgr.createGroup(ch, actionListener) }
                                            }
                                        })
                                    } else {
                                        mgr.createGroup(ch, actionListener)
                                    }
                                } else {
                                    mgr.createGroup(ch, actionListener)
                                }
                            }
                        }
                        if (existingGroup != null) {
                            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() { createNewGroup() }
                                override fun onFailure(reason: Int) { createNewGroup() }
                            })
                        } else {
                            createNewGroup()
                        }
                    }
                }
            }
        }
        if (!p2pAttempted) {
            onFallbackToLocalHotspot()
            onFallbackToLocalHotspotCallback?.invoke()
        }
        broadcastUdpHeartbeatNow()
    }

    @SuppressLint("MissingPermission")
    private fun pollP2pGroupInfo(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        onFallbackToLocalHotspot: () -> Unit = {}
    ) {
        scope.launch(Dispatchers.Main) {
            repeat(5) {
                delay(420L)
                runCatching {
                    mgr.requestGroupInfo(ch) { grp ->
                        if (grp != null && grp.networkName.isNotBlank() && grp.passphrase?.isNotBlank() == true) {
                            val detectedIp = getBestHotspotHostIpv4Address("P2P_GO").ifBlank { P2P_GO_IP }
                            onP2pGroupReady(grp.networkName, grp.passphrase, detectedIp)
                        }
                    }
                }
                if (_status.value.ssid.isNotBlank() && _status.value.passphrase.isNotBlank()) {
                    return@launch
                }
            }
            if (_status.value.ssid.isBlank()) {
                onFallbackToLocalHotspot()
                onFallbackToLocalHotspotCallback?.invoke()
            }
        }
    }

    fun onP2pGroupReady(ssid: String, passphrase: String, hostIp: String) {
        if (ssid.isBlank() || passphrase.isBlank()) return
        val effectiveHostIp = getBestHotspotHostIpv4Address("P2P_GO").ifBlank { hostIp.ifBlank { P2P_GO_IP } }
        val btnText = "HOTSPOT: HOSTING ($ssid)"
        _status.update {
            it.copy(
                role = "P2P_GO",
                isConnected = true,
                isHotspotHost = true,
                ssid = ssid,
                passphrase = passphrase,
                hostIp = effectiveHostIp,
                buttonStateText = btnText,
                statusMessage = "Hosting Swarm Hotspot ($ssid @ $effectiveHostIp)"
            )
        }
        onHotspotButtonStateChanged?.invoke(btnText)
        onLocalHotspotCredentialsUpdated?.invoke(ssid, passphrase)
        acquireWifiMulticastLockIfNeeded()
        registerWifiP2pDnsSdService(ssid, passphrase, effectiveHostIp)
        startHotspotInfoBleBeacon(ssid, passphrase, effectiveHostIp)
        startP2pGroupOwnerKeepaliveLoop()
    }

    @SuppressLint("MissingPermission")
    private fun startP2pGroupOwnerKeepaliveLoop() {
        p2pGroupKeepaliveJob?.cancel()
        p2pGroupKeepaliveJob = scope.launch(Dispatchers.Main) {
            while (isActive && isCurrentlyHotspotHost()) {
                delay(4500L)
                runCatching {
                    val mgr = wifiP2pManager
                    val ch = wifiP2pChannel
                    if (mgr != null && ch != null && _status.value.role == "P2P_GO") {
                        mgr.requestGroupInfo(ch) { grp ->
                            if (grp == null && isCurrentlyHotspotHost() && _status.value.role == "P2P_GO") {
                                // OEM supplicant timed out the autonomous P2P GO: immediately recreate or fall back to Local Hotspot
                                startHostP2pGroup(
                                    manualTrigger = true,
                                    onFallbackToLocalHotspot = { onFallbackToLocalHotspotCallback?.invoke() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startHotspotInfoBleBeacon(ssid: String, passphrase: String, hostIp: String) {
        hotspotAutoJoinBeaconJob?.cancel()
        hotspotAutoJoinBeaconJob = scope.launch(Dispatchers.IO) {
            while (isActive && _status.value.ssid == ssid) {
                val liveIp = getBestHotspotHostIpv4Address(_status.value.role).ifBlank { hostIp }
                val payloadJson = JSONObject().apply {
                    put("ssid", ssid)
                    put("passphrase", passphrase)
                    put("hostIp", liveIp)
                }.toString()
                val packetJson = JSONObject().apply {
                    val pktId = "hs_${localNodeId()}_${System.currentTimeMillis() and 0xFFFFF}"
                    put("id", pktId)
                    put("packetId", pktId)
                    put("type", "HOTSPOT_AUTO_JOIN")
                    put("t", "HOTSPOT_AUTO_JOIN")
                    put("srcId", localNodeId())
                    put("src", localNodeId())
                    put("srcName", localCallsign())
                    put("cs", localCallsign())
                    put("dstId", "ALL")
                    put("dst", "ALL")
                    put("ttl", 7)
                    put("hops", 0)
                    put("payload", payloadJson)
                    put("p", payloadJson)
                }.toString()
                onSendBleGattPacket(packetJson)
                delay(2500L)
            }
        }
    }

    fun onExternalLocalHotspotStarted(ssid: String, passphrase: String, hostIp: String = LOCAL_HOTSPOT_IP) {
        val detectedIp = getBestHotspotHostIpv4Address("HOST_AP").ifBlank { hostIp.ifBlank { LOCAL_HOTSPOT_IP } }
        val btnText = "HOTSPOT: HOSTING ($ssid)"
        _status.update {
            it.copy(
                role = "HOST_AP",
                isConnected = true,
                isHotspotHost = true,
                ssid = ssid,
                passphrase = passphrase,
                hostIp = detectedIp,
                buttonStateText = btnText,
                statusMessage = "Local Swarm Hotspot Active ($ssid @ $detectedIp)"
            )
        }
        onHotspotButtonStateChanged?.invoke(btnText)
        onLocalHotspotCredentialsUpdated?.invoke(ssid, passphrase)
        registerWifiP2pDnsSdService(ssid, passphrase, detectedIp)
        startPassiveSubnetSockets()
        startHotspotInfoBleBeacon(ssid, passphrase, detectedIp)
        broadcastUdpHeartbeatNow()
    }

    @SuppressLint("MissingPermission")
    private fun resolveBestSsidFromScanOrHint(ssidHint: String): String {
        val cleanHint = ssidHint.trim()
        // If we already have a full SSID (e.g. DIRECT-xx-... or AndroidShare_...), NEVER trigger a disruptive wifiManager.startScan()!
        if (cleanHint.isNotBlank() && !cleanHint.endsWith("*") && cleanHint.length >= 9) {
            return cleanHint
        }
        val prefixFilter = cleanHint.removeSuffix("*").trim()
        // Only trigger an active scan if we are NOT already connected to a hotspot AND no WifiNetworkSpecifier chooser is open!
        if (!hasHighSpeedLink() && activeWifiMeshNetwork == null && !isSpecifierRequestInFlight) {
            @Suppress("DEPRECATION")
            runCatching { wifiManager?.startScan() }
        }
        val scanned = runCatching {
            wifiManager?.scanResults
                ?.mapNotNull { it.SSID?.trim()?.removePrefix("\"")?.removeSuffix("\"") }
                ?.firstOrNull { candidate ->
                    if (prefixFilter.isNotBlank() && candidate.startsWith(prefixFilter, ignoreCase = true)) {
                        true
                    } else {
                        candidate.startsWith("DIRECT-") ||
                            candidate.startsWith("AndroidShare") ||
                            candidate.contains("PulseMesh", ignoreCase = true)
                    }
                }
        }.getOrNull()
        return scanned ?: prefixFilter
    }

    fun handleHotspotAutoJoin(
        hostNodeId: String,
        ssid: String,
        passphrase: String,
        hostIp: String,
        isManualJoin: Boolean = false
    ) {
        val normHost = OffGridBleMeshEngine.normalizeId(hostNodeId)
        val targetIp = hostIp.trim().ifBlank { P2P_GO_IP }
        val rawSsid = ssid.trim()
        val fastKey = "${rawSsid.removeSuffix("*").ifBlank { "DIRECT-*" }}:$passphrase"
        val nowMs = System.currentTimeMillis()

        // Clear stale in-flight lock if Android never called back after 35s
        if (isSpecifierRequestInFlight && (nowMs - inFlightRequestStartedMs) > 35_000L) {
            isSpecifierRequestInFlight = false
            inFlightPassphrase = ""
        }

        // Fast-path 1: if we already joined this hotspot and our Wi-Fi mesh network or socket is active,
        // NEVER re-scan or re-register NetworkCallback (which drops the link after ~10s)!
        if (!isManualJoin && joinedHotspotSsid.isNotBlank() &&
            (joinedHotspotSsid == fastKey || (passphrase.isNotBlank() && joinedHotspotSsid.endsWith(":$passphrase"))) &&
            (hasHighSpeedLink() || activeWifiMeshNetwork != null)
        ) {
            registerPeerIp(hostNodeId, targetIp)
            markPeerIpVerifiedReachable(targetIp)
            return
        }

        // Fast-path 2: if Android's WifiNetworkSpecifier chooser is CURRENTLY on screen waiting for the user
        // to tap the device, NEVER call unregisterNetworkCallback or launch a competing requestNetwork!
        // Doing so causes Android to display: "Something came up. The application has cancelled the request to choose a device."
        if (isSpecifierRequestInFlight && (inFlightPassphrase == passphrase || !isManualJoin)) {
            if (normHost.isNotBlank() && passphrase.isNotBlank()) {
                val updatedSsid = if (rawSsid.length >= 10 && !rawSsid.endsWith("*")) rawSsid else (peerHotspotCredentials[normHost]?.first ?: rawSsid)
                peerHotspotCredentials[normHost] = Triple(updatedSsid, passphrase, targetIp)
            }
            registerPeerIp(hostNodeId, targetIp)
            return
        }

        val resolvedSsid = resolveBestSsidFromScanOrHint(rawSsid)
        if (normHost.isNotBlank()) {
            peerIsHotspotHost[normHost] = true
            if (passphrase.isNotBlank()) {
                val prevSsid = peerHotspotCredentials[normHost]?.first.orEmpty()
                val bestSsid = if (resolvedSsid.length >= prevSsid.length) resolvedSsid else prevSsid
                peerHotspotCredentials[normHost] = Triple(bestSsid, passphrase, targetIp)
            }
        }
        registerPeerIp(hostNodeId, targetIp)

        if (!isManualJoin && !isAutoHotspotPopupEnabled) {
            return
        }

        if (isCurrentlyHotspotHost()) {
            val myInt = OffGridBleMeshEngine.normalizeId(localNodeId()).toIntOrNull(16) ?: 0
            val hostInt = normHost.toIntOrNull(16) ?: 0
            if (myInt > hostInt && _status.value.ssid.isNotBlank()) {
                return
            }
        }

        val joinKey = "${resolvedSsid.ifBlank { "DIRECT-*" }}:$passphrase"
        if (!isManualJoin && (dismissedJoinKeys.contains(joinKey) || dismissedJoinKeys.contains("*:$passphrase"))) {
            return
        }

        val displaySsid = (peerHotspotCredentials[normHost]?.first?.takeIf { it.isNotBlank() } ?: resolvedSsid).ifBlank { "DIRECT-*" }

        // Crucial UX & Android 10+ Specifier Fix:
        // When a background BLE beacon arrives (`isManualJoin == false`) on a non-rooted device:
        // - Show our in-app `pendingHotspotPrompt` ("SWARM HOTSPOT DETECTED" -> "CONNECT NOW" / "DISABLE HOTSPOT POPUPS")
        //   WITHOUT firing `cm.requestNetwork(WifiNetworkSpecifier)` at the exact same time!
        // - Firing `cm.requestNetwork` both when the popup appears AND when the user clicks "CONNECT NOW"
        //   (or when the next BLE packet arrives 1s later) cancels the first request and triggers:
        //   "Something came up. The application has cancelled the request to choose a device."
        if (!isManualJoin && !isRootedProvider()) {
            if (!hasHighSpeedLink() && activeWifiMeshNetwork == null && passphrase.isNotBlank()) {
                val existingPrompt = _status.value.pendingHotspotPrompt
                val updatedPrompt = HotspotPromptInfo(
                    hostNodeId = if (normHost.isNotBlank()) "0x$normHost" else hostNodeId,
                    ssid = if (existingPrompt != null && existingPrompt.ssid.length > displaySsid.length) existingPrompt.ssid else displaySsid,
                    passphrase = passphrase,
                    hostIp = targetIp
                )
                _status.update {
                    it.copy(
                        ssid = updatedPrompt.ssid,
                        passphrase = passphrase,
                        hostIp = targetIp,
                        pendingHotspotPrompt = updatedPrompt
                    )
                }
            }
            return
        }

        if (passphrase.isNotBlank() && (isManualJoin || joinedHotspotSsid != joinKey)) {
            joinedHotspotSsid = joinKey

            _status.update {
                it.copy(
                    role = "P2P_CLIENT_CONNECTING",
                    isHotspotHost = false,
                    ssid = displaySsid,
                    passphrase = passphrase,
                    hostIp = targetIp,
                    buttonStateText = if (hasHighSpeedLink()) "HOTSPOT: LINKED" else "HOTSPOT: STARTING...",
                    statusMessage = "Joining Swarm Hotspot $displaySsid ($targetIp)...",
                    pendingHotspotPrompt = null
                )
            }
            onHotspotButtonStateChanged?.invoke(_status.value.buttonStateText)

            if (isRootedProvider()) {
                scope.launch(Dispatchers.IO) {
                    var ssidToConnect = displaySsid.removeSuffix("*")
                    if (ssidToConnect.isBlank() || ssidToConnect.length < 10) {
                        delay(450L)
                        ssidToConnect = resolveBestSsidFromScanOrHint(ssid.trim())
                    }
                    if (ssidToConnect.isNotBlank()) {
                        runCatching {
                            val proc = Runtime.getRuntime().exec(
                                arrayOf("su", "-c", "cmd wifi connect-network \"$ssidToConnect\" wpa2 \"$passphrase\"")
                            )
                            proc.waitFor()
                        }
                        delay(700L)
                        startPassiveSubnetSockets()
                        connectGatewayTcp(targetIp)
                        broadcastUdpHeartbeatNow()
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isSpecifierRequestInFlight = true
                inFlightPassphrase = passphrase
                inFlightRequestStartedMs = System.currentTimeMillis()

                scope.launch(Dispatchers.Main) {
                    // Brief 180ms pause so any Compose AlertDialog dismissal finishes before Android's
                    // system companion chooser window attaches (prevents focus-change cancellation).
                    delay(180L)
                    runCatching {
                        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                            ?: run {
                                isSpecifierRequestInFlight = false
                                return@runCatching
                            }
                        activeNetworkCallback?.let { oldCb ->
                            runCatching { cm.unregisterNetworkCallback(oldCb) }
                        }

                        val latestCredSsid = peerHotspotCredentials[normHost]?.first
                            ?.takeIf { it.isNotBlank() && !it.endsWith("*") }
                            ?: resolvedSsid.removeSuffix("*").trim()

                        val specBuilder = WifiNetworkSpecifier.Builder()
                            .setWpa2Passphrase(passphrase)
                        when {
                            latestCredSsid.isNotBlank() && latestCredSsid.length >= 10 && !latestCredSsid.endsWith("-") -> {
                                specBuilder.setSsid(latestCredSsid)
                            }
                            latestCredSsid.isNotBlank() -> {
                                specBuilder.setSsidPattern(PatternMatcher(latestCredSsid, PatternMatcher.PATTERN_PREFIX))
                            }
                            targetIp.startsWith("192.168.43.") -> {
                                specBuilder.setSsidPattern(PatternMatcher("AndroidShare", PatternMatcher.PATTERN_PREFIX))
                            }
                            else -> {
                                specBuilder.setSsidPattern(PatternMatcher("DIRECT-", PatternMatcher.PATTERN_PREFIX))
                            }
                        }
                        val specifier = specBuilder.build()
                        val request = NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .setNetworkSpecifier(specifier)
                            .build()

                        val callback = object : ConnectivityManager.NetworkCallback() {
                            override fun onAvailable(network: Network) {
                                isSpecifierRequestInFlight = false
                                activeWifiMeshNetwork = network
                                acquireWifiMulticastLockIfNeeded()
                                runCatching { cm.bindProcessToNetwork(network) }
                                runCatching {
                                    udpSocket?.let { sock ->
                                        if (!sock.isClosed) network.bindSocket(sock)
                                    }
                                }
                                markPeerIpVerifiedReachable(targetIp)
                                _status.update {
                                    it.copy(
                                        role = "P2P_CLIENT",
                                        isConnected = true,
                                        isHotspotHost = false,
                                        ssid = displaySsid,
                                        peerIp = targetIp,
                                        buttonStateText = "HOTSPOT: LINKED",
                                        statusMessage = "Swarm Hotspot Connected ($displaySsid -> $targetIp)",
                                        pendingHotspotPrompt = null
                                    )
                                }
                                onHotspotButtonStateChanged?.invoke("HOTSPOT: LINKED")
                                startPassiveSubnetSockets()
                                startNsdMeshService()
                                // Multi-stage post-DHCP handshake burst (0ms, 350ms, 450ms, 700ms, 1100ms)
                                // so sockets connect reliably even if Android DHCP takes 300-800ms after onAvailable!
                                scope.launch(Dispatchers.IO) {
                                    val delays = longArrayOf(0L, 350L, 450L, 700L, 1100L)
                                    for (waitMs in delays) {
                                        if (waitMs > 0L) delay(waitMs)
                                        if (!isActive || activeWifiMeshNetwork !== network) break
                                        val dhcpGw = runCatching {
                                            intIpToString(wifiManager?.dhcpInfo?.gateway ?: 0)
                                        }.getOrDefault("")
                                        val candidateGateways = linkedSetOf(
                                            targetIp,
                                            dhcpGw,
                                            P2P_GO_IP,
                                            LOCAL_HOTSPOT_IP
                                        ).filter { it.isNotBlank() && it != "0.0.0.0" }
                                        for (gw in candidateGateways) {
                                            markPeerIpVerifiedReachable(gw)
                                            connectGatewayTcp(gw)
                                        }
                                        broadcastUdpHeartbeatNow()
                                    }
                                }
                            }

                            override fun onUnavailable() {
                                super.onUnavailable()
                                isSpecifierRequestInFlight = false
                                inFlightPassphrase = ""
                                if (activeWifiMeshNetwork != null) {
                                    activeWifiMeshNetwork = null
                                }
                                if (joinedHotspotSsid == joinKey) {
                                    joinedHotspotSsid = ""
                                }
                                _status.update {
                                    it.copy(
                                        role = if (it.role == "P2P_CLIENT_CONNECTING") "IDLE" else it.role,
                                        buttonStateText = if (it.isHotspotHost) it.buttonStateText else "HOTSPOT: OFF",
                                        pendingHotspotPrompt = null
                                    )
                                }
                            }

                            override fun onLost(network: Network) {
                                isSpecifierRequestInFlight = false
                                inFlightPassphrase = ""
                                if (activeWifiMeshNetwork == network) {
                                    activeWifiMeshNetwork = null
                                }
                                runCatching { cm.bindProcessToNetwork(null) }
                                if (joinedHotspotSsid == joinKey) {
                                    joinedHotspotSsid = ""
                                }
                                _status.update { it.copy(pendingHotspotPrompt = null) }
                            }
                        }
                        activeNetworkCallback = callback
                        cm.requestNetwork(request, callback)
                    }.onFailure {
                        isSpecifierRequestInFlight = false
                        inFlightPassphrase = ""
                    }
                }
            }
        } else {
            if (hasHighSpeedLink()) {
                updateButtonState("HOTSPOT: LINKED")
            }
            connectGatewayTcp(targetIp)
        }
    }

    /**
     * Registers a Wi-Fi Direct DNS-SD Bonjour service so nearby devices scanning over Wi-Fi P2P
     * immediately discover our Node ID, Callsign, and Hotspot credentials (`ssid`, `pass`, `ip`)
     * even before any BLE GATT connection is established.
     */
    @SuppressLint("MissingPermission")
    private fun registerWifiP2pDnsSdService(ssid: String = "", passphrase: String = "", hostIp: String = "") {
        runCatching {
            val mgr = wifiP2pManager ?: return@runCatching
            val ch = wifiP2pChannel ?: mgr.initialize(context, Looper.getMainLooper(), null).also {
                wifiP2pChannel = it
            } ?: return@runCatching

            val record = mapOf(
                "nodeId" to localNodeId(),
                "cs" to localCallsign().take(12),
                "ssid" to ssid,
                "pass" to passphrase,
                "ip" to hostIp.ifBlank { getBestLocalIpv4Address().ifBlank { P2P_GO_IP } }
            )
            val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                "pulsemesh_${OffGridBleMeshEngine.normalizeId(localNodeId())}",
                "_pulsemesh._tcp",
                record
            )
            mgr.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    mgr.addLocalService(ch, serviceInfo, null)
                }
                override fun onFailure(reason: Int) {
                    mgr.addLocalService(ch, serviceInfo, null)
                }
            })
        }
    }

    /**
     * Continuously discovers nearby PulseMesh peers and Hotspot credentials over Wi-Fi Direct DNS-SD.
     */
    @SuppressLint("MissingPermission")
    private fun startWifiP2pDnsSdAutoDiscovery() {
        p2pDnsSdDiscoveryJob?.cancel()
        p2pDnsSdDiscoveryJob = scope.launch(Dispatchers.Main) {
            delay(600L)
            runCatching {
                val mgr = wifiP2pManager ?: return@runCatching
                val ch = wifiP2pChannel ?: mgr.initialize(context, Looper.getMainLooper(), null).also {
                    wifiP2pChannel = it
                } ?: return@runCatching

                registerWifiP2pDnsSdService(
                    ssid = _status.value.ssid,
                    passphrase = _status.value.passphrase,
                    hostIp = _status.value.hostIp
                )

                val txtListener = WifiP2pManager.DnsSdTxtRecordListener { fullDomain, record, _ ->
                    if (!fullDomain.contains("_pulsemesh", ignoreCase = true)) return@DnsSdTxtRecordListener
                    val peerId = record["nodeId"].orEmpty()
                    val peerCs = record["cs"].orEmpty()
                    val ssid = record["ssid"].orEmpty()
                    val pass = record["pass"].orEmpty()
                    val hostIp = record["ip"].orEmpty().ifBlank { P2P_GO_IP }

                    if (peerCs.isNotBlank() && OffGridBleMeshEngine.isBlockedFakeCallsign(peerCs)) {
                        return@DnsSdTxtRecordListener
                    }

                    if (peerId.isNotBlank() && OffGridBleMeshEngine.normalizeId(peerId) != OffGridBleMeshEngine.normalizeId(localNodeId())) {
                        onImmediatePeerRegistered?.invoke(
                            peerId,
                            peerCs,
                            hostIp,
                            null,
                            null,
                            null,
                            "WIFI DIRECT"
                        )
                        if (ssid.isNotBlank() && pass.isNotBlank()) {
                            handleHotspotAutoJoin(peerId, ssid, pass, hostIp)
                        } else {
                            onPeerDiscovered(peerId, peerIsHost = false)
                        }
                    }
                }
                val servListener = WifiP2pManager.DnsSdServiceResponseListener { _, _, _ -> }
                mgr.setDnsSdResponseListeners(ch, servListener, txtListener)
                val req = WifiP2pDnsSdServiceRequest.newInstance()
                mgr.clearServiceRequests(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        mgr.addServiceRequest(ch, req, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                mgr.discoverServices(ch, null)
                            }
                            override fun onFailure(r: Int) {}
                        })
                    }
                    override fun onFailure(r: Int) {}
                })
            }

            while (isActive) {
                delay(15_000L)
                // Crucial stability fix: NEVER run off-channel P2P service discovery while hosting a hotspot,
                // while actively linked to a peer hotspot, or while a WifiNetworkSpecifier device chooser is open!
                if (!hasHighSpeedLink() && !isCurrentlyHotspotHost() && activeWifiMeshNetwork == null && !isSpecifierRequestInFlight) {
                    runCatching {
                        val mgr = wifiP2pManager
                        val ch = wifiP2pChannel
                        if (mgr != null && ch != null) {
                            mgr.discoverServices(ch, null)
                        }
                    }
                }
            }
        }
    }

    private fun startGatewayDiscoveryLoop() {
        gatewayDiscoveryLoopJob?.cancel()
        gatewayDiscoveryLoopJob = scope.launch(Dispatchers.IO) {
            delay(500L)
            while (isActive) {
                pruneStaleReachablePeerIps()
                startPassiveSubnetSockets()
                probePersonalHotspotGatewaysNow()
                broadcastUdpHeartbeatNow()
                delay(2000L)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun probePersonalHotspotGatewaysNow() {
        scope.launch(Dispatchers.IO) {
            val candidateGateways = collectTargetBroadcastAndGatewayIps()
                .filter { !it.endsWith(".255") }
            val localIps = getLocalIpv4Addresses()

            val now = System.currentTimeMillis()
            for (gwIp in candidateGateways) {
                if (gwIp.isBlank() || localIps.contains(gwIp) || gwIp == "127.0.0.1") continue
                val lastProbe = lastGatewayProbeMs[gwIp] ?: 0L
                if (now - lastProbe >= 1800L) {
                    lastGatewayProbeMs[gwIp] = now
                    connectGatewayTcp(gwIp)
                }
            }
        }
    }

    private fun connectGatewayTcp(gatewayIp: String) {
        if (gatewayIp.isBlank() || gatewayIp == "127.0.0.1") return
        val localIps = getLocalIpv4Addresses()
        if (localIps.contains(gatewayIp)) return

        scope.launch(Dispatchers.IO) {
            val hbBytes = (buildSubnetHeartbeatJson() + "\n").toByteArray(Charsets.UTF_8)

            runCatching {
                val udp = udpSocket
                if (udp != null && !udp.isClosed) {
                    val dgram = DatagramPacket(hbBytes, hbBytes.size, InetSocketAddress(gatewayIp, P2P_UDP_PORT))
                    udp.send(dgram)
                }
            }

            val alreadyConnected = activeTcpSockets.any {
                it.inetAddress?.hostAddress == gatewayIp && it.isConnected && !it.isClosed
            }
            if (!alreadyConnected) {
                val connectedWithBoundNet = runCatching {
                    val net = activeWifiMeshNetwork
                    val socket = net?.socketFactory?.createSocket() ?: Socket()
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(gatewayIp, P2P_TCP_PORT), 1200)
                    if (socket.isConnected) {
                        socket.getOutputStream().write(hbBytes)
                        socket.getOutputStream().flush()
                        registerTcpSocket(socket, gatewayIp)
                        true
                    } else {
                        socket.close()
                        false
                    }
                }.getOrDefault(false)

                if (!connectedWithBoundNet && activeWifiMeshNetwork != null) {
                    runCatching {
                        val socket = Socket()
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(gatewayIp, P2P_TCP_PORT), 1200)
                        if (socket.isConnected) {
                            socket.getOutputStream().write(hbBytes)
                            socket.getOutputStream().flush()
                            registerTcpSocket(socket, gatewayIp)
                        } else {
                            socket.close()
                        }
                    }
                }
            }
        }
    }

    private fun startPassiveSubnetSockets() {
        if (tcpServerJob?.isActive != true) {
            tcpServerJob = scope.launch(socketListenerDispatcher) {
                var server: ServerSocket? = null
                try {
                    server = ServerSocket()
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(P2P_TCP_PORT))
                    tcpServerSocket = server
                    while (isActive && !server.isClosed) {
                        val client = runCatching { server.accept() }.getOrNull() ?: break
                        client.tcpNoDelay = true
                        val remoteIp = client.inetAddress?.hostAddress ?: ""
                        registerTcpSocket(client, remoteIp)
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { server?.close() }
                    if (tcpServerSocket === server) tcpServerSocket = null
                }
            }
        }

        if (udpListenerJob?.isActive != true) {
            udpListenerJob = scope.launch(socketListenerDispatcher) {
                var sock: DatagramSocket? = null
                try {
                    acquireWifiMulticastLockIfNeeded()
                    sock = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        receiveBufferSize = 131_072
                        bind(InetSocketAddress(P2P_UDP_PORT))
                    }
                    udpSocket = sock
                    activeWifiMeshNetwork?.let { net ->
                        runCatching { net.bindSocket(sock) }
                    }

                    val localIps = HashSet<String>()
                    val buf = ByteArray(16384)
                    while (isActive && !sock.isClosed) {
                        runCatching {
                            val pkt = DatagramPacket(buf, buf.size)
                            sock.receive(pkt)
                            val senderIp = pkt.address?.hostAddress ?: ""
                            if (pkt.length <= 0 || senderIp.isBlank() || senderIp == "127.0.0.1") return@runCatching

                            localIps.clear()
                            localIps.addAll(getLocalIpv4Addresses())
                            if (localIps.contains(senderIp)) return@runCatching

                            lastPeerUdpRxMs = System.currentTimeMillis()
                            val data = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                            if (!activePeerIps.contains(senderIp)) {
                                activePeerIps.add(senderIp)
                                onTcpOrIpPeerReady?.invoke("", senderIp)
                            }
                            markPeerIpVerifiedReachable(senderIp)
                            if (data.size > 8 && data[0] == 0x50.toByte() &&
                                (data[1] == 0x56.toByte() || data[1] == 0x41.toByte())
                            ) {
                                // Raw binary "PV" (mu-law) or "PA" (IMA-ADPCM) voice frame
                                onAudioFrameReceived?.invoke(data)
                            } else if (data[0] == '{'.code.toByte()) {
                                val text = String(data, Charsets.UTF_8).trim()
                                for (line in text.split('\n')) {
                                    val trimmed = line.trim()
                                    if (trimmed.startsWith("{")) {
                                        handleIncomingJsonLine(trimmed, senderIp, "HOTSPOT WIFI")
                                    }
                                }
                            } else {
                                onAudioFrameReceived?.invoke(data)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Socket setup or closure; finally ensures clean re-bind on next 2s tick
                } finally {
                    runCatching { sock?.close() }
                    if (udpSocket === sock) udpSocket = null
                }
            }
        }

        if (multicastListenerJob?.isActive != true) {
            multicastListenerJob = scope.launch(socketListenerDispatcher) {
                var mSock: MulticastSocket? = null
                try {
                    acquireWifiMulticastLockIfNeeded()
                    mSock = MulticastSocket(P2P_UDP_PORT).apply {
                        reuseAddress = true
                        receiveBufferSize = 131_072
                    }
                    val groupAddr = InetAddress.getByName(IPV4_MESH_MULTICAST_GROUP)
                    @Suppress("DEPRECATION")
                    runCatching { mSock.joinGroup(groupAddr) }
                    multicastSocket = mSock

                    val localIps = HashSet<String>()
                    val buf = ByteArray(16384)
                    while (isActive && !mSock.isClosed) {
                        runCatching {
                            val pkt = DatagramPacket(buf, buf.size)
                            mSock.receive(pkt)
                            val senderIp = pkt.address?.hostAddress ?: ""
                            if (pkt.length <= 0 || senderIp.isBlank() || senderIp == "127.0.0.1") return@runCatching

                            localIps.clear()
                            localIps.addAll(getLocalIpv4Addresses())
                            if (localIps.contains(senderIp)) return@runCatching

                            lastPeerUdpRxMs = System.currentTimeMillis()
                            val data = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                            markPeerIpVerifiedReachable(senderIp)
                            if (data.size > 8 && data[0] == 0x50.toByte() &&
                                (data[1] == 0x56.toByte() || data[1] == 0x41.toByte())
                            ) {
                                onAudioFrameReceived?.invoke(data)
                            } else if (data.isNotEmpty() && data[0] == '{'.code.toByte()) {
                                val text = String(data, Charsets.UTF_8).trim()
                                for (line in text.split('\n')) {
                                    val trimmed = line.trim()
                                    if (trimmed.startsWith("{")) {
                                        handleIncomingJsonLine(trimmed, senderIp, "HOTSPOT WIFI")
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Socket setup or closure; finally ensures clean re-bind on next 2s tick
                } finally {
                    runCatching { mSock?.close() }
                    if (multicastSocket === mSock) multicastSocket = null
                }
            }
        }
    }

    private fun acquireWifiMulticastLockIfNeeded() {
        runCatching {
            val mgr = wifiManager ?: return@runCatching
            val existingMc = multicastLock
            if (existingMc == null || !existingMc.isHeld) {
                multicastLock = mgr.createMulticastLock("PulseMeshMulticastLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            val existingWl = wifiHighPerfLock
            if (existingWl == null || !existingWl.isHeld) {
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiHighPerfLock = mgr.createWifiLock(lockMode, "PulseMeshLowLatencyWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
    }

    private fun startNsdMeshService() {
        val mgr = nsdManager ?: return
        if (nsdRegistrationListener == null) {
            runCatching {
                val serviceInfo = NsdServiceInfo().apply {
                    serviceName = "PulseMesh_${OffGridBleMeshEngine.normalizeId(localNodeId())}"
                    serviceType = NSD_SERVICE_TYPE
                    port = P2P_UDP_PORT
                }
                val regListener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(info: NsdServiceInfo) {}
                    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceUnregistered(info: NsdServiceInfo) {}
                    override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                }
                nsdRegistrationListener = regListener
                mgr.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, regListener)
            }
        }

        if (nsdDiscoveryListener == null) {
            runCatching {
                val discListener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) {}
                    override fun onServiceFound(service: NsdServiceInfo) {
                        if (!service.serviceType.contains("_pulsemesh")) return
                        val selfTag = "PulseMesh_${OffGridBleMeshEngine.normalizeId(localNodeId())}"
                        if (service.serviceName == selfTag) return
                        runCatching {
                            @Suppress("DEPRECATION")
                            mgr.resolveService(service, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                                override fun onServiceResolved(resolved: NsdServiceInfo) {
                                    @Suppress("DEPRECATION")
                                    val hostIp = resolved.host?.hostAddress ?: return
                                    if (hostIp.isNotBlank() && !getLocalIpv4Addresses().contains(hostIp)) {
                                        val rawHex = resolved.serviceName.orEmpty()
                                            .substringAfter("PulseMesh_", "")
                                            .take(4)
                                            .uppercase()
                                        val parsedNodeId = if (rawHex.length == 4 && rawHex.all { it.isDigit() || it in 'A'..'F' }) {
                                            "0x$rawHex"
                                        } else ""
                                        if (parsedNodeId.isNotBlank()) {
                                            onImmediatePeerRegistered?.invoke(
                                                parsedNodeId,
                                                "",
                                                hostIp,
                                                null,
                                                null,
                                                null,
                                                "mDNS/NSD"
                                            )
                                        }
                                        registerPeerIp(parsedNodeId, hostIp)
                                        connectGatewayTcp(hostIp)
                                    }
                                }
                            })
                        }
                    }
                    override fun onServiceLost(service: NsdServiceInfo) {}
                    override fun onDiscoveryStopped(serviceType: String) {}
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                }
                nsdDiscoveryListener = discListener
                mgr.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discListener)
            }
        }
    }

    /**
     * Layer 6: Opportunistic Wi-Fi Aware / NAN ("pulsemesh_nan") non-blocking publish/subscribe session.
     */
    @SuppressLint("MissingPermission")
    private fun startWifiAwareNanSession() {
        if (!isWifiAwareEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) return@runCatching
            val mgr = wifiAwareManager ?: return@runCatching
            if (!mgr.isAvailable) return@runCatching

            mgr.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    wifiAwareSession = session
                    val hbInfo = buildSubnetHeartbeatJson().take(200).toByteArray(Charsets.UTF_8)
                    val pubConfig = PublishConfig.Builder()
                        .setServiceName(WIFI_AWARE_SERVICE_NAME)
                        .setServiceSpecificInfo(hbInfo)
                        .build()
                    session.publish(pubConfig, object : DiscoverySessionCallback() {
                        override fun onPublishStarted(session: PublishDiscoverySession) {
                            wifiAwarePublishSession = session
                        }
                        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                            if (!wifiAwarePeers.contains(peerHandle)) wifiAwarePeers.add(peerHandle)
                            val str = String(message, Charsets.UTF_8).trim()
                            if (str.startsWith("{")) {
                                handleIncomingJsonLine(str, "", "NAN")
                            }
                        }
                    }, null)

                    val subConfig = SubscribeConfig.Builder()
                        .setServiceName(WIFI_AWARE_SERVICE_NAME)
                        .build()
                    session.subscribe(subConfig, object : DiscoverySessionCallback() {
                        override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                            wifiAwareSubscribeSession = session
                        }
                        override fun onServiceDiscovered(
                            peerHandle: PeerHandle,
                            serviceSpecificInfo: ByteArray?,
                            matchFilter: MutableList<ByteArray>?
                        ) {
                            if (!wifiAwarePeers.contains(peerHandle)) wifiAwarePeers.add(peerHandle)
                            if (serviceSpecificInfo != null && serviceSpecificInfo.isNotEmpty()) {
                                val str = String(serviceSpecificInfo, Charsets.UTF_8).trim()
                                if (str.startsWith("{")) {
                                    handleIncomingJsonLine(str, "", "NAN")
                                }
                            }
                            runCatching {
                                wifiAwareSubscribeSession?.sendMessage(
                                    peerHandle,
                                    1,
                                    buildSubnetHeartbeatJson().take(240).toByteArray(Charsets.UTF_8)
                                )
                            }
                        }
                        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                            if (!wifiAwarePeers.contains(peerHandle)) wifiAwarePeers.add(peerHandle)
                            val str = String(message, Charsets.UTF_8).trim()
                            if (str.startsWith("{")) {
                                handleIncomingJsonLine(str, "", "NAN")
                            }
                        }
                    }, null)
                }
            }, null)
        }
    }

    private fun handleIncomingJsonLine(
        jsonLine: String,
        senderIp: String,
        sourceTransport: String = "HOTSPOT WIFI"
    ) {
        runCatching {
            val root = JSONObject(jsonLine)
            val srcNodeId = root.optString("srcId")
                .ifBlank { root.optString("src") }
                .ifBlank { root.optString("srcNodeId") }
            if (srcNodeId.isBlank() || OffGridBleMeshEngine.normalizeId(srcNodeId) == OffGridBleMeshEngine.normalizeId(localNodeId())) {
                return
            }

            val srcName = root.optString("srcName")
                .ifBlank { root.optString("cs") }
                .ifBlank { root.optString("srcCallsign", "") }
            if (srcName.isNotBlank() && OffGridBleMeshEngine.isBlockedFakeCallsign(srcName)) {
                return
            }
            val lat = if (root.has("lat")) root.getDouble("lat") else null
            val lng = if (root.has("lng")) root.getDouble("lng") else null
            val bat = if (root.has("bat")) root.getInt("bat") else if (root.has("battery")) root.getInt("battery") else null

            val reportedClientIp = root.optString("myLocalIp").ifBlank { senderIp }
            val effectivePeerIp = if (senderIp.isNotBlank() && senderIp != "127.0.0.1") senderIp else reportedClientIp

            val isNewIp = effectivePeerIp.isNotBlank() && !activePeerIps.contains(effectivePeerIp)

            // 1. IMMEDIATELY register peer in activePeers BEFORE any TCP handshake!
            onImmediatePeerRegistered?.invoke(
                srcNodeId,
                srcName,
                effectivePeerIp,
                lat,
                lng,
                bat,
                sourceTransport
            )

            if (effectivePeerIp.isNotBlank()) {
                registerPeerIp(srcNodeId, effectivePeerIp)
                markPeerIpVerifiedReachable(effectivePeerIp)
            }

            // 2. Immediately fire a direct unicast UDP HEARTBEAT reply straight back to InetSocketAddress(senderIp, 45888)
            // so both devices see each other within <100ms!
            val now = System.currentTimeMillis()
            val lastReply = lastUnicastReplyMs[effectivePeerIp] ?: 0L
            if (effectivePeerIp.isNotBlank() && (isNewIp || now - lastReply > 900L)) {
                lastUnicastReplyMs[effectivePeerIp] = now
                sendDirectReplyToPeerIp(effectivePeerIp)
            }

            val wifiRssi = getRealWifiRssiDbm()
            val metaConsumer = onPacketReceivedWithMeta
            if (metaConsumer != null) {
                metaConsumer.invoke(jsonLine, sourceTransport, wifiRssi)
            } else {
                onPacketReceived(jsonLine)
            }

            // Star + Broadcast Hub Forwarding: forward packets from client A to clients B, C, D...
            forwardHubPacketToOtherPeersIfNeeded(root, senderIp)
        }
    }

    /**
     * Star + Broadcast Hub Forwarding for N-node Wi-Fi Direct / Hotspot swarms:
     * When hosting or relaying across multiple IP peers, forwards incoming packets (ttl > 1)
     * to all other connected TCP sockets and known peer IPs (excluding senderIp).
     */
    private fun allowHubRelayForSource(srcNodeId: String): Boolean {
        val normSrc = OffGridBleMeshEngine.normalizeId(srcNodeId)
        if (normSrc.isBlank()) return false
        val now = System.currentTimeMillis()
        val list = hubRelayRateMap.getOrPut(normSrc) { mutableListOf() }
        synchronized(list) {
            val iter = list.iterator()
            while (iter.hasNext()) {
                if (now - iter.next() > 1000L) iter.remove()
            }
            if (list.size >= 5) return false
            list.add(now)
            return true
        }
    }

    private fun forwardHubPacketToOtherPeersIfNeeded(root: JSONObject, senderIp: String) {
        val type = root.optString("type").ifBlank { root.optString("t") }.uppercase()
        if (type == "HEARTBEAT" || type == "GPS_BEACON" || type == "VOICE" || type == "VOICE_STREAM" || type == "VOICE_PTT") return
        val ttl = root.optInt("ttl", 7)
        if (ttl <= 1) return

        val srcId = root.optString("srcId").ifBlank { root.optString("src") }
        if (!allowHubRelayForSource(srcId)) return

        val pktId = root.optString("id").ifBlank { root.optString("packetId") }
        if (pktId.isNotBlank()) {
            synchronized(hubForwardedPacketIds) {
                if (hubForwardedPacketIds.contains(pktId)) return
                if (hubForwardedPacketIds.size >= HUB_DEDUP_LIMIT) {
                    val iter = hubForwardedPacketIds.iterator()
                    if (iter.hasNext()) {
                        iter.next()
                        iter.remove()
                    }
                }
                hubForwardedPacketIds.add(pktId)
            }
        }

        if (!isCurrentlyHotspotHost() && activeTcpSockets.size <= 1 && activePeerIps.size <= 1) return

        val forwardedJson = JSONObject(root.toString()).apply {
            put("ttl", ttl - 1)
            put("hops", root.optInt("hops", 0) + 1)
        }.toString()
        val payloadBytes = (forwardedJson + "\n").toByteArray(Charsets.UTF_8)

        scope.launch(Dispatchers.IO) {
            for (sock in activeTcpSockets) {
                val peerHost = sock.inetAddress?.hostAddress ?: ""
                if (peerHost.isBlank() || peerHost == senderIp || sock.isClosed || !sock.isConnected) continue
                runCatching {
                    val out = sock.getOutputStream()
                    synchronized(out) {
                        out.write(payloadBytes)
                        out.flush()
                    }
                }
            }
            val udp = udpSocket
            if (udp != null && !udp.isClosed) {
                for (ip in activePeerIps) {
                    if (ip.isBlank() || ip == senderIp) continue
                    runCatching {
                        val dgram = DatagramPacket(payloadBytes, payloadBytes.size, InetAddress.getByName(ip), P2P_UDP_PORT)
                        udp.send(dgram)
                    }
                }
            }
        }
    }

    private fun sendDirectReplyToPeerIp(peerIp: String) {
        if (peerIp.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val hbBytes = (buildSubnetHeartbeatJson() + "\n").toByteArray(Charsets.UTF_8)

            runCatching {
                val udp = udpSocket
                if (udp != null && !udp.isClosed) {
                    val dgram = DatagramPacket(hbBytes, hbBytes.size, InetSocketAddress(peerIp, P2P_UDP_PORT))
                    udp.send(dgram)
                }
            }

            val alreadyTcpConnected = activeTcpSockets.any {
                it.inetAddress?.hostAddress == peerIp && it.isConnected && !it.isClosed
            }
            if (!alreadyTcpConnected) {
                runCatching {
                    val socket = activeWifiMeshNetwork?.socketFactory?.createSocket() ?: Socket()
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(peerIp, P2P_TCP_PORT), 1500)
                    if (socket.isConnected) {
                        socket.getOutputStream().write(hbBytes)
                        socket.getOutputStream().flush()
                        registerTcpSocket(socket, peerIp)
                    } else {
                        socket.close()
                    }
                }
            }
        }
    }

    fun broadcastUdpHeartbeatNow() {
        val hbJson = buildSubnetHeartbeatJson()
        sendPacket(hbJson)
    }

    private fun buildSubnetHeartbeatJson(): String {
        val (rawLat, rawLng) = getCurrentLocation()
        val locMode = getLocationShareMode()
        val (modeLat, modeLng) = OffGridBleMeshEngine.applyLocationShareMode(rawLat, rawLng, locMode)
        val hasCoords = locMode != com.example.model.LocationShareMode.OFF && (modeLat != 0.0 || modeLng != 0.0)
        val localIp = getBestLocalIpv4Address()
        return JSONObject().apply {
            val pktId = "udp_hb_${localNodeId()}_${System.currentTimeMillis()}"
            put("id", pktId)
            put("packetId", pktId)
            put("type", if (hasCoords) "GPS_BEACON" else "HEARTBEAT")
            put("t", if (hasCoords) "GPS_BEACON" else "HEARTBEAT")
            put("srcId", localNodeId())
            put("src", localNodeId())
            put("srcName", localCallsign())
            put("cs", localCallsign())
            put("dstId", "BROADCAST")
            put("dst", "BROADCAST")
            put("ttl", 1)
            put("hops", 0)
            put("payload", localIp.ifBlank { "Hotspot Heartbeat" })
            put("p", localIp.ifBlank { "Hotspot Heartbeat" })
            if (localIp.isNotBlank()) {
                put("myLocalIp", localIp)
            }
            if (locMode == com.example.model.LocationShareMode.OFF) {
                put("lat", 0.0)
                put("lng", 0.0)
            } else if (hasCoords) {
                put("lat", modeLat)
                put("lng", modeLng)
            }
            put("bat", getBatteryPercent())
            put("ts", System.currentTimeMillis())
        }.toString()
    }

    /**
     * Resolves the true Hotspot / Wi-Fi Direct Group Owner IPv4 address for advertising over BLE and DNS-SD.
     * Crucially avoids advertising a regular `wlan0` home/office router IP (`192.168.1.x`) when hosting a P2P group
     * (`p2p-wlan0-0` -> `192.168.49.1`) or Local-Only Hotspot (`ap0` / `swlan0` -> `192.168.43.1`).
     */
    fun getBestHotspotHostIpv4Address(preferredRole: String = _status.value.role): String {
        var p2pIp: String? = null
        var apIp: String? = null
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (!iface.isUp || iface.isLoopback) continue
                    val name = iface.name?.lowercase() ?: ""
                    for (addr in iface.interfaceAddresses) {
                        val inet = addr.address
                        if (inet is Inet4Address && !inet.isLoopbackAddress) {
                            val ip = inet.hostAddress ?: continue
                            when {
                                name.startsWith("p2p") || ip.startsWith("192.168.49.") -> {
                                    if (p2pIp == null) p2pIp = ip
                                }
                                name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") ||
                                    name == "wlan1" || ip.startsWith("192.168.43.") -> {
                                    if (apIp == null) apIp = ip
                                }
                            }
                        }
                    }
                }
            }
        }
        val preferLocalAp = preferredRole.uppercase().contains("LOCAL")
        return if (preferLocalAp) {
            apIp ?: p2pIp ?: LOCAL_HOTSPOT_IP
        } else {
            p2pIp ?: apIp ?: P2P_GO_IP
        }
    }

    fun getBestLocalIpv4Address(): String {
        val ips = getLocalIpv4Addresses()
        return ips.firstOrNull { it.startsWith("192.168.49.") }
            ?: ips.firstOrNull { it.startsWith("192.168.43.") }
            ?: ips.firstOrNull { isIsolatedHotspotSubnet(it) }
            ?: ips.firstOrNull()
            ?: ""
    }

    private fun getLocalIpv4Addresses(): Set<String> {
        val result = HashSet<String>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.interfaceAddresses) {
                    val inet = addr.address
                    if (inet is Inet4Address && !inet.isLoopbackAddress) {
                        inet.hostAddress?.let { result.add(it) }
                    }
                }
            }
        }
        return result
    }

    @Volatile
    private var cachedTargetIps: Set<String> = emptySet()
    @Volatile
    private var lastTargetIpsRefreshMs: Long = 0L

    @Suppress("DEPRECATION")
    private fun collectTargetBroadcastAndGatewayIps(): Set<String> {
        val now = System.currentTimeMillis()
        val cached = cachedTargetIps
        if (cached.isNotEmpty() && now - lastTargetIpsRefreshMs < 2000L) {
            val fast = LinkedHashSet<String>(cached)
            fast.addAll(activePeerIps)
            fast.addAll(peerIpByNodeId.values)
            return fast
        }

        val targets = LinkedHashSet<String>()
        targets.addAll(activePeerIps)
        targets.addAll(peerIpByNodeId.values)
        targets.add(LOCAL_HOTSPOT_IP)
        targets.add("192.168.43.255")
        targets.add(P2P_GO_IP)
        targets.add("192.168.49.255")
        targets.add("255.255.255.255")
        targets.add(IPV4_MESH_MULTICAST_GROUP)
        // Layer 7: IPv6 Link-Local All-Nodes Multicast (ff02::1)
        targets.add(IPV6_LINK_LOCAL_MULTICAST)

        // 1. Dynamic NetworkInterface broadcast addresses, .1/.2 gateways, .255 broadcasts
        // across wlan0, ap0, swlan0, p2p-wlan0-0, AND Layer 8 USB-C / Ethernet (usb0, rndis0, ncm0, eth0)
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (!iface.isUp || iface.isLoopback) continue
                    val ifaceName = iface.name ?: ""
                    val isWiredOrMeshIface = isTargetMeshInterfaceName(ifaceName)
                    for (ifAddr in iface.interfaceAddresses) {
                        val inet = ifAddr.address
                        if (inet is Inet4Address && !inet.isLoopbackAddress) {
                            ifAddr.broadcast?.hostAddress?.let { bcast ->
                                if (bcast.isNotBlank()) targets.add(bcast)
                            }
                            val hostIp = inet.hostAddress ?: ""
                            if (isIsolatedHotspotSubnet(hostIp) || isWiredOrMeshIface) {
                                val parts = hostIp.split('.')
                                if (parts.size == 4) {
                                    val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                                    targets.add("$prefix.1")
                                    targets.add("$prefix.2")
                                    targets.add("$prefix.255")
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. ConnectivityManager LinkProperties routes (detects arbitrary vendor Wi-Fi / Hotspot / USB / Ethernet gateways)
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.allNetworks?.forEach { net ->
                val lp = cm.getLinkProperties(net)
                val ifaceName = lp?.interfaceName ?: ""
                val isUsbOrEth = isUsbOrEthernetInterfaceName(ifaceName)
                lp?.routes?.forEach { route ->
                    val gw = route.gateway
                    if (gw is Inet4Address && !gw.isLoopbackAddress) {
                        gw.hostAddress?.let { ip ->
                            if (ip.isNotBlank() && (isIsolatedHotspotSubnet(ip) || isUsbOrEth)) {
                                targets.add(ip)
                            }
                        }
                    }
                }
            }
        }

        // 3. WifiManager DhcpInfo gateway & serverAddress
        runCatching {
            val dhcp = wifiManager?.dhcpInfo
            if (dhcp != null) {
                val srvIp = intIpToString(dhcp.serverAddress)
                val gwIp = intIpToString(dhcp.gateway)
                if (srvIp.isNotBlank() && isIsolatedHotspotSubnet(srvIp)) targets.add(srvIp)
                if (gwIp.isNotBlank() && isIsolatedHotspotSubnet(gwIp)) targets.add(gwIp)
            }
        }

        cachedTargetIps = targets
        lastTargetIpsRefreshMs = now
        return targets
    }

    private fun registerTcpSocket(socket: Socket, peerIp: String) {
        val isNewIp = peerIp.isNotBlank() && peerIp != "127.0.0.1" && !activePeerIps.contains(peerIp)
        if (isNewIp) {
            activePeerIps.add(peerIp)
            onTcpOrIpPeerReady?.invoke("", peerIp)
        }
        markPeerIpVerifiedReachable(peerIp)
        activeTcpSockets.add(socket)
        _status.update {
            it.copy(
                isConnected = true,
                peerIp = peerIp.ifBlank { it.peerIp },
                connectedSocketCount = maxOf(activeTcpSockets.size, activePeerIps.size),
                statusMessage = "Swarm TCP Linked (${activeTcpSockets.size} sockets • $peerIp)"
            )
        }

        scope.launch(Dispatchers.IO) {
            runCatching {
                val hbBytes = (buildSubnetHeartbeatJson() + "\n").toByteArray(Charsets.UTF_8)
                val out = socket.getOutputStream()
                synchronized(out) {
                    out.write(hbBytes)
                    out.flush()
                }

                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                while (isActive && socket.isConnected && !socket.isClosed) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.startsWith("{")) {
                        handleIncomingJsonLine(trimmed, peerIp, "HOTSPOT WIFI")
                    }
                }
            }
            activeTcpSockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    fun sendPacket(jsonString: String) {
        scope.launch(Dispatchers.IO) {
            val payloadBytes = (jsonString.trim() + "\n").toByteArray(Charsets.UTF_8)

            for (sock in activeTcpSockets) {
                runCatching {
                    val out: OutputStream = sock.getOutputStream()
                    synchronized(out) {
                        out.write(payloadBytes)
                        out.flush()
                    }
                }
            }

            for (peerIp in activePeerIps) {
                val hasActiveSock = activeTcpSockets.any {
                    it.inetAddress?.hostAddress == peerIp && it.isConnected && !it.isClosed
                }
                if (!hasActiveSock && peerIp.isNotBlank()) {
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            val socket = Socket()
                            socket.tcpNoDelay = true
                            socket.connect(InetSocketAddress(peerIp, P2P_TCP_PORT), 1200)
                            if (socket.isConnected) {
                                socket.getOutputStream().write(payloadBytes)
                                socket.getOutputStream().flush()
                                registerTcpSocket(socket, peerIp)
                            } else {
                                socket.close()
                            }
                        }
                    }
                }
            }

            val udp = udpSocket
            if (udp != null && !udp.isClosed) {
                val targets = collectTargetBroadcastAndGatewayIps()
                for (ip in targets) {
                    runCatching {
                        val addr = InetAddress.getByName(ip)
                        val dgram = DatagramPacket(payloadBytes, payloadBytes.size, addr, P2P_UDP_PORT)
                        udp.send(dgram)
                    }
                }
            }

            // Layer 6: Forward compact JSON frames over Opportunistic Wi-Fi Aware / NAN if peers discovered
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && payloadBytes.size <= 250 && wifiAwarePeers.isNotEmpty()) {
                for (nanPeer in wifiAwarePeers) {
                    runCatching {
                        wifiAwareSubscribeSession?.sendMessage(nanPeer, 2, payloadBytes)
                        wifiAwarePublishSession?.sendMessage(nanPeer, 2, payloadBytes)
                    }
                }
            }
        }
    }

    /**
     * Non-blocking UDP + TCP fallback voice packet sender (`45888` / `45889`) for real-time G.711 µ-Law voice frames.
     * Sends to both `verifiedReachablePeerIps` and subnet gateway/broadcast targets (excluding local IPs),
     * and mirrors onto open TCP sockets if bidirectional UDP RX hasn't been seen recently so voice never stops after ~138 frames!
     */
    fun sendVoiceUdpPacket(jsonString: String) {
        scope.launch(Dispatchers.IO) {
            val payloadBytes = (jsonString.trim() + "\n").toByteArray(Charsets.UTF_8)
            val udp = udpSocket
            if (udp != null && !udp.isClosed) {
                val localIps = getLocalIpv4Addresses()
                val targets = LinkedHashSet<String>().apply {
                    addAll(verifiedReachablePeerIps)
                    addAll(collectTargetBroadcastAndGatewayIps())
                }.filter { it.isNotBlank() && !localIps.contains(it) }

                repeat(2) { attempt ->
                    for (ip in targets) {
                        runCatching {
                            val addr = InetAddress.getByName(ip)
                            val dgram = DatagramPacket(payloadBytes, payloadBytes.size, addr, P2P_UDP_PORT)
                            udp.send(dgram)
                        }
                    }
                    if (attempt == 0) delay(12L)
                }
            }

            // TCP fallback mirror whenever bidirectional UDP traffic isn't actively arriving (or no verified UDP peers yet)
            if (verifiedReachablePeerIps.isEmpty() || !hasActiveUdpTraffic(6_000L)) {
                for (sock in activeTcpSockets) {
                    if (sock.isClosed || !sock.isConnected) continue
                    runCatching {
                        val out = sock.getOutputStream()
                        synchronized(out) {
                            out.write(payloadBytes)
                            out.flush()
                        }
                    }
                }
            }
        }
    }

    /**
     * Low-overhead raw binary voice datagram sender ("PA"/"PV" 8-byte header + raw codec payload) over UDP 45888.
     * Eliminates Base64 (+33%) and JSON key overhead on Wi-Fi Direct / Hotspot UDP while optionally keeping
     * newline-delimited JSON fallback over TCP ONLY when bidirectional UDP hasn't been verified yet.
     */
    fun sendBinaryVoiceUdpPacket(binaryFrame: ByteArray, jsonTcpFallback: String? = null) {
        if (binaryFrame.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val udp = udpSocket
            if (udp != null && !udp.isClosed) {
                val localIps = getLocalIpv4Addresses()
                val targets = LinkedHashSet<String>().apply {
                    addAll(verifiedReachablePeerIps)
                    addAll(collectTargetBroadcastAndGatewayIps())
                }.filter { it.isNotBlank() && !localIps.contains(it) }

                repeat(2) { attempt ->
                    for (ip in targets) {
                        runCatching {
                            val addr = InetAddress.getByName(ip)
                            val dgram = DatagramPacket(binaryFrame, binaryFrame.size, addr, P2P_UDP_PORT)
                            udp.send(dgram)
                        }
                    }
                    if (attempt == 0) delay(8L)
                }
            }

            if (!jsonTcpFallback.isNullOrBlank() && (verifiedReachablePeerIps.isEmpty() || !hasActiveUdpTraffic(6_000L))) {
                val tcpBytes = (jsonTcpFallback.trim() + "\n").toByteArray(Charsets.UTF_8)
                for (sock in activeTcpSockets) {
                    if (sock.isClosed || !sock.isConnected) continue
                    runCatching {
                        val out = sock.getOutputStream()
                        synchronized(out) {
                            out.write(tcpBytes)
                            out.flush()
                        }
                    }
                }
            }
        }
    }

    fun sendAudioFrame(audioBytes: ByteArray) {
        scope.launch(Dispatchers.IO) {
            val udp = udpSocket ?: return@launch
            if (udp.isClosed) return@launch
            val localIps = getLocalIpv4Addresses()
            val targets = LinkedHashSet<String>().apply {
                addAll(activePeerIps)
                addAll(verifiedReachablePeerIps)
                addAll(collectTargetBroadcastAndGatewayIps())
            }.filter { it.isNotBlank() && !localIps.contains(it) }
            for (ip in targets) {
                runCatching {
                    val addr = InetAddress.getByName(ip)
                    val dgram = DatagramPacket(audioBytes, audioBytes.size, addr, P2P_UDP_PORT)
                    udp.send(dgram)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopHotspotGroup() {
        hotspotAutoJoinBeaconJob?.cancel()
        hotspotAutoJoinBeaconJob = null
        joinedHotspotSsid = ""
        isSpecifierRequestInFlight = false
        inFlightPassphrase = ""
        onLocalHotspotCredentialsUpdated?.invoke("", "")
        runCatching {
            val mgr = wifiP2pManager
            val ch = wifiP2pChannel
            if (mgr != null && ch != null) {
                mgr.removeGroup(ch, null)
            }
        }
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            activeNetworkCallback?.let { cb ->
                runCatching { cm?.unregisterNetworkCallback(cb) }
            }
            activeNetworkCallback = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm?.bindProcessToNetwork(null)
            }
        }
        for (sock in activeTcpSockets) {
            runCatching { sock.close() }
        }
        activeTcpSockets.clear()
        activePeerIps.clear()
        verifiedReachablePeerIps.clear()
        _status.value = AutoHotspotStatus(buttonStateText = "HOTSPOT: OFF")
        onHotspotButtonStateChanged?.invoke("HOTSPOT: OFF")
    }

    fun forceReconnect(peerId: String = "") {
        healHotspotConnectionNow(peerId)
    }

    /**
     * 1-Tap Hotspot Link Healer:
     * - Flushes dead/closed TCP sockets and prunes stale peer IPs
     * - Restarts passive UDP/TCP listeners, mDNS/NSD discovery, and Wi-Fi Direct DNS-SD discovery
     * - Re-triggers client join if a remote hotspot candidate is known, or refreshes the host beacon & gateway probes
     */
    fun healHotspotConnectionNow(peerId: String = ""): String {
        // 1. Clean dead TCP sockets & prune stale reachable IPs
        val deadSockets = activeTcpSockets.filter { it.isClosed || !it.isConnected }
        for (dead in deadSockets) {
            activeTcpSockets.remove(dead)
            runCatching { dead.close() }
        }
        pruneStaleReachablePeerIps(nowMs = System.currentTimeMillis())
        dismissedJoinKeys.clear()

        // 2. Restart passive sockets & service discovery
        startPassiveSubnetSockets()
        startNsdMeshService()
        startWifiP2pDnsSdAutoDiscovery()

        // 3. Re-negotiate or re-probe gateways immediately
        val normRequested = OffGridBleMeshEngine.normalizeId(peerId)
        val candidateEntry = if (normRequested.isNotBlank() && peerHotspotCredentials.containsKey(normRequested)) {
            peerHotspotCredentials[normRequested]?.let { normRequested to it }
        } else {
            peerHotspotCredentials.entries.firstOrNull()?.let { it.key to it.value }
        }

        val summary = if (!hasHighSpeedLink() && candidateEntry != null && candidateEntry.second.second.isNotBlank()) {
            val (hostId, creds) = candidateEntry
            val (ssid, pass, ip) = creds
            joinedHotspotSsid = ""
            handleHotspotAutoJoin(
                hostNodeId = "0x$hostId",
                ssid = ssid,
                passphrase = pass,
                hostIp = ip,
                isManualJoin = true
            )
            "Re-joining Hotspot ${ssid.ifBlank { "DIRECT-*" }} ($ip)..."
        } else if (isCurrentlyHotspotHost() && _status.value.ssid.isNotBlank()) {
            val hostIp = getBestHotspotHostIpv4Address(_status.value.role)
            startHotspotInfoBleBeacon(_status.value.ssid, _status.value.passphrase, hostIp)
            lastGatewayProbeMs.clear()
            probePersonalHotspotGatewaysNow()
            broadcastUdpHeartbeatNow()
            "Re-broadcasting Hotspot ${_status.value.ssid} ($hostIp) & probing peers..."
        } else {
            lastGatewayProbeMs.clear()
            probePersonalHotspotGatewaysNow()
            broadcastUdpHeartbeatNow()
            startHostP2pGroup(
                manualTrigger = true,
                onFallbackToLocalHotspot = { onFallbackToLocalHotspotCallback?.invoke() }
            )
            "Healing Hotspot & Wi-Fi Direct group..."
        }

        _status.update {
            it.copy(
                statusMessage = summary,
                buttonStateText = if (hasHighSpeedLink()) "HOTSPOT: LINKED" else "HOTSPOT: HEALING..."
            )
        }
        onHotspotButtonStateChanged?.invoke(_status.value.buttonStateText)
        return summary
    }

    fun release() {
        p2pDnsSdDiscoveryJob?.cancel()
        p2pGroupKeepaliveJob?.cancel()
        hotspotAutoJoinBeaconJob?.cancel()
        gatewayDiscoveryLoopJob?.cancel()
        tcpServerJob?.cancel()
        udpListenerJob?.cancel()
        multicastListenerJob?.cancel()
        runCatching { multicastSocket?.close() }
        multicastSocket = null
        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        }
        multicastLock = null
        runCatching {
            if (wifiHighPerfLock?.isHeld == true) {
                wifiHighPerfLock?.release()
            }
        }
        wifiHighPerfLock = null
        nsdRegistrationListener?.let { listener ->
            runCatching { nsdManager?.unregisterService(listener) }
        }
        nsdDiscoveryListener?.let { listener ->
            runCatching { nsdManager?.stopServiceDiscovery(listener) }
        }
        nsdRegistrationListener = null
        nsdDiscoveryListener = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { wifiAwarePublishSession?.close() }
            runCatching { wifiAwareSubscribeSession?.close() }
            runCatching { wifiAwareSession?.close() }
        }
        wifiAwarePublishSession = null
        wifiAwareSubscribeSession = null
        wifiAwareSession = null
        wifiAwarePeers.clear()
        for (sock in activeTcpSockets) {
            runCatching { sock.close() }
        }
        activeTcpSockets.clear()
        runCatching { tcpServerSocket?.close() }
        runCatching { udpSocket?.close() }
        runCatching { socketListenerDispatcher.close() }
    }
}
