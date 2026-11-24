// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.util.ThemeBrightness
import com.kostadinosk.pulsemesh.util.ThemePreset

@Immutable
data class PulseDesignTokens(
    val preset: ThemePreset,
    val brightness: ThemeBrightness,
    val isDark: Boolean,
    val palette: PulseMeshPalette,
    val cardShape: Shape,
    val buttonShape: Shape,
    val pillShape: Shape,
    val sheetShape: Shape,
    val cardElevation: Dp,
    val borderWidth: Dp,
    val sectionSpacing: Dp,
    val contentPadding: Dp,
    val useMonospaceLabels: Boolean,
    val showHudTelemetryBadges: Boolean,
    val useGlassSheen: Boolean,
    val accentPrimary: Color,
    val accentSecondary: Color,
    val statusOnline: Color,
    val statusMesh: Color,
    val statusWarning: Color,
    val statusDanger: Color
) {
    val labelFontFamily: FontFamily
        get() = if (useMonospaceLabels) FontFamily.Monospace else FontFamily.SansSerif
}

// Liquid Glass Palettes (Frosted translucent glass, specular rims & luminous depth)
private val LiquidGlassDarkPalette = PulseMeshPalette(
    isLight = false,
    isOriginal = false,
    isAero = false,
    isDarkAero = false,
    isLiquidGlass = true,
    background = Color(0xFF070B14),
    card = Color(0xA6121E33),
    surface = Color(0xB8182640),
    surfaceVariant = Color(0xC2213252),
    border = Color(0x40E2F1FF),
    borderBright = Color(0x88E2F1FF),
    emerald = Color(0xFF34D399),
    emeraldDim = Color(0x880E3D2F),
    cyan = Color(0xFF38BDF8),
    cyanDim = Color(0x880E3654),
    amber = Color(0xFFFBBF24),
    amberDim = Color(0x884A320C),
    red = Color(0xFFFB7185),
    redDim = Color(0x884F1424),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFFCBD5E1),
    textMuted = Color(0xFF94A3B8),
    outgoingBubbleBg = Color(0xB80E4A3E),
    incomingBubbleBg = Color(0xB81A2A44),
    topBarBg = Color(0xCC0A1221)
)

private val LiquidGlassLightPalette = PulseMeshPalette(
    isLight = true,
    isOriginal = false,
    isAero = false,
    isDarkAero = false,
    isLiquidGlass = true,
    background = Color(0xFFEBF1F9),
    card = Color(0xCCFFFFFF),
    surface = Color(0xDCFFFFFF),
    surfaceVariant = Color(0xCCEEF5FF),
    border = Color(0xD6FFFFFF),
    borderBright = Color(0xFF7DD3FC),
    emerald = Color(0xFF059669),
    emeraldDim = Color(0xCCD1FAE5),
    cyan = Color(0xFF0284C7),
    cyanDim = Color(0xCCD9F1FF),
    amber = Color(0xFFD97706),
    amberDim = Color(0xCCFEF3C7),
    red = Color(0xFFE11D48),
    redDim = Color(0xCCFFE4E6),
    textPrimary = Color(0xFF0B1220),
    textSecondary = Color(0xFF334155),
    textMuted = Color(0xFF64748B),
    outgoingBubbleBg = Color(0xD4D1FAE5),
    incomingBubbleBg = Color(0xE0FFFFFF),
    topBarBg = Color(0xD8F4F8FF)
)

// Clean Messenger Palettes (Signal / WhatsApp minimalist clarity)
private val CleanMessengerDarkPalette = PulseMeshPalette(
    isLight = false,
    isOriginal = false,
    isAero = false,
    isDarkAero = false,
    background = Color(0xFF0B1117),
    card = Color(0xFF131B24),
    surface = Color(0xFF18222D),
    surfaceVariant = Color(0xFF202D3B),
    border = Color(0xFF263545),
    borderBright = Color(0xFF3A4F66),
    emerald = Color(0xFF22C55E),
    emeraldDim = Color(0xFF143A24),
    cyan = Color(0xFF3B82F6),
    cyanDim = Color(0xFF172E54),
    amber = Color(0xFFF59E0B),
    amberDim = Color(0xFF452C0A),
    red = Color(0xFFEF4444),
    redDim = Color(0xFF4A1419),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF9FB0C3),
    textMuted = Color(0xFF6B7D91),
    outgoingBubbleBg = Color(0xFF134E4A),
    incomingBubbleBg = Color(0xFF1C2733),
    topBarBg = Color(0xFF0F161E)
)

private val CleanMessengerLightPalette = PulseMeshPalette(
    isLight = true,
    isOriginal = false,
    isAero = false,
    isDarkAero = false,
    background = Color(0xFFF7F8FA),
    card = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF2F6),
    border = Color(0xFFDCE3EC),
    borderBright = Color(0xFFB8C5D6),
    emerald = Color(0xFF16A34A),
    emeraldDim = Color(0xFFDCFCE7),
    cyan = Color(0xFF2563EB),
    cyanDim = Color(0xFFDBEAFE),
    amber = Color(0xFFD97706),
    amberDim = Color(0xFFFEF3C7),
    red = Color(0xFFDC2626),
    redDim = Color(0xFFFEE2E2),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF64748B),
    outgoingBubbleBg = Color(0xFFD1FAE5),
    incomingBubbleBg = Color(0xFFFFFFFF),
    topBarBg = Color(0xFFFFFFFF)
)

// High-Density Field Ops / ATAK Console Palettes
private val FieldOpsDarkPalette = DarkPulsePalette.copy(
    isOriginal = true,
    background = Color(0xFF06090D),
    card = Color(0xFF0C1219),
    surface = Color(0xFF101822),
    surfaceVariant = Color(0xFF162130),
    border = Color(0xFF1E3A2F),
    borderBright = Color(0xFF00E676),
    outgoingBubbleBg = Color(0xFF063820),
    topBarBg = Color(0xFF080D13)
)

private val FieldOpsLightPalette = LightPulsePalette.copy(
    isOriginal = true,
    background = Color(0xFFECEFF3),
    card = Color(0xFFF9FBFD),
    border = Color(0xFF94A3B8),
    borderBright = Color(0xFF0F172A)
)

// Practical Tactical (Default Material 3 Expressive) Palettes
private val PracticalTacticalDarkPalette = DarkPulsePalette.copy(
    background = Color(0xFF0C1118),
    card = Color(0xFF141B24),
    surface = Color(0xFF18212C),
    surfaceVariant = Color(0xFF202B39),
    border = Color(0xFF2A394C),
    borderBright = Color(0xFF3E5570),
    outgoingBubbleBg = Color(0xFF0F3D2E),
    incomingBubbleBg = Color(0xFF1C2634),
    topBarBg = Color(0xFF10161F)
)

private val PracticalTacticalLightPalette = LightPulsePalette.copy(
    background = Color(0xFFF4F7FA),
    card = Color(0xFFFFFFFF),
    surface = Color(0xFFFAFBFD),
    surfaceVariant = Color(0xFFE7EEF5),
    border = Color(0xFFCBD7E4),
    borderBright = Color(0xFF94A7BC)
)

fun resolvePulseTokens(
    preset: ThemePreset,
    brightness: ThemeBrightness = ThemeBrightness.SYSTEM,
    isDark: Boolean = true
): PulseDesignTokens {
    val palette = when (preset) {
        ThemePreset.LIQUID_GLASS ->
            if (isDark) LiquidGlassDarkPalette else LiquidGlassLightPalette
        ThemePreset.PRACTICAL_TACTICAL ->
            if (isDark) PracticalTacticalDarkPalette else PracticalTacticalLightPalette
        ThemePreset.CLEAN_MESSENGER ->
            if (isDark) CleanMessengerDarkPalette else CleanMessengerLightPalette
        ThemePreset.FIELD_OPS_HUD ->
            if (isDark) FieldOpsDarkPalette else FieldOpsLightPalette
        ThemePreset.FRUTIGER_AERO ->
            if (isDark) DarkAeroPulsePalette else AeroPulsePalette
    }

    return when (preset) {
        ThemePreset.LIQUID_GLASS -> PulseDesignTokens(
            preset = preset,
            brightness = brightness,
            isDark = isDark,
            palette = palette,
            cardShape = RoundedCornerShape(24.dp),
            buttonShape = RoundedCornerShape(20.dp),
            pillShape = RoundedCornerShape(50),
            sheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            cardElevation = if (isDark) 0.dp else 2.dp,
            borderWidth = 1.dp,
            sectionSpacing = 12.dp,
            contentPadding = 16.dp,
            useMonospaceLabels = false,
            showHudTelemetryBadges = false,
            useGlassSheen = true,
            accentPrimary = palette.cyan,
            accentSecondary = palette.emerald,
            statusOnline = palette.emerald,
            statusMesh = palette.cyan,
            statusWarning = palette.amber,
            statusDanger = palette.red
        )
        ThemePreset.PRACTICAL_TACTICAL -> PulseDesignTokens(
            preset = preset,
            brightness = brightness,
            isDark = isDark,
            palette = palette,
            cardShape = RoundedCornerShape(18.dp),
            buttonShape = RoundedCornerShape(14.dp),
            pillShape = RoundedCornerShape(50),
            sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            cardElevation = if (isDark) 0.dp else 2.dp,
            borderWidth = 1.dp,
            sectionSpacing = 12.dp,
            contentPadding = 16.dp,
            useMonospaceLabels = false,
            showHudTelemetryBadges = false,
            useGlassSheen = false,
            accentPrimary = palette.emerald,
            accentSecondary = palette.cyan,
            statusOnline = palette.emerald,
            statusMesh = palette.cyan,
            statusWarning = palette.amber,
            statusDanger = palette.red
        )
        ThemePreset.CLEAN_MESSENGER -> PulseDesignTokens(
            preset = preset,
            brightness = brightness,
            isDark = isDark,
            palette = palette,
            cardShape = RoundedCornerShape(20.dp),
            buttonShape = RoundedCornerShape(24.dp),
            pillShape = RoundedCornerShape(50),
            sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            cardElevation = if (isDark) 0.dp else 1.dp,
            borderWidth = 0.75.dp,
            sectionSpacing = 10.dp,
            contentPadding = 16.dp,
            useMonospaceLabels = false,
            showHudTelemetryBadges = false,
            useGlassSheen = false,
            accentPrimary = palette.cyan,
            accentSecondary = palette.emerald,
            statusOnline = palette.emerald,
            statusMesh = palette.cyan,
            statusWarning = palette.amber,
            statusDanger = palette.red
        )
        ThemePreset.FIELD_OPS_HUD -> PulseDesignTokens(
            preset = preset,
            brightness = brightness,
            isDark = isDark,
            palette = palette,
            cardShape = CutCornerShape(8.dp),
            buttonShape = CutCornerShape(6.dp),
            pillShape = CutCornerShape(4.dp),
            sheetShape = CutCornerShape(topStart = 12.dp, topEnd = 12.dp),
            cardElevation = 0.dp,
            borderWidth = 1.25.dp,
            sectionSpacing = 8.dp,
            contentPadding = 12.dp,
            useMonospaceLabels = true,
            showHudTelemetryBadges = true,
            useGlassSheen = false,
            accentPrimary = palette.emerald,
            accentSecondary = palette.amber,
            statusOnline = palette.emerald,
            statusMesh = palette.cyan,
            statusWarning = palette.amber,
            statusDanger = palette.red
        )
        ThemePreset.FRUTIGER_AERO -> PulseDesignTokens(
            preset = preset,
            brightness = brightness,
            isDark = isDark,
            palette = palette,
            cardShape = RoundedCornerShape(22.dp),
            buttonShape = RoundedCornerShape(18.dp),
            pillShape = RoundedCornerShape(50),
            sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            cardElevation = if (isDark) 2.dp else 4.dp,
            borderWidth = 1.2.dp,
            sectionSpacing = 12.dp,
            contentPadding = 16.dp,
            useMonospaceLabels = false,
            showHudTelemetryBadges = false,
            useGlassSheen = true,
            accentPrimary = palette.cyan,
            accentSecondary = palette.emerald,
            statusOnline = palette.emerald,
            statusMesh = palette.cyan,
            statusWarning = palette.amber,
            statusDanger = palette.red
        )
    }
}

val LocalPulseTokens = staticCompositionLocalOf {
    resolvePulseTokens(ThemePreset.LIQUID_GLASS, ThemeBrightness.DARK, isDark = true)
}

@Composable
fun Modifier.pulseSurfaceCard(
    accentBorder: Color? = null,
    shape: Shape? = null
): Modifier {
    val tokens = LocalPulseTokens.current
    val resolvedShape = shape ?: tokens.cardShape
    val borderColor = accentBorder ?: tokens.palette.border
    val base = this.clip(resolvedShape)
    return when {
        tokens.palette.isLiquidGlass -> {
            val topHighlight = if (tokens.isDark) Color(0x28FFFFFF) else Color(0xE8FFFFFF)
            val midGlass = tokens.palette.card
            val bottomGlass = if (tokens.isDark) Color(0x9E0B1324) else Color(0xB8E8F1FC)
            val rimBrush = if (accentBorder != null) {
                Brush.linearGradient(
                    listOf(
                        accentBorder.copy(alpha = 0.85f),
                        Color(if (tokens.isDark) 0x44FFFFFF else 0xCCFFFFFF),
                        accentBorder.copy(alpha = 0.35f)
                    )
                )
            } else {
                Brush.linearGradient(
                    if (tokens.isDark) {
                        listOf(Color(0x66FFFFFF), Color(0x1EFFFFFF), Color(0x3838BDF8))
                    } else {
                        listOf(Color(0xFFFFFFFF), Color(0x88FFFFFF), Color(0x5593C5FD))
                    }
                )
            }
            base
                .background(
                    Brush.linearGradient(listOf(topHighlight, midGlass, bottomGlass)),
                    resolvedShape
                )
                .border(BorderStroke(tokens.borderWidth, rimBrush), resolvedShape)
        }
        tokens.useGlassSheen -> {
            val sheenTop = if (tokens.isDark) Color(0x3070D8FF) else Color(0xB8FFFFFF)
            val sheenBottom = tokens.palette.card
            base
                .background(Brush.verticalGradient(listOf(sheenTop, sheenBottom)), resolvedShape)
                .border(BorderStroke(tokens.borderWidth, borderColor), resolvedShape)
        }
        else -> {
            base
                .background(tokens.palette.card, resolvedShape)
                .border(BorderStroke(tokens.borderWidth, borderColor), resolvedShape)
        }
    }
}

@Composable
fun PulseThemeSampleCard(
    preset: ThemePreset,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    PulseMeshTheme(
        themePreset = preset,
        themeBrightness = if (isDark) ThemeBrightness.DARK else ThemeBrightness.LIGHT
    ) {
        val tokens = LocalPulseTokens.current
        Surface(
            color = tokens.palette.background,
            modifier = modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(tokens.contentPadding),
                verticalArrangement = Arrangement.spacedBy(tokens.sectionSpacing)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseSurfaceCard(accentBorder = tokens.accentPrimary.copy(alpha = 0.45f))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = preset.displayName,
                            color = tokens.palette.textPrimary,
                            fontFamily = tokens.labelFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = preset.subtitle,
                            color = tokens.palette.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Surface(
                        color = tokens.statusOnline.copy(alpha = 0.16f),
                        shape = tokens.pillShape,
                        border = BorderStroke(1.dp, tokens.statusOnline.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = "MESH READY",
                            color = tokens.statusOnline,
                            fontFamily = tokens.labelFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Practical Tactical Dark", showBackground = true)
@Composable
fun PreviewPracticalTacticalDark() {
    PulseThemeSampleCard(ThemePreset.PRACTICAL_TACTICAL, isDark = true)
}

@Preview(name = "Clean Messenger Light", showBackground = true)
@Composable
fun PreviewCleanMessengerLight() {
    PulseThemeSampleCard(ThemePreset.CLEAN_MESSENGER, isDark = false)
}

@Preview(name = "Field Ops HUD Dark", showBackground = true)
@Composable
fun PreviewFieldOpsHudDark() {
    PulseThemeSampleCard(ThemePreset.FIELD_OPS_HUD, isDark = true)
}

@Preview(name = "Liquid Glass Dark", showBackground = true)
@Composable
fun PreviewLiquidGlassDark() {
    PulseThemeSampleCard(ThemePreset.LIQUID_GLASS, isDark = true)
}

@Preview(name = "Liquid Glass Light", showBackground = true)
@Composable
fun PreviewLiquidGlassLight() {
    PulseThemeSampleCard(ThemePreset.LIQUID_GLASS, isDark = false)
}

@Preview(name = "Frutiger Aero Light", showBackground = true)
@Composable
fun PreviewFrutigerAeroLight() {
    PulseThemeSampleCard(ThemePreset.FRUTIGER_AERO, isDark = false)
}
