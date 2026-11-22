// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.screens

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.model.PhoneModelProfile
import com.kostadinosk.pulsemesh.ui.components.LiveBleDiagnosticPanel
import com.kostadinosk.pulsemesh.ui.theme.*
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState

/**
 * Live Radio Diagnostics & Link-Cost Inspector (PulseMesh v1.4.3).
 *
 * Replaces legacy placebo +25 dBm / 75-phone catalog toggles with 100% real,
 * measurable radio telemetry: BLE TX/RX sniffer proof, Smart Link-Cost Router stats,
 * PMB2 binary savings, Wi-Fi Aware NAN / L2CAP / WebSocket state, and honest read-only
 * OS root probe diagnostics.
 */
@Composable
fun RootBoosterScreen(
    state: MeshUiState,
    onToggleBleCoded: () -> Unit,
    onToggleWifiStuffing: () -> Unit,
    onToggleUnthrottledScan: () -> Unit,
    onToggleSystemlessMount: () -> Unit,
    onSelectPhoneProfile: (PhoneModelProfile) -> Unit,
    onAutoSwitchToDetected: () -> Unit,
    onForceOverrideMismatch: () -> Unit,
    onOpenMismatchDialog: () -> Unit,
    onVerifyRoot: () -> Unit,
    onStartBleTx: () -> Unit = {},
    onStopBleTx: () -> Unit = {},
    onToggleRxSniffer: () -> Unit = {},
    onClearBleDevices: () -> Unit = {},
    onRequestPermissions: () -> Unit = {},
    onForceGattReDiscovery: () -> Unit = {},
    onToggleWifiAware: () -> Unit = {},
    onToggleUltrasonicModem: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val p = LocalPulsePalette.current
    val cardShape = tacticalCardShape(isSimpleMode = true, isOriginal = p.isOriginal, isAero = p.isAero)
    val btnShape = tacticalButtonShape(isSimpleMode = true, isOriginal = p.isOriginal, isAero = p.isAero)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(if (p.isAero) Color.Transparent else p.background)
            .padding(horizontal = 14.dp)
            .testTag("live_radio_diagnostics_screen"),
        contentPadding = PaddingValues(top = 10.dp, bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Live Link-Cost Router & PMB2 Telemetry Card
        item {
            Surface(
                color = p.card,
                shape = cardShape,
                border = BorderStroke(1.dp, p.cyan.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Hub, contentDescription = null, tint = p.cyan, modifier = Modifier.size(18.dp))
                            Text(
                                text = "SMART LINK-COST ROUTER & PMB2",
                                color = p.cyan,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                        Surface(
                            color = p.emerald.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = "${state.activePeerCount} PEER(S)",
                                color = p.emerald,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Text(
                        text = state.routerSummary,
                        color = p.textPrimary,
                        fontSize = 12.5.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DiagMetricChip(
                            label = "PMB2 SAVED",
                            value = "${state.binaryBytesSaved} B",
                            accent = p.emerald,
                            modifier = Modifier.weight(1f)
                        )
                        DiagMetricChip(
                            label = "PMB2 PEERS",
                            value = "${state.pmb2PeerCount}",
                            accent = p.cyan,
                            modifier = Modifier.weight(1f)
                        )
                        DiagMetricChip(
                            label = "FAILOVERS",
                            value = "${state.routerFailoverCount}",
                            accent = p.amber,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DiagMetricChip(
                            label = "BLE L2CAP",
                            value = if (state.localL2capPsm > 0) "PSM ${state.localL2capPsm} (${state.activeL2capChannelsCount})" else "STANDBY",
                            accent = p.cyan,
                            modifier = Modifier.weight(1f)
                        )
                        DiagMetricChip(
                            label = "WS PORTAL",
                            value = "${state.webPortalStatus.wsClientCount} WS / ${state.webPortalStatus.activeClientCount} WEB",
                            accent = p.emerald,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 2. Real Active Radio Switches (Only Real Working Transports)
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
                        text = "REAL HARDWARE TRANSPORTS (${Build.MANUFACTURER} ${Build.MODEL} • API ${Build.VERSION.SDK_INT})",
                        color = p.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )

                    RealRadioToggleRow(
                        title = "Wi-Fi Aware (NAN) IPv6 Data-Path",
                        subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            "Zero-Hotspot Android 10+ P2P data-path (auto-pauses when Hotspot hosts)"
                        } else {
                            "Requires Android 10+ (API 29+)"
                        },
                        checked = state.wifiAwareEnabled,
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                        onCheckedChange = { onToggleWifiAware() }
                    )

                    HorizontalDivider(color = p.border.copy(alpha = 0.5f))

                    RealRadioToggleRow(
                        title = "Bell 202 Acoustic AFSK RX (1200 / 2200 Hz)",
                        subtitle = "Air-gap speaker/mic modem compatible with Android, iPhone & Web PWA (CRC-16)",
                        checked = state.bell202ModemEnabled,
                        enabled = true,
                        onCheckedChange = { onToggleUltrasonicModem() }
                    )

                    HorizontalDivider(color = p.border.copy(alpha = 0.5f))

                    RealRadioToggleRow(
                        title = "BLE Coded PHY (Long Range when supported)",
                        subtitle = "Requests Android LE Coded PHY advertising on Bluetooth 5.0+ chipsets",
                        checked = state.bleCodedPhyEnabled,
                        enabled = true,
                        onCheckedChange = { onToggleBleCoded() }
                    )
                }
            }
        }

        // 3. Live BLE TX Proof & Real Raw Airwave RX Sniffer
        item {
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
        }

        // 4. Honest Read-Only Root / OS Power Check (Zero Fake dBm Overrides)
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "OS RADIO & ROOT PROBE (READ-ONLY)",
                            color = p.textSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        OutlinedButton(
                            onClick = onVerifyRoot,
                            enabled = !state.isRootVerifying,
                            shape = btnShape,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                text = if (state.isRootVerifying) "CHECKING..." else "CHECK SU",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = if (state.isRootGranted) {
                            "Superuser uid=0 verified. PulseMesh disables Wi-Fi power_save when rooted; legal RF TX power remains governed by your chipset firmware."
                        } else {
                            "Standard Unrooted Android Mode. All PulseMesh transports (BLE, L2CAP, Wi-Fi Aware, Hotspot WebSocket, Bell 202) work at full capability without root."
                        },
                        color = p.textSecondary,
                        fontSize = 12.sp
                    )
                    if (state.rootProbeLogs.isNotEmpty()) {
                        Surface(
                            color = p.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                state.rootProbeLogs.takeLast(5).forEach { logLine ->
                                    Text(
                                        text = logLine,
                                        color = p.textMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagMetricChip(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val p = LocalPulsePalette.current
    Surface(
        color = p.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                color = p.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = value,
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RealRadioToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val p = LocalPulsePalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(text = title, color = p.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(text = subtitle, color = p.textMuted, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}
