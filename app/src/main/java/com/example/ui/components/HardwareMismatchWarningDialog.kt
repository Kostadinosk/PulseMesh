// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.PhoneModelProfile
import com.example.ui.theme.*
import com.example.util.HardwareMismatchReport

@Composable
fun HardwareMismatchWarningDialog(
    report: HardwareMismatchReport,
    selectedProfile: PhoneModelProfile,
    onAutoSwitchToDetected: () -> Unit,
    onForceOverrideAnyway: () -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalPulsePalette.current
    val cardShape = tacticalCardShape(false, p.isOriginal, p.isAero)
    val btnShape = tacticalButtonShape(false, p.isOriginal, p.isAero)
    val btnTextColor = if (p.isLight || p.isAero) Color.White else Color.Black

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .navigationBarsPadding()
                .widthIn(max = 560.dp)
                .fillMaxWidth(0.95f)
                .padding(12.dp)
                .then(
                    if (p.isAero) {
                        Modifier.aeroGlassPanel(
                            palette = p,
                            cornerRadius = 22.dp,
                            tint = if (p.isDarkAero) Color(0xE00A2338) else Color(0xF2FFFFFF),
                            borderColor = p.red,
                        )
                    } else Modifier
                ),
            color = if (p.isAero) Color.Transparent else p.card,
            shape = cardShape,
            border = if (p.isAero) null else androidx.compose.foundation.BorderStroke(2.dp, p.red)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header: Danger Icon & Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (p.isAero) {
                        AeroGlossBadgeIcon(
                            imageVector = Icons.Default.Dangerous,
                            accentColor = p.red,
                            contentDescription = "Mismatch Danger",
                            badgeSize = 36.dp,
                            iconSize = 20.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Dangerous,
                            contentDescription = "Mismatch Danger",
                            tint = p.red,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "HARDWARE PROFILE MISMATCH",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = p.red
                            )
                        )
                        Text(
                            text = "CRITICAL HARDWARE ABSTRACTION COLLISION WARNING",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = p.amber,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Side-by-Side Comparison Box
                Surface(
                    color = if (p.isAero) {
                        if (p.isDarkAero) Color(0xB806192A) else Color(0xCCF2FAFF)
                    } else p.background,
                    shape = RoundedCornerShape(if (p.isAero) 14.dp else 6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, p.border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "REAL DETECTED HARDWARE:",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = p.textMuted,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = report.detectedSummary,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = p.emerald,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                )
                            }

                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "SELECTED PROFILE:",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = p.textMuted,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = report.selectedSummary,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = p.red,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "SPECIFIC FAILURE MODES IF INJECTED ON WRONG SILICON:",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = p.amber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 4 Detailed Risk Cards
                RiskDetailItem(
                    title = "1. WLAN Kernel Driver Collision",
                    description = report.wlanDriverCollisionRisk,
                    color = p.red
                )

                RiskDetailItem(
                    title = "2. Virtual Interface Soft-Lock",
                    description = report.virtualInterfaceLockRisk,
                    color = p.amber
                )

                RiskDetailItem(
                    title = "3. TX Power & Calibration Mismatch",
                    description = report.txPowerMismatchRisk,
                    color = p.amber
                )

                RiskDetailItem(
                    title = "4. OEM Background Killer Mismatch",
                    description = report.oemFreezerMismatchRisk,
                    color = p.red
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Action Buttons
                Button(
                    onClick = onAutoSwitchToDetected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (p.isAero) Modifier.aeroGlossOverlay(p, p.emerald, btnShape, 16.dp)
                            else Modifier
                        ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (p.isAero) Color.Transparent else p.emerald,
                        contentColor = btnTextColor
                    ),
                    shape = btnShape
                ) {
                    Text(
                        text = "Auto-Switch to ${report.recommendedProfile.modelName} (Recommended)",
                        color = btnTextColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedButton(
                    onClick = onForceOverrideAnyway,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = p.amber),
                    border = androidx.compose.foundation.BorderStroke(1.dp, p.amber),
                    shape = btnShape
                ) {
                    Text(
                        text = "Force Override Anyway (Advanced / Dry-Run Only)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun HardwareMismatchWarningBanner(
    report: HardwareMismatchReport,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalPulsePalette.current
    Surface(
        color = p.redDim,
        shape = RoundedCornerShape(if (p.isAero) 12.dp else 4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, p.red),
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Mismatch",
                    tint = p.red,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "HARDWARE PROFILE MISMATCH DETECTED",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = p.red,
                            fontSize = 9.sp
                        )
                    )
                    Text(
                        text = "Host is ${report.detectedSummary}. Tap to inspect driver collision risks.",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = p.textPrimary,
                            fontSize = 8.sp
                        )
                    )
                }
            }
            Text(
                text = "[REVIEW]",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = p.amber,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.sp
                )
            )
        }
    }
}

@Composable
private fun RiskDetailItem(title: String, description: String, color: Color) {
    val p = LocalPulsePalette.current
    Surface(
        color = p.surfaceVariant,
        shape = RoundedCornerShape(if (p.isAero) 10.dp else 4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Column(modifier = Modifier.padding(7.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = p.textPrimary,
                    fontSize = 8.sp,
                    lineHeight = 11.sp
                )
            )
        }
    }
}
