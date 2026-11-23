// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.ui.components.InteractiveMapAreaSelectorDialog
import com.kostadinosk.pulsemesh.ui.theme.LocalPulseTokens
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.ui.theme.pulseSurfaceCard
import com.kostadinosk.pulsemesh.util.AppStrings
import com.kostadinosk.pulsemesh.util.HardwareCapabilityDetector
import com.kostadinosk.pulsemesh.util.HardwareCapabilityReport
import com.kostadinosk.pulsemesh.util.ThemeBrightness
import com.kostadinosk.pulsemesh.util.ThemePreset
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState

enum class OnboardingTrack {
    CHOOSER,
    FAST_3_STEP,
    CUSTOM_5_STEP
}

/**
 * PulseMesh v2.0 Dual-Track Onboarding:
 * - Step 0: Choose Fast Setup (3 steps, <30s) OR Custom Field-Ready Setup (5 steps, adds Offline Map + ICE Card).
 * - Powered by runtime HardwareCapabilityDetector (accurate device/codename/SoC & OEM battery tips).
 */
@Composable
fun PulseOnboardingScreen(
    state: MeshUiState,
    onUpdateCallsign: (String) -> Unit,
    onReserveUsername: (username: String, passphrase: String) -> Unit,
    onRequestPermissions: () -> Unit,
    onDownloadMapRegion: (lat: Double, lon: Double, radiusKm: Double, label: String) -> Unit,
    onSaveIceInfo: (String) -> Unit,
    onOpenQrScanner: () -> Unit,
    onFinish: () -> Unit
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    val context = LocalContext.current
    val lang = state.appLanguage

    var track by remember { mutableStateOf(OnboardingTrack.CHOOSER) }
    var stepIndex by remember { mutableIntStateOf(1) } // 1..3 (fast) or 1..5 (custom)
    var callsignInput by remember(state.operatorCallsign) { mutableStateOf(state.operatorCallsign) }
    var usernameInput by remember(state.claimedUsername) { mutableStateOf(state.claimedUsername) }
    var passphraseInput by remember { mutableStateOf("") }
    var iceInput by remember(state.iceMedicalInfo) { mutableStateOf(state.iceMedicalInfo) }
    var showMapSelector by remember { mutableStateOf(false) }

    val totalSteps = if (track == OnboardingTrack.CUSTOM_5_STEP) 5 else 3

    Surface(
        color = p.background,
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .testTag("pulse_onboarding_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "PulseMesh v2.0",
                        color = tokens.accentPrimary,
                        fontFamily = tokens.labelFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = if (track == OnboardingTrack.CHOOSER) {
                            AppStrings.tr(lang, "Choose Your Setup", "Επιλέξτε Ρύθμιση")
                        } else {
                            AppStrings.tr(lang, "Step $stepIndex of $totalSteps", "Βήμα $stepIndex από $totalSteps")
                        },
                        color = p.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                }
                TextButton(onClick = onFinish, modifier = Modifier.testTag("onboarding_skip_btn")) {
                    Text(AppStrings.tr(lang, "Skip", "Παράλειψη"), color = p.textSecondary)
                }
            }

            if (track != OnboardingTrack.CHOOSER) {
                LinearProgressIndicator(
                    progress = { stepIndex.toFloat() / totalSteps.toFloat() },
                    color = tokens.accentPrimary,
                    trackColor = p.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(tokens.pillShape)
                )
            }

            when {
                track == OnboardingTrack.CHOOSER -> {
                    Text(
                        text = AppStrings.tr(
                            lang,
                            "Start chatting off-grid in under 30 seconds, or complete the full field-ready setup with offline maps and emergency medical info.",
                            "Ξεκινήστε εκτός δικτύου σε λιγότερο από 30 δευτερόλεπτα ή ολοκληρώστε την πλήρη ρύθμιση πεδίου με χάρτες εκτός σύνδεσης και κάρτα ICE."
                        ),
                        color = p.textSecondary,
                        fontSize = 13.sp
                    )

                    OnboardingTrackCard(
                        title = AppStrings.tr(lang, "Fast Setup (Recommended)", "Γρήγορη Ρύθμιση (Προτείνεται)"),
                        badge = AppStrings.tr(lang, "3 STEPS • <30s", "3 ΒΗΜΑΤΑ • <30δ"),
                        description = AppStrings.tr(
                            lang,
                            "1. Pick Callsign + optional @username\n2. Grant Radio Permissions + Auto-Detected Hardware check\n3. Quick QR / NFC Pair or jump straight into Chats",
                            "1. Επιλογή Callsign + προαιρετικό @username\n2. Άδειες Ραδιοσυχνοτήτων + Αυτόματη ανίχνευση συσκευής\n3. Γρήγορη σύζευξη QR/NFC ή είσοδος στα Chats"
                        ),
                        icon = Icons.Default.Bolt,
                        accent = tokens.accentPrimary,
                        onClick = {
                            track = OnboardingTrack.FAST_3_STEP
                            stepIndex = 1
                        },
                        tag = "onboarding_track_fast"
                    )

                    OnboardingTrackCard(
                        title = AppStrings.tr(lang, "Custom Field-Ready Setup", "Πλήρης Ρύθμιση Πεδίου"),
                        badge = AppStrings.tr(lang, "5 STEPS • EXPEDITION", "5 ΒΗΜΑΤΑ • ΕΚΣΤΡΑΤΕΙΑ"),
                        description = AppStrings.tr(
                            lang,
                            "Includes everything in Fast Setup PLUS:\n4. Download Offline Map Region for zero-signal navigation\n5. Configure ICE Emergency Medical Card & Safety check-in",
                            "Περιλαμβάνει τη Γρήγορη Ρύθμιση ΚΑΙ:\n4. Λήψη Περιοχής Χάρτη Εκτός Σύνδεσης\n5. Κάρτα Έκτακτης Ανάγκης ICE & ρυθμίσεις ασφάλειας"
                        ),
                        icon = Icons.Default.Tune,
                        accent = tokens.accentSecondary,
                        onClick = {
                            track = OnboardingTrack.CUSTOM_5_STEP
                            stepIndex = 1
                        },
                        tag = "onboarding_track_custom"
                    )

                    // Compact auto-detected hardware preview right on Step 0
                    state.hardwareReport?.let { hw ->
                        HardwareSummaryCard(hw = hw, onOpenBatterySettings = {
                            HardwareCapabilityDetector.launchOemBatterySettings(context, hw.batteryBypassGuide)
                        })
                    }
                }

                stepIndex == 1 -> {
                    // Step 1: Callsign + Optional (Recommended) @username
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pulseSurfaceCard()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = tokens.accentPrimary)
                            Text(
                                text = AppStrings.tr(lang, "Your Identity", "Η Ταυτότητά σας"),
                                color = p.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        OutlinedTextField(
                            value = callsignInput,
                            onValueChange = { callsignInput = it },
                            label = { Text(AppStrings.tr(lang, "Callsign / Display Name", "Όνομα / Callsign")) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_callsign_input")
                        )
                        Text(
                            text = AppStrings.tr(
                                lang,
                                "Optional (Recommended): Reserve a permanent @username so friends can find you over Internet Hybrid Relay too.",
                                "Προαιρετικό (Προτείνεται): Κρατήστε ένα μόνιμο @username ώστε να σας βρίσκουν φίλοι και μέσω υβριδικού δικτύου."
                            ),
                            color = p.textSecondary,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = usernameInput,
                            onValueChange = { usernameInput = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' }.take(24) },
                            label = { Text(AppStrings.tr(lang, "@username (Optional)", "@username (Προαιρετικό)")) },
                            prefix = { Text("@") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (usernameInput.length >= 3) {
                            OutlinedTextField(
                                value = passphraseInput,
                                onValueChange = { passphraseInput = it },
                                label = { Text(AppStrings.tr(lang, "Vault Passphrase (8+ chars, optional)", "Κωδικός Vault (8+ χαρακτήρες)")) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                stepIndex == 2 -> {
                    // Step 2: Permissions + Accurate Auto-Detected Hardware & OEM Battery Bypass
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pulseSurfaceCard()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = tokens.accentPrimary)
                            Text(
                                text = AppStrings.tr(lang, "Radio Permissions & Device Check", "Άδειες Ραδιοσυχνοτήτων & Έλεγχος Συσκευής"),
                                color = p.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Text(
                            text = if (state.hasRequiredPermissions) {
                                AppStrings.tr(lang, "Bluetooth, Nearby Wi-Fi, Location, and Audio permissions are granted.", "Οι άδειες Bluetooth, Wi-Fi, Τοποθεσίας και Ήχου είναι ενεργές.")
                            } else {
                                AppStrings.tr(lang, "PulseMesh uses Bluetooth LE, Wi-Fi Direct, and optional Location strictly locally for peer-to-peer mesh discovery.", "Το PulseMesh χρησιμοποιεί Bluetooth LE, Wi-Fi Direct και Τοποθεσία αποκλειστικά τοπικά για ανίχνευση κόμβων.")
                            },
                            color = p.textSecondary,
                            fontSize = 12.sp
                        )
                        Button(
                            onClick = onRequestPermissions,
                            shape = tokens.buttonShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.hasRequiredPermissions) tokens.statusOnline else tokens.accentPrimary,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = if (state.hasRequiredPermissions) {
                                    AppStrings.tr(lang, "Permissions Active", "Άδειες Ενεργές")
                                } else {
                                    AppStrings.tr(lang, "Grant Mesh Permissions", "Χορήγηση Αδειών Mesh")
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    state.hardwareReport?.let { hw ->
                        HardwareSummaryCard(hw = hw, onOpenBatterySettings = {
                            HardwareCapabilityDetector.launchOemBatterySettings(context, hw.batteryBypassGuide)
                        })
                    }
                }

                track == OnboardingTrack.CUSTOM_5_STEP && stepIndex == 3 -> {
                    // Step 3 (Custom): Offline Map Area Download
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pulseSurfaceCard()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Map, contentDescription = null, tint = tokens.accentSecondary)
                            Text(
                                text = AppStrings.tr(lang, "Offline Map Region", "Περιοχή Χάρτη Εκτός Σύνδεσης"),
                                color = p.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Text(
                            text = AppStrings.tr(
                                lang,
                                "Pre-cache your city, trail, or region now so Tactical Map 2.0 works completely offline. You can also import .mbtiles or .pmtiles later.",
                                "Αποθηκεύστε τον χάρτη της περιοχής σας τώρα ώστε να λειτουργεί 100% εκτός σύνδεσης."
                            ),
                            color = p.textSecondary,
                            fontSize = 12.sp
                        )
                        OutlinedButton(
                            onClick = { showMapSelector = true },
                            shape = tokens.buttonShape,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(AppStrings.tr(lang, "Select & Download Offline Area", "Επιλογή & Λήψη Περιοχής"))
                        }
                    }
                }

                track == OnboardingTrack.CUSTOM_5_STEP && stepIndex == 4 -> {
                    // Step 4 (Custom): ICE Emergency Medical Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pulseSurfaceCard()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.HealthAndSafety, contentDescription = null, tint = tokens.statusDanger)
                            Text(
                                text = AppStrings.tr(lang, "ICE Emergency Card", "Κάρτα Έκτακτης Ανάγκης ICE"),
                                color = p.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Text(
                            text = AppStrings.tr(
                                lang,
                                "Optional medical notes (blood type, allergies, emergency contact) attached only when you trigger SOS.",
                                "Προαιρετικά ιατρικά στοιχεία (ομάδα αίματος, αλλεργίες, επαφή) που στέλνονται μόνο σε SOS."
                            ),
                            color = p.textSecondary,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = iceInput,
                            onValueChange = { iceInput = it },
                            label = { Text(AppStrings.tr(lang, "Blood Type, Allergies, ICE Contact", "Ομάδα Αίματος, Αλλεργίες, Επαφή")) },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                else -> {
                    // Final Step (Step 3 in Fast, Step 5 in Custom): Quick Pair & Enter Chats
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pulseSurfaceCard(accentBorder = tokens.statusOnline.copy(alpha = 0.5f))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = tokens.statusOnline)
                            Text(
                                text = AppStrings.tr(lang, "Ready to Mesh", "Έτοιμοι για Σύνδεση"),
                                color = p.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Text(
                            text = AppStrings.tr(
                                lang,
                                "Your Signal Double Ratchet keys and hardware radios are active. Scan a friend's QR code now or jump straight into Chats.",
                                "Τα κλειδιά Double Ratchet και τα ραδιοσυστήματα είναι ενεργά. Σαρώστε QR φίλου τώρα ή μπείτε στα Chats."
                            ),
                            color = p.textSecondary,
                            fontSize = 12.sp
                        )
                        OutlinedButton(
                            onClick = onOpenQrScanner,
                            shape = tokens.buttonShape,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(AppStrings.tr(lang, "Scan Peer or Group QR", "Σάρωση QR Κόμβου ή Ομάδας"))
                        }
                    }
                }
            }

            if (track != OnboardingTrack.CHOOSER) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (stepIndex == 1) track = OnboardingTrack.CHOOSER else stepIndex--
                        },
                        shape = tokens.buttonShape,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(AppStrings.tr(lang, "Back", "Πίσω"))
                    }
                    Button(
                        onClick = {
                            if (stepIndex == 1) {
                                if (callsignInput.isNotBlank()) onUpdateCallsign(callsignInput.trim())
                                if (usernameInput.length >= 3) onReserveUsername(usernameInput.trim(), passphraseInput)
                            }
                            if (track == OnboardingTrack.CUSTOM_5_STEP && stepIndex == 4 && iceInput.isNotBlank()) {
                                onSaveIceInfo(iceInput.trim())
                            }
                            if (stepIndex < totalSteps) {
                                stepIndex++
                            } else {
                                onFinish()
                            }
                        },
                        shape = tokens.buttonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.accentPrimary,
                            contentColor = if (tokens.isDark) Color.Black else Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("onboarding_next_btn")
                    ) {
                        Text(
                            text = if (stepIndex < totalSteps) {
                                AppStrings.tr(lang, "Continue", "Συνέχεια")
                            } else {
                                AppStrings.tr(lang, "Enter Chats", "Είσοδος στα Chats")
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (showMapSelector) {
        InteractiveMapAreaSelectorDialog(
            initialLat = (state.userLatitude ?: 0.0).takeIf { it != 0.0 } ?: 37.9838,
            initialLon = (state.userLongitude ?: 0.0).takeIf { it != 0.0 } ?: 23.7275,
            onDismissRequest = { showMapSelector = false },
            onDownloadComplete = {
                showMapSelector = false
                onDownloadMapRegion(
                    (state.userLatitude ?: 0.0).takeIf { it != 0.0 } ?: 37.9838,
                    (state.userLongitude ?: 0.0).takeIf { it != 0.0 } ?: 23.7275,
                    10.0,
                    "Offline Area"
                )
            }
        )
    }
}

@Composable
private fun OnboardingTrackCard(
    title: String,
    badge: String,
    description: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    tag: String
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseSurfaceCard(accentBorder = accent.copy(alpha = 0.55f))
            .clickable { onClick() }
            .padding(16.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
                Text(title, color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Surface(
                color = accent.copy(alpha = 0.16f),
                shape = tokens.pillShape,
                border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
            ) {
                Text(
                    text = badge,
                    color = accent,
                    fontFamily = tokens.labelFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        Text(description, color = p.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
fun HardwareSummaryCard(
    hw: HardwareCapabilityReport,
    onOpenBatterySettings: () -> Unit
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseSurfaceCard()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Memory, contentDescription = null, tint = tokens.accentSecondary, modifier = Modifier.size(18.dp))
            Column {
                Text(
                    text = hw.resolvedMarketingName,
                    color = p.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "${hw.resolvedSocFamily} • ${hw.resolvedRegion} • codename: ${hw.deviceCodename.ifBlank { hw.model }}",
                    color = p.textSecondary,
                    fontSize = 11.sp
                )
            }
        }
        Text(
            text = "Radios: ${hw.activeHardwareSummary}",
            color = tokens.statusOnline,
            fontFamily = tokens.labelFontFamily,
            fontSize = 11.sp
        )
        Text(
            text = "OEM Background Policy: ${hw.batteryBypassGuide.backgroundKillerName} (${hw.batteryBypassGuide.severityLabel})",
            color = if (hw.batteryBypassGuide.isIgnoringBatteryOptimizations) tokens.statusOnline else tokens.statusWarning,
            fontSize = 11.sp
        )
        if (!hw.batteryBypassGuide.isIgnoringBatteryOptimizations) {
            OutlinedButton(
                onClick = onOpenBatterySettings,
                shape = tokens.buttonShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open ${hw.batteryBypassGuide.oemName} Battery Bypass Settings", fontSize = 11.sp)
            }
        }
    }
}

@Preview(name = "Onboarding Track Chooser", showBackground = true)
@Composable
fun PreviewOnboardingChooser() {
    PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
        PulseOnboardingScreen(
            state = MeshUiState(operatorCallsign = "Ranger-01"),
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
