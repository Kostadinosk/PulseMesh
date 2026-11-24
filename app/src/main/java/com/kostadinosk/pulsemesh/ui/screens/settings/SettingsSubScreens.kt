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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.crypto.AppLockTimeout
import com.kostadinosk.pulsemesh.model.BatteryPowerProfile
import com.kostadinosk.pulsemesh.model.LocationShareMode
import com.kostadinosk.pulsemesh.service.LoraProtocol
import com.kostadinosk.pulsemesh.ui.theme.LocalPulseTokens
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.ui.theme.pulseSurfaceCard
import com.kostadinosk.pulsemesh.util.AppStrings
import com.kostadinosk.pulsemesh.util.ThemeBrightness
import com.kostadinosk.pulsemesh.util.ThemePreset
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState

/**
 * Clean, remnant-free v2.0.1 Profile & @username Settings Sub-Screen.
 */
@Composable
fun ProfileSettingsScreen(
    state: MeshUiState,
    onUpdateCallsign: (String) -> Unit,
    onRegenerateNodeId: () -> Unit,
    onClaimOrRecoverUsername: (username: String, pin: String, recoveryKey: String, isRecovery: Boolean, onResult: (Boolean, String) -> Unit) -> Unit,
    onOpenConnectHub: () -> Unit,
    onOpenOnboarding: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    val lang = state.appLanguage

    var callsignDraft by remember(state.operatorCallsign) { mutableStateOf(state.operatorCallsign) }
    var usernameDraft by remember(state.claimedUsername) { mutableStateOf(state.claimedUsername) }
    var pinDraft by remember { mutableStateOf("") }
    var isRecoverMode by remember { mutableStateOf(false) }
    var statusFeedback by remember { mutableStateOf(state.usernameAuthStatus) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
            .testTag("profile_settings_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Callsign & Node Identity Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Badge, contentDescription = null, tint = tokens.accentPrimary)
                Text(AppStrings.tr(lang, "Operator Identity", "Ταυτότητα Χρήστη"), color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            OutlinedTextField(
                value = callsignDraft,
                onValueChange = { callsignDraft = it.take(24) },
                label = { Text(AppStrings.tr(lang, "Callsign / Display Name", "Όνομα / Callsign")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "64-bit Node ID: ${state.localNodeId}",
                color = p.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (callsignDraft.isNotBlank()) onUpdateCallsign(callsignDraft.trim()) },
                    shape = tokens.buttonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.accentPrimary, contentColor = if (tokens.isDark) Color.Black else Color.White),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(AppStrings.tr(lang, "Save Name", "Αποθήκευση"), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onRegenerateNodeId,
                    shape = tokens.buttonShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(AppStrings.tr(lang, "New ID", "Νέο ID"), fontSize = 12.sp)
                }
            }
        }

        // Optional (Recommended) @username Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = AppStrings.tr(lang, "Optional (Recommended): @username", "Προαιρετικό (Προτείνεται): @username"),
                color = p.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                text = if (state.claimedUsername.isNotBlank()) {
                    "Active handle: @${state.claimedUsername}"
                } else {
                    AppStrings.tr(lang, "Reserve a permanent @username for cross-device recovery and Internet Hybrid Relay.", "Κρατήστε ένα μόνιμο @username για ανάκτηση και υβριδικό δίκτυο.")
                },
                color = if (state.claimedUsername.isNotBlank()) tokens.statusOnline else p.textSecondary,
                fontSize = 12.sp
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false to "Reserve New", true to "Sign In / Recover").forEach { (rec, label) ->
                    val active = isRecoverMode == rec
                    Surface(
                        color = if (active) tokens.accentPrimary.copy(alpha = 0.2f) else p.surfaceVariant,
                        shape = tokens.pillShape,
                        border = BorderStroke(1.dp, if (active) tokens.accentPrimary else p.border),
                        modifier = Modifier
                            .weight(1f)
                            .clip(tokens.pillShape)
                            .clickable { isRecoverMode = rec }
                    ) {
                        Text(
                            text = label,
                            color = if (active) tokens.accentPrimary else p.textSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
            OutlinedTextField(
                value = usernameDraft,
                onValueChange = { usernameDraft = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' }.take(24) },
                label = { Text("@username") },
                prefix = { Text("@") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = pinDraft,
                onValueChange = { pinDraft = it.take(64) },
                label = { Text(if (isRecoverMode) "Password, PIN, or PMRK-... Key" else "Password (6+ chars) or 4-8 Digit PIN") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("profile_username_secret_input")
            )
            Text(
                text = AppStrings.tr(lang, "Works identically across Android, iPhone, and Web with either a password or a 4-8 digit PIN.", "Λειτουργεί παντού (Android, iPhone, Web) με κωδικό ή PIN 4-8 ψηφίων."),
                color = p.textSecondary,
                fontSize = 11.sp
            )
            if (statusFeedback.isNotBlank()) {
                Text(statusFeedback, color = tokens.accentSecondary, fontSize = 11.sp)
            }
            val secretReady = pinDraft.startsWith("PMRK-", ignoreCase = true) ||
                com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.isValidUniversalAccountSecret(pinDraft)
            Button(
                onClick = {
                    val isKey = pinDraft.startsWith("PMRK-", ignoreCase = true)
                    onClaimOrRecoverUsername(
                        usernameDraft.trim(),
                        if (isKey) "" else pinDraft.trim(),
                        if (isKey) pinDraft.trim() else "",
                        isRecoverMode
                    ) { _, msg -> statusFeedback = msg }
                },
                enabled = usernameDraft.length >= 3 && secretReady && !state.isReservingUsername,
                shape = tokens.buttonShape,
                colors = ButtonDefaults.buttonColors(containerColor = tokens.statusOnline, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isRecoverMode) "Sign In & Unlock Vault" else "Reserve @username", fontWeight = FontWeight.Bold)
            }
        }

        // Quick Share & Re-run Onboarding
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenConnectHub, shape = tokens.buttonShape, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("My QR & NFC", fontSize = 12.sp)
            }
            OutlinedButton(onClick = onOpenOnboarding, shape = tokens.buttonShape, modifier = Modifier.weight(1f)) {
                Text("Run Setup Guide", fontSize = 12.sp)
            }
        }
    }
}

/**
 * Clean, remnant-free v2.0.1 Radios & Power Profiles Settings Sub-Screen.
 */
@Composable
fun RadiosSettingsScreen(
    state: MeshUiState,
    onSetBatteryPowerProfile: (BatteryPowerProfile) -> Unit,
    onToggleInternetMode: () -> Unit,
    onToggleWebPortal: () -> Unit,
    onToggleBleCodedPhy: () -> Unit,
    onToggleWifiAware: () -> Unit,
    onToggleBackgroundRelay: () -> Unit,
    onUpdateChannelAndPassphrase: (channel: String, passphrase: String) -> Unit,
    onToggleLoraBridge: () -> Unit,
    onSelectLoraProtocol: (LoraProtocol) -> Unit,
    onScanLoraDevices: () -> Unit,
    onToggleAcousticModem: () -> Unit,
    onToggleCotBridge: (Boolean) -> Unit,
    onOpenConnectHub: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    val lang = state.appLanguage
    val context = LocalContext.current

    var channelDraft by remember(state.meshChannel) { mutableStateOf(state.meshChannel) }
    var passDraft by remember(state.meshPassphrase) { mutableStateOf(state.meshPassphrase) }
    var copiedHostNotice by remember { mutableStateOf("") }

    fun copyHostUrl(url: String) {
        if (url.isBlank()) return
        runCatching {
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("PulseMesh Host Link", url))
            copiedHostNotice = AppStrings.tr(lang, "Copied: $url", "Αντιγράφηκε: $url")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
            .testTag("radios_settings_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Dedicated Hotspot Link Host Card (HTTP + HTTPS links, 1-tap Copy & QR Share)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(14.dp)
                .testTag("settings_hotspot_host_card"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val portal = state.webPortalStatus
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, tint = tokens.accentPrimary)
                    Column {
                        Text(
                            text = AppStrings.tr(lang, "Hotspot Link Host", "Σύνδεσμος Hotspot (Host)"),
                            color = p.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = if (portal.isRunning) {
                                AppStrings.tr(lang, "Hosting Web PWA & APK · ${portal.activeClientCount} clients", "Ενεργός Host · ${portal.activeClientCount} συσκευές")
                            } else {
                                AppStrings.tr(lang, "Turn on to share a local browser link over Hotspot or Wi-Fi", "Ενεργοποιήστε για τοπικό σύνδεσμο browser μέσω Hotspot ή Wi-Fi")
                            },
                            color = if (portal.isRunning) tokens.statusOnline else p.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
                Switch(
                    checked = portal.isRunning,
                    onCheckedChange = { onToggleWebPortal() },
                    modifier = Modifier.testTag("settings_hotspot_host_switch")
                )
            }

            if (portal.isRunning) {
                val primaryUrl = portal.portalUrl.ifBlank { "http://${portal.hostIp.ifBlank { "192.168.43.1" }}:${portal.port}" }
                Surface(
                    color = p.surfaceVariant,
                    shape = tokens.buttonShape,
                    border = BorderStroke(1.dp, tokens.accentPrimary.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = AppStrings.tr(lang, "Local Host Link (HTTP)", "Τοπικός Σύνδεσμος (HTTP)"),
                                color = p.textSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = primaryUrl,
                                color = tokens.accentPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier.testTag("settings_hotspot_host_url")
                            )
                        }
                        OutlinedButton(
                            onClick = { copyHostUrl(primaryUrl) },
                            shape = tokens.pillShape,
                            modifier = Modifier.testTag("settings_hotspot_copy_btn")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Host Link", modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(AppStrings.tr(lang, "Copy", "Αντιγραφή"), fontSize = 11.sp)
                        }
                    }
                }

                if (portal.httpsPortalUrl.isNotBlank()) {
                    Surface(
                        color = p.surfaceVariant,
                        shape = tokens.buttonShape,
                        border = BorderStroke(1.dp, p.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = AppStrings.tr(lang, "iPhone HTTPS Link (Mic & PWA)", "Σύνδεσμος HTTPS (iPhone)"),
                                    color = p.textSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = portal.httpsPortalUrl,
                                    color = p.textPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            }
                            OutlinedButton(
                                onClick = { copyHostUrl(portal.httpsPortalUrl) },
                                shape = tokens.pillShape
                            ) {
                                Text(AppStrings.tr(lang, "Copy", "Αντιγραφή"), fontSize = 11.sp)
                            }
                        }
                    }
                }

                if (portal.labeledReachableUrls.size > 1) {
                    portal.labeledReachableUrls.drop(1).take(3).forEach { (altUrl, iface) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { copyHostUrl(altUrl) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(altUrl, color = p.textSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            Text(iface, color = p.textMuted, fontSize = 10.sp)
                        }
                    }
                }

                if (copiedHostNotice.isNotBlank()) {
                    Text(copiedHostNotice, color = tokens.statusOnline, fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = onOpenConnectHub,
                    shape = tokens.buttonShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(AppStrings.tr(lang, "Open QR Code & iPhone Share Guide", "Άνοιγμα QR & Οδηγού iPhone"), fontSize = 12.sp)
                }
            }
        }

        // Smart Battery & Mesh Power Profiles
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = tokens.statusOnline)
                Text(AppStrings.tr(lang, "Smart Battery & Mesh Profile", "Προφίλ Ενέργειας & Mesh"), color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BatteryPowerProfile.entries.forEach { profile ->
                    val active = state.batteryPowerProfile == profile
                    Surface(
                        color = if (active) tokens.accentPrimary else p.surfaceVariant,
                        shape = tokens.pillShape,
                        border = BorderStroke(1.dp, if (active) tokens.accentPrimary else p.border),
                        modifier = Modifier
                            .weight(1f)
                            .clip(tokens.pillShape)
                            .clickable { onSetBatteryPowerProfile(profile) }
                    ) {
                        Text(
                            text = profile.label,
                            color = if (active) (if (tokens.isDark) Color.Black else Color.White) else p.textSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
            Text(state.batteryPowerProfile.description, color = p.textSecondary, fontSize = 11.sp)
        }

        // Core Radio Toggles
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.WifiTethering, contentDescription = null, tint = tokens.accentSecondary)
                Text("Transport & Radio Stack", color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            SettingsToggleRow("Hybrid Internet Relay (Cloudflare)", "Extends local mesh globally when online", state.isInternetEnabled, onToggleInternetMode)
            SettingsToggleRow("Hotspot Web Portal & APK Beaming", state.webPortalStatus.portalUrl.ifBlank { "Serve iPhone PWA & Android APK over LAN" }, state.webPortalStatus.isRunning, onToggleWebPortal)
            SettingsToggleRow("BLE 5 Coded PHY (Long Range)", "Extended Bluetooth range on supported chipsets", state.bleCodedPhyEnabled, onToggleBleCodedPhy)
            SettingsToggleRow("Wi-Fi Aware (NAN) P2P", "Zero-AP peer discovery on Android 10+", state.wifiAwareEnabled, onToggleWifiAware)
            SettingsToggleRow("Background Mesh Relay Service", "Keep store-and-forward DTN relay alive", state.isBackgroundRelayServiceEnabled, onToggleBackgroundRelay)
            SettingsToggleRow("ATAK / WinTAK CoT UDP Bridge", "Multicast 239.2.3.1:6969 interoperability", state.isCotBridgeEnabled) { onToggleCotBridge(!state.isCotBridgeEnabled) }
        }

        // LoRa & Acoustic 2.0 Hardware Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Router, contentDescription = null, tint = tokens.accentSecondary)
                Text("LoRa Companion & Acoustic 2.0", color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            SettingsToggleRow("LoRa USB-OTG / BLE Bridge", state.loraBridgeState.statusText.ifBlank { "Meshtastic / MeshCore hardware companion" }, state.loraBridgeState.isConnected, onToggleLoraBridge)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LoraProtocol.entries.forEach { proto ->
                    val selected = state.loraBridgeState.protocol == proto
                    Surface(
                        color = if (selected) tokens.accentSecondary.copy(alpha = 0.2f) else p.surfaceVariant,
                        shape = tokens.pillShape,
                        border = BorderStroke(1.dp, if (selected) tokens.accentSecondary else p.border),
                        modifier = Modifier
                            .weight(1f)
                            .clip(tokens.pillShape)
                            .clickable { onSelectLoraProtocol(proto) }
                    ) {
                        Text(proto.name, color = if (selected) tokens.accentSecondary else p.textSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 7.dp))
                    }
                }
                OutlinedButton(onClick = onScanLoraDevices, shape = tokens.pillShape) {
                    Text("Scan", fontSize = 11.sp)
                }
            }
            SettingsToggleRow("Acoustic Modem 2.0 (RS-FEC + AES)", "Bell 202 speaker/mic air-gap frames", state.bell202ModemEnabled, onToggleAcousticModem)
        }

        // Mesh Channel & Shared Passphrase
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = tokens.accentPrimary)
                Text("Team Channel & Passphrase", color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            OutlinedTextField(value = channelDraft, onValueChange = { channelDraft = it.take(36) }, label = { Text("Mesh Channel") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = passDraft, onValueChange = { passDraft = it.take(64) }, label = { Text("Shared AES-256-GCM Passphrase") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { onUpdateChannelAndPassphrase(channelDraft.trim(), passDraft.trim()) },
                shape = tokens.buttonShape,
                colors = ButtonDefaults.buttonColors(containerColor = tokens.accentPrimary, contentColor = if (tokens.isDark) Color.Black else Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apply Channel & Key", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Clean, remnant-free v2.0.1 Security & Privacy Settings Sub-Screen.
 */
@Composable
fun SecuritySettingsScreen(
    state: MeshUiState,
    onRotateKeys: () -> Unit,
    onSelectLocationShareMode: (LocationShareMode) -> Unit,
    onSetAppLockPin: (String) -> Unit,
    onSetBiometricUnlock: (Boolean) -> Unit,
    onSetAppLockTimeout: (AppLockTimeout) -> Unit,
    onClearAppLock: () -> Unit,
    onSetDuressPin: (String) -> Unit,
    onCycleBurnTtl: () -> Unit,
    onPurgeStalePeers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    var lockPinDraft by remember { mutableStateOf("") }
    var duressPinDraft by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
            .testTag("security_settings_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Cryptography Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard(accentBorder = tokens.statusOnline.copy(alpha = 0.45f))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Key, contentDescription = null, tint = tokens.statusOnline)
                Text("Zero-Trust E2EE Keys", color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Text("Signal Double Ratchet (PMDR2) + ECDH P-256 + AES-256-GCM", color = tokens.statusOnline, fontSize = 11.sp)
            Text("Fingerprint: ${state.x25519Fingerprint}", color = p.textSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRotateKeys, shape = tokens.buttonShape, modifier = Modifier.weight(1f)) {
                    Text("Rotate Keys", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onCycleBurnTtl, shape = tokens.buttonShape, modifier = Modifier.weight(1f)) {
                    val burn = if (state.messageBurnTtlSeconds <= 0) "Off" else "${state.messageBurnTtlSeconds}s"
                    Text("Burn TTL: $burn", fontSize = 12.sp)
                }
            }
        }

        // Location Privacy Mode (Real working locationShareMode, zero dead fuzzing toggle)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = tokens.accentSecondary)
                Text("Location Sharing Privacy", color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LocationShareMode.entries.forEach { mode ->
                    val active = state.locationShareMode == mode
                    Surface(
                        color = if (active) tokens.accentSecondary else p.surfaceVariant,
                        shape = tokens.pillShape,
                        border = BorderStroke(1.dp, if (active) tokens.accentSecondary else p.border),
                        modifier = Modifier
                            .weight(1f)
                            .clip(tokens.pillShape)
                            .clickable { onSelectLocationShareMode(mode) }
                    ) {
                        Text(
                            text = mode.name,
                            color = if (active) Color.White else p.textSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // App Lock & Duress PIN Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = tokens.accentPrimary)
                Text("App Lock & Duress Protection", color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            SettingsToggleRow("Biometric Unlock", "Use fingerprint / face unlock when App Lock is set", state.isBiometricUnlockEnabled) {
                onSetBiometricUnlock(!state.isBiometricUnlockEnabled)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = lockPinDraft,
                    onValueChange = { lockPinDraft = it.filter(Char::isDigit).take(8) },
                    label = { Text("App Lock PIN (4-8 digits)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        if (lockPinDraft.length >= 4) {
                            onSetAppLockPin(lockPinDraft)
                            onSetAppLockTimeout(AppLockTimeout.ONE_MIN)
                            lockPinDraft = ""
                        }
                    },
                    enabled = lockPinDraft.length >= 4,
                    shape = tokens.buttonShape
                ) {
                    Text("Set")
                }
            }
            if (state.hasAppLockPin) {
                OutlinedButton(onClick = onClearAppLock, shape = tokens.buttonShape, modifier = Modifier.fillMaxWidth()) {
                    Text("Disable App Lock")
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = duressPinDraft,
                    onValueChange = { duressPinDraft = it.filter(Char::isDigit).take(8) },
                    label = { Text("Duress Wipe PIN (Optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        if (duressPinDraft.length >= 4) {
                            onSetDuressPin(duressPinDraft)
                            duressPinDraft = ""
                        }
                    },
                    enabled = duressPinDraft.length >= 4,
                    shape = tokens.buttonShape
                ) {
                    Text("Save")
                }
            }
        }

        // Hygiene Action
        OutlinedButton(onClick = onPurgeStalePeers, shape = tokens.buttonShape, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Purge Stale / Offline Cached Peers")
        }
    }
}

/**
 * Clean, remnant-free v2.0.1 Field Safety, SOS & Weather Settings Sub-Screen.
 */
@Composable
fun SafetySettingsScreen(
    state: MeshUiState,
    onOpenEmergencySosSheet: () -> Unit,
    onSaveIceCard: (blood: String, allergies: String, notes: String, contact: String) -> Unit,
    onSetDeadManMinutes: (Int) -> Unit,
    onCheckInDeadMan: () -> Unit,
    onConfigureGeofence: (enabled: Boolean, radiusMeters: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette

    var blood by remember(state.iceBloodType) { mutableStateOf(state.iceBloodType) }
    var allergies by remember(state.iceAllergies) { mutableStateOf(state.iceAllergies) }
    var contact by remember(state.iceEmergencyContact) { mutableStateOf(state.iceEmergencyContact) }
    var notes by remember(state.iceNotes) { mutableStateOf(state.iceNotes) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
            .testTag("safety_settings_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Emergency Kit Launcher
        Button(
            onClick = onOpenEmergencySosSheet,
            shape = tokens.buttonShape,
            colors = ButtonDefaults.buttonColors(containerColor = tokens.statusDanger, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open Offline Emergency Kit (SOS / Morse / Siren)", fontWeight = FontWeight.Bold)
        }

        // ICE Medical Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.HealthAndSafety, contentDescription = null, tint = tokens.statusDanger)
                Text("ICE Emergency Medical Card", color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = blood, onValueChange = { blood = it.take(10) }, label = { Text("Blood Type") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = contact, onValueChange = { contact = it.take(36) }, label = { Text("ICE Contact") }, singleLine = true, modifier = Modifier.weight(1.4f))
            }
            OutlinedTextField(value = allergies, onValueChange = { allergies = it.take(80) }, label = { Text("Allergies / Conditions") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = notes, onValueChange = { notes = it.take(160) }, label = { Text("Medical Notes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { onSaveIceCard(blood.trim(), allergies.trim(), notes.trim(), contact.trim()) },
                shape = tokens.buttonShape,
                colors = ButtonDefaults.buttonColors(containerColor = tokens.accentPrimary, contentColor = if (tokens.isDark) Color.Black else Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save ICE Medical Card", fontWeight = FontWeight.Bold)
            }
        }

        // Dead-Man Switch & Geofence
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Security, contentDescription = null, tint = tokens.statusWarning)
                Text("Dead-Man Check-In Timer", color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "Off", 30 to "30m", 60 to "1h", 180 to "3h").forEach { (mins, label) ->
                    val active = state.deadManSwitchMinutes == mins
                    Surface(
                        color = if (active) tokens.statusWarning else p.surfaceVariant,
                        shape = tokens.pillShape,
                        border = BorderStroke(1.dp, if (active) tokens.statusWarning else p.border),
                        modifier = Modifier
                            .weight(1f)
                            .clip(tokens.pillShape)
                            .clickable { onSetDeadManMinutes(mins) }
                    ) {
                        Text(label, color = if (active) Color.Black else p.textSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
            if (state.deadManSwitchMinutes > 0) {
                OutlinedButton(onClick = onCheckInDeadMan, shape = tokens.buttonShape, modifier = Modifier.fillMaxWidth()) {
                    Text("Check In Now (Reset Countdown)")
                }
            }

            SettingsToggleRow(
                title = "Geofence Perimeter Alert",
                subtitle = if (state.geofenceEnabled) "Active (${state.geofenceRadiusMeters}m radius)" else "Alert if you drift outside a set perimeter",
                checked = state.geofenceEnabled
            ) {
                onConfigureGeofence(!state.geofenceEnabled, state.geofenceRadiusMeters.coerceAtLeast(250))
            }

            if (state.hasBarometerSensor && state.barometricPressureHpa != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Radar, contentDescription = null, tint = tokens.accentSecondary, modifier = Modifier.size(16.dp))
                    Text(
                        text = "Barometer: ${"%.1f".format(state.barometricPressureHpa)} hPa • ${state.weatherTrendLabel}",
                        color = p.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val p = LocalPulseTokens.current.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = p.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(subtitle, color = p.textSecondary, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Preview(name = "Profile Settings v2.0.1", showBackground = true)
@Composable
fun PreviewProfileSettings() {
    PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
        Surface(color = LocalPulseTokens.current.palette.background) {
            ProfileSettingsScreen(
                state = MeshUiState(operatorCallsign = "Alpha-01", claimedUsername = "alpha01"),
                onUpdateCallsign = {}, onRegenerateNodeId = {},
                onClaimOrRecoverUsername = { _, _, _, _, _ -> },
                onOpenConnectHub = {}, onOpenOnboarding = {}
            )
        }
    }
}
