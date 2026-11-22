// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.kostadinosk.pulsemesh.crypto.AppLockManager
import com.kostadinosk.pulsemesh.crypto.AppLockTimeout
import com.kostadinosk.pulsemesh.crypto.DoubleRatchetEngine
import com.kostadinosk.pulsemesh.crypto.KeystoreMasterKeyManager
import com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager
import com.kostadinosk.pulsemesh.model.MeshPacket
import com.kostadinosk.pulsemesh.model.PacketType
import com.kostadinosk.pulsemesh.service.DtnCourierEngine
import com.kostadinosk.pulsemesh.service.WireMeshPacket
import com.kostadinosk.pulsemesh.model.BatteryPowerProfile
import com.kostadinosk.pulsemesh.util.DevicePreferences
import com.kostadinosk.pulsemesh.util.PersistentChatManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V150SecurityAndCoreTest {

    @Test
    fun `double ratchet enforces forward secrecy, out-of-order decryption, and replay rejection`() {
        DoubleRatchetEngine.clearAllSessions()
        val kpAlice = TacticalE2eeManager.generateKeyPair("alice_dr")
        val kpBob = TacticalE2eeManager.generateKeyPair("bob_dr")
        val rootSecret = TacticalE2eeManager.computeEcdhSharedSecret(kpAlice.privateKeyBase64, kpBob.publicKeyBase64)
        assertTrue(rootSecret.size == 32)

        DoubleRatchetEngine.initSessionAsInitiator("0xB0B0", rootSecret, kpBob.publicKeyBase64)
        val c0 = DoubleRatchetEngine.encrypt("0xB0B0", "msg_zero".toByteArray())!!
        val c1 = DoubleRatchetEngine.encrypt("0xB0B0", "msg_one".toByteArray())!!
        val c2 = DoubleRatchetEngine.encrypt("0xB0B0", "msg_two".toByteArray())!!
        assertTrue(c0.startsWith("PMDR2:") && c1.startsWith("PMDR2:") && c2.startsWith("PMDR2:"))
        assertNotEquals(c0, c1)

        val aliceSessionJson = DoubleRatchetEngine.exportSessionStateJson("0xB0B0")!!
        DoubleRatchetEngine.removeSession("0xB0B0")

        DoubleRatchetEngine.initSessionAsResponder("0xA11C", rootSecret, kpBob.privateKeyBase64, kpBob.publicKeyBase64)

        // Deliver out-of-order: c2 arrives before c1 and c0
        val p2 = DoubleRatchetEngine.decrypt("0xA11C", c2)
        assertNotNull(p2)
        assertEquals("msg_two", String(p2!!))

        val p0 = DoubleRatchetEngine.decrypt("0xA11C", c0)
        assertNotNull(p0)
        assertEquals("msg_zero", String(p0!!))

        val p1 = DoubleRatchetEngine.decrypt("0xA11C", c1)
        assertNotNull(p1)
        assertEquals("msg_one", String(p1!!))

        // Replay of already-consumed message key must fail closed
        assertNull("Replayed ratchet frame must be rejected", DoubleRatchetEngine.decrypt("0xA11C", c1))

        // Responder replies, stepping DH ratchet forward
        val replyCt = DoubleRatchetEngine.encrypt("0xA11C", "pong_from_bob".toByteArray())!!
        assertTrue(DoubleRatchetEngine.importSessionStateJson("0xB0B0", aliceSessionJson))
        val replyPt = DoubleRatchetEngine.decrypt("0xB0B0", replyCt)
        assertEquals("pong_from_bob", String(replyPt!!))

        // Tampered ciphertext rejected via AEAD header binding
        val tampered = c0.dropLast(4) + "AAAA"
        assertNull(DoubleRatchetEngine.decrypt("0xA11C", tampered))
        DoubleRatchetEngine.clearAllSessions()
    }

    @Test
    fun `keystore master key manager wraps and unwraps secrets fail-closed`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val secret = "super_secret_x25519_key_material_v150"
        val wrapped = KeystoreMasterKeyManager.wrapString(app, secret)
        assertTrue(KeystoreMasterKeyManager.isWrapped(wrapped))
        assertNotEquals(secret, wrapped)
        assertEquals(secret, KeystoreMasterKeyManager.unwrapString(app, wrapped))

        // Tampered ciphertext must return null cleanly
        assertNull(KeystoreMasterKeyManager.unwrapString(app, wrapped.dropLast(6) + "ZZZZZZ"))

        val dbPass = KeystoreMasterKeyManager.getOrCreateDatabasePassphrase(app)
        assertTrue(dbPass.size >= 32)
        assertArrayEquals(dbPass, KeystoreMasterKeyManager.getOrCreateDatabasePassphrase(app))
    }

    @Test
    fun `app lock manager enforces PIN verification and background timeout`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = DevicePreferences(app)
        val mgr = AppLockManager(prefs)
        mgr.clearAppLockCredentials()
        assertFalse(mgr.isAppLockActive())

        mgr.setAppLockPin("8420")
        prefs.appLockTimeout = AppLockTimeout.ONE_MIN
        assertTrue(mgr.isAppLockActive())

        mgr.onAppBackgrounded(nowMs = 1_000L)
        mgr.onAppForegrounded(nowMs = 20_000L) // within 1 minute
        assertFalse("Should remain unlocked inside timeout window", mgr.isLocked.value)

        mgr.onAppBackgrounded(nowMs = 1_000L)
        mgr.onAppForegrounded(nowMs = 70_000L) // past 1 minute
        assertTrue("Should lock after background timeout elapsed", mgr.isLocked.value)

        assertFalse(mgr.unlockWithPin("0000"))
        assertTrue(mgr.isLocked.value)
        assertTrue(mgr.unlockWithPin("8420"))
        assertFalse(mgr.isLocked.value)
    }

    @Test
    fun `zero trust crypto rejects plaintext and PMU1 downgrades, enforces strong passphrases and 64-bit node IDs`() {
        val kpAlice = TacticalE2eeManager.generateKeyPair("alice_zero")
        val kpBob = TacticalE2eeManager.generateKeyPair("bob_zero")

        // 1. Fail-closed on invalid keys (no plaintext fallback)
        assertNull(TacticalE2eeManager.encryptDmWithEcdh("secret", "", kpBob.publicKeyBase64))

        // 2. Reject unencrypted PLAINTEXT: and weak keys
        val outPlain = TacticalE2eeManager.decryptDmWithEcdh("PLAINTEXT:leaked", kpAlice.privateKeyBase64, kpBob.publicKeyBase64)
        assertFalse(outPlain.contains("leaked"))

        // 3. Reject legacy PMU1: username inbox envelopes
        assertNull(TacticalE2eeManager.decryptUsernameInboxEnvelope("PMU1:legacy_plaintext_blob", kpBob.privateKeyBase64, "bob_zero"))

        // 4. Vault passphrase strength >= 8 chars & 128-bit 8x4 recovery keys
        assertFalse(TacticalE2eeManager.isValidVaultPassphrase("1234"))
        assertTrue(TacticalE2eeManager.isValidVaultPassphrase("tactical8"))

        val rk = TacticalE2eeManager.generateRecoveryKey()
        assertTrue(Regex("^PMRK-([0-9A-F]{4}-){7}[0-9A-F]{4}$").matches(rk))

        // 5. 64-bit Node ID + compact 8-hex/4-hex display tag
        val id64 = TacticalE2eeManager.derive64BitNodeId(kpAlice.publicKeyBase64)
        assertTrue(Regex("^0x[0-9A-F]{16}$").matches(id64))
        assertTrue(Regex("^[0-9A-F]{4}·[0-9A-F]{4}$").matches(TacticalE2eeManager.shortNodeTag(id64)))
        assertEquals("A1B2", TacticalE2eeManager.shortNodeTag("0xA1B2"))
    }

    @Test
    fun `room persistent chat manager supports unlimited paged history, FTS search, edit, unsend, read receipts, and v7 migration`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val legacyFile = File(app.filesDir, "chat_history_v7.json")
        val legacyRoot = JSONObject().apply {
            put("threads", JSONObject().apply {
                put("dm_0xLEGACY", JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "leg_1")
                        put("sourceId", "0xLEGACY")
                        put("destId", "0xSELF")
                        put("sourceCallsign", "Scout-1")
                        put("type", "TEXT")
                        put("content", "Migrated mountain pass coordinates")
                        put("hops", 1)
                        put("rawHexBadge", "[BLE]")
                        put("isIncoming", true)
                        put("timestamp", System.currentTimeMillis())
                    })
                })
            })
        }
        legacyFile.writeText(legacyRoot.toString())

        val chatMgr = PersistentChatManager(app)
        chatMgr.wipeAllHistoryForDuress()
        legacyFile.writeText(legacyRoot.toString())
        val migratedMgr = PersistentChatManager(app)
        assertFalse("Legacy v7 JSON file should be deleted after Room migration", legacyFile.exists())
        val ftsMigrated = migratedMgr.searchPackets("mountain")
        assertTrue("FTS4 must find migrated Room message", ftsMigrated.any { it.second.id == "leg_1" })

        val testThread = "dm_0xROOMTEST"
        val pkt = MeshPacket(
            id = "msg_room_1",
            sourceId = "0xSELF",
            destId = "0xROOMTEST",
            sourceCallsign = "Me",
            type = PacketType.TEXT,
            content = "Original rally alpha",
            hops = 0,
            rawHexBadge = "[WIFI]",
            isIncoming = false
        )
        migratedMgr.savePacket(testThread, pkt)
        assertTrue(migratedMgr.editPacketContent(testThread, "msg_room_1", "Updated rally bravo"))
        assertTrue(migratedMgr.markPacketRead("msg_room_1"))
        val edited = migratedMgr.getPacketsForThread(testThread).first { it.id == "msg_room_1" }
        assertEquals("Updated rally bravo", edited.content)
        assertTrue(edited.editedAtMs > 0L)
        assertEquals("READ", edited.deliveryStatus)

        assertTrue(migratedMgr.unsendPacket("msg_room_1"))
        val unsent = migratedMgr.getPacketsForThread(testThread).first { it.id == "msg_room_1" }
        assertTrue(unsent.isUnsent)
        assertEquals("[Message unsent]", unsent.content)

        // Paged query past 200-item cache works cleanly from Room
        repeat(210) { i ->
            migratedMgr.savePacket(
                testThread,
                pkt.copy(id = "pg_$i", content = "Page item $i", timestamp = System.currentTimeMillis() + i)
            )
        }
        val secondPage = migratedMgr.getPacketsForThreadPaged(testThread, limit = 50, offset = 180)
        assertEquals(31, secondPage.size)
    }

    @Test
    fun `epidemic bloom DTN computes missing bundles differentially and deletes on custody ACK`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val aliceDtn = DtnCourierEngine(app)
        aliceDtn.wipeAll()

        val pSos = WireMeshPacket("dtn_sos_1", "0xA1", "Alice", "ALL", "SOS", "HELP")
        val pChat = WireMeshPacket("dtn_chat_1", "0xA1", "Alice", "0xB2", "CHAT", "Hi Bob")
        aliceDtn.enqueueBundle(pSos)
        aliceDtn.enqueueBundle(pChat)

        val bobKnownBloom = aliceDtn.buildBloomSyncPayload()
        assertEquals(0, aliceDtn.computeMissingBundlesForPeer(bobKnownBloom).size)

        val emptyBloom = com.kostadinosk.pulsemesh.service.DtnBloomFilter().toBase64()
        val missingForFreshPeer = aliceDtn.computeMissingBundlesForPeer(emptyBloom)
        assertEquals(2, missingForFreshPeer.size)
        assertEquals("dtn_sos_1", missingForFreshPeer.first().packetId) // SOS prioritized first

        assertTrue(aliceDtn.handleCustodyAck("dtn_chat_1"))
        assertEquals(1, aliceDtn.getActiveCount())
    }

    @Test
    fun `battery power profiles configure duty-cycled BLE windows`() {
        assertTrue(BatteryPowerProfile.EXPEDITION.bleScanOffMs > BatteryPowerProfile.BALANCED.bleScanOffMs)
        assertEquals(0L, BatteryPowerProfile.TURBO.bleScanOffMs)
        assertTrue(BatteryPowerProfile.TURBO.allowPersistentMulticastLock)
        assertFalse(BatteryPowerProfile.EXPEDITION.allowPersistentMulticastLock)
        assertEquals(BatteryPowerProfile.EXPEDITION, BatteryPowerProfile.fromName("expedition"))
        assertEquals(BatteryPowerProfile.BALANCED, BatteryPowerProfile.fromName("unknown"))
    }
}
