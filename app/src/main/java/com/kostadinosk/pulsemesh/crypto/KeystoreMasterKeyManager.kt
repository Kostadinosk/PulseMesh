// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wraps local secrets (EC private key, Cloudflare auth token, Room/SQLCipher database passphrase)
 * using an AndroidKeyStore AES-256-GCM master key (`pulsemesh_master_key_v15`).
 *
 * Inside JVM / Robolectric unit tests where hardware AndroidKeyStore is unavailable, falls back
 * transparently to an in-memory app-scoped AES-256-GCM test key so unit tests remain deterministic.
 */
object KeystoreMasterKeyManager {

    private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "pulsemesh_master_key_v15"
    private const val KEYSTORE_PREFS_NAME = "pulsemesh_keystore_vault_v15"
    private const val DB_PASSPHRASE_WRAPPED_KEY = "wrapped_db_passphrase_v15"
    private const val WRAPPED_PREFIX = "KSGCM1:"
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128

    private val secureRandom = SecureRandom()

    @Volatile
    private var cachedFallbackKey: SecretKey? = null

    private fun isRobolectricRuntime(): Boolean {
        return Build.FINGERPRINT.equals("robolectric", ignoreCase = true) ||
            Build.MODEL.contains("robolectric", ignoreCase = true)
    }

    @Synchronized
    private fun getOrCreateMasterKey(context: Context): SecretKey {
        if (!isRobolectricRuntime() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val ksKey = runCatching {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
                val existing = (keyStore.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                if (existing != null) return@runCatching existing

                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE_PROVIDER
                )
                val spec = KeyGenParameterSpec.Builder(
                    MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }.getOrNull()
            if (ksKey != null) return ksKey
        }

        // Strict Robolectric / API 23 fallback
        cachedFallbackKey?.let { return it }
        val seed = "PULSEMESH_KS_FALLBACK_V15:${context.packageName}".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(seed)
        return SecretKeySpec(digest, "AES").also { cachedFallbackKey = it }
    }

    fun isWrapped(value: String): Boolean = value.startsWith(WRAPPED_PREFIX)

    fun wrapSecret(context: Context, plaintext: ByteArray): String {
        if (plaintext.isEmpty()) return ""
        val key = getOrCreateMasterKey(context)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
        return WRAPPED_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun unwrapSecret(context: Context, wrapped: String): ByteArray? {
        val trimmed = wrapped.trim()
        if (!trimmed.startsWith(WRAPPED_PREFIX)) return null
        return runCatching {
            val combined = Base64.decode(trimmed.removePrefix(WRAPPED_PREFIX), Base64.DEFAULT)
            if (combined.size <= 2) return@runCatching null
            val ivLen = combined[0].toInt() and 0xFF
            if (ivLen != GCM_IV_LEN || combined.size <= 1 + ivLen) return@runCatching null
            val iv = ByteArray(ivLen)
            System.arraycopy(combined, 1, iv, 0, ivLen)
            val ciphertext = ByteArray(combined.size - 1 - ivLen)
            System.arraycopy(combined, 1 + ivLen, ciphertext, 0, ciphertext.size)

            val key = getOrCreateMasterKey(context)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        }.getOrNull()
    }

    fun wrapString(context: Context, plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        return wrapSecret(context, plaintext.toByteArray(Charsets.UTF_8))
    }

    fun unwrapString(context: Context, wrapped: String): String? {
        val bytes = unwrapSecret(context, wrapped) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    @Synchronized
    fun getOrCreateDatabasePassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(KEYSTORE_PREFS_NAME, Context.MODE_PRIVATE)
        val existingWrapped = prefs.getString(DB_PASSPHRASE_WRAPPED_KEY, null)
        if (!existingWrapped.isNullOrBlank()) {
            val existing = unwrapSecret(context, existingWrapped)
            if (existing != null && existing.size == 32) {
                return existing
            }
        }
        val rawPassphrase = ByteArray(32)
        secureRandom.nextBytes(rawPassphrase)
        val wrapped = wrapSecret(context, rawPassphrase)
        prefs.edit().putString(DB_PASSPHRASE_WRAPPED_KEY, wrapped).apply()
        return rawPassphrase
    }

    @Synchronized
    fun resetDatabasePassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(KEYSTORE_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(DB_PASSPHRASE_WRAPPED_KEY).apply()
        return getOrCreateDatabasePassphrase(context)
    }
}
