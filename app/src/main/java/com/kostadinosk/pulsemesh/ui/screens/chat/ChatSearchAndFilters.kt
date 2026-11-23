// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.screens.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.ui.theme.LocalPulseTokens
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.util.AppStrings
import com.kostadinosk.pulsemesh.util.ThemeBrightness
import com.kostadinosk.pulsemesh.util.ThemePreset

enum class ChatListFilter(val labelEn: String, val labelEl: String) {
    ALL("All", "Όλα"),
    UNREAD("Unread", "Μη αναγνωσμένα"),
    NEARBY_MESH("Nearby Mesh", "Κοντινό Mesh"),
    GROUPS("Groups", "Ομάδες")
}

@Composable
fun GlobalChatSearchAndFilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    selectedFilter: ChatListFilter,
    onSelectFilter: (ChatListFilter) -> Unit,
    unreadCount: Int,
    nearbyCount: Int,
    appLanguage: String,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            shape = tokens.pillShape,
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(18.dp))
            },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = p.textSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            },
            placeholder = {
                Text(
                    text = AppStrings.tr(appLanguage, "Search peers, groups, @user, or messages...", "Αναζήτηση κόμβων, ομάδων, @user ή μηνυμάτων..."),
                    fontSize = 12.sp,
                    color = p.textMuted
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("global_chat_search_input")
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChatListFilter.entries.forEach { filter ->
                val isSelected = selectedFilter == filter
                val badge = when (filter) {
                    ChatListFilter.UNREAD -> if (unreadCount > 0) " ($unreadCount)" else ""
                    ChatListFilter.NEARBY_MESH -> if (nearbyCount > 0) " ($nearbyCount)" else ""
                    else -> ""
                }
                Surface(
                    color = if (isSelected) tokens.accentPrimary.copy(alpha = 0.2f) else p.surfaceVariant,
                    shape = tokens.pillShape,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) tokens.accentPrimary else p.border
                    ),
                    modifier = Modifier
                        .clip(tokens.pillShape)
                        .clickable { onSelectFilter(filter) }
                        .testTag("chat_filter_${filter.name.lowercase()}")
                ) {
                    Text(
                        text = AppStrings.tr(appLanguage, filter.labelEn, filter.labelEl) + badge,
                        color = if (isSelected) tokens.accentPrimary else p.textSecondary,
                        fontFamily = tokens.labelFontFamily,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun InConversationSearchBar(
    query: String,
    matchCount: Int,
    activeMatchIndex: Int,
    onQueryChange: (String) -> Unit,
    onPreviousMatch: () -> Unit,
    onNextMatch: () -> Unit,
    onClose: () -> Unit,
    appLanguage: String,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette

    Surface(
        color = p.surface,
        border = BorderStroke(tokens.borderWidth, p.border),
        modifier = modifier
            .fillMaxWidth()
            .testTag("in_chat_search_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = {
                    Text(
                        AppStrings.tr(appLanguage, "Find in conversation...", "Αναζήτηση στη συνομιλία..."),
                        fontSize = 12.sp
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("in_chat_search_input")
            )
            Text(
                text = if (matchCount == 0) "0/0" else "${activeMatchIndex + 1}/$matchCount",
                color = if (matchCount > 0) tokens.accentPrimary else p.textMuted,
                fontFamily = tokens.labelFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
            IconButton(onClick = onPreviousMatch, enabled = matchCount > 0, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous", tint = p.textPrimary)
            }
            IconButton(onClick = onNextMatch, enabled = matchCount > 0, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next", tint = p.textPrimary)
            }
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close Search", tint = p.textSecondary)
            }
        }
    }
}

@Preview(name = "Chat Search & Filters", showBackground = true)
@Composable
fun PreviewChatSearchAndFilters() {
    PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
        Surface(color = LocalPulseTokens.current.palette.background) {
            Column {
                GlobalChatSearchAndFilterBar(
                    query = "",
                    onQueryChange = {},
                    selectedFilter = ChatListFilter.ALL,
                    onSelectFilter = {},
                    unreadCount = 3,
                    nearbyCount = 4,
                    appLanguage = "en"
                )
                InConversationSearchBar(
                    query = "checkpoint",
                    matchCount = 4,
                    activeMatchIndex = 1,
                    onQueryChange = {},
                    onPreviousMatch = {},
                    onNextMatch = {},
                    onClose = {},
                    appLanguage = "en"
                )
            }
        }
    }
}
