// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.service

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
import com.kostadinosk.pulsemesh.MainActivity
import com.kostadinosk.pulsemesh.model.BatteryPowerProfile

/**
 * PulseMesh v1.5.0 Power-Managed Off-Grid Background Relay & Active Call Foreground Service.
 *
 * Replaces the legacy 12-hour continuous PARTIAL_WAKE_LOCK & permanent MulticastLock with:
 * - Profile-aware bounded burst WakeLocks (`EXPEDITION`: 5s, `BALANCED`: 15s, `TURBO`: 60s)
 * - Profile-aware MulticastLock (only held continuously in `TURBO` or active voice/LAN bursts)
 * - Dynamic FGS promotion to `connectedDevice | microphone | phoneCall` strictly while a voice
 *   call or PTT session is active, dropping back to `connectedDevice` when idle.
 */
class MeshForegroundRelayService : Service() {

    companion object {
        const val CHANNEL_ID = "pulsemesh_background_relay_v11"
        const val NOTIFICATION_ID = 45888
        const val ACTION_START_OR_UPDATE = "com.kostadinosk.pulsemesh.service.ACTION_RELAY_START_OR_UPDATE"
        const val ACTION_STOP = "com.kostadinosk.pulsemesh.service.ACTION_RELAY_STOP"
        const val EXTRA_PEER_COUNT = "extra_peer_count"
        const val EXTRA_DTN_COUNT = "extra_dtn_count"
        const val EXTRA_STATUS_SUMMARY = "extra_status_summary"
        const val EXTRA_BATTERY_PROFILE = "extra_battery_profile"
        const val EXTRA_ACTIVE_VOICE_CALL = "extra_active_voice_call"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isVoiceFgsActive: Boolean = false
            private set

        fun startOrUpdate(
            context: Context,
            peerCount: Int,
            dtnCount: Int,
            statusSummary: String = "BLE 0xCAFE + Wi-Fi UDP 45888 Relay Active",
            batteryProfile: BatteryPowerProfile = BatteryPowerProfile.BALANCED,
            activeVoiceCall: Boolean = false
        ) {
            runCatching {
                val intent = Intent(context, MeshForegroundRelayService::class.java).apply {
                    action = ACTION_START_OR_UPDATE
                    putExtra(EXTRA_PEER_COUNT, peerCount)
                    putExtra(EXTRA_DTN_COUNT, dtnCount)
                    putExtra(EXTRA_STATUS_SUMMARY, statusSummary)
                    putExtra(EXTRA_BATTERY_PROFILE, batteryProfile.name)
                    putExtra(EXTRA_ACTIVE_VOICE_CALL, activeVoiceCall)
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
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseLocks()
            isRunning = false
            isVoiceFgsActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val peerCount = intent?.getIntExtra(EXTRA_PEER_COUNT, 0) ?: 0
        val dtnCount = intent?.getIntExtra(EXTRA_DTN_COUNT, 0) ?: 0
        val profile = BatteryPowerProfile.fromName(intent?.getStringExtra(EXTRA_BATTERY_PROFILE))
        val activeVoice = intent?.getBooleanExtra(EXTRA_ACTIVE_VOICE_CALL, false) ?: false
        val summary = intent?.getStringExtra(EXTRA_STATUS_SUMMARY)
            ?.ifBlank { "Mesh Relay (${profile.label})" }
            ?: "Mesh Relay (${profile.label})"

        applyPowerProfileLocks(profile, activeVoice)

        val notification = buildNotification(peerCount, dtnCount, summary, activeVoice)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var fgsTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (activeVoice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fgsTypes = fgsTypes or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                }
                startForeground(NOTIFICATION_ID, notification, fgsTypes)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isRunning = true
            isVoiceFgsActive = activeVoice
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        isVoiceFgsActive = false
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
                description = "Keeps off-grid BLE, local Wi-Fi mesh relaying, and background calls active"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        peerCount: Int,
        dtnCount: Int,
        summary: String,
        activeVoice: Boolean
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentPi = PendingIntent.getActivity(this, 1001, openIntent, piFlags)

        val title = if (activeVoice) {
            "PulseMesh Call Active • $peerCount peers"
        } else {
            "PulseMesh Relay ($peerCount peers • $dtnCount DTN queued)"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(summary)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(if (activeVoice) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setCategory(if (activeVoice) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPi)
            .build()
    }

    private fun applyPowerProfileLocks(profile: BatteryPowerProfile, activeVoice: Boolean) {
        val shouldHoldMulticast = activeVoice || profile.allowPersistentMulticastLock
        if (shouldHoldMulticast) {
            runCatching {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (multicastLock == null && wm != null) {
                    multicastLock = wm.createMulticastLock("PulseMesh:SubnetMulticastLock").apply {
                        setReferenceCounted(false)
                    }
                }
                if (multicastLock?.isHeld == false) multicastLock?.acquire()
            }
        } else {
            runCatching {
                if (multicastLock?.isHeld == true) multicastLock?.release()
            }
        }

        // Acquire bounded burst WakeLock rather than a 12-hour infinite CPU lock
        val holdMs = if (activeVoice) 180_000L else profile.wakeLockBurstMs
        if (holdMs > 0L && (activeVoice || profile != BatteryPowerProfile.EXPEDITION)) {
            runCatching {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (wakeLock == null && pm != null) {
                    wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "PulseMesh:OffGridRelayWakeLock"
                    ).apply {
                        setReferenceCounted(false)
                    }
                }
                wakeLock?.acquire(holdMs)
            }
        } else {
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock?.release()
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
