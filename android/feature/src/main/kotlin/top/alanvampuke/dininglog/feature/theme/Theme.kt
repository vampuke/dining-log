package top.alanvampuke.dininglog.feature.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalQuietGridColors = staticCompositionLocalOf { QuietGridLight }
@Composable
fun QuietGridTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalQuietGridColors provides if (darkTheme) QuietGridDark else QuietGridLight) {
        MaterialTheme(
            colorScheme = if (darkTheme) QuietGridDarkScheme else QuietGridLightScheme,
            typography = QuietGridTypography,
            shapes = QuietGridShapes,
            content = content,
        )
    }
}
