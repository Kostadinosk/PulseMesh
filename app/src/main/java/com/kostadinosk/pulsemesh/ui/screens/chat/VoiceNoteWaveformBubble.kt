// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.screens.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.ui.theme.LocalPulseTokens
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.util.ThemeBrightness
import com.kostadinosk.pulsemesh.util.ThemePreset
import kotlin.math.abs

/**
 * Inline Voice-Note Waveform Bubble Player for PulseMesh v2.0.
 */
@Composable
fun VoiceNoteWaveformBubble(
    clipId: String,
    durationSec: Int,
    isPlaying: Boolean,
    isOutgoing: Boolean,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    val accent = if (isOutgoing) tokens.accentPrimary else tokens.accentSecondary
    val bars = remember(clipId) {
        val seed = clipId.hashCode()
        List(24) { idx ->
            val v = abs(((seed * 31 + idx * 17) xor (idx * 131)) % 100)
            0.22f + (v / 100f) * 0.78f
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(tokens.buttonShape)
            .background(p.surfaceVariant.copy(alpha = 0.65f))
            .clickable { onTogglePlay() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(accent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Stop" else "Play voice note",
                tint = if (tokens.isDark) Color.Black else Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(26.dp)
        ) {
            val barWidth = (size.width / (bars.size * 1.6f)).coerceAtLeast(2.dp.toPx())
            val gap = (size.width - barWidth * bars.size) / (bars.size - 1).coerceAtLeast(1)
            bars.forEachIndexed { i, amp ->
                val h = size.height * amp
                val x = i * (barWidth + gap)
                val y = (size.height - h) / 2f
                drawRoundRect(
                    color = if (isPlaying && i % 2 == 0) accent else accent.copy(alpha = 0.55f),
                    topLeft = Offset(x, y),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }

        Text(
            text = "%d:%02d".format(durationSec / 60, durationSec % 60),
            color = p.textSecondary,
            fontFamily = tokens.labelFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp
        )
    }
}

/**
 * Chunked Attachment Transfer Card with live circular progress ring.
 */
@Composable
fun ChunkedAttachmentProgressCard(
    fileName: String,
    sizeLabel: String,
    progressFraction: Float?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    Surface(
        color = p.surfaceVariant.copy(alpha = 0.72f),
        shape = tokens.buttonShape,
        border = BorderStroke(tokens.borderWidth, p.border),
        modifier = modifier
            .fillMaxWidth()
            .clip(tokens.buttonShape)
            .clickable { onOpen() }
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp)) {
                if (progressFraction != null && progressFraction < 1f) {
                    CircularProgressIndicator(
                        progress = { progressFraction.coerceIn(0f, 1f) },
                        color = tokens.accentPrimary,
                        trackColor = p.border,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "${(progressFraction * 100).toInt()}%",
                        color = p.textPrimary,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(Icons.Default.AttachFile, contentDescription = null, tint = tokens.accentSecondary, modifier = Modifier.size(20.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(fileName, color = p.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1)
                Text(sizeLabel, color = p.textSecondary, fontSize = 10.sp)
            }
        }
    }
}

/**
 * Compact Delivery, Hop-Count & E2EE Verification Footer Row for Chat Bubbles.
 */
@Composable
fun MessageDeliveryMetaRow(
    timestampLabel: String,
    transportTag: String,
    hopCount: Int,
    isEncrypted: Boolean,
    isDelivered: Boolean,
    isSending: Boolean,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (isEncrypted) {
            Icon(Icons.Default.Lock, contentDescription = "E2EE", tint = tokens.statusOnline, modifier = Modifier.size(11.dp))
        }
        if (transportTag.isNotBlank()) {
            Text(
                text = if (hopCount > 0) "$transportTag • ${hopCount}h" else transportTag,
                color = p.textMuted,
                fontFamily = tokens.labelFontFamily,
                fontSize = 9.sp
            )
        }
        Text(timestampLabel, color = p.textMuted, fontSize = 9.sp)
        val statusIcon = when {
            isSending -> Icons.Default.Schedule
            isDelivered -> Icons.Default.DoneAll
            else -> Icons.Default.Check
        }
        Icon(
            imageVector = statusIcon,
            contentDescription = "Delivery status",
            tint = if (isDelivered) tokens.statusOnline else p.textMuted,
            modifier = Modifier.size(13.dp)
        )
    }
}

@Preview(name = "Voice Note & Attachment Cards", showBackground = true)
@Composable
fun PreviewVoiceAndAttachmentBubbles() {
    PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
        Surface(color = LocalPulseTokens.current.palette.card) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VoiceNoteWaveformBubble(
                    clipId = "voice_42",
                    durationSec = 14,
                    isPlaying = true,
                    isOutgoing = true,
                    onTogglePlay = {}
                )
                ChunkedAttachmentProgressCard(
                    fileName = "trail_sector_7.gpx",
                    sizeLabel = "148 KB • BLE Chunk 14/20",
                    progressFraction = 0.7f,
                    onOpen = {}
                )
                MessageDeliveryMetaRow(
                    timestampLabel = "23:14",
                    transportTag = "BLE",
                    hopCount = 2,
                    isEncrypted = true,
                    isDelivered = true,
                    isSending = false
                )
            }
        }
    }
}
