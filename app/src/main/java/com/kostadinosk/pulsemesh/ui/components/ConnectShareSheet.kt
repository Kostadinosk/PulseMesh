// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.service.LoraProtocol
import com.kostadinosk.pulsemesh.ui.theme.LocalPulseTokens
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.ui.theme.pulseSurfaceCard
import com.kostadinosk.pulsemesh.util.AppStrings
import com.kostadinosk.pulsemesh.util.QrCodeGenerator
import com.kostadinosk.pulsemesh.util.ThemeBrightness
import com.kostadinosk.pulsemesh.util.ThemePreset
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState

/**
 * PulseMesh v2.0 Unified 1-Tap "Connect & Share" Hub:
 * 1) My QR & NFC Tap
 * 2) Scan QR (launches CameraX/Gallery/Paste QR scanner)
 * 3) Bridge iPhone / Share APK Offline (Hotspot QR + 1-tap .mobileconfig + APK download QR)
 * 4) LoRa & Acoustic 2.0 quick-connect
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectShareSheet(
    state: MeshUiState,
    initialTab: Int = 0,
    onDismiss: () -> Unit,
    onLaunchQrScanner: () -> Unit,
    onToggleHotspotPortal: () -> Unit,
    onToggleLoraBridge: () -> Unit,
    onSelectLoraProtocol: (LoraProtocol) -> Unit,
    onScanLoraDevices: () -> Unit,
    onToggleAcousticModem: () -> Unit = {},
    onSendAcousticMessage: (String) -> Unit = {}
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        shape = tokens.sheetShape
    ) {
        ConnectShareSheetContent(
            state = state,
            initialTab = initialTab,
            onDismiss = onDismiss,
            onLaunchQrScanner = onLaunchQrScanner,
            onToggleHotspotPortal = onToggleHotspotPortal,
            onToggleLoraBridge = onToggleLoraBridge,
            onSelectLoraProtocol = onSelectLoraProtocol,
            onScanLoraDevices = onScanLoraDevices,
            onToggleAcousticModem = onToggleAcousticModem,
            onSendAcousticMessage = onSendAcousticMessage
        )
    }
}

@Composable
fun ConnectShareSheetContent(
    state: MeshUiState,
    initialTab: Int = 0,
    onDismiss: () -> Unit,
    onLaunchQrScanner: () -> Unit,
    onToggleHotspotPortal: () -> Unit,
    onToggleLoraBridge: () -> Unit,
    onSelectLoraProtocol: (LoraProtocol) -> Unit,
    onScanLoraDevices: () -> Unit,
    onToggleAcousticModem: () -> Unit = {},
    onSendAcousticMessage: (String) -> Unit = {}
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    val lang = state.appLanguage
    val clipboard = LocalClipboardManager.current

    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    var copiedFeedback by remember { mutableStateOf(false) }

    val inviteUri = remember(state.localNodeId, state.operatorCallsign, state.claimedUsername, state.x25519PublicKey) {
        val userPart = if (state.claimedUsername.isNotBlank()) "&user=${state.claimedUsername}" else ""
        val keyPart = if (state.x25519PublicKey.isNotBlank()) "&pub=${state.x25519PublicKey}" else ""
        "pulsemesh://join?id=${state.localNodeId}&name=${state.operatorCallsign}$userPart$keyPart"
    }
    val inviteQrBitmap: Bitmap? = remember(inviteUri) {
        runCatching { QrCodeGenerator.generateBitmap(inviteUri, 380) }.getOrNull()
    }

    val portalBaseUrl = state.webPortalStatus.portalUrl.ifBlank { "http://192.168.43.1:8080" }
    val apkDownloadUrl = "$portalBaseUrl/download/pulsemesh.apk"
    val iosProfileUrl = "$portalBaseUrl/ca.mobileconfig"
    val portalQrBitmap: Bitmap? = remember(portalBaseUrl) {
        runCatching { QrCodeGenerator.generateBitmap(portalBaseUrl, 340) }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 28.dp)
            .testTag("connect_share_sheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = AppStrings.tr(lang, "Connect & Share", "Σύνδεση & Κοινοποίηση"),
                    color = p.textPrimary,
                    fontFamily = tokens.labelFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = AppStrings.tr(lang, "QR, NFC, iPhone Bridge, Offline APK & Radios", "QR, NFC, Γέφυρα iPhone, APK & Ράδιο"),
                    color = p.textSecondary,
                    fontSize = 11.sp
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = p.textSecondary)
            }
        }

        // 4-tab segmented selector
        val tabLabels = listOf(
            AppStrings.tr(lang, "My QR", "Το QR μου"),
            AppStrings.tr(lang, "Scan", "Σάρωση"),
            AppStrings.tr(lang, "iPhone/APK", "iPhone/APK"),
            AppStrings.tr(lang, "Radios", "Ράδιο")
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(tokens.buttonShape)
                .background(p.surfaceVariant)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabLabels.forEachIndexed { idx, label ->
                val active = selectedTab == idx
                Surface(
                    color = if (active) tokens.accentPrimary else Color.Transparent,
                    shape = tokens.buttonShape,
                    modifier = Modifier
                        .weight(1f)
                        .clip(tokens.buttonShape)
                        .clickable { selectedTab = idx }
                        .testTag("connect_hub_tab_$idx")
                ) {
                    Text(
                        text = label,
                        color = if (active) (if (tokens.isDark) Color.Black else Color.White) else p.textSecondary,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                }
            }
        }

        when (selectedTab) {
            0 -> {
                // Tab 0: My QR & NFC Tap
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseSurfaceCard()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (inviteQrBitmap != null) {
                        Box(
                            modifier = Modifier
                                .clip(tokens.cardShape)
                                .background(Color.White)
                                .padding(12.dp)
                        ) {
                            Image(
                                bitmap = inviteQrBitmap.asImageBitmap(),
                                contentDescription = "PulseMesh Identity QR",
                                modifier = Modifier.size(196.dp)
                            )
                        }
                    }
                    Text(
                        text = state.operatorCallsign + if (state.claimedUsername.isNotBlank()) " (@${state.claimedUsername})" else "",
                        color = p.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "${state.localNodeId} • Key ${state.x25519Fingerprint.take(17)}",
                        color = p.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(tokens.buttonShape)
                            .background(tokens.statusOnline.copy(alpha = 0.12f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Nfc, contentDescription = null, tint = tokens.statusOnline, modifier = Modifier.size(18.dp))
                        Text(
                            text = AppStrings.tr(lang, "NFC HCE Active: Hold phone backs together to pair instantly.", "NFC HCE Ενεργό: Ακουμπήστε τα τηλέφωνα πλάτη με πλάτη για άμεση σύζευξη."),
                            color = p.textPrimary,
                            fontSize = 11.sp
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(inviteUri))
                            copiedFeedback = true
                        },
                        shape = tokens.buttonShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(
                            if (copiedFeedback) AppStrings.tr(lang, "Invite Link Copied", "Ο σύνδεσμος αντιγράφηκε")
                            else AppStrings.tr(lang, "Copy Invite URI", "Αντιγραφή συνδέσμου")
                        )
                    }
                }
            }

            1 -> {
                // Tab 1: Scan QR
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseSurfaceCard()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = tokens.accentPrimary, modifier = Modifier.size(40.dp))
                    Text(
                        text = AppStrings.tr(lang, "Scan Peer, Group, or Safety Number QR", "Σάρωση QR Κόμβου, Ομάδας ή Αριθμού Ασφαλείας"),
                        color = p.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = AppStrings.tr(
                            lang,
                            "Supports live CameraX scanning (Full), gallery image decode, and pasted pulsemesh:// invite links.",
                            "Υποστηρίζει ζωντανή κάμερα, εικόνα συλλογής και επικόλληση συνδέσμων pulsemesh://."
                        ),
                        color = p.textSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    if (state.lastQrPairingStatus.isNotBlank()) {
                        Text(
                            text = state.lastQrPairingStatus,
                            color = tokens.statusOnline,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    Button(
                        onClick = {
                            onDismiss()
                            onLaunchQrScanner()
                        },
                        shape = tokens.buttonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.accentPrimary,
                            contentColor = if (tokens.isDark) Color.Black else Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(AppStrings.tr(lang, "Open QR Scanner", "Άνοιγμα Σαρωτή QR"), fontWeight = FontWeight.Bold)
                    }
                }
            }

            2 -> {
                // Tab 2: Bridge iPhone / Offline APK Beaming
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseSurfaceCard()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                            Icon(Icons.Default.WifiTethering, contentDescription = null, tint = tokens.statusOnline)
                            Column {
                                Text(
                                    text = AppStrings.tr(lang, "Hotspot Web Portal & APK Beam", "Πύλη Hotspot & Μετάδοση APK"),
                                    color = p.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = if (state.webPortalStatus.isRunning) portalBaseUrl else AppStrings.tr(lang, "Stopped • Tap switch to host", "Ανενεργή • Ενεργοποιήστε για σύνδεση"),
                                    color = p.textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Switch(
                            checked = state.webPortalStatus.isRunning,
                            onCheckedChange = { onToggleHotspotPortal() }
                        )
                    }

                    if (portalQrBitmap != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .clip(tokens.cardShape)
                                .background(Color.White)
                                .padding(10.dp)
                        ) {
                            Image(
                                bitmap = portalQrBitmap.asImageBitmap(),
                                contentDescription = "Hotspot Web Portal QR",
                                modifier = Modifier.size(156.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(tokens.buttonShape)
                            .background(p.surfaceVariant)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = tokens.accentSecondary, modifier = Modifier.size(18.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "iPhone / Safari 1-Tap Root CA: $iosProfileUrl",
                                color = p.textPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                            Text(
                                text = "Offline Android APK Beam: $apkDownloadUrl",
                                color = tokens.statusOnline,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            else -> {
                // Tab 3: LoRa & Acoustic 2.0 Quick-Connect
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseSurfaceCard()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                            Icon(Icons.Default.Router, contentDescription = null, tint = tokens.accentSecondary)
                            Column {
                                Text("LoRa Companion Bridge", color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(
                                    text = state.loraBridgeState.statusText.ifBlank { "USB-OTG & BLE Meshtastic / MeshCore" },
                                    color = p.textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Switch(
                            checked = state.loraBridgeState.isConnected,
                            onCheckedChange = { onToggleLoraBridge() }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
                                Text(
                                    text = proto.name,
                                    color = if (selected) tokens.accentSecondary else p.textSecondary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 7.dp)
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = onScanLoraDevices,
                            shape = tokens.pillShape
                        ) {
                            Text(AppStrings.tr(lang, "Scan", "Σάρωση"), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Connect & Share Hub", showBackground = true)
@Composable
fun PreviewConnectShareSheet() {
    PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
        Surface(color = LocalPulseTokens.current.palette.surface) {
            ConnectShareSheetContent(
                state = MeshUiState(operatorCallsign = "Alpha-01", claimedUsername = "alpha01"),
                onDismiss = {},
                onLaunchQrScanner = {},
                onToggleHotspotPortal = {},
                onToggleLoraBridge = {},
                onSelectLoraProtocol = {},
                onScanLoraDevices = {},
                onToggleAcousticModem = {},
                onSendAcousticMessage = {}
            )
        }
    }
}
