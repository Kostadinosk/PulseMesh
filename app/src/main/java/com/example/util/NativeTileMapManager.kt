package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

object NativeTileMapManager {

    // 192-tile in-memory cache
    private val tileCache = object : LruCache<String, ImageBitmap>(192) {}
    private val inFlightKeys = mutableSetOf<String>()

    private val TILE_SERVERS = listOf(
        "https://tile.openstreetmap.org",
        "https://a.tile.openstreetmap.fr/hot",
        "https://b.tile.openstreetmap.fr/hot"
    )

    /**
     * Hierarchical persistent tile path: File(context.filesDir, "osm_tiles/$z/$x/$y.png")
     */
    fun getTileFile(context: Context, z: Int, x: Int, y: Int): File {
        return File(context.filesDir, "osm_tiles/$z/$x/$y.png")
    }

    private fun getLegacyCacheTileFile(context: Context, z: Int, x: Int, y: Int): File {
        val dir = File(context.cacheDir, "osm_tiles")
        return File(dir, "${z}_${x}_${y}.png")
    }

    fun isTileCachedOnDisk(context: Context, z: Int, x: Int, y: Int): Boolean {
        val primary = File(context.filesDir, "osm_tiles/$z/$x/$y.png")
        if (primary.exists() && primary.length() > 0L) return true
        val legacy = getLegacyCacheTileFile(context, z, x, y)
        return legacy.exists() && legacy.length() > 0L
    }

    /**
     * Non-blocking fast memory-cache lookup for Compose Canvas draw passes.
     * Disk reads and decoding happen asynchronously on Dispatchers.IO via fetchTileAsync.
     */
    fun getCachedTile(context: Context, z: Int, x: Int, y: Int): ImageBitmap? {
        return tileCache.get("$z/$x/$y")
    }

    /**
     * Fetch a 256x256 PNG tile asynchronously if internet is enabled.
     * Always checks `File(context.filesDir, "osm_tiles/$z/$x/$y.png")` first on Dispatchers.IO
     * so downloaded areas render 100% offline without blocking the main thread.
     */
    fun fetchTileAsync(
        context: Context,
        z: Int,
        x: Int,
        y: Int,
        scope: CoroutineScope,
        isInternetEnabled: Boolean,
        onTileLoaded: (z: Int, x: Int, y: Int, bitmap: ImageBitmap) -> Unit
    ) {
        val maxTile = (1 shl z)
        if (x < 0 || x >= maxTile || y < 0 || y >= maxTile) return

        val key = "$z/$x/$y"
        if (tileCache.get(key) != null) return

        synchronized(inFlightKeys) {
            if (!inFlightKeys.add(key)) return
        }

        scope.launch(Dispatchers.IO) {
            try {
                // Check persistent disk cache first (off main thread)
                val primaryFile = getTileFile(context, z, x, y)
                val legacyFile = getLegacyCacheTileFile(context, z, x, y)
                val existingFile = when {
                    primaryFile.exists() && primaryFile.length() > 0L -> primaryFile
                    legacyFile.exists() && legacyFile.length() > 0L -> legacyFile
                    else -> null
                }

                if (existingFile != null) {
                    val bmp = BitmapFactory.decodeFile(existingFile.absolutePath)
                    if (bmp != null) {
                        val imgBmp = bmp.asImageBitmap()
                        tileCache.put(key, imgBmp)
                        withContext(Dispatchers.Main) {
                            onTileLoaded(z, x, y, imgBmp)
                        }
                        return@launch
                    }
                }

                // If offline and not on disk, do NOT initiate HTTP requests
                if (!isInternetEnabled) return@launch

                var loadedBitmap: Bitmap? = null
                for (server in TILE_SERVERS) {
                    if (!isInternetEnabled) break
                    var conn: HttpURLConnection? = null
                    try {
                        val urlStr = "$server/$z/$x/$y.png"
                        val url = URL(urlStr)
                        conn = (url.openConnection() as HttpURLConnection).apply {
                            setRequestProperty("User-Agent", "PulseMesh-Tactical-Android")
                            setRequestProperty("Accept", "image/png,image/*;q=0.8")
                            connectTimeout = 4000
                            readTimeout = 4500
                            instanceFollowRedirects = true
                        }

                        if (conn.responseCode in 200..299) {
                            val bytes = conn.inputStream.use { it.readBytes() }
                            if (bytes.isNotEmpty()) {
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bmp != null) {
                                    loadedBitmap = bmp
                                    runCatching {
                                        primaryFile.parentFile?.mkdirs()
                                        FileOutputStream(primaryFile).use { out ->
                                            out.write(bytes)
                                        }
                                    }
                                }
                            }
                        }
                        if (loadedBitmap != null) break
                    } catch (_: Exception) {
                        // Try next mirror
                    } finally {
                        runCatching { conn?.disconnect() }
                    }
                }

                if (loadedBitmap != null) {
                    val imageBmp = loadedBitmap.asImageBitmap()
                    tileCache.put(key, imageBmp)
                    withContext(Dispatchers.Main) {
                        onTileLoaded(z, x, y, imageBmp)
                    }
                }
            } finally {
                synchronized(inFlightKeys) {
                    inFlightKeys.remove(key)
                }
            }
        }
    }

    /**
     * Convert Latitude & Longitude to exact double tile coordinates (X, Y) at zoom level Z.
     */
    fun latLonToTileCoords(lat: Double, lon: Double, z: Int): Pair<Double, Double> {
        val n = (1 shl z).toDouble()
        val x = (lon + 180.0) / 360.0 * n
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n
        return Pair(x, y)
    }

    /**
     * Convert Latitude & Longitude to exact integer Slippy Tile indices (x, y) at zoom level Z.
     */
    fun latLonToSlippyTileInts(lat: Double, lon: Double, z: Int): Pair<Int, Int> {
        val maxIdx = (1 shl z) - 1
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val x = floor((lon.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, maxIdx)
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * (1 shl z)).toInt().coerceIn(0, maxIdx)
        return Pair(x, y)
    }

    /**
     * Convert tile coordinate (X, Y) at zoom level Z back to (Latitude, Longitude).
     */
    fun tileCoordsToLatLon(tileX: Double, tileY: Double, z: Int): Pair<Double, Double> {
        val n = 2.0.pow(z.toDouble())
        val lon = tileX / n * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(Math.PI * (1.0 - 2.0 * tileY / n))))
        return Pair(lat, lon)
    }
}
