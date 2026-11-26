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
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.kostadinosk.pulsemesh.service.WhatsAppCallPhase
import com.kostadinosk.pulsemesh.ui.theme.LocalPulseTokens
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.ui.theme.pulseSurfaceCard
import com.kostadinosk.pulsemesh.util.AppStrings
import com.kostadinosk.pulsemesh.util.ThemeBrightness
import com.kostadinosk.pulsemesh.util.ThemePreset
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState
import com.kostadinosk.pulsemesh.voice.AudioRoute
import com.kostadinosk.pulsemesh.voice.BluetoothAudioDeviceInfo
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
    onStartSelectiveGroupCall: (List<MeshNode>) -> Unit = { onStartGroupCall() },
    onSelectBluetoothDevice: (AudioRoute, Int?) -> Unit = { route, _ -> onSelectAudioRoute(route) },
    onSearchBluetoothAudio: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    val lang = state.appLanguage

    // 0 = Calls (1-to-1 + Selective Group Voice Calls), 1 = Walkie-Talkie (no PTT channels), 2 = Active Room
    var selectedSegment by remember { mutableIntStateOf(0) }
    var isPttLocked by remember { mutableStateOf(false) }
    var quickDialTarget by remember { mutableStateOf("") }
    var showAudioDevicePicker by remember { mutableStateOf(false) }
    var isMultiSelectGroupMode by remember { mutableStateOf(false) }
    var selectedGroupPeerIds by remember { mutableStateOf(setOf<String>()) }

    val peers = remember(state.nodes) { state.nodes.filter { !it.isSelf } }
    val isIncomingRinging = duplexCallState.signalingState == CallSignalingState.RINGING_INCOMING
    val isOutgoingRinging = duplexCallState.signalingState == CallSignalingState.RINGING_OUTGOING || duplexCallState.isRinging
    val isInActiveCall = duplexCallState.isInCall

    if (showAudioDevicePicker) {
        AudioOutputPickerDialog(
            state = state,
            lang = lang,
            onSelectRoute = { route, devId ->
                onSelectBluetoothDevice(route, devId)
                showAudioDevicePicker = false
            },
            onSearchBluetooth = onSearchBluetoothAudio,
            onDismiss = { showAudioDevicePicker = false }
        )
    }

    // Dedicated Full-Panel WhatsApp-Style Voice Call Screen whenever ringing or active
    if (isIncomingRinging || isOutgoingRinging || isInActiveCall) {
        val callerName = duplexCallState.peerCallsign.ifBlank {
            state.remoteCallerName.ifBlank { AppStrings.tr(lang, "Mesh Peer", "Κόμβος Mesh") }
        }
        val durationMin = duplexCallState.durationSeconds / 60
        val durationSec = duplexCallState.durationSeconds % 60
        val statusText = when {
            isIncomingRinging -> AppStrings.tr(lang, "Incoming voice call...", "Εισερχόμενη φωνητική κλήση...")
            isInActiveCall && duplexCallState.callPhase == WhatsAppCallPhase.CONNECTING && duplexCallState.durationSeconds == 0 ->
                AppStrings.tr(lang, "Connecting...", "Σύνδεση...")
            isInActiveCall -> "%02d:%02d".format(durationMin, durationSec)
            duplexCallState.callPhase == WhatsAppCallPhase.RINGING ->
                AppStrings.tr(lang, "Ringing...", "Κουδουνίζει...")
            else -> AppStrings.tr(lang, "Calling...", "Κλήση...")
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .testTag("phone_active_call_page"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = tokens.statusOnline,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = if (duplexCallState.isWebRtcEngineActive) {
                        AppStrings.tr(lang, "End-to-end encrypted • HD Opus", "Κρυπτογραφημένη • HD Opus")
                    } else {
                        AppStrings.tr(lang, "End-to-end encrypted", "Κρυπτογραφημένη κλήση")
                    },
                    color = p.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(108.dp)
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
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center
                )
                if (duplexCallState.selectedParticipants.size > 1) {
                    Text(
                        text = duplexCallState.selectedParticipants.joinToString(", "),
                        color = p.textSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
                Text(
                    text = statusText,
                    color = if (isInActiveCall) tokens.statusOnline else p.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.testTag("whatsapp_call_phase_label")
                )
                if (isInActiveCall) {
                    LiveVoiceWaveform(
                        isActive = true,
                        accent = tokens.statusOnline
                    )
                }
            }

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
                        modifier = Modifier
                            .fillMaxWidth()
                            .pulseSurfaceCard()
                            .padding(vertical = 14.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        PhoneCircleActionButton(
                            icon = if (duplexCallState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            label = if (duplexCallState.isMuted) AppStrings.tr(lang, "Unmute", "Ήχος") else AppStrings.tr(lang, "Mute", "Σίγαση"),
                            containerColor = if (duplexCallState.isMuted) tokens.statusWarning else p.surfaceVariant,
                            contentColor = if (duplexCallState.isMuted) Color.Black else p.textPrimary,
                            sizeDp = 56,
                            onClick = onToggleMute,
                            tag = "phone_call_mute_btn"
                        )
                        PhoneCircleActionButton(
                            icon = iconForRoute(state.activeAudioRoute),
                            label = if (state.activeAudioRoute == AudioRoute.SPEAKER) {
                                AppStrings.tr(lang, "Speaker", "Ηχείο")
                            } else {
                                state.activeAudioDeviceName.take(10)
                            },
                            containerColor = if (state.activeAudioRoute == AudioRoute.SPEAKER) tokens.accentSecondary else p.surfaceVariant,
                            contentColor = if (state.activeAudioRoute == AudioRoute.SPEAKER) Color.White else p.textPrimary,
                            sizeDp = 56,
                            onClick = onToggleSpeaker,
                            tag = "phone_call_speaker_btn"
                        )
                        PhoneCircleActionButton(
                            icon = Icons.Default.BluetoothAudio,
                            label = AppStrings.tr(lang, "Audio / BT", "Ήχος / BT"),
                            containerColor = if (state.activeAudioRoute == AudioRoute.BLUETOOTH_SCO || state.activeAudioRoute == AudioRoute.BLE_AUDIO) tokens.accentPrimary else p.surfaceVariant,
                            contentColor = if (state.activeAudioRoute == AudioRoute.BLUETOOTH_SCO || state.activeAudioRoute == AudioRoute.BLE_AUDIO) Color.Black else p.textPrimary,
                            sizeDp = 56,
                            onClick = {
                                onSearchBluetoothAudio()
                                showAudioDevicePicker = true
                            },
                            tag = "phone_call_audio_picker_btn"
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

    // Idle Voice Hub: Calls (1-to-1 + Selective Multi-Person Group Calls) | Walkie-Talkie (No PTT Channels) | Room
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("voice_hub_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Minimal Header: Title + 1-Tap Audio Output & Bluetooth Search Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = AppStrings.tr(lang, "Calls", "Κλήσεις"),
                color = p.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
            Surface(
                color = p.surfaceVariant,
                shape = tokens.pillShape,
                border = BorderStroke(1.dp, p.border),
                modifier = Modifier
                    .clip(tokens.pillShape)
                    .clickable {
                        onSearchBluetoothAudio()
                        showAudioDevicePicker = true
                    }
                    .testTag("voice_quick_route_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = iconForRoute(state.activeAudioRoute),
                        contentDescription = state.activeAudioRoute.label,
                        tint = tokens.accentPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = state.activeAudioDeviceName.ifBlank { state.activeAudioRoute.label },
                        color = p.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Clean 3-Mode Segmented Switcher
        val segments = listOf(
            AppStrings.tr(lang, "Calls", "Κλήσεις"),
            AppStrings.tr(lang, "Walkie-Talkie", "Ασύρματος"),
            AppStrings.tr(lang, "Room", "Δωμάτιο")
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(tokens.pillShape)
                .background(p.surfaceVariant)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            segments.forEachIndexed { idx, label ->
                val active = selectedSegment == idx
                Surface(
                    color = if (active) tokens.accentPrimary else Color.Transparent,
                    shape = tokens.pillShape,
                    modifier = Modifier
                        .weight(1f)
                        .clip(tokens.pillShape)
                        .clickable { selectedSegment = idx }
                        .testTag("voice_segment_$idx")
                ) {
                    Text(
                        text = label,
                        color = if (active) (if (tokens.isDark) Color.Black else Color.White) else p.textSecondary,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }

        when (selectedSegment) {
            0 -> {
                // WhatsApp-style Calls Page: Quick Dial + Selective Multi-Person Group Calls + Contacts
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("phone_dialer_page"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = quickDialTarget,
                            onValueChange = { quickDialTarget = it.take(32) },
                            placeholder = { Text(AppStrings.tr(lang, "Call @username or peer...", "Κλήση @username..."), fontSize = 13.sp) },
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

                    // Selective Group Voice Call Toolbar (pick multiple specific people instead of everyone!)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isMultiSelectGroupMode) {
                                AppStrings.tr(lang, "Select people to call (${selectedGroupPeerIds.size})", "Επιλέξτε άτομα (${selectedGroupPeerIds.size})")
                            } else {
                                AppStrings.tr(lang, "Contacts & Nearby", "Επαφές & Κοντινοί")
                            },
                            color = p.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(
                            onClick = {
                                isMultiSelectGroupMode = !isMultiSelectGroupMode
                                if (!isMultiSelectGroupMode) selectedGroupPeerIds = emptySet()
                            },
                            modifier = Modifier.testTag("voice_selective_group_toggle")
                        ) {
                            Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isMultiSelectGroupMode) {
                                    AppStrings.tr(lang, "Cancel", "Άκυρο")
                                } else {
                                    AppStrings.tr(lang, "Select Group", "Επιλογή Ομάδας")
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (peers.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pulseSurfaceCard()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = p.textMuted, modifier = Modifier.size(28.dp))
                            Text(
                                text = AppStrings.tr(lang, "No nearby contacts yet", "Δεν βρέθηκαν κοντινές επαφές"),
                                color = p.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = AppStrings.tr(lang, "Enter a @username above or wait for nearby devices.", "Πληκτρολογήστε @username επάνω ή περιμένετε κοντινές συσκευές."),
                                color = p.textSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            peers.forEach { peer ->
                                val isChecked = peer.id in selectedGroupPeerIds
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .pulseSurfaceCard(accentBorder = if (isChecked) tokens.accentPrimary else null)
                                        .clickable {
                                            if (isMultiSelectGroupMode) {
                                                selectedGroupPeerIds = if (isChecked) {
                                                    selectedGroupPeerIds - peer.id
                                                } else {
                                                    selectedGroupPeerIds + peer.id
                                                }
                                            } else {
                                                onStartCall(peer)
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                        .testTag("voice_peer_row_${peer.id}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        if (isMultiSelectGroupMode) {
                                            Icon(
                                                imageVector = if (isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                                contentDescription = null,
                                                tint = if (isChecked) tokens.accentPrimary else p.textMuted,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(tokens.accentPrimary.copy(alpha = 0.16f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = peer.callsign.trim().take(1).uppercase().ifBlank { "M" },
                                                color = tokens.accentPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp
                                            )
                                        }
                                        Column {
                                            Text(peer.callsign, color = p.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                            Text(
                                                text = if (peer.hopCount <= 1) AppStrings.tr(lang, "Nearby", "Κοντά") else "${peer.hopCount} hops",
                                                color = p.textSecondary,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                    if (!isMultiSelectGroupMode) {
                                        Surface(
                                            color = tokens.statusOnline.copy(alpha = 0.16f),
                                            shape = CircleShape,
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            IconButton(onClick = { onStartCall(peer) }) {
                                                Icon(Icons.Default.Call, contentDescription = "Call ${peer.callsign}", tint = tokens.statusOnline, modifier = Modifier.size(19.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isMultiSelectGroupMode) {
                        val chosenPeers = peers.filter { it.id in selectedGroupPeerIds }
                        Button(
                            onClick = {
                                if (chosenPeers.isNotEmpty()) {
                                    onStartSelectiveGroupCall(chosenPeers)
                                    isMultiSelectGroupMode = false
                                    selectedGroupPeerIds = emptySet()
                                }
                            },
                            enabled = chosenPeers.isNotEmpty(),
                            shape = tokens.pillShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tokens.statusOnline,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("phone_call_selected_group_btn")
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = AppStrings.tr(lang, "Call Selected (${chosenPeers.size})", "Κλήση Επιλεγμένων (${chosenPeers.size})"),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = onStartGroupCall,
                            shape = tokens.pillShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("phone_group_call_btn")
                        ) {
                            Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(AppStrings.tr(lang, "Call All Nearby", "Κλήση Όλων των Κοντινών"), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            1 -> {
                // Clean Walkie-Talkie PTT (NO PTT channels — simple direct PTT + working Audio/Bluetooth output bar)
                Surface(
                    color = p.surfaceVariant,
                    shape = tokens.cardShape,
                    border = BorderStroke(1.dp, p.border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(tokens.cardShape)
                        .clickable {
                            onSearchBluetoothAudio()
                            showAudioDevicePicker = true
                        }
                        .testTag("walkie_audio_route_bar")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(iconForRoute(state.activeAudioRoute), contentDescription = null, tint = tokens.accentPrimary, modifier = Modifier.size(18.dp))
                            Column {
                                Text(
                                    text = AppStrings.tr(lang, "Audio Output", "Έξοδος Ήχου"),
                                    color = p.textSecondary,
                                    fontSize = 10.sp
                                )
                                Text(
                                    text = state.activeAudioDeviceName.ifBlank { state.activeAudioRoute.label },
                                    color = p.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                        Text(
                            text = AppStrings.tr(lang, "Switch / BT", "Αλλαγή / BT"),
                            color = tokens.accentPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseSurfaceCard(accentBorder = if (state.isPttPressed) tokens.statusDanger else p.border)
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = state.activeSpeakingBanner.ifBlank { AppStrings.tr(lang, "Walkie-Talkie Ready", "Ασύρματος Έτοιμος") },
                        color = if (state.isPttPressed) tokens.statusDanger else tokens.statusOnline,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    if (state.isPttPressed) {
                        LiveVoiceWaveform(
                            isActive = true,
                            accent = tokens.statusDanger
                        )
                    }
                    val pttColor = if (state.isPttPressed) tokens.statusDanger else tokens.accentPrimary
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .clip(CircleShape)
                            .background(pttColor.copy(alpha = if (state.isPttPressed) 0.92f else 0.16f))
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
                                text = if (state.isPttPressed) AppStrings.tr(lang, "Talking...", "Εκπομπή...") else AppStrings.tr(lang, "Hold to Talk", "Κρατήστε"),
                                color = if (state.isPttPressed) Color.White else p.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                isPttLocked = !isPttLocked
                                if (isPttLocked) onStartPtt() else onStopPtt()
                            },
                            shape = tokens.pillShape,
                            modifier = Modifier.weight(1f).testTag("voice_ptt_lock_btn")
                        ) {
                            Icon(if (isPttLocked) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isPttLocked) AppStrings.tr(lang, "Unlock", "Ξεκλείδωμα") else AppStrings.tr(lang, "Lock Mic", "Κλείδωμα"), fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = onReplayLastRx, shape = tokens.pillShape, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(AppStrings.tr(lang, "Replay", "Επανάληψη"), fontSize = 12.sp)
                        }
                    }
                }
            }

            else -> {
                // Group Room
                Button(
                    onClick = onStartGroupCall,
                    shape = tokens.pillShape,
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.accentPrimary, contentColor = if (tokens.isDark) Color.Black else Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(AppStrings.tr(lang, "Join Group Call", "Συμμετοχή σε Ομαδική Κλήση"), fontWeight = FontWeight.SemiBold)
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
private fun AudioOutputPickerDialog(
    state: MeshUiState,
    lang: String,
    onSelectRoute: (AudioRoute, Int?) -> Unit,
    onSearchBluetooth: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    val coreRoutes = listOf(AudioRoute.SPEAKER, AudioRoute.EARPIECE, AudioRoute.WIRED_HEADSET)
        .filter { it == AudioRoute.SPEAKER || it == AudioRoute.EARPIECE || it in state.availableAudioRoutes }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.card,
        shape = tokens.cardShape,
        modifier = Modifier.testTag("voice_audio_picker_dialog"),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = AppStrings.tr(lang, "Audio & Bluetooth", "Ήχος & Bluetooth"),
                    color = p.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                TextButton(
                    onClick = onSearchBluetooth,
                    modifier = Modifier.testTag("voice_bt_search_btn")
                ) {
                    Icon(Icons.Default.BluetoothSearching, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(AppStrings.tr(lang, "Scan BT", "Αναζήτηση BT"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                coreRoutes.forEach { route ->
                    val active = state.activeAudioRoute == route
                    Surface(
                        color = if (active) tokens.accentPrimary.copy(alpha = 0.18f) else p.surfaceVariant,
                        shape = tokens.buttonShape,
                        border = BorderStroke(1.dp, if (active) tokens.accentPrimary else p.border),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(tokens.buttonShape)
                            .clickable { onSelectRoute(route, null) }
                            .testTag("audio_picker_route_${route.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(iconForRoute(route), contentDescription = null, tint = if (active) tokens.accentPrimary else p.textSecondary, modifier = Modifier.size(18.dp))
                                Text(route.label, color = p.textPrimary, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp)
                            }
                            if (active) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = tokens.accentPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                Text(
                    text = AppStrings.tr(lang, "Bluetooth Audio Devices", "Συσκευές Ήχου Bluetooth"),
                    color = p.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp)
                )

                if (state.bluetoothAudioDevices.isEmpty()) {
                    Text(
                        text = AppStrings.tr(
                            lang,
                            "No Bluetooth headset detected yet. Turn on your headset and tap Scan BT.",
                            "Δεν βρέθηκε ακουστικό Bluetooth. Ενεργοποιήστε το και πατήστε Αναζήτηση BT."
                        ),
                        color = p.textMuted,
                        fontSize = 11.sp
                    )
                } else {
                    state.bluetoothAudioDevices.forEach { bt ->
                        Surface(
                            color = if (bt.isActive) tokens.accentPrimary.copy(alpha = 0.18f) else p.surfaceVariant,
                            shape = tokens.buttonShape,
                            border = BorderStroke(1.dp, if (bt.isActive) tokens.accentPrimary else p.border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(tokens.buttonShape)
                                .clickable {
                                    onSelectRoute(bt.route, bt.deviceId.takeIf { it > 0 })
                                }
                                .testTag("voice_bt_device_${bt.deviceId}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(iconForRoute(bt.route), contentDescription = null, tint = if (bt.isConnected) tokens.statusOnline else p.textMuted, modifier = Modifier.size(18.dp))
                                    Column {
                                        Text(bt.name, color = p.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Text(
                                            text = if (bt.isConnected) {
                                                AppStrings.tr(lang, "Connected • Tap to route audio", "Συνδεδεμένο • Πατήστε για ήχο")
                                            } else {
                                                AppStrings.tr(lang, "Paired • Connect in Bluetooth", "Ζευγοποιημένο")
                                            },
                                            color = if (bt.isConnected) tokens.statusOnline else p.textSecondary,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                                if (bt.isActive) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = tokens.accentPrimary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.tr(lang, "Done", "Τέλος"))
            }
        }
    )
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
