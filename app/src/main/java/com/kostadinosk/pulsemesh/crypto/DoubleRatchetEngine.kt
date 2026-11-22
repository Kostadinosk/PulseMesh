// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.crypto

import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Signal-style Double Ratchet Engine over ECDH P-256 (`secp256r1`) + RFC 5869 HKDF-SHA256 + AES-256-GCM.
 *
 * Provides per-message Forward Secrecy and Post-Compromise Break-In Recovery (`PMDR2:` envelopes),
 * out-of-order skipped message key caching (up to 100 skipped keys per session), and AEAD AAD
 * authentication over envelope metadata.
 */
object DoubleRatchetEngine {

    const val ENVELOPE_PREFIX = "PMDR2:"
    private const val EC_CURVE = "secp256r1"
    private const val MAX_SKIP = 100
    private const val KDF_RK_INFO = "PULSEMESH_DR_RK_V2"
    private const val KDF_CK_MSG_LABEL = 0x01
    private const val KDF_CK_NEXT_LABEL = 0x02

    private val secureRandom = SecureRandom()

    data class RatchetSessionState(
        val peerId: String,
        val rootKeyB64: String,
        val sendChainKeyB64: String,
        val recvChainKeyB64: String,
        val dhLocalPrivB64: String,
        val dhLocalPubB64: String,
        val dhRemotePubB64: String,
        val sendN: Int = 0,
        val recvN: Int = 0,
        val prevSendN: Int = 0,
        val skippedKeys: LinkedHashMap<String, String> = LinkedHashMap()
    ) {
        fun toJsonString(): String = JSONObject().apply {
            put("peerId", peerId)
            put("rk", rootKeyB64)
            put("cks", sendChainKeyB64)
            put("ckr", recvChainKeyB64)
            put("dhsPriv", dhLocalPrivB64)
            put("dhsPub", dhLocalPubB64)
            put("dhrPub", dhRemotePubB64)
            put("ns", sendN)
            put("nr", recvN)
            put("pn", prevSendN)
            val sk = JSONObject()
            for ((k, v) in skippedKeys) sk.put(k, v)
            put("skipped", sk)
        }.toString()

        companion object {
            fun fromJsonString(raw: String): RatchetSessionState? {
                return runCatching {
                    val obj = JSONObject(raw)
                    val peerId = obj.optString("peerId")
                    val rk = obj.optString("rk")
                    val dhsPriv = obj.optString("dhsPriv")
                    val dhsPub = obj.optString("dhsPub")
                    if (peerId.isBlank() || rk.isBlank() || dhsPriv.isBlank() || dhsPub.isBlank()) {
                        return@runCatching null
                    }
                    val skipped = LinkedHashMap<String, String>()
                    obj.optJSONObject("skipped")?.let { skObj ->
                        val keys = skObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = skObj.optString(k)
                            if (v.isNotBlank()) skipped[k] = v
                        }
                    }
                    RatchetSessionState(
                        peerId = peerId,
                        rootKeyB64 = rk,
                        sendChainKeyB64 = obj.optString("cks", ""),
                        recvChainKeyB64 = obj.optString("ckr", ""),
                        dhLocalPrivB64 = dhsPriv,
                        dhLocalPubB64 = dhsPub,
                        dhRemotePubB64 = obj.optString("dhrPub", ""),
                        sendN = obj.optInt("ns", 0),
                        recvN = obj.optInt("nr", 0),
                        prevSendN = obj.optInt("pn", 0),
                        skippedKeys = skipped
                    )
                }.getOrNull()
            }
        }
    }

    private val sessions = ConcurrentHashMap<String, RatchetSessionState>()

    var onSessionUpdated: ((peerId: String, stateJson: String) -> Unit)? = null

    private fun normPeer(peerId: String): String = peerId.trim().uppercase().removePrefix("0X")

    // =========================================================================
    // RFC 5869 HKDF-SHA256
    // =========================================================================

    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "Invalid HKDF length: $length" }
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(prk, "HmacSHA256")
        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.init(keySpec)
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
            counter++
        }
        return result
    }

    fun hkdfDeriveKey(
        ikm: ByteArray,
        salt: ByteArray = ByteArray(0),
        info: ByteArray,
        length: Int = 32
    ): ByteArray {
        val prk = hkdfExtract(salt, ikm)
        return hkdfExpand(prk, info, length)
    }

    // =========================================================================
    // ECDH & CHAIN KDF HELPERS
    // =========================================================================

    private fun generateEcKeyPair(): Pair<String, String> {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(EC_CURVE), secureRandom)
        val kp = kpg.generateKeyPair()
        val privB64 = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
        val pubB64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        return privB64 to pubB64
    }

    private fun ecdhSharedSecret(privB64: String, pubB64: String): ByteArray {
        val kf = KeyFactory.getInstance("EC")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.DEFAULT)))
        val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.DEFAULT)))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    private fun kdfRootKey(rootKey: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val out = hkdfDeriveKey(
            ikm = dhOut,
            salt = rootKey,
            info = KDF_RK_INFO.toByteArray(Charsets.UTF_8),
            length = 64
        )
        val newRoot = out.copyOfRange(0, 32)
        val newChain = out.copyOfRange(32, 64)
        return newRoot to newChain
    }

    private fun kdfChainKey(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(chainKey, "HmacSHA256"))
        val msgKey = mac.doFinal(byteArrayOf(KDF_CK_MSG_LABEL.toByte()))
        mac.init(SecretKeySpec(chainKey, "HmacSHA256"))
        val nextChain = mac.doFinal(byteArrayOf(KDF_CK_NEXT_LABEL.toByte()))
        return nextChain to msgKey
    }

    // =========================================================================
    // SESSION MANAGEMENT & RATCHET OPERATIONS
    // =========================================================================

    @Synchronized
    fun initSessionAsInitiator(
        peerId: String,
        rootSharedSecret: ByteArray,
        peerDhPublicKeyBase64: String
    ): RatchetSessionState {
        val norm = normPeer(peerId)
        val (dhsPriv, dhsPub) = generateEcKeyPair()
        val dhOut = ecdhSharedSecret(dhsPriv, peerDhPublicKeyBase64)
        val initialRk = hkdfDeriveKey(rootSharedSecret, info = "PM_DR_INIT_RK".toByteArray(Charsets.UTF_8), length = 32)
        val (newRk, sendCk) = kdfRootKey(initialRk, dhOut)
        val state = RatchetSessionState(
            peerId = norm,
            rootKeyB64 = Base64.encodeToString(newRk, Base64.NO_WRAP),
            sendChainKeyB64 = Base64.encodeToString(sendCk, Base64.NO_WRAP),
            recvChainKeyB64 = "",
            dhLocalPrivB64 = dhsPriv,
            dhLocalPubB64 = dhsPub,
            dhRemotePubB64 = peerDhPublicKeyBase64.trim(),
            sendN = 0,
            recvN = 0,
            prevSendN = 0
        )
        sessions[norm] = state
        onSessionUpdated?.invoke(norm, state.toJsonString())
        return state
    }

    @Synchronized
    fun initSessionAsResponder(
        peerId: String,
        rootSharedSecret: ByteArray,
        localDhPrivateKeyBase64: String,
        localDhPublicKeyBase64: String
    ): RatchetSessionState {
        val norm = normPeer(peerId)
        val initialRk = hkdfDeriveKey(rootSharedSecret, info = "PM_DR_INIT_RK".toByteArray(Charsets.UTF_8), length = 32)
        val state = RatchetSessionState(
            peerId = norm,
            rootKeyB64 = Base64.encodeToString(initialRk, Base64.NO_WRAP),
            sendChainKeyB64 = "",
            recvChainKeyB64 = "",
            dhLocalPrivB64 = localDhPrivateKeyBase64.trim(),
            dhLocalPubB64 = localDhPublicKeyBase64.trim(),
            dhRemotePubB64 = "",
            sendN = 0,
            recvN = 0,
            prevSendN = 0
        )
        sessions[norm] = state
        onSessionUpdated?.invoke(norm, state.toJsonString())
        return state
    }

    @Synchronized
    fun hasSession(peerId: String): Boolean = sessions.containsKey(normPeer(peerId))

    @Synchronized
    fun removeSession(peerId: String) {
        sessions.remove(normPeer(peerId))
    }

    @Synchronized
    fun clearAllSessions() {
        sessions.clear()
    }

    @Synchronized
    fun exportSessionStateJson(peerId: String): String? = sessions[normPeer(peerId)]?.toJsonString()

    @Synchronized
    fun importSessionStateJson(peerId: String, rawJson: String): Boolean {
        val state = RatchetSessionState.fromJsonString(rawJson) ?: return false
        val norm = normPeer(peerId)
        sessions[norm] = state.copy(peerId = norm)
        return true
    }

    @Synchronized
    fun encrypt(
        peerId: String,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0)
    ): String? {
        val norm = normPeer(peerId)
        var state = sessions[norm] ?: return null
        if (state.sendChainKeyB64.isBlank()) {
            if (state.dhRemotePubB64.isBlank()) return null
            val (dhsPriv, dhsPub) = generateEcKeyPair()
            val rk = Base64.decode(state.rootKeyB64, Base64.DEFAULT)
            val dhOut = ecdhSharedSecret(dhsPriv, state.dhRemotePubB64)
            val (newRk, newCks) = kdfRootKey(rk, dhOut)
            state = state.copy(
                rootKeyB64 = Base64.encodeToString(newRk, Base64.NO_WRAP),
                sendChainKeyB64 = Base64.encodeToString(newCks, Base64.NO_WRAP),
                dhLocalPrivB64 = dhsPriv,
                dhLocalPubB64 = dhsPub,
                prevSendN = state.sendN,
                sendN = 0
            )
        }

        val cks = Base64.decode(state.sendChainKeyB64, Base64.DEFAULT)
        val (nextCks, msgKey) = kdfChainKey(cks)
        val n = state.sendN
        val headerAad = buildHeaderAad(state.dhLocalPubB64, n, state.prevSendN, aad)

        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(msgKey, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(headerAad)
        val ciphertextWithTag = cipher.doFinal(plaintext)

        val combined = ByteArray(iv.size + ciphertextWithTag.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertextWithTag, 0, combined, iv.size, ciphertextWithTag.size)

        val updated = state.copy(
            sendChainKeyB64 = Base64.encodeToString(nextCks, Base64.NO_WRAP),
            sendN = n + 1
        )
        sessions[norm] = updated
        onSessionUpdated?.invoke(norm, updated.toJsonString())

        val envelopeJson = JSONObject().apply {
            put("dh", updated.dhLocalPubB64)
            put("n", n)
            put("pn", updated.prevSendN)
            put("ct", Base64.encodeToString(combined, Base64.NO_WRAP))
        }.toString()
        return ENVELOPE_PREFIX + Base64.encodeToString(envelopeJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    @Synchronized
    fun decrypt(
        peerId: String,
        payload: String,
        aad: ByteArray = ByteArray(0)
    ): ByteArray? {
        val trimmed = payload.trim()
        if (!trimmed.startsWith(ENVELOPE_PREFIX)) return null
        val norm = normPeer(peerId)
        var state = sessions[norm] ?: return null

        val envJson = runCatching {
            val raw = Base64.decode(trimmed.removePrefix(ENVELOPE_PREFIX), Base64.DEFAULT)
            JSONObject(String(raw, Charsets.UTF_8))
        }.getOrNull() ?: return null

        val headerDh = envJson.optString("dh", "").trim()
        val n = envJson.optInt("n", -1)
        val pn = envJson.optInt("pn", 0)
        val ctB64 = envJson.optString("ct", "")
        if (headerDh.isBlank() || n < 0 || ctB64.isBlank()) return null
        val headerAad = buildHeaderAad(headerDh, n, pn, aad)

        // 1. Check skipped message keys (out-of-order mesh delivery)
        val skipLookupKey = "$headerDh|$n"
        val existingSkippedB64 = state.skippedKeys[skipLookupKey]
        if (!existingSkippedB64.isNullOrBlank()) {
            val mk = Base64.decode(existingSkippedB64, Base64.DEFAULT)
            val plain = decryptAead(ctB64, mk, headerAad) ?: return null
            val nextSkipped = LinkedHashMap(state.skippedKeys)
            nextSkipped.remove(skipLookupKey)
            val updated = state.copy(skippedKeys = nextSkipped)
            sessions[norm] = updated
            onSessionUpdated?.invoke(norm, updated.toJsonString())
            return plain
        }

        // Work on a temporary copy so tampered envelopes never corrupt session state
        val tempSkipped = LinkedHashMap(state.skippedKeys)

        // 2. If sender rotated DH ratchet key, finalize previous receiving chain & advance DH ratchet
        if (headerDh != state.dhRemotePubB64) {
            state = skipMessageKeys(state, pn, tempSkipped) ?: return null
            state = dhRatchetStep(state, headerDh) ?: return null
        }

        // 3. Advance receiving chain up to message number n (saving skipped keys)
        state = skipMessageKeys(state, n, tempSkipped) ?: return null
        if (state.recvChainKeyB64.isBlank()) return null

        val ckr = Base64.decode(state.recvChainKeyB64, Base64.DEFAULT)
        val (nextCkr, msgKey) = kdfChainKey(ckr)
        val plain = decryptAead(ctB64, msgKey, headerAad) ?: return null

        val updated = state.copy(
            recvChainKeyB64 = Base64.encodeToString(nextCkr, Base64.NO_WRAP),
            recvN = state.recvN + 1,
            skippedKeys = tempSkipped
        )
        sessions[norm] = updated
        onSessionUpdated?.invoke(norm, updated.toJsonString())
        return plain
    }

    private fun skipMessageKeys(
        state: RatchetSessionState,
        untilN: Int,
        skippedTarget: LinkedHashMap<String, String>
    ): RatchetSessionState? {
        if (untilN - state.recvN > MAX_SKIP) return null
        if (state.recvChainKeyB64.isBlank()) {
            return if (untilN == state.recvN) state else null
        }
        var currentCk = Base64.decode(state.recvChainKeyB64, Base64.DEFAULT)
        var currentN = state.recvN
        while (currentN < untilN) {
            val (nextCk, mk) = kdfChainKey(currentCk)
            currentCk = nextCk
            val mapKey = "${state.dhRemotePubB64}|$currentN"
            skippedTarget[mapKey] = Base64.encodeToString(mk, Base64.NO_WRAP)
            while (skippedTarget.size > MAX_SKIP) {
                val eldest = skippedTarget.keys.firstOrNull() ?: break
                skippedTarget.remove(eldest)
            }
            currentN++
        }
        return state.copy(
            recvChainKeyB64 = Base64.encodeToString(currentCk, Base64.NO_WRAP),
            recvN = currentN
        )
    }

    private fun dhRatchetStep(
        state: RatchetSessionState,
        newRemoteDhPubB64: String
    ): RatchetSessionState? {
        return runCatching {
            val rk0 = Base64.decode(state.rootKeyB64, Base64.DEFAULT)
            val dhRecv = ecdhSharedSecret(state.dhLocalPrivB64, newRemoteDhPubB64)
            val (rk1, ckr) = kdfRootKey(rk0, dhRecv)

            val (newLocalPriv, newLocalPub) = generateEcKeyPair()
            val dhSend = ecdhSharedSecret(newLocalPriv, newRemoteDhPubB64)
            val (rk2, cks) = kdfRootKey(rk1, dhSend)

            state.copy(
                rootKeyB64 = Base64.encodeToString(rk2, Base64.NO_WRAP),
                sendChainKeyB64 = Base64.encodeToString(cks, Base64.NO_WRAP),
                recvChainKeyB64 = Base64.encodeToString(ckr, Base64.NO_WRAP),
                dhLocalPrivB64 = newLocalPriv,
                dhLocalPubB64 = newLocalPub,
                dhRemotePubB64 = newRemoteDhPubB64,
                prevSendN = state.sendN,
                sendN = 0,
                recvN = 0
            )
        }.getOrNull()
    }

    private fun decryptAead(ctB64: String, msgKey: ByteArray, aad: ByteArray): ByteArray? {
        return runCatching {
            val combined = Base64.decode(ctB64, Base64.DEFAULT)
            if (combined.size <= 12) return@runCatching null
            val iv = combined.copyOfRange(0, 12)
            val ctWithTag = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(msgKey, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(ctWithTag)
        }.getOrNull()
    }

    private fun buildHeaderAad(dhPub: String, n: Int, pn: Int, extraAad: ByteArray): ByteArray {
        val header = "PMDR2|$dhPub|$n|$pn|".toByteArray(Charsets.UTF_8)
        if (extraAad.isEmpty()) return header
        val combined = ByteArray(header.size + extraAad.size)
        System.arraycopy(header, 0, combined, 0, header.size)
        System.arraycopy(extraAad, 0, combined, header.size, extraAad.size)
        return combined
    }
}
