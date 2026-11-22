// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.screens.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.model.MeshNode
import com.kostadinosk.pulsemesh.ui.theme.LocalPulsePalette

@Composable
fun VoiceDialerPanel(
    peers: List<MeshNode>,
    onStartGroupCall: () -> Unit,
    onCallPeer: (MeshNode) -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalPulsePalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("voice_dialer_panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onStartGroupCall,
            colors = ButtonDefaults.buttonColors(containerColor = p.emerald, contentColor = Color.Black),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("voice_dialer_group_btn")
        ) {
            Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("GROUP MESH CONFERENCE CALL", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        peers.forEach { peer ->
            Surface(
                color = p.card,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, p.border),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCallPeer(peer) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(peer.callsign, color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("${peer.id} • ${peer.rssiDbm} dBm", color = p.textMuted, fontSize = 11.sp)
                    }
                    Icon(Icons.Default.Call, contentDescription = "Call", tint = p.emerald)
                }
            }
        }
    }
}
