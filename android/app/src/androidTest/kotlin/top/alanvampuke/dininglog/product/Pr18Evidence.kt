package top.alanvampuke.dininglog.product

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.view.KeyEvent
import android.view.Surface
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import org.junit.Assert.*
import top.alanvampuke.dininglog.P9VisualActivity
import top.alanvampuke.dininglog.core.ThemeStyle
import top.alanvampuke.dininglog.feature.components.BackgroundArgb
import top.alanvampuke.dininglog.feature.components.ForegroundArgb
import top.alanvampuke.dininglog.feature.theme.*

internal val Pr18RenderedStyle = SemanticsPropertyKey<String>("Pr18RenderedStyle")

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
        record("reachable list=$list tag=$tag full=${bounds(tag)} visible=${target(tag).fetchSemanticsNode().boundsInRoot}")
    }

    fun tap(list: String, tag: String) { scroll(list, tag); minimumTarget(tag); target(tag).performClick() }

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

    fun save(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val bitmap = if (dialog) requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            else target("pr18:viewport").captureToImage().asAndroidBitmap()
        val filename = "pr18-$name-${renderedStyle.name.lowercase()}-${if (renderedDark) "dark" else "light"}" +
            "-actualFont$actualFont-api${Build.VERSION.SDK_INT}.png"
        File(folder, filename).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        record("screenshot=$filename pixels=${bitmap.width}x${bitmap.height}")
    }

    fun diagnose(name: String, failure: Throwable) {
        record("FAIL case=$name type=${failure.javaClass.name} message=${failure.message}")
        runCatching { save("failure-$name", dialog = true) }
        runCatching { record("semantics=${compose.onRoot(true).printToString()}") }
    }

    fun hideKeyboard() {
        compose.activityRule.scenario.onActivity { activity ->
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        if (automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
            automation.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true)
            automation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true)
        }
        compose.waitUntil(15_000) { automation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
        compose.waitForIdle()
    }

    fun keyboardBounds(): android.graphics.Rect? {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        return automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { window -> android.graphics.Rect().also { window.getBoundsInScreen(it) } }
    }
}
