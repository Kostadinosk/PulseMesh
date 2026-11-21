// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.ui.screens

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.crypto.TacticalE2eeManager
import com.example.model.PhoneModelProfile
import com.example.ui.components.InteractiveMapAreaSelectorDialog
import com.example.ui.theme.*
import com.example.util.AppTheme
import com.example.util.DetectedHardwareInfo

private data class WizardThemeOption(
    val theme: AppTheme,
    val title: String,
    val subtitle: String,
    val previewColors: List<Color>,
    val icon: ImageVector
)

/**
 * Streamlined 2-Step Onboarding Wizard (PulseMesh v1.4.3):
 * - Step 1: Operator Identity (Callsign + optional @username claim) & Theme picker
 * - Step 2: Off-Grid vs Hybrid Mode + optional Offline Map pre-cache & Launch
 *
 * Automatically detects Android BLE/Wi-Fi hardware with zero manual phone model picking
 * and zero hardware mismatch warning popups.
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
    val p = LocalPulsePalette.current
    val cardShape = tacticalCardShape(isSimpleMode = true, isOriginal = p.isOriginal, isAero = p.isAero)
    val btnShape = tacticalButtonShape(isSimpleMode = true, isOriginal = p.isOriginal, isAero = p.isAero)

    var currentStep by remember { mutableIntStateOf(0) } // 0 = Identity & Theme, 1 = Mode & Launch
    var callsign by remember { mutableStateOf(initialCallsign.ifBlank { "Operator" }) }
    var isPureOffGrid by remember { mutableStateOf(initialPureOffGrid) }
    var selectedTheme by remember { mutableStateOf(currentAppTheme) }
    var showMapPreCacheDialog by remember { mutableStateOf(false) }

    // Optional @username claim fields
    var showAccountSection by remember { mutableStateOf(false) }
    var usernameInput by remember { mutableStateOf(initialClaimedUsername) }
    var pinInput by remember { mutableStateOf("") }
    var recoveryKeyInput by remember { mutableStateOf(initialRecoveryKey) }
    var isRecoveryMode by remember { mutableStateOf(false) }
    var accountFeedback by remember { mutableStateOf(usernameAuthStatus) }

    val derivedNodeId = remember(callsign) {
        val clean = callsign.trim()
        if (clean.isBlank()) initialNodeId else TacticalE2eeManager.deriveNodeIdFromCallsign(clean)
    }

    val themeOptions = remember {
        listOf(
            WizardThemeOption(
                theme = AppTheme.TACTICAL_DARK,
                title = "Tactical Dark",
                subtitle = "High-contrast OLED dark slate with emerald & cyan accents",
                previewColors = listOf(Color(0xFF0A0E14), Color(0xFF00E676), Color(0xFF00B0FF)),
                icon = Icons.Default.DarkMode
            ),
            WizardThemeOption(
                theme = AppTheme.FRUTIGER_AERO,
                title = "Frutiger Aero",
                subtitle = "Translucent sky glass panels & sunlit daylight clarity",
                previewColors = listOf(Color(0xFFB3E5FF), Color(0xFF0088E6), Color(0xFF0BA354)),
                icon = Icons.Default.WbSunny
            ),
            WizardThemeOption(
                theme = AppTheme.FRUTIGER_AERO_DARK,
                title = "Dark Aero",
                subtitle = "Midnight sapphire glass with aurora teal highlights",
                previewColors = listOf(Color(0xFF072137), Color(0xFF38CFFF), Color(0xFF19E68C)),
                icon = Icons.Default.NightsStay
            ),
            WizardThemeOption(
                theme = AppTheme.TACTICAL_LIGHT,
                title = "Daylight Light",
                subtitle = "Crisp outdoor sun-readable light mode",
                previewColors = listOf(Color(0xFFF2F5F9), Color(0xFFD97706), Color(0xFF008F4C)),
                icon = Icons.Default.LightMode
            ),
            WizardThemeOption(
                theme = AppTheme.ORIGINAL,
                title = "Original",
                subtitle = "Classic PulseMesh amber & emerald palette",
                previewColors = listOf(Color(0xFF101620), Color(0xFF00E676), Color(0xFFFF9100)),
                icon = Icons.Default.Radio
            )
        )
    }

    Surface(
        color = p.background,
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .testTag("tactical_device_wizard")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Progress Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "PULSEMESH SETUP",
                        color = p.cyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = if (currentStep == 0) "Step 1 of 2 • Identity & Look" else "Step 2 of 2 • Radio Mode",
                        color = p.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
                if (onDismiss != null) {
                    TextButton(onClick = onDismiss) {
                        Text("SKIP", color = p.textMuted, fontWeight = FontWeight.Bold)
                    }
                }
            }

            LinearProgressIndicator(
                progress = { if (currentStep == 0) 0.5f else 1.0f },
                color = p.emerald,
                trackColor = p.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (currentStep == 0) {
                    // Auto-Detected Hardware Confirmation Pill (Zero manual phone picker)
                    item {
                        Surface(
                            color = p.emerald.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, p.emerald.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.Verified, contentDescription = null, tint = p.emerald, modifier = Modifier.size(20.dp))
                                Column {
                                    Text(
                                        text = "${Build.MANUFACTURER} ${Build.MODEL} Auto-Configured",
                                        color = p.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "Android API ${Build.VERSION.SDK_INT} • BLE, L2CAP, Wi-Fi Aware & Bell 202 ready",
                                        color = p.textSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                    // Callsign Input Card
                    item {
                        Surface(
                            color = p.card,
                            shape = cardShape,
                            border = BorderStroke(1.dp, p.border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "YOUR CALLSIGN",
                                    color = p.cyan,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                                OutlinedTextField(
                                    value = callsign,
                                    onValueChange = { if (it.length <= 24) callsign = it },
                                    label = { Text("Display Name / Callsign") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("wizard_callsign_input")
                                )
                                Text(
                                    text = "Mesh Node ID: $derivedNodeId • E2EE ECDH P-256 generated on-device",
                                    color = p.textMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )

                                TextButton(
                                    onClick = { showAccountSection = !showAccountSection },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        text = if (showAccountSection) "▾ Hide Optional @username Account" else "▸ Optional: Claim or Restore @username Account",
                                        color = p.cyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                if (showAccountSection) {
                                    OutlinedTextField(
                                        value = usernameInput,
                                        onValueChange = { usernameInput = it.trim().take(24) },
                                        label = { Text("@username (e.g. alex_k)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = pinInput,
                                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinInput = it },
                                        label = { Text("4-6 Digit Vault PIN") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (isRecoveryMode) {
                                        OutlinedTextField(
                                            value = recoveryKeyInput,
                                            onValueChange = { recoveryKeyInput = it.trim() },
                                            label = { Text("Recovery Key (PMRK-...)") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            enabled = !isReservingUsername && usernameInput.length >= 3 && pinInput.length >= 4,
                                            onClick = {
                                                onClaimOrRecoverUsername(usernameInput, pinInput, recoveryKeyInput, isRecoveryMode) { ok, msg ->
                                                    accountFeedback = msg
                                                    if (ok && callsign.equals("Operator", ignoreCase = true)) {
                                                        callsign = usernameInput
                                                    }
                                                }
                                            },
                                            shape = btnShape
                                        ) {
                                            Text(if (isReservingUsername) "WORKING..." else if (isRecoveryMode) "RESTORE" else "CLAIM @USERNAME", fontSize = 11.sp)
                                        }
                                        TextButton(onClick = { isRecoveryMode = !isRecoveryMode }) {
                                            Text(if (isRecoveryMode) "New Account" else "Restore Existing", fontSize = 11.sp)
                                        }
                                    }
                                    if (accountFeedback.isNotBlank()) {
                                        Text(accountFeedback, color = p.emerald, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }

                    // Compact 5-Theme Picker
                    item {
                        Surface(
                            color = p.card,
                            shape = cardShape,
                            border = BorderStroke(1.dp, p.border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "CHOOSE APPEARANCE",
                                    color = p.cyan,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                                themeOptions.forEach { opt ->
                                    val isSelected = selectedTheme == opt.theme
                                    Surface(
                                        color = if (isSelected) p.emerald.copy(alpha = 0.14f) else p.surfaceVariant,
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, if (isSelected) p.emerald else p.border),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedTheme = opt.theme
                                                onSelectAppTheme?.invoke(opt.theme)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(CircleShape)
                                                    .background(Brush.linearGradient(opt.previewColors))
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(opt.title, color = p.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                                Text(opt.subtitle, color = p.textMuted, fontSize = 11.sp)
                                            }
                                            if (isSelected) {
                                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = p.emerald, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // STEP 2: Pure Off-Grid vs Hybrid Internet Mode
                    item {
                        TransportPostureCard(
                            title = "Pure Off-Grid Mesh (Zero Internet)",
                            subtitle = "100% local BLE, L2CAP, Wi-Fi Aware NAN, Hotspot WebSocket & Bell 202 Acoustic. Zero outbound internet requests or update pings.",
                            selected = isPureOffGrid,
                            accent = p.emerald,
                            icon = Icons.Default.BluetoothSearching,
                            onClick = {
                                isPureOffGrid = true
                                onSelectTransportPosture?.invoke(true)
                            }
                        )
                    }
                    item {
                        TransportPostureCard(
                            title = "Hybrid Mesh + Encrypted Internet Relay",
                            subtitle = "Uses local off-grid radios first, plus optional AES-256-GCM relay for distant @username friends, WebRTC STUN, and automatic startup update checks.",
                            selected = !isPureOffGrid,
                            accent = p.cyan,
                            icon = Icons.Default.CloudDone,
                            onClick = {
                                isPureOffGrid = false
                                onSelectTransportPosture?.invoke(false)
                            }
                        )
                    }
                    item {
                        Surface(
                            color = p.card,
                            shape = cardShape,
                            border = BorderStroke(1.dp, p.border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "OPTIONAL: PRE-CACHE OFFLINE MAP",
                                    color = p.textSecondary,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "Download offline street/trail tiles for your area before heading off-grid (you can also do this anytime from the Map tab).",
                                    color = p.textMuted,
                                    fontSize = 12.sp
                                )
                                OutlinedButton(
                                    onClick = { showMapPreCacheDialog = true },
                                    shape = btnShape
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("SELECT MAP AREA TO CACHE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Navigation Buttons
            Surface(
                color = p.card,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentStep > 0) {
                        OutlinedButton(
                            onClick = { currentStep = 0 },
                            shape = btnShape
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("BACK", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    Button(
                        onClick = {
                            if (currentStep == 0) {
                                currentStep = 1
                            } else {
                                onCompleteWizard(
                                    callsign.trim().ifBlank { "Operator" },
                                    derivedNodeId,
                                    initialSelectedProfile,
                                    isPureOffGrid,
                                    !isPureOffGrid
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = p.emerald,
                            contentColor = Color.Black
                        ),
                        shape = btnShape,
                        modifier = Modifier.testTag("wizard_primary_action_btn")
                    ) {
                        Text(
                            text = if (currentStep == 0) "NEXT" else "LAUNCH PULSEMESH",
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = if (currentStep == 0) Icons.AutoMirrored.Filled.ArrowForward else Icons.Default.RocketLaunch,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }

    if (showMapPreCacheDialog) {
        InteractiveMapAreaSelectorDialog(
            initialLat = 37.9838,
            initialLon = 23.7275,
            onDismissRequest = { showMapPreCacheDialog = false }
        )
    }
}

@Composable
private fun TransportPostureCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    accent: Color,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val p = LocalPulsePalette.current
    Surface(
        color = if (selected) accent.copy(alpha = 0.14f) else p.card,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else p.border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(subtitle, color = p.textSecondary, fontSize = 12.sp)
            }
            if (selected) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}
