// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.util

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HotspotState(
    val isActive: Boolean = false,
    val ssid: String = "",
    val passphrase: String = "",
    val statusMessage: String = "Local Hotspot Idle"
)

/**
 * Manual 1-Tap Local Wi-Fi Hotspot Manager with Wi-Fi Direct P2P Group Owner Fallback.
 * 1. Attempts WifiManager.startLocalOnlyHotspot (192.168.43.1) for zero-hop off-grid Wi-Fi voice & chat.
 * 2. Automatically falls back to WifiP2pManager.createGroup (192.168.49.1) if carrier/OEM blocks LocalOnlyHotspot.
 * 3. Emits live SSID & Passphrase so AutoHotspotNegotiator can broadcast them over BLE GATT to peers.
 */
class LocalHotspotManager(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val wifiP2pManager = context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var p2pQueryRunnable: Runnable? = null

    private val _hotspotState = MutableStateFlow(HotspotState())
    val hotspotState: StateFlow<HotspotState> = _hotspotState.asStateFlow()

    var onHotspotStartedCallback: ((ssid: String, passphrase: String, hostIp: String) -> Unit)? = null
    var onHotspotStoppedCallback: (() -> Unit)? = null
    var onLocalHotspotFailedCallback: ((reason: Int) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun toggleLocalHotspot() {
        if (_hotspotState.value.isActive) {
            stopLocalHotspot()
        } else {
            startLocalHotspot()
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocalHotspot() {
        if (_hotspotState.value.isActive) {
            val refreshedIp = detectActiveApIpv4() ?: "192.168.43.1"
            onHotspotStartedCallback?.invoke(
                _hotspotState.value.ssid.ifBlank { "PulseMesh-AP" },
                _hotspotState.value.passphrase,
                refreshedIp
            )
            return
        }

        // Fast-path: if Android's System Portable Hotspot (ap0/swlan0/wlan1/wlan2) is ALREADY active, adopt it directly!
        val alreadyActiveApIp = detectDedicatedApOnlyIpv4()
        if (alreadyActiveApIp != null) {
            _hotspotState.value = HotspotState(
                isActive = true,
                ssid = "System Hotspot (Active)",
                passphrase = "",
                statusMessage = "Using Active System Hotspot • $alreadyActiveApIp"
            )
            onHotspotStartedCallback?.invoke("System Hotspot", "", alreadyActiveApIp)
            return
        }

        _hotspotState.value = HotspotState(
            isActive = false,
            statusMessage = "Starting Local Wi-Fi Hotspot..."
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && wifiManager != null) {
            val started = runCatching {
                wifiManager?.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation?) {
                        super.onStarted(res)
                        reservation = res
                        val ssid = extractSsid(res) ?: "PulseMesh-AP"
                        val pass = extractPassphrase(res) ?: generateEphemeralPassphrase()
                        val initialIp = detectActiveApIpv4() ?: "192.168.43.1"
                        _hotspotState.value = HotspotState(
                            isActive = true,
                            ssid = ssid,
                            passphrase = pass,
                            statusMessage = "Local Hotspot Active ($ssid) • $initialIp"
                        )
                        onHotspotStartedCallback?.invoke(ssid, pass, initialIp)
                        // Re-probe SoftAP IPv4 repeatedly (Android 11-16 assigns random SoftAP IPs 0.5-4s after onStarted)
                        var lastReportedIp = initialIp
                        val probeDelays = longArrayOf(500L, 1200L, 2500L, 4500L)
                        for (delayMs in probeDelays) {
                            mainHandler.postDelayed({
                                if (_hotspotState.value.isActive) {
                                    val resolvedIp = detectActiveApIpv4() ?: lastReportedIp
                                    if (resolvedIp != lastReportedIp) {
                                        lastReportedIp = resolvedIp
                                        _hotspotState.value = _hotspotState.value.copy(
                                            statusMessage = "Local Hotspot Active ($ssid) • $resolvedIp"
                                        )
                                        onHotspotStartedCallback?.invoke(ssid, pass, resolvedIp)
                                    }
                                }
                            }, delayMs)
                        }
                    }

                    override fun onStopped() {
                        super.onStopped()
                        reservation = null
                        _hotspotState.value = HotspotState(
                            isActive = false,
                            statusMessage = "Local Hotspot Idle"
                        )
                        onHotspotStoppedCallback?.invoke()
                    }

                    override fun onFailed(reason: Int) {
                        super.onFailed(reason)
                        reservation = null
                        // Code 3 (ERROR_INCOMPATIBLE_MODE): Android System Hotspot is already ON -> adopt its IP!
                        val existingApIp = detectDedicatedApOnlyIpv4() ?: detectActiveApIpv4()
                        if (reason == WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE && existingApIp != null) {
                            _hotspotState.value = HotspotState(
                                isActive = true,
                                ssid = "System Hotspot (Active)",
                                passphrase = "",
                                statusMessage = "Using Active System Hotspot • $existingApIp"
                            )
                            onHotspotStartedCallback?.invoke("System Hotspot", "", existingApIp)
                            return
                        }
                        onLocalHotspotFailedCallback?.invoke(reason)
                        startWifiDirectP2pHotspotFallback()
                    }
                }, mainHandler)
                true
            }.getOrDefault(false)

            if (!started) {
                startWifiDirectP2pHotspotFallback()
            }
        } else {
            startWifiDirectP2pHotspotFallback()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startWifiDirectP2pHotspotFallback() {
        val mgr = wifiP2pManager
        val chan = p2pChannel ?: runCatching {
            wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)
        }.getOrNull()?.also { p2pChannel = it }

        if (mgr == null || chan == null) {
            _hotspotState.value = HotspotState(
                isActive = false,
                statusMessage = "Wi-Fi Hotspot unavailable on this hardware"
            )
            return
        }

        runCatching {
            mgr.requestGroupInfo(chan) { existing ->
                if (existing != null && !existing.networkName.isNullOrBlank() && !existing.passphrase.isNullOrBlank()) {
                    _hotspotState.value = HotspotState(
                        isActive = true,
                        ssid = existing.networkName,
                        passphrase = existing.passphrase,
                        statusMessage = "Wi-Fi Direct Hotspot Active (${existing.networkName}) • 192.168.49.1"
                    )
                    onHotspotStartedCallback?.invoke(existing.networkName, existing.passphrase, "192.168.49.1")
                } else {
                    mgr.createGroup(chan, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            p2pQueryRunnable?.let { mainHandler.removeCallbacks(it) }
                            val queryRunnable = Runnable {
                                runCatching {
                                    mgr.requestGroupInfo(chan) { group ->
                                        if (group != null && !group.networkName.isNullOrBlank() && !group.passphrase.isNullOrBlank()) {
                                            _hotspotState.value = HotspotState(
                                                isActive = true,
                                                ssid = group.networkName,
                                                passphrase = group.passphrase,
                                                statusMessage = "Wi-Fi Direct Hotspot Active (${group.networkName}) • 192.168.49.1"
                                            )
                                            onHotspotStartedCallback?.invoke(group.networkName, group.passphrase, "192.168.49.1")
                                        }
                                    }
                                }
                            }
                            p2pQueryRunnable = queryRunnable
                            mainHandler.postDelayed(queryRunnable, 800L)
                        }

                        override fun onFailure(reason: Int) {
                            _hotspotState.value = HotspotState(
                                isActive = false,
                                statusMessage = "Hotspot Busy (Code $reason) — Ensure Wi-Fi is ON"
                            )
                        }
                    })
                }
            }
        }
    }

    private fun detectDedicatedApOnlyIpv4(): String? {
        return runCatching {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
            val candidates = mutableListOf<Pair<Int, String>>()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") ||
                    name.startsWith("ppp") || name.startsWith("dummy") || name.startsWith("epdg") || name.startsWith("iwlan")
                ) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        val isPrivate = ip.startsWith("192.168.") || ip.startsWith("10.") ||
                            (ip.startsWith("172.") && (ip.substringAfter("172.").substringBefore('.').toIntOrNull() in 16..31))
                        if (!isPrivate) continue
                        val prio = when {
                            name.startsWith("ap_br") || name.startsWith("ap") || name.startsWith("swlan") ||
                                name.startsWith("softap") || name.startsWith("sap") || name.startsWith("tether") -> 130
                            name.startsWith("wlan") && name != "wlan0" -> 125
                            ip.startsWith("192.168.43.") || ip.startsWith("192.168.137.") -> 120
                            else -> 0
                        }
                        if (prio > 0) candidates.add(prio to ip)
                    }
                }
            }
            candidates.maxByOrNull { it.first }?.second
        }.getOrNull()
    }

    private fun detectActiveApIpv4(): String? {
        return runCatching {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
            val candidates = mutableListOf<Pair<Int, String>>()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") ||
                    name.startsWith("ppp") || name.startsWith("dummy") || name.startsWith("epdg") || name.startsWith("iwlan")
                ) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        val isPrivate = ip.startsWith("192.168.") || ip.startsWith("10.") ||
                            (ip.startsWith("172.") && (ip.substringAfter("172.").substringBefore('.').toIntOrNull() in 16..31))
                        if (!isPrivate) continue
                        val prio = when {
                            name.startsWith("ap_br") || name.startsWith("ap") || name.startsWith("swlan") ||
                                name.startsWith("softap") || name.startsWith("sap") || name.startsWith("tether") ||
                                name.startsWith("br") -> 130
                            name.startsWith("wlan") && name != "wlan0" -> 125
                            name.startsWith("p2p") || ip == "192.168.49.1" -> 118
                            name.startsWith("wlan") && ip.endsWith(".1") -> 110
                            name.startsWith("wlan") -> 75
                            else -> 0
                        }
                        if (prio > 0) candidates.add(prio to ip)
                    }
                }
            }
            candidates.maxByOrNull { it.first }?.second
        }.getOrNull()
    }

    private fun extractSsid(res: WifiManager.LocalOnlyHotspotReservation?): String? {
        if (res == null) return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                res.softApConfiguration.ssid ?: res.wifiConfiguration?.SSID?.trim('"')
            } else {
                @Suppress("DEPRECATION")
                res.wifiConfiguration?.SSID?.trim('"')
            }
        }.getOrNull()
    }

    private fun extractPassphrase(res: WifiManager.LocalOnlyHotspotReservation?): String? {
        if (res == null) return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                res.softApConfiguration.passphrase ?: res.wifiConfiguration?.preSharedKey?.trim('"')
            } else {
                @Suppress("DEPRECATION")
                res.wifiConfiguration?.preSharedKey?.trim('"')
            }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    fun stopLocalHotspot() {
        p2pQueryRunnable?.let { mainHandler.removeCallbacks(it) }
        p2pQueryRunnable = null
        runCatching {
            reservation?.close()
        }
        reservation = null

        runCatching {
            val mgr = wifiP2pManager
            val chan = p2pChannel
            if (mgr != null && chan != null) {
                mgr.removeGroup(chan, null)
            }
        }

        _hotspotState.value = HotspotState(
            isActive = false,
            statusMessage = "Local Hotspot Idle"
        )
        onHotspotStoppedCallback?.invoke()
    }

    private fun generateEphemeralPassphrase(length: Int = 12): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val rng = java.security.SecureRandom()
        return buildString(length) {
            repeat(length) {
                append(alphabet[rng.nextInt(alphabet.length)])
            }
        }
    }
}
