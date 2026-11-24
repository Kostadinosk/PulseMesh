// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.util

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.*

data class TacticalRegion(
    val id: String,
    val name: String,
    val centerLat: Double,
    val centerLon: Double,
    val radiusKm: Double = 5.0,
    val description: String
)

data class DownloadProgress(
    val isRunning: Boolean = false,
    val regionName: String = "",
    val downloaded: Int = 0,
    val total: Int = 0,
    val percent: Int = 0,
    val diskCacheSizeMb: Double = 0.0,
    val statusMessage: String = "",
    val isCompleted: Boolean = false,
    val error: String? = null
)

object OfflineRegionDownloader {

    val SUPPORTED_RADII_KM = listOf(1.0, 5.0, 10.0, 20.0, 50.0)

    val PREDEFINED_REGIONS = listOf(
        TacticalRegion(
            id = "current",
            name = "Current GPS Vicinity",
            centerLat = 0.0,
            centerLon = 0.0,
            radiusKm = 5.0,
            description = "Active operational area around device GPS fix"
        ),
        TacticalRegion(
            id = "sf_bay",
            name = "San Francisco Bay Tactical Sector",
            centerLat = 37.7749,
            centerLon = -122.4194,
            radiusKm = 5.0,
            description = "Downtown SF, Embarcadero, Mission & Presidio corridor"
        ),
        TacticalRegion(
            id = "ny_metro",
            name = "New York Urban Grid",
            centerLat = 40.7128,
            centerLon = -74.0060,
            radiusKm = 5.0,
            description = "Lower Manhattan, Midtown & Brooklyn Navy Yard"
        ),
        TacticalRegion(
            id = "tokyo_central",
            name = "Tokyo Tactical Zone",
            centerLat = 35.6762,
            centerLon = 139.6503,
            radiusKm = 5.0,
            description = "Shinjuku, Shibuya, Chiyoda & Minato sectors"
        ),
        TacticalRegion(
            id = "london_central",
            name = "London Mission Sector",
            centerLat = 51.5074,
            centerLon = -0.1278,
            radiusKm = 5.0,
            description = "City of London, Westminster, Southwark & Thames"
        ),
        TacticalRegion(
            id = "paris_sector",
            name = "Paris Tactical Zone",
            centerLat = 48.8566,
            centerLon = 2.3522,
            radiusKm = 5.0,
            description = "1st-8th Arrondissements & Seine tactical corridor"
        )
    )

    private val _downloadState = MutableStateFlow(DownloadProgress())
    val downloadState: StateFlow<DownloadProgress> = _downloadState.asStateFlow()

    var isInternetEnabled: Boolean = false

    @Volatile
    var gatewayTileProxyBaseUrl: (() -> String?)? = null

    @Volatile
    var gatewayPeerCallsign: (() -> String?)? = null

    @Volatile
    var gatewayMeshTileRequester: ((z: Int, x: Int, y: Int) -> Boolean)? = null

    fun hasGatewayMapAccess(): Boolean {
        return !gatewayTileProxyBaseUrl?.invoke().isNullOrBlank() || !gatewayPeerCallsign?.invoke().isNullOrBlank()
    }

    @Volatile
    private var activeHttpConnection: HttpURLConnection? = null

    @Volatile
    var lastCachedRegion: TacticalRegion? = null
        private set

    @Volatile
    private var lastCachedRegionChecked: Boolean = false

    fun getOrLoadLastCachedRegion(context: Context): TacticalRegion? {
        if (lastCachedRegionChecked) return lastCachedRegion
        lastCachedRegionChecked = true
        return runCatching {
            val prefs = context.getSharedPreferences("pulsemesh_production_v11", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("cached_region_exists", false)) return null
            val lat = java.lang.Double.longBitsToDouble(prefs.getLong("cached_region_lat_bits", 0L))
            val lon = java.lang.Double.longBitsToDouble(prefs.getLong("cached_region_lon_bits", 0L))
            val radiusKm = java.lang.Double.longBitsToDouble(prefs.getLong("cached_region_radius_bits", java.lang.Double.doubleToRawLongBits(5.0)))
            val name = prefs.getString("cached_region_name", "Cached Offline Region").orEmpty()
            TacticalRegion(
                id = "persisted_region",
                name = name,
                centerLat = lat,
                centerLon = lon,
                radiusKm = radiusKm,
                description = "Persisted offline sector"
            ).also { lastCachedRegion = it }
        }.getOrNull()
    }

    private fun saveCachedRegionToPrefs(context: Context, region: TacticalRegion?) {
        lastCachedRegionChecked = true
        runCatching {
            val prefs = context.getSharedPreferences("pulsemesh_production_v11", Context.MODE_PRIVATE)
            if (region == null) {
                prefs.edit().putBoolean("cached_region_exists", false).apply()
            } else {
                prefs.edit()
                    .putBoolean("cached_region_exists", true)
                    .putString("cached_region_name", region.name)
                    .putLong("cached_region_lat_bits", java.lang.Double.doubleToRawLongBits(region.centerLat))
                    .putLong("cached_region_lon_bits", java.lang.Double.doubleToRawLongBits(region.centerLon))
                    .putLong("cached_region_radius_bits", java.lang.Double.doubleToRawLongBits(region.radiusKm))
                    .apply()
            }
        }
    }

    private var downloadJob: Job? = null

    /**
     * Computes real Web Mercator ground resolution (meters per pixel) at [centerLat] and [zoom].
     */
    fun calculateMetersPerPixel(centerLat: Double, zoom: Double): Double {
        val cosLat = cos(Math.toRadians(centerLat.coerceIn(-85.0511, 85.0511))).coerceAtLeast(0.01)
        return (156543.03392 * cosLat) / 2.0.pow(zoom)
    }

    /**
     * Computes the true Web Mercator pixel radius on the Map Canvas for [selectedRadiusKm]
     * at [centerLat] and [zoom].
     * Shrinks by 2x for each zoom-out step and expands by 2x for each zoom-in step.
     */
    fun calculateRealRadiusPixels(centerLat: Double, zoom: Double, selectedRadiusKm: Double): Float {
        val metersPerPixel = (156543.03392 * cos(Math.toRadians(centerLat))) / Math.pow(2.0, zoom)
        if (metersPerPixel <= 0.0001) return 100f
        return ((selectedRadiusKm * 1000.0) / metersPerPixel).toFloat()
    }

    /**
     * Computes the zoom pyramid bounds `[zMin, zMax]` around [currentZoom]:
     * `maxOf(10, currentZoom - 1)` to `minOf(16, currentZoom + 2)`, automatically capping
     * maxZoom to 14 or 15 for large 20 km - 50 km radii so tile count stays reasonable and fast.
     */
    fun calculateAdaptiveZoomRange(currentZoom: Int, selectedRadiusKm: Double): Pair<Int, Int> {
        val zMin = maxOf(10, currentZoom - 1)
        val rawMax = minOf(16, currentZoom + 2)
        val cappedMax = when {
            selectedRadiusKm >= 50.0 -> minOf(rawMax, 14)
            selectedRadiusKm >= 20.0 -> minOf(rawMax, 15)
            else -> rawMax
        }
        return Pair(minOf(zMin, cappedMax), maxOf(zMin, cappedMax))
    }

    /**
     * Estimates the download size in megabytes using 18 KB per OSM PNG tile (`totalTiles * 18 KB`).
     */
    fun estimateTilePyramidSizeMb(totalTiles: Int): Double {
        return (totalTiles * 18.0) / 1024.0
    }

    /**
     * Calculate exact OSM Slippy Tile pyramid `(z, x, y)` for a geographic bounding box around
     * `(centerLat, centerLon)` with `radiusKm` and zoom range `minZoom..maxZoom`.
     */
    fun calculateTileList(
        centerLat: Double,
        centerLon: Double,
        radiusKm: Double,
        minZoom: Int = 13,
        maxZoom: Int = 15
    ): List<Triple<Int, Int, Int>> {
        val tiles = mutableListOf<Triple<Int, Int, Int>>()

        val latDelta = radiusKm / 111.32
        val lonDelta = radiusKm / (111.32 * cos(Math.toRadians(centerLat)).coerceAtLeast(0.01))

        val minLat = (centerLat - latDelta).coerceIn(-85.0511, 85.0511)
        val maxLat = (centerLat + latDelta).coerceIn(-85.0511, 85.0511)
        val minLon = (centerLon - lonDelta).coerceIn(-180.0, 180.0)
        val maxLon = (centerLon + lonDelta).coerceIn(-180.0, 180.0)

        val effectiveMaxZoom = when {
            radiusKm >= 50.0 && maxZoom > 14 -> 14
            radiusKm >= 20.0 && maxZoom > 15 -> 15
            else -> maxZoom
        }
        val effectiveMinZoom = minOf(minZoom, effectiveMaxZoom)

        for (z in effectiveMinZoom..effectiveMaxZoom) {
            val maxWorldTile = (1 shl z) - 1

            val minLatRad = Math.toRadians(minLat)
            val maxLatRad = Math.toRadians(maxLat)

            val x1 = floor((minLon + 180.0) / 360.0 * (1 shl z)).toInt()
            val x2 = floor((maxLon + 180.0) / 360.0 * (1 shl z)).toInt()

            val y1 = floor((1.0 - ln(tan(maxLatRad) + 1.0 / cos(maxLatRad)) / Math.PI) / 2.0 * (1 shl z)).toInt()
            val y2 = floor((1.0 - ln(tan(minLatRad) + 1.0 / cos(minLatRad)) / Math.PI) / 2.0 * (1 shl z)).toInt()

            val xMin = min(x1, x2).coerceIn(0, maxWorldTile)
            val xMax = max(x1, x2).coerceIn(0, maxWorldTile)
            val yMin = min(y1, y2).coerceIn(0, maxWorldTile)
            val yMax = max(y1, y2).coerceIn(0, maxWorldTile)

            for (x in xMin..xMax) {
                for (y in yMin..yMax) {
                    tiles.add(Triple(z, x, y))
                }
            }
        }

        return tiles
    }

    /**
     * Start active background tile download into `File(context.filesDir, "osm_tiles/$z/$x/$y.png")`.
     */
    fun startDownload(
        context: Context,
        region: TacticalRegion,
        actualCenterLat: Double,
        actualCenterLon: Double,
        minZoom: Int = 13,
        maxZoom: Int = 15,
        scope: CoroutineScope,
        onFinished: () -> Unit = {}
    ) {
        val gwProxyBase = gatewayTileProxyBaseUrl?.invoke()?.trimEnd('/')
        val gwLabel = gatewayPeerCallsign?.invoke().orEmpty()
        val hasMeshGateway = gwLabel.isNotBlank() && gatewayMeshTileRequester != null
        val useDirectInternet = isInternetEnabled
        val useHttpGateway = !useDirectInternet && !gwProxyBase.isNullOrBlank()
        val useBleMeshGateway = !useDirectInternet && !useHttpGateway && hasMeshGateway

        if (!useDirectInternet && !useHttpGateway && !useBleMeshGateway) {
            _downloadState.value = DownloadProgress(
                isRunning = false,
                statusMessage = "Off-Grid Mode: Connect to an internet Gateway Peer or enable Hybrid Mode",
                error = "No direct internet or internet-connected Gateway Peer detected"
            )
            return
        }
        if (_downloadState.value.isRunning) return

        downloadJob?.cancel()
        downloadJob = scope.launch(Dispatchers.IO) {
            val centerLat = if (region.id == "current") actualCenterLat else region.centerLat
            val centerLon = if (region.id == "current") actualCenterLon else region.centerLon

            val rawTiles = calculateTileList(centerLat, centerLon, region.radiusKm, minZoom, maxZoom)
            val tiles = if (useBleMeshGateway) rawTiles.take(36) else rawTiles
            val total = tiles.size
            var cachedBytesAccum = getDiskCacheStats(context).second
            val initialCacheMb = cachedBytesAccum / (1024.0 * 1024.0)
            val sourcePrefix = when {
                useHttpGateway -> "Via Gateway ${gwLabel.ifBlank { "Peer" }} (Wi-Fi): "
                useBleMeshGateway -> "Via Gateway ${gwLabel.ifBlank { "Peer" }} (BLE Mesh): "
                else -> ""
            }

            _downloadState.value = DownloadProgress(
                isRunning = true,
                regionName = region.name,
                downloaded = 0,
                total = total,
                percent = 0,
                diskCacheSizeMb = initialCacheMb,
                statusMessage = String.format(
                    Locale.US,
                    "%sDownloaded 0 / %d tiles • %.2f MB stored",
                    sourcePrefix,
                    total,
                    initialCacheMb
                )
            )

            val tileServers = listOf(
                "https://tile.openstreetmap.org",
                "https://a.tile.openstreetmap.fr/hot",
                "https://b.tile.openstreetmap.fr/hot"
            )

            var downloadedCount = 0

            for (tile in tiles) {
                if (!isActive) break
                if (useDirectInternet && !isInternetEnabled) break

                val (z, x, y) = tile
                val destFile = File(context.filesDir, "osm_tiles/$z/$x/$y.png")
                destFile.parentFile?.mkdirs()

                // Skip if already cached on disk
                if (destFile.exists() && destFile.length() > 0L) {
                    downloadedCount++
                    val pct = ((downloadedCount.toFloat() / total.coerceAtLeast(1).toFloat()) * 100).toInt()
                    val cacheMb = cachedBytesAccum / (1024.0 * 1024.0)
                    _downloadState.value = _downloadState.value.copy(
                        downloaded = downloadedCount,
                        percent = pct,
                        diskCacheSizeMb = cacheMb,
                        statusMessage = String.format(
                            Locale.US,
                            "%sDownloaded %d / %d tiles • %.2f MB stored",
                            sourcePrefix,
                            downloadedCount,
                            total,
                            cacheMb
                        )
                    )
                    continue
                }

                var success = false
                if (useBleMeshGateway) {
                    val requested = gatewayMeshTileRequester?.invoke(z, x, y) == true
                    if (requested) {
                        repeat(16) {
                            delay(250L)
                            if (destFile.exists() && destFile.length() > 32L) {
                                cachedBytesAccum += destFile.length()
                                success = true
                                return@repeat
                            }
                        }
                    }
                } else {
                    val candidateUrls = if (useDirectInternet) {
                        tileServers.map { "$it/$z/$x/$y.png" }
                    } else {
                        listOf("$gwProxyBase/api/tiles/$z/$x/$y.png?fetch=1")
                    }
                    for (urlStr in candidateUrls) {
                        if (useDirectInternet && !isInternetEnabled) break
                        var conn: HttpURLConnection? = null
                        try {
                            val url = URL(urlStr)
                            conn = (url.openConnection() as HttpURLConnection).apply {
                                setRequestProperty("User-Agent", "PulseMesh-Tactical-Android/1.2.7")
                                setRequestProperty("Accept", "image/png,image/*;q=0.8")
                                connectTimeout = 4500
                                readTimeout = 6000
                                instanceFollowRedirects = true
                            }
                            activeHttpConnection = conn

                            if (conn.responseCode in 200..299) {
                                val bytes = conn.inputStream.use { it.readBytes() }
                                if (bytes.isNotEmpty()) {
                                    FileOutputStream(destFile).use { out ->
                                        out.write(bytes)
                                    }
                                    cachedBytesAccum += bytes.size
                                    success = true
                                }
                            }
                            if (success) break
                        } catch (_: Exception) {
                            // Retry with next mirror
                        } finally {
                            runCatching { conn?.disconnect() }
                            activeHttpConnection = null
                        }
                    }
                }

                downloadedCount++
                val pct = ((downloadedCount.toFloat() / total.coerceAtLeast(1).toFloat()) * 100).toInt()
                val cacheMb = cachedBytesAccum / (1024.0 * 1024.0)
                _downloadState.value = _downloadState.value.copy(
                    downloaded = downloadedCount,
                    percent = pct,
                    diskCacheSizeMb = cacheMb,
                    statusMessage = String.format(
                        Locale.US,
                        "%sDownloaded %d / %d tiles • %.2f MB stored",
                        sourcePrefix,
                        downloadedCount,
                        total,
                        cacheMb
                    )
                )

                delay(if (useBleMeshGateway) 120L else 30L)
            }

            val savedRegion = region.copy(centerLat = centerLat, centerLon = centerLon)
            lastCachedRegion = savedRegion
            saveCachedRegionToPrefs(context, savedRegion)
            val finalCacheMb = getDiskCacheStats(context).second / (1024.0 * 1024.0)
            _downloadState.value = DownloadProgress(
                isRunning = false,
                regionName = region.name,
                downloaded = downloadedCount,
                total = total,
                percent = 100,
                diskCacheSizeMb = finalCacheMb,
                statusMessage = String.format(
                    Locale.US,
                    "%sDownloaded %d / %d tiles • %.2f MB stored",
                    sourcePrefix,
                    downloadedCount,
                    total,
                    finalCacheMb
                ),
                isCompleted = true
            )

            withContext(Dispatchers.Main) {
                onFinished()
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        runCatching { activeHttpConnection?.disconnect() }
        activeHttpConnection = null
        _downloadState.value = DownloadProgress(
            isRunning = false,
            statusMessage = "Download Cancelled"
        )
    }

    fun getDiskCacheStats(context: Context): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        val primaryRoot = File(context.filesDir, "osm_tiles")
        if (primaryRoot.exists()) {
            primaryRoot.walkTopDown().forEach { f ->
                if (f.isFile && f.extension.equals("png", ignoreCase = true)) {
                    count++
                    bytes += f.length()
                }
            }
        }
        val legacyRoot = File(context.cacheDir, "osm_tiles")
        if (legacyRoot.exists()) {
            legacyRoot.walkTopDown().forEach { f ->
                if (f.isFile && f.extension.equals("png", ignoreCase = true)) {
                    count++
                    bytes += f.length()
                }
            }
        }
        return Pair(count, bytes)
    }

    fun clearDiskCache(context: Context): Boolean {
        val primaryRoot = File(context.filesDir, "osm_tiles")
        val legacyRoot = File(context.cacheDir, "osm_tiles")
        val ok1 = if (primaryRoot.exists()) primaryRoot.deleteRecursively() else true
        val ok2 = if (legacyRoot.exists()) legacyRoot.deleteRecursively() else true
        lastCachedRegion = null
        saveCachedRegionToPrefs(context, null)
        return ok1 && ok2
    }
}
