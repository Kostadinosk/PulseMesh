// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.viewmodel.controllers

import com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager
import com.kostadinosk.pulsemesh.model.ConferencePeerStatus
import com.kostadinosk.pulsemesh.model.UnifiedCallState
import com.kostadinosk.pulsemesh.service.DuplexCallState
import javax.crypto.SecretKey

/**
 * Coordinates unified [UnifiedCallState], active SRTP-style E2EE voice session keys,
 * and per-peer conference state across MeshViewModel and the Voice Engine.
 */
class VoiceCallController {

    @Volatile
    private var activeVoiceKey: SecretKey? = null

    @Volatile
    private var activeCallId: String = ""

    @Volatile
    private var outboundSeq: Int = 0

    fun establishPeerVoiceSession(
        callId: String,
        myPrivateKeyBase64: String,
        peerPublicKeyBase64: String
    ): Boolean {
        val key = TacticalE2eeManager.deriveVoiceSessionKey(callId, myPrivateKeyBase64, peerPublicKeyBase64)
        activeVoiceKey = key
        activeCallId = callId
        outboundSeq = 0
        return key != null
    }

    fun establishGroupVoiceSession(callId: String, groupKeyBase64: String): Boolean {
        val key = TacticalE2eeManager.deriveGroupVoiceSessionKey(callId, groupKeyBase64)
        activeVoiceKey = key
        activeCallId = callId
        outboundSeq = 0
        return key != null
    }

    fun clearSession() {
        activeVoiceKey = null
        activeCallId = ""
        outboundSeq = 0
    }

    fun hasActiveVoiceKey(): Boolean = activeVoiceKey != null

    fun currentCallId(): String = activeCallId

    fun protectOutboundFrame(srcNodeId: String, plainAudio: ByteArray): Pair<Int, ByteArray> {
        val key = activeVoiceKey ?: return (outboundSeq++ to plainAudio)
        val seq = outboundSeq++
        val encrypted = TacticalE2eeManager.encryptVoiceFrame(key, activeCallId, srcNodeId, seq, plainAudio)
        return seq to encrypted
    }

    fun unprotectInboundFrame(srcNodeId: String, seq: Int, packetBytes: ByteArray): ByteArray? {
        val key = activeVoiceKey ?: return packetBytes
        return TacticalE2eeManager.decryptVoiceFrame(key, activeCallId, srcNodeId, seq, packetBytes)
    }

    fun buildUnifiedState(
        duplex: DuplexCallState,
        routeLabel: String = if (duplex.isSpeakerphoneOn) "Speaker" else "Earpiece",
        rttMs: Int = 0,
        lossPct: Float = 0f,
        jitterMs: Int = 0,
        codecOverride: String = "",
        waveform: List<Float> = emptyList(),
        conferencePeers: List<ConferencePeerStatus> = emptyList()
    ): UnifiedCallState = UnifiedCallState.fromDuplex(
        duplex = duplex,
        callId = activeCallId,
        routeLabel = routeLabel,
        rttMs = rttMs,
        lossPct = lossPct,
        jitterMs = jitterMs,
        codecOverride = codecOverride,
        e2eeVerified = activeVoiceKey != null,
        waveform = waveform,
        conferencePeers = conferencePeers
    )
}
