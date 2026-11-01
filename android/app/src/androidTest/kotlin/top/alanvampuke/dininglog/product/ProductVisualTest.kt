package top.alanvampuke.dininglog.product

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
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
import top.alanvampuke.dininglog.feature.theme.*

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

    @Test fun lightAndDarkListDetailEditSearchHistoryAndConflictRenderActualApprovedColors() {
        var dark by mutableStateOf(false)
        var screen by mutableStateOf("list")
        compose.setContent { QuietGridTheme(dark) {
            when (screen) {
                "list" -> listScreen(ListUiState(items = listOf(summary), localScope = false))
                "detail" -> RecordDetailScreen(DetailUiState(CachedDetail(snapshot)), {}, {}, {}, {}, {}, {})
                "edit" -> EditScreen(EditorUiState(draft), { _, _ -> }, {}, {}, {}, {})
                "search" -> listScreen(ListUiState(items = listOf(summary), query = ListQuery(query = "辣子鸡"), localScope = false))
                "history" -> RevisionDetailScreen(DetailUiState(CachedDetail(revision)), {}, {}, {}, {}, {}, {})
                "conflict" -> ConflictDialog(compareConflict(draft, snapshot.copy(version = 2, fields = snapshot.fields + ("rating_tenths" to "47"))), false, {}, {})
            }
        } }
        for (night in listOf(false, true)) for (page in listOf("list", "detail", "edit", "search", "history", "conflict")) {
            compose.runOnIdle { dark = night; screen = page }
            compose.waitForIdle()
            val tag = when (page) { "list" -> "screen:restaurant:list"; else -> "screen:$page" }
            val c = if (night) QuietGridDark else QuietGridLight
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
    private fun largeForm(dark: Boolean) {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge(); it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                QuietGridTheme(dark) { EditScreen(EditorUiState(draft), { _, _ -> }, {}, {}, {}, {}) }
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
        val c = if (name == "dark") QuietGridDark else QuietGridLight
        assertPixels("screen:edit", c.bg.toArgb(), c.ink.toArgb(), "$name-font200")
    }
    @Test fun lightKeyboardLeavesSaveAboveActualIme() { largeForm(false); assertKeyboard("light") }
    @Test fun darkKeyboardAt200PercentLeavesSaveAboveActualIme() { largeForm(true); assertKeyboard("dark") }
    private fun assertKeyboard(name: String) {
        compose.onNodeWithTag("field:name").performScrollTo().performClick()
        compose.onNodeWithTag("field:name").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("field:name").assertIsFocused()
        compose.activityRule.scenario.onActivity { activity ->
            val focused = activity.window.decorView.findFocus()
            if (focused != null) activity.getSystemService(InputMethodManager::class.java).showSoftInput(focused, InputMethodManager.SHOW_IMPLICIT)
        }
        try {
            compose.waitUntil(15_000) { compose.onNodeWithTag("screen:edit").fetchSemanticsNode().config[ImeBottomPx] > 0 }
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
        assertTrue(save.top >= visible.top)
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
        compose.onNodeWithTag("record-list").performScrollToNode(hasTestTag("title:${ref.id}"))
        compose.onNodeWithTag("title:${ref.id}").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(isRefreshing = true) }
        compose.onNodeWithTag("record-list").performScrollToNode(hasTestTag("title:${ref.id}"))
        compose.onNodeWithTag("title:${ref.id}").assertIsDisplayed()
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
}
