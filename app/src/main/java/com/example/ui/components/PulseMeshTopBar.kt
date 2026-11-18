package com.example.ui.components

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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
        if (state.isSosActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 30.dp)
                    .background(p.red)
                    .clickable { onToggleSos() }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "⚠ PRIORITY SOS DISTRESS BEACON ACTIVE",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "[CANCEL]",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
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
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "heartbeat_sweep",
    )

    val hsLinked = state.hotspotState.isActive ||
        state.hotspotButtonText.contains("LINKED", ignoreCase = true) ||
        state.hotspotButtonText.contains("HOST", ignoreCase = true)
    val accent = when {
        state.isSosActive -> palette.red
        hsLinked -> palette.cyan
        state.isInternetEnabled -> palette.amber
        else -> palette.emerald
    }
    val packetActivityBoost = ((state.txFramesCount + state.rxFramesCount) % 10).coerceAtLeast(1) / 10f
    val amplitudeScale = if (state.activePeerCount > 0 || hsLinked) (0.65f + 0.35f * packetActivityBoost) else 0.35f
    val path = remember { Path() }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .background(palette.topBarBg),
    ) {
        val midY = size.height * 0.55f
        val maxAmp = (size.height * 0.42f) * amplitudeScale
        val steps = 72
        path.reset()

        for (i in 0..steps) {
            val u = i.toFloat() / steps.toFloat()
            val x = u * size.width
            // Distance from sweeping RF pulse center
            var dist = u - sweep
            if (dist < -0.5f) dist += 1f
            if (dist > 0.5f) dist -= 1f
            val envelope = exp(-(dist * dist) / 0.0045f)
            val carrier = sin(dist * 26.0 * PI).toFloat()
            val y = midY - (carrier * envelope * maxAmp)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawLine(
            color = accent.copy(alpha = 0.16f),
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1f,
        )
        drawPath(
            path = path,
            color = accent.copy(alpha = 0.85f),
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        val pulseX = sweep * size.width
        drawCircle(
            color = accent,
            radius = 2.6.dp.toPx(),
            center = Offset(pulseX, midY),
        )
    }
}
