// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Audible Bell 202 Acoustic AFSK Air-Gap Modem (PulseMesh v1.4.1).
 *
 * Byte-for-byte compatible with the PulseMesh Web/iPhone PWA Web Audio API Bell 202 modem so
 * Android phones, iPhones, laptops, and handheld FM radios can exchange short UTF-8 messages
 * and SOS beacons over speaker + microphone with zero Wi-Fi, zero Bluetooth, and zero cellular.
 *
 * Wire specification:
 * - Sample rate: 44,100 Hz mono 16-bit PCM (Goertzel adapts cleanly to any sample rate).
 * - Symbol duration: 20 ms (50 baud, 882 samples @ 44.1 kHz) for high reliability over room reverb.
 * - Frequencies:
 *   - Preamble pilot: 1650 Hz (4 symbols = 80 ms)
 *   - Mark (bit 1):   1200 Hz (Bell 202 standard)
 *   - Space (bit 0):  2200 Hz (Bell 202 standard)
 * - Frame bytes (MSB-first per byte):
 *   `[0x50, 0x4D, typeByte, srcHi, srcLo, payloadLen, ...utf8Payload, crcHi, crcLo]`
 *   where `CRC-16/CCITT-FALSE` (poly `0x1021`, init `0xFFFF`) covers `[typeByte .. payloadEnd]`.
 */
class Bell202AcousticModem(
    private val context: Context,
    private val scope: CoroutineScope,
    private val isVoiceMicBusy: () -> Boolean,
    private val onBell202FrameDecoded: (frame: DecodedBell202Frame) -> Unit
) {
    data class DecodedBell202Frame(
        val typeByte: Int,
        val srcNodeIdHex: String,
        val text: String
    )

    companion object {
        const val SAMPLE_RATE = 44100
        const val SYMBOL_DURATION_MS = 20
        const val SYMBOL_SAMPLES = (SAMPLE_RATE * SYMBOL_DURATION_MS) / 1000 // 882 samples @ 44.1 kHz
        const val PREAMBLE_SYMBOLS = 4

        const val FREQ_PREAMBLE_HZ = 1650.0
        const val FREQ_MARK_1_HZ = 1200.0
        const val FREQ_SPACE_0_HZ = 2200.0

        const val SYNC_0: Byte = 0x50 // 'P'
        const val SYNC_1: Byte = 0x4D // 'M'
        const val MAX_PAYLOAD_BYTES = 96

        const val TYPE_CHAT = 0x01
        const val TYPE_SOS = 0x02
        const val TYPE_CALL_RING = 0x03
        const val TYPE_KEY_VERIFY = 0x04

        fun crc16Ccitt(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
            var crc = 0xFFFF
            for (i in offset until (offset + length)) {
                crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
                for (bit in 0 until 8) {
                    crc = if ((crc and 0x8000) != 0) {
                        ((crc shl 1) xor 0x1021) and 0xFFFF
                    } else {
                        (crc shl 1) and 0xFFFF
                    }
                }
            }
            return crc and 0xFFFF
        }

        fun encodeFrameBytes(typeByte: Int, srcNodeIdHex: String, text: String): ByteArray {
            val rawPayload = text.toByteArray(Charsets.UTF_8)
            val payload = if (rawPayload.size > MAX_PAYLOAD_BYTES) rawPayload.copyOf(MAX_PAYLOAD_BYTES) else rawPayload
            val srcShort = OffGridBleMeshEngine.normalizeId(srcNodeIdHex).toIntOrNull(16) ?: 0x0001
            val body = ByteArray(4 + payload.size)
            body[0] = (typeByte and 0xFF).toByte()
            body[1] = ((srcShort ushr 8) and 0xFF).toByte()
            body[2] = (srcShort and 0xFF).toByte()
            body[3] = (payload.size and 0xFF).toByte()
            if (payload.isNotEmpty()) {
                System.arraycopy(payload, 0, body, 4, payload.size)
            }
            val crc = crc16Ccitt(body)
            val out = ByteArray(2 + body.size + 2)
            out[0] = SYNC_0
            out[1] = SYNC_1
            System.arraycopy(body, 0, out, 2, body.size)
            out[2 + body.size] = ((crc ushr 8) and 0xFF).toByte()
            out[2 + body.size + 1] = (crc and 0xFF).toByte()
            return out
        }

        fun decodeFrameBytes(frame: ByteArray): DecodedBell202Frame? {
            if (frame.size < 8) return null
            if (frame[0] != SYNC_0 || frame[1] != SYNC_1) return null
            val typeByte = frame[2].toInt() and 0xFF
            val srcShort = ((frame[3].toInt() and 0xFF) shl 8) or (frame[4].toInt() and 0xFF)
            val payloadLen = frame[5].toInt() and 0xFF
            if (payloadLen > MAX_PAYLOAD_BYTES) return null
            val expectedLen = 2 + 4 + payloadLen + 2
            if (frame.size < expectedLen) return null
            val computedCrc = crc16Ccitt(frame, 2, 4 + payloadLen)
            val rxCrc = ((frame[6 + payloadLen].toInt() and 0xFF) shl 8) or (frame[7 + payloadLen].toInt() and 0xFF)
            if (computedCrc != rxCrc) return null
            val text = String(frame, 6, payloadLen, Charsets.UTF_8)
            return DecodedBell202Frame(
                typeByte = typeByte,
                srcNodeIdHex = "0x%04X".format(srcShort),
                text = text
            )
        }

        /**
         * Phase-continuous Bell 202 AFSK synthesis with raised-cosine start/end edges.
         */
        fun modulateFrameToPcm(frameBytes: ByteArray, sampleRate: Int = SAMPLE_RATE): ShortArray {
            val symSamples = (sampleRate * SYMBOL_DURATION_MS) / 1000
            val totalSymbols = PREAMBLE_SYMBOLS + frameBytes.size * 8
            val out = ShortArray(totalSymbols * symSamples)
            val rampSamples = (sampleRate * 2) / 1000 // 2 ms soft edge
            var phase = 0.0
            var sampleIdx = 0

            fun appendSymbol(freqHz: Double, isFirst: Boolean, isLast: Boolean) {
                val dPhase = 2.0 * PI * freqHz / sampleRate
                for (i in 0 until symSamples) {
                    val env = when {
                        isFirst && i < rampSamples -> 0.5 * (1.0 - cos(PI * i / rampSamples))
                        isLast && i > symSamples - rampSamples -> 0.5 * (1.0 - cos(PI * (symSamples - i) / rampSamples))
                        else -> 1.0
                    }
                    out[sampleIdx++] = (sin(phase) * env * 18000.0).toInt().coerceIn(-32767, 32767).toShort()
                    phase += dPhase
                    if (phase >= 2.0 * PI) phase -= 2.0 * PI
                }
            }

            for (p in 0 until PREAMBLE_SYMBOLS) {
                appendSymbol(FREQ_PREAMBLE_HZ, isFirst = p == 0, isLast = false)
            }
            val totalBits = frameBytes.size * 8
            for (bitPos in 0 until totalBits) {
                val byteIdx = bitPos / 8
                val bitInByte = 7 - (bitPos % 8)
                val bit = (frameBytes[byteIdx].toInt() ushr bitInByte) and 1
                val freq = if (bit == 1) FREQ_MARK_1_HZ else FREQ_SPACE_0_HZ
                appendSymbol(freq, isFirst = false, isLast = bitPos == totalBits - 1)
            }
            return out
        }

        /**
         * Offline/unit-testable Goertzel matched-filter demodulator over a full PCM buffer.
         */
        fun demodulatePcm(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): DecodedBell202Frame? {
            val symSamples = (sampleRate * SYMBOL_DURATION_MS) / 1000
            if (samples.size < symSamples * (PREAMBLE_SYMBOLS + 64)) return null

            // Slide by quarter-symbol to lock onto the 1650 Hz preamble cleanly
            val step = (symSamples / 4).coerceAtLeast(1)
            var preambleStart = -1
            var consecutive = 0
            var offset = 0
            while (offset + symSamples <= samples.size) {
                val pPre = UltrasonicAcousticModem.goertzelMagnitudeSquared(samples, offset, symSamples, FREQ_PREAMBLE_HZ, sampleRate)
                val p1 = UltrasonicAcousticModem.goertzelMagnitudeSquared(samples, offset, symSamples, FREQ_MARK_1_HZ, sampleRate)
                val p0 = UltrasonicAcousticModem.goertzelMagnitudeSquared(samples, offset, symSamples, FREQ_SPACE_0_HZ, sampleRate)
                if (pPre > 1.0e9 && pPre > p1 * 1.4 && pPre > p0 * 1.4) {
                    if (consecutive == 0) preambleStart = offset
                    consecutive++
                    offset += symSamples
                    if (consecutive >= PREAMBLE_SYMBOLS - 1) {
                        // Advance past any remaining preamble symbol
                        while (offset + symSamples <= samples.size) {
                            val pp = UltrasonicAcousticModem.goertzelMagnitudeSquared(samples, offset, symSamples, FREQ_PREAMBLE_HZ, sampleRate)
                            val pm = UltrasonicAcousticModem.goertzelMagnitudeSquared(samples, offset, symSamples, FREQ_MARK_1_HZ, sampleRate)
                            val ps = UltrasonicAcousticModem.goertzelMagnitudeSquared(samples, offset, symSamples, FREQ_SPACE_0_HZ, sampleRate)
                            if (pp > pm * 1.3 && pp > ps * 1.3) {
                                offset += symSamples
                            } else {
                                break
                            }
                        }
                        break
                    }
                } else {
                    consecutive = 0
                    preambleStart = -1
                    offset += step
                }
            }
            if (preambleStart < 0) return null

            fun readByteAt(pos: Int): Int? {
                if (pos + symSamples * 8 > samples.size) return null
                var b = 0
                for (bitIdx in 0 until 8) {
                    val sOff = pos + bitIdx * symSamples
                    val pMark = UltrasonicAcousticModem.goertzelMagnitudeSquared(samples, sOff, symSamples, FREQ_MARK_1_HZ, sampleRate)
                    val pSpace = UltrasonicAcousticModem.goertzelMagnitudeSquared(samples, sOff, symSamples, FREQ_SPACE_0_HZ, sampleRate)
                    b = (b shl 1) or (if (pMark >= pSpace) 1 else 0)
                }
                return b and 0xFF
            }

            // Read 6-byte header: [0x50, 0x4D, type, srcHi, srcLo, payloadLen]
            val header = ByteArray(6)
            var cursor = offset
            for (i in 0 until 6) {
                val v = readByteAt(cursor) ?: return null
                header[i] = v.toByte()
                cursor += symSamples * 8
            }
            if (header[0] != SYNC_0 || header[1] != SYNC_1) return null
            val payloadLen = header[5].toInt() and 0xFF
            if (payloadLen > MAX_PAYLOAD_BYTES) return null

            val full = ByteArray(6 + payloadLen + 2)
            System.arraycopy(header, 0, full, 0, 6)
            for (i in 0 until (payloadLen + 2)) {
                val v = readByteAt(cursor) ?: return null
                full[6 + i] = v.toByte()
                cursor += symSamples * 8
            }
            return decodeFrameBytes(full)
        }
    }

    @Volatile
    var isListening: Boolean = false
        private set

    private var rxJob: Job? = null

    fun setListening(enabled: Boolean) {
        isListening = enabled
        if (enabled) startListeningLoop() else stopListeningLoop()
    }

    fun transmitMessage(typeByte: Int, srcNodeIdHex: String, text: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val frame = encodeFrameBytes(typeByte, srcNodeIdHex, text)
                val pcm = modulateFrameToPcm(frame, SAMPLE_RATE)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.write(pcm, 0, pcm.size)
                    track.play()
                    val durationMs = ((pcm.size.toLong() * 1000L) / SAMPLE_RATE) + 120L
                    delay(durationMs)
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
            while (isActive && isListening) {
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
                    SAMPLE_RATE,
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
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuf * 2, SYMBOL_SAMPLES * 16)
                    )
                    if (rec.state != AudioRecord.STATE_INITIALIZED) {
                        delay(1500L)
                        continue
                    }
                    rec.startRecording()
                    val symBuf = ShortArray(SYMBOL_SAMPLES)
                    var preambleHits = 0

                    fun readExactSymbol(): Boolean {
                        var read = 0
                        while (read < SYMBOL_SAMPLES && isActive && isListening && !isVoiceMicBusy()) {
                            val r = rec.read(symBuf, read, SYMBOL_SAMPLES - read)
                            if (r > 0) read += r else return false
                        }
                        return read == SYMBOL_SAMPLES && !isVoiceMicBusy()
                    }

                    fun readByte(): Int? {
                        var b = 0
                        for (bitIdx in 0 until 8) {
                            if (!readExactSymbol()) return null
                            val pMark = UltrasonicAcousticModem.goertzelMagnitudeSquared(symBuf, 0, SYMBOL_SAMPLES, FREQ_MARK_1_HZ, SAMPLE_RATE)
                            val pSpace = UltrasonicAcousticModem.goertzelMagnitudeSquared(symBuf, 0, SYMBOL_SAMPLES, FREQ_SPACE_0_HZ, SAMPLE_RATE)
                            b = (b shl 1) or (if (pMark >= pSpace) 1 else 0)
                        }
                        return b and 0xFF
                    }

                    while (isActive && isListening && !isVoiceMicBusy()) {
                        if (!readExactSymbol()) break
                        val pPre = UltrasonicAcousticModem.goertzelMagnitudeSquared(symBuf, 0, SYMBOL_SAMPLES, FREQ_PREAMBLE_HZ, SAMPLE_RATE)
                        val p1 = UltrasonicAcousticModem.goertzelMagnitudeSquared(symBuf, 0, SYMBOL_SAMPLES, FREQ_MARK_1_HZ, SAMPLE_RATE)
                        val p0 = UltrasonicAcousticModem.goertzelMagnitudeSquared(symBuf, 0, SYMBOL_SAMPLES, FREQ_SPACE_0_HZ, SAMPLE_RATE)

                        if (pPre > 1.5e9 && pPre > p1 * 1.5 && pPre > p0 * 1.5) {
                            preambleHits++
                            if (preambleHits >= PREAMBLE_SYMBOLS - 1) {
                                // Skip trailing preamble symbol if still present
                                var firstBitBuf: ShortArray? = null
                                if (readExactSymbol()) {
                                    val pp2 = UltrasonicAcousticModem.goertzelMagnitudeSquared(symBuf, 0, SYMBOL_SAMPLES, FREQ_PREAMBLE_HZ, SAMPLE_RATE)
                                    val pm2 = UltrasonicAcousticModem.goertzelMagnitudeSquared(symBuf, 0, SYMBOL_SAMPLES, FREQ_MARK_1_HZ, SAMPLE_RATE)
                                    val ps2 = UltrasonicAcousticModem.goertzelMagnitudeSquared(symBuf, 0, SYMBOL_SAMPLES, FREQ_SPACE_0_HZ, SAMPLE_RATE)
                                    if (!(pp2 > pm2 * 1.3 && pp2 > ps2 * 1.3)) {
                                        firstBitBuf = symBuf.copyOf()
                                    }
                                }
                                preambleHits = 0

                                // Decode byte 0 using firstBitBuf if we already consumed bit 0
                                val firstByte = if (firstBitBuf != null) {
                                    val pm = UltrasonicAcousticModem.goertzelMagnitudeSquared(firstBitBuf, 0, SYMBOL_SAMPLES, FREQ_MARK_1_HZ, SAMPLE_RATE)
                                    val ps = UltrasonicAcousticModem.goertzelMagnitudeSquared(firstBitBuf, 0, SYMBOL_SAMPLES, FREQ_SPACE_0_HZ, SAMPLE_RATE)
                                    var b = if (pm >= ps) 1 else 0
                                    var ok = true
                                    for (bitIdx in 1 until 8) {
                                        if (!readExactSymbol()) {
                                            ok = false
                                            break
                                        }
                                        val m = UltrasonicAcousticModem.goertzelMagnitudeSquared(symBuf, 0, SYMBOL_SAMPLES, FREQ_MARK_1_HZ, SAMPLE_RATE)
                                        val s = UltrasonicAcousticModem.goertzelMagnitudeSquared(symBuf, 0, SYMBOL_SAMPLES, FREQ_SPACE_0_HZ, SAMPLE_RATE)
                                        b = (b shl 1) or (if (m >= s) 1 else 0)
                                    }
                                    if (ok) b and 0xFF else null
                                } else {
                                    readByte()
                                }

                                if (firstByte == (SYNC_0.toInt() and 0xFF)) {
                                    val sync1 = readByte()
                                    if (sync1 == (SYNC_1.toInt() and 0xFF)) {
                                        val typeB = readByte() ?: continue
                                        val srcHi = readByte() ?: continue
                                        val srcLo = readByte() ?: continue
                                        val pLen = readByte() ?: continue
                                        if (pLen <= MAX_PAYLOAD_BYTES) {
                                            val full = ByteArray(6 + pLen + 2)
                                            full[0] = SYNC_0
                                            full[1] = SYNC_1
                                            full[2] = typeB.toByte()
                                            full[3] = srcHi.toByte()
                                            full[4] = srcLo.toByte()
                                            full[5] = pLen.toByte()
                                            var ok = true
                                            for (i in 0 until (pLen + 2)) {
                                                val v = readByte()
                                                if (v == null) {
                                                    ok = false
                                                    break
                                                }
                                                full[6 + i] = v.toByte()
                                            }
                                            if (ok) {
                                                decodeFrameBytes(full)?.let(onBell202FrameDecoded)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            preambleHits = 0
                        }
                    }
                } catch (_: Exception) {
                    delay(1500L)
                } finally {
                    runCatching { rec?.stop() }
                    runCatching { rec?.release() }
                }
            }
        }
    }

    private fun stopListeningLoop() {
        rxJob?.cancel()
        rxJob = null
    }
}
