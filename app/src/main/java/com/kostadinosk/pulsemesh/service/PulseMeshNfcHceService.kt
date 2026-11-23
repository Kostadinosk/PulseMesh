// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.service

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Stores the active PulseMesh NFC pairing URI (`pulsemesh://peer?...`) served over Android HCE
 * when two unlocked devices tap back-to-back.
 */
object NfcPairingPayloadStore {
    @Volatile
    var activePairingPayload: String = ""
}

/**
 * Stateless ISO 7816-4 APDU protocol handler for PulseMesh Tap-to-Pair HCE (`AID F050554C53454D455348`).
 */
object NfcPairingProtocol {

    const val AID_HEX = "F050554C53454D455348"
    val SELECT_APDU_PREFIX = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00)
    val CMD_GET_IDENTITY = byteArrayOf(0x80.toByte(), 0x01, 0x00, 0x00)
    val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    val SW_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
    val SW_UNKNOWN = byteArrayOf(0x6F, 0x00)

    fun buildSelectAidCommand(): ByteArray {
        val aidBytes = hexToBytes(AID_HEX)
        return SELECT_APDU_PREFIX + byteArrayOf(aidBytes.size.toByte()) + aidBytes
    }

    fun processCommandApdu(commandApdu: ByteArray?, payloadOverride: String? = null): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) return SW_UNKNOWN
        val selectAid = buildSelectAidCommand()
        if (commandApdu.size >= selectAid.size && commandApdu.copyOf(selectAid.size).contentEquals(selectAid)) {
            return SW_OK
        }
        if (commandApdu.copyOf(4).contentEquals(CMD_GET_IDENTITY)) {
            val payload = (payloadOverride ?: NfcPairingPayloadStore.activePairingPayload).trim()
            if (payload.isEmpty()) return SW_NOT_FOUND
            val bytes = payload.toByteArray(Charsets.UTF_8).let {
                if (it.size > 245) it.copyOf(245) else it
            }
            return bytes + SW_OK
        }
        return SW_UNKNOWN
    }

    fun extractPayloadIfOk(responseApdu: ByteArray): String? {
        if (responseApdu.size < 2) return null
        val sw1 = responseApdu[responseApdu.size - 2]
        val sw2 = responseApdu[responseApdu.size - 1]
        if (sw1 != SW_OK[0] || sw2 != SW_OK[1]) return null
        if (responseApdu.size == 2) return ""
        return String(responseApdu, 0, responseApdu.size - 2, Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}

class PulseMeshNfcHceService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        return NfcPairingProtocol.processCommandApdu(commandApdu)
    }

    override fun onDeactivated(reason: Int) = Unit
}
