// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.ui.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PhoneForwarded
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.MeshNode
import com.example.service.CallSignalingState
import com.example.service.DuplexCallState
import com.example.service.TacticalAudioService
import com.example.ui.components.AeroGlossBadgeIcon
import com.example.ui.theme.*
import com.example.viewmodel.MeshUiState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class VoiceScreenMode {
    SIMPLE_CALL,
    WALKIE_TALKIE_PTT
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Suppress("UNUSED_PARAMETER", "Destructure")
@Composable
fun SimpleCallVoiceScreen(
    state: MeshUiState,
    duplexCallState: DuplexCallState,
    audioService: TacticalAudioService,
    onStartCall: (MeshNode) -> Unit,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onStartPtt: () -> Unit,
    onStopPtt: () -> Unit,
    appLanguage: String = "en",
    modifier: Modifier = Modifier,
    onStartGroupCall: () -> Unit = {},
    onToggleLiveMicrophone: () -> Unit = {},
    onAcceptCall: () -> Unit = {},
    onDeclineCall: () -> Unit = {},
    onTestMicLoopback: () -> Unit = {},
    onToggleHotspot: () -> Unit = {},
    onPushToOverride: (Boolean) -> Unit = {},
    onReplayLastRx: () -> Unit = {},
    onReplayLastCallRx: () -> Unit = onReplayLastRx,
    onReplayLastPttRx: () -> Unit = onReplayLastRx,
    onHealHotspot: () -> Unit = {},
    onCycleVoiceClarity: () -> Unit = {},
    onOpenLockScreenCover: () -> Unit = {},
    onToggleBell202Modem: () -> Unit = {},
    onTransmitBell202Message: (text: String, isSos: Boolean) -> Unit = { _, _ -> },
    onLeaveVoiceDrop: (peerId: String, peerCallsign: String) -> Unit = { _, _ -> },
) {
    val p = LocalPulsePalette.current
    val cardShape = tacticalCardShape(state.isSimpleMode, p.isOriginal, p.isAero)
    val btnShape = tacticalButtonShape(state.isSimpleMode, p.isOriginal, p.isAero)
    var voiceMode by remember { mutableStateOf(VoiceScreenMode.SIMPLE_CALL) }
    val isPttRecording by audioService.isRecording.collectAsState()
    val micPeak by audioService.micLevelPeak.collectAsState()
    val micDb by audioService.micLevelDb.collectAsState()
    val pttRxCount by audioService.voiceFramesPlayed.collectAsState()
    val cachedCallRxReplayFrames by audioService.cachedCallRxReplayFramesCount.collectAsState()
    val isReplayingCallRx by audioService.isReplayingCallRx.collectAsState()
    val cachedPttRxReplayFrames by audioService.cachedPttRxReplayFramesCount.collectAsState()
    val isReplayingPttRx by audioService.isReplayingPttRx.collectAsState()
    val voiceClarityPreset by audioService.voiceClarityPreset.collectAsState()

    val rawPeak = if (duplexCallState.isInCall || duplexCallState.isRecordingVoicemail) duplexCallState.audioLevelRms else micPeak
    val effectivePeak = if (rawPeak.isFinite()) rawPeak.coerceIn(0f, 1f) else 0f
    val effectiveDb = if (duplexCallState.isInCall || duplexCallState.isRecordingVoicemail) duplexCallState.micLevelDb else micDb
    val totalVoiceRx = maxOf(state.rxAudioCount, pttRxCount + duplexCallState.voiceFramesPlayed)

    val discoveredPeers = remember(state.nodes) {
        val seenIds = HashSet<String>()
        val peers = mutableListOf<MeshNode>()
        for (node in state.nodes) {
            if (!node.isSelf && node.id.isNotBlank()) {
                val normKey = node.id.trim().uppercase().removePrefix("0X")
                if (seenIds.add(normKey)) {
                    peers.add(node)
                }
            }
        }
        peers
    }

    val effectiveSignalingState = when {
        duplexCallState.isRecordingVoicemail -> CallSignalingState.IN_CALL_ACTIVE
        state.callState != CallSignalingState.IDLE -> state.callState
        else -> duplexCallState.signalingState
    }

    var lastExitToIdleMs by remember { mutableLongStateOf(0L) }
    var prevSignalingState by remember { mutableStateOf(effectiveSignalingState) }
    LaunchedEffect(effectiveSignalingState) {
        if (effectiveSignalingState != CallSignalingState.IDLE) {
            voiceMode = VoiceScreenMode.SIMPLE_CALL
        } else if (prevSignalingState != CallSignalingState.IDLE) {
            lastExitToIdleMs = System.currentTimeMillis()
        }
        prevSignalingState = effectiveSignalingState
    }

    val peerCallsignDisplay = duplexCallState.peerCallsign
        .ifBlank { state.remoteCallerName }
        .ifBlank { state.incomingPrivateCall?.callsign ?: state.activePrivateCallNode?.callsign ?: "Peer Operator" }

    val peerIdDisplay = duplexCallState.peerNodeId
        .ifBlank { state.remoteCallerId }
        .ifBlank { state.incomingPrivateCall?.id ?: state.activePrivateCallNode?.id ?: "" }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .aeroCanvasBackground(p)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val isCompactHeight = maxHeight < 580.dp
        val isWide = maxWidth >= 600.dp
        val pttOrbSize = if (isCompactHeight) {
            (maxHeight * 0.25f).coerceIn(108.dp, 148.dp)
        } else {
            (maxHeight * 0.34f).coerceIn(136.dp, 196.dp)
        }
        val dialerFooterMaxHeight = (maxHeight * 0.62f).coerceAtLeast(180.dp)

        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .padding(
                    horizontal = if (isWide) 24.dp else 14.dp,
                    vertical = if (isCompactHeight) 4.dp else 12.dp,
                ),
        ) {
            // Clean Segmented Switch at Top: "1-TAP VOICE CALL" vs "WALKIE-TALKIE PTT"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .then(
                        if (p.isAero) {
                            Modifier.aeroGlassPanel(p, cornerRadius = 22.dp)
                        } else {
                            Modifier.background(p.card)
                        }
                    )
                    .border(1.dp, p.border, cardShape)
                    .padding(if (isCompactHeight) 2.dp else 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = if (isCompactHeight) 36.dp else 44.dp)
                        .clip(btnShape)
                        .background(if (voiceMode == VoiceScreenMode.SIMPLE_CALL) p.emerald else Color.Transparent)
                        .then(
                            if (voiceMode == VoiceScreenMode.SIMPLE_CALL && p.isAero) {
                                Modifier.aeroGlossOverlay(p, p.emerald, btnShape, cornerRadius = 18.dp)
                            } else {
                                Modifier
                            }
                        )
                        .clickable { voiceMode = VoiceScreenMode.SIMPLE_CALL }
                        .padding(horizontal = 8.dp, vertical = if (isCompactHeight) 6.dp else 10.dp)
                        .testTag("tab_one_tap_voice_call"),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (p.isAero) {
                            AeroGlossBadgeIcon(
                                imageVector = Icons.Default.Call,
                                accentColor = if (voiceMode == VoiceScreenMode.SIMPLE_CALL) p.emerald else p.textMuted,
                                contentDescription = "1-Tap Voice Call",
                                badgeSize = 22.dp,
                                iconSize = 13.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "1-Tap Voice Call",
                                tint = if (voiceMode == VoiceScreenMode.SIMPLE_CALL) (if (p.isLight) Color.White else Color.Black) else p.textSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = com.example.util.AppStrings.tr(appLanguage, "1-TAP VOICE CALL", "ΚΛΗΣΗ 1-ΠΑΤΗΜΑΤΟΣ"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (voiceMode == VoiceScreenMode.SIMPLE_CALL) (if (p.isLight || p.isAero) Color.White else Color.Black) else p.textSecondary,
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = if (isCompactHeight) 36.dp else 44.dp)
                        .clip(btnShape)
                        .background(if (voiceMode == VoiceScreenMode.WALKIE_TALKIE_PTT) p.cyan else Color.Transparent)
                        .then(
                            if (voiceMode == VoiceScreenMode.WALKIE_TALKIE_PTT && p.isAero) {
                                Modifier.aeroGlossOverlay(p, p.cyan, btnShape, cornerRadius = 18.dp)
                            } else {
                                Modifier
                            }
                        )
                        .clickable { voiceMode = VoiceScreenMode.WALKIE_TALKIE_PTT }
                        .padding(horizontal = 8.dp, vertical = if (isCompactHeight) 6.dp else 10.dp)
                        .testTag("tab_walkie_talkie_ptt"),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (p.isAero) {
                            AeroGlossBadgeIcon(
                                imageVector = Icons.Default.Radio,
                                accentColor = if (voiceMode == VoiceScreenMode.WALKIE_TALKIE_PTT) p.cyan else p.textMuted,
                                contentDescription = "Walkie-Talkie PTT",
                                badgeSize = 22.dp,
                                iconSize = 13.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Radio,
                                contentDescription = "Walkie-Talkie PTT",
                                tint = if (voiceMode == VoiceScreenMode.WALKIE_TALKIE_PTT) (if (p.isLight) Color.White else Color.Black) else p.textSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = com.example.util.AppStrings.tr(appLanguage, "WALKIE & BELL 202", "ΑΣΥΡΜΑΤΟΣ & BELL 202"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (voiceMode == VoiceScreenMode.WALKIE_TALKIE_PTT) (if (p.isLight || p.isAero) Color.White else Color.Black) else p.textSecondary,
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isCompactHeight) 4.dp else 8.dp))

            // Live Voice Clarity DSP Preset + 1-Tap Hotspot Link Healer Bar
            val isFastWifiLinked = duplexCallState.isHighSpeedLink &&
                (state.hotspotButtonText.contains("LINKED") || state.hotspotButtonText.contains("HOSTING") || state.hotspotState.isActive)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = if (p.isAero) Color.Transparent else p.card,
                    shape = btnShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, p.emerald.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .aeroGlassPanel(p, cornerRadius = 18.dp)
                        .clickable { onCycleVoiceClarity() }
                        .testTag("voice_clarity_preset_button"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = if (isCompactHeight) 4.dp else 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (p.isAero) {
                            AeroGlossBadgeIcon(
                                imageVector = Icons.Default.GraphicEq,
                                accentColor = p.emerald,
                                contentDescription = "Voice Clarity DSP",
                                badgeSize = 24.dp,
                                iconSize = 14.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Voice Clarity DSP",
                                tint = p.emerald,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "DSP: ${voiceClarityPreset.shortBadge}",
                                color = p.emerald,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = voiceClarityPreset.label,
                                color = p.textSecondary,
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Surface(
                    color = if (p.isAero) Color.Transparent else p.card,
                    shape = btnShape,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isFastWifiLinked) p.emerald.copy(alpha = 0.65f) else p.cyan.copy(alpha = 0.7f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .aeroGlassPanel(p, cornerRadius = 18.dp)
                        .clickable { onHealHotspot() }
                        .testTag("heal_hotspot_link_button"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = if (isCompactHeight) 4.dp else 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (p.isAero) {
                            AeroGlossBadgeIcon(
                                imageVector = Icons.Default.WifiTethering,
                                accentColor = if (isFastWifiLinked) p.emerald else p.cyan,
                                contentDescription = "Heal Hotspot Link",
                                badgeSize = 24.dp,
                                iconSize = 14.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.WifiTethering,
                                contentDescription = "Heal Hotspot Link",
                                tint = if (isFastWifiLinked) p.emerald else p.cyan,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isFastWifiLinked) "WIFI LINKED" else "HEAL WIFI",
                                color = if (isFastWifiLinked) p.emerald else p.cyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = state.hotspotButtonText.removePrefix("HOTSPOT: "),
                                color = p.textSecondary,
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (effectiveSignalingState != CallSignalingState.IDLE) {
                    Surface(
                        color = if (p.isAero) Color.Transparent else p.card,
                        shape = btnShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, p.amber.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .fillMaxHeight()
                            .aeroGlassPanel(p, cornerRadius = 18.dp)
                            .clickable { onOpenLockScreenCover() }
                            .testTag("lock_screen_call_cover_button"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = if (isCompactHeight) 4.dp else 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            if (p.isAero) {
                                AeroGlossBadgeIcon(
                                    imageVector = Icons.Default.ScreenLockPortrait,
                                    accentColor = p.amber,
                                    contentDescription = "Lock Screen Call Cover",
                                    badgeSize = 22.dp,
                                    iconSize = 13.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.ScreenLockPortrait,
                                    contentDescription = "Lock Screen Call Cover",
                                    tint = p.amber,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                            Column {
                                Text(
                                    text = "LOCK",
                                    color = p.amber,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                )
                                Text(
                                    text = "COVER",
                                    color = p.textSecondary,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isCompactHeight) 4.dp else 10.dp))

            if (voiceMode == VoiceScreenMode.SIMPLE_CALL) {
                when (effectiveSignalingState) {
                    CallSignalingState.RINGING_INCOMING -> {
                        Surface(
                            color = if (p.isAero) Color.Transparent else p.card,
                            shape = cardShape,
                            border = androidx.compose.foundation.BorderStroke(2.dp, p.emerald),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .aeroGlassPanel(p, cornerRadius = 22.dp),
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .padding(if (isCompactHeight) 14.dp else 22.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(
                                        if (isCompactHeight) 12.dp else 20.dp,
                                        Alignment.CenterVertically,
                                    ),
                                ) {
                                    val badgeSize = if (isCompactHeight) 62.dp else 88.dp
                                    if (p.isAero) {
                                        AeroGlossBadgeIcon(
                                            imageVector = Icons.Default.RingVolume,
                                            accentColor = p.emerald,
                                            contentDescription = "Incoming Call",
                                            badgeSize = badgeSize,
                                            iconSize = if (isCompactHeight) 30.dp else 42.dp,
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(badgeSize)
                                                .clip(CircleShape)
                                                .background(p.emeraldDim)
                                                .border(2.dp, p.emerald, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.RingVolume,
                                                contentDescription = "Incoming Call",
                                                tint = p.emerald,
                                                modifier = Modifier.size(if (isCompactHeight) 30.dp else 42.dp)
                                            )
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "INCOMING VOICE CALL",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                color = p.emerald,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = peerCallsignDisplay,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.headlineSmall.copy(
                                                color = p.textPrimary,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                        if (peerIdDisplay.isNotBlank()) {
                                            Text(
                                                text = peerIdDisplay,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    color = p.textSecondary,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            )
                                        }
                                    }
                                }

                                HorizontalDivider(color = p.border)
                                val incomingActionShape = RoundedCornerShape(if (isCompactHeight) 14.dp else 28.dp)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(if (isCompactHeight) 12.dp else 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            lastExitToIdleMs = System.currentTimeMillis()
                                            onDeclineCall()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (p.isAero) Color.Transparent else p.red,
                                            contentColor = Color.White
                                        ),
                                        shape = incomingActionShape,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = if (isCompactHeight) 52.dp else 64.dp)
                                            .aeroGlossOverlay(
                                                p,
                                                p.red,
                                                incomingActionShape,
                                                cornerRadius = if (isCompactHeight) 14.dp else 28.dp
                                            )
                                            .testTag("voice_decline_call_button")
                                    ) {
                                        Icon(Icons.Default.CallEnd, contentDescription = "Decline", modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = com.example.util.AppStrings.tr(appLanguage, "DECLINE", "ΑΠΟΡΡΙΨΗ"),
                                            fontWeight = FontWeight.ExtraBold,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Button(
                                        onClick = onAcceptCall,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (p.isAero) Color.Transparent else p.emerald,
                                            contentColor = if (p.isLight || p.isAero) Color.White else Color.Black
                                        ),
                                        shape = incomingActionShape,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = if (isCompactHeight) 52.dp else 64.dp)
                                            .aeroGlossOverlay(
                                                p,
                                                p.emerald,
                                                incomingActionShape,
                                                cornerRadius = if (isCompactHeight) 14.dp else 28.dp
                                            )
                                            .testTag("voice_accept_call_button")
                                    ) {
                                        Icon(Icons.Default.Call, contentDescription = "Accept", modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = com.example.util.AppStrings.tr(appLanguage, "ACCEPT", "ΑΠΟΔΟΧΗ"),
                                            fontWeight = FontWeight.ExtraBold,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    CallSignalingState.RINGING_OUTGOING, CallSignalingState.IN_CALL_ACTIVE -> {
                        val isRingingOut = effectiveSignalingState == CallSignalingState.RINGING_OUTGOING && !duplexCallState.isRecordingVoicemail
                        val isVoicemailMode = duplexCallState.isRecordingVoicemail
                        val mins = duplexCallState.durationSeconds / 60
                        val secs = duplexCallState.durationSeconds % 60
                        val timerFormatted = "%02d:%02d".format(mins, secs)

                        val floorStateLabel: String
                        val floorStateColor: Color
                        val floorStateBg: Color
                        val floorIcon = when {
                            isVoicemailMode -> {
                                floorStateLabel = duplexCallState.voicemailPromptText.ifBlank {
                                    "NO ANSWER — SPEAK MESSAGE, TAP SEND"
                                }
                                floorStateColor = p.amber
                                floorStateBg = p.amberDim
                                Icons.Default.Voicemail
                            }
                            isRingingOut -> {
                                floorStateLabel = "RINGING PEER — WAITING FOR ANSWER"
                                floorStateColor = p.cyan
                                floorStateBg = p.cyanDim
                                Icons.Default.PhoneInTalk
                            }
                            duplexCallState.isMuted -> {
                                floorStateLabel = com.example.util.AppStrings.tr(appLanguage, "MIC MUTED — RX LISTENING ONLY", "ΜΙΚΡΟΦΩΝΟ ΣΕ ΣΙΓΑΣΗ — ΜΟΝΟ ΑΚΡΟΑΣΗ")
                                floorStateColor = p.red
                                floorStateBg = p.redDim
                                Icons.Default.MicOff
                            }
                            duplexCallState.isPushToOverrideActive -> {
                                floorStateLabel = "TX OVERRIDE — FORCING BLE FLOOR"
                                floorStateColor = p.amber
                                floorStateBg = p.amberDim
                                Icons.Default.RecordVoiceOver
                            }
                            duplexCallState.isVoiceTransmitting -> {
                                floorStateLabel = "TX ACTIVE — YOUR VOICE IS LIVE"
                                floorStateColor = p.emerald
                                floorStateBg = p.emeraldDim
                                Icons.Default.GraphicEq
                            }
                            !duplexCallState.isHighSpeedLink && duplexCallState.isPeerSpeaking -> {
                                floorStateLabel = "RX YIELD — PEER SPEAKING (BLE HALF-DUPLEX)"
                                floorStateColor = p.amber
                                floorStateBg = p.amberDim
                                Icons.AutoMirrored.Filled.VolumeUp
                            }
                            duplexCallState.isPeerSpeaking -> {
                                floorStateLabel = "RX ACTIVE — PEER SPEAKING"
                                floorStateColor = p.cyan
                                floorStateBg = p.cyanDim
                                Icons.AutoMirrored.Filled.VolumeUp
                            }
                            else -> {
                                floorStateLabel = if (duplexCallState.isHighSpeedLink) {
                                    "FULL-DUPLEX READY • SPEAK TO TRANSMIT"
                                } else {
                                    "BLE HALF-DUPLEX READY • SPEAK TO TRANSMIT"
                                }
                                floorStateColor = p.emerald
                                floorStateBg = p.surfaceVariant
                                Icons.Default.Sensors
                            }
                        }

                        Surface(
                            color = if (p.isAero) Color.Transparent else p.card,
                            shape = cardShape,
                            border = androidx.compose.foundation.BorderStroke(
                                2.dp,
                                if (isRingingOut) p.cyan else floorStateColor.copy(alpha = 0.85f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .aeroGlassPanel(p, cornerRadius = 22.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                // Upper Scrollable Identity & Floor State Telemetry
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .padding(
                                            horizontal = if (isCompactHeight) 10.dp else 18.dp,
                                            vertical = if (isCompactHeight) 6.dp else 16.dp
                                        ),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 6.dp else 14.dp)
                                ) {
                                    if (isCompactHeight) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            if (p.isAero) {
                                                AeroGlossBadgeIcon(
                                                    imageVector = if (isRingingOut) Icons.AutoMirrored.Filled.PhoneForwarded else Icons.Default.HeadsetMic,
                                                    accentColor = if (isRingingOut) p.cyan else floorStateColor,
                                                    contentDescription = peerCallsignDisplay,
                                                    badgeSize = 44.dp,
                                                    iconSize = 22.dp,
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .size(44.dp)
                                                        .clip(CircleShape)
                                                        .background(if (isRingingOut) p.cyanDim else p.emeraldDim)
                                                        .border(
                                                            2.dp,
                                                            if (isRingingOut) p.cyan else floorStateColor,
                                                            CircleShape
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = if (isRingingOut) Icons.AutoMirrored.Filled.PhoneForwarded else Icons.Default.HeadsetMic,
                                                        contentDescription = peerCallsignDisplay,
                                                        tint = if (isRingingOut) p.cyan else floorStateColor,
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                }
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = if (isRingingOut) "CALLING..." else "LIVE • $timerFormatted",
                                                        style = MaterialTheme.typography.labelMedium.copy(
                                                            color = if (isRingingOut) p.cyan else p.emerald,
                                                            fontWeight = FontWeight.ExtraBold,
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                    )
                                                    Text(
                                                        text = if (duplexCallState.isHighSpeedLink) {
                                                            "• UDP HD"
                                                        } else if (state.activeL2capChannelsCount > 0) {
                                                            "• L2CAP"
                                                        } else {
                                                            "• BLE 0xCAFF"
                                                        },
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            color = if (duplexCallState.isHighSpeedLink) p.cyan else p.emerald,
                                                            fontWeight = FontWeight.Bold,
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 10.sp
                                                        )
                                                    )
                                                }
                                                Text(
                                                    text = peerCallsignDisplay,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.titleMedium.copy(
                                                        color = p.textPrimary,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                )
                                                if (peerIdDisplay.isNotBlank()) {
                                                    Text(
                                                        text = peerIdDisplay,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            color = p.textSecondary,
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            if (p.isAero) {
                                                AeroGlossBadgeIcon(
                                                    imageVector = if (isRingingOut) Icons.AutoMirrored.Filled.PhoneForwarded else Icons.Default.HeadsetMic,
                                                    accentColor = if (isRingingOut) p.cyan else floorStateColor,
                                                    contentDescription = peerCallsignDisplay,
                                                    badgeSize = 72.dp,
                                                    iconSize = 34.dp,
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .size(72.dp)
                                                        .clip(CircleShape)
                                                        .background(if (isRingingOut) p.cyanDim else floorStateBg)
                                                        .border(
                                                            2.5.dp,
                                                            if (isRingingOut) p.cyan else floorStateColor,
                                                            CircleShape
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = if (isRingingOut) Icons.AutoMirrored.Filled.PhoneForwarded else Icons.Default.HeadsetMic,
                                                        contentDescription = peerCallsignDisplay,
                                                        tint = if (isRingingOut) p.cyan else floorStateColor,
                                                        modifier = Modifier.size(34.dp)
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(10.dp))

                                            Text(
                                                text = peerCallsignDisplay,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.headlineSmall.copy(
                                                    color = p.textPrimary,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            )

                                            Spacer(modifier = Modifier.height(4.dp))

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Surface(
                                                    color = if (isRingingOut) p.cyanDim else p.emeraldDim,
                                                    shape = RoundedCornerShape(6.dp),
                                                    border = androidx.compose.foundation.BorderStroke(
                                                        1.dp,
                                                        if (isRingingOut) p.cyan else p.emerald
                                                    )
                                                ) {
                                                    Text(
                                                        text = if (isRingingOut) "CALLING..." else "LIVE • $timerFormatted",
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                        maxLines = 1,
                                                        style = MaterialTheme.typography.labelMedium.copy(
                                                            color = if (isRingingOut) p.cyan else p.emerald,
                                                            fontWeight = FontWeight.ExtraBold,
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                    )
                                                }

                                                Surface(
                                                    color = p.surfaceVariant,
                                                    shape = RoundedCornerShape(6.dp),
                                                    border = androidx.compose.foundation.BorderStroke(
                                                        1.dp,
                                                        if (duplexCallState.isHighSpeedLink) p.cyan else p.emerald
                                                    )
                                                ) {
                                                    Text(
                                                        text = if (duplexCallState.isHighSpeedLink) {
                                                            "8kHz HD UDP"
                                                        } else if (state.activeL2capChannelsCount > 0) {
                                                            "L2CAP (${state.activeL2capChannelsCount})"
                                                        } else {
                                                            "BLE 0xCAFF"
                                                        },
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                                        maxLines = 1,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            color = if (duplexCallState.isHighSpeedLink) p.cyan else p.emerald,
                                                            fontWeight = FontWeight.Bold,
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 10.sp
                                                        )
                                                    )
                                                }

                                                if (!isRingingOut) {
                                                    val ackReady = state.callAckConfirmed ||
                                                        duplexCallState.isCallAcceptAcked ||
                                                        duplexCallState.voiceFramesPlayed > 0
                                                    Surface(
                                                        color = if (ackReady) p.emeraldDim else p.amberDim,
                                                        shape = RoundedCornerShape(6.dp),
                                                        border = androidx.compose.foundation.BorderStroke(
                                                            1.dp,
                                                            if (ackReady) p.emerald else p.amber
                                                        )
                                                    ) {
                                                        Text(
                                                            text = if (ackReady) "ACK ✓" else "SYNC…",
                                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                                            maxLines = 1,
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                color = if (ackReady) p.emerald else p.amber,
                                                                fontWeight = FontWeight.Bold,
                                                                fontFamily = FontFamily.Monospace,
                                                                fontSize = 10.sp
                                                            )
                                                        )
                                                    }
                                                }
                                            }

                                            if (peerIdDisplay.isNotBlank()) {
                                                Spacer(modifier = Modifier.height(3.dp))
                                                Text(
                                                    text = "NODE $peerIdDisplay • E2EE VOICE",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        color = p.textSecondary,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.sp
                                                    )
                                                )
                                            }
                                        }
                                    }

                                    // Clear Live TX / RX Floor State Pill
                                    Surface(
                                        color = floorStateBg,
                                        shape = RoundedCornerShape(10.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, floorStateColor),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .semantics(mergeDescendants = true) {}
                                            .testTag("voice_floor_state_banner")
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = if (isCompactHeight) 6.dp else 9.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = floorIcon,
                                                contentDescription = null,
                                                tint = floorStateColor,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = floorStateLabel,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center,
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    color = floorStateColor,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            )
                                        }
                                    }

                                    if (state.activeSpeakingBanner.isNotBlank()) {
                                        Text(
                                            text = state.activeSpeakingBanner,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = p.cyan,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                    }

                                    // Live Multi-Bar Voice Spectrum & Telemetry Card
                                    Surface(
                                        color = p.background,
                                        shape = RoundedCornerShape(10.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, p.border),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "MIC: $effectiveDb dB",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = if (effectivePeak > 0.08f) p.emerald else p.textSecondary,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                )
                                                Text(
                                                    text = "TX: ${duplexCallState.voiceFramesSent} • RX: $totalVoiceRx",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = if (totalVoiceRx > 0) p.cyan else p.textSecondary,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                )
                                            }

                                            // 16-Bar Live Audio Equalizer + Progress Bar
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(18.dp),
                                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                verticalAlignment = Alignment.Bottom
                                            ) {
                                                val activeActivity = when {
                                                    duplexCallState.isVoiceTransmitting -> effectivePeak.coerceIn(0.18f, 1f)
                                                    duplexCallState.isPeerSpeaking -> 0.65f
                                                    else -> effectivePeak.coerceIn(0.06f, 1f)
                                                }
                                                val barAccent = when {
                                                    duplexCallState.isMuted -> p.red
                                                    duplexCallState.isPushToOverrideActive -> p.amber
                                                    duplexCallState.isPeerSpeaking && !duplexCallState.isHighSpeedLink -> p.amber
                                                    duplexCallState.isPeerSpeaking -> p.cyan
                                                    else -> p.emerald
                                                }
                                                for (barIdx in 0 until 16) {
                                                    val distanceFromCenter = kotlin.math.abs(barIdx - 7.5f) / 8f
                                                    val envelope = (1f - (distanceFromCenter * 0.55f)).coerceIn(0.25f, 1f)
                                                    val barFraction = (activeActivity * envelope).coerceIn(0.15f, 1f)
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .fillMaxHeight(barFraction)
                                                            .clip(RoundedCornerShape(2.dp))
                                                            .background(
                                                                if (activeActivity > 0.1f) barAccent else p.surfaceVariant
                                                            )
                                                    )
                                                }
                                            }

                                            LinearProgressIndicator(
                                                progress = { effectivePeak.coerceIn(0f, 1f) },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp)),
                                                color = if (effectivePeak > 0.75f) p.amber else p.emerald,
                                                trackColor = p.surfaceVariant
                                            )
                                        }
                                    }
                                }

                                HorizontalDivider(color = p.border)

                                // Pinned Minimal Dialer Action Controls (Oversized Mute, Speaker, Override, End Call)
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = dialerFooterMaxHeight)
                                        .verticalScroll(rememberScrollState())
                                        .padding(
                                            horizontal = if (isCompactHeight) 10.dp else 16.dp,
                                            vertical = if (isCompactHeight) 6.dp else 14.dp
                                        ),
                                    verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 6.dp else 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = onToggleMute,
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = if (duplexCallState.isMuted) p.redDim else p.surfaceVariant,
                                                contentColor = if (duplexCallState.isMuted) p.red else p.textPrimary
                                            ),
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.5.dp,
                                                if (duplexCallState.isMuted) p.red else p.border
                                            ),
                                            shape = RoundedCornerShape(16.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = if (isCompactHeight) 4.dp else 10.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = if (isCompactHeight) 44.dp else 72.dp)
                                                .testTag("voice_mute_button")
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(
                                                    imageVector = if (duplexCallState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                                    contentDescription = "Mute",
                                                    modifier = Modifier.size(if (isCompactHeight) 20.dp else 26.dp)
                                                )
                                                Spacer(modifier = Modifier.height(if (isCompactHeight) 2.dp else 4.dp))
                                                Text(
                                                    text = if (duplexCallState.isMuted) com.example.util.AppStrings.tr(appLanguage, "MUTED", "ΣΙΓΑΣΗ") else com.example.util.AppStrings.tr(appLanguage, "MUTE", "ΣΙΓΑΣΗ"),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = if (isCompactHeight) 11.sp else 12.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }

                                        OutlinedButton(
                                            onClick = onToggleSpeaker,
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = if (duplexCallState.isSpeakerphoneOn) p.cyanDim else p.surfaceVariant,
                                                contentColor = if (duplexCallState.isSpeakerphoneOn) p.cyan else p.textPrimary
                                            ),
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.5.dp,
                                                if (duplexCallState.isSpeakerphoneOn) p.cyan else p.border
                                            ),
                                            shape = RoundedCornerShape(16.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = if (isCompactHeight) 4.dp else 10.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = if (isCompactHeight) 44.dp else 72.dp)
                                                .testTag("voice_speaker_button")
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(
                                                    imageVector = if (duplexCallState.isSpeakerphoneOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.PhoneInTalk,
                                                    contentDescription = "Speakerphone",
                                                    modifier = Modifier.size(if (isCompactHeight) 20.dp else 26.dp)
                                                )
                                                Spacer(modifier = Modifier.height(if (isCompactHeight) 2.dp else 4.dp))
                                                Text(
                                                    text = if (duplexCallState.isSpeakerphoneOn) com.example.util.AppStrings.tr(appLanguage, "SPEAKER ON", "ΗΧΕΙΟ ON") else com.example.util.AppStrings.tr(appLanguage, "EARPIECE", "ΑΚΟΥΣΤΙΚΟ"),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = if (isCompactHeight) 11.sp else 12.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }
                                    }

                                    if (!isRingingOut && !isVoicemailMode) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Surface(
                                                color = if (duplexCallState.isPushToOverrideActive) p.amber else p.surfaceVariant,
                                                shape = RoundedCornerShape(12.dp),
                                                border = androidx.compose.foundation.BorderStroke(
                                                    1.5.dp,
                                                    if (duplexCallState.isPushToOverrideActive) p.amber else p.border
                                                ),
                                                modifier = Modifier
                                                    .weight(1.15f)
                                                    .heightIn(min = if (isCompactHeight) 42.dp else 48.dp)
                                                    .pointerInput(Unit) {
                                                        detectTapGestures(
                                                            onPress = {
                                                                onPushToOverride(true)
                                                                try {
                                                                    tryAwaitRelease()
                                                                } finally {
                                                                    onPushToOverride(false)
                                                                }
                                                            }
                                                        )
                                                    }
                                                    .testTag("voice_push_to_override_button")
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 8.dp, vertical = if (isCompactHeight) 8.dp else 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.RecordVoiceOver,
                                                        contentDescription = "Push To Override",
                                                        tint = if (duplexCallState.isPushToOverrideActive) {
                                                            if (p.isLight || p.isAero) Color.White else Color.Black
                                                        } else p.amber,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(5.dp))
                                                    Text(
                                                        text = if (duplexCallState.isPushToOverrideActive) {
                                                            com.example.util.AppStrings.tr(appLanguage, "OVER-TALKING", "ΥΠΕΡΙΣΧΥΣΗ")
                                                        } else {
                                                            com.example.util.AppStrings.tr(appLanguage, "HOLD OVERRIDE", "ΚΡΑΤΗΣΗ ΟΜΙΛΙΑΣ")
                                                        },
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        color = if (duplexCallState.isPushToOverrideActive) {
                                                            if (p.isLight || p.isAero) Color.White else Color.Black
                                                        } else p.textPrimary,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        fontSize = 10.5.sp,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                }
                                            }

                                            OutlinedButton(
                                                onClick = onReplayLastCallRx,
                                                enabled = (cachedCallRxReplayFrames > 0) && !isReplayingCallRx,
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    containerColor = if (isReplayingCallRx) p.cyanDim else p.surfaceVariant,
                                                    contentColor = if (cachedCallRxReplayFrames > 0) p.cyan else p.textMuted
                                                ),
                                                border = androidx.compose.foundation.BorderStroke(
                                                    1.5.dp,
                                                    if (cachedCallRxReplayFrames > 0) p.cyan else p.border
                                                ),
                                                shape = btnShape,
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = if (isCompactHeight) 6.dp else 8.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .heightIn(min = if (isCompactHeight) 42.dp else 48.dp)
                                                    .testTag("voice_replay_last_rx_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Replay,
                                                    contentDescription = "Replay Last Call RX",
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = if (isReplayingCallRx) {
                                                        "PLAYING..."
                                                    } else {
                                                        com.example.util.AppStrings.tr(appLanguage, "SAY AGAIN (${(cachedCallRxReplayFrames * 160) / 1000}s)", "ΕΠΑΝΑΛΗΨΗ (${(cachedCallRxReplayFrames * 160) / 1000}δ)")
                                                    },
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }
                                    }

                                    val endCallShape = RoundedCornerShape(if (isCompactHeight) 12.dp else 28.dp)
                                    Button(
                                        onClick = {
                                            lastExitToIdleMs = System.currentTimeMillis()
                                            onEndCall()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (p.isAero) Color.Transparent else p.red,
                                            contentColor = Color.White
                                        ),
                                        shape = endCallShape,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = if (isCompactHeight) 44.dp else 64.dp)
                                            .aeroGlossOverlay(
                                                p,
                                                p.red,
                                                endCallShape,
                                                cornerRadius = if (isCompactHeight) 12.dp else 28.dp
                                            )
                                            .testTag("voice_end_call_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CallEnd,
                                            contentDescription = "End Call",
                                            modifier = Modifier.size(if (isCompactHeight) 22.dp else 26.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = if (isVoicemailMode) com.example.util.AppStrings.tr(appLanguage, "HANG UP & SEND MESSAGE", "ΤΕΡΜΑΤΙΣΜΟΣ & ΑΠΟΣΤΟΛΗ") else com.example.util.AppStrings.tr(appLanguage, "END CALL", "ΤΕΡΜΑΤΙΣΜΟΣ"),
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = if (isCompactHeight) 14.sp else 16.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        Button(
                            onClick = {
                                if ((System.currentTimeMillis() - lastExitToIdleMs) >= 900L) {
                                    onStartGroupCall()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (p.isAero) Color.Transparent else p.cyan,
                                contentColor = if (p.isLight || p.isAero) Color.White else Color.Black
                            ),
                            shape = btnShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .aeroGlossOverlay(p, p.cyan, btnShape, cornerRadius = 18.dp)
                                .testTag("group_mesh_call_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Groups,
                                contentDescription = "Group Mesh Call All Peers",
                                tint = if (p.isLight || p.isAero) Color.White else Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = com.example.util.AppStrings.tr(appLanguage, "GROUP MESH CALL (ALL PEERS)", "ΟΜΑΔΙΚΗ ΚΛΗΣΗ MESH (ΟΛΟΙ ΟΙ ΚΟΜΒΟΙ)"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (p.isLight || p.isAero) Color.White else Color.Black,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "${com.example.util.AppStrings.tr(appLanguage, "DISCOVERED PEERS", "ΕΝΤΟΠΙΣΜΕΝΟΙ ΚΟΜΒΟΙ")} (${discoveredPeers.size})",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = p.textMuted,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (discoveredPeers.isEmpty()) {
                            Surface(
                                color = if (p.isAero) Color.Transparent else p.card,
                                shape = cardShape,
                                border = androidx.compose.foundation.BorderStroke(1.dp, p.border),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false)
                                    .aeroGlassPanel(p, cornerRadius = 22.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .verticalScroll(rememberScrollState())
                                        .padding(if (isCompactHeight) 18.dp else 28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Sensors,
                                        contentDescription = "Listening for Peers",
                                        tint = p.emerald,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "SCANNING FOR NEARBY PEERS...",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            color = p.emerald,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Phones discover each other automatically via 12-byte BLE 0xCAFE beacons and Hotspot Autoconnect.",
                                        style = MaterialTheme.typography.bodySmall.copy(color = p.textSecondary),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(bottom = 76.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                items(
                                    count = discoveredPeers.size,
                                    key = { index -> "${discoveredPeers[index].id}_$index" },
                                ) { index ->
                                    val peer = discoveredPeers[index]
                                    Surface(
                                        color = if (p.isAero) Color.Transparent else p.card,
                                        shape = cardShape,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, p.border),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aeroGlassPanel(p, cornerRadius = 22.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                if (p.isAero) {
                                                    AeroGlossBadgeIcon(
                                                        imageVector = Icons.Default.Person,
                                                        accentColor = p.emerald,
                                                        contentDescription = peer.callsign,
                                                        badgeSize = 36.dp,
                                                        iconSize = 19.dp,
                                                    )
                                                } else {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(12.dp)
                                                            .clip(CircleShape)
                                                            .background(p.emerald)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column {
                                                    Text(
                                                        text = peer.callsign,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        style = MaterialTheme.typography.titleMedium.copy(
                                                            color = p.emerald,
                                                            fontWeight = FontWeight.Bold,
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                    )
                                                    val linkDetail = buildString {
                                                        append(peer.id)
                                                        append(" • Hop ${peer.hopCount}")
                                                        if ((state.userLatitude != null) && (state.userLongitude != null) &&
                                                            (peer.latitude != 0.0) && (peer.longitude != 0.0)
                                                        ) {
                                                            val distM = peer.distanceMeters ?: com.example.service.LiveMeshNetworkService.calculateDistanceMeters(
                                                                state.userLatitude, state.userLongitude, peer.latitude, peer.longitude
                                                            )
                                                            val distText = if (distM >= 1000.0) {
                                                                String.format(java.util.Locale.US, "%.2f km", distM / 1000.0)
                                                            } else {
                                                                "${distM.toInt()} m"
                                                            }
                                                            val trueBearing = com.example.service.TacticalSensorService.calculateBearingDegrees(
                                                                state.userLatitude, state.userLongitude, peer.latitude, peer.longitude
                                                            )
                                                            val relBearing = com.example.service.TacticalSensorService.calculateRelativeBearingDegrees(
                                                                trueBearing, state.deviceHeadingDeg
                                                            )
                                                            val arrow = com.example.service.TacticalSensorService.bearingToCompassArrow(relBearing)
                                                            append(" • $distText $arrow${trueBearing.toInt()}°")
                                                        }
                                                        if (peer.altitudeM != 0.0) {
                                                            append(" • ${peer.altitudeM.toInt()}m ASL")
                                                        }
                                                        if (peer.isHotspotHost) {
                                                            append(" • HUB")
                                                        }
                                                        if (peer.ipAddress.isNotBlank()) {
                                                            append(" • ")
                                                            append(peer.ipAddress)
                                                        } else {
                                                            append(" • ")
                                                            append("${peer.rssiDbm} dBm")
                                                        }
                                                    }
                                                    Text(
                                                        text = linkDetail,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            color = p.textSecondary,
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                    )
                                                }
                                            }

                                            val callBtnFg = if (p.isLight || p.isAero) Color.White else Color.Black
                                            Button(
                                                onClick = {
                                                    if ((System.currentTimeMillis() - lastExitToIdleMs) >= 900L) {
                                                        onStartCall(peer)
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (p.isAero) Color.Transparent else p.emerald,
                                                    contentColor = callBtnFg
                                                ),
                                                shape = btnShape,
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                                modifier = Modifier
                                                    .heightIn(min = 48.dp)
                                                    .aeroGlossOverlay(p, p.emerald, btnShape, cornerRadius = 18.dp)
                                                    .testTag("call_peer_${peer.id}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Call,
                                                    contentDescription = "Call ${peer.callsign}",
                                                    tint = callBtnFg,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = if (isWide) "CALL ${peer.callsign.take(8).uppercase()}" else "CALL",
                                                    color = callBtnFg,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // =========================================================================
                // "WALKIE-TALKIE PTT":
                // Scroll-safe & adaptive central Push-To-Talk button + live Mic level bar.
                // =========================================================================
                if (effectiveSignalingState != CallSignalingState.IDLE) {
                    Surface(
                        color = if (p.isAero) Color.Transparent else p.emeraldDim,
                        shape = btnShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, p.emerald),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .aeroGlassPanel(p, cornerRadius = 18.dp, tint = p.emeraldDim, borderColor = p.emerald)
                            .clickable { voiceMode = VoiceScreenMode.SIMPLE_CALL }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "ACTIVE CALL • $peerCallsignDisplay",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = p.textPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = { voiceMode = VoiceScreenMode.SIMPLE_CALL },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.heightIn(min = 36.dp)
                            ) {
                                Text("OPEN CALL", color = p.emerald, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            TextButton(
                                onClick = onEndCall,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.heightIn(min = 36.dp)
                            ) {
                                Text("END", color = p.red, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(
                        if (isCompactHeight) 8.dp else 22.dp,
                        Alignment.CenterVertically
                    )
                ) {
                    // Upgrade 3: Hardware-Inspired 16-LED Segmented VU Meter & RX Indicator Card
                    Surface(
                        color = if (p.isAero) Color.Transparent else p.card,
                        shape = cardShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, p.border),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aeroGlassPanel(p, cornerRadius = 22.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(if (isCompactHeight) 10.dp else 14.dp),
                            verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 6.dp else 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "MIC VU: $effectiveDb dB (${(effectivePeak * 100).toInt()}%)",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (effectivePeak > 0.08f) p.emerald else p.textSecondary,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "Speaker Indicator",
                                        tint = if (totalVoiceRx > 0) p.emerald else p.textMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (totalVoiceRx > 0) "${com.example.util.AppStrings.tr(appLanguage, "SPEAKER RX", "ΛΗΨΗ ΗΧΕΙΟΥ")} ($totalVoiceRx)" else com.example.util.AppStrings.tr(appLanguage, "SPEAKER READY", "ΗΧΕΙΟ ΕΤΟΙΜΟ"),
                                        maxLines = 1,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = if (totalVoiceRx > 0) p.emerald else p.textMuted,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    )
                                }
                            }

                            if (p.isOriginal) {
                                LinearProgressIndicator(
                                    progress = { effectivePeak.coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = if (effectivePeak > 0.75f) p.amber else p.emerald,
                                    trackColor = p.surfaceVariant,
                                )
                            } else {
                                // 16-LED Segmented Physical VU Meter (10 Emerald -> 4 Amber -> 2 Red Peak)
                                val litSegments = (effectivePeak.coerceIn(0f, 1f) * 16f).toInt()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(14.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(p.background)
                                        .border(1.dp, p.border, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    for (seg in 0 until 16) {
                                        val isLit = seg < litSegments
                                        val segColor = when {
                                            !isLit -> p.surfaceVariant
                                            seg >= 14 -> p.red
                                            seg >= 10 -> p.amber
                                            else -> p.emerald
                                        }
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(1.5.dp))
                                                .background(segColor)
                                        )
                                    }
                                }
                            }

                            Text(
                                text = when {
                                    isPttRecording -> "TRANSMITTING LIVE VOICE (8kHz IMA-ADPCM 484B/120ms, 0xCAFF + UDP)..."
                                    state.activeSpeakingBanner.isNotBlank() -> state.activeSpeakingBanner
                                    else -> state.pttStatus
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = when {
                                        isPttRecording -> p.red
                                        state.activeSpeakingBanner.isNotBlank() -> p.emerald
                                        else -> p.cyan
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                        }
                    }

                    // PTT Trigger: Classic Circle in Original, Glossy 3D Vista/Aqua Crystal Orb in Frutiger Aero, Knurled Trigger in HUD/Daylight
                    val bezelColor = if (isPttRecording) p.red else p.cyan
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(pttOrbSize)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        onStartPtt()
                                        try {
                                            tryAwaitRelease()
                                        } finally {
                                            onStopPtt()
                                        }
                                    }
                                )
                            }
                            .testTag("ptt_hold_circle_button")
                    ) {
                        if (p.isAero) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val outerR = size.minDimension / 2f
                                val center = Offset(size.width / 2f, size.height / 2f)
                                drawCircle(
                                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                        colors = listOf(
                                            bezelColor.copy(alpha = if (isPttRecording) 0.48f else 0.30f),
                                            Color.Transparent
                                        ),
                                        center = center,
                                        radius = outerR
                                    ),
                                    radius = outerR,
                                    center = center
                                )
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.85f),
                                    radius = outerR - 6.dp.toPx(),
                                    center = center,
                                    style = Stroke(width = 2.dp.toPx())
                                )
                            }
                        } else if (!p.isOriginal) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val outerR = size.minDimension / 2f
                                val center = Offset(size.width / 2f, size.height / 2f)
                                if (isPttRecording) {
                                    drawCircle(
                                        color = bezelColor.copy(alpha = 0.24f),
                                        radius = outerR,
                                        center = center,
                                    )
                                }
                                val tickCount = 24
                                for (i in 0 until tickCount) {
                                    val angle = (i * (360f / tickCount)) * (PI / 180.0)
                                    val r1 = outerR - 2.dp.toPx()
                                    val r2 = outerR - 8.dp.toPx()
                                    drawLine(
                                        color = bezelColor.copy(alpha = if (isPttRecording) 0.9f else 0.55f),
                                        start = Offset(
                                            center.x + (r1 * cos(angle)).toFloat(),
                                            center.y + (r1 * sin(angle)).toFloat(),
                                        ),
                                        end = Offset(
                                            center.x + (r2 * cos(angle)).toFloat(),
                                            center.y + (r2 * sin(angle)).toFloat(),
                                        ),
                                        strokeWidth = 2.dp.toPx(),
                                    )
                                }
                                drawCircle(
                                    color = bezelColor,
                                    radius = outerR - 10.dp.toPx(),
                                    center = center,
                                    style = Stroke(width = 2.5.dp.toPx()),
                                )
                            }
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(
                                    when {
                                        p.isOriginal -> pttOrbSize
                                        p.isAero -> pttOrbSize * 0.84f
                                        else -> pttOrbSize * 0.78f
                                    }
                                )
                                .clip(CircleShape)
                                .background(
                                    if (p.isAero) {
                                        androidx.compose.ui.graphics.Brush.verticalGradient(
                                            colors = listOf(
                                                androidx.compose.ui.graphics.lerp(bezelColor, Color.White, 0.55f),
                                                bezelColor,
                                                androidx.compose.ui.graphics.lerp(bezelColor, Color.Black, 0.22f),
                                                androidx.compose.ui.graphics.lerp(bezelColor, Color(0xFFB6F8FF), 0.35f)
                                            )
                                        )
                                    } else {
                                        androidx.compose.ui.graphics.SolidColor(bezelColor)
                                    }
                                )
                                .border(
                                    if (p.isOriginal) 4.dp else 3.dp,
                                    when {
                                        p.isAero -> Color.White.copy(alpha = 0.9f)
                                        isPttRecording -> p.redDim
                                        else -> p.borderBright
                                    },
                                    CircleShape
                                )
                        ) {
                            if (p.isAero) {
                                // Iconic 2000s Aqua / Vista curved specular glass dome highlight
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawOval(
                                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                            colors = listOf(Color(0xDCFFFFFF), Color(0x14FFFFFF))
                                        ),
                                        topLeft = Offset(size.width * 0.14f, size.height * 0.05f),
                                        size = androidx.compose.ui.geometry.Size(size.width * 0.72f, size.height * 0.44f)
                                    )
                                }
                            } else if (!p.isOriginal) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val lineColor = Color.Black.copy(alpha = 0.14f)
                                    val step = size.height / 9f
                                    for (idx in 2..7) {
                                        val y = idx * step
                                        drawLine(
                                            color = lineColor,
                                            start = Offset(size.width * 0.16f, y),
                                            end = Offset(size.width * 0.84f, y),
                                            strokeWidth = 1.5.dp.toPx(),
                                        )
                                    }
                                }
                            }
                            val orbForeground = if (p.isAero) Color.White else Color.Black
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Push To Talk",
                                    tint = orbForeground,
                                    modifier = Modifier.size((pttOrbSize * 0.24f).coerceIn(32.dp, 48.dp))
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isPttRecording) com.example.util.AppStrings.tr(appLanguage, "RELEASE TO SEND", "ΑΦΗΣΤΕ ΓΙΑ ΑΠΟΣΤΟΛΗ") else com.example.util.AppStrings.tr(appLanguage, "HOLD TO TALK", "ΚΡΑΤΗΣΤΕ ΓΙΑ ΟΜΙΛΙΑ"),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = orbForeground,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                            }
                        }
                    }

                    // 1-Tap Latch to Talk Toggle Button
                    Button(
                        onClick = {
                            if (isPttRecording) {
                                onStopPtt()
                            } else {
                                onStartPtt()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (p.isAero) Color.Transparent else if (isPttRecording) p.red else p.emerald,
                            contentColor = if (isPttRecording || p.isLight || p.isAero) Color.White else Color.Black
                        ),
                        shape = btnShape,
                        modifier = Modifier
                            .widthIn(max = 420.dp)
                            .fillMaxWidth(0.88f)
                            .heightIn(min = if (isCompactHeight) 42.dp else 52.dp)
                            .aeroGlossOverlay(p, if (isPttRecording) p.red else p.emerald, btnShape, cornerRadius = 18.dp)
                            .testTag("ptt_latch_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isPttRecording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isPttRecording) com.example.util.AppStrings.tr(appLanguage, "TAP TO STOP TALKING", "ΠΑΤΗΣΤΕ ΓΙΑ ΠΑΥΣΗ") else com.example.util.AppStrings.tr(appLanguage, "1-TAP LATCH TO TALK", "1-ΠΑΤΗΜΑ ΣΥΝΕΧΗΣ ΟΜΙΛΙΑ"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Walkie-Talkie PTT Dedicated "Replay Last RX" Button (Voice Drop removed from Walkie-Talkie!)
                    OutlinedButton(
                        onClick = onReplayLastPttRx,
                        enabled = cachedPttRxReplayFrames > 0 && !isReplayingPttRx,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isReplayingPttRx) p.cyanDim else p.card,
                            contentColor = if (cachedPttRxReplayFrames > 0) p.cyan else p.textMuted
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (cachedPttRxReplayFrames > 0) p.cyan else p.border
                        ),
                        shape = btnShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (isCompactHeight) 6.dp else 10.dp),
                        modifier = Modifier
                            .widthIn(max = 420.dp)
                            .fillMaxWidth(0.88f)
                            .heightIn(min = if (isCompactHeight) 40.dp else 46.dp)
                            .testTag("ptt_replay_last_rx_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay,
                            contentDescription = "Replay Last PTT RX",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isReplayingPttRx) {
                                com.example.util.AppStrings.tr(appLanguage, "REPLAYING PTT RX...", "ΕΠΑΝΑΛΗΨΗ ΛΗΨΗΣ PTT...")
                            } else {
                                com.example.util.AppStrings.tr(appLanguage, "REPLAY LAST WALKIE RX (${(cachedPttRxReplayFrames * 160) / 1000}s)", "ΕΠΑΝΑΛΗΨΗ ΤΕΛΕΥΤΑΙΑΣ ΛΗΨΗΣ (${(cachedPttRxReplayFrames * 160) / 1000}δ)")
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Inline Bell 202 Acoustic AFSK Air-Gap Modem Card (PulseMesh v1.4.6)
                    var bell202Draft by remember { mutableStateOf("") }
                    Surface(
                        color = p.card,
                        shape = cardShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, p.cyan.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .widthIn(max = 420.dp)
                            .fillMaxWidth(0.92f)
                            .testTag("voice_bell202_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = com.example.util.AppStrings.tr(
                                        appLanguage,
                                        "BELL 202 ACOUSTIC AFSK (1200/2200 Hz)",
                                        "ΑΚΟΥΣΤΙΚΟ MODEM BELL 202 (1200/2200 Hz)"
                                    ),
                                    color = p.cyan,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "TX:${state.txAudioCount} • RX:$totalVoiceRx",
                                    color = p.textMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                )
                            }
                            OutlinedTextField(
                                value = bell202Draft,
                                onValueChange = { if (it.length <= 96) bell202Draft = it },
                                placeholder = {
                                    Text(
                                        com.example.util.AppStrings.tr(
                                            appLanguage,
                                            "Zero-RF air-gap message (max 96 chars)...",
                                            "Μήνυμα χωρίς σήμα (έως 96 χαρακτήρες)..."
                                        ),
                                        fontSize = 12.sp
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = {
                                        onTransmitBell202Message(bell202Draft, false)
                                        bell202Draft = ""
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = p.cyan, contentColor = Color.Black),
                                    shape = btnShape,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        com.example.util.AppStrings.tr(appLanguage, "Chirp TX", "Εκπομπή"),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                                Button(
                                    onClick = { onTransmitBell202Message(bell202Draft, true) },
                                    colors = ButtonDefaults.buttonColors(containerColor = p.red, contentColor = Color.White),
                                    shape = btnShape,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        com.example.util.AppStrings.tr(appLanguage, "Acoustic SOS", "SOS Ήχου"),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                                OutlinedButton(
                                    onClick = onToggleBell202Modem,
                                    shape = btnShape,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (state.bell202ModemEnabled) p.emerald else p.border
                                    ),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = if (state.bell202ModemEnabled) "RX: ON" else "RX: OFF",
                                        color = if (state.bell202ModemEnabled) p.emerald else p.textSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(52.dp))
                }
            }
        }
    }
}
