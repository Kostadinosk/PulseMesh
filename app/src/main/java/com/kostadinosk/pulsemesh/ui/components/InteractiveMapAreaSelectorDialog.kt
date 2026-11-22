// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kostadinosk.pulsemesh.ui.theme.*
import com.kostadinosk.pulsemesh.util.NativeTileMapManager
import com.kostadinosk.pulsemesh.util.OfflineRegionDownloader
import com.kostadinosk.pulsemesh.util.TacticalRegion
import java.util.Locale
import kotlin.math.*

enum class MapDetailLevel(val label: String, val minZoom: Int, val maxZoom: Int, val description: String) {
    ADAPTIVE_PYRAMID("Auto Pyramid", 10, 16, "Z(cur-1)..Z(cur+2) Web Mercator Pyramid"),
    OVERVIEW("Overview", 10, 12, "Low storage, city outlines"),
    REGIONAL("Regional", 12, 14, "Balanced, major arterials"),
    TACTICAL_STREETS("Tactical Streets", 13, 15, "High detail, street names & grid"),
    EXTREME_DETAIL("Ultra Detail", 14, 16, "Maximum resolution, footpaths & alleys")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InteractiveMapAreaSelectorDialog(
    initialLat: Double,
    initialLon: Double,
    initialZoom: Float = 13.0f,
    onDismissRequest: () -> Unit,
    onDownloadComplete: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var centerLat by remember { mutableDoubleStateOf(initialLat) }
    var centerLon by remember { mutableDoubleStateOf(initialLon) }
    var previewZoom by remember { mutableFloatStateOf(initialZoom.coerceIn(4.0f, 17.0f)) }

    var selectedDetail by remember { mutableStateOf(MapDetailLevel.ADAPTIVE_PYRAMID) }
    var selectedRadiusKm by remember { mutableDoubleStateOf(5.0) }

    val downloadProgress by OfflineRegionDownloader.downloadState.collectAsState()
    var cacheStats by remember { mutableStateOf(OfflineRegionDownloader.getDiskCacheStats(context)) }
    var dialogTileRefreshCounter by remember { mutableIntStateOf(0) }

    val effectiveZoomRange = remember(previewZoom, selectedRadiusKm, selectedDetail) {
        if (selectedDetail == MapDetailLevel.ADAPTIVE_PYRAMID) {
            OfflineRegionDownloader.calculateAdaptiveZoomRange(previewZoom.toInt(), selectedRadiusKm)
        } else {
            val cappedMax = when {
                selectedRadiusKm >= 50.0 -> minOf(selectedDetail.maxZoom, 14)
                selectedRadiusKm >= 20.0 -> minOf(selectedDetail.maxZoom, 15)
                else -> selectedDetail.maxZoom
            }
            Pair(minOf(selectedDetail.minZoom, cappedMax), cappedMax)
        }
    }

    // Live calculation of planned tiles for the selected area and zoom pyramid
    val plannedTiles = remember(centerLat, centerLon, selectedRadiusKm, effectiveZoomRange) {
        OfflineRegionDownloader.calculateTileList(
            centerLat = centerLat,
            centerLon = centerLon,
            radiusKm = selectedRadiusKm,
            minZoom = effectiveZoomRange.first,
            maxZoom = effectiveZoomRange.second
        )
    }

    val estimatedSizeMb = remember(plannedTiles.size) {
        OfflineRegionDownloader.estimateTilePyramidSizeMb(plannedTiles.size)
    }

    LaunchedEffect(downloadProgress.isCompleted) {
        if (downloadProgress.isCompleted) {
            cacheStats = OfflineRegionDownloader.getDiskCacheStats(context)
            onDownloadComplete()
        }
    }

    Dialog(
        onDismissRequest = {
            if (!downloadProgress.isRunning) onDismissRequest()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .navigationBarsPadding()
                .widthIn(max = 720.dp)
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, EmeraldMesh, RoundedCornerShape(16.dp))
                .testTag("interactive_map_area_selector_dialog"),
            color = DeepCarbon
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val mapPreviewHeight = (maxHeight * 0.42f).coerceIn(170.dp, 360.dp)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.DownloadForOffline,
                            contentDescription = null,
                            tint = EmeraldMesh,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "OFFLINE MAP AREA SELECTION",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = EmeraldMesh,
                                    fontSize = 14.sp
                                )
                            )
                            Text(
                                text = "True Web Mercator Radius Scaling • Real OSM Slippy Tile Pyramid",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = TelemetryTextSecondary,
                                    fontSize = 10.sp
                                )
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismissRequest,
                        enabled = !downloadProgress.isRunning,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TelemetryTextSecondary)
                    }
                }

                // Interactive Map View with Mathematically Accurate Web Mercator Radius Circle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(mapPreviewHeight)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TacticalBlack)
                        .border(1.dp, TacticalBorder, RoundedCornerShape(12.dp))
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    previewZoom = (previewZoom + (zoom - 1f) * 1.5f).coerceIn(4.0f, 17.0f)
                                    val n = 2.0.pow(previewZoom.toDouble())
                                    val degreesPerPixel = 360.0 / (256.0 * n)
                                    centerLon -= pan.x * degreesPerPixel
                                    centerLat += pan.y * degreesPerPixel * cos(Math.toRadians(centerLat))
                                    centerLat = centerLat.coerceIn(-85.0, 85.0)
                                    centerLon = centerLon.coerceIn(-180.0, 180.0)
                                }
                            }
                    ) {
                        @Suppress("UNUSED_VARIABLE")
                        val redraw = dialogTileRefreshCounter

                        val viewW = size.width
                        val viewH = size.height
                        val cX = viewW / 2f
                        val cY = viewH / 2f

                        val intZ = previewZoom.toInt()
                        val (curTileX, curTileY) = NativeTileMapManager.latLonToTileCoords(centerLat, centerLon, intZ)
                        val subScale = 2.0.pow((previewZoom - intZ).toDouble()).toFloat()
                        val tileSizePx = 256f * subScale

                        val tilesX = ceil(viewW / tileSizePx).toInt() + 2
                        val tilesY = ceil(viewH / tileSizePx).toInt() + 2
                        val minTX = (curTileX - tilesX / 2).toInt()
                        val maxTX = (curTileX + tilesX / 2).toInt()
                        val minTY = (curTileY - tilesY / 2).toInt()
                        val maxTY = (curTileY + tilesY / 2).toInt()

                        val maxWorld = (1 shl intZ) - 1

                        for (tx in minTX..maxTX) {
                            for (ty in minTY..maxTY) {
                                if (tx in 0..maxWorld && ty in 0..maxWorld) {
                                    val tileBmp = NativeTileMapManager.getCachedTile(context, intZ, tx, ty)
                                    val px = cX + ((tx - curTileX) * tileSizePx).toFloat()
                                    val py = cY + ((ty - curTileY) * tileSizePx).toFloat()

                                    if (tileBmp != null) {
                                        drawImage(
                                            image = tileBmp,
                                            dstOffset = androidx.compose.ui.unit.IntOffset(px.toInt(), py.toInt()),
                                            dstSize = androidx.compose.ui.unit.IntSize(ceil(tileSizePx).toInt(), ceil(tileSizePx).toInt())
                                        )
                                        // Subtle emerald border around cached disk tiles
                                        drawRect(
                                            color = EmeraldMesh.copy(alpha = 0.45f),
                                            topLeft = Offset(px, py),
                                            size = androidx.compose.ui.geometry.Size(tileSizePx, tileSizePx),
                                            style = Stroke(width = 1.2f)
                                        )
                                    } else {
                                        drawRect(
                                            color = TacticalSurfaceVariant.copy(alpha = 0.4f),
                                            topLeft = Offset(px, py),
                                            size = androidx.compose.ui.geometry.Size(tileSizePx, tileSizePx)
                                        )
                                        drawRect(
                                            color = TacticalBorder.copy(alpha = 0.3f),
                                            topLeft = Offset(px, py),
                                            size = androidx.compose.ui.geometry.Size(tileSizePx, tileSizePx),
                                            style = Stroke(width = 1f)
                                        )
                                        if (OfflineRegionDownloader.isInternetEnabled || OfflineRegionDownloader.hasGatewayMapAccess()) {
                                            NativeTileMapManager.fetchTileAsync(
                                                context = context,
                                                z = intZ,
                                                x = tx,
                                                y = ty,
                                                scope = coroutineScope,
                                                isInternetEnabled = OfflineRegionDownloader.isInternetEnabled
                                            ) { _, _, _, _ ->
                                                dialogTileRefreshCounter++
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Mathematically Accurate Web Mercator Radius Circle on Map Canvas:
                        // Shrinks by 2x for each zoom-out step; expands by 2x for each zoom-in step!
                        val realRadiusPx = OfflineRegionDownloader.calculateRealRadiusPixels(
                            centerLat = centerLat,
                            zoom = previewZoom.toDouble(),
                            selectedRadiusKm = selectedRadiusKm
                        )
                        val safeCanvasRadiusPx = realRadiusPx.coerceIn(2f, 100_000f)

                        drawCircle(
                            color = EmeraldMesh.copy(alpha = 0.16f),
                            radius = safeCanvasRadiusPx,
                            center = Offset(cX, cY)
                        )
                        drawCircle(
                            color = EmeraldMesh,
                            radius = safeCanvasRadiusPx,
                            center = Offset(cX, cY),
                            style = Stroke(width = 2.dp.toPx())
                        )

                        // Center crosshair
                        drawLine(
                            color = AmberRootBoost,
                            start = Offset(cX - 16.dp.toPx(), cY),
                            end = Offset(cX + 16.dp.toPx(), cY),
                            strokeWidth = 2.dp.toPx()
                        )
                        drawLine(
                            color = AmberRootBoost,
                            start = Offset(cX, cY - 16.dp.toPx()),
                            end = Offset(cX, cY + 16.dp.toPx()),
                            strokeWidth = 2.dp.toPx()
                        )
                        drawCircle(
                            color = AmberRootBoost,
                            radius = 4.dp.toPx(),
                            center = Offset(cX, cY)
                        )
                    }

                    // Floating GPS reset and zoom controls
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                centerLat = initialLat
                                centerLon = initialLon
                                previewZoom = 13.0f
                            },
                            containerColor = DeepCarbon,
                            contentColor = EmeraldMesh,
                            shape = CircleShape,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.MyLocation, contentDescription = "Center GPS", modifier = Modifier.size(18.dp))
                        }

                        SmallFloatingActionButton(
                            onClick = { previewZoom = (previewZoom + 1f).coerceAtMost(17.0f) },
                            containerColor = DeepCarbon,
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Zoom In", modifier = Modifier.size(18.dp))
                        }

                        SmallFloatingActionButton(
                            onClick = { previewZoom = (previewZoom - 1f).coerceAtLeast(4.0f) },
                            containerColor = DeepCarbon,
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Zoom Out", modifier = Modifier.size(18.dp))
                        }
                    }

                    // Floating Center Coordinates & Ground Resolution Overlay
                    Surface(
                        color = DeepCarbon.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TacticalBorder),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    ) {
                        val mpp = OfflineRegionDownloader.calculateMetersPerPixel(centerLat, previewZoom.toDouble())
                        Text(
                            text = "TARGET: %.4f, %.4f • r=%.0fkm • Z%.1f (%.1f m/px)".format(
                                centerLat,
                                centerLon,
                                selectedRadiusKm,
                                previewZoom,
                                mpp
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = EmeraldMesh,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }

                // Detail Resolution Selection
                Text(
                    text = "PYRAMID ZOOM DEPTH (Z${effectiveZoomRange.first}-Z${effectiveZoomRange.second}):",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF8A99AD),
                        fontSize = 10.sp
                    )
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MapDetailLevel.entries.forEach { level ->
                        val isSel = level == selectedDetail
                        Box(
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) EmeraldMeshDim else DeepCarbon)
                                .border(1.dp, if (isSel) EmeraldMesh else TacticalBorder, RoundedCornerShape(6.dp))
                                .clickable(enabled = !downloadProgress.isRunning) {
                                    selectedDetail = level
                                }
                                .padding(vertical = 6.dp, horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = level.label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSel) EmeraldMesh else TelemetryTextSecondary,
                                        fontSize = 9.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val zText = if (level == MapDetailLevel.ADAPTIVE_PYRAMID) {
                                    "Z${effectiveZoomRange.first}-Z${effectiveZoomRange.second}"
                                } else {
                                    "Z${level.minZoom}-Z${level.maxZoom}"
                                }
                                Text(
                                    text = zText,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (isSel) Color.White else TelemetryTextMuted,
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                            }
                        }
                    }
                }

                // Radius Selection Chips: 1 km, 5 km, 10 km, 20 km, 50 km
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "RADIUS:",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8A99AD),
                            fontSize = 10.sp
                        ),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )

                    OfflineRegionDownloader.SUPPORTED_RADII_KM.forEach { r ->
                        val isSel = selectedRadiusKm == r
                        Box(
                            modifier = Modifier
                                .heightIn(min = 34.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSel) EmeraldMeshDim else DeepCarbon)
                                .border(1.dp, if (isSel) EmeraldMesh else TacticalBorder, RoundedCornerShape(4.dp))
                                .clickable(enabled = !downloadProgress.isRunning) { selectedRadiusKm = r }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("radius_chip_${r.toInt()}km"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "%.0f km".format(r),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (isSel) EmeraldMesh else TelemetryTextSecondary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            )
                        }
                    }
                }

                // Stats & Disk Cache Status (18 KB per tile estimate + stored MB)
                Surface(
                    color = TacticalSurfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TacticalBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Pyramid: %d tiles (~%.2f MB @ 18 KB/tile)",
                                    plannedTiles.size,
                                    estimatedSizeMb
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = EmeraldMesh,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Disk Cache: %d tiles (%.2f MB stored in filesDir/osm_tiles)",
                                    cacheStats.first,
                                    cacheStats.second / (1024.0 * 1024.0)
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Color(0xFF8A99AD),
                                    fontSize = 9.sp
                                )
                            )
                        }

                        Text(
                            text = "[CLEAR CACHE]",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = RedSosAlert,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp
                            ),
                            modifier = Modifier.clickable(enabled = !downloadProgress.isRunning) {
                                OfflineRegionDownloader.clearDiskCache(context)
                                cacheStats = OfflineRegionDownloader.getDiskCacheStats(context)
                            }
                        )
                    }
                }

                // Progress Indicator (if active)
                if (downloadProgress.isRunning || downloadProgress.isCompleted || downloadProgress.error != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { downloadProgress.percent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = if (downloadProgress.error != null) RedSosAlert else EmeraldMesh,
                            trackColor = TacticalSurfaceVariant
                        )
                        Text(
                            text = downloadProgress.statusMessage,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    downloadProgress.error != null -> RedSosAlert
                                    downloadProgress.isCompleted -> EmeraldMesh
                                    else -> AmberRootBoost
                                },
                                fontSize = 10.sp
                            )
                        )
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = onDismissRequest,
                        enabled = !downloadProgress.isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text("CLOSE", color = TelemetryTextSecondary)
                    }

                    if (downloadProgress.isRunning) {
                        Button(
                            onClick = { OfflineRegionDownloader.cancelDownload() },
                            colors = ButtonDefaults.buttonColors(containerColor = RedSosAlert),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(2f)
                                .heightIn(min = 48.dp)
                        ) {
                            Text("CANCEL DOWNLOAD", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = {
                                val region = TacticalRegion(
                                    id = "custom_selection",
                                    name = "Selected Area (%.2f, %.2f)".format(centerLat, centerLon),
                                    centerLat = centerLat,
                                    centerLon = centerLon,
                                    radiusKm = selectedRadiusKm,
                                    description = "Custom user-selected offline area"
                                )
                                OfflineRegionDownloader.startDownload(
                                    context = context,
                                    region = region,
                                    actualCenterLat = centerLat,
                                    actualCenterLon = centerLon,
                                    minZoom = effectiveZoomRange.first,
                                    maxZoom = effectiveZoomRange.second,
                                    scope = coroutineScope,
                                    onFinished = {
                                        cacheStats = OfflineRegionDownloader.getDiskCacheStats(context)
                                        onDownloadComplete()
                                    }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldMesh),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(2f)
                                .heightIn(min = 48.dp)
                                .testTag("confirm_download_tiles_button")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            val gwPeerName = if (!OfflineRegionDownloader.isInternetEnabled) OfflineRegionDownloader.gatewayPeerCallsign?.invoke() else null
                            Text(
                                text = if (!gwPeerName.isNullOrBlank()) {
                                    String.format(
                                        Locale.US,
                                        "DOWNLOAD VIA GATEWAY %s (%d TILES)",
                                        gwPeerName.uppercase(),
                                        plannedTiles.size
                                    )
                                } else {
                                    String.format(
                                        Locale.US,
                                        "DOWNLOAD OFFLINE AREA (%d TILES • %.1f MB)",
                                        plannedTiles.size,
                                        estimatedSizeMb
                                    )
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                }
            }
        }
    }
}
