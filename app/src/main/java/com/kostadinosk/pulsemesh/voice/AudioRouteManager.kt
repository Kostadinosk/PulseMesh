// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.voice

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioRoute(val label: String) {
    EARPIECE("Earpiece"),
    SPEAKER("Speaker"),
    WIRED_HEADSET("Headphones"),
    BLUETOOTH_SCO("Bluetooth"),
    BLE_AUDIO("LE Audio")
}

data class BluetoothAudioDeviceInfo(
    val deviceId: Int,
    val name: String,
    val address: String = "",
    val route: AudioRoute = AudioRoute.BLUETOOTH_SCO,
    val isConnected: Boolean = true,
    val isActive: Boolean = false
)

/**
 * Manages Earpiece, Speakerphone, Wired Headphones, Bluetooth SCO, and LE Audio routing
 * across Android API 24-36, with real-time Bluetooth audio device discovery and direct
 * [AudioDeviceInfo] resolution so [android.media.AudioTrack.setPreferredDevice] and
 * [AudioManager.setCommunicationDevice] stay 100% synchronized.
 */
class AudioRouteManager(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val _availableRoutes = MutableStateFlow(listOf(AudioRoute.SPEAKER, AudioRoute.EARPIECE))
    val availableRoutes: StateFlow<List<AudioRoute>> = _availableRoutes.asStateFlow()

    private val _activeRoute = MutableStateFlow(AudioRoute.SPEAKER)
    val activeRoute: StateFlow<AudioRoute> = _activeRoute.asStateFlow()

    private val _bluetoothDevices = MutableStateFlow<List<BluetoothAudioDeviceInfo>>(emptyList())
    val bluetoothDevices: StateFlow<List<BluetoothAudioDeviceInfo>> = _bluetoothDevices.asStateFlow()

    private val _activeDeviceName = MutableStateFlow("Speaker")
    val activeDeviceName: StateFlow<String> = _activeDeviceName.asStateFlow()

    private val _isScanningBluetoothAudio = MutableStateFlow(false)
    val isScanningBluetoothAudio: StateFlow<Boolean> = _isScanningBluetoothAudio.asStateFlow()

    @Volatile
    private var selectedBluetoothDeviceId: Int? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshRoutes()
            val added = addedDevices.orEmpty()
            val btDev = added.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            if (btDev != null) {
                val targetRoute = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && btDev.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    AudioRoute.BLE_AUDIO
                } else {
                    AudioRoute.BLUETOOTH_SCO
                }
                selectRoute(targetRoute, btDev.id)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshRoutes()
            if (_activeRoute.value !in _availableRoutes.value) {
                selectRoute(AudioRoute.SPEAKER)
            }
        }
    }

    init {
        runCatching {
            audioManager?.registerAudioDeviceCallback(deviceCallback, null)
        }
        refreshRoutes()
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("NewApi", "MissingPermission")
    fun refreshRoutes(): List<AudioRoute> {
        val mgr = audioManager
        if (mgr == null) {
            val fallback = listOf(AudioRoute.SPEAKER, AudioRoute.EARPIECE)
            _availableRoutes.value = fallback
            _bluetoothDevices.value = emptyList()
            return fallback
        }

        val routes = LinkedHashSet<AudioRoute>()
        routes.add(AudioRoute.SPEAKER)
        routes.add(AudioRoute.EARPIECE)

        val discoveredBt = LinkedHashMap<String, BluetoothAudioDeviceInfo>()

        val candidateDevices: List<AudioDeviceInfo> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { addAll(mgr.availableCommunicationDevices) }
            }
            runCatching { addAll(mgr.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) }
        }

        val currentCommId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { mgr.communicationDevice?.id }.getOrNull()
        } else null

        for (dev in candidateDevices) {
            when (dev.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> routes.add(AudioRoute.SPEAKER)
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> routes.add(AudioRoute.EARPIECE)
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET -> routes.add(AudioRoute.WIRED_HEADSET)
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                    routes.add(AudioRoute.BLUETOOTH_SCO)
                    val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { dev.address }.getOrDefault("") else ""
                    val name = dev.productName?.toString()?.trim().orEmpty().ifBlank { "Bluetooth Headset" }
                    val key = addr.ifBlank { "${dev.type}:$name" }
                    val existing = discoveredBt[key]
                    if (existing == null || dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        discoveredBt[key] = BluetoothAudioDeviceInfo(
                            deviceId = dev.id,
                            name = name,
                            address = addr,
                            route = AudioRoute.BLUETOOTH_SCO,
                            isConnected = true,
                            isActive = (selectedBluetoothDeviceId == dev.id || currentCommId == dev.id) &&
                                _activeRoute.value == AudioRoute.BLUETOOTH_SCO
                        )
                    }
                }
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                    routes.add(AudioRoute.BLE_AUDIO)
                    val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { dev.address }.getOrDefault("") else ""
                    val name = dev.productName?.toString()?.trim().orEmpty().ifBlank { "LE Audio Device" }
                    val key = addr.ifBlank { "BLE:$name" }
                    discoveredBt[key] = BluetoothAudioDeviceInfo(
                        deviceId = dev.id,
                        name = name,
                        address = addr,
                        route = AudioRoute.BLE_AUDIO,
                        isConnected = true,
                        isActive = (selectedBluetoothDeviceId == dev.id || currentCommId == dev.id) &&
                            _activeRoute.value == AudioRoute.BLE_AUDIO
                    )
                }
            }
        }

        if (hasBluetoothConnectPermission()) {
            runCatching {
                val adapter: BluetoothAdapter? = bluetoothManager?.adapter
                adapter?.bondedDevices?.forEach { btDev ->
                    val major = btDev.bluetoothClass?.majorDeviceClass
                    if (major == BluetoothClass.Device.Major.AUDIO_VIDEO || major == BluetoothClass.Device.Major.WEARABLE) {
                        val addr = btDev.address.orEmpty()
                        val key = addr.ifBlank { "BONDED:${btDev.name.orEmpty()}" }
                        if (!discoveredBt.containsKey(key)) {
                            discoveredBt[key] = BluetoothAudioDeviceInfo(
                                deviceId = -1,
                                name = btDev.name?.trim().orEmpty().ifBlank { "Paired Bluetooth Audio" },
                                address = addr,
                                route = AudioRoute.BLUETOOTH_SCO,
                                isConnected = false,
                                isActive = false
                            )
                        }
                    }
                }
            }
        }

        val list = routes.toList()
        _availableRoutes.value = list
        _bluetoothDevices.value = discoveredBt.values.sortedWith(
            compareByDescending<BluetoothAudioDeviceInfo> { it.isActive }
                .thenByDescending { it.isConnected }
                .thenBy { it.name.lowercase() }
        )
        return list
    }

    @SuppressLint("MissingPermission")
    fun searchBluetoothAudioDevices(): List<BluetoothAudioDeviceInfo> {
        _isScanningBluetoothAudio.value = true
        runCatching {
            val adapter = bluetoothManager?.adapter
            if (adapter != null && adapter.isEnabled && hasBluetoothScanPermission()) {
                if (!adapter.isDiscovering) {
                    runCatching { adapter.startDiscovery() }
                }
            }
        }
        refreshRoutes()
        _isScanningBluetoothAudio.value = false
        return _bluetoothDevices.value
    }

    @SuppressLint("NewApi")
    fun resolvePreferredOutputDevice(
        route: AudioRoute = _activeRoute.value,
        preferredDeviceId: Int? = selectedBluetoothDeviceId
    ): AudioDeviceInfo? {
        val mgr = audioManager ?: return null
        val targetTypes = when (route) {
            AudioRoute.SPEAKER -> listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            AudioRoute.EARPIECE -> listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            AudioRoute.WIRED_HEADSET -> listOf(
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            )
            AudioRoute.BLUETOOTH_SCO -> listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            )
            AudioRoute.BLE_AUDIO -> listOf(
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            )
        }
        val commDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { mgr.availableCommunicationDevices }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val outputDevices = runCatching { mgr.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
        val allCandidates = commDevices + outputDevices

        if (preferredDeviceId != null && preferredDeviceId > 0) {
            allCandidates.firstOrNull { it.id == preferredDeviceId && it.type in targetTypes }?.let { return it }
        }
        for (type in targetTypes) {
            commDevices.firstOrNull { it.type == type }?.let { return it }
        }
        for (type in targetTypes) {
            outputDevices.firstOrNull { it.type == type }?.let { return it }
        }
        return null
    }

    @Suppress("DEPRECATION")
    @SuppressLint("NewApi")
    fun selectRoute(route: AudioRoute, preferredDeviceId: Int? = null): AudioRoute {
        if (route == AudioRoute.BLUETOOTH_SCO || route == AudioRoute.BLE_AUDIO) {
            if (preferredDeviceId != null && preferredDeviceId > 0) {
                selectedBluetoothDeviceId = preferredDeviceId
            }
        } else {
            selectedBluetoothDeviceId = null
        }

        val mgr = audioManager
        if (mgr == null) {
            _activeRoute.value = route
            _activeDeviceName.value = route.label
            refreshRoutes()
            return route
        }

        val resolvedDevice = resolvePreferredOutputDevice(route, selectedBluetoothDeviceId)

        runCatching {
            mgr.mode = AudioManager.MODE_IN_COMMUNICATION
            when (route) {
                AudioRoute.SPEAKER -> {
                    if (mgr.isBluetoothScoOn) {
                        runCatching { mgr.stopBluetoothSco() }
                        mgr.isBluetoothScoOn = false
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val spk = mgr.availableCommunicationDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        }
                        if (spk != null) {
                            mgr.setCommunicationDevice(spk)
                        } else {
                            mgr.clearCommunicationDevice()
                        }
                    }
                    mgr.isSpeakerphoneOn = true
                }
                AudioRoute.EARPIECE -> {
                    if (mgr.isBluetoothScoOn) {
                        runCatching { mgr.stopBluetoothSco() }
                        mgr.isBluetoothScoOn = false
                    }
                    mgr.isSpeakerphoneOn = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val ear = mgr.availableCommunicationDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                        }
                        if (ear != null) {
                            mgr.setCommunicationDevice(ear)
                        } else {
                            mgr.clearCommunicationDevice()
                        }
                    }
                }
                AudioRoute.WIRED_HEADSET -> {
                    if (mgr.isBluetoothScoOn) {
                        runCatching { mgr.stopBluetoothSco() }
                        mgr.isBluetoothScoOn = false
                    }
                    mgr.isSpeakerphoneOn = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val wired = mgr.availableCommunicationDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        }
                        if (wired != null) {
                            mgr.setCommunicationDevice(wired)
                        }
                    }
                }
                AudioRoute.BLUETOOTH_SCO, AudioRoute.BLE_AUDIO -> {
                    mgr.isSpeakerphoneOn = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val targetTypes = if (route == AudioRoute.BLE_AUDIO) {
                            listOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
                        } else {
                            listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
                        }
                        val btDev = mgr.availableCommunicationDevices.firstOrNull {
                            selectedBluetoothDeviceId != null && it.id == selectedBluetoothDeviceId
                        } ?: mgr.availableCommunicationDevices.firstOrNull { it.type in targetTypes }
                        if (btDev != null) {
                            mgr.setCommunicationDevice(btDev)
                        } else {
                            runCatching { mgr.startBluetoothSco() }
                            mgr.isBluetoothScoOn = true
                        }
                    } else {
                        runCatching { mgr.startBluetoothSco() }
                        mgr.isBluetoothScoOn = true
                    }
                }
            }
        }

        _activeRoute.value = route
        _activeDeviceName.value = resolvedDevice?.productName?.toString()?.trim()?.takeIf {
            it.isNotBlank() && (route == AudioRoute.BLUETOOTH_SCO || route == AudioRoute.BLE_AUDIO || route == AudioRoute.WIRED_HEADSET)
        } ?: route.label
        refreshRoutes()
        return route
    }

    @Suppress("DEPRECATION")
    fun clearCallAudioMode() {
        val mgr = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mgr.clearCommunicationDevice()
            } else if (mgr.isBluetoothScoOn) {
                runCatching { mgr.stopBluetoothSco() }
                mgr.isBluetoothScoOn = false
            }
            mgr.isSpeakerphoneOn = false
            mgr.mode = AudioManager.MODE_NORMAL
        }
        selectedBluetoothDeviceId = null
        _activeRoute.value = AudioRoute.SPEAKER
        _activeDeviceName.value = AudioRoute.SPEAKER.label
        refreshRoutes()
    }

    fun release() {
        runCatching {
            audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        }
    }
}
