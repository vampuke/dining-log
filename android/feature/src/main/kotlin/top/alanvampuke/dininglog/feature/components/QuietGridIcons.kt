package top.alanvampuke.dininglog.feature.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Bolder rounded strokes echo the reference stickers. Existing selected silhouettes remain distinct.
// Selected variants change their silhouettes as well as their color.
object QuietGridIcons {
    val restaurantOutline = outline("RestaurantOutline") {
        moveTo(3f, 3f); verticalLineTo(8f); horizontalLineTo(7f); verticalLineTo(3f)
        moveTo(5f, 3f); verticalLineTo(20f)
        moveTo(18f, 12f)
        curveTo(18f, 9.8f, 16.2f, 8f, 14f, 8f)
        curveTo(11.8f, 8f, 10f, 9.8f, 10f, 12f)
        curveTo(10f, 14.2f, 11.8f, 16f, 14f, 16f)
        curveTo(16.2f, 16f, 18f, 14.2f, 18f, 12f); close()
        moveTo(21f, 3f); verticalLineTo(20f)
        moveTo(19f, 3f); verticalLineTo(10f); horizontalLineTo(21f)
    }
    val restaurantFilled = solid("RestaurantFilled") {
        moveTo(3f, 3f); horizontalLineTo(4.5f); verticalLineTo(8f); horizontalLineTo(5.5f)
        verticalLineTo(3f); horizontalLineTo(7f); verticalLineTo(10f); horizontalLineTo(6f)
        verticalLineTo(21f); horizontalLineTo(4f); verticalLineTo(10f); horizontalLineTo(3f); close()
        moveTo(18f, 12f)
        curveTo(18f, 9.8f, 16.2f, 8f, 14f, 8f)
        curveTo(11.8f, 8f, 10f, 9.8f, 10f, 12f)
        curveTo(10f, 14.2f, 11.8f, 16f, 14f, 16f)
        curveTo(16.2f, 16f, 18f, 14.2f, 18f, 12f); close()
        moveTo(19f, 3f); horizontalLineTo(22f); verticalLineTo(21f)
        horizontalLineTo(20f); verticalLineTo(12f); horizontalLineTo(19f); close()
    }
    val visitsOutline = outline("VisitsOutline") {
        moveTo(3f, 5f); horizontalLineTo(21f); verticalLineTo(21f); horizontalLineTo(3f); close()
        moveTo(3f, 10f); horizontalLineTo(21f)
        moveTo(7f, 2f); verticalLineTo(7f); moveTo(17f, 2f); verticalLineTo(7f)
        moveTo(8f, 14f); horizontalLineTo(12f); verticalLineTo(17f); horizontalLineTo(8f); close()
    }
    val visitsFilled = solid("VisitsFilled") {
        moveTo(3f, 5f); horizontalLineTo(21f); verticalLineTo(21f); horizontalLineTo(3f); close()
        moveTo(5f, 11f); horizontalLineTo(19f); verticalLineTo(19f); horizontalLineTo(5f); close()
        moveTo(6f, 2f); horizontalLineTo(8f); verticalLineTo(5f); horizontalLineTo(6f); close()
        moveTo(16f, 2f); horizontalLineTo(18f); verticalLineTo(5f); horizontalLineTo(16f); close()
        moveTo(8f, 13f); horizontalLineTo(12f); verticalLineTo(17f); horizontalLineTo(8f); close()
    }
    val personOutline = outline("PersonOutline") {
        moveTo(15.5f, 7f)
        curveTo(15.5f, 5.1f, 13.9f, 3.5f, 12f, 3.5f)
        curveTo(10.1f, 3.5f, 8.5f, 5.1f, 8.5f, 7f)
        curveTo(8.5f, 8.9f, 10.1f, 10.5f, 12f, 10.5f)
        curveTo(13.9f, 10.5f, 15.5f, 8.9f, 15.5f, 7f); close()
        moveTo(4f, 21f); verticalLineTo(18f)
        curveTo(4f, 14f, 20f, 14f, 20f, 18f); verticalLineTo(21f); close()
    }
    val personFilled = solid("PersonFilled") {
        moveTo(16f, 7f)
        curveTo(16f, 4.8f, 14.2f, 3f, 12f, 3f)
        curveTo(9.8f, 3f, 8f, 4.8f, 8f, 7f)
        curveTo(8f, 9.2f, 9.8f, 11f, 12f, 11f)
        curveTo(14.2f, 11f, 16f, 9.2f, 16f, 7f); close()
        moveTo(4f, 21f); verticalLineTo(18f)
        curveTo(4f, 13f, 20f, 13f, 20f, 18f); verticalLineTo(21f); close()
    }
    val search = outline("Search") {
        moveTo(17f, 10f)
        curveTo(17f, 6.1f, 13.9f, 3f, 10f, 3f)
        curveTo(6.1f, 3f, 3f, 6.1f, 3f, 10f)
        curveTo(3f, 13.9f, 6.1f, 17f, 10f, 17f)
        curveTo(13.9f, 17f, 17f, 13.9f, 17f, 10f); close()
        moveTo(15f, 15f); lineTo(21f, 21f)
    }
    val filter = outline("Filter") {
        moveTo(4f, 6f); horizontalLineTo(20f)
        moveTo(7f, 12f); horizontalLineTo(17f)
        moveTo(10f, 18f); horizontalLineTo(14f)
    }
    val more = solid("More") {
        for (y in listOf(5f, 12f, 19f)) {
            moveTo(13.5f, y)
            curveTo(13.5f, y - 2f, 10.5f, y - 2f, 10.5f, y)
            curveTo(10.5f, y + 2f, 13.5f, y + 2f, 13.5f, y); close()
        }
    }
    private fun outline(name: String, draw: PathBuilder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
    }.build()
    private fun solid(name: String, draw: PathBuilder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd, pathBuilder = draw)
    }.build()
}
