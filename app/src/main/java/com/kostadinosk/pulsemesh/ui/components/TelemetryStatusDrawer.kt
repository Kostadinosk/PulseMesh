// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.ui.theme.LocalPulseTokens
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.ui.theme.pulseSurfaceCard
import com.kostadinosk.pulsemesh.util.AppStrings
import com.kostadinosk.pulsemesh.util.ThemeBrightness
import com.kostadinosk.pulsemesh.util.ThemePreset
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelemetryStatusDrawer(
    state: MeshUiState,
    onDismiss: () -> Unit,
    onToggleInternetMode: () -> Unit,
    onToggleHotspot: () -> Unit,
    onOpenRadioLab: () -> Unit,
    onOpenConnectHub: () -> Unit,
    onToggleWifiDirect: () -> Unit = {},
    onToggleWifiAware: () -> Unit = {}
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        shape = tokens.sheetShape
    ) {
        TelemetryDrawerContent(
            state = state,
            onDismiss = onDismiss,
            onToggleInternetMode = onToggleInternetMode,
            onToggleHotspot = onToggleHotspot,
            onOpenRadioLab = onOpenRadioLab,
            onOpenConnectHub = onOpenConnectHub,
            onToggleWifiDirect = onToggleWifiDirect,
            onToggleWifiAware = onToggleWifiAware
        )
    }
}

@Composable
fun TelemetryDrawerContent(
    state: MeshUiState,
    onDismiss: () -> Unit,
    onToggleInternetMode: () -> Unit,
    onToggleHotspot: () -> Unit,
    onOpenRadioLab: () -> Unit,
    onOpenConnectHub: () -> Unit,
    onToggleWifiDirect: () -> Unit = {},
    onToggleWifiAware: () -> Unit = {}
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    val lang = state.appLanguage

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(bottom = 28.dp)
            .testTag("telemetry_status_drawer"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = AppStrings.tr(lang, "Mesh & Radio Status", "Κατάσταση Δικτύου & Ραδιοσυχνοτήτων"),
                    color = p.textPrimary,
                    fontFamily = tokens.labelFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                Text(
                    text = "${state.operatorCallsign} • ${state.localNodeId} • ${state.batteryPowerProfile.label}",
                    color = p.textSecondary,
                    fontFamily = tokens.labelFontFamily,
                    fontSize = 11.sp
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = p.textSecondary)
            }
        }

        // Quick Mode Controls (Row 1: Hybrid Relay + Hotspot Portal; Row 2: Wi-Fi Direct + Wi-Fi Aware NAN)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickControlTile(
                title = if (state.isInternetEnabled) AppStrings.tr(lang, "Hybrid Relay", "Υβριδική") else AppStrings.tr(lang, "Pure Off-Grid", "Εκτός Δικτ."),
                subtitle = if (state.isInternetEnabled) AppStrings.tr(lang, "Cloudflare + Mesh", "Cloudflare + Mesh") else AppStrings.tr(lang, "Local Radios Only", "Μόνο Τοπικά Ράδιο"),
                icon = Icons.Default.Public,
                active = state.isInternetEnabled,
                accent = tokens.statusMesh,
                onClick = onToggleInternetMode,
                modifier = Modifier.weight(1f).testTag("drawer_toggle_internet")
            )
            QuickControlTile(
                title = AppStrings.tr(lang, "Hotspot Host", "Hotspot Host"),
                subtitle = if (state.webPortalStatus.isRunning) AppStrings.tr(lang, "Hosting Link Active", "Ενεργός Σύνδεσμος") else AppStrings.tr(lang, "Tap to Host", "Πάτησε για εκκίνηση"),
                icon = Icons.Default.WifiTethering,
                active = state.webPortalStatus.isRunning,
                accent = tokens.statusOnline,
                onClick = onToggleHotspot,
                modifier = Modifier.weight(1f).testTag("drawer_toggle_hotspot")
            )
        }
        val wifiDirectActive = state.hotspotButtonText.contains("HOST", ignoreCase = true) ||
            state.hotspotButtonText.contains("CONNECTED", ignoreCase = true) ||
            state.hotspotButtonText.contains("STARTING", ignoreCase = true)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickControlTile(
                title = AppStrings.tr(lang, "Wi-Fi Direct", "Wi-Fi Direct"),
                subtitle = if (wifiDirectActive) state.hotspotButtonText.removePrefix("HOTSPOT: ").take(18) else AppStrings.tr(lang, "Off-Grid P2P Link", "Απευθείας P2P"),
                icon = Icons.Default.Wifi,
                active = wifiDirectActive,
                accent = tokens.accentPrimary,
                onClick = onToggleWifiDirect,
                modifier = Modifier.weight(1f).testTag("drawer_toggle_wifi_direct")
            )
            QuickControlTile(
                title = AppStrings.tr(lang, "Wi-Fi Aware", "Wi-Fi Aware"),
                subtitle = if (state.wifiAwareEnabled) AppStrings.tr(lang, "NAN Zero-Popup ON", "NAN Ενεργό") else AppStrings.tr(lang, "Tap to Enable NAN", "Ενεργοποίηση NAN"),
                icon = Icons.Default.Router,
                active = state.wifiAwareEnabled,
                accent = tokens.accentSecondary,
                onClick = onToggleWifiAware,
                modifier = Modifier.weight(1f).testTag("drawer_toggle_wifi_aware")
            )
        }

        // Live Transport Grid
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = AppStrings.tr(lang, "Active Transports & Telemetry", "Ενεργές Μεταφορές & Τηλεμετρία"),
                color = p.textPrimary,
                fontFamily = tokens.labelFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricPill(
                    label = AppStrings.tr(lang, "Peers", "Κόμβοι"),
                    value = "${state.activePeerCount}",
                    color = tokens.statusOnline,
                    modifier = Modifier.weight(1f)
                )
                MetricPill(
                    label = AppStrings.tr(lang, "TX / RX", "TX / RX"),
                    value = "${state.txFramesCount}/${state.rxFramesCount}",
                    color = tokens.statusMesh,
                    modifier = Modifier.weight(1f)
                )
                MetricPill(
                    label = AppStrings.tr(lang, "DTN Queue", "Ουρά DTN"),
                    value = "${state.pendingDtnCount}",
                    color = if (state.pendingDtnCount > 0) tokens.statusWarning else p.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                MetricPill(
                    label = AppStrings.tr(lang, "Battery", "Μπαταρία"),
                    value = "${state.realBatteryPercent}%",
                    color = if (state.realBatteryPercent <= 20) tokens.statusDanger else tokens.statusOnline,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TransportChip("BLE", state.bleTxState.isAdvertising || state.activePeerCount > 0, Icons.Default.Bluetooth, Modifier.weight(1f))
                TransportChip("LoRa", state.loraBridgeState.isConnected, Icons.Default.Router, Modifier.weight(1f))
                TransportChip("CoT", state.isCotBridgeEnabled, Icons.Default.BatteryChargingFull, Modifier.weight(1f))
            }

            state.hardwareReport?.let { hw ->
                Text(
                    text = "${hw.resolvedMarketingName} (${hw.resolvedRegion}) • ${hw.activeHardwareSummary}",
                    color = p.textSecondary,
                    fontSize = 11.sp
                )
            }
        }

        // Bottom Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    onDismiss()
                    onOpenRadioLab()
                },
                shape = tokens.buttonShape,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(AppStrings.tr(lang, "Radio Lab", "Εργαστήριο"), fontSize = 12.sp)
            }
            Button(
                onClick = {
                    onDismiss()
                    onOpenConnectHub()
                },
                shape = tokens.buttonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.accentPrimary,
                    contentColor = if (tokens.isDark) Color.Black else Color.White
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(AppStrings.tr(lang, "Connect & Share", "Σύνδεση & QR"), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun QuickControlTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    Surface(
        color = if (active) accent.copy(alpha = 0.16f) else p.card,
        shape = tokens.cardShape,
        border = BorderStroke(tokens.borderWidth, if (active) accent else p.border),
        modifier = modifier
            .clip(tokens.cardShape)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = title, tint = if (active) accent else p.textSecondary, modifier = Modifier.size(20.dp))
            Column {
                Text(title, color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(subtitle, color = p.textSecondary, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun MetricPill(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    Column(
        modifier = modifier
            .clip(tokens.buttonShape)
            .background(p.surfaceVariant)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = color, fontFamily = tokens.labelFontFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(label, color = p.textMuted, fontSize = 9.sp)
    }
}

@Composable
private fun TransportChip(
    label: String,
    active: Boolean,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    Row(
        modifier = modifier
            .clip(tokens.pillShape)
            .background(if (active) tokens.statusOnline.copy(alpha = 0.16f) else p.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (active) tokens.statusOnline else p.textMuted)
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = label,
            color = if (active) tokens.statusOnline else p.textMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Preview(name = "Telemetry Drawer Practical Dark", showBackground = true)
@Composable
fun PreviewTelemetryDrawerDark() {
    PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
        Surface(color = LocalPulseTokens.current.palette.surface) {
            TelemetryDrawerContent(
                state = MeshUiState(
                    operatorCallsign = "Alpha-01",
                    activePeerCount = 3,
                    txFramesCount = 24,
                    rxFramesCount = 31,
                    pendingDtnCount = 2,
                    realBatteryPercent = 82
                ),
                onDismiss = {},
                onToggleInternetMode = {},
                onToggleHotspot = {},
                onOpenRadioLab = {},
                onOpenConnectHub = {}
            )
        }
    }
}
