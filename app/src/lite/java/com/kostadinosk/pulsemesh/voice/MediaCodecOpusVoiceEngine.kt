// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.voice

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.kostadinosk.pulsemesh.service.TacticalAudioService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight Lite-flavor Voice Engine (zero native libwebrtc binary bloat):
 * - Uses Android hardware/software `MediaCodec` (`audio/opus`, 16 kHz mono, 20 kbps) on Android 10+
 *   when present on the chipset, paired with [AdaptiveJitterBuffer] (40-200ms + PLC).
 * - Falls back automatically to IMA-ADPCM (`"PA"`) on Android 7.0-9 (API 24-28) and in Robolectric JVM tests.
 */
class MediaCodecOpusVoiceEngine(context: Context) : VoiceTransportEngine {

    companion object {
        private const val MIME_OPUS = "audio/opus"
        private const val SAMPLE_RATE_OPUS = 16_000
        private const val OPUS_MAGIC_0: Byte = 0x4F // 'O'
        private const val OPUS_MAGIC_1: Byte = 0x50 // 'P'
    }

    private val jitterBuffer = AdaptiveJitterBuffer()
    private val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true) ||
        Build.MODEL.contains("robolectric", ignoreCase = true)
    private val opusSupported: Boolean = detectOpusCodecSupport()

    private val _stats = MutableStateFlow(
        VoiceCodecStats(
            mode = if (opusSupported) VoiceEngineMode.MEDIACODEC_OPUS_LITE else VoiceEngineMode.ADPCM_FALLBACK,
            codecName = if (opusSupported) "MediaCodec Opus 16kHz" else "IMA-ADPCM 16k/8k (Lite)",
            bitrateBps = if (opusSupported) 20_000 else 32_000,
            iceState = "OFFGRID_E2EE"
        )
    )
    override val stats: StateFlow<VoiceCodecStats> = _stats.asStateFlow()

    @Volatile
    private var muted: Boolean = false

    @Volatile
    private var opusEncoder: MediaCodec? = null

    @Volatile
    private var opusDecoder: MediaCodec? = null

    private fun detectOpusCodecSupport(): Boolean {
        if (isRobolectric || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            val hasEnc = infos.any { it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME_OPUS, ignoreCase = true) } }
            val hasDec = infos.any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME_OPUS, ignoreCase = true) } }
            hasEnc && hasDec
        }.getOrDefault(false)
    }

    override fun isNativeWebRtcAvailable(): Boolean = false

    @Synchronized
    override fun startCall(
        callId: String,
        peerNodeId: String,
        isCaller: Boolean,
        iceServers: List<IceServerConfig>,
        onOutboundAudio: (ByteArray) -> Unit,
        onSignalMessage: (String) -> Unit
    ) {
        stopCall()
        jitterBuffer.reset()
        if (opusSupported) {
            runCatching {
                val encFmt = MediaFormat.createAudioFormat(MIME_OPUS, SAMPLE_RATE_OPUS, 1).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, 20_000)
                }
                opusEncoder = MediaCodec.createEncoderByType(MIME_OPUS).apply {
                    configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
                val decFmt = MediaFormat.createAudioFormat(MIME_OPUS, SAMPLE_RATE_OPUS, 1)
                opusDecoder = MediaCodec.createDecoderByType(MIME_OPUS).apply {
                    configure(decFmt, null, null, 0)
                    start()
                }
            }.onFailure {
                releaseMediaCodecs()
            }
        }
        val usingOpus = opusEncoder != null && opusDecoder != null
        _stats.value = VoiceCodecStats(
            mode = if (usingOpus) VoiceEngineMode.MEDIACODEC_OPUS_LITE else VoiceEngineMode.ADPCM_FALLBACK,
            codecName = if (usingOpus) "MediaCodec Opus 16kHz" else "IMA-ADPCM 8k E2EE",
            bitrateBps = if (usingOpus) 20_000 else 32_000,
            iceState = "E2EE_UDP_BLE",
            dtxActive = false,
            fecEnabled = false
        )
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
    }

    override fun onRemoteSignal(signalJson: String) = Unit

    @Synchronized
    override fun encodeOutboundPcmFrame(pcmShorts: ShortArray, sampleRateHz: Int): ByteArray {
        if (pcmShorts.isEmpty() || muted) return ByteArray(0)
        val enc = opusEncoder
        if (enc != null) {
            val encoded = runCatching {
                val pcmBytes = ByteBuffer.allocate(pcmShorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (s in pcmShorts) pcmBytes.putShort(s)
                val inIdx = enc.dequeueInputBuffer(5_000L)
                if (inIdx >= 0) {
                    val inBuf = enc.getInputBuffer(inIdx)
                    inBuf?.clear()
                    inBuf?.put(pcmBytes.array())
                    enc.queueInputBuffer(inIdx, 0, pcmBytes.capacity(), System.nanoTime() / 1000L, 0)
                }
                val info = MediaCodec.BufferInfo()
                val outIdx = enc.dequeueOutputBuffer(info, 5_000L)
                if (outIdx >= 0) {
                    val outBuf = enc.getOutputBuffer(outIdx)
                    val out = ByteArray(info.size + 2)
                    out[0] = OPUS_MAGIC_0
                    out[1] = OPUS_MAGIC_1
                    outBuf?.get(out, 2, info.size)
                    enc.releaseOutputBuffer(outIdx, false)
                    out
                } else null
            }.getOrNull()
            if (encoded != null && encoded.size > 2) return encoded
        }
        val pcm8k = if (sampleRateHz == 16000) TacticalAudioService.downsample16kHzTo8kHz(pcmShorts) else pcmShorts
        return TacticalAudioService.encodeImaAdpcm8kHz(pcm8k, pcm8k.size)
    }

    @Synchronized
    override fun onInboundEncodedFrame(srcNodeId: String, seq: Int, payloadBytes: ByteArray): ShortArray? {
        if (payloadBytes.isEmpty()) return null
        val decoded: ShortArray? = if (payloadBytes.size > 2 &&
            payloadBytes[0] == OPUS_MAGIC_0 &&
            payloadBytes[1] == OPUS_MAGIC_1 &&
            opusDecoder != null
        ) {
            decodeOpusPacket(payloadBytes, 2, payloadBytes.size - 2)
        } else {
            TacticalAudioService.decodeImaAdpcm8kHz(payloadBytes)
        }
        if (decoded == null || decoded.isEmpty()) return null
        jitterBuffer.pushFrame(seq, decoded)
        _stats.value = _stats.value.copy(
            jitterMs = jitterBuffer.getJitterMs(),
            packetLossPct = jitterBuffer.getPacketLossPct()
        )
        return jitterBuffer.popNextFrame(decoded.size) ?: decoded
    }

    private fun decodeOpusPacket(buf: ByteArray, offset: Int, len: Int): ShortArray? {
        val dec = opusDecoder ?: return null
        return runCatching {
            val inIdx = dec.dequeueInputBuffer(5_000L)
            if (inIdx >= 0) {
                val inBuf = dec.getInputBuffer(inIdx)
                inBuf?.clear()
                inBuf?.put(buf, offset, len)
                dec.queueInputBuffer(inIdx, 0, len, System.nanoTime() / 1000L, 0)
            }
            val info = MediaCodec.BufferInfo()
            val outIdx = dec.dequeueOutputBuffer(info, 5_000L)
            if (outIdx >= 0) {
                val outBuf = dec.getOutputBuffer(outIdx)
                val pcmBytes = ByteArray(info.size)
                outBuf?.get(pcmBytes)
                dec.releaseOutputBuffer(outIdx, false)
                val shortBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                ShortArray(pcmBytes.size / 2) { shortBuf.get(it) }
            } else null
        }.getOrNull()
    }

    @Synchronized
    override fun stopCall() {
        releaseMediaCodecs()
        jitterBuffer.reset()
        _stats.value = _stats.value.copy(jitterMs = 0, packetLossPct = 0f)
    }

    private fun releaseMediaCodecs() {
        runCatching {
            opusEncoder?.stop()
            opusEncoder?.release()
        }
        opusEncoder = null
        runCatching {
            opusDecoder?.stop()
            opusDecoder?.release()
        }
        opusDecoder = null
    }
}
