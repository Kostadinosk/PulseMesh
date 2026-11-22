// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.service

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GpsLockState(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val accuracy: Float? = null,
    val isLocked: Boolean = false,
    val isManualPin: Boolean = false,
    val statusMessage: String = "GPS Not Locked Yet — Tap 'Request GPS' or Set Location Pin"
)

class RealGpsLocationService(private val context: Context) {

    companion object {
        /**
         * Guards against the 800m coarse cell-tower offset bug:
         * Never let coarse LocationManager.NETWORK_PROVIDER (accuracy > 40m) overwrite a recent
         * LocationManager.GPS_PROVIDER fix (within 60s) or an accurate current fix (accuracy <= 35m).
         */
        fun shouldAcceptLocationUpdate(
            provider: String?,
            incomingAccuracy: Float,
            nowMs: Long,
            lastGpsFixTimeMs: Long,
            currentBestAccuracy: Float?
        ): Boolean {
            if (provider == LocationManager.NETWORK_PROVIDER) {
                val hasRecentGpsFix = lastGpsFixTimeMs > 0L && (nowMs - lastGpsFixTimeMs) <= 60_000L
                val hasAccurateFix = currentBestAccuracy != null && currentBestAccuracy <= 35f
                if ((hasRecentGpsFix || hasAccurateFix) && incomingAccuracy > 40f) {
                    return false
                }
            }
            return true
        }
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _gpsState = MutableStateFlow(GpsLockState())
    val gpsState: StateFlow<GpsLockState> = _gpsState.asStateFlow()

    private var isListening = false
    private var lastGpsFixTimeMs: Long = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val now = System.currentTimeMillis()
            val curAcc = _gpsState.value.accuracy

            if (!shouldAcceptLocationUpdate(location.provider, location.accuracy, now, lastGpsFixTimeMs, curAcc)) {
                // Ignore coarse cell-tower network triangulation update so it never jumps 800m away
                return
            }

            if (location.provider == LocationManager.GPS_PROVIDER) {
                lastGpsFixTimeMs = now
            }

            _gpsState.value = GpsLockState(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                accuracy = location.accuracy,
                isLocked = true,
                isManualPin = false,
                statusMessage = "GPS Locked (${location.provider?.uppercase() ?: "SAT"}): %.6f, %.6f (±%.0fm)".format(
                    location.latitude,
                    location.longitude,
                    location.accuracy
                )
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            if (_gpsState.value.isManualPin) return
            _gpsState.value = _gpsState.value.copy(
                statusMessage = "GPS Provider Disabled — Enable Location in Android Settings"
            )
        }
    }

    fun startWatchingLocation() {
        if (isListening) return
        val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            _gpsState.value = _gpsState.value.copy(
                statusMessage = "GPS Permission Not Granted — Tap 'GRANT PERMISSIONS' to activate telemetry"
            )
            return
        }

        try {
            val hasGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val hasNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (!hasGps && !hasNetwork) {
                _gpsState.value = _gpsState.value.copy(
                    statusMessage = "No Location Provider Available. Set pin manually on map."
                )
                return
            }

            // Prefer GPS_PROVIDER last known location over coarse NETWORK_PROVIDER
            val gpsLast = if (hasGps) locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null
            val netLast = if (hasNetwork) locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) else null

            val bestLastKnown: Location? = when {
                gpsLast != null && netLast != null -> {
                    if (gpsLast.accuracy <= 40f || netLast.accuracy > 50f) gpsLast else netLast
                }
                gpsLast != null -> gpsLast
                else -> netLast
            }

            bestLastKnown?.let { loc ->
                if (loc.provider == LocationManager.GPS_PROVIDER) {
                    lastGpsFixTimeMs = System.currentTimeMillis()
                }
                _gpsState.value = GpsLockState(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    altitude = loc.altitude,
                    accuracy = loc.accuracy,
                    isLocked = true,
                    isManualPin = false,
                    statusMessage = "GPS Locked: %.6f, %.6f (±%.0fm)".format(loc.latitude, loc.longitude, loc.accuracy)
                )
            }

            if (hasGps) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L,
                    0.5f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
            if (hasNetwork) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    5.0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
            isListening = true
        } catch (e: SecurityException) {
            _gpsState.value = _gpsState.value.copy(
                statusMessage = "GPS Permission Denied — Tap Grant GPS to activate telemetry"
            )
        } catch (e: Exception) {
            _gpsState.value = _gpsState.value.copy(
                statusMessage = "Location Service Error: ${e.localizedMessage}"
            )
        }
    }

    fun stopWatchingLocation() {
        if (!isListening) return
        try {
            locationManager.removeUpdates(locationListener)
            isListening = false
        } catch (_: Exception) {}
    }

    /**
     * Fallback for indoor / mock / manual mesh coordinate testing
     */
    fun setManualLocation(latitude: Double, longitude: Double) {
        lastGpsFixTimeMs = System.currentTimeMillis()
        _gpsState.value = GpsLockState(
            latitude = latitude,
            longitude = longitude,
            altitude = 120.0,
            accuracy = 5.0f,
            isLocked = true,
            isManualPin = true,
            statusMessage = "Manual Map Pin Set: %.6f, %.6f".format(latitude, longitude)
        )
    }
}
