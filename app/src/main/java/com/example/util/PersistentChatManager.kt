// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.util

import android.content.Context
import com.example.model.MeshPacket
import com.example.model.PacketType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Fast JSON file storage with instant memory caching for all messages,
 * channel histories, and 1-on-1 Direct Messages.
 * Persists data to chat_history.json in context.filesDir.
 */
class PersistentChatManager(private val context: Context) {

    private val historyFile = File(context.filesDir, "chat_history_v7.json")
    private val memoryCache = ConcurrentHashMap<String, MutableList<MeshPacket>>()

    init {
        // Purge old chat_history.json if exists to remove legacy bots
        runCatching {
            val oldFile = File(context.filesDir, "chat_history.json")
            if (oldFile.exists()) oldFile.delete()
        }
        loadFromDisk()
    }

    @Synchronized
    private fun loadFromDisk() {
        memoryCache.clear()
        if (!historyFile.exists()) {
            seedDefaultChannels()
            persistToDisk()
            return
        }

        var purgedLegacyFakeThreads = false
        runCatching {
            val jsonStr = historyFile.readText()
            val root = JSONObject(jsonStr)
            val threadsObj = root.optJSONObject("threads") ?: JSONObject()

            val keys = threadsObj.keys()
            while (keys.hasNext()) {
                val threadId = keys.next()
                val array = threadsObj.optJSONArray(threadId) ?: continue
                val list = mutableListOf<MeshPacket>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val contentStr = item.optString("content", "")
                    val cs = item.optString("sourceCallsign", "Operator")
                    val isIncoming = item.optBoolean("isIncoming", false)
                    if (isBlockedSystemPayload(contentStr)) continue
                    if (isIncoming && com.example.service.OffGridBleMeshEngine.isBlockedFakeCallsign(cs)) {
                        purgedLegacyFakeThreads = true
                        continue
                    }
                    val srcId = item.optString("sourceId", "0x0000")
                    val pktId = item.optString("id", "PKT#000")
                    if (srcId == "0x0000" && (pktId == "PKT#001" || pktId == "PKT#002") &&
                        (cs == "Mesh Relay" || cs == "Security Gateway")
                    ) {
                        purgedLegacyFakeThreads = true
                        continue
                    }
                    list.add(
                        MeshPacket(
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
                            isIncoming = isIncoming,
                            timestamp = item.optLong("timestamp", System.currentTimeMillis()),
                            isDelivered = item.optBoolean("isDelivered", false),
                            ackedByCallsign = item.optString("ackedByCallsign", ""),
                            expiresAtMs = item.optLong("expiresAtMs", 0L)
                        )
                    )
                }
                if (threadId.startsWith("dm_") && list.isEmpty()) {
                    purgedLegacyFakeThreads = true
                    continue
                }
                memoryCache[threadId] = list
            }
        }.onFailure {
            seedDefaultChannels()
            persistToDisk()
        }
        if (purgedLegacyFakeThreads) {
            persistToDisk()
        }

        // Guarantee primary and encrypted channels exist (clean empty threads, zero hardcoded bots)
        if (!memoryCache.containsKey("channel_primary")) {
            memoryCache["channel_primary"] = mutableListOf()
        }
        if (!memoryCache.containsKey("channel_encrypted")) {
            memoryCache["channel_encrypted"] = mutableListOf()
        }
    }

    private fun seedDefaultChannels() {
        memoryCache["channel_primary"] = mutableListOf()
        memoryCache["channel_encrypted"] = mutableListOf()
    }

    private fun isBlockedSystemPayload(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.startsWith("[[PM_VOICEDROP]]:") ||
            trimmed.startsWith("[[PM_AUDIO]]:") ||
            trimmed.startsWith("[[PM_IMAGE]]:") ||
            trimmed.startsWith("[[PM_IMG]]:")
        ) {
            return false
        }
        return trimmed.startsWith("[[PM_") || trimmed.startsWith("__PM_SYS_CALL_")
    }

    @Synchronized
    fun savePacket(threadId: String, packet: MeshPacket) {
        if (isBlockedSystemPayload(packet.content)) return
        val list = memoryCache.getOrPut(threadId) { mutableListOf() }
        // Prevent duplicate packet IDs
        if (list.none { it.id == packet.id }) {
            list.add(0, packet) // newest first
            while (list.size > 200) {
                list.removeAt(list.lastIndex)
            }
            persistToDisk()
        }
    }

    @Synchronized
    fun markPacketDelivered(packetId: String, ackerCallsign: String = ""): Boolean {
        if (packetId.isBlank()) return false
        var updatedAny = false
        for ((_, list) in memoryCache) {
            for (idx in list.indices) {
                val existing = list[idx]
                if (existing.id == packetId && !existing.isIncoming) {
                    if (!existing.isDelivered || (ackerCallsign.isNotBlank() && existing.ackedByCallsign.isBlank())) {
                        list[idx] = existing.copy(
                            isDelivered = true,
                            ackedByCallsign = ackerCallsign.ifBlank { existing.ackedByCallsign }
                        )
                        updatedAny = true
                    }
                }
            }
        }
        if (updatedAny) {
            persistToDisk()
        }
        return updatedAny
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
        if (removedCount > 0) {
            persistToDisk()
        }
        return removedCount
    }

    @Synchronized
    fun deleteThread(threadId: String): Boolean {
        val removed = memoryCache.remove(threadId) != null
        if (removed) persistToDisk()
        return removed
    }

    @Synchronized
    fun deleteAllDataMatchingAccount(username: String, nodeId: String): Int {
        val normUser = com.example.crypto.TacticalE2eeManager.normalizeUsername(username)
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
        persistToDisk()
        return removedCount
    }

    @Synchronized
    fun wipeAllHistoryForDuress() {
        memoryCache.clear()
        runCatching {
            if (historyFile.exists()) historyFile.delete()
        }
        seedDefaultChannels()
        persistToDisk()
    }

    @Synchronized
    fun getPacketsForThread(threadId: String): List<MeshPacket> {
        pruneExpiredPackets()
        return memoryCache[threadId]?.toList() ?: emptyList()
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

    /**
     * Merges restored threads & messages into local storage without duplicating packet IDs,
     * preserving newest-first ordering capped at 200 packets per thread.
     * Returns the count of newly imported messages.
     */
    @Synchronized
    fun importThreadsMap(threads: Map<String, List<MeshPacket>>): Int {
        var importedCount = 0
        for ((threadId, incomingList) in threads) {
            if (threadId.isBlank()) continue
            val existing = memoryCache.getOrPut(threadId) { mutableListOf() }
            val existingIds = existing.mapTo(HashSet()) { it.id }
            for (pkt in incomingList) {
                if (isBlockedSystemPayload(pkt.content)) continue
                if (pkt.id.isBlank() || !existingIds.add(pkt.id)) continue
                // Skip redundant static channel seed messages if thread already has messages
                if ((pkt.id == "PKT#001" || pkt.id == "PKT#002") && existing.isNotEmpty()) continue
                existing.add(pkt)
                importedCount++
            }
            existing.sortByDescending { it.timestamp }
            while (existing.size > 200) {
                existing.removeAt(existing.lastIndex)
            }
        }
        if (importedCount > 0) {
            persistToDisk()
        }
        return importedCount
    }

    @Synchronized
    private fun persistToDisk() {
        runCatching {
            val root = JSONObject()
            val threadsObj = JSONObject()
            for ((threadId, packets) in memoryCache) {
                val array = JSONArray()
                // Save at most 200 packets per thread to keep JSON operations instantaneous
                val toSave = packets.take(200)
                for (p in toSave) {
                    val item = JSONObject().apply {
                        put("id", p.id)
                        put("sourceId", p.sourceId)
                        put("destId", p.destId)
                        put("sourceCallsign", p.sourceCallsign)
                        put("type", p.type.name)
                        put("content", p.content)
                        put("hops", p.hops)
                        if (p.rssiDbm != null) put("rssiDbm", p.rssiDbm)
                        put("transport", p.transport)
                        put("rawHexBadge", p.rawHexBadge)
                        put("isIncoming", p.isIncoming)
                        put("timestamp", p.timestamp)
                        put("isDelivered", p.isDelivered)
                        if (p.ackedByCallsign.isNotBlank()) {
                            put("ackedByCallsign", p.ackedByCallsign)
                        }
                        if (p.expiresAtMs > 0L) {
                            put("expiresAtMs", p.expiresAtMs)
                        }
                    }
                    array.put(item)
                }
                threadsObj.put(threadId, array)
            }
            root.put("threads", threadsObj)
            historyFile.writeText(root.toString())
        }
    }
}
