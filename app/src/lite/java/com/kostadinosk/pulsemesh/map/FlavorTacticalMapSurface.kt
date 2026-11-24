// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lite-flavor lightweight raster + vector radar grid background surface.
 * Zero heavy native MapLibre dependencies so Lite APK stays tiny (~3 MB).
 */
@Composable
fun FlavorTacticalMapSurface(
    centerLat: Double,
    centerLon: Double,
    zoomLevel: Float,
    bearingDeg: Float,
    compassRotateEnabled: Boolean,
    layerStyle: MapLayerStyle,
    isInternetEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val packCount = OfflineMapPackManager.listInstalledPacks(context).size

    Canvas(modifier = modifier.fillMaxSize()) {
        val bgColor = when (layerStyle) {
            MapLayerStyle.STANDARD -> Color(0xFFECE8DF)
            MapLayerStyle.TOPO -> Color(0xFFE6ECE3)
            MapLayerStyle.SATELLITE -> Color(0xFF1C2A3A)
            MapLayerStyle.RADAR -> Color(0xFF061310)
        }
        drawRect(bgColor)

        val gridColor = when (layerStyle) {
            MapLayerStyle.STANDARD -> Color(0xFFD6D0C4)
            MapLayerStyle.TOPO -> Color(0xFFCBD8C7)
            MapLayerStyle.SATELLITE -> Color(0xFF283C52)
            MapLayerStyle.RADAR -> Color(0xFF0F3D2E)
        }

        val step = 64.dp.toPx()
        var x = 0f
        while (x <= size.width) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }

        if (layerStyle == MapLayerStyle.RADAR) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val maxR = minOf(size.width, size.height) * 0.45f
            for (ring in 1..4) {
                drawCircle(
                    color = Color(0xFF00E676).copy(alpha = 0.22f),
                    radius = maxR * (ring / 4f),
                    center = c,
                    style = Stroke(width = 1.2f)
                )
            }
            if (compassRotateEnabled) {
                val rad = Math.toRadians((bearingDeg - 90f).toDouble())
                drawLine(
                    color = Color(0xFF00E676).copy(alpha = 0.55f),
                    start = c,
                    end = Offset(
                        c.x + (cos(rad) * maxR).toFloat(),
                        c.y + (sin(rad) * maxR).toFloat()
                    ),
                    strokeWidth = 2f
                )
            }
        } else if (packCount > 0 || isInternetEnabled) {
            // Subtle horizon accent bar
            drawLine(
                color = Color(0xFF00B0FF).copy(alpha = 0.14f),
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1f
            )
        }
    }
}
