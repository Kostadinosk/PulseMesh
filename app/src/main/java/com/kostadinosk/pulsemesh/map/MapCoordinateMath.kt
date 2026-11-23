// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

enum class MapLayerStyle(val label: String) {
    DARK("Dark"),
    TOPO("Topo"),
    SATELLITE("Satellite"),
    RADAR("Radar")
}

/**
 * Exact Web Mercator projection, inverse Mercator unprojection, Haversine distance,
 * and great-circle initial bearing helpers for Tactical Map 2.0.
 */
object MapCoordinateMath {

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val MAX_MERCATOR_LAT = 85.05112878

    fun latLonToTile(lat: Double, lon: Double, zoom: Double): Pair<Double, Double> {
        val clampedLat = lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
        val n = 2.0.pow(zoom)
        val x = ((lon + 180.0) / 360.0) * n
        val latRad = Math.toRadians(clampedLat)
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        return Pair(x.coerceIn(0.0, n), y.coerceIn(0.0, n))
    }

    fun tileToLatLon(tileX: Double, tileY: Double, zoom: Double): Pair<Double, Double> {
        val n = 2.0.pow(zoom)
        val wrappedX = ((tileX % n) + n) % n
        val clampedY = tileY.coerceIn(0.0, n)
        val lon = wrappedX / n * 360.0 - 180.0
        val latRad = atan(sinh(PI * (1.0 - 2.0 * clampedY / n)))
        val lat = Math.toDegrees(latRad).coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
        return Pair(lat, lon)
    }

    /**
     * Exact inverse Web Mercator screen-pixel offset -> geographic coordinates relative to view center.
     * Eliminates spherical `/ cos(lat)` projection drift during long-press waypoint drops and ruler picks.
     */
    fun pixelOffsetToLatLon(
        centerLat: Double,
        centerLon: Double,
        zoomLevel: Float,
        dxPx: Float,
        dyPx: Float
    ): Pair<Double, Double> {
        val zoom = zoomLevel.toDouble()
        val worldPixels = 256.0 * 2.0.pow(zoom)
        val (cxTile, cyTile) = latLonToTile(centerLat, centerLon, zoom)
        val targetTileX = cxTile + (dxPx.toDouble() / 256.0)
        val targetTileY = cyTile + (dyPx.toDouble() / 256.0)
        val n = worldPixels / 256.0
        return tileToLatLon(targetTileX, targetTileY.coerceIn(0.0, n), zoom)
    }

    fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2.0).pow(2) + cos(phi1) * cos(phi2) * sin(dLambda / 2.0).pow(2)
        val c = 2.0 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
        return EARTH_RADIUS_M * c
    }

    fun initialBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun cardinalDirection(bearingDeg: Double): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val normalized = ((bearingDeg % 360.0) + 360.0) % 360.0
        return dirs[((normalized + 22.5) / 45.0).toInt() % dirs.size]
    }
}
