// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class AeroTabIconKind {
    MAP,
    CHATS,
    VOICE,
    SETTINGS
}

/**
 * Nostalgic 2000s Frutiger Aero glossy orb navigation icon rendered cleanly via lightweight Canvas vector primitives.
 * Zero bitmap bloat, crisp at any DPI, and adapts smoothly between Light Aero & Dark Aero.
 */
@Composable
fun AeroNavOrbIcon(
    kind: AeroTabIconKind,
    selected: Boolean,
    accentColor: Color,
    isDarkAero: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
) {
    val baseTint = if (selected) {
        accentColor
    } else if (isDarkAero) {
        Color(0xFF285878)
    } else {
        Color(0xFF6BAED6)
    }
    val secondaryTint = when (kind) {
        AeroTabIconKind.MAP -> if (selected) Color(0xFF1CE896) else Color(0xFF3DA5D9)
        AeroTabIconKind.CHATS -> if (selected) Color(0xFF2ED4FF) else Color(0xFF4CA5D4)
        AeroTabIconKind.VOICE -> if (selected) Color(0xFF19E68C) else Color(0xFF3DA5D9)
        AeroTabIconKind.SETTINGS -> if (selected) Color(0xFF43C6FF) else Color(0xFF5D8AA8)
    }

    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val r = minOf(w, h) / 2f
        val center = Offset(w / 2f, h / 2f)

        // Subtle outer crystal halo when selected
        if (selected) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(baseTint.copy(alpha = 0.45f), Color.Transparent),
                    center = center,
                    radius = r * 1.18f,
                ),
                radius = r * 1.18f,
                center = center,
            )
        }

        // 3D glossy Aqua/Vista sphere body
        drawCircle(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.00f to lerp(baseTint, Color.White, 0.56f),
                    0.46f to baseTint,
                    0.52f to lerp(baseTint, Color.Black, 0.20f),
                    1.00f to lerp(secondaryTint, Color.White, 0.25f),
                )
            ),
            radius = r * 0.90f,
            center = center,
        )

        // Outer glass rim
        drawCircle(
            color = if (selected) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.58f),
            radius = r * 0.90f,
            center = center,
            style = Stroke(width = 1.25.dp.toPx()),
        )

        // Nostalgic custom motif inside the orb
        val glyphStroke = Stroke(width = 1.45.dp.toPx(), cap = StrokeCap.Round)
        val fg = Color.White.copy(alpha = if (selected) 0.98f else 0.88f)
        when (kind) {
            AeroTabIconKind.MAP -> {
                // Classic 2000s glossy Earth globe meridians + pulsing radar pin dot
                val globeR = r * 0.50f
                drawCircle(color = fg, radius = globeR, center = center, style = glyphStroke)
                drawOval(
                    color = fg.copy(alpha = 0.85f),
                    topLeft = Offset(center.x - globeR * 0.46f, center.y - globeR),
                    size = Size(globeR * 0.92f, globeR * 2f),
                    style = Stroke(width = 1.05.dp.toPx()),
                )
                drawLine(
                    color = fg.copy(alpha = 0.85f),
                    start = Offset(center.x - globeR * 0.92f, center.y),
                    end = Offset(center.x + globeR * 0.92f, center.y),
                    strokeWidth = 1.05.dp.toPx(),
                )
                drawCircle(
                    color = Color(0xFFAEFFD8),
                    radius = r * 0.14f,
                    center = Offset(center.x + globeR * 0.34f, center.y - globeR * 0.28f),
                )
            }

            AeroTabIconKind.CHATS -> {
                // Iconic iChat / MSN glossy bubble silhouette
                val bw = r * 1.02f
                val bh = r * 0.72f
                val left = center.x - bw / 2f
                val top = center.y - bh * 0.56f
                drawRoundRect(
                    color = fg,
                    topLeft = Offset(left, top),
                    size = Size(bw, bh),
                    cornerRadius = CornerRadius(bh * 0.45f, bh * 0.45f),
                )
                val tail = Path().apply {
                    moveTo(left + bw * 0.25f, top + bh * 0.85f)
                    lineTo(left + bw * 0.15f, top + bh * 1.24f)
                    lineTo(left + bw * 0.52f, top + bh * 0.90f)
                    close()
                }
                drawPath(path = tail, color = fg)
                // Three crisp aqua typing dots inside the bubble
                val dotColor = lerp(baseTint, Color(0xFF062C46), 0.35f)
                val dotY = top + bh * 0.50f
                drawCircle(color = dotColor, radius = r * 0.07f, center = Offset(center.x - r * 0.22f, dotY))
                drawCircle(color = dotColor, radius = r * 0.07f, center = Offset(center.x, dotY))
                drawCircle(color = dotColor, radius = r * 0.07f, center = Offset(center.x + r * 0.22f, dotY))
            }

            AeroTabIconKind.VOICE -> {
                // Crystal microphone capsule + cradle arc + stand
                val micW = r * 0.42f
                val micH = r * 0.64f
                drawRoundRect(
                    color = fg,
                    topLeft = Offset(center.x - micW / 2f, center.y - micH * 0.64f),
                    size = Size(micW, micH),
                    cornerRadius = CornerRadius(micW / 2f, micW / 2f),
                )
                drawArc(
                    color = fg,
                    startAngle = 15f,
                    sweepAngle = 150f,
                    useCenter = false,
                    topLeft = Offset(center.x - r * 0.38f, center.y - r * 0.24f),
                    size = Size(r * 0.76f, r * 0.58f),
                    style = glyphStroke,
                )
                drawLine(
                    color = fg,
                    start = Offset(center.x, center.y + r * 0.34f),
                    end = Offset(center.x, center.y + r * 0.54f),
                    strokeWidth = 1.4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = fg,
                    start = Offset(center.x - r * 0.22f, center.y + r * 0.54f),
                    end = Offset(center.x + r * 0.22f, center.y + r * 0.54f),
                    strokeWidth = 1.4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            AeroTabIconKind.SETTINGS -> {
                // Brushed chrome/crystal 6-tooth Aero gear
                val gearOuter = r * 0.48f
                val gearInner = r * 0.21f
                drawCircle(
                    color = fg,
                    radius = gearOuter * 0.74f,
                    center = center,
                    style = Stroke(width = 2.6.dp.toPx()),
                )
                for (i in 0 until 6) {
                    val ang = (i * 60.0) * (Math.PI / 180.0)
                    val sx = center.x + (gearOuter * 0.72f * kotlin.math.cos(ang)).toFloat()
                    val sy = center.y + (gearOuter * 0.72f * kotlin.math.sin(ang)).toFloat()
                    val ex = center.x + (gearOuter * 1.06f * kotlin.math.cos(ang)).toFloat()
                    val ey = center.y + (gearOuter * 1.06f * kotlin.math.sin(ang)).toFloat()
                    drawLine(
                        color = fg,
                        start = Offset(sx, sy),
                        end = Offset(ex, ey),
                        strokeWidth = 2.1.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                drawCircle(color = fg, radius = gearInner, center = center, style = glyphStroke)
            }
        }

        // Top 2000s glossy specular dome reflection
        drawOval(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xCCFFFFFF), Color(0x0CFFFFFF))
            ),
            topLeft = Offset(center.x - r * 0.60f, center.y - r * 0.82f),
            size = Size(r * 1.20f, r * 0.70f),
        )
    }
}

/**
 * Reusable glossy 3D Aero orb badge wrapping any vector icon for Settings, Wizard steps, and Voice headers.
 */
@Composable
fun AeroGlossBadgeIcon(
    imageVector: ImageVector,
    accentColor: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    badgeSize: Dp = 36.dp,
    iconSize: Dp = 19.dp,
) {
    Box(
        modifier = modifier
            .size(badgeSize)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to lerp(accentColor, Color.White, 0.56f),
                        0.48f to accentColor,
                        0.52f to lerp(accentColor, Color.Black, 0.22f),
                        1.00f to lerp(accentColor, Color(0xFF9DF6FF), 0.30f),
                    )
                ),
                radius = r * 0.94f,
                center = center,
            )
            drawOval(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xC8FFFFFF), Color(0x10FFFFFF))
                ),
                topLeft = Offset(center.x - r * 0.62f, center.y - r * 0.84f),
                size = Size(r * 1.24f, r * 0.68f),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = r * 0.94f,
                center = center,
                style = Stroke(width = 1.15.dp.toPx()),
            )
        }
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(iconSize),
        )
    }
}
