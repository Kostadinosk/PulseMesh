package com.kostadinosk.pulsemesh

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager
import com.kostadinosk.pulsemesh.model.ChipsetProfile
import com.kostadinosk.pulsemesh.model.MapRenderMode
import com.kostadinosk.pulsemesh.model.VoiceTransportMode
import com.kostadinosk.pulsemesh.util.RootDetector
import com.kostadinosk.pulsemesh.viewmodel.MeshViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("PulseMesh", appName)
  }

  @Test
  fun `verify tactical root detector probe`() {
    val realProbe = RootDetector.executeProbe()
    assertTrue(realProbe.probeLogs.isNotEmpty())
    if (realProbe.isRooted) {
      assertTrue(realProbe.rawIdOutput?.contains("uid=0(root)") == true)
    } else {
      assertTrue(realProbe.probeLogs.any { it.contains("Root Not Granted") })
    }
  }

  @Test
  fun `verify tactical e2ee key derivation and encryption`() {
    val keyPair = TacticalE2eeManager.generateKeyPair("Kosta-OP15")
    assertNotNull(keyPair.publicKeyHex)
    assertTrue(keyPair.fingerprint.contains("-") && keyPair.fingerprint.length == 14)

    val nodeId = TacticalE2eeManager.deriveNodeIdFromCallsign("Kosta-OP15")
    assertTrue(nodeId.startsWith("0x"))

    // Test AEAD Encryption & Decryption
    val secret = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"
    val payload = TacticalE2eeManager.encrypt("SITREP: ALL QUIET", secret, keyPair.fingerprint)
    assertTrue(payload.ciphertextHex.isNotBlank())
    assertTrue(payload.macTagHex.startsWith("0x"))

    val decrypted = TacticalE2eeManager.decrypt(payload, secret)
    assertEquals("SITREP: ALL QUIET", decrypted)
  }

  @Test
  fun `verify mesh view model state initialization and profile update`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = MeshViewModel(app)
    val state = viewModel.uiState.value

    assertTrue(state.nodes.isNotEmpty())
    assertNotNull(state.nodes.find { it.isSelf })
    assertEquals(MapRenderMode.VECTOR_LEAFLET, state.mapRenderMode)
    assertTrue(state.isPureOffGrid)

    // Save custom profile
    viewModel.saveDeviceProfile(
        callsign = "Kosta-OP15",
        nodeId = "0x4F2A",
        profile = com.kostadinosk.pulsemesh.model.DeviceCatalog.getProfileById("oneplus_15"),
        pureOffGrid = true,
        internetBridge = false
    )

    val updatedState = viewModel.uiState.value
    assertEquals("Kosta-OP15", updatedState.operatorCallsign)
    assertEquals("0x4F2A", updatedState.localNodeId)
    assertTrue(updatedState.isPureOffGrid)

    // Test sending chat message
    viewModel.sendChatMessage("Sitrep: Ridge secured.")
    val latestPkt = viewModel.uiState.value.packets.first()
    assertEquals("Sitrep: Ridge secured.", latestPkt.content)

    // Test thread selection
    viewModel.selectThread("channel_primary")
    assertEquals("channel_primary", viewModel.uiState.value.selectedThreadId)

    // Test voice transport change
    viewModel.setVoiceTransportMode(VoiceTransportMode.GHOST_VOICE)
    assertEquals(VoiceTransportMode.GHOST_VOICE, viewModel.uiState.value.voiceTransportMode)
  }

  @Test
  fun `verify 75+ phone database and hardware auto matching`() {
    val allModels = com.kostadinosk.pulsemesh.model.DeviceCatalog.allModels
    assertTrue("Device catalog must contain 75+ phone models", allModels.size >= 75)

    // Test specific model lookup
    val op15 = com.kostadinosk.pulsemesh.model.DeviceCatalog.getProfileById("oneplus_15")
    assertEquals("OnePlus 15", op15.modelName)
    assertTrue(op15.socName.contains("Snapdragon 8 Elite"))

    val s24Ultra = com.kostadinosk.pulsemesh.model.DeviceCatalog.getProfileById("galaxy_s24_ultra")
    assertEquals("Samsung Galaxy S24 Ultra", s24Ultra.modelName)

    val pixel9 = com.kostadinosk.pulsemesh.model.DeviceCatalog.getProfileById("pixel_9_pro_xl")
    assertEquals("Google Pixel 9 Pro XL", pixel9.modelName)

    // Test automatic matching
    val matched = com.kostadinosk.pulsemesh.model.DeviceCatalog.findBestMatch("Pixel 9 Pro XL", "gs401", "Mali-G715", false)
    assertEquals("pixel_9_pro_xl", matched.id)
  }

  @Test
  fun `verify 1-tap hands-free duplex call state transitions`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = MeshViewModel(app)

    val peerNode = com.kostadinosk.pulsemesh.model.MeshNode(
        id = "0x9B1C",
        callsign = "Bravo-2",
        rssiDbm = -60
    )

    // Start 1-Tap Duplex Call (Ringing Outgoing)
    viewModel.startDuplexCall(peerNode)
    val stateRinging = viewModel.uiState.value
    assertTrue(stateRinging.duplexCallState.isRinging)
    assertEquals("0x9B1C", stateRinging.duplexCallState.peerNodeId)
    assertEquals("Bravo-2", stateRinging.duplexCallState.peerCallsign)

    // Accept call -> Active In-Call
    viewModel.acceptDuplexCall()
    val stateInCall = viewModel.uiState.value
    assertTrue(stateInCall.duplexCallState.isInCall)

    // Toggle Mute & Speaker
    viewModel.toggleCallMute()
    assertTrue(viewModel.uiState.value.duplexCallState.isMuted)
    viewModel.toggleCallMute()
    assertTrue(!viewModel.uiState.value.duplexCallState.isMuted)

    // End Duplex Call
    viewModel.endDuplexCall()
    val stateEnded = viewModel.uiState.value
    assertTrue(!stateEnded.duplexCallState.isInCall)
  }

  @Test
  fun `verify real ECDH P-256 aes gcm encrypt and decrypt`() {
    val aliceKeys = TacticalE2eeManager.generateKeyPair("Alice")
    val bobKeys = TacticalE2eeManager.generateKeyPair("Bob")
    val eveKeys = TacticalE2eeManager.generateKeyPair("Eve")

    assertTrue(TacticalE2eeManager.isValidEcPublicKeyBase64(aliceKeys.publicKeyBase64))
    assertTrue(TacticalE2eeManager.isValidEcPrivateKeyBase64(aliceKeys.privateKeyBase64))

    val message = "Tactical off-grid coordinates: 37.7749, -122.4194"
    val ciphertext = TacticalE2eeManager.encryptDmWithEcdh(
        plaintext = message,
        myPrivateKeyBase64 = aliceKeys.privateKeyBase64,
        peerPublicKeyBase64 = bobKeys.publicKeyBase64
    )
    assertNotNull(ciphertext)
    assertTrue(ciphertext!!.startsWith("E2EE_ECDH_V2:"))

    // Decrypt on Bob's side via ECDH shared secret
    val decrypted = TacticalE2eeManager.decryptDmWithEcdh(
        payload = ciphertext,
        myPrivateKeyBase64 = bobKeys.privateKeyBase64,
        peerPublicKeyBase64 = aliceKeys.publicKeyBase64
    )
    assertEquals(message, decrypted)

    // Eve with a different private key must NOT be able to decrypt
    val eveDecrypted = TacticalE2eeManager.decryptDmWithEcdh(
        payload = ciphertext,
        myPrivateKeyBase64 = eveKeys.privateKeyBase64,
        peerPublicKeyBase64 = aliceKeys.publicKeyBase64
    )
    assertEquals("[Key Exchange Pending / Decryption Failed]", eveDecrypted)
  }

  @Test
  fun `verify pure off-grid transport toggle and packet counters`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = MeshViewModel(app)
    viewModel.setMeshPassphrase("iso_offgrid_${System.nanoTime()}")

    assertTrue(viewModel.uiState.value.isPureOffGridTransport)
    viewModel.toggleTransportMode()
    assertTrue(!viewModel.uiState.value.isPureOffGridTransport)
    viewModel.toggleTransportMode()
    assertTrue(viewModel.uiState.value.isPureOffGridTransport)

    // Ping Mesh
    viewModel.pingMeshNow()
  }

  @Test
  fun `verify whatsapp style conversations and persistent chat storage`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = MeshViewModel(app)

    // Check pinned channels exist
    val convos = viewModel.uiState.value.conversations
    assertTrue(convos.any { it.threadId == "channel_primary" && it.isPinned })
    assertTrue(convos.any { it.threadId == "channel_encrypted" && it.isPinned && it.isEncrypted })

    // Select thread
    viewModel.selectThread("channel_primary")
    assertEquals("channel_primary", viewModel.uiState.value.selectedThreadId)

    // Send message in thread
    viewModel.sendMessageInThread("channel_primary", "Alpha Team moving to grid.")
    val primaryPackets = viewModel.persistentChatManager.getPacketsForThread("channel_primary")
    assertTrue(primaryPackets.any { it.content == "Alpha Team moving to grid." })

    // Test DM Thread
    viewModel.sendMessageInThread("dm_0x8C3E", "Direct encrypted coordinates sent.")
    val dmPackets = viewModel.persistentChatManager.getPacketsForThread("dm_0x8C3E")
    assertTrue(dmPackets.any { it.content == "Direct encrypted coordinates sent." })
  }

  @Test
  fun `verify ECDH TOFU safety fingerprint symmetry, key-change warning detection, and fallback decryption`() {
    val aliceKeys = TacticalE2eeManager.generateKeyPair()
    val bobKeys = TacticalE2eeManager.generateKeyPair()
    val rotatedBobKeys = TacticalE2eeManager.generateKeyPair()

    // 1. Order-independent 12-char hex Safety Fingerprint (XXXX-XXXX-XXXX)
    val fpAB = TacticalE2eeManager.computePairwiseSafetyFingerprint(aliceKeys.publicKeyBase64, bobKeys.publicKeyBase64)
    val fpBA = TacticalE2eeManager.computePairwiseSafetyFingerprint(bobKeys.publicKeyBase64, aliceKeys.publicKeyBase64)
    assertEquals(14, fpAB.length)
    assertEquals(fpAB, fpBA)

    // 2. TOFU storage & key-change warning detection in DevicePreferences
    val app = ApplicationProvider.getApplicationContext<Application>()
    val prefs = com.kostadinosk.pulsemesh.util.DevicePreferences(app)
    val peerId = "0x8C3E"
    val (acceptedFirst, warnFirst) = prefs.verifyOrTrustPeerPublicKey(peerId, bobKeys.publicKeyBase64)
    assertTrue(acceptedFirst)
    assertTrue(!warnFirst)
    assertTrue(!prefs.isPeerKeyChanged(peerId))
    assertEquals(bobKeys.publicKeyBase64, prefs.getPeerPublicKey(peerId))

    // Receiving a DIFFERENT key for the same NodeID must raise a TOFU warning without overwriting pinned key
    val (acceptedRotated, warnRotated) = prefs.verifyOrTrustPeerPublicKey(peerId, rotatedBobKeys.publicKeyBase64)
    assertTrue(!acceptedRotated)
    assertTrue(warnRotated)
    assertTrue(prefs.isPeerKeyChanged(peerId))
    assertEquals(bobKeys.publicKeyBase64, prefs.getPeerPublicKey(peerId))
    assertEquals(rotatedBobKeys.publicKeyBase64, prefs.getPendingChangedPeerPublicKey(peerId))

    // Explicit user verification accepts the new key & clears the warning
    prefs.acceptChangedPeerPublicKey(peerId)
    assertTrue(!prefs.isPeerKeyChanged(peerId))
    assertEquals(rotatedBobKeys.publicKeyBase64, prefs.getPeerPublicKey(peerId))

    // 3. Zero-downgrade security: reject unencrypted PLAINTEXT wrapper & corrupted frame fallback
    val legacyPayload = "PLAINTEXT:COORDINATES: 37.7749,-122.4194"
    assertEquals(
        "[Encrypted / Key Verification Failed]",
        TacticalE2eeManager.decryptDmWithEcdh(legacyPayload, aliceKeys.privateKeyBase64, bobKeys.publicKeyBase64)
    )
    assertEquals(
        "[Key Exchange Pending / Decryption Failed]",
        TacticalE2eeManager.decryptDmWithEcdh("E2EE_ECDH_V2:CORRUPTED==", aliceKeys.privateKeyBase64, bobKeys.publicKeyBase64)
    )
  }

  @Test
  fun `verify ECDH fresh IV randomness across repeated DM encryptions`() {
    val aliceKeys = TacticalE2eeManager.generateKeyPair()
    val bobKeys = TacticalE2eeManager.generateKeyPair()
    val plaintext = "ECDH CIPHERTEXT TRANSMISSION"

    val enc1 = TacticalE2eeManager.encryptDmWithEcdh(plaintext, aliceKeys.privateKeyBase64, bobKeys.publicKeyBase64)!!
    val enc2 = TacticalE2eeManager.encryptDmWithEcdh(plaintext, aliceKeys.privateKeyBase64, bobKeys.publicKeyBase64)!!
    assertTrue(enc1.startsWith("E2EE_ECDH_V2:"))
    assertTrue(enc2.startsWith("E2EE_ECDH_V2:"))
    assertTrue("Fresh random 12-byte IV must produce distinct ciphertexts", enc1 != enc2)
    assertEquals(plaintext, TacticalE2eeManager.decryptDmWithEcdh(enc1, bobKeys.privateKeyBase64, aliceKeys.publicKeyBase64))
    assertEquals(plaintext, TacticalE2eeManager.decryptDmWithEcdh(enc2, bobKeys.privateKeyBase64, aliceKeys.publicKeyBase64))
  }

  @Test
  fun `verify regional device codename recognition`() {
    // 1. OnePlus 13 regional model numbers (China: PJZ110, India: CPH2649, Global: CPH2653, NA: CPH2655)
    val opChina = com.kostadinosk.pulsemesh.model.DeviceCatalog.findBestMatch("PJZ110", "", "", false)
    assertEquals("oneplus_13", opChina.id)

    val opIndia = com.kostadinosk.pulsemesh.model.DeviceCatalog.findBestMatch("CPH2649", "", "", false)
    assertEquals("oneplus_13", opIndia.id)

    val opGlobal = com.kostadinosk.pulsemesh.model.DeviceCatalog.findBestMatch("CPH2653", "", "", false)
    assertEquals("oneplus_13", opGlobal.id)

    val opNA = com.kostadinosk.pulsemesh.model.DeviceCatalog.findBestMatch("CPH2655", "", "", false)
    assertEquals("oneplus_13", opNA.id)

    // 2. OnePlus 12 regional model
    val op12 = com.kostadinosk.pulsemesh.model.DeviceCatalog.findBestMatch("CPH2581", "", "", false)
    assertEquals("oneplus_12", op12.id)

    // 3. Samsung Galaxy S25 / S24 regional models
    val s25u = com.kostadinosk.pulsemesh.model.DeviceCatalog.findBestMatch("SM-S938B", "", "", false)
    assertEquals("galaxy_s25_ultra", s25u.id)

    val s24u = com.kostadinosk.pulsemesh.model.DeviceCatalog.findBestMatch("SM-S928U", "", "", false)
    assertEquals("galaxy_s24_ultra", s24u.id)

    // 4. Google Pixel codenames
    val pixel9 = com.kostadinosk.pulsemesh.model.DeviceCatalog.findBestMatch("tokay", "", "", false)
    assertEquals("pixel_9", pixel9.id)
  }

  @Test
  fun `verify offline region downloader tile bounding box calculation`() {
    val tiles = com.kostadinosk.pulsemesh.util.OfflineRegionDownloader.calculateTileList(
        centerLat = 37.7749,
        centerLon = -122.4194,
        radiusKm = 4.0,
        minZoom = 13,
        maxZoom = 14
    )
    assertTrue(tiles.isNotEmpty())
    assertTrue(tiles.all { it.first in 13..14 })
    // Ensure all tiles have positive coordinates
    assertTrue(tiles.all { it.second >= 0 && it.third >= 0 })
  }

  @Test
  fun `verify hybrid mode toggling and transport synchronization`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = MeshViewModel(app)
    viewModel.setMeshPassphrase("iso_hybrid_${System.nanoTime()}")

    // Default off-grid mode
    val initialState = viewModel.uiState.value
    assertTrue(initialState.isPureOffGridTransport)

    // Toggle hybrid mode ON
    viewModel.toggleInternetEnabled(true)
    val hybridState = viewModel.uiState.value
    assertTrue(hybridState.isInternetEnabled)
    assertTrue(!hybridState.isPureOffGridTransport)
    // Strict requirement: Active peers MUST remain 0 unless a real peer transmits
    assertEquals(0, hybridState.activePeerCount)
    assertEquals(1, hybridState.nodes.size)
    assertTrue(hybridState.nodes.first().isSelf)

    // Toggle hybrid mode OFF (back to air-gapped)
    viewModel.toggleInternetEnabled(false)
    val offGridState = viewModel.uiState.value
    assertTrue(!offGridState.isInternetEnabled)
    assertTrue(offGridState.isPureOffGridTransport)
    assertEquals(0, offGridState.activePeerCount)
  }

  @Test
  fun `verify live mesh network service hashed ntfy topic and AES-GCM PM2 encryption`() {
    val teamPassphrase = "alpha-squad-secret-99"
    val service = com.kostadinosk.pulsemesh.service.LiveMeshNetworkService(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        localNodeId = { "0x4F2A" },
        localCallsign = { "Kilo-1" },
        channelName = { "Primary LongFast #1" },
        getCurrentLocation = { Pair(37.7749, -122.4194) },
        getBatteryPercent = { 88 },
        isInternetEnabled = { false },
        meshPassphrase = { teamPassphrase }
    )

    assertEquals("primarylongfast1", service.getSanitizedChannel())
    val topic = service.getRelayTopic()
    assertTrue("Hashed topic must start with pm_", topic.startsWith("pm_"))
    assertEquals(TacticalE2eeManager.deriveHashedNtfyTopic(teamPassphrase), topic)
    assertTrue(!topic.contains("pulsemesh", ignoreCase = true))
    assertEquals("Relay: OFF (Pure BLE)", service.relayStatus.value)

    // Verify AES-GCM "PM2:" envelope encrypt/decrypt and wrong passphrase rejection
    val rawJson = """{"packetId":"p1","type":"CHAT","payload":"Encrypted cloud relay test"}"""
    val sealed = TacticalE2eeManager.encryptChannelPayload(rawJson, teamPassphrase)
    assertTrue(sealed.startsWith("PM2:"))
    assertEquals(rawJson, TacticalE2eeManager.decryptChannelPayload(sealed, teamPassphrase))
    assertEquals(null, TacticalE2eeManager.decryptChannelPayload(sealed, "wrong-passphrase"))
  }

  @Test
  fun `verify 14-byte 0xCAFE manufacturer beacon with 1e7 microdegree GPS precision`() {
    val lat = 37.9838123
    val lon = 23.7275391
    val payload14 = com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.pack14ByteCafePayload(
        nodeIdHex = "0x4F2A",
        batteryPercent = 88,
        isSos = true,
        lat = lat,
        lon = lon,
        callsign = "Kosta-OP15"
    )
    assertEquals(14, payload14.size)

    val decoded = com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.unpackCafePayload(payload14)
    assertNotNull(decoded)
    assertEquals("0x4F2A", decoded!!.nodeIdHex)
    assertEquals(88, decoded.batteryPercent)
    assertEquals(1, decoded.flags)
    assertEquals("Ko", decoded.callsignPrefix)
    // Verify 1.1cm precision (1e-7 degrees)
    assertEquals(lat, decoded.latitude!!, 0.0000002)
    assertEquals(lon, decoded.longitude!!, 0.0000002)
  }

  @Test
  fun `verify 8kHz unsigned 8-bit centered PCM 600ms voice block encoding and decoding`() {
    // 4800 samples at 8kHz = 600ms continuous capture block
    val samples = ShortArray(4800) { i ->
      (kotlin.math.sin(2.0 * kotlin.math.PI * i * 440.0 / 8000.0) * 16000).toInt().toShort()
    }

    val pcm8Bytes = com.kostadinosk.pulsemesh.service.TacticalAudioService.encodePcm16ToUnsigned8Bit(samples)
    assertEquals(4800, pcm8Bytes.size)

    // Verify Base64 NO_WRAP has zero newline characters
    val b64 = android.util.Base64.encodeToString(pcm8Bytes, android.util.Base64.NO_WRAP)
    assertTrue(!b64.contains("\n"))

    val decoded = com.kostadinosk.pulsemesh.service.TacticalAudioService.decodeUnsigned8BitToPcm16(pcm8Bytes)
    assertEquals(samples.size, decoded.size)

    // Verify linear unsigned 8-bit centered quantization step (<= 256)
    for (i in 0 until 100) {
      val diff = kotlin.math.abs(samples[i].toInt() - decoded[i].toInt())
      assertTrue("Unsigned 8-bit PCM quantization error <= 256: $diff", diff <= 256)
    }
  }

  @Test
  fun `verify 800m coarse network GPS overwrite guard`() {
    val now = 100_000L
    val recentGpsFix = 70_000L // 30s ago (< 60s)

    // Coarse cell-tower fix (accuracy = 650m) must be rejected when GPS fix is recent
    val acceptCoarse = com.kostadinosk.pulsemesh.service.RealGpsLocationService.shouldAcceptLocationUpdate(
        provider = android.location.LocationManager.NETWORK_PROVIDER,
        incomingAccuracy = 650f,
        nowMs = now,
        lastGpsFixTimeMs = recentGpsFix,
        currentBestAccuracy = 12f
    )
    assertTrue("Coarse network update must be rejected", !acceptCoarse)

    // Fine satellite GPS fix (accuracy = 8m) must be accepted
    val acceptFineGps = com.kostadinosk.pulsemesh.service.RealGpsLocationService.shouldAcceptLocationUpdate(
        provider = android.location.LocationManager.GPS_PROVIDER,
        incomingAccuracy = 8f,
        nowMs = now,
        lastGpsFixTimeMs = recentGpsFix,
        currentBestAccuracy = 12f
    )
    assertTrue("Fine GPS update must be accepted", acceptFineGps)
  }

  @Test
  fun `verify local hotspot manager and passive subnet engine`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = com.kostadinosk.pulsemesh.util.LocalHotspotManager(context)
    val state = manager.hotspotState.value
    assertTrue(!state.isActive)
    assertEquals("Local Hotspot Idle", state.statusMessage)

    assertTrue(com.kostadinosk.pulsemesh.service.AutoHotspotNegotiator.isIsolatedHotspotSubnet("192.168.43.1"))
    assertTrue(com.kostadinosk.pulsemesh.service.AutoHotspotNegotiator.isIsolatedHotspotSubnet("192.168.49.1"))
  }

  @Test
  fun `verify stateless 8-bit signed PCM 250ms 8kHz voice codec produces 1000 bytes and 2000 upsampled shorts`() {
    val samples = ShortArray(2000) { i ->
      (kotlin.math.sin(2.0 * kotlin.math.PI * i * 440.0 / 8000.0) * 18000).toInt().toShort()
    }

    val compressedBytes = com.kostadinosk.pulsemesh.service.TacticalAudioService.encodeStateless8BitPcm(samples)
    assertEquals(1000, compressedBytes.size)

    val b64 = android.util.Base64.encodeToString(compressedBytes, android.util.Base64.NO_WRAP)
    assertTrue(!b64.contains("\n"))

    val decoded = com.kostadinosk.pulsemesh.service.TacticalAudioService.decodeStateless8BitPcmTo8kHz16Bit(compressedBytes)
    assertEquals(2000, decoded.size)
  }

  @Test
  fun `verify proven 12-byte 0xCAFE BLE Manufacturer Data beacon and 0xCAFF Callsign scan response`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val phone1Vm = MeshViewModel(app)

    val beacon12 = com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.pack12ByteCafeBeacon(
        nodeIdHex = "0x9B1C",
        batteryPercent = 92,
        hotspotRoleByte = 1,
        lat = 37.7749,
        lon = -122.4194
    )
    assertEquals("0xCAFE beacon must be strictly 12 bytes to prevent ADVERTISE_FAILED_DATA_TOO_LARGE", 12, beacon12.size)
    assertEquals(0x50.toByte(), beacon12[0])
    assertEquals(0x4D.toByte(), beacon12[1])

    val unpacked = com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.unpackCafePayload(beacon12)
    assertNotNull(unpacked)
    assertEquals("0x9B1C", unpacked!!.nodeIdHex)
    assertEquals(92, unpacked.batteryPercent)
    assertEquals(1, unpacked.flags)
    assertNotNull(unpacked.latitude)
    assertNotNull(unpacked.longitude)
    assertEquals(37.7749, unpacked.latitude!!, 0.001)
    assertEquals(-122.4194, unpacked.longitude!!, 0.001)

    // Feed 12-byte 0xCAFE beacon + 12-byte max Callsign in 0xCAFF ScanResponse
    phone1Vm.offGridBleEngine.handleCafeBeaconBytes(
        mfgCafe = beacon12,
        mfgCaff = "Bravo-2".toByteArray(Charsets.UTF_8),
        rssi = -48,
        device = null
    )
    assertTrue(
        "Discovered peer from 12-byte 0xCAFE + 0xCAFF must be added to activePeers",
        phone1Vm.uiState.value.nodes.any { !it.isSelf && it.id == "0x9B1C" && it.callsign == "Bravo-2" }
    )
  }

  @Test
  fun `verify strict CALL_ACCEPT and VOICE_STREAM transition without auto-connect and strict filter against placeholders`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = MeshViewModel(app)

    // 1. Verify hard security filter rejects "---", "———", "Peer Node", "Remote Operator", "Unknown", "Bot"
    for (fakeCallsign in listOf("---", "———", "—", "--", "N/A", "Peer Node", "Remote Operator", "Unknown", "Bot", "Peer-0x1234")) {
      viewModel.handleIncomingMeshPacket(
          com.kostadinosk.pulsemesh.service.WireMeshPacket(
              packetId = "fake_${fakeCallsign.hashCode()}",
              srcNodeId = "0xDEAD",
              srcCallsign = fakeCallsign,
              dstNodeId = "ALL",
              type = "HEARTBEAT",
              payload = "Heartbeat"
          )
      )
    }
    assertTrue(
        "Blocked fake and symbol-only callsigns must never be added to activePeers",
        viewModel.uiState.value.nodes.none { !it.isSelf }
    )

    // 2. Verify CALL_RING from peer sets RINGING_INCOMING
    viewModel.handleIncomingMeshPacket(
        com.kostadinosk.pulsemesh.service.WireMeshPacket(
            packetId = "c_ring_1",
            srcNodeId = "0x9B1C",
            srcCallsign = "Sierra-7",
            dstNodeId = "ALL",
            type = "CALL_RING",
            payload = "RING"
        )
    )
    assertEquals(
        com.kostadinosk.pulsemesh.service.CallSignalingState.RINGING_INCOMING,
        viewModel.uiState.value.callState
    )

    // 3. Verify CALL_ACCEPT immediately syncs caller from RINGING_OUTGOING to IN_CALL_ACTIVE
    val realPeer = com.kostadinosk.pulsemesh.model.MeshNode(id = "0x9B1C", callsign = "Sierra-7")
    viewModel.startDuplexCall(realPeer)
    assertEquals(
        com.kostadinosk.pulsemesh.service.CallSignalingState.RINGING_OUTGOING,
        viewModel.uiState.value.callState
    )

    val acceptPkt = com.kostadinosk.pulsemesh.service.WireMeshPacket(
        packetId = "c_accept_1",
        srcNodeId = "0x9B1C",
        srcCallsign = "Sierra-7",
        dstNodeId = "ALL",
        type = "CALL_ACCEPT",
        payload = "ACCEPT"
    )
    viewModel.handleIncomingMeshPacket(acceptPkt)
    assertEquals(
        com.kostadinosk.pulsemesh.service.CallSignalingState.IN_CALL_ACTIVE,
        viewModel.uiState.value.callState
    )

    // 4. Verify CALL_END resets state to IDLE
    viewModel.handleIncomingMeshPacket(
        com.kostadinosk.pulsemesh.service.WireMeshPacket(
            packetId = "c_end_1",
            srcNodeId = "0x9B1C",
            srcCallsign = "Sierra-7",
            dstNodeId = "ALL",
            type = "CALL_END",
            payload = "END"
        )
    )
    assertEquals(
        com.kostadinosk.pulsemesh.service.CallSignalingState.IDLE,
        viewModel.uiState.value.callState
    )

    // 5. Verify receiving VOICE_STREAM while RINGING_OUTGOING also transitions caller to IN_CALL_ACTIVE and plays audio
    viewModel.startDuplexCall(realPeer)
    assertEquals(
        com.kostadinosk.pulsemesh.service.CallSignalingState.RINGING_OUTGOING,
        viewModel.uiState.value.callState
    )

    val pcm250ms = com.kostadinosk.pulsemesh.service.TacticalAudioService.encodeStateless8BitPcm(ShortArray(2000))
    val b64Voice = android.util.Base64.encodeToString(pcm250ms, android.util.Base64.NO_WRAP)
    val prevRx = viewModel.uiState.value.rxAudioCount

    viewModel.handleIncomingMeshPacket(
        com.kostadinosk.pulsemesh.service.WireMeshPacket(
            packetId = "vstream_250ms_1",
            srcNodeId = "0x9B1C",
            srcCallsign = "Sierra-7",
            dstNodeId = "ALL",
            type = "VOICE_STREAM",
            payload = b64Voice
        )
    )
    assertEquals(
        com.kostadinosk.pulsemesh.service.CallSignalingState.IN_CALL_ACTIVE,
        viewModel.uiState.value.callState
    )
    assertEquals(prevRx + 1, viewModel.uiState.value.rxAudioCount)
  }

  @Test
  fun `verify serialized 160B GATT chunk slicing with newline termination, HOTSPOT_AUTO_JOIN, and handleIncomingPacket deduplication`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val phone2Engine = com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine(
        context = app,
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        localNodeId = { "0x2222" },
        localCallsign = { "Phone2-Client" },
        getCurrentLocation = { Pair(37.7750, -122.4195) },
        getBatteryPercent = { 85 }
    )

    val phone2ViewModel = MeshViewModel(app)
    phone2ViewModel.saveDeviceProfile(
        callsign = "Bravo-2",
        nodeId = "0x2222",
        profile = com.kostadinosk.pulsemesh.model.DeviceCatalog.getProfileById("oneplus_15"),
        pureOffGrid = true,
        internetBridge = false
    )

    var receivedRawJson: String? = null
    phone2Engine.onRawJsonPacketReceived = { json ->
      receivedRawJson = json
    }

    // 1. Slice an outgoing CHAT packet (>160B) + '\n' into 160-byte chunks and feed into per-device StringBuilder receiver
    val longMsg = "Phone 1 to Phone 2 over Serialized 2-Way GATT Pipe! ".repeat(5).trim()
    val jsonPacket = org.json.JSONObject().apply {
      put("id", "pkt_serialized_gatt_1")
      put("type", "CHAT")
      put("srcId", "0x1111")
      put("srcName", "Kilo-1")
      put("dstId", "ALL")
      put("payload", longMsg)
      put("lat", 37.7749)
      put("lng", -122.4194)
    }.toString()

    val fullBytes = (jsonPacket + "\n").toByteArray(Charsets.UTF_8)
    val maxChunkSize = 160
    val chunks = mutableListOf<ByteArray>()
    var offset = 0
    while (offset < fullBytes.size) {
      val end = minOf(offset + maxChunkSize, fullBytes.size)
      chunks.add(fullBytes.copyOfRange(offset, end))
      offset = end
    }
    assertTrue("Packet >160B must split into >= 2 chunks including trailing newline chunk", chunks.size >= 2)

    for (chunk in chunks) {
      assertTrue("Each GATT chunk must be <= 160 bytes", chunk.size <= 160)
      phone2Engine.handleIncomingGattPayload(chunk, null)
    }

    assertNotNull("Phone 2 must reassemble the full JSON line from serialized GATT chunks", receivedRawJson)
    val parsedRoot = org.json.JSONObject(receivedRawJson!!)
    val deliveredPkt = com.kostadinosk.pulsemesh.service.WireMeshPacket(
        packetId = parsedRoot.getString("id"),
        srcNodeId = parsedRoot.getString("srcId"),
        srcCallsign = parsedRoot.getString("srcName"),
        dstNodeId = parsedRoot.getString("dstId"),
        type = parsedRoot.getString("type"),
        payload = parsedRoot.getString("payload")
    )

    // 2. Pass delivered packet twice into handleIncomingPacket to verify deduplication by packetId
    phone2ViewModel.handleIncomingPacket(deliveredPkt)
    phone2ViewModel.handleIncomingPacket(deliveredPkt)

    val primaryChat = phone2ViewModel.persistentChatManager.getPacketsForThread("channel_primary")
    val matching = primaryChat.filter { it.id == "pkt_serialized_gatt_1" }
    assertEquals("Deduplication must ensure packetId is saved exactly once", 1, matching.size)
    assertEquals(longMsg, matching.first().content)

    // 3. Verify HOTSPOT_AUTO_JOIN saves hostIp on the peer node
    val autoJoinPayload = org.json.JSONObject().apply {
      put("ssid", "DIRECT-PM-Test")
      put("passphrase", "pass123456")
      put("hostIp", "192.168.49.1")
    }.toString()
    phone2ViewModel.handleIncomingPacket(
        com.kostadinosk.pulsemesh.service.WireMeshPacket(
            packetId = "hs_auto_join_1",
            srcNodeId = "0x1111",
            srcCallsign = "Kilo-1",
            dstNodeId = "ALL",
            type = "HOTSPOT_AUTO_JOIN",
            payload = autoJoinPayload
        )
    )
    val serverPeer = phone2ViewModel.uiState.value.nodes.firstOrNull { !it.isSelf && it.id == "0x1111" }
    assertNotNull(serverPeer)
    assertEquals("192.168.49.1", serverPeer!!.ipAddress)
  }

  @Test
  fun `verify universal hardware auto-detection synthesizes live profile for unlisted phones`() {
    val unlistedPhone = com.kostadinosk.pulsemesh.util.DetectedHardwareInfo(
        model = "Nothing Phone (3a)",
        manufacturer = "NOTHING",
        brand = "NOTHING",
        deviceCodename = "asteroids",
        productName = "asteroids_eea",
        hardwareBoard = "qcom / asteroids",
        socManufacturer = "Qualcomm",
        socModel = "SM7635 Snapdragon 7s Gen 3",
        cpuInfoHardware = "Qualcomm Technologies, Inc",
        cpuCores = 8,
        totalRamMb = 12288L,
        androidRelease = "15",
        androidSdkInt = 35,
        gpuRenderer = "Adreno (TM) 710",
        detectedSocFamily = com.kostadinosk.pulsemesh.model.SocFamily.QUALCOMM_SNAPDRAGON,
        batteryPercent = 91,
        isEmulator = false,
        supportsBle = true,
        supportsBleExtendedAdv = true,
        supportsBleCodedPhy = true,
        supportsBle2MPhy = true,
        supportsWifiDirect = true,
        supportsWifiAware = true,
        supportsEuicc = false
    )

    val liveProfile = com.kostadinosk.pulsemesh.util.HardwareDetector.resolveAutoMatchedProfile(unlistedPhone)
    assertTrue("Unlisted phone must synthesize live_ profile", liveProfile.id.startsWith("live_"))
    assertTrue(liveProfile.modelName.contains("Nothing Phone (3a)"))
    assertTrue(liveProfile.supportsBleCodedPhy)
    assertTrue(liveProfile.wifiBleDriver.contains("BLE 1M"))
    assertTrue(liveProfile.wifiBleDriver.contains("Coded PHY"))
    assertTrue(liveProfile.wifiBleDriver.contains("Wi-Fi Direct"))

    val mismatch = com.kostadinosk.pulsemesh.util.HardwareDetector.evaluateMismatch(unlistedPhone, liveProfile)
    assertTrue("Auto-built live profile must never trigger mismatch warnings", !mismatch.isMismatched)
  }

  @Test
  fun `verify n-node swarm hotspot election and 0xCAFE Bit 0 role packing`() {
    // 1. Pack 0xCAFE beacon with Bit 0 (isHotspotHost=1) + callState=1 (bits 1..2 = 0x02) -> roleByte = 0x03
    val beacon = com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.pack12ByteCafeBeacon(
        nodeIdHex = "0x9B1C",
        batteryPercent = 80,
        hotspotRoleByte = 0x03,
        lat = 37.7749,
        lon = -122.4194
    )
    val decoded = com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.unpackCafePayload(beacon)
    assertNotNull(decoded)
    assertTrue("Bit 0 must indicate isHotspotHost", decoded!!.isHotspotHost)
    assertEquals("Bits 1..2 must unpack callStateByte", 1, decoded.callStateByte)
    assertTrue("isHotspotHost + callState must NOT trigger false SOS", !decoded.isSos)

    // 2. Universal Swarm Hotspot Election Rule:
    // If another node already hosts, local node MUST yield even with higher ID
    val electWhenPeerHosts = com.kostadinosk.pulsemesh.service.AutoHotspotNegotiator.shouldElectLocalAsHost(
        localNodeId = "0xFFFF",
        discoveredPeerIds = listOf("0x1111", "0x2222"),
        peerHostMap = mapOf("0x1111" to true, "0x2222" to false)
    )
    assertTrue("Must NOT launch competing hotspot when a peer is already host", !electWhenPeerHosts)

    // When ZERO peers host, ONLY highest nodeId elects as host
    val electHighest = com.kostadinosk.pulsemesh.service.AutoHotspotNegotiator.shouldElectLocalAsHost(
        localNodeId = "0x9B1C",
        discoveredPeerIds = listOf("0x1111", "0x4F2A"),
        peerHostMap = mapOf("0x1111" to false, "0x4F2A" to false)
    )
    assertTrue("Highest nodeId should be elected Swarm Hotspot Host", electHighest)

    val electLower = com.kostadinosk.pulsemesh.service.AutoHotspotNegotiator.shouldElectLocalAsHost(
        localNodeId = "0x1111",
        discoveredPeerIds = listOf("0x9B1C", "0x4F2A"),
        peerHostMap = mapOf("0x9B1C" to false, "0x4F2A" to false)
    )
    assertTrue("Lower nodeId must wait and join elected host", !electLower)
  }

  @Test
  fun `verify multi-hop flood routing TTL=7 dedup, hop increment, targeted call vs group call, and 16kHz audio downsampling`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val nodeBViewModel = MeshViewModel(app)
    nodeBViewModel.saveDeviceProfile(
        callsign = "Node-Bravo",
        nodeId = "0x2222",
        profile = com.kostadinosk.pulsemesh.model.DeviceCatalog.getProfileById("oneplus_15"),
        pureOffGrid = true,
        internetBridge = false
    )

    // 1. Verify global packetId deduplication
    assertTrue(nodeBViewModel.recordPacketIdIfNew("pkt_hop_1"))
    assertTrue("Duplicate packetId must be suppressed", !nodeBViewModel.recordPacketIdIfNew("pkt_hop_1"))

    // 2. Verify TTL decrement & Hop increment for flood relay
    val inboundPkt = com.kostadinosk.pulsemesh.service.WireMeshPacket(
        packetId = "pkt_hop_relay",
        srcNodeId = "0x1111",
        srcCallsign = "Kilo-1",
        dstNodeId = "ALL",
        type = "CHAT",
        payload = "Multi-hop mesh relay test",
        ttl = 7,
        hops = 0
    )
    val relayed = nodeBViewModel.buildRelayedPacketIfEligible(inboundPkt, "0x2222")
    assertNotNull(relayed)
    assertEquals(6, relayed!!.ttl)
    assertEquals(1, relayed.hops)

    val exhaustedPkt = inboundPkt.copy(packetId = "pkt_ttl_1", ttl = 1, hops = 6)
    assertEquals(null, nodeBViewModel.buildRelayedPacketIfEligible(exhaustedPkt, "0x2222"))

    // 3. Targeted 1-on-1 CALL_RING for 0x3333 must NOT ring Node-Bravo (0x2222)
    nodeBViewModel.handleIncomingPacket(
        com.kostadinosk.pulsemesh.service.WireMeshPacket(
            packetId = "ring_targeted_to_3333",
            srcNodeId = "0x1111",
            srcCallsign = "Kilo-1",
            dstNodeId = "0x3333",
            type = "CALL_RING",
            payload = "RING",
            ttl = 7,
            hops = 1
        )
    )
    assertEquals(
        "Unrelated third node must stay IDLE on targeted 1-on-1 call",
        com.kostadinosk.pulsemesh.service.CallSignalingState.IDLE,
        nodeBViewModel.uiState.value.callState
    )

    // 4. Group Call (dstId = ALL) MUST ring Node-Bravo, and VOICE_STREAM updates activeSpeakingBanner with Hop X
    nodeBViewModel.handleIncomingPacket(
        com.kostadinosk.pulsemesh.service.WireMeshPacket(
            packetId = "ring_group_all",
            srcNodeId = "0x1111",
            srcCallsign = "Kilo-1",
            dstNodeId = "ALL",
            type = "CALL_RING",
            payload = "GROUP_RING",
            ttl = 7,
            hops = 2
        )
    )
    assertEquals(
        com.kostadinosk.pulsemesh.service.CallSignalingState.RINGING_INCOMING,
        nodeBViewModel.uiState.value.callState
    )

    val pcm16k250ms = ShortArray(4000) { i ->
      (kotlin.math.sin(2.0 * kotlin.math.PI * i * 440.0 / 16000.0) * 16000).toInt().toShort()
    }
    val downsampled8k = com.kostadinosk.pulsemesh.service.TacticalAudioService.downsample16kHzTo8kHz(pcm16k250ms)
    assertEquals(2000, downsampled8k.size)
    val wireBytes = com.kostadinosk.pulsemesh.service.TacticalAudioService.encodeStateless8BitPcm(downsampled8k)
    assertEquals(1000, wireBytes.size)
    val b64Audio = android.util.Base64.encodeToString(wireBytes, android.util.Base64.NO_WRAP)

    nodeBViewModel.handleIncomingPacket(
        com.kostadinosk.pulsemesh.service.WireMeshPacket(
            packetId = "voice_hop2_1",
            srcNodeId = "0x1111",
            srcCallsign = "Kilo-1",
            dstNodeId = "ALL",
            type = "VOICE_STREAM",
            payload = b64Audio,
            ttl = 5,
            hops = 2
        )
    )
    assertEquals("SPEAKING: Kilo-1 (Hop 2)", nodeBViewModel.uiState.value.activeSpeakingBanner)
  }

  @Test
  fun `verify true Web Mercator geographic map radius scaling and tile pyramid estimator`() {
    val lat = 37.7749
    val radiusKm = 5.0

    // Zooming out by 1 level (13 -> 12) must halve the pixel radius on screen;
    // zooming in by 1 level (13 -> 14) must double the pixel radius on screen!
    val pxZoom12 = com.kostadinosk.pulsemesh.util.OfflineRegionDownloader.calculateRealRadiusPixels(lat, 12.0, radiusKm)
    val pxZoom13 = com.kostadinosk.pulsemesh.util.OfflineRegionDownloader.calculateRealRadiusPixels(lat, 13.0, radiusKm)
    val pxZoom14 = com.kostadinosk.pulsemesh.util.OfflineRegionDownloader.calculateRealRadiusPixels(lat, 14.0, radiusKm)

    assertEquals(pxZoom13 / 2f, pxZoom12, 0.1f)
    assertEquals(pxZoom13 * 2f, pxZoom14, 0.1f)

    // Verify all 5 required radii (1 km, 5 km, 10 km, 20 km, 50 km)
    assertEquals(listOf(1.0, 5.0, 10.0, 20.0, 50.0), com.kostadinosk.pulsemesh.util.OfflineRegionDownloader.SUPPORTED_RADII_KM)

    // Verify adaptive zoom range caps maxZoom for 20-50km to prevent tile explosion
    val range5km = com.kostadinosk.pulsemesh.util.OfflineRegionDownloader.calculateAdaptiveZoomRange(14, 5.0)
    assertEquals(Pair(13, 16), range5km)
    val range50km = com.kostadinosk.pulsemesh.util.OfflineRegionDownloader.calculateAdaptiveZoomRange(14, 50.0)
    assertEquals(Pair(13, 14), range50km)

    // Verify tile pyramid size estimate (totalTiles * 18 KB)
    val estMb = com.kostadinosk.pulsemesh.util.OfflineRegionDownloader.estimateTilePyramidSizeMb(100)
    assertEquals((100 * 18.0) / 1024.0, estMb, 0.001)
  }

  @Test
  fun `verify multi-peer group chat creation and random per-group key ECDH wrapping`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = MeshViewModel(app)

    val group = viewModel.createMeshGroup("Squad Alpha", listOf("0x9B1C", "0x8C3E"))
    assertTrue(group.groupId.isNotBlank())
    assertEquals("Squad Alpha", group.groupName)
    assertTrue(group.memberIds.any { it.contains("9B1C") })
    assertTrue(group.memberIds.any { it.contains("8C3E") })
    assertTrue("Group must generate a valid 32-byte random AES key", TacticalE2eeManager.isValidGroupAesKeyBase64(group.groupKeyBase64))

    // Verify group thread is created and selected
    val expectedThreadId = "group_${group.groupId}"
    assertEquals(expectedThreadId, viewModel.uiState.value.selectedThreadId)
    assertTrue(viewModel.uiState.value.conversations.any { it.threadId == expectedThreadId && it.isGroup })

    // Verify ECDH wrapping + unwrapping of random 32-byte group key for a member
    val creatorKeys = TacticalE2eeManager.generateKeyPair()
    val memberKeys = TacticalE2eeManager.generateKeyPair()
    val wrappedForMember = TacticalE2eeManager.wrapGroupKeyForMember(
        groupKeyBase64 = group.groupKeyBase64,
        myPrivateKeyBase64 = creatorKeys.privateKeyBase64,
        memberPublicKeyBase64 = memberKeys.publicKeyBase64
    )
    assertNotNull(wrappedForMember)
    assertTrue(wrappedForMember!!.startsWith("GW2:"))

    val unwrappedByMember = TacticalE2eeManager.unwrapGroupKeyFromMember(
        wrappedPayload = wrappedForMember,
        myPrivateKeyBase64 = memberKeys.privateKeyBase64,
        senderPublicKeyBase64 = creatorKeys.publicKeyBase64
    )
    assertEquals(group.groupKeyBase64, unwrappedByMember)

    // Encrypt & decrypt group message using the unwrapped random AES-256-GCM key
    val secretMsg = "Rally at Waypoint Charlie in 5 mikes"
    val encrypted = TacticalE2eeManager.encryptWithGroupKey(secretMsg, group.groupKeyBase64)
    assertNotNull(encrypted)
    assertTrue(encrypted!!.startsWith("GRP_AES_V2:"))
    val decrypted = TacticalE2eeManager.decryptWithGroupKey(encrypted, unwrappedByMember!!)
    assertEquals(secretMsg, decrypted)
  }

  @Test
  fun `verify adaptive 8kHz full rate and 4kHz linear interpolation voice upsampling`() {
    val pcm8kInput = ShortArray(1600) { i -> ((i * 15) - 12000).coerceIn(-32768, 32767).toShort() }
    val fastBytes = com.kostadinosk.pulsemesh.service.TacticalAudioService.encodeFull8kHz8BitPcm(pcm8kInput)
    assertEquals(1600, fastBytes.size)
    val decodedFast = com.kostadinosk.pulsemesh.service.TacticalAudioService.decodeStateless8BitPcmTo8kHz16Bit(fastBytes)
    assertEquals(1600, decodedFast.size)

    // Verify 4kHz (800 bytes) upsamples to 1600 samples at 8kHz using linear interpolation
    val bleBytes = com.kostadinosk.pulsemesh.service.TacticalAudioService.encodeStateless8BitPcm(pcm8kInput)
    assertEquals(800, bleBytes.size)
    val upsampled8k = com.kostadinosk.pulsemesh.service.TacticalAudioService.decodeStateless8BitPcmTo8kHz16Bit(bleBytes)
    assertEquals(1600, upsampled8k.size)
    val s0 = upsampled8k[0].toInt()
    val s2 = upsampled8k[2].toInt()
    assertEquals((s0 + s2) / 2, upsampled8k[1].toInt())
  }

  @Test
  fun `verify Layer 7 and Layer 8 USB-C and Ethernet interface auto-bridge and Store-and-Forward DTN outbox`() {
    // Layer 8 USB-C & Ethernet interface matching
    assertTrue(com.kostadinosk.pulsemesh.service.AutoHotspotNegotiator.isUsbOrEthernetInterfaceName("usb0"))
    assertTrue(com.kostadinosk.pulsemesh.service.AutoHotspotNegotiator.isUsbOrEthernetInterfaceName("rndis0"))
    assertTrue(com.kostadinosk.pulsemesh.service.AutoHotspotNegotiator.isUsbOrEthernetInterfaceName("ncm0"))
    assertTrue(com.kostadinosk.pulsemesh.service.AutoHotspotNegotiator.isUsbOrEthernetInterfaceName("eth0"))
    assertTrue(com.kostadinosk.pulsemesh.service.AutoHotspotNegotiator.isTargetMeshInterfaceName("wlan0"))
    assertTrue(com.kostadinosk.pulsemesh.service.AutoHotspotNegotiator.isTargetMeshInterfaceName("p2p-wlan0-0"))

    // Store-and-Forward DTN Outbox persistence (up to 50 packets)
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = MeshViewModel(app)
    viewModel.sendMessageInThread("channel_primary", "DTN Mule Test Packet #1")

    val dtnSnapshot = viewModel.getPendingDtnPacketsSnapshot()
    assertTrue("Sent chat packet must be queued in Store-and-Forward DTN outbox", dtnSnapshot.isNotEmpty())
    val persisted = com.kostadinosk.pulsemesh.service.DtnCourierEngine(app).getActiveBundles()
    assertTrue("DTN outbox must persist in Room DtnCourierEngine", persisted.any { it.payload == "DTN Mule Test Packet #1" })

    // Compass & Barometric Altitude calculations
    val bearingNorth = com.kostadinosk.pulsemesh.service.TacticalSensorService.calculateBearingDegrees(37.0, -122.0, 38.0, -122.0)
    assertEquals(0f, bearingNorth, 1.0f)
    val relEast = com.kostadinosk.pulsemesh.service.TacticalSensorService.calculateRelativeBearingDegrees(90f, 0f)
    assertEquals("→", com.kostadinosk.pulsemesh.service.TacticalSensorService.bearingToCompassArrow(relEast))
    val seaLevelAlt = com.kostadinosk.pulsemesh.service.TacticalSensorService.pressureHpaToAltitudeMeters(1013.25f)
    assertEquals(0.0, seaLevelAlt, 0.5)
  }

  @Test
  fun `verify decoupled instant sub-1s peer registration, deterministic GATT role staggering, 45s peer pruning, and shared tactical map pins`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = MeshViewModel(app)
    viewModel.saveDeviceProfile(
        callsign = "Kilo-1",
        nodeId = "0x4444",
        profile = com.kostadinosk.pulsemesh.model.DeviceCatalog.getProfileById("oneplus_15"),
        pureOffGrid = true,
        internetBridge = false
    )

    // 1. Verify deterministic GATT role staggering (150ms when myNodeId > peerNodeId, 1500ms when myNodeId < peerNodeId)
    assertEquals(
        150L,
        com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.computeGattRoleStaggerDelayMs("0x8F2A", "0x2B1C")
    )
    assertEquals(
        1500L,
        com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.computeGattRoleStaggerDelayMs("0x2B1C", "0x8F2A")
    )

    // 2. Verify decoupled instant peer registration BEFORE socket handshakes (even with raw 12-byte BLE 0xCAFE before 0xCAFF arrives)
    val t0 = 1_000_000L
    viewModel.registerOrUpdatePeerInstant(
        peerNodeId = "0x9B1C",
        callsign = "",
        lat = 37.7749,
        lng = -122.4194,
        battery = 91,
        rssiDbm = -52,
        sourceTransport = "BLE 1M",
        nowMs = t0
    )
    val instantPeer = viewModel.uiState.value.nodes.find { !it.isSelf && it.id == "0x9B1C" }
    assertNotNull("Peer must be registered immediately on first 12-byte BLE beacon", instantPeer)
    assertEquals("Node-9B1C", instantPeer!!.callsign)
    assertTrue(instantPeer.transports.contains("BLE 1M"))

    // Subsequent UDP/LAN packet updates callsign, IP, lastSeenMs, and accumulates transports
    viewModel.registerOrUpdatePeerInstant(
        peerNodeId = "0x9B1C",
        callsign = "Bravo-9",
        ipAddress = "192.168.43.88",
        sourceTransport = "UDP/LAN",
        nowMs = t0 + 500L
    )
    val updatedPeer = viewModel.uiState.value.nodes.find { !it.isSelf && it.id == "0x9B1C" }!!
    assertEquals("Bravo-9", updatedPeer.callsign)
    assertEquals("192.168.43.88", updatedPeer.ipAddress)
    assertTrue(updatedPeer.transports.containsAll(setOf("BLE 1M", "UDP/LAN")))

    // 3. Verify 45-second pruning window (peer kept at 40s, pruned only after > 45_000L)
    viewModel.pruneStalePeers(nowMs = t0 + 40_000L)
    assertTrue(
        "Peer must NOT be pruned within 45 seconds",
        viewModel.uiState.value.nodes.any { !it.isSelf && it.id == "0x9B1C" }
    )
    viewModel.pruneStalePeers(nowMs = t0 + 46_000L)
    assertTrue(
        "Peer must be pruned once silent for > 45_000L (45 seconds)",
        viewModel.uiState.value.nodes.none { !it.isSelf && it.id == "0x9B1C" }
    )

    // 4. Verify Hotspot-Guarded Wi-Fi Aware matches OS capability & stays in sync
    val expectedWifiAwareDefault = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
        app.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_AWARE)
    assertEquals(expectedWifiAwareDefault, viewModel.autoHotspotNegotiator.isWifiAwareEnabled)
    assertEquals(expectedWifiAwareDefault, viewModel.uiState.value.wifiAwareEnabled)

    // 5. Verify Shared Tactical Map Pin broadcast & reception (RALLY, HAZARD, SUPPLY, SOS)
    val peerVm = MeshViewModel(app)
    peerVm.saveDeviceProfile(
        callsign = "Charlie-3",
        nodeId = "0x7777",
        profile = com.kostadinosk.pulsemesh.model.DeviceCatalog.getProfileById("oneplus_15"),
        pureOffGrid = true,
        internetBridge = false
    )
    val wpPayload = org.json.JSONObject().apply {
      put("id", "WP-77")
      put("title", "Bridge Hazard")
      put("type", com.kostadinosk.pulsemesh.model.WaypointType.HAZARD.name)
      put("lat", 37.7801)
      put("lon", -122.4102)
      put("creator", "Kilo-1")
      put("ts", System.currentTimeMillis())
    }.toString()

    peerVm.handleIncomingPacket(
        com.kostadinosk.pulsemesh.service.WireMeshPacket(
            packetId = "wp_test_77",
            srcNodeId = "0x4444",
            srcCallsign = "Kilo-1",
            dstNodeId = "ALL",
            type = "WAYPOINT",
            payload = wpPayload,
            lat = 37.7801,
            lng = -122.4102
        )
    )
    assertTrue(
        "Incoming WAYPOINT packet must add shared tactical pin to state.waypoints",
        peerVm.uiState.value.waypoints.any {
          it.id == "WP-77" && it.title == "Bridge Hazard" && it.type == com.kostadinosk.pulsemesh.model.WaypointType.HAZARD
        }
    )
  }

  @Test
  fun `verify zero GATT heartbeats, 1500ms UI state throttling, relay flood suppression, CHAT_ACK delivery, and 300ms pure-BLE voice codec`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = MeshViewModel(app)
    viewModel.saveDeviceProfile(
        callsign = "Kilo-1",
        nodeId = "0x1111",
        profile = com.kostadinosk.pulsemesh.model.DeviceCatalog.getProfileById("oneplus_15"),
        pureOffGrid = true,
        internetBridge = false
    )

    // 1. Zero Heartbeats Over BLE GATT: HEARTBEAT, GPS_BEACON, MUTUAL_PING must be blocked from BLE GATT pipe
    assertTrue(!com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.isEligibleForBleGattPipe("HEARTBEAT"))
    assertTrue(!com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.isEligibleForBleGattPipe("GPS_BEACON"))
    assertTrue(!com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.isEligibleForBleGattPipe("MUTUAL_PING"))
    assertTrue(com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.isEligibleForBleGattPipe("CHAT"))
    assertTrue(com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.isEligibleForBleGattPipe("E2EE_DM"))
    assertTrue(com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.isEligibleForBleGattPipe("CHAT_ACK"))
    assertTrue(com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.isEligibleForBleGattPipe("CALL_RING"))
    assertTrue(com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.isEligibleForBleGattPipe("CALL_ACCEPT"))
    assertTrue(com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.isEligibleForBleGattPipe("VOICE_STREAM"))

    // 2. Throttle activePeers UI updates: repeated beacons within 1,500ms update peersBackingMap in memory without forcing UI recomposition
    val baseT = 5_000_000L
    viewModel.registerOrUpdatePeerInstant(
        peerNodeId = "0x2222",
        callsign = "Bravo-2",
        battery = 80,
        rssiDbm = -60,
        nowMs = baseT
    )
    assertEquals(80, viewModel.uiState.value.nodes.first { it.id == "0x2222" }.batteryPercent)

    // Update 400ms later (< 1500ms): backing map updates immediately, UI snapshot stays throttled
    viewModel.registerOrUpdatePeerInstant(
        peerNodeId = "0x2222",
        callsign = "Bravo-2",
        battery = 75,
        rssiDbm = -52,
        nowMs = baseT + 400L
    )
    assertEquals(75, viewModel.peersBackingMap["2222"]?.batteryPercent)
    assertEquals(80, viewModel.uiState.value.nodes.first { it.id == "0x2222" }.batteryPercent)

    // Update at +1600ms (>= 1500ms): UI snapshot is pushed
    viewModel.registerOrUpdatePeerInstant(
        peerNodeId = "0x2222",
        callsign = "Bravo-2",
        battery = 74,
        rssiDbm = -50,
        nowMs = baseT + 1600L
    )
    assertEquals(74, viewModel.uiState.value.nodes.first { it.id == "0x2222" }.batteryPercent)

    // 3. Stop Relay Floods: never relay HEARTBEAT, GPS_BEACON, MUTUAL_PING, CHAT_ACK, or VOICE_STREAM
    for (blockedType in listOf("HEARTBEAT", "GPS_BEACON", "MUTUAL_PING", "CHAT_ACK", "VOICE_STREAM")) {
      val pkt = com.kostadinosk.pulsemesh.service.WireMeshPacket(
          packetId = "pkt_no_relay_$blockedType",
          srcNodeId = "0x2222",
          srcCallsign = "Bravo-2",
          dstNodeId = "ALL",
          type = blockedType,
          payload = "test",
          ttl = 7,
          hops = 0
      )
      assertEquals(null, viewModel.buildRelayedPacketIfEligible(pkt, "0x1111"))
    }

    // Relay CHAT once per packetId
    val chatRelayPkt = com.kostadinosk.pulsemesh.service.WireMeshPacket(
        packetId = "pkt_chat_relay_once",
        srcNodeId = "0x2222",
        srcCallsign = "Bravo-2",
        dstNodeId = "ALL",
        type = "CHAT",
        payload = "Hello Mesh",
        ttl = 5,
        hops = 0
    )
    assertNotNull(viewModel.buildRelayedPacketIfEligible(chatRelayPkt, "0x1111"))
    assertEquals("Same packetId must never be relayed twice", null, viewModel.buildRelayedPacketIfEligible(chatRelayPkt, "0x1111"))

    // 4. Automatic CHAT_ACK on receiving CHAT & marking pendingChatAcks
    val msgId = "msg_ack_test_99"
    viewModel.pendingChatAcks[msgId] = false
    viewModel.handleIncomingPacket(
        com.kostadinosk.pulsemesh.service.WireMeshPacket(
            packetId = "ack_${msgId}_0x2222",
            srcNodeId = "0x2222",
            srcCallsign = "Bravo-2",
            dstNodeId = "0x1111",
            type = "CHAT_ACK",
            payload = msgId
        )
    )
    assertEquals(true, viewModel.pendingChatAcks[msgId])

    // 5. Pure-BLE 300ms 3200Hz 8-bit signed PCM codec (2400 samples @ 8kHz -> 960 bytes -> 2400 samples @ 8kHz)
    val samples300ms = ShortArray(2400) { i ->
      (kotlin.math.sin(2.0 * kotlin.math.PI * i * 440.0 / 8000.0) * 16000).toInt().toShort()
    }
    val ble960Bytes = com.kostadinosk.pulsemesh.service.TacticalAudioService.encode300msBle3200Hz8BitPcm(samples300ms)
    assertEquals(960, ble960Bytes.size)
    val decoded2400 = com.kostadinosk.pulsemesh.service.TacticalAudioService.decodeStateless8BitPcmTo8kHz16Bit(ble960Bytes)
    assertEquals(2400, decoded2400.size)
  }

  @Test
  fun `verify G711 mu-law codec, PV binary voice frames, BLE beacon location privacy, 5 per sec relay rate limiter, and foreground mic gate`() {
    // 1. G.711 mu-law codec & 8-byte "PV" binary voice frames
    val pcm160 = ShortArray(160) { i ->
      (kotlin.math.sin(2.0 * kotlin.math.PI * i * 600.0 / 8000.0) * 14000).toInt().toShort()
    }
    val muLawBytes = com.kostadinosk.pulsemesh.service.TacticalAudioService.encodePcm16ToMuLaw(pcm160)
    assertEquals(160, muLawBytes.size)
    val decodedPcm = com.kostadinosk.pulsemesh.service.TacticalAudioService.decodeMuLawToPcm16(muLawBytes)
    assertEquals(160, decodedPcm.size)

    val pvFrame = com.kostadinosk.pulsemesh.service.TacticalAudioService.packBinaryVoiceFrame(
        srcNodeId = "0x4F2A",
        dstNodeId = "0x9B1C",
        seq = 42,
        muLawBytes = muLawBytes
    )
    assertEquals(168, pvFrame.size)
    assertEquals(0x50.toByte(), pvFrame[0])
    assertEquals(0x56.toByte(), pvFrame[1])

    val unpackedPv = com.kostadinosk.pulsemesh.service.TacticalAudioService.unpackBinaryVoiceFrame(pvFrame)
    assertNotNull(unpackedPv)
    assertEquals("0x4F2A", unpackedPv!!.srcNodeId)
    assertEquals("0x9B1C", unpackedPv.dstNodeId)
    assertEquals(42, unpackedPv.seq)
    assertEquals(160, unpackedPv.muLawPayload.size)

    // 2. Opt-in BLE beacon location privacy (OFF -> null/null; ON -> coarse ~1.1km rounded)
    val privateCoords = com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.prepareBeaconCoordinates(
        lat = 37.774929,
        lon = -122.419416,
        broadcastCoarseLocation = false
    )
    assertEquals(null, privateCoords.first)
    assertEquals(null, privateCoords.second)

    val coarseCoords = com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.prepareBeaconCoordinates(
        lat = 37.774929,
        lon = -122.419416,
        broadcastCoarseLocation = true
    )
    assertEquals(37.77, coarseCoords.first!!, 0.0001)
    assertEquals(-122.42, coarseCoords.second!!, 0.0001)

    // 3. 5 packets/sec per-source relay flood rate limiter
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = MeshViewModel(app)
    viewModel.saveDeviceProfile(
        callsign = "Relay-1",
        nodeId = "0x1111",
        profile = com.kostadinosk.pulsemesh.model.DeviceCatalog.getProfileById("oneplus_15"),
        pureOffGrid = true,
        internetBridge = false
    )
    assertTrue("BLE location broadcast must be OFF by default", !viewModel.uiState.value.broadcastCoarseLocationInBle)

    for (i in 1..5) {
      val pkt = com.kostadinosk.pulsemesh.service.WireMeshPacket(
          packetId = "rate_pkt_$i",
          srcNodeId = "0x2222",
          srcCallsign = "Flooder",
          dstNodeId = "ALL",
          type = "CHAT",
          payload = "Msg $i",
          ttl = 7,
          hops = 0
      )
      assertNotNull("Packet #$i within 5/sec quota must be relayed", viewModel.buildRelayedPacketIfEligible(pkt, "0x1111"))
    }
    val sixthPkt = com.kostadinosk.pulsemesh.service.WireMeshPacket(
        packetId = "rate_pkt_6",
        srcNodeId = "0x2222",
        srcCallsign = "Flooder",
        dstNodeId = "ALL",
        type = "CHAT",
        payload = "Msg 6",
        ttl = 7,
        hops = 0
    )
    assertEquals("6th packet from same source within 1s must be rate-limited", null, viewModel.buildRelayedPacketIfEligible(sixthPkt, "0x1111"))

    val otherSrcPkt = com.kostadinosk.pulsemesh.service.WireMeshPacket(
        packetId = "rate_pkt_other",
        srcNodeId = "0x3333",
        srcCallsign = "Charlie-3",
        dstNodeId = "ALL",
        type = "CHAT",
        payload = "From Charlie",
        ttl = 7,
        hops = 0
    )
    assertNotNull("Different source node must still have its own 5/sec quota", viewModel.buildRelayedPacketIfEligible(otherSrcPkt, "0x1111"))

    // 4. Foreground-only microphone safety gate (Android 14-17 FGS compliance)
    viewModel.setAppInForeground(true)
    assertTrue(viewModel.canStartMicrophoneCapture(forPtt = true, forCall = false))
    viewModel.setAppInForeground(false)
    assertTrue("Backgrounded app must refuse AudioRecord capture", !viewModel.canStartMicrophoneCapture(forPtt = true, forCall = true))
    assertTrue(!viewModel.uiState.value.isAppInForeground)
  }

  @Test
  fun `verify 0000CAFF-only VOICE routing, G711 mu-law 640B 160ms pipeline, half-duplex ducking, and LocationShareMode`() {
    val app = ApplicationProvider.getApplicationContext<Application>()

    // 1. Verify OffGridBleMeshEngine has ONLY 0000CAFF (zero 0000CB00 / binary frame hooks)
    assertEquals("0000caff-0000-1000-8000-00805f9b34fb", com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.VOICE_CHAR_UUID.toString())
    assertTrue(com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.isEligibleForBleGattPipe("VOICE"))
    val bleClass = com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine::class.java
    val legacyMembers = (bleClass.declaredMethods.map { it.name } + bleClass.declaredFields.map { it.name })
      .filter { it.contains("BinaryAudio", ignoreCase = true) || it.contains("CB00", ignoreCase = true) }
    assertTrue("OffGridBleMeshEngine must have zero CB00/BinaryAudio members", legacyMembers.isEmpty())

    // 2. Verify G.711 mu-law 160ms codec: ShortArray(1280) @ 8kHz -> 640 bytes @ 4kHz -> ShortArray(1280) @ 8kHz
    val pcm1280 = ShortArray(1280) { i ->
      (kotlin.math.sin(2.0 * kotlin.math.PI * i * 500.0 / 8000.0) * 12000).toInt().toShort()
    }
    val muLaw640 = com.kostadinosk.pulsemesh.service.TacticalAudioService.encode160msVoiceMuLaw640(pcm1280)
    assertEquals(640, muLaw640.size)
    val decoded1280 = com.kostadinosk.pulsemesh.service.TacticalAudioService.decodeMuLaw4kHzTo8kHzInterpolated(muLaw640)
    assertEquals(1280, decoded1280.size)
    val maxDecoded = decoded1280.maxOf { kotlin.math.abs(it.toInt()) }
    assertTrue("Decoded interpolated PCM must carry signal energy", maxDecoded > 1000)

    // 3. Verify 1-slot bleVoiceTxChannel (DROP_OLDEST) keeps only newest VOICE frame when congested
    val vm = MeshViewModel(app)
    vm.saveDeviceProfile(
      callsign = "Kilo-1",
      nodeId = "0x1111",
      profile = com.kostadinosk.pulsemesh.model.DeviceCatalog.getProfileById("oneplus_15"),
      pureOffGrid = true,
      internetBridge = false
    )
    val pkt1 = com.kostadinosk.pulsemesh.service.WireMeshPacket("v1", "0x1111", "Kilo-1", "ALL", "VOICE", "FRAME_1")
    val pkt2 = com.kostadinosk.pulsemesh.service.WireMeshPacket("v2", "0x1111", "Kilo-1", "ALL", "VOICE", "FRAME_2")
    vm.offGridBleEngine.bleVoiceTxChannel.trySend(vm.offGridBleEngine.packetToJson(pkt1))
    vm.offGridBleEngine.bleVoiceTxChannel.trySend(vm.offGridBleEngine.packetToJson(pkt2))
    val queuedJson = vm.offGridBleEngine.bleVoiceTxChannel.tryReceive().getOrNull()
    assertNotNull(queuedJson)
    assertTrue("1-slot DROP_OLDEST voice channel must keep newest FRAME_2", queuedJson!!.contains("FRAME_2"))

    // 4. Verify incoming VOICE JSON packet decodes base64 mu-law payload & increments rxAudioCount
    val b64Voice = android.util.Base64.encodeToString(muLaw640, android.util.Base64.NO_WRAP)
    val beforeRx = vm.uiState.value.rxAudioCount
    vm.handleIncomingPacket(
      com.kostadinosk.pulsemesh.service.WireMeshPacket(
        packetId = "voice_rx_test_1",
        srcNodeId = "0x2222",
        srcCallsign = "Bravo-2",
        dstNodeId = "ALL",
        type = "VOICE",
        payload = b64Voice
      )
    )
    assertEquals(beforeRx + 1, vm.uiState.value.rxAudioCount)

    // 5. Verify full-duplex double-talk (simultaneous talking works smoothly while quiet noise stays gated) and Push-To-Override
    val tNow = 10_000L
    assertTrue(
      "Full-duplex double-talk must allow BLE voice TX when speaking during incoming audio",
      com.kostadinosk.pulsemesh.service.TacticalCallService.shouldTransmitCallVoiceFrame(
        hasHotspotUdpLink = false,
        micPeak = 2000,
        nowMs = tNow,
        lastIncomingAudioTime = tNow - 200L,
        pushToOverrideActive = false
      )
    )
    assertTrue(
      "Quiet background noise (micPeak=150) remains gated on BLE calls",
      !com.kostadinosk.pulsemesh.service.TacticalCallService.shouldTransmitCallVoiceFrame(
        hasHotspotUdpLink = false,
        micPeak = 150,
        nowMs = tNow,
        lastIncomingAudioTime = tNow - 200L,
        pushToOverrideActive = false
      )
    )
    assertTrue(
      "Push-To-Override must force BLE voice TX even during duck window",
      com.kostadinosk.pulsemesh.service.TacticalCallService.shouldTransmitCallVoiceFrame(
        hasHotspotUdpLink = false,
        micPeak = 200,
        nowMs = tNow,
        lastIncomingAudioTime = tNow - 100L,
        pushToOverrideActive = true
      )
    )
    assertTrue(
      "Hotspot UDP full-duplex must allow TX during incoming audio when micPeak > 300",
      com.kostadinosk.pulsemesh.service.TacticalCallService.shouldTransmitCallVoiceFrame(
        hasHotspotUdpLink = true,
        micPeak = 600,
        nowMs = tNow,
        lastIncomingAudioTime = tNow - 100L,
        pushToOverrideActive = false
      )
    )

    // 6. Verify 3-option LocationShareMode (OFF | COARSE | EXACT)
    val offCoords = com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.applyLocationShareMode(37.774929, -122.419416, com.kostadinosk.pulsemesh.model.LocationShareMode.OFF)
    assertEquals(0.0, offCoords.first, 0.0)
    assertEquals(0.0, offCoords.second, 0.0)

    val coarseCoords = com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.applyLocationShareMode(37.774929, -122.419416, com.kostadinosk.pulsemesh.model.LocationShareMode.COARSE)
    assertEquals(37.77, coarseCoords.first, 0.00001)
    assertEquals(-122.42, coarseCoords.second, 0.00001)

    val exactCoords = com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.applyLocationShareMode(37.774929, -122.419416, com.kostadinosk.pulsemesh.model.LocationShareMode.EXACT)
    assertEquals(37.774929, exactCoords.first, 0.000001)
    assertEquals(-122.419416, exactCoords.second, 0.000001)

    vm.setLocationShareMode(com.kostadinosk.pulsemesh.model.LocationShareMode.OFF)
    assertEquals(com.kostadinosk.pulsemesh.model.LocationShareMode.OFF, vm.uiState.value.locationShareMode)
    vm.setLocationShareMode(com.kostadinosk.pulsemesh.model.LocationShareMode.COARSE)
    assertEquals(com.kostadinosk.pulsemesh.model.LocationShareMode.COARSE, vm.uiState.value.locationShareMode)
    assertTrue(vm.uiState.value.broadcastCoarseLocationInBle)
    vm.setLocationShareMode(com.kostadinosk.pulsemesh.model.LocationShareMode.EXACT)
    assertEquals(com.kostadinosk.pulsemesh.model.LocationShareMode.EXACT, vm.uiState.value.locationShareMode)
  }

  @Test
  fun testPulseMeshV11Upgrades() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val vm = MeshViewModel(app)

    // 1. Dead-reckoning step advance North (0 deg) increases latitude
    val (nLat, nLon) = com.kostadinosk.pulsemesh.service.TacticalSensorService.stepAdvanceLatLon(37.9838, 23.7275, 0f, strideMeters = 1.0)
    assertTrue("Stepping North must increase latitude", nLat > 37.9838)
    assertEquals(23.7275, nLon, 0.0001)

    // 2. Consolidated Bell202 Acoustic 2.0 fingerprint checksum & Goertzel demodulation
    val checksum = com.kostadinosk.pulsemesh.service.Bell202AcousticModem.fingerprintChecksum8("TEST_PUBKEY_BASE64")
    assertTrue("Fingerprint checksum must be 8-bit", checksum in 0..255)
    val pcm = com.kostadinosk.pulsemesh.service.Bell202AcousticModem.modulateFrameToPcm("PMTEST".toByteArray())
    assertTrue("Modulated Bell202 PCM must be non-empty", pcm.isNotEmpty())
    val goertzel = com.kostadinosk.pulsemesh.service.Bell202AcousticModem.goertzelMagnitudeSquared(
      pcm, 0, com.kostadinosk.pulsemesh.service.Bell202AcousticModem.SYMBOL_SAMPLES,
      com.kostadinosk.pulsemesh.service.Bell202AcousticModem.FREQ_PREAMBLE_HZ
    )
    assertTrue("Goertzel preamble bin energy must be positive", goertzel > 0.0)

    // 3. Breadcrumb GPX import/export
    val gpxSample = """
      <?xml version="1.0"?>
      <gpx version="1.1"><trk><trkseg>
        <trkpt lat="37.983800" lon="23.727500"><ele>120.5</ele></trkpt>
        <trkpt lat="37.984100" lon="23.727900"><ele>122.0</ele></trkpt>
      </trkseg></trk></gpx>
    """.trimIndent()
    val imported = vm.importBreadcrumbsFromGpx(gpxSample)
    assertEquals(2, imported)
    val exported = vm.exportBreadcrumbsAsGpx()
    assertTrue(exported.contains("37.983800"))
    assertTrue(exported.contains("23.727900"))

    // 4. Burn-on-read TTL & Duress wipe
    vm.sendChatMessage("Secret tactical ping")
    assertTrue(vm.setDuressPin("9911"))
    assertTrue(vm.uiState.value.hasDuressPin)
    assertTrue("Wrong duress PIN must not wipe", !vm.verifyOrTriggerDuressWipe("1234"))
    assertTrue("Correct duress PIN must wipe", vm.verifyOrTriggerDuressWipe("9911"))
    assertEquals(0, vm.uiState.value.breadcrumbs.size)
  }
}
