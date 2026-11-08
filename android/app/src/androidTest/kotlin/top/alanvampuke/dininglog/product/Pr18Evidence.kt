package top.alanvampuke.dininglog.product

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.view.Surface
import android.view.View
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.*
import top.alanvampuke.dininglog.P9VisualActivity
import top.alanvampuke.dininglog.core.ThemeStyle
import top.alanvampuke.dininglog.feature.components.BackgroundArgb
import top.alanvampuke.dininglog.feature.components.ForegroundArgb
import top.alanvampuke.dininglog.feature.components.NavigationEntryId
import top.alanvampuke.dininglog.feature.theme.*

internal val Pr18RenderedStyle = SemanticsPropertyKey<String>("Pr18RenderedStyle")

internal fun pr18FullBounds(node: SemanticsNode): Rect = Rect(node.positionInRoot.x, node.positionInRoot.y,
    node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height)

internal fun pr18OuterCoordinates(node: SemanticsNode): androidx.compose.ui.layout.LayoutCoordinates {
    var coordinates = node.layoutInfo.coordinates
    val parent = coordinates.parentLayoutCoordinates
    // Public pinned LayoutCoordinates: parentCoordinates traverses modifiers;
    // parentLayoutCoordinates is outside this LayoutNode's outer coordinator.
    while (coordinates.parentCoordinates != null && coordinates.parentCoordinates !== parent) {
        coordinates = requireNotNull(coordinates.parentCoordinates)
    }
    return coordinates
}

internal fun pr18TouchDetails(node: SemanticsNode): String {
    val view = (node.root as ViewRootForTest).view
    val origin = IntArray(2).also { view.getLocationOnScreen(it) }
    val visible = android.graphics.Rect().also { view.getGlobalVisibleRect(it) }
    return "nodeId=${node.id} root=${node.positionInRoot} window=${node.positionInWindow} screen=${node.positionOnScreen} " +
        "size=${node.size} clipped=${node.boundsInRoot} touch=${node.touchBoundsInRoot} " +
        "rootViewScreen=${origin.toList()} rootViewVisible=$visible semantics=${node.config}"
}

internal fun SemanticsNodeInteraction.pr18Click(evidence: Pr18Evidence, name: String): SemanticsNodeInteraction = apply {
    evidence.touch(this, name)
}

internal fun pr18StyleCases(): List<Array<Any>> = listOf(
    arrayOf<Any>(ThemeStyle.INK_PAPER, false), arrayOf<Any>(ThemeStyle.INK_PAPER, true),
    arrayOf<Any>(ThemeStyle.QUIET_GRID, false), arrayOf<Any>(ThemeStyle.QUIET_GRID, true),
)

internal fun pr18Remeasure(reported: TextLayoutResult): TextLayoutResult = with(reported.layoutInput) {
    // Same pinned-Compose 1.8.2 helper as Pr17UiTest. Preserve every overflow,
    // wrapping and maxLines input; only replace the misleading semantics box.
    TextMeasurer(fontFamilyResolver, density, layoutDirection, cacheSize = 0).measure(
        text = text, style = style, placeholders = placeholders, maxLines = maxLines,
        softWrap = softWrap, overflow = overflow,
        constraints = Constraints.fixed(reported.size.width, reported.size.height), skipCache = true,
    )
}

internal fun pr18Contrast(foreground: Int, background: Int): Double {
    fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((argb shr shift) and 255) / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
    val a = luminance(foreground); val b = luminance(background)
    return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
}

internal class Pr18Evidence(
    val compose: AndroidComposeTestRule<ActivityScenarioRule<P9VisualActivity>, P9VisualActivity>,
    val style: ThemeStyle,
    val dark: Boolean,
) {
    val colors: QuietGridColors get() = when (style) {
        ThemeStyle.INK_PAPER -> if (dark) InkPaperDark else InkPaperLight
        ThemeStyle.QUIET_GRID -> if (dark) QuietGridDark else QuietGridLight
    }
    private val originalFont = compose.activity.resources.configuration.fontScale
    private val originalOrientation = compose.activity.requestedOrientation
    private val originalRotation = compose.activity.window.decorView.display.rotation
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val originalAutomationFlags = automation.serviceInfo.flags
    private var content: (@Composable () -> Unit)? = null
    private var installed = false
    private var width by mutableIntStateOf(360)
    private var height by mutableIntStateOf(640)
    var requestedFont = originalFont; private set
    var density = compose.activity.resources.displayMetrics.density; private set
    private var renderedStyle = style
    private var renderedDark = dark
    private val evidenceCase = UUID.randomUUID().toString()
    private var captureStep = 0
    var stateReceipt: (() -> String)? = null
    // PR18_GATE_A_BEGIN
    internal val uiCausalProbeCase get() = evidenceCase
    internal var uiCausalProbe: Pr18UiCausalProbe? = null
    // PR18_GATE_A_END
    fun observedPalette(style: ThemeStyle, dark: Boolean) { renderedStyle = style; renderedDark = dark }
    val actualFont get() = compose.activity.resources.configuration.fontScale
    private val folder get() = ApplicationProvider.getApplicationContext<Context>()
        .getExternalFilesDir("p4-screenshots")!!.apply { mkdirs() }

    fun record(value: String) {
        val line = "style=$renderedStyle dark=$renderedDark requestedFont=$requestedFont actualFont=$actualFont " +
            "api=${Build.VERSION.SDK_INT} density=$density $value"
        File(folder, "pr18-measurements-api${Build.VERSION.SDK_INT}.txt").appendText("$line\n")
        Log.i("DiningP4", line)
    }

    fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).use { it.readBytes().toString(Charsets.UTF_8).trim() }

    fun waitResumed() {
        compose.waitUntil(15_000) {
            try { compose.activityRule.scenario.state == Lifecycle.State.RESUMED } catch (_: Exception) { false }
        }
    }

    private fun attach() {
        waitResumed()
        compose.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            activity.setContent { content?.invoke() }
        }
        installed = true
        compose.waitForIdle()
    }

    fun renderRaw(next: @Composable () -> Unit) {
        content = next
        // A system configuration may have replaced the host between scenarios.
        // Attach to the RESUMED Activity every time, including shared render entry.
        attach()
    }

    fun render(next: @Composable () -> Unit) = renderRaw {
        QuietGridTheme(dark, style = style) {
            val c = LocalQuietGridColors.current
            val renderedStyle = LocalThemeStyle.current
            val native = LocalDensity.current
            SideEffect { density = native.density; observedPalette(renderedStyle, dark) }
            Box(Modifier.requiredSize(width.dp, height.dp).clipToBounds().background(c.bg)
                .testTag("pr18:viewport").semantics {
                    this[Pr18RenderedStyle] = renderedStyle.name
                    this[BackgroundArgb] = c.bg.toArgb()
                }) { next() }
        }
    }

    fun renderProduction(next: @Composable () -> Unit) = renderRaw {
        val native = LocalDensity.current
        SideEffect { density = native.density }
        Box(Modifier.requiredSize(width.dp, height.dp).clipToBounds().testTag("pr18:viewport")) { next() }
    }

    fun renderAdaptiveProduction(next: @Composable () -> Unit) = renderRaw {
        val native = LocalDensity.current
        SideEffect { density = native.density }
        // Keep the actual window/IME height while constraining the input flow to
        // 360dp. No fitted density or synthetic font scale is introduced.
        Box(Modifier.fillMaxHeight().widthIn(max = 360.dp).testTag("pr18:viewport")) { next() }
    }

    fun recreate() {
        compose.activityRule.scenario.recreate()
        waitResumed()
        if (installed) attach()
    }

    fun configure(font: Float, w: Int = 360, h: Int = 640) {
        width = w; height = h; requestedFont = font
        val previous = compose.activity
        if (abs(previous.resources.configuration.fontScale - font) >= .001f) {
            shell("settings put system font_scale $font")
            // Wait for the automatic font recreation before asking the scenario
            // to recreate/reattach; capturing the outgoing owner is a race.
            compose.waitUntil(15_000) {
                try {
                    compose.activity !== previous && compose.activityRule.scenario.state == Lifecycle.State.RESUMED &&
                        abs(compose.activity.resources.configuration.fontScale - font) < .001f
                } catch (_: Exception) { false }
            }
            if (installed) attach()
        } else waitResumed()
        density = compose.activity.resources.displayMetrics.density
        record("configuration resourcesFont=${compose.activity.resources.configuration.fontScale} viewportDp=${w}x$h")
        assertEquals("actual Activity system font", font, actualFont, .001f)
        if (installed) compose.waitForIdle()
    }

    fun landscape(reverse: Boolean = false) {
        val expectedRotation = if (reverse) Surface.ROTATION_270 else Surface.ROTATION_90
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (reverse) ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(15_000) {
            try {
                compose.activityRule.scenario.state == Lifecycle.State.RESUMED &&
                    compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    compose.activity.window.decorView.display.rotation == expectedRotation
            } catch (_: Exception) { false }
        }
        waitResumed()
        width = 640; height = 360
        if (installed) attach()
        record("landscape reverse=$reverse displayRotation=${compose.activity.window.decorView.display.rotation} " +
            "resources=${compose.activity.resources.configuration}")
    }

    fun portrait() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(15_000) {
            try {
                compose.activityRule.scenario.state == Lifecycle.State.RESUMED &&
                    compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                    compose.activity.window.decorView.display.rotation == Surface.ROTATION_0
            } catch (_: Exception) { false }
        }
        waitResumed()
        width = 360; height = 640
        if (installed) attach()
    }

    fun close() {
        // Release real dialogs before restoring configuration; the next legacy
        // test must inherit the system configuration it had before this test.
        try {
            content = {}; attach()
            configure(originalFont)
            val restoreOrientation = when (originalRotation) {
                Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            compose.activityRule.scenario.onActivity { it.requestedOrientation = restoreOrientation }
            compose.waitUntil(15_000) {
                try {
                    compose.activityRule.scenario.state == Lifecycle.State.RESUMED &&
                        compose.activity.window.decorView.display.rotation == originalRotation
                } catch (_: Exception) { false }
            }
            compose.activityRule.scenario.onActivity { it.requestedOrientation = originalOrientation }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalAutomationFlags }
        }
    }

    fun target(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    fun bounds(tag: String, window: Boolean = false): Rect {
        val coordinates = target(tag).fetchSemanticsNode().layoutInfo.coordinates
        val position = if (window) coordinates.positionInWindow() else coordinates.positionInRoot()
        return Rect(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height)
    }

    fun screenBounds(tag: String): Rect {
        val node = target(tag).fetchSemanticsNode()
        return compose.runOnIdle {
            val view = (node.root as ViewRootForTest).view
            val owner = ownerWindow(view)
            check(view.isAttachedToWindow && view.rootView === owner.decorView.rootView) { "Screen bounds owner unavailable" }
            val screen = IntArray(2).also { view.getLocationOnScreen(it) }
            val window = IntArray(2).also { view.getLocationInWindow(it) }
            val position = node.positionInWindow
            val bounds = pr18WindowToScreen(Pr18Rect(position.x, position.y,
                position.x + node.size.width, position.y + node.size.height),
                Pr18Point(screen[0].toFloat(), screen[1].toFloat()), Pr18Point(window[0].toFloat(), window[1].toFloat()))
            Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
    }

    fun taggedBounds(tag: String): Rect {
        // Compose 1.8.2 LayoutInfo.coordinates is the innermost layout, after
        // modifiers. SemanticsNode position/size measure the tagged coordinator
        // (the clickable body for buttons), without viewport clipping.
        val node = target(tag).fetchSemanticsNode()
        val position = node.positionInRoot
        return Rect(position.x, position.y, position.x + node.size.width, position.y + node.size.height)
    }

    fun minimumTarget(tag: String) {
        minimumTarget(target(tag), tag)
    }

    fun minimumTarget(node: SemanticsNodeInteraction, name: String) {
        val semantics = node.fetchSemanticsNode()
        val info = semantics.layoutInfo
        val coordinates = info.coordinates
        // Material's minimumInteractiveComponentSize reserves an actual outer
        // layout around the smaller visual thumb/checkbox. Measure that layout,
        // not boundsInRoot's clipping or automatic minimum-touch semantics.
        val w = info.width / density; val h = info.height / density
        record("target tag=$name measuredLayoutDp=${w}x$h innerPosition=${coordinates.positionInRoot()} " +
            "innerSize=${coordinates.size} semantics=${semantics.boundsInRoot}")
        assertTrue("$name actual target width >=48dp", w >= 47.5f)
        assertTrue("$name actual target height >=48dp", h >= 47.5f)
    }

    fun scroll(list: String, tag: String) {
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
        target(tag).assertIsDisplayed()
        expose(target(tag), tag, list)
        record("reachable list=$list tag=$tag full=${bounds(tag)} visible=${target(tag).fetchSemanticsNode().boundsInRoot}")
    }

    private fun ownerWindow(view: View): Window {
        dialogWindow(view)?.let { return it }
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        return (context as? Activity)?.window ?: error("Cannot identify actual Activity window")
    }

    private data class TouchGeometry(
        val root: View, val window: Window, val full: Rect, val clipped: Rect,
        val windowFull: Rect, val windowClipped: Rect, val point: Offset,
        val screenOrigin: Offset, val visible: Rect, val overlays: List<Pair<String, Rect>>,
        val configuration: String,
    )

    private fun touchGeometry(node: SemanticsNode, point: (SemanticsNode) -> Offset): TouchGeometry {
        val view = (node.root as ViewRootForTest).view
        val window = ownerWindow(view)
        return compose.runOnIdle {
            require(view.isAttachedToWindow && view.rootView === window.decorView.rootView && view.hasWindowFocus()) {
                "Touch target is not in the attached focused owner window"
            }
            val origin = IntArray(2).also { view.getLocationOnScreen(it) }
            val visible = android.graphics.Rect()
            // getGlobalVisibleRect is in the ancestor root's coordinates, not
            // SCREEN. Keep AndroidComposeView-local visibility until the boundary.
            check(view.getLocalVisibleRect(visible)) { "Touch root has no visible local bounds" }
            val screenOrigin = Offset(origin[0].toFloat(), origin[1].toFloat())
            val rootVisible = Rect(visible.left.toFloat(), visible.top.toFloat(), visible.right.toFloat(), visible.bottom.toFloat())
            val position = node.positionInWindow
            TouchGeometry(view, window, pr18FullBounds(node), node.boundsInRoot,
                Rect(position.x, position.y, position.x + node.size.width, position.y + node.size.height),
                node.boundsInWindow, point(node), screenOrigin, rootVisible, emptyList(),
                "font=$actualFont style=$renderedStyle dark=$renderedDark rotation=${view.display.rotation}")
        }
    }

    private fun withOverlays(node: SemanticsNode, geometry: TouchGeometry): TouchGeometry {
        val candidates = compose.onAllNodes(SemanticsMatcher("actionable Compose surfaces") {
            it.config.contains(SemanticsActions.OnClick) || it.config.contains(SemanticsActions.OnLongClick) ||
                it.config.contains(SemanticsActions.ScrollBy) || it.config.contains(SemanticsActions.SetText) ||
                it.config.contains(SemanticsActions.SetProgress)
        }, true).fetchSemanticsNodes()
        val overlays = compose.runOnIdle {
            // Layout ancestry also covers parents omitted by merged semantics.
            val ancestors = generateSequence(node.layoutInfo) { it.parentInfo }.toList()
            val paintRanks = mutableMapOf<Int, Int>()
            fun visit(current: SemanticsNode) {
                val layout = current.layoutInfo
                if (!layout.isAttached || !layout.isPlaced || layout.isDeactivated) return
                paintRanks[current.id] = paintRanks.size
                // Public Compose 1.8.2 children are in paint (inverse hit-test)
                // order, including zIndex/placement and non-semantic wrappers.
                current.children.forEach(::visit)
            }
            node.root?.semanticsOwner?.unmergedRootSemanticsNode?.let(::visit)
            val targetRank = paintRanks[node.id]
            candidates.filter {
                ancestors.none { ancestor -> ancestor === it.layoutInfo } &&
                    ownerWindow((it.root as ViewRootForTest).view) === geometry.window
            }.mapNotNull { overlay ->
                // Coordinates from a different Compose root must first go through screen space.
                val view = (overlay.root as ViewRootForTest).view
                val origin = IntArray(2).also { view.getLocationOnScreen(it) }
                val outer = pr18OuterCoordinates(overlay)
                val position = outer.positionInRoot()
                val full = Rect(position.x, position.y, position.x + outer.size.width, position.y + outer.size.height)
                val windowPosition = outer.positionInWindow()
                val label = "node-${overlay.id}:${overlay.config.getOrElse(SemanticsProperties.TestTag) { "untagged" }}"
                val rank = if (overlay.root === node.root) paintRanks[overlay.id] else null
                val order = when {
                    rank == null || targetRank == null -> "unknown"
                    rank > targetRank -> "above"
                    else -> "below"
                }
                // This is the clipped input coordinator, with Compose's actual
                // minimum-touch extent for clicks, rather than outer padding.
                val input = overlay.touchBoundsInRoot
                record("overlay tag=$label owner=${System.identityHashCode(geometry.window)} " +
                    "root=${System.identityHashCode(view)} order=$order paintRank=$rank targetPaintRank=$targetRank " +
                    "layoutAncestors=${generateSequence(overlay.layoutInfo) { it.parentInfo }.map { it.semanticsId }.toList()} " +
                    "outerFull=$full outerClipped=${outer.boundsInRoot()} inputClipped=$input " +
                    "windowPosition=$windowPosition size=${outer.size} semanticFull=${pr18FullBounds(overlay)} semanticClipped=${overlay.boundsInRoot}")
                if (order == "below") return@mapNotNull null
                // Unrelated roots or unavailable ranks remain conservative
                // obstructions; no tag, action or independently clickable child is exempt.
                val obstruction = if (order == "unknown") "$label:paint-order-unknown" else label
                input.takeIf { it.width > 0 && it.height > 0 }?.let { bounds ->
                    obstruction to bounds.translate(Offset(origin[0].toFloat(), origin[1].toFloat()) - geometry.screenOrigin)
                }
            }
        }.toMutableList()
        val windows = interactiveWindows()
        val ownerId = compose.runOnIdle {
            val info = geometry.root.createAccessibilityNodeInfo()
            try { requireNotNull(info).windowId } finally { info?.recycle() }
        }
        val owner = windows.singleOrNull { it.id == ownerId } ?: error("Screen owner window availability is unknown")
        for (window in windows.filter { it.id != ownerId && it.layer > owner.layer && it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
            for ((index, bounds) in windowRectangles(window).withIndex()) overlays += "window-${window.id}-$index" to
                Rect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat()).translate(-geometry.screenOrigin)
        }
        val keyboards = keyboardRectangles(windows)
        for ((index, keyboard) in keyboards.withIndex()) overlays += "IME-screen-$index" to Rect(keyboard.left.toFloat(), keyboard.top.toFloat(),
            keyboard.right.toFloat(), keyboard.bottom.toFloat()).translate(-geometry.screenOrigin)
        val imeVisible = compose.runOnIdle {
            requireNotNull(ViewCompat.getRootWindowInsets(geometry.root)) { "Touch owner IME insets unavailable" }
                .isVisible(WindowInsetsCompat.Type.ime())
        }
        check(!imeVisible || keyboards.isNotEmpty()) { "IME visible but its actual screen geometry is unknown" }
        return geometry.copy(overlays = overlays)
    }

    private fun logTouchGeometry(name: String, g: TouchGeometry) {
        record("touch-geometry name=$name owner=${System.identityHashCode(g.window)} root=${System.identityHashCode(g.root)} " +
            "full=${g.full} clipped=${g.clipped} windowFull=${g.windowFull} windowClipped=${g.windowClipped} " +
            "pointRoot=${g.point} pointWindow=${g.point + g.windowFull.topLeft - g.full.topLeft} " +
            "pointScreen=${g.point + g.screenOrigin} rootVisible=${g.visible} overlays=${g.overlays}")
    }

    // ScrollBy moves actual layout; OnClick is never invoked through semantics.
    // Compose 1.8.2 minimal ScrollTo does not account for same-window overlays.
    private fun expose(node: SemanticsNodeInteraction, name: String, list: String?,
        horizontal: Boolean = false,
        point: (SemanticsNode) -> Offset = { pr18FullBounds(it).center }): TouchGeometry {
        val deadline = System.nanoTime() + 15_000_000_000L
        var stable: TouchGeometry? = null
        while (System.nanoTime() < deadline) {
            compose.waitForIdle()
            val semantics = node.fetchSemanticsNode()
            val g = withOverlays(semantics, touchGeometry(semantics, point))
            logTouchGeometry(name, g)
            if (horizontal) {
                val xScroller = generateSequence(semantics.parent) { it.parent }.firstOrNull {
                    it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) && it.config.contains(SemanticsActions.ScrollBy)
                } ?: error("Nested touch $name: no horizontal scroll ancestor")
                check(xScroller.root === semantics.root) { "Horizontal ancestor belongs to a different root" }
                // Its Y bounds can be entirely clipped before the outer vertical
                // scroll moves. Measure the actual horizontal viewport in X.
                val xBounds = pr18FullBounds(xScroller)
                val left = maxOf(xBounds.left, g.visible.left)
                val right = minOf(xBounds.right, g.visible.right)
                check(right > left) { "Nested touch $name: no horizontal viewport" }
                val half = minOf(g.full.width / 2, (right - left) / 2)
                val dx = g.point.x - g.point.x.coerceIn(left + half, right - half)
                if (dx != 0f) {
                    val range = xScroller.config[SemanticsProperties.HorizontalScrollAxisRange]
                    val before = compose.runOnIdle { range.value() }
                    var delta = if (range.reverseScrolling) -dx else dx
                    if (xScroller.layoutInfo.layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) delta = -delta
                    record("expose-scroll name=$name horizontalAncestor=${xScroller.id} full=$xBounds " +
                        "clipped=${xScroller.boundsInRoot} viewportX=$left..$right value=$before max=${range.maxValue()} deltaX=$delta")
                    compose.onNode(SemanticsMatcher("measured horizontal ancestor ${xScroller.id}") { it.id == xScroller.id }, true)
                        .performSemanticsAction(SemanticsActions.ScrollBy) { check(it(delta, 0f)) { "Horizontal ScrollBy rejected $name" } }
                    compose.waitForIdle()
                    check(compose.runOnIdle { range.value() } != before || pr18FullBounds(node.fetchSemanticsNode()) != g.full) {
                        "Unreachable touch $name at true horizontal scroll limit: value=$before max=${range.maxValue()}"
                    }
                    stable = null
                    continue
                }
            }
            val exposed = g.full.contains(g.point) && g.clipped.contains(g.point) && g.visible.contains(g.point) &&
                g.overlays.none { it.second.contains(g.point) }
            if (exposed && stable == g) return g
            if (exposed) { stable = g; continue }
            stable = null
            val covering = g.overlays.filter { it.second.contains(g.point) }.map { it.first }
            val obstruction = if (covering.isNotEmpty()) "occluded by $covering" else "hidden/clipped target"
            val scroller = generateSequence(semantics.parent) { it.parent }.firstOrNull {
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange) && it.config.contains(SemanticsActions.ScrollBy)
            } ?: error("Unreachable touch $name: $obstruction; no vertical scroll ancestor")
            if (list != null) check(scroller.config.getOrElse(SemanticsProperties.TestTag) { "" } == list) {
                "Wrong vertical scroll ancestor for $name: expected $list, actual ${scroller.config}"
            }
            check(scroller.root === semantics.root) { "Scroll ancestor belongs to a different root" }
            val viewport = scroller.boundsInRoot.intersect(g.visible)
            var bands = listOf(viewport.top to viewport.bottom)
            for ((_, overlay) in g.overlays.filter { g.point.x >= it.second.left && g.point.x < it.second.right }) {
                bands = bands.flatMap { (top, bottom) ->
                    if (overlay.bottom <= top || overlay.top >= bottom) listOf(top to bottom)
                    else listOf(top to minOf(bottom, overlay.top), maxOf(top, overlay.bottom) to bottom)
                }.filter { it.second > it.first }
            }
            check(g.point.x >= viewport.left && g.point.x < viewport.right && bands.isNotEmpty()) {
                "Unreachable touch $name: no exposed vertical band at actual touch X"
            }
            // Fit the full target when possible; otherwise expose its requested physical point.
            val band = bands.maxBy { it.second - it.first }
            val half = minOf(g.full.height / 2, (band.second - band.first) / 2)
            val desiredY = g.point.y.coerceIn(band.first + half, band.second - half)
            val dy = g.point.y - desiredY
            check(dy != 0f) { "Unreachable touch $name: clipping/ownership cannot be resolved by vertical scrolling" }
            val range = scroller.config[SemanticsProperties.VerticalScrollAxisRange]
            val before = compose.runOnIdle { range.value() }
            val delta = if (range.reverseScrolling) -dy else dy
            record("expose-scroll name=$name ancestor=${scroller.id} full=${pr18FullBounds(scroller)} " +
                "clipped=${scroller.boundsInRoot} value=$before max=${range.maxValue()} deltaY=$delta")
            compose.onNode(SemanticsMatcher("measured vertical ancestor ${scroller.id}") { it.id == scroller.id }, true)
                .performSemanticsAction(SemanticsActions.ScrollBy) { check(it(0f, delta)) { "ScrollBy rejected $name" } }
            compose.waitForIdle()
            val after = node.fetchSemanticsNode()
            val moved = compose.runOnIdle { range.value() } != before || pr18FullBounds(after) != g.full
            check(moved) { "Unreachable touch $name at true vertical scroll limit: value=$before max=${range.maxValue()}" }
        }
        error("Touch $name did not become stable and exposed within 15000ms")
    }

    fun touch(node: SemanticsNodeInteraction, name: String, list: String? = null,
        horizontal: Boolean = false,
        point: (SemanticsNode) -> Offset = { pr18FullBounds(it).center }): Pr18TouchReceipt {
        val geometry = expose(node, name, list, horizontal, point)
        touchReceipt(node, name)
        val current = withOverlays(node.fetchSemanticsNode(), touchGeometry(node.fetchSemanticsNode(), point))
        check(current == geometry) { "Touch geometry changed before injection: $name" }
        val source = screenTarget(node.fetchSemanticsNode(), current, Pr18TouchMode.SCREEN_INPUT)
        val screenPoint = pr18RequireScreenTarget(source,
            current.overlays.map { (_, rect) -> rect.translate(current.screenOrigin).toEvidenceRect() })
        val latest = withOverlays(node.fetchSemanticsNode(), touchGeometry(node.fetchSemanticsNode(), point))
        check(latest == current) { "Screen target provenance changed before physical injection: $name" }
        record("physical-touch name=$name case=$evidenceCase target=$source")
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int): Boolean {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, screenPoint.x, screenPoint.y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            return try { automation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        var down = false; var up = false
        val receipt = try {
            down = inject(MotionEvent.ACTION_DOWN)
            check(down) { "Physical screen DOWN injection failed: $name" }
            up = inject(MotionEvent.ACTION_UP)
            Pr18TouchReceipt(source, down, up)
        } finally {
            if (down && !up) inject(MotionEvent.ACTION_CANCEL)
            record("physical-touch-result name=$name downAccepted=$down upAccepted=$up")
        }
        compose.waitForIdle()
        navigationReceipt("after-$name")
        return receipt
    }

    private fun Rect.toEvidenceRect() = Pr18Rect(left, top, right, bottom)

    private fun screenTarget(node: SemanticsNode, geometry: TouchGeometry, mode: Pr18TouchMode): Pr18ScreenTarget {
        val windows = interactiveWindows()
        // Accessibility IPC must stay off the application's main thread.
        val availableRoots = windows.associate { window ->
            val root = window.root
            try { window.id to (root != null && root.isVisibleToUser) } finally { root?.recycle() }
        }
        return compose.runOnIdle {
            val view = geometry.root
            val provider = requireNotNull(view.accessibilityNodeProvider) { "Screen target accessibility provider unavailable" }
            val info = requireNotNull(provider.createAccessibilityNodeInfo(node.id)) { "Screen target accessibility node hidden/covered/unavailable" }
            try {
                val key = "androidx.compose.ui.semantics.id"
                check(key in info.availableExtraData) { "Pinned Compose target provenance is unavailable" }
                provider.addExtraDataToAccessibilityNodeInfo(node.id, info, key, Bundle())
                val actualBounds = android.graphics.Rect().also { info.getBoundsInScreen(it) }
                val observed = windows.singleOrNull { it.id == info.windowId }
                check(observed != null && availableRoots[observed.id] == true) { "Screen target owner window availability is unknown" }
                val rootOnScreen = IntArray(2).also { view.getLocationOnScreen(it) }
                val rootInWindow = IntArray(2).also { view.getLocationInWindow(it) }
                val fullScreen = pr18WindowToScreen(geometry.windowFull.toEvidenceRect(),
                    Pr18Point(rootOnScreen[0].toFloat(), rootOnScreen[1].toFloat()),
                    Pr18Point(rootInWindow[0].toFloat(), rootInWindow[1].toFloat()))
                val position = node.positionOnScreen
                Pr18ScreenTarget(node.id, info.extras.getInt(key, Int.MIN_VALUE),
                    System.identityHashCode(geometry.window).toString(), System.identityHashCode(ownerWindow(view)).toString(),
                    view.rootView === geometry.window.decorView.rootView && view.windowToken != null &&
                        view.windowToken === geometry.window.decorView.windowToken,
                    view.isAttachedToWindow, view.isShown, view.hasWindowFocus(), info.windowId, observed!!.id,
                    info.windowId, info.isVisibleToUser, fullScreen,
                    Pr18Rect(position.x, position.y, position.x + node.size.width, position.y + node.size.height),
                    geometry.clipped.translate(geometry.screenOrigin).toEvidenceRect(),
                    pr18LocalToScreen(geometry.visible.toEvidenceRect(), Pr18Point(geometry.screenOrigin.x, geometry.screenOrigin.y)),
                    Pr18Rect(actualBounds.left.toFloat(), actualBounds.top.toFloat(), actualBounds.right.toFloat(), actualBounds.bottom.toFloat()),
                    Pr18Point(geometry.point.x + geometry.screenOrigin.x, geometry.point.y + geometry.screenOrigin.y),
                    requireNotNull(view.display).displayId, actualFont, renderedStyle.name, renderedDark, mode)
            } finally { info.recycle() }
        }
    }

    fun touchReceipt(node: SemanticsNodeInteraction, name: String) {
        record("before-touch name=$name ${pr18TouchDetails(node.fetchSemanticsNode())}")
        navigationReceipt("before-$name")
    }

    fun navigationReceipt(stage: String) {
        val entries = compose.onAllNodesWithTag("product-navigation", true).fetchSemanticsNodes()
            .map { it.config.getOrElse(NavigationEntryId) { "missing-entry" } }
        val screens = compose.onAllNodes(SemanticsMatcher("destination tags") {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("screen:")
        }, true).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
        record("navigation stage=$stage entries=$entries screens=$screens")
        stateReceipt?.let { record("flow-state stage=$stage ${it()}") }
    }

    fun tap(list: String, tag: String) {
        scroll(list, tag); minimumTarget(tag)
        touch(target(tag), tag, list)
    }

    fun layouts(node: SemanticsNodeInteraction): List<TextLayoutResult> {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results
    }

    fun textFits(node: SemanticsNodeInteraction, allowEllipsis: Boolean = false) {
        val results = layouts(node)
        record("text-results count=${results.size} semantics=${node.fetchSemanticsNode().config}")
        assertTrue("text layout exposed", results.isNotEmpty())
        for (reported in results) {
            val measured = pr18Remeasure(reported)
            val font = reported.layoutInput.density.fontScale
            record("text window=${if (hasAnyAncestor(isDialog()).matches(node.fetchSemanticsNode())) "dialog" else "host"} " +
                "layoutFont=$font reportedSize=${reported.size} constraints=${reported.layoutInput.constraints} " +
                "reportedOverflow=${reported.hasVisualOverflow} measuredSize=${measured.size} " +
                "widthOverflow=${measured.didOverflowWidth} heightOverflow=${measured.didOverflowHeight} " +
                "maxLines=${reported.layoutInput.maxLines} value=${reported.layoutInput.text}")
            assertEquals("TextLayoutResult uses real system font", actualFont, font, .001f)
            if (!allowEllipsis) assertFalse("rendered text overflows: ${measured.layoutInput.text}", measured.hasVisualOverflow)
            else assertFalse("ellipsis must not clip height", measured.didOverflowHeight &&
                measured.lineCount < reported.layoutInput.maxLines)
        }
    }

    fun visibleTextFits(allowListEllipsis: Boolean = false) {
        compose.waitForIdle()
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), true)
        for (index in nodes.fetchSemanticsNodes().indices) {
            if (nodes[index].isDisplayed()) {
                val tag = nodes[index].fetchSemanticsNode().config.getOrElse(SemanticsProperties.TestTag) { "" }
                val listEllipsis = allowListEllipsis && actualFont <= 1.3f &&
                    (tag.startsWith("record-title:") || tag.startsWith("record-summary:") || tag.startsWith("matches:"))
                textFits(nodes[index], listEllipsis)
            }
        }
    }

    fun assertRendered(screenTag: String? = null, titleTag: String? = "page-title") {
        val expected = when (style) {
            ThemeStyle.INK_PAPER -> if (dark) 0xFF1C1915.toInt() else 0xFFFAF7F0.toInt()
            ThemeStyle.QUIET_GRID -> if (dark) 0xFF14120E.toInt() else 0xFFFFFDF5.toInt()
        }
        val probe = target("pr18:viewport").fetchSemanticsNode().config
        record("renderedStyle=${probe[Pr18RenderedStyle]} paletteBg=${probe[BackgroundArgb]} expectedBg=$expected")
        assertEquals(style.name, probe[Pr18RenderedStyle])
        assertEquals(expected, probe[BackgroundArgb])
        if (screenTag != null) target(screenTag).assert(SemanticsMatcher.expectValue(BackgroundArgb, expected))
        if (titleTag != null) target(titleTag).assert(SemanticsMatcher.expectValue(ForegroundArgb, colors.ink.toArgb()))
    }

    private fun dialogWindow(view: View): Window? {
        if (view is DialogWindowProvider) return view.window
        return (view.parent as? View)?.let(::dialogWindow)
    }

    private fun evidenceWindows(): List<Window> {
        val dialogs = compose.onAllNodes(isDialog(), true).fetchSemanticsNodes().map { node ->
            requireNotNull(dialogWindow((node.root as ViewRootForTest).view)) {
                "Cannot identify the actual dialog window for evidence"
            }
        }
        return (listOf(compose.activity.window) + dialogs).distinct()
    }

    fun probeProtected(name: String): Pr18CaptureReceipt {
        compose.waitForIdle()
        val windows = evidenceWindows()
        compose.runOnIdle {
            windows.forEachIndexed { index, window ->
                val flags = window.attributes.flags
                record("protected-window evidence=$name index=$index flags=$flags")
                assertTrue("protected evidence retains FLAG_SECURE on host and every dialog",
                    flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        }
        visibleTextFits()
        val roots = compose.onAllNodes(isRoot(), true)
        for (index in roots.fetchSemanticsNodes().indices) {
            record("protected-semantics evidence=$name root=$index ${roots[index].printToString()}")
        }
        return probeWindows(name, protected = true, includeDialogs = true)
    }

    fun saveProtected(name: String) { probeProtected(name).requireValid() }

    private fun captureFile(name: String, owner: Int): File {
        val method = Thread.currentThread().stackTrace.firstOrNull { frame ->
            frame.className.startsWith("top.alanvampuke.dininglog.product.Pr18") && frame.className.endsWith("Test") &&
                Class.forName(frame.className).methods.any {
                    it.name == frame.methodName && it.isAnnotationPresent(org.junit.Test::class.java)
                }
        }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" }
            ?: error("Capture requires actual named test provenance")
        val filename = "pr18-case$evidenceCase-step${++captureStep}-$name-owner$owner-" +
            "${renderedStyle.name.lowercase()}-${if (renderedDark) "dark" else "light"}-actualFont$actualFont-api${Build.VERSION.SDK_INT}.png"
        val methodFolder = File(folder, method).apply { check(isDirectory || mkdirs()) { "Capture method directory unavailable" } }
        record("capture-provenance method=$method case=$evidenceCase step=$captureStep name=$name file=$method/$filename")
        return File(methodFolder, filename)
    }

    internal fun observeCaptureSource(source: Pr18CaptureSource, node: SemanticsNode, window: Window): Pr18CaptureSource = compose.runOnIdle {
        val view = (node.root as ViewRootForTest).view
        check(view.isAttachedToWindow && view.rootView === window.decorView.rootView && ownerWindow(view) === window) {
            "Capture source owner/root mismatch"
        }
        val screenOrigin = IntArray(2).also { view.getLocationOnScreen(it) }
        val windowOrigin = IntArray(2).also { view.getLocationInWindow(it) }
        source.copy(owner = System.identityHashCode(window).toString(), root = System.identityHashCode(view).toString(),
            token = System.identityHashCode(requireNotNull(view.windowToken)).toString(), api = Build.VERSION.SDK_INT,
            requestedFont = requestedFont, actualFont = actualFont, density = density, style = renderedStyle.name, dark = renderedDark,
            geometry = "fullRoot=${pr18FullBounds(node)} clippedRoot=${node.boundsInRoot} fullWindow=${node.positionInWindow}/${node.size} " +
                "clippedWindow=${node.boundsInWindow} positionScreen=${node.positionOnScreen} screenOrigin=${screenOrigin.toList()} " +
                "windowOrigin=${windowOrigin.toList()} decor=${window.decorView.width}x${window.decorView.height}",
            flagsBefore = window.attributes.flags, secureBefore = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    private fun captureAttempt(name: String, owner: Int, node: SemanticsNode, window: Window,
        method: Pr18CaptureMethod): Pr18CaptureAttempt {
        val file = captureFile(name, owner)
        val source = Pr18CaptureSource(file.parentFile!!.name, evidenceCase, captureStep, name, method,
            "", "", "", 0, 0f, 0f, 0f, "", false, "", 0, false)
        return Pr18CaptureAttempt(file, observeCaptureSource(source, node, window))
    }

    internal fun probeWindows(name: String, protected: Boolean, includeDialogs: Boolean): Pr18CaptureReceipt {
        val windows = evidenceWindows()
        val flagsBefore = compose.runOnIdle { windows.map { it.attributes.flags } }
        val nodes = pr18ProductCaptureOwners(target("pr18:viewport").fetchSemanticsNode(),
            if (includeDialogs) compose.onAllNodes(isDialog(), true).fetchSemanticsNodes() else emptyList())
        val results = nodes.mapIndexed { index, node ->
            val view = (node.root as ViewRootForTest).view
            val window = ownerWindow(view)
            val capturedColors = when (renderedStyle) {
                ThemeStyle.INK_PAPER -> if (renderedDark) InkPaperDark else InkPaperLight
                ThemeStyle.QUIET_GRID -> if (renderedDark) QuietGridDark else QuietGridLight
            }
            pr18OwnWindowCapture(this, node, window, captureAttempt(name, index, node, window, Pr18CaptureMethod.WINDOW_PIXEL_COPY), protected,
                capturedColors.bg.toArgb(), capturedColors.surface.toArgb(), capturedColors.ink.toArgb(),
                setOf(capturedColors.ink, capturedColors.secondary, capturedColors.tertiary, capturedColors.accent,
                    capturedColors.danger, capturedColors.onAccent, capturedColors.stickerInk).map { it.toArgb() }.toSet())
        }
        compose.runOnIdle {
            windows.forEachIndexed { index, window ->
                val after = window.attributes.flags
                record("capture-context-owner index=$index owner=${System.identityHashCode(window)} " +
                    "flagsBefore=${flagsBefore[index]} flagsAfter=$after productCapture=${nodes.any {
                        ownerWindow((it.root as ViewRootForTest).view) === window }}")
                assertEquals("host and every dialog retain capture protection",
                    flagsBefore[index] and WindowManager.LayoutParams.FLAG_SECURE,
                    after and WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        return Pr18CaptureReceipt(results.mapNotNull { it.capture }, results.mapNotNull { it.failure })
    }

    fun save(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val windows = evidenceWindows()
        compose.runOnIdle {
            windows.forEach { window ->
                assertEquals("normal screenshot evidence requires an unprotected window; use explicit protected evidence",
                    0, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        if (dialog) probeWindows(name, protected = false, includeDialogs = true).requireValid()
        else {
            val viewport = target("pr18:viewport")
            val node = viewport.fetchSemanticsNode()
            val view = (node.root as ViewRootForTest).view
            val window = ownerWindow(view)
            val bounds = node.boundsInRoot
            compose.runOnIdle {
                check(window === compose.activity.window && view.isAttachedToWindow && view.rootView === window.decorView.rootView) {
                    "Normal Activity capture root/owner mismatch"
                }
            }
            val attempt = captureAttempt(name, 0, node, window, Pr18CaptureMethod.ACTIVITY_IMAGE)
            val bitmap = viewport.captureToImage().asAndroidBitmap()
            val settled = viewport.fetchSemanticsNode()
            assertTrue("normal capture retains the same owner root", node.root === settled.root)
            assertEquals("normal capture retains actual geometry", bounds, settled.boundsInRoot)
            val capturedColors = when (renderedStyle) {
                ThemeStyle.INK_PAPER -> if (renderedDark) InkPaperDark else InkPaperLight
                ThemeStyle.QUIET_GRID -> if (renderedDark) QuietGridDark else QuietGridLight
            }
            val pixels = pr18ValidateActivityBitmap(bitmap, bounds.right.roundToInt() - bounds.left.roundToInt(),
                bounds.bottom.roundToInt() - bounds.top.roundToInt(), capturedColors.bg.toArgb(),
                capturedColors.surface.toArgb(), capturedColors.ink.toArgb(), setOf(capturedColors.ink, capturedColors.secondary,
                    capturedColors.tertiary, capturedColors.accent, capturedColors.danger,
                    capturedColors.onAccent, capturedColors.stickerInk).map { it.toArgb() }.toSet(), ::record)
            val flagsAfter = compose.runOnIdle {
                assertEquals("normal capture owner remains unprotected", 0,
                    window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                window.attributes.flags
            }
            assertEquals("Activity capture retains immutable source/provenance", attempt.source,
                observeCaptureSource(attempt.source, settled, window))
            try {
                val provenance = Pr18CaptureProvenance(attempt.source, Pr18CaptureStatus.NORMAL_PNG,
                    flagsAfter, false, null, "Compose captureToImage bounds=$bounds")
                Pr18CaptureReceipt(listOf(pr18PersistCapturedPng(attempt, provenance, bitmap, pixels, ::record)), emptyList()).requireValid()
            } finally { bitmap.recycle() }
        }
    }

    fun diagnose(name: String, failure: Throwable) {
        // PR18_GATE_A_BEGIN
        uiCausalProbe?.failure(name)
        // PR18_GATE_A_END
        record("FAIL case=$name type=${failure.javaClass.name} message=${failure.message}")
        runCatching {
            if (compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                saveProtected("failure-$name")
            else save("failure-$name", dialog = true)
        }.onFailure { record("evidence-failure case=$name type=${it.javaClass.name} message=${it.message}") }
        runCatching {
            navigationReceipt("failure-$name")
            recordKeyboardWindows("failure-$name")
            val roots = compose.onAllNodes(isRoot(), true)
            for (index in roots.fetchSemanticsNodes().indices) record("semantics root=$index ${roots[index].printToString()}")
        }.onFailure { record("diagnostics-failure case=$name type=${it.javaClass.name} message=${it.message}") }
    }

    fun hideKeyboard() {
        val focusedViews = compose.onAllNodes(isFocused(), true).fetchSemanticsNodes()
            .map { (it.root as ViewRootForTest).view }.distinct()
        val views = focusedViews.ifEmpty { listOf(compose.activity.window.decorView) }
        compose.runOnIdle {
            views.forEach { view ->
                val focused = view.findFocus() ?: view
                val window = dialogWindow(view) ?: compose.activity.window
                record("hide-IME owner=${if (dialogWindow(view) != null) "dialog" else "host"} " +
                    "focused=${view.findFocus() != null} tokenMatchesHost=${focused.windowToken == compose.activity.window.decorView.windowToken}")
                (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(focused.windowToken, 0)
                androidx.core.view.WindowInsetsControllerCompat(window, focused).hide(WindowInsetsCompat.Type.ime())
            }
        }
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        recordKeyboardWindows("after-hide-request")
        compose.waitUntil(15_000) {
            val visibleInsets = compose.runOnIdle {
                views.any { view ->
                    requireNotNull(ViewCompat.getRootWindowInsets(view)) { "IME visibility insets unavailable" }
                        .isVisible(WindowInsetsCompat.Type.ime())
                }
            }
            !visibleInsets && keyboardBounds() == null
        }
        recordKeyboardWindows("hidden")
        compose.waitForIdle()
    }

    private fun recordKeyboardWindows(stage: String) {
        compose.onAllNodes(isRoot(), true).fetchSemanticsNodes().forEach { node ->
            val view = (node.root as ViewRootForTest).view
            compose.runOnIdle {
                val owner = ownerWindow(view)
                val insets = ViewCompat.getRootWindowInsets(view)
                val screen = IntArray(2).also { view.getLocationOnScreen(it) }
                val window = IntArray(2).also { view.getLocationInWindow(it) }
                record("IME-owner stage=$stage owner=${System.identityHashCode(owner)} root=${System.identityHashCode(view)} " +
                    "flags=${owner.attributes.flags} full=${pr18FullBounds(node)} clipped=${node.boundsInRoot} " +
                    "windowClipped=${node.boundsInWindow} screenOrigin=${screen.toList()} windowOrigin=${window.toList()} " +
                    "focused=${view.findFocus()} windowFocus=${view.hasWindowFocus()} " +
                    "tokenMatchesOwner=${view.windowToken == owner.decorView.windowToken} " +
                    "imeVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())} imeInsets=${insets?.getInsets(WindowInsetsCompat.Type.ime())}")
            }
        }
        automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.forEach { window ->
            val bounds = android.graphics.Rect().also { window.getBoundsInScreen(it) }
            record("IME-window stage=$stage bounds=$bounds rootVisible=${window.root?.isVisibleToUser} " +
                "active=${window.isActive} focused=${window.isFocused}")
        }
    }

    private fun interactiveWindows(): List<AccessibilityWindowInfo> {
        automation.serviceInfo = requireNotNull(automation.serviceInfo) { "Physical screen automation is unavailable" }.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        return automation.windows.also { check(it.isNotEmpty()) { "Physical screen window availability is unknown" } }
    }

    fun keyboardRectangles(): List<android.graphics.Rect> = keyboardRectangles(interactiveWindows())

    private fun keyboardRectangles(windows: List<AccessibilityWindowInfo>): List<android.graphics.Rect> {
        val rectangles = windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .flatMap(::windowRectangles)
        return pr18ImeRectangles(rectangles.map { Pr18Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) })
            .map { android.graphics.Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }
    }

    // Legacy callers only need an aggregate for presence/diagnostics. Input and
    // the original IME acceptance use every individual observed screen rectangle.
    fun keyboardBounds(): android.graphics.Rect? = keyboardRectangles().takeIf { it.isNotEmpty() }?.let { rectangles ->
        android.graphics.Rect().apply { rectangles.forEach { union(it) } }
    }

    private fun windowRectangles(window: AccessibilityWindowInfo): List<android.graphics.Rect> {
        if (Build.VERSION.SDK_INT >= 30) {
            val region = android.graphics.Region().also { window.getRegionInScreen(it) }
            val iterator = android.graphics.RegionIterator(region)
            val next = android.graphics.Rect()
            val rectangles = mutableListOf<android.graphics.Rect>()
            while (iterator.next(next)) rectangles += android.graphics.Rect(next)
            return rectangles
        }
        return listOfNotNull(android.graphics.Rect().also { window.getBoundsInScreen(it) }.takeIf { !it.isEmpty })
    }
}
