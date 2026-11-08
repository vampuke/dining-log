// Synthetic CONTROL geometry only; no Android rendering or capture evidence.
package androidx.compose.ui.geometry

data class Offset(val x: Float, val y: Float) {
    operator fun plus(other: Offset) = Offset(x + other.x, y + other.y)
    operator fun minus(other: Offset) = Offset(x - other.x, y - other.y)
    operator fun unaryMinus() = Offset(-x, -y)
}

data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val topLeft get() = Offset(left, top)
    val center get() = Offset((left + right) / 2, (top + bottom) / 2)
    fun contains(point: Offset) = point.x >= left && point.x < right && point.y >= top && point.y < bottom
    fun translate(offset: Offset) = Rect(left + offset.x, top + offset.y, right + offset.x, bottom + offset.y)
}
