// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.util

import android.graphics.Bitmap
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject

sealed class QrPairingAction {
    data class AddContact(
        val nodeId: String,
        val callsign: String,
        val publicKeyBase64: String = "",
        val username: String = ""
    ) : QrPairingAction()

    data class VerifySafetyNumber(
        val nodeId: String,
        val expectedFingerprint: String
    ) : QrPairingAction()

    data class JoinGroup(
        val groupId: String,
        val groupName: String,
        val keyBase64: String,
        val keyEpoch: Int = 1,
        val memberIds: List<String> = emptyList(),
        val inviterNodeId: String = "",
        val inviterCallsign: String = "",
        val inviterPublicKeyBase64: String = "",
        val inviterUsername: String = ""
    ) : QrPairingAction()

    data class JoinWifiHotspot(
        val ssid: String,
        val passphrase: String,
        val portalUrl: String = ""
    ) : QrPairingAction()
}

/**
 * Decodes CameraX / ZXing / NFC Tap-to-Pair payloads across contacts, safety numbers,
 * group invites, and Wi-Fi Hotspot QR credentials.
 */
object QrPairingRouter {

    fun parsePayload(raw: String): QrPairingAction? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        // 1. Standard Wi-Fi QR (`WIFI:T:WPA;S:...;P:...;;`)
        if (text.startsWith("WIFI:", ignoreCase = true)) {
            return parseWifiQr(text)
        }

        // 2. PulseMesh Deep-Link URI (`pulsemesh://peer?...`, `pulsemesh://verify?...`, `pulsemesh://hotspot?...`)
        if (text.startsWith("pulsemesh://", ignoreCase = true)) {
            return parseUriPayload(text)
        }

        // 3. JSON Envelope (`pmGroupInvite`, contact card, verify payload, hotspot JSON)
        if (text.startsWith("{")) {
            return parseJsonPayload(text)
        }

        return null
    }

    private fun parseWifiQr(text: String): QrPairingAction.JoinWifiHotspot? {
        val body = text.substring(5)
        val fields = HashMap<String, String>()
        val cur = StringBuilder()
        var escaped = false
        for (ch in body) {
            if (escaped) {
                cur.append(ch)
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == ';') {
                val item = cur.toString()
                val idx = item.indexOf(':')
                if (idx > 0) {
                    fields[item.substring(0, idx).uppercase()] = item.substring(idx + 1)
                }
                cur.setLength(0)
            } else {
                cur.append(ch)
            }
        }
        val ssid = fields["S"].orEmpty().trim()
        if (ssid.isBlank()) return null
        val pass = fields["P"].orEmpty()
        val url = fields["PORTAL"] ?: fields["U"].orEmpty()
        return QrPairingAction.JoinWifiHotspot(ssid = ssid, passphrase = pass, portalUrl = url)
    }

    private fun parseUriPayload(text: String): QrPairingAction? {
        return runCatching {
            val uri = Uri.parse(text)
            val host = uri.host.orEmpty().lowercase()
            when (host) {
                "join" -> {
                    val id = (uri.getQueryParameter("id") ?: uri.getQueryParameter("node")).orEmpty().trim()
                    val callsign = (uri.getQueryParameter("cs") ?: uri.getQueryParameter("callsign")).orEmpty().trim().ifBlank { id }
                    val pub = (uri.getQueryParameter("pk") ?: uri.getQueryParameter("pub")).orEmpty().trim()
                    val user = (uri.getQueryParameter("u") ?: uri.getQueryParameter("user")).orEmpty().trim()
                    val gid = uri.getQueryParameter("gid").orEmpty().trim()
                    val gn = uri.getQueryParameter("gn").orEmpty().trim().ifBlank { if (gid.isNotBlank()) "Group $gid" else "" }
                    val gk = uri.getQueryParameter("gk").orEmpty().trim()
                    when {
                        gid.isNotBlank() -> QrPairingAction.JoinGroup(
                            groupId = gid,
                            groupName = gn,
                            keyBase64 = gk,
                            memberIds = listOfNotNull(id.takeIf { it.isNotBlank() }),
                            inviterNodeId = id,
                            inviterCallsign = callsign,
                            inviterPublicKeyBase64 = pub,
                            inviterUsername = user
                        )
                        id.isNotBlank() -> QrPairingAction.AddContact(
                            nodeId = id,
                            callsign = callsign,
                            publicKeyBase64 = pub,
                            username = user
                        )
                        else -> null
                    }
                }
                "peer", "contact" -> {
                    val id = uri.getQueryParameter("id").orEmpty().trim()
                    if (id.isBlank()) null
                    else QrPairingAction.AddContact(
                        nodeId = id,
                        callsign = uri.getQueryParameter("callsign").orEmpty().ifBlank { id },
                        publicKeyBase64 = uri.getQueryParameter("pub").orEmpty(),
                        username = uri.getQueryParameter("user").orEmpty()
                    )
                }
                "verify" -> {
                    val id = uri.getQueryParameter("id").orEmpty().trim()
                    val fp = uri.getQueryParameter("fp").orEmpty().trim()
                    if (id.isBlank() || fp.isBlank()) null
                    else QrPairingAction.VerifySafetyNumber(nodeId = id, expectedFingerprint = fp)
                }
                "hotspot", "wifi" -> {
                    val ssid = uri.getQueryParameter("ssid").orEmpty().trim()
                    if (ssid.isBlank()) null
                    else QrPairingAction.JoinWifiHotspot(
                        ssid = ssid,
                        passphrase = uri.getQueryParameter("pass").orEmpty(),
                        portalUrl = uri.getQueryParameter("url").orEmpty()
                    )
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun parseJsonPayload(text: String): QrPairingAction? {
        return runCatching {
            val obj = JSONObject(text)
            val type = obj.optString("type").lowercase()
            when {
                type == "pmgroupinvite" || obj.has("groupId") -> {
                    val gid = obj.optString("groupId").trim()
                    val key = obj.optString("groupKeyBase64", obj.optString("keyBase64")).trim()
                    if (gid.isBlank()) return null
                    val members = mutableListOf<String>()
                    obj.optJSONArray("memberIds")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val m = arr.optString(i).trim()
                            if (m.isNotBlank()) members.add(m)
                        }
                    }
                    QrPairingAction.JoinGroup(
                        groupId = gid,
                        groupName = obj.optString("groupName", gid),
                        keyBase64 = key,
                        keyEpoch = obj.optInt("keyEpoch", 1),
                        memberIds = members
                    )
                }
                obj.has("expectedFingerprint") || obj.has("fp") -> {
                    val id = obj.optString("nodeId").trim()
                    val fp = obj.optString("expectedFingerprint", obj.optString("fp")).trim()
                    if (id.isBlank() || fp.isBlank()) null
                    else QrPairingAction.VerifySafetyNumber(id, fp)
                }
                obj.has("nodeId") -> {
                    val id = obj.optString("nodeId").trim()
                    if (id.isBlank()) null
                    else QrPairingAction.AddContact(
                        nodeId = id,
                        callsign = obj.optString("callsign", id),
                        publicKeyBase64 = obj.optString("publicKeyBase64", obj.optString("pub")),
                        username = obj.optString("username", "")
                    )
                }
                else -> null
            }
        }.getOrNull()
    }

    fun decodeQrFromBitmap(bitmap: Bitmap): String? {
        return runCatching {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return null
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            MultiFormatReader().decode(binaryBitmap).text
        }.getOrNull()
    }
}
