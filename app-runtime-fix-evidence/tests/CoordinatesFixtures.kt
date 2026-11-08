package androidx.compose.ui.layout

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

data class Size(val width: Int, val height: Int)

class LayoutCoordinates(val full: Rect, val clipped: Rect = full) {
    val size = Size(full.width.toInt(), full.height.toInt())
    var parentCoordinates: LayoutCoordinates? = null
    var parentLayoutCoordinates: LayoutCoordinates? = null
    fun positionInRoot() = full.topLeft
    fun positionInWindow() = full.topLeft
    fun boundsInRoot() = clipped
}
