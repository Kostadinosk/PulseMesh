// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioRoute(val label: String) {
    EARPIECE("Earpiece"),
    SPEAKER("Speaker"),
    WIRED_HEADSET("Wired"),
    BLUETOOTH_SCO("Bluetooth"),
    BLE_AUDIO("LE Audio")
}

/**
 * Manages Earpiece, Speakerphone, Wired Headset, Bluetooth SCO, and LE Audio routing
 * across Android API 24-35+ while preserving `MODE_IN_COMMUNICATION` so hardware HAL AEC
 * remains operational across every route.
 */
class AudioRouteManager(context: Context) {

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _availableRoutes = MutableStateFlow(listOf(AudioRoute.SPEAKER, AudioRoute.EARPIECE))
    val availableRoutes: StateFlow<List<AudioRoute>> = _availableRoutes.asStateFlow()

    private val _activeRoute = MutableStateFlow(AudioRoute.SPEAKER)
    val activeRoute: StateFlow<AudioRoute> = _activeRoute.asStateFlow()

    @SuppressLint("NewApi")
    fun refreshRoutes(): List<AudioRoute> {
        val mgr = audioManager
        if (mgr == null) {
            val fallback = listOf(AudioRoute.SPEAKER, AudioRoute.EARPIECE)
            _availableRoutes.value = fallback
            return fallback
        }

        val routes = LinkedHashSet<AudioRoute>()
        routes.add(AudioRoute.SPEAKER)
        routes.add(AudioRoute.EARPIECE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                for (dev in mgr.availableCommunicationDevices) {
                    when (dev.type) {
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> routes.add(AudioRoute.SPEAKER)
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> routes.add(AudioRoute.EARPIECE)
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_USB_HEADSET -> routes.add(AudioRoute.WIRED_HEADSET)
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> routes.add(AudioRoute.BLUETOOTH_SCO)
                        AudioDeviceInfo.TYPE_BLE_HEADSET -> routes.add(AudioRoute.BLE_AUDIO)
                    }
                }
            }
        } else {
            runCatching {
                val outputs = mgr.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                for (dev in outputs) {
                    when {
                        dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                dev.type == AudioDeviceInfo.TYPE_USB_HEADSET) -> {
                            routes.add(AudioRoute.WIRED_HEADSET)
                        }
                        dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                            routes.add(AudioRoute.BLUETOOTH_SCO)
                        }
                    }
                }
            }
        }

        val list = routes.toList()
        _availableRoutes.value = list
        return list
    }

    @Suppress("DEPRECATION")
    @SuppressLint("NewApi")
    fun selectRoute(route: AudioRoute): AudioRoute {
        refreshRoutes()
        val mgr = audioManager
        if (mgr == null) {
            _activeRoute.value = route
            return route
        }

        runCatching {
            mgr.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val targetTypes = when (route) {
                    AudioRoute.SPEAKER -> listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                    AudioRoute.EARPIECE -> listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                    AudioRoute.WIRED_HEADSET -> listOf(
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    )
                    AudioRoute.BLUETOOTH_SCO -> listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
                    AudioRoute.BLE_AUDIO -> listOf(
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    )
                }
                val device = mgr.availableCommunicationDevices.firstOrNull { it.type in targetTypes }
                if (device != null) {
                    mgr.setCommunicationDevice(device)
                } else if (route == AudioRoute.SPEAKER) {
                    mgr.isSpeakerphoneOn = true
                } else if (route == AudioRoute.EARPIECE) {
                    mgr.isSpeakerphoneOn = false
                }
            } else {
                when (route) {
                    AudioRoute.SPEAKER -> {
                        if (mgr.isBluetoothScoOn) {
                            runCatching { mgr.stopBluetoothSco() }
                            mgr.isBluetoothScoOn = false
                        }
                        mgr.isSpeakerphoneOn = true
                    }
                    AudioRoute.EARPIECE, AudioRoute.WIRED_HEADSET -> {
                        if (mgr.isBluetoothScoOn) {
                            runCatching { mgr.stopBluetoothSco() }
                            mgr.isBluetoothScoOn = false
                        }
                        mgr.isSpeakerphoneOn = false
                    }
                    AudioRoute.BLUETOOTH_SCO, AudioRoute.BLE_AUDIO -> {
                        mgr.isSpeakerphoneOn = false
                        runCatching { mgr.startBluetoothSco() }
                        mgr.isBluetoothScoOn = true
                    }
                }
            }
        }
        _activeRoute.value = route
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
            mgr.mode = AudioManager.MODE_NORMAL
        }
        _activeRoute.value = AudioRoute.SPEAKER
    }
}
