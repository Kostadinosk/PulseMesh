// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.ui.theme.LocalPulseTokens
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.ui.theme.pulseSurfaceCard
import com.kostadinosk.pulsemesh.ui.theme.resolvePulseTokens
import com.kostadinosk.pulsemesh.util.AppStrings
import com.kostadinosk.pulsemesh.util.ThemeBrightness
import com.kostadinosk.pulsemesh.util.ThemePreset

/**
 * PulseMesh v2.0.3 Appearance & Language Sub-Screen:
 * - 5 selectable Design-Token ThemePresets (Liquid Glass, Practical Tactical, Clean Messenger, Field Ops HUD, Frutiger Aero)
 * - Brightness mode selector (System, Dark, Light)
 * - Language switcher (English / Ελληνικά)
 */
@Composable
fun AppearanceSettingsScreen(
    selectedPreset: ThemePreset,
    selectedBrightness: ThemeBrightness,
    appLanguage: String,
    onSelectPreset: (ThemePreset) -> Unit,
    onSelectBrightness: (ThemeBrightness) -> Unit,
    onSelectLanguage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
            .testTag("appearance_settings_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Visual Theme Preset Picker
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Palette, contentDescription = null, tint = tokens.accentPrimary)
            Text(
                text = AppStrings.tr(appLanguage, "Visual Style Preset", "Στυλ Εμφάνισης"),
                color = p.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemePreset.entries.forEach { preset ->
                val isSelected = selectedPreset == preset
                val previewTokens = resolvePulseTokens(preset, selectedBrightness, isDark = tokens.isDark)
                Surface(
                    color = previewTokens.palette.card,
                    shape = previewTokens.cardShape,
                    border = BorderStroke(
                        if (isSelected) 2.dp else 1.dp,
                        if (isSelected) previewTokens.accentPrimary else previewTokens.palette.border
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(previewTokens.cardShape)
                        .clickable { onSelectPreset(preset) }
                        .testTag("theme_preset_${preset.name.lowercase()}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = preset.displayName,
                                color = previewTokens.palette.textPrimary,
                                fontFamily = previewTokens.labelFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = preset.subtitle,
                                color = previewTokens.palette.textSecondary,
                                fontSize = 11.sp
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = previewTokens.accentPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. Brightness Selector (System / Dark / Light)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = AppStrings.tr(appLanguage, "Brightness Mode", "Φωτεινότητα"),
                color = p.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeBrightness.entries.forEach { mode ->
                    val active = selectedBrightness == mode
                    Surface(
                        color = if (active) tokens.accentPrimary else p.surfaceVariant,
                        shape = tokens.pillShape,
                        border = BorderStroke(1.dp, if (active) tokens.accentPrimary else p.border),
                        modifier = Modifier
                            .weight(1f)
                            .clip(tokens.pillShape)
                            .clickable { onSelectBrightness(mode) }
                            .testTag("theme_brightness_${mode.name.lowercase()}")
                    ) {
                        Text(
                            text = mode.displayName,
                            color = if (active) (if (tokens.isDark) Color.Black else Color.White) else p.textSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 9.dp)
                        )
                    }
                }
            }
        }

        // 3. Language Selector (EN / EL)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseSurfaceCard()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Language, contentDescription = null, tint = tokens.accentSecondary)
                Text(
                    text = AppStrings.tr(appLanguage, "Language", "Γλώσσα"),
                    color = p.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("en" to "English", "el" to "Ελληνικά").forEach { (code, label) ->
                    val active = appLanguage.equals(code, ignoreCase = true)
                    Surface(
                        color = if (active) tokens.accentSecondary else p.surfaceVariant,
                        shape = tokens.pillShape,
                        border = BorderStroke(1.dp, if (active) tokens.accentSecondary else p.border),
                        modifier = Modifier
                            .weight(1f)
                            .clip(tokens.pillShape)
                            .clickable { onSelectLanguage(code) }
                            .testTag("lang_choice_$code")
                    ) {
                        Text(
                            text = label,
                            color = if (active) Color.White else p.textSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 9.dp)
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Appearance Settings Liquid Glass Dark", showBackground = true)
@Composable
fun PreviewAppearanceSettingsDark() {
    PulseMeshTheme(themePreset = ThemePreset.LIQUID_GLASS, themeBrightness = ThemeBrightness.DARK) {
        Surface(color = LocalPulseTokens.current.palette.background) {
            AppearanceSettingsScreen(
                selectedPreset = ThemePreset.LIQUID_GLASS,
                selectedBrightness = ThemeBrightness.DARK,
                appLanguage = "en",
                onSelectPreset = {},
                onSelectBrightness = {},
                onSelectLanguage = {}
            )
        }
    }
}
