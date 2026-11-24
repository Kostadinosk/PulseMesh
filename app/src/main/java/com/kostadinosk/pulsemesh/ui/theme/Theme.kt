// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kostadinosk.pulsemesh.util.AppTheme
import com.kostadinosk.pulsemesh.util.ThemeBrightness
import com.kostadinosk.pulsemesh.util.ThemePreset

@Immutable
data class PulseMeshPalette(
    val isLight: Boolean,
    val isOriginal: Boolean = false,
    val isAero: Boolean = false,
    val isDarkAero: Boolean = false,
    val isLiquidGlass: Boolean = false,
    val background: Color,
    val card: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val border: Color,
    val borderBright: Color,
    val emerald: Color,
    val emeraldDim: Color,
    val cyan: Color,
    val cyanDim: Color,
    val amber: Color,
    val amberDim: Color,
    val red: Color,
    val redDim: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val outgoingBubbleBg: Color,
    val incomingBubbleBg: Color,
    val topBarBg: Color
)

val DarkPulsePalette = PulseMeshPalette(
    isLight = false,
    isOriginal = false,
    isAero = false,
    isDarkAero = false,
    background = TacticalBlack,
    card = DeepCarbon,
    surface = TacticalSurface,
    surfaceVariant = TacticalSurfaceVariant,
    border = TacticalBorder,
    borderBright = TacticalBorderBright,
    emerald = EmeraldMesh,
    emeraldDim = EmeraldMeshDim,
    cyan = CyanGateway,
    cyanDim = CyanGatewayDim,
    amber = AmberRootBoost,
    amberDim = AmberRootBoostDim,
    red = RedSosAlert,
    redDim = RedSosAlertDim,
    textPrimary = TelemetryTextPrimary,
    textSecondary = TelemetryTextSecondary,
    textMuted = TelemetryTextMuted,
    outgoingBubbleBg = Color(0xFF00381B),
    incomingBubbleBg = TacticalSurfaceVariant,
    topBarBg = Color(0xFF111722)
)

val OriginalDarkPulsePalette = DarkPulsePalette.copy(
    isOriginal = true,
    isAero = false,
    isDarkAero = false,
    outgoingBubbleBg = EmeraldMeshDim,
    incomingBubbleBg = TacticalSurfaceVariant,
    topBarBg = DeepCarbon
)

val LightPulsePalette = PulseMeshPalette(
    isLight = true,
    isOriginal = false,
    isAero = false,
    isDarkAero = false,
    background = DaylightCanvas,
    card = DaylightCard,
    surface = DaylightSurface,
    surfaceVariant = DaylightSurfaceVariant,
    border = DaylightBorder,
    borderBright = DaylightBorderBright,
    emerald = DaylightEmerald,
    emeraldDim = DaylightEmeraldDim,
    cyan = DaylightCyan,
    cyanDim = DaylightCyanDim,
    amber = DaylightAmber,
    amberDim = DaylightAmberDim,
    red = DaylightRed,
    redDim = DaylightRedDim,
    textPrimary = DaylightTextPrimary,
    textSecondary = DaylightTextSecondary,
    textMuted = DaylightTextMuted,
    outgoingBubbleBg = Color(0xFFD8F5E5),
    incomingBubbleBg = Color(0xFFFFFFFF),
    topBarBg = Color(0xFFFFFFFF)
)

val AeroPulsePalette = PulseMeshPalette(
    isLight = true,
    isOriginal = false,
    isAero = true,
    isDarkAero = false,
    background = AeroSkyCanvas,
    card = AeroGlassCard,
    surface = AeroGlassSurface,
    surfaceVariant = AeroGlassVariant,
    border = AeroCrystalBorder,
    borderBright = AeroCrystalBorderBright,
    emerald = AeroEmerald,
    emeraldDim = AeroEmeraldDim,
    cyan = AeroCyan,
    cyanDim = AeroCyanDim,
    amber = AeroAmber,
    amberDim = AeroAmberDim,
    red = AeroRed,
    redDim = AeroRedDim,
    textPrimary = AeroTextPrimary,
    textSecondary = AeroTextSecondary,
    textMuted = AeroTextMuted,
    outgoingBubbleBg = Color(0xFFC5F7E3),
    incomingBubbleBg = Color(0xEBF4FBFF),
    topBarBg = Color(0xE0E6F7FF)
)

val DarkAeroPulsePalette = PulseMeshPalette(
    isLight = false,
    isOriginal = false,
    isAero = true,
    isDarkAero = true,
    background = DarkAeroObsidianCanvas,
    card = DarkAeroGlassCard,
    surface = DarkAeroGlassSurface,
    surfaceVariant = DarkAeroGlassVariant,
    border = DarkAeroCrystalBorder,
    borderBright = DarkAeroCrystalBorderBright,
    emerald = DarkAeroEmerald,
    emeraldDim = DarkAeroEmeraldDim,
    cyan = DarkAeroCyan,
    cyanDim = DarkAeroCyanDim,
    amber = DarkAeroAmber,
    amberDim = DarkAeroAmberDim,
    red = DarkAeroRed,
    redDim = DarkAeroRedDim,
    textPrimary = DarkAeroTextPrimary,
    textSecondary = DarkAeroTextSecondary,
    textMuted = DarkAeroTextMuted,
    outgoingBubbleBg = Color(0xBA094132),
    incomingBubbleBg = Color(0xC80D2B44),
    topBarBg = Color(0xDF071C2E)
)

val LocalPulsePalette = staticCompositionLocalOf { DarkPulsePalette }

fun tacticalCardShape(isSimpleMode: Boolean, isOriginal: Boolean = false, isAero: Boolean = false): Shape = when {
    isAero -> RoundedCornerShape(22.dp)
    isOriginal -> RoundedCornerShape(12.dp)
    isSimpleMode -> RoundedCornerShape(20.dp)
    else -> CutCornerShape(topStart = 12.dp, topEnd = 4.dp, bottomEnd = 12.dp, bottomStart = 4.dp)
}

fun tacticalButtonShape(isSimpleMode: Boolean, isOriginal: Boolean = false, isAero: Boolean = false): Shape = when {
    isAero -> RoundedCornerShape(18.dp)
    isOriginal -> RoundedCornerShape(10.dp)
    isSimpleMode -> RoundedCornerShape(14.dp)
    else -> CutCornerShape(topStart = 8.dp, topEnd = 2.dp, bottomEnd = 8.dp, bottomStart = 2.dp)
}

val TacticalColorScheme = darkColorScheme(
    primary = EmeraldMesh,
    onPrimary = Color.Black,
    primaryContainer = TacticalSurfaceVariant,
    onPrimaryContainer = EmeraldMesh,
    secondary = CyanGateway,
    onSecondary = Color.Black,
    secondaryContainer = CyanGatewayDim,
    onSecondaryContainer = CyanGateway,
    tertiary = AmberRootBoost,
    onTertiary = Color.Black,
    tertiaryContainer = AmberRootBoostDim,
    onTertiaryContainer = AmberRootBoost,
    error = RedSosAlert,
    onError = Color.White,
    errorContainer = RedSosAlertDim,
    onErrorContainer = RedSosAlert,
    background = TacticalBlack,
    onBackground = TelemetryTextPrimary,
    surface = TacticalSurface,
    onSurface = TelemetryTextPrimary,
    surfaceVariant = TacticalSurfaceVariant,
    onSurfaceVariant = TelemetryTextSecondary,
    outline = TacticalBorder,
    outlineVariant = TacticalBorderBright
)

val LightTacticalColorScheme = lightColorScheme(
    primary = DaylightEmerald,
    onPrimary = Color.White,
    primaryContainer = DaylightEmeraldDim,
    onPrimaryContainer = DaylightEmerald,
    secondary = DaylightCyan,
    onSecondary = Color.White,
    secondaryContainer = DaylightCyanDim,
    onSecondaryContainer = DaylightCyan,
    tertiary = DaylightAmber,
    onTertiary = Color.White,
    tertiaryContainer = DaylightAmberDim,
    onTertiaryContainer = DaylightAmber,
    error = DaylightRed,
    onError = Color.White,
    errorContainer = DaylightRedDim,
    onErrorContainer = DaylightRed,
    background = DaylightCanvas,
    onBackground = DaylightTextPrimary,
    surface = DaylightCard,
    onSurface = DaylightTextPrimary,
    surfaceVariant = DaylightSurfaceVariant,
    onSurfaceVariant = DaylightTextSecondary,
    outline = DaylightBorder,
    outlineVariant = DaylightBorderBright
)

val AeroColorScheme = lightColorScheme(
    primary = AeroCyan,
    onPrimary = Color.White,
    primaryContainer = AeroCyanDim,
    onPrimaryContainer = AeroTextPrimary,
    secondary = AeroEmerald,
    onSecondary = Color.White,
    secondaryContainer = AeroEmeraldDim,
    onSecondaryContainer = AeroTextPrimary,
    tertiary = AeroAmber,
    onTertiary = Color.White,
    tertiaryContainer = AeroAmberDim,
    onTertiaryContainer = AeroAmber,
    error = AeroRed,
    onError = Color.White,
    errorContainer = AeroRedDim,
    onErrorContainer = AeroRed,
    background = AeroSkyCanvas,
    onBackground = AeroTextPrimary,
    surface = AeroGlassCard,
    onSurface = AeroTextPrimary,
    surfaceVariant = AeroGlassVariant,
    onSurfaceVariant = AeroTextSecondary,
    outline = AeroCrystalBorder,
    outlineVariant = AeroCrystalBorderBright
)

val DarkAeroColorScheme = darkColorScheme(
    primary = DarkAeroCyan,
    onPrimary = Color.White,
    primaryContainer = DarkAeroCyanDim,
    onPrimaryContainer = DarkAeroTextPrimary,
    secondary = DarkAeroEmerald,
    onSecondary = Color.White,
    secondaryContainer = DarkAeroEmeraldDim,
    onSecondaryContainer = DarkAeroTextPrimary,
    tertiary = DarkAeroAmber,
    onTertiary = Color.White,
    tertiaryContainer = DarkAeroAmberDim,
    onTertiaryContainer = DarkAeroAmber,
    error = DarkAeroRed,
    onError = Color.White,
    errorContainer = DarkAeroRedDim,
    onErrorContainer = DarkAeroRed,
    background = DarkAeroObsidianCanvas,
    onBackground = DarkAeroTextPrimary,
    surface = DarkAeroGlassCard,
    onSurface = DarkAeroTextPrimary,
    surfaceVariant = DarkAeroGlassVariant,
    onSurfaceVariant = DarkAeroTextSecondary,
    outline = DarkAeroCrystalBorder,
    outlineVariant = DarkAeroCrystalBorderBright
)

val LiquidGlassDarkColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF04131E),
    primaryContainer = Color(0xFF10324A),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF031F16),
    secondaryContainer = Color(0xFF0D3B2E),
    onSecondaryContainer = Color(0xFFD1FAE5),
    tertiary = Color(0xFFA78BFA),
    onTertiary = Color(0xFF1A0E38),
    tertiaryContainer = Color(0xFF2B1F54),
    onTertiaryContainer = Color(0xFFEDE9FE),
    error = Color(0xFFFB7185),
    onError = Color(0xFF2E050D),
    errorContainer = Color(0xFF4C1120),
    onErrorContainer = Color(0xFFFFE4E6),
    background = Color(0xFF070B14),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF111A2B),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1B2940),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0x52FFFFFF),
    outlineVariant = Color(0x2EFFFFFF)
)

val LiquidGlassLightColorScheme = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F1FF),
    onPrimaryContainer = Color(0xFF082F49),
    secondary = Color(0xFF059669),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFF6366F1),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0E7FF),
    onTertiaryContainer = Color(0xFF1E1B4B),
    error = Color(0xFFE11D48),
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF881337),
    background = Color(0xFFEBF1F9),
    onBackground = Color(0xFF0B1220),
    surface = Color(0xE6FFFFFF),
    onSurface = Color(0xFF0B1220),
    surfaceVariant = Color(0xCCE6EFFA),
    onSurfaceVariant = Color(0xFF334155),
    outline = Color(0x9994A3B8),
    outlineVariant = Color(0xCCFFFFFF)
)

val Material3FallbackDarkColorScheme = darkColorScheme(
    primary = Color(0xFF7AD9C1),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005141),
    onPrimaryContainer = Color(0xFF9DF2DA),
    secondary = Color(0xFFB1CCC3),
    onSecondary = Color(0xFF1C352E),
    secondaryContainer = Color(0xFF334B44),
    onSecondaryContainer = Color(0xFFCDE8DE),
    tertiary = Color(0xFFA9CBE3),
    onTertiary = Color(0xFF0F3447),
    tertiaryContainer = Color(0xFF284B5F),
    onTertiaryContainer = Color(0xFFC7E7FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111418),
    onBackground = Color(0xFFE1E3E8),
    surface = Color(0xFF191D23),
    onSurface = Color(0xFFE1E3E8),
    surfaceVariant = Color(0xFF252A32),
    onSurfaceVariant = Color(0xFFC1C7CE),
    outline = Color(0xFF8B9198),
    outlineVariant = Color(0xFF41474E)
)

val Material3FallbackLightColorScheme = lightColorScheme(
    primary = Color(0xFF006B58),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF96F4DC),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4A635B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE8DF),
    onSecondaryContainer = Color(0xFF062019),
    tertiary = Color(0xFF416278),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC6E7FF),
    onTertiaryContainer = Color(0xFF001E2E),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FAF8),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFE6EEEA),
    onSurfaceVariant = Color(0xFF3F4945),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBFC9C4)
)

fun paletteFromMaterial3Scheme(
    scheme: ColorScheme,
    isDark: Boolean
): PulseMeshPalette = PulseMeshPalette(
    isLight = !isDark,
    isOriginal = false,
    isAero = false,
    isDarkAero = false,
    background = scheme.background,
    card = scheme.surface,
    surface = scheme.surface,
    surfaceVariant = scheme.surfaceVariant,
    border = scheme.outlineVariant,
    borderBright = scheme.outline,
    emerald = scheme.primary,
    emeraldDim = scheme.primaryContainer,
    cyan = scheme.tertiary,
    cyanDim = scheme.tertiaryContainer,
    amber = scheme.secondary,
    amberDim = scheme.secondaryContainer,
    red = scheme.error,
    redDim = scheme.errorContainer,
    textPrimary = scheme.onBackground,
    textSecondary = scheme.onSurfaceVariant,
    textMuted = scheme.outline,
    outgoingBubbleBg = scheme.primaryContainer,
    incomingBubbleBg = scheme.surfaceVariant,
    topBarBg = scheme.surface
)

@Composable
fun PulseMeshTheme(
    appTheme: AppTheme = AppTheme.TACTICAL_DARK,
    themePreset: ThemePreset? = null,
    themeBrightness: ThemeBrightness? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkSystem = isSystemInDarkTheme()
    val resolvedPreset = themePreset ?: appTheme.toThemePreset()
    val resolvedBrightness = themeBrightness ?: appTheme.toThemeBrightness()
    val isDark = when (resolvedBrightness) {
        ThemeBrightness.SYSTEM -> darkSystem
        ThemeBrightness.DARK -> true
        ThemeBrightness.LIGHT -> false
    }

    val tokens = resolvePulseTokens(
        preset = resolvedPreset,
        brightness = resolvedBrightness,
        isDark = isDark
    )

    val colorScheme = when (resolvedPreset) {
        ThemePreset.LIQUID_GLASS -> if (isDark) LiquidGlassDarkColorScheme else LiquidGlassLightColorScheme
        ThemePreset.FRUTIGER_AERO -> if (isDark) DarkAeroColorScheme else AeroColorScheme
        ThemePreset.CLEAN_MESSENGER -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                }.getOrElse {
                    if (isDark) Material3FallbackDarkColorScheme else Material3FallbackLightColorScheme
                }
            } else {
                if (isDark) Material3FallbackDarkColorScheme else Material3FallbackLightColorScheme
            }
        }
        ThemePreset.PRACTICAL_TACTICAL, ThemePreset.FIELD_OPS_HUD ->
            if (isDark) TacticalColorScheme else LightTacticalColorScheme
    }

    val effectiveTokens = if (resolvedPreset == ThemePreset.CLEAN_MESSENGER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        tokens.copy(palette = paletteFromMaterial3Scheme(colorScheme, isDark = isDark))
    } else {
        tokens
    }

    CompositionLocalProvider(
        LocalPulseTokens provides effectiveTokens,
        LocalPulsePalette provides effectiveTokens.palette
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
