package top.alanvampuke.dininglog.feature.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.alanvampuke.dininglog.feature.R

// System sans supplies Chinese fallback; the existing bundled mono keeps scores tabular.
val QuietGridSans = FontFamily.SansSerif
val QuietGridDisplay = QuietGridSans
val QuietGridMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.Bold),
)
private fun style(size: Float, height: Float, weight: FontWeight = FontWeight.Normal, mono: Boolean = false) =
    TextStyle(fontFamily = if (mono) QuietGridMono else QuietGridSans,
        fontSize = size.sp, lineHeight = height.sp, fontWeight = weight,
        letterSpacing = 0.sp, fontFeatureSettings = if (mono) "tnum" else null)
val QuietGridTypography = Typography(
    displayLarge = style(36f, 39.6f, FontWeight.Black, true),
    displayMedium = style(28f, 42f, mono = true),
    displaySmall = style(27f, 37.8f, mono = true),
    headlineLarge = style(26f, 35.1f, FontWeight.Black),
    headlineMedium = style(25f, 33.75f, FontWeight.Black),
    headlineSmall = style(18f, 25f, FontWeight.Black),
    titleLarge = style(15f, 22.5f, FontWeight.Black),
    titleMedium = style(15f, 23f, FontWeight.Bold),
    titleSmall = style(13f, 19.5f, FontWeight.Bold),
    bodyLarge = style(15f, 28.5f),
    bodyMedium = style(14f, 21f),
    bodySmall = style(13f, 20.8f),
    labelLarge = style(14f, 22f, FontWeight.Bold),
    labelMedium = style(12f, 18f, FontWeight.Medium),
    labelSmall = style(11f, 16.5f, FontWeight.Medium),
)
// Compact metadata preserves three complete cards on a small first screen.
object QuietGridText {
    val scoreUnit = style(9f, 13.5f, FontWeight.Medium)
    val previewScoreUnit = style(10f, 15f, FontWeight.Medium)
    val caption = style(11f, 16.5f)
    val date = style(11f, 16.5f, mono = true)
    val metadata = style(12f, 18f)
    val input = style(14f, 21f, FontWeight.Medium)
    val paragraph = style(14f, 23f)
    val dishRating = style(16f, 19.2f, mono = true)
    val listRating = style(16f, 22f, FontWeight.Black, true)
    val originalDetailRating = style(29f, 31.9f, FontWeight.Black, true)
    val detailRating = style(36f, 39.6f, FontWeight.Black, true)
}
