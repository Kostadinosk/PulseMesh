// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh

import android.app.Application
import android.hardware.usb.UsbConstants
import androidx.test.core.app.ApplicationProvider
import com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager
import com.kostadinosk.pulsemesh.service.AppUpdateChecker
import com.kostadinosk.pulsemesh.service.Bell202AcousticModem
import com.kostadinosk.pulsemesh.service.LoraCompanionBridge
import com.kostadinosk.pulsemesh.service.LoraProtocol
import com.kostadinosk.pulsemesh.service.MeshCoreWireCodec
import com.kostadinosk.pulsemesh.service.MeshtasticWireCodec
import com.kostadinosk.pulsemesh.service.ReedSolomonFec
import com.kostadinosk.pulsemesh.service.VerifiedApkUpdater
import com.kostadinosk.pulsemesh.util.AppStrings
import com.kostadinosk.pulsemesh.util.QrPairingAction
import com.kostadinosk.pulsemesh.util.QrPairingRouter
import com.kostadinosk.pulsemesh.viewmodel.MeshViewModel
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
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class V155Phase2And3Test {

    @Test
    fun testReedSolomonFecCorrectsUpToFourErrorsAndRejectsFive() {
        val original = "PulseMesh RS(255,k) tactical FEC test payload 12345!".toByteArray(Charsets.UTF_8)
        val encoded = ReedSolomonFec.encode(original, ReedSolomonFec.DEFAULT_ECC_BYTES)
        assertEquals(original.size + 8, encoded.size)

        val clean = ReedSolomonFec.decodeWithStats(encoded)
        assertNotNull(clean)
        assertArrayEquals(original, clean!!.data)
        assertEquals(0, clean.correctedErrors)

        val errPositions = listOf(0, 7, 23, encoded.size - 1) // data + parity byte positions
        for (k in 1..4) {
            val corrupted = encoded.copyOf()
            for (idx in 0 until k) {
                val pos = errPositions[idx]
                corrupted[pos] = ((corrupted[pos].toInt() and 0xFF) xor (0x3D + idx * 17)).toByte()
            }
            val repaired = ReedSolomonFec.decodeWithStats(corrupted)
            assertNotNull("Expected $k errors to repair", repaired)
            assertArrayEquals(original, repaired!!.data)
            assertEquals(k, repaired.correctedErrors)
        }

        val uncorrectable = encoded.copyOf()
        for (pos in listOf(0, 5, 11, 21, 31)) {
            uncorrectable[pos] = ((uncorrectable[pos].toInt() and 0xFF) xor 0xA5).toByte()
        }
        assertNull(ReedSolomonFec.decodeWithStats(uncorrectable))

        // Exact cross-platform parity vector matching web-pwa/src/acoustic/reedSolomon.js
        val crossVec = ReedSolomonFec.encode("PulseMesh".toByteArray(Charsets.UTF_8), 8)
        val crossParityHex = crossVec.copyOfRange(9, 17).joinToString("") { "%02x".format(it) }
        assertEquals("556d4e7478d817d9", crossParityHex)
    }

    @Test
    fun testAcousticModemV2EncryptedRoundTripAndFecRecovery() {
        val pass = "alpha-team-secret"
        val secretMsg = "Rally at Ridge 4 @ 1800Z"
        val frame = Bell202AcousticModem.encodeV2EncryptedFrameBytes(
            typeByte = Bell202AcousticModem.TYPE_CHAT,
            srcNodeIdHex = "0x4F2A",
            text = secretMsg,
            passphrase = pass
        )
        assertEquals(Bell202AcousticModem.SYNC_V2_0, frame[0])
        assertEquals(Bell202AcousticModem.SYNC_V2_1, frame[1])

        // Clean v2 decode
        val decodedClean = Bell202AcousticModem.decodeV2EncryptedFrameBytes(frame, pass)
        assertNotNull(decodedClean)
        assertTrue(decodedClean!!.isEncrypted)
        assertEquals(0, decodedClean.fecCorrectedBytes)
        assertEquals(Bell202AcousticModem.TYPE_CHAT, decodedClean.typeByte)
        assertEquals("0x4F2A", decodedClean.srcNodeIdHex)
        assertEquals(secretMsg, decodedClean.text)

        // 3 corrupted bytes inside RS codeword recover cleanly
        val noisy = frame.copyOf()
        noisy[6] = ((noisy[6].toInt() and 0xFF) xor 0x55).toByte()
        noisy[14] = ((noisy[14].toInt() and 0xFF) xor 0xFF).toByte()
        noisy[noisy.size - 2] = ((noisy[noisy.size - 2].toInt() and 0xFF) xor 0x0F).toByte()
        val repaired = Bell202AcousticModem.decodeV2EncryptedFrameBytes(noisy, pass)
        assertNotNull(repaired)
        assertEquals(secretMsg, repaired!!.text)
        assertTrue(repaired.isEncrypted)
        assertEquals(3, repaired.fecCorrectedBytes)

        // Corrupted primary length byte recovers via inverse length header
        val lenCorrupted = frame.copyOf()
        lenCorrupted[2] = ((lenCorrupted[2].toInt() and 0xFF) xor 0x10).toByte()
        val lenRecovered = Bell202AcousticModem.decodeV2EncryptedFrameBytes(lenCorrupted, pass)
        assertNotNull(lenRecovered)
        assertEquals(secretMsg, lenRecovered!!.text)

        // Wrong passphrase must fail-closed (no silent downgrade)
        assertNull(Bell202AcousticModem.decodeV2EncryptedFrameBytes(frame, "wrong-passphrase"))

        // Full PCM AFSK modulation -> Goertzel demodulation round-trip for v2
        val pcmV2 = Bell202AcousticModem.modulateFrameToPcm(frame)
        val demodV2 = Bell202AcousticModem.demodulatePcm(pcmV2, passphrase = pass)
        assertNotNull(demodV2)
        assertTrue(demodV2!!.isEncrypted)
        assertEquals(secretMsg, demodV2.text)
        assertNull(Bell202AcousticModem.demodulatePcm(pcmV2, passphrase = "wrong-passphrase"))

        // Legacy "PM" v1 backward compatibility in demodulatePcm
        val legacyFrame = Bell202AcousticModem.encodeFrameBytes(
            Bell202AcousticModem.TYPE_SOS,
            "0x1A2B",
            "LEGACY SOS"
        )
        val demodV1 = Bell202AcousticModem.demodulatePcm(Bell202AcousticModem.modulateFrameToPcm(legacyFrame))
        assertNotNull(demodV1)
        assertFalse(demodV1!!.isEncrypted)
        assertEquals("0x1A2B", demodV1.srcNodeIdHex)
        assertEquals("LEGACY SOS", demodV1.text)
    }

    @Test
    fun testLoraMeshtasticAndMeshCoreCodecsAndUsbDetection() {
        val pb = MeshtasticWireCodec.encodeFromRadioProtobuf(
            fromNode = 0x1234ABCDL,
            text = "Meshtastic trail check",
            rssiDbm = -92,
            snrDb = 7.5f
        )
        val frame = MeshtasticWireCodec.wrapSerialFrame(pb)
        val garbagePrefix = byteArrayOf(0x00, 0x11, 0x94.toByte())
        val partialTail = byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0x00, 0x20, 0x01)
        val (msgs, rem) = MeshtasticWireCodec.decodeFromRadioSerialStream(garbagePrefix + frame + partialTail)

        assertEquals(1, msgs.size)
        assertEquals(LoraProtocol.MESHTASTIC, msgs[0].protocol)
        assertEquals("0x1234ABCD", msgs[0].senderNodeHex)
        assertEquals("Meshtastic trail check", msgs[0].text)
        assertEquals(-92, msgs[0].rssiDbm)
        assertEquals(7.5f, msgs[0].snrDb ?: 0f, 0.01f)
        assertArrayEquals(partialTail, rem)

        val toRadio = MeshtasticWireCodec.encodeTextToRadioSerialFrame("Outbound LoRa")
        assertEquals(0x94.toByte(), toRadio[0])
        assertEquals(0xC3.toByte(), toRadio[1])

        // MeshCore '<' outbound & '>' inbound stream
        val mcOut = MeshCoreWireCodec.encodeChannelTextSerialFrame(channelIdx = 1, text = "Hello MeshCore", timestampSec = 1700000000L)
        assertEquals(0x3C.toByte(), mcOut[0])
        val mcIn = MeshCoreWireCodec.encodeChannelRecvSerialFrame(channelIdx = 2, text = "RX from MeshCore", timestampSec = 1700000005L)
        val (mcMsgs, mcRem) = MeshCoreWireCodec.decodeRadioSerialStream(mcIn)
        assertEquals(1, mcMsgs.size)
        assertEquals(LoraProtocol.MESHCORE, mcMsgs[0].protocol)
        assertEquals("MC-CH2", mcMsgs[0].senderNodeHex)
        assertEquals("RX from MeshCore", mcMsgs[0].text)
        assertEquals(0, mcRem.size)

        // Known USB-OTG LoRa VIDs vs unrelated HID device
        assertTrue(LoraCompanionBridge.isLikelyLoraUsbDevice(0x10C4, 0, 0)) // CP210x
        assertTrue(LoraCompanionBridge.isLikelyLoraUsbDevice(0x1A86, 0, 0)) // CH34x
        assertTrue(LoraCompanionBridge.isLikelyLoraUsbDevice(0x303A, 0, 0)) // Espressif
        assertTrue(LoraCompanionBridge.isLikelyLoraUsbDevice(0x0001, UsbConstants.USB_CLASS_COMM, 0))
        assertFalse(LoraCompanionBridge.isLikelyLoraUsbDevice(0x046D, UsbConstants.USB_CLASS_HID, 0))
    }

    @Test
    fun testVerifiedApkUpdaterSha256CertAndFlavorParsing() {
        val liteSha = "a".repeat(64)
        val fullSha = "b".repeat(64)
        val releaseJson = """
            {
              "tag_name": "v9.9.9",
              "name": "PulseMesh v9.9.9",
              "html_url": "https://github.com/Kostadinosk/PulseMesh/releases/tag/v9.9.9",
              "body": "Checksums:\n$liteSha  pulsemesh-lite-release.apk\n$fullSha  pulsemesh-full-release.apk",
              "assets": [
                {
                  "name": "pulsemesh-lite-release.apk",
                  "browser_download_url": "https://github.com/Kostadinosk/PulseMesh/releases/download/v9.9.9/pulsemesh-lite-release.apk"
                },
                {
                  "name": "pulsemesh-lite-release.apk.sha256",
                  "browser_download_url": "https://github.com/Kostadinosk/PulseMesh/releases/download/v9.9.9/pulsemesh-lite-release.apk.sha256"
                },
                {
                  "name": "pulsemesh-full-release.apk",
                  "browser_download_url": "https://github.com/Kostadinosk/PulseMesh/releases/download/v9.9.9/pulsemesh-full-release.apk"
                },
                {
                  "name": "pulsemesh-full-release.apk.sha256",
                  "browser_download_url": "https://github.com/Kostadinosk/PulseMesh/releases/download/v9.9.9/pulsemesh-full-release.apk.sha256"
                }
              ]
            }
        """.trimIndent()

        val liteInfo = AppUpdateChecker.parseReleaseJson(releaseJson, "1.5.5", flavorTier = "lite")
        assertNotNull(liteInfo)
        assertEquals("pulsemesh-lite-release.apk", liteInfo!!.apkFileName)
        assertEquals("https://github.com/Kostadinosk/PulseMesh/releases/download/v9.9.9/pulsemesh-lite-release.apk.sha256", liteInfo.sha256Url)
        assertEquals(liteSha, liteInfo.expectedSha256Hex)

        val fullInfo = AppUpdateChecker.parseReleaseJson(releaseJson, "1.5.5", flavorTier = "full")
        assertNotNull(fullInfo)
        assertEquals("pulsemesh-full-release.apk", fullInfo!!.apkFileName)
        assertEquals("https://github.com/Kostadinosk/PulseMesh/releases/download/v9.9.9/pulsemesh-full-release.apk.sha256", fullInfo.sha256Url)
        assertEquals(fullSha, fullInfo.expectedSha256Hex)

        // Multi-line sha256sum & single-line .sha256 parsing
        val sumsText = "$liteSha  pulsemesh-lite-release.apk\n$fullSha *pulsemesh-full-release.apk\n"
        assertEquals(fullSha, VerifiedApkUpdater.parseExpectedSha256ForAsset(sumsText, "pulsemesh-full-release.apk"))
        assertEquals(liteSha, VerifiedApkUpdater.parseExpectedSha256ForAsset(liteSha.uppercase(), "anything.apk"))

        // File SHA-256 verification
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val tmpApk = File(ctx.cacheDir, "test_verified.apk").apply {
            writeBytes("pulsemesh-apk-binary-payload".toByteArray(Charsets.UTF_8))
        }
        val expectedDigest = MessageDigest.getInstance("SHA-256")
            .digest(tmpApk.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertTrue(VerifiedApkUpdater.verifyApkSha256(tmpApk, expectedDigest))
        assertFalse(VerifiedApkUpdater.verifyApkSha256(tmpApk, "0".repeat(64)))
        tmpApk.delete()

        // Signing cert matching
        assertTrue(VerifiedApkUpdater.verifySigningCertMatch(setOf(liteSha), setOf(fullSha, liteSha)))
        assertFalse(VerifiedApkUpdater.verifySigningCertMatch(setOf(liteSha), setOf(fullSha)))
        assertFalse(VerifiedApkUpdater.verifySigningCertMatch(emptySet(), setOf(liteSha)))

        // Allowed URL policy
        assertTrue(VerifiedApkUpdater.isAllowedUpdateUrl("https://github.com/Kostadinosk/PulseMesh/releases/download/v1.5.5/app.apk"))
        assertTrue(VerifiedApkUpdater.isAllowedUpdateUrl("http://192.168.43.1:8080/download/pulsemesh.apk"))
        assertFalse(VerifiedApkUpdater.isAllowedUpdateUrl("http://untrusted.example.com/evil.apk"))
    }

    @Test
    fun testQrJoinUriAndViewModelPairingAndCotBridge() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val vm = MeshViewModel(ctx)
        val kp = TacticalE2eeManager.generateKeyPair()

        // pulsemesh://join with group invite + inviter pubkey
        val encPk = java.net.URLEncoder.encode(kp.publicKeyBase64, "UTF-8")
        val joinUri = "pulsemesh://join?id=0xC001&cs=Bravo&u=bravo&pk=$encPk&gid=grp99&gn=ReconTeam&gk=QUJDRA=="
        val parsedGroup = QrPairingRouter.parsePayload(joinUri)
        assertTrue(parsedGroup is QrPairingAction.JoinGroup)
        val (joinedOk, joinedMsg) = vm.handleQrPairingAction(parsedGroup!!)
        assertTrue(joinedOk)
        assertTrue(joinedMsg.contains("ReconTeam"))
        assertTrue(vm.uiState.value.meshGroups.any { it.groupId == "grp99" })
        assertTrue(vm.uiState.value.nodes.any { it.id == "0xC001" })

        // VerifySafetyNumber against trusted peer key
        val verifyOkAction = QrPairingAction.VerifySafetyNumber(
            nodeId = "0xC001",
            expectedFingerprint = TacticalE2eeManager.deriveFingerprint(kp.publicKeyBase64)
        )
        val (fpOk, _) = vm.handleQrPairingAction(verifyOkAction)
        assertTrue(fpOk)

        val verifyBadAction = QrPairingAction.VerifySafetyNumber(
            nodeId = "0xC001",
            expectedFingerprint = "DEAD-BEEF-0000"
        )
        val (fpBad, _) = vm.handleQrPairingAction(verifyBadAction)
        assertFalse(fpBad)

        // CoT Bridge toggle & GPX/KML import
        vm.setCotBridgeEnabled(true)
        assertTrue(vm.uiState.value.isCotBridgeEnabled)
        vm.setCotBridgeEnabled(false)
        assertFalse(vm.uiState.value.isCotBridgeEnabled)

        val gpxSample = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1"><wpt lat="37.98" lon="23.72"><name>AcropolisRally</name><type>RALLY</type></wpt></gpx>
        """.trimIndent()
        val (gpxOk, _) = vm.importGpxOrKmlContent(gpxSample)
        assertTrue(gpxOk)
        assertTrue(vm.uiState.value.waypoints.any { it.title == "AcropolisRally" })
    }

    @Test
    fun testLocalizedStringsXmlEnAndElResolution() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val keysToCheck = listOf(
            R.string.tab_map,
            R.string.tab_chats,
            R.string.tab_voice,
            R.string.tab_settings,
            R.string.lora_bridge_title,
            R.string.qr_scan_button,
            R.string.settings_cat_identity,
            R.string.apk_beaming_title
        )
        for (resId in keysToCheck) {
            val en = AppStrings.resolve(ctx, "en", resId)
            val el = AppStrings.resolve(ctx, "el", resId)
            assertTrue("EN string blank for $resId", en.isNotBlank())
            assertTrue("EL string blank for $resId", el.isNotBlank())
            assertNotEquals("EN and EL string should differ for $resId", en, el)
        }
        assertEquals("Map", AppStrings.resolve(ctx, "en", R.string.tab_map))
        assertEquals("Χάρτης", AppStrings.resolve(ctx, "el", R.string.tab_map))
    }
}
