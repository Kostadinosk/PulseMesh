// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.model

import com.kostadinosk.pulsemesh.service.CallSignalingState
import com.kostadinosk.pulsemesh.service.DuplexCallState

data class ConferencePeerStatus(
    val nodeId: String,
    val callsign: String,
    val isMuted: Boolean = false,
    val volumeGain: Float = 1.0f,
    val vadLevel: Float = 0.0f,
    val isSpeaking: Boolean = false
)

/**
 * Single Unified Call State (PulseMesh v1.5.0) consolidating the previously fragmented
 * `duplexCallState`, `activePrivateCallNode`, and `callState` state slices into one canonical model.
 */
data class UnifiedCallState(
    val callId: String = "",
    val signalingState: CallSignalingState = CallSignalingState.IDLE,
    val peerNodeId: String = "",
    val peerCallsign: String = "",
    val isConferenceCall: Boolean = false,
    val conferencePeers: List<ConferencePeerStatus> = emptyList(),
    val durationSeconds: Int = 0,
    val isMuted: Boolean = false,
    val isSpeakerphoneOn: Boolean = true,
    val audioRouteLabel: String = "Speaker",
    val codecLabel: String = "Opus 20ms (E2EE)",
    val rttMs: Int = 0,
    val packetLossPct: Float = 0f,
    val jitterMs: Int = 0,
    val isE2eeVerified: Boolean = true,
    val waveformSamples: List<Float> = emptyList(),
    val isPushToOverrideActive: Boolean = false
) {
    val isIdle: Boolean get() = signalingState == CallSignalingState.IDLE
    val isRinging: Boolean get() = signalingState == CallSignalingState.RINGING_INCOMING || signalingState == CallSignalingState.RINGING_OUTGOING
    val isIncoming: Boolean get() = signalingState == CallSignalingState.RINGING_INCOMING
    val isInCall: Boolean get() = signalingState == CallSignalingState.IN_CALL_ACTIVE

    companion object {
        fun fromDuplex(
            duplex: DuplexCallState,
            callId: String = "",
            routeLabel: String = if (duplex.isSpeakerphoneOn) "Speaker" else "Earpiece",
            rttMs: Int = 0,
            lossPct: Float = 0f,
            jitterMs: Int = 0,
            codecOverride: String = "",
            e2eeVerified: Boolean = true,
            waveform: List<Float> = emptyList(),
            conferencePeers: List<ConferencePeerStatus> = emptyList()
        ): UnifiedCallState = UnifiedCallState(
            callId = callId,
            signalingState = duplex.signalingState,
            peerNodeId = duplex.peerNodeId,
            peerCallsign = duplex.peerCallsign,
            isConferenceCall = conferencePeers.size > 1 || duplex.peerNodeId.equals("ALL", ignoreCase = true),
            conferencePeers = conferencePeers,
            durationSeconds = duplex.durationSeconds,
            isMuted = duplex.isMuted,
            isSpeakerphoneOn = duplex.isSpeakerphoneOn,
            audioRouteLabel = routeLabel,
            codecLabel = codecOverride.ifBlank { duplex.transportType },
            rttMs = rttMs,
            packetLossPct = lossPct,
            jitterMs = jitterMs,
            isE2eeVerified = e2eeVerified,
            waveformSamples = waveform,
            isPushToOverrideActive = duplex.isPushToOverrideActive
        )
    }
}
