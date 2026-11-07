package top.alanvampuke.dininglog.feature.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import top.alanvampuke.dininglog.core.ThemeStyle

val LocalQuietGridColors = staticCompositionLocalOf { QuietGridLight }
// Which visual style is active. Components that change shape between styles
// (cards vs flat rows, chips vs text segments) branch on this; the colour,
// typography and shape tokens above already follow it.
val LocalThemeStyle = staticCompositionLocalOf { ThemeStyle.QUIET_GRID }
val ThemeStyle.isInkPaper: Boolean get() = this == ThemeStyle.INK_PAPER

// The style parameter defaults to the classic QuietGrid so existing call
// sites (instrumentation tests, the debug visual activity) keep rendering
// exactly as before. The app passes the persisted style from MainActivity.
@Composable
fun QuietGridTheme(darkTheme: Boolean = isSystemInDarkTheme(), style: ThemeStyle = ThemeStyle.QUIET_GRID, content: @Composable () -> Unit) {
    val colors = when (style) {
        ThemeStyle.INK_PAPER -> if (darkTheme) InkPaperDark else InkPaperLight
        ThemeStyle.QUIET_GRID -> if (darkTheme) QuietGridDark else QuietGridLight
    }
    val scheme = when (style) {
        ThemeStyle.INK_PAPER -> if (darkTheme) InkPaperDarkScheme else InkPaperLightScheme
        ThemeStyle.QUIET_GRID -> if (darkTheme) QuietGridDarkScheme else QuietGridLightScheme
    }
    CompositionLocalProvider(
        LocalQuietGridColors provides colors,
        LocalThemeStyle provides style,
        LocalGridDimensions provides if (style.isInkPaper) InkPaperDimensions else QuietGridDimensions,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = if (style.isInkPaper) InkPaperTypography else QuietGridTypography,
            shapes = if (style.isInkPaper) InkPaperShapes else QuietGridShapes,
            content = content,
        )
    }
}
