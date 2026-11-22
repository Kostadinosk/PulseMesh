// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.data.db

import android.content.Context
import android.os.Build
import androidx.room.Room

/**
 * Lite-flavor database factory using standard platform Room SQLite paired with mandatory
 * AndroidKeyStore AES-256-GCM field-level encryption on sensitive columns.
 */
object DatabaseFactory {

    const val DB_NAME = "pulsemesh_v15_lite.db"

    @Volatile
    private var instance: PulseMeshDatabase? = null

    @Volatile
    private var cachedContextIdentity: Int = 0

    fun requiresFieldEncryption(): Boolean = true

    fun isSqlCipherActive(): Boolean = false

    @Synchronized
    fun getDatabase(context: Context): PulseMeshDatabase {
        val appCtx = context.applicationContext
        val identity = System.identityHashCode(appCtx)
        val existing = instance
        if (existing != null && cachedContextIdentity == identity) {
            return existing
        }
        if (existing != null) {
            instance = null
        }

        val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true) ||
            Build.MODEL.contains("robolectric", ignoreCase = true)
        val dbName = if (isRobolectric) "pulsemesh_v15_lite_${identity}.db" else DB_NAME

        return Room.databaseBuilder(appCtx, PulseMeshDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
            .also {
                instance = it
                cachedContextIdentity = identity
            }
    }
}
