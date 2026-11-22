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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.ui.theme.LocalPulsePalette
import com.kostadinosk.pulsemesh.voice.PttChannelState

/**
 * Zello-style Walkie-Talkie PTT Channel Deck:
 * - Named channel pills (`#tactical`, `#ops`, `#medic`, plus custom `#channel` join dialog)
 * - Active floor-holder banner
 * - Courtesy Roger Beep toggle
 */
@Composable
fun WalkieTalkieDeckPanel(
    channels: List<PttChannelState>,
    activeChannelId: String,
    courtesyBeepEnabled: Boolean,
    onJoinChannel: (String) -> Unit,
    onToggleCourtesyBeep: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalPulsePalette.current
    val effectiveChannels = channels.ifEmpty {
        listOf(
            PttChannelState("#tactical", "#tactical", isJoined = true),
            PttChannelState("#ops", "#ops", isJoined = false),
            PttChannelState("#medic", "#medic", isJoined = false)
        )
    }
    val currentChannel = effectiveChannels.firstOrNull { it.channelId == activeChannelId } ?: effectiveChannels.first()
    var showJoinDialog by remember { mutableStateOf(false) }
    var customChannelName by remember { mutableStateOf("") }

    Surface(
        color = p.card,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, p.border),
        modifier = modifier
            .fillMaxWidth()
            .testTag("walkie_talkie_deck_panel")
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PTT CHANNELS",
                    color = p.cyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Roger Beep",
                        color = p.textSecondary,
                        fontSize = 11.sp
                    )
                    Switch(
                        checked = courtesyBeepEnabled,
                        onCheckedChange = onToggleCourtesyBeep,
                        modifier = Modifier.testTag("ptt_courtesy_beep_switch")
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                effectiveChannels.forEach { ch ->
                    val selected = ch.channelId == activeChannelId
                    Surface(
                        color = if (selected) p.emerald.copy(alpha = 0.2f) else p.surfaceVariant,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, if (selected) p.emerald else p.border),
                        modifier = Modifier
                            .clickable { onJoinChannel(ch.channelId) }
                            .testTag("ptt_channel_chip_${ch.channelId}")
                    ) {
                        Text(
                            text = ch.channelId,
                            color = if (selected) p.emerald else p.textSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
                Surface(
                    color = p.surfaceVariant,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, p.cyan.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .clickable { showJoinDialog = true }
                        .testTag("ptt_join_channel_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Join Channel", tint = p.cyan, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Join", color = p.cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.testTag("ptt_floor_holder_banner")
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = if (currentChannel.activeFloorNodeId.isNotBlank()) p.emerald else p.textMuted,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = if (currentChannel.activeFloorNodeId.isNotBlank()) {
                        "FLOOR: ${currentChannel.activeFloorCallsign.ifBlank { currentChannel.activeFloorNodeId }} on ${currentChannel.channelId}"
                    } else {
                        "FLOOR CLEAR on ${currentChannel.channelId}"
                    },
                    color = if (currentChannel.activeFloorNodeId.isNotBlank()) p.emerald else p.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
        }
    }

    if (showJoinDialog) {
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            title = { Text("Join PTT Channel") },
            text = {
                OutlinedTextField(
                    value = customChannelName,
                    onValueChange = { customChannelName = it },
                    label = { Text("Channel (e.g. alpha_ops)") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (customChannelName.isNotBlank()) {
                            onJoinChannel(customChannelName)
                            customChannelName = ""
                        }
                        showJoinDialog = false
                    }
                ) {
                    Text("JOIN")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJoinDialog = false }) { Text("Cancel") }
            }
        )
    }
}
