// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.screens.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.model.MeshNode
import com.kostadinosk.pulsemesh.service.CallSignalingState
import com.kostadinosk.pulsemesh.service.DuplexCallState
import com.kostadinosk.pulsemesh.ui.theme.LocalPulseTokens
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.ui.theme.pulseSurfaceCard
import com.kostadinosk.pulsemesh.util.AppStrings
import com.kostadinosk.pulsemesh.util.ThemeBrightness
import com.kostadinosk.pulsemesh.util.ThemePreset
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState
import com.kostadinosk.pulsemesh.voice.AudioRoute
import kotlin.math.abs
import kotlin.math.sin

/**
 * Compact Voice Hub telemetry & audio-route bar:
 * - E2EE Voice Verified shield badge (`voice_e2ee_badge`) + live Codec/Transport pill
 * - 1-tap Audio Route selector chips (`Earpiece`, `Speaker`, `Wired`, `Bluetooth`, `LE Audio`)
 * - Optional Codec/RTT/Jitter/Loss HUD strip in Field Ops HUD or when `showTacticalHud` is enabled
 */
@Composable
fun VoiceHubHeaderCard(
    state: MeshUiState,
    onSelectAudioRoute: (AudioRoute) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    val routes = state.availableAudioRoutes.ifEmpty { listOf(AudioRoute.SPEAKER, AudioRoute.EARPIECE) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = tokens.statusOnline.copy(alpha = 0.14f),
                shape = tokens.pillShape,
                border = BorderStroke(1.dp, tokens.statusOnline.copy(alpha = 0.45f)),
                modifier = Modifier.testTag("voice_e2ee_badge")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = tokens.statusOnline,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (state.unifiedCallState.isE2eeVerified) "E2EE VOICE VERIFIED" else "MESH VOICE",
                        color = tokens.statusOnline,
                        fontFamily = tokens.labelFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }

            Surface(
                color = p.surfaceVariant,
                shape = tokens.pillShape,
                border = BorderStroke(1.dp, p.border)
            ) {
                Text(
                    text = "${state.voiceCodecStats.codecName.ifBlank { "Opus" }} • ${state.activeAudioRoute.label}",
                    color = tokens.accentSecondary,
                    fontFamily = tokens.labelFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            routes.forEach { route ->
                val selected = route == state.activeAudioRoute
                Surface(
                    color = if (selected) tokens.accentSecondary.copy(alpha = 0.2f) else p.surfaceVariant,
                    shape = tokens.pillShape,
                    border = BorderStroke(1.dp, if (selected) tokens.accentSecondary else p.border),
                    modifier = Modifier
                        .clip(tokens.pillShape)
                        .clickable { onSelectAudioRoute(route) }
                        .testTag("voice_route_${route.name.lowercase()}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = iconForRoute(route),
                            contentDescription = route.label,
                            tint = if (selected) tokens.accentSecondary else p.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = route.label,
                            color = if (selected) p.textPrimary else p.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        if (state.showTacticalHud || tokens.showHudTelemetryBadges) {
            Surface(
                color = p.surfaceVariant,
                shape = tokens.buttonShape,
                border = BorderStroke(tokens.borderWidth, p.border),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("voice_codec_hud_strip")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.voiceCodecStats.codecName,
                        color = tokens.accentSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "RTT ${state.voiceCodecStats.rttMs}ms • JIT ${state.voiceCodecStats.jitterMs}ms • LOSS ${"%.1f".format(state.voiceCodecStats.packetLossPct)}%",
                        color = p.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

/**
 * PulseMesh v2.0 Modular Voice & Walkie-Talkie Hub:
 * - Segmented switcher: Walkie-Talkie PTT | Direct Calls | Room Conference
 * - Hold-to-Talk & Lock-to-Talk ergonomic deck with live audio level waveform
 * - Active call overlay + incoming call accept/decline banner
 * - Direct peer dialer + Conference room participant grid
 */
@Composable
fun PulseVoiceHubScreen(
    state: MeshUiState,
    duplexCallState: DuplexCallState,
    onStartCall: (MeshNode) -> Unit,
    onStartGroupCall: () -> Unit,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onStartPtt: () -> Unit,
    onStopPtt: () -> Unit,
    onAcceptCall: () -> Unit,
    onDeclineCall: () -> Unit,
    onReplayLastRx: () -> Unit,
    onSelectAudioRoute: (AudioRoute) -> Unit,
    onJoinPttChannel: (String) -> Unit,
    onToggleCourtesyBeep: (Boolean) -> Unit,
    onToggleConferenceMute: (String) -> Unit,
    onKickConferencePeer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    val lang = state.appLanguage

    var selectedSegment by remember { mutableIntStateOf(0) } // 0=PTT, 1=Calls, 2=Conference
    var isPttLocked by remember { mutableStateOf(false) }

    val peers = remember(state.nodes) { state.nodes.filter { !it.isSelf } }
    val isIncomingRinging = duplexCallState.signalingState == CallSignalingState.RINGING_INCOMING
    val isInActiveCall = duplexCallState.isInCall

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("voice_hub_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        VoiceHubHeaderCard(
            state = state,
            onSelectAudioRoute = onSelectAudioRoute
        )

        // Incoming Call Card
        if (isIncomingRinging) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseSurfaceCard(accentBorder = tokens.statusOnline)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = AppStrings.tr(lang, "Incoming Encrypted Call", "Εισερχόμενη Κρυπτογραφημένη Κλήση"),
                    color = tokens.statusOnline,
                    fontFamily = tokens.labelFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Text(
                    text = duplexCallState.peerCallsign.ifBlank { state.remoteCallerName.ifBlank { "Mesh Peer" } },
                    color = p.textPrimary,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onDeclineCall,
                        shape = tokens.buttonShape,
                        colors = ButtonDefaults.buttonColors(containerColor = tokens.statusDanger, contentColor = Color.White),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(AppStrings.tr(lang, "Decline", "Απόρριψη"), fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onAcceptCall,
                        shape = tokens.buttonShape,
                        colors = ButtonDefaults.buttonColors(containerColor = tokens.statusOnline, contentColor = Color.Black),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(AppStrings.tr(lang, "Accept", "Αποδοχή"), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Active Duplex Call Panel
        if (isInActiveCall) {
            ActiveCallPanel(
                callState = state.unifiedCallState.copy(
                    peerCallsign = duplexCallState.peerCallsign.ifBlank { state.unifiedCallState.peerCallsign },
                    isMuted = duplexCallState.isMuted,
                    isSpeakerphoneOn = duplexCallState.isSpeakerphoneOn
                ),
                showHud = state.showTacticalHud || tokens.showHudTelemetryBadges,
                onToggleMute = onToggleMute,
                onToggleSpeaker = onToggleSpeaker,
                onEndCall = onEndCall
            )
        }

        // 3-Mode Segmented Switcher
        val segments = listOf(
            AppStrings.tr(lang, "Walkie-Talkie", "Ασύρματος PTT"),
            AppStrings.tr(lang, "Direct Calls", "Κλήσεις"),
            AppStrings.tr(lang, "Conference", "Δωμάτιο")
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(tokens.buttonShape)
                .background(p.surfaceVariant)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            segments.forEachIndexed { idx, label ->
                val active = selectedSegment == idx
                Surface(
                    color = if (active) tokens.accentPrimary else Color.Transparent,
                    shape = tokens.buttonShape,
                    modifier = Modifier
                        .weight(1f)
                        .clip(tokens.buttonShape)
                        .clickable { selectedSegment = idx }
                        .testTag("voice_segment_$idx")
                ) {
                    Text(
                        text = label,
                        color = if (active) (if (tokens.isDark) Color.Black else Color.White) else p.textSecondary,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 9.dp)
                    )
                }
            }
        }

        when (selectedSegment) {
            0 -> {
                // Ergonomic Hold-to-Talk & Lock-to-Talk Deck + Live Waveform
                WalkieTalkieDeckPanel(
                    channels = state.pttChannels,
                    activeChannelId = state.activePttChannelId,
                    courtesyBeepEnabled = state.pttCourtesyBeepEnabled,
                    onJoinChannel = onJoinPttChannel,
                    onToggleCourtesyBeep = onToggleCourtesyBeep
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseSurfaceCard(accentBorder = if (state.isPttPressed) tokens.statusDanger else p.border)
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = state.activeSpeakingBanner.ifBlank { state.pttStatus },
                        color = if (state.isPttPressed) tokens.statusDanger else tokens.statusOnline,
                        fontFamily = tokens.labelFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )

                    LiveVoiceWaveform(
                        isActive = state.isPttPressed || isInActiveCall,
                        accent = if (state.isPttPressed) tokens.statusDanger else tokens.accentPrimary
                    )

                    // Big Ergonomic Hold-to-Talk Orb
                    val pttColor = if (state.isPttPressed) tokens.statusDanger else tokens.accentPrimary
                    Box(
                        modifier = Modifier
                            .size(144.dp)
                            .clip(CircleShape)
                            .background(pttColor.copy(alpha = if (state.isPttPressed) 0.92f else 0.18f))
                            .pointerInput(isPttLocked) {
                                detectTapGestures(
                                    onPress = {
                                        if (!isPttLocked) {
                                            onStartPtt()
                                            tryAwaitRelease()
                                            onStopPtt()
                                        }
                                    }
                                )
                            }
                            .testTag("voice_ptt_hold_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Hold to Talk",
                                tint = if (state.isPttPressed) Color.White else pttColor,
                                modifier = Modifier.size(42.dp)
                            )
                            Text(
                                text = if (state.isPttPressed) {
                                    AppStrings.tr(lang, "TRANSMITTING", "ΕΚΠΟΜΠΗ")
                                } else {
                                    AppStrings.tr(lang, "HOLD TO TALK", "ΚΡΑΤΗΣΤΕ")
                                },
                                color = if (state.isPttPressed) Color.White else p.textPrimary,
                                fontFamily = tokens.labelFontFamily,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                isPttLocked = !isPttLocked
                                if (isPttLocked) onStartPtt() else onStopPtt()
                            },
                            shape = tokens.buttonShape,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("voice_ptt_lock_btn")
                        ) {
                            Icon(
                                imageVector = if (isPttLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (isPttLocked) AppStrings.tr(lang, "Unlock TX", "Ξεκλείδωμα")
                                else AppStrings.tr(lang, "Lock-to-Talk", "Κλείδωμα TX"),
                                fontSize = 12.sp
                            )
                        }
                        OutlinedButton(
                            onClick = onReplayLastRx,
                            shape = tokens.buttonShape,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(AppStrings.tr(lang, "Replay RX", "Επανάληψη"), fontSize = 12.sp)
                        }
                    }
                }
            }

            1 -> {
                // Direct Calls Dialer
                VoiceDialerPanel(
                    peers = peers,
                    onStartGroupCall = onStartGroupCall,
                    onCallPeer = onStartCall
                )
                if (peers.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pulseSurfaceCard()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = AppStrings.tr(lang, "No nearby peers discovered yet", "Δεν βρέθηκαν ακόμη κοντινοί κόμβοι"),
                            color = p.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = AppStrings.tr(lang, "Use the QR/Connect button in the top bar or enable Hybrid Relay to call peers.", "Χρησιμοποιήστε το QR/Σύνδεση επάνω ή την Υβριδική λειτουργία για κλήσεις."),
                            color = p.textSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            else -> {
                // Room Conference Grid
                Button(
                    onClick = onStartGroupCall,
                    shape = tokens.buttonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.accentPrimary,
                        contentColor = if (tokens.isDark) Color.Black else Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(AppStrings.tr(lang, "Start / Join Mesh Conference Room", "Έναρξη / Συμμετοχή σε Ομαδική Κλήση"), fontWeight = FontWeight.Bold)
                }
                ConferenceGridPanel(
                    peers = state.conferencePeers,
                    onToggleMutePeer = onToggleConferenceMute,
                    onKickPeer = onKickConferencePeer
                )
            }
        }
    }
}

@Composable
private fun LiveVoiceWaveform(
    isActive: Boolean,
    accent: Color
) {
    val transition = rememberInfiniteTransition(label = "voice_wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
    ) {
        val bars = 28
        val bw = (size.width / (bars * 1.7f)).coerceAtLeast(3.dp.toPx())
        val gap = (size.width - bw * bars) / (bars - 1)
        for (i in 0 until bars) {
            val amp = if (isActive) {
                0.2f + 0.8f * abs(sin(phase + i * 0.45f))
            } else {
                0.15f + 0.08f * abs(sin(i * 0.6f))
            }
            val h = size.height * amp
            val x = i * (bw + gap)
            val y = (size.height - h) / 2f
            drawRoundRect(
                color = if (isActive) accent else accent.copy(alpha = 0.3f),
                topLeft = Offset(x, y),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw / 2f, bw / 2f)
            )
        }
    }
}

// Legacy wrapper retained so any existing component tests keep compiling cleanly
@Composable
fun VoiceHubScreen(
    state: MeshUiState,
    onSelectAudioRoute: (AudioRoute) -> Unit,
    onJoinPttChannel: (String) -> Unit,
    onToggleCourtesyBeep: (Boolean) -> Unit,
    onToggleConferenceMute: (String) -> Unit,
    onKickConferencePeer: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("voice_hub_screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        VoiceHubHeaderCard(
            state = state,
            onSelectAudioRoute = onSelectAudioRoute
        )
        ConferenceGridPanel(
            peers = state.conferencePeers,
            onToggleMutePeer = onToggleConferenceMute,
            onKickPeer = onKickConferencePeer
        )
        WalkieTalkieDeckPanel(
            channels = state.pttChannels,
            activeChannelId = state.activePttChannelId,
            courtesyBeepEnabled = state.pttCourtesyBeepEnabled,
            onJoinChannel = onJoinPttChannel,
            onToggleCourtesyBeep = onToggleCourtesyBeep
        )
        content()
    }
}

private fun iconForRoute(route: AudioRoute): ImageVector = when (route) {
    AudioRoute.EARPIECE -> Icons.Default.Hearing
    AudioRoute.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
    AudioRoute.WIRED_HEADSET -> Icons.Default.HeadsetMic
    AudioRoute.BLUETOOTH_SCO -> Icons.Default.BluetoothAudio
    AudioRoute.BLE_AUDIO -> Icons.Default.BluetoothConnected
}

@Preview(name = "PulseVoiceHubScreen Practical Dark", showBackground = true)
@Composable
fun PreviewPulseVoiceHubDark() {
    PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
        Surface(color = LocalPulseTokens.current.palette.background) {
            PulseVoiceHubScreen(
                state = MeshUiState(operatorCallsign = "Alpha-01", pttStatus = "STANDBY • READY TO TRANSMIT"),
                duplexCallState = DuplexCallState(),
                onStartCall = {},
                onStartGroupCall = {},
                onEndCall = {},
                onToggleMute = {},
                onToggleSpeaker = {},
                onStartPtt = {},
                onStopPtt = {},
                onAcceptCall = {},
                onDeclineCall = {},
                onReplayLastRx = {},
                onSelectAudioRoute = {},
                onJoinPttChannel = {},
                onToggleCourtesyBeep = {},
                onToggleConferenceMute = {},
                onKickConferencePeer = {}
            )
        }
    }
}
