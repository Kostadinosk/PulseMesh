package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.CallSignalingState
import com.example.service.DuplexCallState
import com.example.ui.theme.*
import com.example.viewmodel.MeshUiState

@Composable
fun PersistentMiniCallBar(
    state: MeshUiState,
    duplexCallState: DuplexCallState,
    onReturnToCallScreen: () -> Unit,
    onToggleMute: () -> Unit,
    onAcceptCall: () -> Unit,
    onDeclineCall: () -> Unit,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalPulsePalette.current
    val effectiveSignaling = if (state.callState != CallSignalingState.IDLE) {
        state.callState
    } else {
        duplexCallState.signalingState
    }

    val isVisible = effectiveSignaling != CallSignalingState.IDLE && state.selectedTabIndex != 2

    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
    ) {
        val peerName = duplexCallState.peerCallsign
            .ifBlank { state.remoteCallerName }
            .ifBlank { state.incomingPrivateCall?.callsign ?: state.activePrivateCallNode?.callsign ?: "Peer" }

        val mins = duplexCallState.durationSeconds / 60
        val secs = duplexCallState.durationSeconds % 60
        val timerText = "%02d:%02d".format(mins, secs)

        val accentColor = when (effectiveSignaling) {
            CallSignalingState.RINGING_INCOMING -> p.emerald
            CallSignalingState.RINGING_OUTGOING -> p.cyan
            CallSignalingState.IN_CALL_ACTIVE -> when {
                duplexCallState.isMuted -> p.red
                duplexCallState.isPushToOverrideActive -> p.amber
                duplexCallState.isVoiceTransmitting -> p.emerald
                !duplexCallState.isHighSpeedLink && duplexCallState.isPeerSpeaking -> p.amber
                duplexCallState.isPeerSpeaking -> p.cyan
                else -> p.emerald
            }
            CallSignalingState.IDLE -> p.emerald
        }

        val statusSummary = when (effectiveSignaling) {
            CallSignalingState.RINGING_INCOMING -> "INCOMING • $peerName"
            CallSignalingState.RINGING_OUTGOING -> "CALLING • $peerName..."
            CallSignalingState.IN_CALL_ACTIVE -> {
                val floorShort = when {
                    duplexCallState.isMuted -> "MUTED"
                    duplexCallState.isPushToOverrideActive -> "OVERRIDE TX"
                    duplexCallState.isVoiceTransmitting -> "TX LIVE"
                    !duplexCallState.isHighSpeedLink && duplexCallState.isPeerSpeaking -> "RX YIELD"
                    duplexCallState.isPeerSpeaking -> "RX LIVE"
                    else -> "READY"
                }
                "$timerText • $peerName • $floorShort"
            }
            CallSignalingState.IDLE -> ""
        }

        val actionForegroundOnAccent = if (p.isLight || p.isAero) Color.White else Color.Black

        Surface(
            color = if (p.isAero) Color.Transparent else p.card,
            modifier = Modifier
                .fillMaxWidth()
                .aeroGlassPanel(p, cornerRadius = 0.dp, borderColor = accentColor.copy(alpha = 0.75f))
                .border(1.dp, accentColor.copy(alpha = 0.7f))
                .clickable { onReturnToCallScreen() }
                .testTag("persistent_mini_call_bar")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusSummary,
                        color = p.textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Tap to open call view",
                        color = accentColor,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (effectiveSignaling == CallSignalingState.RINGING_INCOMING) {
                    IconButton(
                        onClick = onDeclineCall,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(p.red)
                            .aeroGlossOverlay(p, p.red, CircleShape, cornerRadius = 24.dp)
                            .testTag("mini_call_decline_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "Decline Call",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onAcceptCall,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(p.emerald)
                            .aeroGlossOverlay(p, p.emerald, CircleShape, cornerRadius = 24.dp)
                            .testTag("mini_call_accept_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Accept Call",
                            tint = actionForegroundOnAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    if (effectiveSignaling == CallSignalingState.IN_CALL_ACTIVE) {
                        IconButton(
                            onClick = onToggleMute,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    if (duplexCallState.isMuted) p.redDim else p.surfaceVariant
                                )
                                .border(
                                    1.dp,
                                    if (duplexCallState.isMuted) p.red else p.border,
                                    CircleShape
                                )
                                .testTag("mini_call_mute_button")
                        ) {
                            Icon(
                                imageVector = if (duplexCallState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = if (duplexCallState.isMuted) "Unmute" else "Mute",
                                tint = if (duplexCallState.isMuted) p.red else p.textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onEndCall,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(p.red)
                            .aeroGlossOverlay(p, p.red, CircleShape, cornerRadius = 24.dp)
                            .testTag("mini_call_end_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "End Call",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
