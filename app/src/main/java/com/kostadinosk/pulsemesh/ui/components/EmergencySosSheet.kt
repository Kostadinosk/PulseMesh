// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.service.EmergencyToolkitState
import com.kostadinosk.pulsemesh.ui.theme.LocalPulseTokens
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.ui.theme.pulseSurfaceCard
import com.kostadinosk.pulsemesh.util.AppStrings
import com.kostadinosk.pulsemesh.util.ThemeBrightness
import com.kostadinosk.pulsemesh.util.ThemePreset
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencySosSheet(
    state: MeshUiState,
    toolkitState: EmergencyToolkitState,
    onDismiss: () -> Unit,
    onToggleSosBeacon: () -> Unit,
    onSendImSafeCheckIn: () -> Unit,
    onToggleTorchMorse: () -> Unit,
    onToggleSiren: () -> Unit
) {
    val tokens = LocalPulseTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = tokens.palette.surface,
        shape = tokens.sheetShape
    ) {
        EmergencySosSheetContent(
            state = state,
            toolkitState = toolkitState,
            onDismiss = onDismiss,
            onToggleSosBeacon = onToggleSosBeacon,
            onSendImSafeCheckIn = onSendImSafeCheckIn,
            onToggleTorchMorse = onToggleTorchMorse,
            onToggleSiren = onToggleSiren
        )
    }
}

@Composable
fun EmergencySosSheetContent(
    state: MeshUiState,
    toolkitState: EmergencyToolkitState,
    onDismiss: () -> Unit,
    onToggleSosBeacon: () -> Unit,
    onSendImSafeCheckIn: () -> Unit,
    onToggleTorchMorse: () -> Unit,
    onToggleSiren: () -> Unit
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    val lang = state.appLanguage

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(bottom = 28.dp)
            .testTag("emergency_sos_sheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = tokens.statusDanger)
                Column {
                    Text(
                        text = AppStrings.tr(lang, "Offline Emergency Kit & SOS", "Κιτ Έκτακτης Ανάγκης & SOS"),
                        color = p.textPrimary,
                        fontFamily = tokens.labelFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        text = "GPS: ${"%.4f, %.4f".format(state.userLatitude ?: 0.0, state.userLongitude ?: 0.0)} • Battery ${state.realBatteryPercent}%",
                        color = p.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = p.textSecondary)
            }
        }

        // 1-Tap SOS Distress Broadcast
        Button(
            onClick = onToggleSosBeacon,
            shape = tokens.buttonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.isSosActive) p.surfaceVariant else tokens.statusDanger,
                contentColor = if (state.isSosActive) p.textPrimary else Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("sos_kit_broadcast_btn")
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (state.isSosActive) {
                    AppStrings.tr(lang, "STOP SOS DISTRESS BEACON", "ΔΙΑΚΟΠΗ ΣΗΜΑΤΟΣ SOS")
                } else {
                    AppStrings.tr(lang, "BROADCAST SOS (GPS + BATTERY + ICE)", "ΕΚΠΟΜΠΗ SOS (GPS + ΜΠΑΤΑΡΙΑ + ICE)")
                },
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.sp
            )
        }

        // Physical Rescue Signals: Morse Torch & Siren
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onToggleTorchMorse,
                shape = tokens.buttonShape,
                border = BorderStroke(1.2.dp, if (toolkitState.isTorchMorseActive) tokens.statusWarning else p.border),
                modifier = Modifier
                    .weight(1f)
                    .testTag("sos_kit_torch_btn")
            ) {
                Icon(
                    Icons.Default.FlashlightOn,
                    contentDescription = null,
                    tint = if (toolkitState.isTorchMorseActive) tokens.statusWarning else p.textPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (toolkitState.isTorchMorseActive) AppStrings.tr(lang, "Stop Morse", "Στοπ Φακός")
                    else AppStrings.tr(lang, "Morse SOS", "Φακός SOS"),
                    fontSize = 12.sp
                )
            }

            OutlinedButton(
                onClick = onToggleSiren,
                shape = tokens.buttonShape,
                border = BorderStroke(1.2.dp, if (toolkitState.isSirenActive) tokens.statusDanger else p.border),
                modifier = Modifier
                    .weight(1f)
                    .testTag("sos_kit_siren_btn")
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = if (toolkitState.isSirenActive) tokens.statusDanger else p.textPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (toolkitState.isSirenActive) AppStrings.tr(lang, "Stop Siren", "Στοπ Σειρήνα")
                    else AppStrings.tr(lang, "Loud Siren", "Σειρήνα"),
                    fontSize = 12.sp
                )
            }
        }

        // "I'm Safe" Check-in Broadcast
        Button(
            onClick = {
                onSendImSafeCheckIn()
                onDismiss()
            },
            shape = tokens.buttonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = tokens.statusOnline,
                contentColor = Color.Black
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("sos_kit_safe_checkin_btn")
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(AppStrings.tr(lang, "Broadcast \"I'm Safe\" Check-In", "Αποστολή \"Είμαι Ασφαλής\""), fontWeight = FontWeight.Bold)
        }

        // ICE Medical Card Preview
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.HealthAndSafety, contentDescription = null, tint = tokens.statusDanger, modifier = Modifier.size(16.dp))
                Text(AppStrings.tr(lang, "ICE Medical Summary", "Ιατρική Κάρτα ICE"), color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Text(
                text = state.iceMedicalInfo.ifBlank { AppStrings.tr(lang, "Not set yet. Add blood type & emergency contact in Settings -> Safety.", "Δεν έχει οριστεί. Προσθέστε στοιχεία στις Ρυθμίσεις -> Ασφάλεια.") },
                color = p.textSecondary,
                fontSize = 11.sp
            )
        }
    }
}

@Preview(name = "Emergency SOS Kit Sheet", showBackground = true)
@Composable
fun PreviewEmergencySosSheet() {
    PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
        Surface(color = LocalPulseTokens.current.palette.surface) {
            EmergencySosSheetContent(
                state = MeshUiState(userLatitude = 37.9838, userLongitude = 23.7275, realBatteryPercent = 74, iceMedicalInfo = "O+ • No Allergies • ICE: +30 6900000000"),
                toolkitState = EmergencyToolkitState(torchAvailable = true),
                onDismiss = {},
                onToggleSosBeacon = {},
                onSendImSafeCheckIn = {},
                onToggleTorchMorse = {},
                onToggleSiren = {}
            )
        }
    }
}
