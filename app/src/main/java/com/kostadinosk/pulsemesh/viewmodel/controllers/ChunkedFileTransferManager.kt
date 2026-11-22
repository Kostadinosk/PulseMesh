// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.viewmodel.controllers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.kostadinosk.pulsemesh.data.db.MediaBlobStore
import com.kostadinosk.pulsemesh.service.WireMeshPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

enum class FileTransferDirection { OUTBOUND, INBOUND }
enum class FileTransferState { QUEUED, ACTIVE, PAUSED, COMPLETED, FAILED }

data class FileTransferProgress(
    val transferId: String,
    val threadId: String,
    val fileName: String,
    val mimeType: String,
    val totalBytes: Long,
    val totalChunks: Int,
    val completedChunks: Int,
    val sha256: String,
    val direction: FileTransferDirection,
    val state: FileTransferState,
    val blobPath: String = ""
) {
    val fraction: Float
        get() = if (totalChunks <= 0) 0f else (completedChunks.toFloat() / totalChunks.toFloat()).coerceIn(0f, 1f)
}

/**
 * Chunked Resumable File & Adaptive HD Photo Transfer Manager (PulseMesh v1.5.0).
 *
 * - Adaptive photo compression: up to 1600px JPEG q82 on high-speed links (Wi-Fi / L2CAP / WebRTC),
 *   auto-downscaled to 320px JPEG q55 when constrained to BLE GATT.
 * - Splits arbitrary binary files (PDF, GPX, APK, ZIP, voice notes) into SHA-256 verified 32 KB chunks
 *   with pause/resume, missing-chunk resume requests (`FILE_RESUME`), and encrypted at-rest storage
 *   inside [MediaBlobStore].
 */
class ChunkedFileTransferManager(context: Context) {

    companion object {
        const val CHUNK_SIZE_BYTES = 32 * 1024
        const val TYPE_FILE_OFFER = "FILE_OFFER"
        const val TYPE_FILE_CHUNK = "FILE_CHUNK"
        const val TYPE_FILE_ACK = "FILE_ACK"
        const val TYPE_FILE_RESUME = "FILE_RESUME"
    }

    private val blobStore = MediaBlobStore(context)

    private val _transfers = MutableStateFlow<Map<String, FileTransferProgress>>(emptyMap())
    val transfers: StateFlow<Map<String, FileTransferProgress>> = _transfers.asStateFlow()

    private class OutboundSession(
        val progress: FileTransferProgress,
        val rawBytes: ByteArray,
        val targetNodeId: String,
        val acked: BitSet = BitSet(progress.totalChunks)
    )

    private class InboundSession(
        val progress: FileTransferProgress,
        val chunks: Array<ByteArray?> = arrayOfNulls(progress.totalChunks)
    )

    private val outboundMap = ConcurrentHashMap<String, OutboundSession>()
    private val inboundMap = ConcurrentHashMap<String, InboundSession>()

    fun prepareAdaptivePhotoBytes(rawJpegOrPng: ByteArray, isHighSpeedLink: Boolean): ByteArray {
        val maxDim = if (isHighSpeedLink) 1600 else 320
        val quality = if (isHighSpeedLink) 82 else 55
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(rawJpegOrPng, 0, rawJpegOrPng.size)
        }.getOrNull() ?: return rawJpegOrPng

        return try {
            val scale = minOf(1.0f, maxDim.toFloat() / maxOf(decoded.width, decoded.height).coerceAtLeast(1))
            val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
            val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
            val scaled = if (scale < 0.99f) Bitmap.createScaledBitmap(decoded, targetW, targetH, true) else decoded
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            baos.toByteArray()
        } catch (_: Exception) {
            rawJpegOrPng
        }
    }

    fun startOutboundTransfer(
        transferId: String,
        threadId: String,
        targetNodeId: String,
        fileName: String,
        mimeType: String,
        rawBytes: ByteArray,
        chunkSize: Int = CHUNK_SIZE_BYTES
    ): List<WireMeshPacket> {
        val sha256 = sha256Hex(rawBytes)
        val (blobPath, _) = blobStore.storeEncryptedBlob(rawBytes)
        val effectiveChunkSize = chunkSize.coerceIn(512, CHUNK_SIZE_BYTES)
        val totalChunks = ((rawBytes.size + effectiveChunkSize - 1) / effectiveChunkSize).coerceAtLeast(1)

        val progress = FileTransferProgress(
            transferId = transferId,
            threadId = threadId,
            fileName = fileName,
            mimeType = mimeType,
            totalBytes = rawBytes.size.toLong(),
            totalChunks = totalChunks,
            completedChunks = 0,
            sha256 = sha256,
            direction = FileTransferDirection.OUTBOUND,
            state = FileTransferState.ACTIVE,
            blobPath = blobPath
        )
        outboundMap[transferId] = OutboundSession(progress, rawBytes, targetNodeId)
        updateUiTransfer(progress)

        val packets = mutableListOf<WireMeshPacket>()
        val offerPayload = JSONObject().apply {
            put("tid", transferId)
            put("threadId", threadId)
            put("name", fileName)
            put("mime", mimeType)
            put("size", rawBytes.size.toLong())
            put("chunks", totalChunks)
            put("chunkSize", effectiveChunkSize)
            put("sha256", sha256)
        }.toString()
        packets.add(
            WireMeshPacket(
                packetId = "foff_${transferId}",
                srcNodeId = "",
                srcCallsign = "",
                dstNodeId = targetNodeId,
                type = TYPE_FILE_OFFER,
                payload = offerPayload
            )
        )
        for (idx in 0 until totalChunks) {
            val start = idx * effectiveChunkSize
            val end = minOf(rawBytes.size, start + effectiveChunkSize)
            val slice = rawBytes.copyOfRange(start, end)
            val chunkPayload = JSONObject().apply {
                put("tid", transferId)
                put("idx", idx)
                put("total", totalChunks)
                put("data", Base64.encodeToString(slice, Base64.NO_WRAP))
            }.toString()
            packets.add(
                WireMeshPacket(
                    packetId = "fchk_${transferId}_$idx",
                    srcNodeId = "",
                    srcCallsign = "",
                    dstNodeId = targetNodeId,
                    type = TYPE_FILE_CHUNK,
                    payload = chunkPayload
                )
            )
        }
        return packets
    }

    fun pauseTransfer(transferId: String) {
        val current = _transfers.value[transferId] ?: return
        if (current.state == FileTransferState.COMPLETED) return
        updateUiTransfer(current.copy(state = FileTransferState.PAUSED))
    }

    fun buildResumeMissingChunks(transferId: String): List<WireMeshPacket> {
        val inbound = inboundMap[transferId] ?: return emptyList()
        val missing = mutableListOf<Int>()
        for (i in inbound.chunks.indices) {
            if (inbound.chunks[i] == null) missing.add(i)
        }
        if (missing.isEmpty()) return emptyList()
        val resumeJson = JSONObject().apply {
            put("tid", transferId)
            put("missingCsv", missing.joinToString(","))
        }.toString()
        return listOf(
            WireMeshPacket(
                packetId = "fres_${transferId}_${System.currentTimeMillis()}",
                srcNodeId = "",
                srcCallsign = "",
                dstNodeId = "ALL",
                type = TYPE_FILE_RESUME,
                payload = resumeJson
            )
        )
    }

    fun handleIncomingOffer(payloadJson: String, fallbackThreadId: String): FileTransferProgress? {
        val obj = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return null
        val tid = obj.optString("tid")
        val name = obj.optString("name", "file.bin")
        val mime = obj.optString("mime", "application/octet-stream")
        val totalBytes = obj.optLong("size", 0L)
        val totalChunks = obj.optInt("chunks", 0)
        val sha256 = obj.optString("sha256")
        val threadId = obj.optString("threadId").ifBlank { fallbackThreadId }
        if (tid.isBlank() || totalChunks <= 0 || totalChunks > 4096) return null

        val progress = FileTransferProgress(
            transferId = tid,
            threadId = threadId,
            fileName = name,
            mimeType = mime,
            totalBytes = totalBytes,
            totalChunks = totalChunks,
            completedChunks = 0,
            sha256 = sha256,
            direction = FileTransferDirection.INBOUND,
            state = FileTransferState.ACTIVE
        )
        inboundMap.putIfAbsent(tid, InboundSession(progress))
        updateUiTransfer(progress)
        return progress
    }

    /**
     * Stores an incoming chunk. When all chunks arrive and match SHA-256, writes the reassembled
     * payload encrypted to [MediaBlobStore] and returns completed [FileTransferProgress].
     */
    fun handleIncomingChunk(payloadJson: String): FileTransferProgress? {
        val obj = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return null
        val tid = obj.optString("tid")
        val idx = obj.optInt("idx", -1)
        val b64 = obj.optString("data")
        val session = inboundMap[tid] ?: return null
        if (idx !in 0 until session.chunks.size || b64.isBlank()) return null

        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return null
        if (session.chunks[idx] == null) {
            session.chunks[idx] = bytes
        }
        val receivedCount = session.chunks.count { it != null }
        if (receivedCount < session.chunks.size) {
            val updated = session.progress.copy(completedChunks = receivedCount, state = FileTransferState.ACTIVE)
            updateUiTransfer(updated)
            return null
        }

        val baos = ByteArrayOutputStream()
        for (slice in session.chunks) {
            if (slice == null) return null
            baos.write(slice)
        }
        val fullBytes = baos.toByteArray()
        val actualHash = sha256Hex(fullBytes)
        if (session.progress.sha256.isNotBlank() && !actualHash.equals(session.progress.sha256, ignoreCase = true)) {
            val failed = session.progress.copy(state = FileTransferState.FAILED)
            updateUiTransfer(failed)
            return failed
        }

        val (blobPath, _) = blobStore.storeEncryptedBlob(fullBytes)
        val completed = session.progress.copy(
            completedChunks = session.chunks.size,
            state = FileTransferState.COMPLETED,
            blobPath = blobPath
        )
        updateUiTransfer(completed)
        return completed
    }

    fun handleIncomingAck(payloadJson: String) {
        val obj = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return
        val tid = obj.optString("tid")
        val out = outboundMap[tid] ?: return
        val done = out.progress.copy(
            completedChunks = out.progress.totalChunks,
            state = FileTransferState.COMPLETED
        )
        updateUiTransfer(done)
    }

    fun readEncryptedAttachment(blobPath: String): ByteArray? = blobStore.readDecryptedBlob(blobPath)

    private fun updateUiTransfer(progress: FileTransferProgress) {
        _transfers.update { cur -> cur + (progress.transferId to progress) }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
