// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.crypto.TacticalE2eeManager
import com.example.model.*
import com.example.ui.components.AeroGlossBadgeIcon
import com.example.ui.components.HardwareMismatchWarningBanner
import com.example.ui.components.HardwareMismatchWarningDialog
import com.example.ui.components.InteractiveMapAreaSelectorDialog
import com.example.ui.theme.*
import com.example.util.AppTheme
import com.example.util.DetectedHardwareInfo
import com.example.util.HardwareDetector
import com.example.util.RootDetector

private data class WizardThemeOption(
    val theme: AppTheme,
    val title: String,
    val subtitle: String,
    val previewColors: List<Color>,
    val icon: ImageVector,
    val tag: String,
)

/**
 * Multi-Part Guided Device & Theme Setup Wizard:
 * - Part 1 (Intro): Welcome to PulseMesh, key off-grid capabilities, and Easy vs Pro mode overview.
 * - Part 2 (Theme): Live theme gallery (Frutiger Aero, Dark Frutiger Aero, Tactical HUD Dark, Daylight Light, Original).
 * - Part 3 (Identity & Device): Callsign, derived 16-bit Node ID & X25519 fingerprint, hardware scanner, 40+ model catalog, and KernelSU root probe.
 * - Part 4 (Network & Launch): Pure Off-Grid vs Hybrid Internet Bridge, Offline Map Tiles pre-cache, and Launch Summary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TacticalDeviceWizardScreen(
    initialCallsign: String,
    initialNodeId: String,
    initialSelectedProfile: PhoneModelProfile,
    detectedHardware: DetectedHardwareInfo?,
    initialPureOffGrid: Boolean,
    initialInternetBridge: Boolean,
    onCompleteWizard: (callsign: String, nodeId: String, profile: PhoneModelProfile, pureOffGrid: Boolean, internetBridge: Boolean) -> Unit,
    onDismiss: (() -> Unit)? = null,
    onSelectTransportPosture: ((pureOffGrid: Boolean) -> Unit)? = null,
    currentAppTheme: AppTheme = AppTheme.TACTICAL_DARK,
    onSelectAppTheme: ((AppTheme) -> Unit)? = null,
    initialClaimedUsername: String = "",
    initialRecoveryKey: String = "",
    usernameAuthStatus: String = "",
    isReservingUsername: Boolean = false,
    isUsernameOnHold: Boolean = false,
    onClaimOrRecoverUsername: (username: String, pin: String, recoveryKey: String, isRecoveryMode: Boolean, onResult: (Boolean, String) -> Unit) -> Unit = { _, _, _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val p = LocalPulsePalette.current
    val cardShape = tacticalCardShape(isSimpleMode = true, isOriginal = p.isOriginal, isAero = p.isAero)
    val btnShape = tacticalButtonShape(isSimpleMode = true, isOriginal = p.isOriginal, isAero = p.isAero)

    var currentStep by remember { mutableIntStateOf(0) } // 0=Intro, 1=Theme, 2=Device & Identity, 3=Network & Launch
    var selectedThemeState by remember(currentAppTheme) { mutableStateOf(currentAppTheme) }

    var callsign by remember { mutableStateOf(initialCallsign.ifBlank { "Operator" }) }
    var selectedProfile by remember { mutableStateOf(initialSelectedProfile) }
    var selectedBrandTab by remember { mutableStateOf(initialSelectedProfile.brand) }
    var isPureOffGrid by remember { mutableStateOf(initialPureOffGrid && !initialInternetBridge) }
    var isInternetBridge by remember { mutableStateOf(initialInternetBridge || !initialPureOffGrid) }

    val selectNetworkPosture: (Boolean) -> Unit = { pureOffGrid ->
        isPureOffGrid = pureOffGrid
        isInternetBridge = !pureOffGrid
        com.example.util.OfflineRegionDownloader.isInternetEnabled = !pureOffGrid
        onSelectTransportPosture?.invoke(pureOffGrid)
    }

    var showMismatchModal by remember { mutableStateOf(false) }
    var isMismatchOverridden by remember { mutableStateOf(false) }
    var isDropdownExpanded by remember { mutableStateOf(false) }
    var isQuickSetupMode by remember { mutableStateOf(true) }
    var isOsmOfflineCached by remember { mutableStateOf(false) }
    var showMapAreaSelectorDialog by remember { mutableStateOf(false) }

    var wizardUsername by remember(initialClaimedUsername) { mutableStateOf(initialClaimedUsername) }
    var wizardPin by remember { mutableStateOf("") }
    var wizardRecoveryKeyInput by remember { mutableStateOf("") }
    var wizardRecoveryMode by remember { mutableStateOf(false) }
    var wizardRecoveryWithKey by remember { mutableStateOf(false) }
    var wizardAccountFeedback by remember(usernameAuthStatus) { mutableStateOf(usernameAuthStatus) }
    var wizardAccountSuccess by remember { mutableStateOf(initialClaimedUsername.isNotBlank()) }

    // Live Hardware Detection
    var hostHw by remember {
        mutableStateOf(detectedHardware ?: HardwareDetector.detectHardware(context))
    }

    val mismatchReport = remember(hostHw, selectedProfile) {
        HardwareDetector.evaluateMismatch(hostHw, selectedProfile)
    }

    // Real Root Probe State
    var probeResult by remember {
        mutableStateOf(RootDetector.checkRootAccess())
    }
    var isProbingRoot by remember { mutableStateOf(false) }

    val derivedNodeId = remember(callsign, initialNodeId) {
        if (callsign.isNotBlank() && !callsign.startsWith("User-") && callsign != "Operator") {
            TacticalE2eeManager.deriveNodeIdFromCallsign(callsign)
        } else {
            initialNodeId.ifBlank { TacticalE2eeManager.deriveNodeIdFromCallsign(callsign) }
        }
    }

    val derivedFingerprint = remember(callsign) {
        val kp = TacticalE2eeManager.generateKeyPair(callsign)
        kp.fingerprint
    }

    val stepTitles = remember {
        listOf("1. INTRO", "2. THEME", "3. DEVICE", "4. LAUNCH")
    }

    val themeOptions = remember {
        listOf(
            WizardThemeOption(
                theme = AppTheme.FRUTIGER_AERO,
                title = "Frutiger Aero",
                subtitle = "Sunlit sky, translucent aqua glass panels, specular 2000s gel buttons & glossy orb icons",
                previewColors = listOf(Color(0xFFB3E5FF), Color(0xFF0088E6), Color(0xFF0BA354)),
                icon = Icons.Default.WaterDrop,
                tag = "wizard_theme_aero_button",
            ),
            WizardThemeOption(
                theme = AppTheme.FRUTIGER_AERO_DARK,
                title = "Frutiger Aero Dark",
                subtitle = "Midnight sapphire aurora, smoked dark Vista glass, icy-cyan borders & luminous gloss orbs",
                previewColors = listOf(Color(0xFF072137), Color(0xFF38CFFF), Color(0xFF19E68C)),
                icon = Icons.Default.NightsStay,
                tag = "wizard_theme_dark_aero_button",
            ),
            WizardThemeOption(
                theme = AppTheme.TACTICAL_DARK,
                title = "Tactical HUD Dark",
                subtitle = "High-contrast stealth OLED carbon canvas with angled cut-corners and emerald telemetry",
                previewColors = listOf(Color(0xFF0A0E14), Color(0xFF00E676), Color(0xFF00B0FF)),
                icon = Icons.Default.DarkMode,
                tag = "wizard_theme_hud_dark_button",
            ),
            WizardThemeOption(
                theme = AppTheme.TACTICAL_LIGHT,
                title = "Daylight Light",
                subtitle = "Crisp outdoor sunlight visibility with clean high-contrast white & slate cards",
                previewColors = listOf(Color(0xFFF2F5F9), Color(0xFFD97706), Color(0xFF008F4C)),
                icon = Icons.Default.LightMode,
                tag = "wizard_theme_light_button",
            ),
            WizardThemeOption(
                theme = AppTheme.ORIGINAL,
                title = "Original Classic",
                subtitle = "Classic PulseMesh look with clean rounded cards and zero extra HUD ornaments",
                previewColors = listOf(Color(0xFF101620), Color(0xFF00E676), Color(0xFFFF9100)),
                icon = Icons.Default.Restore,
                tag = "wizard_theme_original_button",
            ),
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = if (p.isAero) Color.Transparent else p.background,
        topBar = {
            Surface(
                color = if (p.isAero) Color.Transparent else p.topBarBg,
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (p.isAero) {
                            Modifier.aeroGlassPanel(
                                p,
                                cornerRadius = 0.dp,
                                tint = if (p.isDarkAero) Color(0xE6081F33) else Color(0xEAF2FAFF)
                            )
                        } else {
                            Modifier
                        }
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                            )
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (p.isAero) {
                                AeroGlossBadgeIcon(
                                    imageVector = Icons.Default.Shield,
                                    accentColor = p.emerald,
                                    contentDescription = "Wizard",
                                    badgeSize = 26.dp,
                                    iconSize = 14.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = "Wizard",
                                    tint = p.emerald,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "PULSEMESH SETUP WIZARD",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = p.emerald,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp
                                    )
                                )
                                Text(
                                    text = "STEP ${currentStep + 1} OF 4 • ${stepTitles[currentStep].substringAfter(". ")}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = p.textSecondary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp
                                    )
                                )
                            }
                        }

                        if (onDismiss != null) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("wizard_dismiss_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = p.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // 4-Part Clickable Step Progress Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        stepTitles.forEachIndexed { idx, label ->
                            val isActive = idx == currentStep
                            val isDone = idx < currentStep
                            val pillBg = when {
                                isActive -> p.cyan
                                isDone -> p.emeraldDim
                                else -> p.surfaceVariant
                            }
                            val pillBorder = when {
                                isActive -> p.cyan
                                isDone -> p.emerald
                                else -> p.border
                            }
                            val textCol = when {
                                isActive -> if (p.isLight || p.isAero) Color.White else Color.Black
                                isDone -> p.emerald
                                else -> p.textSecondary
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(btnShape)
                                    .background(if (isActive && p.isAero) Color.Transparent else pillBg)
                                    .then(
                                        if (isActive && p.isAero) {
                                            Modifier.aeroGlossOverlay(p, p.cyan, btnShape, cornerRadius = 18.dp)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .border(1.dp, pillBorder, btnShape)
                                    .clickable { currentStep = idx }
                                    .padding(vertical = 6.dp, horizontal = 4.dp)
                                    .testTag("wizard_step_$idx"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = textCol,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                color = if (p.isAero) Color.Transparent else p.topBarBg,
                tonalElevation = 8.dp,
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
                    .navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (mismatchReport.isMismatched && !isMismatchOverridden && currentStep >= 2) {
                        Surface(
                            color = p.redDim,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, p.red),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Mismatch",
                                        tint = p.red,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Hardware Mismatch: Host ${hostHw.detectedSocFamily.familyName}",
                                        color = p.red,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                TextButton(
                                    onClick = { showMismatchModal = true },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) {
                                    Text("REVIEW", color = p.red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (currentStep > 0) {
                            OutlinedButton(
                                onClick = { currentStep = (currentStep - 1).coerceAtLeast(0) },
                                border = BorderStroke(1.dp, p.border),
                                shape = btnShape,
                                modifier = Modifier
                                    .weight(0.42f)
                                    .heightIn(min = 48.dp)
                                    .testTag("wizard_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = p.textPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "BACK",
                                    color = p.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                        } else if (onDismiss != null) {
                            OutlinedButton(
                                onClick = onDismiss,
                                border = BorderStroke(1.dp, p.border),
                                shape = btnShape,
                                modifier = Modifier
                                    .weight(0.42f)
                                    .heightIn(min = 48.dp)
                            ) {
                                Text(
                                    text = "SKIP",
                                    color = p.textSecondary,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        if (currentStep < 3) {
                            Button(
                                onClick = { currentStep = (currentStep + 1).coerceAtMost(3) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (p.isAero) Color.Transparent else p.cyan,
                                    contentColor = if (p.isLight || p.isAero) Color.White else Color.Black
                                ),
                                shape = btnShape,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .aeroGlossOverlay(p, p.cyan, btnShape, cornerRadius = 18.dp)
                                    .testTag("wizard_next_button")
                            ) {
                                Text(
                                    text = "NEXT: ${stepTitles[currentStep + 1].substringAfter(". ")}",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Next",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            val launchColor = if (mismatchReport.isMismatched && !isMismatchOverridden) p.amber else p.emerald
                            Button(
                                onClick = {
                                    if (mismatchReport.isMismatched && !isMismatchOverridden) {
                                        showMismatchModal = true
                                    } else {
                                        onCompleteWizard(
                                            callsign.trim().ifEmpty { "Operator" },
                                            derivedNodeId,
                                            selectedProfile,
                                            isPureOffGrid,
                                            isInternetBridge
                                        )
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (p.isAero) Color.Transparent else launchColor,
                                    contentColor = if (p.isLight || p.isAero) Color.White else Color.Black
                                ),
                                shape = btnShape,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .aeroGlossOverlay(p, launchColor, btnShape, cornerRadius = 18.dp)
                                    .testTag("wizard_complete_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Radar,
                                    contentDescription = "Initialize",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (mismatchReport.isMismatched && !isMismatchOverridden) {
                                        "REVIEW MISMATCH"
                                    } else {
                                        "INITIALIZE & ENTER MESH"
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        modifier = modifier
            .fillMaxSize()
            .aeroCanvasBackground(p)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .testTag("wizard_content_list"),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (currentStep) {
                // =========================================================================
                // PART 1: APP INTRODUCTION & EXPERIENCE LEVEL
                // =========================================================================
                0 -> {
                    item {
                        Surface(
                            color = if (p.isAero) Color.Transparent else p.card,
                            shape = cardShape,
                            border = BorderStroke(1.dp, p.emerald),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aeroGlassPanel(p, cornerRadius = 22.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (p.isAero) {
                                        AeroGlossBadgeIcon(
                                            imageVector = Icons.Default.Public,
                                            accentColor = p.emerald,
                                            contentDescription = "PulseMesh Intro",
                                            badgeSize = 46.dp,
                                            iconSize = 24.dp,
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(CircleShape)
                                                .background(p.emeraldDim),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Public,
                                                contentDescription = "PulseMesh Intro",
                                                tint = p.emerald,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "WELCOME TO PULSEMESH",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                color = p.emerald,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                        Text(
                                            text = "100% Off-Grid Encrypted Voice, Chat & GPS Radar",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = p.textSecondary,
                                                fontSize = 11.sp
                                            )
                                        )
                                    }
                                }

                                Text(
                                    text = "PulseMesh turns nearby smartphones into a resilient off-grid mesh network — no cell towers, SIM cards, or internet required.",
                                    color = p.textPrimary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    item {
                        WizardFeatureCard(
                            icon = Icons.Default.RecordVoiceOver,
                            accent = p.cyan,
                            title = "HD Off-Grid Calls & Walkie-Talkie",
                            description = "Crystal-clear 8 kHz IMA-ADPCM voice over Wi-Fi Direct Hotspot UDP with automatic Bluetooth LE mesh fallback and Lock-Screen Call Cover.",
                            palette = p,
                            cardShape = cardShape,
                        )
                    }

                    item {
                        WizardFeatureCard(
                            icon = Icons.Default.Lock,
                            accent = p.emerald,
                            title = "X25519 Encrypted Chats & Map Radar",
                            description = "End-to-end encrypted direct & group chats, delivery retries, offline OpenStreetMap tiles, and privacy-aware GPS sharing.",
                            palette = p,
                            cardShape = cardShape,
                        )
                    }

                    item {
                        Surface(
                            color = if (p.isAero) Color.Transparent else p.card,
                            shape = cardShape,
                            border = BorderStroke(1.dp, p.border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aeroGlassPanel(p, cornerRadius = 22.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "SETUP DETAIL LEVEL",
                                    color = p.amber,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(btnShape)
                                            .background(if (isQuickSetupMode && !p.isAero) p.emerald else if (!isQuickSetupMode) p.surfaceVariant else Color.Transparent)
                                            .then(
                                                if (isQuickSetupMode && p.isAero) {
                                                    Modifier.aeroGlossOverlay(p, p.emerald, btnShape, cornerRadius = 18.dp)
                                                } else {
                                                    Modifier
                                                }
                                            )
                                            .border(1.dp, if (isQuickSetupMode) p.emerald else p.border, btnShape)
                                            .clickable { isQuickSetupMode = true }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "QUICK (EASY)",
                                            color = if (isQuickSetupMode) (if (p.isLight || p.isAero) Color.White else Color.Black) else p.textSecondary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(btnShape)
                                            .background(if (!isQuickSetupMode && !p.isAero) p.cyan else if (isQuickSetupMode) p.surfaceVariant else Color.Transparent)
                                            .then(
                                                if (!isQuickSetupMode && p.isAero) {
                                                    Modifier.aeroGlossOverlay(p, p.cyan, btnShape, cornerRadius = 18.dp)
                                                } else {
                                                    Modifier
                                                }
                                            )
                                            .border(1.dp, if (!isQuickSetupMode) p.cyan else p.border, btnShape)
                                            .clickable { isQuickSetupMode = false }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "ADVANCED / PRO",
                                            color = if (!isQuickSetupMode) (if (p.isLight || p.isAero) Color.White else Color.Black) else p.textSecondary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // =========================================================================
                // PART 2: LIVE THEME SELECTION (INCLUDING LIGHT & DARK FRUTIGER AERO)
                // =========================================================================
                1 -> {
                    item {
                        Surface(
                            color = if (p.isAero) Color.Transparent else p.card,
                            shape = cardShape,
                            border = BorderStroke(1.dp, p.cyan),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aeroGlassPanel(p, cornerRadius = 22.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "CHOOSE YOUR VISUAL STYLE",
                                    color = p.cyan,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Tap any theme below to preview it live right here in the wizard. You can change themes anytime in Settings.",
                                    color = p.textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    items(themeOptions) { option ->
                        val isSelected = selectedThemeState == option.theme
                        Surface(
                            color = if (p.isAero) Color.Transparent else if (isSelected) p.surfaceVariant else p.card,
                            shape = cardShape,
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) p.cyan else p.border
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aeroGlassPanel(
                                    p,
                                    cornerRadius = 22.dp,
                                    borderColor = if (isSelected) p.cyan else p.border
                                )
                                .clickable {
                                    selectedThemeState = option.theme
                                    onSelectAppTheme?.invoke(option.theme)
                                }
                                .testTag(option.tag)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Glossy swatch orb preview
                                if (p.isAero) {
                                    AeroGlossBadgeIcon(
                                        imageVector = option.icon,
                                        accentColor = option.previewColors.getOrElse(1) { p.cyan },
                                        contentDescription = option.title,
                                        badgeSize = 48.dp,
                                        iconSize = 22.dp,
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(Brush.linearGradient(option.previewColors))
                                            .border(2.dp, if (isSelected) Color.White else p.borderBright, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = option.icon,
                                            contentDescription = option.title,
                                            tint = Color.White,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = option.title,
                                            color = if (isSelected) p.cyan else p.textPrimary,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 13.sp
                                        )
                                        if (isSelected) {
                                            Surface(
                                                color = p.cyanDim,
                                                shape = RoundedCornerShape(6.dp),
                                                border = BorderStroke(1.dp, p.cyan)
                                            ) {
                                                Text(
                                                    text = "ACTIVE",
                                                    color = p.cyan,
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontFamily = FontFamily.Monospace,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = option.subtitle,
                                        color = p.textSecondary,
                                        fontSize = 11.sp
                                    )
                                }

                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        selectedThemeState = option.theme
                                        onSelectAppTheme?.invoke(option.theme)
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = p.cyan)
                                )
                            }
                        }
                    }
                }

                // =========================================================================
                // PART 3: OPERATOR IDENTITY, HARDWARE SCAN & DEVICE TUNING
                // =========================================================================
                2 -> {
                    if (mismatchReport.isMismatched) {
                        item {
                            HardwareMismatchWarningBanner(
                                report = mismatchReport,
                                onClick = { showMismatchModal = true }
                            )
                        }
                    }

                    // Operator Callsign & 16-bit Node ID
                    item {
                        Surface(
                            color = if (p.isAero) Color.Transparent else p.card,
                            shape = cardShape,
                            border = BorderStroke(1.dp, p.border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aeroGlassPanel(p, cornerRadius = 22.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "OPERATOR IDENTITY & E2EE",
                                        color = p.emerald,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Surface(
                                        color = p.surfaceVariant,
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, p.emerald)
                                    ) {
                                        Text(
                                            text = "ID: $derivedNodeId",
                                            color = p.emerald,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = callsign,
                                    onValueChange = { callsign = it },
                                    label = { Text("Your Name or Callsign") },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = p.emerald,
                                        unfocusedBorderColor = p.border,
                                        focusedTextColor = p.textPrimary,
                                        unfocusedTextColor = p.textPrimary
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FingerprintBox("NODE ID", derivedNodeId, p, Modifier.weight(1f))
                                    FingerprintBox("X25519 FINGERPRINT", derivedFingerprint, p, Modifier.weight(2f))
                                }
                            }
                        }
                    }

                    // Unique @Username Reservation + PIN & Recovery Key Login
                    item {
                        Surface(
                            color = if (p.isAero) Color.Transparent else p.card,
                            shape = cardShape,
                            border = BorderStroke(
                                1.dp,
                                if (initialClaimedUsername.isNotBlank()) p.emerald else p.cyan
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aeroGlassPanel(p, cornerRadius = 22.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (p.isAero) {
                                            AeroGlossBadgeIcon(
                                                imageVector = Icons.Default.AlternateEmail,
                                                accentColor = p.cyan,
                                                contentDescription = "Unique Username",
                                                badgeSize = 26.dp,
                                                iconSize = 14.dp,
                                            )
                                        }
                                        Text(
                                            text = "UNIQUE @USERNAME & RECOVERY",
                                            color = p.cyan,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    if (initialClaimedUsername.isNotBlank()) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "@$initialClaimedUsername",
                                                color = if (isUsernameOnHold) p.amber else p.emerald,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            if (isUsernameOnHold) {
                                                Surface(
                                                    color = p.amberDim,
                                                    shape = RoundedCornerShape(4.dp),
                                                    border = BorderStroke(1.dp, p.amber),
                                                    modifier = Modifier.testTag("wizard_username_on_hold_badge")
                                                ) {
                                                    Text(
                                                        text = "ON HOLD (OFFLINE)",
                                                        color = p.amber,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        fontSize = 9.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Text(
                                    text = "First-come, first-serve global username so distant friends can find & chat with you. Protect with a 4-6 digit PIN and Recovery Key.",
                                    color = p.textSecondary,
                                    fontSize = 10.sp
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { wizardRecoveryMode = false },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (!wizardRecoveryMode) p.emeraldDim else Color.Transparent
                                        ),
                                        border = BorderStroke(1.dp, if (!wizardRecoveryMode) p.emerald else p.border),
                                        shape = btnShape,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("CLAIM NEW", color = if (!wizardRecoveryMode) p.emerald else p.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = { wizardRecoveryMode = true },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (wizardRecoveryMode) p.cyanDim else Color.Transparent
                                        ),
                                        border = BorderStroke(1.dp, if (wizardRecoveryMode) p.cyan else p.border),
                                        shape = btnShape,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("LOGIN / RECOVER", color = if (wizardRecoveryMode) p.cyan else p.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                OutlinedTextField(
                                    value = wizardUsername,
                                    onValueChange = { wizardUsername = it.lowercase().replace(" ", "") },
                                    label = { Text("Username (e.g. alpha_op)") },
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
                                        .testTag("wizard_username_input")
                                )

                                if (wizardRecoveryMode) {
                                    Text(
                                        text = "Log in with your 4-6 digit PIN OR your Recovery Key to move all your E2EE keys, chats, contacts, groups & settings to this device.",
                                        color = p.cyan,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { wizardRecoveryWithKey = false },
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = if (!wizardRecoveryWithKey) p.emeraldDim else Color.Transparent
                                            ),
                                            border = BorderStroke(1.dp, if (!wizardRecoveryWithKey) p.emerald else p.border),
                                            shape = btnShape,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("wizard_login_with_pin_tab")
                                        ) {
                                            Text("WITH PIN", color = if (!wizardRecoveryWithKey) p.emerald else p.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                        OutlinedButton(
                                            onClick = { wizardRecoveryWithKey = true },
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = if (wizardRecoveryWithKey) p.amberDim else Color.Transparent
                                            ),
                                            border = BorderStroke(1.dp, if (wizardRecoveryWithKey) p.amber else p.border),
                                            shape = btnShape,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("wizard_login_with_key_tab")
                                        ) {
                                            Text("WITH RECOVERY KEY", color = if (wizardRecoveryWithKey) p.amber else p.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                if (!wizardRecoveryMode || !wizardRecoveryWithKey) {
                                    OutlinedTextField(
                                        value = wizardPin,
                                        onValueChange = { ch -> wizardPin = ch.filter { it.isDigit() }.take(6) },
                                        label = { Text(if (wizardRecoveryMode) "Enter 4-6 Digit PIN" else "Create 4-6 Digit PIN") },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = p.emerald,
                                            unfocusedBorderColor = p.border,
                                            focusedTextColor = p.textPrimary,
                                            unfocusedTextColor = p.textPrimary
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("wizard_pin_input")
                                    )
                                }

                                if (wizardRecoveryMode && wizardRecoveryWithKey) {
                                    OutlinedTextField(
                                        value = wizardRecoveryKeyInput,
                                        onValueChange = { wizardRecoveryKeyInput = it.uppercase().trim() },
                                        label = { Text("Recovery Key (PMRK-XXXX-XXXX-XXXX)") },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = p.amber,
                                            unfocusedBorderColor = p.border,
                                            focusedTextColor = p.textPrimary,
                                            unfocusedTextColor = p.textPrimary
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("wizard_recovery_key_input")
                                    )
                                }

                                Button(
                                    onClick = {
                                        onClaimOrRecoverUsername(
                                            wizardUsername,
                                            if (!wizardRecoveryMode || !wizardRecoveryWithKey) wizardPin else "",
                                            if (wizardRecoveryMode && wizardRecoveryWithKey) wizardRecoveryKeyInput else "",
                                            wizardRecoveryMode
                                        ) { ok, msg ->
                                            wizardAccountSuccess = ok
                                            wizardAccountFeedback = msg
                                        }
                                    },
                                    enabled = !isReservingUsername,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (p.isAero) Color.Transparent else p.cyan,
                                        contentColor = if (p.isLight || p.isAero) Color.White else Color.Black
                                    ),
                                    shape = btnShape,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aeroGlossOverlay(p, p.cyan, btnShape, cornerRadius = 18.dp)
                                        .testTag("wizard_claim_username_button")
                                ) {
                                    Text(
                                        text = if (isReservingUsername) {
                                            if (wizardRecoveryMode) "MOVING DATA..." else "RESERVING..."
                                        } else if (wizardRecoveryMode) {
                                            "LOGIN & MOVE DATA"
                                        } else {
                                            "CLAIM @USERNAME"
                                        },
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                }

                                if (wizardAccountFeedback.isNotBlank()) {
                                    Text(
                                        text = wizardAccountFeedback,
                                        color = if (wizardAccountSuccess) p.emerald else p.red,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }

                    // Host Hardware Detection + 40+ Model Catalog
                    item {
                        Surface(
                            color = if (p.isAero) Color.Transparent else p.card,
                            shape = cardShape,
                            border = BorderStroke(
                                1.dp,
                                if (mismatchReport.isMismatched && !isMismatchOverridden) p.amber else p.border
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aeroGlassPanel(p, cornerRadius = 22.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "PHONE HARDWARE & SILICON PROFILE",
                                        color = p.cyan,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(p.surfaceVariant)
                                            .clickable { hostHw = HardwareDetector.detectHardware(context) }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Rescan",
                                            tint = p.cyan,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("RE-SCAN", color = p.cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FingerprintBox("HOST", "${hostHw.manufacturer} ${hostHw.model}".take(18), p, Modifier.weight(1.2f))
                                    FingerprintBox("SILICON", hostHw.detectedSocFamily.familyName.take(15), p, Modifier.weight(1.1f))
                                    FingerprintBox("BATTERY", "${hostHw.batteryPercent}%", p, Modifier.weight(0.7f))
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Surface(
                                    color = p.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, p.border),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            Icon(
                                                imageVector = Icons.Default.PhoneAndroid,
                                                contentDescription = "Phone",
                                                tint = p.emerald,
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = selectedProfile.modelName,
                                                    color = p.textPrimary,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp
                                                )
                                                Text(
                                                    text = "${selectedProfile.socName} • +${selectedProfile.maxTxPowerDbm}dBm",
                                                    color = p.textSecondary,
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                        if (mismatchReport.isMismatched && !isMismatchOverridden) {
                                            Button(
                                                onClick = {
                                                    val matched = DeviceCatalog.allModels.find { it.socFamily == hostHw.detectedSocFamily }
                                                    if (matched != null) {
                                                        selectedProfile = matched
                                                        selectedBrandTab = matched.brand
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = p.amber),
                                                shape = btnShape,
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.heightIn(min = 34.dp)
                                            ) {
                                                Text("AUTO-MATCH", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isDropdownExpanded = !isDropdownExpanded }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (isDropdownExpanded || !isQuickSetupMode) "Phone Model Catalog (40+ Devices) ▲" else "Choose a Different Phone Model (40+ Devices) ▼",
                                        color = p.cyan,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                if (isDropdownExpanded || !isQuickSetupMode) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        items(DeviceBrand.values()) { brand ->
                                            val isSel = selectedBrandTab == brand
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (isSel) p.emerald else p.surfaceVariant)
                                                    .clickable { selectedBrandTab = brand }
                                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                                            ) {
                                                Text(
                                                    text = brand.displayName,
                                                    color = if (isSel) (if (p.isLight || p.isAero) Color.White else Color.Black) else p.textSecondary,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    val modelsForBrand = DeviceCatalog.allModels.filter { it.brand == selectedBrandTab }
                                    val visibleModels = if (isQuickSetupMode) modelsForBrand.take(5) else modelsForBrand
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        visibleModels.forEach { profile ->
                                            val isChosen = selectedProfile.id == profile.id
                                            Surface(
                                                color = if (isChosen) p.surfaceVariant else p.surface,
                                                shape = RoundedCornerShape(6.dp),
                                                border = BorderStroke(1.dp, if (isChosen) p.emerald else p.border),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { selectedProfile = profile }
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = profile.modelName,
                                                            color = if (isChosen) p.emerald else p.textPrimary,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Text(
                                                            text = "${profile.socName} • ${profile.wifiBleDriver}",
                                                            color = p.textSecondary,
                                                            fontSize = 8.sp
                                                        )
                                                    }
                                                    Text(
                                                        text = "+${profile.maxTxPowerDbm}dBm",
                                                        color = p.amber,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Radio & KernelSU Root Status
                    item {
                        Surface(
                            color = if (p.isAero) Color.Transparent else if (probeResult.isRooted) p.card else p.redDim,
                            shape = cardShape,
                            border = BorderStroke(1.dp, if (probeResult.isRooted) p.emerald else p.border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aeroGlassPanel(p, cornerRadius = 22.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        imageVector = if (probeResult.isRooted) Icons.Default.Security else Icons.Default.Sensors,
                                        contentDescription = "Root Status",
                                        tint = if (probeResult.isRooted) p.emerald else p.cyan,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = if (probeResult.isRooted) "Super-Node Booster Unlocked (+25 dBm)" else "Standard Legal Radio Mode (+14 dBm)",
                                            color = if (probeResult.isRooted) p.emerald else p.textPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                        Text(
                                            text = if (probeResult.isRooted) "KernelSU verified (${probeResult.rootType})" else "Works cleanly without root",
                                            color = p.textSecondary,
                                            fontSize = 9.sp
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        isProbingRoot = true
                                        probeResult = RootDetector.checkRootAccess()
                                        isProbingRoot = false
                                    },
                                    enabled = !isProbingRoot,
                                    border = BorderStroke(1.dp, p.border),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = btnShape,
                                    modifier = Modifier.heightIn(min = 34.dp)
                                ) {
                                    Text(if (probeResult.isRooted) "RE-VERIFY" else "CHECK ROOT", fontSize = 9.sp, color = p.textSecondary)
                                }
                            }
                        }
                    }
                }

                // =========================================================================
                // PART 4: RADIO TRANSPORT, OFFLINE MAPS & READY SUMMARY
                // =========================================================================
                else -> {
                    item {
                        Surface(
                            color = if (p.isAero) Color.Transparent else p.card,
                            shape = cardShape,
                            border = BorderStroke(1.dp, if (isPureOffGrid) p.emerald else p.cyan),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aeroGlassPanel(p, cornerRadius = 22.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "NETWORK TRANSMISSION POSTURE",
                                        color = if (isPureOffGrid) p.emerald else p.cyan,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Surface(
                                        color = if (isPureOffGrid) p.emeraldDim else p.cyanDim,
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, if (isPureOffGrid) p.emerald else p.cyan)
                                    ) {
                                        Text(
                                            text = if (isPureOffGrid) "ACTIVE: OFF-GRID" else "ACTIVE: HYBRID",
                                            color = if (isPureOffGrid) p.emerald else p.cyan,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                if (!isQuickSetupMode) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(btnShape)
                                                .background(if (isPureOffGrid) p.emerald else p.surfaceVariant)
                                                .clickable { selectNetworkPosture(true) }
                                                .padding(vertical = 8.dp)
                                                .testTag("wizard_pro_offgrid_button"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "AIR-GAPPED OFF-GRID",
                                                color = if (isPureOffGrid) (if (p.isLight || p.isAero) Color.White else Color.Black) else p.textSecondary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(btnShape)
                                                .background(if (!isPureOffGrid) p.cyan else p.surfaceVariant)
                                                .clickable { selectNetworkPosture(false) }
                                                .padding(vertical = 8.dp)
                                                .testTag("wizard_pro_internet_button"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "WITH INTERNET (HYBRID)",
                                                color = if (!isPureOffGrid) (if (p.isLight || p.isAero) Color.White else Color.Black) else p.textSecondary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                Surface(
                                    color = if (isPureOffGrid) p.surfaceVariant else p.surface,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, if (isPureOffGrid) p.emerald else p.border),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectNetworkPosture(true) }
                                        .testTag("wizard_easy_offgrid_card")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isPureOffGrid,
                                            onClick = { selectNetworkPosture(true) },
                                            colors = RadioButtonDefaults.colors(selectedColor = p.emerald)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column {
                                            Text("Pure Off-Grid Mode (Recommended)", color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            Text("100% offline phone-to-phone via Bluetooth & Wi-Fi Direct radios.", color = p.textSecondary, fontSize = 9.sp)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Surface(
                                    color = if (!isPureOffGrid) p.surfaceVariant else p.surface,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, if (!isPureOffGrid) p.cyan else p.border),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectNetworkPosture(false) }
                                        .testTag("wizard_easy_internet_card")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = !isPureOffGrid,
                                            onClick = { selectNetworkPosture(false) },
                                            colors = RadioButtonDefaults.colors(selectedColor = p.cyan)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column {
                                            Text("With Internet (Hybrid Gateway Bridge)", color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            Text("Combines local BLE/Wi-Fi mesh with encrypted HTTPS cloud relay & map downloads.", color = p.textSecondary, fontSize = 9.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Offline Map Tiles Card
                    item {
                        Surface(
                            color = if (p.isAero) Color.Transparent else p.card,
                            shape = cardShape,
                            border = BorderStroke(1.dp, if (isOsmOfflineCached) p.emerald else p.border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aeroGlassPanel(p, cornerRadius = 22.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Map,
                                            contentDescription = "Offline Tiles",
                                            tint = if (isOsmOfflineCached) p.emerald else p.cyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "OFFLINE MAP TILES",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = p.textPrimary
                                            )
                                        )
                                    }
                                    if (isOsmOfflineCached) {
                                        Surface(color = p.emeraldDim, shape = RoundedCornerShape(4.dp)) {
                                            Text(
                                                text = "READY",
                                                color = p.emerald,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Cache regional OpenStreetMap tiles locally for 100% offline tactical navigation.",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, color = p.textSecondary)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = { showMapAreaSelectorDialog = true },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = if (isOsmOfflineCached) p.emerald else p.textPrimary
                                    ),
                                    border = BorderStroke(1.dp, if (isOsmOfflineCached) p.emerald else p.border),
                                    shape = btnShape,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("setup_offline_map_tiles_button")
                                ) {
                                    Icon(
                                        imageVector = if (isOsmOfflineCached) Icons.Default.CheckCircle else Icons.Default.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isOsmOfflineCached) "TILES PRE-CACHED (TAP FOR MORE)" else "SELECT & DOWNLOAD MAP TILES",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Ready Summary Card
                    item {
                        Surface(
                            color = if (p.isAero) Color.Transparent else p.card,
                            shape = cardShape,
                            border = BorderStroke(1.dp, p.emerald),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aeroGlassPanel(p, cornerRadius = 22.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "READY TO LAUNCH",
                                    color = p.emerald,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FingerprintBox("OPERATOR", "${callsign.trim().ifEmpty { "Operator" }} ($derivedNodeId)", p, Modifier.weight(1f))
                                    FingerprintBox("THEME", selectedThemeState.displayName, p, Modifier.weight(1f))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FingerprintBox("DEVICE", selectedProfile.modelName, p, Modifier.weight(1f))
                                    FingerprintBox("NETWORK", if (isPureOffGrid) "Pure Off-Grid" else "Hybrid Bridge", p, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMismatchModal && mismatchReport.isMismatched) {
        HardwareMismatchWarningDialog(
            report = mismatchReport,
            selectedProfile = selectedProfile,
            onAutoSwitchToDetected = {
                val matched = DeviceCatalog.allModels.find { it.socFamily == hostHw.detectedSocFamily }
                if (matched != null) {
                    selectedProfile = matched
                    selectedBrandTab = matched.brand
                }
                showMismatchModal = false
            },
            onForceOverrideAnyway = {
                isMismatchOverridden = true
                showMismatchModal = false
            },
            onDismiss = { showMismatchModal = false }
        )
    }

    if (showMapAreaSelectorDialog) {
        InteractiveMapAreaSelectorDialog(
            initialLat = 37.7749,
            initialLon = -122.4194,
            onDismissRequest = { showMapAreaSelectorDialog = false },
            onDownloadComplete = {
                isOsmOfflineCached = true
                showMapAreaSelectorDialog = false
            }
        )
    }
}

@Composable
private fun WizardFeatureCard(
    icon: ImageVector,
    accent: Color,
    title: String,
    description: String,
    palette: PulseMeshPalette,
    cardShape: androidx.compose.ui.graphics.Shape,
) {
    Surface(
        color = if (palette.isAero) Color.Transparent else palette.card,
        shape = cardShape,
        border = BorderStroke(1.dp, palette.border),
        modifier = Modifier
            .fillMaxWidth()
            .aeroGlassPanel(palette, cornerRadius = 22.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (palette.isAero) {
                AeroGlossBadgeIcon(
                    imageVector = icon,
                    accentColor = accent,
                    contentDescription = title,
                    badgeSize = 40.dp,
                    iconSize = 20.dp,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    color = palette.textSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun FingerprintBox(
    label: String,
    value: String,
    palette: PulseMeshPalette,
    modifier: Modifier = Modifier
) {
    Surface(
        color = palette.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, palette.border),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(label, color = palette.textMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text(
                text = value,
                color = palette.textPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
