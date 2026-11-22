// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.sqrt

enum class CallSignalingState {
    IDLE,
    RINGING_OUTGOING,
    RINGING_INCOMING,
    IN_CALL_ACTIVE
}

data class DuplexCallState(
    val signalingState: CallSignalingState = CallSignalingState.IDLE,
    val isInCall: Boolean = false,
    val isRinging: Boolean = false,
    val isIncoming: Boolean = false,
    val peerNodeId: String = "",
    val peerCallsign: String = "",
    val durationSeconds: Int = 0,
    val isMuted: Boolean = false,
    val isPushToOverrideActive: Boolean = false,
    val isSpeakerphoneOn: Boolean = true,
    val transportType: String = "G.711 u-Law 160ms (640B / 0xCAFF + UDP)",
    val audioLevelRms: Float = 0.0f,
    val micLevelDb: Int = -60,
    val voiceFramesSent: Int = 0,
    val voiceFramesPlayed: Int = 0,
    val isVoiceTransmitting: Boolean = false,
    val isPeerSpeaking: Boolean = false,
    val isHighSpeedLink: Boolean = true,
    val isCallAcceptAcked: Boolean = false,
    val isRecordingVoicemail: Boolean = false,
    val voicemailPromptText: String = ""
)

/**
 * 3-State Call Signaling & Collision-Free Voice Duplexing Engine (Zero Synthetic Beeps):
 * 1. Incoming calls use ONLY the visual Incoming Call banner and device vibration (`Vibrator`) — zero `ToneGenerator` or synthetic tones.
 * 2. Tapping "Accept" stops vibration, immediately sets `IN_CALL_ACTIVE`, and waits `startMicDelayMs` (350ms)
 *    before starting microphone capture so all 3 `CALL_ACCEPT` signals finish transmitting first with zero contention.
 * 3. Captures 160ms slices (`ShortArray(1280)` @ 8000 Hz) with `AudioSource.MIC` (zero AEC/NS), applies 1.5x voice boost,
 *    downsamples 2:1 to 4000 Hz (`640 samples`), and encodes with `pcm16ToMuLaw` into a compact `640-byte` `ByteArray`.
 * 4. Collision-Free Voice Duplexing during `IN_CALL_ACTIVE`:
 *    - Tracks `lastIncomingAudioTime` (updated every time a `"VOICE"` packet arrives).
 *    - When on Hotspot Wi-Fi UDP (`isHighSpeedLinkActive() == true`), allows full-duplex simultaneous speaking whenever `micPeak > 300` (or `isPushToOverrideActive`).
 *    - When on pure BLE GATT (`isHighSpeedLinkActive() == false`), automatically pauses outgoing BLE `VOICE` transmission while
 *      `System.currentTimeMillis() - lastIncomingAudioTime < 350L` (unless `isPushToOverrideActive == true`) and only transmits
 *      when you speak (`micPeak > 350` or `isPushToOverrideActive`).
 */
class TacticalCallService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val audioService: TacticalAudioService? = null,
    private val onSendAudioFrame: (ByteArray) -> Unit = {}
) {
    companion object {
        const val BLE_HALF_DUPLEX_DUCK_WINDOW_MS = 350L
        const val SPEAKER_ECHO_DUCK_WINDOW_MS = 260L
        const val CALL_VAD_SPEAKER_ECHO_PEAK_THRESHOLD = 1650

        fun shouldTransmitCallVoiceFrame(
            hasHotspotUdpLink: Boolean,
            micPeak: Int,
            nowMs: Long,
            lastIncomingAudioTime: Long,
            pushToOverrideActive: Boolean = false,
            isMuted: Boolean = false,
            isSpeakerphoneOn: Boolean = false
        ): Boolean {
            if (isMuted) return false
            if (pushToOverrideActive) return true
            return if (hasHotspotUdpLink) {
                if (isSpeakerphoneOn && (nowMs - lastIncomingAudioTime) < SPEAKER_ECHO_DUCK_WINDOW_MS) {
                    // Suppress speakerphone-to-mic acoustic echo ("micing") while letting close-mic speech override
                    micPeak > CALL_VAD_SPEAKER_ECHO_PEAK_THRESHOLD
                } else {
                    micPeak > TacticalAudioService.CALL_VAD_PEAK_THRESHOLD // 300
                }
            } else {
                val isOtherPersonSpeaking = (nowMs - lastIncomingAudioTime) < BLE_HALF_DUPLEX_DUCK_WINDOW_MS
                !isOtherPersonSpeaking && micPeak > TacticalAudioService.CALL_VAD_BLE_PEAK_THRESHOLD // 350
            }
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private val _callState = MutableStateFlow(DuplexCallState())
    val callState: StateFlow<DuplexCallState> = _callState.asStateFlow()

    var isHighSpeedLinkActive: () -> Boolean = { true }
    var isBleVoiceSending: () -> Boolean = { false }
    var isCapturePermitted: () -> Boolean = { true }

    var onUnansweredCallResetToIdle: (() -> Unit)? = null

    /**
     * Dispatches 90-byte Base64 micro-chunks for a speech slice when needed.
     */
    var onSendCallMicroChunks: ((clipId: String, base64Chunks: List<String>) -> Unit)? = null

    @Volatile
    var lastIncomingAudioTime: Long = 0L

    @Volatile
    var isOutgoingRingbackActive: Boolean = false
        private set

    @Volatile
    var isIncomingRingtoneActive: Boolean = false
        private set

    @Volatile
    var lastConfirmationToneType: Int = -1
        private set

    private var micCaptureJob: Job? = null
    private var voicemailCaptureJob: Job? = null
    private var delayedMicStartJob: Job? = null
    private var networkSenderJob: Job? = null
    private var playJob: Job? = null
    private var timerJob: Job? = null
    private var outgoingRingResetJob: Job? = null
    private var outgoingRingbackToneJob: Job? = null
    private var incomingRingtoneJob: Job? = null
    private var peerSpeakingClearJob: Job? = null

    private val voicemailBuffer = ByteArrayOutputStream()

    @Volatile
    private var ttsEngine: TextToSpeech? = null

    @Volatile
    private var isTtsReady: Boolean = false

    @Volatile
    private var pendingTtsSpeech: String? = null

    @Volatile
    private var activeToneGenerator: ToneGenerator? = null

    @Volatile
    private var activeIncomingRingtone: Ringtone? = null

    private val audioRecordLock = Any()
    private val audioTrackLock = Any()
    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var audioTrack: AudioTrack? = null

    private val voiceTxChannel = Channel<ByteArray>(capacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val playbackChannel = Channel<ShortArray>(capacity = 20, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val sampleRate = TacticalAudioService.SAMPLE_RATE_8KHZ // 8000 Hz mono voice
    private val channelIn = AudioFormat.CHANNEL_IN_MONO
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    init {
        if (audioService == null) {
            initPersistentAudioTrack()
        }
        startAsyncNetworkSenderWorker()
        initTtsEngineSafely()
    }

    private fun initTtsEngineSafely() {
        runCatching {
            ttsEngine = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    runCatching {
                        ttsEngine?.language = Locale.US
                        ttsEngine?.setSpeechRate(1.05f)
                    }
                    isTtsReady = true
                    val queued = pendingTtsSpeech
                    if (!queued.isNullOrBlank()) {
                        pendingTtsSpeech = null
                        runCatching {
                            ttsEngine?.speak(queued, TextToSpeech.QUEUE_FLUSH, null, "pulsemesh_tts")
                        }
                    }
                }
            }
        }
    }

    fun speakVoicePrompt(text: String) {
        if (text.isBlank()) return
        configureAudioForSpeakerphone()
        runCatching {
            if (isTtsReady && ttsEngine != null) {
                ttsEngine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pulsemesh_tts_${System.currentTimeMillis()}")
            } else {
                pendingTtsSpeech = text
            }
        }
    }

    private fun startAsyncNetworkSenderWorker() {
        networkSenderJob?.cancel()
        networkSenderJob = scope.launch(Dispatchers.IO) {
            for (muLaw640Bytes in voiceTxChannel) {
                if (!isActive) break
                if (_callState.value.isInCall && !_callState.value.isMuted) {
                    runCatching {
                        onSendAudioFrame(muLaw640Bytes)
                    }
                }
            }
        }
    }

    fun startOutgoingCall(peerId: String, peerCallsign: String) {
        stopAllRinging()
        voicemailCaptureJob?.cancel()
        voicemailCaptureJob = null
        synchronized(voicemailBuffer) { voicemailBuffer.reset() }
        delayedMicStartJob?.cancel()
        stopAudioCapture()
        _callState.value = DuplexCallState(
            signalingState = CallSignalingState.RINGING_OUTGOING,
            isRinging = true,
            isIncoming = false,
            peerNodeId = peerId,
            peerCallsign = peerCallsign
        )
        startOutgoingRingbackTone()

        // If 20 seconds pass with no answer, speak prompt and transition into Voicemail Recording mode
        // so the caller can state their message and press Hang Up to dispatch it into Chat.
        outgoingRingResetJob?.cancel()
        outgoingRingResetJob = scope.launch(Dispatchers.Default) {
            delay(20_000L)
            if (isActive && _callState.value.signalingState == CallSignalingState.RINGING_OUTGOING) {
                onUnansweredCallResetToIdle?.invoke()
                enterVoicemailRecordingMode(
                    peerId = peerId,
                    peerCallsign = peerCallsign,
                    promptBanner = "RECIPIENT DID NOT PICK UP — SPEAK YOUR MESSAGE, THEN TAP HANG UP TO SEND",
                    spokenPrompt = "The recipient has not picked up. Please say what you want to send, then press hang up."
                )
            }
        }
    }

    fun startCall(peerId: String, peerCallsign: String) {
        startOutgoingCall(peerId, peerCallsign)
    }

    fun startIncomingCall(callerId: String, callerCallsign: String, announceCallerId: Boolean = false) {
        if (_callState.value.signalingState == CallSignalingState.IN_CALL_ACTIVE && _callState.value.isInCall) {
            return
        }
        if (_callState.value.signalingState == CallSignalingState.RINGING_INCOMING &&
            _callState.value.peerNodeId == callerId
        ) {
            return
        }
        stopAllRinging()
        voicemailCaptureJob?.cancel()
        voicemailCaptureJob = null
        _callState.value = DuplexCallState(
            signalingState = CallSignalingState.RINGING_INCOMING,
            isRinging = true,
            isIncoming = true,
            peerNodeId = callerId,
            peerCallsign = callerCallsign
        )

        if (announceCallerId) {
            speakVoicePrompt("Incoming mesh call from $callerCallsign")
        }
        startIncomingRingtoneAndVibration()
    }

    /**
     * Enters live Voicemail Recording mode when a call goes unanswered for 20s or when the remote party hangs up/declines:
     * - Speaks the voice prompt out loud (`spokenPrompt`)
     * - Records the caller's live microphone continuously until the caller taps Hang Up (`stopVoicemailAndEndCall`)
     */
    @SuppressLint("MissingPermission")
    fun enterVoicemailRecordingMode(
        peerId: String = _callState.value.peerNodeId,
        peerCallsign: String = _callState.value.peerCallsign,
        promptBanner: String = "RECIPIENT DID NOT PICK UP — SPEAK YOUR MESSAGE, THEN TAP HANG UP TO SEND",
        spokenPrompt: String = "The recipient has not picked up. Please say what you want to send, then press hang up."
    ) {
        stopAllRinging()
        delayedMicStartJob?.cancel()
        delayedMicStartJob = null
        stopAudioCapture()
        voicemailCaptureJob?.cancel()
        synchronized(voicemailBuffer) { voicemailBuffer.reset() }

        val cleanId = peerId.ifBlank { "ALL" }
        val cleanName = peerCallsign.ifBlank { cleanId }

        _callState.value = DuplexCallState(
            signalingState = CallSignalingState.IDLE,
            isInCall = false,
            isRinging = false,
            isIncoming = false,
            peerNodeId = cleanId,
            peerCallsign = cleanName,
            durationSeconds = 0,
            isRecordingVoicemail = true,
            voicemailPromptText = promptBanner,
            transportType = "VOICEMAIL RECORDING (8kHz G.711 -> CHAT)"
        )

        speakVoicePrompt(spokenPrompt)

        timerJob?.cancel()
        timerJob = scope.launch(Dispatchers.Default) {
            var sec = 0
            while (isActive && _callState.value.isRecordingVoicemail) {
                delay(1000L)
                sec++
                _callState.update { it.copy(durationSeconds = sec) }
            }
        }

        voicemailCaptureJob = scope.launch(Dispatchers.IO) {
            // Wait 1.6s so the spoken TTS prompt finishes or clears its initial phrase before opening the mic
            delay(1600L)
            if (!isActive || !_callState.value.isRecordingVoicemail) return@launch
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMic || !isCapturePermitted()) return@launch

            runCatching {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            }
            val (rec, activeRate) = TacticalAudioService.createSafeAudioRecord()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                synchronized(audioRecordLock) {
                    audioRecord = rec
                }
                val audioEffects = TacticalAudioService.attachManagedAudioEffects(rec.audioSessionId)
                try {
                    rec.startRecording()
                    val target8k = 800 // 100ms slices @ 8000 Hz
                    val captureSamples = if (activeRate == 16000) target8k * 2 else target8k
                    val rawBuf = ShortArray(captureSamples)
                    var recordedSlices = 0
                    // Allow up to 60 seconds (600 x 100ms slices) of voicemail recording until user hangs up
                    while (isActive && _callState.value.isRecordingVoicemail && recordedSlices < 600 && isCapturePermitted()) {
                        var totalRead = 0
                        while (totalRead < captureSamples && isActive && _callState.value.isRecordingVoicemail) {
                            val r = rec.read(rawBuf, totalRead, captureSamples - totalRead)
                            if (r > 0) totalRead += r else break
                        }
                        if (totalRead >= captureSamples) {
                            val shortBuf = if (activeRate == 16000) {
                                TacticalAudioService.downsample16kHzTo8kHz(rawBuf, totalRead)
                            } else {
                                rawBuf
                            }
                            val validSamples = minOf(target8k, shortBuf.size)
                            if (validSamples <= 0) continue
                            TacticalAudioService.removeDcAndLowRumbleInPlace(shortBuf, validSamples)

                            var maxPeak = 0
                            var sumSq = 0.0
                            for (i in 0 until validSamples) {
                                val s = shortBuf[i].toInt()
                                val absS = kotlin.math.abs(s)
                                if (absS > maxPeak) maxPeak = absS
                                sumSq += s.toDouble() * s.toDouble()
                            }
                            val rms = sqrt(sumSq / validSamples.coerceAtLeast(1))
                            val rawNorm = (maxPeak / 16384.0).coerceIn(0.0, 1.0).toFloat()
                            val peakNorm = if (rawNorm.isFinite()) rawNorm else 0f
                            val db = if (rms > 1.0 && rms.isFinite()) (20.0 * kotlin.math.log10(rms / 32768.0)).toInt().coerceIn(-60, 0) else -60

                            val muBytes = TacticalAudioService.encode160msVoiceMuLaw8kHz1280(shortBuf, validSamples)
                            synchronized(voicemailBuffer) {
                                voicemailBuffer.write(muBytes)
                            }
                            recordedSlices++
                            _callState.update {
                                it.copy(
                                    audioLevelRms = peakNorm.coerceAtLeast(0.06f),
                                    micLevelDb = db,
                                    voiceFramesSent = recordedSlices,
                                    isVoiceTransmitting = maxPeak > 250
                                )
                            }
                        } else {
                            delay(5L)
                        }
                    }
                } catch (_: Throwable) {
                    // Prevent mic HAL errors from crashing voicemail capture
                } finally {
                    audioEffects.release()
                    releaseAudioRecordInstance(rec)
                }
            }
        }
    }

    /**
     * Stops any active voicemail recording when the caller taps Hang Up, returning
     * `Triple(peerNodeId, peerCallsign, voiceDropPayload)` if audio was recorded.
     */
    fun finishVoicemailAndEndCall(): Triple<String, String, String>? {
        val st = _callState.value
        val wasRecordingVm = st.isRecordingVoicemail
        val peerId = st.peerNodeId
        val peerName = st.peerCallsign
        voicemailCaptureJob?.cancel()
        voicemailCaptureJob = null
        runCatching { ttsEngine?.stop() }
        cleanUpRecord()

        val recordedBytes = synchronized(voicemailBuffer) {
            val bytes = voicemailBuffer.toByteArray()
            voicemailBuffer.reset()
            bytes
        }

        endCall(isUserBusy = false)

        if (!wasRecordingVm || recordedBytes.size < 400) {
            return null
        }
        val b64 = android.util.Base64.encodeToString(recordedBytes, android.util.Base64.NO_WRAP)
        return Triple(peerId, peerName, "[[PM_VOICEDROP]]:ULAW8K:$b64")
    }

    /**
     * Transitions call state to `IN_CALL_ACTIVE`, cancels ringtones/vibration, plays a brief connect confirmation tone,
     * and starts microphone capture after `startMicDelayMs` (default 350ms) so the 3 `CALL_ACCEPT` signals transmit first with zero contention.
     */
    fun acceptCall(startMicDelayMs: Long = 350L) {
        stopAllRinging()
        if (_callState.value.signalingState == CallSignalingState.IN_CALL_ACTIVE && _callState.value.isInCall) {
            return
        }

        val current = _callState.value
        _callState.value = current.copy(
            signalingState = CallSignalingState.IN_CALL_ACTIVE,
            isInCall = true,
            isRinging = false,
            isIncoming = false,
            durationSeconds = 0,
            isMuted = false,
            isPushToOverrideActive = false,
            isSpeakerphoneOn = true,
            transportType = if (isHighSpeedLinkActive()) {
                "IMA-ADPCM 8kHz HD 80ms (324B / Binary UDP 45888)"
            } else {
                "IMA-ADPCM 8kHz HD 120ms (484B / BLE L2CAP+0xCAFF)"
            },
            voiceFramesSent = 0,
            voiceFramesPlayed = 0,
            isVoiceTransmitting = false,
            isPeerSpeaking = false,
            isHighSpeedLink = isHighSpeedLinkActive(),
            isCallAcceptAcked = startMicDelayMs == 0L
        )

        playBriefConfirmationTone(ToneGenerator.TONE_PROP_ACK)
        audioService?.setSpeakerRouting(true)
        configureAudioForSpeakerphone()
        if (audioService == null) {
            ensurePersistentAudioTrackPlaying()
        }
        startCallTimer()

        delayedMicStartJob?.cancel()
        if (isCapturePermitted()) {
            if (startMicDelayMs > 0L) {
                delayedMicStartJob = scope.launch(Dispatchers.IO) {
                    delay(startMicDelayMs)
                    if (isActive && _callState.value.signalingState == CallSignalingState.IN_CALL_ACTIVE && isCapturePermitted()) {
                        startAudioCapture()
                    }
                }
            } else {
                startAudioCapture()
            }
        }
    }

    fun markCallAcceptAcked() {
        if (_callState.value.signalingState == CallSignalingState.IN_CALL_ACTIVE) {
            _callState.update { it.copy(isCallAcceptAcked = true) }
        }
    }

    fun onAppBackgrounded() {
        delayedMicStartJob?.cancel()
        delayedMicStartJob = null
        stopAudioCapture()
    }

    fun onAppForegrounded() {
        if (_callState.value.signalingState == CallSignalingState.IN_CALL_ACTIVE &&
            _callState.value.isInCall &&
            isCapturePermitted() &&
            micCaptureJob?.isActive != true
        ) {
            startAudioCapture()
        }
    }

    fun endCall(isUserBusy: Boolean = false) {
        val wasActiveOrRinging = _callState.value.signalingState != CallSignalingState.IDLE ||
            _callState.value.isInCall ||
            _callState.value.isRinging ||
            _callState.value.isRecordingVoicemail
        stopAllRinging()
        voicemailCaptureJob?.cancel()
        voicemailCaptureJob = null
        runCatching { ttsEngine?.stop() }
        delayedMicStartJob?.cancel()
        delayedMicStartJob = null

        if (wasActiveOrRinging) {
            playBriefConfirmationTone(ToneGenerator.TONE_PROP_PROMPT)
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager?.clearCommunicationDevice()
            }
            audioManager?.mode = AudioManager.MODE_NORMAL
        }

        peerSpeakingClearJob?.cancel()
        peerSpeakingClearJob = null
        audioService?.setSpeakerRouting(true)
        _callState.value = DuplexCallState()
        stopAudioCapture()
        timerJob?.cancel()
        timerJob = null
    }

    fun toggleMute() {
        _callState.update { it.copy(isMuted = !it.isMuted) }
    }

    fun setPushToOverride(active: Boolean) {
        _callState.update { cur ->
            if (active && cur.isMuted) {
                cur.copy(isMuted = false, isPushToOverrideActive = true)
            } else {
                cur.copy(isPushToOverrideActive = active)
            }
        }
    }

    fun toggleSpeakerphone() {
        val next = !_callState.value.isSpeakerphoneOn
        setSpeakerphone(next)
        _callState.update { it.copy(isSpeakerphoneOn = next) }
    }

    private fun configureAudioForSpeakerphone() {
        audioService?.setSpeakerRouting(true)
        setSpeakerphone(true)
    }

    private fun setSpeakerphone(on: Boolean) {
        audioService?.setSpeakerRouting(on)
        runCatching {
            val mgr = audioManager ?: return@runCatching
            mgr.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val targetType = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                val dev = mgr.availableCommunicationDevices.firstOrNull { it.type == targetType }
                if (dev != null) {
                    runCatching { mgr.setCommunicationDevice(dev) }
                } else if (on) {
                    @Suppress("DEPRECATION")
                    mgr.isSpeakerphoneOn = true
                }
            } else {
                @Suppress("DEPRECATION")
                mgr.isSpeakerphoneOn = on
            }
            val maxVoiceVol = mgr.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val curVoiceVol = mgr.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            if (maxVoiceVol > 0 && curVoiceVol < maxVoiceVol / 2) {
                mgr.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVoiceVol * 0.85).toInt().coerceAtLeast(1), 0)
            }
        }
    }

    private fun startOutgoingRingbackTone() {
        outgoingRingbackToneJob?.cancel()
        isOutgoingRingbackActive = true
        outgoingRingbackToneJob = scope.launch(Dispatchers.IO) {
            var toneGen: ToneGenerator? = null
            try {
                toneGen = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80) }.getOrNull()
                activeToneGenerator = toneGen
                while (isActive && _callState.value.signalingState == CallSignalingState.RINGING_OUTGOING) {
                    runCatching { toneGen?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1200) }
                    delay(2800L)
                }
            } finally {
                runCatching { toneGen?.stopTone() }
                runCatching { toneGen?.release() }
                if (activeToneGenerator === toneGen) {
                    activeToneGenerator = null
                }
                isOutgoingRingbackActive = false
            }
        }
    }

    private fun startIncomingRingtoneAndVibration() {
        isIncomingRingtoneActive = true
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 1000, 800), 1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 800, 1000, 800), 1)
            }
        }

        incomingRingtoneJob?.cancel()
        incomingRingtoneJob = scope.launch(Dispatchers.IO) {
            var ringtone: Ringtone? = null
            var fallbackToneGen: ToneGenerator? = null
            try {
                val uri = runCatching { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) }.getOrNull()
                ringtone = if (uri != null) {
                    runCatching { RingtoneManager.getRingtone(context, uri) }.getOrNull()
                } else null
                activeIncomingRingtone = ringtone

                val playedDefaultRingtone = runCatching {
                    if (ringtone != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            ringtone.isLooping = true
                        }
                        ringtone.play()
                        ringtone.isPlaying
                    } else false
                }.getOrDefault(false)

                if (!playedDefaultRingtone) {
                    fallbackToneGen = runCatching { ToneGenerator(AudioManager.STREAM_RING, 85) }.getOrNull()
                    activeToneGenerator = fallbackToneGen
                }

                while (isActive && _callState.value.signalingState == CallSignalingState.RINGING_INCOMING) {
                    if (!playedDefaultRingtone) {
                        runCatching {
                            fallbackToneGen?.startTone(ToneGenerator.TONE_CDMA_NETWORK_CALLWAITING, 1000)
                        }
                    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                        runCatching {
                            if (ringtone?.isPlaying == false) ringtone.play()
                        }
                    }
                    delay(2400L)
                }
            } finally {
                runCatching { ringtone?.stop() }
                if (activeIncomingRingtone === ringtone) {
                    activeIncomingRingtone = null
                }
                runCatching { fallbackToneGen?.stopTone() }
                runCatching { fallbackToneGen?.release() }
                if (activeToneGenerator === fallbackToneGen) {
                    activeToneGenerator = null
                }
                isIncomingRingtoneActive = false
            }
        }
    }

    private fun playBriefConfirmationTone(toneType: Int) {
        lastConfirmationToneType = toneType
        scope.launch(Dispatchers.IO) {
            var toneGen: ToneGenerator? = null
            try {
                toneGen = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70) }.getOrNull()
                runCatching { toneGen?.startTone(toneType, 120) }
                delay(140L)
            } finally {
                runCatching { toneGen?.stopTone() }
                runCatching { toneGen?.release() }
            }
        }
    }

    fun stopAllRinging() {
        isOutgoingRingbackActive = false
        isIncomingRingtoneActive = false
        outgoingRingResetJob?.cancel()
        outgoingRingResetJob = null
        outgoingRingbackToneJob?.cancel()
        outgoingRingbackToneJob = null
        incomingRingtoneJob?.cancel()
        incomingRingtoneJob = null
        runCatching { activeIncomingRingtone?.stop() }
        activeIncomingRingtone = null
        runCatching { activeToneGenerator?.stopTone() }
        runCatching { activeToneGenerator?.release() }
        activeToneGenerator = null
        runCatching { vibrator?.cancel() }
    }

    private fun startCallTimer() {
        timerJob?.cancel()
        timerJob = scope.launch(Dispatchers.Default) {
            var sec = 0
            while (isActive && _callState.value.isInCall) {
                delay(1000)
                sec++
                _callState.update { it.copy(durationSeconds = sec) }
            }
        }
    }

    /**
     * Active Voice Call Capture (`type = "VOICE"` 160ms G.711 µ-Law Engine):
     * - Always uses `AudioSource.MIC` at `8000` Hz with zero `AcousticEchoCanceler` / `NoiseSuppressor`.
     * - Captures **160ms slices** (`ShortArray(1280)` at `8000` Hz).
     * - Computes live microphone peak amplitude (`maxOf abs(sample)`) on every captured frame and updates `audioLevelRms`
     *   and `micLevelDb` so the green `Mic Input Level` bar moves in real time.
     * - Collision-free voice duplexing:
     *   * Hotspot UDP (`isHighSpeedLinkActive() == true`): full-duplex whenever `maxPeak > 300` (or `isPushToOverrideActive`).
     *   * Pure BLE GATT (`isHighSpeedLinkActive() == false`): pauses outgoing BLE `VOICE` while `now - lastIncomingAudioTime < 350L`
     *     (unless `isPushToOverrideActive`) and transmits when `maxPeak > 350` (or `isPushToOverrideActive`).
     */
    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        if (!isCapturePermitted()) return
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) return

        micCaptureJob?.cancel()
        micCaptureJob = scope.launch(Dispatchers.IO) {
            if (!isCapturePermitted()) return@launch
            runCatching {
                runCatching {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                }

                if (!isCapturePermitted()) return@runCatching
                val (rec, activeRate) = TacticalAudioService.createSafeAudioRecord()

                if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                    synchronized(audioRecordLock) {
                        audioRecord = rec
                    }
                    val audioEffects = TacticalAudioService.attachManagedAudioEffects(rec.audioSessionId)
                    try {
                        rec.startRecording()
                        var vadHangoverFramesRemaining = 0
                        var wasTransmittingPrevFrame = false
                        var lookback8kBuf: ShortArray? = null

                        while (isActive && _callState.value.isInCall && isCapturePermitted()) {
                            val highSpeedNow = isHighSpeedLinkActive()
                            // Low-latency 8kHz IMA-ADPCM framing: 80ms (640 samples -> 324B) on Wi-Fi UDP vs 120ms (960 samples -> 484B) on BLE
                            val target8kSamples = if (highSpeedNow) {
                                TacticalAudioService.VOICE_SLICE_SAMPLES_80MS
                            } else {
                                TacticalAudioService.VOICE_SLICE_SAMPLES_120MS
                            }
                            val captureFrameSamples = if (activeRate == 16000) target8kSamples * 2 else target8kSamples
                            val rawCaptureBuf = ShortArray(captureFrameSamples)

                            var totalRead = 0
                            var readErrorCount = 0
                            while (totalRead < captureFrameSamples && isActive && _callState.value.isInCall && isCapturePermitted()) {
                                val read = rec.read(rawCaptureBuf, totalRead, captureFrameSamples - totalRead)
                                if (read > 0) {
                                    totalRead += read
                                    readErrorCount = 0
                                } else {
                                    readErrorCount++
                                    break
                                }
                            }
                            if (readErrorCount > 0 && totalRead == 0) {
                                delay(25L)
                                continue
                            }

                            if (!isCapturePermitted()) break

                            if (totalRead >= captureFrameSamples) {
                                val shortBuf = if (activeRate == 16000) {
                                    TacticalAudioService.downsample16kHzTo8kHz(rawCaptureBuf, totalRead)
                                } else {
                                    rawCaptureBuf
                                }
                                val validSamples = minOf(target8kSamples, shortBuf.size)
                                if (validSamples <= 0) continue
                                TacticalAudioService.removeDcAndLowRumbleInPlace(shortBuf, validSamples)

                                var maxPeak = 0
                                var sumSq = 0.0
                                for (i in 0 until validSamples) {
                                    val sample = shortBuf[i].toInt()
                                    val absSample = kotlin.math.abs(sample)
                                    if (absSample > maxPeak) maxPeak = absSample
                                    sumSq += sample.toDouble() * sample.toDouble()
                                }
                                val rms = sqrt(sumSq / validSamples.coerceAtLeast(1))
                                val rawNorm = (maxPeak / 16384.0).coerceIn(0.0, 1.0).toFloat()
                                val peakNorm = if (rawNorm.isFinite()) rawNorm else 0f
                                val db = if (rms > 1.0 && rms.isFinite()) (20.0 * kotlin.math.log10(rms / 32768.0)).toInt().coerceIn(-60, 0) else -60

                            val curState = _callState.value
                            val highSpeed = isHighSpeedLinkActive()
                            val nowMs = System.currentTimeMillis()
                            val rawShouldTx = shouldTransmitCallVoiceFrame(
                                hasHotspotUdpLink = highSpeed,
                                micPeak = maxPeak,
                                nowMs = nowMs,
                                lastIncomingAudioTime = lastIncomingAudioTime,
                                pushToOverrideActive = curState.isPushToOverrideActive,
                                isMuted = curState.isMuted,
                                isSpeakerphoneOn = curState.isSpeakerphoneOn
                            )

                            val isOtherPersonSpeaking = !highSpeed && ((nowMs - lastIncomingAudioTime) < BLE_HALF_DUPLEX_DUCK_WINDOW_MS)
                            val isSpeakerEchoGateActive = highSpeed &&
                                curState.isSpeakerphoneOn &&
                                !curState.isPushToOverrideActive &&
                                ((nowMs - lastIncomingAudioTime) < SPEAKER_ECHO_DUCK_WINDOW_MS) &&
                                maxPeak <= CALL_VAD_SPEAKER_ECHO_PEAK_THRESHOLD
                            val shouldTx = when {
                                curState.isMuted -> {
                                    vadHangoverFramesRemaining = 0
                                    false
                                }
                                rawShouldTx -> {
                                    // 3 frames (240ms) hangover on UDP, 2 frames (240ms) on BLE so syllables never clip
                                    vadHangoverFramesRemaining = if (highSpeed) 3 else 2
                                    true
                                }
                                vadHangoverFramesRemaining > 0 && !isOtherPersonSpeaking && !isSpeakerEchoGateActive -> {
                                    vadHangoverFramesRemaining--
                                    true
                                }
                                else -> {
                                    vadHangoverFramesRemaining = 0
                                    false
                                }
                            }

                            if (shouldTx) {
                                // Speech onset lookback on high-speed links so initial consonant plosives are never clipped
                                if (!wasTransmittingPrevFrame && highSpeed && !isSpeakerEchoGateActive) {
                                    lookback8kBuf?.let { preRoll ->
                                        val preEncoded = TacticalAudioService.encodeImaAdpcm8kHz(preRoll, preRoll.size)
                                        voiceTxChannel.trySend(preEncoded)
                                    }
                                }
                                wasTransmittingPrevFrame = true
                                lookback8kBuf = null

                                val encodedVoiceBytes = TacticalAudioService.encodeImaAdpcm8kHz(shortBuf, validSamples)
                                voiceTxChannel.trySend(encodedVoiceBytes)

                                _callState.update { cur ->
                                    cur.copy(
                                        audioLevelRms = peakNorm.coerceAtLeast(0.08f),
                                        micLevelDb = db,
                                        voiceFramesSent = cur.voiceFramesSent + 1,
                                        isVoiceTransmitting = true,
                                        isHighSpeedLink = highSpeed,
                                        transportType = if (highSpeed) {
                                            "IMA-ADPCM 8kHz HD 80ms (324B / Binary UDP 45888)"
                                        } else {
                                            "IMA-ADPCM 8kHz HD 120ms (484B / BLE L2CAP+0xCAFF)"
                                        }
                                    )
                                }
                            } else {
                                wasTransmittingPrevFrame = false
                                lookback8kBuf = shortBuf.copyOf(validSamples)
                                _callState.update {
                                    it.copy(
                                        audioLevelRms = if (it.isMuted) 0f else peakNorm,
                                        micLevelDb = if (it.isMuted) -60 else db,
                                        isVoiceTransmitting = false,
                                        isHighSpeedLink = highSpeed
                                    )
                                }
                            }
                        } else {
                            delay(5)
                        }
                    }
                    } finally {
                        audioEffects.release()
                        releaseAudioRecordInstance(rec)
                    }
                }
            }
        }
    }

    @Synchronized
    private fun initPersistentAudioTrack() {
        if (audioService != null) return
        runCatching {
            configureAudioForSpeakerphone()
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding).coerceAtLeast(1280)
            val bufferSize = maxOf(minBuf, 2560)

            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(encoding)
                        .setChannelMask(channelOut)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val track = builder.build()
            audioTrack = track

            if (track.state == AudioTrack.STATE_INITIALIZED) {
                runCatching { track.setVolume(1.0f) }
                track.play()
            }

            playJob?.cancel()
            playJob = scope.launch(Dispatchers.IO) {
                runCatching {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                }
                for (pcm8k in playbackChannel) {
                    if (!isActive) break
                    writeCallShortsToTrack(pcm8k)
                }
            }
        }
    }

    private fun ensurePersistentAudioTrackPlaying() {
        if (audioService != null) return
        runCatching {
            val trk = audioTrack
            if (trk == null || trk.state != AudioTrack.STATE_INITIALIZED) {
                initPersistentAudioTrack()
            } else if (trk.playState != AudioTrack.PLAYSTATE_PLAYING) {
                trk.play()
            }
        }
    }

    private fun writeCallShortsToTrack(pcm16: ShortArray) {
        if (pcm16.isEmpty()) return
        synchronized(audioTrackLock) {
            runCatching {
                val trk = audioTrack
                if (trk != null && trk.state == AudioTrack.STATE_INITIALIZED) {
                    if (trk.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        trk.play()
                    }
                    val written = trk.write(pcm16, 0, pcm16.size, AudioTrack.WRITE_BLOCKING)
                    if (written > 0) {
                        _callState.update { it.copy(voiceFramesPlayed = it.voiceFramesPlayed + 1) }
                    }
                }
            }
        }
    }

    private fun markPeerSpeaking(nowMs: Long = System.currentTimeMillis(), incrementPlayed: Boolean = true) {
        lastIncomingAudioTime = nowMs
        val highSpeed = isHighSpeedLinkActive()
        _callState.update {
            it.copy(
                isPeerSpeaking = true,
                isHighSpeedLink = highSpeed,
                voiceFramesPlayed = if (incrementPlayed) it.voiceFramesPlayed + 1 else it.voiceFramesPlayed
            )
        }
        peerSpeakingClearJob?.cancel()
        peerSpeakingClearJob = scope.launch(Dispatchers.Default) {
            delay(BLE_HALF_DUPLEX_DUCK_WINDOW_MS)
            _callState.update { it.copy(isPeerSpeaking = false) }
        }
    }

    fun recordIncomingFramePlayed(nowMs: Long = System.currentTimeMillis()) {
        markPeerSpeaking(nowMs = nowMs, incrementPlayed = true)
    }

    fun playIncomingMuLawFrame(muLawBytes: ByteArray) {
        if (muLawBytes.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        if (audioService != null) {
            audioService.playIncomingMuLawBytes(muLawBytes, isCallAudio = true)
            markPeerSpeaking(nowMs = nowMs, incrementPlayed = true)
        } else {
            if (!_callState.value.isInCall) return
            markPeerSpeaking(nowMs = nowMs, incrementPlayed = false)
            val pcm8k = if (muLawBytes.size == TacticalAudioService.VOICE_SLICE_SAMPLES_160MS || muLawBytes.size == 800) {
                TacticalAudioService.decodeMuLaw8kHzDirect(muLawBytes)
            } else {
                TacticalAudioService.decodeMuLaw4kHzTo8kHzInterpolated(muLawBytes)
            }
            playbackChannel.trySend(pcm8k)
        }
    }

    fun playIncomingAudioFrame(rawBytes: ByteArray) {
        if (rawBytes.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        if (audioService != null) {
            audioService.playIncomingPcmBytes(rawBytes, isCallAudio = true)
            markPeerSpeaking(nowMs = nowMs, incrementPlayed = true)
        } else {
            if (!_callState.value.isInCall) return
            markPeerSpeaking(nowMs = nowMs, incrementPlayed = false)
            val pcm16 = TacticalAudioService.decodeStateless8BitPcmTo8kHz16Bit(rawBytes)
            playbackChannel.trySend(pcm16)
        }
    }

    private fun stopAudioCapture() {
        micCaptureJob?.cancel()
        micCaptureJob = null
        synchronized(audioRecordLock) {
            runCatching {
                if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord?.stop()
                }
            }
        }
    }

    private fun releaseAudioRecordInstance(rec: AudioRecord?) {
        if (rec == null) return
        synchronized(audioRecordLock) {
            runCatching {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
            }
            runCatching { rec.release() }
            if (audioRecord === rec) {
                audioRecord = null
            }
        }
    }

    private fun cleanUpRecord() {
        val currentRec: AudioRecord?
        synchronized(audioRecordLock) {
            currentRec = audioRecord
            audioRecord = null
        }
        if (currentRec != null) {
            runCatching {
                if (currentRec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    currentRec.stop()
                }
            }
            runCatching { currentRec.release() }
        }
    }

    private fun stopAudioPlayback() {
        playJob?.cancel()
        playJob = null
        synchronized(audioTrackLock) {
            runCatching {
                if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                    if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.stop()
                    }
                    audioTrack?.release()
                }
            }
            audioTrack = null
        }
    }

    fun release() {
        stopAllRinging()
        voicemailCaptureJob?.cancel()
        delayedMicStartJob?.cancel()
        stopAudioCapture()
        stopAudioPlayback()
        networkSenderJob?.cancel()
        runCatching {
            ttsEngine?.stop()
            ttsEngine?.shutdown()
        }
        ttsEngine = null
    }
}
