// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.os.Build
import com.kostadinosk.pulsemesh.service.TacticalAudioService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.BuiltinAudioDecoderFactoryFactory
import org.webrtc.BuiltinAudioEncoderFactoryFactory
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Full-flavor WebRTC voice engine (`io.getstream:stream-video-webrtc-android`):
 * - Uses libwebrtc Opus 16-24 kbps with in-band FEC and DTX over ICE/STUN/TURN (Cloudflare TURN
 *   on hybrid internet links + direct local/hotspot candidates).
 * - Uses hardware AcousticEchoCanceler & NoiseSuppressor via [JavaAudioDeviceModule] and `VOICE_COMMUNICATION`.
 * - Falls back seamlessly to [AdaptiveJitterBuffer] + IMA-ADPCM for off-grid P2P/BLE calls and
 *   safely bypasses native `.so` loading under Robolectric JVM tests.
 */
class WebRtcVoiceEngine(context: Context) : VoiceTransportEngine {

    private val appContext = context.applicationContext
    private val jitterBuffer = AdaptiveJitterBuffer()

    private val _stats = MutableStateFlow(
        VoiceCodecStats(
            mode = VoiceEngineMode.WEBRTC_FULL,
            codecName = "WebRTC Opus (DTX/FEC)",
            bitrateBps = 24_000,
            dtxActive = true,
            fecEnabled = true,
            iceState = "STANDBY"
        )
    )
    override val stats: StateFlow<VoiceCodecStats> = _stats.asStateFlow()

    @Volatile
    private var factory: PeerConnectionFactory? = null

    @Volatile
    private var peerConnection: PeerConnection? = null

    @Volatile
    private var audioSource: AudioSource? = null

    @Volatile
    private var localAudioTrack: AudioTrack? = null

    @Volatile
    private var audioDeviceModule: JavaAudioDeviceModule? = null

    @Volatile
    private var nativeReady: Boolean = false

    @Volatile
    private var muted: Boolean = false

    companion object {
        @Volatile
        private var globalWebRtcInitialized: Boolean = false
    }

    private fun isRobolectric(): Boolean {
        return Build.FINGERPRINT.equals("robolectric", ignoreCase = true) ||
            Build.MODEL.contains("robolectric", ignoreCase = true)
    }

    @Synchronized
    private fun initWebRtcFactoryIfSupported() {
        if (nativeReady) return
        if (isRobolectric()) {
            _stats.value = _stats.value.copy(
                mode = VoiceEngineMode.ADPCM_FALLBACK,
                codecName = "IMA-ADPCM 16k/8k (Off-Grid / JVM)",
                iceState = "OFFGRID"
            )
            return
        }
        val hasMicPerm = androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) {
            _stats.value = _stats.value.copy(
                mode = VoiceEngineMode.ADPCM_FALLBACK,
                codecName = "IMA-ADPCM 16k/8k (Mic Perm Pending)",
                iceState = "MIC_PERMISSION_NEEDED"
            )
            nativeReady = false
            return
        }
        val ok = runCatching {
            if (!globalWebRtcInitialized) {
                synchronized(WebRtcVoiceEngine::class.java) {
                    if (!globalWebRtcInitialized) {
                        PeerConnectionFactory.initialize(
                            PeerConnectionFactory.InitializationOptions.builder(appContext)
                                .setEnableInternalTracer(false)
                                .createInitializationOptions()
                        )
                        globalWebRtcInitialized = true
                    }
                }
            }
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val hasHwAec = runCatching { android.media.audiofx.AcousticEchoCanceler.isAvailable() }.getOrDefault(false)
            val hasHwNs = runCatching { android.media.audiofx.NoiseSuppressor.isAvailable() }.getOrDefault(false)
            val hasLowLatency = runCatching {
                appContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUDIO_LOW_LATENCY)
            }.getOrDefault(false)
            val adm = JavaAudioDeviceModule.builder(appContext)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setUseHardwareAcousticEchoCanceler(hasHwAec)
                .setUseHardwareNoiseSuppressor(hasHwNs)
                .setUseLowLatency(hasLowLatency)
                .setAudioAttributes(attrs)
                .createAudioDeviceModule()
            audioDeviceModule = adm
            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setAudioEncoderFactoryFactory(BuiltinAudioEncoderFactoryFactory())
                .setAudioDecoderFactoryFactory(BuiltinAudioDecoderFactoryFactory())
                .createPeerConnectionFactory()
            factory != null
        }.getOrDefault(false)
        nativeReady = ok
        if (!ok) {
            _stats.value = _stats.value.copy(
                mode = VoiceEngineMode.ADPCM_FALLBACK,
                codecName = "IMA-ADPCM 16k/8k (Fallback)",
                iceState = "FALLBACK"
            )
        }
    }

    override fun isNativeWebRtcAvailable(): Boolean = nativeReady

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
        initWebRtcFactoryIfSupported()

        val pcf = factory
        if (!nativeReady || pcf == null) {
            _stats.value = _stats.value.copy(
                mode = VoiceEngineMode.ADPCM_FALLBACK,
                codecName = "IMA-ADPCM 8k E2EE",
                bitrateBps = 32_000,
                iceState = "OFFGRID_UDP",
                dtxActive = false,
                fecEnabled = false
            )
            return
        }

        val rtcIce = if (iceServers.isNotEmpty()) {
            iceServers.map { cfg ->
                PeerConnection.IceServer.builder(cfg.urls).apply {
                    if (cfg.username.isNotBlank()) setUsername(cfg.username)
                    if (cfg.credential.isNotBlank()) setPassword(cfg.credential)
                }.createIceServer()
            }
        } else {
            listOf(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())
        }

        val rtcConfig = PeerConnection.RTCConfiguration(rtcIce).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                _stats.value = _stats.value.copy(iceState = newState?.name ?: "UNKNOWN")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                val payload = JSONObject().apply {
                    put("kind", "ice")
                    put("callId", callId)
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                }.toString()
                onSignalMessage(payload)
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(dataChannel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit
        }

        val pc = pcf.createPeerConnection(rtcConfig, observer)
        if (pc == null) {
            _stats.value = _stats.value.copy(iceState = "PC_INIT_FAILED")
            return
        }
        peerConnection = pc

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        val src = pcf.createAudioSource(audioConstraints)
        audioSource = src
        val track = pcf.createAudioTrack("pulsemesh_audio_0", src).apply {
            setEnabled(!muted)
        }
        localAudioTrack = track
        pc.addTrack(track, listOf("pulsemesh_stream"))

        _stats.value = VoiceCodecStats(
            mode = VoiceEngineMode.WEBRTC_FULL,
            codecName = "Opus 24kbps DTX+FEC",
            bitrateBps = 24_000,
            iceState = "CHECKING",
            dtxActive = true,
            fecEnabled = true
        )

        if (isCaller) {
            val sdpConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            }
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    val offer = sdp ?: return
                    val tuned = SessionDescription(offer.type, tuneOpusFmtp(offer.description))
                    pc.setLocalDescription(NoopSdpObserver, tuned)
                    val json = JSONObject().apply {
                        put("kind", "offer")
                        put("callId", callId)
                        put("sdp", tuned.description)
                    }.toString()
                    onSignalMessage(json)
                }
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String?) = Unit
                override fun onSetFailure(error: String?) = Unit
            }, sdpConstraints)
        }
    }

    @Synchronized
    override fun onRemoteSignal(signalJson: String) {
        val pc = peerConnection ?: return
        runCatching {
            val obj = JSONObject(signalJson)
            when (obj.optString("kind")) {
                "offer" -> {
                    val sdp = SessionDescription(SessionDescription.Type.OFFER, obj.getString("sdp"))
                    pc.setRemoteDescription(NoopSdpObserver, sdp)
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    }
                    pc.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(ans: SessionDescription?) {
                            val a = ans ?: return
                            val tuned = SessionDescription(a.type, tuneOpusFmtp(a.description))
                            pc.setLocalDescription(NoopSdpObserver, tuned)
                        }
                        override fun onSetSuccess() = Unit
                        override fun onCreateFailure(error: String?) = Unit
                        override fun onSetFailure(error: String?) = Unit
                    }, constraints)
                }
                "answer" -> {
                    val sdp = SessionDescription(SessionDescription.Type.ANSWER, obj.getString("sdp"))
                    pc.setRemoteDescription(NoopSdpObserver, sdp)
                }
                "ice" -> {
                    val cand = IceCandidate(
                        obj.optString("sdpMid", "0"),
                        obj.optInt("sdpMLineIndex", 0),
                        obj.getString("candidate")
                    )
                    pc.addIceCandidate(cand)
                }
            }
        }
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        runCatching { localAudioTrack?.setEnabled(!muted) }
    }

    override fun encodeOutboundPcmFrame(pcmShorts: ShortArray, sampleRateHz: Int): ByteArray {
        val pcm8k = if (sampleRateHz == 16000) TacticalAudioService.downsample16kHzTo8kHz(pcmShorts) else pcmShorts
        return TacticalAudioService.encodeImaAdpcm8kHz(pcm8k, pcm8k.size)
    }

    override fun onInboundEncodedFrame(srcNodeId: String, seq: Int, payloadBytes: ByteArray): ShortArray? {
        if (payloadBytes.isEmpty()) return null
        val decoded = TacticalAudioService.decodeImaAdpcm8kHz(payloadBytes)
        if (decoded.isEmpty()) return null
        jitterBuffer.pushFrame(seq, decoded)
        _stats.value = _stats.value.copy(
            jitterMs = jitterBuffer.getJitterMs(),
            packetLossPct = jitterBuffer.getPacketLossPct()
        )
        return jitterBuffer.popNextFrame(decoded.size) ?: decoded
    }

    @Synchronized
    override fun stopCall() {
        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching {
            peerConnection?.close()
            peerConnection?.dispose()
        }
        peerConnection = null
        jitterBuffer.reset()
        _stats.value = _stats.value.copy(iceState = "STANDBY", packetLossPct = 0f, jitterMs = 0)
    }

    private fun tuneOpusFmtp(sdp: String): String {
        return sdp.replace(
            "useinbandfec=1",
            "useinbandfec=1;usedtx=1;maxaveragebitrate=24000;ptime=20"
        )
    }

    private object NoopSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}
