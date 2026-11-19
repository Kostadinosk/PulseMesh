// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.MeshNode
import com.example.service.TacticalSensorService
import com.example.ui.theme.*

@Composable
fun TelemetryHudCard(
    node: MeshNode,
    userLat: Double?,
    userLon: Double?,
    onClose: () -> Unit,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    deviceHeadingDeg: Float = 0f,
    userAltitudeMeters: Double? = null,
    onVerifyFingerprint: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val absBearing = if (userLat != null && userLon != null && node.latitude != 0.0 && node.longitude != 0.0 && !node.isSelf) {
        TacticalSensorService.calculateBearingDegrees(userLat, userLon, node.latitude, node.longitude)
    } else null
    val relBearing = absBearing?.let {
        TacticalSensorService.calculateRelativeBearingDegrees(it, deviceHeadingDeg)
    }
    val arrowStr = relBearing?.let { TacticalSensorService.bearingToCompassArrow(it) } ?: "↑"
    val cardinalStr = absBearing?.let { TacticalSensorService.bearingToCardinal(it) } ?: "N"

    val effectiveAltitude = if (node.isSelf) {
        userAltitudeMeters ?: node.altitudeM
    } else {
        node.altitudeM
    }

    Surface(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .navigationBarsPadding()
            .widthIn(max = 600.dp)
            .fillMaxWidth(0.94f)
            .padding(bottom = 12.dp),
        color = DeepCarbon,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, TacticalBorder),
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (node.isSelf) CyanGateway else EmeraldMesh)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = node.callsign,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (node.isSelf) CyanGateway else EmeraldMesh,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        Text(
                            text = "NODE ID: ${node.id} • Hop ${node.hopCount} • ${node.model}",
                            style = MaterialTheme.typography.labelSmall.copy(color = TelemetryTextSecondary)
                        )
                    }
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close HUD",
                        tint = TelemetryTextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Telemetry Grid (Distance, Compass Bearing Arrow, Barometric Altitude ASL, Battery, RSSI)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(TacticalBlack)
                    .border(1.dp, TacticalBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("DISTANCE", style = MaterialTheme.typography.labelSmall.copy(color = TelemetryTextMuted, fontSize = 8.sp))
                    val distStr = node.distanceMeters?.let {
                        if (it >= 1000) "%.2f km".format(it / 1000.0) else "%.0f m".format(it)
                    } ?: "Direct"
                    Text(distStr, style = MaterialTheme.typography.labelSmall.copy(color = EmeraldMesh, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
                }
                Column {
                    Text("BEARING", style = MaterialTheme.typography.labelSmall.copy(color = TelemetryTextMuted, fontSize = 8.sp))
                    val bearingDisplay = if (absBearing != null && relBearing != null) {
                        "$arrowStr ${relBearing.toInt()}° ($cardinalStr)"
                    } else {
                        "↑ ${deviceHeadingDeg.toInt()}° HDG"
                    }
                    Text(bearingDisplay, style = MaterialTheme.typography.labelSmall.copy(color = CyanGateway, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
                }
                Column {
                    Text("ALTITUDE", style = MaterialTheme.typography.labelSmall.copy(color = TelemetryTextMuted, fontSize = 8.sp))
                    Text("${effectiveAltitude.toInt()} m ASL", style = MaterialTheme.typography.labelSmall.copy(color = AmberRootBoost, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
                }
                Column {
                    Text("BAT / RSSI", style = MaterialTheme.typography.labelSmall.copy(color = TelemetryTextMuted, fontSize = 8.sp))
                    Text(
                        "${node.batteryPercent}% • ${node.rssiDbm}dBm",
                        style = MaterialTheme.typography.labelSmall.copy(color = TelemetryTextPrimary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    )
                }
            }

            if (!node.isSelf) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = if (node.isKeyChangedWarning) RedSosAlert.copy(alpha = 0.18f) else TacticalBlack,
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        when {
                            node.isKeyChangedWarning -> RedSosAlert
                            node.safetyFingerprint.isNotBlank() -> EmeraldMesh.copy(alpha = 0.5f)
                            else -> TacticalBorder
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (node.isKeyChangedWarning) Icons.Default.Warning else Icons.Default.VerifiedUser,
                                contentDescription = "ECDH TOFU Fingerprint",
                                tint = when {
                                    node.isKeyChangedWarning -> RedSosAlert
                                    node.safetyFingerprint.isNotBlank() -> EmeraldMesh
                                    else -> TelemetryTextMuted
                                },
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when {
                                    node.isKeyChangedWarning -> "KEY CHANGED — VERIFY ${node.safetyFingerprint}"
                                    node.safetyFingerprint.isNotBlank() -> "TOFU • ${node.safetyFingerprint}"
                                    else -> "ECDH Key Exchange Pending"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = when {
                                        node.isKeyChangedWarning -> RedSosAlert
                                        node.safetyFingerprint.isNotBlank() -> EmeraldMesh
                                        else -> TelemetryTextSecondary
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            )
                        }
                        if (node.isKeyChangedWarning) {
                            TextButton(
                                onClick = { onVerifyFingerprint(node.id) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.heightIn(min = 36.dp)
                            ) {
                                Text(
                                    text = "TRUST NEW KEY",
                                    color = AmberRootBoost,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onCall,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldMesh),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("1-TAP CALL", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }

                    Button(
                        onClick = onMessage,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanGateway),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Chat, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ENCRYPTED DM", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
