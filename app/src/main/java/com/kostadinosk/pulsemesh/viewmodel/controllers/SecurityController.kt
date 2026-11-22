// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.viewmodel.controllers

import android.content.Context
import com.kostadinosk.pulsemesh.crypto.DoubleRatchetEngine
import com.kostadinosk.pulsemesh.crypto.KeystoreMasterKeyManager
import com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager
import com.kostadinosk.pulsemesh.data.db.DatabaseFactory
import com.kostadinosk.pulsemesh.data.db.PulseMeshDatabase
import com.kostadinosk.pulsemesh.data.db.RatchetSessionEntity
import com.kostadinosk.pulsemesh.util.DevicePreferences

/**
 * Manages Double Ratchet session hydration/persistence in Room (`ratchet_sessions`),
 * 1-to-1 Forward Secret E2EE, and zero-plaintext security policy.
 */
class SecurityController(
    context: Context,
    private val prefs: DevicePreferences
) {
    private val appContext = context.applicationContext
    private val db: PulseMeshDatabase = DatabaseFactory.getDatabase(appContext)
    private val ratchetDao = db.ratchetSessionDao()

    fun hydrateRatchetSessions() {
        runCatching {
            val all = ratchetDao.getAllSessions()
            for (row in all) {
                val json = if (KeystoreMasterKeyManager.isWrapped(row.encryptedStateBlob)) {
                    KeystoreMasterKeyManager.unwrapString(appContext, row.encryptedStateBlob)
                } else {
                    row.encryptedStateBlob
                }
                if (!json.isNullOrBlank()) {
                    DoubleRatchetEngine.importSessionStateJson(row.peerId, json)
                }
            }
            DoubleRatchetEngine.onSessionUpdated = { peerId, stateJson ->
                persistRatchetSession(peerId, stateJson)
            }
        }
    }

    private fun persistRatchetSession(peerId: String, stateJson: String) {
        runCatching {
            val stored = if (DatabaseFactory.requiresFieldEncryption()) {
                KeystoreMasterKeyManager.wrapString(appContext, stateJson)
            } else {
                stateJson
            }
            ratchetDao.upsert(
                RatchetSessionEntity(
                    peerId = peerId,
                    encryptedStateBlob = stored,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    fun encryptDm(peerId: String, peerPubKeyBase64: String, plaintext: String): String? {
        if (!TacticalE2eeManager.isValidEcPrivateKeyBase64(prefs.x25519PrivateKey) ||
            !TacticalE2eeManager.isValidEcPublicKeyBase64(peerPubKeyBase64)
        ) {
            return null
        }
        val norm = peerId.trim().uppercase().removePrefix("0X")
        val dr = runCatching {
            if (!DoubleRatchetEngine.hasSession(norm)) {
                val root = TacticalE2eeManager.computeEcdhSharedSecret(prefs.x25519PrivateKey, peerPubKeyBase64)
                DoubleRatchetEngine.initSessionAsInitiator(norm, root, peerPubKeyBase64)
            }
            DoubleRatchetEngine.encrypt(norm, plaintext.toByteArray(Charsets.UTF_8))
        }.getOrNull()
        return dr ?: TacticalE2eeManager.encryptDmWithEcdh(
            plaintext = plaintext,
            myPrivateKeyBase64 = prefs.x25519PrivateKey,
            peerPublicKeyBase64 = peerPubKeyBase64
        )
    }

    fun decryptDm(peerId: String, peerPubKeyBase64: String, payload: String): String {
        val trimmed = payload.trim()
        if (trimmed.startsWith(DoubleRatchetEngine.ENVELOPE_PREFIX)) {
            if (!TacticalE2eeManager.isValidEcPrivateKeyBase64(prefs.x25519PrivateKey) ||
                !TacticalE2eeManager.isValidEcPublicKeyBase64(peerPubKeyBase64)
            ) {
                return "[Key Exchange Pending / Decryption Failed]"
            }
            val norm = peerId.trim().uppercase().removePrefix("0X")
            val plain = runCatching {
                if (!DoubleRatchetEngine.hasSession(norm)) {
                    val root = TacticalE2eeManager.computeEcdhSharedSecret(prefs.x25519PrivateKey, peerPubKeyBase64)
                    DoubleRatchetEngine.initSessionAsResponder(
                        peerId = norm,
                        rootSharedSecret = root,
                        localDhPrivateKeyBase64 = prefs.x25519PrivateKey,
                        localDhPublicKeyBase64 = prefs.x25519PublicKey
                    )
                }
                DoubleRatchetEngine.decrypt(norm, trimmed)
            }.getOrNull()
            return if (plain != null) String(plain, Charsets.UTF_8) else "[Ratchet Key Desync / Decryption Failed]"
        }
        return TacticalE2eeManager.decryptDmWithEcdh(
            payload = trimmed,
            myPrivateKeyBase64 = prefs.x25519PrivateKey,
            peerPublicKeyBase64 = peerPubKeyBase64
        )
    }

    fun clearAllRatchetSessions() {
        DoubleRatchetEngine.clearAllSessions()
        runCatching { ratchetDao.deleteAll() }
    }
}
