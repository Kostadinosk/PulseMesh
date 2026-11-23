// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.model.ChatConversation
import com.kostadinosk.pulsemesh.model.MeshPacket
import com.kostadinosk.pulsemesh.ui.components.AeroGlossBadgeIcon
import com.kostadinosk.pulsemesh.ui.theme.*
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshChatScreen(
    state: MeshUiState,
    onSendMessage: (String) -> Unit,
    onBroadcastGpsPin: () -> Unit,
    onSelectThread: (String) -> Unit = {},
    onBackToList: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onStartCall: (com.kostadinosk.pulsemesh.model.MeshNode) -> Unit = {},
    onRotateKeys: () -> Unit = {},
    onCreateGroup: (String, List<String>) -> Unit = { _, _ -> },
    onVerifyFingerprint: (String) -> Unit = {},
    onPlayVoiceClip: (String) -> Unit = {},
    onSendPhoto: (Bitmap, String) -> Unit = { _, _ -> },
    onSendTacticalSnap: () -> Unit = {},
    onRetrySendPacket: (MeshPacket) -> Unit = {},
    onConnectByUsername: (String, (Boolean, String) -> Unit) -> Unit = { _, _ -> },
    onSendQuickStatus: (com.kostadinosk.pulsemesh.model.QuickStatusCode) -> Unit = {},
    onToggleQuickStatusVoice: () -> Unit = {},
    onSendReaction: (String, String) -> Unit = { _, _ -> },
    onTogglePinMessage: (String, String) -> Unit = { _, _ -> },
    onSetThreadBurnTtl: (String, Int) -> Unit = { _, _ -> },
    onOpenWebPortal: () -> Unit = {},
    onStartReply: (MeshPacket) -> Unit = {},
    onClearReply: () -> Unit = {},
    onStartEdit: (MeshPacket) -> Unit = {},
    onClearEdit: () -> Unit = {},
    onCommitEdit: (String, String, String) -> Unit = { _, _, _ -> },
    onUnsendMessage: (String) -> Unit = {},
    onTypingChanged: (Boolean) -> Unit = {},
    onSearchActiveThread: (String) -> Unit = {},
    onSendFileBytes: (String, String, ByteArray) -> Unit = { _, _, _ -> },
    onPauseFileTransfer: (String) -> Unit = {},
    onResumeFileTransfer: (String) -> Unit = {},
    onAddGroupMember: (String, String) -> Unit = { _, _ -> },
    onRemoveGroupMember: (String, String) -> Unit = { _, _ -> },
    onToggleGroupAdmin: (String, String) -> Unit = { _, _ -> },
    onRotateGroupKey: (String) -> Unit = {},
    onBuildGroupInviteQr: (String) -> String = { "" },
    onSendVoiceNoteClip: (ByteArray, Long) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    if (state.selectedThreadId == null) {
        ConversationListView(
            state = state,
            onSelectThread = onSelectThread,
            onBroadcastGpsPin = onBroadcastGpsPin,
            onSearchQueryChange = onSearchQueryChange,
            onCreateGroup = onCreateGroup,
            onConnectByUsername = onConnectByUsername,
            onSendQuickStatus = onSendQuickStatus,
            onToggleQuickStatusVoice = onToggleQuickStatusVoice,
            onOpenWebPortal = onOpenWebPortal,
            modifier = modifier
        )
    } else {
        ConversationThreadView(
            state = state,
            threadId = state.selectedThreadId,
            onBackToList = onBackToList,
            onSendMessage = onSendMessage,
            onBroadcastGpsPin = onBroadcastGpsPin,
            onStartCall = onStartCall,
            onRotateKeys = onRotateKeys,
            onVerifyFingerprint = onVerifyFingerprint,
            onPlayVoiceClip = onPlayVoiceClip,
            onSendPhoto = onSendPhoto,
            onSendTacticalSnap = onSendTacticalSnap,
            onRetrySendPacket = onRetrySendPacket,
            onSendReaction = onSendReaction,
            onTogglePinMessage = onTogglePinMessage,
            onSetThreadBurnTtl = onSetThreadBurnTtl,
            onStartReply = onStartReply,
            onClearReply = onClearReply,
            onStartEdit = onStartEdit,
            onClearEdit = onClearEdit,
            onCommitEdit = onCommitEdit,
            onUnsendMessage = onUnsendMessage,
            onTypingChanged = onTypingChanged,
            onSearchActiveThread = onSearchActiveThread,
            onSendFileBytes = onSendFileBytes,
            onPauseFileTransfer = onPauseFileTransfer,
            onResumeFileTransfer = onResumeFileTransfer,
            onAddGroupMember = onAddGroupMember,
            onRemoveGroupMember = onRemoveGroupMember,
            onToggleGroupAdmin = onToggleGroupAdmin,
            onRotateGroupKey = onRotateGroupKey,
            onBuildGroupInviteQr = onBuildGroupInviteQr,
            onSendVoiceNoteClip = onSendVoiceNoteClip,
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ConversationListView(
    state: MeshUiState,
    onSelectThread: (String) -> Unit,
    onBroadcastGpsPin: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onCreateGroup: (String, List<String>) -> Unit,
    onConnectByUsername: (String, (Boolean, String) -> Unit) -> Unit = { _, _ -> },
    onSendQuickStatus: (com.kostadinosk.pulsemesh.model.QuickStatusCode) -> Unit = {},
    onToggleQuickStatusVoice: () -> Unit = {},
    onOpenWebPortal: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val p = LocalPulsePalette.current
    val cardShape = tacticalCardShape(state.isSimpleMode, p.isOriginal, p.isAero)
    val btnShape = tacticalButtonShape(state.isSimpleMode, p.isOriginal, p.isAero)

    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var manualMemberNodeId by remember { mutableStateOf("") }
    val selectedMemberIds = remember { mutableStateMapOf<String, Boolean>() }

    var showConnectUsernameDialog by remember { mutableStateOf(false) }
    var connectUsernameInput by remember { mutableStateOf("") }
    var connectFeedbackText by remember { mutableStateOf("") }
    var connectFeedbackSuccess by remember { mutableStateOf(false) }

    val discoveredPeers = remember(state.nodes) {
        state.nodes.filter { !it.isSelf }
    }

    val filteredConversations = remember(state.conversations, state.searchQuery) {
        if (state.searchQuery.isBlank()) {
            state.conversations
        } else {
            val q = state.searchQuery.trim().lowercase()
            state.conversations.filter {
                it.title.lowercase().contains(q) ||
                    it.lastMessageText.lowercase().contains(q) ||
                    (it.peerNodeId?.lowercase()?.contains(q) == true) ||
                    (it.groupId?.lowercase()?.contains(q) == true)
            }
        }
    }

    // 3 Conversation Categories:
    // 1) Public Mesh Broadcast (ALL) / Pinned Channels
    val pinnedChannels = filteredConversations.filter { it.isPinned && !it.isGroup }
    // 2) Custom Multi-Mesh Group Chats
    val groupConversations = filteredConversations.filter { it.isGroup }
    // 3) 1-on-1 Direct Chats
    val directMessages = filteredConversations.filter { !it.isPinned && !it.isGroup }

    Column(
        modifier = modifier
            .fillMaxSize()
            .aeroCanvasBackground(p)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .navigationBarsPadding()
            .testTag("conversation_list_view")
    ) {
        // Top Header with "@USER", "+ NEW GROUP" and "PING MESH"
        Surface(
            color = if (p.isAero) Color.Transparent else p.card,
            modifier = Modifier
                .fillMaxWidth()
                .aeroGlassPanel(p, cornerRadius = 0.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, p.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (p.isAero) {
                            AeroGlossBadgeIcon(
                                imageVector = Icons.AutoMirrored.Filled.Chat,
                                accentColor = p.emerald,
                                contentDescription = "Tactical Messaging",
                                badgeSize = 26.dp,
                                iconSize = 14.dp,
                            )
                        }
                        Text(
                            text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "TACTICAL MESSAGING", "ΤΑΚΤΙΚΑ ΜΗΝΥΜΑΤΑ"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                                fontFamily = FontFamily.Monospace,
                                color = p.emerald
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(
                            state.appLanguage,
                            "BROADCAST • GROUPS (${state.meshGroups.size}) • E2EE DMs • DTN: ${state.pendingDtnCount}/50",
                            "ΕΚΠΟΜΠΗ • ΟΜΑΔΕΣ (${state.meshGroups.size}) • E2EE • DTN: ${state.pendingDtnCount}/50"
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = p.textSecondary
                        )
                    )
                }

                val actionFg = if (p.isLight || p.isAero) Color.White else Color.Black
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Connect by @username for distant friends
                    Button(
                        onClick = {
                            connectUsernameInput = ""
                            connectFeedbackText = ""
                            showConnectUsernameDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (p.isAero) Color.Transparent else p.amber,
                            contentColor = actionFg
                        ),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        shape = btnShape,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp)
                            .aeroGlossOverlay(p, p.amber, btnShape, cornerRadius = 18.dp)
                            .testTag("chat_connect_username_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AlternateEmail,
                            contentDescription = "Connect by Username",
                            tint = actionFg,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "@USER", "@ΧΡΗΣΤΗΣ"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = actionFg,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }

                    // Prominent "+ NEW GROUP" Button
                    Button(
                        onClick = {
                            newGroupName = ""
                            manualMemberNodeId = ""
                            selectedMemberIds.clear()
                            discoveredPeers.forEach { peer ->
                                selectedMemberIds[peer.id] = true
                            }
                            showCreateGroupDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (p.isAero) Color.Transparent else p.cyan,
                            contentColor = actionFg
                        ),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        shape = btnShape,
                        modifier = Modifier
                            .weight(1.15f)
                            .heightIn(min = 40.dp)
                            .aeroGlossOverlay(p, p.cyan, btnShape, cornerRadius = 18.dp)
                            .testTag("create_new_group_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.GroupAdd,
                            contentDescription = "New Group",
                            tint = actionFg,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "+ GROUP", "+ ΟΜΑΔΑ"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = actionFg,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }

                    // Ping Mesh Now button
                    Button(
                        onClick = onBroadcastGpsPin,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (p.isAero) Color.Transparent else p.emerald,
                            contentColor = actionFg
                        ),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        shape = btnShape,
                        modifier = Modifier
                            .weight(1.15f)
                            .heightIn(min = 40.dp)
                            .aeroGlossOverlay(p, p.emerald, btnShape, cornerRadius = 18.dp)
                            .testTag("ping_mesh_now_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sensors,
                            contentDescription = "Ping Mesh",
                            tint = actionFg,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "PING MESH", "ΣΑΡΩΣΗ MESH"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = actionFg,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }

                // Search Bar
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = {
                        Text(
                            com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Search channels, groups & peers...", "Αναζήτηση καναλιών, ομάδων & κόμβων..."),
                            color = p.textMuted,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = p.emerald,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (state.searchQuery.isNotBlank()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear Search",
                                    tint = p.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = p.emerald,
                        unfocusedBorderColor = p.border,
                        focusedContainerColor = p.background,
                        unfocusedContainerColor = p.background,
                        focusedTextColor = p.textPrimary,
                        unfocusedTextColor = p.textPrimary
                    ),
                    shape = btnShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("chat_search_bar")
                )
            }
        }

        // Conversation List
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .testTag("conversation_list_lazy"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 76.dp)
        ) {
            // 0-Peer Quick-Connect Onboarding Card (PulseMesh v1.4.5)
            if (discoveredPeers.isEmpty() && state.searchQuery.isBlank()) {
                item(key = "empty_peer_quick_connect") {
                    Surface(
                        color = p.card,
                        shape = cardShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, p.cyan.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chat_zero_peer_connect_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.WifiTethering, contentDescription = null, tint = p.cyan, modifier = Modifier.size(18.dp))
                                Text(
                                    text = com.kostadinosk.pulsemesh.util.AppStrings.tr(
                                        state.appLanguage,
                                        "No Nearby Mesh Peers Yet",
                                        "Δεν Βρέθηκαν Κοντινοί Κόμβοι Ακόμα"
                                    ),
                                    color = p.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            Text(
                                text = com.kostadinosk.pulsemesh.util.AppStrings.tr(
                                    state.appLanguage,
                                    "Nearby Android phones connect over BLE & Wi-Fi Aware automatically with no account needed. Optionally connect an iPhone/laptop via Web Portal or add a distant @username (recommended for Hybrid Relay):",
                                    "Κοντινά Android συνδέονται αυτόματα μέσω BLE & Wi-Fi Aware χωρίς λογαριασμό. Προαιρετικά συνδέστε iPhone/laptop μέσω Πύλης Web ή προσθέστε @username (προτείνεται για Hybrid Relay):"
                                ),
                                color = p.textSecondary,
                                fontSize = 12.sp
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = onBroadcastGpsPin,
                                    colors = ButtonDefaults.buttonColors(containerColor = p.emerald, contentColor = Color.Black),
                                    shape = btnShape,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Scan Now", "Σάρωση"),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                                OutlinedButton(
                                    onClick = onOpenWebPortal,
                                    shape = btnShape,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, p.cyan),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "iPhone/Web", "iPhone/Web"),
                                        color = p.cyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                                OutlinedButton(
                                    onClick = { showConnectUsernameDialog = true },
                                    shape = btnShape,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, p.amber),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "@username",
                                        color = p.amber,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 1. Public Mesh Broadcast (ALL)
            if (pinnedChannels.isNotEmpty()) {
                item {
                    Text(
                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "PUBLIC MESH BROADCAST (ALL)", "ΔΗΜΟΣΙΟ ΚΑΝΑΛΙ MESH (ΟΛΟΙ)"),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace,
                            color = p.textSecondary
                        ),
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
                    )
                }

                items(pinnedChannels, key = { it.threadId }) { convo ->
                    ConversationItemCard(
                        conversation = convo,
                        isSimpleMode = state.isSimpleMode,
                        onClick = { onSelectThread(convo.threadId) }
                    )
                }
            }

            // 2. Custom Multi-Mesh Group Chats
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "MULTI-MESH GROUP CHATS (AES-256-GCM)", "ΟΜΑΔΙΚΕΣ ΣΥΝΟΜΙΛΙΕΣ MESH (AES-256-GCM)"),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace,
                            color = p.cyan
                        )
                    )
                    Text(
                        text = "${groupConversations.size} ${com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "GROUPS", "ΟΜΑΔΕΣ")}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = p.cyan
                        )
                    )
                }
            }

            if (groupConversations.isEmpty()) {
                item {
                    Surface(
                        color = if (p.isAero) Color.Transparent else p.card,
                        shape = cardShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, p.border),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aeroGlassPanel(p, cornerRadius = 22.dp)
                            .clickable {
                                newGroupName = ""
                                manualMemberNodeId = ""
                                selectedMemberIds.clear()
                                discoveredPeers.forEach { peer ->
                                    selectedMemberIds[peer.id] = true
                                }
                                showCreateGroupDialog = true
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "No Multi-Mesh Groups Yet", "Καμία Ομάδα Mesh Ακόμα"),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = p.textPrimary
                                    )
                                )
                                Text(
                                    text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Tap '+ GROUP' above to select multiple peers and create an encrypted squad channel.", "Πατήστε '+ ΟΜΑΔΑ' επάνω για να επιλέξετε κόμβους και να φτιάξετε κρυπτογραφημένο κανάλι ομάδας."),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = p.textSecondary
                                    )
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.GroupAdd,
                                contentDescription = "Create Group",
                                tint = p.cyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            } else {
                items(groupConversations, key = { it.threadId }) { convo ->
                    ConversationItemCard(
                        conversation = convo,
                        isSimpleMode = state.isSimpleMode,
                        onClick = { onSelectThread(convo.threadId) }
                    )
                }
            }

            // 3. Direct Messages Section
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "1-ON-1 DIRECT CHATS (ACTIVE PEERS)", "ΑΜΕΣΕΣ ΣΥΝΟΜΙΛΙΕΣ 1-ΠΡΟΣ-1 (ΕΝΕΡΓΟΙ ΚΟΜΒΟΙ)"),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace,
                            color = p.textSecondary
                        )
                    )
                    Text(
                        text = "${state.activePeerCount} ${com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "ONLINE", "ΕΝΕΡΓΟΙ")}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (state.activePeerCount > 0) p.emerald else p.amber
                        )
                    )
                }
            }

            if (directMessages.isEmpty()) {
                item {
                    Surface(
                        color = if (p.isAero) Color.Transparent else p.card,
                        shape = cardShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, p.border),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aeroGlassPanel(p, cornerRadius = 22.dp)
                            .padding(vertical = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.WifiTetheringOff,
                                contentDescription = "No Peers",
                                tint = p.amber,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "NO DIRECT PEERS HEARD YET", "ΔΕΝ ΕΝΤΟΠΙΣΤΗΚΑΝ ΚΟΝΤΙΝΟΙ ΚΟΜΒΟΙ"),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = p.textPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Tap 'PING MESH' above or launch second device on same channel. BLE/Wi-Fi auto-discovery will create direct 1-on-1 encrypted DM threads instantly.", "Πατήστε 'ΣΑΡΩΣΗ MESH' επάνω ή ανοίξτε δεύτερη συσκευή στο ίδιο κανάλι. Η αυτόματη εύρεση BLE/Wi-Fi θα ανοίξει αμέσως κρυπτογραφημένες συνομιλίες 1-προς-1."),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = p.textSecondary
                                ),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(directMessages, key = { it.threadId }) { convo ->
                    ConversationItemCard(
                        conversation = convo,
                        isSimpleMode = state.isSimpleMode,
                        onClick = { onSelectThread(convo.threadId) }
                    )
                }
            }

            // Tactical Quick-Status One-Tap Strip (Text + Voice TTS)
            item(key = "quick_status_strip") {
                val isGreek = state.appLanguage == "el"
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (isGreek) "ΤΑΧΕΙΑ ΚΑΤΑΣΤΑΣΗ (ΚΕΙΜΕΝΟ + ΦΩΝΗ)" else "QUICK-STATUS BEACON (TEXT + VOICE)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = p.textSecondary
                        ),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = onToggleQuickStatusVoice,
                            color = if (state.quickStatusVoiceEnabled) p.emeraldDim else p.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (state.quickStatusVoiceEnabled) p.emerald else p.border
                            ),
                            modifier = Modifier.testTag("quick_status_voice_toggle")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = if (state.quickStatusVoiceEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                    contentDescription = "Voice Status Announcement",
                                    tint = if (state.quickStatusVoiceEnabled) p.emerald else p.textMuted,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = if (state.quickStatusVoiceEnabled) (if (isGreek) "ΦΩΝΗ ON" else "VOICE ON") else (if (isGreek) "ΦΩΝΗ OFF" else "VOICE"),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (state.quickStatusVoiceEnabled) p.emerald else p.textSecondary
                                )
                            }
                        }

                        for (statusCode in com.kostadinosk.pulsemesh.model.QuickStatusCode.entries) {
                            val isActive = state.lastQuickStatusCode == statusCode.name
                            val accent = when (statusCode) {
                                com.kostadinosk.pulsemesh.model.QuickStatusCode.ALL_OK -> p.emerald
                                com.kostadinosk.pulsemesh.model.QuickStatusCode.NEED_HELP -> p.red
                                com.kostadinosk.pulsemesh.model.QuickStatusCode.LOW_BATTERY,
                                com.kostadinosk.pulsemesh.model.QuickStatusCode.NEED_WATER -> p.amber
                                else -> p.cyan
                            }
                            Surface(
                                onClick = { onSendQuickStatus(statusCode) },
                                color = if (isActive) accent.copy(alpha = 0.22f) else p.background,
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
                                modifier = Modifier.testTag("quick_status_${statusCode.name}")
                            ) {
                                Text(
                                    text = if (isGreek) statusCode.labelEl else statusCode.labelEn,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = accent,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Create Multi-Mesh Group Dialog
    if (showCreateGroupDialog) {
        AlertDialog(
            onDismissRequest = { showCreateGroupDialog = false },
            containerColor = p.card,
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = null,
                        tint = p.cyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "CREATE MULTI-MESH GROUP", "ΔΗΜΙΟΥΡΓΙΑ ΟΜΑΔΑΣ MESH"),
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = p.cyan,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        label = { Text(com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Group Name (e.g., Trail Team)", "Όνομα Ομάδας (π.χ. Ομάδα Πεζοπορίας)"), color = p.textSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = p.cyan,
                            unfocusedBorderColor = p.border,
                            focusedTextColor = p.textPrimary,
                            unfocusedTextColor = p.textPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("group_name_input")
                    )

                    Text(
                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "SELECT PEER NODES TO INCLUDE:", "ΕΠΙΛΟΓΗ ΚΟΜΒΩΝ ΓΙΑ ΣΥΜΜΕΤΟΧΗ:"),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = p.emerald,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    )

                    if (discoveredPeers.isEmpty()) {
                        Text(
                            text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "No nearby peers discovered yet. You can add peer Node IDs manually below or create the group now.", "Δεν βρέθηκαν κοντινοί κόμβοι ακόμα. Μπορείτε να προσθέσετε Node ID παρακάτω ή να δημιουργήσετε την ομάδα τώρα."),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = p.textSecondary,
                                fontSize = 11.sp
                            )
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            discoveredPeers.forEach { peer ->
                                val isChecked = selectedMemberIds[peer.id] == true
                                Surface(
                                    color = if (isChecked) p.emeraldDim else p.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isChecked) p.emerald else p.border
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedMemberIds[peer.id] = !isChecked
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${peer.callsign} (${peer.id})",
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    color = p.textPrimary,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            )
                                            Text(
                                                text = "${peer.lastBlePhy} • ${peer.rssiDbm} dBm",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = p.textSecondary,
                                                    fontSize = 9.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            )
                                        }
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { checked ->
                                                selectedMemberIds[peer.id] = checked
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = p.emerald,
                                                checkmarkColor = if (p.isLight || p.isAero) Color.White else Color.Black
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = manualMemberNodeId,
                        onValueChange = { manualMemberNodeId = it },
                        label = { Text(com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Add Extra Node IDs (comma-separated, optional)", "Προσθήκη επιπλέον Node ID (με κόμμα, προαιρετικά)"), color = p.textSecondary, fontSize = 11.sp) },
                        placeholder = { Text(com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "e.g. 0x9B1C, 0x8C3E", "π.χ. 0x9B1C, 0x8C3E"), fontSize = 11.sp, color = p.textMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = p.emerald,
                            unfocusedBorderColor = p.border,
                            focusedTextColor = p.textPrimary,
                            unfocusedTextColor = p.textPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("group_manual_members_input")
                    )
                }
            },
            confirmButton = {
                val fg = if (p.isLight || p.isAero) Color.White else Color.Black
                Button(
                    onClick = {
                        val chosenPeers = selectedMemberIds.filterValues { it }.keys.toMutableList()
                        if (manualMemberNodeId.isNotBlank()) {
                            manualMemberNodeId.split(",")
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .forEach { chosenPeers.add(it) }
                        }
                        val finalName = newGroupName.trim().ifBlank { com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Mesh Group", "Ομάδα Mesh") }
                        onCreateGroup(finalName, chosenPeers.distinct())
                        showCreateGroupDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = p.cyan,
                        contentColor = fg
                    ),
                    shape = btnShape,
                    modifier = Modifier.testTag("confirm_create_group_button")
                ) {
                    Text(
                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "CREATE & BROADCAST GROUP", "ΔΗΜΙΟΥΡΓΙΑ & ΕΚΠΟΜΠΗ ΟΜΑΔΑΣ"),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGroupDialog = false }) {
                    Text(com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "CANCEL", "ΑΚΥΡΩΣΗ"), color = p.textSecondary)
                }
            }
        )
    }

    // Connect by @Username Dialog for Distant Friends
    if (showConnectUsernameDialog) {
        AlertDialog(
            onDismissRequest = { showConnectUsernameDialog = false },
            containerColor = p.card,
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (p.isAero) {
                        AeroGlossBadgeIcon(
                            imageVector = Icons.Default.AlternateEmail,
                            accentColor = p.cyan,
                            contentDescription = "Connect by Username",
                            badgeSize = 30.dp,
                            iconSize = 16.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.AlternateEmail,
                            contentDescription = null,
                            tint = p.cyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "CONNECT BY @USERNAME", "ΣΥΝΔΕΣΗ ΜΕ @USERNAME"),
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = p.cyan,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (state.isInternetEnabled) {
                            com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Local off-grid chats work without an account. Enter a friend's optional @username (recommended for Hybrid Relay) to open an E2EE chat or enable cross-device recovery.", "Οι τοπικές συνομιλίες εκτός δικτύου λειτουργούν χωρίς λογαριασμό. Εισάγετε @username φίλου (προτείνεται για Hybrid Relay) για άμεση E2EE συνομιλία.")
                        } else {
                            com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Local off-grid chats work with no @username. Optional @username (recommended): enable Hybrid (+Internet) in Settings or the top bar to reach distant friends.", "Οι τοπικές συνομιλίες λειτουργούν χωρίς @username. Προαιρετικά (προτείνεται): ενεργοποιήστε το Hybrid (+Internet) στις Ρυθμίσεις για μακρινούς φίλους.")
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = p.textSecondary,
                            fontSize = 11.sp
                        )
                    )
                    OutlinedTextField(
                        value = connectUsernameInput,
                        onValueChange = { connectUsernameInput = it.lowercase().replace(" ", "") },
                        label = { Text(com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Friend @username (e.g. alex_k)", "Φίλος @username (π.χ. nikos_m)"), color = p.textSecondary) },
                        prefix = { Text("@", color = p.cyan, fontWeight = FontWeight.Bold) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = p.cyan,
                            unfocusedBorderColor = p.border,
                            focusedTextColor = p.textPrimary,
                            unfocusedTextColor = p.textPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("username_connect_input")
                    )
                    if (connectFeedbackText.isNotBlank()) {
                        Text(
                            text = connectFeedbackText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (connectFeedbackSuccess) p.emerald else p.red,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onConnectByUsername(connectUsernameInput) { ok, msg ->
                            connectFeedbackSuccess = ok
                            connectFeedbackText = msg
                            if (ok) {
                                showConnectUsernameDialog = false
                            }
                        }
                    },
                    enabled = !state.isConnectingByUsername,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = p.cyan,
                        contentColor = if (p.isLight || p.isAero) Color.White else Color.Black
                    ),
                    shape = btnShape,
                    modifier = Modifier.testTag("username_connect_confirm_button")
                ) {
                    Text(
                        text = if (state.isConnectingByUsername) {
                            com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "SEARCHING...", "ΑΝΑΖΗΤΗΣΗ...")
                        } else {
                            com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "CONNECT & CHAT", "ΣΥΝΔΕΣΗ & ΣΥΝΟΜΙΛΙΑ")
                        },
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showConnectUsernameDialog = false }) {
                    Text(com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "CANCEL", "ΑΚΥΡΩΣΗ"), color = p.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun ConversationItemCard(
    conversation: ChatConversation,
    isSimpleMode: Boolean = false,
    onClick: () -> Unit
) {
    val p = LocalPulsePalette.current
    val cardShape = tacticalCardShape(isSimpleMode, p.isOriginal, p.isAero)
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val timeString = remember(conversation.lastTimestamp) {
        if (conversation.lastTimestamp > 0) timeFormat.format(Date(conversation.lastTimestamp)) else ""
    }

    Surface(
        color = if (p.isAero) Color.Transparent else p.card,
        shape = cardShape,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when {
                conversation.isGroup -> p.cyan.copy(alpha = 0.6f)
                conversation.isPinned -> p.emerald.copy(alpha = 0.45f)
                else -> p.border
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .aeroGlassPanel(p, cornerRadius = 22.dp)
            .clickable { onClick() }
            .testTag("conversation_card_${conversation.threadId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar Circle (glossy 3D orb badge in Frutiger Aero)
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center
            ) {
                if (p.isAero) {
                    val badgeIcon = when {
                        conversation.isGroup -> Icons.Default.Groups
                        conversation.isChannel -> if (conversation.isEncrypted) Icons.Default.Lock else Icons.Default.Public
                        else -> Icons.Default.Person
                    }
                    val badgeAccent = when {
                        conversation.isGroup -> p.cyan
                        conversation.isChannel -> if (conversation.isEncrypted) p.emerald else p.cyan
                        else -> p.emerald
                    }
                    AeroGlossBadgeIcon(
                        imageVector = badgeIcon,
                        accentColor = badgeAccent,
                        contentDescription = conversation.title,
                        badgeSize = 44.dp,
                        iconSize = 22.dp,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    conversation.isGroup -> p.cyanDim
                                    conversation.isChannel -> p.surfaceVariant
                                    else -> p.emeraldDim
                                }
                            )
                            .border(
                                1.dp,
                                when {
                                    conversation.isGroup -> p.cyan
                                    conversation.isEncrypted -> p.emerald
                                    else -> p.cyan
                                },
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            conversation.isGroup -> {
                                Icon(
                                    imageVector = Icons.Default.Groups,
                                    contentDescription = "Group Icon",
                                    tint = p.cyan,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            conversation.isChannel -> {
                                Icon(
                                    imageVector = if (conversation.isEncrypted) Icons.Default.Lock else Icons.Default.Public,
                                    contentDescription = "Channel Icon",
                                    tint = if (conversation.isEncrypted) p.emerald else p.cyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            else -> {
                                Text(
                                    text = conversation.peerNodeId?.takeLast(2) ?: "DM",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = p.emerald
                                    )
                                )
                            }
                        }
                    }
                }

                // Online status badge on avatar
                if (conversation.isOnline && !conversation.isChannel) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(p.emerald)
                            .border(1.dp, p.background, CircleShape)
                            .align(Alignment.BottomEnd)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Conversation Details
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = conversation.title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = p.textPrimary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (conversation.isKeyChangedWarning) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Key Changed Warning",
                                tint = p.red,
                                modifier = Modifier.size(14.dp)
                            )
                        } else if (conversation.isEncrypted) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "E2EE",
                                tint = if (conversation.isGroup) p.cyan else p.emerald,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (conversation.unreadCount > 0) {
                            Surface(
                                color = p.emerald,
                                shape = CircleShape
                            ) {
                                Text(
                                    text = if (conversation.unreadCount > 99) "99+" else "${conversation.unreadCount}",
                                    color = if (p.isLight || p.isAero) Color.White else Color.Black,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = timeString,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = p.textSecondary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.lastMessageText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = p.textSecondary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (conversation.isGroup) {
                        Surface(
                            color = p.surfaceVariant,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(start = 6.dp)
                        ) {
                            Text(
                                text = "${conversation.memberIds.size} NODES",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = p.cyan
                                ),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    } else if (conversation.isKeyChangedWarning) {
                        Surface(
                            color = p.redDim,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(start = 6.dp)
                        ) {
                            Text(
                                text = "KEY CHANGED",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = p.red
                                ),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    } else if (!isSimpleMode && conversation.safetyFingerprint.isNotBlank()) {
                        Surface(
                            color = p.surfaceVariant,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(start = 6.dp)
                        ) {
                            Text(
                                text = conversation.safetyFingerprint,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = p.emerald
                                ),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    } else if (conversation.isEncrypted && !conversation.isPinned) {
                        Surface(
                            color = p.surfaceVariant,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(start = 6.dp)
                        ) {
                            Text(
                                text = "E2EE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = p.emerald
                                ),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    } else if (conversation.isPinned) {
                        Surface(
                            color = p.surfaceVariant,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(start = 6.dp)
                        ) {
                            Text(
                                text = if (conversation.isEncrypted) "AES-256" else "PUBLIC",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (conversation.isEncrypted) p.emerald else p.cyan
                                ),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationThreadView(
    state: MeshUiState,
    threadId: String,
    onBackToList: () -> Unit,
    onSendMessage: (String) -> Unit,
    onBroadcastGpsPin: () -> Unit,
    onStartCall: (com.kostadinosk.pulsemesh.model.MeshNode) -> Unit,
    onRotateKeys: () -> Unit,
    onVerifyFingerprint: (String) -> Unit = {},
    onPlayVoiceClip: (String) -> Unit = {},
    onSendPhoto: (Bitmap, String) -> Unit = { _, _ -> },
    onSendTacticalSnap: () -> Unit = {},
    onRetrySendPacket: (MeshPacket) -> Unit = {},
    onSendReaction: (String, String) -> Unit = { _, _ -> },
    onTogglePinMessage: (String, String) -> Unit = { _, _ -> },
    onSetThreadBurnTtl: (String, Int) -> Unit = { _, _ -> },
    onStartReply: (MeshPacket) -> Unit = {},
    onClearReply: () -> Unit = {},
    onStartEdit: (MeshPacket) -> Unit = {},
    onClearEdit: () -> Unit = {},
    onCommitEdit: (String, String, String) -> Unit = { _, _, _ -> },
    onUnsendMessage: (String) -> Unit = {},
    onTypingChanged: (Boolean) -> Unit = {},
    onSearchActiveThread: (String) -> Unit = {},
    onSendFileBytes: (String, String, ByteArray) -> Unit = { _, _, _ -> },
    onPauseFileTransfer: (String) -> Unit = {},
    onResumeFileTransfer: (String) -> Unit = {},
    onAddGroupMember: (String, String) -> Unit = { _, _ -> },
    onRemoveGroupMember: (String, String) -> Unit = { _, _ -> },
    onToggleGroupAdmin: (String, String) -> Unit = { _, _ -> },
    onRotateGroupKey: (String) -> Unit = {},
    onBuildGroupInviteQr: (String) -> String = { "" },
    onSendVoiceNoteClip: (ByteArray, Long) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    // Intercept back button to return to conversation list
    BackHandler {
        onBackToList()
    }

    val context = LocalContext.current
    var textInput by remember(state.editingPacket?.id) {
        mutableStateOf(state.editingPacket?.content ?: "")
    }
    var showPhotoPickerModal by remember { mutableStateOf(false) }
    var showGroupMgmtDialog by remember { mutableStateOf(false) }
    var pendingPhotoCaption by remember { mutableStateOf("") }
    var isRecordingVoiceNote by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "attachment.bin" } ?: "attachment.bin"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    onSendFileBytes(name, mime, bytes)
                }
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { bmp ->
                        onSendPhoto(bmp, pendingPhotoCaption.ifBlank { textInput })
                        pendingPhotoCaption = ""
                        if (textInput.isNotBlank()) textInput = ""
                    }
                }
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bmp ->
        if (bmp != null) {
            onSendPhoto(bmp, pendingPhotoCaption.ifBlank { textInput })
            pendingPhotoCaption = ""
            if (textInput.isNotBlank()) textInput = ""
        }
    }

    val p = LocalPulsePalette.current
    val btnShape = tacticalButtonShape(state.isSimpleMode, p.isOriginal, p.isAero)
    val currentConvo = state.conversations.find { it.threadId == threadId }
    val threadTitle = currentConvo?.title ?: threadId
    val isEncrypted = currentConvo?.isEncrypted ?: true
    val isGroup = currentConvo?.isGroup == true
    val isDm = currentConvo != null && !currentConvo.isChannel && !currentConvo.isGroup && !currentConvo.peerNodeId.isNullOrBlank()

    val packets = state.activeThreadPackets
    var threadSearchQuery by remember(threadId) { mutableStateOf("") }
    var showThreadSearch by remember(threadId) { mutableStateOf(false) }
    val filteredPackets = remember(packets, threadSearchQuery) {
        if (threadSearchQuery.isBlank()) packets
        else packets.filter {
            it.content.contains(threadSearchQuery, ignoreCase = true) ||
                it.sourceCallsign.contains(threadSearchQuery, ignoreCase = true)
        }
    }
    val pinnedPacket = remember(packets) { packets.firstOrNull { it.isPinned } }
    val activeThreadBurnSec = state.perThreadBurnTtlMap[threadId] ?: state.messageBurnTtlSeconds

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .aeroCanvasBackground(p)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .testTag("conversation_thread_view")
    ) {
        // Thread Top Bar with Back Button, Call Button & GPS Pin
        Surface(
            color = if (p.isAero) Color.Transparent else p.card,
            modifier = Modifier
                .fillMaxWidth()
                .aeroGlassPanel(p, cornerRadius = 0.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, p.border)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackToList,
                    modifier = Modifier.testTag("chat_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Chats",
                        tint = p.emerald
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = threadTitle,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = p.textPrimary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = when {
                                isGroup -> Icons.Default.Groups
                                isEncrypted -> Icons.Default.Lock
                                else -> Icons.Default.Public
                            },
                            contentDescription = "Security Status",
                            tint = when {
                                isGroup -> p.cyan
                                isEncrypted -> p.emerald
                                else -> p.cyan
                            },
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    Text(
                        text = when {
                            isGroup && state.isSimpleMode -> com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "ENCRYPTED GROUP CHAT • ${currentConvo?.memberIds?.size ?: 0} MEMBERS", "ΚΡΥΠΤΟΓΡΑΦΗΜΕΝΗ ΟΜΑΔΑ • ${currentConvo?.memberIds?.size ?: 0} ΜΕΛΗ")
                            isGroup -> "ECDH-WRAPPED AES-256-GCM • ${currentConvo?.memberIds?.size ?: 0} ${com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "MEMBERS", "ΜΕΛΗ")} (${currentConvo?.memberIds?.joinToString(", ") ?: ""})"
                            isDm && state.isSimpleMode -> com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "ENCRYPTED DIRECT CHAT", "ΚΡΥΠΤΟΓΡΑΦΗΜΕΝΗ ΙΔΙΩΤΙΚΗ ΣΥΝΟΜΙΛΙΑ")
                            isDm -> "ECDH P-256 TOFU • ${currentConvo?.safetyFingerprint?.ifBlank { com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "KEY PENDING", "ΕΚΚΡΕΜΕΙ ΚΛΕΙΔΙ") } ?: ""}"
                            isEncrypted -> com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "E2EE ENCRYPTED • AES-256-GCM CHANNEL", "ΚΡΥΠΤΟΓΡΑΦΗΜΕΝΟ • ΚΑΝΑΛΙ AES-256-GCM")
                            else -> com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "PUBLIC MESH BROADCAST (ALL)", "ΔΗΜΟΣΙΟ ΚΑΝΑΛΙ MESH (ΟΛΟΙ)")
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                currentConvo?.isKeyChangedWarning == true -> p.red
                                isGroup -> p.cyan
                                isEncrypted -> p.emerald
                                else -> p.textSecondary
                            }
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // If 1-to-1 DM, show prominent Direct Call button!
                val dmPeerId = currentConvo?.peerNodeId.orEmpty()
                if (isDm && dmPeerId.isNotBlank()) {
                    val peerNode = state.nodes.find { it.id == dmPeerId }
                        ?: com.kostadinosk.pulsemesh.model.MeshNode(id = dmPeerId, callsign = currentConvo.title)

                    // Direct Call Button
                    IconButton(
                        onClick = { onStartCall(peerNode) },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(p.emeraldDim)
                            .border(1.dp, p.emerald.copy(alpha = 0.6f), CircleShape)
                            .testTag("chat_direct_call_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Call ${currentConvo.title}",
                            tint = p.emerald,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                if (isGroup && !currentConvo?.groupId.isNullOrBlank()) {
                    IconButton(
                        onClick = { showGroupMgmtDialog = true },
                        modifier = Modifier.testTag("chat_group_mgmt_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ManageAccounts,
                            contentDescription = "Manage Group",
                            tint = p.cyan
                        )
                    }
                }

                // Quick GPS Pin broadcast
                IconButton(onClick = onBroadcastGpsPin) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Broadcast GPS Pin",
                        tint = p.emerald
                    )
                }
            }
        }

        if (isDm && currentConvo?.isKeyChangedWarning == true) {
            Surface(
                color = p.redDim,
                border = androidx.compose.foundation.BorderStroke(1.dp, p.red),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Key Changed Warning",
                            tint = p.red,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "KEY CHANGED — VERIFY FINGERPRINT (${currentConvo.safetyFingerprint})", "ΑΛΛΑΓΗ ΚΛΕΙΔΙΟΥ — ΕΠΑΛΗΘΕΥΣΗ (${currentConvo.safetyFingerprint})"),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = p.red,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                    TextButton(
                        onClick = { onVerifyFingerprint(currentConvo.peerNodeId) }
                    ) {
                        Text(
                            text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "TRUST KEY", "ΕΜΠΙΣΤΟΣΥΝΗ ΚΛΕΙΔΙΟΥ"),
                            color = p.amber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Thread Utility Strip: Search Toggle + Per-Chat Burn Timer + Pinned Message Banner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(p.surface.copy(alpha = 0.72f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                color = if (showThreadSearch) p.cyanDim else p.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(0.8.dp, if (showThreadSearch) p.cyan else p.border),
                modifier = Modifier
                    .clickable {
                        showThreadSearch = !showThreadSearch
                        if (!showThreadSearch) {
                            threadSearchQuery = ""
                            onSearchActiveThread("")
                        }
                    }
                    .testTag("chat_thread_search_toggle")
            ) {
                Text(
                    text = if (showThreadSearch) com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "✕ SEARCH", "✕ ΑΝΑΖΗΤΗΣΗ") else com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "🔍 SEARCH", "🔍 ΑΝΑΖΗΤΗΣΗ"),
                    color = if (showThreadSearch) p.cyan else p.textSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
            val burnLabel = when (activeThreadBurnSec) {
                0 -> com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "BURN: OFF", "ΚΑΥΣΗ: OFF")
                60 -> com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "BURN: 1m", "ΚΑΥΣΗ: 1λ")
                300 -> com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "BURN: 5m", "ΚΑΥΣΗ: 5λ")
                3600 -> com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "BURN: 1h", "ΚΑΥΣΗ: 1ω")
                86400 -> com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "BURN: 24h", "ΚΑΥΣΗ: 24ω")
                else -> "${com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "BURN", "ΚΑΥΣΗ")}: ${activeThreadBurnSec}s"
            }
            Surface(
                color = if (activeThreadBurnSec > 0) p.amberDim else p.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(0.8.dp, if (activeThreadBurnSec > 0) p.amber else p.border),
                modifier = Modifier.clickable {
                    val opts = listOf(0, 60, 300, 3600, 86400)
                    val next = opts[(opts.indexOf(activeThreadBurnSec).coerceAtLeast(0) + 1) % opts.size]
                    onSetThreadBurnTtl(threadId, next)
                }
            ) {
                Text(
                    text = "🔥 $burnLabel",
                    color = if (activeThreadBurnSec > 0) p.amber else p.textSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
            if (pinnedPacket != null) {
                Text(
                    text = "📌 ${pinnedPacket.content.take(36)}",
                    color = p.emerald,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (showThreadSearch) {
            OutlinedTextField(
                value = threadSearchQuery,
                onValueChange = {
                    threadSearchQuery = it
                    onSearchActiveThread(it)
                },
                placeholder = { Text(com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Search messages in this chat...", "Αναζήτηση μηνυμάτων στη συνομιλία..."), fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("chat_thread_search_input")
            )
        }

        // Messages Feed
        if (filteredPackets.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = if (p.isAero) Color.Transparent else p.card,
                    shape = tacticalCardShape(state.isSimpleMode, p.isOriginal, p.isAero),
                    border = androidx.compose.foundation.BorderStroke(1.dp, p.border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aeroGlassPanel(p, cornerRadius = 22.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isEncrypted) Icons.Default.Lock else Icons.Default.Public,
                            contentDescription = null,
                            tint = if (isGroup) p.cyan else p.emerald,
                            modifier = Modifier.size(30.dp)
                        )
                        Text(
                            text = if (isEncrypted) {
                                com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "ENCRYPTED CHANNEL READY", "ΚΡΥΠΤΟΓΡΑΦΗΜΕΝΟ ΚΑΝΑΛΙ ΕΤΟΙΜΟ")
                            } else {
                                com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "PUBLIC MESH CHANNEL READY", "ΔΗΜΟΣΙΟ ΚΑΝΑΛΙ MESH ΕΤΟΙΜΟ")
                            },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                color = p.textPrimary
                            )
                        )
                        Text(
                            text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Send an off-grid text, encrypted photo, or GPS pin below.", "Στείλτε μήνυμα εκτός δικτύου, κρυπτογραφημένη φωτογραφία ή στίγμα GPS παρακάτω."),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = p.textSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredPackets, key = { it.id }) { packet ->
                    MessageBubble(
                        packet = packet,
                        isSimpleMode = state.isSimpleMode,
                        onPlayVoiceClip = onPlayVoiceClip,
                        onRetrySendPacket = onRetrySendPacket,
                        onReact = { emoji -> onSendReaction(packet.id, emoji) },
                        appLanguage = state.appLanguage,
                        onTogglePin = { onTogglePinMessage(threadId, packet.id) },
                        onStartReply = onStartReply,
                        onStartEdit = onStartEdit,
                        onUnsendMessage = onUnsendMessage
                    )
                }
            }
        }

        // Quick-Reply Chips Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val quickReplies = if (state.appLanguage == "el") {
                listOf("Όλα καλά", "Έρχομαι", "Ελήφθη", "Αναμονή")
            } else {
                listOf("All OK", "On my way", "Copy", "Standby")
            }
            for (qr in quickReplies) {
                Surface(
                    color = p.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, p.border),
                    modifier = Modifier.clickable { onSendMessage(qr) }
                ) {
                    Text(
                        text = qr,
                        color = p.textSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }
        }

        val activeTyping = state.typingPeersByThread[threadId]
        if (activeTyping != null) {
            Text(
                text = "${activeTyping.first} is typing...",
                color = p.cyan,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .testTag("chat_typing_indicator")
            )
        }

        val activeTransfers = state.fileTransfers.values.filter { it.threadId == threadId }
        if (activeTransfers.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                activeTransfers.forEach { ft ->
                    Surface(
                        color = p.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, p.cyan.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chat_file_attachment_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null, tint = p.cyan, modifier = Modifier.size(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${ft.fileName} (${ft.completedChunks}/${ft.totalChunks})",
                                    color = p.textPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                LinearProgressIndicator(
                                    progress = { ft.fraction },
                                    color = p.emerald,
                                    trackColor = p.background,
                                    modifier = Modifier.fillMaxWidth().height(3.dp)
                                )
                            }
                            if (ft.state == com.kostadinosk.pulsemesh.viewmodel.controllers.FileTransferState.PAUSED) {
                                TextButton(onClick = { onResumeFileTransfer(ft.transferId) }) {
                                    Text("RESUME", fontSize = 10.sp, color = p.emerald, fontWeight = FontWeight.Bold)
                                }
                            } else if (ft.state == com.kostadinosk.pulsemesh.viewmodel.controllers.FileTransferState.ACTIVE) {
                                TextButton(onClick = { onPauseFileTransfer(ft.transferId) }) {
                                    Text("PAUSE", fontSize = 10.sp, color = p.amber, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        val replyTarget = state.replyingToPacket
        if (replyTarget != null) {
            Surface(
                color = p.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, p.cyan.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("chat_reply_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "↩ Replying to ${replyTarget.sourceCallsign}: ${replyTarget.content.take(44)}",
                        color = p.cyan,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onClearReply, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel Reply", tint = p.textMuted, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        val editTarget = state.editingPacket
        if (editTarget != null) {
            Surface(
                color = p.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, p.amber.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("chat_edit_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "✎ Editing: ${editTarget.content.take(44)}",
                        color = p.amber,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            onClearEdit()
                            textInput = ""
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel Edit", tint = p.textMuted, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        // Bottom Input Row with Photo/File Sharing, Voice Note & Send
        Surface(
            color = if (p.isAero) Color.Transparent else p.card,
            modifier = Modifier
                .fillMaxWidth()
                .aeroGlassPanel(p, cornerRadius = 0.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            border = androidx.compose.foundation.BorderStroke(1.dp, p.border)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Share Photo / Recon Snapshot / File button
                IconButton(
                    onClick = {
                        pendingPhotoCaption = textInput
                        showPhotoPickerModal = true
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(btnShape)
                        .background(p.surfaceVariant)
                        .border(1.dp, p.cyan.copy(alpha = 0.6f), btnShape)
                        .testTag("chat_attach_photo_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddAPhoto,
                        contentDescription = "Share Photo in Chat",
                        tint = p.cyan,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                OutlinedTextField(
                    value = textInput,
                    onValueChange = {
                        textInput = it
                        onTypingChanged(it.isNotBlank())
                    },
                    placeholder = {
                        Text(
                            when {
                                isGroup -> com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Encrypted group message...", "Κρυπτογραφημένο ομαδικό μήνυμα...")
                                isEncrypted -> "Secure message to $threadTitle..."
                                else -> com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Broadcast to Public Mesh...", "Εκπομπή στο Δημόσιο Mesh...")
                            },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = p.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp)
                        .testTag("chat_input_field"),
                    shape = btnShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = p.emerald,
                        unfocusedBorderColor = p.border,
                        focusedContainerColor = p.background,
                        unfocusedContainerColor = p.background,
                        focusedTextColor = p.textPrimary,
                        unfocusedTextColor = p.textPrimary
                    ),
                    maxLines = 3
                )

                Spacer(modifier = Modifier.width(6.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(btnShape)
                        .background(if (isRecordingVoiceNote) p.red else p.surfaceVariant)
                        .border(1.dp, if (isRecordingVoiceNote) p.red else p.border, btnShape)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    isRecordingVoiceNote = true
                                    val startAt = System.currentTimeMillis()
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        isRecordingVoiceNote = false
                                        val elapsedMs = (System.currentTimeMillis() - startAt).coerceAtLeast(500L)
                                        onSendVoiceNoteClip(ByteArray(320), elapsedMs)
                                    }
                                }
                            )
                        }
                        .testTag("chat_voice_note_hold_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Hold to record voice note",
                        tint = if (isRecordingVoiceNote) Color.White else p.cyan,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                val canSend = textInput.isNotBlank()
                val sendTint = if (canSend) (if (p.isLight || p.isAero) Color.White else Color.Black) else p.textMuted
                IconButton(
                    onClick = {
                        if (canSend) {
                            val pendingEdit = state.editingPacket
                            if (pendingEdit != null) {
                                onCommitEdit(threadId, pendingEdit.id, textInput)
                                onClearEdit()
                            } else {
                                onSendMessage(textInput)
                                onTypingChanged(false)
                            }
                            textInput = ""
                            scope.launch {
                                listState.animateScrollToItem(0)
                            }
                        }
                    },
                    modifier = Modifier
                        .size(46.dp)
                        .clip(btnShape)
                        .background(if (canSend) p.emerald else p.surfaceVariant)
                        .then(
                            if (canSend && p.isAero) {
                                Modifier.aeroGlossOverlay(p, p.emerald, btnShape, cornerRadius = 18.dp)
                            } else {
                                Modifier
                            }
                        )
                        .testTag("chat_send_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send Message",
                        tint = sendTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    if (showPhotoPickerModal) {
        val actionFg = if (p.isLight || p.isAero) Color.White else Color.Black
        AlertDialog(
            onDismissRequest = { showPhotoPickerModal = false },
            containerColor = p.card,
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = null,
                        tint = p.cyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "SHARE ENCRYPTED PHOTO", "ΚΟΙΝΟΠΟΙΗΣΗ ΚΡΥΠΤΟΓΡΑΦΗΜΕΝΗΣ ΦΩΤΟΓΡΑΦΙΑΣ"),
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = p.cyan,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Photos are automatically compressed into compact encrypted JPEG mesh frames for instant transmission over Bluetooth, Wi-Fi Hotspot, or Hybrid Relay.", "Οι φωτογραφίες συμπιέζονται αυτόματα σε κρυπτογραφημένα καρέ JPEG για άμεση αποστολή μέσω Bluetooth, Wi-Fi Hotspot ή Hybrid Relay."),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = p.textSecondary,
                            fontSize = 11.sp
                        )
                    )
                    OutlinedTextField(
                        value = pendingPhotoCaption,
                        onValueChange = { pendingPhotoCaption = it },
                        label = { Text(com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "Optional Caption", "Προαιρετική λεζάντα"), color = p.textSecondary, fontSize = 11.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = p.cyan,
                            unfocusedBorderColor = p.border,
                            focusedTextColor = p.textPrimary,
                            unfocusedTextColor = p.textPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            showPhotoPickerModal = false
                            galleryLauncher.launch("image/*")
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = p.emerald,
                            contentColor = actionFg
                        ),
                        shape = btnShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("photo_picker_gallery_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "CHOOSE PHOTO FROM GALLERY", "ΕΠΙΛΟΓΗ ΦΩΤΟΓΡΑΦΙΑΣ ΑΠΟ ΣΥΛΛΟΓΗ"),
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }

                    Button(
                        onClick = {
                            showPhotoPickerModal = false
                            cameraLauncher.launch(null)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = p.cyan,
                            contentColor = actionFg
                        ),
                        shape = btnShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("photo_picker_camera_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "CAPTURE CAMERA PHOTO", "ΛΗΨΗ ΦΩΤΟΓΡΑΦΙΑΣ ΑΠΟ ΚΑΜΕΡΑ"),
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            showPhotoPickerModal = false
                            onSendTacticalSnap()
                        },
                        border = androidx.compose.foundation.BorderStroke(1.dp, p.amber),
                        shape = btnShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("photo_picker_tactical_snap_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ScreenshotMonitor,
                            contentDescription = null,
                            tint = p.amber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "SEND TACTICAL RECON CARD", "ΑΠΟΣΤΟΛΗ ΤΑΚΤΙΚΗΣ ΚΑΡΤΑΣ"),
                            color = p.amber,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            showPhotoPickerModal = false
                            fileLauncher.launch("*/*")
                        },
                        border = androidx.compose.foundation.BorderStroke(1.dp, p.emerald),
                        shape = btnShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("photo_picker_file_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = null,
                            tint = p.emerald,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "SEND FILE (CHUNKED E2EE)", "ΑΠΟΣΤΟΛΗ ΑΡΧΕΙΟΥ (E2EE)"),
                            color = p.emerald,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPhotoPickerModal = false }) {
                    Text(com.kostadinosk.pulsemesh.util.AppStrings.tr(state.appLanguage, "CANCEL", "ΑΚΥΡΩΣΗ"), color = p.textSecondary)
                }
            }
        )
    }

    val activeGroupId = currentConvo?.groupId
    val activeGroupObj = if (showGroupMgmtDialog && !activeGroupId.isNullOrBlank()) {
        state.meshGroups.find { it.groupId == activeGroupId }
    } else null
    if (activeGroupObj != null) {
        com.kostadinosk.pulsemesh.ui.screens.chat.GroupManagementSheet(
            group = activeGroupObj,
            onDismiss = { showGroupMgmtDialog = false },
            onAddMember = { memberId -> onAddGroupMember(activeGroupObj.groupId, memberId) },
            onRemoveMember = { memberId -> onRemoveGroupMember(activeGroupObj.groupId, memberId) },
            onToggleAdmin = { memberId -> onToggleGroupAdmin(activeGroupObj.groupId, memberId) },
            onRotateGroupKey = { onRotateGroupKey(activeGroupObj.groupId) },
            qrInvitePayload = onBuildGroupInviteQr(activeGroupObj.groupId)
        )
    }
    }
}

@Composable
private fun MessageBubble(
    packet: MeshPacket,
    isSimpleMode: Boolean = false,
    onPlayVoiceClip: (String) -> Unit = {},
    onRetrySendPacket: (MeshPacket) -> Unit = {},
    onReact: (String) -> Unit = {},
    appLanguage: String = "en",
    onTogglePin: () -> Unit = {},
    onStartReply: (MeshPacket) -> Unit = {},
    onStartEdit: (MeshPacket) -> Unit = {},
    onUnsendMessage: (String) -> Unit = {}
) {
    val p = LocalPulsePalette.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val timeString = remember(packet.timestamp) { timeFormat.format(Date(packet.timestamp)) }
    val isOutgoing = !packet.isIncoming
    var showFullScreenPhoto by remember { mutableStateOf(false) }
    var showRetryConfirmDialog by remember { mutableStateOf(false) }
    var playbackSpeedIdx by remember { mutableIntStateOf(0) }
    val playbackSpeedLabels = remember { listOf("1x", "1.5x", "2x") }
    var lastRetriedAtMs by remember { mutableLongStateOf(0L) }
    val retryBaseTimeMs = if (lastRetriedAtMs > 0L) lastRetriedAtMs else packet.timestamp
    var retryGraceElapsed by remember(packet.id, packet.isDelivered, retryBaseTimeMs) {
        mutableStateOf(packet.isDelivered || (System.currentTimeMillis() - retryBaseTimeMs) >= 6_000L)
    }
    LaunchedEffect(packet.id, packet.isDelivered, retryBaseTimeMs) {
        if (!packet.isDelivered && isOutgoing) {
            val elapsed = System.currentTimeMillis() - retryBaseTimeMs
            val remaining = (6_000L - elapsed).coerceAtLeast(0L)
            if (remaining > 0L) {
                retryGraceElapsed = false
                kotlinx.coroutines.delay(remaining)
            }
            retryGraceElapsed = true
        }
    }

    val transportBadge = remember(packet.transportUsed, packet.transport) {
        val raw = packet.transportUsed.ifBlank { packet.transport }
        when {
            raw.contains("WIFI", ignoreCase = true) || raw.contains("UDP", ignoreCase = true) -> "WIFI"
            raw.contains("WEB", ignoreCase = true) -> "WEB"
            raw.contains("L2CAP", ignoreCase = true) -> "L2CAP"
            raw.contains("HYBRID", ignoreCase = true) || raw.contains("NTFY", ignoreCase = true) -> "INTERNET"
            raw.contains("GATT", ignoreCase = true) -> "BLE"
            else -> raw.take(10).uppercase()
        }
    }

    val bubbleShape = when {
        p.isAero -> RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = if (isOutgoing) 20.dp else 6.dp,
            bottomEnd = if (isOutgoing) 6.dp else 20.dp
        )
        p.isOriginal -> RoundedCornerShape(12.dp)
        isSimpleMode -> RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomStart = if (isOutgoing) 18.dp else 4.dp,
            bottomEnd = if (isOutgoing) 4.dp else 18.dp
        )
        else -> CutCornerShape(
            topStart = if (isOutgoing) 12.dp else 2.dp,
            topEnd = if (isOutgoing) 2.dp else 12.dp,
            bottomStart = if (isOutgoing) 12.dp else 0.dp,
            bottomEnd = if (isOutgoing) 0.dp else 12.dp
        )
    }
    val accentColor = if (isOutgoing) p.emerald else p.cyan

    val isImagePacket = packet.content.startsWith("[[PM_IMAGE]]:") || packet.content.startsWith("[[PM_IMG]]:")
    val decodedPhotoAndCaption = remember(packet.content) {
        if (!isImagePacket) {
            null
        } else {
            runCatching {
                val raw = packet.content
                    .removePrefix("[[PM_IMAGE]]:")
                    .removePrefix("[[PM_IMG]]:")
                    .trim()
                val b64 = raw.substringBefore("||").trim()
                val caption = raw.substringAfter("||", "").trim()
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) Pair(bmp.asImageBitmap(), caption) else null
            }.getOrNull()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (p.isAero) Color.Transparent else if (isOutgoing) p.outgoingBubbleBg else p.incomingBubbleBg,
            shape = bubbleShape,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isOutgoing) p.emerald.copy(alpha = 0.55f) else p.border
            ),
            modifier = Modifier
                .widthIn(max = 292.dp)
                .aeroGlassPanel(
                    palette = p,
                    cornerRadius = 20.dp,
                    tint = if (isOutgoing) p.outgoingBubbleBg else p.incomingBubbleBg,
                    borderColor = if (isOutgoing) p.emerald else p.border
                )
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                // Header (Accent dot + Sender callsign + timestamp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                        )
                        Text(
                            text = if (isOutgoing) "${com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "Self", "Εγώ")} (${packet.sourceId})" else "${packet.sourceCallsign} (${packet.sourceId})",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = accentColor
                            )
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = timeString,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = p.textSecondary
                        )
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                if (packet.replyToId.isNotBlank()) {
                    Surface(
                        color = p.surfaceVariant.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, p.cyan.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                            .testTag("bubble_reply_quote_${packet.id}")
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)) {
                            Text(
                                text = "↩ ${packet.replyToSender.ifBlank { "Reply" }}",
                                color = p.cyan,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = packet.replyToPreview.take(60),
                                color = p.textSecondary,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                val isVoiceDrop = packet.content.startsWith("[[PM_VOICEDROP]]:") ||
                    packet.content.startsWith("[[PM_AUDIO]]:") ||
                    packet.content.startsWith("[[PM_VOICENOTE]]:")
                when {
                    decodedPhotoAndCaption != null -> {
                        val (imageBitmap, captionText) = decodedPhotoAndCaption
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chat_photo_bubble"),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                color = p.background,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, p.cyan.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showFullScreenPhoto = true }
                            ) {
                                Column {
                                    Image(
                                        bitmap = imageBitmap,
                                        contentDescription = captionText.ifBlank { "Shared Tactical Photo" },
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 120.dp, max = 200.dp)
                                            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(p.card)
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Image,
                                                contentDescription = null,
                                                tint = p.cyan,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "ENCRYPTED PHOTO • TAP TO ENLARGE", "ΚΡΥΠΤΟΓΡΑΦΗΜΕΝΗ ΦΩΤΟ • ΠΑΤΗΣΤΕ ΓΙΑ ΜΕΓΕΘΥΝΣΗ"),
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 8.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = p.cyan
                                                )
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.ZoomOutMap,
                                            contentDescription = "Enlarge Photo",
                                            tint = p.cyan,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                            if (captionText.isNotBlank()) {
                                Text(
                                    text = captionText,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = p.textPrimary
                                    )
                                )
                            }
                        }
                    }
                    isImagePacket -> {
                        Surface(
                            color = p.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, p.cyan.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BrokenImage,
                                    contentDescription = "Encrypted Photo",
                                    tint = p.cyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "ENCRYPTED PHOTO (TRANSFER INCOMPLETE)", "ΚΡΥΠΤΟΓΡΑΦΗΜΕΝΗ ΦΩΤΟ (ΗΜΙΤΕΛΗΣ ΜΕΤΑΦΟΡΑ)"),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = p.textSecondary
                                    )
                                )
                            }
                        }
                    }
                    isVoiceDrop -> {
                        Surface(
                            color = p.background,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, p.amber),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPlayVoiceClip(packet.content) }
                                .testTag("voice_drop_play_bubble")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(
                                            imageVector = Icons.Default.PlayCircleFilled,
                                            contentDescription = "Play Voice Drop",
                                            tint = p.amber,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "TACTICAL VOICE DROP", "ΦΩΝΗΤΙΚΟ ΜΗΝΥΜΑ"),
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = p.amber
                                                )
                                            )
                                            Text(
                                                text = if (packet.voiceDurationMs > 0L) {
                                                    "${(packet.voiceDurationMs / 1000.0).let { "%.1fs".format(it) }} • TAP TO PLAY"
                                                } else {
                                                    com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "TAP TO PLAY AUDIO CLIP", "ΠΑΤΗΣΤΕ ΓΙΑ ΑΝΑΠΑΡΑΓΩΓΗ")
                                                },
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 9.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = p.textSecondary
                                                )
                                            )
                                        }
                                    }
                                    Surface(
                                        color = p.surfaceVariant,
                                        shape = RoundedCornerShape(6.dp),
                                        border = androidx.compose.foundation.BorderStroke(0.8.dp, p.emerald.copy(alpha = 0.6f)),
                                        modifier = Modifier
                                            .clickable { playbackSpeedIdx = (playbackSpeedIdx + 1) % playbackSpeedLabels.size }
                                            .testTag("voice_note_speed_btn")
                                    ) {
                                        Text(
                                            text = playbackSpeedLabels[playbackSpeedIdx],
                                            color = p.emerald,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    for (bar in 0 until 18) {
                                        val h = 0.35f + (((bar * 7 + packet.id.hashCode()) and 7) / 10f)
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight(h.coerceIn(0.25f, 1f))
                                                .clip(RoundedCornerShape(1.dp))
                                                .background(p.emerald.copy(alpha = 0.7f))
                                        )
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        if (packet.isUnsent) {
                            Text(
                                text = com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "Message unsent", "Το μήνυμα αναιρέθηκε"),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    color = p.textMuted
                                )
                            )
                        } else {
                            // Message Text Content + optional (edited) marker
                            Text(
                                text = if (packet.editedAtMs > 0L) "${packet.content} (edited)" else packet.content,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = p.textPrimary
                                )
                            )
                        }
                    }
                }

                if (packet.transport == "ECDH KEY PENDING" || packet.rawHexBadge.contains("KEY EXCHANGE PENDING")) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "Key exchange pending — queued locally", "Εκκρεμεί ανταλλαγή κλειδιού — σε τοπική ουρά"),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = p.amber
                        )
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Upgrade 4: Inline Transport / Hop / RSSI Pill Badges + Delivery Receipt Tick Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        val badgeColor = when (transportBadge) {
                            "WIFI", "L2CAP" -> p.cyan
                            "HYBRID" -> p.amber
                            else -> p.emerald
                        }
                        Surface(
                            color = p.surfaceVariant,
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(0.8.dp, badgeColor.copy(alpha = 0.55f))
                        ) {
                            Text(
                                text = transportBadge,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = badgeColor
                                ),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                        if (!isOutgoing && packet.hops > 0) {
                            Surface(
                                color = p.surfaceVariant,
                                shape = RoundedCornerShape(4.dp),
                                border = androidx.compose.foundation.BorderStroke(0.8.dp, p.border)
                            ) {
                                Text(
                                    text = if (packet.hops == 1) com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "1 HOP", "1 ΑΛΜΑ") else com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "${packet.hops} HOPS", "${packet.hops} ΑΛΜΑΤΑ"),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = p.textSecondary
                                    ),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    if (isOutgoing) {
                        val isQueuedKeyPending = packet.transport == "ECDH KEY PENDING" || packet.rawHexBadge.contains("KEY EXCHANGE PENDING")
                        val isInGraceWindow = !packet.isDelivered && !isQueuedKeyPending && !retryGraceElapsed
                        val isReadReceipt = packet.deliveryStatus.equals("READ", ignoreCase = true)
                        val receiptIcon = when {
                            isQueuedKeyPending || isInGraceWindow -> Icons.Default.Schedule
                            isReadReceipt || packet.isDelivered -> Icons.Default.DoneAll
                            else -> Icons.Default.Check
                        }
                        val receiptTint = when {
                            isQueuedKeyPending -> p.amber
                            isReadReceipt -> p.cyan
                            packet.isDelivered -> p.emerald
                            else -> p.textSecondary
                        }
                        val receiptLabel = when {
                            isQueuedKeyPending -> com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "QUEUED", "ΣΕ ΑΝΑΜΟΝΗ")
                            isReadReceipt -> com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "READ", "ΑΝΑΓΝΩΣΤΗΚΕ")
                            packet.isDelivered -> if (packet.ackedByCallsign.isNotBlank()) "${com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "RECEIVED", "ΠΑΡΑΔΟΘΗΚΕ")} (${packet.ackedByCallsign})" else com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "RECEIVED", "ΠΑΡΑΔΟΘΗΚΕ")
                            isInGraceWindow -> com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "SENDING", "ΑΠΟΣΤΟΛΗ")
                            else -> com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "NO ACK", "ΧΩΡΙΣ ACK")
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (!packet.isDelivered && !isQueuedKeyPending && retryGraceElapsed) {
                                Surface(
                                    color = p.amberDim,
                                    shape = RoundedCornerShape(4.dp),
                                    border = androidx.compose.foundation.BorderStroke(0.8.dp, p.amber.copy(alpha = 0.7f)),
                                    modifier = Modifier
                                        .clickable { showRetryConfirmDialog = true }
                                        .testTag("retry_send_message_prompt_${packet.id}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Retry Sending",
                                            tint = p.amber,
                                            modifier = Modifier.size(10.dp)
                                        )
                                        Text(
                                            text = com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "RETRY", "ΕΠΑΝΑΛΗΨΗ"),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontFamily = FontFamily.Monospace,
                                                color = p.amber
                                            )
                                        )
                                    }
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                modifier = Modifier.testTag("message_delivery_status_${packet.id}")
                            ) {
                                Text(
                                    text = receiptLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = receiptTint
                                    )
                                )
                                Icon(
                                    imageVector = receiptIcon,
                                    contentDescription = receiptLabel,
                                    tint = receiptTint,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }

                // Message Reactions & Pin Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for ((emoji, actors) in packet.reactions) {
                        Surface(
                            color = p.emeraldDim,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(0.8.dp, p.emerald.copy(alpha = 0.6f)),
                            modifier = Modifier.clickable { onReact(emoji) }
                        ) {
                            Text(
                                text = "$emoji ${actors.size}",
                                fontSize = 9.sp,
                                color = p.textPrimary,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (!packet.isUnsent) {
                        Text(
                            text = "↩",
                            fontSize = 11.sp,
                            color = p.cyan,
                            modifier = Modifier
                                .clickable { onStartReply(packet) }
                                .padding(horizontal = 3.dp)
                                .testTag("bubble_reply_btn_${packet.id}")
                        )
                        if (isOutgoing) {
                            Text(
                                text = "✎",
                                fontSize = 11.sp,
                                color = p.amber,
                                modifier = Modifier
                                    .clickable { onStartEdit(packet) }
                                    .padding(horizontal = 3.dp)
                                    .testTag("bubble_edit_btn_${packet.id}")
                            )
                            Text(
                                text = "🗑",
                                fontSize = 10.sp,
                                color = p.red,
                                modifier = Modifier
                                    .clickable { onUnsendMessage(packet.id) }
                                    .padding(horizontal = 3.dp)
                                    .testTag("bubble_unsend_btn_${packet.id}")
                            )
                        }
                    }
                    for (quickEmoji in listOf("👍", "✅", "⚠️")) {
                        Text(
                            text = quickEmoji,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .clickable { onReact(quickEmoji) }
                                .padding(horizontal = 2.dp)
                        )
                    }
                    Text(
                        text = if (packet.isPinned) "📌" else "📍",
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clickable { onTogglePin() }
                            .padding(horizontal = 2.dp)
                    )
                }
            }
        }
    }

    if (showRetryConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRetryConfirmDialog = false },
            containerColor = p.card,
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SyncProblem,
                        contentDescription = null,
                        tint = p.amber,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "MESSAGE NOT RECEIVED YET", "ΤΟ ΜΗΝΥΜΑ ΔΕΝ ΕΧΕΙ ΠΑΡΑΔΟΘΕΙ ΑΚΟΜΑ"),
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = p.amber,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            },
            text = {
                Text(
                    text = com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "No delivery receipt (ACK) has arrived for this message yet. Would you like to retry sending it across all active mesh transports (BLE, L2CAP, and Wi-Fi Hotspot)?", "Δεν έχει φτάσει επιβεβαίωση παράδοσης (ACK) για αυτό το μήνυμα ακόμα. Θέλετε να ξανασταλεί σε όλα τα ενεργά δίκτυα (BLE, L2CAP και Wi-Fi Hotspot);"),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = p.textPrimary,
                        fontSize = 12.sp
                    )
                )
            },
            confirmButton = {
                val fg = if (p.isLight || p.isAero) Color.White else Color.Black
                Button(
                    onClick = {
                        showRetryConfirmDialog = false
                        lastRetriedAtMs = System.currentTimeMillis()
                        onRetrySendPacket(packet)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = p.emerald,
                        contentColor = fg
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("confirm_retry_send_button")
                ) {
                    Text(
                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "RETRY SEND NOW", "ΕΠΑΝΑΠΟΣΤΟΛΗ ΤΩΡΑ"),
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRetryConfirmDialog = false }) {
                    Text(com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "WAIT", "ΑΝΑΜΟΝΗ"), color = p.textSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        )
    }

    if (showFullScreenPhoto && decodedPhotoAndCaption != null) {
        val (imageBitmap, captionText) = decodedPhotoAndCaption
        AlertDialog(
            onDismissRequest = { showFullScreenPhoto = false },
            containerColor = p.card,
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isOutgoing) com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "SHARED PHOTO (SELF)", "ΚΟΙΝΟΠΟΙΗΜΕΝΗ ΦΩΤΟ (ΕΓΩ)") else "${com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "PHOTO FROM", "ΦΩΤΟ ΑΠΟ")} ${packet.sourceCallsign}",
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = p.cyan,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                    Text(
                        text = timeString,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = p.textSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = captionText.ifBlank { "Shared Tactical Photo" },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 360.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, p.cyan, RoundedCornerShape(10.dp))
                    )
                    if (captionText.isNotBlank()) {
                        Text(
                            text = captionText,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = p.textPrimary,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                    Text(
                        text = "${com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "Transport", "Μεταφορά")}: $transportBadge",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = p.emerald,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            },
            confirmButton = {
                val fg = if (p.isLight || p.isAero) Color.White else Color.Black
                Button(
                    onClick = { showFullScreenPhoto = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = p.emerald,
                        contentColor = fg
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = com.kostadinosk.pulsemesh.util.AppStrings.tr(appLanguage, "CLOSE", "ΚΛΕΙΣΙΜΟ"),
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        )
    }
}
