// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.viewmodel.controllers

import com.kostadinosk.pulsemesh.model.MeshPacket
import com.kostadinosk.pulsemesh.util.PersistentChatManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * Manages modern messenger interactions (reply/quote, edit, unsend, typing indicators,
 * delivery & read receipts, FTS search, and inline voice-note metadata).
 */
class ChatController(
    private val chatManager: PersistentChatManager
) {
    companion object {
        const val TYPE_MSG_EDIT = "MSG_EDIT"
        const val TYPE_MSG_UNSEND = "MSG_UNSEND"
        const val TYPE_MSG_READ = "MSG_READ"
        const val TYPE_TYPING_START = "TYPING_START"
        const val TYPE_TYPING_STOP = "TYPING_STOP"
    }

    private val _typingPeersByThread = MutableStateFlow<Map<String, Pair<String, Long>>>(emptyMap())
    val typingPeersByThread: StateFlow<Map<String, Pair<String, Long>>> = _typingPeersByThread.asStateFlow()

    fun searchThread(query: String, threadIdFilter: String? = null): List<Pair<String, MeshPacket>> {
        return chatManager.searchPackets(query, threadIdFilter)
    }

    fun applyEdit(threadId: String, packetId: String, newText: String, editedAtMs: Long = System.currentTimeMillis()): Boolean {
        return chatManager.editPacketContent(threadId, packetId, newText, editedAtMs)
    }

    fun applyUnsend(packetId: String): Boolean {
        return chatManager.unsendPacket(packetId)
    }

    fun applyReadReceipt(packetId: String): Boolean {
        return chatManager.markPacketRead(packetId)
    }

    fun updateTypingIndicator(threadId: String, callsign: String, isTyping: Boolean, nowMs: Long = System.currentTimeMillis()) {
        _typingPeersByThread.update { cur ->
            val next = cur.toMutableMap()
            if (isTyping) next[threadId] = callsign to nowMs else next.remove(threadId)
            next
        }
    }

    fun pruneStaleTyping(nowMs: Long = System.currentTimeMillis()) {
        _typingPeersByThread.update { cur ->
            cur.filterValues { (_, ts) -> (nowMs - ts) <= 4_500L }
        }
    }

    fun encodeEditPayload(targetPacketId: String, threadId: String, newContent: String, editedAtMs: Long): String {
        return JSONObject().apply {
            put("targetId", targetPacketId)
            put("threadId", threadId)
            put("content", newContent)
            put("editedAt", editedAtMs)
        }.toString()
    }

    fun encodeReadReceiptPayload(targetPacketId: String, threadId: String): String {
        return JSONObject().apply {
            put("targetId", targetPacketId)
            put("threadId", threadId)
        }.toString()
    }
}
