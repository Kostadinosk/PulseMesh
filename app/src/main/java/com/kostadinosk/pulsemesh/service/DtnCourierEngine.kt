// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.service

import android.content.Context
import android.util.Base64
import com.kostadinosk.pulsemesh.crypto.KeystoreMasterKeyManager
import com.kostadinosk.pulsemesh.data.db.DatabaseFactory
import com.kostadinosk.pulsemesh.data.db.DtnBundleEntity
import com.kostadinosk.pulsemesh.data.db.DtnDao
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * 256-byte (2048-bit, 3 SHA-256 sub-hashes) Bloom Filter for Epidemic DTN Reconciliation.
 */
class DtnBloomFilter(private val bits: ByteArray = ByteArray(BLOOM_BYTES)) {

    init {
        require(bits.size == BLOOM_BYTES) { "Bloom filter must be exactly $BLOOM_BYTES bytes" }
    }

    fun add(packetId: String) {
        val id = packetId.trim()
        if (id.isEmpty()) return
        for (idx in hashIndices(id)) {
            bits[idx ushr 3] = (bits[idx ushr 3].toInt() or (1 shl (idx and 7))).toByte()
        }
    }

    fun mightContain(packetId: String): Boolean {
        val id = packetId.trim()
        if (id.isEmpty()) return false
        for (idx in hashIndices(id)) {
            if ((bits[idx ushr 3].toInt() and (1 shl (idx and 7))) == 0) return false
        }
        return true
    }

    fun toBase64(): String = Base64.encodeToString(bits, Base64.NO_WRAP)

    companion object {
        const val BLOOM_BYTES = 256
        const val BLOOM_BITS = BLOOM_BYTES * 8 // 2048 bits

        fun fromBase64(rawB64: String): DtnBloomFilter? {
            return runCatching {
                val decoded = Base64.decode(rawB64.trim(), Base64.DEFAULT)
                if (decoded.size != BLOOM_BYTES) null else DtnBloomFilter(decoded)
            }.getOrNull()
        }

        private fun hashIndices(packetId: String): IntArray {
            val digest = MessageDigest.getInstance("SHA-256").digest(packetId.toByteArray(Charsets.UTF_8))
            val bb = ByteBuffer.wrap(digest)
            val h1 = bb.int and 0x7FFFFFFF
            val h2 = bb.int and 0x7FFFFFFF
            val h3 = bb.int and 0x7FFFFFFF
            return intArrayOf(
                h1 % BLOOM_BITS,
                h2 % BLOOM_BITS,
                h3 % BLOOM_BITS
            )
        }
    }
}

/**
 * Room-backed Epidemic / Bloom-Filter DTN Store-and-Forward Courier Engine.
 *
 * Replaces the 50-packet SharedPreferences list with:
 * - Up to 5,000 bundles / 20 MB storage cap (configurable)
 * - Priority scheduling (`SOS > GROUP_INVITE > E2EE_DM / GROUP_CHAT > CHAT`)
 * - 256-byte Bloom-filter summary exchange on peer/island reconnection (`DTN_BLOOM_SYNC`)
 * - Custody acknowledgments (`DTN_CUSTODY_ACK`) and automatic TTL expiration pruning
 */
class DtnCourierEngine(context: Context) {

    private val appContext = context.applicationContext
    private val db = DatabaseFactory.getDatabase(appContext)
    private val dtnDao: DtnDao = db.dtnDao()

    companion object {
        const val DEFAULT_TTL_MS = 72L * 60L * 60L * 1000L // 72 hours
        const val DEFAULT_MAX_BYTES = 20L * 1024L * 1024L // 20 MB
        const val DEFAULT_MAX_BUNDLES = 5000

        fun priorityFor(packet: WireMeshPacket): Int {
            return when (packet.type.uppercase()) {
                "SOS" -> 4
                "GROUP_INVITE" -> 3
                "E2EE_DM", "GROUP_CHAT" -> 2
                "CHAT" -> 1
                else -> 0
            }
        }
    }

    @Synchronized
    fun enqueueBundle(
        packet: WireMeshPacket,
        ttlMs: Long = DEFAULT_TTL_MS,
        priority: Int = priorityFor(packet),
        maxBytes: Long = DEFAULT_MAX_BYTES,
        maxCount: Int = DEFAULT_MAX_BUNDLES
    ) {
        if (packet.packetId.isBlank() || packet.payload.isBlank()) return
        val wireJsonPlain = serializeWirePacket(packet)
        val storedWire = if (DatabaseFactory.requiresFieldEncryption()) {
            KeystoreMasterKeyManager.wrapString(appContext, wireJsonPlain)
        } else {
            wireJsonPlain
        }
        val now = System.currentTimeMillis()
        val expiresAt = if (ttlMs > 0L) now + ttlMs else 0L
        val entity = DtnBundleEntity(
            packetId = packet.packetId,
            srcId = packet.srcNodeId,
            dstId = packet.dstNodeId,
            packetType = packet.type,
            wireJson = storedWire,
            priority = priority,
            createdAtMs = packet.timestamp.takeIf { it > 0L } ?: now,
            expiresAtMs = expiresAt,
            hopCount = packet.hops,
            custodyAcked = false,
            sizeBytes = wireJsonPlain.toByteArray(Charsets.UTF_8).size
        )
        dtnDao.upsert(entity)
        pruneExpiredAndEnforceCap(maxBytes = maxBytes, maxCount = maxCount, nowMs = now)
    }

    @Synchronized
    fun getActiveBundles(nowMs: Long = System.currentTimeMillis()): List<WireMeshPacket> {
        dtnDao.pruneExpiredOrAcked(nowMs)
        return dtnDao.getActiveBundlesPrioritized(nowMs).mapNotNull { decryptEntity(it) }
    }

    @Synchronized
    fun buildBloomSyncPayload(nowMs: Long = System.currentTimeMillis()): String {
        dtnDao.pruneExpiredOrAcked(nowMs)
        val filter = DtnBloomFilter()
        for (bundle in dtnDao.getActiveBundlesPrioritized(nowMs)) {
            filter.add(bundle.packetId)
        }
        return filter.toBase64()
    }

    @Synchronized
    fun computeMissingBundlesForPeer(
        remoteBloomBase64: String,
        maxBytes: Int = 32_768,
        maxCount: Int = 24,
        nowMs: Long = System.currentTimeMillis()
    ): List<WireMeshPacket> {
        val remoteFilter = DtnBloomFilter.fromBase64(remoteBloomBase64) ?: DtnBloomFilter()
        dtnDao.pruneExpiredOrAcked(nowMs)
        val active = dtnDao.getActiveBundlesPrioritized(nowMs)
        val out = mutableListOf<WireMeshPacket>()
        var accumulatedBytes = 0
        for (entity in active) {
            if (remoteFilter.mightContain(entity.packetId)) continue
            val pkt = decryptEntity(entity) ?: continue
            val nextBytes = accumulatedBytes + entity.sizeBytes.coerceAtLeast(1)
            if (out.isNotEmpty() && nextBytes > maxBytes) break
            out.add(pkt)
            accumulatedBytes = nextBytes
            if (out.size >= maxCount) break
        }
        return out
    }

    @Synchronized
    fun handleCustodyAck(packetId: String): Boolean {
        if (packetId.isBlank()) return false
        val updated = dtnDao.markCustodyAcked(packetId)
        val deleted = dtnDao.deleteById(packetId)
        return updated > 0 || deleted > 0
    }

    @Synchronized
    fun getActiveCount(nowMs: Long = System.currentTimeMillis()): Int {
        dtnDao.pruneExpiredOrAcked(nowMs)
        return dtnDao.getTotalCount()
    }

    @Synchronized
    fun getTotalStoredBytes(): Long = dtnDao.getTotalStoredBytes()

    @Synchronized
    fun pruneExpiredAndEnforceCap(
        maxBytes: Long = DEFAULT_MAX_BYTES,
        maxCount: Int = DEFAULT_MAX_BUNDLES,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        var removed = dtnDao.pruneExpiredOrAcked(nowMs)
        var currentCount = dtnDao.getTotalCount()
        var currentBytes = dtnDao.getTotalStoredBytes()
        if (currentCount <= maxCount && currentBytes <= maxBytes) return removed

        val evictCandidates = dtnDao.getAllLowestPriorityFirst()
        for (candidate in evictCandidates) {
            if (currentCount <= maxCount && currentBytes <= maxBytes) break
            if (dtnDao.deleteById(candidate.packetId) > 0) {
                currentCount--
                currentBytes = (currentBytes - candidate.sizeBytes).coerceAtLeast(0L)
                removed++
            }
        }
        return removed
    }

    @Synchronized
    fun wipeAll() {
        dtnDao.deleteAll()
    }

    private fun decryptEntity(entity: DtnBundleEntity): WireMeshPacket? {
        val rawJson = if (KeystoreMasterKeyManager.isWrapped(entity.wireJson)) {
            KeystoreMasterKeyManager.unwrapString(appContext, entity.wireJson) ?: return null
        } else {
            entity.wireJson
        }
        return deserializeWirePacket(rawJson)
    }

    private fun serializeWirePacket(pkt: WireMeshPacket): String = JSONObject().apply {
        put("packetId", pkt.packetId)
        put("srcNodeId", pkt.srcNodeId)
        put("srcCallsign", pkt.srcCallsign)
        put("dstNodeId", pkt.dstNodeId)
        put("type", pkt.type)
        put("payload", pkt.payload)
        put("ttl", pkt.ttl)
        put("hops", pkt.hops)
        if (pkt.lat != null) put("lat", pkt.lat)
        if (pkt.lng != null) put("lng", pkt.lng)
        if (pkt.altitude != null) put("alt", pkt.altitude)
        if (pkt.accuracy != null) put("acc", pkt.accuracy.toDouble())
        if (pkt.battery != null) put("bat", pkt.battery)
        put("timestamp", pkt.timestamp)
        if (pkt.groupId.isNotBlank()) put("groupId", pkt.groupId)
        if (pkt.groupName.isNotBlank()) put("groupName", pkt.groupName)
        if (pkt.memberIdsCsv.isNotBlank()) put("memberIdsCsv", pkt.memberIdsCsv)
        if (pkt.senderPubKey.isNotBlank()) put("senderPubKey", pkt.senderPubKey)
        if (pkt.encryptedKeysJson.isNotBlank()) put("encryptedKeysJson", pkt.encryptedKeysJson)
        if (pkt.srcUsername.isNotBlank()) put("srcUsername", pkt.srcUsername)
        if (pkt.dstUsername.isNotBlank()) put("dstUsername", pkt.dstUsername)
        if (pkt.isGateway) put("isGateway", true)
    }.toString()

    private fun deserializeWirePacket(raw: String): WireMeshPacket? {
        return runCatching {
            val obj = JSONObject(raw)
            val id = obj.optString("packetId")
            if (id.isBlank()) return@runCatching null
            WireMeshPacket(
                packetId = id,
                srcNodeId = obj.optString("srcNodeId"),
                srcCallsign = obj.optString("srcCallsign"),
                dstNodeId = obj.optString("dstNodeId", "ALL"),
                type = obj.optString("type", "CHAT"),
                payload = obj.optString("payload"),
                ttl = obj.optInt("ttl", 7),
                hops = obj.optInt("hops", 0),
                lat = if (obj.has("lat") && !obj.isNull("lat")) obj.getDouble("lat") else null,
                lng = if (obj.has("lng") && !obj.isNull("lng")) obj.getDouble("lng") else null,
                accuracy = if (obj.has("acc") && !obj.isNull("acc")) obj.getDouble("acc").toFloat() else null,
                altitude = if (obj.has("alt") && !obj.isNull("alt")) obj.getDouble("alt") else null,
                battery = if (obj.has("bat") && !obj.isNull("bat")) obj.getInt("bat") else null,
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                sourceTransport = "DTN COURIER",
                groupId = obj.optString("groupId", ""),
                groupName = obj.optString("groupName", ""),
                memberIdsCsv = obj.optString("memberIdsCsv", ""),
                senderPubKey = obj.optString("senderPubKey", ""),
                encryptedKeysJson = obj.optString("encryptedKeysJson", ""),
                srcUsername = obj.optString("srcUsername", ""),
                dstUsername = obj.optString("dstUsername", ""),
                isGateway = obj.optBoolean("isGateway", false)
            )
        }.getOrNull()
    }
}
