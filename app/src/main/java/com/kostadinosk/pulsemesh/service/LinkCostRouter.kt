// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Scored Link-Cost Router with ACK-Backed Automatic Failover (PulseMesh v1.4.0).
 *
 * Replaces blind all-radio spraying with deterministic per-peer link scoring:
 *   `WIFI_NAN_USB (cost 10)` < `BLE_L2CAP (cost 25)` < `BLE_GATT (cost 50)` < `HYBRID_RELAY (cost 75)`
 *
 * - Reserves full all-radio fan-out strictly for emergency `SOS` / `SOS_CLEAR` and live call signaling
 *   (`CALL_RING`, `CALL_INVITE`, `CALL_ACCEPT`, `CALL_DECLINE`, `CALL_END`).
 * - Tracks per-peer `PMB2` binary capability (auto-negotiated when a peer sends a `PMB2` frame or `pmv>=2`).
 * - Records delivery ACK successes (lowering cost) and timeouts/failures (penalizing link cost so the
 *   next attempt immediately fails over to the next best radio).
 */
class LinkCostRouter {

    enum class TransportTier(val baseCost: Int, val label: String) {
        WIFI_NAN_USB(10, "Wi-Fi / NAN / USB"),
        BLE_L2CAP(25, "BLE L2CAP CoC"),
        BLE_GATT(50, "BLE GATT Mesh"),
        HYBRID_RELAY(75, "Hybrid E2EE Relay")
    }

    data class RoutePlan(
        val orderedTiers: List<TransportTier>,
        val fanOutAll: Boolean,
        val useCompactBinary: Boolean,
        val reason: String
    )

    data class PeerLinkHealth(
        val normalizedPeerId: String,
        val supportsPmb2: Boolean = false,
        val seenTiers: Map<TransportTier, Long> = emptyMap(),
        val failurePenalties: Map<TransportTier, Int> = emptyMap(),
        val rssiDbm: Int = -65
    )

    data class RouterStats(
        val totalRoutedPackets: Int = 0,
        val failoverEscalations: Int = 0,
        val binaryBytesSaved: Long = 0L,
        val pmb2PeerCount: Int = 0
    )

    companion object {
        private const val TIER_FRESHNESS_MS = 90_000L
        private val CRITICAL_FANOUT_TYPES = setOf(
            "SOS", "SOS_CLEAR",
            "CALL_RING", "CALL_INVITE", "CALL_ACCEPT", "CALL_ACCEPT_ACK", "CALL_DECLINE", "CALL_END",
            "WEBRTC_OFFER", "WEBRTC_ANSWER", "WEBRTC_ICE"
        )
    }

    private val peerHealthMap = ConcurrentHashMap<String, PeerLinkHealth>()
    private val totalRouted = AtomicInteger(0)
    private val failoverCount = AtomicInteger(0)
    private val bytesSaved = AtomicLong(0L)

    private fun norm(id: String): String {
        val clean = id.trim().uppercase().removePrefix("0X").removePrefix("NODE-").removePrefix("PEER-")
        return if (clean.length > 4) clean.takeLast(16) else clean
    }

    fun recordPeerObserved(
        peerNodeId: String,
        tier: TransportTier,
        supportsPmb2: Boolean = false,
        rssiDbm: Int? = null
    ) {
        val key = norm(peerNodeId)
        if (key.isBlank()) return
        val now = System.currentTimeMillis()
        peerHealthMap.compute(key) { _, prev ->
            val base = prev ?: PeerLinkHealth(normalizedPeerId = key)
            val nextSeen = base.seenTiers.toMutableMap().apply { put(tier, now) }
            val nextPenalties = base.failurePenalties.toMutableMap().apply {
                val cur = get(tier) ?: 0
                if (cur > 0) put(tier, (cur - 10).coerceAtLeast(0))
            }
            base.copy(
                supportsPmb2 = base.supportsPmb2 || supportsPmb2,
                seenTiers = nextSeen,
                failurePenalties = nextPenalties,
                rssiDbm = rssiDbm ?: base.rssiDbm
            )
        }
    }

    fun recordAckSuccess(peerNodeId: String, tier: TransportTier) {
        val key = norm(peerNodeId)
        if (key.isBlank()) return
        val now = System.currentTimeMillis()
        peerHealthMap.compute(key) { _, prev ->
            val base = prev ?: PeerLinkHealth(normalizedPeerId = key)
            val nextSeen = base.seenTiers.toMutableMap().apply { put(tier, now) }
            val nextPenalties = base.failurePenalties.toMutableMap().apply { put(tier, 0) }
            base.copy(seenTiers = nextSeen, failurePenalties = nextPenalties)
        }
    }

    fun recordDeliveryTimeout(peerNodeId: String, tier: TransportTier) {
        failoverCount.incrementAndGet()
        val key = norm(peerNodeId)
        if (key.isBlank()) return
        peerHealthMap.compute(key) { _, prev ->
            val base = prev ?: PeerLinkHealth(normalizedPeerId = key)
            val nextPenalties = base.failurePenalties.toMutableMap().apply {
                put(tier, ((get(tier) ?: 0) + 35).coerceAtMost(140))
            }
            base.copy(failurePenalties = nextPenalties)
        }
    }

    fun recordBinarySavings(jsonBytes: Int, binaryBytes: Int) {
        val saved = (jsonBytes - binaryBytes).coerceAtLeast(0)
        if (saved > 0) bytesSaved.addAndGet(saved.toLong())
    }

    fun peerSupportsPmb2(peerNodeId: String): Boolean {
        val key = norm(peerNodeId)
        return peerHealthMap[key]?.supportsPmb2 == true
    }

    /**
     * Computes the ordered transport route plan for an outbound packet.
     */
    fun planRoute(
        packet: WireMeshPacket,
        hasLocalWifiOrNan: Boolean,
        hasBleL2cap: Boolean,
        hasBleGatt: Boolean,
        isInternetEnabled: Boolean
    ): RoutePlan {
        totalRouted.incrementAndGet()
        val typeUpper = packet.type.uppercase()
        val dstUpper = packet.dstNodeId.trim().uppercase()
        val isBroadcast = dstUpper.isBlank() || dstUpper == "ALL" || dstUpper == "BROADCAST" || dstUpper == "ENC_TACNET"

        val available = mutableListOf<TransportTier>()
        if (hasLocalWifiOrNan) available += TransportTier.WIFI_NAN_USB
        if (hasBleL2cap) available += TransportTier.BLE_L2CAP
        if (hasBleGatt) available += TransportTier.BLE_GATT
        if (isInternetEnabled) available += TransportTier.HYBRID_RELAY
        if (available.isEmpty()) available += TransportTier.BLE_GATT

        // 1. Critical SOS & Call Signaling ALWAYS fan out across all active radios immediately
        if (typeUpper in CRITICAL_FANOUT_TYPES) {
            return RoutePlan(
                orderedTiers = available,
                fanOutAll = true,
                useCompactBinary = !isBroadcast && peerSupportsPmb2(packet.dstNodeId),
                reason = "Critical $typeUpper fan-out"
            )
        }

        // 2. Broadcasts use local high-speed + BLE + optional Internet relay
        if (isBroadcast) {
            return RoutePlan(
                orderedTiers = available,
                fanOutAll = true,
                useCompactBinary = peerHealthMap.values.any { it.supportsPmb2 },
                reason = "Broadcast mesh flood"
            )
        }

        // 3. Directed DM / peer-specific packet: rank active links by dynamic cost
        val peer = peerHealthMap[norm(packet.dstNodeId)]
        val now = System.currentTimeMillis()
        val scored = available.map { tier ->
            var score = tier.baseCost
            val lastSeen = peer?.seenTiers?.get(tier)
            if (lastSeen != null && (now - lastSeen) <= TIER_FRESHNESS_MS) {
                score -= 12 // Fresh confirmed link on this transport
            } else if (peer != null && peer.seenTiers.isNotEmpty()) {
                score += 22 // Peer has been seen elsewhere, not recently on this tier
            }
            score += peer?.failurePenalties?.get(tier) ?: 0
            if (tier == TransportTier.BLE_GATT && (peer?.rssiDbm ?: -65) < -85) {
                score += 12 // Weak BLE signal prefers Wi-Fi/L2CAP/Relay
            }
            tier to score
        }.sortedBy { it.second }.map { it.first }

        return RoutePlan(
            orderedTiers = scored,
            fanOutAll = false,
            useCompactBinary = peer?.supportsPmb2 == true,
            reason = "Best link: ${scored.first().label} (failover chain: ${scored.joinToString(" -> ") { it.name }})"
        )
    }

    fun getStats(): RouterStats {
        return RouterStats(
            totalRoutedPackets = totalRouted.get(),
            failoverEscalations = failoverCount.get(),
            binaryBytesSaved = bytesSaved.get(),
            pmb2PeerCount = peerHealthMap.values.count { it.supportsPmb2 }
        )
    }
}
