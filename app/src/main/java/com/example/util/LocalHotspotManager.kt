// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.util

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
                        val pass = extractPassphrase(res) ?: "pulsemesh2026"
                        val initialIp = detectActiveApIpv4() ?: "192.168.43.1"
                        _hotspotState.value = HotspotState(
                            isActive = true,
                            ssid = ssid,
                            passphrase = pass,
                            statusMessage = "Local Hotspot Active ($ssid) • $initialIp"
                        )
                        onHotspotStartedCallback?.invoke(ssid, pass, initialIp)
                        // Re-probe interface IP after 1.2s since Android 11-16 assigns random SoftAP IPs asynchronously
                        mainHandler.postDelayed({
                            if (_hotspotState.value.isActive) {
                                val resolvedIp = detectActiveApIpv4() ?: initialIp
                                if (resolvedIp != initialIp) {
                                    _hotspotState.value = _hotspotState.value.copy(
                                        statusMessage = "Local Hotspot Active ($ssid) • $resolvedIp"
                                    )
                                    onHotspotStartedCallback?.invoke(ssid, pass, resolvedIp)
                                }
                            }
                        }, 1200L)
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
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val prio = when {
                            name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") -> 120
                            name.startsWith("p2p") || ip == "192.168.49.1" -> 115
                            (name.startsWith("wlan") && name != "wlan0") -> 110
                            name.startsWith("wlan") && ip.endsWith(".1") -> 105
                            name.startsWith("wlan") -> 70
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
}
