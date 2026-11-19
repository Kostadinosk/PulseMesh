// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity

/**
 * PulseMesh v1.1 Persistent Off-Grid Background Relay Foreground Service.
 * Holds a partial CPU WakeLock and a Wi-Fi MulticastLock while running as a `connectedDevice`
 * Foreground Service so Android OEM battery killers do not suspend BLE scanning, GATT relaying,
 * or UDP 45888 / TCP 45889 subnet forwarding when the screen is off inside a backpack.
 */
class MeshForegroundRelayService : Service() {

    companion object {
        const val CHANNEL_ID = "pulsemesh_background_relay_v11"
        const val NOTIFICATION_ID = 45888
        const val ACTION_START_OR_UPDATE = "com.example.service.ACTION_RELAY_START_OR_UPDATE"
        const val ACTION_STOP = "com.example.service.ACTION_RELAY_STOP"
        const val EXTRA_PEER_COUNT = "extra_peer_count"
        const val EXTRA_DTN_COUNT = "extra_dtn_count"
        const val EXTRA_STATUS_SUMMARY = "extra_status_summary"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startOrUpdate(
            context: Context,
            peerCount: Int,
            dtnCount: Int,
            statusSummary: String = "BLE 0xCAFE + Wi-Fi UDP 45888 Relay Active"
        ) {
            runCatching {
                val intent = Intent(context, MeshForegroundRelayService::class.java).apply {
                    action = ACTION_START_OR_UPDATE
                    putExtra(EXTRA_PEER_COUNT, peerCount)
                    putExtra(EXTRA_DTN_COUNT, dtnCount)
                    putExtra(EXTRA_STATUS_SUMMARY, statusSummary)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                val intent = Intent(context, MeshForegroundRelayService::class.java).apply {
                    action = ACTION_STOP
                }
                context.stopService(intent)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        acquireLocks()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseLocks()
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val peerCount = intent?.getIntExtra(EXTRA_PEER_COUNT, 0) ?: 0
        val dtnCount = intent?.getIntExtra(EXTRA_DTN_COUNT, 0) ?: 0
        val summary = intent?.getStringExtra(EXTRA_STATUS_SUMMARY)
            ?.ifBlank { "BLE 0xCAFE + Wi-Fi UDP 45888 Relay Active" }
            ?: "BLE 0xCAFE + Wi-Fi UDP 45888 Relay Active"

        val notification = buildNotification(peerCount, dtnCount, summary)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isRunning = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PulseMesh Off-Grid Relay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps off-grid BLE and local Wi-Fi mesh relaying active while the screen is off"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(peerCount: Int, dtnCount: Int, summary: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentPi = PendingIntent.getActivity(this, 1001, openIntent, piFlags)

        val title = "PulseMesh Relay Active ($peerCount peers • $dtnCount DTN queued)"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(summary)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPi)
            .build()
    }

    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock == null && pm != null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "PulseMesh:OffGridRelayWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L)
                }
            }
        }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (multicastLock == null && wm != null) {
                multicastLock = wm.createMulticastLock("PulseMesh:SubnetMulticastLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
        }
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
            multicastLock = null
        }
    }
}
