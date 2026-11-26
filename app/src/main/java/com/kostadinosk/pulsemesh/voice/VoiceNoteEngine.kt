// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import androidx.core.content.ContextCompat
import com.kostadinosk.pulsemesh.service.TacticalAudioService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

data class VoiceNoteRecordingState(
    val isRecording: Boolean = false,
    val isLocked: Boolean = false,
    val elapsedMs: Long = 0L,
    val liveAmplitude: Float = 0f,
    val waveformBars: List<Int> = emptyList()
)

data class VoiceNotePlaybackState(
    val activePacketId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f
)

data class RecordedVoiceNote(
    val payload: String,
    val durationMs: Long,
    val codec: String,
    val waveformBars: List<Int>
)

data class ParsedVoiceNote(
    val codec: String,
    val durationMs: Long,
    val waveformBars: List<Int>,
    val audioBytes: ByteArray
)

/**
 * WhatsApp-grade Voice Note recording and playback engine:
 * - Records real microphone audio using compact AAC/M4A (24 kbps / 16 kHz, native Android + iPhone/Web)
 *   when on high-speed links, with automatic fallback to ultra-compact 8 kHz IMA-ADPCM for BLE off-grid mesh.
 * - Captures real 24-bar amplitude waveforms, supports Hold-to-Record + Lock + Cancel, and plays back
 *   with live progress scrubbing, Pause/Resume, 1x/1.5x/2x speed, and explicit AudioDeviceInfo routing.
 */
class VoiceNoteEngine(
    context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        const val PREFIX = "[[PM_VOICENOTE]]:"
        const val MAX_DURATION_MS = 120_000L

        fun isVoiceNotePayload(content: String): Boolean {
            val t = content.trim()
            return t.startsWith(PREFIX) ||
                t.startsWith("[[PM_VOICEDROP]]:") ||
                t.startsWith("[[PM_AUDIO]]:")
        }

        fun buildPayload(
            codec: String,
            durationMs: Long,
            waveformBars: List<Int>,
            audioBytes: ByteArray
        ): String {
            val safeBars = normalizeBarsTo24(waveformBars)
            val waveCsv = safeBars.joinToString(",")
            val b64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
            return "$PREFIX$codec:${durationMs.coerceAtLeast(300L)}:$waveCsv:$b64"
        }

        fun parsePayload(content: String): ParsedVoiceNote? {
            val trimmed = content.trim()
            if (trimmed.startsWith(PREFIX)) {
                val body = trimmed.removePrefix(PREFIX)
                val parts = body.split(":", limit = 4)
                if (parts.size == 4) {
                    val codec = parts[0].uppercase()
                    val dur = parts[1].toLongOrNull()?.coerceAtLeast(300L) ?: 1_000L
                    val bars = parts[2].split(",").mapNotNull { it.toIntOrNull()?.coerceIn(8, 100) }
                    val bytes = runCatching { Base64.decode(parts[3], Base64.DEFAULT) }.getOrNull() ?: return null
                    if (bytes.isEmpty()) return null
                    return ParsedVoiceNote(codec, dur, normalizeBarsTo24(bars), bytes)
                }
            }
            if (trimmed.startsWith("[[PM_VOICEDROP]]:") || trimmed.startsWith("[[PM_AUDIO]]:")) {
                val stripped = trimmed.removePrefix("[[PM_VOICEDROP]]:").removePrefix("[[PM_AUDIO]]:")
                val codec = when {
                    stripped.startsWith("ADPCM8K:") -> "ADPCM8K"
                    stripped.startsWith("ULAW8K:") -> "ULAW8K"
                    else -> "ULAW4K"
                }
                val rawB64 = stripped.removePrefix("ADPCM8K:").removePrefix("ULAW8K:").removePrefix("ULAW:")
                val bytes = runCatching { Base64.decode(rawB64, Base64.DEFAULT) }.getOrNull() ?: return null
                if (bytes.isEmpty()) return null
                val estDurationMs = when (codec) {
                    "ADPCM8K" -> ((bytes.size.toLong() * 2L * 1000L) / 8000L).coerceAtLeast(500L)
                    "ULAW8K" -> ((bytes.size.toLong() * 1000L) / 8000L).coerceAtLeast(500L)
                    else -> ((bytes.size.toLong() * 1000L) / 4000L).coerceAtLeast(500L)
                }
                return ParsedVoiceNote(codec, estDurationMs, computeWaveformFromBytes(bytes), bytes)
            }
            return null
        }

        fun normalizeBarsTo24(input: List<Int>): List<Int> {
            if (input.isEmpty()) return List(24) { idx -> 24 + ((idx * 13) % 45) }
            if (input.size == 24) return input.map { it.coerceIn(10, 100) }
            return List(24) { i ->
                val srcIdx = ((i.toFloat() / 24f) * input.size).toInt().coerceIn(0, input.lastIndex)
                input[srcIdx].coerceIn(10, 100)
            }
        }

        fun computeWaveformFromBytes(bytes: ByteArray): List<Int> {
            if (bytes.isEmpty()) return List(24) { 25 }
            val step = (bytes.size / 24).coerceAtLeast(1)
            return List(24) { bar ->
                val start = (bar * step).coerceAtMost(bytes.lastIndex)
                val end = (start + step).coerceAtMost(bytes.size)
                var sum = 0L
                for (i in start until end) {
                    sum += abs(bytes[i].toInt())
                }
                val avg = sum.toFloat() / (end - start).coerceAtLeast(1)
                ((avg / 128f) * 100f).toInt().coerceIn(14, 100)
            }
        }
    }

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.cacheDir, "pulsemesh_voice_notes").apply { mkdirs() }

    private val _recordingState = MutableStateFlow(VoiceNoteRecordingState())
    val recordingState: StateFlow<VoiceNoteRecordingState> = _recordingState.asStateFlow()

    private val _playbackState = MutableStateFlow(VoiceNotePlaybackState())
    val playbackState: StateFlow<VoiceNotePlaybackState> = _playbackState.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartMs: Long = 0L
    private var recordingUseM4a: Boolean = true
    private var recordingJob: Job? = null
    private val adpcmBuffer = ByteArrayOutputStream()
    private val amplitudeSamples = mutableListOf<Int>()

    private var mediaPlayer: MediaPlayer? = null
    private var pcmTrack: AudioTrack? = null
    private var playbackJob: Job? = null

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startRecording(preferM4a: Boolean = true): Boolean {
        if (_recordingState.value.isRecording) return true
        stopPlayback()
        recordingStartMs = System.currentTimeMillis()
        amplitudeSamples.clear()
        synchronized(adpcmBuffer) { adpcmBuffer.reset() }
        recordingUseM4a = preferM4a

        val startedM4a = if (preferM4a && hasMicPermission()) {
            tryStartMediaRecorderM4a()
        } else {
            false
        }

        recordingUseM4a = startedM4a
        _recordingState.value = VoiceNoteRecordingState(
            isRecording = true,
            isLocked = false,
            elapsedMs = 0L,
            liveAmplitude = 0.1f,
            waveformBars = emptyList()
        )

        recordingJob?.cancel()
        if (startedM4a) {
            recordingJob = scope.launch(Dispatchers.IO) {
                while (isActive && _recordingState.value.isRecording) {
                    val elapsed = System.currentTimeMillis() - recordingStartMs
                    val rawAmp = runCatching { mediaRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    val norm = (rawAmp.toFloat() / 24000f).coerceIn(0.08f, 1.0f)
                    val bar = (norm * 100f).toInt().coerceIn(10, 100)
                    synchronized(amplitudeSamples) {
                        amplitudeSamples.add(bar)
                    }
                    _recordingState.value = _recordingState.value.copy(
                        elapsedMs = elapsed,
                        liveAmplitude = norm,
                        waveformBars = synchronized(amplitudeSamples) { amplitudeSamples.takeLast(24) }
                    )
                    if (elapsed >= MAX_DURATION_MS) break
                    delay(90L)
                }
            }
        } else {
            recordingJob = scope.launch(Dispatchers.IO) {
                if (hasMicPermission()) {
                    val (rec, activeRate) = TacticalAudioService.createSafeAudioRecord()
                    if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                        try {
                            rec.startRecording()
                            val target8k = TacticalAudioService.VOICE_SLICE_SAMPLES_120MS
                            val captureLen = if (activeRate == 16000) target8k * 2 else target8k
                            val buf = ShortArray(captureLen)
                            while (isActive && _recordingState.value.isRecording) {
                                var readTotal = 0
                                while (readTotal < captureLen && isActive && _recordingState.value.isRecording) {
                                    val r = rec.read(buf, readTotal, captureLen - readTotal)
                                    if (r > 0) readTotal += r else break
                                }
                                if (readTotal > 0) {
                                    val slice8k = if (activeRate == 16000) {
                                        TacticalAudioService.downsample16kHzTo8kHz(buf, readTotal)
                                    } else {
                                        buf.copyOf(readTotal)
                                    }
                                    var peak = 0
                                    for (s in slice8k) {
                                        val a = abs(s.toInt())
                                        if (a > peak) peak = a
                                    }
                                    val norm = (peak.toFloat() / 16000f).coerceIn(0.08f, 1.0f)
                                    val bar = (norm * 100f).toInt().coerceIn(10, 100)
                                    synchronized(amplitudeSamples) { amplitudeSamples.add(bar) }
                                    val encoded = TacticalAudioService.encodeImaAdpcm8kHz(slice8k)
                                    synchronized(adpcmBuffer) { adpcmBuffer.write(encoded) }
                                    val elapsed = System.currentTimeMillis() - recordingStartMs
                                    _recordingState.value = _recordingState.value.copy(
                                        elapsedMs = elapsed,
                                        liveAmplitude = norm,
                                        waveformBars = synchronized(amplitudeSamples) { amplitudeSamples.takeLast(24) }
                                    )
                                    if (elapsed >= MAX_DURATION_MS) break
                                }
                            }
                        } finally {
                            runCatching { rec.stop() }
                            runCatching { rec.release() }
                        }
                    }
                } else {
                    while (isActive && _recordingState.value.isRecording) {
                        val elapsed = System.currentTimeMillis() - recordingStartMs
                        _recordingState.value = _recordingState.value.copy(elapsedMs = elapsed)
                        delay(100L)
                    }
                }
            }
        }
        return true
    }

    fun lockRecording() {
        if (_recordingState.value.isRecording) {
            _recordingState.value = _recordingState.value.copy(isLocked = true)
        }
    }

    private fun tryStartMediaRecorderM4a(): Boolean {
        return runCatching {
            val outFile = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(appContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(16_000)
            mr.setAudioEncodingBitRate(24_000)
            mr.setAudioChannels(1)
            mr.setOutputFile(outFile.absolutePath)
            mr.prepare()
            mr.start()
            mediaRecorder = mr
            recordingFile = outFile
            true
        }.getOrElse {
            cleanupMediaRecorder(deleteFile = true)
            false
        }
    }

    private fun cleanupMediaRecorder(deleteFile: Boolean) {
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        if (deleteFile) {
            runCatching { recordingFile?.delete() }
            recordingFile = null
        }
    }

    fun cancelRecording() {
        _recordingState.value = VoiceNoteRecordingState()
        recordingJob?.cancel()
        recordingJob = null
        cleanupMediaRecorder(deleteFile = true)
        synchronized(adpcmBuffer) { adpcmBuffer.reset() }
        synchronized(amplitudeSamples) { amplitudeSamples.clear() }
    }

    suspend fun stopAndFinishRecording(): RecordedVoiceNote? = withContext(Dispatchers.IO) {
        if (!_recordingState.value.isRecording) return@withContext null
        val elapsedMs = (System.currentTimeMillis() - recordingStartMs).coerceAtLeast(400L)
        _recordingState.value = VoiceNoteRecordingState()
        recordingJob?.cancel()
        recordingJob = null

        val barsSnapshot = synchronized(amplitudeSamples) { normalizeBarsTo24(amplitudeSamples.toList()) }
        if (recordingUseM4a) {
            val targetFile = recordingFile
            cleanupMediaRecorder(deleteFile = false)
            recordingFile = null
            val bytes = if (targetFile != null && targetFile.exists() && targetFile.length() > 64L) {
                val b = targetFile.readBytes()
                runCatching { targetFile.delete() }
                b
            } else {
                runCatching { targetFile?.delete() }
                ByteArray(0)
            }
            if (bytes.isNotEmpty()) {
                val payload = buildPayload("M4A", elapsedMs, barsSnapshot, bytes)
                return@withContext RecordedVoiceNote(payload, elapsedMs, "M4A", barsSnapshot)
            }
        }

        val adpcmBytes = synchronized(adpcmBuffer) { adpcmBuffer.toByteArray() }
        if (adpcmBytes.isNotEmpty()) {
            val payload = buildPayload("ADPCM8K", elapsedMs, barsSnapshot, adpcmBytes)
            return@withContext RecordedVoiceNote(payload, elapsedMs, "ADPCM8K", barsSnapshot)
        }
        null
    }

    fun togglePlayOrPause(
        packetId: String,
        content: String,
        speed: Float = _playbackState.value.speed,
        preferredOutputDevice: AudioDeviceInfo? = null
    ) {
        val cur = _playbackState.value
        if (cur.activePacketId == packetId && cur.isPlaying) {
            // Pause active playback
            if (mediaPlayer != null) {
                runCatching { mediaPlayer?.pause() }
                _playbackState.value = cur.copy(isPlaying = false)
                return
            } else {
                stopPlayback()
                return
            }
        }
        if (cur.activePacketId == packetId && !cur.isPlaying && mediaPlayer != null) {
            runCatching {
                applyMediaPlayerSpeed(mediaPlayer!!, speed)
                mediaPlayer?.start()
            }
            _playbackState.value = cur.copy(isPlaying = true, speed = speed)
            return
        }

        stopPlayback()
        val parsed = parsePayload(content) ?: return
        if (parsed.codec == "M4A") {
            playM4a(packetId, parsed, speed, preferredOutputDevice)
        } else {
            playPcmDecoded(packetId, parsed, speed, preferredOutputDevice)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.75f, 2.5f)
        _playbackState.value = _playbackState.value.copy(speed = clamped)
        mediaPlayer?.let { mp ->
            runCatching {
                if (mp.isPlaying) applyMediaPlayerSpeed(mp, clamped)
            }
        }
    }

    fun seekTo(packetId: String, fraction: Float) {
        val cur = _playbackState.value
        if (cur.activePacketId != packetId || cur.durationMs <= 0L) return
        val targetMs = (cur.durationMs * fraction.coerceIn(0f, 1f)).toLong()
        mediaPlayer?.let { mp ->
            runCatching { mp.seekTo(targetMs.toInt()) }
            _playbackState.value = cur.copy(positionMs = targetMs)
        }
    }

    private fun applyMediaPlayerSpeed(mp: MediaPlayer, speed: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val params = mp.playbackParams
                params.speed = speed.coerceIn(0.75f, 2.5f)
                mp.playbackParams = params
            }
        }
    }

    private fun playM4a(
        packetId: String,
        parsed: ParsedVoiceNote,
        speed: Float,
        preferredOutputDevice: AudioDeviceInfo?
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val file = File(cacheDir, "play_${packetId.hashCode()}.m4a")
                file.writeBytes(parsed.audioBytes)
                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(file.absolutePath)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && preferredOutputDevice != null) {
                        runCatching { setPreferredDevice(preferredOutputDevice) }
                    }
                    prepare()
                    setOnCompletionListener {
                        _playbackState.value = VoiceNotePlaybackState(
                            activePacketId = null,
                            isPlaying = false,
                            positionMs = 0L,
                            durationMs = 0L,
                            speed = _playbackState.value.speed
                        )
                    }
                }
                mediaPlayer = mp
                val dur = mp.duration.toLong().takeIf { it > 0L } ?: parsed.durationMs
                applyMediaPlayerSpeed(mp, speed)
                mp.start()
                _playbackState.value = VoiceNotePlaybackState(
                    activePacketId = packetId,
                    isPlaying = true,
                    positionMs = 0L,
                    durationMs = dur,
                    speed = speed
                )
                playbackJob?.cancel()
                playbackJob = scope.launch(Dispatchers.IO) {
                    while (isActive && mediaPlayer === mp) {
                        val playing = runCatching { mp.isPlaying }.getOrDefault(false)
                        val pos = runCatching { mp.currentPosition.toLong() }.getOrDefault(0L)
                        if (playing) {
                            _playbackState.value = _playbackState.value.copy(
                                isPlaying = true,
                                positionMs = pos.coerceIn(0L, dur),
                                durationMs = dur
                            )
                        }
                        delay(80L)
                    }
                }
            }.onFailure {
                stopPlayback()
            }
        }
    }

    private fun playPcmDecoded(
        packetId: String,
        parsed: ParsedVoiceNote,
        speed: Float,
        preferredOutputDevice: AudioDeviceInfo?
    ) {
        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.IO) {
            try {
                val pcm8k = when (parsed.codec) {
                    "ADPCM8K" -> TacticalAudioService.decodeImaAdpcm8kHz(parsed.audioBytes)
                    "ULAW8K" -> TacticalAudioService.decodeMuLaw8kHzDirect(parsed.audioBytes)
                    else -> TacticalAudioService.decodeMuLaw4kHzTo8kHzInterpolated(parsed.audioBytes)
                }
                if (pcm8k.isEmpty()) return@launch
                val effectiveRate = (8000f * speed.coerceIn(0.75f, 2.5f)).toInt().coerceIn(6000, 18000)
                val minBuf = AudioTrack.getMinBufferSize(
                    effectiveRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(2048)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(effectiveRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf * 2, 4096))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                if (preferredOutputDevice != null) {
                    runCatching { track.setPreferredDevice(preferredOutputDevice) }
                }
                pcmTrack = track
                val totalDurMs = ((pcm8k.size.toLong() * 1000L) / effectiveRate).coerceAtLeast(parsed.durationMs)
                _playbackState.value = VoiceNotePlaybackState(
                    activePacketId = packetId,
                    isPlaying = true,
                    positionMs = 0L,
                    durationMs = totalDurMs,
                    speed = speed
                )
                track.play()
                val chunkSize = 800
                var offset = 0
                while (isActive && offset < pcm8k.size && pcmTrack === track) {
                    val len = minOf(chunkSize, pcm8k.size - offset)
                    track.write(pcm8k, offset, len, AudioTrack.WRITE_BLOCKING)
                    offset += len
                    val posMs = ((offset.toLong() * 1000L) / effectiveRate).coerceIn(0L, totalDurMs)
                    _playbackState.value = _playbackState.value.copy(positionMs = posMs)
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { pcmTrack?.stop() }
                runCatching { pcmTrack?.release() }
                pcmTrack = null
                _playbackState.value = VoiceNotePlaybackState(speed = _playbackState.value.speed)
            }
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        runCatching { pcmTrack?.stop() }
        runCatching { pcmTrack?.release() }
        pcmTrack = null
        _playbackState.value = VoiceNotePlaybackState(speed = _playbackState.value.speed)
    }

    fun release() {
        cancelRecording()
        stopPlayback()
    }
}
