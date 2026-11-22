// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.ui.theme.LocalPulsePalette

@Composable
fun AppLockOverlay(
    hasPinConfigured: Boolean,
    biometricAvailable: Boolean,
    onUnlockWithPin: (String) -> Boolean,
    onRequestBiometric: () -> Unit,
    errorHint: String = ""
) {
    val p = LocalPulsePalette.current
    var pinInput by remember { mutableStateOf("") }
    var localError by remember(errorHint) { mutableStateOf(errorHint) }

    LaunchedEffect(biometricAvailable) {
        if (biometricAvailable) {
            onRequestBiometric()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
            .testTag("app_lock_overlay"),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = p.surface),
            border = BorderStroke(1.dp, p.border)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = p.cyan.copy(alpha = 0.14f),
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "PulseMesh Locked",
                            tint = p.cyan,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Text(
                    text = "PulseMesh Vault Locked",
                    color = p.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Authenticate to unlock keys, messages, and tactical map state.",
                    color = p.textSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )

                if (hasPinConfigured) {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = {
                            pinInput = it
                            localError = ""
                        },
                        label = { Text("Enter App Lock PIN / Passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("app_lock_pin_input")
                    )

                    Button(
                        onClick = {
                            if (!onUnlockWithPin(pinInput)) {
                                localError = "Incorrect PIN or passphrase"
                                pinInput = ""
                            }
                        },
                        enabled = pinInput.length >= 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("app_lock_unlock_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = p.cyan, contentColor = p.background)
                    ) {
                        Text("Unlock with PIN", fontWeight = FontWeight.Bold)
                    }
                }

                if (biometricAvailable) {
                    OutlinedButton(
                        onClick = onRequestBiometric,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("app_lock_biometric_button"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, p.cyan)
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null, tint = p.cyan)
                        Spacer(Modifier.width(8.dp))
                        Text("Use Biometrics / Device Lock", color = p.cyan, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (localError.isNotBlank()) {
                    Text(
                        text = localError,
                        color = p.red,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
