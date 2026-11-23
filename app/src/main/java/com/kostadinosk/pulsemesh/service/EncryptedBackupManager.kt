// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.service

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class PulseMeshBackupPayload(
    val formatVersion: Int = 2,
    val exportedAtEpochMs: Long = System.currentTimeMillis(),
    val operatorCallsign: String,
    val nodeId: String,
    val claimedUsername: String,
    val x25519PublicKey: String,
    val x25519PrivateKey: String,
    val iceMedicalInfo: String,
    val meshChannel: String,
    val contactsJson: String = "[]",
    val groupsJson: String = "[]",
    val recentMessagesJson: String = "[]"
)

/**
 * PulseMesh v2.0 Encrypted Offline Backup (.pmbackup):
 * - 100% offline migration of identity keys, contacts, groups, ICE card, and recent chat history
 * - PBKDF2-HMAC-SHA256 (600,000 iterations) + AES-256-GCM with AAD "PM_BACKUP_V2"
 * - Envelope format: PMBK2:<base64(salt16 || iv12 || ciphertext+tag)>
 */
object EncryptedBackupManager {
    const val MAGIC_PREFIX = "PMBK2:"
    private const val PBKDF2_ITERATIONS = 600_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private val AAD_BYTES = "PM_BACKUP_V2".toByteArray(Charsets.UTF_8)

    fun exportEncryptedBackup(
        payload: PulseMeshBackupPayload,
        passphrase: String,
        iterations: Int = PBKDF2_ITERATIONS
    ): String {
        require(passphrase.length >= 8) { "Backup passphrase must be at least 8 characters." }
        val json = JSONObject().apply {
            put("v", payload.formatVersion)
            put("exportedAt", payload.exportedAtEpochMs)
            put("callsign", payload.operatorCallsign)
            put("nodeId", payload.nodeId)
            put("username", payload.claimedUsername)
            put("pub", payload.x25519PublicKey)
            put("priv", payload.x25519PrivateKey)
            put("ice", payload.iceMedicalInfo)
            put("channel", payload.meshChannel)
            put("contacts", runCatching { JSONArray(payload.contactsJson) }.getOrElse { JSONArray() })
            put("groups", runCatching { JSONArray(payload.groupsJson) }.getOrElse { JSONArray() })
            put("messages", runCatching { JSONArray(payload.recentMessagesJson) }.getOrElse { JSONArray() })
        }.toString().toByteArray(Charsets.UTF_8)

        val rng = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rng.nextBytes(it) }
        val key = deriveAesKey(passphrase, salt, iterations)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(AAD_BYTES)
        val ct = cipher.doFinal(json)

        val out = ByteArray(SALT_LEN + IV_LEN + ct.size)
        System.arraycopy(salt, 0, out, 0, SALT_LEN)
        System.arraycopy(iv, 0, out, SALT_LEN, IV_LEN)
        System.arraycopy(ct, 0, out, SALT_LEN + IV_LEN, ct.size)

        return MAGIC_PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun importEncryptedBackup(
        encodedEnvelope: String,
        passphrase: String,
        iterations: Int = PBKDF2_ITERATIONS
    ): Result<PulseMeshBackupPayload> = runCatching {
        val trimmed = encodedEnvelope.trim()
        require(trimmed.startsWith(MAGIC_PREFIX)) { "Invalid .pmbackup header (expected PMBK2:)." }
        val raw = Base64.decode(trimmed.removePrefix(MAGIC_PREFIX), Base64.DEFAULT)
        require(raw.size > SALT_LEN + IV_LEN + 16) { "Truncated .pmbackup payload." }

        val salt = raw.copyOfRange(0, SALT_LEN)
        val iv = raw.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val ct = raw.copyOfRange(SALT_LEN + IV_LEN, raw.size)
        val key = deriveAesKey(passphrase, salt, iterations)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(AAD_BYTES)
        val plain = cipher.doFinal(ct).toString(Charsets.UTF_8)
        val obj = JSONObject(plain)

        PulseMeshBackupPayload(
            formatVersion = obj.optInt("v", 2),
            exportedAtEpochMs = obj.optLong("exportedAt", System.currentTimeMillis()),
            operatorCallsign = obj.optString("callsign", "Operator"),
            nodeId = obj.optString("nodeId", ""),
            claimedUsername = obj.optString("username", ""),
            x25519PublicKey = obj.optString("pub", ""),
            x25519PrivateKey = obj.optString("priv", ""),
            iceMedicalInfo = obj.optString("ice", ""),
            meshChannel = obj.optString("channel", "pulsemesh_live_primary"),
            contactsJson = obj.optJSONArray("contacts")?.toString() ?: "[]",
            groupsJson = obj.optJSONArray("groups")?.toString() ?: "[]",
            recentMessagesJson = obj.optJSONArray("messages")?.toString() ?: "[]"
        )
    }

    private fun deriveAesKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }
}
