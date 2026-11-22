// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.screens.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.model.ConferencePeerStatus
import com.kostadinosk.pulsemesh.ui.theme.LocalPulsePalette

/**
 * Multi-peer Audio Conference Grid (up to 6 peers) displaying per-peer speaking rings
 * and host Mute / Kick controls.
 */
@Composable
fun ConferenceGridPanel(
    peers: List<ConferencePeerStatus>,
    onToggleMutePeer: (String) -> Unit,
    onKickPeer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (peers.isEmpty()) return
    val p = LocalPulsePalette.current
    val visiblePeers = peers.take(6)

    Surface(
        color = p.card,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, p.border),
        modifier = modifier
            .fillMaxWidth()
            .testTag("conference_grid_panel")
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "CONFERENCE MIXER (${visiblePeers.size}/6)",
                color = p.cyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )

            visiblePeers.chunked(2).forEach { rowPeers ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowPeers.forEach { peer ->
                        val ringColor = when {
                            peer.isMuted -> p.red
                            peer.isSpeaking -> p.emerald
                            else -> p.border
                        }
                        Surface(
                            color = p.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(if (peer.isSpeaking) 2.dp else 1.dp, ringColor),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("conference_peer_${peer.nodeId}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(ringColor.copy(alpha = 0.2f))
                                            .border(1.5.dp, ringColor, CircleShape)
                                    ) {
                                        Text(
                                            text = peer.callsign.take(1).uppercase(),
                                            color = p.textPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Text(
                                        text = peer.callsign,
                                        color = p.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { onToggleMutePeer(peer.nodeId) },
                                        modifier = Modifier
                                            .size(26.dp)
                                            .testTag("conference_mute_${peer.nodeId}")
                                    ) {
                                        Icon(
                                            imageVector = if (peer.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                            contentDescription = "Mute Peer",
                                            tint = if (peer.isMuted) p.red else p.textSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { onKickPeer(peer.nodeId) },
                                        modifier = Modifier
                                            .size(26.dp)
                                            .testTag("conference_kick_${peer.nodeId}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Kick Peer",
                                            tint = p.textMuted,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (rowPeers.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
