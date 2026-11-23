// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.crypto.SafetyNumberUtil
import com.kostadinosk.pulsemesh.ui.theme.LocalPulseTokens
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.ui.theme.pulseSurfaceCard
import com.kostadinosk.pulsemesh.util.AppStrings
import com.kostadinosk.pulsemesh.util.QrCodeGenerator
import com.kostadinosk.pulsemesh.util.ThemeBrightness
import com.kostadinosk.pulsemesh.util.ThemePreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyNumberSheet(
    localNodeId: String,
    localPublicKey: String,
    peerNodeId: String,
    peerCallsign: String,
    peerPublicKey: String,
    isKeyChangedWarning: Boolean,
    isVerified: Boolean,
    appLanguage: String,
    onMarkVerified: () -> Unit,
    onScanPeerQr: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = LocalPulseTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = tokens.palette.surface,
        shape = tokens.sheetShape
    ) {
        SafetyNumberSheetContent(
            localNodeId = localNodeId,
            localPublicKey = localPublicKey,
            peerNodeId = peerNodeId,
            peerCallsign = peerCallsign,
            peerPublicKey = peerPublicKey,
            isKeyChangedWarning = isKeyChangedWarning,
            isVerified = isVerified,
            appLanguage = appLanguage,
            onMarkVerified = onMarkVerified,
            onScanPeerQr = onScanPeerQr,
            onDismiss = onDismiss
        )
    }
}

@Composable
fun SafetyNumberSheetContent(
    localNodeId: String,
    localPublicKey: String,
    peerNodeId: String,
    peerCallsign: String,
    peerPublicKey: String,
    isKeyChangedWarning: Boolean,
    isVerified: Boolean,
    appLanguage: String,
    onMarkVerified: () -> Unit,
    onScanPeerQr: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    val safetyNumber = remember(localNodeId, localPublicKey, peerNodeId, peerPublicKey) {
        SafetyNumberUtil.compute60DigitSafetyNumber(
            localNodeId = localNodeId,
            localPublicKey = localPublicKey.ifBlank { localNodeId },
            peerNodeId = peerNodeId,
            peerPublicKey = peerPublicKey.ifBlank { peerNodeId }
        )
    }
    val qrPayload = remember(localNodeId, peerNodeId, safetyNumber) {
        SafetyNumberUtil.buildVerificationQrPayload(localNodeId, peerNodeId, safetyNumber)
    }
    val qrBitmap: Bitmap? = remember(qrPayload) {
        runCatching { QrCodeGenerator.generateBitmap(qrPayload, 320) }.getOrNull()
    }
    val groups = remember(safetyNumber) { safetyNumber.split(" ") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(bottom = 28.dp)
            .testTag("safety_number_sheet"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = if (isKeyChangedWarning) Icons.Default.Warning else Icons.Default.VerifiedUser,
                    contentDescription = null,
                    tint = if (isKeyChangedWarning) tokens.statusDanger else tokens.statusOnline
                )
                Column {
                    Text(
                        text = AppStrings.tr(appLanguage, "Verify Safety Number", "Επαλήθευση Αριθμού Ασφαλείας"),
                        color = p.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "$peerCallsign ($peerNodeId)",
                        color = p.textSecondary,
                        fontSize = 11.sp
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = p.textSecondary)
            }
        }

        if (isKeyChangedWarning) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(tokens.buttonShape)
                    .background(tokens.statusDanger.copy(alpha = 0.16f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = tokens.statusDanger, modifier = Modifier.size(18.dp))
                Text(
                    text = AppStrings.tr(
                        appLanguage,
                        "Peer identity key changed. Compare the 60-digit code below in person or by voice before trusting.",
                        "Το κλειδί ταυτότητας άλλαξε. Επαληθεύστε τον 60-ψήφιο κωδικό πριν εμπιστευτείτε."
                    ),
                    color = tokens.statusDanger,
                    fontSize = 11.sp
                )
            }
        }

        if (qrBitmap != null) {
            Box(
                modifier = Modifier
                    .clip(tokens.cardShape)
                    .background(Color.White)
                    .padding(10.dp)
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Safety Number QR",
                    modifier = Modifier.size(164.dp)
                )
            }
        }

        // 12 x 5-digit Signal-style safety number grid (3 rows x 4 columns)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(12.dp)
                .testTag("safety_number_digits"),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            groups.chunked(4).forEach { rowGroups ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    rowGroups.forEach { block ->
                        Text(
                            text = block,
                            color = p.textPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    onDismiss()
                    onScanPeerQr()
                },
                shape = tokens.buttonShape,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(AppStrings.tr(appLanguage, "Scan QR", "Σάρωση QR"), fontSize = 12.sp)
            }
            Button(
                onClick = {
                    onMarkVerified()
                    onDismiss()
                },
                shape = tokens.buttonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.statusOnline,
                    contentColor = Color.Black
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("safety_number_mark_verified_btn")
            ) {
                Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isVerified) AppStrings.tr(appLanguage, "Verified", "Επαληθευμένο")
                    else AppStrings.tr(appLanguage, "Mark Verified", "Επαλήθευση"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Preview(name = "Safety Number Sheet", showBackground = true)
@Composable
fun PreviewSafetyNumberSheet() {
    PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
        Surface(color = LocalPulseTokens.current.palette.surface) {
            SafetyNumberSheetContent(
                localNodeId = "0xA1B2C3D4E5F60001",
                localPublicKey = "LOCAL_DEMO_KEY",
                peerNodeId = "0xF0E1D2C3B4A50002",
                peerCallsign = "Bravo-02",
                peerPublicKey = "PEER_DEMO_KEY",
                isKeyChangedWarning = false,
                isVerified = false,
                appLanguage = "en",
                onMarkVerified = {},
                onScanPeerQr = {},
                onDismiss = {}
            )
        }
    }
}
