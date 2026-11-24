// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.kostadinosk.pulsemesh.crypto.SafetyNumberUtil
import com.kostadinosk.pulsemesh.service.EmergencyToolkitState
import com.kostadinosk.pulsemesh.service.EncryptedBackupManager
import com.kostadinosk.pulsemesh.service.PulseMeshBackupPayload
import com.kostadinosk.pulsemesh.ui.components.ConnectShareSheetContent
import com.kostadinosk.pulsemesh.ui.components.EmergencySosSheetContent
import com.kostadinosk.pulsemesh.ui.components.PulseMeshTopBar
import com.kostadinosk.pulsemesh.ui.components.SafetyNumberSheetContent
import com.kostadinosk.pulsemesh.ui.components.TelemetryDrawerContent
import com.kostadinosk.pulsemesh.ui.onboarding.PulseOnboardingScreen
import com.kostadinosk.pulsemesh.ui.screens.map.TacticalMapOverlaySheet
import com.kostadinosk.pulsemesh.ui.screens.settings.AppearanceSettingsScreen
import com.kostadinosk.pulsemesh.ui.screens.settings.ModularSettingsHub
import com.kostadinosk.pulsemesh.ui.screens.settings.RadioLabDiagnosticsScreen
import com.kostadinosk.pulsemesh.ui.screens.settings.UpdatesDataSettingsScreen
import com.kostadinosk.pulsemesh.ui.screens.voice.PulseVoiceHubScreen
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.ui.theme.resolvePulseTokens
import com.kostadinosk.pulsemesh.util.AppTheme
import com.kostadinosk.pulsemesh.util.HardwareCapabilityDetector
import com.kostadinosk.pulsemesh.util.ThemeBrightness
import com.kostadinosk.pulsemesh.util.ThemePreset
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState
import com.kostadinosk.pulsemesh.viewmodel.MeshViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V200UiAndFeaturesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `design tokens resolve all 4 ThemePresets cleanly and map AppTheme bidirectionally`() {
        ThemePreset.entries.forEach { preset ->
            val darkTokens = resolvePulseTokens(preset, ThemeBrightness.DARK, isDark = true)
            val lightTokens = resolvePulseTokens(preset, ThemeBrightness.LIGHT, isDark = false)
            assertEquals(preset, darkTokens.preset)
            assertTrue(darkTokens.isDark)
            assertFalse(lightTokens.isDark)
            assertEquals(preset == ThemePreset.FIELD_OPS_HUD, darkTokens.useMonospaceLabels)
            assertEquals(preset == ThemePreset.FRUTIGER_AERO, darkTokens.useGlassSheen)
        }
        assertEquals(ThemePreset.FIELD_OPS_HUD, AppTheme.ORIGINAL.toThemePreset())
        assertEquals(ThemePreset.FRUTIGER_AERO, AppTheme.FRUTIGER_AERO.toThemePreset())
        assertEquals(ThemePreset.CLEAN_MESSENGER, AppTheme.MATERIAL3.toThemePreset())
        assertEquals(ThemePreset.PRACTICAL_TACTICAL, AppTheme.TACTICAL_DARK.toThemePreset())
    }

    @Test
    fun `hardware capability detector accurately resolves regional codenames and SoC families`() {
        val s24Eu = HardwareCapabilityDetector.resolveDeviceIdentity(
            manufacturer = "samsung", model = "SM-S928B", deviceCodename = "e3q",
            productCode = "e3qxxx", socModel = "SM8650", hardware = "qcom"
        )
        assertEquals("Samsung Galaxy S24 Ultra", s24Eu.first)
        assertEquals("Global / Europe (EEA)", s24Eu.second)
        assertTrue(s24Eu.third.contains("Snapdragon 8 Gen 3"))

        val s24Us = HardwareCapabilityDetector.resolveDeviceIdentity(
            manufacturer = "samsung", model = "SM-S928U1", deviceCodename = "e3q",
            productCode = "e3quew", socModel = "SM8650", hardware = "qcom"
        )
        assertEquals("USA (Unlocked)", s24Us.second)

        val pixel9 = HardwareCapabilityDetector.resolveDeviceIdentity(
            manufacturer = "Google", model = "Pixel 9", deviceCodename = "tokay",
            productCode = "tokay", socModel = "Tensor G4", hardware = "tokay"
        )
        assertEquals("Google Pixel 9", pixel9.first)
        assertTrue(pixel9.third.contains("Tensor G4"))

        val xiaomiEea = HardwareCapabilityDetector.resolveDeviceIdentity(
            manufacturer = "Xiaomi", model = "24129PN74E", deviceCodename = "dada",
            productCode = "dada_eea", socModel = "SM8750", hardware = "qcom"
        )
        assertEquals("Xiaomi 15", xiaomiEea.first)
        assertEquals("Europe (EEA)", xiaomiEea.second)
        assertTrue(xiaomiEea.third.contains("Snapdragon 8 Elite"))
    }

    @Test
    fun `safety number 60-digit code is strictly symmetric between peers and verifies QR payload`() {
        val snAlice = SafetyNumberUtil.compute60DigitSafetyNumber(
            localNodeId = "0xAAA1", localPublicKey = "PUB_ALICE_BASE64",
            peerNodeId = "0xBBB2", peerPublicKey = "PUB_BOB_BASE64"
        )
        val snBob = SafetyNumberUtil.compute60DigitSafetyNumber(
            localNodeId = "0xBBB2", localPublicKey = "PUB_BOB_BASE64",
            peerNodeId = "0xAAA1", peerPublicKey = "PUB_ALICE_BASE64"
        )
        assertEquals("Safety numbers must match identically on both peers", snAlice, snBob)
        assertTrue("Must match JS Web PWA Safety Number prefix exactly", snAlice.startsWith("10953 01315 58733"))
        val blocks = snAlice.split(" ")
        assertEquals(12, blocks.size)
        assertTrue(blocks.all { it.length == 5 && it.all(Char::isDigit) })

        val qr = SafetyNumberUtil.buildVerificationQrPayload("0xAAA1", "0xBBB2", snAlice)
        assertTrue(SafetyNumberUtil.verifyScannedQrPayload(qr, snBob))
        assertFalse(SafetyNumberUtil.verifyScannedQrPayload(qr + "9", snBob))
    }

    @Test
    fun `encrypted pmbackup round-trip restores payload and fails closed on wrong passphrase or tamper`() {
        val payload = PulseMeshBackupPayload(
            operatorCallsign = "Ranger-42",
            nodeId = "0x1122334455667788",
            claimedUsername = "ranger42",
            x25519PublicKey = "PUB_KEY_SAMPLE",
            x25519PrivateKey = "PRIV_KEY_SAMPLE",
            iceMedicalInfo = "O+ • No Allergies",
            meshChannel = "pulsemesh_live_primary"
        )
        // Use reduced iterations for fast unit test execution while verifying real PBKDF2 + AES-256-GCM AAD
        val encoded = EncryptedBackupManager.exportEncryptedBackup(payload, "correct-horse-battery", iterations = 10_000)
        assertTrue(encoded.startsWith(EncryptedBackupManager.MAGIC_PREFIX))

        val restored = EncryptedBackupManager.importEncryptedBackup(encoded, "correct-horse-battery", iterations = 10_000)
        assertTrue(restored.isSuccess)
        assertEquals("Ranger-42", restored.getOrThrow().operatorCallsign)
        assertEquals("ranger42", restored.getOrThrow().claimedUsername)
        assertEquals("O+ • No Allergies", restored.getOrThrow().iceMedicalInfo)

        val wrongPass = EncryptedBackupManager.importEncryptedBackup(encoded, "wrong-passphrase", iterations = 10_000)
        assertTrue("Wrong passphrase must fail closed", wrongPass.isFailure)

        val tampered = encoded.dropLast(2) + if (encoded.endsWith("AA")) "BB" else "AA"
        val tamperRes = EncryptedBackupManager.importEncryptedBackup(tampered, "correct-horse-battery", iterations = 10_000)
        assertTrue("Tampered ciphertext must fail GCM auth tag", tamperRes.isFailure)
    }

    @Test
    fun `viewmodel defaults to Chats start tab and supports theme preset and safe check-in`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MeshViewModel(app)
        assertEquals("Chats tab (1) should be the v2.0 start tab", 1, vm.uiState.value.selectedTabIndex)

        vm.setThemePreset(ThemePreset.FIELD_OPS_HUD)
        assertEquals(ThemePreset.FIELD_OPS_HUD, vm.uiState.value.themePreset)
        vm.setThemeBrightness(ThemeBrightness.LIGHT)
        assertEquals(ThemeBrightness.LIGHT, vm.uiState.value.themeBrightness)

        vm.sendImSafeCheckIn()
        assertFalse(vm.uiState.value.isSosActive)
    }

    @Test
    fun `v200 core composables render cleanly across presets`() {
        var openedTelemetry = false
        var openedConnect = false
        var openedSos = false

        composeTestRule.setContent {
            PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
                PulseMeshTopBar(
                    state = MeshUiState(operatorCallsign = "Alpha-01", activePeerCount = 3),
                    onOpenTelemetryDrawer = { openedTelemetry = true },
                    onOpenConnectHub = { openedConnect = true },
                    onOpenSosDrawer = { openedSos = true }
                )
            }
        }
        composeTestRule.onNodeWithTag("top_bar_mesh_status_pill").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("top_bar_connect_hub_btn").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("top_bar_sos_btn").assertIsDisplayed().performClick()
        assertTrue(openedTelemetry && openedConnect && openedSos)
    }

    @Test
    fun `dual track onboarding chooser and modular settings hub render interactively`() {
        composeTestRule.setContent {
            PulseMeshTheme(themePreset = ThemePreset.CLEAN_MESSENGER, themeBrightness = ThemeBrightness.LIGHT) {
                PulseOnboardingScreen(
                    state = MeshUiState(operatorCallsign = "Maya"),
                    onUpdateCallsign = {},
                    onReserveUsername = { _, _ -> },
                    onRequestPermissions = {},
                    onDownloadMapRegion = { _, _, _, _ -> },
                    onSaveIceInfo = {},
                    onOpenQrScanner = {},
                    onFinish = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("onboarding_track_fast").assertIsDisplayed()
        composeTestRule.onNodeWithTag("onboarding_track_custom").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("onboarding_callsign_input").assertIsDisplayed()
    }

    @Test
    fun `connect hub, emergency kit, safety number, voice hub, map overlay, and settings sub-screens render cleanly`() {
        composeTestRule.setContent {
            PulseMeshTheme(themePreset = ThemePreset.FIELD_OPS_HUD, themeBrightness = ThemeBrightness.DARK) {
                TelemetryDrawerContent(
                    state = MeshUiState(),
                    onDismiss = {}, onToggleInternetMode = {}, onToggleHotspot = {},
                    onOpenRadioLab = {}, onOpenConnectHub = {}
                )
                ConnectShareSheetContent(
                    state = MeshUiState(),
                    onDismiss = {}, onLaunchQrScanner = {}, onToggleHotspotPortal = {},
                    onToggleLoraBridge = {}, onSelectLoraProtocol = {}, onScanLoraDevices = {},
                    onToggleAcousticModem = {}, onSendAcousticMessage = {}
                )
                EmergencySosSheetContent(
                    state = MeshUiState(),
                    toolkitState = EmergencyToolkitState(torchAvailable = true),
                    onDismiss = {}, onToggleSosBeacon = {}, onSendImSafeCheckIn = {},
                    onToggleTorchMorse = {}, onToggleSiren = {}
                )
                SafetyNumberSheetContent(
                    localNodeId = "0x1", localPublicKey = "K1", peerNodeId = "0x2",
                    peerCallsign = "Peer", peerPublicKey = "K2", isKeyChangedWarning = false,
                    isVerified = false, appLanguage = "en", onMarkVerified = {},
                    onScanPeerQr = {}, onDismiss = {}
                )
                PulseVoiceHubScreen(
                    state = MeshUiState(),
                    duplexCallState = com.kostadinosk.pulsemesh.service.DuplexCallState(),
                    onStartCall = {}, onStartGroupCall = {}, onEndCall = {}, onToggleMute = {},
                    onToggleSpeaker = {}, onStartPtt = {}, onStopPtt = {}, onAcceptCall = {},
                    onDeclineCall = {}, onReplayLastRx = {}, onSelectAudioRoute = {},
                    onJoinPttChannel = {}, onToggleCourtesyBeep = {}, onToggleConferenceMute = {},
                    onKickConferencePeer = {}
                )
                ModularSettingsHub(
                    state = MeshUiState(), activeDestination = null, onNavigate = {}, subScreenContent = {}
                )
                AppearanceSettingsScreen(
                    selectedPreset = ThemePreset.PRACTICAL_TACTICAL,
                    selectedBrightness = ThemeBrightness.DARK,
                    appLanguage = "en", onSelectPreset = {}, onSelectBrightness = {}, onSelectLanguage = {}
                )
                RadioLabDiagnosticsScreen(
                    state = MeshUiState(), onRunPingSweep = {}, onVerifyRoot = {}, onRefreshHardware = {}
                )
                UpdatesDataSettingsScreen(
                    state = MeshUiState(), backupStatusMessage = "",
                    onCheckUpdates = {}, onInstallVerifiedUpdate = {},
                    onExportEncryptedBackup = {}, onImportEncryptedBackup = {}
                )
                TacticalMapOverlaySheet(
                    state = MeshUiState(), onSelectPeer = {}, onImportMapPack = {},
                    onImportGpxKml = {}, onExportGpx = {}, onToggleCotBridge = {}, onBroadcastGpsPin = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("telemetry_status_drawer").assertExists()
        composeTestRule.onNodeWithTag("connect_share_sheet").assertExists()
        composeTestRule.onNodeWithTag("emergency_sos_sheet").assertExists()
        composeTestRule.onNodeWithTag("safety_number_sheet").assertExists()
        composeTestRule.onNodeWithTag("voice_hub_screen").assertExists()
        composeTestRule.onNodeWithTag("modular_settings_home").assertExists()
        composeTestRule.onNodeWithTag("appearance_settings_screen").assertExists()
        composeTestRule.onNodeWithTag("radio_lab_diagnostics_screen").assertExists()
        composeTestRule.onNodeWithTag("updates_data_settings_screen").assertExists()
        composeTestRule.onNodeWithTag("map_3state_bottom_sheet").assertExists()
    }

    @Test
    fun `v202 map styles remove dark mode and isolate per-style tile servers and caches`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(
            "Dark map mode must be removed",
            com.kostadinosk.pulsemesh.map.MapLayerStyle.entries.none { it.name.equals("DARK", ignoreCase = true) }
        )
        val standardUrls = com.kostadinosk.pulsemesh.util.NativeTileMapManager.buildTileUrlsForStyle(
            com.kostadinosk.pulsemesh.map.MapLayerStyle.STANDARD, 12, 2318, 1580
        )
        val topoUrls = com.kostadinosk.pulsemesh.util.NativeTileMapManager.buildTileUrlsForStyle(
            com.kostadinosk.pulsemesh.map.MapLayerStyle.TOPO, 12, 2318, 1580
        )
        val satUrls = com.kostadinosk.pulsemesh.util.NativeTileMapManager.buildTileUrlsForStyle(
            com.kostadinosk.pulsemesh.map.MapLayerStyle.SATELLITE, 12, 2318, 1580
        )
        val radarUrls = com.kostadinosk.pulsemesh.util.NativeTileMapManager.buildTileUrlsForStyle(
            com.kostadinosk.pulsemesh.map.MapLayerStyle.RADAR, 12, 2318, 1580
        )
        assertTrue(standardUrls.first().contains("openstreetmap.org"))
        assertTrue(topoUrls.first().contains("opentopomap"))
        assertTrue(satUrls.first().contains("World_Imagery"))
        assertTrue("Radar mode must not fetch raster tiles", radarUrls.isEmpty())

        val standardFile = com.kostadinosk.pulsemesh.util.NativeTileMapManager.getTileFile(app, 12, 1, 2, com.kostadinosk.pulsemesh.map.MapLayerStyle.STANDARD)
        val topoFile = com.kostadinosk.pulsemesh.util.NativeTileMapManager.getTileFile(app, 12, 1, 2, com.kostadinosk.pulsemesh.map.MapLayerStyle.TOPO)
        val satFile = com.kostadinosk.pulsemesh.util.NativeTileMapManager.getTileFile(app, 12, 1, 2, com.kostadinosk.pulsemesh.map.MapLayerStyle.SATELLITE)
        assertTrue(standardFile.path != topoFile.path && topoFile.path != satFile.path)
    }

    @Test
    fun `v201 phone call screen and remnant-free settings sub-screens render cleanly`() {
        composeTestRule.setContent {
            PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
                PulseVoiceHubScreen(
                    state = MeshUiState(),
                    duplexCallState = com.kostadinosk.pulsemesh.service.DuplexCallState(
                        isInCall = true,
                        peerCallsign = "Bravo-02",
                        durationSeconds = 42
                    ),
                    onStartCall = {}, onStartGroupCall = {}, onEndCall = {}, onToggleMute = {},
                    onToggleSpeaker = {}, onStartPtt = {}, onStopPtt = {}, onAcceptCall = {},
                    onDeclineCall = {}, onReplayLastRx = {}, onSelectAudioRoute = {},
                    onJoinPttChannel = {}, onToggleCourtesyBeep = {}, onToggleConferenceMute = {},
                    onKickConferencePeer = {}
                )
                com.kostadinosk.pulsemesh.ui.screens.settings.ProfileSettingsScreen(
                    state = MeshUiState(), onUpdateCallsign = {}, onRegenerateNodeId = {},
                    onClaimOrRecoverUsername = { _, _, _, _, _ -> }, onOpenConnectHub = {}, onOpenOnboarding = {}
                )
                com.kostadinosk.pulsemesh.ui.screens.settings.RadiosSettingsScreen(
                    state = MeshUiState(), onSetBatteryPowerProfile = {}, onToggleInternetMode = {},
                    onToggleWebPortal = {}, onToggleBleCodedPhy = {}, onToggleWifiAware = {},
                    onToggleBackgroundRelay = {}, onUpdateChannelAndPassphrase = { _, _ -> },
                    onToggleLoraBridge = {}, onSelectLoraProtocol = {}, onScanLoraDevices = {},
                    onToggleAcousticModem = {}, onToggleCotBridge = {}
                )
                com.kostadinosk.pulsemesh.ui.screens.settings.SecuritySettingsScreen(
                    state = MeshUiState(), onRotateKeys = {}, onSelectLocationShareMode = {},
                    onSetAppLockPin = {}, onSetBiometricUnlock = {}, onSetAppLockTimeout = {},
                    onClearAppLock = {}, onSetDuressPin = {}, onCycleBurnTtl = {}, onPurgeStalePeers = {}
                )
                com.kostadinosk.pulsemesh.ui.screens.settings.SafetySettingsScreen(
                    state = MeshUiState(), onOpenEmergencySosSheet = {},
                    onSaveIceCard = { _, _, _, _ -> }, onSetDeadManMinutes = {},
                    onCheckInDeadMan = {}, onConfigureGeofence = { _, _ -> }
                )
            }
        }
        composeTestRule.onNodeWithTag("phone_active_call_page").assertExists()
        composeTestRule.onNodeWithTag("phone_call_end_btn").assertExists()
        composeTestRule.onNodeWithTag("profile_settings_screen").assertExists()
        composeTestRule.onNodeWithTag("radios_settings_screen").assertExists()
        composeTestRule.onNodeWithTag("security_settings_screen").assertExists()
        composeTestRule.onNodeWithTag("safety_settings_screen").assertExists()
    }

    @Test
    fun `v202 simplified messaging and calling screens render uncluttered controls`() {
        composeTestRule.setContent {
            PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
                com.kostadinosk.pulsemesh.ui.screens.MeshChatScreen(
                    state = MeshUiState(selectedThreadId = null),
                    onSendMessage = {},
                    onBroadcastGpsPin = {}
                )
                PulseVoiceHubScreen(
                    state = MeshUiState(),
                    duplexCallState = com.kostadinosk.pulsemesh.service.DuplexCallState(),
                    onStartCall = {}, onStartGroupCall = {}, onEndCall = {}, onToggleMute = {},
                    onToggleSpeaker = {}, onStartPtt = {}, onStopPtt = {}, onAcceptCall = {},
                    onDeclineCall = {}, onReplayLastRx = {}, onSelectAudioRoute = {},
                    onJoinPttChannel = {}, onToggleCourtesyBeep = {}, onToggleConferenceMute = {},
                    onKickConferencePeer = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("chat_search_toggle_button").assertExists()
        composeTestRule.onNodeWithTag("chat_connect_username_button").assertExists()
        composeTestRule.onNodeWithTag("create_new_group_button").assertExists()
        composeTestRule.onNodeWithTag("quick_status_expand_toggle").assertExists()
        composeTestRule.onNodeWithTag("voice_quick_route_pill").assertExists()
        composeTestRule.onNodeWithTag("phone_dialer_page").assertExists()
    }
}
