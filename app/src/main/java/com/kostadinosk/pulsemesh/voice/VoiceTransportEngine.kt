// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.voice

import kotlinx.coroutines.flow.StateFlow

enum class VoiceEngineMode {
    WEBRTC_FULL,
    MEDIACODEC_OPUS_LITE,
    ADPCM_FALLBACK
}

data class VoiceCodecStats(
    val mode: VoiceEngineMode = VoiceEngineMode.ADPCM_FALLBACK,
    val codecName: String = "IMA-ADPCM 16k/8k",
    val bitrateBps: Int = 32_000,
    val rttMs: Int = 0,
    val packetLossPct: Float = 0f,
    val jitterMs: Int = 0,
    val iceState: String = "STANDBY",
    val dtxActive: Boolean = false,
    val fecEnabled: Boolean = false
)

data class IceServerConfig(
    val urls: List<String>,
    val username: String = "",
    val credential: String = ""
)

/**
 * Unified interface implemented per flavor:
 * - Full flavor: [WebRtcVoiceEngine] (WebRTC libwebrtc Opus DTX/FEC + ICE + Cloudflare TURN + off-grid UDP fallback)
 * - Lite flavor: [MediaCodecOpusVoiceEngine] (Android MediaCodec Opus API 29+ / ADPCM fallback + AdaptiveJitterBuffer)
 */
interface VoiceTransportEngine {
    val stats: StateFlow<VoiceCodecStats>

    fun isNativeWebRtcAvailable(): Boolean

    fun startCall(
        callId: String,
        peerNodeId: String,
        isCaller: Boolean,
        iceServers: List<IceServerConfig>,
        onOutboundAudio: (ByteArray) -> Unit,
        onSignalMessage: (String) -> Unit
    )

    fun stopCall()

    fun setMuted(muted: Boolean)

    fun onRemoteSignal(signalJson: String)

    fun encodeOutboundPcmFrame(pcmShorts: ShortArray, sampleRateHz: Int = 8000): ByteArray

    fun onInboundEncodedFrame(srcNodeId: String, seq: Int, payloadBytes: ByteArray): ShortArray?
}
