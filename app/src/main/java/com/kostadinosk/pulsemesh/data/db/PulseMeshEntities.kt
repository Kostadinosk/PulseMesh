// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["threadId", "id"], unique = true),
        Index(value = ["threadId", "timestamp"]),
        Index(value = ["id"])
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0L,
    val id: String,
    val threadId: String,
    val sourceId: String,
    val destId: String,
    val sourceCallsign: String,
    val type: String,
    val content: String,
    val hops: Int,
    val maxHops: Int = 5,
    val rssiDbm: Int? = null,
    val transport: String = "BLE 1M",
    val rawHexBadge: String = "",
    val isIncoming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val isDelivered: Boolean = false,
    val deliveryStatus: String = "SENT",
    val ackedByCallsign: String = "",
    val expiresAtMs: Long = 0L,
    val reactionsJson: String = "",
    val isPinned: Boolean = false,
    val replyToId: String = "",
    val replyToSender: String = "",
    val replyToPreview: String = "",
    val editedAtMs: Long = 0L,
    val isUnsent: Boolean = false,
    val attachmentPath: String = "",
    val attachmentMime: String = "",
    val attachmentName: String = "",
    val attachmentSizeBytes: Long = 0L,
    val voiceDurationMs: Long = 0L
)

@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFtsEntity(
    val threadId: String,
    val sourceCallsign: String,
    val content: String
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey
    val groupId: String,
    val groupName: String,
    val adminIdsCsv: String = "",
    val memberIdsCsv: String = "",
    val keyEpoch: Int = 1,
    val encryptedGroupKey: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "dtn_bundles",
    indices = [
        Index(value = ["priority", "createdAtMs"]),
        Index(value = ["expiresAtMs"])
    ]
)
data class DtnBundleEntity(
    @PrimaryKey
    val packetId: String,
    val srcId: String,
    val dstId: String,
    val packetType: String,
    val wireJson: String,
    val priority: Int = 1,
    val createdAtMs: Long = System.currentTimeMillis(),
    val expiresAtMs: Long = 0L,
    val hopCount: Int = 0,
    val custodyAcked: Boolean = false,
    val sizeBytes: Int = 0
)

@Entity(tableName = "ratchet_sessions")
data class RatchetSessionEntity(
    @PrimaryKey
    val peerId: String,
    val encryptedStateBlob: String,
    val updatedAtMs: Long = System.currentTimeMillis()
)
