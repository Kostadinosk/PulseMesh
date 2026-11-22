// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.data.db

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts binary media attachments (HD photos, voice notes, shared PDF/GPX/APK/ZIP files)
 * into `filesDir/media_blobs/<sha256>.enc` using the AndroidKeyStore-wrapped database passphrase
 * so large attachments stay out of SQLite rows AND remain encrypted at rest on both Lite and Full.
 */
class MediaBlobStore(context: Context) {

    private val appContext = context.applicationContext
    private val blobDir: File = File(appContext.filesDir, "media_blobs").apply {
        if (!exists()) mkdirs()
    }
    private val secureRandom = SecureRandom()

    private fun deriveMediaAesKey(): SecretKey {
        val dbSecret = com.kostadinosk.pulsemesh.crypto.KeystoreMasterKeyManager
            .getOrCreateDatabasePassphrase(appContext)
        val derived = MessageDigest.getInstance("SHA-256").digest(
            dbSecret + "PM_MEDIA_BLOB_V15".toByteArray(Charsets.UTF_8)
        )
        return SecretKeySpec(derived, "AES")
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Encrypts and stores `plainBytes` under its SHA-256 digest name; returns Pair(relativePath, sha256).
     */
    fun storeEncryptedBlob(plainBytes: ByteArray): Pair<String, String> {
        val digestHex = sha256Hex(plainBytes)
        val relPath = "media_blobs/$digestHex.enc"
        val outFile = File(appContext.filesDir, relPath)
        outFile.parentFile?.mkdirs()

        val key = deriveMediaAesKey()
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(digestHex.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(plainBytes)

        val combined = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ct, 0, combined, iv.size, ct.size)
        outFile.writeBytes(combined)
        return relPath to digestHex
    }

    fun readDecryptedBlob(relativePath: String): ByteArray? {
        if (relativePath.isBlank()) return null
        val file = File(appContext.filesDir, relativePath)
        if (!file.exists()) return null
        val expectedHash = file.nameWithoutExtension
        return runCatching {
            val combined = file.readBytes()
            if (combined.size <= 12) return@runCatching null
            val iv = combined.copyOfRange(0, 12)
            val ct = combined.copyOfRange(12, combined.size)
            val key = deriveMediaAesKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(expectedHash.toByteArray(Charsets.UTF_8))
            cipher.doFinal(ct)
        }.getOrNull()
    }

    fun deleteBlob(relativePath: String): Boolean {
        if (relativePath.isBlank()) return false
        return runCatching { File(appContext.filesDir, relativePath).delete() }.getOrDefault(false)
    }

    fun wipeAllBlobs() {
        runCatching {
            blobDir.listFiles()?.forEach { it.delete() }
        }
    }
}
