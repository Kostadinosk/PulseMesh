// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh

import android.app.Application
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager
import com.kostadinosk.pulsemesh.model.ChatConversation
import com.kostadinosk.pulsemesh.model.ConferencePeerStatus
import com.kostadinosk.pulsemesh.model.MeshGroup
import com.kostadinosk.pulsemesh.model.MeshPacket
import com.kostadinosk.pulsemesh.model.PacketType
import com.kostadinosk.pulsemesh.service.CallSignalingState
import com.kostadinosk.pulsemesh.service.DuplexCallState
import com.kostadinosk.pulsemesh.service.TacticalAudioService
import com.kostadinosk.pulsemesh.service.WireMeshPacket
import com.kostadinosk.pulsemesh.ui.screens.MeshChatScreen
import com.kostadinosk.pulsemesh.ui.screens.SettingsScreen
import com.kostadinosk.pulsemesh.ui.screens.SimpleCallVoiceScreen
import com.kostadinosk.pulsemesh.ui.theme.DarkPulsePalette
import com.kostadinosk.pulsemesh.ui.theme.LocalPulsePalette
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.util.AppTheme
import com.kostadinosk.pulsemesh.model.BatteryPowerProfile
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState
import com.kostadinosk.pulsemesh.viewmodel.controllers.ChunkedFileTransferManager
import com.kostadinosk.pulsemesh.viewmodel.controllers.FileTransferDirection
import com.kostadinosk.pulsemesh.viewmodel.controllers.FileTransferProgress
import com.kostadinosk.pulsemesh.viewmodel.controllers.FileTransferState
import com.kostadinosk.pulsemesh.voice.AdaptiveJitterBuffer
import com.kostadinosk.pulsemesh.voice.AudioRoute
import com.kostadinosk.pulsemesh.voice.AudioRouteManager
import com.kostadinosk.pulsemesh.voice.ConferenceAudioMixer
import com.kostadinosk.pulsemesh.voice.HardwarePttController
import com.kostadinosk.pulsemesh.voice.TacticalPttChannelController
import com.kostadinosk.pulsemesh.voice.VoiceEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h860dp")
class V150VoiceAndMessengerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `adaptive jitter buffer reorders frames and conceals missing slot with PLC fade`() {
        val jb = AdaptiveJitterBuffer(frameDurationMs = 20, minDepthMs = 40, maxDepthMs = 200)
        val f0 = ShortArray(160) { 10_000 }
        val f1 = ShortArray(160) { 12_000 }
        val f3 = ShortArray(160) { 8_000 }

        // Deliver out of order and omit seq 2
        jb.pushFrame(seq = 0, pcmSamples = f0, arrivalMs = 1000L)
        jb.pushFrame(seq = 3, pcmSamples = f3, arrivalMs = 1090L)
        jb.pushFrame(seq = 1, pcmSamples = f1, arrivalMs = 1030L)

        val out0 = jb.popNextFrame(160)
        val out1 = jb.popNextFrame(160)
        val out2Plc = jb.popNextFrame(160)
        val out3 = jb.popNextFrame(160)

        assertArrayEquals(f0, out0)
        assertArrayEquals(f1, out1)
        assertNotNull("PLC should synthesize attenuated frame on missing seq=2", out2Plc)
        assertEquals(8400, out2Plc!![0].toInt()) // 12000 * 0.70
        assertArrayEquals(f3, out3)
    }

    @Test
    fun `conference audio mixer caps at 6 peers, soft-clips loud sums, and enforces mute and kick`() {
        val mixer = ConferenceAudioMixer()
        for (i in 1..6) {
            assertTrue(mixer.addOrUpdatePeer("0xP$i", "Peer-$i"))
            mixer.pushPeerPcmFrame("0xP$i", "Peer-$i", seq = 0, pcmSamples = ShortArray(160) { 20_000 })
            mixer.pushPeerPcmFrame("0xP$i", "Peer-$i", seq = 1, pcmSamples = ShortArray(160) { 20_000 })
        }
        assertFalse("7th conference peer must be rejected above MAX=6", mixer.addOrUpdatePeer("0xP7", "Peer-7"))

        // Soft-clipped sum stays within signed 16-bit PCM bounds
        val mixed = mixer.mixPlaybackFrame(160)
        assertNotNull(mixed)
        assertTrue(mixed!!.all { it.toInt() in -32767..32767 && it > 24000 })

        mixer.setPeerMuted("0xP1", true)
        assertTrue(mixer.peerStatuses.value.first { it.nodeId == "0xP1" }.isMuted)

        mixer.kickPeer("0xP2")
        assertFalse(mixer.peerStatuses.value.any { it.nodeId == "0xP2" })
        // Slot freed after kick allows Peer-7
        assertTrue(mixer.addOrUpdatePeer("0xP7", "Peer-7"))
    }

    @Test
    fun `tactical PTT channels arbitrate floor deterministically and hardware PTT ignores keyguard`() {
        val ctrl = TacticalPttChannelController()
        ctrl.joinChannel("ops", "0xA1")
        assertEquals("#ops", ctrl.activeChannelId.value)
        assertTrue(ctrl.requestFloor("#ops", "0xA1", "Alice", nowMs = 5_000L))
        assertFalse(ctrl.requestFloor("#ops", "0xB2", "Bob", nowMs = 5_100L))

        // Remote signal with earlier timestamp preempts cleanly
        assertTrue(ctrl.onRemoteFloorSignal("#ops", "0xB2", "Bob", timestampMs = 4_900L, acquired = true))
        assertEquals("0xB2", ctrl.getActiveChannel().activeFloorNodeId)
        ctrl.setCourtesyBeepEnabled(false)
        assertFalse(ctrl.courtesyBeepEnabled.value)

        var pttActive = false
        var locked = false
        val hw = HardwarePttController(
            isHardwarePttEnabled = { true },
            isKeyguardLocked = { locked },
            isCurrentlyTransmitting = { pttActive },
            onStartPtt = { pttActive = true },
            onStopPtt = { pttActive = false }
        )
        assertTrue(hw.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, HardwarePttController.KEYCODE_HARDWARE_PTT)))
        assertTrue(pttActive)
        assertTrue(hw.handleKeyEvent(KeyEvent(KeyEvent.ACTION_UP, HardwarePttController.KEYCODE_HARDWARE_PTT)))
        assertFalse(pttActive)

        locked = true
        assertFalse(hw.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
        assertFalse(pttActive)
    }

    @Test
    fun `flavor voice engine, audio route manager, and SRTP voice crypto verify E2EE transport`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val routes = AudioRouteManager(app)
        assertTrue(routes.refreshRoutes().containsAll(listOf(AudioRoute.SPEAKER, AudioRoute.EARPIECE)))
        routes.selectRoute(AudioRoute.EARPIECE)
        assertEquals(AudioRoute.EARPIECE, routes.activeRoute.value)

        val engine = VoiceEngineFactory.create(app)
        val encoded = engine.encodeOutboundPcmFrame(ShortArray(640) { (it * 13 % 12000).toShort() }, sampleRateHz = 8000)
        assertTrue(encoded.isNotEmpty())
        val decoded = engine.onInboundEncodedFrame("0xPEER", seq = 1, payloadBytes = encoded)
        assertNotNull(decoded)

        val kpA = TacticalE2eeManager.generateKeyPair("a_voice")
        val kpB = TacticalE2eeManager.generateKeyPair("b_voice")
        val kA = TacticalE2eeManager.deriveVoiceSessionKey("call_9", kpA.privateKeyBase64, kpB.publicKeyBase64)!!
        val kB = TacticalE2eeManager.deriveVoiceSessionKey("call_9", kpB.privateKeyBase64, kpA.publicKeyBase64)!!

        val plainVoice = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val encVoice = TacticalE2eeManager.encryptVoiceFrame(kA, "call_9", "0xA", seqNum = 42, audioBytes = plainVoice)
        assertArrayEquals(plainVoice, TacticalE2eeManager.decryptVoiceFrame(kB, "call_9", "0xA", seqNum = 42, encryptedPacket = encVoice))
        // Seq mismatch violates AAD binding
        assertNull(TacticalE2eeManager.decryptVoiceFrame(kB, "call_9", "0xA", seqNum = 43, encryptedPacket = encVoice))
    }

    @Test
    fun `chunked file transfer splits, pauses, resumes, and verifies SHA-256`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sender = ChunkedFileTransferManager(app)
        val receiver = ChunkedFileTransferManager(app)

        val raw = ByteArray(1500) { (it % 251).toByte() }
        val packets = sender.startOutboundTransfer(
            transferId = "ft_1",
            threadId = "dm_0xB2",
            targetNodeId = "0xB2",
            fileName = "map-patch.bin",
            mimeType = "application/octet-stream",
            rawBytes = raw,
            chunkSize = 512
        )
        assertEquals(4, packets.size) // 1 offer + 3 chunks

        val offer = receiver.handleIncomingOffer(packets.first().payload, "dm_0xB2")
        assertNotNull(offer)
        receiver.pauseTransfer("ft_1")
        assertEquals(FileTransferState.PAUSED, receiver.transfers.value["ft_1"]?.state)

        // Feed chunks 0 and 2, verify chunk 1 reported missing by resume builder
        receiver.handleIncomingChunk(packets[1].payload)
        receiver.handleIncomingChunk(packets[3].payload)
        val resumePkts = receiver.buildResumeMissingChunks("ft_1")
        assertEquals(1, resumePkts.size)
        assertEquals("1", org.json.JSONObject(resumePkts[0].payload).getString("missingCsv"))

        // Deliver missing chunk 1 -> SHA-256 verification completes transfer
        val done = receiver.handleIncomingChunk(packets[2].payload)
        assertEquals(FileTransferState.COMPLETED, done?.state)
        assertArrayEquals(raw, receiver.readEncryptedAttachment(done!!.blobPath))
    }

    @Test
    fun `material3 theme palette is distinct from tactical dark`() {
        var m3Background: androidx.compose.ui.graphics.Color? = null
        composeTestRule.setContent {
            PulseMeshTheme(appTheme = AppTheme.MATERIAL3) {
                val p = LocalPulsePalette.current
                m3Background = p.background
                assertFalse(p.isAero)
                assertFalse(p.isOriginal)
            }
        }
        composeTestRule.waitForIdle()
        assertNotEquals(DarkPulsePalette.background, m3Background)
    }

    @Test
    fun `settings home exposes searchable battery profiles and tactical HUD switch`() {
        var hudToggledTo: Boolean? = null
        var batteryPicked: BatteryPowerProfile? = null

        composeTestRule.setContent {
            PulseMeshTheme(appTheme = AppTheme.TACTICAL_DARK) {
                Box(modifier = Modifier.size(412.dp, 860.dp)) {
                    SettingsScreen(
                        state = MeshUiState(showTacticalHud = false, batteryPowerProfile = BatteryPowerProfile.BALANCED),
                        onToggleSimpleMode = {},
                        onToggleTransportMode = {},
                        onSelectPhoneProfile = {},
                        onAutoMatchHardware = {},
                        onRegenerateNodeId = {},
                        onUpdateCallsign = {},
                        onUpdateMeshChannel = {},
                        onPingMeshNow = {},
                        onRequestGpsPermission = {},
                        onSetManualLocation = { _, _ -> },
                        onOpenSetupWizard = {},
                        onSetShowTacticalHud = { hudToggledTo = it },
                        onSetBatteryPowerProfile = { batteryPicked = it }
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_search_bar").performScrollTo().performTextInput("battery")
        composeTestRule.onNodeWithTag("settings_battery_profile_expedition").performScrollTo().performClick()
        assertEquals(BatteryPowerProfile.EXPEDITION, batteryPicked)
        composeTestRule.onNodeWithTag("settings_tactical_hud_switch").performScrollTo().performClick()
        assertEquals(true, hudToggledTo)
    }

    @Test
    fun `voice hub renders E2EE badge, audio routes, codec HUD, conference grid, and PTT deck`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val audioSvc = TacticalAudioService(app, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        composeTestRule.setContent {
            PulseMeshTheme(appTheme = AppTheme.TACTICAL_DARK) {
                Box(modifier = Modifier.size(412.dp, 860.dp)) {
                    SimpleCallVoiceScreen(
                        state = MeshUiState(
                            showTacticalHud = true,
                            conferencePeers = listOf(ConferencePeerStatus("0xP1", "Scout", isSpeaking = true))
                        ),
                        duplexCallState = DuplexCallState(
                            signalingState = CallSignalingState.IN_CALL_ACTIVE,
                            isInCall = true
                        ),
                        audioService = audioSvc,
                        onStartCall = {},
                        onEndCall = {},
                        onToggleMute = {},
                        onToggleSpeaker = {},
                        onStartPtt = {},
                        onStopPtt = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("voice_e2ee_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("voice_route_speaker").assertIsDisplayed()
        composeTestRule.onNodeWithTag("voice_codec_hud_strip").assertIsDisplayed()
        composeTestRule.onNodeWithTag("conference_grid_panel").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("tab_walkie_talkie_ptt").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("walkie_talkie_deck_panel").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `messenger thread renders typing banner, reply quote, file card, voice speed, and group admin`() {
        val samplePkt = MeshPacket(
            id = "vn_1",
            sourceId = "0x1111",
            destId = "grp_1",
            sourceCallsign = "Scout",
            type = PacketType.TEXT,
            content = "[[PM_VOICENOTE]]:AAAA",
            hops = 0,
            rawHexBadge = "[WIFI]",
            isIncoming = false,
            isDelivered = true,
            deliveryStatus = "READ",
            replyToId = "prev_0",
            replyToSender = "Lead",
            replyToPreview = "Check sector 4",
            voiceDurationMs = 3_200L
        )
        val grp = MeshGroup("grp_1", "Alpha Net", listOf("0xSELF", "0x1111"), adminIds = listOf("0xSELF"), keyEpoch = 2)
        val convo = ChatConversation("grp_1", "Alpha Net", isChannel = false, isEncrypted = true, isGroup = true, groupId = "grp_1", memberIds = listOf("0xSELF", "0x1111"))

        composeTestRule.setContent {
            PulseMeshTheme(appTheme = AppTheme.TACTICAL_DARK) {
                Box(modifier = Modifier.size(412.dp, 860.dp)) {
                    MeshChatScreen(
                        state = MeshUiState(
                            selectedThreadId = "grp_1",
                            conversations = listOf(convo),
                            meshGroups = listOf(grp),
                            activeThreadPackets = listOf(samplePkt),
                            typingPeersByThread = mapOf("grp_1" to ("Scout" to System.currentTimeMillis())),
                            replyingToPacket = samplePkt,
                            fileTransfers = mapOf(
                                "ft_ui" to FileTransferProgress(
                                    transferId = "ft_ui",
                                    threadId = "grp_1",
                                    fileName = "route.gpx",
                                    mimeType = "application/gpx+xml",
                                    totalBytes = 1024L,
                                    totalChunks = 2,
                                    completedChunks = 1,
                                    sha256 = "abc",
                                    direction = FileTransferDirection.OUTBOUND,
                                    state = FileTransferState.ACTIVE
                                )
                            )
                        ),
                        onSendMessage = {},
                        onBroadcastGpsPin = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("chat_typing_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_reply_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_file_attachment_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_voice_note_hold_btn").assertIsDisplayed()
        composeTestRule.onNodeWithTag("voice_note_speed_btn").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_group_mgmt_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("group_mgmt_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("group_rotate_key_btn").assertIsDisplayed()
    }
}
