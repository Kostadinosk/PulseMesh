// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.voice

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/**
 * Self-managed Android Telecom [ConnectionService] (`CAPABILITY_SELF_MANAGED`) so incoming and
 * outgoing PulseMesh encrypted voice calls integrate cleanly with native OS call audio routing,
 * Bluetooth headset answer/hangup controls, and cellular call coexistence (API 26+).
 */
class PulseMeshTelecomConnectionService : ConnectionService() {

    companion object {
        private const val ACCOUNT_ID = "pulsemesh_e2ee_voice"

        @Volatile
        private var currentConnection: PulseMeshCallConnection? = null

        @Volatile
        var onSystemAnswerRequested: ((String) -> Unit)? = null

        @Volatile
        var onSystemDisconnectRequested: ((String) -> Unit)? = null

        fun registerSelfManagedPhoneAccount(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            return runCatching {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return false
                val handle = PhoneAccountHandle(
                    ComponentName(context, PulseMeshTelecomConnectionService::class.java),
                    ACCOUNT_ID
                )
                val account = PhoneAccount.builder(handle, "PulseMesh E2EE")
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .build()
                tm.registerPhoneAccount(account)
                true
            }.getOrDefault(false)
        }

        @SuppressLint("MissingPermission")
        fun reportOutgoingCall(context: Context, callId: String, peerLabel: String) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            runCatching {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
                val handle = PhoneAccountHandle(
                    ComponentName(context, PulseMeshTelecomConnectionService::class.java),
                    ACCOUNT_ID
                )
                val extras = Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                    putString("pulsemesh_call_id", callId)
                    putString("pulsemesh_peer_label", peerLabel)
                }
                tm.placeCall(Uri.fromParts("sip", peerLabel.ifBlank { "pulsemesh" }, null), extras)
            }
        }

        fun reportIncomingCall(context: Context, callId: String, peerLabel: String) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            runCatching {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
                val handle = PhoneAccountHandle(
                    ComponentName(context, PulseMeshTelecomConnectionService::class.java),
                    ACCOUNT_ID
                )
                val extras = Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("sip", peerLabel.ifBlank { "pulsemesh" }, null))
                    putString("pulsemesh_call_id", callId)
                    putString("pulsemesh_peer_label", peerLabel)
                }
                tm.addNewIncomingCall(handle, extras)
            }
        }

        fun markCallActive() {
            currentConnection?.setActive()
        }

        fun markCallEnded() {
            runCatching {
                currentConnection?.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
                currentConnection?.destroy()
            }
            currentConnection = null
        }
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        return createConnection(request, isIncoming = true)
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        return createConnection(request, isIncoming = false)
    }

    private fun createConnection(request: ConnectionRequest?, isIncoming: Boolean): Connection {
        val callId = request?.extras?.getString("pulsemesh_call_id").orEmpty()
        val peerLabel = request?.extras?.getString("pulsemesh_peer_label").orEmpty().ifBlank { "PulseMesh Peer" }
        val conn = PulseMeshCallConnection(callId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            conn.connectionProperties = Connection.PROPERTY_SELF_MANAGED
        }
        conn.setCallerDisplayName(peerLabel, TelecomManager.PRESENTATION_ALLOWED)
        if (isIncoming) conn.setRinging() else conn.setDialing()
        currentConnection = conn
        return conn
    }

    private class PulseMeshCallConnection(
        private val callId: String
    ) : Connection() {
        override fun onAnswer() {
            super.onAnswer()
            setActive()
            onSystemAnswerRequested?.invoke(callId)
        }

        override fun onReject() {
            super.onReject()
            setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
            destroy()
            onSystemDisconnectRequested?.invoke(callId)
        }

        override fun onDisconnect() {
            super.onDisconnect()
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
            onSystemDisconnectRequested?.invoke(callId)
        }
    }
}
