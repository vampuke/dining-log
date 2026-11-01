package top.alanvampuke.dininglog.product

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowInsetsControllerCompat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File
import kotlin.math.pow
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.*
import top.alanvampuke.dininglog.feature.components.*
import top.alanvampuke.dininglog.feature.restaurants.*
import top.alanvampuke.dininglog.feature.revisions.*
import top.alanvampuke.dininglog.feature.settings.*
import top.alanvampuke.dininglog.feature.theme.*
import top.alanvampuke.dininglog.applySystemBarTheme

@RunWith(AndroidJUnit4::class)
class ProductVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ref = RecordRef(RecordKind.RESTAURANT, "visual-restaurant")
    private val snapshot = RecordSnapshot(ref, 1, mapOf("name" to "兰记川味小馆", "category" to "restaurant", "cuisine" to "川菜", "address" to "上海愚园路 123 号", "rating_tenths" to "43", "review" to "花椒香，鸡块酥脆。", "note" to "和朋友一起到店。"), "2026-11-01T08:00:00.000Z")
    private val summary = RecordSummary(ref, snapshot.title, 1, snapshot.updatedAt, snapshot.fields + ("address_excerpt" to "上海愚园路 123 号"), matches = listOf(MatchType.DISH, MatchType.REVIEW))
    private val draft = SavedDraft("visual-account", "visual-draft", ref, WriteAction.UPDATE, snapshot, snapshot.editable + ("rating_tenths" to "46"))
    private val revision = RecordSnapshot(RecordRef(RecordKind.REVISION, "visual-revision", ref.id), 1,
        mapOf("entity_label" to snapshot.title, "entity_type" to "restaurant", "entity_id" to ref.id, "entity_version_before" to "1", "entity_version_after" to "2", "created_at" to snapshot.updatedAt, "note" to "再次到店后改分"), snapshot.updatedAt,
        diff = listOf(Change("rating_tenths", "40", "43"), Change("review", "原来的评价", "新评价，花椒香")))
    private var saveTaps = 0

    @Test fun lightAndDarkListDetailEditSearchHistoryAndConflictRenderActualApprovedColors() {
        var dark by mutableStateOf(false)
        var screen by mutableStateOf("list")
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        compose.setContent { QuietGridTheme(dark) {
            SideEffect { applySystemBarTheme(compose.activity.window, dark) }
            when (screen) {
                "list" -> listScreen(ListUiState(items = listOf(summary), localScope = false))
                "detail" -> RecordDetailScreen(DetailUiState(CachedDetail(snapshot)), {}, {}, {}, {}, {}, {})
                "edit" -> EditScreen(EditorUiState(draft), { _, _ -> }, {}, {}, {}, {})
                "search" -> listScreen(ListUiState(items = listOf(summary), query = ListQuery(query = "辣子鸡"), localScope = false))
                "history" -> RevisionDetailScreen(DetailUiState(CachedDetail(revision)), {}, {}, {}, {}, {}, {})
                "history-list" -> listScreen(ListUiState(items = listOf(RecordSummary(revision.ref, snapshot.title, 1, revision.updatedAt,
                    revision.fields, changedFields = listOf("rating_tenths"), cachedDiff = revision.diff)), query = ListQuery(RecordKind.REVISION, ref.id), localScope = false))
                "visits" -> listScreen(ListUiState(items = listOf(RecordSummary(RecordRef(RecordKind.VISIT, "visual-visit", ref.id), "2026-11-01", 1, snapshot.updatedAt,
                    mapOf("restaurant_name" to snapshot.title, "dining_date" to "2026-11-01", "meal" to "dinner", "note_excerpt" to "同日第二次到店"))), query = ListQuery(RecordKind.VISIT), localScope = false))
                "settings" -> SettingsScreen(SettingsUiState(), "quiet_grid", emptyList(), ThemeChoice.DARK, "0.4.0", {}, {}, { _, _ -> }, {}, {}, {}, {})
                "conflict" -> ConflictDialog(compareConflict(draft, snapshot.copy(version = 2, fields = snapshot.fields + ("rating_tenths" to "47"))), false, {}, {})
            }
        } }
        for (night in listOf(false, true)) for (page in listOf("list", "detail", "edit", "search", "history", "history-list", "visits", "settings", "conflict")) {
            compose.runOnIdle { dark = night; screen = page }
            compose.waitForIdle()
            val tag = when (page) { "list" -> "screen:restaurant:list"; "history-list" -> "screen:revision:list"; "visits" -> "screen:visit:list"; else -> "screen:$page" }
            val c = if (night) QuietGridDark else QuietGridLight
            compose.activityRule.scenario.onActivity {
                val controller = WindowInsetsControllerCompat(it.window, it.window.decorView)
                assertEquals(!night, controller.isAppearanceLightStatusBars)
                assertEquals(!night, controller.isAppearanceLightNavigationBars)
            }
            val background = if (page == "conflict") c.surface else c.bg
            val titleTag = if (page == "conflict") "dialog-title" else "page-title"
            compose.onNodeWithTag(titleTag).assert(SemanticsMatcher.expectValue(ForegroundArgb, c.ink.toArgb()))
            assertPixels(tag, background.toArgb(), c.ink.toArgb(), "${if (night) "dark" else "light"}-$page")
            assertTrue("normal text contrast", contrast(c.ink.toArgb(), background.toArgb()) >= 4.5)
            assertTrue("secondary text contrast", contrast(c.secondary.toArgb(), background.toArgb()) >= 4.5)
            assertTrue("error text contrast", contrast(c.danger.toArgb(), background.toArgb()) >= 4.5)
            assertTrue("save button contrast", contrast(c.onAccent.toArgb(), c.cta.toArgb()) >= 4.5)
        }
    }
    @Composable private fun listScreen(state: ListUiState) {
        RecordListScreen(state, state.query.query, state.query.filters, {}, {}, {}, {}, {})
    }
    private fun assertPixels(tag: String, background: Int, ink: Int, name: String) {
        compose.onNodeWithTag(tag).assertExists()
        // Compose's PixelCopy helper cannot capture a dialog before API 28.
        // UiAutomation captures the actually rendered dialog on API 26 too.
        val bitmap = if (tag == "screen:conflict") requireNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ).asImageBitmap() else compose.onNodeWithTag(tag).captureToImage()
        val pixels = bitmap.toPixelMap()
        var backgroundPixels = 0; var inkPixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val argb = pixels[x, y].toArgb()
            if (argb == background) backgroundPixels++
            if (argb == ink) inkPixels++
        }
        assertTrue("$name actually paints approved background $background", backgroundPixels > 250)
        assertTrue("$name actually paints approved foreground $ink", inkPixels > 10)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = context.getExternalFilesDir("p4-screenshots")!!; folder.mkdirs()
        File(folder, "$name-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use { bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun contrast(foreground: Int, background: Int): Double {
        fun luminance(argb: Int): Double {
            fun channel(shift: Int): Double { val value = ((argb shr shift) and 255) / 255.0; return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4) }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        val a = luminance(foreground); val b = luminance(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
    private fun largeForm(dark: Boolean, message: String? = null) {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge(); it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                QuietGridTheme(dark) {
                    SideEffect { applySystemBarTheme(compose.activity.window, dark) }
                    EditScreen(EditorUiState(draft, message = message), { _, _ -> }, {}, { saveTaps++ }, {}, {})
                }
            }
        }
    }
    @Test fun light200PercentFontKeepsFullInputTextAndSaveReachable() { largeForm(false); assertLargeLayout("light") }
    @Test fun dark200PercentFontKeepsFullInputTextAndSaveReachable() { largeForm(true); assertLargeLayout("dark") }
    private fun assertLargeLayout(name: String) {
        compose.onNodeWithTag("field:name").performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("field:name").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty()); layouts.forEach { assertFalse("200% input must not clip", it.hasVisualOverflow) }
        compose.onNodeWithTag("save").assertIsDisplayed().assertIsEnabled()
        val save = compose.onNodeWithTag("save").fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("screen:edit").fetchSemanticsNode().boundsInRoot
        assertTrue(save.top >= root.top && save.bottom <= root.bottom)
        compose.onNodeWithTag("save").performClick()
        compose.runOnIdle { assertEquals(1, saveTaps) }
        val c = if (name == "dark") QuietGridDark else QuietGridLight
        assertPixels("screen:edit", c.bg.toArgb(), c.ink.toArgb(), "$name-font200")
    }
    @Test fun lightKeyboardLeavesSaveAboveActualIme() { largeForm(false); assertKeyboard("light") }
    @Test fun darkKeyboardAt200PercentLeavesSaveAboveActualIme() { largeForm(true); assertKeyboard("dark") }
    @Test fun darkKeyboardAt200PercentWithLongSaveMessageKeepsButtonReachable() {
        largeForm(true, "保存结果未知；草稿已保留，请用原请求重试确认结果。".repeat(4))
        assertKeyboard("dark")
    }
    @Test fun allThreeTabsAndPageTitlesRemainUnclippedAt200PercentInBothThemes() {
        var dark by mutableStateOf(false)
        var page by mutableStateOf("list")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                QuietGridTheme(dark) {
                    val tabs: @Composable () -> Unit = { BottomTabs("RestaurantsRoute") {} }
                    when (page) {
                        "list" -> RecordListScreen(ListUiState(items = listOf(summary), localScope = false), "", ListFilters(), {}, {}, {}, {}, {}, bottomBar = tabs)
                        "detail" -> RecordDetailScreen(DetailUiState(CachedDetail(snapshot)), {}, {}, {}, {}, {}, {}, bottomBar = tabs)
                        "history" -> RevisionDetailScreen(DetailUiState(CachedDetail(revision)), {}, {}, {}, {}, {}, {}, bottomBar = tabs)
                        "settings" -> SettingsScreen(SettingsUiState(), "quiet_grid", emptyList(), ThemeChoice.SYSTEM, "0.4.0", {}, {}, { _, _ -> }, {}, {}, {}, tabs)
                    }
                }
            }
        }
        for (night in listOf(false, true)) for (screen in listOf("list", "detail", "history", "settings")) {
            compose.runOnIdle { dark = night; page = screen }
            val folder = ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir("p4-screenshots")!!
            folder.mkdirs()
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(folder, "${if (night) "dark" else "light"}-$screen-font200-tabs-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("page-title").assertIsDisplayed()
            val titleLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("page-title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
            assertTrue(titleLayouts.isNotEmpty()); titleLayouts.forEach {
                assertFalse("200% $screen title ${it.layoutInput.text}: size=${it.size}, widthOverflow=${it.didOverflowWidth}, heightOverflow=${it.didOverflowHeight}", it.hasVisualOverflow)
            }
            for ((id, label) in listOf("restaurants" to "餐厅", "visits" to "到店", "settings" to "我的")) {
                compose.onNodeWithTag("tab:$id").assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("tab:$id")), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue(layouts.isNotEmpty()); layouts.forEach { assertFalse("200% tab label $label must not clip", it.hasVisualOverflow) }
            }
        }
    }
    private fun assertKeyboard(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val service = requireNotNull(automation.serviceInfo)
        service.flags = service.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = service
        fun keyboardBounds(): Rect? = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let {
            Rect().also(it::getBoundsInScreen).takeIf { rect -> rect.height() > 0 }
        }
        compose.onNodeWithTag("field:name").performScrollTo().performClick()
        compose.onNodeWithTag("field:name").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("field:name").assertIsFocused()
        compose.activityRule.scenario.onActivity { activity ->
            val focused = activity.window.decorView.findFocus()
            if (focused != null) activity.getSystemService(InputMethodManager::class.java).showSoftInput(focused, InputMethodManager.SHOW_IMPLICIT)
        }
        try {
            compose.waitUntil(15_000) { keyboardBounds() != null && compose.onNodeWithTag("screen:edit").fetchSemanticsNode().config[ImeBottomPx] > 0 }
        } catch (error: Throwable) {
            val folder = ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir("p4-screenshots")!!
            folder.mkdirs()
            val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(folder, "$name-keyboard-failure-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.activityRule.scenario.onActivity {
                val visible = Rect(); it.window.decorView.getWindowVisibleDisplayFrame(visible)
                println("IME diagnostic: visible=$visible decorHeight=${it.window.decorView.height} focused=${it.window.decorView.findFocus() != null}")
            }
            throw error
        }
        compose.onNodeWithTag("save").assertIsDisplayed().assertIsEnabled()
        val save = compose.onNodeWithTag("save").fetchSemanticsNode().boundsInWindow
        val visible = Rect()
        compose.activityRule.scenario.onActivity { it.window.decorView.getWindowVisibleDisplayFrame(visible) }
        assertTrue("save bottom ${save.bottom} above IME top ${visible.bottom}", save.bottom <= visible.bottom)
        val keyboard = requireNotNull(keyboardBounds())
        assertTrue("save bottom ${save.bottom} above actual input-method window ${keyboard.top}", save.bottom <= keyboard.top)
        assertTrue(save.top >= visible.top)
        compose.onNodeWithTag("save").performClick()
        compose.runOnIdle { assertEquals(1, saveTaps) }
        val c = if (name == "dark") QuietGridDark else QuietGridLight
        assertPixels("screen:edit", c.bg.toArgb(), c.ink.toArgb(), "$name-font200-keyboard")
    }
    @Test fun emptyNoResultsOfflineAndRefreshingContentRemainDistinct() {
        var state by mutableStateOf(ListUiState(localScope = false))
        compose.setContent { QuietGridTheme(true) { listScreen(state) } }
        compose.onNodeWithTag("empty-state").assertExists()
        compose.runOnIdle { state = ListUiState(query = ListQuery(query = "不存在"), localScope = false) }
        compose.onNodeWithTag("no-results").assertExists()
        compose.runOnIdle { state = ListUiState(items = listOf(summary), isOffline = true, query = ListQuery(query = "辣子鸡"), fetchedAt = 100, localScope = true) }
        compose.onNodeWithTag("offline").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cache-scope").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("record-list", useUnmergedTree = true).performScrollToNode(hasTestTag("title:${ref.id}"))
        compose.onNodeWithTag("title:${ref.id}", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(isRefreshing = true) }
        compose.onNodeWithTag("record-list", useUnmergedTree = true).performScrollToNode(hasTestTag("title:${ref.id}"))
        compose.onNodeWithTag("title:${ref.id}", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(isRefreshing = false, error = "连接失败，缓存保留") }
        compose.onNodeWithTag("load-error").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state = ListUiState(isOffline = true, localScope = true, query = ListQuery(query = "未缓存")) }
        compose.onNodeWithTag("cache-empty").assertExists()
        compose.onNodeWithText("已缓存内容中没有匹配；服务器范围未确认").assertExists()
        compose.onNodeWithTag("no-results").assertDoesNotExist()
    }
    @Test fun sameFieldConflictSaveIsDisabledUntilExplicitChoice() {
        var confirmations = 0
        compose.setContent { QuietGridTheme(true) { ConflictDialog(compareConflict(draft, snapshot.copy(version = 2, fields = snapshot.fields + ("rating_tenths" to "47"))), false, { confirmations++ }, {}) } }
        compose.onNodeWithTag("base_snapshot").assertExists(); compose.onNodeWithTag("server_current").assertExists(); compose.onNodeWithTag("local_draft").assertExists()
        compose.onNodeWithTag("confirm-merge").assertIsNotEnabled()
        compose.onNodeWithText("使用草稿").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-merge").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, confirmations) }
    }
    @Test fun selectingDefaultRatingSetsAnUnratedRecordAndEveryStepIsOneTenth() {
        var value by mutableStateOf<String?>(null)
        compose.setContent { QuietGridTheme(true) { RatingEditor(value, { value = it }, true) } }
        compose.onNodeWithText("未评分").assertExists()
        compose.onNodeWithTag("rating").performSemanticsAction(SemanticsActions.SetProgress) { it(4.0f) }
        compose.onNodeWithText("★ 4.0").assertExists()
        compose.onNodeWithTag("rating:plus").performClick()
        compose.onNodeWithText("★ 4.1").assertExists()
        compose.onNodeWithTag("rating:minus").performClick()
        compose.onNodeWithText("★ 4.0").assertExists()
        compose.onNodeWithTag("rating").performSemanticsAction(SemanticsActions.SetProgress) { it(4.3f) }
        compose.onNodeWithText("★ 4.3").assertExists()
        compose.runOnIdle { assertEquals("43", value) }
    }
}
