// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

/**
 * PulseMesh v2.0 Updates & Encrypted Offline Backup (.pmbackup) Sub-Screen.
 */
@Composable
fun UpdatesDataSettingsScreen(
    state: MeshUiState,
    backupStatusMessage: String,
    onCheckUpdates: () -> Unit,
    onInstallVerifiedUpdate: () -> Unit,
    onExportEncryptedBackup: (passphrase: String) -> Unit,
    onImportEncryptedBackup: (passphrase: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    val lang = state.appLanguage
    var passphrase by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
            .testTag("updates_data_settings_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Verified APK Updater Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = tokens.accentPrimary)
                Column {
                    Text(
                        text = AppStrings.tr(lang, "Verified In-App Updater", "Επαληθευμένες Ενημερώσεις"),
                        color = p.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Installed: v${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR_TIER.uppercase()})",
                        color = p.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
            Text(
                text = AppStrings.tr(
                    lang,
                    "Every APK update is verified fail-closed against its SHA-256 checksum and signing certificate before installation.",
                    "Κάθε ενημέρωση APK επαληθεύεται αυστηρά με SHA-256 και πιστοποιητικό υπογραφής πριν την εγκατάσταση."
                ),
                color = p.textSecondary,
                fontSize = 12.sp
            )
            if (state.apkUpdateInstallState.message.isNotBlank()) {
                Text(
                    text = state.apkUpdateInstallState.message,
                    color = tokens.statusOnline,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCheckUpdates,
                    shape = tokens.buttonShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(AppStrings.tr(lang, "Check Releases", "Έλεγχος"), fontSize = 12.sp)
                }
                if (state.availableUpdate != null) {
                    Button(
                        onClick = onInstallVerifiedUpdate,
                        shape = tokens.buttonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.statusOnline,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Install v${state.availableUpdate.latestVersion}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // 2. Encrypted Offline .pmbackup Export & Import Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard(accentBorder = tokens.statusOnline.copy(alpha = 0.45f))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = tokens.statusOnline)
                Column {
                    Text(
                        text = AppStrings.tr(lang, "Encrypted Offline Backup (.pmbackup)", "Κρυπτογραφημένο Αντίγραφο (.pmbackup)"),
                        color = p.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "PBKDF2 600,000 + AES-256-GCM • 100% Offline",
                        color = tokens.statusOnline,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }
            Text(
                text = AppStrings.tr(
                    lang,
                    "Migrate your Callsign, Node ID, identity keys, @username, ICE medical card, groups, and recent chats to a new Android phone or Web PWA without cloud servers.",
                    "Μεταφέρετε Callsign, Node ID, κλειδιά ταυτότητας, @username, ICE, ομάδες και συνομιλίες σε νέα συσκευή 100% εκτός σύνδεσης."
                ),
                color = p.textSecondary,
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text(AppStrings.tr(lang, "Backup Passphrase (8+ chars)", "Κωδικός Αντιγράφου (8+ χαρακτήρες)")) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pmbackup_passphrase_input")
            )
            if (backupStatusMessage.isNotBlank()) {
                Text(
                    text = backupStatusMessage,
                    color = tokens.accentSecondary,
                    fontSize = 11.sp
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onExportEncryptedBackup(passphrase) },
                    enabled = passphrase.length >= 8,
                    shape = tokens.buttonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.accentPrimary,
                        contentColor = if (tokens.isDark) Color.Black else Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pmbackup_export_btn")
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(AppStrings.tr(lang, "Export", "Εξαγωγή"), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { onImportEncryptedBackup(passphrase) },
                    enabled = passphrase.length >= 8,
                    shape = tokens.buttonShape,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pmbackup_import_btn")
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(AppStrings.tr(lang, "Import", "Εισαγωγή"), fontSize = 12.sp)
                }
            }
        }
    }
}

@Preview(name = "Updates & Encrypted Backup", showBackground = true)
@Composable
fun PreviewUpdatesDataSettings() {
    PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
        Surface(color = LocalPulseTokens.current.palette.background) {
            UpdatesDataSettingsScreen(
                state = MeshUiState(),
                backupStatusMessage = "Ready to export or restore .pmbackup",
                onCheckUpdates = {},
                onInstallVerifiedUpdate = {},
                onExportEncryptedBackup = {},
                onImportEncryptedBackup = {}
            )
        }
    }
}
