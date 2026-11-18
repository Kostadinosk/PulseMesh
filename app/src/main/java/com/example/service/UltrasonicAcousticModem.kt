package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Near-Ultrasonic Acoustic Air-Gap BFSK Modem (18.0 kHz – 19.2 kHz @ 44,100 Hz).
 *
 * Provides an opt-in, zero-RF physical transport channel across room-scale air-gapped devices
 * using standard phone speakers and microphones:
 * - Preamble tone: `17,500 Hz` (2 symbols)
 * - Bit `0` mark: `18,100 Hz`
 * - Bit `1` mark: `18,900 Hz`
 * - Symbol duration: `25 ms` (1102 samples @ 44.1 kHz) with raised-cosine envelope shaping
 *   so transitions are inaudible/click-free.
 * - Frame format (32 bits = 4 bytes):
 *   `[opCode: 4 bits | nodeIdShort: 16 bits | targetOrFlag: 8 bits | crc4: 4 bits]`
 * - Strictly yields the microphone whenever a voice call or PTT capture is active.
 */
class UltrasonicAcousticModem(
    private val context: Context,
    private val scope: CoroutineScope,
    private val isVoiceMicBusy: () -> Boolean,
    private val onAcousticSignalDecoded: (type: String, srcNodeIdHex: String) -> Unit
) {
    companion object {
        const val SAMPLE_RATE_44K = 44100
        const val SYMBOL_SAMPLES = 1102 // ~25 ms per symbol @ 44.1 kHz
        const val FREQ_PREAMBLE_HZ = 17500.0
        const val FREQ_BIT0_HZ = 18100.0
        const val FREQ_BIT1_HZ = 18900.0

        const val OP_CALL_RING = 0x1
        const val OP_CALL_ACCEPT = 0x2
        const val OP_CALL_ACCEPT_ACK = 0x3
        const val OP_SOS_BEACON = 0x4
        const val OP_PING_DISCOVERY = 0x5

        fun computeCrc4(bits28: Int): Int {
            var crc = 0xF
            for (i in 27 downTo 0) {
                val bit = (bits28 ushr i) and 1
                val msb = (crc ushr 3) and 1
                crc = ((crc shl 1) and 0xF)
                if ((bit xor msb) != 0) {
                    crc = crc xor 0x3 // x^4 + x + 1
                }
            }
            return crc and 0xF
        }

        fun packFrame32(opCode: Int, srcNodeIdHex: String, flagByte: Int = 0): Int {
            val srcShort = OffGridBleMeshEngine.normalizeId(srcNodeIdHex).toIntOrNull(16) ?: 0
            val data28 = ((opCode and 0xF) shl 24) or
                ((srcShort and 0xFFFF) shl 8) or
                (flagByte and 0xFF)
            val crc = computeCrc4(data28)
            return (data28 shl 4) or (crc and 0xF)
        }

        fun unpackFrame32(frame32: Int): Triple<Int, String, Int>? {
            val data28 = (frame32 ushr 4) and 0x0FFFFFFF
            val rxCrc = frame32 and 0xF
            if (computeCrc4(data28) != rxCrc) return null
            val opCode = (data28 ushr 24) and 0xF
            val srcShort = (data28 ushr 8) and 0xFFFF
            val flagByte = data28 and 0xFF
            if (srcShort == 0) return null
            return Triple(opCode, "0x%04X".format(srcShort), flagByte)
        }

        /**
         * Single-bin Goertzel power detector at `targetFreqHz` over `samples[offset .. offset + length)`.
         */
        fun goertzelMagnitudeSquared(
            samples: ShortArray,
            offset: Int,
            length: Int,
            targetFreqHz: Double,
            sampleRate: Int = SAMPLE_RATE_44K
        ): Double {
            if (length <= 0 || offset + length > samples.size) return 0.0
            val k = (0.5 + (length * targetFreqHz) / sampleRate).toInt()
            val omega = (2.0 * PI * k) / length
            val coeff = 2.0 * cos(omega)
            var q0 = 0.0
            var q1 = 0.0
            var q2 = 0.0
            for (i in 0 until length) {
                q0 = coeff * q1 - q2 + samples[offset + i].toDouble()
                q2 = q1
                q1 = q0
            }
            return q1 * q1 + q2 * q2 - q1 * q2 * coeff
        }

        /**
         * Synthesizes a 34-symbol (2 preamble + 32 data bits) 18–19 kHz BFSK ultrasonic burst.
         */
        fun modulateFrameToPcm44k(frame32: Int): ShortArray {
            val totalSymbols = 34
            val out = ShortArray(totalSymbols * SYMBOL_SAMPLES)
            val rampSamples = 110 // 2.5 ms raised-cosine edge to avoid audible clicks

            fun writeToneSymbol(symbolIdx: Int, freqHz: Double) {
                val baseOffset = symbolIdx * SYMBOL_SAMPLES
                for (i in 0 until SYMBOL_SAMPLES) {
                    val env = when {
                        i < rampSamples -> 0.5 * (1.0 - cos(PI * i / rampSamples))
                        i > SYMBOL_SAMPLES - rampSamples -> 0.5 * (1.0 - cos(PI * (SYMBOL_SAMPLES - i) / rampSamples))
                        else -> 1.0
                    }
                    val phase = 2.0 * PI * freqHz * i / SAMPLE_RATE_44K
                    out[baseOffset + i] = (sin(phase) * env * 14000.0).toInt().coerceIn(-32767, 32767).toShort()
                }
            }

            writeToneSymbol(0, FREQ_PREAMBLE_HZ)
            writeToneSymbol(1, FREQ_PREAMBLE_HZ)
            for (bitIdx in 0 until 32) {
                val bit = (frame32 ushr (31 - bitIdx)) and 1
                val freq = if (bit == 1) FREQ_BIT1_HZ else FREQ_BIT0_HZ
                writeToneSymbol(2 + bitIdx, freq)
            }
            return out
        }
    }

    @Volatile
    var isEnabled: Boolean = false
        private set

    private var rxJob: Job? = null

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (enabled) {
            startListeningLoop()
        } else {
            stopListeningLoop()
        }
    }

    fun transmitControlSignal(type: String, localNodeIdHex: String) {
        if (!isEnabled) return
        val opCode = when (type) {
            "CALL_RING" -> OP_CALL_RING
            "CALL_ACCEPT" -> OP_CALL_ACCEPT
            "CALL_ACCEPT_ACK" -> OP_CALL_ACCEPT_ACK
            "SOS" -> OP_SOS_BEACON
            else -> OP_PING_DISCOVERY
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val frame32 = packFrame32(opCode, localNodeIdHex)
                val pcm44k = modulateFrameToPcm44k(frame32)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE_44K)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm44k.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.write(pcm44k, 0, pcm44k.size)
                    track.play()
                    delay(920L)
                    runCatching { track.stop() }
                }
                runCatching { track.release() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startListeningLoop() {
        if (rxJob?.isActive == true) return
        rxJob = scope.launch(Dispatchers.IO) {
            while (isActive && isEnabled) {
                if (isVoiceMicBusy()) {
                    delay(800L)
                    continue
                }
                val hasMic = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasMic) {
                    delay(2000L)
                    continue
                }

                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE_44K,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) {
                    delay(2000L)
                    continue
                }

                var rec: AudioRecord? = null
                try {
                    rec = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE_44K,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuf * 2, SYMBOL_SAMPLES * 8)
                    )
                    if (rec.state != AudioRecord.STATE_INITIALIZED) {
                        delay(1500L)
                        continue
                    }
                    rec.startRecording()
                    val symbolBuf = ShortArray(SYMBOL_SAMPLES)
                    var consecutivePreamble = 0

                    while (isActive && isEnabled && !isVoiceMicBusy()) {
                        var read = 0
                        while (read < SYMBOL_SAMPLES && isActive && isEnabled && !isVoiceMicBusy()) {
                            val r = rec.read(symbolBuf, read, SYMBOL_SAMPLES - read)
                            if (r > 0) read += r else break
                        }
                        if (read < SYMBOL_SAMPLES || isVoiceMicBusy()) break

                        val pPreamble = goertzelMagnitudeSquared(symbolBuf, 0, SYMBOL_SAMPLES, FREQ_PREAMBLE_HZ)
                        val p0 = goertzelMagnitudeSquared(symbolBuf, 0, SYMBOL_SAMPLES, FREQ_BIT0_HZ)
                        val p1 = goertzelMagnitudeSquared(symbolBuf, 0, SYMBOL_SAMPLES, FREQ_BIT1_HZ)

                        if (pPreamble > 2.5e10 && pPreamble > p0 * 1.6 && pPreamble > p1 * 1.6) {
                            consecutivePreamble++
                            if (consecutivePreamble >= 2) {
                                consecutivePreamble = 0
                                var decodedBits = 0
                                var validCapture = true
                                for (bitIdx in 0 until 32) {
                                    var bRead = 0
                                    while (bRead < SYMBOL_SAMPLES && isActive && !isVoiceMicBusy()) {
                                        val r = rec.read(symbolBuf, bRead, SYMBOL_SAMPLES - bRead)
                                        if (r > 0) bRead += r else break
                                    }
                                    if (bRead < SYMBOL_SAMPLES) {
                                        validCapture = false
                                        break
                                    }
                                    val mag0 = goertzelMagnitudeSquared(symbolBuf, 0, SYMBOL_SAMPLES, FREQ_BIT0_HZ)
                                    val mag1 = goertzelMagnitudeSquared(symbolBuf, 0, SYMBOL_SAMPLES, FREQ_BIT1_HZ)
                                    val bit = if (mag1 > mag0) 1 else 0
                                    decodedBits = (decodedBits shl 1) or bit
                                }
                                if (validCapture) {
                                    unpackFrame32(decodedBits)?.let { (opCode, srcNodeId, _) ->
                                        val sigType = when (opCode) {
                                            OP_CALL_RING -> "CALL_RING"
                                            OP_CALL_ACCEPT -> "CALL_ACCEPT"
                                            OP_CALL_ACCEPT_ACK -> "CALL_ACCEPT_ACK"
                                            OP_SOS_BEACON -> "SOS"
                                            else -> "HEARTBEAT"
                                        }
                                        onAcousticSignalDecoded(sigType, srcNodeId)
                                    }
                                }
                            }
                        } else {
                            consecutivePreamble = 0
                        }
                    }
                } catch (_: Throwable) {
                    delay(1000L)
                } finally {
                    runCatching { rec?.stop() }
                    runCatching { rec?.release() }
                }
                delay(250L)
            }
        }
    }

    fun stopListeningLoop() {
        rxJob?.cancel()
        rxJob = null
    }
}
