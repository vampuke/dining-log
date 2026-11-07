package top.alanvampuke.dininglog.feature.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Legacy public names remain stable for consumers and instrumentation semantics.
// Pop fills deliberately keep near-black lettering in both themes.
data class QuietGridColors(
    val bg: Color, val surface: Color, val ink: Color, val secondary: Color,
    val tertiary: Color, val border: Color, val accent: Color, val onAccent: Color,
    val danger: Color, val accentInk: Color, val cta: Color, val accentSoft: Color,
    val borderStrong: Color, val gallery: Color,
    val paper: Color, val shadow: Color, val dieCut: Color, val dots: Color,
    val butter: Color, val popRed: Color, val mint: Color, val klein: Color,
    val lilac: Color, val peach: Color, val bubble: Color, val stickerInk: Color,
)
val QuietGridLight = QuietGridColors(
    bg = Color(0xFFFFFDF5), surface = Color(0xFFFFFFFF), ink = Color(0xFF111111),
    secondary = Color(0xFF514B40), tertiary = Color(0xFF5D564A), border = Color(0xFF111111),
    accent = Color(0xFFFFD60A), onAccent = Color(0xFF111111), danger = Color(0xFFA61B27),
    accentInk = Color(0xFF111111), cta = Color(0xFFFFD60A), accentSoft = Color(0xFFFFD60A),
    borderStrong = Color(0xFF111111), gallery = Color(0xFFFAF4E4),
    paper = Color(0xFFFAF4E4), shadow = Color(0xFF111111), dieCut = Color(0xFFFFFFFF), dots = Color(0xFFD8D0C1),
    butter = Color(0xFFFFD60A), popRed = Color(0xFFFF595E), mint = Color(0xFF06D6A0), klein = Color(0xFF3A86FF),
    lilac = Color(0xFF8338EC), peach = Color(0xFFFF9F1C), bubble = Color(0xFFFF70A6), stickerInk = Color(0xFF111111),
)
val QuietGridDark = QuietGridColors(
    bg = Color(0xFF14120E), surface = Color(0xFF24211B), ink = Color(0xFFF5F2E8),
    secondary = Color(0xFFD3CBBB), tertiary = Color(0xFFBCB3A2), border = Color(0xFFF5F2E8),
    accent = Color(0xFFFFD60A), onAccent = Color(0xFF111111), danger = Color(0xFFFF9398),
    accentInk = Color(0xFFF5F2E8), cta = Color(0xFFFFD60A), accentSoft = Color(0xFF393326),
    borderStrong = Color(0xFFF5F2E8), gallery = Color(0xFF14120E),
    paper = Color(0xFF302B23), shadow = Color(0xFFB5A88C), dieCut = Color(0xFFE6DDCA), dots = Color(0xFF343026),
    butter = Color(0xFFFFD60A), popRed = Color(0xFFFF595E), mint = Color(0xFF06D6A0), klein = Color(0xFF3A86FF),
    lilac = Color(0xFF8338EC), peach = Color(0xFFFF9F1C), bubble = Color(0xFFFF70A6), stickerInk = Color(0xFF111111),
)
// Ink & Paper ("纸墨"): the default style. One persimmon accent; the rainbow pop
// fills are retired to warm neutrals so no saturated colour can leak through a
// shared component. Dots match the background, so the dot grid disappears.
val InkPaperLight = QuietGridColors(
    bg = Color(0xFFFAF7F0), surface = Color(0xFFFFFFFF), ink = Color(0xFF1A1712),
    secondary = Color(0xFF6B6257), tertiary = Color(0xFF786E60), border = Color(0xFFE5DDCF),
    accent = Color(0xFFBC3E1D), onAccent = Color(0xFFFFFFFF), danger = Color(0xFFA61B27),
    accentInk = Color(0xFF1A1712), cta = Color(0xFFBC3E1D), accentSoft = Color(0xFFF3EDE0),
    borderStrong = Color(0xFFD8CDB8), gallery = Color(0xFFFAF7F0),
    paper = Color(0xFFF3EDE0), shadow = Color(0x00000000), dieCut = Color(0xFFF3EDE0), dots = Color(0xFFFAF7F0),
    butter = Color(0xFFBC3E1D), popRed = Color(0xFFBC3E1D), mint = Color(0xFF6B6257), klein = Color(0xFF6B6257),
    lilac = Color(0xFF6B6257), peach = Color(0xFFBC3E1D), bubble = Color(0xFF6B6257), stickerInk = Color(0xFF1A1712),
)
val InkPaperDark = QuietGridColors(
    bg = Color(0xFF1C1915), surface = Color(0xFF26211B), ink = Color(0xFFF5F1E8),
    secondary = Color(0xFFB3A996), tertiary = Color(0xFF998F7E), border = Color(0xFF3A332A),
    // Warm dark action text keeps contrast through the focused/pressed state layer;
    // this persimmon also remains readable as small rating text on paper.
    accent = Color(0xFFE56E44), onAccent = Color(0xFF1C1915), danger = Color(0xFFFF9398),
    accentInk = Color(0xFFF5F1E8), cta = Color(0xFFE56E44), accentSoft = Color(0xFF2E2820),
    borderStrong = Color(0xFF4A4234), gallery = Color(0xFF1C1915),
    paper = Color(0xFF2E2820), shadow = Color(0x00000000), dieCut = Color(0xFF2E2820), dots = Color(0xFF1C1915),
    butter = Color(0xFFE56E44), popRed = Color(0xFFE56E44), mint = Color(0xFFB3A996), klein = Color(0xFFB3A996),
    lilac = Color(0xFFB3A996), peach = Color(0xFFE56E44), bubble = Color(0xFFB3A996), stickerInk = Color(0xFF1C1915),
)
private fun roles(c: QuietGridColors, inverse: QuietGridColors) = lightColorScheme(
    primary = c.accentInk, onPrimary = c.surface,
    primaryContainer = c.accentSoft, onPrimaryContainer = c.accentInk,
    inversePrimary = inverse.accentInk,
    secondary = c.secondary, onSecondary = c.surface,
    secondaryContainer = c.paper, onSecondaryContainer = c.ink,
    tertiary = c.tertiary, onTertiary = c.surface,
    tertiaryContainer = c.paper, onTertiaryContainer = c.ink,
    background = c.bg, onBackground = c.ink,
    surface = c.surface, onSurface = c.ink,
    surfaceVariant = c.paper, onSurfaceVariant = c.secondary,
    surfaceTint = Color.Transparent,
    inverseSurface = inverse.surface, inverseOnSurface = inverse.ink,
    error = c.danger, onError = c.bg,
    errorContainer = c.paper, onErrorContainer = c.danger,
    outline = c.borderStrong, outlineVariant = c.border, scrim = Color.Black,
    surfaceBright = c.surface, surfaceDim = c.bg,
    surfaceContainer = c.surface, surfaceContainerLow = c.bg,
    surfaceContainerLowest = c.bg, surfaceContainerHigh = c.surface,
    surfaceContainerHighest = c.surface,
)
val QuietGridLightScheme = roles(QuietGridLight, QuietGridDark)
val QuietGridDarkScheme = roles(QuietGridDark, QuietGridLight)
val InkPaperLightScheme = roles(InkPaperLight, InkPaperDark)
val InkPaperDarkScheme = roles(InkPaperDark, InkPaperLight)
