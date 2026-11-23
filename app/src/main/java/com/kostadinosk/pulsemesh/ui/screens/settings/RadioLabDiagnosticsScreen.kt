// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.screens.settings

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.ui.components.LiveBleDiagnosticPanel
import com.kostadinosk.pulsemesh.ui.components.ProMeshRssiChartCard
import com.kostadinosk.pulsemesh.ui.onboarding.HardwareSummaryCard
import com.kostadinosk.pulsemesh.ui.theme.LocalPulseTokens
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.ui.theme.pulseSurfaceCard
import com.kostadinosk.pulsemesh.util.AppStrings
import com.kostadinosk.pulsemesh.util.HardwareCapabilityDetector
import com.kostadinosk.pulsemesh.util.ThemeBrightness
import com.kostadinosk.pulsemesh.util.ThemePreset
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState

/**
 * PulseMesh v2.0 Radio Lab & Diagnostics (retires RootBoosterScreen and removes duplicated radio toggles):
 * - Runtime Hardware Capability Matrix & OEM Battery Bypass
 * - Real-Time Mesh RSSI Telemetry Chart
 * - Live BLE TX/RX Sniffer & Link-Cost Router Stats
 * - Root Wi-Fi Power-Save Card (only shown expanded when root is verified)
 */
@Composable
fun RadioLabDiagnosticsScreen(
    state: MeshUiState,
    onStartBleTx: () -> Unit = {},
    onStopBleTx: () -> Unit = {},
    onToggleRxSniffer: () -> Unit = {},
    onClearBleDevices: () -> Unit = {},
    onRequestPermissions: () -> Unit = {},
    onForceGattReDiscovery: () -> Unit = {},
    onRunPingSweep: () -> Unit,
    onVerifyRoot: () -> Unit,
    onRefreshHardware: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    val context = LocalContext.current
    val lang = state.appLanguage

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
            .testTag("radio_lab_diagnostics_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Science, contentDescription = null, tint = tokens.accentPrimary)
                Column {
                    Text(
                        text = AppStrings.tr(lang, "Radio Lab & Diagnostics", "Εργαστήριο Ραδιοσυχνοτήτων"),
                        color = p.textPrimary,
                        fontFamily = tokens.labelFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        text = AppStrings.tr(lang, "Live BLE sniffer, RSSI chart, Link-Cost router & hardware probes", "Ζωντανό BLE, γράφημα RSSI, δρομολόγηση & υλικό"),
                        color = p.textSecondary,
                        fontSize = 11.sp
                    )
                }
            }
            OutlinedButton(onClick = onRefreshHardware, shape = tokens.pillShape) {
                Text(AppStrings.tr(lang, "Rescan", "Έλεγχος"), fontSize = 11.sp)
            }
        }

        // Runtime Hardware Capability Report + Regional Codename Detection
        state.hardwareReport?.let { hw ->
            HardwareSummaryCard(
                hw = hw,
                onOpenBatterySettings = {
                    HardwareCapabilityDetector.launchOemBatterySettings(context, hw.batteryBypassGuide)
                }
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseSurfaceCard()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Memory, contentDescription = null, tint = tokens.accentSecondary, modifier = Modifier.size(16.dp))
                    Text(
                        text = AppStrings.tr(lang, "Runtime Capability Matrix", "Πίνακας Δυνατοτήτων Συσκευής"),
                        color = p.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
                CapabilityRow("BLE 5 Coded PHY (Long Range)", hw.ble5CodedPhySupported)
                CapabilityRow("BLE 2M PHY & Extended Adv", hw.ble2MPhySupported || hw.bleExtendedAdvSupported)
                CapabilityRow("Wi-Fi Aware (NAN)", hw.wifiAwareSupported)
                CapabilityRow("Wi-Fi Direct (P2P)", hw.wifiDirectSupported)
                CapabilityRow("NFC Host Card Emulation (HCE)", hw.nfcHceSupported)
                CapabilityRow("USB-OTG Host (LoRa Serial)", hw.usbHostOtgSupported)
                CapabilityRow("Barometric Pressure Sensor", hw.barometerSupported)
            }
        }

        // Real-Time Mesh RSSI Telemetry Chart
        ProMeshRssiChartCard(
            state = state,
            modifier = Modifier.fillMaxWidth()
        )

        // Link-Cost & Ping Sweep Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = AppStrings.tr(lang, "Smart Link-Cost Router & RTT Sweep", "Έξυπνη Δρομολόγηση & Έλεγχος RTT"),
                color = p.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Text(
                text = state.lastPingSweepReport,
                color = p.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            OutlinedButton(
                onClick = onRunPingSweep,
                enabled = !state.isPingSweepRunning,
                shape = tokens.buttonShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (state.isPingSweepRunning) AppStrings.tr(lang, "Probing Mesh Links...", "Γίνεται σάρωση...")
                    else AppStrings.tr(lang, "Run Mesh Link Sweep", "Εκτέλεση Ελέγχου Συνδέσεων")
                )
            }
        }

        // Live BLE TX/RX Sniffer Panel
        LiveBleDiagnosticPanel(
            txState = state.bleTxState,
            isRxSnifferActive = state.isBleRxSnifferActive,
            scannedDevices = state.scannedBleDevices,
            totalRxPackets = state.totalBleRxPackets,
            onStartTx = onStartBleTx,
            onStopTx = onStopBleTx,
            onToggleRxSniffer = onToggleRxSniffer,
            onClearDevices = onClearBleDevices,
            onRequestPermissions = onRequestPermissions,
            connectedServerDevices = state.connectedServerDeviceAddresses,
            connectedGattClients = state.connectedGattClientAddresses,
            isTwoWaySymmetric = state.isTwoWayGattSymmetric,
            onForceGattReDiscovery = onForceGattReDiscovery
        )

        // Optional Root Wi-Fi Power-Save Card (compact unless root is verified)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = if (state.isRootGranted) tokens.statusOnline else p.textMuted, modifier = Modifier.size(16.dp))
                    Text(
                        text = if (state.isRootGranted) "Root Wi-Fi Power-Save Active" else "Optional Root Diagnostics",
                        color = p.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                OutlinedButton(
                    onClick = onVerifyRoot,
                    enabled = !state.isRootVerifying,
                    shape = tokens.pillShape
                ) {
                    Text(if (state.isRootVerifying) "Checking..." else "Probe su", fontSize = 11.sp)
                }
            }
            if (state.isRootGranted) {
                Text(
                    text = "Superuser uid=0 verified. Wi-Fi power_save is disabled for steadier local P2P relays; legal RF TX power remains governed by your chipset.",
                    color = tokens.statusOnline,
                    fontSize = 11.sp
                )
                if (state.rootProbeLogs.isNotEmpty()) {
                    Surface(
                        color = p.surfaceVariant,
                        shape = tokens.buttonShape,
                        border = BorderStroke(1.dp, p.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            state.rootProbeLogs.takeLast(5).forEach { line ->
                                Text(line, color = p.textSecondary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "Standard unrooted mode. All PulseMesh transports (BLE, L2CAP, Wi-Fi Aware, Hotspot, LoRa, Acoustic 2.0) run without root.",
                    color = p.textMuted,
                    fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.size(12.dp))
    }
}

@Composable
private fun CapabilityRow(label: String, supported: Boolean) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = p.textSecondary, fontSize = 11.sp)
        Icon(
            imageVector = if (supported) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = if (supported) "Supported" else "Unavailable",
            tint = if (supported) tokens.statusOnline else p.textMuted,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Preview(name = "Radio Lab & Diagnostics", showBackground = true)
@Composable
fun PreviewRadioLabDiagnostics() {
    PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
        Surface(color = LocalPulseTokens.current.palette.background) {
            RadioLabDiagnosticsScreen(
                state = MeshUiState(operatorCallsign = "Alpha-01"),
                onRunPingSweep = {},
                onVerifyRoot = {},
                onRefreshHardware = {}
            )
        }
    }
}
