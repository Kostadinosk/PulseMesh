// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.LocationShareMode
import com.example.model.MeshNode
import com.example.model.TacticalWaypoint
import com.example.model.WaypointType
import com.example.service.LiveMeshNetworkService
import com.example.service.TacticalSensorService
import com.example.ui.theme.*
import com.example.util.NativeTileMapManager
import com.example.util.OfflineRegionDownloader
import kotlin.math.*

/**
 * 100% Native Jetpack Compose Interactive Slippy Street Map.
 * Renders standard 256x256 Web Mercator tiles with persistent disk caching in context.filesDir/osm_tiles/$z/$x/$y.png.
 * Supports smooth pan, pinch-to-zoom (3.0f to 19.0f), +/- buttons, "Center on My GPS",
 * true Web Mercator geographic radius boundary scaling, live compass bearing arrow, barometric altitude ASL,
 * and shared tactical map pins (RALLY, HAZARD, SUPPLY, SOS) on long-press.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TacticalMap(
    userLat: Double?,
    userLon: Double?,
    isGpsLocked: Boolean,
    nodes: List<MeshNode>,
    selectedNode: MeshNode?,
    onSelectNode: (MeshNode?) -> Unit,
    onSetManualLocation: (Double, Double) -> Unit,
    onRequestGps: () -> Unit,
    isInternetEnabled: Boolean = false,
    deviceHeadingDeg: Float = 0f,
    barometricAltitudeMeters: Double? = null,
    waypoints: List<TacticalWaypoint> = emptyList(),
    onDropWaypoint: (String, WaypointType, Double, Double) -> Unit = { _, _, _, _ -> },
    broadcastCoarseLocationInBle: Boolean = false,
    onToggleBroadcastCoarseLocation: () -> Unit = {},
    locationShareMode: LocationShareMode = LocationShareMode.EXACT,
    onSelectLocationShareMode: (LocationShareMode) -> Unit = {},
    breadcrumbs: List<com.example.model.BreadcrumbPoint> = emptyList(),
    geofenceEnabled: Boolean = false,
    geofenceLat: Double = 0.0,
    geofenceLng: Double = 0.0,
    geofenceRadiusMeters: Int = 500,
    geofenceAlertMessage: String = "",
    onDismissGeofenceAlert: () -> Unit = {},
    hasBarometerSensor: Boolean = false,
    barometricPressureHpa: Float? = null,
    weatherTrendLabel: String = "",
    isStormAlertActive: Boolean = false,
    appLanguage: String = "en",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var centerLat by remember { mutableDoubleStateOf(userLat ?: 37.7749) }
    var centerLon by remember { mutableDoubleStateOf(userLon ?: -122.4194) }
    var zoomLevel by remember { mutableFloatStateOf(14.0f) }

    // Follow live GPS lock on initial fix
    LaunchedEffect(userLat, userLon) {
        if (userLat != null && userLon != null) {
            centerLat = userLat
            centerLon = userLon
        }
    }

    var tileRefreshCounter by remember { mutableIntStateOf(0) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var pendingPinCoords by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var selectedPinType by remember { mutableStateOf(WaypointType.RALLY) }
    var customPinTitle by remember { mutableStateOf("") }

    val downloadProgress by OfflineRegionDownloader.downloadState.collectAsState()
    var cacheStats by remember { mutableStateOf(OfflineRegionDownloader.getDiskCacheStats(context)) }

    LaunchedEffect(downloadProgress.isCompleted) {
        if (downloadProgress.isCompleted) {
            tileRefreshCounter++
            cacheStats = OfflineRegionDownloader.getDiskCacheStats(context)
        }
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 500.dp)
            .fillMaxSize()
            .background(TacticalBlack)
            .testTag("slippy_street_map_container")
    ) {
        // -----------------------------------------------------------------
        // SLIPPY STREET MAP CANVAS
        // -----------------------------------------------------------------
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        zoomLevel = (zoomLevel + (zoom - 1f) * 1.8f).coerceIn(3.0f, 19.0f)
                        val n = 2.0.pow(zoomLevel.toDouble())
                        val degreesPerPixel = 360.0 / (256.0 * n)
                        centerLon -= pan.x * degreesPerPixel
                        centerLat += pan.y * degreesPerPixel * cos(Math.toRadians(centerLat))
                        centerLat = centerLat.coerceIn(-85.0, 85.0)
                        centerLon = centerLon.coerceIn(-180.0, 180.0)
                    }
                }
                .pointerInput(nodes, centerLat, centerLon, zoomLevel) {
                    detectTapGestures(
                        onTap = { tapOffset ->
                            val intZoom = zoomLevel.toInt()
                            val (curTileX, curTileY) = NativeTileMapManager.latLonToTileCoords(centerLat, centerLon, intZoom)
                            val subScale = 2.0.pow((zoomLevel - intZoom).toDouble()).toFloat()
                            val tileSizePx = 256f * subScale
                            val viewCenterX = size.width / 2f
                            val viewCenterY = size.height / 2f

                            // Detect tap on peer pins
                            for (node in nodes) {
                                val (nodeTileX, nodeTileY) = NativeTileMapManager.latLonToTileCoords(node.latitude, node.longitude, intZoom)
                                val px = viewCenterX + ((nodeTileX - curTileX) * tileSizePx).toFloat()
                                val py = viewCenterY + ((nodeTileY - curTileY) * tileSizePx).toFloat()

                                if (hypot(tapOffset.x - px, tapOffset.y - py) < 40.dp.toPx()) {
                                    onSelectNode(node)
                                    return@detectTapGestures
                                }
                            }
                            onSelectNode(null)
                        },
                        onLongPress = { longPressOffset ->
                            val n = 2.0.pow(zoomLevel.toDouble())
                            val degreesPerPixel = 360.0 / (256.0 * n)
                            val dx = longPressOffset.x - (size.width / 2f)
                            val dy = longPressOffset.y - (size.height / 2f)
                            val tappedLon = (centerLon + dx * degreesPerPixel).coerceIn(-180.0, 180.0)
                            val tappedLat = (centerLat - (dy * degreesPerPixel) / cos(Math.toRadians(centerLat))).coerceIn(-85.0, 85.0)
                            onSetManualLocation(tappedLat, tappedLon)
                            pendingPinCoords = Pair(tappedLat, tappedLon)
                        }
                    )
                }
        ) {
            @Suppress("UNUSED_VARIABLE")
            val redraw = tileRefreshCounter

            val canvasWidth = size.width
            val canvasHeight = size.height
            val viewCenterX = canvasWidth / 2f
            val viewCenterY = canvasHeight / 2f

            val intZoom = zoomLevel.toInt()
            val subScale = 2.0.pow((zoomLevel - intZoom).toDouble()).toFloat()
            val tileSizePx = 256f * subScale

            val (curTileX, curTileY) = NativeTileMapManager.latLonToTileCoords(centerLat, centerLon, intZoom)
            val halfVisibleTilesX = (canvasWidth / tileSizePx) / 2f + 1f
            val halfVisibleTilesY = (canvasHeight / tileSizePx) / 2f + 1f

            val minTileX = (curTileX - halfVisibleTilesX).toInt()
            val maxTileX = (curTileX + halfVisibleTilesX).toInt()
            val minTileY = (curTileY - halfVisibleTilesY).toInt()
            val maxTileY = (curTileY + halfVisibleTilesY).toInt()

            val maxWorldTiles = 1 shl intZoom

            // 1. Draw OpenStreetMap Tiles (checking persistent filesDir/osm_tiles/$z/$x/$y.png FIRST)
            for (tx in minTileX..maxTileX) {
                for (ty in minTileY..maxTileY) {
                    val wrappedX = ((tx % maxWorldTiles) + maxWorldTiles) % maxWorldTiles
                    if (ty < 0 || ty >= maxWorldTiles) continue

                    val tileLeft = viewCenterX + ((tx - curTileX) * tileSizePx).toFloat()
                    val tileTop = viewCenterY + ((ty - curTileY) * tileSizePx).toFloat()

                    val cachedBmp = NativeTileMapManager.getCachedTile(context, intZoom, wrappedX, ty)
                    if (cachedBmp != null) {
                        drawImage(
                            image = cachedBmp,
                            dstOffset = IntOffset(tileLeft.toInt(), tileTop.toInt()),
                            dstSize = IntSize(ceil(tileSizePx).toInt(), ceil(tileSizePx).toInt())
                        )
                        // Draw subtle emerald border around cached offline tiles
                        drawRect(
                            color = EmeraldMesh.copy(alpha = 0.35f),
                            topLeft = Offset(tileLeft, tileTop),
                            size = androidx.compose.ui.geometry.Size(tileSizePx, tileSizePx),
                            style = Stroke(width = 1f)
                        )
                    } else {
                        // Render clean dark cartographic coordinate grid tile when offline / uncached
                        drawRect(
                            color = Color(0xFF141A24),
                            topLeft = Offset(tileLeft, tileTop),
                            size = androidx.compose.ui.geometry.Size(tileSizePx, tileSizePx)
                        )
                        drawRect(
                            color = Color(0xFF222D3D),
                            topLeft = Offset(tileLeft, tileTop),
                            size = androidx.compose.ui.geometry.Size(tileSizePx, tileSizePx),
                            style = Stroke(width = 1f)
                        )

                        // Fetch tile asynchronously (checks persistent offline disk cache first on IO)
                        NativeTileMapManager.fetchTileAsync(
                            context = context,
                            z = intZoom,
                            x = wrappedX,
                            y = ty,
                            scope = coroutineScope,
                            isInternetEnabled = isInternetEnabled
                        ) { _, _, _, _ ->
                            tileRefreshCounter++
                        }
                    }
                }
            }

            // 1b. Draw True Web Mercator Cached Region Boundary if a sector was downloaded
            OfflineRegionDownloader.getOrLoadLastCachedRegion(context)?.let { cachedRegion ->
                val (regTileX, regTileY) = NativeTileMapManager.latLonToTileCoords(
                    cachedRegion.centerLat,
                    cachedRegion.centerLon,
                    intZoom
                )
                val regPx = viewCenterX + ((regTileX - curTileX) * tileSizePx).toFloat()
                val regPy = viewCenterY + ((regTileY - curTileY) * tileSizePx).toFloat()
                val realRadiusPx = OfflineRegionDownloader.calculateRealRadiusPixels(
                    centerLat = cachedRegion.centerLat,
                    zoom = zoomLevel.toDouble(),
                    selectedRadiusKm = cachedRegion.radiusKm
                )
                val safeRadiusPx = realRadiusPx.coerceIn(2f, 100_000f)
                drawCircle(
                    color = EmeraldMesh.copy(alpha = 0.08f),
                    radius = safeRadiusPx,
                    center = Offset(regPx, regPy)
                )
                drawCircle(
                    color = EmeraldMesh.copy(alpha = 0.65f),
                    radius = safeRadiusPx,
                    center = Offset(regPx, regPy),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            // Draw Geofence Perimeter Circle when enabled
            if (geofenceEnabled && (geofenceLat != 0.0 || geofenceLng != 0.0)) {
                val (gfTileX, gfTileY) = NativeTileMapManager.latLonToTileCoords(geofenceLat, geofenceLng, intZoom)
                val gfPx = viewCenterX + ((gfTileX - curTileX) * tileSizePx).toFloat()
                val gfPy = viewCenterY + ((gfTileY - curTileY) * tileSizePx).toFloat()
                val gfRadiusPx = OfflineRegionDownloader.calculateRealRadiusPixels(
                    centerLat = geofenceLat,
                    zoom = zoomLevel.toDouble(),
                    selectedRadiusKm = geofenceRadiusMeters / 1000.0
                ).coerceIn(8f, 100_000f)
                val fenceColor = if (geofenceAlertMessage.isNotBlank()) RedSosAlert else CyanGateway
                drawCircle(
                    color = fenceColor.copy(alpha = 0.09f),
                    radius = gfRadiusPx,
                    center = Offset(gfPx, gfPy)
                )
                drawCircle(
                    color = fenceColor.copy(alpha = 0.85f),
                    radius = gfRadiusPx,
                    center = Offset(gfPx, gfPy),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                    )
                )
            }

            // Draw Mesh Topology & Relay Route Lines between Self and live Peers
            val selfNodeWithPos = nodes.firstOrNull { it.isSelf && (it.latitude != 0.0 || it.longitude != 0.0) }
            if (selfNodeWithPos != null) {
                val (sxT, syT) = NativeTileMapManager.latLonToTileCoords(selfNodeWithPos.latitude, selfNodeWithPos.longitude, intZoom)
                val selfPx = viewCenterX + ((sxT - curTileX) * tileSizePx).toFloat()
                val selfPy = viewCenterY + ((syT - curTileY) * tileSizePx).toFloat()
                nodes.forEach { peer ->
                    if (!peer.isSelf && (peer.latitude != 0.0 || peer.longitude != 0.0)) {
                        val (pxT, pyT) = NativeTileMapManager.latLonToTileCoords(peer.latitude, peer.longitude, intZoom)
                        val pPx = viewCenterX + ((pxT - curTileX) * tileSizePx).toFloat()
                        val pPy = viewCenterY + ((pyT - curTileY) * tileSizePx).toFloat()
                        val linkColor = when {
                            peer.isGateway -> CyanGateway.copy(alpha = 0.78f)
                            peer.hopCount <= 1 -> EmeraldMesh.copy(alpha = 0.75f)
                            else -> AmberRootBoost.copy(alpha = 0.78f)
                        }
                        drawLine(
                            color = linkColor,
                            start = Offset(selfPx, selfPy),
                            end = Offset(pPx, pPy),
                            strokeWidth = if (peer.isGateway) 2.5.dp.toPx() else 1.8.dp.toPx(),
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                if (peer.hopCount <= 1) floatArrayOf(12f, 8f) else floatArrayOf(6f, 10f)
                            )
                        )
                    }
                }
            }

            // 2. Draw Real Peer GPS Pin Markers with Live Distance, Compass Bearing Arrow & Altitude ASL
            val paintText = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 28f
                typeface = Typeface.MONOSPACE
                isAntiAlias = true
                setShadowLayer(4f, 0f, 2f, android.graphics.Color.BLACK)
            }
            val paintSub = Paint().apply {
                color = android.graphics.Color.rgb(0, 230, 118)
                textSize = 22f
                typeface = Typeface.MONOSPACE
                isAntiAlias = true
                setShadowLayer(3f, 0f, 1f, android.graphics.Color.BLACK)
            }

            nodes.forEach { node ->
                if (node.latitude != 0.0 && node.longitude != 0.0) {
                    val (nodeTileX, nodeTileY) = NativeTileMapManager.latLonToTileCoords(node.latitude, node.longitude, intZoom)
                    val px = viewCenterX + ((nodeTileX - curTileX) * tileSizePx).toFloat()
                    val py = viewCenterY + ((nodeTileY - curTileY) * tileSizePx).toFloat()

                    val isSelf = node.isSelf
                    val isSelected = selectedNode?.id == node.id

                    // Selection ring
                    if (isSelected) {
                        drawCircle(
                            color = EmeraldMesh,
                            radius = 26f,
                            center = Offset(px, py),
                            style = Stroke(width = 3f)
                        )
                    }

                    // Outer Pin Ring
                    drawCircle(
                        color = if (isSelf) Color(0xFF00B0FF) else EmeraldMesh,
                        radius = 16f,
                        center = Offset(px, py)
                    )
                    drawCircle(
                        color = Color.Black,
                        radius = 12f,
                        center = Offset(px, py)
                    )
                    drawCircle(
                        color = if (isSelf) Color(0xFF00E5FF) else EmeraldMesh,
                        radius = 8f,
                        center = Offset(px, py)
                    )

                    // Node Label Box with Distance, Compass Bearing Arrow & Barometric Altitude (m ASL)
                    val title = if (isSelf) "MY GPS (${node.callsign})" else node.callsign
                    val distStr = node.distanceMeters?.let { d ->
                        if (d >= 1000) " • %.1fkm".format(d / 1000.0) else " • ${d.toInt()}m"
                    } ?: ""
                    val bearingStr = if (!isSelf && userLat != null && userLon != null) {
                        val absBrg = TacticalSensorService.calculateBearingDegrees(userLat, userLon, node.latitude, node.longitude)
                        val relBrg = TacticalSensorService.calculateRelativeBearingDegrees(absBrg, deviceHeadingDeg)
                        val arrow = TacticalSensorService.bearingToCompassArrow(relBrg)
                        " • $arrow${relBrg.toInt()}°"
                    } else ""
                    val altMeters = if (isSelf) (barometricAltitudeMeters ?: node.altitudeM).toInt() else node.altitudeM.toInt()
                    val sub = "${node.id}$distStr$bearingStr • ${altMeters}m ASL • ${node.batteryPercent}%"

                    drawContext.canvas.nativeCanvas.drawText(
                        title,
                        px + 22f,
                        py - 6f,
                        paintText
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        sub,
                        px + 22f,
                        py + 20f,
                        paintSub
                    )
                }
            }

            // Draw Operator & Mesh Breadcrumb Trail Polyline
            if (breadcrumbs.size >= 2) {
                var prevOffset: Offset? = null
                for (pt in breadcrumbs) {
                    val (bTileX, bTileY) = NativeTileMapManager.latLonToTileCoords(pt.lat, pt.lon, intZoom)
                    val bx = viewCenterX + ((bTileX - curTileX) * tileSizePx).toFloat()
                    val by = viewCenterY + ((bTileY - curTileY) * tileSizePx).toFloat()
                    val curOffset = Offset(bx, by)
                    prevOffset?.let { prev ->
                        drawLine(
                            color = CyanGateway.copy(alpha = 0.78f),
                            start = prev,
                            end = curOffset,
                            strokeWidth = 5f
                        )
                    }
                    drawCircle(
                        color = CyanGateway,
                        radius = 4.5f,
                        center = curOffset
                    )
                    prevOffset = curOffset
                }
            }

            // 3. Draw Shared Tactical Waypoints (RALLY, HAZARD, SUPPLY, SOS) with Live Distance & Compass Bearing
            waypoints.forEach { wp ->
                if (wp.latitude != 0.0 || wp.longitude != 0.0) {
                    val (wpTileX, wpTileY) = NativeTileMapManager.latLonToTileCoords(wp.latitude, wp.longitude, intZoom)
                    val px = viewCenterX + ((wpTileX - curTileX) * tileSizePx).toFloat()
                    val py = viewCenterY + ((wpTileY - curTileY) * tileSizePx).toFloat()

                    val pinColor = when (wp.type) {
                        WaypointType.SOS -> RedSosAlert
                        WaypointType.HAZARD -> AmberRootBoost
                        WaypointType.SUPPLY, WaypointType.WATER, WaypointType.CACHE -> EmeraldMesh
                        WaypointType.RALLY, WaypointType.MEETUP, WaypointType.CAMP -> CyanGateway
                    }

                    drawCircle(
                        color = pinColor.copy(alpha = 0.25f),
                        radius = 22f,
                        center = Offset(px, py)
                    )
                    drawCircle(
                        color = pinColor,
                        radius = 12f,
                        center = Offset(px, py)
                    )
                    drawCircle(
                        color = Color.Black,
                        radius = 5f,
                        center = Offset(px, py)
                    )

                    val distBearingStr = if (userLat != null && userLon != null) {
                        val distM = LiveMeshNetworkService.calculateDistanceMeters(userLat, userLon, wp.latitude, wp.longitude)
                        val distLabel = if (distM >= 1000) "%.1fkm".format(distM / 1000.0) else "${distM.toInt()}m"
                        val absBrg = TacticalSensorService.calculateBearingDegrees(userLat, userLon, wp.latitude, wp.longitude)
                        val relBrg = TacticalSensorService.calculateRelativeBearingDegrees(absBrg, deviceHeadingDeg)
                        val arrow = TacticalSensorService.bearingToCompassArrow(relBrg)
                        " • $distLabel • $arrow${relBrg.toInt()}°"
                    } else ""

                    val wpHeader = "[${wp.type.name}] ${wp.title}"
                    val wpSub = "by ${wp.creatorCallsign}$distBearingStr"
                    drawContext.canvas.nativeCanvas.drawText(
                        wpHeader,
                        px + 18f,
                        py - 6f,
                        paintText
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        wpSub,
                        px + 18f,
                        py + 18f,
                        paintSub
                    )
                }
            }
        }

        // -----------------------------------------------------------------
        // MAP CONTROLS OVERLAY (ZOOM +/- & CENTER GPS BUTTONS)
        // -----------------------------------------------------------------
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // "Center on My GPS" Button
            FloatingActionButton(
                onClick = {
                    if (userLat != null && userLon != null) {
                        centerLat = userLat
                        centerLon = userLon
                        zoomLevel = 15.0f
                    } else {
                        onRequestGps()
                    }
                },
                containerColor = DeepCarbon,
                contentColor = if (isGpsLocked) EmeraldMesh else AmberRootBoost,
                shape = CircleShape,
                modifier = Modifier
                    .size(48.dp)
                    .border(1.dp, if (isGpsLocked) EmeraldMesh else TacticalBorder, CircleShape)
                    .testTag("map_center_gps_button")
            ) {
                Icon(
                    imageVector = if (isGpsLocked) Icons.Default.MyLocation else Icons.Default.LocationSearching,
                    contentDescription = "Center GPS",
                    modifier = Modifier.size(22.dp)
                )
            }

            // Drop Tactical Waypoint Button
            FloatingActionButton(
                onClick = {
                    pendingPinCoords = Pair(centerLat, centerLon)
                },
                containerColor = DeepCarbon,
                contentColor = CyanGateway,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .size(44.dp)
                    .border(1.dp, CyanGateway, RoundedCornerShape(8.dp))
                    .testTag("map_drop_waypoint_button")
            ) {
                Icon(
                    imageVector = Icons.Default.AddLocationAlt,
                    contentDescription = "Drop Shared Tactical Waypoint",
                    modifier = Modifier.size(20.dp)
                )
            }

            // Zoom In (+) Button
            FloatingActionButton(
                onClick = { zoomLevel = (zoomLevel + 1.0f).coerceAtMost(19.0f) },
                containerColor = DeepCarbon,
                contentColor = Color.White,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .size(44.dp)
                    .border(1.dp, TacticalBorder, RoundedCornerShape(8.dp))
                    .testTag("map_zoom_in_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Zoom In",
                    modifier = Modifier.size(20.dp)
                )
            }

            // Zoom Out (-) Button
            FloatingActionButton(
                onClick = { zoomLevel = (zoomLevel - 1.0f).coerceAtLeast(3.0f) },
                containerColor = DeepCarbon,
                contentColor = Color.White,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .size(44.dp)
                    .border(1.dp, TacticalBorder, RoundedCornerShape(8.dp))
                    .testTag("map_zoom_out_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Zoom Out",
                    modifier = Modifier.size(20.dp)
                )
            }

            // Download Offline Region Button
            FloatingActionButton(
                onClick = {
                    cacheStats = OfflineRegionDownloader.getDiskCacheStats(context)
                    showDownloadDialog = true
                },
                containerColor = DeepCarbon,
                contentColor = EmeraldMesh,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .size(44.dp)
                    .border(1.dp, EmeraldMesh, RoundedCornerShape(8.dp))
                    .testTag("map_download_region_button")
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = "Download Region Offline",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Top Status HUD Overlay (GPS + Compass Heading + Barometric Altitude ASL + BLE Location Privacy Opt-In)
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                color = DeepCarbon.copy(alpha = 0.88f),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, TacticalBorder)
            ) {
                val cardinal = TacticalSensorService.bearingToCardinal(deviceHeadingDeg)
                val altText = barometricAltitudeMeters?.let { "• ${it.toInt()}m ASL" } ?: ""
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isGpsLocked) EmeraldMesh else AmberRootBoost)
                    )
                    Text(
                        text = if (isGpsLocked) {
                            "GPS: %.4f, %.4f".format(centerLat, centerLon)
                        } else {
                            com.example.util.AppStrings.tr(appLanguage, "GPS: ACQUIRING (PAN/PIN ACTIVE)", "GPS: ΑΝΑΖΗΤΗΣΗ ΣΗΜΑΤΟΣ")
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "• HDG ${deviceHeadingDeg.toInt()}° $cardinal $altText • Z${zoomLevel.toInt()}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = CyanGateway
                        )
                    )
                }
            }

            // Barometric Weather Pill (ONLY shown on phones equipped with a hardware barometer sensor)
            if (hasBarometerSensor && barometricPressureHpa != null) {
                val localizedTrend = com.example.util.AppStrings.weatherTrendLabel(appLanguage, weatherTrendLabel)
                Surface(
                    color = if (isStormAlertActive) RedSosAlert.copy(alpha = 0.92f) else DeepCarbon.copy(alpha = 0.88f),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isStormAlertActive) Color.White else CyanGateway.copy(alpha = 0.7f)
                    )
                ) {
                    Text(
                        text = if (isStormAlertActive) {
                            com.example.util.AppStrings.tr(
                                appLanguage,
                                "⛈️ STORM DROP • %.1f hPa • %s".format(barometricPressureHpa, localizedTrend),
                                "⛈️ ΠΤΩΣΗ ΚΑΤΑΙΓΙΔΑΣ • %.1f hPa • %s".format(barometricPressureHpa, localizedTrend)
                            )
                        } else {
                            com.example.util.AppStrings.tr(
                                appLanguage,
                                "BARO: %.1f hPa • %s".format(barometricPressureHpa, localizedTrend),
                                "ΒΑΡΟΜΕΤΡΟ: %.1f hPa • %s".format(barometricPressureHpa, localizedTrend)
                            )
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Geofence Breach Alert Banner
            if (geofenceAlertMessage.isNotBlank()) {
                Surface(
                    color = RedSosAlert.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White),
                    modifier = Modifier.clickable { onDismissGeofenceAlert() }
                ) {
                    Text(
                        text = com.example.util.AppStrings.tr(
                            appLanguage,
                            "⚠ $geofenceAlertMessage (TAP TO DISMISS)",
                            "⚠ $geofenceAlertMessage (ΠΑΤΗΣΤΕ ΓΙΑ ΚΛΕΙΣΙΜΟ)"
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        ),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Surface(
                color = DeepCarbon.copy(alpha = 0.90f),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    when (locationShareMode) {
                        LocationShareMode.OFF -> EmeraldMesh.copy(alpha = 0.7f)
                        LocationShareMode.COARSE -> AmberRootBoost
                        LocationShareMode.EXACT -> CyanGateway
                    }
                ),
                modifier = Modifier.testTag("map_location_share_selector")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (locationShareMode == LocationShareMode.OFF) Icons.Default.LocationOff else Icons.Default.LocationOn,
                        contentDescription = "Location Sharing Mode",
                        tint = when (locationShareMode) {
                            LocationShareMode.OFF -> EmeraldMesh
                            LocationShareMode.COARSE -> AmberRootBoost
                            LocationShareMode.EXACT -> CyanGateway
                        },
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "LOC:",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TelemetryTextSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    LocationShareMode.entries.forEach { mode ->
                        val isSelected = locationShareMode == mode
                        val activeBg = when (mode) {
                            LocationShareMode.OFF -> EmeraldMesh
                            LocationShareMode.COARSE -> AmberRootBoost
                            LocationShareMode.EXACT -> CyanGateway
                        }
                        Box(
                            modifier = Modifier
                                .heightIn(min = 32.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (isSelected) activeBg else TacticalSurfaceVariant.copy(alpha = 0.65f))
                                .clickable { onSelectLocationShareMode(mode) }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                .testTag("map_loc_mode_${mode.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isSelected) Color.Black else TelemetryTextPrimary,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            )
                        }
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // SHARED TACTICAL WAYPOINT DROP MODAL (LONG-PRESS OR PIN BUTTON)
        // -----------------------------------------------------------------
        pendingPinCoords?.let { (pinLat, pinLon) ->
            AlertDialog(
                onDismissRequest = { pendingPinCoords = null },
                containerColor = DeepCarbon,
                title = {
                    Text(
                        text = com.example.util.AppStrings.tr(appLanguage, "DROP SHARED TACTICAL PIN", "ΠΡΟΣΘΗΚΗ ΚΟΙΝΟΧΡΗΣΤΗΣ ΠΙΝΕΖΑΣ"),
                        color = CyanGateway,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Coords: %.5f, %.5f".format(pinLat, pinLon),
                            color = TelemetryTextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val pinTypes = listOf(
                                WaypointType.RALLY,
                                WaypointType.HAZARD,
                                WaypointType.SUPPLY,
                                WaypointType.SOS
                            )
                            pinTypes.forEach { t ->
                                val selected = selectedPinType == t
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedPinType = t },
                                    label = { Text(t.name, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = customPinTitle,
                            onValueChange = { customPinTitle = it },
                            label = { Text(com.example.util.AppStrings.tr(appLanguage, "Waypoint Label (optional)", "Ετικέτα Σημείου (προαιρετικό)"), fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val label = customPinTitle.trim().ifBlank { selectedPinType.label }
                            onDropWaypoint(label, selectedPinType, pinLat, pinLon)
                            customPinTitle = ""
                            pendingPinCoords = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldMesh,
                            contentColor = Color.Black
                        )
                    ) {
                        Text(com.example.util.AppStrings.tr(appLanguage, "BROADCAST PIN", "ΑΠΟΣΤΟΛΗ ΣΗΜΕΙΟΥ"), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingPinCoords = null }) {
                        Text(com.example.util.AppStrings.tr(appLanguage, "CLOSE", "ΚΛΕΙΣΙΜΟ"), color = TelemetryTextSecondary, fontSize = 11.sp)
                    }
                }
            )
        }

        // -----------------------------------------------------------------
        // TACTICAL OFFLINE REGION DOWNLOADER DIALOG
        // -----------------------------------------------------------------
        if (showDownloadDialog) {
            InteractiveMapAreaSelectorDialog(
                initialLat = userLat ?: centerLat,
                initialLon = userLon ?: centerLon,
                initialZoom = zoomLevel,
                onDismissRequest = { showDownloadDialog = false },
                onDownloadComplete = {
                    tileRefreshCounter++
                    cacheStats = OfflineRegionDownloader.getDiskCacheStats(context)
                }
            )
        }
    }
}
