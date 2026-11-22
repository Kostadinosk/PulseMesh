// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.voice

import com.kostadinosk.pulsemesh.model.ConferencePeerStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Multi-peer Audio Conference Mixer (up to [MAX_CONFERENCE_PEERS] = 6 simultaneous peers).
 * Maintains a dedicated [AdaptiveJitterBuffer], mute/kick flags, volume gain, and real-time
 * RMS speaking indicator per peer, mixing decoded PCM frames with smooth `tanh` soft-clipping.
 */
class ConferenceAudioMixer {

    companion object {
        const val MAX_CONFERENCE_PEERS = 6
    }

    private data class PeerChannel(
        val nodeId: String,
        var callsign: String,
        val jitterBuffer: AdaptiveJitterBuffer = AdaptiveJitterBuffer(),
        var isMuted: Boolean = false,
        var isKicked: Boolean = false,
        var volumeGain: Float = 1.0f,
        var vadLevel: Float = 0f,
        var lastActiveAtMs: Long = 0L
    )

    private val peerChannels = ConcurrentHashMap<String, PeerChannel>()

    private val _peerStatuses = MutableStateFlow<List<ConferencePeerStatus>>(emptyList())
    val peerStatuses: StateFlow<List<ConferencePeerStatus>> = _peerStatuses.asStateFlow()

    @Synchronized
    fun addOrUpdatePeer(nodeId: String, callsign: String): Boolean {
        val existing = peerChannels[nodeId]
        if (existing != null) {
            if (existing.isKicked) return false
            if (callsign.isNotBlank()) existing.callsign = callsign
            publishSnapshot()
            return true
        }
        val activeNonKicked = peerChannels.values.count { !it.isKicked }
        if (activeNonKicked >= MAX_CONFERENCE_PEERS) {
            return false
        }
        peerChannels[nodeId] = PeerChannel(
            nodeId = nodeId,
            callsign = callsign.ifBlank { nodeId },
            lastActiveAtMs = System.currentTimeMillis()
        )
        publishSnapshot()
        return true
    }

    @Synchronized
    fun pushPeerPcmFrame(
        nodeId: String,
        callsign: String,
        seq: Int,
        pcmSamples: ShortArray,
        arrivalMs: Long = System.currentTimeMillis()
    ): Boolean {
        val ch = peerChannels[nodeId] ?: run {
            if (!addOrUpdatePeer(nodeId, callsign)) return false
            peerChannels[nodeId]
        } ?: return false
        if (ch.isKicked || ch.isMuted) return false

        var sumSq = 0.0
        for (s in pcmSamples) {
            val v = s.toDouble()
            sumSq += v * v
        }
        val rms = if (pcmSamples.isNotEmpty()) sqrt(sumSq / pcmSamples.size) else 0.0
        ch.vadLevel = (rms / 8000.0).coerceIn(0.0, 1.0).toFloat()
        ch.lastActiveAtMs = arrivalMs
        ch.jitterBuffer.pushFrame(seq, pcmSamples, arrivalMs)
        publishSnapshot(arrivalMs)
        return true
    }

    @Synchronized
    fun mixPlaybackFrame(expectedSamples: Int = 160, nowMs: Long = System.currentTimeMillis()): ShortArray? {
        if (peerChannels.isEmpty()) return null
        val accum = DoubleArray(expectedSamples)
        var mixedCount = 0

        for (ch in peerChannels.values) {
            if (ch.isKicked || ch.isMuted) continue
            val frame = ch.jitterBuffer.popNextFrame(expectedSamples) ?: continue
            mixedCount++
            val gain = ch.volumeGain.coerceIn(0f, 2.0f).toDouble()
            val len = minOf(expectedSamples, frame.size)
            for (i in 0 until len) {
                accum[i] += frame[i].toDouble() * gain
            }
        }

        if (mixedCount == 0) return null

        val out = ShortArray(expectedSamples)
        for (i in 0 until expectedSamples) {
            val v = accum[i]
            // Smooth tanh soft-limiter prevents digital wrap/clipping when 2-6 peers talk at once
            val limited = if (abs(v) > 24000.0) {
                32760.0 * tanh(v / 32760.0)
            } else {
                v
            }
            out[i] = limited.toInt().coerceIn(-32767, 32767).toShort()
        }
        publishSnapshot(nowMs)
        return out
    }

    @Synchronized
    fun setPeerMuted(nodeId: String, muted: Boolean) {
        peerChannels[nodeId]?.let {
            it.isMuted = muted
            if (muted) it.vadLevel = 0f
            publishSnapshot()
        }
    }

    @Synchronized
    fun setPeerGain(nodeId: String, gain: Float) {
        peerChannels[nodeId]?.let {
            it.volumeGain = gain.coerceIn(0f, 2.0f)
            publishSnapshot()
        }
    }

    @Synchronized
    fun kickPeer(nodeId: String) {
        peerChannels[nodeId]?.let {
            it.isKicked = true
            it.isMuted = true
            it.vadLevel = 0f
            it.jitterBuffer.reset()
        }
        publishSnapshot()
    }

    @Synchronized
    fun clear() {
        peerChannels.values.forEach { it.jitterBuffer.reset() }
        peerChannels.clear()
        _peerStatuses.value = emptyList()
    }

    private fun publishSnapshot(nowMs: Long = System.currentTimeMillis()) {
        _peerStatuses.value = peerChannels.values
            .filter { !it.isKicked }
            .sortedBy { it.callsign.lowercase() }
            .map { ch ->
                val activeSpeaking = !ch.isMuted && ch.vadLevel >= 0.04f && (nowMs - ch.lastActiveAtMs) <= 1500L
                ConferencePeerStatus(
                    nodeId = ch.nodeId,
                    callsign = ch.callsign,
                    isMuted = ch.isMuted,
                    volumeGain = ch.volumeGain,
                    vadLevel = if (activeSpeaking) ch.vadLevel else 0f,
                    isSpeaking = activeSpeaking
                )
            }
    }
}
