// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.service

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.roundToInt

/**
 * PulseMesh Compact Binary Wire Protocol (`PMB2`) with Dual-Protocol Auto-Negotiation.
 *
 * Cuts BLE GATT / BLE L2CAP / Hotspot UDP / Wi-Fi Aware NAN payload sizes by ~60-70% compared to
 * legacy duplicated-key JSON envelopes, while transparently decoding both `PMB2` binary frames
 * (`magic = 0x50 0x4D 0x02`) and legacy `{...}` JSON frames for full backward compatibility with
 * PulseMesh v1.3.x nodes and Web clients.
 *
 * Binary Frame Layout (`PMB2`):
 * - [0..2]  Magic bytes: `0x50 ('P'), 0x4D ('M'), 0x02 (version 2)`
 * - [3]     Packet type code (1 byte; `0xFF` = custom UTF-8 string follows)
 * - [4]     Packed TTL (high nibble 0..15) | Hops (low nibble 0..15)
 * - [5..6]  Presence bitmask (`UShort`):
 *             bit 0 (0x0001): lat/lng (2x signed Int32 in 1e-7 micro-degrees = 8B)
 *             bit 1 (0x0002): altitude (Float32 = 4B)
 *             bit 2 (0x0004): accuracy (Float32 = 4B)
 *             bit 3 (0x0008): battery (1B unsigned 0..100)
 *             bit 4 (0x0010): groupId + groupName + memberIdsCsv
 *             bit 5 (0x0020): senderPubKey
 *             bit 6 (0x0040): encryptedKeysJson
 *             bit 7 (0x0080): srcUsername + dstUsername
 *             bit 8 (0x0100): isGateway = true
 *             bit 9 (0x0200): custom timestamp delta/epoch (Int64 = 8B)
 * - Core length-prefixed UTF-8 fields: packetId, srcNodeId, srcCallsign, dstNodeId, payload
 * - Optional fields in bitmask order.
 */
object CompactMeshProtocol {

    const val MAGIC_0: Byte = 0x50 // 'P'
    const val MAGIC_1: Byte = 0x4D // 'M'
    const val VERSION_2: Byte = 0x02

    /** Base64 ASCII line prefix for line-oriented transports (BLE L2CAP / TCP newline streams). */
    const val LINE_PREFIX = "PMB2:"

    private const val FLAG_COORDS = 0x0001
    private const val FLAG_ALT = 0x0002
    private const val FLAG_ACC = 0x0004
    private const val FLAG_BAT = 0x0008
    private const val FLAG_GROUP = 0x0010
    private const val FLAG_PUBKEY = 0x0020
    private const val FLAG_EKEYS = 0x0040
    private const val FLAG_USERNAMES = 0x0080
    private const val FLAG_GATEWAY = 0x0100
    private const val FLAG_TIMESTAMP = 0x0200

    private val TYPE_TO_CODE = mapOf(
        "CHAT" to 0x01,
        "E2EE_DM" to 0x02,
        "GROUP_CHAT" to 0x03,
        "GROUP_INVITE" to 0x04,
        "HEARTBEAT" to 0x05,
        "GPS_BEACON" to 0x06,
        "SOS" to 0x07,
        "SOS_CLEAR" to 0x08,
        "WAYPOINT" to 0x09,
        "CHAT_ACK" to 0x0A,
        "QUICK_STATUS" to 0x0B,
        "REACTION" to 0x0C,
        "CALL_RING" to 0x0D,
        "CALL_ACCEPT" to 0x0E,
        "CALL_DECLINE" to 0x0F,
        "CALL_END" to 0x10,
        "VOICE_PTT" to 0x11,
        "CALL_AUDIO" to 0x12,
        "VOICE_STREAM" to 0x13,
        "HOTSPOT_INFO" to 0x14,
        "HOTSPOT_AUTO_JOIN" to 0x15,
        "CALL_ACCEPT_ACK" to 0x16
    )

    private val CODE_TO_TYPE = TYPE_TO_CODE.entries.associate { (k, v) -> v to k }

    fun isCompactBinary(bytes: ByteArray): Boolean {
        return bytes.size >= 7 &&
            bytes[0] == MAGIC_0 &&
            bytes[1] == MAGIC_1 &&
            bytes[2] == VERSION_2
    }

    fun isCompactLine(line: String): Boolean {
        return line.startsWith(LINE_PREFIX)
    }

    fun encodeCompact(packet: WireMeshPacket): ByteArray {
        val baos = ByteArrayOutputStream(128)
        val out = DataOutputStream(baos)

        out.writeByte(MAGIC_0.toInt())
        out.writeByte(MAGIC_1.toInt())
        out.writeByte(VERSION_2.toInt())

        val upperType = packet.type.uppercase()
        val typeCode = TYPE_TO_CODE[upperType] ?: 0xFF
        out.writeByte(typeCode)

        val ttlNib = packet.ttl.coerceIn(0, 15)
        val hopsNib = packet.hops.coerceIn(0, 15)
        out.writeByte((ttlNib shl 4) or hopsNib)

        var flags = 0
        if (packet.lat != null && packet.lng != null) flags = flags or FLAG_COORDS
        if (packet.altitude != null) flags = flags or FLAG_ALT
        if (packet.accuracy != null) flags = flags or FLAG_ACC
        if (packet.battery != null) flags = flags or FLAG_BAT
        if (packet.groupId.isNotBlank() || packet.groupName.isNotBlank() || packet.memberIdsCsv.isNotBlank()) {
            flags = flags or FLAG_GROUP
        }
        if (packet.senderPubKey.isNotBlank()) flags = flags or FLAG_PUBKEY
        if (packet.encryptedKeysJson.isNotBlank()) flags = flags or FLAG_EKEYS
        if (packet.srcUsername.isNotBlank() || packet.dstUsername.isNotBlank()) flags = flags or FLAG_USERNAMES
        if (packet.isGateway) flags = flags or FLAG_GATEWAY
        if (packet.timestamp > 0L) flags = flags or FLAG_TIMESTAMP
        out.writeShort(flags)

        if (typeCode == 0xFF) {
            writeShortString(out, upperType)
        }
        writeShortString(out, packet.packetId)
        writeShortString(out, packet.srcNodeId)
        writeShortString(out, packet.srcCallsign)
        writeShortString(out, packet.dstNodeId)
        writeVarString(out, packet.payload)

        if ((flags and FLAG_COORDS) != 0) {
            out.writeInt(((packet.lat ?: 0.0) * 1e7).roundToInt())
            out.writeInt(((packet.lng ?: 0.0) * 1e7).roundToInt())
        }
        if ((flags and FLAG_ALT) != 0) {
            out.writeFloat((packet.altitude ?: 0.0).toFloat())
        }
        if ((flags and FLAG_ACC) != 0) {
            out.writeFloat(packet.accuracy ?: 0f)
        }
        if ((flags and FLAG_BAT) != 0) {
            out.writeByte((packet.battery ?: 100).coerceIn(0, 100))
        }
        if ((flags and FLAG_GROUP) != 0) {
            writeShortString(out, packet.groupId)
            writeShortString(out, packet.groupName)
            writeShortString(out, packet.memberIdsCsv)
        }
        if ((flags and FLAG_PUBKEY) != 0) {
            writeShortString(out, packet.senderPubKey)
        }
        if ((flags and FLAG_EKEYS) != 0) {
            writeVarString(out, packet.encryptedKeysJson)
        }
        if ((flags and FLAG_USERNAMES) != 0) {
            writeShortString(out, packet.srcUsername)
            writeShortString(out, packet.dstUsername)
        }
        if ((flags and FLAG_TIMESTAMP) != 0) {
            out.writeLong(packet.timestamp)
        }

        out.flush()
        return baos.toByteArray()
    }

    fun encodeCompactLine(packet: WireMeshPacket): String {
        val raw = encodeCompact(packet)
        val b64 = Base64.encodeToString(raw, Base64.NO_WRAP)
        return LINE_PREFIX + b64
    }

    fun decodeCompact(bytes: ByteArray, sourceTransport: String = "PMB2"): WireMeshPacket? {
        if (!isCompactBinary(bytes)) return null
        return runCatching {
            val inp = DataInputStream(ByteArrayInputStream(bytes))
            inp.skipBytes(3) // magic + version
            val typeCode = inp.readUnsignedByte()
            val ttlHops = inp.readUnsignedByte()
            val ttl = (ttlHops ushr 4) and 0x0F
            val hops = ttlHops and 0x0F
            val flags = inp.readUnsignedShort()

            val type = if (typeCode == 0xFF) {
                readShortString(inp)
            } else {
                CODE_TO_TYPE[typeCode] ?: "CHAT"
            }

            val packetId = readShortString(inp)
            val srcNodeId = readShortString(inp)
            val srcCallsign = readShortString(inp)
            val dstNodeId = readShortString(inp)
            val payload = readVarString(inp)

            var lat: Double? = null
            var lng: Double? = null
            if ((flags and FLAG_COORDS) != 0) {
                lat = inp.readInt() / 1e7
                lng = inp.readInt() / 1e7
            }
            val alt: Double? = if ((flags and FLAG_ALT) != 0) inp.readFloat().toDouble() else null
            val acc: Float? = if ((flags and FLAG_ACC) != 0) inp.readFloat() else null
            val bat: Int? = if ((flags and FLAG_BAT) != 0) inp.readUnsignedByte() else null
            var groupId = ""
            var groupName = ""
            var memberIdsCsv = ""
            if ((flags and FLAG_GROUP) != 0) {
                groupId = readShortString(inp)
                groupName = readShortString(inp)
                memberIdsCsv = readShortString(inp)
            }
            val senderPubKey = if ((flags and FLAG_PUBKEY) != 0) readShortString(inp) else ""
            val encryptedKeysJson = if ((flags and FLAG_EKEYS) != 0) readVarString(inp) else ""
            var srcUsername = ""
            var dstUsername = ""
            if ((flags and FLAG_USERNAMES) != 0) {
                srcUsername = readShortString(inp)
                dstUsername = readShortString(inp)
            }
            val isGateway = (flags and FLAG_GATEWAY) != 0
            val ts = if ((flags and FLAG_TIMESTAMP) != 0) inp.readLong() else System.currentTimeMillis()

            WireMeshPacket(
                packetId = packetId,
                srcNodeId = srcNodeId,
                srcCallsign = srcCallsign,
                dstNodeId = dstNodeId,
                type = type,
                payload = payload,
                ttl = ttl,
                hops = hops,
                lat = lat,
                lng = lng,
                accuracy = acc,
                altitude = alt,
                battery = bat,
                timestamp = ts,
                sourceTransport = sourceTransport,
                groupId = groupId,
                groupName = groupName,
                memberIdsCsv = memberIdsCsv,
                senderPubKey = senderPubKey,
                encryptedKeysJson = encryptedKeysJson,
                srcUsername = srcUsername,
                dstUsername = dstUsername,
                isGateway = isGateway
            )
        }.getOrNull()
    }

    /**
     * Auto-decodes either a `PMB2:` Base64 compact line, raw `PMB2` binary bytes, or legacy JSON `{...}`.
     */
    fun decodeLineAuto(line: String, sourceTransport: String = ""): WireMeshPacket? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith(LINE_PREFIX)) {
            val raw = runCatching {
                Base64.decode(trimmed.removePrefix(LINE_PREFIX), Base64.DEFAULT)
            }.getOrNull() ?: return null
            return decodeCompact(raw, sourceTransport = sourceTransport.ifBlank { "PMB2" })
        }
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return decodeLegacyJson(trimmed, sourceTransport)
        }
        return null
    }

    fun decodeBytesAuto(bytes: ByteArray, sourceTransport: String = ""): WireMeshPacket? {
        if (isCompactBinary(bytes)) {
            return decodeCompact(bytes, sourceTransport = sourceTransport.ifBlank { "PMB2" })
        }
        val str = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
        return decodeLineAuto(str, sourceTransport)
    }

    fun decodeLegacyJson(jsonStr: String, sourceTransport: String = ""): WireMeshPacket? {
        return runCatching {
            val obj = JSONObject(jsonStr)
            val id = obj.optString("id").ifBlank { obj.optString("packetId") }
            val src = obj.optString("srcId").ifBlank { obj.optString("src") }
            val type = obj.optString("type").ifBlank { obj.optString("t", "CHAT") }
            if (id.isBlank() || src.isBlank()) return null
            WireMeshPacket(
                packetId = id,
                srcNodeId = src,
                srcCallsign = obj.optString("srcName").ifBlank { obj.optString("cs", src) },
                dstNodeId = obj.optString("dstId").ifBlank { obj.optString("dst", "ALL") },
                type = type,
                payload = obj.optString("payload").ifBlank { obj.optString("p") },
                ttl = obj.optInt("ttl", 7),
                hops = obj.optInt("hops", 0),
                lat = if (obj.has("lat") && !obj.isNull("lat")) obj.optDouble("lat") else null,
                lng = if (obj.has("lng") && !obj.isNull("lng")) obj.optDouble("lng") else null,
                accuracy = if (obj.has("acc") && !obj.isNull("acc")) obj.optDouble("acc").toFloat() else null,
                altitude = if (obj.has("alt") && !obj.isNull("alt")) obj.optDouble("alt") else null,
                battery = if (obj.has("bat") && !obj.isNull("bat")) obj.optInt("bat") else null,
                timestamp = obj.optLong("ts", System.currentTimeMillis()),
                sourceTransport = sourceTransport,
                groupId = obj.optString("groupId", ""),
                groupName = obj.optString("groupName", ""),
                memberIdsCsv = obj.optString("memberIds", ""),
                senderPubKey = obj.optString("pk", ""),
                encryptedKeysJson = obj.optString("ekeys", ""),
                srcUsername = obj.optString("su", ""),
                dstUsername = obj.optString("du", ""),
                isGateway = obj.optBoolean("gw", false)
            )
        }.getOrNull()
    }

    /**
     * Converts a `WireMeshPacket` back to standard JSON so legacy v1.3.x handlers (`handleIncomingJsonLine`)
     * can ingest decoded `PMB2` packets without duplicating routing logic.
     */
    fun toCanonicalJson(packet: WireMeshPacket): String {
        return JSONObject().apply {
            put("id", packet.packetId)
            put("type", packet.type)
            put("srcId", packet.srcNodeId)
            put("srcName", packet.srcCallsign)
            put("dstId", packet.dstNodeId)
            put("ttl", packet.ttl)
            put("hops", packet.hops)
            put("payload", packet.payload)
            packet.lat?.let { put("lat", it) }
            packet.lng?.let { put("lng", it) }
            packet.altitude?.let { put("alt", it) }
            packet.accuracy?.let { put("acc", it.toDouble()) }
            packet.battery?.let { put("bat", it) }
            if (packet.groupId.isNotBlank()) put("groupId", packet.groupId)
            if (packet.groupName.isNotBlank()) put("groupName", packet.groupName)
            if (packet.memberIdsCsv.isNotBlank()) put("memberIds", packet.memberIdsCsv)
            if (packet.senderPubKey.isNotBlank()) put("pk", packet.senderPubKey)
            if (packet.encryptedKeysJson.isNotBlank()) put("ekeys", packet.encryptedKeysJson)
            if (packet.srcUsername.isNotBlank()) put("su", packet.srcUsername)
            if (packet.dstUsername.isNotBlank()) put("du", packet.dstUsername)
            if (packet.isGateway) put("gw", true)
            put("ts", packet.timestamp)
            put("pmv", 2)
        }.toString()
    }

    private fun writeShortString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val len = bytes.size.coerceAtMost(65535)
        out.writeShort(len)
        if (len > 0) out.write(bytes, 0, len)
    }

    private fun readShortString(inp: DataInputStream): String {
        val len = inp.readUnsignedShort()
        if (len == 0) return ""
        val buf = ByteArray(len)
        inp.readFully(buf)
        return String(buf, Charsets.UTF_8)
    }

    private fun writeVarString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        if (bytes.isNotEmpty()) out.write(bytes)
    }

    private fun readVarString(inp: DataInputStream): String {
        val len = inp.readInt()
        if (len <= 0 || len > 4 * 1024 * 1024) return ""
        val buf = ByteArray(len)
        inp.readFully(buf)
        return String(buf, Charsets.UTF_8)
    }
}
