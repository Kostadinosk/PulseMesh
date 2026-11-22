// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.util

import android.content.Context
import com.kostadinosk.pulsemesh.crypto.KeystoreMasterKeyManager
import com.kostadinosk.pulsemesh.data.db.DatabaseFactory
import com.kostadinosk.pulsemesh.data.db.MessageDao
import com.kostadinosk.pulsemesh.data.db.MessageEntity
import com.kostadinosk.pulsemesh.model.MeshPacket
import com.kostadinosk.pulsemesh.model.PacketType
import com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val MAX_MEMORY_CACHE_PER_THREAD = 200

/**
 * Room-backed unlimited message repository (`PulseMeshDatabase`: SQLCipher on Full flavor,
 * Keystore AES-256-GCM field encryption on Lite flavor) with FTS search, full messenger
 * metadata (replies, edits, unsend, delivery/read receipts, attachments, voice notes), and
 * one-time verified migration from legacy `chat_history_v7.json`.
 */
class PersistentChatManager(context: Context) {

    private val appContext = context.applicationContext
    private val db = DatabaseFactory.getDatabase(appContext)
    private val messageDao: MessageDao = db.messageDao()
    private val historyFile = File(appContext.filesDir, "chat_history_v7.json")
    private val memoryCache = ConcurrentHashMap<String, MutableList<MeshPacket>>()

    init {
        purgeLegacyVersionFiles()
        migrateLegacyV7JsonIfNeeded()
        loadFromRoom()
    }

    private fun purgeLegacyVersionFiles() {
        runCatching {
            val legacyNames = listOf(
                "chat_history.json",
                "chat_history_v1.json",
                "chat_history_v2.json",
                "chat_history_v3.json",
                "chat_history_v4.json",
                "chat_history_v5.json",
                "chat_history_v6.json"
            )
            for (name in legacyNames) {
                val oldFile = File(appContext.filesDir, name)
                if (oldFile.exists()) oldFile.delete()
            }
        }
    }

    @Synchronized
    private fun migrateLegacyV7JsonIfNeeded() {
        if (!historyFile.exists()) return
        val entitiesToInsert = mutableListOf<MessageEntity>()
        runCatching {
            val jsonStr = historyFile.readText()
            val root = JSONObject(jsonStr)
            val threadsObj = root.optJSONObject("threads") ?: JSONObject()
            val keys = threadsObj.keys()
            while (keys.hasNext()) {
                val threadId = keys.next()
                val array = threadsObj.optJSONArray(threadId) ?: continue
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val contentStr = item.optString("content", "")
                    val cs = item.optString("sourceCallsign", "Operator")
                    if (isBlockedSystemPayload(contentStr)) continue
                    if (OffGridBleMeshEngine.isBlockedFakeCallsign(cs)) continue
                    val srcId = item.optString("sourceId", "0x0000")
                    val pktId = item.optString("id", "PKT#000")
                    if (srcId == "0x0000" && (pktId == "PKT#001" || pktId == "PKT#002") &&
                        (cs == "Mesh Relay" || cs == "Security Gateway")
                    ) {
                        continue
                    }
                    val parsedReactions = LinkedHashMap<String, List<String>>()
                    item.optJSONObject("reactions")?.let { rObj ->
                        val rKeys = rObj.keys()
                        while (rKeys.hasNext()) {
                            val emoji = rKeys.next()
                            val arr = rObj.optJSONArray(emoji) ?: continue
                            val actors = mutableListOf<String>()
                            for (ri in 0 until arr.length()) {
                                val a = arr.optString(ri)
                                if (a.isNotBlank()) actors.add(a)
                            }
                            if (actors.isNotEmpty()) parsedReactions[emoji] = actors
                        }
                    }
                    val pkt = MeshPacket(
                        id = pktId,
                        sourceId = srcId,
                        destId = item.optString("destId", "ALL"),
                        sourceCallsign = cs,
                        type = runCatching { PacketType.valueOf(item.optString("type", "TEXT")) }.getOrDefault(PacketType.TEXT),
                        content = contentStr,
                        hops = item.optInt("hops", 0),
                        rssiDbm = if (item.has("rssiDbm") && !item.isNull("rssiDbm")) item.optInt("rssiDbm") else null,
                        transport = item.optString("transport", "BLE 1M"),
                        rawHexBadge = item.optString("rawHexBadge", ""),
                        isIncoming = item.optBoolean("isIncoming", false),
                        timestamp = item.optLong("timestamp", System.currentTimeMillis()),
                        isDelivered = item.optBoolean("isDelivered", false),
                        ackedByCallsign = item.optString("ackedByCallsign", ""),
                        expiresAtMs = item.optLong("expiresAtMs", 0L),
                        reactions = parsedReactions,
                        isPinned = item.optBoolean("isPinned", false)
                    )
                    if (messageDao.getOneInThread(threadId, pkt.id) == null) {
                        entitiesToInsert.add(toEntity(threadId, pkt))
                    }
                }
            }
            if (entitiesToInsert.isNotEmpty()) {
                messageDao.upsertAll(entitiesToInsert)
            }
            historyFile.delete()
        }
    }

    @Synchronized
    private fun loadFromRoom() {
        memoryCache.clear()
        val allEntities = messageDao.getAllMessagesDesc()
        var removedFake = false
        for (entity in allEntities) {
            val pkt = fromEntity(entity) ?: continue
            if (isBlockedSystemPayload(pkt.content) || OffGridBleMeshEngine.isBlockedFakeCallsign(pkt.sourceCallsign)) {
                removedFake = true
                continue
            }
            val list = memoryCache.getOrPut(entity.threadId) { mutableListOf() }
            if (list.size < MAX_MEMORY_CACHE_PER_THREAD && list.none { it.id == pkt.id }) {
                list.add(pkt)
            }
        }
        if (removedFake) {
            purgeCachedFakeThreads()
        }
        seedDefaultChannelsIfMissing()
    }

    private fun seedDefaultChannelsIfMissing() {
        if (!memoryCache.containsKey("channel_primary")) {
            memoryCache["channel_primary"] = mutableListOf()
        }
        if (!memoryCache.containsKey("channel_encrypted")) {
            memoryCache["channel_encrypted"] = mutableListOf()
        }
    }

    @Synchronized
    fun purgeCachedFakeThreads(): Int {
        var removedCount = 0
        val iter = memoryCache.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val tid = entry.key
            val list = entry.value
            val before = list.size
            val removedIds = mutableListOf<String>()
            list.removeAll { pkt ->
                val drop = OffGridBleMeshEngine.isBlockedFakeCallsign(pkt.sourceCallsign) ||
                    isBlockedSystemPayload(pkt.content)
                if (drop) removedIds.add(pkt.id)
                drop
            }
            removedCount += (before - list.size)
            if (tid.startsWith("dm_") && list.isEmpty()) {
                messageDao.deleteThread(tid)
                iter.remove()
                removedCount++
            }
        }
        if (removedCount > 0) {
            rebuildRoomFromCache()
        }
        return removedCount
    }

    private fun isBlockedSystemPayload(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.startsWith("[[PM_VOICEDROP]]:") ||
            trimmed.startsWith("[[PM_AUDIO]]:") ||
            trimmed.startsWith("[[PM_IMAGE]]:") ||
            trimmed.startsWith("[[PM_IMG]]:") ||
            trimmed.startsWith("[[PM_FILE]]:") ||
            trimmed.startsWith("[[PM_VOICENOTE]]:")
        ) {
            return false
        }
        return trimmed.startsWith("[[PM_") || trimmed.startsWith("__PM_SYS_CALL_")
    }

    @Synchronized
    fun savePacket(threadId: String, packet: MeshPacket) {
        if (isBlockedSystemPayload(packet.content)) return
        val list = memoryCache.getOrPut(threadId) { mutableListOf() }
        if (list.none { it.id == packet.id }) {
            list.add(0, packet) // newest first in hot cache; unlimited history persisted in Room
            while (list.size > MAX_MEMORY_CACHE_PER_THREAD) {
                list.removeAt(list.lastIndex)
            }
            messageDao.upsert(toEntity(threadId, packet))
        }
    }

    @Synchronized
    fun updatePacketInThread(threadId: String, updated: MeshPacket): Boolean {
        val list = memoryCache[threadId] ?: return false
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx < 0) return false
        list[idx] = updated
        val existingRow = messageDao.getOneInThread(threadId, updated.id)
        val entity = toEntity(threadId, updated).copy(rowId = existingRow?.rowId ?: 0L)
        messageDao.upsert(entity)
        return true
    }

    @Synchronized
    fun editPacketContent(threadId: String, packetId: String, newContent: String, editedAtMs: Long = System.currentTimeMillis()): Boolean {
        val list = memoryCache[threadId] ?: return false
        val idx = list.indexOfFirst { it.id == packetId }
        if (idx < 0) return false
        val updated = list[idx].copy(content = newContent, editedAtMs = editedAtMs)
        return updatePacketInThread(threadId, updated)
    }

    @Synchronized
    fun unsendPacket(packetId: String): Boolean {
        if (packetId.isBlank()) return false
        var changed = false
        for ((tid, list) in memoryCache) {
            for (idx in list.indices) {
                val p = list[idx]
                if (p.id == packetId) {
                    val next = p.copy(
                        isUnsent = true,
                        content = "[Message unsent]",
                        attachmentPath = "",
                        attachmentName = "",
                        attachmentSizeBytes = 0L
                    )
                    list[idx] = next
                    val existingRow = messageDao.getOneInThread(tid, p.id)
                    messageDao.upsert(toEntity(tid, next).copy(rowId = existingRow?.rowId ?: 0L))
                    changed = true
                }
            }
        }
        return changed
    }

    @Synchronized
    fun markPacketDelivered(packetId: String, ackerCallsign: String = ""): Boolean {
        if (packetId.isBlank()) return false
        var updatedAny = false
        for ((tid, list) in memoryCache) {
            for (idx in list.indices) {
                val existing = list[idx]
                if (existing.id == packetId && (!existing.isIncoming || existing.transport == "WEB PORTAL")) {
                    if (!existing.isDelivered || (ackerCallsign.isNotBlank() && existing.ackedByCallsign.isBlank())) {
                        val nextStatus = if (existing.deliveryStatus == "READ") "READ" else "DELIVERED"
                        val next = existing.copy(
                            isDelivered = true,
                            deliveryStatus = nextStatus,
                            ackedByCallsign = ackerCallsign.ifBlank { existing.ackedByCallsign }
                        )
                        list[idx] = next
                        val existingRow = messageDao.getOneInThread(tid, existing.id)
                        messageDao.upsert(toEntity(tid, next).copy(rowId = existingRow?.rowId ?: 0L))
                        updatedAny = true
                    }
                }
            }
        }
        return updatedAny
    }

    @Synchronized
    fun markPacketRead(packetId: String): Boolean {
        if (packetId.isBlank()) return false
        var updatedAny = false
        for ((tid, list) in memoryCache) {
            for (idx in list.indices) {
                val existing = list[idx]
                if (existing.id == packetId && existing.deliveryStatus != "READ") {
                    val next = existing.copy(isDelivered = true, deliveryStatus = "READ")
                    list[idx] = next
                    val existingRow = messageDao.getOneInThread(tid, existing.id)
                    messageDao.upsert(toEntity(tid, next).copy(rowId = existingRow?.rowId ?: 0L))
                    updatedAny = true
                }
            }
        }
        return updatedAny
    }

    @Synchronized
    fun updatePacketTransportBadge(
        threadId: String,
        packetId: String,
        transport: String,
        rawHexBadge: String
    ): Boolean {
        val list = memoryCache[threadId] ?: return false
        for (idx in list.indices) {
            val existing = list[idx]
            if (existing.id == packetId) {
                val next = existing.copy(transport = transport, rawHexBadge = rawHexBadge)
                list[idx] = next
                val existingRow = messageDao.getOneInThread(threadId, existing.id)
                messageDao.upsert(toEntity(threadId, next).copy(rowId = existingRow?.rowId ?: 0L))
                return true
            }
        }
        return false
    }

    @Synchronized
    fun pruneExpiredPackets(nowMs: Long = System.currentTimeMillis()): Int {
        var removedCount = 0
        for ((_, list) in memoryCache) {
            val iter = list.iterator()
            while (iter.hasNext()) {
                val pkt = iter.next()
                if (pkt.expiresAtMs in 1..nowMs) {
                    iter.remove()
                    removedCount++
                }
            }
        }
        val dbRemoved = messageDao.deleteExpired(nowMs)
        return maxOf(removedCount, dbRemoved)
    }

    @Synchronized
    fun deleteThread(threadId: String): Boolean {
        val removedMem = memoryCache.remove(threadId) != null
        val removedDb = messageDao.deleteThread(threadId) > 0
        return removedMem || removedDb
    }

    @Synchronized
    fun deleteAllDataMatchingAccount(username: String, nodeId: String): Int {
        val normUser = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.normalizeUsername(username)
        val normNode = nodeId.trim().uppercase().removePrefix("0X")
        var removedCount = 0

        val threadIter = memoryCache.entries.iterator()
        while (threadIter.hasNext()) {
            val entry = threadIter.next()
            val tid = entry.key
            val tidPeerNorm = tid.removePrefix("dm_").trim().uppercase().removePrefix("0X")
            if (tid.startsWith("dm_") && normNode.isNotBlank() && tidPeerNorm == normNode) {
                removedCount += entry.value.size
                threadIter.remove()
                continue
            }
            val pktIter = entry.value.iterator()
            while (pktIter.hasNext()) {
                val p = pktIter.next()
                val srcNorm = p.sourceId.trim().uppercase().removePrefix("0X")
                val dstNorm = p.destId.trim().uppercase().removePrefix("0X")
                val matchesNode = normNode.isNotBlank() && (srcNorm == normNode || dstNorm == normNode)
                val matchesUser = normUser.isNotBlank() && (
                    p.sourceCallsign.contains("@$normUser", ignoreCase = true) ||
                        p.sourceCallsign.equals(normUser, ignoreCase = true) ||
                        p.content.contains("@$normUser", ignoreCase = true)
                    )
                if (matchesNode || matchesUser) {
                    pktIter.remove()
                    removedCount++
                }
            }
        }
        if (removedCount > 0) {
            rebuildRoomFromCache()
        }
        return removedCount
    }

    @Synchronized
    fun wipeAllHistoryForDuress() {
        memoryCache.clear()
        runCatching {
            if (historyFile.exists()) historyFile.delete()
        }
        messageDao.deleteAll()
        seedDefaultChannelsIfMissing()
    }

    @Synchronized
    fun getPacketsForThread(threadId: String): List<MeshPacket> {
        pruneExpiredPackets()
        return memoryCache[threadId]?.toList() ?: emptyList()
    }

    @Synchronized
    fun getPacketsForThreadPaged(threadId: String, limit: Int, offset: Int): List<MeshPacket> {
        pruneExpiredPackets()
        return messageDao.getThreadMessagesPaged(threadId, limit, offset).mapNotNull { fromEntity(it) }
    }

    @Synchronized
    fun getAllThreadIds(): Set<String> {
        return memoryCache.keys.toSet()
    }

    @Synchronized
    fun exportAllThreadsMap(): Map<String, List<MeshPacket>> {
        val out = LinkedHashMap<String, List<MeshPacket>>()
        for ((threadId, list) in memoryCache) {
            out[threadId] = list.toList()
        }
        return out
    }

    @Synchronized
    fun importThreadsMap(threads: Map<String, List<MeshPacket>>): Int {
        var importedCount = 0
        val newEntities = mutableListOf<MessageEntity>()
        for ((threadId, incomingList) in threads) {
            if (threadId.isBlank()) continue
            val existing = memoryCache.getOrPut(threadId) { mutableListOf() }
            val existingIds = existing.mapTo(HashSet()) { it.id }
            for (pkt in incomingList) {
                if (isBlockedSystemPayload(pkt.content)) continue
                if (pkt.id.isBlank() || !existingIds.add(pkt.id)) continue
                if ((pkt.id == "PKT#001" || pkt.id == "PKT#002") && existing.isNotEmpty()) continue
                existing.add(pkt)
                newEntities.add(toEntity(threadId, pkt))
                importedCount++
            }
            existing.sortByDescending { it.timestamp }
        }
        if (newEntities.isNotEmpty()) {
            messageDao.upsertAll(newEntities)
        }
        return importedCount
    }

    @Synchronized
    fun toggleReaction(packetId: String, emoji: String, actorCallsign: String): Boolean {
        if (packetId.isBlank() || emoji.isBlank()) return false
        val cleanActor = actorCallsign.ifBlank { "Peer" }
        var changed = false
        for ((tid, list) in memoryCache) {
            for (idx in list.indices) {
                val p = list[idx]
                if (p.id == packetId) {
                    val nextMap = p.reactions.mapValues { it.value.toMutableList() }.toMutableMap()
                    val actors = nextMap.getOrPut(emoji) { mutableListOf() }
                    if (actors.contains(cleanActor)) {
                        actors.remove(cleanActor)
                        if (actors.isEmpty()) nextMap.remove(emoji)
                    } else {
                        actors.add(cleanActor)
                    }
                    val next = p.copy(reactions = nextMap.mapValues { it.value.toList() })
                    list[idx] = next
                    val existingRow = messageDao.getOneInThread(tid, p.id)
                    messageDao.upsert(toEntity(tid, next).copy(rowId = existingRow?.rowId ?: 0L))
                    changed = true
                }
            }
        }
        return changed
    }

    @Synchronized
    fun togglePin(threadId: String, packetId: String): Boolean {
        val list = memoryCache[threadId] ?: return false
        for (idx in list.indices) {
            val p = list[idx]
            if (p.id == packetId) {
                val next = p.copy(isPinned = !p.isPinned)
                list[idx] = next
                val existingRow = messageDao.getOneInThread(threadId, p.id)
                messageDao.upsert(toEntity(threadId, next).copy(rowId = existingRow?.rowId ?: 0L))
                return true
            }
        }
        return false
    }

    @Synchronized
    fun searchPackets(query: String, threadIdFilter: String? = null): List<Pair<String, MeshPacket>> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        // Use native SQLite FTS when SQLCipher covers full database encryption; use decrypted fallback on field-encrypted Lite
        if (!DatabaseFactory.requiresFieldEncryption()) {
            val sanitized = q.replace("\"", " ").trim()
            if (sanitized.isNotEmpty()) {
                val ftsRes = runCatching {
                    val ftsQuery = sanitized.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ") { "$it*" }
                    val entities = if (threadIdFilter != null) {
                        messageDao.searchFtsInThread(threadIdFilter, ftsQuery)
                    } else {
                        messageDao.searchFts(ftsQuery)
                    }
                    entities.mapNotNull { ent -> fromEntity(ent)?.let { ent.threadId to it } }
                }.getOrNull()
                if (!ftsRes.isNullOrEmpty()) return ftsRes
            }
        }

        val results = mutableListOf<Pair<String, MeshPacket>>()
        for ((tid, list) in memoryCache) {
            if (threadIdFilter != null && tid != threadIdFilter) continue
            for (p in list) {
                if (p.content.contains(q, ignoreCase = true) || p.sourceCallsign.contains(q, ignoreCase = true)) {
                    results.add(tid to p)
                }
            }
        }
        return results.sortedByDescending { it.second.timestamp }
    }

    private fun rebuildRoomFromCache() {
        messageDao.deleteAll()
        val entities = mutableListOf<MessageEntity>()
        for ((tid, list) in memoryCache) {
            for (pkt in list) entities.add(toEntity(tid, pkt))
        }
        if (entities.isNotEmpty()) messageDao.upsertAll(entities)
    }

    private fun encodeReactionsJson(reactions: Map<String, List<String>>): String {
        if (reactions.isEmpty()) return ""
        val rObj = JSONObject()
        for ((emoji, actors) in reactions) {
            val arr = JSONArray()
            actors.forEach { arr.put(it) }
            rObj.put(emoji, arr)
        }
        return rObj.toString()
    }

    private fun parseReactionsJson(raw: String): Map<String, List<String>> {
        if (raw.isBlank()) return emptyMap()
        val parsed = LinkedHashMap<String, List<String>>()
        runCatching {
            val rObj = JSONObject(raw)
            val rKeys = rObj.keys()
            while (rKeys.hasNext()) {
                val emoji = rKeys.next()
                val arr = rObj.optJSONArray(emoji) ?: continue
                val actors = mutableListOf<String>()
                for (ri in 0 until arr.length()) {
                    val a = arr.optString(ri)
                    if (a.isNotBlank()) actors.add(a)
                }
                if (actors.isNotEmpty()) parsed[emoji] = actors
            }
        }
        return parsed
    }

    private fun toEntity(threadId: String, p: MeshPacket): MessageEntity {
        val encryptFields = DatabaseFactory.requiresFieldEncryption()
        val storedContent = if (encryptFields && p.content.isNotEmpty()) {
            KeystoreMasterKeyManager.wrapString(appContext, p.content)
        } else {
            p.content
        }
        val storedReplyPreview = if (encryptFields && p.replyToPreview.isNotEmpty()) {
            KeystoreMasterKeyManager.wrapString(appContext, p.replyToPreview)
        } else {
            p.replyToPreview
        }
        return MessageEntity(
            id = p.id,
            threadId = threadId,
            sourceId = p.sourceId,
            destId = p.destId,
            sourceCallsign = p.sourceCallsign,
            type = p.type.name,
            content = storedContent,
            hops = p.hops,
            maxHops = p.maxHops,
            rssiDbm = p.rssiDbm,
            transport = p.transport,
            rawHexBadge = p.rawHexBadge,
            isIncoming = p.isIncoming,
            timestamp = p.timestamp,
            isDelivered = p.isDelivered,
            deliveryStatus = p.deliveryStatus,
            ackedByCallsign = p.ackedByCallsign,
            expiresAtMs = p.expiresAtMs,
            reactionsJson = encodeReactionsJson(p.reactions),
            isPinned = p.isPinned,
            replyToId = p.replyToId,
            replyToSender = p.replyToSender,
            replyToPreview = storedReplyPreview,
            editedAtMs = p.editedAtMs,
            isUnsent = p.isUnsent,
            attachmentPath = p.attachmentPath,
            attachmentMime = p.attachmentMime,
            attachmentName = p.attachmentName,
            attachmentSizeBytes = p.attachmentSizeBytes,
            voiceDurationMs = p.voiceDurationMs
        )
    }

    private fun fromEntity(e: MessageEntity): MeshPacket? {
        val plainContent = if (KeystoreMasterKeyManager.isWrapped(e.content)) {
            KeystoreMasterKeyManager.unwrapString(appContext, e.content) ?: return null
        } else {
            e.content
        }
        val plainReplyPreview = if (KeystoreMasterKeyManager.isWrapped(e.replyToPreview)) {
            KeystoreMasterKeyManager.unwrapString(appContext, e.replyToPreview).orEmpty()
        } else {
            e.replyToPreview
        }
        return MeshPacket(
            id = e.id,
            sourceId = e.sourceId,
            destId = e.destId,
            sourceCallsign = e.sourceCallsign,
            type = runCatching { PacketType.valueOf(e.type) }.getOrDefault(PacketType.TEXT),
            content = plainContent,
            hops = e.hops,
            maxHops = e.maxHops,
            rssiDbm = e.rssiDbm,
            transport = e.transport,
            rawHexBadge = e.rawHexBadge,
            isIncoming = e.isIncoming,
            timestamp = e.timestamp,
            isDelivered = e.isDelivered,
            ackedByCallsign = e.ackedByCallsign,
            expiresAtMs = e.expiresAtMs,
            reactions = parseReactionsJson(e.reactionsJson),
            isPinned = e.isPinned,
            deliveryStatus = e.deliveryStatus,
            replyToId = e.replyToId,
            replyToSender = e.replyToSender,
            replyToPreview = plainReplyPreview,
            editedAtMs = e.editedAtMs,
            isUnsent = e.isUnsent,
            attachmentPath = e.attachmentPath,
            attachmentMime = e.attachmentMime,
            attachmentName = e.attachmentName,
            attachmentSizeBytes = e.attachmentSizeBytes,
            voiceDurationMs = e.voiceDurationMs
        )
    }
}
