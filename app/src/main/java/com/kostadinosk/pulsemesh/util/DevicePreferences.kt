// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.util

import android.content.Context
import android.content.SharedPreferences
import com.kostadinosk.pulsemesh.crypto.AppLockTimeout
import com.kostadinosk.pulsemesh.crypto.KeystoreMasterKeyManager
import com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager
import com.kostadinosk.pulsemesh.model.LocationShareMode
import java.security.SecureRandom

enum class AppTheme(val displayName: String) {
    ORIGINAL("Original Classic"),
    FRUTIGER_AERO("Frutiger Aero"),
    FRUTIGER_AERO_DARK("Frutiger Aero Dark"),
    MATERIAL3("Material 3"),
    TACTICAL_DARK("Tactical Dark"),
    TACTICAL_LIGHT("Daylight Light")
}

class DevicePreferences(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pulsemesh_production_v11", Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    var operatorCallsign: String
        get() = prefs.getString("callsign", "User-" + nodeId.takeLast(4)) ?: ("User-" + nodeId.takeLast(4))
        set(value) = prefs.edit().putString("callsign", value).apply()

    var nodeId: String
        get() {
            var id = prefs.getString("node_id", null)
            if (id.isNullOrBlank() || id == "0x4F2A") {
                id = if (TacticalE2eeManager.isValidEcPublicKeyBase64(x25519PublicKey)) {
                    TacticalE2eeManager.derive64BitNodeId(x25519PublicKey)
                } else {
                    generateRandomNodeId()
                }
                prefs.edit().putString("node_id", id).apply()
            }
            return id
        }
        set(value) = prefs.edit().putString("node_id", value).apply()

    fun regenerateNodeId(): String {
        val newId = generateRandomNodeId()
        nodeId = newId
        return newId
    }

    private fun generateRandomNodeId(): String {
        val bytes = ByteArray(8)
        secureRandom.nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02X".format(it) }
        return "0x$hex"
    }

    var appTheme: AppTheme
        get() {
            val saved = prefs.getString("app_theme", AppTheme.TACTICAL_DARK.name)
            return runCatching { AppTheme.valueOf(saved ?: AppTheme.TACTICAL_DARK.name) }
                .getOrDefault(AppTheme.TACTICAL_DARK)
        }
        set(value) = prefs.edit().putString("app_theme", value.name).apply()

    var meshChannel: String
        get() = prefs.getString("mesh_channel", "pulsemesh_live_primary") ?: "pulsemesh_live_primary"
        set(value) = prefs.edit().putString("mesh_channel", value).apply()

    var deviceProfileName: String
        get() = prefs.getString("device_profile", "OnePlus 15")
            ?: "OnePlus 15"
        set(value) = prefs.edit().putString("device_profile", value).apply()

    var selectedProfileId: String
        get() = prefs.getString("selected_profile_id", "oneplus_15") ?: "oneplus_15"
        set(value) = prefs.edit().putString("selected_profile_id", value).apply()

    var isMismatchOverrideConfirmed: Boolean
        get() = prefs.getBoolean("mismatch_override_confirmed", false)
        set(value) = prefs.edit().putBoolean("mismatch_override_confirmed", value).apply()

    var isPureOffGrid: Boolean
        get() = prefs.getBoolean("pure_off_grid", true)
        set(value) = prefs.edit().putBoolean("pure_off_grid", value).apply()

    var internetGatewayBridgeEnabled: Boolean
        get() = prefs.getBoolean("internet_gateway_bridge", false)
        set(value) = prefs.edit().putBoolean("internet_gateway_bridge", value).apply()

    var isInternetEnabled: Boolean
        get() = prefs.getBoolean("is_internet_enabled", false)
        set(value) = prefs.edit().putBoolean("is_internet_enabled", value).apply()

    var isHotspotAutoPopupEnabled: Boolean
        get() = prefs.getBoolean("hotspot_auto_popup_enabled", true)
        set(value) = prefs.edit().putBoolean("hotspot_auto_popup_enabled", value).apply()

    var autoCheckInIntervalMin: Int
        get() = prefs.getInt("auto_checkin_interval_min", 0)
        set(value) = prefs.edit().putInt("auto_checkin_interval_min", value).apply()

    var isSpokenCallerIdEnabled: Boolean
        get() = prefs.getBoolean("spoken_caller_id_enabled", false)
        set(value) = prefs.edit().putBoolean("spoken_caller_id_enabled", value).apply()

    var isFirstLaunchCompleted: Boolean
        get() = prefs.getBoolean("first_launch_done", false)
        set(value) = prefs.edit().putBoolean("first_launch_done", value).apply()

    var isSimpleMode: Boolean
        get() = prefs.getBoolean("is_simple_mode", true)
        set(value) = prefs.edit().putBoolean("is_simple_mode", value).apply()

    var locationShareMode: LocationShareMode
        get() {
            val raw = prefs.getString("location_share_mode", LocationShareMode.EXACT.name)
            return runCatching { LocationShareMode.valueOf(raw ?: LocationShareMode.EXACT.name) }
                .getOrDefault(LocationShareMode.EXACT)
        }
        set(value) {
            prefs.edit()
                .putString("location_share_mode", value.name)
                .putBoolean("broadcast_coarse_location_ble", value == LocationShareMode.COARSE)
                .apply()
        }

    var broadcastCoarseLocationInBle: Boolean
        get() = prefs.getBoolean("broadcast_coarse_location_ble", false)
        set(value) = prefs.edit().putBoolean("broadcast_coarse_location_ble", value).apply()

    var meshPassphrase: String
        get() = prefs.getString("mesh_passphrase", "pulsemesh-tactical") ?: "pulsemesh-tactical"
        set(value) = prefs.edit().putString("mesh_passphrase", value.ifBlank { "pulsemesh-tactical" }).apply()

    var x25519PublicKey: String
        get() = prefs.getString("x25519_pub", "") ?: ""
        set(value) = prefs.edit().putString("x25519_pub", value).apply()

    var x25519PrivateKey: String
        get() {
            val wrapped = prefs.getString("ks_ec_priv_v15", null)
            if (!wrapped.isNullOrBlank()) {
                val plain = KeystoreMasterKeyManager.unwrapString(appContext, wrapped)
                if (!plain.isNullOrBlank()) return plain
            }
            val legacy = prefs.getString("x25519_priv", "") ?: ""
            if (legacy.isNotBlank()) {
                val rewrapped = KeystoreMasterKeyManager.wrapString(appContext, legacy)
                prefs.edit().putString("ks_ec_priv_v15", rewrapped).remove("x25519_priv").apply()
                return legacy
            }
            return ""
        }
        set(value) {
            val clean = value.trim()
            val wrapped = if (clean.isNotEmpty()) KeystoreMasterKeyManager.wrapString(appContext, clean) else ""
            prefs.edit().putString("ks_ec_priv_v15", wrapped).remove("x25519_priv").apply()
        }

    var fingerprint: String
        get() = prefs.getString("x25519_fp", "") ?: ""
        set(value) = prefs.edit().putString("x25519_fp", value).apply()

    var encryptionKeyEpoch: Int
        get() = prefs.getInt("encryption_key_epoch", 1)
        set(value) = prefs.edit().putInt("encryption_key_epoch", value.coerceAtLeast(1)).apply()

    fun rotateEncryptionKey(): Int {
        val kp = TacticalE2eeManager.generateKeyPair()
        x25519PublicKey = kp.publicKeyBase64
        x25519PrivateKey = kp.privateKeyBase64
        fingerprint = kp.fingerprint
        val nextEpoch = encryptionKeyEpoch + 1
        prefs.edit().putInt("encryption_key_epoch", nextEpoch).apply()
        return nextEpoch
    }

    private fun normalizePeerKeyId(nodeId: String): String {
        val clean = nodeId.trim().uppercase().removePrefix("0X")
        return when {
            clean.length > 4 -> clean.takeLast(16)
            else -> clean.padStart(4, '0').takeLast(4)
        }
    }

    fun getPeerPublicKey(nodeId: String): String? {
        val norm = normalizePeerKeyId(nodeId)
        val stored = prefs.getString("peer_pubkey_$norm", null)
            ?: if (norm.length > 4) prefs.getString("peer_pubkey_${norm.takeLast(4)}", null) else null
        return stored?.takeIf { TacticalE2eeManager.isValidEcPublicKeyBase64(it) }
    }

    fun isPeerKeyChanged(nodeId: String): Boolean {
        val norm = normalizePeerKeyId(nodeId)
        return prefs.getBoolean("peer_key_changed_$norm", false)
    }

    fun getPendingChangedPeerPublicKey(nodeId: String): String? {
        val norm = normalizePeerKeyId(nodeId)
        return prefs.getString("peer_pending_pubkey_$norm", null)
            ?.takeIf { TacticalE2eeManager.isValidEcPublicKeyBase64(it) }
    }

    /**
     * Trust-On-First-Use (TOFU) verification.
     * Stores the peer's first-seen EC P-256 public key in SharedPreferences (`peer_pubkey_$nodeId`).
     * Returns Pair(isAcceptedForCrypto, isKeyChangedWarning).
     * If a previously stored key changes, keeps first-seen TOFU key until explicitly verified/trusted,
     * records pending key, and flags warning = true.
     */
    fun verifyOrTrustPeerPublicKey(nodeId: String, incomingPubKeyBase64: String): Pair<Boolean, Boolean> {
        val cleanKey = incomingPubKeyBase64.trim()
        if (!TacticalE2eeManager.isValidEcPublicKeyBase64(cleanKey)) return false to false
        val norm = normalizePeerKeyId(nodeId)
        val existing = prefs.getString("peer_pubkey_$norm", null)
        return if (existing.isNullOrBlank() || !TacticalE2eeManager.isValidEcPublicKeyBase64(existing)) {
            prefs.edit()
                .putString("peer_pubkey_$norm", cleanKey)
                .putBoolean("peer_key_changed_$norm", false)
                .remove("peer_pending_pubkey_$norm")
                .apply()
            true to false
        } else if (existing == cleanKey) {
            true to prefs.getBoolean("peer_key_changed_$norm", false)
        } else {
            prefs.edit()
                .putBoolean("peer_key_changed_$norm", true)
                .putString("peer_pending_pubkey_$norm", cleanKey)
                .apply()
            false to true
        }
    }

    fun acceptChangedPeerPublicKey(nodeId: String): String? {
        val norm = normalizePeerKeyId(nodeId)
        val pending = getPendingChangedPeerPublicKey(nodeId)
        if (!pending.isNullOrBlank()) {
            prefs.edit()
                .putString("peer_pubkey_$norm", pending)
                .putBoolean("peer_key_changed_$norm", false)
                .remove("peer_pending_pubkey_$norm")
                .apply()
            return pending
        }
        prefs.edit().putBoolean("peer_key_changed_$norm", false).apply()
        return getPeerPublicKey(nodeId)
    }

    fun getAllPeerPublicKeys(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in prefs.all) {
            if (k.startsWith("peer_pubkey_") && v is String && TacticalE2eeManager.isValidEcPublicKeyBase64(v)) {
                out[k.removePrefix("peer_pubkey_")] = v.trim()
            }
        }
        return out
    }

    fun restorePeerPublicKeys(keys: Map<String, String>) {
        if (keys.isEmpty()) return
        val editor = prefs.edit()
        for ((rawId, pubKey) in keys) {
            val cleanKey = pubKey.trim()
            if (!TacticalE2eeManager.isValidEcPublicKeyBase64(cleanKey)) continue
            val norm = normalizePeerKeyId(rawId)
            editor.putString("peer_pubkey_$norm", cleanKey)
                .putBoolean("peer_key_changed_$norm", false)
                .remove("peer_pending_pubkey_$norm")
        }
        editor.apply()
    }

    fun getGroupAesKey(groupId: String): String? {
        val clean = groupId.trim()
        if (clean.isBlank()) return null
        return prefs.getString("group_aes_key_$clean", null)
            ?.takeIf { TacticalE2eeManager.isValidGroupAesKeyBase64(it) }
    }

    fun saveGroupAesKey(groupId: String, keyBase64: String) {
        val clean = groupId.trim()
        if (clean.isBlank() || !TacticalE2eeManager.isValidGroupAesKeyBase64(keyBase64)) return
        prefs.edit().putString("group_aes_key_$clean", keyBase64.trim()).apply()
    }

    /**
     * Store-and-Forward "Data Mule" Outbox (DTN):
     * Persists up to 50 recent CHAT / E2EE_DM packets in SharedPreferences so if a node is out of range,
     * messages are automatically carried and delivered when a peer enters range.
     */
    fun loadPendingDtnPackets(): List<com.kostadinosk.pulsemesh.service.WireMeshPacket> {
        val raw = prefs.getString("pending_dtn_packets_json", null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        val list = mutableListOf<com.kostadinosk.pulsemesh.service.WireMeshPacket>()
        runCatching {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val pkt = com.kostadinosk.pulsemesh.service.WireMeshPacket(
                    packetId = obj.optString("packetId"),
                    srcNodeId = obj.optString("srcNodeId"),
                    srcCallsign = obj.optString("srcCallsign"),
                    dstNodeId = obj.optString("dstNodeId", "ALL"),
                    type = obj.optString("type", "CHAT"),
                    payload = obj.optString("payload"),
                    ttl = obj.optInt("ttl", 7),
                    hops = obj.optInt("hops", 0),
                    lat = if (obj.has("lat")) obj.getDouble("lat") else null,
                    lng = if (obj.has("lng")) obj.getDouble("lng") else null,
                    altitude = if (obj.has("altitude")) obj.getDouble("altitude") else null,
                    battery = if (obj.has("battery")) obj.getInt("battery") else null,
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    groupId = obj.optString("groupId", ""),
                    groupName = obj.optString("groupName", ""),
                    memberIdsCsv = obj.optString("memberIdsCsv", ""),
                    senderPubKey = obj.optString("senderPubKey", ""),
                    encryptedKeysJson = obj.optString("encryptedKeysJson", "")
                )
                if (pkt.packetId.isNotBlank() && pkt.payload.isNotBlank()) {
                    list.add(pkt)
                }
            }
        }
        return list.takeLast(50)
    }

    fun savePendingDtnPackets(packets: List<com.kostadinosk.pulsemesh.service.WireMeshPacket>) {
        val capped = packets
            .filter {
                it.type == "CHAT" ||
                    it.type == "E2EE_DM" ||
                    it.type == "GROUP_CHAT" ||
                    it.type == "GROUP_INVITE"
            }
            .distinctBy { it.packetId }
            .takeLast(50)
        val arr = org.json.JSONArray()
        for (pkt in capped) {
            val obj = org.json.JSONObject().apply {
                put("packetId", pkt.packetId)
                put("srcNodeId", pkt.srcNodeId)
                put("srcCallsign", pkt.srcCallsign)
                put("dstNodeId", pkt.dstNodeId)
                put("type", pkt.type)
                put("payload", pkt.payload)
                put("ttl", pkt.ttl)
                put("hops", pkt.hops)
                pkt.lat?.let { put("lat", it) }
                pkt.lng?.let { put("lng", it) }
                pkt.altitude?.let { put("altitude", it) }
                pkt.battery?.let { put("battery", it) }
                put("timestamp", pkt.timestamp)
                if (pkt.groupId.isNotBlank()) put("groupId", pkt.groupId)
                if (pkt.groupName.isNotBlank()) put("groupName", pkt.groupName)
                if (pkt.memberIdsCsv.isNotBlank()) put("memberIdsCsv", pkt.memberIdsCsv)
                if (pkt.senderPubKey.isNotBlank()) put("senderPubKey", pkt.senderPubKey)
                if (pkt.encryptedKeysJson.isNotBlank()) put("encryptedKeysJson", pkt.encryptedKeysJson)
            }
            arr.put(obj)
        }
        prefs.edit().putString("pending_dtn_packets_json", arr.toString()).apply()
    }

    fun loadMeshGroups(): List<com.kostadinosk.pulsemesh.model.MeshGroup> {
        val raw = prefs.getString("mesh_groups_json", null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        val list = mutableListOf<com.kostadinosk.pulsemesh.model.MeshGroup>()
        runCatching {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val groupId = obj.optString("groupId").trim()
                val groupName = obj.optString("groupName").trim()
                val memberIdsCsv = obj.optString("memberIdsCsv").trim()
                val members = memberIdsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
                val groupKeyB64 = obj.optString("groupKeyBase64", "").ifBlank {
                    getGroupAesKey(groupId) ?: ""
                }
                if (groupId.isNotBlank() && groupName.isNotBlank()) {
                    if (groupKeyB64.isNotBlank()) {
                        saveGroupAesKey(groupId, groupKeyB64)
                    }
                    list.add(
                        com.kostadinosk.pulsemesh.model.MeshGroup(
                            groupId = groupId,
                            groupName = groupName,
                            memberIds = members,
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                            groupKeyBase64 = groupKeyB64
                        )
                    )
                }
            }
        }
        return list
    }

    fun saveMeshGroups(groups: List<com.kostadinosk.pulsemesh.model.MeshGroup>) {
        val arr = org.json.JSONArray()
        for (grp in groups.distinctBy { it.groupId }) {
            if (grp.groupKeyBase64.isNotBlank()) {
                saveGroupAesKey(grp.groupId, grp.groupKeyBase64)
            }
            val obj = org.json.JSONObject().apply {
                put("groupId", grp.groupId)
                put("groupName", grp.groupName)
                put("memberIdsCsv", grp.memberIds.joinToString(","))
                put("createdAt", grp.createdAt)
                if (grp.groupKeyBase64.isNotBlank()) {
                    put("groupKeyBase64", grp.groupKeyBase64)
                }
            }
            arr.put(obj)
        }
        prefs.edit().putString("mesh_groups_json", arr.toString()).apply()
    }

    // =========================================================================
    // UNIQUE USERNAME RESERVATION, PIN & RECOVERY KEY ACCOUNT PERSISTENCE
    // =========================================================================

    var claimedUsername: String
        get() = prefs.getString("claimed_username", "") ?: ""
        set(value) = prefs.edit().putString("claimed_username", TacticalE2eeManager.normalizeUsername(value)).apply()

    var accountSaltHex: String
        get() = prefs.getString("account_salt_hex", "") ?: ""
        set(value) = prefs.edit().putString("account_salt_hex", value.trim()).apply()

    var accountPinHash: String
        get() = prefs.getString("account_pin_hash", "") ?: ""
        set(value) = prefs.edit().putString("account_pin_hash", value.trim()).apply()

    var accountRecoveryKey: String
        get() = prefs.getString("account_recovery_key", "") ?: ""
        set(value) = prefs.edit().putString("account_recovery_key", value.trim()).apply()

    var accountRecoveryHash: String
        get() = prefs.getString("account_recovery_hash", "") ?: ""
        set(value) = prefs.edit().putString("account_recovery_hash", value.trim()).apply()

    var pendingUsernameHold: String
        get() = prefs.getString("pending_username_hold", "") ?: ""
        set(value) = prefs.edit().putString("pending_username_hold", TacticalE2eeManager.normalizeUsername(value)).apply()

    var isUsernameOnHold: Boolean
        get() = prefs.getBoolean("is_username_on_hold", false)
        set(value) = prefs.edit().putBoolean("is_username_on_hold", value).apply()

    companion object {
        const val DEFAULT_CLOUDFLARE_RELAY_URL = "https://pulsemesh-username-relay.pulsemesh.workers.dev"
    }

    var usernameRelayUrl: String
        get() = prefs.getString("username_relay_url", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_CLOUDFLARE_RELAY_URL
        set(value) = prefs.edit().putString("username_relay_url", value.trim().trimEnd('/')).apply()

    // PulseMesh Field Preferences: Language (en/el), ICE Medical, Dead-Man's Switch, Geofence, Expedition, Decoy PIN, Per-Chat Burn TTL
    var appLanguage: String
        get() = prefs.getString("app_language", "en")?.takeIf { it == "el" || it == "en" } ?: "en"
        set(value) = prefs.edit().putString("app_language", if (value == "el") "el" else "en").apply()

    var iceBloodType: String
        get() = prefs.getString("ice_blood_type", "") ?: ""
        set(value) = prefs.edit().putString("ice_blood_type", value.trim().take(16)).apply()

    var iceAllergies: String
        get() = prefs.getString("ice_allergies", "") ?: ""
        set(value) = prefs.edit().putString("ice_allergies", value.trim().take(120)).apply()

    var iceNotes: String
        get() = prefs.getString("ice_notes", "") ?: ""
        set(value) = prefs.edit().putString("ice_notes", value.trim().take(160)).apply()

    var iceEmergencyContact: String
        get() = prefs.getString("ice_emergency_contact", "") ?: ""
        set(value) = prefs.edit().putString("ice_emergency_contact", value.trim().take(80)).apply()

    var deadManSwitchMinutes: Int
        get() = prefs.getInt("dead_man_switch_min", 0)
        set(value) = prefs.edit().putInt("dead_man_switch_min", value.coerceIn(0, 720)).apply()

    var geofenceEnabled: Boolean
        get() = prefs.getBoolean("geofence_enabled", false)
        set(value) = prefs.edit().putBoolean("geofence_enabled", value).apply()

    var geofenceLat: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong("geofence_lat_bits", 0L))
        set(value) = prefs.edit().putLong("geofence_lat_bits", java.lang.Double.doubleToRawLongBits(value)).apply()

    var geofenceLng: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong("geofence_lng_bits", 0L))
        set(value) = prefs.edit().putLong("geofence_lng_bits", java.lang.Double.doubleToRawLongBits(value)).apply()

    var geofenceRadiusMeters: Int
        get() = prefs.getInt("geofence_radius_m", 500)
        set(value) = prefs.edit().putInt("geofence_radius_m", value.coerceIn(50, 25_000)).apply()

    var expeditionModeEnabled: Boolean
        get() = prefs.getBoolean("expedition_mode_enabled", false)
        set(value) = prefs.edit().putBoolean("expedition_mode_enabled", value).apply()

    var expeditionAutoBelow20: Boolean
        get() = prefs.getBoolean("expedition_auto_below_20", true)
        set(value) = prefs.edit().putBoolean("expedition_auto_below_20", value).apply()

    var quickStatusVoiceEnabled: Boolean
        get() = prefs.getBoolean("quick_status_voice_enabled", true)
        set(value) = prefs.edit().putBoolean("quick_status_voice_enabled", value).apply()

    var decoyPinHash: String
        get() = prefs.getString("decoy_pin_hash", "") ?: ""
        set(value) = prefs.edit().putString("decoy_pin_hash", value).apply()

    fun getPerThreadBurnTtlMap(): Map<String, Int> {
        val raw = prefs.getString("per_thread_burn_ttl_json", null) ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        runCatching {
            val obj = org.json.JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.optInt(k, 0)
            }
        }
        return out
    }

    fun setPerThreadBurnTtl(threadId: String, ttlSeconds: Int) {
        val map = getPerThreadBurnTtlMap().toMutableMap()
        if (ttlSeconds < 0) map.remove(threadId) else map[threadId] = ttlSeconds
        val obj = org.json.JSONObject()
        for ((k, v) in map) obj.put(k, v)
        prefs.edit().putString("per_thread_burn_ttl_json", obj.toString()).apply()
    }

    var accountAuthToken: String
        get() {
            val wrapped = prefs.getString("ks_auth_token_v15", null)
            if (!wrapped.isNullOrBlank()) {
                val plain = KeystoreMasterKeyManager.unwrapString(appContext, wrapped)
                if (!plain.isNullOrBlank()) return plain
            }
            val legacy = prefs.getString("account_auth_token", "") ?: ""
            if (legacy.isNotBlank()) {
                val rewrapped = KeystoreMasterKeyManager.wrapString(appContext, legacy)
                prefs.edit().putString("ks_auth_token_v15", rewrapped).remove("account_auth_token").apply()
                return legacy
            }
            return ""
        }
        set(value) {
            val clean = value.trim()
            val wrapped = if (clean.isNotEmpty()) KeystoreMasterKeyManager.wrapString(appContext, clean) else ""
            prefs.edit().putString("ks_auth_token_v15", wrapped).remove("account_auth_token").apply()
        }

    var appLockTimeout: AppLockTimeout
        get() = AppLockTimeout.fromName(prefs.getString("app_lock_timeout", AppLockTimeout.OFF.name))
        set(value) = prefs.edit().putString("app_lock_timeout", value.name).apply()

    var appLockPinHash: String
        get() = prefs.getString("app_lock_pin_hash", "") ?: ""
        set(value) = prefs.edit().putString("app_lock_pin_hash", value.trim()).apply()

    var appLockPinSaltHex: String
        get() = prefs.getString("app_lock_pin_salt_hex", "") ?: ""
        set(value) = prefs.edit().putString("app_lock_pin_salt_hex", value.trim()).apply()

    var isBiometricUnlockEnabled: Boolean
        get() = prefs.getBoolean("biometric_unlock_enabled", false)
        set(value) = prefs.edit().putBoolean("biometric_unlock_enabled", value).apply()

    var batteryPowerProfile: com.kostadinosk.pulsemesh.model.BatteryPowerProfile
        get() = com.kostadinosk.pulsemesh.model.BatteryPowerProfile.fromName(
            prefs.getString("battery_power_profile", com.kostadinosk.pulsemesh.model.BatteryPowerProfile.BALANCED.name)
        )
        set(value) = prefs.edit().putString("battery_power_profile", value.name).apply()

    var showTacticalHud: Boolean
        get() = prefs.getBoolean("show_tactical_hud", false)
        set(value) {
            prefs.edit()
                .putBoolean("show_tactical_hud", value)
                .putBoolean("is_simple_mode", !value)
                .apply()
        }

    var isHideLockedPreviewsEnabled: Boolean
        get() = prefs.getBoolean("hide_locked_previews_enabled", true)
        set(value) = prefs.edit().putBoolean("hide_locked_previews_enabled", value).apply()

    var lastUsernameInboxCursor: Long
        get() = prefs.getLong("last_username_inbox_cursor", 0L)
        set(value) = prefs.edit().putLong("last_username_inbox_cursor", value).apply()

    var lastUsernameClaimRefreshAt: Long
        get() = prefs.getLong("last_username_claim_refresh_at", 0L)
        set(value) = prefs.edit().putLong("last_username_claim_refresh_at", value).apply()

    var lastAccountBackupSyncAt: Long
        get() = prefs.getLong("last_account_backup_sync_at", 0L)
        set(value) = prefs.edit().putLong("last_account_backup_sync_at", value).apply()

    fun getAllKnownUsernameClaims(): List<com.kostadinosk.pulsemesh.crypto.UsernameClaimRecord> {
        val raw = prefs.getString("known_username_claims_json", null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        val list = mutableListOf<com.kostadinosk.pulsemesh.crypto.UsernameClaimRecord>()
        runCatching {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                com.kostadinosk.pulsemesh.crypto.UsernameClaimRecord.fromJson(obj)?.let { list.add(it) }
            }
        }
        return list
    }

    fun getUsernameClaim(username: String): com.kostadinosk.pulsemesh.crypto.UsernameClaimRecord? {
        val norm = TacticalE2eeManager.normalizeUsername(username)
        if (norm.isBlank()) return null
        return getAllKnownUsernameClaims().firstOrNull { it.username == norm }
    }

    fun findUsernameClaimByNodeId(nodeId: String): com.kostadinosk.pulsemesh.crypto.UsernameClaimRecord? {
        val normId = normalizePeerKeyId(nodeId)
        if (normId.isBlank()) return null
        val claims = getAllKnownUsernameClaims()
        return claims.firstOrNull { normalizePeerKeyId(it.nodeId) == normId }
            ?: claims.firstOrNull {
                val cNorm = normalizePeerKeyId(it.nodeId)
                (cNorm.length <= 4 || normId.length <= 4) && cNorm.takeLast(4) == normId.takeLast(4)
            }
    }

    fun saveUsernameClaim(record: com.kostadinosk.pulsemesh.crypto.UsernameClaimRecord) {
        val norm = TacticalE2eeManager.normalizeUsername(record.username)
        if (norm.length < 3) return
        val existing = getAllKnownUsernameClaims().filterNot { it.username == norm }
        val updated = (existing + record.copy(username = norm)).takeLast(200)
        val arr = org.json.JSONArray()
        for (item in updated) {
            arr.put(item.toJson())
        }
        prefs.edit().putString("known_username_claims_json", arr.toString()).apply()
        if (record.publicKeyBase64.isNotBlank()) {
            verifyOrTrustPeerPublicKey(record.nodeId, record.publicKeyBase64)
        }
    }

    /**
     * Automatic migration & on-demand purge removing any legacy bot username claims,
     * bot peer public keys, and bot-named mesh groups left over from earlier builds
     * without touching real user chats or account data.
     */
    fun purgeLegacyBotRecords(): Int {
        var removed = 0
        val allClaims = getAllKnownUsernameClaims()
        val cleanClaims = allClaims.filterNot { claim ->
            com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.isBlockedFakeCallsign(claim.username) ||
                com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.isBlockedFakeCallsign(claim.displayName)
        }
        if (cleanClaims.size < allClaims.size) {
            val removedIds = allClaims.filter { it !in cleanClaims }.map { it.nodeId }
            removed += (allClaims.size - cleanClaims.size)
            val arr = org.json.JSONArray()
            cleanClaims.forEach { arr.put(it.toJson()) }
            val editor = prefs.edit().putString("known_username_claims_json", arr.toString())
            for (rid in removedIds) {
                val norm = rid.trim().uppercase().removePrefix("0X")
                if (norm.isNotBlank()) {
                    editor.remove("peer_pubkey_$norm")
                    editor.remove("peer_pubkey_pending_$norm")
                }
            }
            editor.apply()
        }

        val allGroups = loadMeshGroups()
        val cleanGroups = allGroups.filterNot { grp ->
            com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.isBlockedFakeCallsign(grp.groupName)
        }
        if (cleanGroups.size < allGroups.size) {
            removed += (allGroups.size - cleanGroups.size)
            saveMeshGroups(cleanGroups)
        }
        return removed
    }

    // =========================================================================
    // PULSEMESH v1.1 TACTICAL UPGRADES PERSISTENCE
    // =========================================================================

    var isBackgroundRelayServiceEnabled: Boolean
        get() = prefs.getBoolean("background_relay_service_enabled", true)
        set(value) = prefs.edit().putBoolean("background_relay_service_enabled", value).apply()

    var isVolumeButtonPttEnabled: Boolean
        get() = prefs.getBoolean("volume_button_ptt_enabled", true)
        set(value) = prefs.edit().putBoolean("volume_button_ptt_enabled", value).apply()

    var isRadioVoxBridgeEnabled: Boolean
        get() = prefs.getBoolean("radio_vox_bridge_enabled", false)
        set(value) = prefs.edit().putBoolean("radio_vox_bridge_enabled", value).apply()

    var isDeadReckoningEnabled: Boolean
        get() = prefs.getBoolean("dead_reckoning_enabled", false)
        set(value) = prefs.edit().putBoolean("dead_reckoning_enabled", value).apply()

    var isBreadcrumbRecording: Boolean
        get() = prefs.getBoolean("breadcrumb_recording_enabled", true)
        set(value) = prefs.edit().putBoolean("breadcrumb_recording_enabled", value).apply()

    var messageBurnTtlSeconds: Int
        get() = prefs.getInt("message_burn_ttl_seconds", 0)
        set(value) = prefs.edit().putInt("message_burn_ttl_seconds", value.coerceAtLeast(0)).apply()

    var duressPinHash: String
        get() = prefs.getString("duress_pin_hash", "") ?: ""
        set(value) = prefs.edit().putString("duress_pin_hash", value.trim()).apply()

    fun loadBreadcrumbs(): List<com.kostadinosk.pulsemesh.model.BreadcrumbPoint> {
        val raw = prefs.getString("breadcrumbs_json", null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        val list = mutableListOf<com.kostadinosk.pulsemesh.model.BreadcrumbPoint>()
        runCatching {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val lat = obj.optDouble("lat", Double.NaN)
                val lon = obj.optDouble("lon", Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) {
                    list.add(
                        com.kostadinosk.pulsemesh.model.BreadcrumbPoint(
                            lat = lat,
                            lon = lon,
                            altM = obj.optDouble("altM", 0.0),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
            }
        }
        return list.takeLast(500)
    }

    fun saveBreadcrumbs(points: List<com.kostadinosk.pulsemesh.model.BreadcrumbPoint>) {
        val capped = points.takeLast(500)
        val arr = org.json.JSONArray()
        for (pt in capped) {
            arr.put(
                org.json.JSONObject().apply {
                    put("lat", pt.lat)
                    put("lon", pt.lon)
                    put("altM", pt.altM)
                    put("timestamp", pt.timestamp)
                }
            )
        }
        prefs.edit().putString("breadcrumbs_json", arr.toString()).apply()
    }

    fun deleteAccountInformation(targetUsername: String = ""): Boolean {
        val normTarget = TacticalE2eeManager.normalizeUsername(targetUsername)
        val normActive = TacticalE2eeManager.normalizeUsername(claimedUsername)
        val isDeletingActive = normTarget.isBlank() || normTarget == normActive

        // Filter out any cached known claims matching the target username
        val filteredClaims = getAllKnownUsernameClaims().filterNot { claim ->
            val cNorm = TacticalE2eeManager.normalizeUsername(claim.username)
            (normTarget.isNotBlank() && cNorm == normTarget) ||
                (isDeletingActive && normActive.isNotBlank() && cNorm == normActive)
        }
        val arr = org.json.JSONArray()
        for (item in filteredClaims) arr.put(item.toJson())

        val editor = prefs.edit()
            .putString("known_username_claims_json", arr.toString())

        if (isDeletingActive) {
            val kp = TacticalE2eeManager.generateKeyPair()
            val newId = TacticalE2eeManager.derive64BitNodeId(kp.publicKeyBase64)
            val wrappedPriv = KeystoreMasterKeyManager.wrapString(appContext, kp.privateKeyBase64)
            editor
                .remove("claimed_username")
                .remove("account_salt_hex")
                .remove("account_pin_hash")
                .remove("account_recovery_key")
                .remove("account_recovery_hash")
                .remove("pending_username_hold")
                .remove("is_username_on_hold")
                .remove("account_auth_token")
                .remove("ks_auth_token_v15")
                .remove("x25519_priv")
                .remove("pending_dtn_packets_json")
                .remove("mesh_groups_json")
                .remove("breadcrumbs_json")
                .putString("node_id", newId)
                .putString("callsign", "User-" + newId.takeLast(4))
                .putString("x25519_pub", kp.publicKeyBase64)
                .putString("ks_ec_priv_v15", wrappedPriv)
                .putString("x25519_fp", kp.fingerprint)
        }
        editor.apply()
        return isDeletingActive
    }

    fun wipeSensitiveVaultForDuress() {
        val kp = TacticalE2eeManager.generateKeyPair()
        val newId = TacticalE2eeManager.derive64BitNodeId(kp.publicKeyBase64)
        val wrappedPriv = KeystoreMasterKeyManager.wrapString(appContext, kp.privateKeyBase64)
        prefs.edit()
            .remove("claimed_username")
            .remove("account_salt_hex")
            .remove("account_pin_hash")
            .remove("account_recovery_key")
            .remove("account_recovery_hash")
            .remove("pending_username_hold")
            .remove("is_username_on_hold")
            .remove("account_auth_token")
            .remove("ks_auth_token_v15")
            .remove("x25519_priv")
            .remove("pending_dtn_packets_json")
            .remove("mesh_groups_json")
            .remove("breadcrumbs_json")
            .remove("known_username_claims_json")
            .putString("node_id", newId)
            .putString("callsign", "User-" + newId.takeLast(4))
            .putString("x25519_pub", kp.publicKeyBase64)
            .putString("ks_ec_priv_v15", wrappedPriv)
            .putString("x25519_fp", kp.fingerprint)
            .apply()
    }

    init {
        // Ensure real EC P-256 (secp256r1) keypair is initialized & Keystore-wrapped
        if (!TacticalE2eeManager.isValidEcPublicKeyBase64(x25519PublicKey) ||
            !TacticalE2eeManager.isValidEcPrivateKeyBase64(x25519PrivateKey)
        ) {
            val keyPair = TacticalE2eeManager.generateKeyPair()
            x25519PublicKey = keyPair.publicKeyBase64
            x25519PrivateKey = keyPair.privateKeyBase64
            fingerprint = keyPair.fingerprint
        }

        // Ensure Keystore migration triggers even if keypair already existed in legacy plaintext prefs
        val currentPriv = x25519PrivateKey
        if (currentPriv.isNotBlank() && prefs.contains("x25519_priv")) {
            x25519PrivateKey = currentPriv
        }
        val currentTok = accountAuthToken
        if (currentTok.isNotBlank() && prefs.contains("account_auth_token")) {
            accountAuthToken = currentTok
        }

        // Initialize or upgrade local node ID to collision-resistant 64-bit key-derived ID if unset/default/16-bit
        val storedId = prefs.getString("node_id", null)?.trim().orEmpty()
        val hexPart = storedId.uppercase().removePrefix("0X")
        if (storedId.isBlank() || storedId == "0x4F2A" || (hexPart.length <= 4 && TacticalE2eeManager.isValidEcPublicKeyBase64(x25519PublicKey))) {
            val derivedId = TacticalE2eeManager.derive64BitNodeId(x25519PublicKey)
            prefs.edit().putString("node_id", derivedId).apply()
        }
    }
}

