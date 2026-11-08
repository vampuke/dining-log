// Narrow platform/Compose CONTROL fixtures for executing the extracted adapter.
// Child order is supplied explicitly in paint order, as the pinned public API requires.
package top.alanvampuke.dininglog.product

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates

class SemanticsPropertyKey<T>(val name: String)
class SemanticsConfiguration {
    private val values = mutableMapOf<SemanticsPropertyKey<*>, Any>()
    fun <T> contains(key: SemanticsPropertyKey<T>) = key in values
    operator fun <T : Any> set(key: SemanticsPropertyKey<T>, value: T) { values[key] = value }
    @Suppress("UNCHECKED_CAST") fun <T> getOrElse(key: SemanticsPropertyKey<T>, default: () -> T): T =
        values[key] as T? ?: default()
    @Suppress("UNCHECKED_CAST") fun <T> getOrNull(key: SemanticsPropertyKey<T>): T? = values[key] as T?
}

object SemanticsActions {
    val OnClick = SemanticsPropertyKey<Unit>("OnClick")
    val OnLongClick = SemanticsPropertyKey<Unit>("OnLongClick")
    val ScrollBy = SemanticsPropertyKey<Unit>("ScrollBy")
    val SetText = SemanticsPropertyKey<Unit>("SetText")
    val SetProgress = SemanticsPropertyKey<Unit>("SetProgress")
    val GetTextLayoutResult = SemanticsPropertyKey<Unit>("GetTextLayoutResult")
    val SetTextSubstitution = SemanticsPropertyKey<Unit>("SetTextSubstitution")
    val ShowTextSubstitution = SemanticsPropertyKey<Unit>("ShowTextSubstitution")
    val ClearTextSubstitution = SemanticsPropertyKey<Unit>("ClearTextSubstitution")
}
data class AnnotatedString(val text: String)
object SemanticsProperties {
    val TestTag = SemanticsPropertyKey<String>("TestTag")
    val Text = SemanticsPropertyKey<List<AnnotatedString>>("Text")
    val InputText = SemanticsPropertyKey<AnnotatedString>("InputText")
    val EditableText = SemanticsPropertyKey<AnnotatedString>("EditableText")
}

class Window(val accessibilityId: Int = 7)
class AccessibilityInfo(val windowId: Int) { fun recycle() {} }
class View(val window: Window, val x: Int = 0, val y: Int = 0,
    val visible: Rect = Rect(0f, 0f, 1000f, 1000f)) {
    var imeVisible = false
    fun getLocationOnScreen(location: IntArray) { location[0] = x; location[1] = y }
    fun createAccessibilityNodeInfo(): AccessibilityInfo? = AccessibilityInfo(window.accessibilityId)
}
class SemanticsOwner(val unmergedRootSemanticsNode: SemanticsNode)
class ViewRootForTest(val view: View, val semanticsOwner: SemanticsOwner)
class LayoutInfo(val semanticsId: Int, val coordinates: LayoutCoordinates, var parentInfo: LayoutInfo?) {
    var isPlaced = true
    var isAttached = true
    var isDeactivated = false
}
class SemanticsNode(val id: Int, val full: Rect, var boundsInRoot: Rect = full,
    var touchBoundsInRoot: Rect = boundsInRoot, val outer: Rect = full,
    val outerClipped: Rect = outer, var parent: SemanticsNode? = null) {
    val config = SemanticsConfiguration()
    var mergingEnabled = false
    lateinit var root: ViewRootForTest
    val children = mutableListOf<SemanticsNode>()
    val positionInRoot get() = full.topLeft
    val size = androidx.compose.ui.layout.Size(full.width.toInt(), full.height.toInt())
    private val innerCoordinates = LayoutCoordinates(full, boundsInRoot)
    private val outerCoordinates = LayoutCoordinates(outer, outerClipped)
    val layoutInfo: LayoutInfo = LayoutInfo(id, innerCoordinates, parent?.layoutInfo)
    init {
        innerCoordinates.parentCoordinates = outerCoordinates
        innerCoordinates.parentLayoutCoordinates = parent?.layoutInfo?.coordinates
        outerCoordinates.parentCoordinates = parent?.layoutInfo?.coordinates
        outerCoordinates.parentLayoutCoordinates = parent?.layoutInfo?.coordinates
    }
}
class SemanticsMatcher(val description: String, val predicate: (SemanticsNode) -> Boolean) {
    fun matches(node: SemanticsNode) = predicate(node)
}
class NodeCollection(val nodes: List<SemanticsNode>) { fun fetchSemanticsNodes() = nodes }
class FixtureCompose(val nodes: List<SemanticsNode>) {
    fun <T> runOnIdle(block: () -> T): T = block()
    fun onAllNodes(matcher: SemanticsMatcher, useUnmergedTree: Boolean): NodeCollection {
        check(useUnmergedTree)
        return NodeCollection(nodes.filter(matcher::matches))
    }
}
object WindowInsetsCompat { object Type { fun ime() = 1 } }
class Insets(val view: View) { fun isVisible(type: Int): Boolean { check(type == 1); return view.imeVisible } }
object ViewCompat { fun getRootWindowInsets(view: View): Insets? = Insets(view) }
class SemanticsNodeInteraction(val node: SemanticsNode) {
    fun assert(matcher: SemanticsMatcher): SemanticsNodeInteraction = apply {
        if (!matcher.matches(node)) throw AssertionError(matcher.description)
    }
}
enum class UpdateStage { DOWNLOADING, VERIFYING }
data class ProgressState(val stage: UpdateStage, val percent: Int?)

data class ObservedCover(val id: Int, val full: Rect, val clipped: Rect, val outer: Rect, val outerClipped: Rect)
data class ObservedFailureControl(val name: String, val targetId: Int, val target: Rect, val clipped: Rect,
    val rootVisible: Rect, val covers: List<ObservedCover>)
