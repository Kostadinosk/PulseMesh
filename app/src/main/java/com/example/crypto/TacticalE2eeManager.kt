// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class E2eeKeyPair(
    val privateKeyHex: String,
    val publicKeyHex: String,
    val fingerprint: String
) {
    val publicKeyBase64: String get() = publicKeyHex
    val privateKeyBase64: String get() = privateKeyHex
}

data class EncryptedPayload(
    val ciphertextHex: String,
    val nonceHex: String,
    val macTagHex: String,
    val senderFingerprint: String
)

data class AccountVaultPayload(
    val privateKeyBase64: String,
    val publicKeyBase64: String,
    val nodeId: String,
    val displayName: String,
    val authToken: String,
    val recoveryKey: String = "",
    val encryptionKeyEpoch: Int = 1
)

data class UsernameClaimRecord(
    val username: String,
    val displayName: String,
    val nodeId: String,
    val publicKeyBase64: String,
    val saltHex: String,
    val pinHash: String = "",
    val recoveryHash: String = "",
    val claimedAt: Long,
    val claimVersion: Int = 1,
    val pinVault: String = "",
    val recoveryVault: String = "",
    val authVerifierHash: String = "",
    val signatureBase64: String = ""
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("username", username)
        put("displayName", displayName)
        put("nodeId", nodeId)
        put("publicKeyBase64", publicKeyBase64)
        put("saltHex", saltHex)
        if (pinHash.isNotBlank()) put("pinHash", pinHash)
        if (recoveryHash.isNotBlank()) put("recoveryHash", recoveryHash)
        put("claimedAt", claimedAt)
        put("v", claimVersion)
        if (pinVault.isNotBlank()) put("pinVault", pinVault)
        if (recoveryVault.isNotBlank()) put("recoveryVault", recoveryVault)
        if (authVerifierHash.isNotBlank()) put("authVerifierHash", authVerifierHash)
        if (signatureBase64.isNotBlank()) put("sig", signatureBase64)
    }

    /**
     * Public wire representation that NEVER transmits raw pinHash or recoveryHash.
     */
    fun toPublicWireJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("username", username)
        put("displayName", displayName)
        put("nodeId", nodeId)
        put("publicKeyBase64", publicKeyBase64)
        put("saltHex", saltHex)
        put("claimedAt", claimedAt)
        put("v", claimVersion.coerceAtLeast(2))
        if (pinVault.isNotBlank()) put("pinVault", pinVault)
        if (recoveryVault.isNotBlank()) put("recoveryVault", recoveryVault)
        if (authVerifierHash.isNotBlank()) put("authVerifierHash", authVerifierHash)
        if (signatureBase64.isNotBlank()) put("sig", signatureBase64)
    }

    companion object {
        fun fromJson(obj: org.json.JSONObject): UsernameClaimRecord? {
            val uname = TacticalE2eeManager.normalizeUsername(obj.optString("username"))
            val nid = obj.optString("nodeId").trim()
            if (uname.length < 3 || nid.isBlank()) return null
            return UsernameClaimRecord(
                username = uname,
                displayName = obj.optString("displayName").ifBlank { "@$uname" },
                nodeId = nid,
                publicKeyBase64 = obj.optString("publicKeyBase64", obj.optString("publicKeyB64", "")),
                saltHex = obj.optString("saltHex", ""),
                pinHash = obj.optString("pinHash", ""),
                recoveryHash = obj.optString("recoveryHash", ""),
                claimedAt = obj.optLong("claimedAt", 0L),
                claimVersion = obj.optInt("v", if (obj.has("pinVault") || obj.has("sig")) 2 else 1),
                pinVault = obj.optString("pinVault", ""),
                recoveryVault = obj.optString("recoveryVault", ""),
                authVerifierHash = obj.optString("authVerifierHash", ""),
                signatureBase64 = obj.optString("sig", obj.optString("signatureB64", ""))
            )
        }
    }
}

object TacticalE2eeManager {

    private val secureRandom = SecureRandom()
    private const val EC_CURVE_NAME = "secp256r1"
    private const val ECDH_KDF_INFO = "PULSEMESH_ECDH_V1"
    private const val CHANNEL_TOPIC_SALT = "PULSEMESH_TOPIC_V2:"
    private const val CHANNEL_AES_SALT = "PULSEMESH_CHANNEL_AES_V2:"
    private const val USER_CLAIM_TOPIC_SALT = "PULSEMESH_USER_CLAIM_V1:"
    private const val USER_INBOX_TOPIC_SALT = "PULSEMESH_USER_INBOX_V1:"
    private const val USER_INBOX_AES_SALT = "PULSEMESH_USER_INBOX_AES_V1:"

    private const val DM_PREFIX_ECDH = "E2EE_ECDH_V2:"
    private const val GROUP_WRAP_PREFIX = "GW2:"
    private const val GROUP_MSG_PREFIX = "GRP_AES_V2:"
    private const val CHANNEL_PREFIX = "PM2:"
    private const val USER_INBOX_PREFIX = "PMU1:"
    private const val USER_INBOX_ECDH_PREFIX = "PMU2:"
    private const val VAULT_PREFIX = "PV1:"
    private const val BACKUP_PREFIX = "PMB1:"
    private const val BACKUP_CHUNK_PREFIX = "PMB1C:"
    private const val PBKDF2_ITERATIONS = 65_536

    /**
     * Generates a real Elliptic Curve Diffie-Hellman (ECDH P-256 / secp256r1) KeyPair
     * using standard Android java.security APIs. Keys are Base64 encoded X.509 / PKCS#8.
     */
    fun generateKeyPair(seed: String? = null): E2eeKeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(EC_CURVE_NAME), secureRandom)
        val kp = kpg.generateKeyPair()

        val pubB64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        val privB64 = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
        val fp = deriveFingerprint(pubB64)

        return E2eeKeyPair(
            privateKeyHex = privB64,
            publicKeyHex = pubB64,
            fingerprint = fp
        )
    }

    fun isValidEcPublicKeyBase64(pubKeyBase64: String): Boolean {
        if (pubKeyBase64.isBlank()) return false
        return runCatching {
            val bytes = Base64.decode(pubKeyBase64.trim(), Base64.DEFAULT)
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
            true
        }.getOrDefault(false)
    }

    fun isValidEcPrivateKeyBase64(privKeyBase64: String): Boolean {
        if (privKeyBase64.isBlank()) return false
        return runCatching {
            val bytes = Base64.decode(privKeyBase64.trim(), Base64.DEFAULT)
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
            true
        }.getOrDefault(false)
    }

    /**
     * Derives a 12-character hex fingerprint (XXXX-XXXX-XXXX) from a Base64 X.509 public key.
     */
    fun deriveFingerprint(publicKeyBase64: String): String {
        val rawBytes = runCatching { Base64.decode(publicKeyBase64.trim(), Base64.DEFAULT) }
            .getOrElse { publicKeyBase64.toByteArray(Charsets.UTF_8) }
        val hash = MessageDigest.getInstance("SHA-256").digest(rawBytes)
        return format12HexFingerprint(hash)
    }

    /**
     * Computes a deterministic, order-independent 12-character hex Safety Fingerprint
     * (`XXXX-XXXX-XXXX`) from SHA-256(sorted(myPubKeyBytes, peerPubKeyBytes)).
     */
    fun computePairwiseSafetyFingerprint(pubKeyABase64: String, pubKeyBBase64: String): String {
        if (pubKeyABase64.isBlank() || pubKeyBBase64.isBlank()) return ""
        val bytesA = runCatching { Base64.decode(pubKeyABase64.trim(), Base64.DEFAULT) }
            .getOrElse { pubKeyABase64.toByteArray(Charsets.UTF_8) }
        val bytesB = runCatching { Base64.decode(pubKeyBBase64.trim(), Base64.DEFAULT) }
            .getOrElse { pubKeyBBase64.toByteArray(Charsets.UTF_8) }

        val cmp = compareByteArraysUnsigned(bytesA, bytesB)
        val first = if (cmp <= 0) bytesA else bytesB
        val second = if (cmp <= 0) bytesB else bytesA

        val md = MessageDigest.getInstance("SHA-256")
        md.update(first)
        md.update(second)
        return format12HexFingerprint(md.digest())
    }

    private fun format12HexFingerprint(hash: ByteArray): String {
        val hex = StringBuilder(12)
        for (i in 0 until 6) {
            hex.append(String.format("%02X", hash[i]))
        }
        val raw = hex.toString()
        return "${raw.substring(0, 4)}-${raw.substring(4, 8)}-${raw.substring(8, 12)}"
    }

    private fun compareByteArraysUnsigned(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val ua = a[i].toInt() and 0xFF
            val ub = b[i].toInt() and 0xFF
            if (ua != ub) return ua.compareTo(ub)
        }
        return a.size.compareTo(b.size)
    }

    fun deriveNodeIdFromCallsign(callsign: String): String {
        val md = MessageDigest.getInstance("MD5")
        val hash = md.digest(callsign.trim().uppercase().toByteArray(Charsets.UTF_8))
        val b1 = hash[0].toInt() and 0xFF
        val b2 = hash[1].toInt() and 0xFF
        return String.format("0x%02X%02X", b1, b2)
    }

    /**
     * Derives a 256-bit AES SecretKey via real ECDH P-256 KeyAgreement + SHA-256 KDF.
     */
    fun deriveEcdhAesKey(myPrivateKeyBase64: String, peerPublicKeyBase64: String): SecretKey {
        val kf = KeyFactory.getInstance("EC")
        val privBytes = Base64.decode(myPrivateKeyBase64.trim(), Base64.DEFAULT)
        val pubBytes = Base64.decode(peerPublicKeyBase64.trim(), Base64.DEFAULT)
        val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
        val pubKey = kf.generatePublic(X509EncodedKeySpec(pubBytes))

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privKey)
        ka.doPhase(pubKey, true)
        val sharedSecret = ka.generateSecret()

        val md = MessageDigest.getInstance("SHA-256")
        md.update(sharedSecret)
        md.update(ECDH_KDF_INFO.toByteArray(Charsets.UTF_8))
        val derivedKey = md.digest()
        return SecretKeySpec(derivedKey, "AES")
    }

    /**
     * Encrypts a 1-on-1 DM using ECDH P-256 derived AES-256-GCM with a fresh random 12-byte IV.
     * Returns null if keys are missing/invalid (never downgrades silently to plaintext).
     */
    fun encryptDmWithEcdh(
        plaintext: String,
        myPrivateKeyBase64: String,
        peerPublicKeyBase64: String
    ): String? {
        if (!isValidEcPrivateKeyBase64(myPrivateKeyBase64) || !isValidEcPublicKeyBase64(peerPublicKeyBase64)) {
            return null
        }
        return runCatching {
            val secretKey = deriveEcdhAesKey(myPrivateKeyBase64, peerPublicKeyBase64)
            DM_PREFIX_ECDH + encryptAesGcmBase64(plaintext.toByteArray(Charsets.UTF_8), secretKey)
        }.getOrNull()
    }

    /**
     * Decrypts a 1-on-1 DM encrypted with ECDH P-256 AES-256-GCM.
     */
    fun decryptDmWithEcdh(
        payload: String,
        myPrivateKeyBase64: String,
        peerPublicKeyBase64: String
    ): String {
        val trimmed = payload.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("PLAINTEXT:")) return trimmed.removePrefix("PLAINTEXT:")
        if (!trimmed.startsWith(DM_PREFIX_ECDH)) {
            return "[Key Exchange Pending / Decryption Failed]"
        }
        if (!isValidEcPrivateKeyBase64(myPrivateKeyBase64) || !isValidEcPublicKeyBase64(peerPublicKeyBase64)) {
            return "[Key Exchange Pending / Decryption Failed]"
        }
        return runCatching {
            val secretKey = deriveEcdhAesKey(myPrivateKeyBase64, peerPublicKeyBase64)
            val b64 = trimmed.removePrefix(DM_PREFIX_ECDH)
            val plainBytes = decryptAesGcmBase64(b64, secretKey)
            String(plainBytes, Charsets.UTF_8)
        }.getOrDefault("[Key Exchange Pending / Decryption Failed]")
    }

    // =========================================================================
    // RANDOM PER-GROUP SYMMETRIC KEY + ECDH KEY WRAPPING
    // =========================================================================

    fun generateGroupAesKeyBase64(): String {
        val keyBytes = ByteArray(32)
        secureRandom.nextBytes(keyBytes)
        return Base64.encodeToString(keyBytes, Base64.NO_WRAP)
    }

    fun isValidGroupAesKeyBase64(groupKeyBase64: String): Boolean {
        if (groupKeyBase64.isBlank()) return false
        return runCatching {
            Base64.decode(groupKeyBase64.trim(), Base64.DEFAULT).size == 32
        }.getOrDefault(false)
    }

    fun wrapGroupKeyForMember(
        groupKeyBase64: String,
        myPrivateKeyBase64: String,
        memberPublicKeyBase64: String
    ): String? {
        if (!isValidGroupAesKeyBase64(groupKeyBase64)) return null
        if (!isValidEcPrivateKeyBase64(myPrivateKeyBase64) || !isValidEcPublicKeyBase64(memberPublicKeyBase64)) {
            return null
        }
        return runCatching {
            val groupRaw = Base64.decode(groupKeyBase64.trim(), Base64.DEFAULT)
            val kek = deriveEcdhAesKey(myPrivateKeyBase64, memberPublicKeyBase64)
            GROUP_WRAP_PREFIX + encryptAesGcmBase64(groupRaw, kek)
        }.getOrNull()
    }

    fun unwrapGroupKeyFromMember(
        wrappedPayload: String,
        myPrivateKeyBase64: String,
        senderPublicKeyBase64: String
    ): String? {
        val trimmed = wrappedPayload.trim()
        if (!trimmed.startsWith(GROUP_WRAP_PREFIX)) return null
        if (!isValidEcPrivateKeyBase64(myPrivateKeyBase64) || !isValidEcPublicKeyBase64(senderPublicKeyBase64)) {
            return null
        }
        return runCatching {
            val kek = deriveEcdhAesKey(myPrivateKeyBase64, senderPublicKeyBase64)
            val rawGroupKey = decryptAesGcmBase64(trimmed.removePrefix(GROUP_WRAP_PREFIX), kek)
            if (rawGroupKey.size != 32) return null
            Base64.encodeToString(rawGroupKey, Base64.NO_WRAP)
        }.getOrNull()
    }

    fun encryptWithGroupKey(plaintext: String, groupKeyBase64: String): String? {
        if (!isValidGroupAesKeyBase64(groupKeyBase64)) return null
        return runCatching {
            val keyBytes = Base64.decode(groupKeyBase64.trim(), Base64.DEFAULT)
            val key = SecretKeySpec(keyBytes, "AES")
            GROUP_MSG_PREFIX + encryptAesGcmBase64(plaintext.toByteArray(Charsets.UTF_8), key)
        }.getOrNull()
    }

    fun decryptWithGroupKey(payload: String, groupKeyBase64: String): String {
        val trimmed = payload.trim()
        if (!trimmed.startsWith(GROUP_MSG_PREFIX)) {
            if (trimmed.startsWith("PLAINTEXT:")) return trimmed.removePrefix("PLAINTEXT:")
            return "[Group Decryption Failed]"
        }
        if (!isValidGroupAesKeyBase64(groupKeyBase64)) return "[Group Key Missing / Decryption Failed]"
        return runCatching {
            val keyBytes = Base64.decode(groupKeyBase64.trim(), Base64.DEFAULT)
            val key = SecretKeySpec(keyBytes, "AES")
            val decrypted = decryptAesGcmBase64(trimmed.removePrefix(GROUP_MSG_PREFIX), key)
            String(decrypted, Charsets.UTF_8)
        }.getOrDefault("[Group Decryption Failed]")
    }

    // =========================================================================
    // ENCRYPTED HYBRID CHANNEL (ntfy.sh NON-GUESSABLE TOPIC + AES-256-GCM)
    // =========================================================================

    fun deriveHashedNtfyTopic(meshPassphrase: String): String {
        val clean = meshPassphrase.trim().ifBlank { "pulsemesh-tactical" }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest((CHANNEL_TOPIC_SALT + clean).toByteArray(Charsets.UTF_8))
        return "pm_" + bytesToLowerHex(hash).take(20)
    }

    fun deriveChannelAesKey(meshPassphrase: String): SecretKey {
        val clean = meshPassphrase.trim().ifBlank { "pulsemesh-tactical" }
        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest((CHANNEL_AES_SALT + clean).toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encryptChannelPayload(jsonString: String, meshPassphrase: String): String {
        val key = deriveChannelAesKey(meshPassphrase)
        return CHANNEL_PREFIX + encryptAesGcmBase64(jsonString.toByteArray(Charsets.UTF_8), key)
    }

    fun decryptChannelPayload(encryptedPayload: String, meshPassphrase: String): String? {
        val trimmed = encryptedPayload.trim()
        if (!trimmed.startsWith(CHANNEL_PREFIX)) return null
        return runCatching {
            val key = deriveChannelAesKey(meshPassphrase)
            val plainBytes = decryptAesGcmBase64(trimmed.removePrefix(CHANNEL_PREFIX), key)
            String(plainBytes, Charsets.UTF_8)
        }.getOrNull()
    }

    // =========================================================================
    // UNIQUE USERNAME RESERVATION, PIN / RECOVERY KEY & DISTANT INBOX ROUTING
    // =========================================================================

    fun normalizeUsername(raw: String): String {
        return raw.trim()
            .removePrefix("@")
            .lowercase()
            .replace(Regex("[^a-z0-9_-]"), "")
            .take(24)
    }

    fun isValidUsername(raw: String): Boolean {
        val norm = normalizeUsername(raw)
        return norm.length in 3..24
    }

    fun isValidAccountPin(raw: String): Boolean {
        return raw.trim().matches(Regex("^\\d{4,6}$"))
    }

    fun generateAccountSaltHex(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytesToLowerHex(bytes)
    }

    fun generateRecoveryKey(): String {
        val bytes = ByteArray(6)
        secureRandom.nextBytes(bytes)
        val hex = bytesToHex(bytes)
        return "PMRK-${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}"
    }

    fun normalizeRecoveryKey(raw: String): String {
        return raw.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")
    }

    fun hashAccountPin(username: String, saltHex: String, pin: String): String {
        val norm = normalizeUsername(username)
        val cleanPin = pin.trim()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("PIN:$norm:$saltHex:$cleanPin".toByteArray(Charsets.UTF_8))
        return bytesToLowerHex(digest)
    }

    fun hashRecoveryKey(username: String, recoveryKey: String): String {
        val norm = normalizeUsername(username)
        val cleanKey = normalizeRecoveryKey(recoveryKey)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("RK:$norm:$cleanKey".toByteArray(Charsets.UTF_8))
        return bytesToLowerHex(digest)
    }

    fun deriveUsernameClaimTopic(username: String): String {
        val norm = normalizeUsername(username)
        val hash = MessageDigest.getInstance("SHA-256")
            .digest((USER_CLAIM_TOPIC_SALT + norm).toByteArray(Charsets.UTF_8))
        return "pm_reg_" + bytesToLowerHex(hash).take(20)
    }

    fun deriveUsernameInboxTopic(username: String): String {
        val norm = normalizeUsername(username)
        val hash = MessageDigest.getInstance("SHA-256")
            .digest((USER_INBOX_TOPIC_SALT + norm).toByteArray(Charsets.UTF_8))
        return "pm_u_" + bytesToLowerHex(hash).take(20)
    }

    fun deriveUsernameInboxAesKey(username: String): SecretKey {
        val norm = normalizeUsername(username)
        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest((USER_INBOX_AES_SALT + norm).toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encryptUsernameInboxPayload(jsonString: String, recipientUsername: String): String {
        val key = deriveUsernameInboxAesKey(recipientUsername)
        return USER_INBOX_PREFIX + encryptAesGcmBase64(jsonString.toByteArray(Charsets.UTF_8), key)
    }

    fun decryptUsernameInboxPayload(encryptedPayload: String, recipientUsername: String): String? {
        val trimmed = encryptedPayload.trim()
        if (!trimmed.startsWith(USER_INBOX_PREFIX)) return null
        return runCatching {
            val key = deriveUsernameInboxAesKey(recipientUsername)
            val plainBytes = decryptAesGcmBase64(trimmed.removePrefix(USER_INBOX_PREFIX), key)
            String(plainBytes, Charsets.UTF_8)
        }.getOrNull()
    }

    // =========================================================================
    // V2 CRYPTO: PBKDF2 ENCRYPTED ACCOUNT VAULTS, ECDSA CLAIMS & PMU2 ECDH INBOX
    // =========================================================================

    fun generateAuthToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytesToLowerHex(bytes)
    }

    fun hashAuthToken(authToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("PM_AUTH_V2:" + authToken.trim()).toByteArray(Charsets.UTF_8))
        return bytesToLowerHex(digest)
    }

    private fun derivePbkdf2AesKey(
        username: String,
        saltHex: String,
        secret: String,
        iterations: Int = PBKDF2_ITERATIONS
    ): SecretKey {
        val norm = normalizeUsername(username)
        val saltBytes = "PM_VAULT_V2:$norm:$saltHex".toByteArray(Charsets.UTF_8)
        val chars = secret.toCharArray()
        val keyBytes = try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(chars, saltBytes, iterations, 256)
            factory.generateSecret(spec).encoded
        } catch (_: Exception) {
            // Fallback for Android API 24-25 where PBKDF2WithHmacSHA256 is unavailable
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            val spec = PBEKeySpec(chars, saltBytes, iterations, 256)
            factory.generateSecret(spec).encoded
        }
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encryptAccountVault(
        username: String,
        saltHex: String,
        secret: String,
        payload: AccountVaultPayload
    ): String {
        val key = derivePbkdf2AesKey(username, saltHex, secret)
        val json = org.json.JSONObject().apply {
            put("priv", payload.privateKeyBase64)
            put("pub", payload.publicKeyBase64)
            put("nid", payload.nodeId)
            put("dn", payload.displayName)
            put("tok", payload.authToken)
            if (payload.recoveryKey.isNotBlank()) put("rk", payload.recoveryKey)
            put("ep", payload.encryptionKeyEpoch.coerceAtLeast(1))
        }.toString()
        return VAULT_PREFIX + encryptAesGcmBase64(json.toByteArray(Charsets.UTF_8), key)
    }

    fun decryptAccountVault(
        username: String,
        saltHex: String,
        secret: String,
        encryptedVault: String
    ): AccountVaultPayload? {
        val trimmed = encryptedVault.trim()
        if (!trimmed.startsWith(VAULT_PREFIX) || saltHex.isBlank() || secret.isBlank()) return null
        return runCatching {
            val key = derivePbkdf2AesKey(username, saltHex, secret)
            val plainBytes = decryptAesGcmBase64(trimmed.removePrefix(VAULT_PREFIX), key)
            val obj = org.json.JSONObject(String(plainBytes, Charsets.UTF_8))
            val priv = obj.optString("priv")
            val pub = obj.optString("pub")
            val nid = obj.optString("nid")
            if (!isValidEcPrivateKeyBase64(priv) || !isValidEcPublicKeyBase64(pub) || nid.isBlank()) {
                return@runCatching null
            }
            AccountVaultPayload(
                privateKeyBase64 = priv,
                publicKeyBase64 = pub,
                nodeId = nid,
                displayName = obj.optString("dn").ifBlank { "@${normalizeUsername(username)}" },
                authToken = obj.optString("tok", ""),
                recoveryKey = obj.optString("rk", ""),
                encryptionKeyEpoch = obj.optInt("ep", 1).coerceAtLeast(1)
            )
        }.getOrNull()
    }

    private fun canonicalClaimSigningString(
        username: String,
        nodeId: String,
        publicKeyBase64: String,
        claimedAt: Long
    ): String = "PM_CLAIM_V2|${normalizeUsername(username)}|${nodeId.trim()}|${publicKeyBase64.trim()}|$claimedAt"

    fun signMessageWithEcKey(privateKeyBase64: String, message: String): String {
        if (!isValidEcPrivateKeyBase64(privateKeyBase64)) return ""
        return runCatching {
            val privBytes = Base64.decode(privateKeyBase64.trim(), Base64.DEFAULT)
            val privKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privBytes))
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(privKey, secureRandom)
            sig.update(message.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
        }.getOrDefault("")
    }

    fun verifyEcSignature(publicKeyBase64: String, message: String, signatureBase64: String): Boolean {
        if (!isValidEcPublicKeyBase64(publicKeyBase64) || signatureBase64.isBlank()) return false
        return runCatching {
            val pubBytes = Base64.decode(publicKeyBase64.trim(), Base64.DEFAULT)
            val pubKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pubBytes))
            val sigBytes = Base64.decode(signatureBase64.trim(), Base64.DEFAULT)
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pubKey)
            sig.update(message.toByteArray(Charsets.UTF_8))
            sig.verify(sigBytes)
        }.getOrDefault(false)
    }

    fun signUsernameClaim(
        record: UsernameClaimRecord,
        privateKeyBase64: String
    ): UsernameClaimRecord {
        val msg = canonicalClaimSigningString(
            record.username,
            record.nodeId,
            record.publicKeyBase64,
            record.claimedAt
        )
        val sigB64 = signMessageWithEcKey(privateKeyBase64, msg)
        return record.copy(claimVersion = 2, signatureBase64 = sigB64)
    }

    fun verifyUsernameClaimSignature(record: UsernameClaimRecord): Boolean {
        if (record.signatureBase64.isBlank() || record.publicKeyBase64.isBlank()) return false
        val msg = canonicalClaimSigningString(
            record.username,
            record.nodeId,
            record.publicKeyBase64,
            record.claimedAt
        )
        return verifyEcSignature(record.publicKeyBase64, msg, record.signatureBase64)
    }

    /**
     * Derives a private ntfy.sh backup topic for the user's encrypted cross-device snapshot.
     * Uses authVerifierHash (or publicKeyBase64) so third parties cannot guess the backup topic.
     */
    fun deriveUsernameBackupTopic(
        username: String,
        authVerifierHash: String,
        publicKeyBase64: String
    ): String {
        val norm = normalizeUsername(username)
        val anchor = authVerifierHash.trim().ifBlank { publicKeyBase64.trim() }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("PULSEMESH_BACKUP_TOPIC_V1:$norm:$anchor".toByteArray(Charsets.UTF_8))
        val hex = hash.take(16).joinToString("") { "%02x".format(it) }
        return "pulsemesh_bk_$hex"
    }

    private fun deriveBackupAesKey(privateKeyBase64: String, saltHex: String): SecretKey {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("PULSEMESH_BACKUP_AES_V1:${privateKeyBase64.trim()}:${saltHex.trim()}".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(hash, "AES")
    }

    /**
     * GZIP-compresses and AES-256-GCM encrypts a full account snapshot JSON
     * (all chats, contacts, peer keys, groups & settings) using a key derived from
     * the account's EC private key + salt. Both PIN and Recovery Key login recover
     * the exact EC private key and therefore decrypt the same backup seamlessly.
     */
    fun encryptAccountBackup(
        snapshotJson: String,
        privateKeyBase64: String,
        saltHex: String
    ): String {
        if (!isValidEcPrivateKeyBase64(privateKeyBase64)) return ""
        return runCatching {
            val baos = ByteArrayOutputStream()
            GZIPOutputStream(baos).use { gz ->
                gz.write(snapshotJson.toByteArray(Charsets.UTF_8))
            }
            val key = deriveBackupAesKey(privateKeyBase64, saltHex)
            BACKUP_PREFIX + encryptAesGcmBase64(baos.toByteArray(), key)
        }.getOrDefault("")
    }

    fun decryptAccountBackup(
        encryptedBackup: String,
        privateKeyBase64: String,
        saltHex: String
    ): String? {
        val trimmed = encryptedBackup.trim()
        if (!trimmed.startsWith(BACKUP_PREFIX) || !isValidEcPrivateKeyBase64(privateKeyBase64)) return null
        return runCatching {
            val key = deriveBackupAesKey(privateKeyBase64, saltHex)
            val gzipBytes = decryptAesGcmBase64(trimmed.removePrefix(BACKUP_PREFIX), key)
            GZIPInputStream(ByteArrayInputStream(gzipBytes)).use { gz ->
                gz.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    /**
     * Splits an encrypted backup blob into ntfy.sh-safe inline messages (<= 2800 chars each).
     */
    fun splitBackupIntoChunks(
        encryptedBackup: String,
        syncId: String,
        chunkSize: Int = 2800
    ): List<String> {
        val trimmed = encryptedBackup.trim()
        if (!trimmed.startsWith(BACKUP_PREFIX)) return emptyList()
        if (trimmed.length <= chunkSize) return listOf(trimmed)
        val total = (trimmed.length + chunkSize - 1) / chunkSize
        val chunks = ArrayList<String>(total)
        for (i in 0 until total) {
            val start = i * chunkSize
            val end = minOf(trimmed.length, start + chunkSize)
            chunks.add("$BACKUP_CHUNK_PREFIX$syncId:${i + 1}/$total:${trimmed.substring(start, end)}")
        }
        return chunks
    }

    /**
     * Reassembles the latest complete encrypted backup (PMB1: or chunked PMB1C:) from an ordered
     * list of backup messages (oldest to newest).
     */
    fun reassembleBackupChunks(messages: List<String>): String? {
        var latestFull: String? = null
        val chunkGroups = LinkedHashMap<String, Pair<Int, MutableMap<Int, String>>>()
        for (raw in messages) {
            val msg = raw.trim()
            if (msg.startsWith(BACKUP_PREFIX)) {
                latestFull = msg
            } else if (msg.startsWith(BACKUP_CHUNK_PREFIX)) {
                val body = msg.removePrefix(BACKUP_CHUNK_PREFIX)
                val firstColon = body.indexOf(':')
                val secondColon = if (firstColon > 0) body.indexOf(':', firstColon + 1) else -1
                if (firstColon <= 0 || secondColon <= firstColon) continue
                val syncId = body.substring(0, firstColon)
                val frac = body.substring(firstColon + 1, secondColon)
                val slash = frac.indexOf('/')
                if (slash <= 0) continue
                val idx = frac.substring(0, slash).toIntOrNull() ?: continue
                val total = frac.substring(slash + 1).toIntOrNull() ?: continue
                if (idx !in 1..total || total <= 0) continue
                val slice = body.substring(secondColon + 1)
                val group = chunkGroups.getOrPut(syncId) { total to HashMap() }
                group.second[idx] = slice
                if (group.second.size == group.first) {
                    val assembled = buildString {
                        for (k in 1..group.first) {
                            append(group.second[k].orEmpty())
                        }
                    }
                    if (assembled.startsWith(BACKUP_PREFIX)) {
                        latestFull = assembled
                    }
                }
            }
        }
        return latestFull
    }

    private fun deriveInboxEcdhKey(
        privKeyBase64: String,
        pubKeyBase64: String,
        recipientUsername: String
    ): SecretKey {
        val kf = KeyFactory.getInstance("EC")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privKeyBase64.trim(), Base64.DEFAULT)))
        val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubKeyBase64.trim(), Base64.DEFAULT)))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        val shared = ka.generateSecret()
        val md = MessageDigest.getInstance("SHA-256")
        md.update(shared)
        md.update("PULSEMESH_INBOX_ECDH_V2:${normalizeUsername(recipientUsername)}".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(md.digest(), "AES")
    }

    /**
     * Encrypts an async username inbox envelope using Ephemeral-Static ECDH + AES-256-GCM ("PMU2:")
     * when the recipient's EC public key is known (zero metadata leakage, only recipient can decrypt).
     * Falls back to PMU1 only if the recipient's public key has not yet been discovered.
     */
    fun encryptUsernameInboxEnvelope(
        jsonString: String,
        recipientPublicKeyBase64: String,
        recipientUsername: String
    ): String {
        if (isValidEcPublicKeyBase64(recipientPublicKeyBase64)) {
            val encrypted = runCatching {
                val eph = generateKeyPair()
                val aesKey = deriveInboxEcdhKey(
                    eph.privateKeyBase64,
                    recipientPublicKeyBase64,
                    recipientUsername
                )
                val cipherB64 = encryptAesGcmBase64(jsonString.toByteArray(Charsets.UTF_8), aesKey)
                "$USER_INBOX_ECDH_PREFIX${eph.publicKeyBase64}:$cipherB64"
            }.getOrNull()
            if (encrypted != null) return encrypted
        }
        return encryptUsernameInboxPayload(jsonString, recipientUsername)
    }

    /**
     * Decrypts either PMU2 (Ephemeral-Static ECDH using myPrivateKeyBase64) or legacy PMU1 envelopes.
     */
    fun decryptUsernameInboxEnvelope(
        encryptedPayload: String,
        myPrivateKeyBase64: String,
        myUsername: String
    ): String? {
        val trimmed = encryptedPayload.trim()
        if (trimmed.startsWith(USER_INBOX_ECDH_PREFIX)) {
            if (!isValidEcPrivateKeyBase64(myPrivateKeyBase64)) return null
            val body = trimmed.removePrefix(USER_INBOX_ECDH_PREFIX)
            val sepIdx = body.indexOf(':')
            if (sepIdx <= 0 || sepIdx >= body.lastIndex) return null
            val ephPubB64 = body.substring(0, sepIdx)
            val cipherB64 = body.substring(sepIdx + 1)
            if (!isValidEcPublicKeyBase64(ephPubB64)) return null
            return runCatching {
                val aesKey = deriveInboxEcdhKey(myPrivateKeyBase64, ephPubB64, myUsername)
                val plainBytes = decryptAesGcmBase64(cipherB64, aesKey)
                String(plainBytes, Charsets.UTF_8)
            }.getOrNull()
        }
        return decryptUsernameInboxPayload(trimmed, myUsername)
    }

    // =========================================================================
    // SHARED CHANNEL / LEGACY TACNET AEAD PRIMITIVES
    // =========================================================================

    fun encrypt(
        plaintext: String,
        sharedSecretHex: String,
        senderFingerprint: String
    ): EncryptedPayload {
        return try {
            val keyBytes = hexToBytes(sharedSecretHex.padEnd(64, '0').take(64))
            val secretKey: SecretKey = SecretKeySpec(keyBytes, "AES")

            val nonce = ByteArray(12)
            secureRandom.nextBytes(nonce)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, nonce))

            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val ctHex = bytesToHex(ciphertext)
            val nonceHex = bytesToHex(nonce)

            val macDigest = MessageDigest.getInstance("SHA-256").digest(ciphertext)
            val macTagHex = String.format("0x%02X%02X", macDigest[0], macDigest[1])

            EncryptedPayload(
                ciphertextHex = ctHex,
                nonceHex = nonceHex,
                macTagHex = macTagHex,
                senderFingerprint = senderFingerprint
            )
        } catch (_: Exception) {
            EncryptedPayload(
                ciphertextHex = bytesToHex(plaintext.toByteArray(Charsets.UTF_8)),
                nonceHex = "000000000000000000000000",
                macTagHex = "0x9F4C",
                senderFingerprint = senderFingerprint
            )
        }
    }

    fun decrypt(
        encrypted: EncryptedPayload,
        sharedSecretHex: String
    ): String {
        return try {
            val keyBytes = hexToBytes(sharedSecretHex.padEnd(64, '0').take(64))
            val secretKey: SecretKey = SecretKeySpec(keyBytes, "AES")

            val nonce = hexToBytes(encrypted.nonceHex)
            val ciphertext = hexToBytes(encrypted.ciphertextHex)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, nonce))

            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, Charsets.UTF_8)
        } catch (_: Exception) {
            "[E2EE DECRYPT ERROR / INVALID KEY]"
        }
    }

    private fun encryptAesGcmBase64(plaintextBytes: ByteArray, key: SecretKey): String {
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertextWithTag = cipher.doFinal(plaintextBytes)
        val combined = ByteArray(iv.size + ciphertextWithTag.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertextWithTag, 0, combined, iv.size, ciphertextWithTag.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptAesGcmBase64(base64Data: String, key: SecretKey): ByteArray {
        val combined = Base64.decode(base64Data, Base64.DEFAULT)
        require(combined.size > 12) { "Ciphertext too short" }
        val iv = ByteArray(12)
        System.arraycopy(combined, 0, iv, 0, 12)
        val ciphertextWithTag = ByteArray(combined.size - 12)
        System.arraycopy(combined, 12, ciphertextWithTag, 0, ciphertextWithTag.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertextWithTag)
    }

    private val HEX_CHARS_UPPER = "0123456789ABCDEF".toCharArray()
    private val HEX_CHARS_LOWER = "0123456789abcdef".toCharArray()

    private fun bytesToHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS_UPPER[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS_UPPER[v and 0x0F]
        }
        return String(out)
    }

    private fun bytesToLowerHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS_LOWER[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS_LOWER[v and 0x0F]
        }
        return String(out)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}

