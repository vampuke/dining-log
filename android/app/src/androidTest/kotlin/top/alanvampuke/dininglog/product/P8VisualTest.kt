package top.alanvampuke.dininglog.product

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.View
import android.view.Surface
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.alanvampuke.dininglog.BuildConfig
import top.alanvampuke.dininglog.P9VisualActivity
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.*
import top.alanvampuke.dininglog.feature.components.*
import top.alanvampuke.dininglog.feature.restaurants.*
import top.alanvampuke.dininglog.feature.settings.*
import top.alanvampuke.dininglog.feature.theme.*

@RunWith(AndroidJUnit4::class)
class P8VisualTest {
    @get:Rule val compose = createAndroidComposeRule<P9VisualActivity>()
    private val ref = RecordRef(RecordKind.RESTAURANT, "p8-parent")
    private val record = RecordSnapshot(ref, 2, mapOf("name" to "兰巴赫", "category" to "restaurant", "cuisine" to "川菜",
        "review" to "文字记录每一口", "rating_tenths" to "43"), "2026-11-02T08:00:00Z")
    private val rows = (1..20).map { RecordSummary(ref.copy(id = "p8-$it"), "餐厅 $it", 1, record.updatedAt) }
    private val release = AppRelease(BuildConfig.VERSION_CODE + 1, "下一版本", "https://github.com/vampuke/dining-log/releases/download/v8/app-release.apk",
        "0".repeat(64), "新的交互与视觉修正", 1793620800)
    private val fieldError = "请填写名称，保存前检查输入内容"

    @Test fun eightEvidenceScreensAndEveryListTailStayAboveTheFloatingCapsuleInBothThemes() {
        var dark by mutableStateOf(false)
        var page by mutableStateOf("home")
        var scale by mutableStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                QuietGridTheme(dark) {
                    Box(Modifier.requiredSize(360.dp, 640.dp).clipToBounds().background(LocalQuietGridColors.current.bg).testTag("p8-viewport")) {
                        val tabs: @Composable () -> Unit = { BottomTabs("restaurants", onAdd = {}) {} }
                        when (page) {
                            "home", "visits", "dishes" -> RecordListScreen(ListUiState(items = rows.map {
                                if (page == "home") it else it.copy(ref = it.ref.copy(kind = if (page == "visits") RecordKind.VISIT else RecordKind.DISH, restaurantId = ref.id))
                            }, query = ListQuery(if (page == "home") RecordKind.RESTAURANT else if (page == "visits") RecordKind.VISIT else RecordKind.DISH), localScope = false),
                                "", ListFilters(), {}, {}, {}, {}, {}, bottomBar = tabs)
                            "edit" -> EditScreen(EditorUiState(SaveInput("p8-owner", ref, WriteAction.CREATE, null,
                                mapOf("name" to "", "category" to "restaurant"))), { _, _ -> }, {}, {}, {})
                            "detail" -> RecordDetailScreen(DetailUiState(CachedDetail(record)), {}, {}, {}, {}, {}, {},
                                visits = CachedPage(rows.map { it.copy(ref = it.ref.copy(kind = RecordKind.VISIT, restaurantId = ref.id)) }),
                                onNewVisit = {}, onVisits = {}, bottomBar = tabs)
                            "settings" -> SettingsScreen(SettingsUiState(), "p8_owner", ThemeChoice.SYSTEM, BuildConfig.VERSION_NAME,
                                {}, {}, { _, _ -> }, {}, {}, tabs)
                            "update" -> UpdateDialog(UpdateInstallState(release, UpdateStage.DOWNLOADING, 512 * 1024, 1024 * 1024), {}, {}, {}, {}, {})
                        }
                    }
                }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night; scale = 1f }
            for (screen in listOf("home", "edit", "detail", "update")) {
                compose.runOnIdle { page = screen }
                when (screen) {
                    "home" -> {
                        compose.onNodeWithTag("record-list").performScrollToNode(hasTestTag("record:p8-20"))
                        assertTail("record:p8-20")
                        compose.onNodeWithTag("tab:visits").assertDoesNotExist()
                        val capsule = compose.onNodeWithTag("floating-tabs").getUnclippedBoundsInRoot()
                        val plus = compose.onNodeWithTag("nav-add").getUnclippedBoundsInRoot()
                        assertEquals((capsule.left.value + capsule.right.value) / 2, (plus.left.value + plus.right.value) / 2, 0.5f)
                    }
                    "edit" -> {
                        compose.onNodeWithTag("field:name").assertIsDisplayed()
                        compose.onNodeWithText("例如：兰巴赫", useUnmergedTree = true).assertExists()
                        compose.onNodeWithText("放弃本机草稿").assertDoesNotExist()
                        compose.onNodeWithTag("save").assertIsDisplayed()
                    }
                    "detail" -> {
                        compose.onNodeWithTag("detail-scroll").performScrollToNode(hasTestTag("history"))
                        assertTail("history")
                    }
                    "update" -> {
                        compose.onNodeWithTag("update-progress").assertTextContains("50%", substring = true)
                        compose.onNodeWithTag("update-cancel").assertIsDisplayed()
                        compose.onNodeWithTag("update-download-page").assertIsDisplayed()
                    }
                }
                save("p8-${if (night) "dark" else "light"}-$screen", screen == "update")
            }
            for (font in listOf(1f, 2f)) for (screen in listOf("home", "dishes", "visits", "settings")) {
                compose.runOnIdle { page = screen; scale = font }
                if (screen == "settings") {
                    compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("logout")); assertTail("logout")
                    compose.onNodeWithText("未提交草稿", substring = true).assertDoesNotExist()
                } else {
                    compose.onNodeWithTag("record-list").performScrollToNode(hasTestTag("record:p8-20")); assertTail("record:p8-20")
                }
                for (label in listOf("餐厅", "我的")) {
                    val layouts = mutableListOf<TextLayoutResult>()
                    compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("tab:${if (label == "餐厅") "restaurants" else "settings"}")), useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertTrue(layouts.isNotEmpty()); layouts.forEach { assertFalse(it.hasVisualOverflow) }
                }
                if (font == 2f) save("p8-${if (night) "dark" else "light"}-$screen-tail-font200", false)
            }
        }
    }
    @Test fun updatePanelFitsShortContentAndScrollsLongNotesAt200PercentInBothThemes() {
        var dark by mutableStateOf(false)
        var longNotes by mutableStateOf(false)
        var stage by mutableStateOf(UpdateStage.IDLE)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, if (longNotes) 2f else 1f)) {
                QuietGridTheme(dark) {
                    key(longNotes, stage) {
                        UpdateDialog(UpdateInstallState(release.copy(notes = if (longNotes) "详细发布说明，记录每一口。\n".repeat(100) else "更新说明"),
                            stage, 512 * 1024, 1024 * 1024), {}, {}, {}, {}, {})
                    }
                }
            }
        }
        for (night in listOf(false, true)) {
            for (downloadStage in listOf(UpdateStage.IDLE, UpdateStage.DOWNLOADING)) {
                compose.runOnIdle { dark = night; longNotes = false; stage = downloadStage }
                assertUpdateFontScale(1f)
                compose.onNodeWithTag("update-close").assertIsDisplayed()
                val panel = compose.onNodeWithTag("screen:update").getUnclippedBoundsInRoot()
                val last = compose.onNodeWithTag("update-close").getUnclippedBoundsInRoot()
                assertEquals("short panel ends at its content padding", 16f, (panel.bottom - last.bottom).value, 1f)
                compose.onNodeWithTag("update-scroll").assert(SemanticsMatcher("short notes need no scroll") {
                    it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() == 0f
                })
                save("p9-${if (night) "dark" else "light"}-update-short-${downloadStage.name.lowercase()}", true)
            }
            compose.runOnIdle { longNotes = true; stage = UpdateStage.IDLE }
            assertUpdateFontScale(2f)
            compose.onNodeWithTag("update-scroll").assert(SemanticsMatcher("long notes scroll internally") {
                it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f
            })
            compose.onNodeWithTag("update-scroll").performScrollToNode(hasTestTag("update-close"))
            compose.onNodeWithTag("update-close").assertIsDisplayed()
            val panel = compose.onNodeWithTag("screen:update").getUnclippedBoundsInRoot()
            val last = compose.onNodeWithTag("update-close").getUnclippedBoundsInRoot()
            assertTrue(last.top >= panel.top && last.bottom <= panel.bottom)
            assertTrue("panel remains within the available window", panel.top >= 0.dp && panel.bottom <=
                compose.activity.resources.configuration.screenHeightDp.dp)
            save("p9-${if (night) "dark" else "light"}-update-long-font200", true)
        }
    }
    @Test fun landscapeCapsuleAndControlsRespectSideBarsAndCutoutsInBothDirectionsAndThemes() {
        var dark by mutableStateOf(false)
        var composeView: View? = null
        var pixelsPerDp = 1f
        compose.setContent {
            QuietGridTheme(dark) {
                val view = LocalView.current
                val density = LocalDensity.current.density
                SideEffect { composeView = view; pixelsPerDp = density }
                Box(Modifier.fillMaxSize().testTag("p8-viewport")) {
                    RecordListScreen(ListUiState(items = rows, localScope = false), "", ListFilters(), {}, {}, {}, {}, {},
                        bottomBar = { BottomTabs("restaurants", onAdd = {}) {} })
                }
            }
        }
        val original = compose.activity.requestedOrientation
        try {
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)) {
                compose.runOnIdle { compose.activity.requestedOrientation = orientation }
                val barOnRight = orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                val rotation = if (barOnRight) Surface.ROTATION_90 else Surface.ROTATION_270
                compose.waitUntil(10_000) {
                    val viewport = compose.onNodeWithTag("p8-viewport").getUnclippedBoundsInRoot()
                    compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        composeView?.display?.rotation == rotation &&
                        viewport.right - viewport.left > viewport.bottom - viewport.top
                }
                for (night in listOf(false, true)) {
                    compose.runOnIdle { dark = night }
                    // API 26 exercises side navigation bars. Typed synthetic cutout insets
                    // are supported from API 30, so API 35 also adds an opposite 32dp cutout.
                    val cutoutDp = if (Build.VERSION.SDK_INT >= 30) 32 else 0
                    val left = if (barOnRight) cutoutDp else 48
                    val right = if (barOnRight) 48 else cutoutDp
                    compose.runOnIdle {
                        val bars = Insets.of(if (barOnRight) 0 else (48 * pixelsPerDp).toInt(), 0,
                            if (barOnRight) (48 * pixelsPerDp).toInt() else 0, 0)
                        val cutout = Insets.of(if (barOnRight) (cutoutDp * pixelsPerDp).toInt() else 0, 0,
                            if (barOnRight) 0 else (cutoutDp * pixelsPerDp).toInt(), 0)
                        val insets = WindowInsetsCompat.Builder()
                            .setInsets(WindowInsetsCompat.Type.navigationBars(), bars)
                            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), bars)
                            .setInsets(WindowInsetsCompat.Type.displayCutout(), cutout).build()
                        val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                        assertEquals("left synthetic safe inset", (left * pixelsPerDp).toInt(), safe.left)
                        assertEquals("right synthetic safe inset", (right * pixelsPerDp).toInt(), safe.right)
                        ViewCompat.dispatchApplyWindowInsets(requireNotNull(composeView), insets)
                    }
                    val viewport = compose.onNodeWithTag("p8-viewport").getUnclippedBoundsInRoot()
                    for (tag in listOf("floating-tabs", "tab:restaurants", "nav-add", "tab:settings")) {
                        compose.onNodeWithTag(tag).assertIsDisplayed()
                        val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                        assertTrue("$tag clears the left safe inset plus margin", bounds.left >= viewport.left + (left + 20).dp - 1.dp)
                        assertTrue("$tag clears the right safe inset plus margin", bounds.right <= viewport.right - (right + 20).dp + 1.dp)
                    }
                    save("p9-${if (night) "dark" else "light"}-landscape-${if (barOnRight) "right-bar" else "left-bar"}", false)
                }
            }
        } finally {
            compose.runOnIdle { compose.activity.requestedOrientation = original; ViewCompat.requestApplyInsets(requireNotNull(composeView)) }
        }
    }
    @Test fun emptyFocusedDisabledErrorAndMultilineFieldsFitAt100And200PercentInBothThemes() {
        var dark by mutableStateOf(false)
        var scale by mutableStateOf(1f)
        var enabled by mutableStateOf(true)
        var error by mutableStateOf(false)
        var lines by mutableStateOf(1)
        var value by mutableStateOf("")
        val idleFocus = FocusRequester()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                QuietGridTheme(dark) {
                    Column(Modifier.requiredSize(360.dp, 640.dp).clipToBounds()
                        .background(LocalQuietGridColors.current.bg).testTag("p8-viewport").padding(16.dp)) {
                        // Android 8 can refocus the sole focusable field after clearFocus.
                        Box(Modifier.size(1.dp).focusRequester(idleFocus).focusable())
                        Box(Modifier.fillMaxWidth().testTag("field-container")) {
                            FormField("name", value, { value = it }, enabled, if (error) fieldError else null,
                                label = "字段名称", minLines = lines)
                        }
                    }
                }
            }
        }
        for (night in listOf(false, true)) for (font in listOf(1f, 2f)) for (active in listOf(true, false))
            for (invalid in listOf(false, true)) for (minLines in listOf(1, 3, 4)) {
            compose.runOnIdle { dark = night; scale = font; enabled = active; error = invalid; lines = minLines; value = "" }
            // Apply the new field configuration before clearing any restored focus.
            compose.runOnIdle { idleFocus.requestFocus() }
            compose.onNodeWithTag("field:name").assert(SemanticsMatcher("field has no active focus") {
                !it.config.contains(SemanticsProperties.Focused) || !it.config[SemanticsProperties.Focused]
            })
            val c = if (night) QuietGridDark else QuietGridLight
            assertFieldName("字段名称")
            compose.onNodeWithText("例如：兰巴赫", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(ForegroundArgb, c.tertiary.toArgb()))
            compose.onNodeWithText("字段名称", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(ForegroundArgb, if (invalid) c.danger.toArgb() else c.secondary.toArgb()))
            assertFieldLayout(empty = true, invalid = invalid, expectedScale = font)
            if (font == 2f && minLines == 4) {
                save("p9-${if (night) "dark" else "light"}-field-font200-${if (active) "enabled" else "disabled"}-${if (invalid) "error" else "normal"}-multiline", false)
            }
            if (active) {
                compose.onNodeWithTag("field:name").performTextReplacement("兰巴赫")
                compose.onNodeWithText("例如：兰巴赫", useUnmergedTree = true).assertDoesNotExist()
                compose.onNodeWithText("字段名称", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(ForegroundArgb, if (invalid) c.danger.toArgb() else c.ink.toArgb()))
            } else compose.onNodeWithTag("field:name").assertIsNotEnabled()
            compose.runOnIdle { value = "已填内容"; idleFocus.requestFocus() }
            assertFieldName("字段名称")
            assertFieldLayout(empty = false, invalid = invalid, expectedScale = font)
        }
    }
    @Test fun removedChooserEntriesAreAbsentFromOpenRestaurantAndVisitMenusInBothThemes() {
        var dark by mutableStateOf(false)
        var kind by mutableStateOf(RecordKind.RESTAURANT)
        compose.setContent {
            QuietGridTheme(dark) {
                RecordListScreen(ListUiState(query = ListQuery(kind, if (kind == RecordKind.VISIT) ref.id else null)),
                    "", ListFilters(), {}, {}, {}, {}, {}, onTrash = {})
            }
        }
        for (night in listOf(false, true)) for (recordKind in listOf(RecordKind.RESTAURANT, RecordKind.VISIT)) {
            compose.runOnIdle { dark = night; kind = recordKind }
            compose.onNodeWithTag("record-list").performScrollToNode(hasTestTag("list-more"))
            compose.onNodeWithTag("list-more").performClick()
            compose.onNodeWithTag("refresh").assertIsDisplayed()
            compose.onNodeWithTag("trash").assertIsDisplayed()
            compose.onNodeWithTag("choose-restaurant").assertDoesNotExist()
            compose.onNodeWithTag("clear-restaurant").assertDoesNotExist()
            compose.onNodeWithTag("refresh").performClick()
        }
    }
    private fun assertUpdateFontScale(expected: Float) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("发现新版本 ${release.versionName}", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("update title must expose its text layout", layouts.isNotEmpty())
        layouts.forEach { assertEquals("dialog renders the requested font scale", expected, it.layoutInput.density.fontScale, 0.001f) }
    }
    private fun assertFieldLayout(empty: Boolean, invalid: Boolean, expectedScale: Float) {
        val field = compose.onNodeWithTag("field:name")
        val fieldBounds = field.getUnclippedBoundsInRoot()
        val parent = compose.onNodeWithTag("field-container").getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("p8-viewport").getUnclippedBoundsInRoot()
        assertContained("field", fieldBounds, parent)
        assertContained("field container", parent, viewport)
        assertTextFits(field, "editable text", expectedScale)
        val label = compose.onNodeWithText("字段名称", useUnmergedTree = true)
        val labelBounds = label.getUnclippedBoundsInRoot()
        assertTextFits(label, "label", expectedScale)
        assertContained("label", labelBounds, parent)
        assertTrue("label must end above the input box", labelBounds.bottom <= fieldBounds.top)
        val placeholder = compose.onNodeWithText("例如：兰巴赫", useUnmergedTree = true)
        val placeholderBounds = if (empty) {
            assertTextFits(placeholder, "placeholder", expectedScale)
            placeholder.getUnclippedBoundsInRoot().also { assertContained("placeholder", it, fieldBounds) }
        } else {
            placeholder.assertDoesNotExist()
            null
        }
        val error = compose.onNodeWithText(fieldError, useUnmergedTree = true)
        if (invalid) {
            assertTextFits(error, "supporting error", expectedScale)
            val errorBounds = error.getUnclippedBoundsInRoot()
            assertContained("supporting error", errorBounds, fieldBounds)
            if (placeholderBounds != null) assertTrue("placeholder must not overlap supporting error", placeholderBounds.bottom <= errorBounds.top)
        } else error.assertDoesNotExist()
    }
    private fun assertTextFits(node: SemanticsNodeInteraction, description: String, expectedScale: Float) {
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("$description must expose its rendered text layout", layouts.isNotEmpty())
        layouts.forEach {
            assertEquals("$description renders the requested font scale", expectedScale, it.layoutInput.density.fontScale, 0.001f)
            assertFalse("$description must not overflow: ${it.layoutInput.text}", it.hasVisualOverflow)
        }
    }
    private fun assertContained(description: String, bounds: DpRect, parent: DpRect) {
        assertTrue("$description must not be clipped by its parent: $bounds inside $parent",
            bounds.left >= parent.left - 1.dp && bounds.top >= parent.top - 1.dp &&
                bounds.right <= parent.right + 1.dp && bounds.bottom <= parent.bottom + 1.dp)
    }
    private fun assertFieldName(label: String) {
        val name = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(label))
        compose.onNodeWithTag("field:name").assert(name).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
        compose.onAllNodes(name).assertCountEquals(1)
        compose.onAllNodes(hasText(label)).assertCountEquals(0)
    }
    private fun assertTail(tag: String) {
        // Scroll to the actual end, including the reserved capsule clearance.
        // performScrollToNode considers a node under an overlay to be displayed.
        val scroller = listOf("record-list", "detail-scroll", "settings-scroll").first {
            compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(scroller).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.onNodeWithTag(tag).assertIsDisplayed()
        val tail = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val capsule = compose.onNodeWithTag("floating-tabs").getUnclippedBoundsInRoot()
        assertTrue("$tag must be entirely above the capsule", tail.bottom <= capsule.top)
    }
    private fun save(name: String, dialog: Boolean) {
        val bitmap = if (dialog) requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            else compose.onNodeWithTag("p8-viewport").captureToImage().asAndroidBitmap()
        val folder = ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir("p4-screenshots")!!.apply { mkdirs() }
        File(folder, "$name-api${Build.VERSION.SDK_INT}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
