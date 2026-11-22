// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.model.MeshGroup
import com.kostadinosk.pulsemesh.ui.theme.LocalPulsePalette
import com.kostadinosk.pulsemesh.util.QrCodeGenerator

/**
 * Group Admin & Member Management modal dialog (`group_mgmt_sheet`):
 * - Add / remove members (auto-rotating AES-256 group key epoch)
 * - Promote / demote group admins
 * - Manual key epoch rotation (`group_rotate_key_btn`)
 * - Encrypted QR Invite display (`pmGroupInvite` JSON payload)
 */
@Composable
fun GroupManagementSheet(
    group: MeshGroup,
    onDismiss: () -> Unit,
    onAddMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onToggleAdmin: (String) -> Unit,
    onRotateGroupKey: () -> Unit,
    qrInvitePayload: String
) {
    val p = LocalPulsePalette.current
    var newMemberInput by remember { mutableStateOf("") }
    var showQr by remember { mutableStateOf(false) }

    val qrBitmap = remember(qrInvitePayload, showQr) {
        if (showQr && qrInvitePayload.isNotBlank()) {
            runCatching { QrCodeGenerator.generateBitmap(qrInvitePayload, pixelSize = 380) }.getOrNull()
        } else null
    }

    BackHandler(enabled = true) { onDismiss() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            color = p.card,
            contentColor = p.textPrimary,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            border = BorderStroke(1.dp, p.border),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clickable(enabled = false, onClick = {})
                .testTag("group_mgmt_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = group.groupName.ifBlank { group.groupId },
                        color = p.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "${group.memberIds.size} members • AES-256 Epoch #${group.keyEpoch}",
                        color = p.cyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newMemberInput,
                        onValueChange = { newMemberInput = it },
                        placeholder = { Text("Node ID (0x...) or @user") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("group_add_member_input")
                    )
                    Button(
                        enabled = newMemberInput.isNotBlank(),
                        onClick = {
                            onAddMember(newMemberInput.trim())
                            newMemberInput = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = p.emerald, contentColor = Color.Black),
                        modifier = Modifier.testTag("group_add_member_btn")
                    ) {
                        Text("ADD", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    group.memberIds.forEach { memberId ->
                        key(memberId) {
                            val isAdmin = group.adminIds.any { it.equals(memberId, ignoreCase = true) }
                            Surface(
                                color = p.surfaceVariant,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, if (isAdmin) p.amber.copy(alpha = 0.5f) else p.border),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = memberId,
                                            color = p.textPrimary,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (isAdmin) {
                                            Text("ADMIN", color = p.amber, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Row {
                                        IconButton(
                                            onClick = { onToggleAdmin(memberId) },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AdminPanelSettings,
                                                contentDescription = "Toggle Admin",
                                                tint = if (isAdmin) p.amber else p.textMuted,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { onRemoveMember(memberId) },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PersonRemove,
                                                contentDescription = "Remove Member",
                                                tint = p.red,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRotateGroupKey,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("group_rotate_key_btn")
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("ROTATE KEY", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { showQr = !showQr },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("group_qr_invite_btn")
                    ) {
                        Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (showQr) "HIDE QR" else "INVITE QR", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (showQr) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Group Invite QR",
                                modifier = Modifier.size(160.dp)
                            )
                        }
                        if (qrInvitePayload.isNotBlank()) {
                            Text(
                                text = qrInvitePayload.take(64) + if (qrInvitePayload.length > 64) "..." else "",
                                color = p.textMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onDismiss) {
                        Text("CLOSE")
                    }
                }
            }
        }
    }
}
