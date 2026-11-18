package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.example.MainActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class CallNotificationAction {
    ACCEPT,
    DECLINE,
    END,
    MUTE_TOGGLE
}

data class ChatNotificationReply(
    val threadId: String,
    val text: String
)

class TacticalNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val actionStr = intent?.action ?: return
        when (actionStr) {
            TacticalNotificationManager.ACTION_ACCEPT_CALL -> {
                TacticalNotificationManager.cancelCallNotification(context)
                TacticalNotificationManager.emitCallAction(CallNotificationAction.ACCEPT)
                // Also bring MainActivity to front so audio capture can run immediately
                val launch = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(TacticalNotificationManager.EXTRA_CALL_ACTION, TacticalNotificationManager.ACTION_ACCEPT_CALL)
                }
                runCatching { context.startActivity(launch) }
            }
            TacticalNotificationManager.ACTION_DECLINE_CALL -> {
                TacticalNotificationManager.emitCallAction(CallNotificationAction.DECLINE)
                TacticalNotificationManager.cancelCallNotification(context)
            }
            TacticalNotificationManager.ACTION_END_CALL -> {
                TacticalNotificationManager.emitCallAction(CallNotificationAction.END)
                TacticalNotificationManager.cancelCallNotification(context)
            }
            TacticalNotificationManager.ACTION_TOGGLE_MUTE -> {
                TacticalNotificationManager.emitCallAction(CallNotificationAction.MUTE_TOGGLE)
            }
            TacticalNotificationManager.ACTION_MARK_CHAT_READ -> {
                val threadId = intent.getStringExtra(TacticalNotificationManager.EXTRA_OPEN_THREAD_ID).orEmpty()
                val notifId = intent.getIntExtra(TacticalNotificationManager.EXTRA_NOTIFICATION_ID, -1)
                if (notifId != -1) {
                    runCatching { NotificationManagerCompat.from(context).cancel(notifId) }
                }
                if (threadId.isNotBlank()) {
                    TacticalNotificationManager.cancelChatNotification(context, threadId)
                    TacticalNotificationManager.emitMarkThreadRead(threadId)
                }
            }
            TacticalNotificationManager.ACTION_REPLY_CHAT -> {
                val threadId = intent.getStringExtra(TacticalNotificationManager.EXTRA_OPEN_THREAD_ID).orEmpty()
                val notifId = intent.getIntExtra(TacticalNotificationManager.EXTRA_NOTIFICATION_ID, -1)
                val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
                val replyText = remoteInputResults
                    ?.getCharSequence(TacticalNotificationManager.EXTRA_REPLY_TEXT)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (replyText.isNotBlank() && threadId.isNotBlank()) {
                    TacticalNotificationManager.emitChatReply(threadId, replyText)
                    TacticalNotificationManager.emitMarkThreadRead(threadId)
                }
                if (notifId != -1) {
                    runCatching { NotificationManagerCompat.from(context).cancel(notifId) }
                }
                if (threadId.isNotBlank()) {
                    TacticalNotificationManager.cancelChatNotification(context, threadId)
                }
            }
        }
    }
}

/**
 * Manages:
 * 1. High-Priority Lock-Screen Incoming & Ongoing Call Notifications (`fullScreenIntent`, `CATEGORY_CALL`,
 *    `CallStyle` on Android 12+ with Accept / Decline / Hang Up actions).
 * 2. Tactical Mesh Chat Notifications (`CHANNEL_CHAT_MESSAGES`, `CATEGORY_MESSAGE`) with direct jump into the chat thread
 *    and a 1-tap "MARK AS READ" action button.
 */
class TacticalNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_CALLS = "pulsemesh_tactical_calls_v1"
        const val CHANNEL_CHATS = "pulsemesh_tactical_chats_v1"
        const val CHANNEL_AUTO_CHECKIN = "pulsemesh_auto_checkin_silent_v1"

        const val NOTIFICATION_ID_CALL = 45880
        const val NOTIFICATION_ID_AUTO_CHECKIN = 45885
        private const val NOTIFICATION_ID_CHAT_BASE = 45900

        const val ACTION_ACCEPT_CALL = "com.example.action.ACCEPT_CALL"
        const val ACTION_DECLINE_CALL = "com.example.action.DECLINE_CALL"
        const val ACTION_END_CALL = "com.example.action.END_CALL"
        const val ACTION_TOGGLE_MUTE = "com.example.action.TOGGLE_MUTE"
        const val ACTION_MARK_CHAT_READ = "com.example.action.MARK_CHAT_READ"
        const val ACTION_REPLY_CHAT = "com.example.action.REPLY_CHAT"

        const val EXTRA_OPEN_THREAD_ID = "extra_open_thread_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_OPEN_VOICE_TAB = "extra_open_voice_tab"
        const val EXTRA_CALL_ACTION = "extra_call_action"
        const val EXTRA_REPLY_TEXT = "extra_reply_text"

        private val _callActions = MutableSharedFlow<CallNotificationAction>(extraBufferCapacity = 8)
        val callActions: SharedFlow<CallNotificationAction> = _callActions.asSharedFlow()

        private val _markThreadReadEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val markThreadReadEvents: SharedFlow<String> = _markThreadReadEvents.asSharedFlow()

        private val _chatReplyEvents = MutableSharedFlow<ChatNotificationReply>(extraBufferCapacity = 16)
        val chatReplyEvents: SharedFlow<ChatNotificationReply> = _chatReplyEvents.asSharedFlow()

        fun emitCallAction(action: CallNotificationAction) {
            _callActions.tryEmit(action)
        }

        fun emitMarkThreadRead(threadId: String) {
            if (threadId.isNotBlank()) {
                _markThreadReadEvents.tryEmit(threadId)
            }
        }

        fun emitChatReply(threadId: String, text: String) {
            if (threadId.isNotBlank() && text.isNotBlank()) {
                _chatReplyEvents.tryEmit(ChatNotificationReply(threadId, text))
            }
        }

        fun notificationIdForThread(threadId: String): Int {
            return NOTIFICATION_ID_CHAT_BASE + (threadId.hashCode() and 0x3FF)
        }

        fun cancelCallNotification(context: Context) {
            runCatching {
                NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_CALL)
            }
        }

        fun cancelChatNotification(context: Context, threadId: String) {
            if (threadId.isBlank()) return
            runCatching {
                NotificationManagerCompat.from(context).cancel(notificationIdForThread(threadId))
            }
        }
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    init {
        ensureChannelsCreated()
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun ensureChannelsCreated() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = notificationManager ?: return

        val callChannel = NotificationChannel(
            CHANNEL_CALLS,
            "Tactical Mesh Voice Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Full-screen lock-screen alerts for incoming and active off-grid mesh calls"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            setSound(null, null) // Ringtone is managed by TacticalCallService
        }

        val chatChannel = NotificationChannel(
            CHANNEL_CHATS,
            "Tactical Mesh Chat Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Encrypted & off-grid mesh chat message notifications"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            enableVibration(true)
        }

        val checkInChannel = NotificationChannel(
            CHANNEL_AUTO_CHECKIN,
            "Silent Auto Check-In Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Silent status updates when periodic tactical check-in beacons are transmitted"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            enableVibration(false)
            setSound(null, null)
            setShowBadge(false)
        }

        runCatching {
            mgr.createNotificationChannel(callChannel)
            mgr.createNotificationChannel(chatChannel)
            mgr.createNotificationChannel(checkInChannel)
        }
    }

    @SuppressLint("MissingPermission")
    fun showIncomingCallNotification(
        callerNodeId: String,
        callerCallsign: String,
        transportLabel: String = "Off-Grid Mesh"
    ) {
        if (!hasNotificationPermission()) return
        ensureChannelsCreated()

        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_VOICE_TAB, true)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            100,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptIntent = Intent(context, TacticalNotificationActionReceiver::class.java).apply {
            action = ACTION_ACCEPT_CALL
        }
        val acceptPendingIntent = PendingIntent.getBroadcast(
            context,
            101,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(context, TacticalNotificationActionReceiver::class.java).apply {
            action = ACTION_DECLINE_CALL
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            102,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callerPerson = Person.Builder()
            .setName(callerCallsign.ifBlank { callerNodeId.ifBlank { "Mesh Operator" } })
            .setImportant(true)
            .build()

        val builder = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("INCOMING MESH CALL • $callerCallsign")
            .setContentText("Node $callerNodeId ($transportLabel) — Tap to Answer")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    callerPerson,
                    declinePendingIntent,
                    acceptPendingIntent
                )
            )
        } else {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "DECLINE", declinePendingIntent)
            builder.addAction(android.R.drawable.sym_action_call, "ACCEPT", acceptPendingIntent)
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_CALL, builder.build())
        }
    }

    @SuppressLint("MissingPermission")
    fun showOngoingCallNotification(
        peerNodeId: String,
        peerCallsign: String,
        isMuted: Boolean,
        transportLabel: String
    ) {
        if (!hasNotificationPermission()) return
        ensureChannelsCreated()

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_VOICE_TAB, true)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            103,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endIntent = Intent(context, TacticalNotificationActionReceiver::class.java).apply {
            action = ACTION_END_CALL
        }
        val endPendingIntent = PendingIntent.getBroadcast(
            context,
            104,
            endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(context, TacticalNotificationActionReceiver::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val mutePendingIntent = PendingIntent.getBroadcast(
            context,
            105,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val peerPerson = Person.Builder()
            .setName(peerCallsign.ifBlank { peerNodeId.ifBlank { "Mesh Operator" } })
            .setImportant(true)
            .build()

        val statusSubtitle = buildString {
            append(if (isMuted) "MIC MUTED • " else "LIVE VOICE • ")
            append(transportLabel)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("ACTIVE CALL • $peerCallsign")
            .setContentText(statusSubtitle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setContentIntent(openPendingIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    peerPerson,
                    endPendingIntent
                )
            )
            builder.addAction(
                android.R.drawable.ic_lock_silent_mode,
                if (isMuted) "UNMUTE" else "MUTE",
                mutePendingIntent
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_lock_silent_mode,
                if (isMuted) "UNMUTE" else "MUTE",
                mutePendingIntent
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "END CALL", endPendingIntent)
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_CALL, builder.build())
        }
    }

    fun clearCallNotification() {
        cancelCallNotification(context)
    }

    fun cancelChatNotificationForThread(threadId: String) {
        cancelChatNotification(context, threadId)
    }

    @SuppressLint("MissingPermission")
    fun showChatNotification(
        threadId: String,
        senderCallsign: String,
        senderNodeId: String,
        messagePreview: String,
        transport: String
    ) {
        if (!hasNotificationPermission()) return
        ensureChannelsCreated()

        val cleanPreview = when {
            messagePreview.startsWith("[[PM_IMAGE]]:") || messagePreview.startsWith("[[PM_IMG]]:") -> "📷 Sent an off-grid tactical photo"
            messagePreview.startsWith("[[PM_VOICEDROP]]:") || messagePreview.startsWith("[[PM_AUDIO]]:") -> "🎙️ Sent a 3s voice drop memo"
            else -> messagePreview
        }

        val notifId = notificationIdForThread(threadId)
        val openChatIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_THREAD_ID, threadId)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            openChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val markReadIntent = Intent(context, TacticalNotificationActionReceiver::class.java).apply {
            action = ACTION_MARK_CHAT_READ
            putExtra(EXTRA_OPEN_THREAD_ID, threadId)
            putExtra(EXTRA_NOTIFICATION_ID, notifId)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 1000,
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val remoteInput = RemoteInput.Builder(EXTRA_REPLY_TEXT)
            .setLabel("Reply to $senderCallsign...")
            .build()
        val replyIntent = Intent(context, TacticalNotificationActionReceiver::class.java).apply {
            action = ACTION_REPLY_CHAT
            putExtra(EXTRA_OPEN_THREAD_ID, threadId)
            putExtra(EXTRA_NOTIFICATION_ID, notifId)
        }
        val replyFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 2000,
            replyIntent,
            replyFlags
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "REPLY",
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        val builder = NotificationCompat.Builder(context, CHANNEL_CHATS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("$senderCallsign ($senderNodeId)")
            .setContentText(cleanPreview)
            .setSubText(transport)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cleanPreview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(replyAction)
            .addAction(
                android.R.drawable.ic_menu_view,
                "MARK AS READ",
                markReadPendingIntent
            )

        runCatching {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        }
    }

    /**
     * Posts a completely silent (no sound, no vibration, no heads-up) status notification
     * whenever the Automated Tactical Check-In Beacon transmits.
     */
    @SuppressLint("MissingPermission")
    fun showSilentAutoCheckInNotification(
        callsign: String,
        intervalMin: Int,
        batteryPct: Int,
        locationSummary: String
    ) {
        if (!hasNotificationPermission()) return
        ensureChannelsCreated()

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_AUTO_CHECKIN,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis()))

        val builder = NotificationCompat.Builder(context, CHANNEL_AUTO_CHECKIN)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Auto Check-In Sent (${intervalMin}m timer • $timeStr)")
            .setContentText("$callsign • ⚡$batteryPct% • $locationSummary")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_AUTO_CHECKIN, builder.build())
        }
    }
}
