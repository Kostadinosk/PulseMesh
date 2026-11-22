// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.voice

import java.util.TreeMap
import kotlin.math.abs

/**
 * 20ms Adaptive Jitter Buffer with RFC 3550 inter-arrival jitter estimator and single-frame
 * Packet Loss Concealment (PLC).
 *
 * - Default frame size: 160 samples @ 8 kHz (20 ms) or 320 samples @ 16 kHz.
 * - Adapts playout target depth smoothly between 40 ms (2 frames) and 200 ms (10 frames).
 * - Uses deterministic exponential decay fade (`gain = 0.70f`) for up to 2 consecutive missing slots
 *   to mask transient packet drops without introducing harsh clicks.
 */
class AdaptiveJitterBuffer(
    private val frameDurationMs: Int = 20,
    private val minDepthMs: Int = 40,
    private val maxDepthMs: Int = 200,
    private val maxCapacityFrames: Int = 32
) {
    private val frameSlots = TreeMap<Int, ShortArray>()
    private var nextPlayoutSeq: Int? = null
    private var lastArrivalTimeMs: Long = 0L
    private var lastFrameTimestampMs: Long = 0L
    private var estimatedJitterMs: Float = 0f
    private var lastDeliveredFrame: ShortArray? = null
    private var consecutiveConcealed: Int = 0
    private var receivedCount: Long = 0L
    private var expectedCount: Long = 0L
    private var highestSeqSeen: Int = -1

    @Synchronized
    fun pushFrame(seq: Int, pcmSamples: ShortArray, arrivalMs: Long = System.currentTimeMillis()) {
        if (seq < 0 || pcmSamples.isEmpty()) return

        if (nextPlayoutSeq == null) {
            nextPlayoutSeq = seq
            highestSeqSeen = seq
            expectedCount = 1L
        } else if (seq > highestSeqSeen) {
            expectedCount += (seq - highestSeqSeen).toLong().coerceAtLeast(1L)
            highestSeqSeen = seq
        }
        receivedCount++

        val frameTimestampMs = seq.toLong() * frameDurationMs
        if (lastArrivalTimeMs > 0L) {
            val d = abs((arrivalMs - lastArrivalTimeMs) - (frameTimestampMs - lastFrameTimestampMs)).toFloat()
            estimatedJitterMs += (d - estimatedJitterMs) / 16f
        }
        lastArrivalTimeMs = arrivalMs
        lastFrameTimestampMs = frameTimestampMs

        val curNext = nextPlayoutSeq ?: seq
        if (seq < curNext) {
            // Late frame after playout window already advanced: drop cleanly
            return
        }

        frameSlots[seq] = pcmSamples.copyOf()
        while (frameSlots.size > maxCapacityFrames) {
            val oldestKey = frameSlots.firstKey()
            frameSlots.remove(oldestKey)
            if (nextPlayoutSeq != null && oldestKey >= nextPlayoutSeq!!) {
                nextPlayoutSeq = oldestKey + 1
            }
        }
    }

    @Synchronized
    fun popNextFrame(expectedSamples: Int = 160): ShortArray? {
        val nextSeq = nextPlayoutSeq ?: return null
        val targetFrames = currentTargetFrames()
        if (frameSlots.isEmpty() && lastDeliveredFrame == null) {
            return null
        }

        // Initial buffering hysteresis: wait until at least targetFrames are queued before starting playout
        if (lastDeliveredFrame == null && frameSlots.size < targetFrames) {
            val firstQueued = frameSlots.firstKey()
            if ((highestSeqSeen - firstQueued + 1) < targetFrames) {
                return null
            }
        }

        val exact = frameSlots.remove(nextSeq)
        if (exact != null) {
            nextPlayoutSeq = nextSeq + 1
            lastDeliveredFrame = exact
            consecutiveConcealed = 0
            return exact
        }

        // Missing slot: advance seq and emit gentle PLC attenuation fade from last delivered frame
        if (frameSlots.isNotEmpty()) {
            nextPlayoutSeq = nextSeq + 1
            val prev = lastDeliveredFrame
            if (prev != null && consecutiveConcealed < 2) {
                consecutiveConcealed++
                val gain = if (consecutiveConcealed == 1) 0.70f else 0.35f
                val faded = ShortArray(prev.size) { i -> (prev[i] * gain).toInt().toShort() }
                lastDeliveredFrame = faded
                return faded
            }
            return ShortArray(expectedSamples)
        }

        return null
    }

    @Synchronized
    fun currentTargetDepthMs(): Int {
        val target = (minDepthMs + estimatedJitterMs * 2f).toInt()
        return target.coerceIn(minDepthMs, maxDepthMs)
    }

    @Synchronized
    fun currentTargetFrames(): Int {
        return (currentTargetDepthMs() / frameDurationMs.coerceAtLeast(1)).coerceIn(2, 10)
    }

    @Synchronized
    fun getJitterMs(): Int = estimatedJitterMs.toInt().coerceAtLeast(0)

    @Synchronized
    fun getPacketLossPct(): Float {
        if (expectedCount <= 0L) return 0f
        val lost = (expectedCount - receivedCount).coerceAtLeast(0L)
        return ((lost.toFloat() / expectedCount.toFloat()) * 100f).coerceIn(0f, 100f)
    }

    @Synchronized
    fun bufferedFrameCount(): Int = frameSlots.size

    @Synchronized
    fun reset() {
        frameSlots.clear()
        nextPlayoutSeq = null
        lastArrivalTimeMs = 0L
        lastFrameTimestampMs = 0L
        estimatedJitterMs = 0f
        lastDeliveredFrame = null
        consecutiveConcealed = 0
        receivedCount = 0L
        expectedCount = 0L
        highestSeqSeen = -1
    }
}
