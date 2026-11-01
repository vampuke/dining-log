package top.alanvampuke.dininglog.feature.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Every D2 CSS color token, including those without a Material role.
data class QuietGridColors(
    val bg: Color, val surface: Color, val ink: Color, val secondary: Color,
    val tertiary: Color, val border: Color, val accent: Color, val onAccent: Color,
    val danger: Color, val accentInk: Color, val cta: Color, val accentSoft: Color,
    val borderStrong: Color, val gallery: Color,
)
val QuietGridLight = QuietGridColors(
    bg = Color(0xFFFBFBFA), surface = Color(0xFFFFFFFF), ink = Color(0xFF181916),
    secondary = Color(0xFF555650), tertiary = Color(0xFF6B6A66), border = Color.Black.copy(alpha = 0.08f),
    accent = Color(0xFF2F6B4F), onAccent = Color(0xFFFFFFFF), danger = Color(0xFF922F29),
    accentInk = Color(0xFF23543C), cta = Color(0xFF23543C), accentSoft = Color(0xFFE8F0EA),
    borderStrong = Color(0xFFA3A59C), gallery = Color(0xFFF0F0ED),
)
val QuietGridDark = QuietGridColors(
    bg = Color(0xFF0F0F0E), surface = Color(0xFF191918), ink = Color(0xFFF2F2EC),
    secondary = Color(0xFFB8BAB0), tertiary = Color(0xFFA1A398), border = Color.White.copy(alpha = 0.18f),
    accent = Color(0xFF6FBF95), onAccent = Color(0xFF0F0F0E), danger = Color(0xFFF0A099),
    accentInk = Color(0xFF6FBF95), cta = Color(0xFF6FBF95), accentSoft = Color(0xFF1B2E23),
    borderStrong = Color(0xFF686C60), gallery = Color(0xFF080808),
)
private fun roles(c: QuietGridColors, inverse: QuietGridColors) = lightColorScheme(
    primary = c.accent, onPrimary = c.onAccent,
    primaryContainer = c.accentSoft, onPrimaryContainer = c.accentInk,
    inversePrimary = inverse.accent,
    secondary = c.secondary, onSecondary = c.surface,
    secondaryContainer = c.accentSoft, onSecondaryContainer = c.ink,
    tertiary = c.tertiary, onTertiary = c.surface,
    tertiaryContainer = c.accentSoft, onTertiaryContainer = c.ink,
    background = c.bg, onBackground = c.ink,
    surface = c.surface, onSurface = c.ink,
    surfaceVariant = c.bg, onSurfaceVariant = c.secondary,
    surfaceTint = Color.Transparent,
    inverseSurface = inverse.surface, inverseOnSurface = inverse.ink,
    error = c.danger, onError = c.surface,
    errorContainer = c.bg, onErrorContainer = c.danger,
    outline = c.borderStrong, outlineVariant = c.border, scrim = Color.Black,
    surfaceBright = c.surface, surfaceDim = c.bg,
    surfaceContainer = c.surface, surfaceContainerLow = c.bg,
    surfaceContainerLowest = c.bg, surfaceContainerHigh = c.surface,
    surfaceContainerHighest = c.surface,
)
val QuietGridLightScheme = roles(QuietGridLight, QuietGridDark)
// Copy every role, so Material defaults cannot introduce unrelated dark palette colors.
val QuietGridDarkScheme = darkColorScheme().copy(
    primary = QuietGridDark.accent, onPrimary = QuietGridDark.onAccent,
    primaryContainer = QuietGridDark.accentSoft, onPrimaryContainer = QuietGridDark.accentInk,
    inversePrimary = QuietGridLight.accent,
    secondary = QuietGridDark.secondary, onSecondary = QuietGridDark.surface,
    secondaryContainer = QuietGridDark.accentSoft, onSecondaryContainer = QuietGridDark.ink,
    tertiary = QuietGridDark.tertiary, onTertiary = QuietGridDark.surface,
    tertiaryContainer = QuietGridDark.accentSoft, onTertiaryContainer = QuietGridDark.ink,
    background = QuietGridDark.bg, onBackground = QuietGridDark.ink,
    surface = QuietGridDark.surface, onSurface = QuietGridDark.ink,
    surfaceVariant = QuietGridDark.bg, onSurfaceVariant = QuietGridDark.secondary,
    surfaceTint = Color.Transparent, inverseSurface = QuietGridLight.surface,
    inverseOnSurface = QuietGridLight.ink,
    error = QuietGridDark.danger, onError = QuietGridDark.surface,
    errorContainer = QuietGridDark.bg, onErrorContainer = QuietGridDark.danger,
    outline = QuietGridDark.borderStrong, outlineVariant = QuietGridDark.border, scrim = Color.Black,
    surfaceBright = QuietGridDark.surface, surfaceDim = QuietGridDark.bg,
    surfaceContainer = QuietGridDark.surface, surfaceContainerLow = QuietGridDark.bg,
    surfaceContainerLowest = QuietGridDark.bg, surfaceContainerHigh = QuietGridDark.surface,
    surfaceContainerHighest = QuietGridDark.surface,
)
