// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

data class PttChannelState(
    val channelId: String,
    val displayName: String,
    val memberNodeIds: Set<String> = emptySet(),
    val activeFloorNodeId: String = "",
    val activeFloorCallsign: String = "",
    val floorAcquiredAtMs: Long = 0L,
    val isJoined: Boolean = false
)

/**
 * Zello-style named Walkie-Talkie PTT channel controller with deterministic floor-control
 * arbitration (earliest monotonic timestamp wins, tie-broken by Node ID), multi-channel
 * presets (`#tactical`, `#ops`, `#medic`), and courtesy roger beep state.
 */
class TacticalPttChannelController {

    private val _channels = MutableStateFlow(
        listOf(
            PttChannelState("#tactical", "#tactical", isJoined = true),
            PttChannelState("#ops", "#ops", isJoined = false),
            PttChannelState("#medic", "#medic", isJoined = false)
        )
    )
    val channels: StateFlow<List<PttChannelState>> = _channels.asStateFlow()

    private val _activeChannelId = MutableStateFlow("#tactical")
    val activeChannelId: StateFlow<String> = _activeChannelId.asStateFlow()

    private val _courtesyBeepEnabled = MutableStateFlow(true)
    val courtesyBeepEnabled: StateFlow<Boolean> = _courtesyBeepEnabled.asStateFlow()

    private val _floorConflictMessage = MutableStateFlow("")
    val floorConflictMessage: StateFlow<String> = _floorConflictMessage.asStateFlow()

    fun normalizeChannelId(raw: String): String {
        val cleaned = raw.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9_-]"), "")
        if (cleaned.isBlank()) return "#tactical"
        return "#${cleaned.take(20)}"
    }

    @Synchronized
    fun joinChannel(rawChannelId: String, localNodeId: String = ""): PttChannelState {
        val id = normalizeChannelId(rawChannelId)
        val existing = _channels.value.firstOrNull { it.channelId == id }
        val updated = (existing ?: PttChannelState(id, id)).copy(
            isJoined = true,
            memberNodeIds = if (localNodeId.isNotBlank()) {
                (existing?.memberNodeIds ?: emptySet()) + localNodeId
            } else {
                existing?.memberNodeIds ?: emptySet()
            }
        )
        _channels.update { cur ->
            val without = cur.filterNot { it.channelId == id }
            listOf(updated) + without
        }
        _activeChannelId.value = id
        return updated
    }

    @Synchronized
    fun leaveChannel(rawChannelId: String, localNodeId: String = "") {
        val id = normalizeChannelId(rawChannelId)
        _channels.update { cur ->
            cur.map { ch ->
                if (ch.channelId == id) {
                    val releaseFloor = ch.activeFloorNodeId.equals(localNodeId, ignoreCase = true)
                    ch.copy(
                        isJoined = false,
                        memberNodeIds = ch.memberNodeIds - localNodeId,
                        activeFloorNodeId = if (releaseFloor) "" else ch.activeFloorNodeId,
                        activeFloorCallsign = if (releaseFloor) "" else ch.activeFloorCallsign,
                        floorAcquiredAtMs = if (releaseFloor) 0L else ch.floorAcquiredAtMs
                    )
                } else ch
            }
        }
        if (_activeChannelId.value == id) {
            val next = _channels.value.firstOrNull { it.isJoined }?.channelId ?: "#tactical"
            if (_channels.value.none { it.channelId == "#tactical" && it.isJoined }) {
                joinChannel("#tactical", localNodeId)
            } else {
                _activeChannelId.value = next
            }
        }
    }

    @Synchronized
    fun requestFloor(
        channelId: String = _activeChannelId.value,
        nodeId: String,
        callsign: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val id = normalizeChannelId(channelId)
        val ch = _channels.value.firstOrNull { it.channelId == id } ?: joinChannel(id, nodeId)
        val holder = ch.activeFloorNodeId
        // Allow floor acquisition if channel is idle, already held by same node, or expired (> 45s)
        val holderExpired = ch.floorAcquiredAtMs > 0L && (nowMs - ch.floorAcquiredAtMs) > 45_000L
        if (holder.isBlank() || holder.equals(nodeId, ignoreCase = true) || holderExpired) {
            updateChannelFloor(id, nodeId, callsign, nowMs)
            _floorConflictMessage.value = ""
            return true
        }
        _floorConflictMessage.value = "${ch.activeFloorCallsign.ifBlank { holder }} has the floor on $id"
        return false
    }

    @Synchronized
    fun releaseFloor(
        channelId: String = _activeChannelId.value,
        nodeId: String
    ): Boolean {
        val id = normalizeChannelId(channelId)
        val ch = _channels.value.firstOrNull { it.channelId == id } ?: return false
        if (!ch.activeFloorNodeId.equals(nodeId, ignoreCase = true) && nodeId.isNotBlank()) {
            return false
        }
        updateChannelFloor(id, "", "", 0L)
        _floorConflictMessage.value = ""
        return true
    }

    @Synchronized
    fun onRemoteFloorSignal(
        channelId: String,
        speakerNodeId: String,
        speakerCallsign: String,
        timestampMs: Long,
        acquired: Boolean
    ): Boolean {
        val id = normalizeChannelId(channelId)
        val ch = _channels.value.firstOrNull { it.channelId == id }
            ?: PttChannelState(id, id, isJoined = false).also { added ->
                _channels.update { it + added }
            }

        if (!acquired) {
            if (ch.activeFloorNodeId.equals(speakerNodeId, ignoreCase = true)) {
                updateChannelFloor(id, "", "", 0L)
                return true
            }
            return false
        }

        val currentHolder = ch.activeFloorNodeId
        val holderExpired = ch.floorAcquiredAtMs > 0L && (timestampMs - ch.floorAcquiredAtMs) > 45_000L
        val remoteWins = currentHolder.isBlank() ||
            currentHolder.equals(speakerNodeId, ignoreCase = true) ||
            holderExpired ||
            timestampMs < ch.floorAcquiredAtMs ||
            (timestampMs == ch.floorAcquiredAtMs && speakerNodeId.lowercase(Locale.US) < currentHolder.lowercase(Locale.US))

        if (remoteWins) {
            updateChannelFloor(id, speakerNodeId, speakerCallsign, timestampMs)
            return true
        }
        return false
    }

    fun setCourtesyBeepEnabled(enabled: Boolean) {
        _courtesyBeepEnabled.value = enabled
    }

    fun getActiveChannel(): PttChannelState {
        val id = _activeChannelId.value
        return _channels.value.firstOrNull { it.channelId == id } ?: _channels.value.first()
    }

    private fun updateChannelFloor(
        channelId: String,
        holderNodeId: String,
        holderCallsign: String,
        acquiredAtMs: Long
    ) {
        _channels.update { cur ->
            cur.map { ch ->
                if (ch.channelId == channelId) {
                    val members = if (holderNodeId.isNotBlank()) ch.memberNodeIds + holderNodeId else ch.memberNodeIds
                    ch.copy(
                        memberNodeIds = members,
                        activeFloorNodeId = holderNodeId,
                        activeFloorCallsign = holderCallsign,
                        floorAcquiredAtMs = acquiredAtMs
                    )
                } else ch
            }
        }
    }
}
