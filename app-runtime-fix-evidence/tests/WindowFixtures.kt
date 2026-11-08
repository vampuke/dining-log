package android.view.accessibility

import android.graphics.Rect

class AccessibilityWindowInfo(val id: Int, val layer: Int, val type: Int = 1, val rectangles: List<Rect> = emptyList()) {
    companion object { const val TYPE_INPUT_METHOD = 2 }
}
