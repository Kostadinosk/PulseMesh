// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MessageEntity::class,
        MessageFtsEntity::class,
        GroupEntity::class,
        DtnBundleEntity::class,
        RatchetSessionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PulseMeshDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun groupDao(): GroupDao
    abstract fun dtnDao(): DtnDao
    abstract fun ratchetSessionDao(): RatchetSessionDao
}
