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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
 * Compact Voice Hub telemetry & audio-route bar (shared with tests & Phone screen).
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
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = tokens.statusOnline, modifier = Modifier.size(14.dp))
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
                    Text(state.voiceCodecStats.codecName, color = tokens.accentSecondary, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
 * PulseMesh v2.0.1 Phone-App Style Voice & Call Hub:
 * - When ringing or in an active call: clean full-screen Phone Call layout (large avatar, MM:SS timer, circular Mute/Speaker/Replay controls, centered red End Call button).
 * - When idle: starts on the familiar Phone-style **Calls** page (quick dial input, 1-tap Group Mesh Call, and clean contact list with instant green Call buttons), with Walkie-Talkie PTT and Conference right beside it.
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

    // Default to Phone Call page (0 = Calls, 1 = Walkie-Talkie, 2 = Conference)
    var selectedSegment by remember { mutableIntStateOf(0) }
    var isPttLocked by remember { mutableStateOf(false) }
    var quickDialTarget by remember { mutableStateOf("") }

    val peers = remember(state.nodes) { state.nodes.filter { !it.isSelf } }
    val isIncomingRinging = duplexCallState.signalingState == CallSignalingState.RINGING_INCOMING
    val isOutgoingRinging = duplexCallState.signalingState == CallSignalingState.RINGING_OUTGOING || duplexCallState.isRinging
    val isInActiveCall = duplexCallState.isInCall

    // Dedicated Full-Panel Phone Call Screen whenever ringing or active (just like native Phone app)
    if (isIncomingRinging || isOutgoingRinging || isInActiveCall) {
        val callerName = duplexCallState.peerCallsign.ifBlank {
            state.remoteCallerName.ifBlank { AppStrings.tr(lang, "Mesh Peer", "Κόμβος Mesh") }
        }
        val durationMin = duplexCallState.durationSeconds / 60
        val durationSec = duplexCallState.durationSeconds % 60
        val statusText = when {
            isIncomingRinging -> AppStrings.tr(lang, "Incoming Encrypted Call...", "Εισερχόμενη Κρυπτογραφημένη Κλήση...")
            isInActiveCall -> "%02d:%02d • %s".format(durationMin, durationSec, state.activeAudioRoute.label)
            else -> AppStrings.tr(lang, "Calling...", "Κλήση...")
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .testTag("phone_active_call_page"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            VoiceHubHeaderCard(state = state, onSelectAudioRoute = onSelectAudioRoute)

            // Large Caller Avatar + Name + Timer
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(tokens.accentPrimary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = callerName.trim().take(1).uppercase().ifBlank { "P" },
                        color = tokens.accentPrimary,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 40.sp
                    )
                }
                Text(
                    text = callerName,
                    color = p.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                )
                Text(
                    text = statusText,
                    color = if (isInActiveCall) tokens.statusOnline else tokens.accentSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                LiveVoiceWaveform(
                    isActive = isInActiveCall,
                    accent = tokens.statusOnline
                )
            }

            // Bottom Native Phone-App Controls
            if (isIncomingRinging) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PhoneCircleActionButton(
                        icon = Icons.Default.CallEnd,
                        label = AppStrings.tr(lang, "Decline", "Απόρριψη"),
                        containerColor = tokens.statusDanger,
                        contentColor = Color.White,
                        sizeDp = 70,
                        onClick = onDeclineCall,
                        tag = "phone_call_decline_btn"
                    )
                    PhoneCircleActionButton(
                        icon = Icons.Default.Call,
                        label = AppStrings.tr(lang, "Accept", "Αποδοχή"),
                        containerColor = tokens.statusOnline,
                        contentColor = Color.Black,
                        sizeDp = 70,
                        onClick = onAcceptCall,
                        tag = "phone_call_accept_btn"
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        PhoneCircleActionButton(
                            icon = if (duplexCallState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            label = if (duplexCallState.isMuted) AppStrings.tr(lang, "Unmute", "Ήχος") else AppStrings.tr(lang, "Mute", "Σίγαση"),
                            containerColor = if (duplexCallState.isMuted) tokens.statusWarning else p.surfaceVariant,
                            contentColor = if (duplexCallState.isMuted) Color.Black else p.textPrimary,
                            sizeDp = 58,
                            onClick = onToggleMute,
                            tag = "phone_call_mute_btn"
                        )
                        PhoneCircleActionButton(
                            icon = Icons.AutoMirrored.Filled.VolumeUp,
                            label = if (duplexCallState.isSpeakerphoneOn) AppStrings.tr(lang, "Speaker", "Ηχείο") else AppStrings.tr(lang, "Earpiece", "Ακουστικό"),
                            containerColor = if (duplexCallState.isSpeakerphoneOn) tokens.accentSecondary else p.surfaceVariant,
                            contentColor = if (duplexCallState.isSpeakerphoneOn) Color.White else p.textPrimary,
                            sizeDp = 58,
                            onClick = onToggleSpeaker,
                            tag = "phone_call_speaker_btn"
                        )
                        PhoneCircleActionButton(
                            icon = Icons.Default.Replay,
                            label = AppStrings.tr(lang, "Replay", "Επανάληψη"),
                            containerColor = p.surfaceVariant,
                            contentColor = p.textPrimary,
                            sizeDp = 58,
                            onClick = onReplayLastRx,
                            tag = "phone_call_replay_btn"
                        )
                    }
                    PhoneCircleActionButton(
                        icon = Icons.Default.CallEnd,
                        label = AppStrings.tr(lang, "End Call", "Τερματισμός"),
                        containerColor = tokens.statusDanger,
                        contentColor = Color.White,
                        sizeDp = 72,
                        onClick = onEndCall,
                        tag = "phone_call_end_btn"
                    )
                }
            }
        }
        return
    }

    // Idle Voice Hub: Phone Call Page (default) | Walkie-Talkie PTT | Conference
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("voice_hub_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 3-Mode Segmented Switcher (Calls is primary default!)
        val segments = listOf(
            AppStrings.tr(lang, "Calls", "Κλήσεις"),
            AppStrings.tr(lang, "Walkie-Talkie", "Ασύρματος"),
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
                // Clean Phone-App Style Direct Call Page
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("phone_dialer_page"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Quick Dial Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = quickDialTarget,
                            onValueChange = { quickDialTarget = it.take(32) },
                            placeholder = { Text(AppStrings.tr(lang, "Call @username or 0xNodeId...", "Κλήση @username ή 0xNodeId..."), fontSize = 12.sp) },
                            singleLine = true,
                            shape = tokens.pillShape,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("phone_quick_dial_input")
                        )
                        Surface(
                            color = tokens.statusOnline,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable {
                                    val clean = quickDialTarget.trim()
                                    if (clean.isNotBlank()) {
                                        val existing = peers.firstOrNull {
                                            it.id.equals(clean, ignoreCase = true) ||
                                                it.callsign.equals(clean.removePrefix("@"), ignoreCase = true)
                                        }
                                        val target = existing ?: MeshNode(
                                            id = if (clean.startsWith("0x", ignoreCase = true)) clean else "0x${clean.removePrefix("@")}",
                                            callsign = clean
                                        )
                                        onStartCall(target)
                                        quickDialTarget = ""
                                    }
                                }
                                .testTag("phone_quick_dial_btn")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Call, contentDescription = "Call", tint = Color.Black, modifier = Modifier.size(22.dp))
                            }
                        }
                    }

                    // 1-Tap Group Mesh Call Banner
                    Button(
                        onClick = onStartGroupCall,
                        shape = tokens.buttonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.accentPrimary,
                            contentColor = if (tokens.isDark) Color.Black else Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("phone_group_call_btn")
                    ) {
                        Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(AppStrings.tr(lang, "Start Group Mesh Call", "Ομαδική Κλήση Mesh"), fontWeight = FontWeight.Bold)
                    }

                    // Contacts / Nearby Mesh Peers List
                    Text(
                        text = AppStrings.tr(lang, "Contacts & Nearby Mesh Peers", "Επαφές & Κοντινοί Κόμβοι"),
                        color = p.textSecondary,
                        fontFamily = tokens.labelFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )

                    if (peers.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pulseSurfaceCard()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = p.textMuted, modifier = Modifier.size(32.dp))
                            Text(
                                text = AppStrings.tr(lang, "No nearby peers yet", "Δεν βρέθηκαν ακόμη κοντινοί κόμβοι"),
                                color = p.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = AppStrings.tr(lang, "Type a @username above, pair via QR in the top bar, or wait for nearby BLE/Wi-Fi peers.", "Πληκτρολογήστε @username επάνω ή σαρώστε QR από τη γραμμή κορυφής."),
                                color = p.textSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        peers.forEach { peer ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pulseSurfaceCard()
                                    .clickable { onStartCall(peer) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(tokens.accentSecondary.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = peer.callsign.trim().take(1).uppercase().ifBlank { "M" },
                                            color = tokens.accentSecondary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        )
                                    }
                                    Column {
                                        Text(peer.callsign, color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Text(
                                            text = "${peer.id} • ${peer.rssiDbm} dBm • ${peer.hopCount}h",
                                            color = p.textSecondary,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                                Surface(
                                    color = tokens.statusOnline.copy(alpha = 0.18f),
                                    shape = CircleShape,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    IconButton(onClick = { onStartCall(peer) }) {
                                        Icon(Icons.Default.Call, contentDescription = "Call ${peer.callsign}", tint = tokens.statusOnline, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }

                    // Audio route quick selector tucked cleanly below contacts
                    VoiceHubHeaderCard(state = state, onSelectAudioRoute = onSelectAudioRoute)
                }
            }

            1 -> {
                // Walkie-Talkie PTT Deck
                VoiceHubHeaderCard(state = state, onSelectAudioRoute = onSelectAudioRoute)
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
                        isActive = state.isPttPressed,
                        accent = if (state.isPttPressed) tokens.statusDanger else tokens.accentPrimary
                    )
                    val pttColor = if (state.isPttPressed) tokens.statusDanger else tokens.accentPrimary
                    Box(
                        modifier = Modifier
                            .size(140.dp)
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
                            Icon(Icons.Default.Mic, contentDescription = "Hold to Talk", tint = if (state.isPttPressed) Color.White else pttColor, modifier = Modifier.size(40.dp))
                            Text(
                                text = if (state.isPttPressed) AppStrings.tr(lang, "TRANSMITTING", "ΕΚΠΟΜΠΗ") else AppStrings.tr(lang, "HOLD TO TALK", "ΚΡΑΤΗΣΤΕ"),
                                color = if (state.isPttPressed) Color.White else p.textPrimary,
                                fontFamily = tokens.labelFontFamily,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                isPttLocked = !isPttLocked
                                if (isPttLocked) onStartPtt() else onStopPtt()
                            },
                            shape = tokens.buttonShape,
                            modifier = Modifier.weight(1f).testTag("voice_ptt_lock_btn")
                        ) {
                            Icon(if (isPttLocked) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isPttLocked) AppStrings.tr(lang, "Unlock TX", "Ξεκλείδωμα") else AppStrings.tr(lang, "Lock-to-Talk", "Κλείδωμα"), fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = onReplayLastRx, shape = tokens.buttonShape, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(AppStrings.tr(lang, "Replay RX", "Επανάληψη"), fontSize = 12.sp)
                        }
                    }
                }
            }

            else -> {
                // Conference Room
                VoiceHubHeaderCard(state = state, onSelectAudioRoute = onSelectAudioRoute)
                Button(
                    onClick = onStartGroupCall,
                    shape = tokens.buttonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.accentPrimary, contentColor = if (tokens.isDark) Color.Black else Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(AppStrings.tr(lang, "Start / Join Mesh Conference Room", "Έναρξη Ομαδικής Κλήσης"), fontWeight = FontWeight.Bold)
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
private fun PhoneCircleActionButton(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    sizeDp: Int,
    onClick: () -> Unit,
    tag: String
) {
    val p = LocalPulseTokens.current.palette
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            color = containerColor,
            shape = CircleShape,
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .clickable { onClick() }
                .testTag(tag)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = contentColor, modifier = Modifier.size((sizeDp * 0.44f).dp))
            }
        }
        Text(label, color = p.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 900, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "wave_phase"
    )
    Canvas(modifier = Modifier.fillMaxWidth().height(34.dp)) {
        val bars = 28
        val bw = (size.width / (bars * 1.7f)).coerceAtLeast(3.dp.toPx())
        val gap = (size.width - bw * bars) / (bars - 1)
        for (i in 0 until bars) {
            val amp = if (isActive) 0.2f + 0.8f * abs(sin(phase + i * 0.45f)) else 0.15f + 0.08f * abs(sin(i * 0.6f))
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

// Legacy wrapper retained for backward test compatibility
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
        modifier = modifier.fillMaxSize().testTag("voice_hub_screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        VoiceHubHeaderCard(state = state, onSelectAudioRoute = onSelectAudioRoute)
        ConferenceGridPanel(peers = state.conferencePeers, onToggleMutePeer = onToggleConferenceMute, onKickPeer = onKickConferencePeer)
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

@Preview(name = "Phone Call Page (Idle)", showBackground = true)
@Composable
fun PreviewPhoneDialerPage() {
    PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
        Surface(color = LocalPulseTokens.current.palette.background) {
            PulseVoiceHubScreen(
                state = MeshUiState(
                    nodes = listOf(
                        MeshNode(id = "0xA1B2", callsign = "Bravo-02", rssiDbm = -58, hopCount = 1),
                        MeshNode(id = "0xC3D4", callsign = "Sierra-04", rssiDbm = -72, hopCount = 2)
                    )
                ),
                duplexCallState = DuplexCallState(),
                onStartCall = {}, onStartGroupCall = {}, onEndCall = {}, onToggleMute = {},
                onToggleSpeaker = {}, onStartPtt = {}, onStopPtt = {}, onAcceptCall = {},
                onDeclineCall = {}, onReplayLastRx = {}, onSelectAudioRoute = {},
                onJoinPttChannel = {}, onToggleCourtesyBeep = {}, onToggleConferenceMute = {},
                onKickConferencePeer = {}
            )
        }
    }
}

@Preview(name = "Phone Call Page (Active Call)", showBackground = true)
@Composable
fun PreviewPhoneActiveCallPage() {
    PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
        Surface(color = LocalPulseTokens.current.palette.background) {
            PulseVoiceHubScreen(
                state = MeshUiState(),
                duplexCallState = DuplexCallState(isInCall = true, peerCallsign = "Bravo-02", durationSeconds = 95, isSpeakerphoneOn = true),
                onStartCall = {}, onStartGroupCall = {}, onEndCall = {}, onToggleMute = {},
                onToggleSpeaker = {}, onStartPtt = {}, onStopPtt = {}, onAcceptCall = {},
                onDeclineCall = {}, onReplayLastRx = {}, onSelectAudioRoute = {},
                onJoinPttChannel = {}, onToggleCourtesyBeep = {}, onToggleConferenceMute = {},
                onKickConferencePeer = {}
            )
        }
    }
}
