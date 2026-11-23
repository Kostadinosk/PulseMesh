// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.qr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.kostadinosk.pulsemesh.R
import com.kostadinosk.pulsemesh.ui.theme.LocalPulsePalette
import com.kostadinosk.pulsemesh.util.AppStrings
import com.kostadinosk.pulsemesh.util.QrPairingAction
import com.kostadinosk.pulsemesh.util.QrPairingRouter
import java.util.concurrent.Executors

/**
 * Full-flavor CameraX + ZXing live QR Scanner sheet with gallery image and manual paste fallback.
 */
@Composable
fun FlavorQrScannerSheet(
    onActionDecoded: (QrPairingAction) -> Unit,
    onDismiss: () -> Unit,
    appLanguage: String = "en",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val p = LocalPulsePalette.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    var manualInput by remember { mutableStateOf("") }
    var statusError by remember { mutableStateOf("") }
    var handled by remember { mutableStateOf(false) }

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
            if (action != null && !handled) {
                handled = true
                onActionDecoded(action)
            } else {
                statusError = AppStrings.resolve(context, appLanguage, R.string.qr_decode_image_error)
            }
        }
    }

    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analyzerExecutor.shutdown() }
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
                text = AppStrings.resolve(context, appLanguage, R.string.qr_scanner_title_full),
                color = p.cyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp
            )

            if (hasCameraPermission) {
                val previewView = remember { PreviewView(context) }
                LaunchedEffect(previewView) {
                    runCatching {
                        val providerFuture = ProcessCameraProvider.getInstance(context)
                        providerFuture.addListener({
                            runCatching {
                                val cameraProvider = providerFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }
                                val reader = MultiFormatReader()
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                analysis.setAnalyzer(analyzerExecutor) { proxy ->
                                    try {
                                        if (!handled && proxy.planes.isNotEmpty()) {
                                            val yBuffer = proxy.planes[0].buffer
                                            val data = ByteArray(yBuffer.remaining())
                                            yBuffer.get(data)
                                            val source = PlanarYUVLuminanceSource(
                                                data,
                                                proxy.width,
                                                proxy.height,
                                                0,
                                                0,
                                                proxy.width,
                                                proxy.height,
                                                false
                                            )
                                            val text = runCatching {
                                                reader.decode(BinaryBitmap(HybridBinarizer(source))).text
                                            }.getOrNull()
                                            val parsed = text?.let { QrPairingRouter.parsePayload(it) }
                                            if (parsed != null && !handled) {
                                                handled = true
                                                ContextCompat.getMainExecutor(context).execute {
                                                    onActionDecoded(parsed)
                                                }
                                            }
                                        }
                                    } finally {
                                        proxy.close()
                                    }
                                }
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis
                                )
                            }
                        }, ContextCompat.getMainExecutor(context))
                    }
                }
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                )
            } else {
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(AppStrings.resolve(context, appLanguage, R.string.qr_enable_camera), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

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
                maxLines = 2,
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
