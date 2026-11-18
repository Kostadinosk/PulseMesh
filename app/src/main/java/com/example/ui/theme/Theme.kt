package com.example.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.example.util.AppTheme

@Immutable
data class PulseMeshPalette(
    val isLight: Boolean,
    val isOriginal: Boolean = false,
    val isAero: Boolean = false,
    val isDarkAero: Boolean = false,
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

@Composable
fun PulseMeshTheme(
    appTheme: AppTheme = AppTheme.TACTICAL_DARK,
    content: @Composable () -> Unit
) {
    val palette = when (appTheme) {
        AppTheme.FRUTIGER_AERO -> AeroPulsePalette
        AppTheme.FRUTIGER_AERO_DARK -> DarkAeroPulsePalette
        AppTheme.TACTICAL_LIGHT -> LightPulsePalette
        AppTheme.ORIGINAL -> OriginalDarkPulsePalette
        else -> DarkPulsePalette
    }
    val colorScheme = when (appTheme) {
        AppTheme.FRUTIGER_AERO -> AeroColorScheme
        AppTheme.FRUTIGER_AERO_DARK -> DarkAeroColorScheme
        AppTheme.TACTICAL_LIGHT -> LightTacticalColorScheme
        else -> TacticalColorScheme
    }

    CompositionLocalProvider(LocalPulsePalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
