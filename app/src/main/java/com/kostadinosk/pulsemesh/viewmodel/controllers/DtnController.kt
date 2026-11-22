// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.viewmodel.controllers

import com.kostadinosk.pulsemesh.service.DtnCourierEngine
import com.kostadinosk.pulsemesh.service.WireMeshPacket

/**
 * Modular DTN Courier & Epidemic Bloom Sync Controller delegated by [MeshViewModel].
 */
class DtnController(
    private val dtnEngine: DtnCourierEngine
) {
    companion object {
        const val TYPE_DTN_BLOOM_SYNC = "DTN_BLOOM_SYNC"
        const val TYPE_DTN_CUSTODY_ACK = "DTN_CUSTODY_ACK"
    }

    fun enqueue(packet: WireMeshPacket): Int {
        if (packet.type == "CHAT" || packet.type == "E2EE_DM" ||
            packet.type == "GROUP_CHAT" || packet.type == "GROUP_INVITE" || packet.type == "SOS"
        ) {
            dtnEngine.enqueueBundle(packet)
        }
        return dtnEngine.getActiveCount()
    }

    fun buildBloomSyncPacket(myNodeId: String, myCallsign: String, targetNodeId: String = "ALL"): WireMeshPacket {
        return WireMeshPacket(
            packetId = "dtn_bloom_${myNodeId}_${System.currentTimeMillis() and 0xFFFF}",
            srcNodeId = myNodeId,
            srcCallsign = myCallsign,
            dstNodeId = targetNodeId,
            type = TYPE_DTN_BLOOM_SYNC,
            payload = dtnEngine.buildBloomSyncPayload(),
            ttl = 1,
            hops = 0
        )
    }

    fun buildCustodyAckPacket(
        myNodeId: String,
        myCallsign: String,
        targetNodeId: String,
        ackedPacketId: String
    ): WireMeshPacket = WireMeshPacket(
        packetId = "dtn_cack_${ackedPacketId}_${System.currentTimeMillis() and 0xFFFF}",
        srcNodeId = myNodeId,
        srcCallsign = myCallsign,
        dstNodeId = targetNodeId,
        type = TYPE_DTN_CUSTODY_ACK,
        payload = ackedPacketId,
        ttl = 3,
        hops = 0
    )

    fun onRemoteBloomSyncReceived(remoteBloomBase64: String): List<WireMeshPacket> {
        return dtnEngine.computeMissingBundlesForPeer(remoteBloomBase64)
    }

    fun onCustodyAckReceived(packetId: String): Int {
        dtnEngine.handleCustodyAck(packetId)
        return dtnEngine.getActiveCount()
    }

    fun activeBundles(): List<WireMeshPacket> = dtnEngine.getActiveBundles()
    fun activeCount(): Int = dtnEngine.getActiveCount()
    fun storedBytes(): Long = dtnEngine.getTotalStoredBytes()
    fun wipeAll() = dtnEngine.wipeAll()
}
