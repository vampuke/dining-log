package top.alanvampuke.dininglog.feature.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val QuietGridShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(14.dp),
)
// Ink & Paper de-cards the layout: no rounded panels, capsule buttons only.
val InkPaperShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)
data class GridDimensions(
    val edge: Dp, val radius: Dp, val buttonRadius: Dp,
    val scoreRadius: Dp, val hairline: Dp, val elevation: Dp,
)
val QuietGridDimensions = GridDimensions(
    edge = 16.dp, radius = 14.dp, buttonRadius = 12.dp,
    scoreRadius = 10.dp, hairline = 2.5.dp, elevation = 0.dp,
)
val InkPaperDimensions = GridDimensions(
    edge = 20.dp, radius = 0.dp, buttonRadius = 999.dp,
    scoreRadius = 0.dp, hairline = 1.dp, elevation = 0.dp,
)
// The theme entry point provides the active set; call sites that must follow
// the style read this instead of the QuietGrid constant above.
val LocalGridDimensions = staticCompositionLocalOf { QuietGridDimensions }
const val colorMotionMillis = 140
