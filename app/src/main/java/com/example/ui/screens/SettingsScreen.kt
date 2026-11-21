// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
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
    onToggleWebPortal: () -> Unit = {},
    onDeleteAccountInfo: (username: String, pin: String, recoveryKey: String) -> Pair<Boolean, String> = { _, _, _ -> Pair(false, "") },
    onSetAppLanguage: (String) -> Unit = {},
    onShareLocalFile: (String, String, ByteArray) -> Boolean = { _, _, _ -> false },
    onDeleteSharedFile: (String) -> Unit = {},
    onSendQuickStatus: (com.example.model.QuickStatusCode) -> Unit = {},
    onToggleQuickStatusVoice: () -> Unit = {},
    onSaveIceMedicalCard: (String, String, String) -> Unit = { _, _, _ -> },
    onSetDeadManSwitchMinutes: (Int) -> Unit = {},
    onCheckInDeadManSwitch: () -> Unit = {},
    onConfigureGeofence: (Boolean, Int) -> Unit = { _, _ -> },
    onToggleExpeditionMode: () -> Unit = {},
    onToggleExpeditionAutoBelow20: () -> Unit = {},
    onExportTripArchive: () -> String = { "" },
    onBuildInstantJoinQr: () -> String = { "" },
    onImportInstantJoinQr: (String) -> Pair<Boolean, String> = { Pair(false, "") },
    onSetDecoyPin: (String) -> Boolean = { false },
    onTriggerDecoyMode: (String) -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    val p = LocalPulsePalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
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
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var deleteAccountUsernameInput by remember(state.claimedUsername) { mutableStateOf(state.claimedUsername) }
    var deleteAccountPinInput by remember { mutableStateOf("") }
    var deleteAccountRecoveryInput by remember { mutableStateOf("") }
    var deleteAccountFeedback by remember { mutableStateOf("") }
    var deleteAccountError by remember { mutableStateOf("") }

    // Horizontal Expandable Topic Lanes state (defaults to APPEARANCE)
    var selectedLane by remember { mutableStateOf("APPEARANCE") }
    var iceBloodInput by remember(state.iceBloodType) { mutableStateOf(state.iceBloodType) }
    var iceAllergiesInput by remember(state.iceAllergies) { mutableStateOf(state.iceAllergies) }
    var iceContactInput by remember(state.iceEmergencyContact) { mutableStateOf(state.iceEmergencyContact) }
    var iceSavedNotice by remember { mutableStateOf("") }
    var shareFileNameInput by remember { mutableStateOf("field-note.txt") }
    var shareFileTextInput by remember { mutableStateOf("") }
    var shareFileNotice by remember { mutableStateOf("") }
    var tripExportNotice by remember { mutableStateOf("") }
    var joinQrImportInput by remember { mutableStateOf("") }
    var joinQrFeedback by remember { mutableStateOf("") }
    var showInstantJoinQrDialog by remember { mutableStateOf(false) }
    var decoyPinInput by remember { mutableStateOf("") }
    var decoyFeedback by remember { mutableStateOf("") }

    val isGreek = state.appLanguage == "el"

    Column(
        modifier = modifier
            .fillMaxSize()
            .aeroCanvasBackground(p)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .navigationBarsPadding()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
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
                            text = if (state.isSimpleMode) {
                                if (isGreek) "Διάταξη: Απλή Λειτουργία" else "UI Layout: Easy Mode"
                            } else {
                                if (isGreek) "Διάταξη: Λειτουργία Pro" else "UI Layout: Pro Mode"
                            },
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = p.textPrimary
                            )
                        )
                        Text(
                            text = if (isGreek) "Πάτησε για εναλλαγή Easy / Pro" else "Tap to switch instantly between Easy Mode and Pro Mode",
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
        // HORIZONTAL EXPANDABLE SETTINGS TOPIC LANES
        // -------------------------------------------------------------
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (isGreek) "ΕΠΙΛΕΞΕ ΚΑΤΗΓΟΡΙΑ ΡΥΘΜΙΣΕΩΝ (ΣΥΡΕ ΟΡΙΖΟΝΤΙΑ)" else "TAP A SETTINGS LANE TO EXPAND (SWIPE HORIZONTALLY)",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = p.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            )
            data class SettingsLaneSpec(
                val id: String,
                val labelEn: String,
                val labelEl: String,
                val subEn: String,
                val icon: androidx.compose.ui.graphics.vector.ImageVector,
                val accent: Color
            )
            val lanes = listOf(
                SettingsLaneSpec("APPEARANCE", "THEME & LANG", "ΘΕΜΑ & ΓΛΩΣΣΑ", state.appTheme.displayName.substringBefore(" "), Icons.Default.Palette, p.cyan),
                SettingsLaneSpec("IDENTITY", "ACCOUNT & QR", "ΛΟΓΑΡΙΑΣΜΟΣ", if (state.claimedUsername.isNotBlank()) "@${state.claimedUsername}" else "Cloudflare D1", Icons.Default.Badge, EmeraldMesh),
                SettingsLaneSpec("HOTSPOT", "HOTSPOT & SSL", "HOTSPOT & SSL", if (state.webPortalStatus.isRunning) (if (isGreek) "HTTP+HTTPS ΟΚ" else "HTTP+HTTPS ON") else (if (isGreek) "Πύλη & Mesh" else "Portal & Mesh"), Icons.Default.WifiTethering, CyanGateway),
                SettingsLaneSpec("SAFETY", "SAFETY & SOS", "ΑΣΦΑΛΕΙΑ/SOS", if (state.geofenceEnabled) (if (isGreek) "Περίμετρος ${state.geofenceRadiusMeters}μ" else "Fence ${state.geofenceRadiusMeters}m") else (if (isGreek) "ICE & Κατάσταση" else "ICE & Status"), Icons.Default.HealthAndSafety, RedSosAlert),
                SettingsLaneSpec("MAPS", "MAPS & WEATHER", "ΧΑΡΤΗΣ/ΚΑΙΡΟΣ", if (state.hasBarometerSensor) "${state.barometricPressureHpa?.toInt() ?: "--"} hPa" else "GPS & GPX", Icons.Default.Map, AmberRootBoost),
                SettingsLaneSpec("FILES", "FILES & EXPORT", "ΑΡΧΕΙΑ", if (isGreek) "${state.sharedFiles.size} αρχεία" else "${state.sharedFiles.size} shared", Icons.Default.FolderShared, p.emerald),
                SettingsLaneSpec("POWER", "BATTERY & HW", "ΜΠΑΤΑΡΙΑ/HW", if (state.expeditionModeEnabled) (if (isGreek) "ΕΚΣΤΡΑΤΕΙΑ" else "EXPEDITION") else (if (isGreek) "Ασύρματοι & HW" else "Radios & HW"), Icons.Default.BatterySaver, AmberRootBoost),
                SettingsLaneSpec("SECURITY", "SECURITY/DECOY", "ΑΣΦΑΛΕΙΑ/PIN", if (state.hasDecoyPin) (if (isGreek) "ΔΟΛΩΜΑ ΕΝΕΡΓΟ" else "DECOY ARMED") else (if (isGreek) "Κλειδιά & Wipe" else "Keys & Wipe"), Icons.Default.Security, p.cyan)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (lane in lanes) {
                    val isSelected = selectedLane == lane.id
                    Surface(
                        color = if (isSelected) lane.accent.copy(alpha = 0.22f) else p.card,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            if (isSelected) 1.8.dp else 1.dp,
                            if (isSelected) lane.accent else p.border
                        ),
                        modifier = Modifier
                            .width(122.dp)
                            .clickable { selectedLane = lane.id }
                            .testTag("settings_lane_${lane.id.lowercase()}")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = lane.icon,
                                    contentDescription = lane.labelEn,
                                    tint = lane.accent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Icon(
                                    imageVector = if (isSelected) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = if (isSelected) lane.accent else p.textMuted,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Text(
                                text = if (isGreek) lane.labelEl else lane.labelEn,
                                color = if (isSelected) lane.accent else p.textPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = lane.subEn,
                                color = p.textSecondary,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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
        // LANE 1: APPEARANCE & LANGUAGE (DEFAULT EXPANDED)
        // -------------------------------------------------------------
        if (selectedLane == "APPEARANCE") {
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
                                        onSelectAppTheme(themeValue)
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

        // Multi-Language Switcher (English / Ελληνικά)
        Surface(
            color = p.card,
            shape = cardShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, p.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isGreek) "Γλώσσα Εφαρμογής & Φωνητικών" else "App & Voice Language",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = p.textPrimary)
                    )
                    Text(
                        text = if (isGreek) "English ή Ελληνικά για κείμενο & φωνητικές καταστάσεις" else "Switch UI & Quick-Status voice TTS between English and Greek",
                        style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary, fontSize = 11.sp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { onSetAppLanguage("en") },
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (!isGreek) p.emerald else p.border),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("settings_lang_en_button")
                    ) {
                        Text("EN", fontWeight = FontWeight.Bold, color = if (!isGreek) p.emerald else p.textSecondary, fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { onSetAppLanguage("el") },
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isGreek) p.cyan else p.border),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("settings_lang_el_button")
                    ) {
                        Text("ΕΛ", fontWeight = FontWeight.Bold, color = if (isGreek) p.cyan else p.textSecondary, fontSize = 11.sp)
                    }
                }
            }
        }
        } // end selectedLane == "APPEARANCE"

        // -------------------------------------------------------------
        // PRO MODE COMPACT TELEMETRY & QUICK-ACTION CONSOLE STRIP
        // -------------------------------------------------------------
        if (!state.isSimpleMode && selectedLane == "POWER") {
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
        // LANE: HOTSPOT & SSL (TRANSPORT POSTURE, HOTSPOT & HTTP/HTTPS PORTAL)
        // -------------------------------------------------------------
        if (selectedLane == "HOTSPOT") {
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

                // Wi-Fi Direct Embedded Web Portal & Official iPhone QR Join Card (Visible in both Simple & Pro modes)
                var showIphoneQrDialog by remember { mutableStateOf(false) }
                Surface(
                    color = p.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (state.webPortalStatus.isRunning) EmeraldMesh else CyanGateway.copy(alpha = 0.55f)
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isGreek) "HOTSPOT ΠΥΛΗ WEB (HTTP + HTTPS SSL) & QR" else "HOTSPOT WEB PORTAL (HTTP + HTTPS SSL) & QR",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = if (state.webPortalStatus.isRunning) EmeraldMesh else CyanGateway,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                                Text(
                                    text = if (state.webPortalStatus.isRunning) {
                                        val verifiedStr = when {
                                            state.webPortalStatus.lanSelfTestPassed -> "LAN + LOCAL HTTP 200 OK"
                                            state.webPortalStatus.selfTestPassed -> "LOCAL HTTP 200 OK"
                                            else -> "STARTING"
                                        }
                                        val sslStr = if (state.webPortalStatus.sslEnabled) {
                                            val verified = if (state.webPortalStatus.httpsSelfTestPassed) " ✓" else ""
                                            val err = if (state.webPortalStatus.sslError.isNotBlank()) " [${state.webPortalStatus.sslError}]" else ""
                                            " • SSL :${state.webPortalStatus.httpsPort}$verified$err"
                                        } else ""
                                        "$verifiedStr$sslStr • ${state.webPortalStatus.activeClientCount} linked • ${state.webPortalStatus.requestsServed} hits"
                                    } else if (state.webPortalStatus.bindError.isNotBlank()) {
                                        "Bind error: ${state.webPortalStatus.bindError}"
                                    } else {
                                        "Auto-hosts the PulseMesh Web/iPhone PWA when Local or System Hotspot is active"
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = p.textSecondary,
                                        fontSize = 10.sp
                                    )
                                )
                            }
                            Switch(
                                checked = state.webPortalStatus.isRunning,
                                onCheckedChange = { onToggleWebPortal() },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = EmeraldMesh,
                                    uncheckedThumbColor = p.textMuted,
                                    uncheckedTrackColor = p.card
                                ),
                                modifier = Modifier.testTag("toggle_web_portal_switch")
                            )
                        }

                        if (state.webPortalStatus.isRunning && state.webPortalStatus.portalUrl.isNotBlank()) {
                            Surface(
                                color = p.background,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldMeshDim),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            val primaryLabel = state.webPortalStatus.labeledReachableUrls
                                                .firstOrNull { it.first == state.webPortalStatus.portalUrl }?.second
                                            Text(
                                                text = if (!primaryLabel.isNullOrBlank()) {
                                                    "${state.webPortalStatus.portalUrl}  [$primaryLabel]"
                                                } else {
                                                    state.webPortalStatus.portalUrl
                                                },
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    color = EmeraldMesh,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            )
                                            if (state.webPortalStatus.sslEnabled && state.webPortalStatus.httpsPortalUrl.isNotBlank()) {
                                                Text(
                                                    text = if (state.webPortalStatus.httpsSelfTestPassed) {
                                                        "🔒 HTTPS SSL ✓: ${state.webPortalStatus.httpsPortalUrl}"
                                                    } else {
                                                        "🔒 HTTPS SSL: ${state.webPortalStatus.httpsPortalUrl}"
                                                    },
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = if (state.webPortalStatus.httpsSelfTestPassed) EmeraldMesh else AmberRootBoost,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 10.sp
                                                    ),
                                                    modifier = Modifier.testTag("hotspot_https_url_text")
                                                )
                                            }
                                            if (state.webPortalStatus.sslError.isNotBlank()) {
                                                Text(
                                                    text = "⚠ SSL: ${state.webPortalStatus.sslError}",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        color = RedSosAlert,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 9.sp
                                                    )
                                                )
                                            }
                                            val altLabeled = state.webPortalStatus.labeledReachableUrls.filter { it.first != state.webPortalStatus.portalUrl }
                                            if (altLabeled.isNotEmpty()) {
                                                Text(
                                                    text = "${if (isGreek) "Εναλλ." else "Alt"}: ${altLabeled.joinToString(" • ") { "${it.first} (${it.second})" }}",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        color = CyanGateway,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 9.sp
                                                    )
                                                )
                                            }
                                            Text(
                                                text = if (isGreek) {
                                                    "Συνδέστε το άλλο τηλέφωνο στο Hotspot Wi-Fi, ανοίξτε το HTTPS :8443 για κλήσεις μικροφώνου (ή HTTP :8080 / QR 2 βημάτων). Απενεργοποιήστε προσωρινά τα δεδομένα 4G/5G στο άλλο κινητό."
                                                } else {
                                                    "Connect the other phone to this Hotspot Wi-Fi, open HTTPS :${state.webPortalStatus.httpsPort} for mic calling (or HTTP :${state.webPortalStatus.port} / 2-step QR). Pause mobile data on the other phone if it stays on 4G/5G."
                                                },
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    color = p.textSecondary,
                                                    fontSize = 9.sp
                                                )
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            val hasHttpsUrl = state.webPortalStatus.sslEnabled && state.webPortalStatus.httpsPortalUrl.isNotBlank()
                                            if (hasHttpsUrl) {
                                                TextButton(
                                                    onClick = {
                                                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                                        cm?.setPrimaryClip(
                                                            android.content.ClipData.newPlainText(
                                                                "PulseMesh HTTPS Portal URL",
                                                                state.webPortalStatus.httpsPortalUrl
                                                            )
                                                        )
                                                    }
                                                ) {
                                                    Text(if (isGreek) "ΑΝΤΙΓΡ. HTTPS" else "COPY HTTPS", color = AmberRootBoost, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                                }
                                                TextButton(
                                                    onClick = {
                                                        runCatching {
                                                            val intent = android.content.Intent(
                                                                android.content.Intent.ACTION_VIEW,
                                                                android.net.Uri.parse(state.webPortalStatus.httpsPortalUrl)
                                                            )
                                                            context.startActivity(intent)
                                                        }
                                                    }
                                                ) {
                                                    Text(if (isGreek) "ΑΝΟΙΓΜΑ HTTPS" else "OPEN HTTPS", color = EmeraldMesh, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                                }
                                            }
                                            TextButton(
                                                onClick = {
                                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                                    cm?.setPrimaryClip(
                                                        android.content.ClipData.newPlainText(
                                                            "PulseMesh Web Portal URL",
                                                            state.webPortalStatus.portalUrl
                                                        )
                                                    )
                                                }
                                            ) {
                                                Text(if (isGreek) "ΑΝΤΙΓΡ. HTTP" else "COPY", color = CyanGateway, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                            }
                                            TextButton(
                                                onClick = {
                                                    runCatching {
                                                        val intent = android.content.Intent(
                                                            android.content.Intent.ACTION_VIEW,
                                                            android.net.Uri.parse(state.webPortalStatus.portalUrl)
                                                        )
                                                        context.startActivity(intent)
                                                    }
                                                }
                                            ) {
                                                Text(if (isGreek) "ΔΟΚΙΜΗ" else "TEST", color = EmeraldMesh, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { showIphoneQrDialog = true },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = EmeraldMesh,
                                                contentColor = Color.Black
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .weight(1.4f)
                                                .testTag("show_iphone_qr_button")
                                        ) {
                                            Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                "2-STEP QR CODES",
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                runCatching {
                                                    val tetherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                                                        setClassName("com.android.settings", "com.android.settings.TetherSettings")
                                                    }
                                                    context.startActivity(tetherIntent)
                                                }.onFailure {
                                                    runCatching {
                                                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
                                                    }
                                                }
                                            },
                                            border = androidx.compose.foundation.BorderStroke(1.dp, CyanGateway.copy(alpha = 0.7f)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                "HOTSPOT SETTINGS",
                                                color = CyanGateway,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 9.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (showIphoneQrDialog && state.webPortalStatus.portalUrl.isNotBlank()) {
                    val hasWifiCreds = state.hotspotState.ssid.isNotBlank()
                    val wifiPayload = remember(state.hotspotState.ssid, state.hotspotState.passphrase) {
                        if (hasWifiCreds) {
                            com.example.util.QrCodeGenerator.buildWifiJoinPayload(
                                state.hotspotState.ssid,
                                state.hotspotState.passphrase
                            )
                        } else ""
                    }
                    val wifiQrBmp = remember(wifiPayload) {
                        if (wifiPayload.isNotBlank()) {
                            com.example.util.QrCodeGenerator.generateBitmap(wifiPayload, 360)?.asImageBitmap()
                        } else null
                    }
                    val defaultQrUrl = remember(
                        state.webPortalStatus.sslEnabled,
                        state.webPortalStatus.httpsPortalUrl,
                        state.webPortalStatus.portalUrl
                    ) {
                        if (state.webPortalStatus.sslEnabled && state.webPortalStatus.httpsPortalUrl.isNotBlank()) {
                            state.webPortalStatus.httpsPortalUrl
                        } else {
                            state.webPortalStatus.portalUrl
                        }
                    }
                    var useHttpsForQr by remember(defaultQrUrl) {
                        mutableStateOf(state.webPortalStatus.sslEnabled && state.webPortalStatus.httpsPortalUrl.isNotBlank())
                    }
                    val activeQrUrl = if (useHttpsForQr && state.webPortalStatus.httpsPortalUrl.isNotBlank()) {
                        state.webPortalStatus.httpsPortalUrl
                    } else {
                        state.webPortalStatus.portalUrl
                    }
                    val urlQrBmp = remember(activeQrUrl) {
                        com.example.util.QrCodeGenerator.generateBitmap(activeQrUrl, 360)?.asImageBitmap()
                    }
                    var qrStep by remember { mutableStateOf(if (wifiQrBmp != null) 1 else 2) }

                    AlertDialog(
                        onDismissRequest = { showIphoneQrDialog = false },
                        containerColor = p.card,
                        title = {
                            Text(
                                text = "iPHONE & BROWSER QR JOIN",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    color = EmeraldMesh,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                        },
                        text = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (wifiQrBmp != null) {
                                        OutlinedButton(
                                            onClick = { qrStep = 1 },
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                if (qrStep == 1) EmeraldMesh else p.border
                                            ),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("1. JOIN WI-FI", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (qrStep == 1) EmeraldMesh else p.textSecondary)
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { qrStep = 2 },
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (qrStep == 2) CyanGateway else p.border
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("2. OPEN APP", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (qrStep == 2) CyanGateway else p.textSecondary)
                                    }
                                }

                                val activeBmp = if (qrStep == 1 && wifiQrBmp != null) wifiQrBmp else urlQrBmp
                                if (activeBmp != null) {
                                    Surface(
                                        color = Color.White,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.size(220.dp)
                                    ) {
                                        androidx.compose.foundation.Image(
                                            bitmap = activeBmp,
                                            contentDescription = "PulseMesh QR Code",
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(8.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = if (qrStep == 1 && wifiQrBmp != null) {
                                        if (isGreek) {
                                            "Βήμα 1: Στρέψτε την κάμερα για σύνδεση στο Wi-Fi \"${state.hotspotState.ssid}\" (Κωδικός: ${state.hotspotState.passphrase.ifBlank { "Open" }}) και πατήστε Επόμενο."
                                        } else {
                                            "Step 1: Point iPhone Camera here to join Wi-Fi \"${state.hotspotState.ssid}\" (Pass: ${state.hotspotState.passphrase.ifBlank { "Open" }}), then tap Step 2."
                                        }
                                    } else {
                                        if (isGreek) {
                                            "Βήμα 2: Στρέψτε την κάμερα για να ανοίξετε το $activeQrUrl εκτός δικτύου (το HTTPS :${state.webPortalStatus.httpsPort} επιτρέπει μικρόφωνο/κλήσεις · Κοινοποίηση -> Προσθήκη στην Αφετηρία)."
                                        } else {
                                            "Step 2: Point iPhone Camera here to open $activeQrUrl offline in Safari (HTTPS :${state.webPortalStatus.httpsPort} enables mic calling; Share -> Add to Home Screen)."
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = p.textSecondary,
                                        fontSize = 11.sp
                                    )
                                )
                                if (qrStep != 1 && state.webPortalStatus.sslEnabled && state.webPortalStatus.httpsPortalUrl.isNotBlank()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { useHttpsForQr = true },
                                            border = androidx.compose.foundation.BorderStroke(1.dp, if (useHttpsForQr) EmeraldMesh else p.border),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = "HTTPS :${state.webPortalStatus.httpsPort} (SSL)",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (useHttpsForQr) EmeraldMesh else p.textSecondary
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = { useHttpsForQr = false },
                                            border = androidx.compose.foundation.BorderStroke(1.dp, if (!useHttpsForQr) CyanGateway else p.border),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = "HTTP :${state.webPortalStatus.port}",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (!useHttpsForQr) CyanGateway else p.textSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            if (qrStep == 1 && wifiQrBmp != null) {
                                Button(
                                    onClick = { qrStep = 2 },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyanGateway, contentColor = Color.Black)
                                ) {
                                    Text(if (isGreek) "Επόμενο: QR Πύλης" else "Next: Portal QR", fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Button(
                                    onClick = { showIphoneQrDialog = false },
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldMesh, contentColor = Color.Black)
                                ) {
                                    Text(if (isGreek) "Τέλος" else "Done", fontWeight = FontWeight.Bold)
                                }
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showIphoneQrDialog = false }) {
                                Text(if (isGreek) "Κλείσιμο" else "Close", color = p.textSecondary)
                            }
                        }
                    )
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
        } // end selectedLane == "HOTSPOT"

        // -------------------------------------------------------------
        // LANE: POWER & HARDWARE (2.2 EXPEDITION MODE + 75+ CHIPSET PROFILES)
        // -------------------------------------------------------------
        if (selectedLane == "POWER") {
        // Battery-Saver Duty-Cycle Mode ("Expedition Mode")
        ElevatedCard(
            shape = cardShape,
            colors = CardDefaults.elevatedCardColors(containerColor = p.card),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (state.expeditionModeEnabled) AmberRootBoost else p.border, cardShape)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isGreek) "Λειτουργία Εξοικονόμησης Μπαταρίας" else "Expedition Battery-Saver Mode",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = AmberRootBoost)
                        )
                        Text(
                            text = if (isGreek) "Εναλλάσσει έξυπνα τις σαρώσεις BLE & beacons για πολυήμερη αυτονομία εκτός δικτύου." else "Duty-cycles BLE scans & beacons to extend multi-day off-grid battery life.",
                            style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                        )
                    }
                    Switch(
                        checked = state.expeditionModeEnabled,
                        onCheckedChange = { onToggleExpeditionMode() },
                        modifier = Modifier.testTag("toggle_expedition_mode")
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isGreek) "Αυτόματη Ενεργοποίηση Κάτω από 20%" else "Auto-Enable Below 20% Battery",
                            style = MaterialTheme.typography.bodyMedium.copy(color = p.textPrimary, fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = if (isGreek) "Ενεργοποιεί αυτόματα το duty-cycling όταν η μπαταρία πέσει στο 20% ή χαμηλότερα." else "Automatically engages duty-cycling when battery drops to 20% or lower.",
                            style = MaterialTheme.typography.labelSmall.copy(color = p.textSecondary)
                        )
                    }
                    Switch(
                        checked = state.expeditionAutoBelow20,
                        onCheckedChange = { onToggleExpeditionAutoBelow20() },
                        modifier = Modifier.testTag("toggle_expedition_auto20")
                    )
                }
            }
        }

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
        } // end selectedLane == "POWER" (part 1)

        // -------------------------------------------------------------
        // LANE: SAFETY & SOS (1.3 QUICK STATUS + 1.4 ICE & DEAD-MAN + 1.5 GEOFENCE + CHECK-IN)
        // -------------------------------------------------------------
        if (selectedLane == "SAFETY") {
        // Tactical Quick-Status (Text + Voice TTS)
        ElevatedCard(
            shape = cardShape,
            colors = CardDefaults.elevatedCardColors(containerColor = p.card),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, EmeraldMesh.copy(alpha = 0.7f), cardShape)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isGreek) "Ταχεία Κατάσταση & Φωνή" else "Quick-Status & Voice Check-Ins",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = EmeraldMesh)
                        )
                        Text(
                            text = if (isGreek) "Στείλε με 1 πάτημα κείμενο + φωνητική εκφώνηση στο δίκτυο" else "Broadcast one-tap status beacons with optional spoken voice announcement",
                            style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                        )
                    }
                    Switch(
                        checked = state.quickStatusVoiceEnabled,
                        onCheckedChange = { onToggleQuickStatusVoice() },
                        modifier = Modifier.testTag("settings_quick_status_voice_switch")
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (code in com.example.model.QuickStatusCode.entries) {
                        val accent = when (code) {
                            com.example.model.QuickStatusCode.ALL_OK -> EmeraldMesh
                            com.example.model.QuickStatusCode.NEED_HELP -> RedSosAlert
                            com.example.model.QuickStatusCode.LOW_BATTERY,
                            com.example.model.QuickStatusCode.NEED_WATER -> AmberRootBoost
                            else -> CyanGateway
                        }
                        OutlinedButton(
                            onClick = { onSendQuickStatus(code) },
                            border = androidx.compose.foundation.BorderStroke(1.dp, accent),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (isGreek) code.labelEl else code.labelEn,
                                color = accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // Offline Emergency Medical / ICE Card + Dead-Man's Switch
        ElevatedCard(
            shape = cardShape,
            colors = CardDefaults.elevatedCardColors(containerColor = p.card),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, RedSosAlert.copy(alpha = 0.7f), cardShape)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (isGreek) "Κάρτα ICE & Διακόπτης Dead-Man" else "Emergency Medical / ICE Card & Dead-Man Switch",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = RedSosAlert)
                )
                Text(
                    text = if (isGreek) "Τα ιατρικά στοιχεία επισυνάπτονται αυτόματα στις εκπομπές SOS. Ο διακόπτης Dead-Man εκπέμπει SOS αν δεν γίνει check-in πριν λήξει η αντίστροφη μέτρηση." else "Medical info is attached automatically to SOS distress broadcasts. Dead-Man's Switch auto-triggers SOS if you don't check in before countdown ends.",
                    style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                )
                OutlinedTextField(
                    value = iceBloodInput,
                    onValueChange = { iceBloodInput = it },
                    label = { Text(if (isGreek) "Ομάδα Αίματος (π.χ. O+, A-)" else "Blood Type (e.g. O+, A-)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = iceAllergiesInput,
                    onValueChange = { iceAllergiesInput = it },
                    label = { Text(if (isGreek) "Αλλεργίες / Ιατρικές Σημειώσεις" else "Allergies / Medical Notes") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = iceContactInput,
                    onValueChange = { iceContactInput = it },
                    label = { Text(if (isGreek) "Επαφή ICE (Όνομα / Τηλέφωνο)" else "ICE Contact Name / Phone") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            onSaveIceMedicalCard(iceBloodInput, iceAllergiesInput, iceContactInput)
                            iceSavedNotice = if (isGreek) "Η κάρτα ICE αποθηκεύτηκε τοπικά." else "ICE medical card saved offline."
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldMesh, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isGreek) "ΑΠΟΘΗΚΕΥΣΗ ICE" else "SAVE ICE CARD", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    if (state.deadManSwitchMinutes > 0) {
                        Button(
                            onClick = onCheckInDeadManSwitch,
                            colors = ButtonDefaults.buttonColors(containerColor = CyanGateway, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isGreek) "ΕΙΜΑΙ ΚΑΛΑ (${state.deadManRemainingSeconds / 60}λ)" else "I'M OK (${state.deadManRemainingSeconds / 60}m)", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
                if (iceSavedNotice.isNotBlank()) {
                    Text(iceSavedNotice, color = EmeraldMesh, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                Text(
                    text = if (isGreek) "Αντίστροφη Μέτρηση Dead-Man Switch:" else "Dead-Man's Switch Countdown:",
                    style = MaterialTheme.typography.labelSmall.copy(color = p.textMuted, fontWeight = FontWeight.Bold)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for ((mins, label) in listOf(0 to "OFF", 15 to "15m", 30 to "30m", 60 to "60m", 120 to "2h")) {
                        val active = state.deadManSwitchMinutes == mins
                        OutlinedButton(
                            onClick = { onSetDeadManSwitchMinutes(mins) },
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (active) RedSosAlert else p.border),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (active) RedSosAlert else p.textSecondary)
                        }
                    }
                }
            }
        }

        // Geofence / Perimeter Alerts
        ElevatedCard(
            shape = cardShape,
            colors = CardDefaults.elevatedCardColors(containerColor = p.card),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (state.geofenceEnabled) AmberRootBoost else p.border, cardShape)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isGreek) "Ειδοποίηση Περιμέτρου (Geofence)" else "Geofence Perimeter Alert",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = AmberRootBoost)
                        )
                        Text(
                            text = if (isGreek) "Σχεδιάζει περίμετρο στον χάρτη γύρω από τη θέση σας και ειδοποιεί αν κάποιος κόμβος απομακρυνθεί." else "Draws a perimeter on the map around your location and alerts when any mesh peer drifts outside.",
                            style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                        )
                    }
                    Switch(
                        checked = state.geofenceEnabled,
                        onCheckedChange = { onConfigureGeofence(it, state.geofenceRadiusMeters) },
                        modifier = Modifier.testTag("toggle_geofence_switch")
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (radius in listOf(100, 250, 500, 1000, 2000)) {
                        val active = state.geofenceRadiusMeters == radius
                        OutlinedButton(
                            onClick = { onConfigureGeofence(true, radius) },
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (active) AmberRootBoost else p.border),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (radius >= 1000) "${radius / 1000}km" else "${radius}m",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (active) AmberRootBoost else p.textSecondary
                            )
                        }
                    }
                }
            }
        }

        // SECTION 3: TACTICAL MESH LINK-QUALITY SWEEP & AUTO CHECK-IN TIMER
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
        } // end selectedLane == "SAFETY"

        // -------------------------------------------------------------
        // LANE: SECURITY & DECOY (4.2 DECOY PIN + ECDH E2EE + LOCATION PRIVACY)
        // -------------------------------------------------------------
        if (selectedLane == "SECURITY" || selectedLane == "SAFETY") {
        // Decoy / Panic Mode Profile (only shown in SECURITY lane)
        if (selectedLane == "SECURITY") {
            ElevatedCard(
                shape = cardShape,
                colors = CardDefaults.elevatedCardColors(containerColor = p.card),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CyanGateway.copy(alpha = 0.7f), cardShape)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (isGreek) "Προφίλ Δολώματος / Πανικού (Decoy PIN)" else "Decoy / Panic Mode Profile",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = CyanGateway)
                    )
                    Text(
                        text = if (isGreek) "Ορίστε ξεχωριστό Decoy PIN (4-8 ψηφία). Με την εισαγωγή του εμφανίζεται ένα απλό ημερολόγιο πεζοπορίας χωρίς διαγραφή του κρυπτογραφημένου θησαυροφυλακίου." else "Set a separate Decoy PIN (4-8 digits). Entering it switches the screen into a benign Hiking Trail Log without erasing your encrypted vault.",
                        style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                    )
                    OutlinedTextField(
                        value = decoyPinInput,
                        onValueChange = { decoyPinInput = it },
                        label = { Text(if (isGreek) "Decoy PIN (4-8 ψηφία)" else "Decoy PIN (4-8 digits)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val ok = onSetDecoyPin(decoyPinInput)
                                decoyFeedback = if (ok) (if (isGreek) "Το Decoy PIN ενεργοποιήθηκε." else "Decoy PIN armed.") else (if (isGreek) "Εισάγετε 4 έως 8 ψηφία." else "Enter 4 to 8 digits.")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanGateway, contentColor = Color.Black),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (state.hasDecoyPin) (if (isGreek) "ΕΝΗΜΕΡΩΣΗ DECOY PIN" else "UPDATE DECOY PIN") else (if (isGreek) "ΟΠΛΙΣΜΟΣ DECOY PIN" else "ARM DECOY PIN"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        if (state.hasDecoyPin) {
                            OutlinedButton(
                                onClick = {
                                    val triggered = onTriggerDecoyMode(decoyPinInput)
                                    decoyFeedback = if (triggered) (if (isGreek) "Λειτουργία Decoy ενεργή." else "Decoy Mode active.") else (if (isGreek) "Πληκτρολογήστε το Decoy PIN για ενεργοποίηση." else "Enter your armed Decoy PIN to activate.")
                                },
                                border = androidx.compose.foundation.BorderStroke(1.dp, AmberRootBoost),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("ENTER DECOY", color = AmberRootBoost, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (decoyFeedback.isNotBlank()) {
                        Text(decoyFeedback, color = EmeraldMesh, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

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
        } // end selectedLane == "SECURITY" || "SAFETY"

        // -------------------------------------------------------------
        // LANE: IDENTITY & QR (CLOUDFLARE D1 @USERNAME + 3.3 QR INSTANT JOIN)
        // -------------------------------------------------------------
        if (selectedLane == "IDENTITY") {
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

        // QR Contact & Group Instant Join Card
        ElevatedCard(
            shape = cardShape,
            colors = CardDefaults.elevatedCardColors(containerColor = p.card),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CyanGateway.copy(alpha = 0.7f), cardShape)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (isGreek) "Άμεση Σύνδεση Επαφής & Ομάδας με QR" else "QR Contact & Group Instant Join",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = CyanGateway)
                )
                Text(
                    text = if (isGreek) "Μοιραστείτε τον κωδικό QR pulsemesh://join (Node ID, Κωδικός Κλήσης, Κανάλι & Δημόσιο Κλειδί) ή επικολλήστε ένα URI για προσθήκη εκτός δικτύου." else "Share your scannable pulsemesh://join QR code (Node ID, Callsign, Channel & Public Key) or paste a peer's join URI to add them offline.",
                    style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showInstantJoinQrDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldMesh, contentColor = Color.Black),
                        modifier = Modifier.weight(1f).testTag("settings_show_join_qr_button")
                    ) {
                        Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isGreek) "ΤΟ QR ΜΟΥ" else "MY JOIN QR", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            val payload = onBuildInstantJoinQr()
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("PulseMesh Join URI", payload))
                            joinQrFeedback = if (isGreek) "Το pulsemesh://join URI αντιγράφηκε." else "Copied pulsemesh://join URI to clipboard."
                        },
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyanGateway),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isGreek) "ΑΝΤΙΓΡΑΦΗ URI" else "COPY URI", color = CyanGateway, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedTextField(
                    value = joinQrImportInput,
                    onValueChange = { joinQrImportInput = it },
                    label = { Text(if (isGreek) "Επικόλληση pulsemesh://join?... URI" else "Paste pulsemesh://join?... URI") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val (ok, msg) = onImportInstantJoinQr(joinQrImportInput)
                        joinQrFeedback = msg
                        if (ok) joinQrImportInput = ""
                    },
                    enabled = joinQrImportInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanGateway, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isGreek) "ΕΙΣΑΓΩΓΗ ΚΟΜΒΟΥ & ΣΥΝΔΕΣΗ" else "IMPORT PEER & JOIN CHANNEL", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
                if (joinQrFeedback.isNotBlank()) {
                    Text(joinQrFeedback, color = EmeraldMesh, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        } // end selectedLane == "IDENTITY" (part 1)

        // -------------------------------------------------------------
        // LANE: MAPS & WEATHER (BAROMETRIC STORM ALERT + GPS)
        // -------------------------------------------------------------
        if (selectedLane == "MAPS") {
        // Offline Weather & Barometric Storm Alert (ONLY for phones that support a barometer sensor)
        if (state.hasBarometerSensor) {
            ElevatedCard(
                shape = cardShape,
                colors = CardDefaults.elevatedCardColors(containerColor = p.card),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, if (state.isStormAlertActive) RedSosAlert else CyanGateway, cardShape)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isGreek) "Βαρομετρικός Καιρός & Προειδοποίηση Καταιγίδας" else "Barometric Weather & Storm Alert",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (state.isStormAlertActive) RedSosAlert else CyanGateway
                        )
                    )
                    Text(
                        text = buildString {
                            append(if (isGreek) "Πίεση: " else "Pressure: ")
                            append(state.barometricPressureHpa?.let { "%.1f hPa".format(it) } ?: if (isGreek) "Βαθμονόμηση..." else "Calibrating...")
                            append(if (isGreek) " • Τάση: " else " • Trend: ")
                            append(com.example.util.AppStrings.weatherTrendLabel(state.appLanguage, state.weatherTrendLabel))
                            state.pressureTrendHpaPer3h?.let { append(" (%+.1f hPa/3h)".format(it)) }
                        },
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = if (state.isStormAlertActive) RedSosAlert else EmeraldMesh,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = if (state.isStormAlertActive) {
                            if (isGreek) {
                                "ΣΥΝΑΓΕΡΜΟΣ ΚΑΤΑΙΓΙΔΑΣ: Ταχεία πτώση πίεσης (<= -3.0 hPa / 3h). Η προειδοποίηση μεταδόθηκε στο mesh."
                            } else {
                                "STORM ALERT: Rapid pressure drop detected (<= -3.0 hPa / 3h). Weather warning broadcast to nearby mesh peers."
                            }
                        } else {
                            if (isGreek) {
                                "Το βαρόμετρο υλικού είναι ενεργό. Παρακολουθεί αυτόματα τις μεταβολές πίεσης εκτός δικτύου."
                            } else {
                                "Hardware barometer active. Automatically monitors 3-hour pressure trends offline and alerts your team before incoming storms."
                            }
                        },
                        style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                    )
                }
            }
        } else {
            Surface(
                color = p.surfaceVariant,
                shape = cardShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, p.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isGreek) "Βαρομετρικός Καιρός εκτός δικτύου: Μη διαθέσιμος σε αυτή τη συσκευή (δεν ανιχνεύθηκε αισθητήρας βαρομέτρου)." else "Offline Barometric Weather: Not available on this phone (no hardware barometer sensor detected).",
                    style = MaterialTheme.typography.bodySmall.copy(color = p.textMuted, fontSize = 11.sp),
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // SECTION 5: REAL GPS SENSOR & COORDINATE MANAGEMENT
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
        } // end selectedLane == "MAPS" (part 1)

        // -------------------------------------------------------------
        // LANE: FILES & EXPORT (1.1 OFFLINE FILE TRANSFER + 3.2 TRIP ARCHIVE)
        // -------------------------------------------------------------
        if (selectedLane == "FILES") {
        ElevatedCard(
            shape = cardShape,
            colors = CardDefaults.elevatedCardColors(containerColor = p.card),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, EmeraldMesh.copy(alpha = 0.7f), cardShape)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (isGreek) "Μεταφορά Αρχείων & Εγγράφων εκτός Δικτύου" else "Offline File & Document Transfer",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = EmeraldMesh)
                )
                Text(
                    text = if (isGreek) "Μοιραστείτε σημειώσεις, διαδρομές GPX ή λίστες μέσω της πύλης Hotspot HTTP/HTTPS με έλεγχο SHA-256 (έως 5 MB)." else "Share notes, GPX routes, or field checklists over the Hotspot HTTP/HTTPS portal with SHA-256 checksum verification (up to 5 MB per file).",
                    style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                )
                OutlinedTextField(
                    value = shareFileNameInput,
                    onValueChange = { shareFileNameInput = it },
                    label = { Text(if (isGreek) "Όνομα Αρχείου (π.χ. rally-plan.txt)" else "File Name (e.g. rally-plan.txt)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = shareFileTextInput,
                    onValueChange = { shareFileTextInput = it },
                    label = { Text(if (isGreek) "Περιεχόμενο / Λίστα Ελέγχου" else "Document Content / Checklist") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val ok = onShareLocalFile(
                                shareFileNameInput.ifBlank { "note.txt" },
                                "text/plain",
                                shareFileTextInput.toByteArray(Charsets.UTF_8)
                            )
                            shareFileNotice = if (ok) (if (isGreek) "Κοινοποιήθηκε στην Πύλη Hotspot με SHA-256!" else "Shared over Hotspot Portal with SHA-256!") else (if (isGreek) "Αποτυχία κοινοποίησης αρχείου." else "Failed to share file.")
                            if (ok) shareFileTextInput = ""
                        },
                        enabled = shareFileTextInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldMesh, contentColor = Color.Black),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isGreek) "ΚΟΙΝΟΠΟΙΗΣΗ ΣΤΗΝ ΠΥΛΗ" else "SHARE TO PORTAL", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val gpx = onExportGpx()
                            val ok = onShareLocalFile("pulsemesh-trail.gpx", "application/gpx+xml", gpx.toByteArray(Charsets.UTF_8))
                            shareFileNotice = if (ok) (if (isGreek) "Η διαδρομή GPX δημοσιεύθηκε!" else "GPX trail published to Hotspot Portal!") else (if (isGreek) "Αποτυχία δημοσίευσης GPX." else "Could not publish GPX.")
                        },
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyanGateway),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isGreek) "ΔΗΜΟΣΙΕΥΣΗ GPX" else "SHARE GPX TRAIL", color = CyanGateway, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
                if (shareFileNotice.isNotBlank()) {
                    Text(shareFileNotice, color = EmeraldMesh, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                if (state.sharedFiles.isNotEmpty()) {
                    HorizontalDivider(color = p.border)
                    Text(
                        text = "SHARED FILES ON HOTSPOT PORTAL (${state.sharedFiles.size}):",
                        style = MaterialTheme.typography.labelSmall.copy(color = CyanGateway, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    )
                    for (file in state.sharedFiles) {
                        Surface(
                            color = p.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, p.border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(file.name, color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text(
                                        "${file.sizeBytes} B • SHA-256: ${file.sha256.take(12)}...",
                                        color = p.textMuted,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                TextButton(onClick = { onDeleteSharedFile(file.id) }) {
                                    Text("REMOVE", color = RedSosAlert, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Trip Log & Chat Archive Export
        ElevatedCard(
            shape = cardShape,
            colors = CardDefaults.elevatedCardColors(containerColor = p.card),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CyanGateway.copy(alpha = 0.7f), cardShape)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isGreek) "Εξαγωγή Ημερολογίου Ταξιδιού & Αρχείου Συνομιλιών" else "Trip Log & Chat Archive Export",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = CyanGateway)
                )
                Text(
                    text = if (isGreek) "Πακετάρετε συνομιλίες, καρφιτσωμένα μηνύματα, σημεία διαδρομής και συντεταγμένες σε φορητό αρχείο JSON." else "Bundle your chats, pinned messages, waypoints, and breadcrumb coordinates into a portable JSON archive.",
                    style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary)
                )
                Button(
                    onClick = {
                        val json = onExportTripArchive()
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("PulseMesh Trip Archive JSON", json))
                        onShareLocalFile("pulsemesh-trip-archive.json", "application/json", json.toByteArray(Charsets.UTF_8))
                        tripExportNotice = if (isGreek) "Το αρχείο ταξιδιού (${json.length} χαρακτήρες) αντιγράφηκε & δημοσιεύθηκε!" else "Trip archive (${json.length} chars) copied & published to Hotspot Files!"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanGateway, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isGreek) "ΕΞΑΓΩΓΗ ΑΡΧΕΙΟΥ ΤΑΞΙΔΙΟΥ (ΠΡΟΧΕΙΡΟ + ΠΥΛΗ)" else "EXPORT TRIP ARCHIVE (CLIPBOARD + PORTAL)", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
                if (tripExportNotice.isNotBlank()) {
                    Text(tripExportNotice, color = EmeraldMesh, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        } // end selectedLane == "FILES"

        // -----------------------------------------------------------------
        // PULSEMESH FIELD RELAY, AUDIO, MAPS & SECURITY CONTROLS
        // -----------------------------------------------------------------
        if (selectedLane == "POWER" || selectedLane == "MAPS" || selectedLane == "SECURITY") {
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
                        contentDescription = "Field Upgrades",
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
        } // end Field Relay/Audio/Security Card

        // Delete Account Information Card (inside IDENTITY lane)
        if (selectedLane == "IDENTITY") {
        Surface(
            color = p.card,
            shape = cardShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, RedSosAlert.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "DELETE ACCOUNT INFORMATION",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = RedSosAlert,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                    Text(
                        text = "Permanently erase a username's saved credentials, keys, claims, and local chat history from this device.",
                        style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary, fontSize = 11.sp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Button(
                    onClick = {
                        deleteAccountUsernameInput = state.claimedUsername
                        deleteAccountPinInput = ""
                        deleteAccountRecoveryInput = ""
                        deleteAccountFeedback = ""
                        deleteAccountError = ""
                        showDeleteAccountDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedSosAlert, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("delete_account_info_button")
                ) {
                    Text("DELETE", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
            if (deleteAccountFeedback.isNotBlank()) {
                Text(
                    text = deleteAccountFeedback,
                    color = EmeraldMesh,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
                )
            }
        }
        } // end selectedLane == "IDENTITY" (part 2)

        if (showInstantJoinQrDialog) {
            val joinPayload = remember(state.localNodeId, state.operatorCallsign, state.meshChannel, state.x25519Fingerprint) {
                onBuildInstantJoinQr()
            }
            val joinBmp = remember(joinPayload) {
                com.example.util.QrCodeGenerator.generateBitmap(joinPayload, 360)?.asImageBitmap()
            }
            AlertDialog(
                onDismissRequest = { showInstantJoinQrDialog = false },
                containerColor = p.card,
                title = {
                    Text(
                        text = "INSTANT CONTACT & GROUP JOIN QR",
                        color = EmeraldMesh,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (joinBmp != null) {
                            Surface(
                                color = Color.White,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.size(220.dp)
                            ) {
                                androidx.compose.foundation.Image(
                                    bitmap = joinBmp,
                                    contentDescription = "Instant Join QR",
                                    modifier = Modifier.fillMaxSize().padding(8.dp)
                                )
                            }
                        }
                        Text(
                            text = joinPayload,
                            color = CyanGateway,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showInstantJoinQrDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldMesh, contentColor = Color.Black)
                    ) {
                        Text("DONE", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        if (showDeleteAccountDialog) {
            val activeNormUser = com.example.crypto.TacticalE2eeManager.normalizeUsername(state.claimedUsername)
            val inputNormUser = com.example.crypto.TacticalE2eeManager.normalizeUsername(deleteAccountUsernameInput)
            val isAuthenticatedBySession = activeNormUser.isNotBlank() && (inputNormUser.isBlank() || inputNormUser == activeNormUser)

            AlertDialog(
                onDismissRequest = { showDeleteAccountDialog = false },
                containerColor = p.card,
                title = {
                    Text(
                        text = "DELETE ACCOUNT DATA",
                        color = RedSosAlert,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = if (isAuthenticatedBySession) {
                                "Signed in as @$activeNormUser. Confirm below to permanently erase your account credentials, keys, claims, and local chat history."
                            } else {
                                "Not signed in to this account. To delete without an active session, enter the @username AND BOTH its PIN and Recovery Key."
                            },
                            color = if (isAuthenticatedBySession) EmeraldMesh else AmberRootBoost,
                            fontSize = 11.5.sp
                        )
                        OutlinedTextField(
                            value = deleteAccountUsernameInput,
                            onValueChange = {
                                deleteAccountUsernameInput = it
                                deleteAccountError = ""
                            },
                            label = { Text("Account @username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (!isAuthenticatedBySession) {
                            OutlinedTextField(
                                value = deleteAccountPinInput,
                                onValueChange = {
                                    deleteAccountPinInput = it
                                    deleteAccountError = ""
                                },
                                label = { Text("Account PIN (required)") },
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = deleteAccountRecoveryInput,
                                onValueChange = {
                                    deleteAccountRecoveryInput = it
                                    deleteAccountError = ""
                                },
                                label = { Text("Recovery Key PMRK-... (required)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (deleteAccountError.isNotBlank()) {
                            Text(
                                text = deleteAccountError,
                                color = RedSosAlert,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    val canSubmit = isAuthenticatedBySession ||
                        (inputNormUser.isNotBlank() && deleteAccountPinInput.isNotBlank() && deleteAccountRecoveryInput.isNotBlank())
                    Button(
                        enabled = canSubmit,
                        onClick = {
                            val (ok, msg) = onDeleteAccountInfo(
                                deleteAccountUsernameInput,
                                deleteAccountPinInput,
                                deleteAccountRecoveryInput
                            )
                            if (ok) {
                                deleteAccountFeedback = msg
                                deleteAccountError = ""
                                deleteAccountPinInput = ""
                                deleteAccountRecoveryInput = ""
                                showDeleteAccountDialog = false
                            } else {
                                deleteAccountError = msg
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RedSosAlert, contentColor = Color.White)
                    ) {
                        Text("ERASE PERMANENTLY", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteAccountDialog = false }) {
                        Text("Cancel", color = p.textSecondary)
                    }
                }
            )
        }

        // About & GNU GPL-3.0 License Footer Notice
        Text(
            text = "PulseMesh v1.3.1 • © 2026 Konstantinos Kourtis • GNU GPL-3.0",
            style = MaterialTheme.typography.labelSmall.copy(
                color = p.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            ),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 8.dp)
                .testTag("about_gpl_footer")
        )
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

