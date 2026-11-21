// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.service.CallSignalingState
import com.example.service.TacticalNotificationManager
import com.example.ui.components.AeroGlossBadgeIcon
import com.example.ui.components.AeroNavOrbIcon
import com.example.ui.components.AeroTabIconKind
import com.example.ui.components.LockScreenCallCoverOverlay
import com.example.ui.components.PersistentMiniCallBar
import com.example.ui.components.PulseMeshTopBar
import com.example.ui.components.TacticalMap
import com.example.ui.components.TelemetryHudCard
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.viewmodel.MeshViewModel
import kotlinx.coroutines.flow.MutableStateFlow

data class NavTabItem(
    val title: String,
    val icon: ImageVector,
)

class MainActivity : ComponentActivity() {

    private val viewModel: MeshViewModel by viewModels()
    private val keyguardLockedFlow = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keyguardLockedFlow.value = isKeyguardCurrentlyLocked()
        updateLockScreenWindowFlags(allowOnLockScreen = false)
        enableEdgeToEdge()
        handleNotificationIntent(intent)

        setContent {
            val state by viewModel.uiState.collectAsState()
            val isDeviceKeyguardLocked by keyguardLockedFlow.collectAsState()
            var manualLockCoverRequested by remember { mutableStateOf(false) }

            PulseMeshTheme(appTheme = state.appTheme) {
                val p = LocalPulsePalette.current

                // Permission Request Launcher for Audio, Location, Notifications & BLE/Wi-Fi Radios
                val permissionsToRequest = remember {
                    buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            add(Manifest.permission.BLUETOOTH_SCAN)
                            add(Manifest.permission.BLUETOOTH_ADVERTISE)
                            add(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.NEARBY_WIFI_DEVICES)
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }.toTypedArray()
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) { results ->
                    val allGranted = results.values.all { it }
                    viewModel.onPermissionsResult(allGranted)
                    if (allGranted) {
                        viewModel.requestGpsUpdates()
                    }
                }

                LaunchedEffect(Unit) {
                    if (!isKeyguardCurrentlyLocked()) {
                        permissionLauncher.launch(permissionsToRequest)
                    }
                    viewModel.requestGpsUpdates()
                }

                // Android 14-17 Microphone FGS Safety: Only permit AudioRecord capture while app is foregrounded
                DisposableEffect(this@MainActivity) {
                    val observer = LifecycleEventObserver { _, event ->
                        keyguardLockedFlow.value = isKeyguardCurrentlyLocked()
                        when (event) {
                            Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME ->
                                viewModel.setAppInForeground(inForeground = true)
                            Lifecycle.Event.ON_STOP ->
                                viewModel.setAppInForeground(inForeground = false)
                            else -> Unit
                        }
                    }
                    lifecycle.addObserver(observer)
                    onDispose {
                        lifecycle.removeObserver(observer)
                    }
                }

                val effectiveCallSignaling = if (state.callState != CallSignalingState.IDLE) {
                    state.callState
                } else {
                    state.duplexCallState.signalingState
                }

                // Strictly permit showing over lock screen ONLY while an incoming or active call exists
                LaunchedEffect(effectiveCallSignaling) {
                    keyguardLockedFlow.value = isKeyguardCurrentlyLocked()
                    updateLockScreenWindowFlags(allowOnLockScreen = (effectiveCallSignaling != CallSignalingState.IDLE))
                }

                var prevEffectiveCallSignaling by remember { mutableStateOf(CallSignalingState.IDLE) }
                LaunchedEffect(effectiveCallSignaling) {
                    if (effectiveCallSignaling == CallSignalingState.IDLE) {
                        manualLockCoverRequested = false
                    } else if (prevEffectiveCallSignaling == CallSignalingState.IDLE) {
                        if (state.selectedTabIndex != 2) {
                            viewModel.setTab(2)
                        }
                    }
                    prevEffectiveCallSignaling = effectiveCallSignaling
                }

                // Strict Lock Screen Isolation:
                // When the device is locked, ONLY calls are accessible — never expose Map, Chats, Settings, or Wizard.
                if (isDeviceKeyguardLocked) {
                    if (effectiveCallSignaling != CallSignalingState.IDLE) {
                        LockScreenCallCoverOverlay(
                            state = state,
                            duplexCallState = state.duplexCallState,
                            isDeviceLocked = true,
                            onAcceptCall = { viewModel.acceptDuplexCall() },
                            onDeclineCall = {
                                manualLockCoverRequested = false
                                viewModel.declineDuplexCall()
                            },
                            onEndCall = {
                                manualLockCoverRequested = false
                                viewModel.endDuplexCall()
                            },
                            onToggleMute = { viewModel.toggleCallMute() },
                            onToggleSpeaker = { viewModel.toggleCallSpeaker() },
                            onDismissToApp = { requestDeviceUnlock() },
                        )
                    } else {
                        LockedPrivacyGuardScreen(
                            onUnlockDevice = { requestDeviceUnlock() },
                        )
                    }
                    return@PulseMeshTheme
                }

                // BackHandler navigation behavior
                BackHandler(enabled = (state.selectedNode != null) || (state.selectedTabIndex != 0)) {
                    if (state.selectedNode != null) {
                        viewModel.selectNode(null)
                    } else if (state.selectedTabIndex != 0) {
                        viewModel.setTab(0)
                    }
                }

                // 4-Tab structure: MESH MAP, CHATS, VOICE & PTT, RF & SETTINGS
                val tabs = remember {
                    listOf(
                        NavTabItem("MESH MAP", Icons.Default.Map),
                        NavTabItem("CHATS", Icons.AutoMirrored.Filled.Chat),
                        NavTabItem("VOICE & PTT", Icons.Default.Radio),
                        NavTabItem("RF & SETTINGS", Icons.Default.Settings),
                    )
                }

                val density = LocalDensity.current
                val isImeVisible = WindowInsets.ime.getBottom(density) > 0
                val isInsideChatThread = (state.selectedTabIndex == 1) && (!state.selectedThreadId.isNullOrBlank())

                Scaffold(
                    containerColor = if (p.isAero) Color.Transparent else p.background,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            PulseMeshTopBar(
                                state = state,
                                onToggleSimpleMode = { viewModel.toggleSimpleMode() },
                                onToggleTransportMode = { viewModel.toggleInternetEnabled() },
                                onRequestPermissions = { permissionLauncher.launch(permissionsToRequest) },
                                onOpenSetupWizard = { viewModel.openSetupWizard() },
                                onToggleSos = { viewModel.toggleSosBeacon() },
                                onDismissIncomingSos = { viewModel.dismissIncomingSosAlert() },
                                onAutoConnectHotspot = { viewModel.toggleOrConnectHotspot() },
                            )
                            PersistentMiniCallBar(
                                state = state,
                                duplexCallState = state.duplexCallState,
                                onReturnToCallScreen = { viewModel.setTab(2) },
                                onToggleMute = { viewModel.toggleCallMute() },
                                onAcceptCall = { viewModel.acceptDuplexCall() },
                                onDeclineCall = { viewModel.declineDuplexCall() },
                                onEndCall = { viewModel.endDuplexCall() },
                            )
                        }
                    },
                    bottomBar = {
                        if (!isImeVisible && !isInsideChatThread) {
                            NavigationBar(
                                containerColor = if (p.isAero) {
                                    if (p.isDarkAero) Color(0xDF071C2E) else Color(0xDCF2FAFF)
                                } else {
                                    p.card
                                },
                                contentColor = p.textSecondary,
                                tonalElevation = 6.dp,
                                windowInsets = WindowInsets(0, 0, 0, 0),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (p.isAero) {
                                            Modifier.aeroGlassPanel(
                                                p,
                                                cornerRadius = 0.dp,
                                                tint = if (p.isDarkAero) Color(0xDF071C2E) else Color(0xE6F4FBFF)
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                    .navigationBarsPadding(),
                            ) {
                                val totalUnreadChats = remember(state.conversations) {
                                    state.conversations.sumOf { it.unreadCount }
                                }
                                tabs.forEachIndexed { index, (title, icon) ->
                                    val isSelected = state.selectedTabIndex == index
                                    val tabColor = if (isSelected) {
                                        when (index) {
                                            1 -> p.cyan
                                            3 -> if (!state.isSimpleMode) p.amber else p.emerald
                                            else -> p.emerald
                                        }
                                    } else {
                                        p.textMuted
                                    }
                                    val aeroKind = when (index) {
                                        0 -> AeroTabIconKind.MAP
                                        1 -> AeroTabIconKind.CHATS
                                        2 -> AeroTabIconKind.VOICE
                                        else -> AeroTabIconKind.SETTINGS
                                    }
                                    NavigationBarItem(
                                        selected = isSelected,
                                        onClick = { viewModel.setTab(index) },
                                        icon = {
                                            BadgedBox(
                                                badge = {
                                                    if (index == 1 && totalUnreadChats > 0) {
                                                        Badge(
                                                            containerColor = p.emerald,
                                                            contentColor = if (p.isLight || p.isAero) Color.White else Color.Black,
                                                        ) {
                                                            Text(
                                                                text = if (totalUnreadChats > 99) "99+" else "$totalUnreadChats",
                                                                fontSize = 8.sp,
                                                                fontWeight = FontWeight.ExtraBold,
                                                                fontFamily = FontFamily.Monospace,
                                                            )
                                                        }
                                                    }
                                                }
                                            ) {
                                                if (p.isAero) {
                                                    AeroNavOrbIcon(
                                                        kind = aeroKind,
                                                        selected = isSelected,
                                                        accentColor = when (index) {
                                                            1 -> p.cyan
                                                            3 -> if (!state.isSimpleMode) p.amber else p.emerald
                                                            else -> p.emerald
                                                        },
                                                        isDarkAero = p.isDarkAero,
                                                        size = 26.dp,
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = icon,
                                                        contentDescription = title,
                                                        tint = tabColor,
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                }
                                            }
                                        },
                                        label = {
                                            val displayTitle = if (state.appLanguage == "el") {
                                                when (index) {
                                                    0 -> "Χάρτης"
                                                    1 -> "Μηνύματα"
                                                    2 -> "Φωνή"
                                                    else -> "Ρυθμίσεις"
                                                }
                                            } else title
                                            Text(
                                                text = displayTitle,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    fontSize = 9.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = tabColor,
                                                ),
                                            )
                                        },
                                        colors = NavigationBarItemDefaults.colors(
                                            indicatorColor = if (p.isAero) p.cyanDim else p.surfaceVariant,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .aeroCanvasBackground(p),
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding)
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        var proSettingsSubTab by remember { mutableIntStateOf(0) }
                        when (state.selectedTabIndex) {
                            0 -> Box(modifier = Modifier.fillMaxSize()) {
                                TacticalMap(
                                    userLat = state.userLatitude,
                                    userLon = state.userLongitude,
                                    isGpsLocked = state.isGpsLocked,
                                    nodes = state.nodes,
                                    selectedNode = state.selectedNode,
                                    onSelectNode = { viewModel.selectNode(it) },
                                    onSetManualLocation = { lat, lon -> viewModel.setManualGpsLocation(lat, lon) },
                                    onRequestGps = {
                                        permissionLauncher.launch(permissionsToRequest)
                                        viewModel.requestGpsUpdates()
                                    },
                                    isInternetEnabled = state.isInternetEnabled,
                                    deviceHeadingDeg = state.deviceHeadingDeg,
                                    barometricAltitudeMeters = state.barometricAltitudeMeters,
                                    waypoints = state.waypoints,
                                    onDropWaypoint = { title, type, lat, lon ->
                                        viewModel.dropWaypoint(title, type, lat, lon)
                                    },
                                    broadcastCoarseLocationInBle = state.broadcastCoarseLocationInBle,
                                    onToggleBroadcastCoarseLocation = { viewModel.toggleBroadcastCoarseLocationInBle() },
                                    locationShareMode = state.locationShareMode,
                                    onSelectLocationShareMode = { viewModel.setLocationShareMode(it) },
                                    breadcrumbs = state.breadcrumbs,
                                    geofenceEnabled = state.geofenceEnabled,
                                    geofenceLat = state.geofenceLat,
                                    geofenceLng = state.geofenceLng,
                                    geofenceRadiusMeters = state.geofenceRadiusMeters,
                                    geofenceAlertMessage = state.geofenceAlertMessage,
                                    onDismissGeofenceAlert = { viewModel.dismissGeofenceAlert() },
                                    hasBarometerSensor = state.hasBarometerSensor,
                                    barometricPressureHpa = state.barometricPressureHpa,
                                    weatherTrendLabel = state.weatherTrendLabel,
                                    isStormAlertActive = state.isStormAlertActive,
                                    appLanguage = state.appLanguage,
                                )
                                state.selectedNode?.let { selNode ->
                                    TelemetryHudCard(
                                        node = selNode,
                                        userLat = state.userLatitude,
                                        userLon = state.userLongitude,
                                        deviceHeadingDeg = state.deviceHeadingDeg,
                                        userAltitudeMeters = state.barometricAltitudeMeters,
                                        onClose = { viewModel.selectNode(null) },
                                        onCall = {
                                            viewModel.startDuplexCall(selNode)
                                            viewModel.setTab(2)
                                        },
                                        onMessage = { viewModel.setTab(1) },
                                        onVerifyFingerprint = { peerId -> viewModel.acceptChangedPeerKey(peerId) },
                                        modifier = Modifier.align(Alignment.BottomCenter),
                                    )
                                }
                            }
                            1 -> MeshChatScreen(
                                state = state,
                                onSendMessage = { viewModel.sendChatMessage(it) },
                                onBroadcastGpsPin = { viewModel.pingMeshNow() },
                                onSelectThread = { viewModel.selectThread(it) },
                                onBackToList = { viewModel.selectThread(null) },
                                onSearchQueryChange = { viewModel.setSearchQuery(it) },
                                onStartCall = { node ->
                                    viewModel.startDuplexCall(node)
                                    viewModel.setTab(2)
                                },
                                onRotateKeys = { viewModel.rotateEncryptionKeys() },
                                onCreateGroup = { groupName, memberIds ->
                                    viewModel.createMeshGroup(groupName, memberIds)
                                },
                                onVerifyFingerprint = { peerId -> viewModel.acceptChangedPeerKey(peerId) },
                                onPlayVoiceClip = { viewModel.audioService.playAudioBase64(it) },
                                onSendPhoto = { bmp, caption -> viewModel.sendPhotoInChat(bmp, caption) },
                                onSendTacticalSnap = { viewModel.sendTacticalSnapInChat() },
                                onRetrySendPacket = { pkt -> viewModel.retrySendChatPacket(pkt) },
                                onConnectByUsername = { username, cb -> viewModel.connectByUsername(username, cb) },
                                onSendQuickStatus = { code -> viewModel.sendQuickStatus(code) },
                                onToggleQuickStatusVoice = { viewModel.toggleQuickStatusVoice() },
                                onSendReaction = { pktId, emoji -> viewModel.sendReaction(pktId, emoji) },
                                onTogglePinMessage = { threadId, pktId -> viewModel.togglePinMessage(threadId, pktId) },
                                onSetThreadBurnTtl = { threadId, ttl -> viewModel.setThreadBurnTtl(threadId, ttl) },
                            )
                            2 -> SimpleCallVoiceScreen(
                                state = state,
                                duplexCallState = state.duplexCallState,
                                audioService = viewModel.audioService,
                                onStartCall = { viewModel.startDuplexCall(it) },
                                appLanguage = state.appLanguage,
                                onStartGroupCall = { viewModel.startGroupMeshCall() },
                                onEndCall = { viewModel.endDuplexCall() },
                                onToggleMute = { viewModel.toggleCallMute() },
                                onToggleSpeaker = { viewModel.toggleCallSpeaker() },
                                onStartPtt = { viewModel.startPtt() },
                                onStopPtt = { viewModel.stopPtt() },
                                onToggleLiveMicrophone = { viewModel.toggleUseLiveMicrophone() },
                                onAcceptCall = { viewModel.acceptDuplexCall() },
                                onDeclineCall = { viewModel.declineDuplexCall() },
                                onTestMicLoopback = { viewModel.audioService.testMicAndSpeakerLoopback() },
                                onToggleHotspot = { viewModel.toggleOrConnectHotspot() },
                                onPushToOverride = { viewModel.setCallPushToOverride(it) },
                                onReplayLastRx = { viewModel.replayLastRxVoice(isCallMode = false) },
                                onReplayLastCallRx = { viewModel.replayLastRxVoice(isCallMode = true) },
                                onReplayLastPttRx = { viewModel.replayLastRxVoice(isCallMode = false) },
                                onHealHotspot = { viewModel.healHotspotLinkNow() },
                                onCycleVoiceClarity = { viewModel.cycleVoiceClarityPreset() },
                                onOpenLockScreenCover = { manualLockCoverRequested = true },
                            ) { peerId, peerName ->
                                viewModel.recordAndSendVoiceDropToPeer(peerId, peerName)
                            }
                            else -> {
                                if (state.isSimpleMode) {
                                    SettingsScreen(
                                        state = state,
                                        onToggleSimpleMode = { viewModel.toggleSimpleMode() },
                                        onToggleTransportMode = { viewModel.toggleInternetEnabled() },
                                        onSelectPhoneProfile = { viewModel.selectPhoneModel(it) },
                                        onAutoMatchHardware = { viewModel.autoSwitchToDetectedHardware() },
                                        onRegenerateNodeId = { viewModel.regenerateNodeId() },
                                        onUpdateCallsign = { viewModel.updateCallsign(it) },
                                        onUpdateMeshChannel = { viewModel.setMeshChannel(it) },
                                        onPingMeshNow = { viewModel.pingMeshNow() },
                                        onRequestGpsPermission = {
                                            permissionLauncher.launch(permissionsToRequest)
                                            viewModel.requestGpsUpdates()
                                        },
                                        onSetManualLocation = { lat, lon ->
                                            viewModel.setManualGpsLocation(lat, lon)
                                        },
                                        onOpenSetupWizard = { viewModel.openSetupWizard() },
                                        onVerifyRoot = { viewModel.verifyRootStatus() },
                                        onToggleInternetEnabled = { viewModel.toggleInternetEnabled() },
                                        onToggleSpokenCallerId = { viewModel.toggleSpokenCallerId() },
                                        onReconnectRelay = { viewModel.reconnectRelay() },
                                        onRotateKeys = { viewModel.rotateEncryptionKeys() },
                                        onToggleHotspot = { viewModel.toggleOrConnectHotspot() },
                                        onHostHotspot = { viewModel.hostHotspotExplicitly() },
                                        onJoinHotspot = { viewModel.joinPeerHotspotExplicitly() },
                                        onStopHotspot = { viewModel.stopHotspotExplicitly() },
                                        onForceGattReDiscovery = { viewModel.forceGattReDiscovery() },
                                        onToggleBroadcastCoarseLocation = { viewModel.toggleBroadcastCoarseLocationInBle() },
                                        onSelectLocationShareMode = { viewModel.setLocationShareMode(it) },
                                        onUpdateMeshPassphrase = { viewModel.setMeshPassphrase(it) },
                                        onToggleHotspotAutoPopup = { viewModel.setHotspotAutoPopupEnabled(it) },
                                        onRunMeshLinkSweep = { viewModel.runMeshLinkQualitySweep() },
                                        onSelectAutoCheckInInterval = { viewModel.setAutoCheckInIntervalMin(it) },
                                        onToggleLightMode = { viewModel.toggleLightMode() },
                                        onSelectAppTheme = { viewModel.setAppTheme(it) },
                                        onClaimOrRecoverUsername = { u, pin, rk, rec, cb ->
                                            viewModel.claimOrRecoverUsername(u, pin, rk, rec, cb)
                                        },
                                        onUpdateUsernameRelayUrl = { url, cb ->
                                            viewModel.setUsernameRelayUrl(url, cb)
                                        },
                                        onSyncAccountBackupNow = { cb ->
                                            viewModel.syncAccountBackupNow(cb)
                                        },
                                        onToggleBackgroundRelayService = { viewModel.toggleBackgroundRelayService() },
                                        onToggleVolumeButtonPtt = { viewModel.toggleVolumeButtonPtt() },
                                        onToggleRadioVoxBridge = { viewModel.toggleRadioVoxBridge() },
                                        onToggleDeadReckoning = { viewModel.toggleDeadReckoning() },
                                        onToggleBreadcrumbRecording = { viewModel.toggleBreadcrumbRecording() },
                                        onClearBreadcrumbTrail = { viewModel.clearBreadcrumbTrail() },
                                        onShareBreadcrumbTrail = { viewModel.shareBreadcrumbTrailToMesh() },
                                        onBroadcastOfflineMapTiles = { viewModel.broadcastOfflineMapTilesToPeers() },
                                        onTransmitKeyVerifyChirp = { viewModel.transmitUltrasonicKeyVerifyChirp() },
                                        onCycleMessageBurnTtl = { viewModel.cycleMessageBurnTtl() },
                                        onSetDuressPin = { pin -> viewModel.setDuressPin(pin) },
                                        onTriggerDuressWipe = { pin -> viewModel.verifyOrTriggerDuressWipe(pin) },
                                        onExportGpx = { viewModel.exportBreadcrumbsAsGpx() },
                                        onImportGpx = { xml -> viewModel.importBreadcrumbsFromGpx(xml) },
                                        onToggleWebPortal = { viewModel.toggleWebPortal() },
                                        onDeleteAccountInfo = { u, pin, rk -> viewModel.deleteAllAccountInformation(u, pin, rk) },
                                        onSetAppLanguage = { lang -> viewModel.setAppLanguage(lang) },
                                        onShareLocalFile = { name, _, bytes -> viewModel.shareLocalFileToPortal(name, bytes) != null },
                                        onDeleteSharedFile = { id -> viewModel.deleteSharedFileFromPortal(id) },
                                        onSendQuickStatus = { code -> viewModel.sendQuickStatus(code) },
                                        onToggleQuickStatusVoice = { viewModel.toggleQuickStatusVoice() },
                                        onSaveIceMedicalCard = { b, a, c -> viewModel.saveIceMedicalCard(b, a, "", c) },
                                        onSetDeadManSwitchMinutes = { m -> viewModel.setDeadManSwitchMinutes(m) },
                                        onCheckInDeadManSwitch = { viewModel.checkInDeadManSwitch() },
                                        onConfigureGeofence = { en, rad -> viewModel.configureGeofence(enabled = en, radiusMeters = rad) },
                                        onToggleExpeditionMode = { viewModel.toggleExpeditionMode() },
                                        onToggleExpeditionAutoBelow20 = { viewModel.toggleExpeditionAutoBelow20() },
                                        onExportTripArchive = { viewModel.exportTripArchiveJson() },
                                        onBuildInstantJoinQr = { viewModel.buildInstantJoinQrPayload() },
                                        onImportInstantJoinQr = { uri -> viewModel.importInstantJoinQrPayload(uri) },
                                        onSetDecoyPin = { pin -> viewModel.setDecoyPin(pin) },
                                        onTriggerDecoyMode = { pin -> viewModel.verifyOrEnterDecoyMode(pin) },
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .aeroCanvasBackground(p)
                                    ) {
                                        val subTabShape = tacticalButtonShape(false, p.isOriginal, p.isAero)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(
                                                    horizontal = if (p.isAero) 10.dp else 0.dp,
                                                    vertical = if (p.isAero) 6.dp else 0.dp,
                                                )
                                                .then(
                                                    if (p.isAero) {
                                                        Modifier.aeroGlassPanel(
                                                            palette = p,
                                                            cornerRadius = 18.dp,
                                                            tint = if (p.isDarkAero) Color(0xD40A2338) else Color(0xECFFFFFF),
                                                            borderColor = p.borderBright,
                                                        )
                                                    } else {
                                                        Modifier.background(p.card)
                                                    }
                                                )
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            val sysSelected = proSettingsSubTab == 0
                                            val rfSelected = proSettingsSubTab == 1
                                            val activeFg = if (p.isLight || p.isAero) Color.White else Color.Black
                                            Button(
                                                onClick = { proSettingsSubTab = 0 },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (sysSelected) {
                                                        if (p.isAero) Color.Transparent else p.emerald
                                                    } else p.surfaceVariant,
                                                    contentColor = if (sysSelected) activeFg else p.textSecondary,
                                                ),
                                                shape = subTabShape,
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .heightIn(min = 36.dp)
                                                    .then(
                                                        if (sysSelected && p.isAero) {
                                                            Modifier.aeroGlossOverlay(p, p.emerald, subTabShape, 14.dp)
                                                        } else Modifier
                                                    )
                                                    .testTag("pro_subtab_system"),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Tune,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = com.example.util.AppStrings.tr(state.appLanguage, "SYSTEM & CRYPTO", "ΣΥΣΤΗΜΑ & ΚΡΥΠΤΟ"),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Button(
                                                onClick = { proSettingsSubTab = 1 },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (rfSelected) {
                                                        if (p.isAero) Color.Transparent else p.amber
                                                    } else p.surfaceVariant,
                                                    contentColor = if (rfSelected) activeFg else p.textSecondary,
                                                ),
                                                shape = subTabShape,
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .heightIn(min = 36.dp)
                                                    .then(
                                                        if (rfSelected && p.isAero) {
                                                            Modifier.aeroGlossOverlay(p, p.amber, subTabShape, 14.dp)
                                                        } else Modifier
                                                    )
                                                    .testTag("pro_subtab_rflab"),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Terminal,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = com.example.util.AppStrings.tr(state.appLanguage, "RF LAB & KERNEL", "RF LAB & ΠΥΡΗΝΑΣ"),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        if (proSettingsSubTab == 0) {
                                            SettingsScreen(
                                                state = state,
                                                onToggleSimpleMode = { viewModel.toggleSimpleMode() },
                                                onToggleTransportMode = { viewModel.toggleInternetEnabled() },
                                                onSelectPhoneProfile = { viewModel.selectPhoneModel(it) },
                                                onAutoMatchHardware = { viewModel.autoSwitchToDetectedHardware() },
                                                onRegenerateNodeId = { viewModel.regenerateNodeId() },
                                                onUpdateCallsign = { viewModel.updateCallsign(it) },
                                                onUpdateMeshChannel = { viewModel.setMeshChannel(it) },
                                                onPingMeshNow = { viewModel.pingMeshNow() },
                                                onRequestGpsPermission = {
                                                    permissionLauncher.launch(permissionsToRequest)
                                                    viewModel.requestGpsUpdates()
                                                },
                                                onSetManualLocation = { lat, lon ->
                                                    viewModel.setManualGpsLocation(lat, lon)
                                                },
                                                onOpenSetupWizard = { viewModel.openSetupWizard() },
                                                onVerifyRoot = { viewModel.verifyRootStatus() },
                                                onToggleInternetEnabled = { viewModel.toggleInternetEnabled() },
                                                onToggleSpokenCallerId = { viewModel.toggleSpokenCallerId() },
                                                onReconnectRelay = { viewModel.reconnectRelay() },
                                                onRotateKeys = { viewModel.rotateEncryptionKeys() },
                                                onToggleHotspot = { viewModel.toggleOrConnectHotspot() },
                                                onHostHotspot = { viewModel.hostHotspotExplicitly() },
                                                onJoinHotspot = { viewModel.joinPeerHotspotExplicitly() },
                                                onStopHotspot = { viewModel.stopHotspotExplicitly() },
                                                onForceGattReDiscovery = { viewModel.forceGattReDiscovery() },
                                                onToggleBroadcastCoarseLocation = { viewModel.toggleBroadcastCoarseLocationInBle() },
                                                onSelectLocationShareMode = { viewModel.setLocationShareMode(it) },
                                                onUpdateMeshPassphrase = { viewModel.setMeshPassphrase(it) },
                                                onToggleHotspotAutoPopup = { viewModel.setHotspotAutoPopupEnabled(it) },
                                                onRunMeshLinkSweep = { viewModel.runMeshLinkQualitySweep() },
                                                onSelectAutoCheckInInterval = { viewModel.setAutoCheckInIntervalMin(it) },
                                                onToggleLightMode = { viewModel.toggleLightMode() },
                                                onSelectAppTheme = { viewModel.setAppTheme(it) },
                                                onClaimOrRecoverUsername = { u, pin, rk, rec, cb ->
                                                    viewModel.claimOrRecoverUsername(u, pin, rk, rec, cb)
                                                },
                                                onUpdateUsernameRelayUrl = { url, cb ->
                                                    viewModel.setUsernameRelayUrl(url, cb)
                                                },
                                                onSyncAccountBackupNow = { cb ->
                                                    viewModel.syncAccountBackupNow(cb)
                                                },
                                                onToggleBackgroundRelayService = { viewModel.toggleBackgroundRelayService() },
                                                onToggleVolumeButtonPtt = { viewModel.toggleVolumeButtonPtt() },
                                                onToggleRadioVoxBridge = { viewModel.toggleRadioVoxBridge() },
                                                onToggleDeadReckoning = { viewModel.toggleDeadReckoning() },
                                                onToggleBreadcrumbRecording = { viewModel.toggleBreadcrumbRecording() },
                                                onClearBreadcrumbTrail = { viewModel.clearBreadcrumbTrail() },
                                                onShareBreadcrumbTrail = { viewModel.shareBreadcrumbTrailToMesh() },
                                                onBroadcastOfflineMapTiles = { viewModel.broadcastOfflineMapTilesToPeers() },
                                                onTransmitKeyVerifyChirp = { viewModel.transmitUltrasonicKeyVerifyChirp() },
                                                onCycleMessageBurnTtl = { viewModel.cycleMessageBurnTtl() },
                                                onSetDuressPin = { pin -> viewModel.setDuressPin(pin) },
                                                onTriggerDuressWipe = { pin -> viewModel.verifyOrTriggerDuressWipe(pin) },
                                                onExportGpx = { viewModel.exportBreadcrumbsAsGpx() },
                                                onImportGpx = { xml -> viewModel.importBreadcrumbsFromGpx(xml) },
                                                onToggleWebPortal = { viewModel.toggleWebPortal() },
                                                onDeleteAccountInfo = { u, pin, rk -> viewModel.deleteAllAccountInformation(u, pin, rk) },
                                                onSetAppLanguage = { lang -> viewModel.setAppLanguage(lang) },
                                                onShareLocalFile = { name, _, bytes -> viewModel.shareLocalFileToPortal(name, bytes) != null },
                                                onDeleteSharedFile = { id -> viewModel.deleteSharedFileFromPortal(id) },
                                                onSendQuickStatus = { code -> viewModel.sendQuickStatus(code) },
                                                onToggleQuickStatusVoice = { viewModel.toggleQuickStatusVoice() },
                                                onSaveIceMedicalCard = { b, a, c -> viewModel.saveIceMedicalCard(b, a, "", c) },
                                                onSetDeadManSwitchMinutes = { m -> viewModel.setDeadManSwitchMinutes(m) },
                                                onCheckInDeadManSwitch = { viewModel.checkInDeadManSwitch() },
                                                onConfigureGeofence = { en, rad -> viewModel.configureGeofence(enabled = en, radiusMeters = rad) },
                                                onToggleExpeditionMode = { viewModel.toggleExpeditionMode() },
                                                onToggleExpeditionAutoBelow20 = { viewModel.toggleExpeditionAutoBelow20() },
                                                onExportTripArchive = { viewModel.exportTripArchiveJson() },
                                                onBuildInstantJoinQr = { viewModel.buildInstantJoinQrPayload() },
                                                onImportInstantJoinQr = { uri -> viewModel.importInstantJoinQrPayload(uri) },
                                                onSetDecoyPin = { pin -> viewModel.setDecoyPin(pin) },
                                                onTriggerDecoyMode = { pin -> viewModel.verifyOrEnterDecoyMode(pin) },
                                            )
                                        } else {
                                            RootBoosterScreen(
                                                state = state,
                                                onToggleBleCoded = { viewModel.toggleBleCodedPhy() },
                                                onToggleWifiStuffing = { viewModel.toggleWifiBeaconStuffing() },
                                                onToggleUnthrottledScan = { viewModel.toggleUnthrottledScan() },
                                                onToggleSystemlessMount = { viewModel.toggleSystemlessMount() },
                                                onSelectPhoneProfile = { profile -> viewModel.selectPhoneModel(profile) },
                                                onAutoSwitchToDetected = { viewModel.autoSwitchToDetectedHardware() },
                                                onForceOverrideMismatch = { viewModel.forceOverrideMismatch() },
                                                onOpenMismatchDialog = { viewModel.openMismatchDialog() },
                                                onVerifyRoot = { viewModel.verifyRootStatus() },
                                                onStartBleTx = { viewModel.startLiveBleTx() },
                                                onStopBleTx = { viewModel.stopLiveBleTx() },
                                                onToggleRxSniffer = { viewModel.toggleRawBleSniffer() },
                                                onClearBleDevices = { viewModel.clearScannedBleDevices() },
                                                onRequestPermissions = { permissionLauncher.launch(permissionsToRequest) },
                                                onForceGattReDiscovery = { viewModel.forceGattReDiscovery() },
                                                onToggleWifiAware = { viewModel.toggleWifiAware() },
                                                onToggleUltrasonicModem = { viewModel.toggleUltrasonicModem() },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Persistent Corner SOS Beacon Button (Start corner, hidden inside chat threads and active/ringing calls so it never blocks message/call controls)
                        var showSosConfirmDialog by remember { mutableStateOf(false) }
                        val isOnActiveCallScreen = (state.selectedTabIndex == 2) && (effectiveCallSignaling != CallSignalingState.IDLE)
                        if (!isInsideChatThread && !isOnActiveCallScreen) {
                            val sosPulseAnim = androidx.compose.animation.core.rememberInfiniteTransition(label = "sos_fab_pulse")
                            val sosGlow by sosPulseAnim.animateFloat(
                                initialValue = 0.55f,
                                targetValue = 1f,
                                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                    animation = androidx.compose.animation.core.tween(if (state.isSosActive) 450 else 1200),
                                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                                ),
                                label = "sos_glow"
                            )
                            val sosAccent = if (state.isSosActive) Color(0xFFFF1744) else Color(0xFFE51937)
                            val sosShape = RoundedCornerShape(50)
                            Surface(
                                color = sosAccent,
                                shape = sosShape,
                                shadowElevation = 12.dp,
                                border = BorderStroke(
                                    width = if (state.isSosActive) 3.dp else 2.dp,
                                    color = Color.White.copy(alpha = sosGlow)
                                ),
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(start = 14.dp, bottom = 14.dp)
                                    .clickable {
                                        if (state.isSosActive) {
                                            viewModel.toggleSosBeacon()
                                        } else {
                                            showSosConfirmDialog = true
                                        }
                                    }
                                    .testTag("sos_corner_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Sos,
                                        contentDescription = "SOS Beacon",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Text(
                                        text = if (state.isSosActive) {
                                            com.example.util.AppStrings.tr(state.appLanguage, "⚠ SOS BROADCASTING", "⚠ ΕΚΠΟΜΠΗ SOS")
                                        } else {
                                            "⚠ SOS"
                                        },
                                        color = Color.White,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }

                        if (showSosConfirmDialog) {
                            AlertDialog(
                                onDismissRequest = { showSosConfirmDialog = false },
                                containerColor = p.card,
                                shape = RoundedCornerShape(16.dp),
                                title = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (p.isAero) {
                                            AeroGlossBadgeIcon(
                                                imageVector = Icons.Default.Sos,
                                                accentColor = p.red,
                                                contentDescription = "Emergency SOS",
                                                badgeSize = 30.dp,
                                                iconSize = 16.dp,
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Sos,
                                                contentDescription = null,
                                                tint = p.red,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                        Text(
                                            text = com.example.util.AppStrings.tr(state.appLanguage, "BROADCAST EMERGENCY SOS?", "ΕΚΠΟΜΠΗ ΕΚΤΑΚΤΟΥ SOS;"),
                                            color = p.red,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 14.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                },
                                text = {
                                    Text(
                                        text = com.example.util.AppStrings.tr(
                                            state.appLanguage,
                                            "This will immediately broadcast an SOS alert and your current GPS coordinates (if locked) to all nearby mesh peers and drop an SOS waypoint on the tactical map.",
                                            "Θα σταλεί άμεσα σήμα κινδύνου SOS με τις συντεταγμένες GPS σε όλους τους κοντινούς κόμβους και θα προστεθεί πινέζα SOS στον χάρτη."
                                        ),
                                        color = p.textPrimary,
                                        fontSize = 12.sp
                                    )
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            showSosConfirmDialog = false
                                            viewModel.toggleSosBeacon()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = p.red,
                                            contentColor = Color.White
                                        ),
                                        modifier = Modifier.testTag("sos_confirm_dialog_button")
                                    ) {
                                        Text(
                                            text = com.example.util.AppStrings.tr(state.appLanguage, "TRIGGER SOS NOW", "ΕΚΠΟΜΠΗ SOS ΤΩΡΑ"),
                                            fontWeight = FontWeight.ExtraBold,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        )
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showSosConfirmDialog = false }) {
                                        Text(com.example.util.AppStrings.tr(state.appLanguage, "CANCEL", "ΑΚΥΡΩΣΗ"), color = p.textSecondary)
                                    }
                                }
                            )
                        }
                    }
                }

                // Decoy / Panic Mode Benign Hiking Trail Log Overlay
                if (state.isDecoyModeActive) {
                    var exitTapCount by remember { mutableIntStateOf(0) }
                    Surface(
                        color = Color(0xFFF6F8F4),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("decoy_hiking_log_overlay")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .navigationBarsPadding()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        exitTapCount += 1
                                        if (exitTapCount >= 3) {
                                            exitTapCount = 0
                                            viewModel.exitDecoyMode()
                                        }
                                    },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = com.example.util.AppStrings.tr(state.appLanguage, "TrailNote Compass & Hike Log", "Ημερολόγιο Διαδρομών & Πυξίδα"),
                                        color = Color(0xFF1E3A2F),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 20.sp
                                    )
                                    Text(
                                        text = com.example.util.AppStrings.tr(state.appLanguage, "Offline Step & Elevation Journal", "Καταγραφή Βημάτων & Υψομέτρου"),
                                        color = Color(0xFF5A6B62),
                                        fontSize = 12.sp
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.Terrain,
                                    contentDescription = "Trail Journal",
                                    tint = Color(0xFF2E6F40),
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                            HorizontalDivider(color = Color(0xFFD6E0D8))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(com.example.util.AppStrings.tr(state.appLanguage, "Today's Trail Summary", "Σημερινή Σύνοψη"), fontWeight = FontWeight.Bold, color = Color(0xFF1E3A2F))
                                    Text("${com.example.util.AppStrings.tr(state.appLanguage, "Compass Heading", "Πυξίδα")}: ${state.deviceHeadingDeg.toInt()}°", color = Color(0xFF3E5248), fontSize = 13.sp)
                                    Text("${com.example.util.AppStrings.tr(state.appLanguage, "Steps Logged", "Βήματα")}: ${state.stepCount}", color = Color(0xFF3E5248), fontSize = 13.sp)
                                    if (state.hasBarometerSensor && state.barometricPressureHpa != null) {
                                        Text(
                                            text = "${com.example.util.AppStrings.tr(state.appLanguage, "Barometer", "Βαρόμετρο")}: %.1f hPa (${com.example.util.AppStrings.weatherTrendLabel(state.appLanguage, state.weatherTrendLabel)})".format(state.barometricPressureHpa),
                                            color = Color(0xFF3E5248),
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Recent Trail Notes", fontWeight = FontWeight.Bold, color = Color(0xFF1E3A2F))
                                    Text("• Pine Ridge Loop - Clear trail, shade near mile 2.4.", color = Color(0xFF4A5B52), fontSize = 13.sp)
                                    Text("• Creek Crossing - Water flowing well, refill bottles.", color = Color(0xFF4A5B52), fontSize = 13.sp)
                                    Text("• Summit Overlook - Light breeze, great sunset view.", color = Color(0xFF4A5B52), fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }

                // Tactical Landing & Device Setup Wizard Screen
                if (state.showSetupWizard) {
                    TacticalDeviceWizardScreen(
                        initialCallsign = state.operatorCallsign,
                        initialNodeId = state.localNodeId,
                        initialSelectedProfile = state.selectedPhoneProfile,
                        detectedHardware = state.detectedHardware,
                        initialPureOffGrid = !state.isInternetEnabled,
                        initialInternetBridge = state.isInternetEnabled,
                        onCompleteWizard = { callsign, nodeId, profile, pureOffGrid, internetBridge ->
                            viewModel.saveDeviceProfile(callsign, nodeId, profile, pureOffGrid, internetBridge)
                            viewModel.closeSetupWizard()
                        },
                        onDismiss = { viewModel.closeSetupWizard() },
                        onSelectTransportPosture = { pureOffGrid ->
                            viewModel.toggleInternetEnabled(enabled = !pureOffGrid)
                        },
                        currentAppTheme = state.appTheme,
                        onSelectAppTheme = { selectedTheme ->
                            viewModel.setAppTheme(selectedTheme)
                        },
                        initialClaimedUsername = state.claimedUsername,
                        initialRecoveryKey = "",
                        usernameAuthStatus = state.usernameAuthStatus,
                        isReservingUsername = state.isReservingUsername,
                        isUsernameOnHold = state.isUsernameOnHold,
                        onClaimOrRecoverUsername = { u, pin, rk, rec, cb ->
                            viewModel.claimOrRecoverUsername(u, pin, rk, rec, cb)
                        },
                    )
                }

                // Hotspot Auto-Join Popup with 1-Tap "Disable Hotspot Popups" Option
                val hotspotPrompt = state.pendingHotspotPrompt
                if ((hotspotPrompt != null) && state.isHotspotAutoPopupEnabled) {
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissHotspotPrompt() },
                        containerColor = p.card,
                        shape = RoundedCornerShape(14.dp),
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.WifiTethering,
                                    contentDescription = null,
                                    tint = p.cyan,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = com.example.util.AppStrings.tr(state.appLanguage, "SWARM HOTSPOT DETECTED", "ΕΝΤΟΠΙΣΤΗΚΕ HOTSPOT ΟΜΑΔΑΣ"),
                                    color = p.cyan,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = com.example.util.AppStrings.tr(
                                        state.appLanguage,
                                        "Peer ${hotspotPrompt.hostNodeId} is hosting a high-speed off-grid Wi-Fi Direct / Swarm Hotspot (${hotspotPrompt.ssid} @ ${hotspotPrompt.hostIp}).",
                                        "Ο κόμβος ${hotspotPrompt.hostNodeId} φιλοξενεί γρήγορο Hotspot Wi-Fi Direct (${hotspotPrompt.ssid} @ ${hotspotPrompt.hostIp})."
                                    ),
                                    color = p.textPrimary,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    text = com.example.util.AppStrings.tr(
                                        state.appLanguage,
                                        "You can connect for high-speed UDP voice, dismiss for now, or disable automatic hotspot popups to stay silently on Bluetooth.",
                                        "Μπορείς να συνδεθείς για φωνή UDP υψηλής ταχύτητας, να το αγνοήσεις ή να απενεργοποιήσεις τις αυτόματες ειδοποιήσεις Hotspot."
                                    ),
                                    color = p.textSecondary,
                                    fontSize = 11.sp,
                                )
                                OutlinedButton(
                                    onClick = { viewModel.setHotspotAutoPopupEnabled(enabled = false) },
                                    border = BorderStroke(1.dp, p.red),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.NotificationsOff,
                                        contentDescription = null,
                                        tint = p.red,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = com.example.util.AppStrings.tr(state.appLanguage, "DISABLE HOTSPOT POPUPS", "ΑΠΕΝΕΡΓΟΠΟΙΗΣΗ ΕΙΔΟΠΟΙΗΣΕΩΝ"),
                                        color = p.red,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { viewModel.confirmHotspotPromptJoin() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = p.emerald,
                                    contentColor = if (p.isLight || p.isAero) Color.White else Color.Black,
                                ),
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    text = com.example.util.AppStrings.tr(state.appLanguage, "CONNECT NOW", "ΣΥΝΔΕΣΗ ΤΩΡΑ"),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.dismissHotspotPrompt() }) {
                                Text(
                                    text = com.example.util.AppStrings.tr(state.appLanguage, "NOT NOW", "ΟΧΙ ΤΩΡΑ"),
                                    color = p.textSecondary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        },
                    )
                }

                // Hardware Mismatch Warning Dialog (Risk Modal)
                val mismatch = state.hardwareMismatchReport
                if (state.showMismatchWarningDialog && mismatch != null) {
                    com.example.ui.components.HardwareMismatchWarningDialog(
                        report = mismatch,
                        selectedProfile = state.selectedPhoneProfile,
                        onAutoSwitchToDetected = { viewModel.autoSwitchToDetectedHardware() },
                        onForceOverrideAnyway = { viewModel.forceOverrideMismatch() },
                    ) { viewModel.closeMismatchDialog() }
                }

                // Manual / Pocket Call Cover Overlay (when device is unlocked and user tapped LOCK COVER)
                if ((effectiveCallSignaling != CallSignalingState.IDLE) && manualLockCoverRequested) {
                    LockScreenCallCoverOverlay(
                        state = state,
                        duplexCallState = state.duplexCallState,
                        isDeviceLocked = false,
                        onAcceptCall = { viewModel.acceptDuplexCall() },
                        onDeclineCall = {
                            manualLockCoverRequested = false
                            viewModel.declineDuplexCall()
                        },
                        onEndCall = {
                            manualLockCoverRequested = false
                            viewModel.endDuplexCall()
                        },
                        onToggleMute = { viewModel.toggleCallMute() },
                        onToggleSpeaker = { viewModel.toggleCallSpeaker() },
                        onDismissToApp = {
                            manualLockCoverRequested = false
                            viewModel.setTab(2)
                        },
                    )
                }

                // Offline Username Hold / Online Confirmation / Conflict Popup
                if (state.showUsernamePopup && state.usernamePopupMessage.isNotBlank()) {
                    val isTakenAlert = state.usernamePopupTitle.contains("TAKEN", ignoreCase = true)
                    val isConfirmedAlert = state.usernamePopupTitle.contains("CONFIRMED", ignoreCase = true)
                    val accentColor = when {
                        isTakenAlert -> p.red
                        isConfirmedAlert -> p.emerald
                        else -> p.amber
                    }
                    val icon = when {
                        isTakenAlert -> Icons.Default.Warning
                        isConfirmedAlert -> Icons.Default.VerifiedUser
                        else -> Icons.Default.HourglassTop
                    }
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissUsernamePopup() },
                        containerColor = p.card,
                        shape = RoundedCornerShape(16.dp),
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = state.usernamePopupTitle.ifBlank { "USERNAME STATUS" },
                                    color = accentColor,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        },
                        text = {
                            Text(
                                text = state.usernamePopupMessage,
                                color = p.textPrimary,
                                fontSize = 13.sp,
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = { viewModel.dismissUsernamePopup() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentColor,
                                    contentColor = if (p.isLight || p.isAero) Color.White else Color.Black,
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("username_hold_popup_ok_button"),
                            ) {
                                Text(
                                    text = "OK",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        },
                        dismissButton = if (state.accountRecoveryKey.isNotBlank() &&
                            (state.usernamePopupTitle == "USERNAME RESERVED!" || state.usernamePopupTitle == "USERNAME ON HOLD")
                        ) {
                            {
                                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                                TextButton(
                                    onClick = {
                                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.accountRecoveryKey))
                                    }
                                ) {
                                    Text(
                                        text = "COPY KEY",
                                        color = p.amber,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        } else null,
                        modifier = Modifier.testTag("username_hold_popup_dialog"),
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            keyguardLockedFlow.value = isKeyguardCurrentlyLocked()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        keyguardLockedFlow.value = isKeyguardCurrentlyLocked()
        handleNotificationIntent(intent)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val state = viewModel.uiState.value
        val isPttHardwareKey = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == android.view.KeyEvent.KEYCODE_HEADSETHOOK
        if (isPttHardwareKey && state.isVolumeButtonPttEnabled && !isKeyguardCurrentlyLocked()) {
            if (event?.repeatCount == 0 && !state.isPttPressed) {
                viewModel.startPtt()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val state = viewModel.uiState.value
        val isPttHardwareKey = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == android.view.KeyEvent.KEYCODE_HEADSETHOOK
        if (isPttHardwareKey && state.isVolumeButtonPttEnabled && !isKeyguardCurrentlyLocked()) {
            if (state.isPttPressed) {
                viewModel.stopPtt()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun updateLockScreenWindowFlags(allowOnLockScreen: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(allowOnLockScreen)
                setTurnScreenOn(allowOnLockScreen)
            } else {
                @Suppress("DEPRECATION")
                if (allowOnLockScreen) {
                    window.addFlags(
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    )
                } else {
                    window.clearFlags(
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    )
                }
            }
            if (allowOnLockScreen) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } catch (_: Exception) {}
    }

    private fun requestDeviceUnlock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (km?.isKeyguardLocked == true) {
                km.requestDismissKeyguard(
                    this,
                    object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() {
                            keyguardLockedFlow.value = false
                        }

                        override fun onDismissCancelled() {
                            keyguardLockedFlow.value = isKeyguardCurrentlyLocked()
                        }

                        override fun onDismissError() {
                            keyguardLockedFlow.value = isKeyguardCurrentlyLocked()
                        }
                    }
                )
            } else {
                keyguardLockedFlow.value = false
            }
        } else {
            keyguardLockedFlow.value = isKeyguardCurrentlyLocked()
        }
    }

    private fun isKeyguardCurrentlyLocked(): Boolean {
        return try {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.isKeyguardLocked == true
        } catch (_: Exception) {
            false
        }
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return
        val threadId = intent.getStringExtra(TacticalNotificationManager.EXTRA_OPEN_THREAD_ID)
        if (!threadId.isNullOrBlank()) {
            viewModel.openThreadFromNotification(threadId)
            intent.removeExtra(TacticalNotificationManager.EXTRA_OPEN_THREAD_ID)
            return
        }
        val callAction = intent.getStringExtra(TacticalNotificationManager.EXTRA_CALL_ACTION)
        if (callAction == TacticalNotificationManager.ACTION_ACCEPT_CALL) {
            TacticalNotificationManager.cancelCallNotification(this)
            viewModel.setTab(2)
            viewModel.acceptDuplexCall()
            intent.removeExtra(TacticalNotificationManager.EXTRA_CALL_ACTION)
            return
        }
        if (intent.getBooleanExtra(TacticalNotificationManager.EXTRA_OPEN_VOICE_TAB, false)) {
            viewModel.setTab(2)
            intent.removeExtra(TacticalNotificationManager.EXTRA_OPEN_VOICE_TAB)
        }
    }
}

@Composable
private fun LockedPrivacyGuardScreen(
    onUnlockDevice: () -> Unit,
) {
    val p = LocalPulsePalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .aeroCanvasBackground(p)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp)
            .testTag("locked_privacy_guard_screen"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (p.isAero) {
                AeroGlossBadgeIcon(
                    imageVector = Icons.Default.Lock,
                    accentColor = p.amber,
                    contentDescription = "Lock Screen Restricted",
                    badgeSize = 84.dp,
                    iconSize = 42.dp,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(p.amberDim)
                        .padding(18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Lock Screen Restricted",
                        tint = p.amber,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
            Text(
                text = "ONLY CALLS ARE ACCESSIBLE ON LOCK SCREEN",
                color = p.amber,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Map, tactical chats, RF telemetry, and settings are hidden while your device is locked. Unlock your device to access the full console.",
                color = p.textSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onUnlockDevice,
                colors = ButtonDefaults.buttonColors(
                    containerColor = p.emerald,
                    contentColor = if (p.isLight || p.isAero) Color.White else Color.Black,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
            ) {
                Icon(Icons.Default.LockOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "UNLOCK DEVICE",
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
