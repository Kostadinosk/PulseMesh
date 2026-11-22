// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.screens.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.model.UnifiedCallState
import com.kostadinosk.pulsemesh.ui.theme.LocalPulsePalette

@Composable
fun ActiveCallPanel(
    callState: UnifiedCallState,
    showHud: Boolean,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalPulsePalette.current
    Surface(
        color = p.card,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, p.emerald.copy(alpha = 0.6f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("active_call_panel")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = callState.peerCallsign.ifBlank { "Encrypted Voice Call" },
                        color = p.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "${callState.durationSeconds}s • ${callState.audioRouteLabel}" +
                            if (callState.isE2eeVerified) " • E2EE" else "",
                        color = p.emerald,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
                if (showHud) {
                    Text(
                        text = callState.codecLabel,
                        color = p.cyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onToggleMute,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(if (callState.isMuted) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (callState.isMuted) "UNMUTE" else "MUTE", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = onToggleSpeaker,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (callState.isSpeakerphoneOn) "SPEAKER" else "EARPIECE", fontSize = 11.sp)
                }
                Button(
                    onClick = onEndCall,
                    colors = ButtonDefaults.buttonColors(containerColor = p.red, contentColor = Color.White),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("END", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
