// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.service

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

data class EmergencyToolkitState(
    val isTorchMorseActive: Boolean = false,
    val isSirenActive: Boolean = false,
    val torchAvailable: Boolean = false
)

/**
 * PulseMesh v2.0 Offline Emergency Kit:
 * - Real Camera2 Flashlight Morse SOS (... --- ...) strobe
 * - Real AudioTrack high-decibel dual-tone emergency siren whistle
 */
class EmergencyToolkitService(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(EmergencyToolkitState(torchAvailable = findFlashCameraId() != null))
    val state: StateFlow<EmergencyToolkitState> = _state.asStateFlow()

    private var torchJob: Job? = null
    private var sirenJob: Job? = null

    private fun findFlashCameraId(): String? = runCatching {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    fun toggleTorchMorseSos() {
        if (_state.value.isTorchMorseActive) {
            stopTorchMorse()
        } else {
            startTorchMorse()
        }
    }

    fun startTorchMorse() {
        val camId = findFlashCameraId() ?: return
        torchJob?.cancel()
        _state.value = _state.value.copy(isTorchMorseActive = true, torchAvailable = true)
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return

        // Standard Morse SOS: ... --- ...
        val patternMs = listOf(
            180L to true, 160L to false, 180L to true, 160L to false, 180L to true, 360L to false,
            520L to true, 180L to false, 520L to true, 180L to false, 520L to true, 360L to false,
            180L to true, 160L to false, 180L to true, 160L to false, 180L to true, 1100L to false
        )

        torchJob = scope.launch(Dispatchers.Default) {
            try {
                while (isActive) {
                    for ((durMs, on) in patternMs) {
                        if (!isActive) break
                        runCatching { cm.setTorchMode(camId, on) }
                        delay(durMs)
                    }
                }
            } finally {
                runCatching { cm.setTorchMode(camId, false) }
                _state.value = _state.value.copy(isTorchMorseActive = false)
            }
        }
    }

    fun stopTorchMorse() {
        torchJob?.cancel()
        torchJob = null
        findFlashCameraId()?.let { camId ->
            runCatching {
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                cm?.setTorchMode(camId, false)
            }
        }
        _state.value = _state.value.copy(isTorchMorseActive = false)
    }

    fun toggleSiren() {
        if (_state.value.isSirenActive) {
            stopSiren()
        } else {
            startSiren()
        }
    }

    fun startSiren() {
        sirenJob?.cancel()
        _state.value = _state.value.copy(isSirenActive = true)
        sirenJob = scope.launch(Dispatchers.Default) {
            val sampleRate = 22050
            val chunkSamples = sampleRate / 10 // 100ms chunks
            val pcm = ShortArray(chunkSamples)
            var track: AudioTrack? = null
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(chunkSamples * 2)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()

                var phase = 0.0
                var step = 0
                while (isActive) {
                    // Alternating high-visibility rescue siren (920 Hz <-> 1680 Hz)
                    val freq = if ((step / 4) % 2 == 0) 920.0 + (step % 4) * 190.0 else 1680.0 - (step % 4) * 190.0
                    val phaseInc = 2.0 * PI * freq / sampleRate
                    for (i in 0 until chunkSamples) {
                        pcm[i] = (sin(phase) * 29000.0).toInt().toShort()
                        phase += phaseInc
                        if (phase > 2.0 * PI) phase -= 2.0 * PI
                    }
                    track.write(pcm, 0, chunkSamples)
                    step++
                }
            } catch (_: Throwable) {
                // Unit test / audio unavailable fallback
            } finally {
                runCatching { track?.stop() }
                runCatching { track?.release() }
                _state.value = _state.value.copy(isSirenActive = false)
            }
        }
    }

    fun stopSiren() {
        sirenJob?.cancel()
        sirenJob = null
        _state.value = _state.value.copy(isSirenActive = false)
    }

    fun release() {
        stopTorchMorse()
        stopSiren()
    }
}
