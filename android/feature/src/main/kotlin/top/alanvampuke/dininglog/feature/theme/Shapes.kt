package top.alanvampuke.dininglog.feature.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val QuietGridShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(14.dp),
)
object QuietGridDimensions {
    val edge = 16.dp
    val radius = 14.dp
    val buttonRadius = 12.dp
    val scoreRadius = 10.dp
    val hairline = 2.5.dp
    val elevation = 0.dp
    const val colorMotionMillis = 140
}
