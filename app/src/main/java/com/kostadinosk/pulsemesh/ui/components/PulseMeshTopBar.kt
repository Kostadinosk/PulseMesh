// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.ui.theme.LocalPulseTokens
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.util.AppStrings
import com.kostadinosk.pulsemesh.util.ThemeBrightness
import com.kostadinosk.pulsemesh.util.ThemePreset
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * PulseMesh v2.0 Compact 1-Row Practical Top Bar:
 * - Left: Interactive Mesh Status Pill (live transport dot, callsign/mode, peer count -> taps open Telemetry Drawer)
 * - Right: Permissions button (only when missing), 1-tap Connect & Share QR button, and SOS Emergency Kit trigger
 * - Bottom: Subtle 3dp signal-reactive mesh heartbeat ribbon
 */
@Composable
fun PulseMeshTopBar(
    state: MeshUiState,
    onToggleSimpleMode: () -> Unit = {},
    onToggleTransportMode: () -> Unit = {},
    onRequestPermissions: () -> Unit = {},
    onOpenSetupWizard: () -> Unit = {},
    onToggleSos: () -> Unit = {},
    onDismissIncomingSos: () -> Unit = {},
    onAutoConnectHotspot: () -> Unit = {},
    onOpenTelemetryDrawer: () -> Unit = onToggleTransportMode,
    onOpenConnectHub: () -> Unit = onAutoConnectHotspot,
    onOpenSosDrawer: () -> Unit = onToggleSos,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(p.topBarBg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
    ) {
        val showSosBanner = state.isSosActive || state.incomingSosAlert.isNotBlank()
        if (showSosBanner) {
            val sosTransition = rememberInfiniteTransition(label = "sos_strobe")
            val sosPulse by sosTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 480, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "sos_flash"
            )
            val strobeBg = Color(
                red = 0.78f + 0.22f * sosPulse,
                green = 0.04f + 0.10f * sosPulse,
                blue = 0.14f + 0.08f * sosPulse,
                alpha = 1f
            )
            val bannerText = when {
                state.isSosActive && state.incomingSosAlert.isNotBlank() ->
                    AppStrings.tr(state.appLanguage, "SOS ACTIVE • ${state.incomingSosAlert}", "ΕΝΕΡΓΟ SOS • ${state.incomingSosAlert}")
                state.incomingSosAlert.isNotBlank() ->
                    AppStrings.tr(state.appLanguage, "EMERGENCY ${state.incomingSosAlert}", "ΕΚΤΑΚΤΟ ${state.incomingSosAlert}")
                else ->
                    AppStrings.tr(state.appLanguage, "SOS DISTRESS BEACON BROADCASTING", "ΕΚΠΟΜΠΗ ΣΗΜΑΤΟΣ ΚΙΝΔΥΝΟΥ SOS")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .background(strobeBg)
                    .clickable {
                        if (state.isSosActive) onOpenSosDrawer() else onDismissIncomingSos()
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("top_bar_sos_banner"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "SOS",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = bannerText,
                        color = Color.White,
                        fontFamily = tokens.labelFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = if (state.isSosActive) AppStrings.tr(state.appLanguage, "MANAGE", "ΕΛΕΓΧΟΣ") else AppStrings.tr(state.appLanguage, "DISMISS", "ΚΛΕΙΣΙΜΟ"),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }
        }

        // Compact 1-row main bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val statusColor = when {
                state.isSosActive -> tokens.statusDanger
                state.isInternetEnabled -> tokens.statusMesh
                state.activePeerCount > 0 -> tokens.statusOnline
                else -> tokens.statusWarning
            }
            val modeLabel = when {
                state.isInternetEnabled -> AppStrings.tr(state.appLanguage, "Hybrid", "Υβριδικό")
                state.webPortalStatus.isRunning -> AppStrings.tr(state.appLanguage, "Hotspot", "Hotspot")
                else -> AppStrings.tr(state.appLanguage, "Off-Grid", "Εκτός Δικτ.")
            }

            // Interactive Mesh Status Pill -> Opens Tap-to-Expand Telemetry Drawer
            Surface(
                color = p.surface,
                shape = tokens.pillShape,
                border = BorderStroke(tokens.borderWidth, statusColor.copy(alpha = 0.45f)),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(tokens.pillShape)
                    .clickable { onOpenTelemetryDrawer() }
                    .testTag("top_bar_mesh_status_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = state.operatorCallsign.ifBlank { "PulseMesh" },
                            color = p.textPrimary,
                            fontFamily = tokens.labelFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "$modeLabel • ${state.activePeerCount} ${AppStrings.tr(state.appLanguage, "peers", "κόμβοι")}${if (tokens.showHudTelemetryBadges) " • ${state.realBatteryPercent}%" else ""}",
                            color = p.textSecondary,
                            fontFamily = tokens.labelFontFamily,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = "Mesh Telemetry",
                        tint = p.textMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right compact action cluster
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!state.hasRequiredPermissions && state.showPermissionsBanner) {
                    Button(
                        onClick = onRequestPermissions,
                        shape = tokens.pillShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.statusWarning,
                            contentColor = Color.Black
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("top_bar_grant_perms_btn")
                    ) {
                        Text(
                            text = AppStrings.tr(state.appLanguage, "Grant", "Άδειες"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }

                // 1-Tap Connect & Share Hub (QR / NFC / iPhone Bridge / APK / LoRa)
                Surface(
                    color = p.surfaceVariant,
                    shape = tokens.pillShape,
                    border = BorderStroke(tokens.borderWidth, p.border),
                    modifier = Modifier.size(36.dp)
                ) {
                    IconButton(
                        onClick = onOpenConnectHub,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("top_bar_connect_hub_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCode2,
                            contentDescription = AppStrings.tr(state.appLanguage, "Connect & Share", "Σύνδεση & Κοινοποίηση"),
                            tint = tokens.accentPrimary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                // 1-Tap SOS Emergency Kit Trigger
                Surface(
                    color = if (state.isSosActive) tokens.statusDanger else tokens.statusDanger.copy(alpha = 0.14f),
                    shape = tokens.pillShape,
                    border = BorderStroke(1.dp, tokens.statusDanger.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .height(36.dp)
                        .clip(tokens.pillShape)
                        .clickable { onOpenSosDrawer() }
                        .testTag("top_bar_sos_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "SOS",
                            color = if (state.isSosActive) Color.White else tokens.statusDanger,
                            fontFamily = tokens.labelFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // Subtle live RF Mesh Heartbeat Ribbon
        val packetEnergy = remember { Animatable(0.2f) }
        val totalPackets = state.txFramesCount + state.rxFramesCount
        LaunchedEffect(totalPackets) {
            if (totalPackets > 0) {
                packetEnergy.snapTo(1f)
                packetEnergy.animateTo(0.18f, animationSpec = tween(durationMillis = 850))
            }
        }
        val sweepTransition = rememberInfiniteTransition(label = "mesh_ribbon")
        val sweepPhase by sweepTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "ribbon_phase"
        )
        val ribbonColor = when {
            state.isSosActive -> tokens.statusDanger
            state.isInternetEnabled -> tokens.statusMesh
            else -> tokens.accentPrimary
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(p.border.copy(alpha = 0.35f))
        ) {
            val w = size.width
            val h = size.height
            val cy = h / 2f
            val path = Path()
            val steps = 48
            val amp = (h * 0.45f) * packetEnergy.value
            for (i in 0..steps) {
                val t = i.toFloat() / steps.toFloat()
                val dist = t - sweepPhase
                val env = exp((-dist * dist * 42f).toDouble()).toFloat()
                val y = cy + sin((t * 16.0 * PI)).toFloat() * amp * env
                val x = t * w
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = ribbonColor.copy(alpha = 0.85f),
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawCircle(
                color = ribbonColor,
                radius = 1.8.dp.toPx(),
                center = Offset(sweepPhase * w, cy)
            )
        }
    }
}

@Preview(name = "TopBar Practical Tactical Dark", showBackground = true)
@Composable
fun PreviewTopBarPracticalDark() {
    PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
        PulseMeshTopBar(
            state = MeshUiState(
                operatorCallsign = "Alpha-01",
                activePeerCount = 4,
                hasRequiredPermissions = true
            )
        )
    }
}

@Preview(name = "TopBar Field Ops HUD", showBackground = true)
@Composable
fun PreviewTopBarFieldOps() {
    PulseMeshTheme(themePreset = ThemePreset.FIELD_OPS_HUD, themeBrightness = ThemeBrightness.DARK) {
        PulseMeshTopBar(
            state = MeshUiState(
                operatorCallsign = "Ranger-7",
                activePeerCount = 7,
                isInternetEnabled = true,
                realBatteryPercent = 78,
                hasRequiredPermissions = false
            )
        )
    }
}

@Preview(name = "TopBar SOS Active", showBackground = true)
@Composable
fun PreviewTopBarSosActive() {
    PulseMeshTheme(themePreset = ThemePreset.CLEAN_MESSENGER, themeBrightness = ThemeBrightness.LIGHT) {
        PulseMeshTopBar(
            state = MeshUiState(
                operatorCallsign = "Maya",
                activePeerCount = 2,
                isSosActive = true,
                hasRequiredPermissions = true
            )
        )
    }
}
