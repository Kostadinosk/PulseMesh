package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ChipsetProfile
import com.example.model.DeviceCatalog
import com.example.model.LocationShareMode
import com.example.model.PhoneModelProfile
import com.example.ui.components.AeroGlossBadgeIcon
import com.example.ui.components.ProMeshRssiChartCard
import com.example.ui.theme.*
import com.example.util.AppTheme
import com.example.viewmodel.MeshUiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: MeshUiState,
    onToggleSimpleMode: () -> Unit,
    onToggleTransportMode: () -> Unit,
    onSelectPhoneProfile: (PhoneModelProfile) -> Unit,
    onAutoMatchHardware: () -> Unit,
    onRegenerateNodeId: () -> Unit,
    onUpdateCallsign: (String) -> Unit,
    onUpdateMeshChannel: (String) -> Unit,
    onPingMeshNow: () -> Unit,
    onRequestGpsPermission: () -> Unit,
    onSetManualLocation: (Double, Double) -> Unit,
    onOpenSetupWizard: () -> Unit,
    onVerifyRoot: () -> Unit = {},
    onToggleInternetEnabled: () -> Unit = {},
    onToggleSpokenCallerId: () -> Unit = {},
    onReconnectRelay: () -> Unit = {},
    onRotateKeys: () -> Unit = {},
    onToggleHotspot: () -> Unit = {},
    onHostHotspot: () -> Unit = {},
    onJoinHotspot: () -> Unit = {},
    onStopHotspot: () -> Unit = {},
    onForceGattReDiscovery: () -> Unit = {},
    onToggleBroadcastCoarseLocation: () -> Unit = {},
    onSelectLocationShareMode: (LocationShareMode) -> Unit = {},
    onUpdateMeshPassphrase: (String) -> Unit = {},
    onToggleHotspotAutoPopup: (Boolean) -> Unit = {},
    onRunMeshLinkSweep: () -> Unit = {},
    onSelectAutoCheckInInterval: (Int) -> Unit = {},
    onToggleLightMode: () -> Unit = {},
    onSelectAppTheme: (AppTheme) -> Unit = {},
    onClaimOrRecoverUsername: (String, String, String, Boolean, (Boolean, String) -> Unit) -> Unit = { _, _, _, _, _ -> },
    onUpdateUsernameRelayUrl: (String, (Boolean, String) -> Unit) -> Unit = { _, _ -> },
    onSyncAccountBackupNow: ((Boolean, String) -> Unit) -> Unit = {},
    onToggleBackgroundRelayService: () -> Unit = {},
    onToggleVolumeButtonPtt: () -> Unit = {},
    onToggleRadioVoxBridge: () -> Unit = {},
    onToggleDeadReckoning: () -> Unit = {},
    onToggleBreadcrumbRecording: () -> Unit = {},
    onClearBreadcrumbTrail: () -> Unit = {},
    onShareBreadcrumbTrail: () -> Unit = {},
    onBroadcastOfflineMapTiles: () -> Unit = {},
    onTransmitKeyVerifyChirp: () -> Unit = {},
    onCycleMessageBurnTtl: () -> Unit = {},
    onSetDuressPin: (String) -> Boolean = { false },
    onTriggerDuressWipe: (String) -> Boolean = { false },
    onExportGpx: () -> String = { "" },
    onImportGpx: (String) -> Int = { 0 },
    modifier: Modifier = Modifier
) {
    val p = LocalPulsePalette.current
    val cardShape = tacticalCardShape(state.isSimpleMode, p.isOriginal, p.isAero)
    val btnShape = tacticalButtonShape(state.isSimpleMode, p.isOriginal, p.isAero)
    val isLightMode = state.appTheme == AppTheme.TACTICAL_LIGHT
    val isOriginalTheme = state.appTheme == AppTheme.ORIGINAL
    val isDarkAeroTheme = state.appTheme == AppTheme.FRUTIGER_AERO_DARK
    val isAeroTheme = state.appTheme == AppTheme.FRUTIGER_AERO || isDarkAeroTheme
    val scrollState = rememberScrollState()
    var editCallsignDialog by remember { mutableStateOf(false) }
    var tempCallsign by remember { mutableStateOf(state.operatorCallsign) }
    var showDuressDialog by remember { mutableStateOf(false) }
    var duressPinInput by remember { mutableStateOf("") }
    var duressFeedback by remember { mutableStateOf("") }
    var showGpxDialog by remember { mutableStateOf(false) }
    var gpxTextInput by remember { mutableStateOf("") }
    var gpxFeedback by remember { mutableStateOf("") }

    var editChannelDialog by remember { mutableStateOf(false) }
    var tempChannel by remember { mutableStateOf(state.meshChannel) }

    var editPassphraseDialog by remember { mutableStateOf(false) }
    var tempPassphrase by remember { mutableStateOf(state.meshPassphrase) }

    var manualGpsDialog by remember { mutableStateOf(false) }
    var manualLatText by remember { mutableStateOf(state.userLatitude?.toString() ?: "37.7749") }
    var manualLonText by remember { mutableStateOf(state.userLongitude?.toString() ?: "-122.4194") }

    var showProfilePickerModal by remember { mutableStateOf(false) }
    var showUsernameAccountDialog by remember { mutableStateOf(false) }
    var accountDialogRecoveryMode by remember { mutableStateOf(false) }
    var accountDialogRecoveryWithKey by remember { mutableStateOf(false) }
    var accountUsernameInput by remember(state.claimedUsername) { mutableStateOf(state.claimedUsername) }
    var showRelayUrlDialog by remember { mutableStateOf(false) }
    var relayUrlInput by remember(state.usernameRelayUrl) { mutableStateOf(state.usernameRelayUrl) }
    var relayDialogFeedback by remember { mutableStateOf("") }
    var accountPinInput by remember { mutableStateOf("") }
    var accountRecoveryKeyInput by remember { mutableStateOf("") }
    var accountDialogStatus by remember { mutableStateOf("") }
    var accountDialogSuccess by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .aeroCanvasBackground(p)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .navigationBarsPadding()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // -------------------------------------------------------------
        // TOP PERMANENT MODE SWITCH PILL: MODE: EASY / MODE: PRO
        // -------------------------------------------------------------
        Surface(
            color = if (p.isAero) Color.Transparent else p.card,
            shape = cardShape,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (state.isSimpleMode) p.cyan else p.amber
            ),
            modifier = Modifier
                .fillMaxWidth()
                .aeroGlassPanel(p, cornerRadius = 22.dp)
                .clickable { onToggleSimpleMode() }
                .testTag("settings_top_mode_toggle")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (p.isAero) {
                        AeroGlossBadgeIcon(
                            imageVector = if (state.isSimpleMode) Icons.Default.CheckCircle else Icons.Default.Tune,
                            accentColor = if (state.isSimpleMode) p.cyan else p.amber,
                            contentDescription = "App Mode",
                            badgeSize = 32.dp,
                            iconSize = 17.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (state.isSimpleMode) Icons.Default.CheckCircle else Icons.Default.Tune,
                            contentDescription = "App Mode",
                            tint = if (state.isSimpleMode) p.cyan else p.amber,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (state.isSimpleMode) "UI Layout: Easy Mode" else "UI Layout: Pro Mode",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = p.textPrimary
                            )
                        )
                        Text(
                            text = "Tap to switch instantly between Easy Mode and Pro Mode",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = p.textSecondary,
                                fontSize = 11.sp
                            )
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(btnShape)
                            .background(if (state.isSimpleMode) p.cyanDim else p.amberDim)
                            .border(1.dp, if (state.isSimpleMode) p.cyan else p.amber, btnShape)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = if (state.isSimpleMode) "MODE: EASY" else "MODE: PRO",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (state.isSimpleMode) p.cyan else p.amber,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        )
                    }
                    Switch(
                        checked = state.isSimpleMode,
                        onCheckedChange = { onToggleSimpleMode() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = if (p.isLight || p.isAero) Color.White else Color.Black,
                            checkedTrackColor = p.cyan,
                            uncheckedThumbColor = if (p.isLight || p.isAero) Color.White else Color.Black,
                            uncheckedTrackColor = p.amber
                        ),
                        modifier = Modifier.testTag("toggle_simple_mode_switch")
                    )
                }
            }
        }

        // -------------------------------------------------------------
        // APPEARANCE THEME PICKER: ORIGINAL CLASSIC / TACTICAL HUD / DAYLIGHT LIGHT / FRUTIGER AERO
        // -------------------------------------------------------------
        Surface(
            color = if (p.isAero) Color.Transparent else p.card,
            shape = cardShape,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                when {
                    isAeroTheme -> p.cyan
                    isLightMode -> p.amber
                    isOriginalTheme -> p.emerald
                    else -> p.border
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .aeroGlassPanel(p, cornerRadius = 22.dp)
                .testTag("settings_light_mode_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        if (p.isAero) {
                            AeroGlossBadgeIcon(
                                imageVector = if (isDarkAeroTheme) Icons.Default.NightsStay else Icons.Default.WaterDrop,
                                accentColor = p.cyan,
                                contentDescription = "Appearance Theme",
                                badgeSize = 32.dp,
                                iconSize = 17.dp,
                            )
                        } else {
                            Icon(
                                imageVector = when {
                                    isDarkAeroTheme -> Icons.Default.NightsStay
                                    isAeroTheme -> Icons.Default.WaterDrop
                                    isLightMode -> Icons.Default.LightMode
                                    isOriginalTheme -> Icons.Default.Restore
                                    else -> Icons.Default.DarkMode
                                },
                                contentDescription = "Appearance Theme",
                                tint = when {
                                    isAeroTheme -> p.cyan
                                    isLightMode -> p.amber
                                    isOriginalTheme -> p.emerald
                                    else -> p.cyan
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = when (state.appTheme) {
                                    AppTheme.FRUTIGER_AERO -> "Theme: Frutiger Aero"
                                    AppTheme.FRUTIGER_AERO_DARK -> "Theme: Frutiger Aero Dark"
                                    AppTheme.TACTICAL_LIGHT -> "Theme: Daylight Light"
                                    AppTheme.ORIGINAL -> "Theme: Original Classic"
                                    else -> "Theme: Tactical HUD Dark"
                                },
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = p.textPrimary
                                )
                            )
                            Text(
                                text = "Original, Tactical HUD, Daylight, Light Aero, or Dark Aero glass",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = p.textSecondary,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }

                    Switch(
                        checked = isLightMode,
                        onCheckedChange = { onToggleLightMode() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = p.amber,
                            uncheckedThumbColor = if (p.isAero) Color.White else Color.Black,
                            uncheckedTrackColor = p.cyan
                        ),
                        modifier = Modifier.testTag("settings_light_mode_switch")
                    )
                }

                val themeRows = listOf(
                    listOf(
                        Triple(AppTheme.ORIGINAL, "ORIGINAL", "settings_theme_original_button"),
                        Triple(AppTheme.TACTICAL_DARK, "HUD DARK", "settings_theme_hud_dark_button"),
                        Triple(AppTheme.TACTICAL_LIGHT, "DAYLIGHT", "settings_theme_light_button")
                    ),
                    listOf(
                        Triple(AppTheme.FRUTIGER_AERO, "FRUTIGER AERO", "settings_theme_aero_button"),
                        Triple(AppTheme.FRUTIGER_AERO_DARK, "DARK AERO", "settings_theme_dark_aero_button")
                    )
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (rowOptions in themeRows) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for ((themeValue, label, tag) in rowOptions) {
                                val isSelected = state.appTheme == themeValue ||
                                    (themeValue == AppTheme.TACTICAL_DARK &&
                                        state.appTheme != AppTheme.ORIGINAL &&
                                        state.appTheme != AppTheme.TACTICAL_LIGHT &&
                                        state.appTheme != AppTheme.FRUTIGER_AERO &&
                                        state.appTheme != AppTheme.FRUTIGER_AERO_DARK)
                                val activeColor = when (themeValue) {
                                    AppTheme.ORIGINAL -> p.emerald
                                    AppTheme.TACTICAL_LIGHT -> p.amber
                                    AppTheme.FRUTIGER_AERO -> p.cyan
                                    AppTheme.FRUTIGER_AERO_DARK -> Color(0xFF1986D6)
                                    else -> p.cyan
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (themeValue == AppTheme.TACTICAL_LIGHT && !isLightMode) {
                                            onSelectAppTheme(themeValue)
                                            onToggleLightMode()
                                        } else {
                                            onSelectAppTheme(themeValue)
                                        }
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (isSelected && p.isAero) {
                                            Color.Transparent
                                        } else if (isSelected) {
                                            activeColor.copy(alpha = 0.2f)
                                        } else {
                                            p.surfaceVariant
                                        },
                                        contentColor = if (isSelected) {
                                            if (p.isAero) Color.White else activeColor
                                        } else {
                                            p.textSecondary
                                        }
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.2.dp,
                                        if (isSelected) activeColor else p.border
                                    ),
                                    shape = btnShape,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 38.dp)
                                        .then(
                                            if (isSelected && p.isAero) {
                                                Modifier.aeroGlossOverlay(p, activeColor, btnShape, cornerRadius = 18.dp)
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .testTag(tag)
                                ) {
                                    Text(
                                        text = label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // PRO-ONLY REAL-TIME D3 / RECHARTS MESH RSSI TELEMETRY CHART
        // -------------------------------------------------------------
        if (!state.isSimpleMode) {
            ProMeshRssiChartCard(state = state)
        }

        // -------------------------------------------------------------
        // PRO MODE COMPACT TELEMETRY & QUICK-ACTION CONSOLE STRIP
        // -------------------------------------------------------------
        if (!state.isSimpleMode) {
            Surface(
                color = p.card,
                shape = cardShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, p.amber.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = p.amber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "PRO ENGINEERING TELEMETRY & QUICK ACTIONS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = p.amber,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                )
                            )
                        }
                        Text(
                            text = "CH: ${state.meshChannel}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = p.cyan,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp
                            )
                        )
                    }

                    // 4-Metric High-Density HUD Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ProMetricTile(
                            label = "TX / RX",
                            value = "${state.txFramesCount}/${state.rxFramesCount}",
                            accent = p.emerald,
                            modifier = Modifier.weight(1f)
                        )
                        ProMetricTile(
                            label = "GATT 2-WAY",
                            value = if (state.isTwoWayGattSymmetric) "SYMMETRIC" else "${state.connectedServerDeviceAddresses.size + state.connectedGattClientAddresses.size} LINK",
                            accent = if (state.isTwoWayGattSymmetric) p.emerald else p.cyan,
                            modifier = Modifier.weight(1f)
                        )
                        ProMetricTile(
                            label = "DTN QUEUE",
                            value = "${state.pendingDtnCount}/50",
                            accent = p.amber,
                            modifier = Modifier.weight(1f)
                        )
                        ProMetricTile(
                            label = "EC EPOCH",
                            value = "v${state.encryptionKeyEpoch}",
                            accent = p.cyan,
                            modifier = Modifier.weight(0.85f)
                        )
                    }

                    // Pro Quick Action Bar (Setup Wizard, Mesh Channel, Caller TTS, Re-Sync Relay)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenSetupWizard,
                            shape = btnShape,
                            border = androidx.compose.foundation.BorderStroke(1.dp, p.emerald),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(min = 105.dp)
                                .heightIn(min = 36.dp)
                        ) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = p.emerald, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("SETUP WIZARD", color = p.emerald, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }

                        OutlinedButton(
                            onClick = {
                                tempChannel = state.meshChannel
                                editChannelDialog = true
                            },
                            shape = btnShape,
                            border = androidx.compose.foundation.BorderStroke(1.dp, p.cyan),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(min = 105.dp)
                                .heightIn(min = 36.dp)
                        ) {
                            Icon(Icons.Default.Tag, contentDescription = null, tint = p.cyan, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("SET CHANNEL", color = p.cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }

                        OutlinedButton(
                            onClick = onToggleSpokenCallerId,
                            shape = btnShape,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (state.isSpokenCallerIdEnabled) p.amber else p.border
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(min = 105.dp)
                                .heightIn(min = 36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                tint = if (state.isSpokenCallerIdEnabled) p.amber else p.textSecondary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (state.isSpokenCallerIdEnabled) "TTS ID: ON" else "TTS ID: OFF",
                                color = if (state.isSpokenCallerIdEnabled) p.amber else p.textSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        if (state.isInternetEnabled) {
                            OutlinedButton(
                                onClick = onReconnectRelay,
                                shape = btnShape,
                                border = androidx.compose.foundation.BorderStroke(1.dp, p.cyan),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .widthIn(min = 105.dp)
                                    .heightIn(min = 36.dp)
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = null, tint = p.cyan, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("SYNC RELAY", color = p.cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // SECTION 1: TRANSPORT NETWORK POSTURE (OFF-GRID VS HYBRID)
        // -------------------------------------------------------------
        ElevatedCard(
            shape = cardShape,
            colors = CardDefaults.elevatedCardColors(containerColor = p.card),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (state.isPureOffGridTransport) p.emerald else p.cyan, cardShape)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (p.isAero) {
                            AeroGlossBadgeIcon(
                                imageVector = Icons.Default.Sensors,
                                accentColor = if (state.isPureOffGridTransport) EmeraldMesh else CyanGateway,
                                contentDescription = "Transport",
                                badgeSize = 30.dp,
                                iconSize = 16.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Sensors,
                                contentDescription = "Transport",
                                tint = if (state.isPureOffGridTransport) EmeraldMesh else CyanGateway,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Transport Network Posture",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = p.textPrimary
                            )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (state.isPureOffGridTransport) EmeraldMeshDim else CyanGatewayDim)
                            .border(1.dp, if (state.isPureOffGridTransport) EmeraldMesh else CyanGateway, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (state.isPureOffGridTransport) "100% PURE BLUETOOTH" else "HYBRID RELAY",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (state.isPureOffGridTransport) EmeraldMesh else CyanGateway,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (state.isPureOffGridTransport)
                        "Operating in 100% Pure Bluetooth & Wi-Fi Off-Grid Mode (Zero Internet touched). Communicates via 0xCAFE 12B BLE Beacons, Bidirectional BLE GATT (0xCAFF), and Wi-Fi Direct / Swarm Hotspot."
                    else
                        "Operating in Hybrid Mode: Combines BLE 1M/Coded/2M + GATT + Wi-Fi Direct/Hotspot alongside the AES-256-GCM encrypted ntfy.sh HTTPS cloud relay stream.",
                    style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Transport Switcher Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!state.isInternetEnabled) EmeraldMesh else p.surfaceVariant)
                            .clickable { if (state.isInternetEnabled) onToggleInternetEnabled() }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "AIR-GAPPED OFF-GRID",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (!state.isInternetEnabled) Color.Black else p.textSecondary
                            )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (state.isInternetEnabled) AmberRootBoost else p.surfaceVariant)
                            .clickable { if (!state.isInternetEnabled) onToggleInternetEnabled() }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "HYBRID (+INTERNET)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (state.isInternetEnabled) Color.Black else p.textSecondary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Internet Capabilities Toggle Switch
                Surface(
                    color = p.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, p.border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable Internet Capabilities",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (state.isInternetEnabled) AmberRootBoost else p.textPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Allows HTTP/HTTPS hybrid relay and online OpenStreetMap tile downloads. OFF by default for 100% air-gapped radio privacy.",
                                style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                            )
                        }
                        Switch(
                            checked = state.isInternetEnabled,
                            onCheckedChange = { onToggleInternetEnabled() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = AmberRootBoost,
                                uncheckedThumbColor = p.textMuted,
                                uncheckedTrackColor = p.card
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Hotspot Auto-Join Popup Toggle (Allows disabling Hotspot popups permanently)
                Surface(
                    color = p.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (state.isHotspotAutoPopupEnabled) CyanGateway.copy(alpha = 0.6f) else p.border
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleHotspotAutoPopup(!state.isHotspotAutoPopupEnabled) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Hotspot Connection Popup",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (state.isHotspotAutoPopupEnabled) CyanGateway else p.textPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (state.isHotspotAutoPopupEnabled) {
                                    "Prompts to auto-join nearby peer Wi-Fi Direct / Swarm Hotspots. Turn OFF to disable hotspot popups and stay on silent BLE."
                                } else {
                                    "Hotspot popups DISABLED. Operating silently on BLE until you manually tap Host or Join Hotspot."
                                },
                                style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                            )
                        }
                        Switch(
                            checked = state.isHotspotAutoPopupEnabled,
                            onCheckedChange = { onToggleHotspotAutoPopup(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = CyanGateway,
                                uncheckedThumbColor = p.textMuted,
                                uncheckedTrackColor = p.card
                            ),
                            modifier = Modifier.testTag("toggle_hotspot_popup_switch")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (state.isSimpleMode) {
                    // Streamlined Easy Mode Auto-Connection Status Summary
                    Surface(
                        color = p.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldMesh.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "AUTOMATIC MULTI-RADIO MESH",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = EmeraldMesh,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                                Text(
                                    text = "ACTIVE PEERS: ${state.activePeerCount}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = CyanGateway,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                            }
                            Text(
                                text = "BLE 1M/Coded/2M, Wi-Fi Direct DNS-SD, Swarm Hotspot, and LAN UDP/mDNS are managing connections automatically in the background.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = p.textSecondary,
                                    fontSize = 11.sp
                                )
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = state.hotspotButtonText,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = EmeraldMesh,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                TextButton(onClick = { onPingMeshNow() }) {
                                    Icon(Icons.Default.Sensors, contentDescription = null, tint = EmeraldMesh, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Ping Peers Now", color = EmeraldMesh, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    // Pro Mode Sub-Menu: Separate Host Hotspot / Join Hotspot Controls + Full RF Diagnostics
                    Surface(
                        color = p.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (state.hotspotState.isActive) EmeraldMesh else CyanGateway.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.WifiTethering,
                                        contentDescription = "Hotspot",
                                        tint = if (state.hotspotState.isActive) EmeraldMesh else CyanGateway,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "PRO HOTSPOT & WI-FI DIRECT CONTROL",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                color = if (state.hotspotState.isActive) EmeraldMesh else CyanGateway,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                        Text(
                                            text = "${state.hotspotButtonText} • UDP: 45888 • TCP: 45889",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = p.textSecondary,
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                    }
                                }
                            }

                            // Separate HOST HOTSPOT, JOIN HOTSPOT, and STOP buttons (Never conflict!)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onHostHotspot() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = EmeraldMesh,
                                        contentColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .weight(1f, fill = true)
                                        .widthIn(min = 110.dp)
                                        .heightIn(min = 44.dp)
                                        .testTag("pro_host_hotspot_button")
                                ) {
                                    Icon(Icons.Default.WifiTethering, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("HOST HOTSPOT", fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                }

                                Button(
                                    onClick = { onJoinHotspot() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CyanGateway,
                                        contentColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .weight(1f, fill = true)
                                        .widthIn(min = 110.dp)
                                        .heightIn(min = 44.dp)
                                        .testTag("pro_join_hotspot_button")
                                ) {
                                    Icon(Icons.Default.WifiFind, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("JOIN HOTSPOT", fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                }

                                if (state.hotspotState.isActive || state.hotspotButtonText != "HOTSPOT: OFF") {
                                    OutlinedButton(
                                        onClick = { onStopHotspot() },
                                        border = androidx.compose.foundation.BorderStroke(1.dp, RedSosAlert),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                        modifier = Modifier
                                            .heightIn(min = 44.dp)
                                            .testTag("pro_stop_hotspot_button")
                                    ) {
                                        Text("STOP", color = RedSosAlert, fontSize = 9.sp, maxLines = 1, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }

                            if (state.hotspotState.ssid.isNotBlank() || state.hotspotState.isActive) {
                                Surface(
                                    color = p.background,
                                    shape = RoundedCornerShape(6.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldMeshDim),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "SSID: ${state.hotspotState.ssid.ifBlank { "Auto" }}",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = EmeraldMesh,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            )
                                            Text(
                                                text = "PASS: ${state.hotspotState.passphrase.ifBlank { "Auto" }}",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = CyanGateway,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = state.hotspotState.statusMessage,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = p.textSecondary,
                                                fontSize = 9.sp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    com.example.ui.components.GattTwoWayLinkDiagnosticCard(
                        connectedServerDevices = state.connectedServerDeviceAddresses,
                        connectedGattClients = state.connectedGattClientAddresses,
                        isTwoWaySymmetric = state.isTwoWayGattSymmetric,
                        onForceReDiscovery = onForceGattReDiscovery
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Live Frame Telemetry Counters & Multi-Layer Omni Summary
                    Surface(
                        color = p.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, p.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "TX FRAMES: ${state.txFramesCount}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = EmeraldMesh,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = "RX FRAMES: ${state.rxFramesCount}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = CyanGateway,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = "DTN: ${state.pendingDtnCount}/50",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = AmberRootBoost,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                            Text(
                                text = "L1 BLE 1M • L2 BLE Coded LR • L3 BLE 2M • L4 Wi-Fi P2P DNS-SD • L5 NAN • L6 IPv6/mDNS • L7 USB/Eth • L8 eSIM Hybrid",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = p.textSecondary,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // SECTION 2: 75+ PHONE CHIPSET DATABASE & HARDWARE MATCHING
        // -------------------------------------------------------------
        ElevatedCard(
            shape = cardShape,
            colors = CardDefaults.elevatedCardColors(containerColor = p.card),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, p.border, cardShape)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (p.isAero) {
                            AeroGlossBadgeIcon(
                                imageVector = Icons.Default.Memory,
                                accentColor = EmeraldMesh,
                                contentDescription = "Chipset",
                                badgeSize = 30.dp,
                                iconSize = 16.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = "Chipset",
                                tint = EmeraldMesh,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Universal Hardware Profile",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = p.textPrimary
                            )
                        )
                    }

                    Button(
                        onClick = onAutoMatchHardware,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldMesh),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 36.dp)
                    ) {
                        Text("AUTO-MATCH", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Works universally on any Android device via live OS/radio inspection plus 75+ tuned chipset driver profiles.",
                    style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Host detected info banner
                Surface(
                    color = p.surfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, p.border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val hw = state.detectedHardware
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("DETECTED HOST:", style = MaterialTheme.typography.labelSmall.copy(color = p.textMuted))
                            Text(
                                text = "${hw?.manufacturer ?: ""} ${hw?.model ?: "Android"}",
                                style = MaterialTheme.typography.labelSmall.copy(color = EmeraldMesh, fontWeight = FontWeight.Bold)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("OS / COMPUTE:", style = MaterialTheme.typography.labelSmall.copy(color = p.textMuted))
                            Text(
                                text = if (hw != null) {
                                    "Android ${hw.androidRelease} (API ${hw.androidSdkInt}) • ${hw.cpuCores}C • ${hw.totalRamMb} MB"
                                } else "Detecting...",
                                style = MaterialTheme.typography.labelSmall.copy(color = p.textSecondary)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("SOC / GPU:", style = MaterialTheme.typography.labelSmall.copy(color = p.textMuted))
                            Text(
                                text = "${hw?.resolvedSocDisplay ?: "SoC"} (${hw?.gpuRenderer?.take(16) ?: ""})",
                                style = MaterialTheme.typography.labelSmall.copy(color = p.textSecondary)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("RADIOS:", style = MaterialTheme.typography.labelSmall.copy(color = p.textMuted))
                            Text(
                                text = hw?.supportedRadioLayersSummary ?: "BLE 1M",
                                style = MaterialTheme.typography.labelSmall.copy(color = CyanGateway)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("ACTIVE PROFILE:", style = MaterialTheme.typography.labelSmall.copy(color = p.textMuted))
                            Text(
                                text = state.selectedPhoneProfile.modelName,
                                style = MaterialTheme.typography.labelSmall.copy(color = EmeraldMesh, fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showProfilePickerModal = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldMesh)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = EmeraldMesh, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SELECT FROM 75+ PHONE PROFILES", color = EmeraldMesh, fontWeight = FontWeight.Bold)
                }
            }
        }

        // -------------------------------------------------------------
        // SECTION 3: TACTICAL MESH LINK-QUALITY SWEEP & AUTO CHECK-IN TIMER
        // -------------------------------------------------------------
        ElevatedCard(
            shape = cardShape,
            colors = CardDefaults.elevatedCardColors(containerColor = p.card),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, p.emerald.copy(alpha = 0.7f), cardShape)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (p.isAero) {
                            AeroGlossBadgeIcon(
                                imageVector = Icons.Default.Radar,
                                accentColor = EmeraldMesh,
                                contentDescription = "Mesh Radar",
                                badgeSize = 28.dp,
                                iconSize = 15.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Radar,
                                contentDescription = "Mesh Radar",
                                tint = EmeraldMesh,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (state.isSimpleMode) "Automated Check-In Timer" else "Mesh Link Analyzer & Auto Check-In",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = p.textPrimary
                            )
                        )
                    }
                    Surface(
                        color = EmeraldMeshDim,
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldMesh)
                    ) {
                        Text(
                            text = if (state.autoCheckInIntervalMin > 0) "BEACON: ${state.autoCheckInIntervalMin}M" else if (state.isSimpleMode) "STANDBY" else "DIAGNOSTIC",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = EmeraldMesh,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (!state.isSimpleMode) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Probes multi-hop link margin (dB), active radio layers, and flushes queued Store-and-Forward DTN packets, or broadcasts automated periodic status check-ins.",
                        style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        color = p.background,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, p.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = state.lastPingSweepReport,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (state.isPingSweepRunning) AmberRootBoost else EmeraldMesh,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = onRunMeshLinkSweep,
                        enabled = !state.isPingSweepRunning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldMesh,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 42.dp)
                            .testTag("run_mesh_link_sweep_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.NetworkCheck,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (state.isPingSweepRunning) "PROBING ALL RADIO LAYERS..." else "RUN MESH LINK-QUALITY SWEEP & FLUSH DTN",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = p.border)
                }
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Automated Tactical Check-In Beacon (Dead-Man Timer)",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = p.textPrimary
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Automatically broadcasts your battery level and privacy-filtered GPS status to the team mesh at a fixed interval.",
                    style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(p.surfaceVariant)
                        .border(1.dp, p.border, RoundedCornerShape(8.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val intervals = listOf(0 to "OFF", 1 to "1 MIN", 5 to "5 MIN", 15 to "15 MIN")
                    intervals.forEach { (mins, label) ->
                        val isSelected = state.autoCheckInIntervalMin == mins
                        val pillColor = if (mins == 0) CyanGateway else EmeraldMesh
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 38.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) pillColor else Color.Transparent)
                                .clickable { onSelectAutoCheckInInterval(mins) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (isSelected) Color.Black else p.textSecondary,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                )
                            )
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // SECTION 3B: ECDH P-256 E2EE + TOFU, PASSPHRASE (PRO ONLY) & BLE PRIVACY
        // -------------------------------------------------------------
        ElevatedCard(
            shape = cardShape,
            colors = CardDefaults.elevatedCardColors(containerColor = p.card),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, p.border, cardShape)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (!state.isSimpleMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (p.isAero) {
                                AeroGlossBadgeIcon(
                                    imageVector = Icons.Default.Key,
                                    accentColor = AmberRootBoost,
                                    contentDescription = "ECDH Key Security",
                                    badgeSize = 28.dp,
                                    iconSize = 15.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = "ECDH Key Security",
                                    tint = AmberRootBoost,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ECDH P-256 E2EE + TOFU",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = p.textPrimary
                                )
                            )
                        }

                        Surface(
                            color = p.surfaceVariant,
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldMesh)
                        ) {
                            Text(
                                text = "ECDH P-256",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = EmeraldMesh,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Real secp256r1 ECDH + AES-256-GCM protects 1-on-1 DMs with Trust-On-First-Use (TOFU) safety fingerprints. Group chats use random 256-bit keys wrapped per member via ECDH.",
                        style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Local Key Fingerprint (Epoch ${state.encryptionKeyEpoch})",
                                style = MaterialTheme.typography.labelSmall,
                                color = p.textMuted
                            )
                            Text(
                                text = state.x25519Fingerprint,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = EmeraldMesh,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                        OutlinedButton(
                            onClick = onRotateKeys,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AmberRootBoost)
                        ) {
                            Text("Rotate EC Keys", color = AmberRootBoost, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = p.border)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Encrypted Hybrid Mesh Passphrase (hashes ntfy.sh topic + encrypts PM2 payloads)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Encrypted Hybrid Passphrase (ntfy.sh)",
                                style = MaterialTheme.typography.labelSmall,
                                color = p.textMuted
                            )
                            Text(
                                text = if (state.meshPassphrase.isBlank()) "Default Passphrase" else "•••••• (${state.meshPassphrase.length} chars • AES-256-GCM)",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = CyanGateway,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                        TextButton(onClick = {
                            tempPassphrase = state.meshPassphrase
                            editPassphraseDialog = true
                        }) {
                            Text("Edit", color = CyanGateway)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = p.border)
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // 3-Option Location Privacy Selector (OFF | COARSE | EXACT)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Location Sharing Privacy (BLE Beacon & Mesh Packets)",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = p.textPrimary
                        )
                    )
                    Text(
                        text = when (state.locationShareMode) {
                            LocationShareMode.OFF -> "OFF: Sends 0.0, 0.0 (all zero bytes) in BLE beacons and outgoing packets."
                            LocationShareMode.COARSE -> "COARSE: Rounds coordinates to 2 decimal places (~1.1 km neighborhood grid)."
                            LocationShareMode.EXACT -> "EXACT: Broadcasts full 6-decimal-place GPS precision to peers."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(p.surfaceVariant)
                            .border(1.dp, p.border, RoundedCornerShape(8.dp))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LocationShareMode.entries.forEach { mode ->
                            val isSelected = state.locationShareMode == mode
                            val activeBg = when (mode) {
                                LocationShareMode.OFF -> EmeraldMesh
                                LocationShareMode.COARSE -> AmberRootBoost
                                LocationShareMode.EXACT -> CyanGateway
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 42.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) activeBg else Color.Transparent)
                                    .clickable { onSelectLocationShareMode(mode) }
                                    .testTag("settings_loc_mode_${mode.name.lowercase()}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isSelected) Color.Black else p.textSecondary
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // SECTION 4: OPERATOR PROFILE, UNIQUE @USERNAME & RECOVERY KEY
        // -------------------------------------------------------------
        ElevatedCard(
            shape = cardShape,
            colors = CardDefaults.elevatedCardColors(containerColor = p.card),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, p.border, cardShape)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p.isAero) {
                        AeroGlossBadgeIcon(
                            imageVector = Icons.Default.Badge,
                            accentColor = p.cyan,
                            contentDescription = "Operator Identity",
                            badgeSize = 28.dp,
                            iconSize = 15.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = "Operator Identity & Unique @Username",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = p.textPrimary
                        )
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Reserve a unique @username protected by a 4-6 digit PIN and Recovery Key so distant friends can connect over Hybrid Relay and you can restore your identity on another device.",
                    style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Unique @Username + Recovery Key block
                Surface(
                    color = p.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (state.claimedUsername.isNotBlank()) p.emerald else p.border
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Reserved Username (First-Come First-Serve)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = p.textMuted
                                )
                                Text(
                                    text = when {
                                        state.claimedUsername.isNotBlank() && state.isUsernameOnHold ->
                                            "@${state.claimedUsername} • ON HOLD (OFFLINE)"
                                        state.claimedUsername.isNotBlank() ->
                                            "@${state.claimedUsername}"
                                        else -> "Not Reserved (Local Node Only)"
                                    },
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = when {
                                            state.claimedUsername.isNotBlank() && state.isUsernameOnHold -> p.amber
                                            state.claimedUsername.isNotBlank() -> p.emerald
                                            else -> p.textSecondary
                                        },
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                                if (state.usernameAuthStatus.isNotBlank()) {
                                    Text(
                                        text = state.usernameAuthStatus,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = if (state.isUsernameOnHold) p.amber else p.textSecondary,
                                            fontSize = 10.sp
                                        )
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    accountDialogStatus = ""
                                    accountDialogRecoveryMode = state.claimedUsername.isBlank()
                                    accountUsernameInput = state.claimedUsername
                                    accountPinInput = ""
                                    accountRecoveryKeyInput = ""
                                    showUsernameAccountDialog = true
                                },
                                shape = btnShape,
                                border = androidx.compose.foundation.BorderStroke(1.dp, p.cyan),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("settings_username_account_button")
                            ) {
                                Text(
                                    text = if (state.claimedUsername.isNotBlank()) "MANAGE / SWITCH" else "RESERVE / RECOVER",
                                    color = p.cyan,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        // Encrypted Relay Backend Row (Free Built-in E2EE vs Free Cloudflare D1)
                        HorizontalDivider(color = p.border.copy(alpha = 0.5f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (state.usernameRelayUrl.isNotBlank()) {
                                        "Relay: Cloudflare D1 Atomic E2EE"
                                    } else {
                                        "Relay: Free Built-in E2EE (PBKDF2 + ECDSA + PMU2)"
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (state.usernameRelayUrl.isNotBlank()) p.emerald else p.cyan,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp
                                    )
                                )
                                val subStatus = state.usernameRelayTestStatus.ifBlank {
                                    if (state.usernameRelayUrl.isNotBlank()) state.usernameRelayUrl
                                    else "Zero-cost encrypted mode • Optional: set free Cloudflare Worker URL for permanent DB"
                                }
                                Text(
                                    text = subStatus,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = p.textMuted,
                                        fontSize = 9.sp
                                    ),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(
                                onClick = {
                                    relayUrlInput = state.usernameRelayUrl
                                    relayDialogFeedback = ""
                                    showRelayUrlDialog = true
                                },
                                modifier = Modifier.testTag("settings_username_relay_button")
                            ) {
                                Text(
                                    text = if (state.usernameRelayUrl.isNotBlank()) "EDIT URL" else "CLOUDFLARE URL",
                                    color = p.cyan,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        if (state.claimedUsername.isNotBlank()) {
                            HorizontalDivider(color = p.border.copy(alpha = 0.5f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Encrypted Cross-Device Data Sync",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = p.emerald,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp
                                        )
                                    )
                                    Text(
                                        text = state.lastAccountBackupSyncStatus.ifBlank {
                                            "Moves chats, contacts, keys, groups & settings when logging in on another device."
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = p.textMuted,
                                            fontSize = 9.sp
                                        ),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(
                                    onClick = { onSyncAccountBackupNow { _, _ -> } },
                                    modifier = Modifier.testTag("settings_sync_backup_button")
                                ) {
                                    Text(
                                        text = "SYNC NOW",
                                        color = p.emerald,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Node ID (16-bit Hex)",
                            style = MaterialTheme.typography.labelSmall,
                            color = p.textMuted
                        )
                        Text(
                            text = state.localNodeId,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = EmeraldMesh,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }

                    OutlinedButton(
                        onClick = onRegenerateNodeId,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldMesh)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = EmeraldMesh, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Regenerate ID", color = EmeraldMesh)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = p.border)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Callsign / Display Name",
                            style = MaterialTheme.typography.labelSmall,
                            color = p.textMuted
                        )
                        Text(
                            text = state.operatorCallsign,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = p.textPrimary
                            )
                        )
                    }

                    TextButton(onClick = {
                        tempCallsign = state.operatorCallsign
                        editCallsignDialog = true
                    }) {
                        Text("Edit Name", color = CyanGateway)
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // SECTION 5: REAL GPS SENSOR & COORDINATE MANAGEMENT
        // -------------------------------------------------------------
        ElevatedCard(
            shape = cardShape,
            colors = CardDefaults.elevatedCardColors(containerColor = p.card),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, p.border, cardShape)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p.isAero) {
                        AeroGlossBadgeIcon(
                            imageVector = Icons.Default.GpsFixed,
                            accentColor = if (state.isGpsLocked) EmeraldMesh else AmberRootBoost,
                            contentDescription = "GPS",
                            badgeSize = 28.dp,
                            iconSize = 15.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.GpsFixed,
                            contentDescription = "GPS",
                            tint = if (state.isGpsLocked) EmeraldMesh else AmberRootBoost,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "GPS & Location Telemetry",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = p.textPrimary
                        )
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.gpsStatusMessage,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = if (state.isGpsLocked) EmeraldMesh else AmberRootBoost
                )

                if (state.userLatitude != null && state.userLongitude != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Current Coords: %.5f, %.5f".format(state.userLatitude, state.userLongitude),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = p.textMuted
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onRequestGpsPermission,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldMesh,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Request GPS", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { manualGpsDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyanGateway)
                    ) {
                        Icon(Icons.Default.EditLocation, contentDescription = null, tint = CyanGateway, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Set Map Pin", color = CyanGateway)
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // PULSEMESH v1.1 TACTICAL SUITE (RELAY FGS, HW PTT, VOX, DEAD-RECKONING, GPX, P2P TILES, DURESS)
        // -----------------------------------------------------------------
        Card(
            colors = CardDefaults.cardColors(containerColor = p.card),
            shape = cardShape,
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CyanGateway.copy(alpha = 0.55f), cardShape)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.RocketLaunch,
                        contentDescription = "v1.1 Tactical Upgrades",
                        tint = CyanGateway,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "v1.1 Field Relay, Audio & Security",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = CyanGateway
                        )
                    )
                }

                // 1. Background Foreground Relay Service
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "24/7 Background Mesh Relay Service",
                            style = MaterialTheme.typography.bodyMedium.copy(color = p.textPrimary, fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Holds WakeLock & MulticastLock while screen is off",
                            style = MaterialTheme.typography.labelSmall.copy(color = p.textSecondary)
                        )
                    }
                    Switch(
                        checked = state.isBackgroundRelayServiceEnabled,
                        onCheckedChange = { onToggleBackgroundRelayService() },
                        modifier = Modifier.testTag("toggle_background_relay")
                    )
                }

                // 2. Hardware Volume-Down PTT
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Hardware Volume-Down PTT",
                            style = MaterialTheme.typography.bodyMedium.copy(color = p.textPrimary, fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Hold Volume Down or Headset button to talk",
                            style = MaterialTheme.typography.labelSmall.copy(color = p.textSecondary)
                        )
                    }
                    Switch(
                        checked = state.isVolumeButtonPttEnabled,
                        onCheckedChange = { onToggleVolumeButtonPtt() },
                        modifier = Modifier.testTag("toggle_hw_volume_ptt")
                    )
                }

                // 3. Wired Baofeng Radio VOX Pre-Key Pilot
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Wired Radio VOX Pre-Key Pilot",
                            style = MaterialTheme.typography.bodyMedium.copy(color = p.textPrimary, fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Prepends 180ms 1kHz tone for Baofeng K1 / UHF VOX",
                            style = MaterialTheme.typography.labelSmall.copy(color = p.textSecondary)
                        )
                    }
                    Switch(
                        checked = state.isRadioVoxBridgeEnabled,
                        onCheckedChange = { onToggleRadioVoxBridge() },
                        modifier = Modifier.testTag("toggle_vox_bridge")
                    )
                }

                // 4. Dead-Reckoning Step Counter Fallback
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Step Dead-Reckoning (${state.stepCount} steps)",
                            style = MaterialTheme.typography.bodyMedium.copy(color = p.textPrimary, fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Estimates movement via compass + steps when GPS is jammed",
                            style = MaterialTheme.typography.labelSmall.copy(color = p.textSecondary)
                        )
                    }
                    Switch(
                        checked = state.isDeadReckoningEnabled,
                        onCheckedChange = { onToggleDeadReckoning() },
                        modifier = Modifier.testTag("toggle_dead_reckoning")
                    )
                }

                // 5. Breadcrumb Trail & GPX Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Offline Breadcrumb Trail (${state.breadcrumbs.size} pts)",
                            style = MaterialTheme.typography.bodyMedium.copy(color = p.textPrimary, fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Records local track + GPX import/export",
                            style = MaterialTheme.typography.labelSmall.copy(color = p.textSecondary)
                        )
                    }
                    Switch(
                        checked = state.isBreadcrumbRecording,
                        onCheckedChange = { onToggleBreadcrumbRecording() },
                        modifier = Modifier.testTag("toggle_breadcrumbs")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = onShareBreadcrumbTrail,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Broadcast Trail", fontSize = 11.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            gpxTextInput = onExportGpx()
                            gpxFeedback = "Exported ${state.breadcrumbs.size} track points to GPX."
                            showGpxDialog = true
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("GPX I/O", fontSize = 11.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onClearBreadcrumbTrail,
                        modifier = Modifier.weight(0.8f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Clear", fontSize = 11.sp, color = RedSosAlert, maxLines = 1)
                    }
                }

                // 6. P2P Offline Map Tile Sync
                Text(
                    text = state.p2pTileSyncStatus,
                    style = MaterialTheme.typography.labelSmall.copy(color = p.textMuted, fontFamily = FontFamily.Monospace)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onBroadcastOfflineMapTiles,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanGateway, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Beam Map Tiles P2P", fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onTransmitKeyVerifyChirp,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Ultrasonic Verify Key", fontSize = 11.sp, maxLines = 1)
                    }
                }

                // 7. Auto-Burn TTL & Duress PIN Wipe
                val burnLabel = when (state.messageBurnTtlSeconds) {
                    60 -> "1 MIN"
                    300 -> "5 MIN"
                    3600 -> "1 HOUR"
                    86400 -> "24 HOURS"
                    else -> "OFF"
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCycleMessageBurnTtl,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Burn TTL: $burnLabel", fontSize = 11.sp, maxLines = 1)
                    }
                    Button(
                        onClick = {
                            duressPinInput = ""
                            duressFeedback = ""
                            showDuressDialog = true
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.hasDuressPin) AmberRootBoost else RedSosAlert,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            if (state.hasDuressPin) "Duress PIN (Armed)" else "Set Duress Wipe PIN",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }

    // Modal: 75+ Phone Profile Picker
    if (showProfilePickerModal) {
        AlertDialog(
            onDismissRequest = { showProfilePickerModal = false },
            containerColor = p.card,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = "SELECT CHIPSET PROFILE (75+ MODELS)",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = EmeraldMesh,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                )
            },
            text = {
                Box(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
                        .heightIn(max = 420.dp)
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(DeviceCatalog.allModels) { profile ->
                            val isSelected = profile.id == state.selectedPhoneProfile.id
                            Surface(
                                color = if (isSelected) EmeraldMeshDim else p.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) EmeraldMesh else p.border
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectPhoneProfile(profile)
                                        showProfilePickerModal = false
                                    }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = profile.modelName,
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                color = if (isSelected) EmeraldMesh else p.textPrimary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = EmeraldMesh, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${profile.socName} • ${profile.wifiBleDriver.take(30)}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = p.textSecondary,
                                            fontSize = 9.sp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProfilePickerModal = false }) {
                    Text("CLOSE", color = EmeraldMesh)
                }
            }
        )
    }

    // Dialog: Edit Callsign
    if (editCallsignDialog) {
        AlertDialog(
            onDismissRequest = { editCallsignDialog = false },
            containerColor = p.card,
            title = { Text("Update Operator Callsign", color = EmeraldMesh) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = tempCallsign,
                        onValueChange = { tempCallsign = it },
                        label = { Text("Callsign", color = p.textSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = EmeraldMesh,
                            unfocusedBorderColor = p.border,
                            focusedTextColor = p.textPrimary,
                            unfocusedTextColor = p.textPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempCallsign.isNotBlank()) {
                            onUpdateCallsign(tempCallsign.trim())
                        }
                        editCallsignDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldMesh)
                ) {
                    Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editCallsignDialog = false }) {
                    Text("Cancel", color = p.textSecondary)
                }
            }
        )
    }

    // Dialog: Edit Channel
    if (editChannelDialog) {
        AlertDialog(
            onDismissRequest = { editChannelDialog = false },
            containerColor = p.card,
            title = { Text("Change Mesh Channel", color = CyanGateway) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(
                        "Set the same channel identifier on all phones so they discover each other.",
                        style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempChannel,
                        onValueChange = { tempChannel = it.trim().lowercase().replace(" ", "_") },
                        label = { Text("Channel Identifier", color = p.textSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanGateway,
                            unfocusedBorderColor = p.border,
                            focusedTextColor = p.textPrimary,
                            unfocusedTextColor = p.textPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempChannel.isNotBlank()) {
                            onUpdateMeshChannel(tempChannel.trim())
                        }
                        editChannelDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanGateway)
                ) {
                    Text("Connect", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editChannelDialog = false }) {
                    Text("Cancel", color = p.textSecondary)
                }
            }
        )
    }

    // Dialog: Manual GPS Pin
    if (manualGpsDialog) {
        AlertDialog(
            onDismissRequest = { manualGpsDialog = false },
            containerColor = p.card,
            title = { Text("Set Manual Location Pin", color = AmberRootBoost) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Enter coordinates manually for testing or indoor zero-GPS scenarios.",
                        style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                    )
                    OutlinedTextField(
                        value = manualLatText,
                        onValueChange = { manualLatText = it },
                        label = { Text("Latitude (e.g. 37.7749)", color = p.textSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberRootBoost,
                            unfocusedBorderColor = p.border,
                            focusedTextColor = p.textPrimary,
                            unfocusedTextColor = p.textPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = manualLonText,
                        onValueChange = { manualLonText = it },
                        label = { Text("Longitude (e.g. -122.4194)", color = p.textSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberRootBoost,
                            unfocusedBorderColor = p.border,
                            focusedTextColor = p.textPrimary,
                            unfocusedTextColor = p.textPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val lat = manualLatText.toDoubleOrNull()
                        val lon = manualLonText.toDoubleOrNull()
                        if (lat != null && lon != null) {
                            onSetManualLocation(lat, lon)
                        }
                        manualGpsDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AmberRootBoost)
                ) {
                    Text("Set Location", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { manualGpsDialog = false }) {
                    Text("Cancel", color = p.textSecondary)
                }
            }
        )
    }

    // Dialog: Edit Encrypted Hybrid Passphrase
    if (editPassphraseDialog) {
        AlertDialog(
            onDismissRequest = { editPassphraseDialog = false },
            containerColor = p.card,
            title = { Text("Hybrid Relay Passphrase", color = CyanGateway) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Set the same shared passphrase on your team's devices to derive the private hashed ntfy.sh topic and AES-256-GCM payload encryption key.",
                        style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                    )
                    OutlinedTextField(
                        value = tempPassphrase,
                        onValueChange = { tempPassphrase = it },
                        label = { Text("Team Passphrase", color = p.textSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanGateway,
                            unfocusedBorderColor = p.border,
                            focusedTextColor = p.textPrimary,
                            unfocusedTextColor = p.textPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempPassphrase.isNotBlank()) {
                            onUpdateMeshPassphrase(tempPassphrase.trim())
                        }
                        editPassphraseDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanGateway)
                ) {
                    Text("Apply", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editPassphraseDialog = false }) {
                    Text("Cancel", color = p.textSecondary)
                }
            }
        )
    }

    // Dialog: Reserve or Recover Unique @Username
    if (showUsernameAccountDialog) {
        AlertDialog(
            onDismissRequest = { showUsernameAccountDialog = false },
            containerColor = p.card,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = if (accountDialogRecoveryMode) "RECOVER / LOGIN @USERNAME" else "RESERVE UNIQUE @USERNAME",
                    color = p.cyan,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { accountDialogRecoveryMode = false },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (!accountDialogRecoveryMode) p.emeraldDim else Color.Transparent
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (!accountDialogRecoveryMode) p.emerald else p.border),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("CLAIM NEW", color = if (!accountDialogRecoveryMode) p.emerald else p.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { accountDialogRecoveryMode = true },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (accountDialogRecoveryMode) p.cyanDim else Color.Transparent
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (accountDialogRecoveryMode) p.cyan else p.border),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("RECOVER / LOGIN", color = if (accountDialogRecoveryMode) p.cyan else p.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedTextField(
                        value = accountUsernameInput,
                        onValueChange = { accountUsernameInput = it.lowercase().replace(" ", "") },
                        label = { Text("Unique Username (3-20 chars)", color = p.textSecondary) },
                        prefix = { Text("@", color = p.cyan, fontWeight = FontWeight.Bold) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = p.cyan,
                            unfocusedBorderColor = p.border,
                            focusedTextColor = p.textPrimary,
                            unfocusedTextColor = p.textPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_username_input")
                    )

                    if (accountDialogRecoveryMode) {
                        Text(
                            text = "Log in with your 4-6 digit PIN OR your Recovery Key to move all your E2EE keys, chats, contacts, groups & settings to this device.",
                            style = MaterialTheme.typography.bodySmall.copy(color = p.cyan, fontSize = 10.sp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { accountDialogRecoveryWithKey = false },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (!accountDialogRecoveryWithKey) p.emeraldDim else Color.Transparent
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (!accountDialogRecoveryWithKey) p.emerald else p.border),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_login_with_pin_tab")
                            ) {
                                Text("WITH PIN", color = if (!accountDialogRecoveryWithKey) p.emerald else p.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { accountDialogRecoveryWithKey = true },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (accountDialogRecoveryWithKey) p.amberDim else Color.Transparent
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (accountDialogRecoveryWithKey) p.amber else p.border),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_login_with_key_tab")
                            ) {
                                Text("WITH RECOVERY KEY", color = if (accountDialogRecoveryWithKey) p.amber else p.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (!accountDialogRecoveryMode || !accountDialogRecoveryWithKey) {
                        OutlinedTextField(
                            value = accountPinInput,
                            onValueChange = { ch -> accountPinInput = ch.filter { it.isDigit() }.take(6) },
                            label = { Text(if (accountDialogRecoveryMode) "Enter 4-6 Digit PIN" else "Create 4-6 Digit PIN", color = p.textSecondary) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = p.emerald,
                                unfocusedBorderColor = p.border,
                                focusedTextColor = p.textPrimary,
                                unfocusedTextColor = p.textPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_pin_input")
                        )
                    }

                    if (accountDialogRecoveryMode && accountDialogRecoveryWithKey) {
                        OutlinedTextField(
                            value = accountRecoveryKeyInput,
                            onValueChange = { accountRecoveryKeyInput = it.uppercase().trim() },
                            label = { Text("Recovery Key (PMRK-XXXX-XXXX-XXXX)", color = p.textSecondary) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = p.amber,
                                unfocusedBorderColor = p.border,
                                focusedTextColor = p.textPrimary,
                                unfocusedTextColor = p.textPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_recovery_key_input")
                        )
                    }

                    if (accountDialogStatus.isNotBlank()) {
                        Text(
                            text = accountDialogStatus,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (accountDialogSuccess) p.emerald else p.red,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClaimOrRecoverUsername(
                            accountUsernameInput,
                            if (!accountDialogRecoveryMode || !accountDialogRecoveryWithKey) accountPinInput else "",
                            if (accountDialogRecoveryMode && accountDialogRecoveryWithKey) accountRecoveryKeyInput else "",
                            accountDialogRecoveryMode
                        ) { ok, msg ->
                            accountDialogSuccess = ok
                            accountDialogStatus = msg
                            if (ok) {
                                showUsernameAccountDialog = false
                            }
                        }
                    },
                    enabled = !state.isReservingUsername,
                    colors = ButtonDefaults.buttonColors(containerColor = p.cyan),
                    modifier = Modifier.testTag("settings_username_submit_button")
                ) {
                    Text(
                        text = if (state.isReservingUsername) "MOVING DATA..." else if (accountDialogRecoveryMode) "LOGIN & MOVE DATA" else "CLAIM @USERNAME",
                        color = if (p.isLight || p.isAero) Color.White else Color.Black,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showUsernameAccountDialog = false }) {
                    Text("Cancel", color = p.textSecondary)
                }
            }
        )
    }

    if (showRelayUrlDialog) {
        AlertDialog(
            onDismissRequest = { showRelayUrlDialog = false },
            containerColor = p.card,
            title = {
                Text(
                    text = "FREE ENCRYPTED RELAY URL",
                    color = p.cyan,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Leave blank for the zero-setup Free Built-in E2EE relay (PBKDF2 vaults + ECDSA signatures + ECDH PMU2 inbox), OR paste your $0/mo Cloudflare Worker URL from cloudflare-username-relay/ for permanent D1 atomic locks.",
                        style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary, fontSize = 11.sp)
                    )
                    OutlinedTextField(
                        value = relayUrlInput,
                        onValueChange = { relayUrlInput = it.trim() },
                        label = { Text("https://pulsemesh-username-relay.<you>.workers.dev", fontSize = 10.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = p.textPrimary,
                            unfocusedTextColor = p.textPrimary,
                            focusedBorderColor = p.cyan,
                            unfocusedBorderColor = p.border
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_relay_url_input")
                    )
                    if (relayDialogFeedback.isNotBlank()) {
                        Text(
                            text = relayDialogFeedback,
                            style = MaterialTheme.typography.labelSmall.copy(color = p.amber, fontSize = 10.sp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateUsernameRelayUrl(relayUrlInput) { ok, msg ->
                            relayDialogFeedback = msg
                            if (ok) {
                                showRelayUrlDialog = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = p.cyan),
                    modifier = Modifier.testTag("settings_relay_url_save_button")
                ) {
                    Text(
                        text = "TEST & SAVE",
                        color = if (p.isLight || p.isAero) Color.White else Color.Black,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp
                    )
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (relayUrlInput.isNotBlank()) {
                        TextButton(onClick = {
                            relayUrlInput = ""
                            onUpdateUsernameRelayUrl("") { _, _ ->
                                showRelayUrlDialog = false
                            }
                        }) {
                            Text("Reset Built-in", color = p.amber, fontSize = 11.sp)
                        }
                    }
                    TextButton(onClick = { showRelayUrlDialog = false }) {
                        Text("Cancel", color = p.textSecondary)
                    }
                }
            }
        )
    }

    if (showDuressDialog) {
        AlertDialog(
            onDismissRequest = { showDuressDialog = false },
            containerColor = p.card,
            title = {
                Text(
                    text = "DURESS PIN & EMERGENCY WIPE",
                    color = RedSosAlert,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Configure a 4+ digit Duress PIN, or enter your existing Duress PIN and tap 'TRIGGER WIPE' to immediately erase all chat history, breadcrumbs, and private identity keys.",
                        style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary, fontSize = 11.sp)
                    )
                    OutlinedTextField(
                        value = duressPinInput,
                        onValueChange = { duressPinInput = it },
                        label = { Text("4+ Digit Duress PIN", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (duressFeedback.isNotBlank()) {
                        Text(
                            text = duressFeedback,
                            style = MaterialTheme.typography.labelSmall.copy(color = p.amber, fontSize = 10.sp)
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.hasDuressPin) {
                        Button(
                            onClick = {
                                if (onTriggerDuressWipe(duressPinInput)) {
                                    duressFeedback = "Wipe executed. Identity & history purged."
                                    showDuressDialog = false
                                } else {
                                    duressFeedback = "Incorrect Duress PIN."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RedSosAlert, contentColor = Color.White)
                        ) {
                            Text("TRIGGER WIPE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Button(
                        onClick = {
                            if (onSetDuressPin(duressPinInput)) {
                                duressFeedback = if (duressPinInput.isBlank()) "Duress PIN cleared." else "Duress PIN armed."
                                showDuressDialog = false
                            } else {
                                duressFeedback = "Enter at least 4 characters (or leave empty to clear)."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberRootBoost, contentColor = Color.Black)
                    ) {
                        Text("SAVE PIN", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDuressDialog = false }) {
                    Text("Close", color = p.textSecondary)
                }
            }
        )
    }

    if (showGpxDialog) {
        AlertDialog(
            onDismissRequest = { showGpxDialog = false },
            containerColor = p.card,
            title = {
                Text(
                    text = "GPX TRAIL IMPORT / EXPORT",
                    color = CyanGateway,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = gpxTextInput,
                        onValueChange = { gpxTextInput = it },
                        label = { Text("GPX XML Payload", fontSize = 10.sp) },
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (gpxFeedback.isNotBlank()) {
                        Text(
                            text = gpxFeedback,
                            style = MaterialTheme.typography.labelSmall.copy(color = EmeraldMesh, fontSize = 10.sp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val imported = onImportGpx(gpxTextInput)
                        gpxFeedback = if (imported > 0) "Imported $imported GPX track points." else "No valid <trkpt> coordinates found."
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanGateway, contentColor = Color.Black)
                ) {
                    Text("IMPORT GPX", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showGpxDialog = false }) {
                    Text("Close", color = p.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun ProMetricTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val p = LocalPulsePalette.current
    Surface(
        color = p.background,
        shape = tacticalButtonShape(false),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = p.textMuted,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

