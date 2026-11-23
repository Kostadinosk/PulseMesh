// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.qr

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.R
import com.kostadinosk.pulsemesh.ui.theme.LocalPulsePalette
import com.kostadinosk.pulsemesh.util.AppStrings
import com.kostadinosk.pulsemesh.util.QrPairingAction
import com.kostadinosk.pulsemesh.util.QrPairingRouter

/**
 * Lite-flavor lightweight QR Pairing sheet (gallery QR image decode + manual payload paste).
 * Keeps CameraX dependencies completely out of the Lite APK.
 */
@Composable
fun FlavorQrScannerSheet(
    onActionDecoded: (QrPairingAction) -> Unit,
    onDismiss: () -> Unit,
    appLanguage: String = "en",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val p = LocalPulsePalette.current
    var manualInput by remember { mutableStateOf("") }
    var statusError by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val decoded = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { bmp -> QrPairingRouter.decodeQrFromBitmap(bmp) }
                }
            }.getOrNull()
            val action = decoded?.let { QrPairingRouter.parsePayload(it) }
            if (action != null) {
                onActionDecoded(action)
            } else {
                statusError = AppStrings.resolve(context, appLanguage, R.string.qr_decode_image_error)
            }
        }
    }

    Surface(
        color = p.card,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, p.border),
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .testTag("qr_scanner_sheet")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = AppStrings.resolve(context, appLanguage, R.string.qr_scanner_title_lite),
                color = p.cyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp
            )
            Text(
                text = AppStrings.tr(
                    appLanguage,
                    "Select a QR screenshot from gallery or paste a PulseMesh pairing URI/JSON below.",
                    "Επιλέξτε στιγμιότυπο QR από τη συλλογή ή επικολλήστε ένα PulseMesh pairing URI/JSON."
                ),
                color = p.textSecondary,
                fontSize = 11.sp
            )
            OutlinedButton(
                onClick = { imagePicker.launch("image/*") },
                modifier = Modifier.fillMaxWidth().testTag("qr_pick_image_btn")
            ) {
                Text(AppStrings.resolve(context, appLanguage, R.string.qr_pick_image), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedTextField(
                value = manualInput,
                onValueChange = {
                    manualInput = it
                    statusError = ""
                },
                label = { Text(AppStrings.resolve(context, appLanguage, R.string.qr_manual_hint), fontSize = 11.sp) },
                singleLine = false,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("qr_manual_input")
            )
            if (statusError.isNotBlank()) {
                Text(statusError, color = p.red, fontSize = 11.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(AppStrings.resolve(context, appLanguage, R.string.qr_cancel_button), color = p.textSecondary)
                }
                Button(
                    enabled = manualInput.isNotBlank(),
                    onClick = {
                        val parsed = QrPairingRouter.parsePayload(manualInput)
                        if (parsed != null) {
                            onActionDecoded(parsed)
                        } else {
                            statusError = AppStrings.resolve(context, appLanguage, R.string.qr_invalid_format)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = p.emerald, contentColor = Color.Black),
                    modifier = Modifier.weight(1f).testTag("qr_apply_manual_btn")
                ) {
                    Text(AppStrings.resolve(context, appLanguage, R.string.qr_apply_button), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
