// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(entities: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp DESC")
    fun getThreadMessagesDesc(threadId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    fun getThreadMessagesPaged(threadId: String, limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessagesDesc(): List<MessageEntity>

    @Query("SELECT DISTINCT threadId FROM messages")
    fun getAllThreadIds(): List<String>

    @Query("SELECT * FROM messages WHERE id = :packetId")
    fun getByPacketId(packetId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE threadId = :threadId AND id = :packetId LIMIT 1")
    fun getOneInThread(threadId: String, packetId: String): MessageEntity?

    @Query("SELECT messages.* FROM messages JOIN messages_fts ON (messages.rowid = messages_fts.rowid) WHERE messages_fts MATCH :ftsQuery ORDER BY messages.timestamp DESC LIMIT 200")
    fun searchFts(ftsQuery: String): List<MessageEntity>

    @Query("SELECT messages.* FROM messages JOIN messages_fts ON (messages.rowid = messages_fts.rowid) WHERE messages.threadId = :threadId AND messages_fts MATCH :ftsQuery ORDER BY messages.timestamp DESC LIMIT 200")
    fun searchFtsInThread(threadId: String, ftsQuery: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE expiresAtMs > 0 AND expiresAtMs <= :nowMs")
    fun deleteExpired(nowMs: Long): Int

    @Query("DELETE FROM messages WHERE threadId = :threadId")
    fun deleteThread(threadId: String): Int

    @Query("DELETE FROM messages")
    fun deleteAll(): Int
}

@Dao
interface GroupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(group: GroupEntity)

    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    fun getAllGroups(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE groupId = :groupId LIMIT 1")
    fun getGroup(groupId: String): GroupEntity?

    @Query("DELETE FROM groups WHERE groupId = :groupId")
    fun deleteGroup(groupId: String): Int

    @Query("DELETE FROM groups")
    fun deleteAll(): Int
}

@Dao
interface DtnDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(bundle: DtnBundleEntity)

    @Query("SELECT * FROM dtn_bundles WHERE custodyAcked = 0 AND (expiresAtMs = 0 OR expiresAtMs > :nowMs) ORDER BY priority DESC, createdAtMs ASC")
    fun getActiveBundlesPrioritized(nowMs: Long): List<DtnBundleEntity>

    @Query("SELECT * FROM dtn_bundles ORDER BY priority ASC, createdAtMs ASC")
    fun getAllLowestPriorityFirst(): List<DtnBundleEntity>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM dtn_bundles")
    fun getTotalStoredBytes(): Long

    @Query("SELECT COUNT(*) FROM dtn_bundles")
    fun getTotalCount(): Int

    @Query("UPDATE dtn_bundles SET custodyAcked = 1 WHERE packetId = :packetId")
    fun markCustodyAcked(packetId: String): Int

    @Query("DELETE FROM dtn_bundles WHERE (expiresAtMs > 0 AND expiresAtMs <= :nowMs) OR custodyAcked = 1")
    fun pruneExpiredOrAcked(nowMs: Long): Int

    @Query("DELETE FROM dtn_bundles WHERE packetId = :packetId")
    fun deleteById(packetId: String): Int

    @Query("DELETE FROM dtn_bundles")
    fun deleteAll(): Int
}

@Dao
interface RatchetSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(session: RatchetSessionEntity)

    @Query("SELECT * FROM ratchet_sessions WHERE peerId = :peerId LIMIT 1")
    fun getSession(peerId: String): RatchetSessionEntity?

    @Query("SELECT * FROM ratchet_sessions")
    fun getAllSessions(): List<RatchetSessionEntity>

    @Query("DELETE FROM ratchet_sessions WHERE peerId = :peerId")
    fun deleteSession(peerId: String): Int

    @Query("DELETE FROM ratchet_sessions")
    fun deleteAll(): Int
}
