// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.BuildConfig
import com.kostadinosk.pulsemesh.ui.theme.LocalPulseTokens
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.ui.theme.pulseSurfaceCard
import com.kostadinosk.pulsemesh.util.AppStrings
import com.kostadinosk.pulsemesh.util.ThemeBrightness
import com.kostadinosk.pulsemesh.util.ThemePreset
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState

enum class SettingsDestination(
    val titleEn: String,
    val titleEl: String,
    val keywords: String,
    val icon: ImageVector
) {
    PROFILE("Profile & @username", "Προφίλ & @username", "profile callsign username qr nfc identity", Icons.Default.Badge),
    RADIOS("Radios & Power Profiles", "Ράδιο & Ενέργεια", "ble wifi hotspot lora meshtastic meshcore cot battery expedition power", Icons.Default.Radio),
    SECURITY("Security & Privacy", "Ασφάλεια & Ιδιωτικότητα", "e2ee encryption safety number key pin lock decoy duress location privacy", Icons.Default.Security),
    SAFETY("Safety, SOS & Weather", "Ασφάλεια Πεδίου & SOS", "sos emergency ice medical dead man geofence barometer weather checkin", Icons.Default.HealthAndSafety),
    APPEARANCE("Appearance & Language", "Εμφάνιση & Γλώσσα", "theme preset dark light aero tactical messenger language greek english", Icons.Default.Palette),
    RADIO_LAB("Radio Lab & Diagnostics", "Εργαστήριο & Διαγνωστικά", "ble sniffer rssi chart link cost hardware codename battery bypass root", Icons.Default.Science),
    UPDATES_DATA("Updates & Encrypted Backup", "Ενημερώσεις & Αντίγραφα", "update apk sha256 backup pmbackup export import archive", Icons.Default.SystemUpdate)
}

/**
 * PulseMesh v2.0 Grouped Settings Home with Live Search + Push-In Sub-Screen Shell.
 */
@Composable
fun ModularSettingsHub(
    state: MeshUiState,
    activeDestination: SettingsDestination?,
    onNavigate: (SettingsDestination?) -> Unit,
    subScreenContent: @Composable (SettingsDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    val lang = state.appLanguage

    if (activeDestination != null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .testTag("settings_subscreen_${activeDestination.name.lowercase()}")
        ) {
            Surface(
                color = p.surface,
                border = BorderStroke(tokens.borderWidth, p.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = { onNavigate(null) },
                        modifier = Modifier.testTag("settings_subscreen_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = p.textPrimary
                        )
                    }
                    Text(
                        text = AppStrings.tr(lang, activeDestination.titleEn, activeDestination.titleEl),
                        color = p.textPrimary,
                        fontFamily = tokens.labelFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
            subScreenContent(activeDestination)
        }
        return
    }

    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isBlank()) SettingsDestination.entries
        else SettingsDestination.entries.filter {
            it.titleEn.lowercase().contains(q) ||
                it.titleEl.lowercase().contains(q) ||
                it.keywords.contains(q)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
            .testTag("modular_settings_home"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Profile summary banner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard(accentBorder = tokens.accentPrimary.copy(alpha = 0.45f))
                .clickable { onNavigate(SettingsDestination.PROFILE) }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = state.operatorCallsign + if (state.claimedUsername.isNotBlank()) " (@${state.claimedUsername})" else "",
                    color = p.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = "${state.localNodeId} • ${state.themePreset.displayName} • ${state.batteryPowerProfile.label}",
                    color = p.textSecondary,
                    fontSize = 11.sp
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = p.textMuted)
        }

        // Instant Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            singleLine = true,
            shape = tokens.pillShape,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = p.textSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            },
            placeholder = {
                Text(
                    AppStrings.tr(lang, "Search settings (LoRa, Theme, Backup, SOS...)", "Αναζήτηση ρυθμίσεων (LoRa, Θέμα, SOS...)"),
                    fontSize = 12.sp
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_home_search_input")
        )

        // Grouped Destination Cards
        filtered.forEach { dest ->
            val subtitle = when (dest) {
                SettingsDestination.PROFILE -> if (state.claimedUsername.isNotBlank()) "@${state.claimedUsername} • QR & NFC" else "Callsign, optional @username & QR"
                SettingsDestination.RADIOS -> "${state.batteryPowerProfile.label} • ${if (state.loraBridgeState.isConnected) "LoRa ON" else "BLE/Wi-Fi/LoRa"}"
                SettingsDestination.SECURITY -> "Double Ratchet E2EE • Safety Numbers • ${state.locationShareMode.name}"
                SettingsDestination.SAFETY -> "SOS Kit, ICE Card, Dead-Man & Geofence"
                SettingsDestination.APPEARANCE -> "${state.themePreset.displayName} (${state.themeBrightness.displayName}) • ${lang.uppercase()}"
                SettingsDestination.RADIO_LAB -> state.hardwareReport?.resolvedMarketingName ?: "BLE Sniffer, RSSI & Hardware Matrix"
                SettingsDestination.UPDATES_DATA -> if (state.availableUpdate != null) "Update v${state.availableUpdate.latestVersion} available!" else "Verified Updater & Encrypted .pmbackup"
            }
            val accent: Color = when (dest) {
                SettingsDestination.PROFILE -> tokens.accentPrimary
                SettingsDestination.RADIOS -> tokens.accentSecondary
                SettingsDestination.SECURITY -> tokens.statusOnline
                SettingsDestination.SAFETY -> tokens.statusDanger
                SettingsDestination.APPEARANCE -> tokens.accentSecondary
                SettingsDestination.RADIO_LAB -> tokens.statusWarning
                SettingsDestination.UPDATES_DATA -> tokens.accentPrimary
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseSurfaceCard()
                    .clickable { onNavigate(dest) }
                    .padding(horizontal = 14.dp, vertical = 13.dp)
                    .testTag("settings_row_${dest.name.lowercase()}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(dest.icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = AppStrings.tr(lang, dest.titleEn, dest.titleEl),
                        color = p.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = subtitle,
                        color = p.textSecondary,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = p.textMuted)
            }
        }

        Spacer(Modifier.size(6.dp))
        Text(
            text = "PulseMesh v${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR_TIER.uppercase()}) • 100% FOSS GPL-3.0",
            color = p.textMuted,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Preview(name = "Modular Settings Home", showBackground = true)
@Composable
fun PreviewModularSettingsHome() {
    PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
        Surface(color = LocalPulseTokens.current.palette.background) {
            ModularSettingsHub(
                state = MeshUiState(operatorCallsign = "Alpha-01", claimedUsername = "alpha01"),
                activeDestination = null,
                onNavigate = {},
                subScreenContent = {}
            )
        }
    }
}
