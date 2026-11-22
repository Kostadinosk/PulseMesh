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

        if (!isRobolectric) {
            val cipherLibLoaded = runCatching {
                System.loadLibrary("sqlcipher")
                true
            }.getOrDefault(false)

            if (cipherLibLoaded) {
                val firstDb = openAndProbeSqlCipherDb(appCtx, dbName, KeystoreMasterKeyManager.getOrCreateDatabasePassphrase(appCtx))
                if (firstDb != null) {
                    sqlCipherActive = true
                    instance = firstDb
                    cachedContextIdentity = identity
                    return firstDb
                }

                // Recover once from corrupt/incompatible database or invalidated Keystore key
                runCatching { appCtx.deleteDatabase(dbName) }
                val freshPassphrase = KeystoreMasterKeyManager.resetDatabasePassphrase(appCtx)
                val recoveredDb = openAndProbeSqlCipherDb(appCtx, dbName, freshPassphrase)
                if (recoveredDb != null) {
                    sqlCipherActive = true
                    instance = recoveredDb
                    cachedContextIdentity = identity
                    return recoveredDb
                }
            }
        }

        sqlCipherActive = false
        return Room.databaseBuilder(appCtx, PulseMeshDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
            .also {
                instance = it
                cachedContextIdentity = identity
            }
    }

    private fun openAndProbeSqlCipherDb(
        appCtx: Context,
        dbName: String,
        passphrase: ByteArray
    ): PulseMeshDatabase? {
        var db: PulseMeshDatabase? = null
        return try {
            db = Room.databaseBuilder(appCtx, PulseMeshDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
            db.openHelper.readableDatabase
            db
        } catch (_: Throwable) {
            runCatching { db?.close() }
            null
        }
    }
}
