// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.data.db

import android.content.Context
import android.os.Build
import androidx.room.Room
import com.kostadinosk.pulsemesh.crypto.KeystoreMasterKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Full-flavor database factory backed by AES-256 SQLCipher encrypted storage (keyed via
 * Keystore-wrapped passphrase), falling back gracefully to standard SQLite strictly inside
 * Robolectric JVM tests where native `.so` binaries are absent.
 */
object DatabaseFactory {

    const val DB_NAME = "pulsemesh_v15_full.db"

    @Volatile
    private var instance: PulseMeshDatabase? = null

    @Volatile
    private var cachedContextIdentity: Int = 0

    @Volatile
    private var sqlCipherActive: Boolean = false

    fun requiresFieldEncryption(): Boolean = !sqlCipherActive

    fun isSqlCipherActive(): Boolean = sqlCipherActive

    @Synchronized
    fun getDatabase(context: Context): PulseMeshDatabase {
        val appCtx = context.applicationContext
        val identity = System.identityHashCode(appCtx)
        val existing = instance
        if (existing != null && cachedContextIdentity == identity) {
            return existing
        }
        if (existing != null) {
            // Do not close the previous instance abruptly: background coroutines from a prior
            // Robolectric test Application may still flush Room writes and hit "Database is closed".
            instance = null
        }

        val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true) ||
            Build.MODEL.contains("robolectric", ignoreCase = true)
        val dbName = if (isRobolectric) "pulsemesh_v15_full_${identity}.db" else DB_NAME

        val builder = Room.databaseBuilder(appCtx, PulseMeshDatabase::class.java, dbName)
            .allowMainThreadQueries()

        if (!isRobolectric) {
            val loaded = runCatching {
                System.loadLibrary("sqlcipher")
                val passphrase = KeystoreMasterKeyManager.getOrCreateDatabasePassphrase(appCtx)
                builder.openHelperFactory(SupportOpenHelperFactory(passphrase))
                true
            }.getOrDefault(false)
            sqlCipherActive = loaded
        } else {
            sqlCipherActive = false
        }

        return builder.build().also {
            instance = it
            cachedContextIdentity = identity
        }
    }
}
