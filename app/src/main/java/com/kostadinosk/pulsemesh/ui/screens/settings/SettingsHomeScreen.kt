// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.crypto.AppLockTimeout
import com.kostadinosk.pulsemesh.model.BatteryPowerProfile
import com.kostadinosk.pulsemesh.ui.theme.LocalPulsePalette
import com.kostadinosk.pulsemesh.util.AppTheme
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState

enum class SettingsCategory(
    val title: String,
    val subtitle: String,
    val keywords: String,
    val icon: ImageVector
) {
    IDENTITY("Identity & Account", "Callsign, @username claim, cloud backup", "identity callsign username backup pin recovery", Icons.Default.Badge),
    SECURITY("Security & Privacy", "App Lock, biometric, E2EE keys, Double Ratchet, duress", "security lock biometric pin e2ee ratchet key duress wipe", Icons.Default.Lock),
    VOICE("Voice & Audio", "Hardware PTT, audio route, clarity DSP, Bell 202", "voice ptt bluetooth audio mic clarity bell202", Icons.Default.RecordVoiceOver),
    NETWORK("Network & Mesh", "Battery profile, hotspot web portal, relay", "network mesh battery hotspot portal relay ble wifi", Icons.Default.Hub),
    APPEARANCE("Appearance & Layout", "Material 3 Dynamic Color, themes, Tactical HUD", "theme material3 aero dark light hud layout simple pro", Icons.Default.Palette),
    DIAGNOSTICS("Diagnostics & Storage", "Room DB, DTN store-and-forward, updates", "diagnostics storage dtn room updates logs", Icons.Default.Tune)
}

/**
 * Modular Searchable v1.5.0 Settings Hub:
 * - Instant search bar (`settings_search_bar`) + 6 category drill-down chips/cards
 * - Exposes App Lock (PIN/Biometric/Timeout), Battery Power Profile, Hardware PTT,
 *   Tactical HUD toggle, Double Ratchet reset, and Material 3 Dynamic Color.
 */
@Composable
fun SettingsHomeScreen(
    state: MeshUiState,
    onSelectAppTheme: (AppTheme) -> Unit,
    onSetShowTacticalHud: (Boolean) -> Unit,
    onSetBatteryPowerProfile: (BatteryPowerProfile) -> Unit,
    onSetAppLockTimeout: (AppLockTimeout) -> Unit,
    onSetAppLockPin: (String) -> Unit,
    onSetBiometricUnlock: (Boolean) -> Unit,
    onClearAppLock: () -> Unit,
    onToggleHardwarePtt: () -> Unit,
    onRotateKeys: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalPulsePalette.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    var newLockPin by remember { mutableStateOf("") }

    val filteredCategories = remember(searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isBlank()) SettingsCategory.entries
        else SettingsCategory.entries.filter {
            it.title.lowercase().contains(q) ||
                it.subtitle.lowercase().contains(q) ||
                it.keywords.contains(q)
        }
    }

    val q = searchQuery.trim().lowercase()
    val showSecurity = q.isNotBlank() && SettingsCategory.SECURITY.keywords.contains(q) || selectedCategory == SettingsCategory.SECURITY
    val showNetwork = q.isNotBlank() && SettingsCategory.NETWORK.keywords.contains(q) || selectedCategory == SettingsCategory.NETWORK
    val showAppearance = q.isNotBlank() && SettingsCategory.APPEARANCE.keywords.contains(q) || selectedCategory == SettingsCategory.APPEARANCE
    val showVoice = q.isNotBlank() && SettingsCategory.VOICE.keywords.contains(q) || selectedCategory == SettingsCategory.VOICE

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings_home_screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search settings (App Lock, Battery, Material 3, PTT...)") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_search_bar")
        )

        // Quick 6-category pill navigator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            filteredCategories.forEach { cat ->
                val isSelected = selectedCategory == cat
                Surface(
                    color = if (isSelected) p.emerald.copy(alpha = 0.2f) else p.surfaceVariant,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, if (isSelected) p.emerald else p.border),
                    modifier = Modifier
                        .clickable {
                            selectedCategory = if (isSelected) null else cat
                        }
                        .testTag("settings_cat_${cat.name.lowercase()}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(cat.icon, contentDescription = null, tint = if (isSelected) p.emerald else p.cyan, modifier = Modifier.size(14.dp))
                        Text(
                            text = cat.title.substringBefore(" "),
                            color = if (isSelected) p.emerald else p.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Core v1.5.0 Quick Controls Card (always easy to find or scoped by category/search)
        if (selectedCategory == null || showSecurity || showNetwork || showAppearance || showVoice) {
            Surface(
                color = p.card,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, p.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = selectedCategory?.title?.uppercase() ?: "PULSEMESH v1.5 CONTROLS",
                        color = p.cyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )

                    if (selectedCategory == null || showAppearance) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Tactical RF HUD", color = p.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Show RSSI, bitrate, codec, jitter & hop badges", color = p.textMuted, fontSize = 11.sp)
                            }
                            Switch(
                                checked = state.showTacticalHud,
                                onCheckedChange = onSetShowTacticalHud,
                                modifier = Modifier.testTag("settings_tactical_hud_switch")
                            )
                        }

                        OutlinedButton(
                            onClick = { onSelectAppTheme(AppTheme.MATERIAL3) },
                            border = BorderStroke(
                                1.dp,
                                if (state.appTheme == AppTheme.MATERIAL3) p.emerald else p.border
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_theme_material3_button")
                        ) {
                            Icon(Icons.Default.Palette, contentDescription = null, tint = p.emerald, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (state.appTheme == AppTheme.MATERIAL3) "MATERIAL 3 DYNAMIC COLOR (ACTIVE)" else "USE MATERIAL 3 DYNAMIC COLOR",
                                color = p.textPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (selectedCategory == null || showNetwork) {
                        Text(
                            text = "BATTERY & RADIO DUTY CYCLE",
                            color = p.textSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BatteryPowerProfile.entries.forEach { profile ->
                                val selected = state.batteryPowerProfile == profile
                                Surface(
                                    color = if (selected) p.emerald.copy(alpha = 0.2f) else p.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, if (selected) p.emerald else p.border),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onSetBatteryPowerProfile(profile) }
                                        .testTag("settings_battery_profile_${profile.name.lowercase()}")
                                ) {
                                    Text(
                                        text = profile.label,
                                        color = if (selected) p.emerald else p.textSecondary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (selectedCategory == null || showSecurity) {
                        Text(
                            text = "ZERO-TRUST APP LOCK",
                            color = p.textSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AppLockTimeout.entries.forEach { t ->
                                val selected = state.appLockTimeout == t
                                Surface(
                                    color = if (selected) p.cyan.copy(alpha = 0.2f) else p.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, if (selected) p.cyan else p.border),
                                    modifier = Modifier
                                        .clickable { onSetAppLockTimeout(t) }
                                        .testTag("settings_applock_timeout_${t.name.lowercase()}")
                                ) {
                                    Text(
                                        text = t.label,
                                        color = if (selected) p.cyan else p.textSecondary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Biometric Unlock", color = p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Switch(
                                checked = state.isBiometricUnlockEnabled,
                                onCheckedChange = onSetBiometricUnlock,
                                modifier = Modifier.testTag("settings_biometric_switch")
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newLockPin,
                                onValueChange = { if (it.length <= 16) newLockPin = it },
                                placeholder = { Text("App Lock PIN (4+)") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_applock_pin_input")
                            )
                            Button(
                                enabled = newLockPin.length >= 4,
                                onClick = {
                                    onSetAppLockPin(newLockPin)
                                    newLockPin = ""
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = p.emerald, contentColor = Color.Black),
                                modifier = Modifier.testTag("settings_applock_pin_save")
                            ) {
                                Text("SET", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            if (state.hasAppLockPin) {
                                TextButton(onClick = onClearAppLock) {
                                    Text("CLEAR", color = p.red, fontSize = 10.sp)
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = onRotateKeys,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_rotate_ratchet_btn")
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("ROTATE X25519 & RATCHET KEYS", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (showVoice) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Hardware Volume/Headset PTT", color = p.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Hold Volume Down or headset button to transmit", color = p.textMuted, fontSize = 11.sp)
                            }
                            Switch(
                                checked = state.isVolumeButtonPttEnabled,
                                onCheckedChange = { onToggleHardwarePtt() },
                                modifier = Modifier.testTag("settings_hw_ptt_switch")
                            )
                        }
                    }
                }
            }
        }
    }
}
