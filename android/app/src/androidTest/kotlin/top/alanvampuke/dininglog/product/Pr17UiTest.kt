package top.alanvampuke.dininglog.product

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.alanvampuke.dininglog.P9VisualActivity
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.*
import top.alanvampuke.dininglog.feature.components.*
import top.alanvampuke.dininglog.feature.restaurants.*
import top.alanvampuke.dininglog.feature.revisions.*
import top.alanvampuke.dininglog.feature.settings.*
import top.alanvampuke.dininglog.feature.theme.*

@RunWith(AndroidJUnit4::class)
class Pr17UiTest {
    @get:Rule val compose = createAndroidComposeRule<P9VisualActivity>()
    private var scale by mutableStateOf(1f)
    private var dark by mutableStateOf(false)
    private var width by mutableStateOf(360)
    private var height by mutableStateOf(640)
    private var exactPixels by mutableStateOf(false)
    private var pixelsPerDp = 1f
    private val time = "2026-11-02T08:00:00Z"
    private val parent = RecordRef(RecordKind.RESTAURANT, "pr17-parent")
    private val longName = "街角的一家有很长名字的小馆，记录完整名称"
    private val longAddress = "中山路沿江街区第十二号，经过桥边的咖啡店后向右转。".repeat(12)
    private val release = AppRelease(999, "下一版本".repeat(12),
        "https://github.com/vampuke/dining-log/releases/download/fixture/app.apk", "0".repeat(64), "更新说明", 1793620800)
    private fun restaurant() = RecordSnapshot(parent, 3, mapOf("name" to longName, "category" to "restaurant",
        "cuisine" to "川菜", "address" to longAddress, "rating_tenths" to "47", "last_visit_date" to "2026-11-01",
        "dish_count" to "23", "visit_count" to "12"), time)

    private fun render(content: @Composable () -> Unit) {
        compose.setContent {
            val native = LocalDensity.current
            // Wide content containers fit physically on the 360dp emulator while
            // retaining their requested logical width and fontScale for constraints.
            val fit = minOf(1f, compose.activity.resources.configuration.screenWidthDp.toFloat() / width)
            val measuredDensity = if (exactPixels) 1f else native.density * fit
            CompositionLocalProvider(LocalDensity provides Density(measuredDensity, scale)) {
                SideEffect { pixelsPerDp = measuredDensity }
                QuietGridTheme(dark) {
                    Box(Modifier.requiredSize(width.dp, height.dp).clipToBounds()
                        .background(LocalQuietGridColors.current.bg).testTag("pr17:viewport")) { content() }
                }
            }
        }
    }
    private fun configure(font: Float, night: Boolean, w: Int = 360, h: Int = 640) {
        compose.runOnIdle { scale = font; dark = night; width = w; height = h }
    }
    private fun scroll(list: String, tag: String) {
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun tap(list: String, tag: String) { scroll(list, tag); target(tag).performClick() }
    private fun target(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun bounds(tag: String) = target(tag).fetchSemanticsNode().boundsInRoot
    private fun assertPair(first: String, second: String, horizontal: Boolean) {
        val a = bounds(first); val b = bounds(second)
        if (horizontal) {
            assertTrue("$first / $second must share a row", a.top < b.bottom && b.top < a.bottom)
            assertTrue("$second follows $first", b.left >= a.right)
        } else assertTrue("$second must be below $first", b.top >= a.bottom)
        for (tag in listOf(first, second)) {
            val box = bounds(tag)
            assertTrue("$tag keeps a 48dp touch target", box.height / pixelsPerDp >= 47.5f)
        }
    }
    private fun assertTextFits(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("text exposes layout", layouts.isNotEmpty())
        for (layout in layouts) {
            assertFalse("overflow at font=$scale: ${layout.layoutInput.text}", layout.hasVisualOverflow)
            assertEquals(scale, layout.layoutInput.density.fontScale, 0.001f)
            recordEvidence("text font=$scale lines=${layout.lineCount} size=${layout.size} overflow=${layout.hasVisualOverflow} value=${layout.layoutInput.text}")
        }
    }
    private fun assertVisibleTextFits() {
        // Use unmerged text nodes, including children of clickable buttons. Original
        // list ellipsis is checked by the existing tests, outside this assertion.
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        for (index in nodes.fetchSemanticsNodes().indices) assertTextFits(nodes[index])
    }
    private fun folder() = ApplicationProvider.getApplicationContext<Context>()
        .getExternalFilesDir("p4-screenshots")!!.apply { mkdirs() }
    private fun recordEvidence(text: String) {
        File(folder(), "pr17-layout-api${Build.VERSION.SDK_INT}.txt").appendText("$text\n")
    }
    private fun save(name: String, dialog: Boolean = false) {
        val bitmap = if (dialog) requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            else target("pr17:viewport").captureToImage().asAndroidBitmap()
        File(folder(), "pr17-$name-font$scale-${if (dark) "dark" else "light"}-api${Build.VERSION.SDK_INT}.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun detailEntityStateMatrixPreservesValuesMetadataAndAllCallbacks() {
        var state by mutableStateOf(DetailUiState(CachedDetail(restaurant())))
        var callbacks by mutableStateOf(true)
        var previews by mutableStateOf(false)
        val actions = mutableListOf<String>()
        val dish = RecordSummary(RecordRef(RecordKind.DISH, "pr17-dish", parent.id), "菜品", 1, time)
        render {
            key(state.cached.snapshot?.ref, state.cached.snapshot?.deletedAt, scale, width, callbacks, previews) {
                RecordDetailScreen(state, { actions += "back" }, { actions += "refresh" }, { actions += "edit" },
                    { actions += "delete" }, { actions += "restore" }, { actions += "history" },
                    dishes = CachedPage(if (previews) listOf(dish) else emptyList()),
                    onChild = { actions += "child" },
                    onDishes = if (callbacks) ({ actions += "dishes" }) else null,
                    onVisits = if (callbacks) ({ actions += "visits" }) else null,
                    onAddDish = if (callbacks) ({ actions += "dish" }) else null,
                    onNewVisit = if (callbacks) ({ actions += "visit" }) else null)
            }
        }
        for (night in listOf(false, true)) for (font in listOf(1f, 1.3f, 1.31f, 2f)) {
            configure(font, night, if (font > 1.3f) 320 else 360)
            compose.runOnIdle { state = DetailUiState(CachedDetail(restaurant())); callbacks = true; previews = false }
            target("page-title").assertTextEquals("探店小票")
            target("detail-refresh").assertIsEnabled()
            scroll("detail-scroll", "pr17:receipt-head")
            compose.onNodeWithText(longName, useUnmergedTree = true).assertExists()
            compose.onAllNodesWithText("★ 4.7", useUnmergedTree = true).assertCountEquals(1)
            compose.onAllNodesWithContentDescription("评分 4.7，满分 5.0", useUnmergedTree = true).assertCountEquals(1)
            compose.onNodeWithText("BITE! 食记 · 探店小票 · 版本 3", useUnmergedTree = true).assertExists()
            compose.onNodeWithText("已修订", useUnmergedTree = true).assertExists()
            assertVisibleTextFits(); save("detail-head")
            for (field in listOf("category", "cuisine", "address")) {
                scroll("detail-scroll", "detail:$field")
                target("detail:$field").assertTextEquals(displayValue(field, restaurant().fields[field]))
                assertTextFits(target("detail:$field"))
            }
            scroll("detail-scroll", "visit-history")
            compose.onNodeWithText("到店记录 · 12 次", useUnmergedTree = true).assertExists()
            compose.onNodeWithText("最近到店 2026-11-01", useUnmergedTree = true).assertExists()
            assertVisibleTextFits(); save("detail-visit-actions")
            // Missing optional fields and counts never invent values or totals.
            compose.runOnIdle { state = DetailUiState(CachedDetail(restaurant().copy(fields = restaurant().fields - setOf("cuisine", "address", "dish_count", "visit_count", "last_visit_date")))) }
            scroll("detail-scroll", "add-dish")
            target("detail:cuisine").assertDoesNotExist(); target("detail:address").assertDoesNotExist()
            compose.onNodeWithText("菜品在本餐厅内管理", useUnmergedTree = true).assertExists()
            for ((tag, action) in listOf("edit" to "edit", "add-dish" to "dish", "add-visit" to "visit",
                "visit-history" to "visits", "history" to "history")) {
                tap("detail-scroll", tag); assertEquals(action, actions.last())
            }
            compose.onNodeWithTag("detail-scroll").performScrollToNode(hasText("管理店内菜品"))
            compose.onNodeWithText("管理店内菜品").performClick(); assertEquals("dishes", actions.last())
            tap("detail-scroll", "delete")
            val beforeCancel = actions.size
            compose.onNodeWithText("取消").performClick(); assertEquals(beforeCancel, actions.size)
            tap("detail-scroll", "delete"); target("confirm").performClick(); assertEquals("delete", actions.last())
            compose.runOnIdle { state = DetailUiState(CachedDetail(restaurant().copy(deletedAt = time))) }
            tap("detail-scroll", "restore"); target("confirm").performClick(); assertEquals("restore", actions.last())
            target("edit").assertDoesNotExist()
            compose.runOnIdle { state = DetailUiState(CachedDetail(restaurant())); callbacks = false }
            scroll("detail-scroll", "pr17:detail-history-panel")
            for (tag in listOf("add-dish", "add-visit", "visit-history")) target(tag).assertDoesNotExist()
            for (kind in listOf(RecordKind.DISH, RecordKind.VISIT)) {
                val fields = if (kind == RecordKind.DISH) mapOf("name" to longName, "rating_tenths" to null)
                    else mapOf("dining_date" to "2026-11-02", "meal" to "dinner")
                compose.runOnIdle { state = DetailUiState(CachedDetail(RecordSnapshot(RecordRef(kind, kind.name, parent.id), 1, fields, time))) }
                scroll("detail-scroll", "pr17:receipt-head")
                compose.onAllNodesWithContentDescription("评分 4.7，满分 5.0", useUnmergedTree = true).assertCountEquals(0)
                if (kind == RecordKind.DISH) compose.onAllNodesWithText("未评分", useUnmergedTree = true).assertCountEquals(2)
                else {
                    scroll("detail-scroll", "detail:meal"); target("detail:meal").assertTextEquals("晚餐")
                    target("detail:dining_date").assertTextEquals("2026-11-02")
                }
                assertVisibleTextFits(); save("detail-${kind.name.lowercase()}")
            }
            for (variant in listOf(
                DetailUiState(CachedDetail(), refreshing = true), DetailUiState(CachedDetail(restaurant()), refreshing = true),
                DetailUiState(CachedDetail(), offline = true), DetailUiState(CachedDetail(), error = "读取失败"),
                DetailUiState(CachedDetail(restaurant(), 1793620800000, true), offline = true, error = "离线失败"))) {
                compose.runOnIdle { state = variant }
                scroll("detail-scroll", "detail-refresh")
                if (variant.refreshing) target("detail-refresh").assertIsNotEnabled() else target("detail-refresh").assertIsEnabled()
                if (variant.cached.snapshot == null) target("detail-empty").assertExists()
                if (variant.offline) target("offline").assertExists()
                if (variant.cached.stale) target("stale-detail").assertExists()
                if (variant.error != null) target("error").assertTextEquals(variant.error!!)
            }
        }
        // The four dish-count / preview combinations use server count, never preview size.
        configure(1f, false)
        for (count in listOf(null, "23")) for (hasPreview in listOf(false, true)) {
            compose.runOnIdle {
                callbacks = true; previews = hasPreview
                state = DetailUiState(CachedDetail(restaurant().copy(fields = restaurant().fields + ("dish_count" to count))))
            }
            scroll("detail-scroll", "add-dish")
            val caption = if (count != null) "店内菜品 · 23 道" + if (hasPreview) "" else " · 菜品在本餐厅内管理"
                else if (!hasPreview) "菜品在本餐厅内管理" else null
            if (caption != null) compose.onNodeWithText(caption, useUnmergedTree = true).assertExists()
            else compose.onNodeWithText("菜品在本餐厅内管理", useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test fun pageActionsAndHeadersUseContentWidthBoundaryAndSingleCallbackFillsTheRow() {
        var state by mutableStateOf(DetailUiState(CachedDetail(restaurant().copy(fields = mapOf("name" to "小馆")))))
        var visitsEnabled by mutableStateOf(true)
        val actions = mutableListOf<String>()
        render { key(width, scale, visitsEnabled) {
            RecordDetailScreen(state, {}, {}, {}, {}, {}, {}, onNewVisit = { actions += "new" },
                onVisits = if (visitsEnabled) ({ actions += "all" }) else null)
        } }
        // Integer pixels per dp isolate the boundary from dp-to-pixel rounding.
        compose.runOnIdle { exactPixels = true }
        // Screen edges 16*2 and panel padding 14*2 leave the chosen content width.
        for (font in listOf(1f, 1.3f, 1.31f, 2f)) for (content in listOf(279, 280)) {
            configure(font, false, content + 60)
            scroll("detail-scroll", "visit-history")
            assertPair("add-visit", "visit-history", font <= 1.3f && content >= 280)
            assertVisibleTextFits()
            target("visit-history").performClick(); assertEquals("all", actions.last())
            recordEvidence("page-pair font=$font content=$content first=${bounds("add-visit")} second=${bounds("visit-history")}")
        }
        for (font in listOf(1f, 1.3f, 1.31f, 2f)) for (content in listOf(279, 280)) {
            configure(font, false, content + 48)
            scroll("detail-scroll", "detail-refresh")
            val title = bounds("page-title"); val refresh = bounds("detail-refresh")
            if (font <= 1.3f && content >= 280) assertTrue(title.top < refresh.bottom && refresh.top < title.bottom)
            else assertTrue(refresh.top >= title.bottom)
            assertTextFits(target("page-title"))
            target("detail-refresh").performClick()
            recordEvidence("header font=$font content=$content title=$title refresh=$refresh")
        }
        configure(1f, false)
        compose.runOnIdle { visitsEnabled = false }
        scroll("detail-scroll", "add-visit")
        val button = bounds("add-visit")
        assertEquals("one callback occupies the full panel content", 300f, button.width / pixelsPerDp, 1f)
        target("visit-history").assertDoesNotExist()
        target("add-visit").performClick(); assertEquals("new", actions.last())
    }

    @Test fun receiptTailIsMeasuredAgainstTheOriginalCardWithMatchingData() {
        val record = restaurant().copy(fields = mapOf("name" to "小馆", "rating_tenths" to "31"))
        var legacy by mutableStateOf(true)
        render {
            if (legacy) Box(Modifier.padding(16.dp)) { BitePanel(Modifier.testTag("pr17:legacy-history-tail")) {
                GridText("修改历史", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                Column(Modifier.revisionRail(LocalQuietGridColors.current.border).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GridText("当前版本 ${record.version}", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    GridText(formatAbsoluteTime(record.updatedAt), style = QuietGridText.date, color = LocalQuietGridColors.current.secondary)
                    BiteTextButton(onClick = {}) { androidx.compose.material3.Text("查看修改历史") }
                }
                ReceiptBarcode()
                GridText("食记 · 每一口都值得记录", style = QuietGridText.caption, color = LocalQuietGridColors.current.secondary)
            } } else RecordDetailScreen(DetailUiState(CachedDetail(record)), {}, {}, {}, {}, {}, {})
        }
        for (night in listOf(false, true)) for (font in listOf(1f, 1.3f)) {
            configure(font, night)
            compose.runOnIdle { legacy = true }
            val old = bounds("pr17:legacy-history-tail").height / pixelsPerDp
            save("receipt-tail-before")
            compose.runOnIdle { legacy = false }
            scroll("detail-scroll", "pr17:detail-history-panel")
            val history = bounds("pr17:detail-history-panel").height / pixelsPerDp
            scroll("detail-scroll", "pr17:receipt-footer")
            val footer = bounds("pr17:receipt-footer").height / pixelsPerDp
            recordEvidence("A7 font=$font dark=$night old=$old new=${history + 12 + footer} history=$history gap=12 footer=$footer")
            assertTrue("A7 ordinary-font two cards and their gap must not grow", history + 12 + footer <= old + 1f)
            assertVisibleTextFits(); save("receipt-tail-after")
        }
    }

    @Test fun ratingEditorNullSteppersBoundariesAndFilterControlsDoNotCrossValues() {
        var filters by mutableStateOf(ListFilters(minRating = 10, maxRating = 50))
        var editor by mutableStateOf(true)
        var rating by mutableStateOf<String?>(null)
        render {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("pr17:controls-scroll")) {
                if (editor) RatingEditor(rating, { rating = it }, true, framed = false)
                else ListFilterPanel(RecordKind.DISH, filters, { filters = it })
            }
        }
        compose.runOnIdle { exactPixels = true }
        for (font in listOf(1f, 1.3f, 1.31f, 2f)) for (content in listOf(279, 280)) {
            configure(font, false, content)
            compose.runOnIdle { editor = true; rating = null }
            target("rating").assertContentDescriptionEquals("未评分")
            target("rating:minus").assertDoesNotExist()
            target("rating").performTouchInput { click(center) }
            compose.runOnIdle { assertNull(rating) }
            compose.onNodeWithText("设置评分").performClick()
            compose.runOnIdle { assertEquals("40", rating) }
            target("rating:plus").performClick(); compose.runOnIdle { assertEquals("41", rating) }
            target("rating:minus").performClick(); compose.runOnIdle { assertEquals("40", rating) }
            if (font > 1.3f) assertPair("rating:minus", "rating:plus", false) else assertPair("rating:minus", "rating:plus", true)
            val clear = compose.onNodeWithText("清除评分")
            clear.performScrollTo().performClick(); compose.runOnIdle { assertNull(rating) }
            compose.onNodeWithText("设置评分").performScrollTo().performClick()
            for ((value, tag) in listOf("10" to "rating:minus", "50" to "rating:plus")) {
                compose.runOnIdle { rating = value }
                target(tag).performClick(); compose.runOnIdle { assertEquals(value, rating) }
            }
        }
        compose.runOnIdle { editor = false }
        for (night in listOf(false, true)) for (font in listOf(1f, 1.3f, 1.31f, 2f))
            for (content in listOf(319, 320, 491, 492)) {
            configure(font, night, content + 28)
            compose.runOnIdle { filters = ListFilters(minRating = 10, maxRating = 50) }
            target("pr17:controls-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100_000f) }
            scroll("pr17:controls-scroll", "filter-rating-range")
            assertPair("filter-unrated", "filter-rating-range", font <= 1.3f && content >= 320)
            scroll("pr17:controls-scroll", "filter-min")
            val min = bounds("filter-min")
            scroll("pr17:controls-scroll", "filter-max")
            // Compare placement without changes in scroll offset: at 492 both tracks
            // can be observed together; in the vertical branch their x positions match.
            val max = bounds("filter-max")
            if (font <= 1.3f && content >= 492) assertTrue(max.left > min.right)
            else assertEquals(min.left, max.left, 1f)
            tap("pr17:controls-scroll", "filter-min:plus")
            compose.runOnIdle { assertEquals(11, filters.minRating); assertEquals(50, filters.maxRating) }
            tap("pr17:controls-scroll", "filter-max:minus")
            compose.runOnIdle { assertEquals(11, filters.minRating); assertEquals(49, filters.maxRating) }
            assertVisibleTextFits(); save("filter-$content-with-editors")
            tap("pr17:controls-scroll", "filter-unrated")
            compose.runOnIdle { assertTrue(filters.unrated); assertNull(filters.minRating); assertNull(filters.maxRating) }
            target("filter-min").assertDoesNotExist(); target("filter-max").assertDoesNotExist()
            tap("pr17:controls-scroll", "filter-rating-range")
            compose.runOnIdle { assertFalse(filters.unrated); assertEquals(10, filters.minRating); assertEquals(50, filters.maxRating) }
            // Each range keeps its own SetProgress accessibility action too.
            scroll("pr17:controls-scroll", "filter-min")
            target("filter-min").performSemanticsAction(SemanticsActions.SetProgress) { it(2.3f) }
            compose.runOnIdle { assertEquals(23, filters.minRating); assertEquals(50, filters.maxRating) }
            scroll("pr17:controls-scroll", "filter-max")
            target("filter-max").performSemanticsAction(SemanticsActions.SetProgress) { it(4.6f) }
            compose.runOnIdle { assertEquals(23, filters.minRating); assertEquals(46, filters.maxRating) }
            tap("pr17:controls-scroll", "filter-rating-range")
            compose.runOnIdle { assertNull(filters.minRating); assertNull(filters.maxRating) }
            recordEvidence("filter font=$font content=$content minimum=$min maximum=$max")
            save("filter-$content")
        }
    }

    @Test fun editorKeepsTheOuterRatingCardAndSavedRestaurantChildActions() {
        val base = restaurant().copy(fields = mapOf("name" to "小馆", "category" to "restaurant", "rating_tenths" to "40"))
        var input by mutableStateOf(SaveInput("owner", parent, WriteAction.UPDATE, base, base.editable))
        val actions = mutableListOf<String>()
        render { key(scale, width, input.baseSnapshot) {
            EditScreen(EditorUiState(input), { field, value -> input = input.copy(localFields = input.localFields + (field to value)) }, {}, {}, {},
                onAddDish = { actions += "add" }, onManageDishes = { actions += "manage" })
        } }
        for (night in listOf(false, true)) for (font in listOf(1f, 1.3f, 1.31f, 2f)) {
            configure(font, night)
            scroll("edit-scroll", "rating")
            assertVisibleTextFits(); save("edit-rating-one-frame")
            tap("edit-scroll", "edit-add-dish"); assertEquals("add", actions.last())
            tap("edit-scroll", "edit-manage-dishes"); assertEquals("manage", actions.last())
            assertPair("edit-add-dish", "edit-manage-dishes", font <= 1.3f)
        }
        compose.runOnIdle { input = input.copy(action = WriteAction.CREATE, baseSnapshot = null) }
        compose.onNodeWithTag("edit-scroll").performScrollToNode(hasText("保存餐厅后即可在这里新增、编辑、删除菜品"))
        target("edit-add-dish").assertDoesNotExist(); target("edit-manage-dishes").assertDoesNotExist()
    }

    @Test fun historyPairsFullTextTimeAndConfirmationCallbacksRemainReachable() {
        var record by mutableStateOf(RecordSnapshot(RecordRef(RecordKind.REVISION, "pr17-history"), 1,
            mapOf("entity_label" to longName, "created_at" to time, "entity_version_before" to "99", "entity_version_after" to "100"), time,
            diff = listOf(Change("rating_tenths", null, "47"), Change("address", "短地址", longAddress),
                Change("review", "旧".repeat(240), "新".repeat(241)))))
        val actions = mutableListOf<String>()
        var flags by mutableStateOf(DetailUiState())
        var missing by mutableStateOf(false)
        render { key(scale, width, record.deletedAt) {
            RevisionDetailScreen(flags.copy(cached = CachedDetail(if (missing) null else record, stale = flags.cached.stale)), {}, { actions += "refresh" }, { actions += "note" },
                { actions += "hide" }, { actions += "restore" }, { actions += "apply" })
        } }
        for (night in listOf(false, true)) for (font in listOf(1f, 1.3f, 1.31f, 2f)) {
            configure(font, night, if (font > 1.3f) 320 else 360)
            scroll("history-scroll", "pr17:history-refresh"); target("pr17:history-refresh").performClick()
            assertEquals("refresh", actions.last())
            scroll("history-scroll", "history-time"); target("history-time").performClick()
            target("absolute-time").assertTextEquals(formatAbsoluteTime(time))
            if (font > 1.3f) assertTextFits(target("absolute-time"))
            target("history-time").performClick()
            for (change in record.diff) {
                scroll("history-scroll", "before:${change.field}")
                target("before:${change.field}").assertTextEquals(displayValue(change.field, change.before))
                scroll("history-scroll", "after:${change.field}")
                val full = displayValue(change.field, change.after)
                target("after:${change.field}").assertTextEquals(if (full.length > 240) full.take(240) + "…" else full)
                if (full.length > 240) {
                    tap("history-scroll", "expand:after:${change.field}")
                    scroll("history-scroll", "after:${change.field}")
                    target("after:${change.field}").assertTextEquals(full)
                    assertTextFits(target("after:${change.field}"))
                    tap("history-scroll", "expand:after:${change.field}")
                } else target("expand:after:${change.field}").assertDoesNotExist()
            }
            target("expand:before:review").assertDoesNotExist()
            tap("history-scroll", "history-note"); assertEquals("note", actions.last())
            tap("history-scroll", "hide-history")
            val before = actions.size
            compose.onNodeWithText("取消").performClick(); assertEquals(before, actions.size)
            tap("history-scroll", "hide-history"); target("confirm").performClick(); assertEquals("hide", actions.last())
            tap("history-scroll", "reapply"); assertEquals("apply", actions.last())
            assertVisibleTextFits(); save("history-actions")
            compose.runOnIdle { record = record.copy(deletedAt = time) }
            tap("history-scroll", "restore-history"); target("confirm").performClick(); assertEquals("restore", actions.last())
            compose.runOnIdle { record = record.copy(deletedAt = null) }
        }
        configure(2f, true, 320)
        for (variant in listOf(DetailUiState(offline = true), DetailUiState(CachedDetail(stale = true)),
            DetailUiState(error = "历史读取失败"), DetailUiState(refreshing = true))) {
            compose.runOnIdle { flags = variant }
            scroll("history-scroll", "pr17:history-refresh")
            if (variant.refreshing) target("pr17:history-refresh").assertIsNotEnabled()
            else target("pr17:history-refresh").assertIsEnabled()
            if (variant.offline) target("offline").assertExists()
            if (variant.cached.stale) target("stale-detail").assertExists()
            if (variant.error != null) target("error").assertTextEquals(variant.error!!)
        }
        compose.runOnIdle { flags = DetailUiState(); missing = true }
        compose.onNodeWithText("尚无完整 diff 缓存，请联网后重试").assertExists()
    }

    @Test fun applyPreviewAndComparisonColumnsKeepTheirOwnValuesAndScroll() {
        val current = restaurant().copy(fields = mapOf("name" to "当前名称", "address" to "当前地址", "review" to null))
        val revision = RecordSnapshot(RecordRef(RecordKind.REVISION, "apply"), 1, emptyMap(), time,
            diff = listOf(Change("name", null, "新名称"), Change("address", "旧地址", longAddress), Change("review", null, "新评价")))
        var state by mutableStateOf(ApplyUiState(revision, current, side = "before"))
        var comparison by mutableStateOf(false)
        var saved = 0
        render {
            if (comparison) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("pr17:comparison")) {
                ComparisonColumns(listOf(ConflictField("address", "基准", longAddress, "本地地址", true)), 3)
            } else ApplyFieldsDialog(state, { state = state.copy(side = it) }, { field, checked ->
                state = state.copy(selected = if (checked) state.selected + field else state.selected - field)
            }, { saved++ }, {})
        }
        for (font in listOf(1f, 2f)) {
            configure(font, false, 320)
            compose.runOnIdle { comparison = false; state = state.copy(side = "before", selected = emptySet()) }
            target("apply-save").assertIsNotEnabled()
            target("apply-field:name").performScrollTo().assertIsNotEnabled()
            target("apply-current:name").performScrollTo().assertTextEquals("当前名称")
            target("apply-proposed:name").assertTextEquals("未填写")
            compose.onNodeWithText("应用新值").performScrollTo().performClick()
            target("apply-proposed:name").performScrollTo().assertTextEquals("新名称")
            target("apply-field:name").performScrollTo().assertIsEnabled().performClick()
            target("apply-save").assertIsEnabled().performClick(); assertTrue(saved > 0)
            target("apply-current:address").performScrollTo().assertTextEquals("当前地址")
            target("expand:apply-proposed:address").performScrollTo().performClick()
            target("apply-proposed:address").performScrollTo().assertTextEquals(longAddress)
            assertTextFits(target("apply-proposed:address"))
            target("apply-current:review").performScrollTo().assertTextEquals("未填写")
            target("apply-proposed:review").assertTextEquals("新评价")
            save("apply-pairs", true)
            compose.runOnIdle { comparison = true }
            target("compare:address:0").assertTextEquals(longAddress.take(240) + "…")
            target("expand:compare:address:0").performClick()
            assertTextFits(target("compare:address:0"))
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(100_000f, 0f) }
            target("local_input").assertIsDisplayed()
            target("compare:address:1").assertIsDisplayed().assertTextEquals("本地地址")
            save("comparison")
        }
    }

    @Test fun settingsProfileUpdatePasswordAndLogoutRemainReachableWithoutSessionManagement() {
        var state by mutableStateOf(SettingsUiState(profile = AccountInfo("owner", "owner", "显示名称", time)))
        var update by mutableStateOf(AppUpdateState(enabled = false))
        val actions = mutableListOf<String>()
        render { key(scale, width) {
            SettingsScreen(state, "owner", if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT, "fixture",
                onPassword = { _, _ -> actions += "password" }, onLogout = { actions += "logout" }, onTheme = { actions += "theme" }, bottomBar = {},
                updateState = update, onUpdateToggle = { update = update.copy(enabled = it); actions += "toggle" },
                onOpenUpdate = { actions += "open" }, onCheckUpdate = { actions += "check" })
        } }
        for (night in listOf(false, true)) for (font in listOf(1f, 1.3f, 1.31f, 2f)) {
            configure(font, night, if (font > 1.3f) 320 else 360)
            scroll("settings-scroll", "page-title")
            compose.onNodeWithText("账号 显示名称", useUnmergedTree = true).assertExists()
            compose.onNodeWithText("登录会话").assertDoesNotExist()
            compose.onNodeWithText("刷新账号与会话").assertDoesNotExist()
            compose.onNodeWithText("撤销会话").assertDoesNotExist()
            tap("settings-scroll", "update-checks"); assertEquals("toggle", actions.last())
            tap("settings-scroll", "check-update"); assertEquals("check", actions.last())
            compose.runOnIdle { update = AppUpdateState(enabled = false, checking = true) }
            target("check-update").assertIsNotEnabled()
            compose.runOnIdle { update = AppUpdateState(enabled = false, manual = true, failed = true) }
            scroll("settings-scroll", "update-check-error")
            compose.runOnIdle { update = AppUpdateState(enabled = false, manual = true, upToDate = true) }
            scroll("settings-scroll", "update-up-to-date")
            compose.runOnIdle { update = AppUpdateState(enabled = false, manual = true, release = release) }
            tap("settings-scroll", "app-update"); assertEquals("open", actions.last())
            compose.onNodeWithTag("settings-scroll").performScrollToNode(hasText("跟随系统"))
            compose.onNodeWithText("跟随系统").performClick(); assertEquals("theme", actions.last())
            tap("settings-scroll", "change-password"); target("screen:password").assertExists()
            compose.onNodeWithText("关闭").performClick()
            tap("settings-scroll", "logout")
            compose.onNodeWithText("将清理此账号的本机缓存。服务器已保存记录仍属于账号。").assertExists()
            val before = actions.size
            compose.onNodeWithText("取消").performClick(); assertEquals(before, actions.size)
            tap("settings-scroll", "logout"); target("confirm").performClick(); assertEquals("logout", actions.last())
            assertVisibleTextFits(); save("settings-tail")
        }
    }

    @Test fun updateStageMatrixPreservesCallbacksProgressAndScrollableBottomPadding() {
        var state by mutableStateOf(UpdateInstallState(release))
        val actions = mutableListOf<String>()
        render { key(state.stage, scale, state.release?.notes) {
            UpdateDialog(state, { actions += "download" }, { actions += "cancel" }, { actions += "install" },
                { actions += "page" }, { actions += "dismiss" })
        } }
        val variants = listOf(UpdateInstallState(release),
            UpdateInstallState(release, UpdateStage.DOWNLOADING, 512, 1024),
            UpdateInstallState(release, UpdateStage.DOWNLOADING, 512, null),
            UpdateInstallState(release, UpdateStage.DOWNLOADING, 512, 0),
            UpdateInstallState(release, UpdateStage.VERIFYING, 1024, 1024),
            UpdateInstallState(release, UpdateStage.READY, packageVerified = true),
            UpdateInstallState(release, UpdateStage.FAILED, message = "下载失败"),
            UpdateInstallState(release, UpdateStage.FAILED, message = "安装失败", packageVerified = true),
            UpdateInstallState(release, UpdateStage.CANCELED), UpdateInstallState(release, UpdateStage.NEED_PERMISSION),
            UpdateInstallState(release, UpdateStage.INSTALLING))
        for (night in listOf(false, true)) for (font in listOf(1f, 1.3f, 1.31f, 2f)) for ((index, variant) in variants.withIndex()) {
            configure(font, night)
            compose.runOnIdle { state = variant.copy(release = release.copy(notes = if (font > 1.3f) "详细更新说明。\n".repeat(120) else "更新说明")) }
            val primary = when (state.stage) {
                UpdateStage.IDLE -> "update-now" to "download"
                UpdateStage.FAILED, UpdateStage.CANCELED -> if (state.packageVerified) "update-install" to "install" else "update-retry" to "download"
                UpdateStage.READY, UpdateStage.NEED_PERMISSION, UpdateStage.INSTALLING -> "update-install" to "install"
                else -> null
            }
            if (state.busy) {
                target("update-progress").performScrollTo().assertTextContains(if (state.stage == UpdateStage.VERIFYING) "正在校验安装包…"
                    else state.percent?.let { "$it%" } ?: "百分比待确认", substring = true)
                target("update-cancel").performScrollTo().performClick(); assertEquals("cancel", actions.last())
                compose.onNodeWithText(if (state.total == null) "0.00 MB / 总大小未知" else "0.00 MB / 0.00 MB", useUnmergedTree = true).assertExists()
            } else target("update-cancel").assertDoesNotExist()
            if (primary != null) {
                target(primary.first).performScrollTo().assertIsEnabled().performClick(); assertEquals(primary.second, actions.last())
            }
            for (tag in listOf("update-now", "update-retry", "update-install")) if (tag != primary?.first) target(tag).assertDoesNotExist()
            if (state.message != null) target("update-message").performScrollTo().assertTextEquals(state.message!!)
            else target("update-message").assertDoesNotExist()
            target("update-download-page").performScrollTo().performClick(); assertEquals("page", actions.last())
            compose.onNodeWithTag("update-close").performScrollTo().assertTextEquals(if (state.busy) "后台继续下载" else "稍后")
            target("update-close").performClick(); assertEquals("dismiss", actions.last())
            target("update-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            val panel = bounds("screen:update"); val close = bounds("update-close")
            assertEquals("dialog footer keeps 16dp below close", 16f, (panel.bottom - close.bottom) / pixelsPerDp, 1f)
            assertVisibleTextFits(); save("update-stage-$index", true)
        }
    }

    @Test fun updatePairUsesDialogContent239And240Boundary() {
        var fraction by mutableStateOf(1f)
        val actions = mutableListOf<String>()
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * fraction, scale)) {
                SideEffect { pixelsPerDp = base.density * fraction }
                QuietGridTheme(false) { UpdateDialog(UpdateInstallState(release.copy(versionName = "版本"), UpdateStage.DOWNLOADING, 1, null),
                    {}, { actions += "cancel" }, {}, {}, { actions += "dismiss" }) }
            }
        }
        // Dialog's platform width is separate from the page. Calibrate the inherited
        // logical density from the measured panel to exercise its actual content edge.
        for (font in listOf(1f, 1.3f, 1.31f, 2f)) for (content in listOf(239, 240)) {
            compose.runOnIdle { scale = font; fraction = 1f }
            val panelPx = bounds("screen:update").width
            val desired = content + 0.05f
            var density = panelPx / (desired + 32)
            repeat(4) { density = (panelPx - 2 * (16 * density).roundToInt()) / desired }
            compose.runOnIdle { fraction = density / nativeDensity }
            target("update-close").performScrollTo()
            val actual = (bounds("screen:update").width - 2 * (16 * pixelsPerDp).roundToInt()) / pixelsPerDp
            assertEquals(content.toFloat(), actual, 0.5f)
            assertPair("update-download-page", "update-close", font <= 1.3f && content >= 240)
            target("update-close").performClick(); assertEquals("dismiss", actions.last())
            recordEvidence("dialog-pair font=$font content=$actual page=${bounds("update-download-page")} close=${bounds("update-close")}")
        }
    }
    private val nativeDensity: Float get() = compose.activity.resources.displayMetrics.density

    @Test fun listKindsBrandHeadersAndLandscapeRemainReachableWithAllFontsAndThemes() {
        var kind by mutableStateOf(RecordKind.RESTAURANT)
        var search by mutableStateOf(false)
        var back by mutableStateOf(false)
        val actions = mutableListOf<String>()
        render { key(kind, scale, width, height, search, back) {
            val rows = (1..20).map { RecordSummary(RecordRef(kind, "pr17-row-$it", if (kind == RecordKind.RESTAURANT) null else parent.id), "记录 $it", 2, time,
                mapOf("rating_tenths" to "47", "dish_count" to "2", "note" to "摘要")) }
            RecordListScreen(ListUiState(items = rows, query = ListQuery(kind), localScope = false), if (search) "记录" else "", ListFilters(), {}, {},
                { actions += "record" }, {}, {}, onAdd = { actions += "add" }, onBack = if (back) ({ actions += "back" }) else null,
                bottomBar = { BottomTabs("restaurants") {} })
        } }
        for (night in listOf(false, true)) for (font in listOf(1f, 1.3f, 1.31f, 2f))
            for (type in RecordKind.entries) for (landscape in listOf(false, true)) {
            configure(font, night, if (landscape) 640 else 360, if (landscape) 360 else 640)
            compose.runOnIdle { kind = type; back = type != RecordKind.RESTAURANT; search = false }
            target("page-title").assertIsDisplayed(); target("add").assertIsDisplayed().performClick()
            assertEquals("add", actions.last())
            assertTextFits(target("page-title"))
            val complete = compose.onAllNodes(hasTestTag("record:pr17-row-1") or hasTestTag("record:pr17-row-2") or hasTestTag("record:pr17-row-3"))
                .fetchSemanticsNodes().count { node -> val view = bounds("pr17:viewport"); node.boundsInRoot.top >= view.top && node.boundsInRoot.bottom <= view.bottom }
            recordEvidence("list kind=$type font=$font dark=$night landscape=$landscape first-three-complete=$complete")
            scroll("record-list", "record:pr17-row-20")
            target("record-list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            assertTrue("list tail clears floating tabs", bounds("record:pr17-row-20").bottom <= bounds("floating-tabs").top)
            save("list-${type.name.lowercase()}-${if (landscape) "landscape" else "portrait"}-tail")
        }
        configure(1f, false, 320)
        compose.runOnIdle { kind = RecordKind.RESTAURANT; search = true; back = false }
        target("add").assertIsDisplayed(); target("page-title").assertIsDisplayed()
        save("search-narrow")
    }
}
