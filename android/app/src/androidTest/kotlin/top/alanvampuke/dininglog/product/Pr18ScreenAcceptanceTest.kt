package top.alanvampuke.dininglog.product

import android.content.Context
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import top.alanvampuke.dininglog.P9VisualActivity
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.ProductViewModel
import top.alanvampuke.dininglog.feature.components.NavigationEntryId
import top.alanvampuke.dininglog.feature.components.BackgroundArgb
import top.alanvampuke.dininglog.feature.components.ForegroundArgb

@RunWith(Parameterized::class)
class Pr18ScreenAcceptanceTest(private val style: ThemeStyle, private val dark: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-dark={1}")
        fun cases() = pr18StyleCases()
    }
    @get:Rule val compose = createAndroidComposeRule<P9VisualActivity>()
    private lateinit var evidence: Pr18Evidence
    private var runtime: Pr18Runtime? = null
    @Before fun prepare() { evidence = Pr18Evidence(compose, style, dark); evidence.configure(1f) }
    @After fun restore() { evidence.close(); runtime?.close() }

    private fun launch(font: Float, authenticated: Boolean = true, adaptive: Boolean = false): Pair<Pr18Runtime, ProductViewModel?> {
        if (runtime != null) {
            evidence.renderRaw {}; runtime!!.close(); runtime = null
            compose.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        }
        evidence.configure(font)
        val next = Pr18Runtime(ApplicationProvider.getApplicationContext<Context>(), enabled = false, style = style,
            choice = if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT, authenticated = authenticated)
        runtime = next; next.render(evidence, adaptive)
        val vm = if (authenticated) next.model(evidence) else null
        evidence.stateReceipt = { compose.runOnIdle {
            val current = compose.activity.viewModelStore.get("product:pr18-owner:${next.account.generation}") as? ProductViewModel
            "expectedVm=${vm?.let { System.identityHashCode(it) }} currentVm=${current?.let { System.identityHashCode(it) }} " +
                "sameVm=${current === vm} activity=${System.identityHashCode(compose.activity)} " +
                "window=${System.identityHashCode(compose.activity.window)} generation=${next.account.generation} " +
                "listKind=${vm?.list?.value?.query?.kind} detailRef=${vm?.detail?.value?.cached?.snapshot?.ref} " +
                "currentListKind=${current?.list?.value?.query?.kind} currentDetailRef=${current?.detail?.value?.cached?.snapshot?.ref} " +
                "currentEditorRef=${current?.editor?.value?.input?.ref} " +
                "editorRef=${vm?.editor?.value?.input?.ref} errors=${vm?.editor?.value?.errors} " +
                "detailRequests=${next.repository.detailRequests.toList()} writes=${next.repository.writes.size}"
        } }
        evidence.navigationReceipt("launch")
        return next to vm
    }
    private fun boundary(stage: String, block: () -> Unit) {
        evidence.navigationReceipt("entry-before-$stage")
        try { block(); evidence.navigationReceipt("entry-after-$stage") }
        catch (failure: Throwable) { evidence.diagnose(stage, failure); throw failure }
    }
    private fun list(vm: ProductViewModel, kind: RecordKind) {
        boundary("list-$kind") {
            compose.waitUntil(15_000) { vm.list.value.query.kind == kind && !vm.list.value.isRefreshing && vm.list.value.items.isNotEmpty() }
            evidence.target("screen:${kind.name.lowercase()}:list").assertExists()
        }
    }
    private fun detail(vm: ProductViewModel, ref: RecordRef) {
        boundary("detail-$ref") {
            compose.waitUntil(15_000) { vm.detail.value.cached.snapshot?.ref == ref && !vm.detail.value.refreshing }
            evidence.target(if (ref.kind == RecordKind.REVISION) "screen:history" else "screen:detail").assertExists()
        }
    }
    private fun editor(vm: ProductViewModel, kind: RecordKind) {
        boundary("editor-$kind") {
            compose.waitUntil(15_000) { vm.editor.value.input?.ref?.kind == kind && !vm.editor.value.loading }
            evidence.target("screen:edit").assertExists()
        }
    }
    private fun openRecord(next: Pr18Runtime, vm: ProductViewModel, ref: RecordRef) {
        val entry = evidence.target("product-navigation").fetchSemanticsNode().config[NavigationEntryId]
        val requests = next.repository.detailRequests.size
        evidence.tap("record-list", "record:${ref.id}")
        detail(vm, ref)
        val after = evidence.target("product-navigation").fetchSemanticsNode().config[NavigationEntryId]
        evidence.record("record-open ref=$ref entryBefore=$entry entryAfter=$after detailCallbackDelta=${next.repository.detailRequests.size - requests}")
        assertNotEquals("record touch creates a new destination entry", entry, after)
        assertEquals("record touch requests the correct detail once", requests + 1, next.repository.detailRequests.size)
        assertEquals("record destination has the exact ref", ref, vm.detail.value.cached.snapshot?.ref)
    }
    private fun input(tag: String, text: String) {
        evidence.scroll("edit-scroll", tag)
        evidence.minimumTarget(tag)
        evidence.target(tag).performTextReplacement(text)
        evidence.target(tag).assertTextEquals(text)
        evidence.textFits(evidence.target(tag))
    }
    private fun save(next: Pr18Runtime, vm: ProductViewModel, kind: RecordKind): RecordRef {
        val ref = vm.editor.value.input!!.ref
        val before = next.repository.writes.size
        evidence.minimumTarget("save")
        evidence.target("save").assertIsEnabled().pr18Click(evidence, "save")
        compose.waitUntil(15_000) { next.repository.writes.size > before && vm.detail.value.cached.snapshot?.ref == ref &&
            !vm.detail.value.refreshing && !vm.editor.value.saving &&
            compose.onAllNodesWithTag(if (kind == RecordKind.REVISION) "history-scroll" else "detail-scroll", true).fetchSemanticsNodes().isNotEmpty() }
        evidence.record("save kind=$kind writeCallbackDelta=${next.repository.writes.size - before} ref=$ref " +
            "editorErrors=${vm.editor.value.errors}")
        assertEquals("one save callback", before + 1, next.repository.writes.size)
        assertEquals(kind, ref.kind)
        evidence.hideKeyboard()
        return ref
    }
    private fun frame(next: Pr18Runtime, screen: String, name: String, listEllipsis: Boolean = false) {
        evidence.hideKeyboard()
        val scroll = when (screen) {
            "screen:edit" -> "edit-scroll"
            "screen:detail" -> "detail-scroll"
            "screen:history" -> "history-scroll"
            "screen:settings" -> "settings-scroll"
            else -> "record-list"
        }
        // Inputs and nested lists can leave their lazy header uncomposed. Check
        // the actual input text above, then return to the header for this frame.
        compose.onNodeWithTag(scroll).performScrollToIndex(0)
        next.assertPalette(evidence, style, dark)
        val screenArgb = evidence.target(screen).fetchSemanticsNode().config[BackgroundArgb]
        val titleArgb = evidence.target("page-title").fetchSemanticsNode().config[ForegroundArgb]
        val bitmap = evidence.target("pr18:viewport").captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val background = pixels.count { it == evidence.colors.bg.toArgb() }
        val ink = pixels.count { it == evidence.colors.ink.toArgb() }
        evidence.record("screen=$screen renderedStyle=$style backgroundArgb=${evidence.colors.bg.toArgb()} " +
            "inkArgb=${evidence.colors.ink.toArgb()} backgroundPixels=$background inkPixels=$ink " +
            "screenArgb=$screenArgb titleArgb=$titleArgb viewport=${evidence.bounds("pr18:viewport")}")
        evidence.save(name)
        assertEquals("screen uses the selected palette", evidence.colors.bg.toArgb(), screenArgb)
        assertEquals("page title uses the selected palette", evidence.colors.ink.toArgb(), titleArgb)
        assertTrue("actual palette background painted", background > 250)
        assertTrue("actual palette ink painted", ink > 10)
        evidence.visibleTextFits(listEllipsis)
    }

    @Test fun f7RestaurantSearchDishVisitHistoryDetailEditAndSettingsCompleteFlowsAt100And200Percent() {
        for (font in listOf(1f, 2f)) {
            val (next, loaded) = launch(font)
            val vm = loaded!!
            try {
                list(vm, RecordKind.RESTAURANT)
                frame(next, "screen:restaurant:list", "flow-restaurants", true)
                evidence.scroll("record-list", "record:pr18-row-20")
                evidence.minimumTarget("record:pr18-row-20")
                evidence.save("flow-restaurants-last-row")
                evidence.scroll("record-list", "search")
                evidence.target("search").assertContentDescriptionEquals("搜索").performTextReplacement("兰巴赫")
                compose.waitUntil(15_000) { vm.list.value.query.query == "兰巴赫" && !vm.list.value.isRefreshing && vm.list.value.items.size == 1 }
                evidence.hideKeyboard()
                frame(next, "screen:search", "flow-search", true)
                evidence.target("search").performTextReplacement("")
                compose.waitUntil(15_000) { vm.list.value.query.query.isEmpty() && !vm.list.value.isRefreshing }
                evidence.hideKeyboard()
                openRecord(next, vm, next.repository.parent)
                frame(next, "screen:detail", "flow-restaurant-detail")
                evidence.tap("detail-scroll", "add-dish"); editor(vm, RecordKind.DISH)
                input("field:name", "新菜品"); input("field:review", "保留完整短评。")
                frame(next, "screen:edit", "flow-dish-create")
                save(next, vm, RecordKind.DISH)
                evidence.tap("detail-scroll", "back"); detail(vm, next.repository.parent)
                evidence.tap("detail-scroll", "add-visit"); editor(vm, RecordKind.VISIT)
                input("field:dining_date", "2026-11-07"); input("field:note", "同日多次到店分别保留。")
                frame(next, "screen:edit", "flow-visit-create")
                save(next, vm, RecordKind.VISIT)
                evidence.tap("detail-scroll", "back"); detail(vm, next.repository.parent)
                compose.onNodeWithTag("detail-scroll").performScrollToNode(hasText("管理店内菜品"))
                val manage = compose.onNode(hasClickAction() and hasAnyDescendant(hasText("管理店内菜品")), true)
                evidence.minimumTarget(manage, "管理店内菜品")
                evidence.touch(manage, "管理店内菜品", "detail-scroll")
                evidence.navigationReceipt("after-manage-dishes")
                list(vm, RecordKind.DISH)
                frame(next, "screen:dish:list", "flow-dishes", true)
                openRecord(next, vm, next.repository.dish)
                frame(next, "screen:detail", "flow-unrated-dish-detail")
                evidence.tap("detail-scroll", "edit"); editor(vm, RecordKind.DISH)
                evidence.scroll("edit-scroll", "rating")
                evidence.target("rating").assertContentDescriptionEquals("未评分")
                evidence.target("rating").performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
                input("field:review", "评分以 0.1 步进保存。")
                save(next, vm, RecordKind.DISH)
                assertEquals("40", next.repository.snapshot(next.repository.dish).fields["rating_tenths"])
                evidence.tap("detail-scroll", "back"); list(vm, RecordKind.DISH)
                evidence.tap("record-list", "back"); detail(vm, next.repository.parent)
                evidence.tap("detail-scroll", "visit-history"); list(vm, RecordKind.VISIT)
                frame(next, "screen:visit:list", "flow-visits", true)
                openRecord(next, vm, next.repository.visit)
                frame(next, "screen:detail", "flow-visit-detail")
                evidence.tap("detail-scroll", "back"); list(vm, RecordKind.VISIT)
                evidence.tap("record-list", "back"); detail(vm, next.repository.parent)
                evidence.tap("detail-scroll", "history"); list(vm, RecordKind.REVISION)
                frame(next, "screen:revision:list", "flow-history-list", true)
                val timeTag = "history-time:${next.repository.revision.id}"
                evidence.scroll("record-list", timeTag); evidence.minimumTarget(timeTag)
                val entryBefore = evidence.target("product-navigation").fetchSemanticsNode().config[NavigationEntryId]
                val detailRequests = next.repository.detailRequests.size
                evidence.touch(evidence.target(timeTag), timeTag, "record-list")
                evidence.target("history-absolute:${next.repository.revision.id}").assertExists()
                val entryAfterTime = evidence.target("product-navigation").fetchSemanticsNode().config[NavigationEntryId]
                evidence.record("nested-history-time entryBefore=$entryBefore entryAfter=$entryAfterTime " +
                    "detailCallbackDelta=${next.repository.detailRequests.size - detailRequests}")
                assertEquals("time tap does not open card", entryBefore, entryAfterTime)
                assertEquals("time tap does not request detail", detailRequests, next.repository.detailRequests.size)
                val card = evidence.target("record:${next.repository.revision.id}")
                evidence.touch(card, "history-card", "record-list") { node ->
                    val full = pr18FullBounds(node)
                    Offset(full.center.x, full.top + 8 * evidence.density)
                }
                detail(vm, next.repository.revision)
                val entryAfterCard = evidence.target("product-navigation").fetchSemanticsNode().config[NavigationEntryId]
                evidence.record("history-card entryBefore=$entryBefore entryAfter=$entryAfterCard " +
                    "detailCallbackDelta=${next.repository.detailRequests.size - detailRequests}")
                assertNotEquals("card tap opens history detail", entryBefore, entryAfterCard)
                assertEquals("card open requests detail once", detailRequests + 1, next.repository.detailRequests.size)
                frame(next, "screen:history", "flow-history-detail")
                evidence.tap("history-scroll", "history-note"); editor(vm, RecordKind.REVISION)
                input("field:note", "保留历史说明。")
                frame(next, "screen:edit", "flow-history-note-edit")
                save(next, vm, RecordKind.REVISION)
                evidence.tap("history-scroll", "reapply")
                compose.waitUntil(15_000) { vm.apply.value != null }
                evidence.target("apply-save").assertIsNotEnabled()
                evidence.target("apply-field:rating_tenths").performScrollTo().pr18Click(evidence, "apply-field:rating_tenths")
                val beforeApply = next.repository.writes.size
                evidence.minimumTarget("apply-save"); evidence.target("apply-save").pr18Click(evidence, "apply-save")
                compose.waitUntil(15_000) { vm.apply.value == null && next.repository.writes.size > beforeApply }
                evidence.record("apply writeCallbackDelta=${next.repository.writes.size - beforeApply}")
                assertEquals(beforeApply + 1, next.repository.writes.size)
                evidence.target("tab:settings").pr18Click(evidence, "tab:settings")
                compose.waitUntil(15_000) { vm.settings.value.profile != null && !vm.settings.value.loading }
                frame(next, "screen:settings", "flow-settings")
                evidence.tap("settings-scroll", "logout")
                evidence.visibleTextFits(); evidence.save("flow-logout-confirm", dialog = true)
                val savedSession = next.account.session.value
                compose.onNodeWithText("取消").pr18Click(evidence, "取消")
                assertEquals("cancel logout keeps the session", savedSession, next.account.session.value)
            } catch (failure: Throwable) { evidence.diagnose("complete-flow", failure); throw failure }
        }
    }

    @Test fun f7NarrowLandscapeImeKeepsActualSaveTargetAboveKeyboardAtAllFontBranches() {
        for (font in listOf(1f, 1.3f, 1.31f, 2f)) {
            val (next, loaded) = launch(font, adaptive = true)
            val vm = loaded!!
            try {
                evidence.landscape()
                next.render(evidence, adaptive = true)
                compose.activityRule.scenario.onActivity { it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
                list(vm, RecordKind.RESTAURANT)
                openRecord(next, vm, next.repository.parent)
                evidence.tap("detail-scroll", "edit"); editor(vm, RecordKind.RESTAURANT)
                input("field:name", "窄屏输入测试")
                compose.waitUntil(15_000) { evidence.keyboardBounds()?.height()?.let { it > 0 } == true }
                val keyboards = evidence.keyboardRectangles()
                assertTrue("actual IME rectangles are available", keyboards.isNotEmpty())
                val save = evidence.screenBounds("save")
                val viewport = evidence.screenBounds("pr18:viewport")
                val before = next.repository.writes.size
                evidence.record("landscape-IME keyboardsScreen=$keyboards saveScreen=$save viewportScreen=$viewport viewportWidthDp=${viewport.width / evidence.density}")
                evidence.save("landscape-ime", dialog = true)
                evidence.minimumTarget("save")
                assertEquals("narrow 360dp input flow", 360f, viewport.width / evidence.density, .5f)
                assertTrue("save clears actual IME", pr18ClearsIme(Pr18Rect(save.left, save.top, save.right, save.bottom),
                    keyboards.map { Pr18Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) },
                    .5f * evidence.density))
                assertTrue("save is within visible viewport", save.top >= viewport.top)
                val touch = evidence.touch(evidence.target("save"), "landscape-ime-save")
                compose.waitUntil(15_000) { next.repository.writes.size > before }
                evidence.record("landscape-IME saveCallbackDelta=${next.repository.writes.size - before}")
                assertEquals(before + 1, next.repository.writes.size)
                touch.requireCallbackDelta(before, next.repository.writes.size)
                evidence.hideKeyboard(); evidence.portrait()
            } catch (failure: Throwable) { evidence.diagnose("narrow-landscape-ime", failure); throw failure }
        }
    }

    @Test fun f7EveryListAndSearchRemainReachableInBothLandscapeDirectionsAtAllFontBranches() {
        for (font in listOf(1f, 1.3f, 1.31f, 2f)) {
            val (next, loaded) = launch(font)
            val vm = loaded!!
            try {
                for (reverse in listOf(false, true)) {
                    evidence.landscape(reverse); next.render(evidence)
                    evidence.target("tab:restaurants").pr18Click(evidence, "tab:restaurants"); list(vm, RecordKind.RESTAURANT)
                    compose.onNodeWithTag("record-list").performScrollToIndex(0)
                    frame(next, "screen:restaurant:list", "landscape-restaurants-reverse$reverse", true)
                    evidence.scroll("record-list", "record:pr18-row-20")
                    evidence.minimumTarget("record:pr18-row-20")
                    openRecord(next, vm, next.repository.parent)
                    compose.onNodeWithTag("detail-scroll").performScrollToNode(hasText("管理店内菜品"))
                    val manage = compose.onNode(hasClickAction() and hasAnyDescendant(hasText("管理店内菜品")), true)
                    evidence.minimumTarget(manage, "landscape-管理店内菜品")
                    evidence.touch(manage, "landscape-管理店内菜品", "detail-scroll")
                    evidence.navigationReceipt("after-landscape-manage-dishes")
                    list(vm, RecordKind.DISH)
                    frame(next, "screen:dish:list", "landscape-dishes-reverse$reverse", true)
                    openRecord(next, vm, next.repository.dish)
                    evidence.tap("detail-scroll", "back"); list(vm, RecordKind.DISH)
                    evidence.tap("record-list", "back"); detail(vm, next.repository.parent)
                    evidence.tap("detail-scroll", "visit-history"); list(vm, RecordKind.VISIT)
                    frame(next, "screen:visit:list", "landscape-visits-reverse$reverse", true)
                    evidence.tap("record-list", "back"); detail(vm, next.repository.parent)
                    evidence.tap("detail-scroll", "history"); list(vm, RecordKind.REVISION)
                    frame(next, "screen:revision:list", "landscape-history-reverse$reverse", true)
                    evidence.scroll("record-list", "history-time:${next.repository.revision.id}")
                    evidence.minimumTarget("history-time:${next.repository.revision.id}")
                    evidence.target("tab:restaurants").pr18Click(evidence, "tab:restaurants"); list(vm, RecordKind.RESTAURANT)
                    evidence.scroll("record-list", "search")
                    evidence.target("search").performTextReplacement("兰巴赫")
                    compose.waitUntil(15_000) { vm.list.value.query.query == "兰巴赫" && !vm.list.value.isRefreshing }
                    evidence.hideKeyboard()
                    compose.onNodeWithTag("record-list").performScrollToIndex(0)
                    frame(next, "screen:search", "landscape-search-reverse$reverse", true)
                    evidence.scroll("record-list", "search"); evidence.target("search").performTextReplacement("")
                    compose.waitUntil(15_000) { vm.list.value.query.query.isEmpty() && !vm.list.value.isRefreshing }
                    evidence.hideKeyboard()
                }
                evidence.portrait()
            } catch (failure: Throwable) { evidence.diagnose("landscape-lists", failure); throw failure }
        }
    }

    @Test fun f7ProductionLoginRegistrationRecoveryAndAcknowledgementAt100And200Percent() {
        for (font in listOf(1f, 2f)) for (page in listOf("登录", "注册账号", "恢复账号")) {
            val (next, _) = launch(font, authenticated = false, adaptive = true)
            if (page != "登录") compose.onNodeWithText(page).performScrollTo().pr18Click(evidence, page)
            val fields = listOf("auth-username" to "pr18_user") +
                (if (page == "恢复账号") listOf("auth-recovery" to "pr18-public-recovery-fixture") else emptyList()) +
                listOf("auth-password" to "Pr18 fixture passphrase")
            for ((tag, value) in fields) {
                evidence.target(tag).performScrollTo()
                evidence.minimumTarget(tag)
                evidence.target(tag).performTextReplacement(value)
                evidence.textFits(evidence.target(tag))
            }
            evidence.hideKeyboard()
            next.assertPalette(evidence, style, dark, authenticated = false)
            evidence.visibleTextFits()
            val protectedFrame = evidence.probeProtected("auth-${page}-form")
            val before = next.auth.logins.get() + next.auth.registrations.get() + next.auth.recoveries.get()
            evidence.target("auth-submit").performScrollTo()
            evidence.minimumTarget("auth-submit"); evidence.target("auth-submit").assertIsEnabled().pr18Click(evidence, "auth-submit")
            compose.waitUntil(15_000) { next.account.session.value != null }
            val after = next.auth.logins.get() + next.auth.registrations.get() + next.auth.recoveries.get()
            evidence.record("auth page=$page submitCallbackDelta=${after - before} sessionInstalled=${next.account.session.value != null}")
            assertEquals("one authentication submission", before + 1, after)
            if (page != "登录") {
                assertEquals(8, next.account.session.value!!.recoveryCodes.size)
                val acknowledge = compose.onNodeWithText("我已安全保存").performScrollTo()
                evidence.minimumTarget(acknowledge, "我已安全保存")
                evidence.visibleTextFits()
                acknowledge.pr18Click(evidence, "我已安全保存")
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("product-navigation", true).fetchSemanticsNodes().isNotEmpty() }
            next.assertPalette(evidence, style, dark)
            evidence.record("auth page=$page production-navigation reached after acknowledgement")
            evidence.save("auth-${page}-completed", dialog = true)
            protectedFrame.requireValid()
        }
    }
}
