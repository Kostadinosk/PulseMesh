// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
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
import com.kostadinosk.pulsemesh.service.CallSignalingState
import com.kostadinosk.pulsemesh.service.TacticalNotificationManager
import com.kostadinosk.pulsemesh.ui.components.AeroGlossBadgeIcon
import com.kostadinosk.pulsemesh.ui.components.AeroNavOrbIcon
import com.kostadinosk.pulsemesh.ui.components.AeroTabIconKind
import com.kostadinosk.pulsemesh.ui.components.AppLockOverlay
import com.kostadinosk.pulsemesh.ui.components.LockScreenCallCoverOverlay
import com.kostadinosk.pulsemesh.ui.components.PersistentMiniCallBar
import com.kostadinosk.pulsemesh.ui.components.PulseMeshTopBar
import com.kostadinosk.pulsemesh.ui.components.ConnectShareSheet
import com.kostadinosk.pulsemesh.ui.components.EmergencySosSheet
import com.kostadinosk.pulsemesh.ui.components.SafetyNumberSheet
import com.kostadinosk.pulsemesh.ui.components.TacticalMap
import com.kostadinosk.pulsemesh.ui.components.TelemetryStatusDrawer
import com.kostadinosk.pulsemesh.ui.components.TelemetryHudCard
import com.kostadinosk.pulsemesh.ui.screens.*
import androidx.compose.ui.window.Dialog
import com.kostadinosk.pulsemesh.ui.theme.*
import com.kostadinosk.pulsemesh.util.AppStrings
import com.kostadinosk.pulsemesh.viewmodel.MeshViewModel
import kotlinx.coroutines.flow.MutableStateFlow

data class NavTabItem(
    val title: String,
    val icon: ImageVector,
)

class MainActivity : FragmentActivity() {

    private val viewModel: MeshViewModel by viewModels()
    private val keyguardLockedFlow = MutableStateFlow(false)
    private val hardwarePttController by lazy {
        com.kostadinosk.pulsemesh.voice.HardwarePttController(
            isHardwarePttEnabled = { viewModel.uiState.value.isVolumeButtonPttEnabled },
            isKeyguardLocked = { isKeyguardCurrentlyLocked() },
            isCurrentlyTransmitting = { viewModel.uiState.value.isPttPressed },
            onStartPtt = { viewModel.startPtt() },
            onStopPtt = { viewModel.stopPtt() }
        )
    }

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

            PulseMeshTheme(
                appTheme = state.appTheme,
                themePreset = state.themePreset,
                themeBrightness = state.themeBrightness
            ) {
                val p = LocalPulsePalette.current
                val tokens = com.kostadinosk.pulsemesh.ui.theme.LocalPulseTokens.current
                val toolkitState by viewModel.emergencyToolkit.state.collectAsState()
                var showQrScannerDialog by remember { mutableStateOf(false) }
                var showTelemetryDrawer by remember { mutableStateOf(false) }
                var showConnectHub by remember { mutableStateOf(false) }
                var showEmergencySosSheet by remember { mutableStateOf(false) }
                var safetyNumberPeer by remember { mutableStateOf<Pair<String, String>?>(null) }
                var activeSettingsDest by remember { mutableStateOf<com.kostadinosk.pulsemesh.ui.screens.settings.SettingsDestination?>(null) }
                var activeMapLayerStyle by remember { mutableStateOf(com.kostadinosk.pulsemesh.map.MapLayerStyle.DARK) }
                var pendingBackupPassphrase by remember { mutableStateOf("") }

                val backupExportLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/octet-stream")
                ) { uri ->
                    if (uri != null && pendingBackupPassphrase.length >= 8) {
                        val res = viewModel.exportEncryptedBackupEnvelope(pendingBackupPassphrase)
                        pendingBackupPassphrase = ""
                        res.onSuccess { envelope ->
                            runCatching {
                                contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(envelope) }
                            }
                            android.widget.Toast.makeText(this@MainActivity, "Exported encrypted .pmbackup", android.widget.Toast.LENGTH_SHORT).show()
                        }.onFailure { err ->
                            android.widget.Toast.makeText(this@MainActivity, err.message ?: "Backup export failed", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                val backupImportLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null && pendingBackupPassphrase.length >= 8) {
                        val raw = runCatching {
                            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        }.getOrNull().orEmpty()
                        val res = viewModel.importEncryptedBackupEnvelope(raw, pendingBackupPassphrase)
                        pendingBackupPassphrase = ""
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            res.getOrElse { it.message ?: "Backup restore failed" },
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }

                val mapPackPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        val resolvedName = runCatching {
                            contentResolver.query(
                                uri,
                                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                                null,
                                null,
                                null
                            )?.use { cur ->
                                if (cur.moveToFirst()) cur.getString(0) else null
                            }
                        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
                        val pack = com.kostadinosk.pulsemesh.map.OfflineMapPackManager.importPackFromUri(
                            this@MainActivity,
                            uri,
                            resolvedName
                        )
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            if (pack != null) "Imported map pack ${pack.fileName}" else "Invalid .mbtiles / .pmtiles map pack",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                val gpxKmlPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        val raw = runCatching {
                            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        }.getOrNull().orEmpty()
                        val (_, msg) = viewModel.importGpxOrKmlContent(raw)
                        android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }

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
                    viewModel.onPermissionsResult(results)
                }

                LaunchedEffect(Unit) {
                    if (!isKeyguardCurrentlyLocked()) {
                        permissionLauncher.launch(permissionsToRequest)
                    }
                    val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (hasFine || hasCoarse) {
                        viewModel.requestGpsUpdates()
                    }
                }

                // Android 14-17 Microphone FGS Safety: Only permit AudioRecord capture while app is foregrounded
                DisposableEffect(this@MainActivity) {
                    val observer = LifecycleEventObserver { _, event ->
                        keyguardLockedFlow.value = isKeyguardCurrentlyLocked()
                        when (event) {
                            Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                                viewModel.setAppInForeground(inForeground = true)
                                viewModel.appLockManager.onAppForegrounded()
                            }
                            Lifecycle.Event.ON_STOP -> {
                                viewModel.setAppInForeground(inForeground = false)
                                viewModel.appLockManager.onAppBackgrounded()
                            }
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

                val isAppLocked by viewModel.appLockManager.isLocked.collectAsState()
                if (isAppLocked && effectiveCallSignaling == CallSignalingState.IDLE) {
                    var biometricError by remember { mutableStateOf("") }
                    val bioAvail = remember { viewModel.appLockManager.canUseBiometrics(this@MainActivity) }
                    AppLockOverlay(
                        hasPinConfigured = state.hasAppLockPin,
                        biometricAvailable = bioAvail && state.isBiometricUnlockEnabled,
                        onUnlockWithPin = { pin -> viewModel.appLockManager.unlockWithPin(pin) },
                        onRequestBiometric = {
                            viewModel.appLockManager.launchBiometricPrompt(
                                activity = this@MainActivity,
                                onSuccess = { biometricError = "" },
                                onError = { err -> biometricError = err }
                            )
                        },
                        errorHint = biometricError
                    )
                    return@PulseMeshTheme
                }

                // BackHandler navigation behavior (start tab is Chats = 1)
                BackHandler(enabled = (activeSettingsDest != null) || (state.selectedNode != null) || (state.selectedTabIndex != 1)) {
                    when {
                        activeSettingsDest != null -> activeSettingsDest = null
                        state.selectedNode != null -> viewModel.selectNode(null)
                        state.selectedTabIndex != 1 -> viewModel.setTab(1)
                    }
                }

                // Clean 4-Tab practical order: Chats (default start), Map, Voice, Settings
                val tabs = remember(state.appLanguage) {
                    listOf(
                        1 to NavTabItem(AppStrings.resolve(this@MainActivity, state.appLanguage, R.string.tab_chats), Icons.AutoMirrored.Filled.Chat),
                        0 to NavTabItem(AppStrings.resolve(this@MainActivity, state.appLanguage, R.string.tab_map), Icons.Default.Map),
                        2 to NavTabItem(AppStrings.resolve(this@MainActivity, state.appLanguage, R.string.tab_voice), Icons.Default.Radio),
                        3 to NavTabItem(AppStrings.resolve(this@MainActivity, state.appLanguage, R.string.tab_settings), Icons.Default.Settings),
                    )
                }

                val density = LocalDensity.current
                val isImeVisible = WindowInsets.ime.getBottom(density) > 0
                val isInsideChatThread = (state.selectedTabIndex == 1) && (!state.selectedThreadId.isNullOrBlank())

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isWideTablet = maxWidth >= 720.dp

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
                                onOpenTelemetryDrawer = { showTelemetryDrawer = true },
                                onOpenConnectHub = { showConnectHub = true },
                                onOpenSosDrawer = { showEmergencySosSheet = true },
                            )
                            state.availableUpdate?.let { upd ->
                                Surface(
                                    color = p.emerald.copy(alpha = 0.16f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("app_update_banner")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = AppStrings.resolve(this@MainActivity, state.appLanguage, R.string.update_available_title, upd.latestVersion),
                                                color = p.emerald,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                            val bannerSubtitle = if (
                                                state.apkUpdateInstallState.stage != com.kostadinosk.pulsemesh.service.ApkInstallStage.IDLE &&
                                                state.apkUpdateInstallState.message.isNotBlank()
                                            ) {
                                                state.apkUpdateInstallState.message
                                            } else {
                                                upd.releaseNotesSnippet
                                            }
                                            if (bannerSubtitle.isNotBlank()) {
                                                Text(
                                                    text = bannerSubtitle,
                                                    color = p.textSecondary,
                                                    fontSize = 10.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = { viewModel.downloadAndInstallVerifiedUpdate() },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = p.emerald,
                                                contentColor = Color.Black
                                            ),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text(
                                                text = AppStrings.resolve(this@MainActivity, state.appLanguage, R.string.update_banner_button),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        IconButton(
                                            onClick = { viewModel.dismissAvailableUpdate() },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Text("✕", color = p.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
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
                        if (!isWideTablet && !isImeVisible && !isInsideChatThread) {
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
                                tabs.forEach { (tabId, tabItem) ->
                                    val (title, icon) = tabItem
                                    val isSelected = state.selectedTabIndex == tabId
                                    val tabColor = if (isSelected) tokens.accentPrimary else p.textMuted
                                    val aeroKind = when (tabId) {
                                        0 -> AeroTabIconKind.MAP
                                        1 -> AeroTabIconKind.CHATS
                                        2 -> AeroTabIconKind.VOICE
                                        else -> AeroTabIconKind.SETTINGS
                                    }
                                    NavigationBarItem(
                                        selected = isSelected,
                                        onClick = {
                                            if (tabId == 3) activeSettingsDest = null
                                            viewModel.setTab(tabId)
                                        },
                                        icon = {
                                            BadgedBox(
                                                badge = {
                                                    if (tabId == 1 && totalUnreadChats > 0) {
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
                                                        accentColor = tokens.accentPrimary,
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
                                            Text(
                                                text = title,
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
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding)
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        if (isWideTablet) {
                            NavigationRail(
                                containerColor = p.card,
                                contentColor = p.textSecondary,
                                modifier = Modifier.testTag("tablet_navigation_rail")
                            ) {
                                Spacer(modifier = Modifier.weight(1f))
                                tabs.forEach { (tabId, tabItem) ->
                                    val (title, icon) = tabItem
                                    val isSelected = state.selectedTabIndex == tabId
                                    NavigationRailItem(
                                        selected = isSelected,
                                        onClick = {
                                            if (tabId == 3) activeSettingsDest = null
                                            viewModel.setTab(tabId)
                                        },
                                        icon = { Icon(icon, contentDescription = title) },
                                        label = { Text(title, fontSize = 10.sp) }
                                    )
                                }
                                Spacer(modifier = Modifier.weight(1f))
                            }
                            VerticalDivider(color = p.border)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
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
                                    onExportGpx = { viewModel.exportBreadcrumbsAsGpx() },
                                    onToggleCotBridge = { enabled -> viewModel.setCotBridgeEnabled(enabled) },
                                    isCotBridgeEnabled = state.isCotBridgeEnabled,
                                    onImportMapPack = { mapPackPicker.launch(arrayOf("*/*")) },
                                    onImportGpxKml = { gpxKmlPicker.launch(arrayOf("*/*")) },
                                    externalLayerStyle = activeMapLayerStyle,
                                    onLayerStyleChanged = { activeMapLayerStyle = it },
                                )
                                com.kostadinosk.pulsemesh.ui.screens.map.TacticalMapOverlaySheet(
                                    state = state,
                                    selectedLayerStyle = activeMapLayerStyle,
                                    onSelectLayerStyle = { activeMapLayerStyle = it },
                                    onSelectPeer = { viewModel.selectNode(it) },
                                    onImportMapPack = { mapPackPicker.launch(arrayOf("*/*")) },
                                    onImportGpxKml = { gpxKmlPicker.launch(arrayOf("*/*")) },
                                    onExportGpx = { viewModel.exportBreadcrumbsAsGpx() },
                                    onToggleCotBridge = { enabled -> viewModel.setCotBridgeEnabled(enabled) },
                                    onBroadcastGpsPin = { viewModel.pingMeshNow() }
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
                            1 -> if (isWideTablet) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("tablet_two_pane_chats")
                                ) {
                                    MeshChatScreen(
                                        state = state.copy(selectedThreadId = null),
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
                                        onCreateGroup = { groupName, memberIds -> viewModel.createMeshGroup(groupName, memberIds) },
                                        onVerifyFingerprint = { peerId -> viewModel.acceptChangedPeerKey(peerId) },
                                        onOpenSafetyNumber = { peerId, peerName -> safetyNumberPeer = peerId to peerName },
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
                                        onOpenWebPortal = {
                                            if (!state.webPortalStatus.isRunning) viewModel.toggleWebPortal()
                                            viewModel.setTab(3)
                                        },
                                        modifier = Modifier.weight(0.42f).fillMaxHeight(),
                                    )
                                    VerticalDivider(color = p.border)
                                    val activeSplitThread = state.selectedThreadId
                                        ?: state.conversations.firstOrNull()?.threadId
                                        ?: "channel_primary"
                                    MeshChatScreen(
                                        state = state.copy(selectedThreadId = activeSplitThread),
                                        onSendMessage = { viewModel.sendMessageInThread(activeSplitThread, it) },
                                        onBroadcastGpsPin = { viewModel.pingMeshNow() },
                                        onSelectThread = { viewModel.selectThread(it) },
                                        onBackToList = { viewModel.selectThread(null) },
                                        onSearchQueryChange = { viewModel.setSearchQuery(it) },
                                        onStartCall = { node ->
                                            viewModel.startDuplexCall(node)
                                            viewModel.setTab(2)
                                        },
                                        onRotateKeys = { viewModel.rotateEncryptionKeys() },
                                        onCreateGroup = { groupName, memberIds -> viewModel.createMeshGroup(groupName, memberIds) },
                                        onVerifyFingerprint = { peerId -> viewModel.acceptChangedPeerKey(peerId) },
                                        onOpenSafetyNumber = { peerId, peerName -> safetyNumberPeer = peerId to peerName },
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
                                        onOpenWebPortal = {
                                            if (!state.webPortalStatus.isRunning) viewModel.toggleWebPortal()
                                            viewModel.setTab(3)
                                        },
                                        onStartReply = { pkt -> viewModel.startReplyTo(pkt) },
                                        onClearReply = { viewModel.clearReply() },
                                        onStartEdit = { pkt -> viewModel.startEditPacket(pkt) },
                                        onClearEdit = { viewModel.clearEditPacket() },
                                        onCommitEdit = { tid, pid, txt -> viewModel.sendEditedMessage(tid, pid, txt) },
                                        onUnsendMessage = { pid -> viewModel.unsendMessage(pid) },
                                        onTypingChanged = { typing -> viewModel.sendTypingStatus(activeSplitThread, typing) },
                                        onSearchActiveThread = { q -> viewModel.searchActiveThread(q) },
                                        onSendFileBytes = { name, mime, bytes -> viewModel.sendFileAttachment(activeSplitThread, name, mime, bytes) },
                                        onPauseFileTransfer = { id -> viewModel.pauseFileTransfer(id) },
                                        onResumeFileTransfer = { id -> viewModel.resumeFileTransfer(id) },
                                        onAddGroupMember = { gid, mid -> viewModel.addGroupMember(gid, mid) },
                                        onRemoveGroupMember = { gid, mid -> viewModel.removeGroupMember(gid, mid) },
                                        onToggleGroupAdmin = { gid, mid -> viewModel.toggleGroupAdmin(gid, mid) },
                                        onRotateGroupKey = { gid -> viewModel.rotateGroupKeyById(gid) },
                                        onBuildGroupInviteQr = { gid -> viewModel.buildGroupInviteQr(gid) },
                                        onSendVoiceNoteClip = { bytes, dur -> viewModel.sendVoiceNoteBytes(activeSplitThread, bytes, dur) },
                                        modifier = Modifier.weight(0.58f).fillMaxHeight(),
                                    )
                                }
                            } else {
                                MeshChatScreen(
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
                                        onOpenSafetyNumber = { peerId, peerName -> safetyNumberPeer = peerId to peerName },
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
                                    onOpenWebPortal = {
                                        if (!state.webPortalStatus.isRunning) viewModel.toggleWebPortal()
                                        viewModel.setTab(3)
                                    },
                                    onStartReply = { pkt -> viewModel.startReplyTo(pkt) },
                                    onClearReply = { viewModel.clearReply() },
                                    onStartEdit = { pkt -> viewModel.startEditPacket(pkt) },
                                    onClearEdit = { viewModel.clearEditPacket() },
                                    onCommitEdit = { tid, pid, txt -> viewModel.sendEditedMessage(tid, pid, txt) },
                                    onUnsendMessage = { pid -> viewModel.unsendMessage(pid) },
                                    onTypingChanged = { typing ->
                                        val tid = state.selectedThreadId ?: "channel_primary"
                                        viewModel.sendTypingStatus(tid, typing)
                                    },
                                    onSearchActiveThread = { q -> viewModel.searchActiveThread(q) },
                                    onSendFileBytes = { name, mime, bytes ->
                                        val tid = state.selectedThreadId ?: "channel_primary"
                                        viewModel.sendFileAttachment(tid, name, mime, bytes)
                                    },
                                    onPauseFileTransfer = { id -> viewModel.pauseFileTransfer(id) },
                                    onResumeFileTransfer = { id -> viewModel.resumeFileTransfer(id) },
                                    onAddGroupMember = { gid, mid -> viewModel.addGroupMember(gid, mid) },
                                    onRemoveGroupMember = { gid, mid -> viewModel.removeGroupMember(gid, mid) },
                                    onToggleGroupAdmin = { gid, mid -> viewModel.toggleGroupAdmin(gid, mid) },
                                    onRotateGroupKey = { gid -> viewModel.rotateGroupKeyById(gid) },
                                    onBuildGroupInviteQr = { gid -> viewModel.buildGroupInviteQr(gid) },
                                    onSendVoiceNoteClip = { bytes, dur ->
                                        val tid = state.selectedThreadId ?: "channel_primary"
                                        viewModel.sendVoiceNoteBytes(tid, bytes, dur)
                                    },
                                )
                            }
                            2 -> com.kostadinosk.pulsemesh.ui.screens.voice.PulseVoiceHubScreen(
                                state = state,
                                duplexCallState = state.duplexCallState,
                                onStartCall = { viewModel.startDuplexCall(it) },
                                onStartGroupCall = { viewModel.startGroupMeshCall() },
                                onEndCall = { viewModel.endDuplexCall() },
                                onToggleMute = { viewModel.toggleCallMute() },
                                onToggleSpeaker = { viewModel.toggleCallSpeaker() },
                                onStartPtt = { viewModel.startPtt() },
                                onStopPtt = { viewModel.stopPtt() },
                                onAcceptCall = { viewModel.acceptDuplexCall() },
                                onDeclineCall = { viewModel.declineDuplexCall() },
                                onReplayLastRx = { viewModel.replayLastRxVoice(isCallMode = false) },
                                onSelectAudioRoute = { route -> viewModel.selectAudioRoute(route) },
                                onJoinPttChannel = { cid -> viewModel.joinPttChannel(cid) },
                                onToggleCourtesyBeep = { enabled -> viewModel.setPttCourtesyBeepEnabled(enabled) },
                                onToggleConferenceMute = { nid -> viewModel.toggleConferencePeerMute(nid) },
                                onKickConferencePeer = { nid -> viewModel.kickConferencePeer(nid) }
                            )
                            else -> {
                                com.kostadinosk.pulsemesh.ui.screens.settings.ModularSettingsHub(
                                    state = state,
                                    activeDestination = activeSettingsDest,
                                    onNavigate = { activeSettingsDest = it },
                                    subScreenContent = { dest ->
                                        when (dest) {
                                            com.kostadinosk.pulsemesh.ui.screens.settings.SettingsDestination.APPEARANCE ->
                                                com.kostadinosk.pulsemesh.ui.screens.settings.AppearanceSettingsScreen(
                                                    selectedPreset = state.themePreset,
                                                    selectedBrightness = state.themeBrightness,
                                                    appLanguage = state.appLanguage,
                                                    onSelectPreset = { viewModel.setThemePreset(it) },
                                                    onSelectBrightness = { viewModel.setThemeBrightness(it) },
                                                    onSelectLanguage = { viewModel.setAppLanguage(it) }
                                                )
                                            com.kostadinosk.pulsemesh.ui.screens.settings.SettingsDestination.RADIO_LAB ->
                                                com.kostadinosk.pulsemesh.ui.screens.settings.RadioLabDiagnosticsScreen(
                                                    state = state,
                                                    onStartBleTx = { viewModel.startLiveBleTx() },
                                                    onStopBleTx = { viewModel.stopLiveBleTx() },
                                                    onToggleRxSniffer = { viewModel.toggleRawBleSniffer() },
                                                    onClearBleDevices = { viewModel.clearScannedBleDevices() },
                                                    onRequestPermissions = { permissionLauncher.launch(permissionsToRequest) },
                                                    onForceGattReDiscovery = { viewModel.forceGattReDiscovery() },
                                                    onRunPingSweep = { viewModel.runMeshLinkQualitySweep() },
                                                    onVerifyRoot = { viewModel.verifyRootStatus() },
                                                    onRefreshHardware = { viewModel.refreshHardwareReport() }
                                                )
                                            com.kostadinosk.pulsemesh.ui.screens.settings.SettingsDestination.UPDATES_DATA ->
                                                com.kostadinosk.pulsemesh.ui.screens.settings.UpdatesDataSettingsScreen(
                                                    state = state,
                                                    backupStatusMessage = state.backupStatusMessage,
                                                    onCheckUpdates = { viewModel.checkForAppUpdateNow(userInitiated = true) },
                                                    onInstallVerifiedUpdate = { viewModel.downloadAndInstallVerifiedUpdate() },
                                                    onExportEncryptedBackup = { pass ->
                                                        pendingBackupPassphrase = pass
                                                        backupExportLauncher.launch("pulsemesh_${state.operatorCallsign}.pmbackup")
                                                    },
                                                    onImportEncryptedBackup = { pass ->
                                                        pendingBackupPassphrase = pass
                                                        backupImportLauncher.launch(arrayOf("*/*"))
                                                    }
                                                )
                                            com.kostadinosk.pulsemesh.ui.screens.settings.SettingsDestination.PROFILE ->
                                                com.kostadinosk.pulsemesh.ui.screens.settings.ProfileSettingsScreen(
                                                    state = state,
                                                    onUpdateCallsign = { viewModel.updateCallsign(it) },
                                                    onRegenerateNodeId = { viewModel.regenerateNodeId() },
                                                    onClaimOrRecoverUsername = { u, pin, rk, rec, cb ->
                                                        viewModel.claimOrRecoverUsername(u, pin, rk, rec, cb)
                                                    },
                                                    onOpenConnectHub = { showConnectHub = true },
                                                    onOpenOnboarding = { viewModel.openSetupWizard() }
                                                )
                                            com.kostadinosk.pulsemesh.ui.screens.settings.SettingsDestination.RADIOS ->
                                                com.kostadinosk.pulsemesh.ui.screens.settings.RadiosSettingsScreen(
                                                    state = state,
                                                    onSetBatteryPowerProfile = { viewModel.setBatteryPowerProfile(it) },
                                                    onToggleInternetMode = { viewModel.toggleInternetEnabled() },
                                                    onToggleWebPortal = { viewModel.toggleWebPortal() },
                                                    onToggleBleCodedPhy = { viewModel.toggleBleCodedPhy() },
                                                    onToggleWifiAware = { viewModel.toggleWifiAware() },
                                                    onToggleBackgroundRelay = { viewModel.toggleBackgroundRelayService() },
                                                    onUpdateChannelAndPassphrase = { ch, pass ->
                                                        if (ch.isNotBlank()) viewModel.setMeshChannel(ch)
                                                        if (pass.isNotBlank()) viewModel.setMeshPassphrase(pass)
                                                    },
                                                    onToggleLoraBridge = { viewModel.toggleLoraBridge() },
                                                    onSelectLoraProtocol = { viewModel.selectLoraProtocol(it) },
                                                    onScanLoraDevices = { viewModel.scanLoraDevices() },
                                                    onToggleAcousticModem = { viewModel.toggleBell202Modem() },
                                                    onToggleCotBridge = { viewModel.setCotBridgeEnabled(it) }
                                                )
                                            com.kostadinosk.pulsemesh.ui.screens.settings.SettingsDestination.SECURITY ->
                                                com.kostadinosk.pulsemesh.ui.screens.settings.SecuritySettingsScreen(
                                                    state = state,
                                                    onRotateKeys = { viewModel.rotateEncryptionKeys() },
                                                    onSelectLocationShareMode = { viewModel.setLocationShareMode(it) },
                                                    onSetAppLockPin = { viewModel.setAppLockPin(it) },
                                                    onSetBiometricUnlock = { viewModel.setBiometricUnlockEnabled(it) },
                                                    onSetAppLockTimeout = { viewModel.setAppLockTimeout(it) },
                                                    onClearAppLock = { viewModel.clearAppLock() },
                                                    onSetDuressPin = { viewModel.setDuressPin(it) },
                                                    onCycleBurnTtl = { viewModel.cycleMessageBurnTtl() },
                                                    onPurgeStalePeers = { viewModel.purgePhantomAndCachedPeers() }
                                                )
                                            com.kostadinosk.pulsemesh.ui.screens.settings.SettingsDestination.SAFETY ->
                                                com.kostadinosk.pulsemesh.ui.screens.settings.SafetySettingsScreen(
                                                    state = state,
                                                    onOpenEmergencySosSheet = { showEmergencySosSheet = true },
                                                    onSaveIceCard = { b, a, n, c -> viewModel.saveIceMedicalCard(b, a, n, c) },
                                                    onSetDeadManMinutes = { viewModel.setDeadManSwitchMinutes(it) },
                                                    onCheckInDeadMan = { viewModel.checkInDeadManSwitch() },
                                                    onConfigureGeofence = { en, rad -> viewModel.configureGeofence(enabled = en, radiusMeters = rad) }
                                                )
                                        }
                                    }
                                )
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
                                        showEmergencySosSheet = true
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
                                            com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "⚠ SOS BROADCASTING", "⚠ ΕΚΠΟΜΠΗ SOS")
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
                                            text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "BROADCAST EMERGENCY SOS?", "ΕΚΠΟΜΠΗ ΕΚΤΑΚΤΟΥ SOS;"),
                                            color = p.red,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 14.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                },
                                text = {
                                    Text(
                                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(
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
                                            text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "TRIGGER SOS NOW", "ΕΚΠΟΜΠΗ SOS ΤΩΡΑ"),
                                            fontWeight = FontWeight.ExtraBold,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        )
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showSosConfirmDialog = false }) {
                                        Text(com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "CANCEL", "ΑΚΥΡΩΣΗ"), color = p.textSecondary)
                                    }
                                }
                            )
                        }
                    }
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
                                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "TrailNote Compass & Hike Log", "Ημερολόγιο Διαδρομών & Πυξίδα"),
                                        color = Color(0xFF1E3A2F),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 20.sp
                                    )
                                    Text(
                                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Offline Step & Elevation Journal", "Καταγραφή Βημάτων & Υψομέτρου"),
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
                                    Text(com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Today's Trail Summary", "Σημερινή Σύνοψη"), fontWeight = FontWeight.Bold, color = Color(0xFF1E3A2F))
                                    Text("${com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Compass Heading", "Πυξίδα")}: ${state.deviceHeadingDeg.toInt()}°", color = Color(0xFF3E5248), fontSize = 13.sp)
                                    Text("${com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Steps Logged", "Βήματα")}: ${state.stepCount}", color = Color(0xFF3E5248), fontSize = 13.sp)
                                    if (state.hasBarometerSensor && state.barometricPressureHpa != null) {
                                        Text(
                                            text = "${com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Barometer", "Βαρόμετρο")}: %.1f hPa (${com.kostadinosk.pulsemesh.util.AppStrings.weatherTrendLabel(state.appLanguage, state.weatherTrendLabel)})".format(state.barometricPressureHpa),
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
                                val decoyPrefs = remember { getSharedPreferences("pulsemesh_decoy_notes", android.content.Context.MODE_PRIVATE) }
                                var personalTrailNote by remember { mutableStateOf(decoyPrefs.getString("user_note", "") ?: "") }
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Personal Trail Notes", "Προσωπικές Σημειώσεις Διαδρομής"),
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1E3A2F)
                                    )
                                    OutlinedTextField(
                                        value = personalTrailNote,
                                        onValueChange = {
                                            personalTrailNote = it
                                            decoyPrefs.edit().putString("user_note", it).apply()
                                        },
                                        placeholder = {
                                            Text(
                                                com.kostadinosk.pulsemesh.util.AppStrings.tr(
                                                    state.appLanguage,
                                                    "Write your own hiking notes here (saved offline)...",
                                                    "Γράψτε τις σημειώσεις πεζοπορίας σας εδώ..."
                                                ),
                                                fontSize = 12.sp
                                            )
                                        },
                                        minLines = 3,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }

                // PulseMesh v2.0 Dual-Track Onboarding (Fast 3-Step / Custom 5-Step)
                if (state.showSetupWizard) {
                    com.kostadinosk.pulsemesh.ui.onboarding.PulseOnboardingScreen(
                        state = state,
                        onUpdateCallsign = { viewModel.updateCallsign(it) },
                        onReserveUsername = { user, pass ->
                            viewModel.claimOrRecoverUsername(user, pass, "", false) { _, _ -> }
                        },
                        onRequestPermissions = { permissionLauncher.launch(permissionsToRequest) },
                        onDownloadMapRegion = { lat, lon, _, _ ->
                            viewModel.broadcastOfflineMapTilesToPeers(lat, lon)
                        },
                        onSaveIceInfo = { ice -> viewModel.saveIceMedicalCard("", "", "", ice) },
                        onOpenQrScanner = {
                            viewModel.closeSetupWizard()
                            showQrScannerDialog = true
                        },
                        onFinish = { viewModel.closeSetupWizard() }
                    )
                }

                // PulseMesh v2.0 Tap-to-Expand Telemetry Drawer
                if (showTelemetryDrawer) {
                    TelemetryStatusDrawer(
                        state = state,
                        onDismiss = { showTelemetryDrawer = false },
                        onToggleInternetMode = { viewModel.toggleInternetEnabled() },
                        onToggleHotspot = { viewModel.toggleWebPortal() },
                        onOpenRadioLab = {
                            activeSettingsDest = com.kostadinosk.pulsemesh.ui.screens.settings.SettingsDestination.RADIO_LAB
                            viewModel.setTab(3)
                        },
                        onOpenConnectHub = { showConnectHub = true }
                    )
                }

                // PulseMesh v2.0 Unified 1-Tap Connect & Share Hub
                if (showConnectHub) {
                    ConnectShareSheet(
                        state = state,
                        onDismiss = { showConnectHub = false },
                        onLaunchQrScanner = { showQrScannerDialog = true },
                        onToggleHotspotPortal = { viewModel.toggleWebPortal() },
                        onToggleLoraBridge = { viewModel.toggleLoraBridge() },
                        onSelectLoraProtocol = { viewModel.selectLoraProtocol(it) },
                        onScanLoraDevices = { viewModel.scanLoraDevices() },
                        onToggleAcousticModem = { viewModel.toggleBell202Modem() },
                        onSendAcousticMessage = { msg -> viewModel.transmitBell202AcousticMessage(msg, isSos = false) }
                    )
                }

                // PulseMesh v2.0 Offline Emergency Kit & SOS Drawer
                if (showEmergencySosSheet) {
                    EmergencySosSheet(
                        state = state,
                        toolkitState = toolkitState,
                        onDismiss = { showEmergencySosSheet = false },
                        onToggleSosBeacon = { viewModel.toggleSosBeacon() },
                        onSendImSafeCheckIn = { viewModel.sendImSafeCheckIn() },
                        onToggleTorchMorse = { viewModel.emergencyToolkit.toggleTorchMorseSos() },
                        onToggleSiren = { viewModel.emergencyToolkit.toggleSiren() }
                    )
                }

                // PulseMesh v2.0 Interactive Safety Number & Key Verification Sheet
                safetyNumberPeer?.let { (peerId, peerName) ->
                    val convo = state.conversations.firstOrNull { it.peerNodeId == peerId }
                    val peerNode = state.nodes.firstOrNull { it.id == peerId }
                    SafetyNumberSheet(
                        localNodeId = state.localNodeId,
                        localPublicKey = state.x25519PublicKey,
                        peerNodeId = peerId,
                        peerCallsign = peerName,
                        peerPublicKey = convo?.safetyFingerprint.orEmpty().ifBlank { peerId },
                        isKeyChangedWarning = convo?.isKeyChangedWarning == true,
                        isVerified = convo?.isKeyChangedWarning == false && !convo.safetyFingerprint.isNullOrBlank(),
                        appLanguage = state.appLanguage,
                        onMarkVerified = { viewModel.acceptChangedPeerKey(peerId) },
                        onScanPeerQr = { showQrScannerDialog = true },
                        onDismiss = { safetyNumberPeer = null }
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
                                    text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "SWARM HOTSPOT DETECTED", "ΕΝΤΟΠΙΣΤΗΚΕ HOTSPOT ΟΜΑΔΑΣ"),
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
                                    text = com.kostadinosk.pulsemesh.util.AppStrings.tr(
                                        state.appLanguage,
                                        "Peer ${hotspotPrompt.hostNodeId} is hosting a high-speed off-grid Wi-Fi Direct / Swarm Hotspot (${hotspotPrompt.ssid} @ ${hotspotPrompt.hostIp}).",
                                        "Ο κόμβος ${hotspotPrompt.hostNodeId} φιλοξενεί γρήγορο Hotspot Wi-Fi Direct (${hotspotPrompt.ssid} @ ${hotspotPrompt.hostIp})."
                                    ),
                                    color = p.textPrimary,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    text = com.kostadinosk.pulsemesh.util.AppStrings.tr(
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
                                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "DISABLE HOTSPOT POPUPS", "ΑΠΕΝΕΡΓΟΠΟΙΗΣΗ ΕΙΔΟΠΟΙΗΣΕΩΝ"),
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
                                    text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "CONNECT NOW", "ΣΥΝΔΕΣΗ ΤΩΡΑ"),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.dismissHotspotPrompt() }) {
                                Text(
                                    text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "NOT NOW", "ΟΧΙ ΤΩΡΑ"),
                                    color = p.textSecondary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        },
                    )
                }

                // Hardware mismatch popups removed in v1.4.3 (Android hardware is auto-detected)

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

                if (showQrScannerDialog) {
                    Dialog(onDismissRequest = { showQrScannerDialog = false }) {
                        com.kostadinosk.pulsemesh.ui.qr.FlavorQrScannerSheet(
                            onActionDecoded = { action ->
                                val (ok, msg) = viewModel.handleQrPairingAction(action)
                                android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                                if (ok) showQrScannerDialog = false
                            },
                            onDismiss = { showQrScannerDialog = false },
                            appLanguage = state.appLanguage
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.syncNfcPairingPayload()
        runCatching {
            val adapter = NfcAdapter.getDefaultAdapter(this) ?: return@runCatching
            adapter.enableReaderMode(
                this,
                { tag ->
                    runCatching {
                        val isoDep = IsoDep.get(tag) ?: return@runCatching
                        isoDep.connect()
                        isoDep.timeout = 2_000
                        val selResp = isoDep.transceive(
                            com.kostadinosk.pulsemesh.service.NfcPairingProtocol.buildSelectAidCommand()
                        )
                        if (com.kostadinosk.pulsemesh.service.NfcPairingProtocol.extractPayloadIfOk(selResp) != null) {
                            val idResp = isoDep.transceive(com.kostadinosk.pulsemesh.service.NfcPairingProtocol.CMD_GET_IDENTITY)
                            val rawPayload = com.kostadinosk.pulsemesh.service.NfcPairingProtocol.extractPayloadIfOk(idResp)
                            val action = rawPayload?.let { com.kostadinosk.pulsemesh.util.QrPairingRouter.parsePayload(it) }
                            if (action != null) {
                                runOnUiThread {
                                    val (_, msg) = viewModel.handleQrPairingAction(action)
                                    android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        runCatching { isoDep.close() }
                    }
                },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null
            )
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching {
            NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
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
        if (hardwarePttController.handleKeyEvent(event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (hardwarePttController.handleKeyEvent(event)) {
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
