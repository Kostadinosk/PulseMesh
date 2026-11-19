// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.viewmodel.MeshUiState
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Adaptive Top Bar (`>= 48.dp` minimum height, overflow & font-scale safe) + Live Signal-Reactive Mesh Heartbeat Ribbon:
 * - Left: Callsign + Node ID in compact bold emerald text (ellipsizes gracefully on narrow widths).
 * - Center: Status pill ("OFF-GRID" or "HYBRID") + clickable Hotspot status pill + live peer badge ("P:${state.activePeerCount}").
 * - Right: Battery % and compact "Grant Perms" chip ONLY while permissions are missing.
 * - Bottom Ribbon: Sweeping RF Mesh Heartbeat waveform reacting to live TX/RX packets & active transport color.
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
    modifier: Modifier = Modifier
) {
    val p = LocalPulsePalette.current
    val pillShape = tacticalButtonShape(state.isSimpleMode, p.isOriginal, p.isAero)

    Column(
        modifier = modifier
            .fillMaxWidth()
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
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "sos_flash",
            )
            val strobeBg = Color(
                red = 0.78f + 0.22f * sosPulse,
                green = 0.04f + 0.10f * sosPulse,
                blue = 0.14f + 0.08f * sosPulse,
                alpha = 1f
            )
            val bannerText = when {
                state.isSosActive && state.incomingSosAlert.isNotBlank() -> "⚠ SOS ACTIVE • ${state.incomingSosAlert}"
                state.incomingSosAlert.isNotBlank() -> "⚠ EMERGENCY ${state.incomingSosAlert}"
                else -> "⚠ PRIORITY SOS DISTRESS BEACON BROADCASTING"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .background(strobeBg)
                    .border(2.dp, Color.White.copy(alpha = 0.55f + 0.45f * sosPulse))
                    .clickable {
                        if (state.isSosActive) onToggleSos() else onDismissIncomingSos()
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.4f + 0.6f * sosPulse))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = bannerText,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .border(1.dp, Color.White, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (state.isSosActive) "STOP SOS" else "DISMISS",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(
                    if (p.isAero) {
                        Modifier.aeroGlassPanel(
                            p,
                            cornerRadius = 0.dp,
                            tint = if (p.isDarkAero) Color(0xE6081F33) else Color(0xEAF2FAFF)
                        )
                    } else {
                        Modifier.background(p.topBarBg)
                    }
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Left: Callsign + Node ID in compact bold emerald text
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onOpenSetupWizard() }
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = "${state.operatorCallsign} (${state.localNodeId})",
                    color = p.emerald,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Center: Status pill ("OFF-GRID" or "HYBRID") + Hotspot status pill + Live Peer Count badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val isOffGrid = !state.isInternetEnabled
                val pillColor = if (isOffGrid) p.emerald else p.amber
                val pillBg = if (isOffGrid) p.emeraldDim else p.amberDim
                Box(
                    modifier = Modifier
                        .clip(pillShape)
                        .background(pillBg)
                        .then(
                            if (p.isAero) {
                                Modifier.aeroGlossOverlay(p, pillBg, pillShape, cornerRadius = 18.dp)
                            } else {
                                Modifier
                            }
                        )
                        .border(1.dp, pillColor, pillShape)
                        .clickable { onToggleTransportMode() }
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isOffGrid) "OFF-GRID" else "HYBRID",
                        color = pillColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                val hsShort = when {
                    state.hotspotButtonText.contains("LINKED", ignoreCase = true) -> "HS:LINK"
                    state.hotspotButtonText.contains("HOST", ignoreCase = true) -> "HS:HOST"
                    state.hotspotButtonText.contains("START", ignoreCase = true) -> "HS:..."
                    else -> "HS:OFF"
                }
                val hsActive = hsShort != "HS:OFF"
                val hsColor = if (hsActive) p.cyan else p.textSecondary
                Box(
                    modifier = Modifier
                        .clip(pillShape)
                        .background(if (hsActive) p.cyanDim else p.surfaceVariant)
                        .border(1.dp, if (hsActive) p.cyan else p.border, pillShape)
                        .clickable { onAutoConnectHotspot() }
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = hsShort,
                        color = hsColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                val hasPeers = state.activePeerCount > 0
                Box(
                    modifier = Modifier
                        .clip(pillShape)
                        .background(if (hasPeers) p.emeraldDim else p.surfaceVariant)
                        .border(1.dp, if (hasPeers) p.emerald else p.border, pillShape)
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "P:${state.activePeerCount}",
                        color = if (hasPeers) p.emerald else p.textSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Right: Battery % and compact "Grant Perms" chip ONLY while permissions missing
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "⚡${state.realBatteryPercent}%",
                    color = p.emerald,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                if (!state.hasRequiredPermissions) {
                    Box(
                        modifier = Modifier
                            .clip(pillShape)
                            .background(p.amberDim)
                            .border(1.dp, p.amber, pillShape)
                            .clickable { onRequestPermissions() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Grant Perms",
                            color = p.amber,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Upgrade 2: Live Signal-Reactive Mesh Heartbeat Ribbon (hidden in Original Classic theme)
        if (!p.isOriginal) {
            MeshHeartbeatRibbon(state = state, palette = p)
        }
        HorizontalDivider(color = p.border, thickness = 1.dp)
    }
}

@Composable
private fun MeshHeartbeatRibbon(
    state: MeshUiState,
    palette: PulseMeshPalette,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mesh_heartbeat")
    val sweep by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "heartbeat_sweep",
    )
    val fastPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "packet_wave_phase",
    )

    // Spike burst energy whenever packets, messages, or audio frames are sent/received
    val packetActivityCounter = state.txFramesCount + state.rxFramesCount +
        state.packets.size + state.txAudioCount + state.rxAudioCount
    val burstEnergy = remember { Animatable(0f) }
    LaunchedEffect(packetActivityCounter) {
        if (packetActivityCounter > 0) {
            burstEnergy.snapTo(1f)
            burstEnergy.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 2100, easing = LinearEasing)
            )
        }
    }

    val hsLinked = state.hotspotState.isActive ||
        state.hotspotButtonText.contains("LINKED", ignoreCase = true) ||
        state.hotspotButtonText.contains("HOST", ignoreCase = true)
    val accent = when {
        state.isSosActive || state.incomingSosAlert.isNotBlank() -> palette.red
        hsLinked -> palette.cyan
        state.isInternetEnabled -> palette.amber
        else -> palette.emerald
    }
    val energy = if (state.isSosActive || state.isPttPressed) 1f else burstEnergy.value
    val baseAmp = if (state.activePeerCount > 0 || hsLinked) 0.52f else 0.28f
    val amplitudeScale = (baseAmp + 0.62f * energy).coerceAtMost(1.15f)
    val ribbonHeight = (14f + 7f * energy).dp
    val primaryPath = remember { Path() }
    val harmonicPath = remember { Path() }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(ribbonHeight)
            .background(palette.topBarBg),
    ) {
        val midY = size.height * 0.52f
        val maxAmp = (size.height * 0.42f) * amplitudeScale
        val steps = 96
        primaryPath.reset()
        harmonicPath.reset()

        // More waves & wider active envelope when packets are being transmitted/received!
        val envelopeWidth = 0.0045f + 0.095f * energy
        val waveFreq = 26.0 + 34.0 * energy
        val globalWaveMix = 0.38f * energy

        for (i in 0..steps) {
            val u = i.toFloat() / steps.toFloat()
            val x = u * size.width
            var dist = u - sweep
            if (dist < -0.5f) dist += 1f
            if (dist > 0.5f) dist -= 1f
            val localEnvelope = exp(-(dist * dist) / envelopeWidth)
            val carrier = sin(dist * waveFreq * PI - fastPhase * (0.4f + 1.4f * energy)).toFloat()
            val globalBurstWaves = sin(u * (14.0 + 18.0 * energy) * PI - fastPhase * 2f).toFloat() * globalWaveMix
            val combined = (carrier * localEnvelope + globalBurstWaves).coerceIn(-1.15f, 1.15f)
            val y = midY - (combined * maxAmp)
            if (i == 0) primaryPath.moveTo(x, y) else primaryPath.lineTo(x, y)

            // Secondary harmonic wave visible during active packet bursts
            if (energy > 0.05f) {
                val hWave = sin(u * 22.0 * PI + fastPhase * 1.6f).toFloat() * (0.55f * energy) * maxAmp
                val hy = midY - hWave
                if (i == 0) harmonicPath.moveTo(x, hy) else harmonicPath.lineTo(x, hy)
            }
        }

        drawLine(
            color = accent.copy(alpha = 0.18f),
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1f,
        )
        if (energy > 0.05f) {
            drawPath(
                path = harmonicPath,
                color = accent.copy(alpha = 0.32f * energy),
                style = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            // Soft outer glow during active TX/RX bursts
            drawPath(
                path = primaryPath,
                color = accent.copy(alpha = 0.28f * energy),
                style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        drawPath(
            path = primaryPath,
            color = accent.copy(alpha = 0.86f + 0.14f * energy),
            style = Stroke(width = (1.6f + 0.7f * energy).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        val pulseX = sweep * size.width
        drawCircle(
            color = accent,
            radius = (2.6f + 1.4f * energy).dp.toPx(),
            center = Offset(pulseX, midY),
        )
    }
}
