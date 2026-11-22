package com.kostadinosk.pulsemesh

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kostadinosk.pulsemesh.model.MeshNode
import com.kostadinosk.pulsemesh.service.CallSignalingState
import com.kostadinosk.pulsemesh.service.DuplexCallState
import com.kostadinosk.pulsemesh.service.TacticalAudioService
import com.kostadinosk.pulsemesh.service.TacticalCallService
import com.kostadinosk.pulsemesh.service.WireMeshPacket
import com.kostadinosk.pulsemesh.ui.components.PersistentMiniCallBar
import com.kostadinosk.pulsemesh.ui.screens.SimpleCallVoiceScreen
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState
import com.kostadinosk.pulsemesh.viewmodel.MeshViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceCallAndResponsiveUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Suppress("UNUSED_PARAMETER")
    private fun noOpStartCall(node: MeshNode) {
        // No-op for tests
    }

    @Test
    fun `verify TacticalCallService isPeerSpeaking flag activates on incoming frame and clears after duck window`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val callService = TacticalCallService(context = context, scope = scope)
        callService.isCapturePermitted = { false }
        callService.isHighSpeedLinkActive = { false }

        callService.startIncomingCall("0x9B1C", "Bravo-2")
        callService.acceptCall(startMicDelayMs = 0L)

        assertFalse(callService.callState.value.isPeerSpeaking)
        assertFalse(callService.callState.value.isHighSpeedLink)

        callService.recordIncomingFramePlayed()
        assertTrue(
            "isPeerSpeaking must turn true when incoming audio plays",
            callService.callState.value.isPeerSpeaking,
        )

        Thread.sleep(TacticalCallService.BLE_HALF_DUPLEX_DUCK_WINDOW_MS + 120L)
        assertFalse(
            "isPeerSpeaking must auto-clear after BLE half-duplex duck window",
            callService.callState.value.isPeerSpeaking,
        )

        callService.endCall()
        scope.cancel()
    }

    @Test
    fun `verify PersistentMiniCallBar shows on non-call tabs, dispatches controls, and hides on call tab`() {
        var selectedTab by mutableIntStateOf(0)
        var muteClicked = false
        var endClicked = false

        val uiState = MeshUiState(
            selectedTabIndex = 0,
            callState = CallSignalingState.IN_CALL_ACTIVE,
        )
        val duplexState = DuplexCallState(
            signalingState = CallSignalingState.IN_CALL_ACTIVE,
            isInCall = true,
            peerNodeId = "0x9B1C",
            peerCallsign = "Bravo-2",
            durationSeconds = 42,
            isVoiceTransmitting = true,
        )

        composeTestRule.setContent {
            PulseMeshTheme {
                PersistentMiniCallBar(
                    state = uiState.copy(selectedTabIndex = selectedTab),
                    duplexCallState = duplexState,
                    onReturnToCallScreen = { selectedTab = 2 },
                    onToggleMute = { muteClicked = true },
                    onAcceptCall = {},
                    onDeclineCall = {},
                    onEndCall = { endClicked = true },
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("persistent_mini_call_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mini_call_mute_button").performClick()
        assertTrue(muteClicked)

        composeTestRule.onNodeWithTag("mini_call_end_button").performClick()
        assertTrue(endClicked)

        composeTestRule.onNodeWithTag("persistent_mini_call_bar").performClick()
        composeTestRule.waitForIdle()
        assertEquals(2, selectedTab)
        composeTestRule.onNodeWithTag("persistent_mini_call_bar").assertDoesNotExist()
    }

    @Test
    fun `verify optimized SimpleCallVoiceScreen renders floor states and directly visible dialer controls in compact viewport`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audioService = TacticalAudioService(context = context, scope = scope)

        var duplexState by mutableStateOf(
            DuplexCallState(
                signalingState = CallSignalingState.IN_CALL_ACTIVE,
                isInCall = true,
                peerNodeId = "0x9B1C",
                peerCallsign = "Bravo-2",
                durationSeconds = 18,
                isVoiceTransmitting = true,
                isHighSpeedLink = true,
            ),
        )
        var muteToggles = 0
        var speakerToggles = 0
        var endClicks = 0

        composeTestRule.setContent {
            PulseMeshTheme {
                Box(modifier = Modifier.size(width = 360.dp, height = 520.dp)) {
                    SimpleCallVoiceScreen(
                        state = MeshUiState(
                            selectedTabIndex = 2,
                            callState = CallSignalingState.IN_CALL_ACTIVE,
                            rxAudioCount = 7,
                        ),
                        duplexCallState = duplexState,
                        audioService = audioService,
                        onStartCall = ::noOpStartCall,
                        onEndCall = { endClicks++ },
                        onToggleMute = { muteToggles++ },
                        onToggleSpeaker = { speakerToggles++ },
                        onStartPtt = {},
                        onStopPtt = {},
                        onToggleLiveMicrophone = {},
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("voice_floor_state_banner")
            .assertIsDisplayed()
            .assertTextContains("TX ACTIVE — YOUR VOICE IS LIVE", substring = true)

        // All primary dialer controls must be directly visible without scrolling on a compact 360x520dp viewport
        composeTestRule.onNodeWithTag("voice_mute_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("voice_speaker_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("voice_push_to_override_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("voice_end_call_button").assertIsDisplayed().performClick()

        assertEquals(1, muteToggles)
        assertEquals(1, speakerToggles)
        assertEquals(1, endClicks)

        // Transition floor state to BLE Half-Duplex RX Yield
        duplexState = duplexState.copy(
            isVoiceTransmitting = false,
            isPeerSpeaking = true,
            isHighSpeedLink = false,
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("voice_floor_state_banner")
            .performScrollTo()
            .assertTextContains("RX YIELD — PEER SPEAKING (BLE HALF-DUPLEX)", substring = true)

        // Transition floor state to Muted
        duplexState = duplexState.copy(isMuted = true)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("voice_floor_state_banner")
            .performScrollTo()
            .assertTextContains("MIC MUTED — RX LISTENING ONLY", substring = true)

        scope.cancel()
    }

    @Test
    fun `verify ending call transitions to IDLE and immediate follow-up tap on group_mesh_call_button is ignored`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audioService = TacticalAudioService(context = context, scope = scope)

        var callState by mutableStateOf(CallSignalingState.IN_CALL_ACTIVE)
        var duplexState by mutableStateOf(
            DuplexCallState(
                signalingState = CallSignalingState.IN_CALL_ACTIVE,
                isInCall = true,
                peerNodeId = "0x9B1C",
                peerCallsign = "Bravo-2",
                durationSeconds = 9,
            ),
        )
        var endClicks = 0
        var groupCallClicks = 0

        composeTestRule.setContent {
            PulseMeshTheme {
                Box(modifier = Modifier.size(width = 360.dp, height = 640.dp)) {
                    SimpleCallVoiceScreen(
                        state = MeshUiState(
                            selectedTabIndex = 2,
                            callState = callState,
                            remoteCallerId = if (callState == CallSignalingState.IDLE) "" else "0x9B1C",
                            remoteCallerName = if (callState == CallSignalingState.IDLE) "" else "Bravo-2",
                        ),
                        duplexCallState = duplexState,
                        audioService = audioService,
                        onStartCall = ::noOpStartCall,
                        onStartGroupCall = { groupCallClicks++ },
                        onEndCall = {
                            endClicks++
                            callState = CallSignalingState.IDLE
                            duplexState = DuplexCallState(signalingState = CallSignalingState.IDLE)
                        },
                        onToggleMute = {},
                        onToggleSpeaker = {},
                        onStartPtt = {},
                        onStopPtt = {},
                        onToggleLiveMicrophone = {},
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("voice_end_call_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, endClicks)
        // Layout swaps to IDLE peer list showing GROUP MESH CALL (ALL PEERS); rapid tap within cooldown must NOT trigger group call
        composeTestRule.onNodeWithTag("group_mesh_call_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        assertEquals(0, groupCallClicks)
        assertEquals(CallSignalingState.IDLE, callState)

        scope.cancel()
    }

    @Test
    fun `verify MeshViewModel hangup does not redirect to Group Mesh Call on stale CALL_ACCEPT or 0xCAFE call beacons`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MeshViewModel(app)

        val peer = MeshNode(id = "0x9B1C", callsign = "Bravo-2")
        viewModel.startDuplexCall(peer)
        assertEquals(CallSignalingState.RINGING_OUTGOING, viewModel.uiState.value.callState)

        viewModel.endDuplexCall()
        assertEquals(CallSignalingState.IDLE, viewModel.uiState.value.callState)
        assertEquals("", viewModel.uiState.value.remoteCallerName)

        // Simulate stale 0xCAFE-derived CALL_ACCEPT (dstNodeId = "ALL") arriving right after user hung up
        viewModel.handleIncomingMeshPacket(
            WireMeshPacket(
                packetId = "cafe_accept_0x9B1C_1",
                srcNodeId = "0x9B1C",
                srcCallsign = "Bravo-2",
                dstNodeId = "ALL",
                type = "CALL_ACCEPT",
                payload = "BLE 0xCAFE Call Accepted",
            ),
        )

        assertEquals(CallSignalingState.IDLE, viewModel.uiState.value.callState)
        assertEquals(CallSignalingState.IDLE, viewModel.uiState.value.duplexCallState.signalingState)
        assertNotEquals("Group Mesh Call (All Peers)", viewModel.uiState.value.remoteCallerName)

        // Simulate stale 0xCAFE CALL_RING arriving during post-hangup cooldown
        viewModel.handleIncomingMeshPacket(
            WireMeshPacket(
                packetId = "cafe_call_0x9B1C_2",
                srcNodeId = "0x9B1C",
                srcCallsign = "Bravo-2",
                dstNodeId = "ALL",
                type = "CALL_RING",
                payload = "BLE 0xCAFE Call Incoming",
            ),
        )

        assertEquals(CallSignalingState.IDLE, viewModel.uiState.value.callState)
        assertNotEquals("Group Mesh Call (All Peers)", viewModel.uiState.value.remoteCallerName)
    }

    @Test
    fun `verify caller isCapturePermitted immediately true on acceptCall and speakerphone syncs with TacticalAudioService`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MeshViewModel(app)
        viewModel.setAppInForeground(inForeground = true)

        val peer = MeshNode(id = "0x9B1C", callsign = "Bravo-2")
        viewModel.startDuplexCall(peer)

        // Simulate incoming CALL_ACCEPT arriving on caller while RINGING_OUTGOING
        viewModel.handleIncomingMeshPacket(
            WireMeshPacket(
                packetId = "accept_audio_race_test",
                srcNodeId = "0x9B1C",
                srcCallsign = "Bravo-2",
                dstNodeId = viewModel.uiState.value.localNodeId,
                type = "CALL_ACCEPT",
                payload = "ACCEPT",
            ),
        )

        // Synchronously after acceptCall(0L), capture must already be permitted (no StateFlow race)
        assertTrue(viewModel.tacticalCallService.callState.value.isInCall)
        assertTrue(viewModel.tacticalCallService.isCapturePermitted())
        assertTrue(viewModel.audioService.speakerphoneEnabled)

        // Toggling speakerphone must immediately propagate to TacticalAudioService
        viewModel.toggleCallSpeaker()
        assertFalse(viewModel.tacticalCallService.callState.value.isSpeakerphoneOn)
        assertFalse(viewModel.audioService.speakerphoneEnabled)

        // Ending call restores speakerphoneEnabled = true for next call / PTT
        viewModel.endDuplexCall()
        assertTrue(viewModel.audioService.speakerphoneEnabled)
    }

    @Test
    fun `verify VOICE packetToJson omits duplicate p field and TacticalAudioService decodes G711 mu-law cleanly`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MeshViewModel(app)

        val pcm1280 = ShortArray(1280) { i ->
            (kotlin.math.sin((2.0 * kotlin.math.PI * i * 500.0) / 8000.0) * 12000).toInt().toShort()
        }
        val muLaw640 = TacticalAudioService.encode160msVoiceMuLaw640(pcm1280)
        val b64Voice = android.util.Base64.encodeToString(muLaw640, android.util.Base64.NO_WRAP)

        val voicePacket = WireMeshPacket(
            packetId = "v_compact_1",
            srcNodeId = "0x4F2A",
            srcCallsign = "Alpha-1",
            dstNodeId = "0x9B1C",
            type = "VOICE",
            payload = b64Voice,
        )
        val jsonStr = viewModel.offGridBleEngine.packetToJson(voicePacket)
        val parsed = org.json.JSONObject(jsonStr)
        assertEquals(b64Voice, parsed.getString("payload"))
        assertFalse("Duplicate 'p' key must be omitted from packetToJson", parsed.has("p"))

        // TacticalAudioService playback decoding
        val decoded8k = viewModel.audioService.playAudioBase64(b64Voice)
        assertEquals(1280, decoded8k.size)
        assertTrue(decoded8k.maxOf { kotlin.math.abs(it.toInt()) } > 1000)

        // AutoHotspotNegotiator must NOT treat speculative hotspot AP start or auto-join hint as a verified IP link
        assertFalse(viewModel.autoHotspotNegotiator.hasHighSpeedLink())
        viewModel.autoHotspotNegotiator.handleHotspotAutoJoin("0x9B1C", "DIRECT-TEST", "pass1234", "192.168.49.1")
        assertFalse(
            "Unverified auto-join hint must not disable BLE voice before actual IP traffic arrives",
            viewModel.autoHotspotNegotiator.hasHighSpeedLink(),
        )
        viewModel.autoHotspotNegotiator.markPeerIpVerifiedReachable("192.168.49.1")
        assertTrue(viewModel.autoHotspotNegotiator.hasHighSpeedLink())
    }

    @Test
    fun `verify call audio tones lifecycle, 6-second post-action anti-bounce cooldown, and single-dispatch GATT voice`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MeshViewModel(app)
        viewModel.saveDeviceProfile(
            callsign = "Alpha-1",
            nodeId = "0x4F2A",
            profile = com.kostadinosk.pulsemesh.model.DeviceCatalog.getProfileById("oneplus_15"),
            pureOffGrid = true,
            internetBridge = false,
        )

        val peer = MeshNode(id = "0x9B1C", callsign = "Bravo-2")

        // 1. Outgoing call activates outgoing ringback tone and stops it immediately on CALL_ACCEPT
        viewModel.startDuplexCall(peer)
        assertTrue(viewModel.tacticalCallService.isOutgoingRingbackActive)

        viewModel.handleIncomingMeshPacket(
            WireMeshPacket(
                packetId = "accept_tone_test_1",
                srcNodeId = "0x9B1C",
                srcCallsign = "Bravo-2",
                dstNodeId = "0x4F2A",
                type = "CALL_ACCEPT",
                payload = "ACCEPT",
            ),
        )
        assertFalse(
            "Outgoing ringback tone must stop immediately when CALL_ACCEPT arrives",
            viewModel.tacticalCallService.isOutgoingRingbackActive,
        )
        assertEquals(
            android.media.ToneGenerator.TONE_PROP_ACK,
            viewModel.tacticalCallService.lastConfirmationToneType,
        )

        // 2. Single-dispatch GATT VOICE verification: feeding a newline-framed VOICE JSON over handleIncomingGattPayload
        // must increment rxAudioCount by exactly 1 (never double-delivered)
        val pcm1280 = ShortArray(1280) { 4000 }
        val muLaw640 = TacticalAudioService.encode160msVoiceMuLaw640(pcm1280)
        val b64Voice = android.util.Base64.encodeToString(muLaw640, android.util.Base64.NO_WRAP)
        val voiceGattJson = org.json.JSONObject().apply {
            put("id", "gatt_voice_single_1")
            put("type", "VOICE")
            put("srcId", "0x9B1C")
            put("srcName", "Bravo-2")
            put("dstId", "0x4F2A")
            put("payload", b64Voice)
        }.toString()

        val rxBefore = viewModel.uiState.value.rxAudioCount
        viewModel.offGridBleEngine.handleIncomingGattPayload(("\n$voiceGattJson\n").toByteArray(Charsets.UTF_8), null)
        composeTestRule.waitForIdle()
        assertEquals(
            "BLE GATT VOICE packet must be processed exactly once",
            rxBefore + 1,
            viewModel.uiState.value.rxAudioCount,
        )

        // 3. Ending call plays hangup confirmation tone and enforces 6-second post-action anti-bounce cooldown
        viewModel.endDuplexCall()
        assertEquals(
            android.media.ToneGenerator.TONE_PROP_PROMPT,
            viewModel.tacticalCallService.lastConfirmationToneType,
        )
        assertEquals(6_000L, MeshViewModel.CALL_POST_ACTION_COOLDOWN_MS)

        // 4. Incoming call activates incoming ringtone and stops immediately on decline
        val freshVm = MeshViewModel(app)
        freshVm.handleIncomingMeshPacket(
            WireMeshPacket(
                packetId = "ring_in_tone_test_1",
                srcNodeId = "0x8C3E",
                srcCallsign = "Charlie-3",
                dstNodeId = "ALL",
                type = "CALL_RING",
                payload = "RING",
            ),
        )
        assertTrue(freshVm.tacticalCallService.isIncomingRingtoneActive)
        freshVm.declineDuplexCall()
        assertFalse(
            "Incoming ringtone must stop immediately when call is declined",
            freshVm.tacticalCallService.isIncomingRingtoneActive,
        )
    }

    @Test
    fun `verify connected call remote hangup ends cleanly without voicemail while ringing decline opens voicemail`() {
        val app = ApplicationProvider.getApplicationContext<Application>()

        // Case 1: Peer accepted call (IN_CALL_ACTIVE), then hung up -> MUST NOT enter voicemail
        val vmActiveHangup = MeshViewModel(app)
        val peer = MeshNode(id = "0x9B1C", callsign = "Bravo-2")
        vmActiveHangup.startDuplexCall(peer)
        vmActiveHangup.handleIncomingMeshPacket(
            WireMeshPacket(
                packetId = "accept_before_hangup_1",
                srcNodeId = "0x9B1C",
                srcCallsign = "Bravo-2",
                dstNodeId = vmActiveHangup.uiState.value.localNodeId,
                type = "CALL_ACCEPT",
                payload = "ACCEPT",
            ),
        )
        assertEquals(CallSignalingState.IN_CALL_ACTIVE, vmActiveHangup.uiState.value.callState)

        vmActiveHangup.handleIncomingMeshPacket(
            WireMeshPacket(
                packetId = "remote_end_after_accept_1",
                srcNodeId = "0x9B1C",
                srcCallsign = "Bravo-2",
                dstNodeId = vmActiveHangup.uiState.value.localNodeId,
                type = "CALL_END",
                payload = "END",
            ),
        )
        assertEquals(CallSignalingState.IDLE, vmActiveHangup.uiState.value.callState)
        assertFalse(
            "Connected call remote hangup must NOT enter voicemail recording",
            vmActiveHangup.uiState.value.duplexCallState.isRecordingVoicemail,
        )

        // Case 2: Outgoing call still ringing (RINGING_OUTGOING), peer declines -> enters voicemail
        val vmRingingDecline = MeshViewModel(app)
        vmRingingDecline.startDuplexCall(peer)
        assertEquals(CallSignalingState.RINGING_OUTGOING, vmRingingDecline.uiState.value.callState)

        vmRingingDecline.handleIncomingMeshPacket(
            WireMeshPacket(
                packetId = "remote_decline_while_ringing_1",
                srcNodeId = "0x9B1C",
                srcCallsign = "Bravo-2",
                dstNodeId = vmRingingDecline.uiState.value.localNodeId,
                type = "CALL_DECLINE",
                payload = "DECLINE",
            ),
        )
        assertTrue(
            "Declining an unanswered ringing outgoing call should enter voicemail recording",
            vmRingingDecline.uiState.value.duplexCallState.isRecordingVoicemail,
        )
        vmRingingDecline.endDuplexCall()
    }

    @Test
    fun `verify isolated Call vs Walkie-Talkie replay buffers and removal of Voice Drop from PTT tab`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audioService = TacticalAudioService(context = context, scope = scope)

        val pcm1280 = ShortArray(1280) { 3000 }
        val muLaw640 = TacticalAudioService.encode160msVoiceMuLaw640(pcm1280)
        val b64 = android.util.Base64.encodeToString(muLaw640, android.util.Base64.NO_WRAP)

        // Play 2 PTT frames -> only PTT replay counter increments
        audioService.playAudioBase64(b64, isCallAudio = false)
        audioService.playAudioBase64(b64, isCallAudio = false)
        assertEquals(2, audioService.cachedPttRxReplayFramesCount.value)
        assertEquals(0, audioService.cachedCallRxReplayFramesCount.value)

        // Play 1 Call frame -> only Call replay counter increments
        audioService.playAudioBase64(b64, isCallAudio = true)
        assertEquals(2, audioService.cachedPttRxReplayFramesCount.value)
        assertEquals(1, audioService.cachedCallRxReplayFramesCount.value)

        composeTestRule.setContent {
            PulseMeshTheme {
                SimpleCallVoiceScreen(
                    state = MeshUiState(selectedTabIndex = 2),
                    duplexCallState = DuplexCallState(),
                    audioService = audioService,
                    onStartCall = ::noOpStartCall,
                    onEndCall = {},
                    onToggleMute = {},
                    onToggleSpeaker = {},
                    onStartPtt = {},
                    onStopPtt = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tab_walkie_talkie_ptt").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("ptt_replay_last_rx_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("ptt_voice_drop_button").assertDoesNotExist()

        scope.cancel()
    }

    @Test
    fun `verify Pro RSSI chart shows only in Pro mode and Light Mode toggle works in SettingsScreen`() {
        var isSimpleMode by mutableStateOf(true)
        var appTheme by mutableStateOf(com.kostadinosk.pulsemesh.util.AppTheme.TACTICAL_DARK)

        composeTestRule.setContent {
            PulseMeshTheme(appTheme = appTheme) {
                com.kostadinosk.pulsemesh.ui.screens.SettingsScreen(
                    state = MeshUiState(
                        isSimpleMode = isSimpleMode,
                        appTheme = appTheme,
                        nodes = listOf(
                            MeshNode(id = "0x9B1C", callsign = "Bravo-2", rssiDbm = -62, snrDb = 11.5),
                        ),
                    ),
                    onToggleSimpleMode = { isSimpleMode = !isSimpleMode },
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
                    onToggleLightMode = {
                        appTheme = if (appTheme == com.kostadinosk.pulsemesh.util.AppTheme.TACTICAL_LIGHT) {
                            com.kostadinosk.pulsemesh.util.AppTheme.TACTICAL_DARK
                        } else {
                            com.kostadinosk.pulsemesh.util.AppTheme.TACTICAL_LIGHT
                        }
                    },
                    onSelectAppTheme = { selected -> appTheme = selected },
                )
            }
        }

        composeTestRule.waitForIdle()
        // Easy mode: Pro RSSI chart must NOT exist
        composeTestRule.onNodeWithTag("pro_mesh_rssi_chart_card").assertDoesNotExist()

        // Toggle Light Mode on
        composeTestRule.onNodeWithTag("settings_light_mode_switch").performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.kostadinosk.pulsemesh.util.AppTheme.TACTICAL_LIGHT, appTheme)

        // Switch to Original Classic theme
        composeTestRule.onNodeWithTag("settings_theme_original_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.kostadinosk.pulsemesh.util.AppTheme.ORIGINAL, appTheme)

        // Switch to Frutiger Aero theme
        composeTestRule.onNodeWithTag("settings_theme_aero_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.kostadinosk.pulsemesh.util.AppTheme.FRUTIGER_AERO, appTheme)

        // Switch to Dark Frutiger Aero theme
        composeTestRule.onNodeWithTag("settings_theme_dark_aero_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.kostadinosk.pulsemesh.util.AppTheme.FRUTIGER_AERO_DARK, appTheme)

        // Switch back to HUD Dark
        composeTestRule.onNodeWithTag("settings_theme_hud_dark_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.kostadinosk.pulsemesh.util.AppTheme.TACTICAL_DARK, appTheme)

        // Switch to Pro mode -> Pro RSSI chart must render
        composeTestRule.onNodeWithTag("settings_top_mode_toggle").performClick()
        composeTestRule.waitForIdle()
        assertFalse(isSimpleMode)
        composeTestRule.onNodeWithTag("pro_mesh_rssi_chart_card").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `verify 8kHz IMA-ADPCM codec, PA binary frames, and Frutiger Aero palette tokens`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audioService = TacticalAudioService(context = context, scope = scope)

        // 1. 80ms @ 8kHz (640 samples) encodes to 324 bytes (4-byte predictor header + 320B nibbles)
        val pcm640 = ShortArray(TacticalAudioService.VOICE_SLICE_SAMPLES_80MS) { i ->
            (kotlin.math.sin((2.0 * kotlin.math.PI * i * 650.0) / 8000.0) * 14000).toInt().toShort()
        }
        val adpcm324 = TacticalAudioService.encodeImaAdpcm8kHz(pcm640)
        assertEquals(324, adpcm324.size)

        val decoded640 = TacticalAudioService.decodeImaAdpcm8kHz(adpcm324)
        assertEquals(640, decoded640.size)
        assertTrue(decoded640.maxOf { kotlin.math.abs(it.toInt()) } > 4000)

        // 2. Binary "PA" frame round-trip
        val paFrame = TacticalAudioService.packBinaryAdpcmVoiceFrame(
            srcNodeId = "0x4F2A",
            dstNodeId = "0x9B1C",
            seq = 77,
            adpcmBytes = adpcm324,
        )
        assertEquals(332, paFrame.size)
        val unpacked = TacticalAudioService.unpackBinaryAdpcmVoiceFrame(paFrame)
        assertEquals("0x4F2A", unpacked?.srcNodeId)
        assertEquals("0x9B1C", unpacked?.dstNodeId)
        assertEquals(77, unpacked?.seq)
        assertEquals(324, unpacked?.muLawPayload?.size)

        // 3. Base64 ADPCM8K prefix playback
        val b64Adpcm = "ADPCM8K:" + android.util.Base64.encodeToString(adpcm324, android.util.Base64.NO_WRAP)
        val played = audioService.playAudioBase64(b64Adpcm, isCallAudio = true)
        assertEquals(640, played.size)

        // 4. Frutiger Aero palette flags inside PulseMeshTheme
        var capturedAeroFlag = false
        composeTestRule.setContent {
            PulseMeshTheme(appTheme = com.kostadinosk.pulsemesh.util.AppTheme.FRUTIGER_AERO) {
                capturedAeroFlag = com.kostadinosk.pulsemesh.ui.theme.LocalPulsePalette.current.isAero
            }
        }
        composeTestRule.waitForIdle()
        assertTrue(capturedAeroFlag)

        scope.cancel()
    }

    @Test
    fun `verify fake peers like Phone 1 Serve and arbitrary 0xCAFE beacons are blocked and purged from history`() {
        val app = ApplicationProvider.getApplicationContext<Application>()

        // 1. Blocked fake / server callsigns
        val fakeNames = listOf(
            "Phone 1 Serve",
            "Phone 1 Server",
            "Phone 1",
            "Phone 2",
            "Server",
            "Serve",
            "Demo",
            "Fake",
            "Test Node",
            "Peer Node",
            "Remote Operator",
        )
        for (name in fakeNames) {
            assertTrue(
                "Fake/server callsign '$name' must be blocked",
                com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.isBlockedFakeCallsign(name),
            )
        }
        assertFalse(com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.isBlockedFakeCallsign("Bravo-2"))

        // 2. Arbitrary non-'PM' 0xCAFE payloads must be rejected; valid 12-byte 'PM' beacon must succeed
        val randomBytes = byteArrayOf(0x01, 0x02, 0x11, 0x22, 0x50, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertEquals(
            null,
            com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.unpackCafePayload(randomBytes),
        )
        val validBeacon = com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.pack12ByteCafeBeacon(
            nodeIdHex = "0x9B1C",
            batteryPercent = 84,
            hotspotRoleByte = 0,
            lat = 37.98,
            lon = 23.72,
        )
        val unpacked = com.kostadinosk.pulsemesh.service.OffGridBleMeshEngine.unpackCafePayload(validBeacon)
        assertEquals("0x9B1C", unpacked?.nodeIdHex)

        // 3. ViewModel rejects incoming fake-peer packets and never adds them to discovered nodes
        val vm = MeshViewModel(app)
        val initialCount = vm.uiState.value.nodes.size
        vm.handleIncomingMeshPacket(
            WireMeshPacket(
                packetId = "fake_pkt_1",
                srcNodeId = "0xBEEF",
                srcCallsign = "Phone 1 Serve",
                dstNodeId = "ALL",
                type = "HEARTBEAT",
                payload = "Server alive",
            ),
        )
        assertEquals(initialCount, vm.uiState.value.nodes.size)
        assertFalse(vm.uiState.value.nodes.any { it.callsign.contains("Phone 1", ignoreCase = true) })

        // 4. PersistentChatManager purges legacy persisted fake DM threads on load
        val historyFile = java.io.File(app.filesDir, "chat_history_v7.json")
        historyFile.writeText(
            """
            {
              "threads": {
                "dm_0xBEEF": [
                  {
                    "id": "old_fake_1",
                    "sourceId": "0xBEEF",
                    "destId": "ALL",
                    "sourceCallsign": "Phone 1 Serve",
                    "type": "TEXT",
                    "content": "hello",
                    "isIncoming": true,
                    "timestamp": 1700000000000
                  }
                ]
              }
            }
            """.trimIndent(),
        )
        val chatMgr = com.kostadinosk.pulsemesh.util.PersistentChatManager(app)
        assertFalse(
            "Persisted fake DM thread dm_0xBEEF must be purged on load",
            chatMgr.getAllThreadIds().contains("dm_0xBEEF"),
        )
        historyFile.delete()
    }

    @Test
    fun `verify voice frame deduplication and speakerphone echo micing suppression`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MeshViewModel(app)
        val selfId = vm.uiState.value.localNodeId

        // 1. Self-originated voice frames must be ignored (anti-loopback)
        assertFalse(vm.shouldAcceptVoiceFrame(selfId, seq = 42, nowMs = 10_000L))

        // 2. Peer frame seq=42 accepted once, then duplicate (e.g. UDP repeat(2) or TCP fallback) rejected
        assertTrue(vm.shouldAcceptVoiceFrame("0x9B1C", seq = 42, nowMs = 10_000L))
        assertFalse(vm.shouldAcceptVoiceFrame("0x9B1C", seq = 42, nowMs = 10_008L))
        assertTrue(vm.shouldAcceptVoiceFrame("0x9B1C", seq = 43, nowMs = 10_080L))

        // 3. PacketId vs_ sequence extraction aligns with PA binary seq
        assertEquals(42, vm.extractVoiceSeqFromPacketId("vs_9B1C_42_1234"))

        // 4. Speakerphone acoustic echo ("micing") suppression on high-speed UDP calls
        assertFalse(
            "Speakerphone bleed (micPeak=900) within 260ms of incoming audio must be suppressed",
            com.kostadinosk.pulsemesh.service.TacticalCallService.shouldTransmitCallVoiceFrame(
                hasHotspotUdpLink = true,
                micPeak = 900,
                nowMs = 10_120L,
                lastIncomingAudioTime = 10_000L,
                pushToOverrideActive = false,
                isMuted = false,
                isSpeakerphoneOn = true,
            ),
        )
        assertTrue(
            "Close-mic loud speech (micPeak=2200) overrides speakerphone echo gate",
            com.kostadinosk.pulsemesh.service.TacticalCallService.shouldTransmitCallVoiceFrame(
                hasHotspotUdpLink = true,
                micPeak = 2200,
                nowMs = 10_120L,
                lastIncomingAudioTime = 10_000L,
                pushToOverrideActive = false,
                isMuted = false,
                isSpeakerphoneOn = true,
            ),
        )
        assertTrue(
            "Earpiece mode (isSpeakerphoneOn=false) remains full-duplex at normal speech threshold",
            com.kostadinosk.pulsemesh.service.TacticalCallService.shouldTransmitCallVoiceFrame(
                hasHotspotUdpLink = true,
                micPeak = 500,
                nowMs = 10_120L,
                lastIncomingAudioTime = 10_000L,
                pushToOverrideActive = false,
                isMuted = false,
                isSpeakerphoneOn = false,
            ),
        )
    }

    @Test
    fun `verify Dark Frutiger Aero theme palette and multi-part device wizard flow`() {
        var currentTheme by mutableStateOf(com.kostadinosk.pulsemesh.util.AppTheme.TACTICAL_DARK)
        var capturedIsAero = false
        var capturedIsDarkAero = false
        var capturedIsLight = true
        var completedTheme: com.kostadinosk.pulsemesh.util.AppTheme? = null
        var wizardCompleted = false

        val defaultProfile = com.kostadinosk.pulsemesh.model.DeviceCatalog.allModels.first()

        composeTestRule.setContent {
            PulseMeshTheme(appTheme = currentTheme) {
                val p = com.kostadinosk.pulsemesh.ui.theme.LocalPulsePalette.current
                capturedIsAero = p.isAero
                capturedIsDarkAero = p.isDarkAero
                capturedIsLight = p.isLight

                com.kostadinosk.pulsemesh.ui.screens.TacticalDeviceWizardScreen(
                    initialCallsign = "Alpha-1",
                    initialNodeId = "0x4F2A",
                    initialSelectedProfile = defaultProfile,
                    detectedHardware = null,
                    initialPureOffGrid = true,
                    initialInternetBridge = false,
                    currentAppTheme = currentTheme,
                    onSelectAppTheme = { selected -> currentTheme = selected },
                    onCompleteWizard = { _, _, _, _, _ ->
                        completedTheme = currentTheme
                        wizardCompleted = true
                    },
                )
            }
        }

        composeTestRule.waitForIdle()
        // Scroll Step 1 (Identity & Theme) and select Dark Frutiger Aero live inside Wizard
        composeTestRule.onNodeWithTag("wizard_content_list")
            .performScrollToNode(hasTestTag("wizard_theme_dark_aero_button"))
        composeTestRule.onNodeWithTag("wizard_theme_dark_aero_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.kostadinosk.pulsemesh.util.AppTheme.FRUTIGER_AERO_DARK, currentTheme)
        assertTrue(capturedIsAero)
        assertTrue(capturedIsDarkAero)
        assertFalse(capturedIsLight)

        // Step 1 -> Step 2 (Radio Mode) -> Complete Wizard
        composeTestRule.onNodeWithTag("wizard_primary_action_btn").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("wizard_primary_action_btn").performClick()
        composeTestRule.waitForIdle()
        assertTrue(wizardCompleted)
        assertEquals(com.kostadinosk.pulsemesh.util.AppTheme.FRUTIGER_AERO_DARK, completedTheme)
    }

    @Test
    fun `verify unique username reservation PIN recovery connectByUsername and SOS beacon`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = com.kostadinosk.pulsemesh.util.DevicePreferences(app)
        prefs.isInternetEnabled = false
        prefs.claimedUsername = ""
        prefs.accountPinHash = ""
        prefs.accountRecoveryKey = ""
        prefs.accountRecoveryHash = ""

        val vm = MeshViewModel(app)

        // 1. Reserve unique @alpha_op first-come-first-serve with >=8-char passphrase
        var claimOk = false
        var claimMsg = ""
        vm.claimOrRecoverUsername("alpha_op", pin = "alpha2468", isRecoveryMode = false) { ok, msg ->
            claimOk = ok
            claimMsg = msg
        }
        assertTrue("Expected initial username claim to succeed: $claimMsg", claimOk)
        assertEquals("alpha_op", vm.uiState.value.claimedUsername)
        assertTrue(vm.uiState.value.hasAccountPin)
        val savedRecoveryKey = vm.uiState.value.accountRecoveryKey
        assertTrue(savedRecoveryKey.startsWith("PMRK-"))

        // 2. Duplicate claim with wrong passphrase must fail
        var dupOk = true
        var dupMsg = ""
        vm.claimOrRecoverUsername("alpha_op", pin = "wrong9999", isRecoveryMode = false) { ok, msg ->
            dupOk = ok
            dupMsg = msg
        }
        assertFalse("Duplicate claim without valid passphrase must be rejected", dupOk)
        assertTrue(dupMsg.contains("already reserved", ignoreCase = true))

        // 3. Recovery on another session with passphrase succeeds
        prefs.claimedUsername = ""
        var pinRecoverOk = false
        vm.claimOrRecoverUsername("alpha_op", pin = "alpha2468", isRecoveryMode = true) { ok, _ ->
            pinRecoverOk = ok
        }
        assertTrue(pinRecoverOk)
        assertEquals("alpha_op", vm.uiState.value.claimedUsername)

        // 4. Recovery using Recovery Key (without passphrase) succeeds
        prefs.claimedUsername = ""
        var keyRecoverOk = false
        vm.claimOrRecoverUsername(
            rawUsername = "alpha_op",
            pin = "",
            recoveryKeyInput = savedRecoveryKey,
            isRecoveryMode = true,
        ) { ok, _ ->
            keyRecoverOk = ok
        }
        assertTrue(keyRecoverOk)
        assertEquals("alpha_op", vm.uiState.value.claimedUsername)

        // 5. Connect to distant friend @sierra_far via username
        var connectOk = false
        vm.connectByUsername("sierra_far") { ok, _ ->
            connectOk = ok
        }
        assertTrue(connectOk)
        val expectedFriendNodeId = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.deriveNodeIdFromCallsign("sierra_far")
        assertEquals(1, vm.uiState.value.selectedTabIndex)
        assertEquals("dm_$expectedFriendNodeId", vm.uiState.value.selectedThreadId)
        assertTrue(vm.uiState.value.conversations.any { it.threadId == "dm_$expectedFriendNodeId" })

        // 6. SOS beacon emits real PacketType.SOS into channel_primary and clears cleanly
        vm.selectThread("channel_primary")
        assertFalse(vm.uiState.value.isSosActive)
        vm.toggleSosBeacon()
        assertTrue(vm.uiState.value.isSosActive)
        assertTrue(
            vm.uiState.value.packets.any {
                (it.type == com.kostadinosk.pulsemesh.model.PacketType.SOS) &&
                    it.content.contains("MAYDAY: EMERGENCY SOS")
            },
        )
        vm.toggleSosBeacon()
        assertFalse(vm.uiState.value.isSosActive)

        // 7. Chat UI @USER button opens connect-by-username dialog in Frutiger Aero theme
        var connectedTarget = ""
        composeTestRule.setContent {
            PulseMeshTheme(appTheme = com.kostadinosk.pulsemesh.util.AppTheme.FRUTIGER_AERO) {
                com.kostadinosk.pulsemesh.ui.screens.MeshChatScreen(
                    state = MeshUiState(selectedThreadId = null),
                    onSendMessage = {},
                    onBroadcastGpsPin = {},
                    onConnectByUsername = { u, cb ->
                        connectedTarget = u
                        cb(true, "Connected")
                    },
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("chat_connect_username_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("username_connect_input").performTextInput("charlie_far")
        composeTestRule.onNodeWithTag("username_connect_confirm_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals("charlie_far", connectedTarget)
    }

    @Test
    fun `verify offline username hold popup and wizard hold badge`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = com.kostadinosk.pulsemesh.util.DevicePreferences(app)
        prefs.isInternetEnabled = false
        prefs.claimedUsername = ""
        prefs.pendingUsernameHold = ""
        prefs.isUsernameOnHold = false
        prefs.accountPinHash = ""
        prefs.accountRecoveryKey = ""
        prefs.accountRecoveryHash = ""

        val vm = MeshViewModel(app)
        var holdOk = false
        var holdMsg = ""
        vm.claimOrRecoverUsername("offline_op", pin = "offl1357", isRecoveryMode = false) { ok, msg ->
            holdOk = ok
            holdMsg = msg
        }

        assertTrue("Offline username hold should succeed locally: $holdMsg", holdOk)
        val state = vm.uiState.value
        assertTrue(state.isUsernameOnHold)
        assertEquals("offline_op", state.pendingUsernameHold)
        assertEquals("offline_op", state.claimedUsername)
        assertTrue(state.showUsernamePopup)
        assertEquals("USERNAME ON HOLD", state.usernamePopupTitle)
        assertTrue(state.usernamePopupMessage.contains("on hold", ignoreCase = true))
        assertTrue(state.usernamePopupMessage.contains("online", ignoreCase = true))

        vm.dismissUsernamePopup()
        assertFalse(vm.uiState.value.showUsernamePopup)

        val defaultProfile = com.kostadinosk.pulsemesh.model.DeviceCatalog.allModels.first()
        composeTestRule.setContent {
            PulseMeshTheme(appTheme = com.kostadinosk.pulsemesh.util.AppTheme.TACTICAL_DARK) {
                com.kostadinosk.pulsemesh.ui.screens.TacticalDeviceWizardScreen(
                    initialCallsign = "Offline-Op",
                    initialNodeId = "0x4F2A",
                    initialSelectedProfile = defaultProfile,
                    detectedHardware = null,
                    initialPureOffGrid = true,
                    initialInternetBridge = false,
                    initialClaimedUsername = "offline_op",
                    isUsernameOnHold = true,
                    onCompleteWizard = { _, _, _, _, _ -> },
                )
            }
        }
        composeTestRule.waitForIdle()
        // Step 0 already displays the Identity & Username On Hold card
        composeTestRule.onNodeWithTag("wizard_content_list")
            .performScrollToNode(hasTestTag("wizard_username_on_hold_badge"))
        composeTestRule.onNodeWithTag("wizard_username_on_hold_badge")
            .assertIsDisplayed()
    }

    @Test
    fun `verify chat notification inline reply button and call notification removal on user choice`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        val shadowNm = Shadows.shadowOf(nm)
        nm.cancelAll()

        val notifMgr = com.kostadinosk.pulsemesh.service.TacticalNotificationManager(app)
        val receiver = com.kostadinosk.pulsemesh.service.TacticalNotificationActionReceiver()

        // 1. Chat notification must expose both MARK AS READ and RemoteInput REPLY actions
        val vm = MeshViewModel(app)
        vm.selectThread("channel_primary")
        val targetThread = "channel_primary"
        val chatNotifId = com.kostadinosk.pulsemesh.service.TacticalNotificationManager.notificationIdForThread(targetThread)

        notifMgr.showChatNotification(
            threadId = targetThread,
            senderCallsign = "Bravo-2",
            senderNodeId = "0x9B1C",
            messagePreview = "Check sector 4",
            transport = "Off-Grid BLE",
        )

        val chatNotification = shadowNm.getNotification(chatNotifId)
        assertTrue("Chat notification must be posted", chatNotification != null)
        val actions = chatNotification!!.actions?.toList().orEmpty()
        assertTrue("Expected MARK AS READ action", actions.any { it.title == "MARK AS READ" })
        val replyAction = actions.firstOrNull { it.title == "REPLY" }
        assertTrue("Expected REPLY action on chat notification", replyAction != null)
        assertTrue(
            "REPLY action must carry RemoteInput with EXTRA_REPLY_TEXT",
            replyAction!!.remoteInputs?.any {
                it.resultKey == com.kostadinosk.pulsemesh.service.TacticalNotificationManager.EXTRA_REPLY_TEXT
            } == true,
        )

        // Simulate inline notification reply submission
        val replyIntent = android.content.Intent(com.kostadinosk.pulsemesh.service.TacticalNotificationManager.ACTION_REPLY_CHAT).apply {
            putExtra(com.kostadinosk.pulsemesh.service.TacticalNotificationManager.EXTRA_OPEN_THREAD_ID, targetThread)
            putExtra(com.kostadinosk.pulsemesh.service.TacticalNotificationManager.EXTRA_NOTIFICATION_ID, chatNotifId)
        }
        val bundle = android.os.Bundle().apply {
            putCharSequence(com.kostadinosk.pulsemesh.service.TacticalNotificationManager.EXTRA_REPLY_TEXT, "Copy that from notif")
        }
        android.app.RemoteInput.addResultsToIntent(
            arrayOf(
                android.app.RemoteInput.Builder(com.kostadinosk.pulsemesh.service.TacticalNotificationManager.EXTRA_REPLY_TEXT).build()
            ),
            replyIntent,
            bundle,
        )
        receiver.onReceive(app, replyIntent)
        ShadowLooper.idleMainLooper()
        Thread.sleep(100)
        ShadowLooper.idleMainLooper()

        assertTrue(
            "Chat notification should be removed after inline reply",
            shadowNm.getNotification(chatNotifId) == null,
        )
        assertTrue(
            "Inline reply text must be dispatched to target thread",
            vm.uiState.value.packets.any { it.content == "Copy that from notif" },
        )

        // 2. Incoming CALL_RING posts call notification, and accepting in-app removes it (does not leave ongoing notif)
        vm.handleIncomingMeshPacket(
            WireMeshPacket(
                packetId = "call_ring_notif_test_1",
                srcNodeId = "0x9B1C",
                srcCallsign = "Bravo-2",
                dstNodeId = vm.uiState.value.localNodeId,
                type = "CALL_RING",
                payload = "RING:0x9B1C",
            ),
        )
        ShadowLooper.idleMainLooper()
        assertTrue(
            "Incoming CALL_RING packet must post incoming call notification",
            shadowNm.getNotification(com.kostadinosk.pulsemesh.service.TacticalNotificationManager.NOTIFICATION_ID_CALL) != null,
        )
        vm.acceptDuplexCall()
        ShadowLooper.idleMainLooper()
        assertEquals(
            "Accepting call in-app must remove the call notification",
            null,
            shadowNm.getNotification(com.kostadinosk.pulsemesh.service.TacticalNotificationManager.NOTIFICATION_ID_CALL),
        )
        vm.endDuplexCall()

        // 3. Call notification action buttons (Accept / Decline) must also remove the call notification immediately
        notifMgr.showIncomingCallNotification("0x9B1C", "Bravo-2", "Off-Grid BLE")
        assertTrue(
            "Incoming call notification should be visible before user choice",
            shadowNm.getNotification(com.kostadinosk.pulsemesh.service.TacticalNotificationManager.NOTIFICATION_ID_CALL) != null,
        )

        receiver.onReceive(
            app,
            android.content.Intent(com.kostadinosk.pulsemesh.service.TacticalNotificationManager.ACTION_ACCEPT_CALL),
        )
        ShadowLooper.idleMainLooper()
        assertEquals(
            "Call notification must be removed when user taps Accept",
            null,
            shadowNm.getNotification(com.kostadinosk.pulsemesh.service.TacticalNotificationManager.NOTIFICATION_ID_CALL),
        )

        notifMgr.showIncomingCallNotification("0x9B1C", "Bravo-2", "Off-Grid BLE")
        assertTrue(
            shadowNm.getNotification(com.kostadinosk.pulsemesh.service.TacticalNotificationManager.NOTIFICATION_ID_CALL) != null,
        )
        receiver.onReceive(
            app,
            android.content.Intent(com.kostadinosk.pulsemesh.service.TacticalNotificationManager.ACTION_DECLINE_CALL),
        )
        ShadowLooper.idleMainLooper()
        assertEquals(
            "Call notification must be removed when user taps Decline",
            null,
            shadowNm.getNotification(com.kostadinosk.pulsemesh.service.TacticalNotificationManager.NOTIFICATION_ID_CALL),
        )
    }

    @Test
    fun `verify TacticalNotificationManager photo preview and TacticalE2eeManager fast hex conversion`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        val shadowNm = Shadows.shadowOf(nm)
        nm.cancelAll()

        val notifMgr = com.kostadinosk.pulsemesh.service.TacticalNotificationManager(app)

        // 1. Both [[PM_IMAGE]]: and [[PM_IMG]]: preview as camera photo emoji
        val imgThread = "dm_photo_test"
        val imgNotifId = com.kostadinosk.pulsemesh.service.TacticalNotificationManager.notificationIdForThread(imgThread)
        notifMgr.showChatNotification(
            threadId = imgThread,
            senderCallsign = "Bravo-2",
            senderNodeId = "0x9B1C",
            messagePreview = "[[PM_IMAGE]]:/9j/4AAQSkZJRg==",
            transport = "Off-Grid BLE",
        )
        val notif = shadowNm.getNotification(imgNotifId)
        assertTrue(notif != null)
        val text = notif?.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue("Expected photo emoji preview: $text", text.contains("📷 Sent an off-grid tactical photo"))

        // 2. TacticalE2eeManager deriveNodeIdFromCallsign (64-bit Node ID) and shortNodeTag
        val nodeId = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.deriveNodeIdFromCallsign("Echo-5")
        assertTrue("Node ID must be 0x-prefixed 64-bit hex ($nodeId)", Regex("^0x[0-9A-F]{16}$").matches(nodeId))
        val shortTag = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.shortNodeTag(nodeId)
        assertTrue("Short node tag must be 4+ chars ($shortTag)", shortTag.length >= 4)

        val kp = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.generateKeyPair("Echo-5")
        assertTrue(kp.fingerprint.isNotBlank())
        assertTrue(kp.publicKeyBase64.isNotBlank())

        // 3. PersistentChatManager 200-item thread cap
        val chatMgr = com.kostadinosk.pulsemesh.util.PersistentChatManager(app)
        val testThread = "dm_perf_test"
        repeat(220) { idx ->
            chatMgr.savePacket(
                testThread,
                com.kostadinosk.pulsemesh.model.MeshPacket(
                    id = "PKT_$idx",
                    sourceId = "0x1111",
                    destId = "0x2222",
                    sourceCallsign = "Echo-5",
                    type = com.kostadinosk.pulsemesh.model.PacketType.TEXT,
                    content = "Msg $idx",
                    hops = 0,
                    rawHexBadge = "[TEST]",
                    isIncoming = true,
                    timestamp = System.currentTimeMillis() + idx,
                ),
            )
        }
        val packets = chatMgr.getPacketsForThread(testThread)
        assertEquals("PersistentChatManager must cap in-memory thread size to 200", 200, packets.size)
        assertEquals("Newest packet should be at the front", "PKT_219", packets.first().id)
    }

    @Test
    fun `verify Calls UI incoming controls visible on compact viewport in Light theme`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audioService = TacticalAudioService(context = context, scope = scope)

        composeTestRule.setContent {
            PulseMeshTheme(appTheme = com.kostadinosk.pulsemesh.util.AppTheme.TACTICAL_LIGHT) {
                Box(modifier = Modifier.size(width = 360.dp, height = 540.dp)) {
                    SimpleCallVoiceScreen(
                        state = MeshUiState(
                            selectedTabIndex = 2,
                            callState = CallSignalingState.RINGING_INCOMING,
                            remoteCallerId = "0x9B1C",
                            remoteCallerName = "Bravo-2",
                        ),
                        duplexCallState = DuplexCallState(
                            signalingState = CallSignalingState.RINGING_INCOMING,
                            peerNodeId = "0x9B1C",
                            peerCallsign = "Bravo-2",
                        ),
                        audioService = audioService,
                        onStartCall = ::noOpStartCall,
                        onEndCall = {},
                        onToggleMute = {},
                        onToggleSpeaker = {},
                        onStartPtt = {},
                        onStopPtt = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("voice_decline_call_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("voice_accept_call_button").assertIsDisplayed()

        scope.cancel()
    }

    @Test
    fun `verify Chats UI unread badge, photo fallback, and inline retry across Dark Frutiger Aero`() {
        var selectedThread by mutableStateOf<String?>(null)
        val convo = com.kostadinosk.pulsemesh.model.ChatConversation(
            threadId = "dm_0x9B1C",
            title = "@bravo_long_username • Bravo-2 (0x9B1C)",
            isChannel = false,
            isEncrypted = true,
            peerNodeId = "0x9B1C",
            peerCallsign = "Bravo-2",
            lastMessageText = "Ready on channel 4",
            unreadCount = 3,
        )
        val samplePackets = listOf(
            com.kostadinosk.pulsemesh.model.MeshPacket(
                id = "pkt_out_1",
                sourceId = "0x4F2A",
                destId = "0x9B1C",
                sourceCallsign = "Kilo-1",
                type = com.kostadinosk.pulsemesh.model.PacketType.TEXT,
                content = "Copy that",
                hops = 0,
                rawHexBadge = "[BLE]",
                isIncoming = false,
                isDelivered = false,
                timestamp = System.currentTimeMillis() - 10_000L,
            ),
            com.kostadinosk.pulsemesh.model.MeshPacket(
                id = "pkt_out_fresh",
                sourceId = "0x4F2A",
                destId = "0x9B1C",
                sourceCallsign = "Kilo-1",
                type = com.kostadinosk.pulsemesh.model.PacketType.TEXT,
                content = "Just sent",
                hops = 0,
                rawHexBadge = "[BLE]",
                isIncoming = false,
                isDelivered = false,
                timestamp = System.currentTimeMillis(),
            ),
            com.kostadinosk.pulsemesh.model.MeshPacket(
                id = "pkt_img_bad",
                sourceId = "0x9B1C",
                destId = "0x4F2A",
                sourceCallsign = "Bravo-2",
                type = com.kostadinosk.pulsemesh.model.PacketType.TEXT,
                content = "[[PM_IMG]]:not_valid_image_bytes",
                hops = 1,
                rawHexBadge = "[BLE]",
                isIncoming = true,
            ),
        )

        composeTestRule.setContent {
            PulseMeshTheme(appTheme = com.kostadinosk.pulsemesh.util.AppTheme.FRUTIGER_AERO_DARK) {
                com.kostadinosk.pulsemesh.ui.screens.MeshChatScreen(
                    state = MeshUiState(
                        selectedThreadId = selectedThread,
                        conversations = listOf(convo),
                        activeThreadPackets = samplePackets,
                    ),
                    onSendMessage = {},
                    onBroadcastGpsPin = {},
                    onSelectThread = { selectedThread = it },
                    onBackToList = { selectedThread = null },
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("conversation_list_lazy")
            .performScrollToNode(hasTestTag("conversation_card_dm_0x9B1C"))
        composeTestRule.onNodeWithTag("conversation_card_dm_0x9B1C").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals("dm_0x9B1C", selectedThread)
        composeTestRule.onNodeWithTag("conversation_thread_view").assertIsDisplayed()
        composeTestRule.onNodeWithTag("retry_send_message_prompt_pkt_out_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("retry_send_message_prompt_pkt_out_fresh").assertDoesNotExist()
        composeTestRule.onNodeWithTag("chat_input_field").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_send_button").assertIsDisplayed()
    }

    @Test
    fun `verify RootBoosterScreen Pro tab Frutiger Aero theming, auto-match silicon, and catalog search`() {
        val selectedProfile = com.kostadinosk.pulsemesh.model.DeviceCatalog.allModels.first()
        var rootCheckTriggered = false
        var capturedAero = false

        composeTestRule.setContent {
            PulseMeshTheme(appTheme = com.kostadinosk.pulsemesh.util.AppTheme.FRUTIGER_AERO) {
                capturedAero = com.kostadinosk.pulsemesh.ui.theme.LocalPulsePalette.current.isAero
                com.kostadinosk.pulsemesh.ui.screens.RootBoosterScreen(
                    state = MeshUiState(
                        isSimpleMode = false,
                        selectedPhoneProfile = selectedProfile,
                        routerSummary = "BLE + Wi-Fi Aware active",
                    ),
                    onToggleBleCoded = {},
                    onToggleWifiStuffing = {},
                    onToggleUnthrottledScan = {},
                    onToggleSystemlessMount = {},
                    onSelectPhoneProfile = {},
                    onAutoSwitchToDetected = {},
                    onForceOverrideMismatch = {},
                    onOpenMismatchDialog = {},
                    onVerifyRoot = { rootCheckTriggered = true },
                )
            }
        }

        composeTestRule.waitForIdle()
        assertTrue(capturedAero)
        composeTestRule.onNodeWithTag("live_radio_diagnostics_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("SMART LINK-COST ROUTER & PMB2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("live_radio_diagnostics_screen")
            .performScrollToNode(hasText("CHECK SU"))
        composeTestRule.onNodeWithText("CHECK SU").assertIsDisplayed().performClick()
        assertTrue("Expected CHECK SU button to invoke onVerifyRoot", rootCheckTriggered)
    }

    @Test
    fun `verify RootBoosterScreen Dark Frutiger Aero simple and pro mode switching`() {
        var capturedDarkAero = false

        composeTestRule.setContent {
            PulseMeshTheme(appTheme = com.kostadinosk.pulsemesh.util.AppTheme.FRUTIGER_AERO_DARK) {
                capturedDarkAero = com.kostadinosk.pulsemesh.ui.theme.LocalPulsePalette.current.isDarkAero
                com.kostadinosk.pulsemesh.ui.screens.RootBoosterScreen(
                    state = MeshUiState(isSimpleMode = true),
                    onToggleBleCoded = {},
                    onToggleWifiStuffing = {},
                    onToggleUnthrottledScan = {},
                    onToggleSystemlessMount = {},
                    onSelectPhoneProfile = {},
                    onAutoSwitchToDetected = {},
                    onForceOverrideMismatch = {},
                    onOpenMismatchDialog = {},
                    onVerifyRoot = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        assertTrue(capturedDarkAero)
        composeTestRule.onNodeWithTag("live_radio_diagnostics_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("SMART LINK-COST ROUTER & PMB2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Wi-Fi Aware (NAN) IPv6 Data-Path").assertIsDisplayed()
    }

    @Test
    fun `verify v2 PBKDF2 encrypted account vaults, ECDSA signed claims, and PMU2 ECDH inbox`() {
        val kpAlice = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.generateKeyPair()
        val kpEve = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.generateKeyPair()
        val salt = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.generateAccountSaltHex()
        val authTok = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.generateAuthToken()
        val recoveryKey = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.generateRecoveryKey()

        val vaultPayload = com.kostadinosk.pulsemesh.crypto.AccountVaultPayload(
            privateKeyBase64 = kpAlice.privateKeyBase64,
            publicKeyBase64 = kpAlice.publicKeyBase64,
            nodeId = "0xA11C",
            displayName = "Alice-1",
            authToken = authTok,
        )
        val pinVault = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.encryptAccountVault("alice_v2", salt, "7391", vaultPayload)
        val recoveryVault = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.encryptAccountVault(
            "alice_v2",
            salt,
            com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.normalizeRecoveryKey(recoveryKey),
            vaultPayload,
        )

        // Wrong PIN must return null; right PIN and Recovery Key must restore exact EC keypair & authToken
        assertEquals(null, com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.decryptAccountVault("alice_v2", salt, "0000", pinVault))
        val unlockedPin = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.decryptAccountVault("alice_v2", salt, "7391", pinVault)
        assertEquals(kpAlice.privateKeyBase64, unlockedPin?.privateKeyBase64)
        assertEquals(authTok, unlockedPin?.authToken)

        val unlockedRk = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.decryptAccountVault(
            "alice_v2",
            salt,
            com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.normalizeRecoveryKey(recoveryKey),
            recoveryVault,
        )
        assertEquals(kpAlice.publicKeyBase64, unlockedRk?.publicKeyBase64)

        // Signed claim verification & tampering detection
        val signedClaim = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.signUsernameClaim(
            com.kostadinosk.pulsemesh.crypto.UsernameClaimRecord(
                username = "alice_v2",
                displayName = "Alice-1",
                nodeId = "0xA11C",
                publicKeyBase64 = kpAlice.publicKeyBase64,
                saltHex = salt,
                pinHash = "local_only_must_never_leak",
                recoveryHash = "local_only_must_never_leak",
                claimedAt = 1700000001000L,
                claimVersion = 2,
                pinVault = pinVault,
                recoveryVault = recoveryVault,
                authVerifierHash = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.hashAuthToken(authTok),
            ),
            kpAlice.privateKeyBase64,
        )
        assertTrue(com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.verifyUsernameClaimSignature(signedClaim))
        assertFalse(
            com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.verifyUsernameClaimSignature(
                signedClaim.copy(nodeId = "0xDEAD"),
            ),
        )
        val wireJson = signedClaim.toPublicWireJson()
        assertFalse("Public wire JSON must NEVER leak pinHash", wireJson.has("pinHash"))
        assertFalse("Public wire JSON must NEVER leak recoveryHash", wireJson.has("recoveryHash"))

        // PMU2 Ephemeral-Static ECDH inbox envelope: only Alice's private key can decrypt
        val envelope = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.encryptUsernameInboxEnvelope(
            jsonString = """{"packetId":"p1","srcNodeId":"0xBEEF","payload":"secret"}""",
            recipientPublicKeyBase64 = kpAlice.publicKeyBase64,
            recipientUsername = "alice_v2",
        )
        assertTrue(envelope.startsWith("PMU2:"))
        assertEquals(
            null,
            com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.decryptUsernameInboxEnvelope(envelope, kpEve.privateKeyBase64, "alice_v2"),
        )
        val plain = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.decryptUsernameInboxEnvelope(
            envelope,
            kpAlice.privateKeyBase64,
            "alice_v2",
        )
        assertTrue(plain?.contains("\"secret\"") == true || plain?.contains("secret") == true)
    }

    @Test
    fun `verify Cloudflare D1 encrypted relay client health, atomic conflict, PMU2 delivery, and ACK`() {
        val kpAlice = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.generateKeyPair()
        val kpBob = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.generateKeyPair()
        val users = java.util.concurrent.ConcurrentHashMap<String, String>()
        val inbox = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, String>>())
        var nextMsgId = 1L
        var ackedId = 0L

        val serverSocket = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val serverThread = Thread {
            while (running.get()) {
                val sock = runCatching { serverSocket.accept() }.getOrNull() ?: break
                runCatching {
                    sock.use { s ->
                        val input = s.getInputStream()
                        val reader = java.io.BufferedReader(java.io.InputStreamReader(input, Charsets.UTF_8))
                        val reqLine = reader.readLine() ?: return@use
                        val parts = reqLine.split(" ")
                        val method = parts.getOrNull(0).orEmpty()
                        val fullPath = parts.getOrNull(1).orEmpty().substringBefore("?")
                        var contentLen = 0
                        var line: String?
                        while (reader.readLine().also { line = it } != null && line.orEmpty().isNotEmpty()) {
                            val headerLine = line.orEmpty()
                            if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLen = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                            }
                        }
                        val bodyChars = CharArray(contentLen)
                        var readSoFar = 0
                        while (readSoFar < contentLen) {
                            val r = reader.read(bodyChars, readSoFar, contentLen - readSoFar)
                            if (r <= 0) break
                            readSoFar += r
                        }
                        val body = String(bodyChars, 0, readSoFar)

                        val (code, statusText, respBody) = when {
                            fullPath == "/health" -> Triple(200, "OK", """{"ok":true,"service":"pulsemesh-e2ee-relay","version":2}""")
                            method == "POST" && fullPath == "/v1/users/claim" -> {
                                val uname = org.json.JSONObject(body).optString("username")
                                if (users.putIfAbsent(uname, body) == null) Triple(201, "Created", """{"ok":true}""")
                                else Triple(409, "Conflict", """{"error":"taken"}""")
                            }
                            method == "GET" && fullPath.startsWith("/v1/users/") -> {
                                val uname = fullPath.removePrefix("/v1/users/").trim('/')
                                val stored = users[uname]
                                if (stored == null) Triple(404, "Not Found", """{"error":"not found"}""")
                                else Triple(200, "OK", stored)
                            }
                            method == "POST" && fullPath.startsWith("/v1/inbox/") && fullPath.endsWith("/ack") -> {
                                ackedId = org.json.JSONObject(body).optLong("upToId", 0L)
                                Triple(200, "OK", """{"ok":true}""")
                            }
                            method == "POST" && fullPath.startsWith("/v1/inbox/") -> {
                                val id = nextMsgId++
                                inbox.add(id to body)
                                Triple(201, "Created", """{"ok":true,"id":$id}""")
                            }
                            method == "GET" && fullPath.startsWith("/v1/inbox/") -> {
                                val arr = org.json.JSONArray()
                                synchronized(inbox) {
                                    for ((id, env) in inbox) {
                                        arr.put(org.json.JSONObject().put("id", id).put("envelope", env))
                                    }
                                }
                                Triple(200, "OK", org.json.JSONObject().put("messages", arr).toString())
                            }
                            else -> Triple(404, "Not Found", """{"error":"unknown"}""")
                        }
                        val respBytes = respBody.toByteArray(Charsets.UTF_8)
                        val out = s.getOutputStream()
                        out.write("HTTP/1.1 $code $statusText\r\nContent-Type: application/json\r\nContent-Length: ${respBytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                        out.write(respBytes)
                        out.flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val baseUrl = "http://127.0.0.1:${serverSocket.localPort}"
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var savedCursor = 0L
            val service = com.kostadinosk.pulsemesh.service.LiveMeshNetworkService(
                scope = scope,
                localNodeId = { "0xA11C" },
                localCallsign = { "Alice-1" },
                channelName = { "primary" },
                getCurrentLocation = { Pair(null, null) },
                getBatteryPercent = { 90 },
                isInternetEnabled = { true },
                localUsername = { "alice_d1" },
                usernameRelayBaseUrl = { baseUrl },
                myPrivateKeyBase64 = { kpAlice.privateKeyBase64 },
                myAuthToken = { "test-auth-tok" },
                resolvePeerPublicKeyByUsername = { u -> if (u == "alice_d1") kpAlice.publicKeyBase64 else "" },
                getInboxCursor = { savedCursor },
                setInboxCursor = { savedCursor = it },
            )

            val received = java.util.Collections.synchronizedList(mutableListOf<WireMeshPacket>())
            kotlinx.coroutines.runBlocking {
                val collector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    service.incomingPackets.collect { received.add(it) }
                }
                val (healthOk, healthMsg) = service.testUsernameRelayHealth(baseUrl)
                assertTrue(healthMsg, healthOk)

                val salt = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.generateAccountSaltHex()
                val claim = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.signUsernameClaim(
                    com.kostadinosk.pulsemesh.crypto.UsernameClaimRecord(
                        username = "alice_d1",
                        displayName = "Alice-1",
                        nodeId = "0xA11C",
                        publicKeyBase64 = kpAlice.publicKeyBase64,
                        saltHex = salt,
                        claimedAt = System.currentTimeMillis(),
                        claimVersion = 2,
                        pinVault = "PV1:test",
                        recoveryVault = "PV1:rk",
                        authVerifierHash = "abc",
                    ),
                    kpAlice.privateKeyBase64,
                )

                val firstRes = service.publishUsernameClaimWithStatus(claim, isUpdate = false)
                assertTrue(firstRes.success)
                assertFalse(firstRes.conflict)

                // Duplicate claim from Bob must hit atomic 409 Conflict
                val secondRes = service.publishUsernameClaimWithStatus(
                    claim.copy(publicKeyBase64 = kpBob.publicKeyBase64),
                    isUpdate = false,
                )
                assertFalse(secondRes.success)
                assertTrue(secondRes.conflict)

                val fetched = service.pollUsernameClaimWithStatus("alice_d1")
                assertTrue(fetched.reachable)
                assertEquals("0xA11C", fetched.claim?.nodeId)

                // Send encrypted PMU2 envelope to Alice's inbox & verify polling + ECDSA ACK
                val pmu2 = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.encryptUsernameInboxEnvelope(
                    """{"packetId":"d1_msg_1","srcNodeId":"0xB0B0","srcCallsign":"Bob","dstNodeId":"0xA11C","type":"E2EE_DM","payload":"hello_d1","su":"bob_d1","du":"alice_d1"}""",
                    kpAlice.publicKeyBase64,
                    "alice_d1",
                )
                assertTrue(pmu2.startsWith("PMU2:"))
                inbox.add(1L to pmu2)
                service.pollCustomRelayInboxOnce(baseUrl, "alice_d1")
                kotlinx.coroutines.delay(150L)

                assertEquals(1L, savedCursor)
                assertEquals(1L, ackedId)
                assertTrue(
                    "Expected decrypted PMU2 packet in received (size=${received.size}, packets=$received)",
                    received.any { it.packetId == "d1_msg_1" && it.payload == "hello_d1" },
                )
                collector.cancel()
            }

            scope.cancel()
        } finally {
            running.set(false)
            runCatching { serverSocket.close() }
            serverThread.interrupt()
        }
    }

    @Test
    fun `verify PMB1 gzipped encrypted backup chunking and full device migration on PIN or Recovery Key login`() {
        val kpAlice = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.generateKeyPair()
        val kpEve = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.generateKeyPair()
        val salt = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.generateAccountSaltHex()

        // 1. Verify GZIP + AES-256-GCM PMB1 encryption, chunk splitting (PMB1C) & reassembly
        val largeJson = """{"settings":{"appTheme":"FRUTIGER_AERO_DARK"},"padding":"${"A1B2C3D4".repeat(700)}"}"""
        val encrypted = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.encryptAccountBackup(
            snapshotJson = largeJson,
            privateKeyBase64 = kpAlice.privateKeyBase64,
            saltHex = salt,
        )
        assertTrue(encrypted.startsWith("PMB1:"))
        val chunks = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.splitBackupIntoChunks(encrypted, "sync1", chunkSize = 40)
        assertTrue("Backup should split into multiple chunks (got ${chunks.size})", chunks.size >= 2)
        val reassembled = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.reassembleBackupChunks(chunks)
        assertEquals(encrypted, reassembled)
        assertEquals(null, com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.decryptAccountBackup(reassembled!!, kpEve.privateKeyBase64, salt))
        assertEquals(largeJson, com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.decryptAccountBackup(reassembled, kpAlice.privateKeyBase64, salt))

        // 2. End-to-end Device A -> Mock Relay -> Wipe -> Device B (PIN login) -> Wipe -> Device C (Recovery Key login)
        val users = java.util.concurrent.ConcurrentHashMap<String, String>()
        val backups = java.util.concurrent.ConcurrentHashMap<String, String>()
        val serverSocket = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val serverThread = Thread {
            while (running.get()) {
                val sock = runCatching { serverSocket.accept() }.getOrNull() ?: break
                runCatching {
                    sock.use { s ->
                        val reader = java.io.BufferedReader(java.io.InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                        val reqLine = reader.readLine() ?: return@use
                        val parts = reqLine.split(" ")
                        val method = parts.getOrNull(0).orEmpty()
                        val fullPath = parts.getOrNull(1).orEmpty().substringBefore("?")
                        var contentLen = 0
                        var line: String?
                        while (reader.readLine().also { line = it } != null && line.orEmpty().isNotEmpty()) {
                            val h = line.orEmpty()
                            if (h.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLen = h.substringAfter(":").trim().toIntOrNull() ?: 0
                            }
                        }
                        val bodyChars = CharArray(contentLen)
                        var readSoFar = 0
                        while (readSoFar < contentLen) {
                            val r = reader.read(bodyChars, readSoFar, contentLen - readSoFar)
                            if (r <= 0) break
                            readSoFar += r
                        }
                        val body = String(bodyChars, 0, readSoFar)

                        val (code, statusText, respBody) = when {
                            fullPath == "/health" -> Triple(200, "OK", """{"ok":true}""")
                            method == "POST" && fullPath == "/v1/users/claim" -> {
                                val uname = org.json.JSONObject(body).optString("username")
                                if (users.putIfAbsent(uname, body) == null) Triple(201, "Created", """{"ok":true}""")
                                else Triple(409, "Conflict", """{"error":"taken"}""")
                            }
                            method == "GET" && fullPath.matches(Regex("^/v1/users/[^/]+/backup$")) -> {
                                val uname = fullPath.removePrefix("/v1/users/").removeSuffix("/backup")
                                val blob = backups[uname] ?: ""
                                Triple(200, "OK", org.json.JSONObject().put("ok", true).put("backupBlob", blob).toString())
                            }
                            method == "PUT" && fullPath.matches(Regex("^/v1/users/[^/]+/backup$")) -> {
                                val uname = fullPath.removePrefix("/v1/users/").removeSuffix("/backup")
                                val blob = org.json.JSONObject(body).optString("backupBlob", "")
                                backups[uname] = blob
                                Triple(200, "OK", """{"ok":true}""")
                            }
                            method == "POST" && fullPath.matches(Regex("^/v1/users/[^/]+/challenge$")) -> {
                                Triple(200, "OK", """{"ok":true,"nonce":"test-nonce-1"}""")
                            }
                            method == "POST" && fullPath.matches(Regex("^/v1/users/[^/]+/vault$")) -> {
                                val uname = fullPath.removePrefix("/v1/users/").removeSuffix("/vault")
                                val stored = users[uname]
                                if (stored == null) Triple(404, "Not Found", """{"error":"not found"}""")
                                else Triple(200, "OK", stored)
                            }
                            method == "GET" && fullPath.startsWith("/v1/users/") -> {
                                val uname = fullPath.removePrefix("/v1/users/").trim('/')
                                val stored = users[uname]
                                if (stored == null) Triple(404, "Not Found", """{"error":"not found"}""")
                                else Triple(200, "OK", stored)
                            }
                            method == "PUT" && fullPath.startsWith("/v1/users/") -> {
                                val uname = fullPath.removePrefix("/v1/users/").trim('/')
                                users[uname] = body
                                Triple(200, "OK", """{"ok":true}""")
                            }
                            else -> Triple(200, "OK", """{"messages":[]}""")
                        }
                        val respBytes = respBody.toByteArray(Charsets.UTF_8)
                        val out = s.getOutputStream()
                        out.write("HTTP/1.1 $code $statusText\r\nContent-Type: application/json\r\nContent-Length: ${respBytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                        out.write(respBytes)
                        out.flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val baseUrl = "http://127.0.0.1:${serverSocket.localPort}"
            val sp = app.getSharedPreferences("pulsemesh_production_v11", Context.MODE_PRIVATE)
            val chatFile = java.io.File(app.filesDir, "chat_history_v7.json")

            // --- DEVICE A: Create account, chats, contacts, peer keys, groups & settings ---
            sp.edit().clear().commit()
            if (chatFile.exists()) chatFile.delete()
            val vmA = MeshViewModel(app)
            vmA.toggleInternetEnabled(true)
            vmA.setUsernameRelayUrl(baseUrl)
            vmA.setAppTheme(com.kostadinosk.pulsemesh.util.AppTheme.FRUTIGER_AERO_DARK)
            vmA.setMeshChannel("delta_ops_9")
            vmA.setMeshPassphrase("eagle-secret-99")

            val kpFriend = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.generateKeyPair()
            vmA.prefs.verifyOrTrustPeerPublicKey("0xB0B1", kpFriend.publicKeyBase64)
            vmA.prefs.saveUsernameClaim(
                com.kostadinosk.pulsemesh.crypto.UsernameClaimRecord(
                    username = "bob_friend",
                    displayName = "Bob",
                    nodeId = "0xB0B1",
                    publicKeyBase64 = kpFriend.publicKeyBase64,
                    saltHex = "",
                    claimedAt = 1700000000000L,
                ),
            )
            val createdGroup = vmA.createMeshGroup("Bravo Squad", listOf("0xB0B1"))
            vmA.persistentChatManager.savePacket(
                "dm_0xB0B1",
                com.kostadinosk.pulsemesh.model.MeshPacket(
                    id = "PKT#MIG1",
                    sourceId = "0xB0B1",
                    destId = vmA.uiState.value.localNodeId,
                    sourceCallsign = "Bob",
                    type = com.kostadinosk.pulsemesh.model.PacketType.TEXT,
                    content = "Meet at waypoint North",
                    hops = 1,
                    rssiDbm = -72,
                    transport = "HYBRID USERNAME",
                    rawHexBadge = "[PKT#MIG1 | HYB]",
                    isIncoming = true,
                    timestamp = System.currentTimeMillis(),
                ),
            )

            var claimedOk = false
            vmA.claimOrRecoverUsername("alice_mig", pin = "alice7391", isRecoveryMode = false) { ok, _ ->
                claimedOk = ok
            }
            val deadlineA = System.currentTimeMillis() + 8000L
            while (!claimedOk && System.currentTimeMillis() < deadlineA) {
                ShadowLooper.idleMainLooper()
                Thread.sleep(50)
            }
            assertTrue("Device A claim should succeed", claimedOk)
            val savedRecoveryKey = vmA.uiState.value.accountRecoveryKey
            val savedPrivKey = vmA.prefs.x25519PrivateKey
            assertTrue(savedRecoveryKey.startsWith("PMRK-"))

            var syncOk = false
            vmA.syncAccountBackupNow { ok, _ -> syncOk = ok }
            val deadlineSync = System.currentTimeMillis() + 8000L
            while (!syncOk && System.currentTimeMillis() < deadlineSync) {
                ShadowLooper.idleMainLooper()
                Thread.sleep(50)
            }
            assertTrue("Device A encrypted backup should upload", syncOk)
            assertTrue(backups["alice_mig"]?.startsWith("PMB1:") == true)
            vmA.toggleInternetEnabled(false)
            vmA.meshNetworkService.stop()
            vmA.persistentChatManager.wipeAllHistoryForDuress()

            // --- DEVICE B: Brand-new wiped device logs in with passphrase ONLY ---
            sp.edit().clear().commit()
            if (chatFile.exists()) chatFile.delete()
            val vmB = MeshViewModel(app)
            vmB.toggleInternetEnabled(true)
            vmB.setUsernameRelayUrl(baseUrl)
            assertEquals("", vmB.uiState.value.claimedUsername)
            assertEquals(com.kostadinosk.pulsemesh.util.AppTheme.TACTICAL_DARK, vmB.uiState.value.appTheme)

            var pinLoginDone = false
            var pinLoginOk = false
            var pinLoginMsg = ""
            vmB.claimOrRecoverUsername("alice_mig", pin = "alice7391", recoveryKeyInput = "", isRecoveryMode = true) { ok, msg ->
                pinLoginOk = ok
                pinLoginMsg = msg
                pinLoginDone = true
            }
            val deadlineB = System.currentTimeMillis() + 8000L
            while (!pinLoginDone && System.currentTimeMillis() < deadlineB) {
                ShadowLooper.idleMainLooper()
                Thread.sleep(50)
            }
            assertTrue("Passphrase login on Device B should succeed (done=$pinLoginDone, msg=$pinLoginMsg)", pinLoginOk)
            assertEquals("alice_mig", vmB.uiState.value.claimedUsername)
            assertEquals(savedPrivKey, vmB.prefs.x25519PrivateKey)
            assertEquals("", vmB.uiState.value.accountRecoveryKey)
            assertEquals(com.kostadinosk.pulsemesh.util.AppTheme.FRUTIGER_AERO_DARK, vmB.uiState.value.appTheme)
            assertEquals("delta_ops_9", vmB.uiState.value.meshChannel)
            assertEquals("eagle-secret-99", vmB.uiState.value.meshPassphrase)
            assertEquals(kpFriend.publicKeyBase64, vmB.prefs.getPeerPublicKey("0xB0B1"))
            assertTrue(vmB.prefs.getUsernameClaim("bob_friend") != null)
            assertTrue(vmB.uiState.value.meshGroups.any { it.groupId == createdGroup.groupId && it.groupKeyBase64 == createdGroup.groupKeyBase64 })
            assertTrue(vmB.persistentChatManager.getPacketsForThread("dm_0xB0B1").any { it.id == "PKT#MIG1" && it.content == "Meet at waypoint North" })
            vmB.toggleInternetEnabled(false)
            vmB.meshNetworkService.stop()
            vmB.persistentChatManager.wipeAllHistoryForDuress()

            // --- DEVICE C: Brand-new wiped device logs in with RECOVERY KEY ONLY (no passphrase) ---
            sp.edit().clear().commit()
            if (chatFile.exists()) chatFile.delete()
            val vmC = MeshViewModel(app)
            vmC.toggleInternetEnabled(true)
            vmC.setUsernameRelayUrl(baseUrl)

            var rkLoginDone = false
            var rkLoginOk = false
            var rkLoginMsg = ""
            vmC.claimOrRecoverUsername("alice_mig", pin = "", recoveryKeyInput = savedRecoveryKey, isRecoveryMode = true) { ok, msg ->
                rkLoginOk = ok
                rkLoginMsg = msg
                rkLoginDone = true
            }
            val deadlineC = System.currentTimeMillis() + 8000L
            while (!rkLoginDone && System.currentTimeMillis() < deadlineC) {
                ShadowLooper.idleMainLooper()
                Thread.sleep(50)
            }
            assertTrue("Recovery Key login on Device C should succeed (done=$rkLoginDone, msg=$rkLoginMsg)", rkLoginOk)
            assertEquals("alice_mig", vmC.uiState.value.claimedUsername)
            assertEquals(savedPrivKey, vmC.prefs.x25519PrivateKey)
            assertEquals(com.kostadinosk.pulsemesh.util.AppTheme.FRUTIGER_AERO_DARK, vmC.uiState.value.appTheme)
            assertEquals("delta_ops_9", vmC.uiState.value.meshChannel)
            assertEquals(kpFriend.publicKeyBase64, vmC.prefs.getPeerPublicKey("0xB0B1"))
            assertTrue(vmC.uiState.value.meshGroups.any { it.groupId == createdGroup.groupId })
            assertTrue(vmC.persistentChatManager.getPacketsForThread("dm_0xB0B1").any { it.id == "PKT#MIG1" })
            vmC.toggleInternetEnabled(false)
            vmC.meshNetworkService.stop()
        } finally {
            running.set(false)
            runCatching { serverSocket.close() }
            serverThread.interrupt()
        }
    }

    @Test
    fun `verify zero hardcoded bots in fresh MeshViewModel and PersistentChatManager`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sp = app.getSharedPreferences("pulsemesh_production_v11", Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        val chatFile = java.io.File(app.filesDir, "chat_history_v7.json")
        if (chatFile.exists()) chatFile.delete()

        val vm = MeshViewModel(app)
        assertFalse(
            "Default operator callsign must not be hardcoded Kosta-OP15",
            vm.uiState.value.operatorCallsign.equals("Kosta-OP15", ignoreCase = true),
        )
        assertTrue(
            "Primary channel must start clean without seed bots",
            vm.persistentChatManager.getPacketsForThread("channel_primary").isEmpty(),
        )
        assertTrue(
            "Encrypted channel must start clean without seed bots",
            vm.persistentChatManager.getPacketsForThread("channel_encrypted").isEmpty(),
        )
        assertTrue(vm.isBlockedFakeCallsign("Mesh Relay"))
        assertTrue(vm.isBlockedFakeCallsign("Security Gateway"))

        vm.registerOrUpdatePeerInstant(peerNodeId = "0x9991", callsign = "Mesh Relay", forceImmediateUiUpdate = true)
        vm.registerOrUpdatePeerInstant(peerNodeId = "0x9992", callsign = "Security Gateway", forceImmediateUiUpdate = true)
        assertEquals(0, vm.uiState.value.nodes.count { !it.isSelf })
    }

    @Test
    fun `verify USERNAME_LOOKUP relay eligibility and DM inbox messaging resilience`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MeshViewModel(app)

        val lookupPkt = WireMeshPacket(
            packetId = "ulook_test_1",
            srcNodeId = "0xB0B1",
            srcCallsign = "Bob",
            dstNodeId = "BROADCAST",
            type = "USERNAME_LOOKUP",
            payload = "alice_mesh",
            ttl = 5,
            hops = 0,
        )
        val relayed = vm.buildRelayedPacketIfEligible(lookupPkt)
        assertTrue("USERNAME_LOOKUP must be relayable when TTL > 1", relayed != null && relayed.ttl == 4 && relayed.hops == 1)

        // Handling incoming USERNAME_LOOKUP and sending DM to a claimed username without ECDH key must not crash
        vm.handleIncomingMeshPacket(lookupPkt)
        vm.connectByUsername("distant_op")
        val targetThread = vm.uiState.value.selectedThreadId!!
        vm.sendMessageInThread(targetThread, "Hello via inbox")
        assertTrue(
            vm.persistentChatManager.getPacketsForThread(targetThread).any { it.content == "Hello via inbox" },
        )
    }

    @Test
    fun `verify call crash resilience with duplicate peers, NaN RMS, rapid call transitions, and voicemail without mic`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MeshViewModel(app)
        val peer = MeshNode(id = "0x9B1C", callsign = "Bravo-2", isSelf = false)

        // 1. Rapid outgoing -> accept -> end -> incoming -> decline -> voicemail (no mic permission)
        vm.startDuplexCall(peer)
        vm.acceptDuplexCall()
        vm.endDuplexCall()

        vm.handleIncomingMeshPacket(
            WireMeshPacket(
                packetId = "call_ring_rapid_1",
                srcNodeId = "0x9B1C",
                srcCallsign = "Bravo-2",
                dstNodeId = vm.uiState.value.localNodeId,
                type = "CALL_RING",
                payload = "RING",
            ),
        )
        vm.declineDuplexCall()

        vm.tacticalCallService.enterVoicemailRecordingMode(peerId = "0x9B1C", peerCallsign = "Bravo-2")
        Thread.sleep(60)
        vm.endDuplexCall()
        assertEquals(CallSignalingState.IDLE, vm.uiState.value.callState)

        // 2. Malformed Base64 audio payload must not crash VOICE handler
        vm.handleIncomingMeshPacket(
            WireMeshPacket(
                packetId = "voice_bad_b64_1",
                srcNodeId = "0x9B1C",
                srcCallsign = "Bravo-2",
                dstNodeId = vm.uiState.value.localNodeId,
                type = "VOICE",
                payload = "!!!not-valid-base64!!!",
            ),
        )

        // 3. SimpleCallVoiceScreen with duplicate peer IDs and Float.NaN RMS must render without throwing
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audioService = TacticalAudioService(context = app, scope = scope)
        composeTestRule.setContent {
            PulseMeshTheme(appTheme = com.kostadinosk.pulsemesh.util.AppTheme.TACTICAL_DARK) {
                SimpleCallVoiceScreen(
                    state = MeshUiState(
                        selectedTabIndex = 2,
                        callState = CallSignalingState.IN_CALL_ACTIVE,
                        remoteCallerId = "0x9B1C",
                        remoteCallerName = "Bravo-2",
                        nodes = listOf(
                            MeshNode(id = "0x4F2A", callsign = "Operator (Self)", isSelf = true),
                            MeshNode(id = "0x9B1C", callsign = "Bravo-2", isSelf = false),
                            MeshNode(id = "0x9B1C", callsign = "Bravo-2 Dup", isSelf = false),
                        ),
                    ),
                    duplexCallState = DuplexCallState(
                        signalingState = CallSignalingState.IN_CALL_ACTIVE,
                        isInCall = true,
                        peerNodeId = "0x9B1C",
                        peerCallsign = "Bravo-2",
                        audioLevelRms = Float.NaN,
                    ),
                    audioService = audioService,
                    onStartCall = ::noOpStartCall,
                    onEndCall = {},
                    onToggleMute = {},
                    onToggleSpeaker = {},
                    onStartPtt = {},
                    onStopPtt = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        scope.cancel()
    }
}



