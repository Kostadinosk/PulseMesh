// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.CallSignalingState
import com.example.service.DuplexCallState
import com.example.ui.theme.*
import com.example.viewmodel.MeshUiState

/**
 * Full-Screen Tactical Lock-Screen Call Cover:
 * Automatically displayed when the device keyguard is locked (`KeyguardManager.isKeyguardLocked`)
 * and an incoming or active mesh voice call is in progress, allowing 1-tap Answer / Decline / Mute / Speaker / End Call
 * without unlocking the phone or exposing map/chat/settings tabs on the lock screen.
 */
@Composable
fun LockScreenCallCoverOverlay(
    state: MeshUiState,
    duplexCallState: DuplexCallState,
    onAcceptCall: () -> Unit,
    onDeclineCall: () -> Unit,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onDismissToApp: () -> Unit,
    modifier: Modifier = Modifier,
    isDeviceLocked: Boolean = false
) {
    val p = LocalPulsePalette.current
    val signalingState = if (state.callState != CallSignalingState.IDLE) {
        state.callState
    } else {
        duplexCallState.signalingState
    }
    val isIncoming = signalingState == CallSignalingState.RINGING_INCOMING
    val isRingingOut = signalingState == CallSignalingState.RINGING_OUTGOING

    val peerCallsign = duplexCallState.peerCallsign
        .ifBlank { state.remoteCallerName }
        .ifBlank { state.incomingPrivateCall?.callsign ?: state.activePrivateCallNode?.callsign ?: "Mesh Operator" }
    val peerId = duplexCallState.peerNodeId
        .ifBlank { state.remoteCallerId }
        .ifBlank { state.incomingPrivateCall?.id ?: state.activePrivateCallNode?.id ?: "" }

    val mins = duplexCallState.durationSeconds / 60
    val secs = duplexCallState.durationSeconds % 60
    val timerFormatted = "%02d:%02d".format(mins, secs)

    val transportBadge = when {
        duplexCallState.isHighSpeedLink -> "WIFI 8kHz HD UDP"
        state.activeL2capChannelsCount > 0 -> "BLE L2CAP STREAM"
        else -> "BLE 0xCAFF MESH"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .aeroCanvasBackground(p)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp)
            .testTag("lock_screen_call_cover_overlay"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Lock-Screen Security & Transport Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = p.card,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, p.emerald.copy(alpha = 0.7f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Lock Screen Call Cover",
                            tint = p.emerald,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (isDeviceLocked) "LOCK SCREEN • CALLS ONLY" else "LOCK SCREEN CALL COVER",
                            color = p.emerald,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Surface(
                    color = p.card,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (duplexCallState.isHighSpeedLink) p.cyan else p.emerald
                    )
                ) {
                    Text(
                        text = transportBadge,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = if (duplexCallState.isHighSpeedLink) p.cyan else p.emerald,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Center Caller Avatar, Identity & Live Audio Status (scroll-safe on compact viewports)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp)
            ) {
                if (p.isAero) {
                    AeroGlossBadgeIcon(
                        imageVector = when {
                            isIncoming -> Icons.Default.RingVolume
                            isRingingOut -> Icons.Default.PhoneInTalk
                            else -> Icons.Default.HeadsetMic
                        },
                        accentColor = if (isIncoming) p.emerald else p.cyan,
                        contentDescription = peerCallsign,
                        badgeSize = 104.dp,
                        iconSize = 48.dp,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .clip(CircleShape)
                            .background(if (isIncoming) p.emeraldDim else p.cyanDim)
                            .border(
                                3.dp,
                                if (isIncoming) p.emerald else p.cyan,
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                isIncoming -> Icons.Default.RingVolume
                                isRingingOut -> Icons.Default.PhoneInTalk
                                else -> Icons.Default.HeadsetMic
                            },
                            contentDescription = peerCallsign,
                            tint = if (isIncoming) p.emerald else p.cyan,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                Text(
                    text = when {
                        isIncoming -> "INCOMING TACTICAL CALL"
                        isRingingOut -> "CALLING PEER..."
                        else -> "LIVE MESH CALL • $timerFormatted"
                    },
                    color = if (isIncoming) p.emerald else p.cyan,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )

                Text(
                    text = peerCallsign,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = p.textPrimary,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 28.sp,
                    textAlign = TextAlign.Center
                )

                if (peerId.isNotBlank()) {
                    Text(
                        text = "NODE $peerId • E2EE VOICE",
                        color = p.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }

                if (!isIncoming && !isRingingOut) {
                    val ackReady = state.callAckConfirmed ||
                        duplexCallState.isCallAcceptAcked ||
                        duplexCallState.voiceFramesPlayed > 0
                    Surface(
                        color = if (ackReady) p.emeraldDim else p.amberDim,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (ackReady) p.emerald else p.amber
                        )
                    ) {
                        Text(
                            text = if (ackReady) "HANDSHAKE ACK VERIFIED ✓" else "SYNCING HANDSHAKE ACK…",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            color = if (ackReady) p.emerald else p.amber,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Bottom Oversized Lock-Screen Call Controls
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isIncoming) {
                    val lockIncomingShape = RoundedCornerShape(24.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Button(
                            onClick = onDeclineCall,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (p.isAero) Color.Transparent else p.red,
                                contentColor = Color.White
                            ),
                            shape = lockIncomingShape,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .aeroGlossOverlay(p, p.red, lockIncomingShape, cornerRadius = 24.dp)
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = "Decline")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "DECLINE",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp
                            )
                        }

                        Button(
                            onClick = onAcceptCall,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (p.isAero) Color.Transparent else p.emerald,
                                contentColor = if (p.isLight || p.isAero) Color.White else Color.Black
                            ),
                            shape = lockIncomingShape,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .aeroGlossOverlay(p, p.emerald, lockIncomingShape, cornerRadius = 24.dp)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = "Accept")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ANSWER",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onToggleMute,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (duplexCallState.isMuted) p.redDim else p.card,
                                contentColor = if (duplexCallState.isMuted) p.red else p.textPrimary
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                if (duplexCallState.isMuted) p.red else p.border
                            ),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp)
                        ) {
                            Icon(
                                imageVector = if (duplexCallState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Mute"
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (duplexCallState.isMuted) "MUTED" else "MUTE",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        OutlinedButton(
                            onClick = onToggleSpeaker,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (duplexCallState.isSpeakerphoneOn) p.cyanDim else p.card,
                                contentColor = if (duplexCallState.isSpeakerphoneOn) p.cyan else p.textPrimary
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                if (duplexCallState.isSpeakerphoneOn) p.cyan else p.border
                            ),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp)
                        ) {
                            Icon(
                                imageVector = if (duplexCallState.isSpeakerphoneOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.PhoneInTalk,
                                contentDescription = "Speaker"
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (duplexCallState.isSpeakerphoneOn) "SPEAKER" else "EARPIECE",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    val lockEndShape = RoundedCornerShape(22.dp)
                    Button(
                        onClick = onEndCall,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (p.isAero) Color.Transparent else p.red,
                            contentColor = Color.White
                        ),
                        shape = lockEndShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(62.dp)
                            .aeroGlossOverlay(p, p.red, lockEndShape, cornerRadius = 22.dp)
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "End Call")
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "END CALL",
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 15.sp
                        )
                    }
                }

                TextButton(
                    onClick = onDismissToApp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Icon(
                        imageVector = if (isDeviceLocked) Icons.Default.LockOpen else Icons.Default.OpenInFull,
                        contentDescription = if (isDeviceLocked) "Unlock & Open App" else "Open Full Console",
                        tint = p.textSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isDeviceLocked) "UNLOCK TO ACCESS CONSOLE" else "OPEN FULL CONSOLE",
                        color = p.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
