// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.crypto

import java.security.MessageDigest

/**
 * Signal-inspired symmetric 60-digit Safety Number generator for PulseMesh v2.0.
 * Both peers independently derive the exact same 60-digit number (12 groups of 5 digits)
 * from their sorted (nodeId + publicKey) pair using iterated SHA-512.
 */
object SafetyNumberUtil {
    private const val HASH_ITERATIONS = 5200
    private const val VERSION_BYTE: Byte = 0x02

    fun compute60DigitSafetyNumber(
        localNodeId: String,
        localPublicKey: String,
        peerNodeId: String,
        peerPublicKey: String
    ): String {
        val localDigits = derive30Digits(localNodeId.trim().uppercase(), localPublicKey.trim())
        val peerDigits = derive30Digits(peerNodeId.trim().uppercase(), peerPublicKey.trim())
        val combined = if (localDigits <= peerDigits) localDigits + peerDigits else peerDigits + localDigits
        return combined.chunked(5).joinToString(" ")
    }

    fun buildVerificationQrPayload(
        localNodeId: String,
        peerNodeId: String,
        safetyNumber: String
    ): String {
        val compact = safetyNumber.replace(" ", "")
        return "pulsemesh://verify?from=${localNodeId.trim()}&peer=${peerNodeId.trim()}&sn=$compact"
    }

    fun verifyScannedQrPayload(
        scannedRaw: String,
        expectedSafetyNumber: String
    ): Boolean {
        val trimmed = scannedRaw.trim()
        if (!trimmed.startsWith("pulsemesh://verify")) return false
        val snParam = trimmed.substringAfter("sn=", "").substringBefore("&").replace(" ", "")
        val expected = expectedSafetyNumber.replace(" ", "")
        return snParam.length == 60 && snParam == expected
    }

    private fun derive30Digits(nodeId: String, publicKey: String): String {
        val md = MessageDigest.getInstance("SHA-512")
        val idBytes = nodeId.toByteArray(Charsets.UTF_8)
        val keyBytes = publicKey.toByteArray(Charsets.UTF_8)

        md.update(byteArrayOf(0x00, VERSION_BYTE))
        md.update(keyBytes)
        md.update(idBytes)
        var digest = md.digest()

        repeat(HASH_ITERATIONS - 1) {
            md.reset()
            md.update(digest)
            md.update(keyBytes)
            digest = md.digest()
        }

        // Convert 6 x 5-byte chunks into 6 x 5-digit blocks (30 digits)
        val sb = StringBuilder(30)
        for (chunk in 0 until 6) {
            val offset = chunk * 5
            var value = 0L
            for (b in 0 until 5) {
                value = (value shl 8) or (digest[offset + b].toLong() and 0xFFL)
            }
            sb.append("%05d".format(value % 100_000L))
        }
        return sb.toString()
    }
}
