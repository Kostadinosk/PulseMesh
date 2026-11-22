// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.screens.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.ui.theme.LocalPulsePalette
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState
import com.kostadinosk.pulsemesh.voice.AudioRoute

/**
 * Compact Voice Hub telemetry & audio-route bar:
 * - E2EE Voice Verified shield badge (`voice_e2ee_badge`)
 * - Audio Route selector chips (`Earpiece`, `Speaker`, `Wired`, `Bluetooth`, `LE Audio`)
 * - Optional Codec/RTT/Jitter/Loss HUD telemetry strip when `state.showTacticalHud == true`
 */
@Composable
fun VoiceHubHeaderCard(
    state: MeshUiState,
    onSelectAudioRoute: (AudioRoute) -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalPulsePalette.current
    val routes = state.availableAudioRoutes.ifEmpty { listOf(AudioRoute.SPEAKER, AudioRoute.EARPIECE) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = p.emerald.copy(alpha = 0.14f),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, p.emerald.copy(alpha = 0.45f)),
                modifier = Modifier.testTag("voice_e2ee_badge")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = p.emerald,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = if (state.unifiedCallState.isE2eeVerified) "E2EE VOICE VERIFIED" else "MESH VOICE",
                        color = p.emerald,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }

            Text(
                text = state.activeAudioRoute.label.uppercase(),
                color = p.cyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
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
                    color = if (selected) p.cyan.copy(alpha = 0.2f) else p.surfaceVariant,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, if (selected) p.cyan else p.border),
                    modifier = Modifier
                        .clickable { onSelectAudioRoute(route) }
                        .testTag("voice_route_${route.name.lowercase()}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = iconForRoute(route),
                            contentDescription = route.label,
                            tint = if (selected) p.cyan else p.textSecondary,
                            modifier = Modifier.size(13.dp)
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

        if (state.showTacticalHud) {
            Surface(
                color = p.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, p.border),
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
                        color = p.cyan,
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
    AudioRoute.SPEAKER -> Icons.Default.VolumeUp
    AudioRoute.WIRED_HEADSET -> Icons.Default.HeadsetMic
    AudioRoute.BLUETOOTH_SCO -> Icons.Default.BluetoothAudio
    AudioRoute.BLE_AUDIO -> Icons.Default.BluetoothConnected
}
