// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.voice

import android.view.KeyEvent

/**
 * Hardware Push-to-Talk key event interceptor supporting:
 * - Volume Down (`KEYCODE_VOLUME_DOWN`)
 * - Wired/Bluetooth headset hook & media play/pause (`KEYCODE_HEADSETHOOK`, `KEYCODE_MEDIA_PLAY_PAUSE`)
 * - Dedicated rugged-device PTT keys (`KEYCODE_PTT` / `259` / `KEYCODE_MEDIA_RECORD`)
 */
class HardwarePttController(
    private val isHardwarePttEnabled: () -> Boolean,
    private val isKeyguardLocked: () -> Boolean,
    private val isCurrentlyTransmitting: () -> Boolean,
    private val onStartPtt: () -> Unit,
    private val onStopPtt: () -> Unit
) {
    companion object {
        // Android dedicated PTT hardware button keycode (rugged devices & external BT RSM mics)
        const val KEYCODE_HARDWARE_PTT = 259

        fun isSupportedPttKeyCode(keyCode: Int): Boolean {
            return keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                keyCode == KeyEvent.KEYCODE_MEDIA_RECORD ||
                keyCode == KEYCODE_HARDWARE_PTT
        }
    }

    fun handleKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        if (!isSupportedPttKeyCode(event.keyCode)) return false
        if (!isHardwarePttEnabled() || isKeyguardLocked()) return false

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0 && !isCurrentlyTransmitting()) {
                    onStartPtt()
                }
                true
            }
            KeyEvent.ACTION_UP -> {
                if (isCurrentlyTransmitting()) {
                    onStopPtt()
                }
                true
            }
            else -> false
        }
    }
}
