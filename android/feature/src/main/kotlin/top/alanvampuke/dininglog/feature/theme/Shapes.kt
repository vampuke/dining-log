package top.alanvampuke.dininglog.feature.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val QuietGridShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(6.dp),
)
object QuietGridDimensions {
    val edge = 24.dp
    val radius = 4.dp
    val buttonRadius = 6.dp
    val scoreRadius = 3.dp
    val hairline = 1.dp
    val elevation = 0.dp
    const val colorMotionMillis = 140
}
