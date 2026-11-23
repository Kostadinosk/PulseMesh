// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.map

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-flavor MapLibre Native surface with synchronized camera + Dark/Topo/Satellite/Radar styles.
 * Automatically renders the pure-Canvas grid fallback under Robolectric or if MapLibre GL cannot
 * initialize on headless environments.
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
    val isRobolectric = remember {
        Build.FINGERPRINT.equals("robolectric", ignoreCase = true) ||
            Build.MODEL.contains("robolectric", ignoreCase = true)
    }

    val mapLibreReady = remember(context) {
        if (isRobolectric) false
        else runCatching {
            MapLibre.getInstance(context.applicationContext)
            true
        }.getOrDefault(false)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Base offline grid / Radar layer underneath or fallback if GL/network is unavailable
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bgColor = when (layerStyle) {
                MapLayerStyle.DARK -> Color(0xFF0A0F18)
                MapLayerStyle.TOPO -> Color(0xFF131D17)
                MapLayerStyle.SATELLITE -> Color(0xFF0D141F)
                MapLayerStyle.RADAR -> Color(0xFF061310)
            }
            drawRect(bgColor)
            val gridColor = when (layerStyle) {
                MapLayerStyle.DARK -> Color(0xFF1A2536)
                MapLayerStyle.TOPO -> Color(0xFF233A2C)
                MapLayerStyle.SATELLITE -> Color(0xFF1E2D42)
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
            }
        }

        if (mapLibreReady && layerStyle != MapLayerStyle.RADAR) {
            val lifecycleOwner = LocalLifecycleOwner.current
            var mapController by remember { mutableStateOf<MapLibreMap?>(null) }
            val mapView = remember {
                runCatching {
                    MapView(context).apply {
                        onCreate(null)
                    }
                }.getOrNull()
            }

            if (mapView != null) {
                DisposableEffect(lifecycleOwner, mapView) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> runCatching { mapView.onStart() }
                            Lifecycle.Event.ON_RESUME -> runCatching { mapView.onResume() }
                            Lifecycle.Event.ON_PAUSE -> runCatching { mapView.onPause() }
                            Lifecycle.Event.ON_STOP -> runCatching { mapView.onStop() }
                            Lifecycle.Event.ON_DESTROY -> runCatching { mapView.onDestroy() }
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        runCatching { mapView.onDestroy() }
                    }
                }

                LaunchedEffect(mapView, layerStyle, isInternetEnabled) {
                    runCatching {
                        mapView.getMapAsync { mlMap ->
                            mapController = mlMap
                            mlMap.uiSettings.setAllGesturesEnabled(false)
                            mlMap.uiSettings.isCompassEnabled = false
                            mlMap.uiSettings.isLogoEnabled = false
                            mlMap.uiSettings.isAttributionEnabled = false
                            val styleJson = buildLocalVectorOrRasterStyleJson(layerStyle, isInternetEnabled)
                            mlMap.setStyle(Style.Builder().fromJson(styleJson))
                        }
                    }
                }

                LaunchedEffect(mapController, centerLat, centerLon, zoomLevel, bearingDeg, compassRotateEnabled) {
                    val mlMap = mapController ?: return@LaunchedEffect
                    runCatching {
                        mlMap.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(centerLat, centerLon))
                            .zoom((zoomLevel - 1f).coerceIn(1f, 20f).toDouble())
                            .bearing(if (compassRotateEnabled) bearingDeg.toDouble() else 0.0)
                            .build()
                    }
                }

                AndroidView(
                    factory = { mapView },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private fun buildLocalVectorOrRasterStyleJson(style: MapLayerStyle, online: Boolean): String {
    val bgHex = when (style) {
        MapLayerStyle.DARK -> "#0A0F18"
        MapLayerStyle.TOPO -> "#131D17"
        MapLayerStyle.SATELLITE -> "#0D141F"
        MapLayerStyle.RADAR -> "#061310"
    }
    if (!online || style == MapLayerStyle.RADAR) {
        return """{"version":8,"name":"PulseMeshOffline","sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"$bgHex"}}]}"""
    }
    val tileUrl = when (style) {
        MapLayerStyle.DARK -> "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png"
        MapLayerStyle.TOPO -> "https://a.tile.opentopomap.org/{z}/{x}/{y}.png"
        MapLayerStyle.SATELLITE -> "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        MapLayerStyle.RADAR -> return """{"version":8,"name":"PulseMeshRadar","sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"$bgHex"}}]}"""
    }
    return """
    {
      "version": 8,
      "name": "PulseMesh-${style.name}",
      "sources": {
        "osm-raster": {
          "type": "raster",
          "tiles": ["$tileUrl"],
          "tileSize": 256
        }
      },
      "layers": [
        {"id": "bg", "type": "background", "paint": {"background-color": "$bgHex"}},
        {"id": "raster-tiles", "type": "raster", "source": "osm-raster", "paint": {"raster-opacity": 0.96}}
      ]
    }
    """.trimIndent()
}
