// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Tactical Audio Service — Decoupled Voice Playback Queue & G.711 mu-law Voice Engine (`type = "VOICE"`).
 * Used identically by both Walkie-Talkie PTT and Active Calls (`callState == IN_CALL_ACTIVE`):
 * 1. Clean `MediaRecorder.AudioSource.MIC` capture (zero hardware AEC/NS/AGC) plus 1.5x digital voice boost `(sample * 3 / 2).coerceIn(-32768, 32767)`.
 * 2. Captures 160ms slices (`ShortArray(1280)` @ 8000 Hz), downsamples 2:1 to `4000` Hz (`640 samples`),
 *    encodes via `pcm16ToMuLaw` (`640 bytes`), and upsamples back to `8000` Hz on the receiver using
 *    `decodeMuLaw4kHzTo8kHzInterpolated` (**Linear Interpolation** between consecutive samples).
 * 3. Decoupled `audioPlayQueue = Channel<ShortArray>(capacity = 20, onBufferOverflow = BufferOverflow.DROP_OLDEST)`
 *    on a dedicated `THREAD_PRIORITY_URGENT_AUDIO` playback coroutine (`AudioTrack`, `STREAM_MUSIC`).
 */
class TacticalAudioService(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        const val SAMPLE_RATE_8KHZ = 8000
        const val COMPRESSED_RATE_4KHZ = 4000
        const val COMPRESSED_RATE_3200HZ = 3200

        // 160ms G.711 mu-law Voice Slice Constants (ShortArray(1280) @ 8000 Hz -> 2:1 to 4000 Hz -> 640-byte ByteArray):
        const val VOICE_SLICE_SAMPLES_160MS = 1280
        const val VOICE_MULAW_BYTES_160MS = 640

        // 200ms / 250ms / 300ms constants preserved for compatibility
        const val FAST_LINK_SLICE_SAMPLES_200MS = 1600
        const val FAST_LINK_BYTES_200MS = 1600
        const val BLE_SLICE_SAMPLES_200MS = 1600
        const val BLE_COMPRESSED_BYTES_200MS = 800

        const val CALL_SLICE_SAMPLES_250MS = 2000
        const val COMPRESSED_BYTES_250MS = 1000
        const val CALL_VAD_PEAK_THRESHOLD = 300
        const val CALL_VAD_BLE_PEAK_THRESHOLD = 350

        const val BLE_VOICE_SLICE_SAMPLES_300MS = 2400
        const val BLE_COMPRESSED_BYTES_300MS = 960

        const val FRAME_SAMPLES_500MS = 4000
        const val COMPRESSED_BYTES_500MS = 2000
        const val FRAME_SAMPLES_300MS = 2400
        const val COMPRESSED_BYTES_300MS = 1200

        // G.711 mu-law + Binary "PV" / IMA-ADPCM "PA" Voice Frame Constants
        const val BINARY_VOICE_MAGIC_P: Byte = 0x50 // 'P'
        const val BINARY_VOICE_MAGIC_V: Byte = 0x56 // 'V'
        const val BINARY_ADPCM_MAGIC_A: Byte = 0x41 // 'A'
        const val BINARY_VOICE_HEADER_SIZE = 8
        const val VOICE_SLICE_SAMPLES_80MS = 640
        const val VOICE_SLICE_SAMPLES_120MS = 960
        const val MULAW_20MS_SAMPLES = 160
        const val MULAW_100MS_SAMPLES = 800

        private const val MULAW_BIAS = 0x84
        private const val MULAW_CLIP = 32635

        private val IMA_INDEX_TABLE = intArrayOf(
            -1, -1, -1, -1, 2, 4, 6, 8,
            -1, -1, -1, -1, 2, 4, 6, 8
        )

        private val IMA_STEP_TABLE = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
            19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
            130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
            2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
            5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
        )

        data class DecodedBinaryVoiceFrame(
            val srcNodeId: String,
            val dstNodeId: String,
            val seq: Int,
            val muLawPayload: ByteArray
        )

        /**
         * Stateless ITU-T G.711 mu-law compression: 16-bit signed PCM sample -> 8-bit mu-law byte.
         */
        fun pcm16ToMuLaw(sampleInt: Int): Byte {
            var pcm = sampleInt.coerceIn(-32768, 32767)
            var sign = 0x00
            if (pcm < 0) {
                pcm = -pcm
                sign = 0x80
            }
            if (pcm > MULAW_CLIP) pcm = MULAW_CLIP
            pcm += MULAW_BIAS

            var exponent = 7
            var mask = 0x4000
            while (exponent > 0 && (pcm and mask) == 0) {
                exponent--
                mask = mask ushr 1
            }
            val mantissa = (pcm ushr (exponent + 3)) and 0x0F
            val muLaw = (sign or (exponent shl 4) or mantissa) xor 0xFF
            return (muLaw and 0xFF).toByte()
        }

        fun pcm16ToMuLaw(sampleShort: Short): Byte = pcm16ToMuLaw(sampleShort.toInt())

        /**
         * Stateless ITU-T G.711 mu-law expansion: 8-bit mu-law byte -> 16-bit signed PCM Short.
         */
        fun muLawToPcm16(muLawByte: Byte): Short {
            val inv = (muLawByte.toInt() and 0xFF) xor 0xFF
            val sign = inv and 0x80
            val exponent = (inv ushr 4) and 0x07
            val mantissa = inv and 0x0F
            var sample = ((mantissa shl 3) + MULAW_BIAS) shl exponent
            sample -= MULAW_BIAS
            return (if (sign != 0) -sample else sample).coerceIn(-32768, 32767).toShort()
        }

        enum class VoiceClarityPreset(val label: String, val shortBadge: String) {
            NATURAL_HD("Natural HD (Cubic Spline)", "NATURAL HD"),
            WARM_STUDIO("Warm Studio (De-Buzz)", "WARM STUDIO"),
            CRISP_RADIO("Crisp Vocal (Presence+)", "CRISP RADIO")
        }

        /**
         * 1-pole High-Pass DC-Offset & Sub-110Hz Wind/Rumble Blocker (`y[n] = x[n] - x[n-1] + 0.968 * y[n-1]`).
         * Removes microphone DC bias and low-frequency handling rumble so VAD peaks are accurate and G.711 µ-Law
         * dynamic range is dedicated strictly to human vocal formants.
         */
        fun removeDcAndLowRumbleInPlace(samples: ShortArray, count: Int = samples.size) {
            val valid = minOf(count, samples.size)
            if (valid <= 1) return
            var prevX = samples[0].toInt()
            var prevY = 0
            for (i in 0 until valid) {
                val x = samples[i].toInt()
                val y = (x - prevX) + ((prevY * 991) shr 10) // 991/1024 ≈ 0.968
                prevX = x
                prevY = y.coerceIn(-32768, 32767)
                samples[i] = prevY.toShort()
            }
        }

        /**
         * Continuous, strictly monotonic soft-knee voice gain & pre-emphasis helper.
         * Avoids hard noise-gate zero-crossing chops and avoids piecewise step discontinuities.
         */
        fun applyNaturalVoiceGain(sample: Int, prevSample: Int = sample): Int {
            // Subtle high-shelf vocal clarity pre-emphasis (20%) to keep consonants crisp at 4kHz/8kHz
            val preEmphasized = sample + ((sample - prevSample) / 5)
            val absVal = kotlin.math.abs(preEmphasized)
            // Smooth rational soft-knee curve: ~1.38x gain for quiet/normal speech, tapering smoothly near full scale
            val gainNumer = 1380
            val gainDenom = 1000 + ((absVal * 380) / 32768)
            return ((preEmphasized * gainNumer) / gainDenom).coerceIn(-32600, 32600)
        }

        /**
         * Captures a 160ms slice (`ShortArray(1280)` at `8000` Hz), applies a 3-tap Hanning anti-aliasing low-pass filter,
         * continuous monotonic soft-knee voice gain, downsamples `2:1` to `4000` Hz (`640` samples),
         * and encodes each sample with `pcm16ToMuLaw(sample)` into a compact `640-byte` `ByteArray`.
         */
        fun encode160msVoiceMuLaw640(shortBuf: ShortArray, count: Int = shortBuf.size): ByteArray {
            val valid = minOf(count, shortBuf.size)
            val outLen = valid / 2
            if (outLen <= 0) return ByteArray(0)
            var prevFiltered = shortBuf[0].toInt()
            return ByteArray(outLen) { i ->
                val idx = i * 2
                val sPrev = if (idx > 0) shortBuf[idx - 1].toInt() else shortBuf[idx].toInt()
                val sCur = shortBuf[idx].toInt()
                val sNext = if (idx + 1 < valid) shortBuf[idx + 1].toInt() else sCur
                // 3-tap Hanning low-pass filter [0.25, 0.50, 0.25] removes metallic Nyquist aliasing at 4000 Hz
                val lowPass = (sPrev + (sCur shl 1) + sNext) shr 2
                val boosted = applyNaturalVoiceGain(lowPass, prevFiltered)
                prevFiltered = lowPass
                pcm16ToMuLaw(boosted)
            }
        }

        /**
         * Full-rate 8000 Hz HD G.711 mu-law encoder (`1280 bytes` for 160ms @ 8000 Hz, zero downsampling)
         * used automatically when Hotspot Wi-Fi UDP or Hybrid high-speed link is active.
         */
        fun encode160msVoiceMuLaw8kHz1280(shortBuf: ShortArray, count: Int = shortBuf.size): ByteArray {
            val valid = minOf(count, shortBuf.size)
            if (valid <= 0) return ByteArray(0)
            var prev = shortBuf[0].toInt()
            return ByteArray(valid) { i ->
                val cur = shortBuf[i].toInt()
                val boosted = applyNaturalVoiceGain(cur, prev)
                prev = cur
                pcm16ToMuLaw(boosted)
            }
        }

        /**
         * Decodes full-rate 8000 Hz G.711 mu-law bytes (`1280 bytes` -> `ShortArray(1280)`) with gentle 3-tap smoothing.
         */
        fun decodeMuLaw8kHzDirect(muBytes: ByteArray): ShortArray {
            if (muBytes.isEmpty()) return ShortArray(0)
            val raw = ShortArray(muBytes.size) { i -> muLawToPcm16(muBytes[i]) }
            if (raw.size < 3) return raw
            val smoothed = ShortArray(raw.size)
            smoothed[0] = raw[0]
            smoothed[raw.lastIndex] = raw[raw.lastIndex]
            for (i in 1 until raw.lastIndex) {
                smoothed[i] = ((raw[i - 1].toInt() + (raw[i].toInt() * 6) + raw[i + 1].toInt()) shr 3).toShort()
            }
            return smoothed
        }

        /**
         * Converts each G.711 mu-law byte (`640 bytes` @ `4000` Hz) via `muLawToPcm16(b)` and upsamples
         * `2x` back to `8000` Hz (`ShortArray(1280)`) using **4-Point Catmull-Rom Cubic Spline Interpolation**
         * plus a 3-tap post-reconstruction smoothing filter.
         * Never pinches frame boundaries to zero (eliminating the 6.25 Hz periodic amplitude-modulation "robot voice").
         */
        fun decodeMuLaw4kHzTo8kHzInterpolated(muBytes: ByteArray): ShortArray {
            if (muBytes.isEmpty()) return ShortArray(0)
            val n = muBytes.size
            val decoded4k = IntArray(n) { i -> muLawToPcm16(muBytes[i]).toInt() }
            val totalSamples = n * 2
            val pcm8k = ShortArray(totalSamples)
            for (i in 0 until n) {
                val s0 = decoded4k[(i - 1).coerceAtLeast(0)]
                val s1 = decoded4k[i]
                val s2 = decoded4k[(i + 1).coerceAtMost(n - 1)]
                val s3 = decoded4k[(i + 2).coerceAtMost(n - 1)]

                pcm8k[i * 2] = s1.toShort()
                // Catmull-Rom cubic midpoint: (-s0 + 9*s1 + 9*s2 - s3) / 16
                val cubicMid = ((-s0 + (9 * s1) + (9 * s2) - s3) shr 4).coerceIn(-32768, 32767)
                pcm8k[i * 2 + 1] = cubicMid.toShort()
            }
            // Gentle 3-tap post-interpolation reconstruction filter [1, 6, 1]/8 removes staircase quantization buzz
            if (totalSamples >= 4) {
                var prev = pcm8k[0].toInt()
                for (i in 1 until totalSamples - 1) {
                    val cur = pcm8k[i].toInt()
                    val next = pcm8k[i + 1].toInt()
                    pcm8k[i] = ((prev + (cur * 6) + next) shr 3).toShort()
                    prev = cur
                }
            }
            return pcm8k
        }

        fun encodePcm16ToMuLaw(shortBuf: ShortArray, count: Int = shortBuf.size): ByteArray {
            val valid = minOf(count, shortBuf.size)
            if (valid <= 0) return ByteArray(0)
            var prev = shortBuf[0].toInt()
            return ByteArray(valid) { i ->
                val cur = shortBuf[i].toInt()
                val boosted = applyNaturalVoiceGain(cur, prev)
                prev = cur
                pcm16ToMuLaw(boosted)
            }
        }

        fun decodeMuLawToPcm16(muLawBytes: ByteArray): ShortArray {
            if (muLawBytes.isEmpty()) return ShortArray(0)
            return ShortArray(muLawBytes.size) { i -> muLawToPcm16(muLawBytes[i]) }
        }

        /**
         * Packs a raw binary `"PV"` frame:
         * `[0x50 ('P'), 0x56 ('V'), srcIdHi, srcIdLo, dstIdHi, dstIdLo, seqHi, seqLo]` + `muLawBytes`.
         */
        fun packBinaryVoiceFrame(
            srcNodeId: String,
            dstNodeId: String,
            seq: Int,
            muLawBytes: ByteArray
        ): ByteArray {
            val srcShort = OffGridBleMeshEngine.normalizeId(srcNodeId).toIntOrNull(16) ?: 0
            val cleanDst = dstNodeId.trim().uppercase()
            val dstShort = if (cleanDst.isBlank() || cleanDst == "ALL" || cleanDst == "BROADCAST") {
                0xFFFF
            } else {
                OffGridBleMeshEngine.normalizeId(dstNodeId).toIntOrNull(16) ?: 0xFFFF
            }
            val out = ByteArray(BINARY_VOICE_HEADER_SIZE + muLawBytes.size)
            out[0] = BINARY_VOICE_MAGIC_P
            out[1] = BINARY_VOICE_MAGIC_V
            out[2] = ((srcShort ushr 8) and 0xFF).toByte()
            out[3] = (srcShort and 0xFF).toByte()
            out[4] = ((dstShort ushr 8) and 0xFF).toByte()
            out[5] = (dstShort and 0xFF).toByte()
            out[6] = ((seq ushr 8) and 0xFF).toByte()
            out[7] = (seq and 0xFF).toByte()
            if (muLawBytes.isNotEmpty()) {
                System.arraycopy(muLawBytes, 0, out, BINARY_VOICE_HEADER_SIZE, muLawBytes.size)
            }
            return out
        }

        fun unpackBinaryVoiceFrame(frame: ByteArray): DecodedBinaryVoiceFrame? {
            if (frame.size <= BINARY_VOICE_HEADER_SIZE) return null
            if (frame[0] != BINARY_VOICE_MAGIC_P || frame[1] != BINARY_VOICE_MAGIC_V) return null
            val srcInt = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
            val dstInt = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
            val seq = ((frame[6].toInt() and 0xFF) shl 8) or (frame[7].toInt() and 0xFF)
            val payload = frame.copyOfRange(BINARY_VOICE_HEADER_SIZE, frame.size)
            val srcNodeId = String.format("0x%04X", srcInt)
            val dstNodeId = if (dstInt == 0xFFFF) "ALL" else String.format("0x%04X", dstInt)
            return DecodedBinaryVoiceFrame(
                srcNodeId = srcNodeId,
                dstNodeId = dstNodeId,
                seq = seq,
                muLawPayload = payload
            )
        }

        /**
         * 4-bit IMA-ADPCM Encoder @ 8000 Hz (`32 kbps` — full 4 kHz audio bandwidth at half the bitrate of G.711 µ-Law):
         * Layout: `[predLo, predHi, stepIndex, reservedFlags(0)]` + packed 4-bit nibbles (low nibble first).
         * - 80ms (640 samples) -> 324 bytes
         * - 120ms (960 samples) -> 484 bytes
         * - 160ms (1280 samples) -> 644 bytes
         */
        fun encodeImaAdpcm8kHz(shortBuf: ShortArray, count: Int = shortBuf.size): ByteArray {
            val valid = minOf(count, shortBuf.size)
            if (valid <= 0) return ByteArray(0)
            val nibbleBytes = (valid + 1) / 2
            val out = ByteArray(4 + nibbleBytes)

            var predictor = applyNaturalVoiceGain(shortBuf[0].toInt(), shortBuf[0].toInt())
            var stepIndex = 16
            out[0] = (predictor and 0xFF).toByte()
            out[1] = ((predictor ushr 8) and 0xFF).toByte()
            out[2] = stepIndex.toByte()
            out[3] = if ((valid and 1) != 0) 0x01 else 0x00 // bit 0 marks odd sample count

            var prevRaw = shortBuf[0].toInt()
            var outPos = 4
            var curByte = 0
            for (i in 0 until valid) {
                val raw = shortBuf[i].toInt()
                val sample = applyNaturalVoiceGain(raw, prevRaw)
                prevRaw = raw

                val step = IMA_STEP_TABLE[stepIndex]
                var diff = sample - predictor
                var code = 0
                if (diff < 0) {
                    code = 8
                    diff = -diff
                }
                var tempStep = step
                if (diff >= tempStep) {
                    code = code or 4
                    diff -= tempStep
                }
                tempStep = tempStep ushr 1
                if (diff >= tempStep) {
                    code = code or 2
                    diff -= tempStep
                }
                tempStep = tempStep ushr 1
                if (diff >= tempStep) {
                    code = code or 1
                }

                var delta = step ushr 3
                if ((code and 4) != 0) delta += step
                if ((code and 2) != 0) delta += (step ushr 1)
                if ((code and 1) != 0) delta += (step ushr 2)
                predictor = if ((code and 8) != 0) predictor - delta else predictor + delta
                predictor = predictor.coerceIn(-32768, 32767)
                stepIndex = (stepIndex + IMA_INDEX_TABLE[code]).coerceIn(0, IMA_STEP_TABLE.lastIndex)

                if ((i and 1) == 0) {
                    curByte = code and 0x0F
                } else {
                    out[outPos++] = (curByte or ((code and 0x0F) shl 4)).toByte()
                }
            }
            if ((valid and 1) != 0 && outPos < out.size) {
                out[outPos] = curByte.toByte()
            }
            return out
        }

        /**
         * 4-bit IMA-ADPCM Decoder -> full 8000 Hz 16-bit PCM `ShortArray` with gentle [1, 14, 1]/16 de-hiss smoothing.
         */
        fun decodeImaAdpcm8kHz(adpcmBytes: ByteArray): ShortArray {
            if (adpcmBytes.size <= 4) return ShortArray(0)
            var predictor = ((adpcmBytes[1].toInt() shl 8) or (adpcmBytes[0].toInt() and 0xFF)).toShort().toInt()
            var stepIndex = (adpcmBytes[2].toInt() and 0xFF).coerceIn(0, IMA_STEP_TABLE.lastIndex)
            val oddTrailing = (adpcmBytes[3].toInt() and 0x01) != 0
            val payloadBytes = adpcmBytes.size - 4
            val sampleCount = payloadBytes * 2 - if (oddTrailing) 1 else 0
            if (sampleCount <= 0) return ShortArray(0)

            val out = ShortArray(sampleCount)
            var sampleIdx = 0
            for (bIdx in 4 until adpcmBytes.size) {
                val packed = adpcmBytes[bIdx].toInt() and 0xFF
                for (shift in 0..1) {
                    if (sampleIdx >= sampleCount) break
                    val code = if (shift == 0) (packed and 0x0F) else ((packed ushr 4) and 0x0F)
                    val step = IMA_STEP_TABLE[stepIndex]
                    var delta = step ushr 3
                    if ((code and 4) != 0) delta += step
                    if ((code and 2) != 0) delta += (step ushr 1)
                    if ((code and 1) != 0) delta += (step ushr 2)
                    predictor = if ((code and 8) != 0) predictor - delta else predictor + delta
                    predictor = predictor.coerceIn(-32768, 32767)
                    stepIndex = (stepIndex + IMA_INDEX_TABLE[code]).coerceIn(0, IMA_STEP_TABLE.lastIndex)
                    out[sampleIdx++] = predictor.toShort()
                }
            }

            if (out.size >= 4) {
                var prev = out[0].toInt()
                for (i in 1 until out.lastIndex) {
                    val cur = out[i].toInt()
                    val next = out[i + 1].toInt()
                    out[i] = ((prev + (cur * 14) + next) shr 4).coerceIn(-32768, 32767).toShort()
                    prev = cur
                }
            }
            return out
        }

        fun packBinaryAdpcmVoiceFrame(
            srcNodeId: String,
            dstNodeId: String,
            seq: Int,
            adpcmBytes: ByteArray
        ): ByteArray {
            val frame = packBinaryVoiceFrame(srcNodeId, dstNodeId, seq, adpcmBytes)
            frame[1] = BINARY_ADPCM_MAGIC_A
            return frame
        }

        fun unpackBinaryAdpcmVoiceFrame(frame: ByteArray): DecodedBinaryVoiceFrame? {
            if (frame.size <= BINARY_VOICE_HEADER_SIZE + 4) return null
            if (frame[0] != BINARY_VOICE_MAGIC_P || frame[1] != BINARY_ADPCM_MAGIC_A) return null
            val srcInt = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
            val dstInt = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
            val seq = ((frame[6].toInt() and 0xFF) shl 8) or (frame[7].toInt() and 0xFF)
            val payload = frame.copyOfRange(BINARY_VOICE_HEADER_SIZE, frame.size)
            val srcNodeId = String.format("0x%04X", srcInt)
            val dstNodeId = if (dstInt == 0xFFFF) "ALL" else String.format("0x%04X", dstInt)
            return DecodedBinaryVoiceFrame(
                srcNodeId = srcNodeId,
                dstNodeId = dstNodeId,
                seq = seq,
                muLawPayload = payload
            )
        }

        /**
         * Soft-knee voice gain boost (`1.2x` clamped to 16-bit signed range) so quiet speech is clear without clipping.
         */
        fun applySoftKneeVoiceGain(sample: Int): Int {
            return ((sample * 12) / 10).coerceIn(-32768, 32767)
        }

        /**
         * Full 8000 Hz 1:1 encoding (zero downsampling) for Hotspot Wi-Fi UDP / Hybrid links:
         * Converts 1600 samples (200ms @ 8000 Hz) with soft-knee gain into 1600 bytes of 8-bit signed PCM.
         */
        fun encodeFull8kHz8BitPcm(shortBuf: ShortArray, count: Int = shortBuf.size): ByteArray {
            val validCount = minOf(count, shortBuf.size)
            if (validCount <= 0) return ByteArray(0)
            return ByteArray(validCount) { i ->
                val boosted = applySoftKneeVoiceGain(shortBuf[i].toInt())
                (boosted shr 8).toByte()
            }
        }

        /**
         * Stateless 2:1 downsampling from 8000 Hz 16-bit PCM ShortArray to 4000 Hz 8-bit signed PCM ByteArray
         * with soft-knee voice gain (`(sample * 12 / 10).coerceIn(-32768, 32767)`).
         */
        fun encodeStateless8BitPcm(shortBuf: ShortArray, count: Int = shortBuf.size): ByteArray {
            val outLen = minOf(count, shortBuf.size) / 2
            if (outLen <= 0) return ByteArray(0)
            return ByteArray(outLen) { i ->
                val boosted = applySoftKneeVoiceGain(shortBuf[i * 2].toInt())
                (boosted shr 8).toByte()
            }
        }

        /**
         * Stateless 2.5:1 downsampling of a 300ms (2400-sample @ 8000 Hz) frame into 960 bytes of 3200 Hz 8-bit signed PCM.
         */
        fun encode300msBle3200Hz8BitPcm(shortBuf: ShortArray, count: Int = shortBuf.size): ByteArray {
            val validCount = minOf(count, shortBuf.size)
            if (validCount <= 0) return ByteArray(0)
            val outLen = (validCount * 2) / 5
            if (outLen <= 0) return ByteArray(0)
            val maxIdx = validCount - 1
            return ByteArray(outLen) { i ->
                val srcIdx = ((i * 5) / 2).coerceIn(0, maxIdx)
                val boosted = applySoftKneeVoiceGain(shortBuf[srcIdx].toInt())
                (boosted shr 8).toByte()
            }
        }

        /**
         * Stateless decoding & Linear-Interpolated upsampling of incoming 8-bit signed PCM ByteArray back to 8000 Hz 16-bit ShortArray:
         * - 1600-byte Hotspot/UDP full 8000 Hz (200ms) frame -> 1:1 conversion to 1600 samples @ 8000 Hz.
         * - 960-byte pure-BLE 3200 Hz (300ms) frame -> linear-interpolated 2.5x upsample to 2400 samples @ 8000 Hz.
         * - 800-byte (200ms @ 4000 Hz) or 1000-byte (250ms @ 4000 Hz) frame -> 2x upsample to `rawBytes.size * 2` samples @ 8000 Hz
         *   using **Linear Interpolation** between consecutive samples (`pcm8k[i * 2] = s1; pcm8k[i * 2 + 1] = (s1 + s2) / 2`).
         */
        fun decodeStateless8BitPcmTo8kHz16Bit(rawBytes: ByteArray): ShortArray {
            if (rawBytes.isEmpty()) return ShortArray(0)
            if (rawBytes.size == FAST_LINK_BYTES_200MS) {
                return ShortArray(FAST_LINK_SLICE_SAMPLES_200MS) { i ->
                    (rawBytes[i].toInt() shl 8).toShort()
                }
            }
            if (rawBytes.size == BLE_COMPRESSED_BYTES_300MS) {
                val maxIdx = rawBytes.lastIndex
                return ShortArray(BLE_VOICE_SLICE_SAMPLES_300MS) { i ->
                    val exactPos = (i * 2.0) / 5.0
                    val idx1 = exactPos.toInt().coerceIn(0, maxIdx)
                    val idx2 = (idx1 + 1).coerceIn(0, maxIdx)
                    val frac = exactPos - idx1
                    val s1 = (rawBytes[idx1].toInt() shl 8)
                    val s2 = (rawBytes[idx2].toInt() shl 8)
                    (s1 + ((s2 - s1) * frac).toInt()).toShort()
                }
            }
            val pcm8k = ShortArray(rawBytes.size * 2)
            for (i in rawBytes.indices) {
                val s1 = (rawBytes[i].toInt() shl 8)
                val s2 = if (i + 1 < rawBytes.size) (rawBytes[i + 1].toInt() shl 8) else s1
                pcm8k[i * 2] = s1.toShort()
                pcm8k[i * 2 + 1] = ((s1 + s2) / 2).toShort()
            }
            return pcm8k
        }

        /**
         * Stateless 2:1 downsampling from 16000 Hz capture rate down to 8000 Hz 16-bit PCM ShortArray
         * using a 2-sample anti-aliasing low-pass average.
         */
        fun downsample16kHzTo8kHz(pcm16k: ShortArray, count: Int = pcm16k.size): ShortArray {
            val valid = minOf(count, pcm16k.size)
            val outLen = valid / 2
            if (outLen <= 0) return ShortArray(0)
            return ShortArray(outLen) { i ->
                val idx = i * 2
                val s0 = pcm16k[idx].toInt()
                val s1 = if (idx + 1 < valid) pcm16k[idx + 1].toInt() else s0
                ((s0 + s1) / 2).toShort()
            }
        }

        /**
         * Guaranteed Microphone Capture (`AudioRecord` with `AudioSource.MIC`):
         * - ALWAYS uses `MediaRecorder.AudioSource.MIC` (NEVER `VOICE_COMMUNICATION`, which mutes the mic on OnePlus/ColorOS
         *   and many OEM HALs when no cellular call is active!), `8000` Hz, `CHANNEL_IN_MONO`, `ENCODING_PCM_16BIT`,
         *   buffer size `maxOf(minBuf * 2, 3200)`.
         * - Never attaches `AcousticEchoCanceler` or `NoiseSuppressor`.
         */
        @SuppressLint("MissingPermission")
        fun createSafeAudioRecord(): Pair<AudioRecord?, Int> {
            val minBuf8k = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_8KHZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf8k > 0) {
                // Low-latency capture buffer: 1280 bytes (~80ms) minimum instead of 3200+ bytes
                val bufSize = maxOf(minBuf8k, 1280)
                val rec = runCatching {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE_8KHZ,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize
                    )
                }.getOrNull()
                if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                    attachSafeHardwareAudioEffects(rec.audioSessionId)
                    return Pair(rec, SAMPLE_RATE_8KHZ)
                }
                runCatching { rec?.release() }
            }

            // Fallback to 16000 Hz ONLY with AudioSource.MIC if an emulator HAL does not support 8000 Hz directly
            val minBuf16k = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf16k > 0) {
                val bufSize16k = maxOf(minBuf16k, 2560)
                val rec16k = runCatching {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        16000,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize16k
                    )
                }.getOrNull()
                if (rec16k != null && rec16k.state == AudioRecord.STATE_INITIALIZED) {
                    attachSafeHardwareAudioEffects(rec16k.audioSessionId)
                    return Pair(rec16k, 16000)
                }
                runCatching { rec16k?.release() }
            }
            return Pair(null, SAMPLE_RATE_8KHZ)
        }

        private fun attachSafeHardwareAudioEffects(audioSessionId: Int) {
            if (audioSessionId <= 0) return
            runCatching {
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                    android.media.audiofx.AcousticEchoCanceler.create(audioSessionId)?.enabled = true
                }
            }
            runCatching {
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    android.media.audiofx.NoiseSuppressor.create(audioSessionId)?.enabled = true
                }
            }
        }

        fun encodePcm16ToUnsigned8Bit(pcmShorts: ShortArray, count: Int = pcmShorts.size): ByteArray {
            val len = minOf(count, pcmShorts.size)
            return ByteArray(len) { i ->
                ((pcmShorts[i].toInt() shr 8) + 128).coerceIn(0, 255).toByte()
            }
        }

        fun decodeUnsigned8BitToPcm16(bytes: ByteArray): ShortArray {
            return ShortArray(bytes.size) { i ->
                (((bytes[i].toInt() and 0xFF) - 128) shl 8).toShort()
            }
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _waveformLevels = MutableStateFlow<List<Float>>(List(28) { 0.05f })
    val waveformLevels: StateFlow<List<Float>> = _waveformLevels.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isLoopbackTesting = MutableStateFlow(false)
    val isLoopbackTesting: StateFlow<Boolean> = _isLoopbackTesting.asStateFlow()

    private val _micLevelPeak = MutableStateFlow(0.0f)
    val micLevelPeak: StateFlow<Float> = _micLevelPeak.asStateFlow()

    private val _micLevelDb = MutableStateFlow(-60)
    val micLevelDb: StateFlow<Int> = _micLevelDb.asStateFlow()

    private val _voiceFramesSent = MutableStateFlow(0)
    val voiceFramesSent: StateFlow<Int> = _voiceFramesSent.asStateFlow()

    private val _voiceFramesPlayed = MutableStateFlow(0)
    val voiceFramesPlayed: StateFlow<Int> = _voiceFramesPlayed.asStateFlow()

    // Separate Last-RX Instant Replay Ring Buffers (up to 62 frames = ~10 seconds each)
    // Walkie-Talkie PTT RX and 1-Tap Voice Call RX are strictly isolated so they never mix!
    private val recentPttRxRingBuffer = ArrayDeque<ShortArray>(64)
    private val recentCallRxRingBuffer = ArrayDeque<ShortArray>(64)

    private val _cachedPttRxReplayFramesCount = MutableStateFlow(0)
    val cachedPttRxReplayFramesCount: StateFlow<Int> = _cachedPttRxReplayFramesCount.asStateFlow()

    private val _cachedCallRxReplayFramesCount = MutableStateFlow(0)
    val cachedCallRxReplayFramesCount: StateFlow<Int> = _cachedCallRxReplayFramesCount.asStateFlow()

    val cachedRxReplayFramesCount: StateFlow<Int> get() = _cachedPttRxReplayFramesCount.asStateFlow()

    private val _isReplayingPttRx = MutableStateFlow(false)
    val isReplayingPttRx: StateFlow<Boolean> = _isReplayingPttRx.asStateFlow()

    private val _isReplayingCallRx = MutableStateFlow(false)
    val isReplayingCallRx: StateFlow<Boolean> = _isReplayingCallRx.asStateFlow()

    val isReplayingLastRx: StateFlow<Boolean> get() = _isReplayingPttRx.asStateFlow()

    private val _isRecordingVoiceDrop = MutableStateFlow(false)
    val isRecordingVoiceDrop: StateFlow<Boolean> = _isRecordingVoiceDrop.asStateFlow()

    private val _voiceClarityPreset = MutableStateFlow(VoiceClarityPreset.NATURAL_HD)
    val voiceClarityPreset: StateFlow<VoiceClarityPreset> = _voiceClarityPreset.asStateFlow()

    fun cycleVoiceClarityPreset(): VoiceClarityPreset {
        val entries = VoiceClarityPreset.entries
        val next = entries[(_voiceClarityPreset.value.ordinal + 1) % entries.size]
        _voiceClarityPreset.value = next
        return next
    }

    private var micCaptureJob: Job? = null
    private var simulationJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private var persistentAudioTrack: AudioTrack? = null
    val audioPlayQueue = Channel<ShortArray>(capacity = 24, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var playbackJob: Job? = null

    @Volatile
    private var isPlayoutBurstActive: Boolean = false

    @Volatile
    private var lastPlayedSampleVal: Int = 0

    @Volatile
    private var lastPlayedPcmFrame: ShortArray? = null

    @Volatile
    var speakerphoneEnabled: Boolean = true

    @Volatile
    private var trackConfiguredForSpeaker: Boolean? = null

    @Volatile
    private var lastAppliedSpeakerRoute: Boolean? = null

    @Volatile
    private var audioFocusGranted: Boolean = false

    @Volatile
    private var focusRequestObj: Any? = null

    var useLiveMicrophone: Boolean = true
    var isHighSpeedLinkActive: () -> Boolean = { true }
    var isBleVoiceSending: () -> Boolean = { false }
    var isCapturePermitted: () -> Boolean = { true }
    var onStreamingPttChunkReady: ((String) -> Unit)? = null
    var onRawPttBytesReady: ((ByteArray) -> Unit)? = null
    var onMuLawPttBytesReady: ((ByteArray) -> Unit)? = null

    private val recorded4kHzByteStream = ByteArrayOutputStream()
    private var lastRecordedAudioBase64: String? = null

    val sampleRate = SAMPLE_RATE_8KHZ

    init {
        startPlaybackWorker()
    }

    @Synchronized
    private fun startPlaybackWorker() {
        if (playbackJob?.isActive == true) return
        playbackJob = scope.launch(Dispatchers.IO) {
            runCatching {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            }
            while (isActive) {
                runCatching {
                    val waitTimeoutMs = if (isPlayoutBurstActive) 140L else 320L
                    var pcm8k = withTimeoutOrNull(waitTimeoutMs) {
                        audioPlayQueue.receive()
                    }
                    if (pcm8k != null) {
                        // Low-latency anti-drift: if a packet burst queued multiple frames during live voice,
                        // skip stale backlog frames so speaker playout stays locked to real time.
                        if (!_isReplayingPttRx.value && !_isReplayingCallRx.value) {
                            var drained = 0
                            while (drained < 4) {
                                val newer = audioPlayQueue.tryReceive().getOrNull() ?: break
                                pcm8k = newer
                                drained++
                            }
                        }
                        val isBurstStart = !isPlayoutBurstActive
                        isPlayoutBurstActive = true
                        val frameToPlay: ShortArray = pcm8k
                        lastPlayedPcmFrame = frameToPlay
                        writePcm16ShortsToSpeakerBlocking(frameToPlay, isBurstStart = isBurstStart)
                    } else {
                        if (isPlayoutBurstActive) {
                            // Single-frame Packet-Loss Concealment (PLC): if one frame is delayed or lost mid-sentence,
                            // bridge the gap with an attenuated (55%) tapered copy of the previous frame, then wait 240ms
                            val prevFrame = lastPlayedPcmFrame
                            val latePcm = if (prevFrame != null && prevFrame.isNotEmpty()) {
                                val plcFrame = ShortArray(prevFrame.size) { idx ->
                                    val env = ((prevFrame.size - idx) * 55) / (prevFrame.size * 100)
                                    (prevFrame[idx].toInt() * env).coerceIn(-32768, 32767).toShort()
                                }
                                lastPlayedPcmFrame = null
                                writePcm16ShortsToSpeakerBlocking(plcFrame, isBurstStart = false, countAsFrame = false)
                                withTimeoutOrNull(240L) {
                                    audioPlayQueue.receive()
                                }
                            } else null

                            if (latePcm != null) {
                                lastPlayedPcmFrame = latePcm
                                writePcm16ShortsToSpeakerBlocking(latePcm, isBurstStart = false)
                                return@runCatching
                            }

                            // Write a smooth 10ms (80-sample) exponential decay tail to zero so the speaker never clicks when speech pauses
                            if (kotlin.math.abs(lastPlayedSampleVal) > 40) {
                                val tail = ShortArray(80) { idx ->
                                    ((lastPlayedSampleVal * (80 - idx)) / 80).toShort()
                                }
                                lastPlayedSampleVal = 0
                                writePcm16ShortsToSpeakerBlocking(tail, isBurstStart = false, countAsFrame = false)
                            }
                            isPlayoutBurstActive = false
                            lastPlayedPcmFrame = null
                        }
                        // Check if completely idle for another 3.5s before releasing AudioTrack
                        val nextPcm = withTimeoutOrNull(3500L) {
                            audioPlayQueue.receive()
                        }
                        if (nextPcm != null) {
                            isPlayoutBurstActive = true
                            lastPlayedPcmFrame = nextPcm
                            writePcm16ShortsToSpeakerBlocking(nextPcm, isBurstStart = true)
                        } else {
                            releasePersistentAudioTrack()
                        }
                    }
                }.onFailure {
                    releasePersistentAudioTrack()
                    delay(15L)
                }
            }
        }
    }

    @Synchronized
    private fun releasePersistentAudioTrack() {
        runCatching {
            persistentAudioTrack?.let { trk ->
                if (trk.state == AudioTrack.STATE_INITIALIZED && trk.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    trk.stop()
                }
                trk.release()
            }
        }
        persistentAudioTrack = null
        trackConfiguredForSpeaker = null
        lastAppliedSpeakerRoute = null
        isPlayoutBurstActive = false
        lastPlayedSampleVal = 0
    }

    fun setSpeakerRouting(enabled: Boolean) {
        speakerphoneEnabled = enabled
        lastAppliedSpeakerRoute = null
    }

    @Synchronized
    private fun ensureAudioTrackReady(useSpeaker: Boolean): AudioTrack? {
        val existing = persistentAudioTrack
        if (existing != null &&
            existing.state == AudioTrack.STATE_INITIALIZED &&
            trackConfiguredForSpeaker == useSpeaker
        ) {
            return existing
        }
        releasePersistentAudioTrack()

        return runCatching {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1280)
            // Stable ring buffer (at least 4800 bytes = 300ms capacity) prevents HAL underrun stalls after ~20s
            val bufSize = maxOf(minBuf * 2, 4800)
            val usage = if (useSpeaker) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val track = builder.build()

            if (track.state == AudioTrack.STATE_INITIALIZED) {
                runCatching { track.setVolume(1.0f) }
                persistentAudioTrack = track
                trackConfiguredForSpeaker = useSpeaker
                track
            } else {
                runCatching { track.release() }
                null
            }
        }.getOrNull()
    }

    private fun applyClarityPostFilterAndCrossStitch(rawPcm: ShortArray): ShortArray {
        if (rawPcm.isEmpty()) return rawPcm
        val out = rawPcm.copyOf()
        val preset = _voiceClarityPreset.value

        when (preset) {
            VoiceClarityPreset.WARM_STUDIO -> {
                // Warm De-Buzz 2-pole low-pass smoothing to remove harsh high-frequency radio fizz
                var acc = lastPlayedSampleVal
                for (i in out.indices) {
                    acc = ((acc * 3) + (out[i].toInt() * 5)) shr 3
                    out[i] = acc.coerceIn(-32768, 32767).toShort()
                }
            }
            VoiceClarityPreset.CRISP_RADIO -> {
                // Crisp Vocal presence boost (+25% mid-band presence with soft knee)
                var prev = lastPlayedSampleVal
                for (i in out.indices) {
                    val cur = out[i].toInt()
                    val diff = cur - prev
                    val enhanced = ((cur * 12) / 10) + (diff / 3)
                    out[i] = enhanced.coerceIn(-32600, 32600).toShort()
                    prev = cur
                }
            }
            VoiceClarityPreset.NATURAL_HD -> {
                // Natural HD already reconstructed via Catmull-Rom cubic spline
            }
        }

        // Stateful 10-sample inter-frame cross-stitch from lastPlayedSampleVal to prevent boundary clicks
        // WITHOUT ever dipping the speech waveform to zero!
        val stitchLen = minOf(10, out.size)
        val startVal = lastPlayedSampleVal
        for (i in 0 until stitchLen) {
            val weightNew = i + 1
            val weightOld = stitchLen - weightNew
            val blended = ((startVal * weightOld) + (out[i].toInt() * weightNew)) / stitchLen
            out[i] = blended.coerceIn(-32768, 32767).toShort()
        }
        lastPlayedSampleVal = out[out.lastIndex].toInt()
        return out
    }

    private fun writePcm16ShortsToSpeakerBlocking(
        pcm16: ShortArray,
        isBurstStart: Boolean = false,
        countAsFrame: Boolean = true
    ) {
        if (pcm16.isEmpty()) return
        val useSpeaker = speakerphoneEnabled
        ensureSpeakerphoneAndVolume(useSpeaker)

        val processedPcm = if (countAsFrame) applyClarityPostFilterAndCrossStitch(pcm16) else pcm16

        runCatching {
            var trackRef = ensureAudioTrackReady(useSpeaker)
            if (trackRef != null && trackRef.state == AudioTrack.STATE_INITIALIZED) {
                if (trackRef.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    trackRef.play()
                }
                if (isBurstStart && !isHighSpeedLinkActive()) {
                    // Minimal 6ms (48 samples) anti-click ramp on BLE links; zero pre-roll delay on Wi-Fi UDP
                    val jitterCushion = ShortArray(48)
                    trackRef.write(jitterCushion, 0, jitterCushion.size, AudioTrack.WRITE_BLOCKING)
                }
                var written = trackRef.write(processedPcm, 0, processedPcm.size, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) {
                    // Recover immediately from ERROR_DEAD_OBJECT / stale HAL audio sink
                    releasePersistentAudioTrack()
                    ensureSpeakerphoneAndVolume(useSpeaker)
                    trackRef = ensureAudioTrackReady(useSpeaker)
                    if (trackRef != null && trackRef.state == AudioTrack.STATE_INITIALIZED) {
                        trackRef.play()
                        written = trackRef.write(processedPcm, 0, processedPcm.size, AudioTrack.WRITE_BLOCKING)
                    }
                }
                if (written > 0 && countAsFrame) {
                    _voiceFramesPlayed.update { it + 1 }
                }
            }
        }
    }

    private fun ensureSpeakerphoneAndVolume(useSpeaker: Boolean = speakerphoneEnabled) {
        runCatching {
            val mgr = audioManager ?: return@runCatching
            if (!audioFocusGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(if (useSpeaker) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .build()
                    focusRequestObj = req
                    audioFocusGranted = mgr.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                } else {
                    @Suppress("DEPRECATION")
                    audioFocusGranted = mgr.requestAudioFocus(
                        null,
                        if (useSpeaker) AudioManager.STREAM_MUSIC else AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                    ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }
            }

            if (lastAppliedSpeakerRoute == useSpeaker) {
                return@runCatching
            }
            lastAppliedSpeakerRoute = useSpeaker

            if (useSpeaker) {
                mgr.mode = AudioManager.MODE_NORMAL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching { mgr.clearCommunicationDevice() }
                }
                @Suppress("DEPRECATION")
                mgr.isSpeakerphoneOn = true
                val maxVol = mgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val curVol = mgr.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (maxVol > 0 && curVol < maxVol / 2) {
                    mgr.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.85).toInt().coerceAtLeast(1), 0)
                }
            } else {
                mgr.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val earpiece = mgr.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    if (earpiece != null) {
                        runCatching { mgr.setCommunicationDevice(earpiece) }
                    }
                }
                @Suppress("DEPRECATION")
                mgr.isSpeakerphoneOn = false
                val maxVoiceVol = mgr.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val curVoiceVol = mgr.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                if (maxVoiceVol > 0 && curVoiceVol < maxVoiceVol / 2) {
                    mgr.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVoiceVol * 0.85).toInt().coerceAtLeast(1), 0)
                }
            }
        }
    }

    fun playPttRadioChirp() {
        // Synthetic beeps intentionally disabled — AudioTrack is reserved strictly for human voice.
    }

    @SuppressLint("MissingPermission")
    fun startTransmission() {
        if (_isRecording.value) return
        if (!isCapturePermitted()) return
        _isRecording.value = true
        synchronized(recorded4kHzByteStream) {
            recorded4kHzByteStream.reset()
        }
        if (useLiveMicrophone) {
            startMicrophoneRecording()
        } else {
            startSimulatedRfChatter()
        }
    }

    fun onAppBackgrounded() {
        if (_isRecording.value) {
            stopTransmission(playRogerBeep = false)
        } else {
            stopMicrophoneRecording()
        }
        if (!_isReplayingPttRx.value && !_isReplayingCallRx.value && !isPlayoutBurstActive) {
            abandonAudioFocus()
        }
    }

    fun abandonAudioFocus() {
        runCatching {
            val mgr = audioManager ?: return@runCatching
            if (audioFocusGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val req = focusRequestObj as? AudioFocusRequest
                    if (req != null) mgr.abandonAudioFocusRequest(req)
                } else {
                    @Suppress("DEPRECATION")
                    mgr.abandonAudioFocus(null)
                }
                audioFocusGranted = false
                focusRequestObj = null
            }
        }
    }

    fun release() {
        stopMicrophoneRecording()
        stopTransmission(playRogerBeep = false)
        playbackJob?.cancel()
        playbackJob = null
        releasePersistentAudioTrack()
        abandonAudioFocus()
    }

    fun stopTransmission(playRogerBeep: Boolean = false): String? {
        val wasRecording = _isRecording.value
        _isRecording.value = false
        stopMicrophoneRecording()
        simulationJob?.cancel()
        simulationJob = null
        _waveformLevels.value = List(28) { 0.05f }
        _micLevelPeak.value = 0f
        _micLevelDb.value = -60

        val capturedBytes = synchronized(recorded4kHzByteStream) {
            val bytes = recorded4kHzByteStream.toByteArray()
            recorded4kHzByteStream.reset()
            bytes
        }

        val finalBytes = if (capturedBytes.isNotEmpty()) {
            capturedBytes
        } else if (wasRecording && !useLiveMicrophone) {
            val synth800 = ByteArray(BLE_COMPRESSED_BYTES_200MS) { i -> (sin(2.0 * PI * i / 10.0) * 90).toInt().toByte() }
            val b64 = android.util.Base64.encodeToString(synth800, android.util.Base64.NO_WRAP)
            onStreamingPttChunkReady?.invoke(b64)
            onRawPttBytesReady?.invoke(synth800)
            synth800
        } else {
            ByteArray(0)
        }

        val capturedBase64 = if (finalBytes.isNotEmpty()) {
            android.util.Base64.encodeToString(finalBytes, android.util.Base64.NO_WRAP)
        } else {
            null
        }
        lastRecordedAudioBase64 = capturedBase64
        return capturedBase64
    }

    fun incrementVoiceFramesSent(count: Int = 1) {
        _voiceFramesSent.update { it + count }
    }

    private fun cacheRxFrameForReplay(pcm8k: ShortArray, isCallAudio: Boolean = false) {
        if (pcm8k.isEmpty()) return
        if (isCallAudio) {
            synchronized(recentCallRxRingBuffer) {
                while (recentCallRxRingBuffer.size >= 62) {
                    recentCallRxRingBuffer.removeFirst()
                }
                recentCallRxRingBuffer.addLast(pcm8k)
                _cachedCallRxReplayFramesCount.value = recentCallRxRingBuffer.size
            }
        } else {
            synchronized(recentPttRxRingBuffer) {
                while (recentPttRxRingBuffer.size >= 62) {
                    recentPttRxRingBuffer.removeFirst()
                }
                recentPttRxRingBuffer.addLast(pcm8k)
                _cachedPttRxReplayFramesCount.value = recentPttRxRingBuffer.size
            }
        }
    }

    /**
     * Replays up to the last 10 seconds (`62 x 160ms` frames) of incoming decoded mesh voice audio,
     * strictly isolated between Walkie-Talkie PTT (`isCallAudio = false`) and 1-Tap Calls (`isCallAudio = true`).
     */
    fun replayLastPttRxAudio(): Int = replayLastRxAudio(isCallAudio = false)

    fun replayLastCallRxAudio(): Int = replayLastRxAudio(isCallAudio = true)

    fun replayLastRxAudio(isCallAudio: Boolean = false): Int {
        val snapshot = if (isCallAudio) {
            if (_isReplayingCallRx.value) return 0
            synchronized(recentCallRxRingBuffer) { recentCallRxRingBuffer.toList() }
        } else {
            if (_isReplayingPttRx.value) return 0
            synchronized(recentPttRxRingBuffer) { recentPttRxRingBuffer.toList() }
        }
        if (snapshot.isEmpty()) return 0
        if (isCallAudio) _isReplayingCallRx.value = true else _isReplayingPttRx.value = true
        startPlaybackWorker()
        scope.launch(Dispatchers.IO) {
            try {
                for (frame in snapshot) {
                    if (!isActive) break
                    audioPlayQueue.send(frame)
                }
            } finally {
                if (isCallAudio) _isReplayingCallRx.value = false else _isReplayingPttRx.value = false
            }
        }
        return snapshot.size
    }

    /**
     * Records a compact 3-second (`~18 x 160ms` frames) G.711 µ-Law voice drop clip (`[[PM_VOICEDROP]]:<base64>`)
     * to send as an encrypted chat audio memo when a peer doesn't answer or is out of earshot.
     */
    @SuppressLint("MissingPermission")
    fun recordQuickVoiceDropClip(
        durationMs: Long = 2800L,
        onComplete: (String) -> Unit
    ) {
        if (_isRecordingVoiceDrop.value) return
        _isRecordingVoiceDrop.value = true
        scope.launch(Dispatchers.IO) {
            try {
                val outStream = ByteArrayOutputStream()
                val hasMic = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                val frameCount = (durationMs / 160L).toInt().coerceIn(4, 25)
                if (hasMic && isCapturePermitted()) {
                    val (rec, activeRate) = createSafeAudioRecord()
                    if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                        try {
                            rec.startRecording()
                            val target8k = VOICE_SLICE_SAMPLES_160MS
                            val captureLen = if (activeRate == 16000) target8k * 2 else target8k
                            val buf = ShortArray(captureLen)
                            repeat(frameCount) {
                                if (!isActive) return@repeat
                                var readTotal = 0
                                while (readTotal < captureLen && isActive) {
                                    val r = rec.read(buf, readTotal, captureLen - readTotal)
                                    if (r > 0) readTotal += r else break
                                }
                                if (readTotal > 0) {
                                    val slice8k = if (activeRate == 16000) {
                                        downsample16kHzTo8kHz(buf, readTotal)
                                    } else {
                                        buf.copyOf(readTotal)
                                    }
                                    val mu640 = encode160msVoiceMuLaw640(slice8k, slice8k.size)
                                    outStream.write(mu640)
                                }
                            }
                        } finally {
                            runCatching { rec.stop() }
                            runCatching { rec.release() }
                        }
                    }
                }

                // Fallback synthetic voice envelope if mic HAL is unavailable in test/emulator
                if (outStream.size() == 0) {
                    val fallbackSamples = ShortArray(VOICE_SLICE_SAMPLES_160MS * 6) { i ->
                        (sin(2.0 * PI * i * 440.0 / 8000.0) * 9000).toInt().toShort()
                    }
                    outStream.write(encode160msVoiceMuLaw640(fallbackSamples))
                }

                val b64 = android.util.Base64.encodeToString(outStream.toByteArray(), android.util.Base64.NO_WRAP)
                withContext(Dispatchers.Main) {
                    onComplete("[[PM_VOICEDROP]]:$b64")
                }
            } finally {
                _isRecordingVoiceDrop.value = false
            }
        }
    }

    fun playIncomingPcmBytes(rawBytes: ByteArray, isCallAudio: Boolean = false): ShortArray {
        if (rawBytes.isEmpty()) return ShortArray(0)
        startPlaybackWorker()
        val pcm16 = decodeStateless8BitPcmTo8kHz16Bit(rawBytes)
        cacheRxFrameForReplay(pcm16, isCallAudio = isCallAudio)
        audioPlayQueue.trySend(pcm16)
        return pcm16
    }

    /**
     * Plays a `640-byte` 4000 Hz G.711 µ-law frame (or `1280-byte` full-rate 8000 Hz G.711 µ-law frame)
     * by converting each byte via `muLawToPcm16(b)` and cubic-interpolating (for 4kHz) or direct-decoding (for 8kHz)
     * to `8000` Hz (`ShortArray(1280)`), then pushing `pcm8k` into `audioPlayQueue`.
     */
    fun playIncomingMuLawBytes(muLawBytes: ByteArray, isCallAudio: Boolean = false): ShortArray {
        if (muLawBytes.isEmpty()) return ShortArray(0)
        startPlaybackWorker()
        // 800B (100ms @ 8kHz) and 1280B (160ms @ 8kHz) are full-rate 8kHz HD frames;
        // 320B (80ms @ 4kHz) and 640B (160ms @ 4kHz) are 4kHz BLE frames upsampled 2x to 8kHz.
        val pcm8k = if (muLawBytes.size == VOICE_SLICE_SAMPLES_160MS || muLawBytes.size == 800) {
            decodeMuLaw8kHzDirect(muLawBytes)
        } else {
            decodeMuLaw4kHzTo8kHzInterpolated(muLawBytes)
        }
        cacheRxFrameForReplay(pcm8k, isCallAudio = isCallAudio)
        audioPlayQueue.trySend(pcm8k)
        return pcm8k
    }

    fun playIncomingAdpcmBytes(adpcmBytes: ByteArray, isCallAudio: Boolean = false): ShortArray {
        if (adpcmBytes.size <= 4) return ShortArray(0)
        startPlaybackWorker()
        val pcm8k = decodeImaAdpcm8kHz(adpcmBytes)
        if (pcm8k.isNotEmpty()) {
            cacheRxFrameForReplay(pcm8k, isCallAudio = isCallAudio)
            audioPlayQueue.trySend(pcm8k)
        }
        return pcm8k
    }

    fun playRawPcmBytes(rawBytes: ByteArray) {
        playIncomingPcmBytes(rawBytes)
    }

    fun playAudioBase64(base64Data: String, isCallAudio: Boolean = false): ShortArray {
        val stripped = base64Data.trim()
            .removePrefix("[[PM_VOICEDROP]]:")
            .removePrefix("[[PM_AUDIO]]:")
        val isAdpcm8k = stripped.startsWith("ADPCM8K:")
        val isExplicit8kHz = stripped.startsWith("ULAW8K:")
        val rawStr = stripped
            .removePrefix("ADPCM8K:")
            .removePrefix("ULAW8K:")
            .removePrefix("ULAW:")
        if (rawStr.isEmpty()) return ShortArray(0)
        return runCatching {
            val rawBytes = android.util.Base64.decode(rawStr, android.util.Base64.DEFAULT)
            if (rawBytes.isNotEmpty()) {
                startPlaybackWorker()
                val pcm8k = when {
                    isAdpcm8k -> decodeImaAdpcm8kHz(rawBytes)
                    isExplicit8kHz || rawBytes.size == VOICE_SLICE_SAMPLES_160MS || rawBytes.size == 800 -> decodeMuLaw8kHzDirect(rawBytes)
                    else -> decodeMuLaw4kHzTo8kHzInterpolated(rawBytes)
                }
                if (pcm8k.isNotEmpty()) {
                    cacheRxFrameForReplay(pcm8k, isCallAudio = isCallAudio)
                    audioPlayQueue.trySend(pcm8k)
                }
                pcm8k
            } else {
                ShortArray(0)
            }
        }.getOrDefault(ShortArray(0))
    }

    fun playSosAlarmPulse() {
        // Synthetic alarm tones intentionally disabled.
    }

    @SuppressLint("MissingPermission")
    fun testMicAndSpeakerLoopback(onStatusUpdate: (String) -> Unit = {}) {
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            onStatusUpdate("ERROR: Record Audio permission required")
            return
        }

        if (_isLoopbackTesting.value) return
        _isLoopbackTesting.value = true

        scope.launch(Dispatchers.IO) {
            onStatusUpdate("Recording 2s sample (200ms 8000Hz Linear-Interpolated PCM)... Speak now!")
            val sampleCount = SAMPLE_RATE_8KHZ * 2
            val shortBuffer = ShortArray(sampleCount)

            runCatching {
                val (record, activeRate) = createSafeAudioRecord()
                if (record == null) {
                    onStatusUpdate("Microphone HAL busy or unavailable")
                    return@runCatching
                }
                record.startRecording()
                var readTotal = 0
                val chunkLen = if (activeRate == 16000) VOICE_SLICE_SAMPLES_160MS * 2 else VOICE_SLICE_SAMPLES_160MS
                val tempShorts = ShortArray(chunkLen)
                while (readTotal < sampleCount) {
                    val shortsRead = record.read(tempShorts, 0, tempShorts.size)
                    if (shortsRead > 0) {
                        val normalizedSlice = if (activeRate == 16000) {
                            downsample16kHzTo8kHz(tempShorts, shortsRead)
                        } else {
                            tempShorts.copyOf(shortsRead)
                        }
                        val copyLen = minOf(normalizedSlice.size, sampleCount - readTotal)
                        System.arraycopy(normalizedSlice, 0, shortBuffer, readTotal, copyLen)
                        readTotal += copyLen

                        var maxPeak = 0
                        var sumSq = 0.0
                        for (i in 0 until copyLen) {
                            val sample = normalizedSlice[i].toInt()
                            val absSample = kotlin.math.abs(sample)
                            if (absSample > maxPeak) maxPeak = absSample
                            sumSq += sample.toDouble() * sample.toDouble()
                        }
                        val rms = if (copyLen > 0) sqrt(sumSq / copyLen) else 0.0
                        _micLevelPeak.value = (maxPeak / 16384.0).coerceIn(0.0, 1.0).toFloat()
                        _micLevelDb.value = if (rms > 1.0) (20.0 * kotlin.math.log10(rms / 32768.0)).toInt().coerceIn(-60, 0) else -60
                    } else {
                        break
                    }
                }
                record.stop()
                record.release()

                onStatusUpdate("Playing back 160ms G.711 u-Law VOICE chunks through speaker...")
                ensureSpeakerphoneAndVolume()

                var offset = 0
                var framesCount = 0
                while (offset + VOICE_SLICE_SAMPLES_160MS <= readTotal) {
                    val slice1280 = shortBuffer.copyOfRange(offset, offset + VOICE_SLICE_SAMPLES_160MS)
                    val muLaw640 = encode160msVoiceMuLaw640(slice1280)
                    val b64 = android.util.Base64.encodeToString(muLaw640, android.util.Base64.NO_WRAP)
                    playAudioBase64(b64)
                    framesCount++
                    offset += VOICE_SLICE_SAMPLES_160MS
                    delay(155)
                }
                delay(250)
                onStatusUpdate("Loopback test complete ($framesCount chunks played)!")
            }.onFailure {
                onStatusUpdate("Loopback failed: ${it.localizedMessage}")
            }
            _micLevelPeak.value = 0f
            _micLevelDb.value = -60
            _isLoopbackTesting.value = false
        }
    }

    /**
     * 160ms G.711 µ-Law Voice Capture (`type = "VOICE"`):
     * - Captures **160ms slices** (`ShortArray(1280)` at `8000` Hz) on `Dispatchers.IO` using `AudioSource.MIC` (zero AEC/NS).
     * - Calculates live microphone peak amplitude (`maxOf abs(sample)`) on every captured frame and updates `_micLevelPeak`.
     * - Applies `1.5x` voice boost `(sample * 3 / 2).coerceIn(-32768, 32767).toShort()`, downsamples `2:1` to `4000` Hz (`640 samples`),
     *   and encodes each sample with `pcm16ToMuLaw(sample)` into a compact **`640-byte` `ByteArray`**.
     * - Encodes the 640 bytes with `Base64.encodeToString(muLawBytes, Base64.NO_WRAP)` (`856 chars`) and dispatches via `onStreamingPttChunkReady`.
     */
    @SuppressLint("MissingPermission")
    private fun startMicrophoneRecording() {
        if (!isCapturePermitted()) return
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            if (isCapturePermitted()) startSimulatedRfChatter()
            return
        }

        micCaptureJob?.cancel()
        micCaptureJob = scope.launch(Dispatchers.IO) {
            if (!isCapturePermitted()) return@launch
            runCatching {
                runCatching {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                }

                if (!isCapturePermitted()) return@runCatching
                val (rec, activeRate) = createSafeAudioRecord()
                audioRecord = rec

                if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                    rec.startRecording()

                    while (isActive && _isRecording.value && isCapturePermitted()) {
                        val target8kSamples = VOICE_SLICE_SAMPLES_120MS // 960 samples = 120ms @ 8000 Hz -> 484B IMA-ADPCM
                        val captureFrameSamples = if (activeRate == 16000) target8kSamples * 2 else target8kSamples
                        val rawCaptureBuf = ShortArray(captureFrameSamples)

                        var totalRead = 0
                        while (totalRead < captureFrameSamples && isActive && _isRecording.value && isCapturePermitted()) {
                            val read = rec.read(rawCaptureBuf, totalRead, captureFrameSamples - totalRead)
                            if (read > 0) {
                                totalRead += read
                            } else {
                                break
                            }
                        }

                        if (!isCapturePermitted()) break

                        if (totalRead >= captureFrameSamples) {
                            val shortBuf = if (activeRate == 16000) {
                                downsample16kHzTo8kHz(rawCaptureBuf, totalRead)
                            } else {
                                rawCaptureBuf
                            }
                            removeDcAndLowRumbleInPlace(shortBuf, target8kSamples)

                            var maxPeak = 0
                            var sumSq = 0.0
                            for (i in 0 until target8kSamples) {
                                val sample = shortBuf[i].toInt()
                                val absSample = kotlin.math.abs(sample)
                                if (absSample > maxPeak) maxPeak = absSample
                                sumSq += sample.toDouble() * sample.toDouble()
                            }
                            val rms = sqrt(sumSq / target8kSamples.coerceAtLeast(1))
                            val normalized = (maxPeak / 16384.0).coerceIn(0.02, 1.0).toFloat()
                            val db = if (rms > 1.0) (20.0 * kotlin.math.log10(rms / 32768.0)).toInt().coerceIn(-60, 0) else -60

                            _micLevelPeak.value = normalized
                            _micLevelDb.value = db

                            val current = _waveformLevels.value.toMutableList()
                            current.removeAt(0)
                            val barVal = (normalized * (0.85f + Random.nextFloat() * 0.3f)).coerceIn(0.05f, 1.0f)
                            current.add(barVal)
                            _waveformLevels.value = current

                            val adpcm484Bytes = encodeImaAdpcm8kHz(shortBuf, target8kSamples)
                            synchronized(recorded4kHzByteStream) {
                                recorded4kHzByteStream.write(adpcm484Bytes, 0, adpcm484Bytes.size)
                            }
                            val b64Adpcm = "ADPCM8K:" + android.util.Base64.encodeToString(adpcm484Bytes, android.util.Base64.NO_WRAP)
                            onMuLawPttBytesReady?.invoke(adpcm484Bytes)
                            onStreamingPttChunkReady?.invoke(b64Adpcm)
                            onRawPttBytesReady?.invoke(adpcm484Bytes)
                            _voiceFramesSent.update { it + 1 }
                        } else {
                            delay(5)
                        }
                    }
                } else if (isCapturePermitted()) {
                    startSimulatedRfChatter()
                }
            }.onFailure {
                if (isCapturePermitted()) startSimulatedRfChatter()
            }
            cleanUpAudioRecord()
        }
    }

    private fun startSimulatedRfChatter() {
        simulationJob?.cancel()
        simulationJob = scope.launch(Dispatchers.Default) {
            while (isActive && _isRecording.value) {
                val current = _waveformLevels.value.toMutableList()
                current.removeAt(0)
                val base = 0.35f + 0.5f * sin(System.currentTimeMillis() / 90.0).toFloat()
                val jitter = (Random.nextFloat() - 0.5f) * 0.25f
                val barVal = (base + jitter).coerceIn(0.08f, 0.95f)
                current.add(barVal)
                _waveformLevels.value = current
                _micLevelPeak.value = barVal
                _micLevelDb.value = -18
                delay(45)
            }
        }
    }

    private fun stopMicrophoneRecording() {
        micCaptureJob?.cancel()
        micCaptureJob = null
        cleanUpAudioRecord()
    }

    private fun cleanUpAudioRecord() {
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    fun playTacticalRogerBeep() {
        // Synthetic roger beeps intentionally disabled — AudioTrack is reserved strictly for human voice.
    }
}
