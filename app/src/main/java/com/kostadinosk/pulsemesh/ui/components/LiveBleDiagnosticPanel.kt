// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.service.BleTxDiagnosticState
import com.kostadinosk.pulsemesh.service.RawBleDeviceRecord
import com.kostadinosk.pulsemesh.ui.theme.*

/**
 * Live Radio Hardware Diagnostic & BLE Sniffer Panel:
 * 1. Live TX Proof: calls BluetoothLeAdvertiser and displays real callback status
 *    "Hardware TX Status: ACTIVE (0) | Negotiated Power: +X dBm".
 * 2. Live RX Proof ("Raw BLE Airwave Sniffer"): runs BluetoothLeScanner without filters,
 *    displaying all real nearby Bluetooth devices (MacBooks, AirTags, headphones, phones)
 *    with real live RSSI, MAC address, and packet counts.
 */
@Composable
fun GattTwoWayLinkDiagnosticCard(
    connectedServerDevices: List<String>,
    connectedGattClients: List<String>,
    isTwoWaySymmetric: Boolean,
    onForceReDiscovery: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalPulsePalette.current
    val cardShape = tacticalCardShape(false, p.isOriginal, p.isAero)
    val btnShape = tacticalButtonShape(false, p.isOriginal, p.isAero)
    val innerShape = if (p.isAero) RoundedCornerShape(14.dp) else RoundedCornerShape(6.dp)

    val isAsymmetricStale = (connectedServerDevices.isNotEmpty() && connectedGattClients.isEmpty()) ||
        (connectedGattClients.isNotEmpty() && connectedServerDevices.isEmpty())
    val statusColor = when {
        isTwoWaySymmetric -> p.emerald
        isAsymmetricStale -> p.amber
        else -> p.textSecondary
    }
    val statusBadgeText = when {
        isTwoWaySymmetric -> "2-WAY SYMMETRIC LINK"
        isAsymmetricStale -> "STALE / 1-WAY LINK"
        else -> "AWAITING GATT PEER"
    }
    val cardBorderColor = if (isTwoWaySymmetric) p.emerald else if (isAsymmetricStale) p.amber else p.border

    Surface(
        color = if (p.isAero) Color.Transparent else p.card,
        shape = cardShape,
        border = if (p.isAero) null else androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor),
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (p.isAero) {
                    Modifier.aeroGlassPanel(
                        palette = p,
                        cornerRadius = 20.dp,
                        tint = if (p.isDarkAero) Color(0xD40A2338) else Color(0xECFFFFFF),
                        borderColor = cardBorderColor,
                    )
                } else Modifier
            )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (p.isAero) {
                        AeroGlossBadgeIcon(
                            imageVector = Icons.Default.SyncAlt,
                            accentColor = statusColor,
                            contentDescription = "2-Way GATT",
                            badgeSize = 26.dp,
                            iconSize = 14.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.SyncAlt,
                            contentDescription = "2-Way GATT",
                            tint = statusColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "2-WAY BLE GATT PIPE DIAGNOSTIC",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(if (p.isAero) 10.dp else 4.dp))
                        .background(statusColor.copy(alpha = 0.16f))
                        .border(1.dp, statusColor, RoundedCornerShape(if (p.isAero) 10.dp else 4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = statusBadgeText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 8.sp
                        )
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Separate Box 1: connectedServerDevices (Inbound GATT Server)
                Surface(
                    color = if (p.isAero) {
                        if (p.isDarkAero) Color(0xB806192A) else Color(0xCCF0FAFF)
                    } else p.background,
                    shape = innerShape,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (connectedServerDevices.isNotEmpty()) p.emerald else p.border
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "INBOUND SERVER (${connectedServerDevices.size})",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (connectedServerDevices.isNotEmpty()) p.emerald else p.textMuted,
                                fontWeight = FontWeight.Bold,
                                fontSize = 8.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = if (connectedServerDevices.isNotEmpty()) {
                                connectedServerDevices.joinToString("\n")
                            } else {
                                "0 inbound peers"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = p.textPrimary,
                                fontSize = 9.sp
                            )
                        )
                    }
                }

                // Separate Box 2: connectedGattClients (Outbound GATT Client)
                Surface(
                    color = if (p.isAero) {
                        if (p.isDarkAero) Color(0xB806192A) else Color(0xCCF0FAFF)
                    } else p.background,
                    shape = innerShape,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (connectedGattClients.isNotEmpty()) p.cyan else p.border
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "OUTBOUND CLIENT (${connectedGattClients.size})",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (connectedGattClients.isNotEmpty()) p.cyan else p.textMuted,
                                fontWeight = FontWeight.Bold,
                                fontSize = 8.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = if (connectedGattClients.isNotEmpty()) {
                                connectedGattClients.joinToString("\n")
                            } else {
                                "0 outbound clients"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = p.textPrimary,
                                fontSize = 9.sp
                            )
                        )
                    }
                }
            }

            val refreshAccent = if (isAsymmetricStale) p.amber else p.cyan
            val refreshContentColor = if (p.isLight || p.isAero) Color.White else Color.Black
            Button(
                onClick = onForceReDiscovery,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (p.isAero) Color.Transparent else refreshAccent,
                    contentColor = refreshContentColor
                ),
                shape = btnShape,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .then(
                        if (p.isAero) Modifier.aeroGlossOverlay(p, refreshAccent, btnShape, 16.dp)
                        else Modifier
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Force Re-Discovery",
                    tint = refreshContentColor,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isAsymmetricStale) "FORCE 2-WAY GATT RE-DISCOVERY" else "REFRESH 2-WAY GATT LINK",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun LiveBleDiagnosticPanel(
    txState: BleTxDiagnosticState,
    isRxSnifferActive: Boolean,
    scannedDevices: List<RawBleDeviceRecord>,
    totalRxPackets: Int,
    onStartTx: () -> Unit,
    onStopTx: () -> Unit,
    onToggleRxSniffer: () -> Unit,
    onClearDevices: () -> Unit,
    onRequestPermissions: () -> Unit,
    connectedServerDevices: List<String> = emptyList(),
    connectedGattClients: List<String> = emptyList(),
    isTwoWaySymmetric: Boolean = false,
    onForceGattReDiscovery: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val p = LocalPulsePalette.current
    val cardShape = tacticalCardShape(false, p.isOriginal, p.isAero)
    val btnShape = tacticalButtonShape(false, p.isOriginal, p.isAero)
    val innerShape = if (p.isAero) RoundedCornerShape(14.dp) else RoundedCornerShape(6.dp)
    val innerBg = if (p.isAero) {
        if (p.isDarkAero) Color(0xB806192A) else Color(0xCCF2FAFF)
    } else p.background
    val btnFg = if (p.isLight || p.isAero) Color.White else Color.Black

    Surface(
        color = if (p.isAero) Color.Transparent else p.card,
        shape = cardShape,
        border = if (p.isAero) null else androidx.compose.foundation.BorderStroke(1.dp, p.cyan),
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (p.isAero) {
                    Modifier.aeroGlassPanel(
                        palette = p,
                        cornerRadius = 22.dp,
                        tint = if (p.isDarkAero) Color(0xD40A2338) else Color(0xEAFFFFFF),
                        borderColor = p.cyan,
                    )
                } else Modifier
            )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (p.isAero) {
                        AeroGlossBadgeIcon(
                            imageVector = Icons.Default.BluetoothSearching,
                            accentColor = p.cyan,
                            contentDescription = "BLE Diagnostic",
                            badgeSize = 34.dp,
                            iconSize = 18.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.BluetoothSearching,
                            contentDescription = "BLE Diagnostic",
                            tint = p.cyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "LIVE RADIO HARDWARE SELF-TEST & BLE SNIFFER",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = p.cyan,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = "1-PHONE HARDWARE PROOF: SILICON TX BEACON & RAW RX AIRWAVE CAPTURE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = p.textSecondary,
                                fontSize = 8.sp
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                val isRadioActive = txState.isAdvertising || isRxSnifferActive
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(if (p.isAero) 10.dp else 4.dp))
                        .background(if (isRadioActive) p.emeraldDim else p.surfaceVariant)
                        .border(
                            1.dp,
                            if (isRadioActive) p.emerald else p.border,
                            RoundedCornerShape(if (p.isAero) 10.dp else 4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isRadioActive) "RADIO ONLINE" else "STANDBY",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (isRadioActive) p.emerald else p.textMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 8.sp
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            GattTwoWayLinkDiagnosticCard(
                connectedServerDevices = connectedServerDevices,
                connectedGattClients = connectedGattClients,
                isTwoWaySymmetric = isTwoWaySymmetric,
                onForceReDiscovery = onForceGattReDiscovery
            )

            Spacer(modifier = Modifier.height(10.dp))

            // -------------------------------------------------------------
            // SUB-SECTION A: LIVE TX PROOF (BluetoothLeAdvertiser Callback)
            // -------------------------------------------------------------
            Surface(
                color = innerBg,
                shape = innerShape,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (txState.isAdvertising) p.emerald else p.border
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(9.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (txState.isAdvertising) p.emerald else p.amber)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "A) LIVE TX PROOF (BluetoothLeAdvertiser)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = p.emerald,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp
                                )
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        val txBtnColor = if (txState.isAdvertising) p.red else p.emerald
                        val txBtnTextColor = if (txState.isAdvertising) Color.White else btnFg
                        Button(
                            onClick = {
                                if (txState.isAdvertising) onStopTx() else onStartTx()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (p.isAero) Color.Transparent else txBtnColor,
                                contentColor = txBtnTextColor
                            ),
                            shape = btnShape,
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp),
                            modifier = Modifier
                                .height(30.dp)
                                .then(
                                    if (p.isAero) Modifier.aeroGlossOverlay(p, txBtnColor, btnShape, 12.dp)
                                    else Modifier
                                )
                        ) {
                            Text(
                                text = if (txState.isAdvertising) "STOP TX BEACON" else "START LIVE TX BEACON",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = txState.statusMessage,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (txState.isAdvertising) p.emerald else p.textPrimary,
                            fontWeight = if (txState.isAdvertising) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 10.sp
                        )
                    )

                    if (txState.isAdvertising) {
                        Text(
                            text = "Beacon frames pushed: ${txState.txPacketsCount} packets (Coded PHY S=8 / Vendor IE 0xDD payload)",
                            style = MaterialTheme.typography.labelSmall.copy(color = p.cyan, fontSize = 8.sp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // -------------------------------------------------------------
            // SUB-SECTION B: LIVE RX PROOF ("Raw BLE Airwave Sniffer")
            // -------------------------------------------------------------
            Surface(
                color = innerBg,
                shape = innerShape,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isRxSnifferActive) p.cyan else p.border
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(9.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isRxSnifferActive) p.cyan else p.textMuted)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "B) LIVE RX PROOF (Raw BLE Airwave Sniffer)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = p.cyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp
                                )
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (scannedDevices.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = onClearDevices,
                                    shape = btnShape,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, p.border),
                                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("CLEAR", fontSize = 8.sp, color = p.textSecondary)
                                }
                            }

                            val rxBtnColor = if (isRxSnifferActive) p.red else p.cyan
                            val rxBtnTextColor = if (isRxSnifferActive) Color.White else btnFg
                            Button(
                                onClick = onToggleRxSniffer,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (p.isAero) Color.Transparent else rxBtnColor,
                                    contentColor = rxBtnTextColor
                                ),
                                shape = btnShape,
                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(30.dp)
                                    .then(
                                        if (p.isAero) Modifier.aeroGlossOverlay(p, rxBtnColor, btnShape, 12.dp)
                                        else Modifier
                                    )
                            ) {
                                Text(
                                    text = if (isRxSnifferActive) "STOP SNIFFER" else "START RAW SNIFFER",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (isRxSnifferActive) {
                            "Sniffing raw RF airwaves without UUID filter: $totalRxPackets packets captured across ${scannedDevices.size} nearby Bluetooth devices (MacBooks, AirTags, phones, accessories)."
                        } else {
                            "Sniffer Idle — Tap 'START RAW SNIFFER' to prove physical radio receiver is capturing nearby Bluetooth signals in this room."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (isRxSnifferActive) p.textPrimary else p.textSecondary,
                            fontSize = 9.sp
                        )
                    )

                    // Live Scanned Devices Stream (Shows real nearby devices)
                    if (scannedDevices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 140.dp)
                        ) {
                            scannedDevices.take(6).forEach { dev ->
                                Surface(
                                    color = p.surfaceVariant,
                                    shape = RoundedCornerShape(if (p.isAero) 10.dp else 3.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, p.border),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(
                                                text = dev.name,
                                                color = p.textPrimary,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "${dev.address} • ${dev.packetsReceived} pkts",
                                                color = p.textMuted,
                                                fontSize = 7.sp
                                            )
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val rssiColor = when {
                                                dev.rssiDbm >= -70 -> p.emerald
                                                dev.rssiDbm >= -85 -> p.amber
                                                else -> p.red
                                            }
                                            Text(
                                                text = "${dev.rssiDbm} dBm",
                                                color = rssiColor,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
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
    }
}
