package top.alanvampuke.dininglog.product

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.core.auth.*
import top.alanvampuke.dininglog.feature.*
import top.alanvampuke.dininglog.feature.components.GridText
import top.alanvampuke.dininglog.feature.restaurants.*
import top.alanvampuke.dininglog.feature.revisions.RevisionDetailScreen
import top.alanvampuke.dininglog.feature.settings.SettingsScreen
import top.alanvampuke.dininglog.feature.theme.*

@RunWith(AndroidJUnit4::class)
class Pr16UiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun invalidFirstVisitDateIsRevealedAfterFoldingAndSavingAndStaysVisibleWhenFoldedAgain() {
        lateinit var fixture: EditorFixture
        compose.runOnIdle { fixture = EditorFixture() }
        try {
            compose.setContent { QuietGridTheme(true) {
                val state by fixture.vm.editor.collectAsState()
                EditScreen(state, fixture.vm::editField, fixture.vm::editFirstVisit, fixture.vm::saveEditor, {})
            } }
            scrollEditor("first-visit-toggle")
            compose.onNodeWithTag("field:first-visit-date").assertDoesNotExist()
            compose.onNodeWithTag("first-visit-toggle").performClick()
            scrollEditor("field:first-visit-date")
            compose.onNodeWithTag("field:first-visit-date").performTextReplacement("2026-13-45")
            compose.onNodeWithText("YYYY-MM-DD", useUnmergedTree = true).assertExists()
            scrollEditor("first-visit-toggle")
            compose.onNodeWithTag("first-visit-toggle").performClick()
            compose.onNodeWithTag("field:first-visit-date").assertDoesNotExist()
            compose.onNodeWithTag("save").performClick()
            compose.runOnIdle {
                assertEquals(0, fixture.repository.submissions)
                assertEquals("2026-13-45", fixture.vm.editor.value.input!!.firstVisit!!.date)
                assertTrue(fixture.vm.editor.value.errors.containsKey("first_visit"))
            }
            scrollEditor("field:first-visit-date")
            compose.onNodeWithTag("field:first-visit-date").assertIsDisplayed()
            val error = "请输入有效首访日期 YYYY-MM-DD（1900–2100）"
            compose.onNodeWithText(error, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            scrollEditor("first-visit-toggle")
            compose.onNodeWithTag("first-visit-toggle").performClick()
            scrollEditor("first-visit-date-error")
            compose.onNodeWithTag("first-visit-date-error").assertIsDisplayed().assertTextEquals(error)
            compose.onNodeWithTag("save").performClick()
            compose.onNodeWithTag("first-visit-date-error").assertExists()
            compose.runOnIdle { assertEquals(0, fixture.repository.submissions) }
        } finally { compose.runOnIdle { fixture.close() } }
    }

    @Test fun invalidFirstVisitNoteRemainsVisibleAfterSaveAndCompositionRestoration() {
        lateinit var fixture: EditorFixture
        compose.runOnIdle { fixture = EditorFixture() }
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { QuietGridTheme(true) {
                val state by fixture.vm.editor.collectAsState()
                EditScreen(state, fixture.vm::editField, fixture.vm::editFirstVisit, fixture.vm::saveEditor, {})
            } }
            scrollEditor("first-visit-toggle")
            compose.onNodeWithTag("first-visit-toggle").performClick()
            scrollEditor("field:first-visit-note")
            compose.onNodeWithTag("field:first-visit-note").performTextReplacement("x".repeat(2001))
            scrollEditor("first-visit-toggle")
            compose.onNodeWithTag("first-visit-toggle").performClick()
            compose.onNodeWithTag("save").performClick()
            scrollEditor("field:first-visit-note")
            compose.onNodeWithTag("field:first-visit-note").assertExists()
            restoration.emulateSavedInstanceStateRestore()
            scrollEditor("field:first-visit-note")
            val error = "首访备注最多 2000 个字符，不能含空字符"
            compose.onNodeWithText(error, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(0, fixture.repository.submissions)
                assertEquals(2001, fixture.vm.editor.value.input!!.firstVisit!!.note!!.length)
            }
        } finally { compose.runOnIdle { fixture.close() } }
    }

    @Test fun historyTimeHasBothActionsAndStableHeightAtLargeFontWithoutOpeningTheCard() {
        val timestamp = Instant.now().plusSeconds(3600).toString()
        val row = RecordSummary(RecordRef(RecordKind.REVISION, "time"), "餐厅", 1, timestamp,
            mapOf("entity_label" to "餐厅", "action" to "create"))
        var opened = 0
        compose.setContent { QuietGridTheme(true) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Column(Modifier.width(240.dp)) {
                    RecordRow(row, { opened++ })
                    GridText("下一条", Modifier.testTag("following-row"))
                }
            }
        } }
        val toggle = compose.onNodeWithTag("history-time:time", useUnmergedTree = true)
        toggle.assertHasClickAction().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "相对时间"))
        assertEquals("切换为绝对时间", toggle.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        assertTrue(toggle.getUnclippedBoundsInRoot().height >= 48.dp)
        compose.onNodeWithTag("history-relative:time", useUnmergedTree = true).assertTextEquals("刚刚")
        compose.onNodeWithTag("record-summary:time", useUnmergedTree = true).assertTextEquals("新增记录")
        val nextTop = compose.onNodeWithTag("following-row").getUnclippedBoundsInRoot().top
        // Exercise the real touch path so the parent card must not navigate.
        toggle.performTouchInput { click(center) }
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "绝对时间"))
        assertEquals("切换为相对时间", toggle.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        compose.onNodeWithTag("history-relative:time", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("history-absolute:time", useUnmergedTree = true).assertTextEquals(formatAbsoluteTime(timestamp))
        assertEquals(nextTop, compose.onNodeWithTag("following-row").getUnclippedBoundsInRoot().top)
        compose.runOnIdle { assertEquals(0, opened) }
        toggle.performClick()
        compose.onNodeWithTag("history-relative:time", useUnmergedTree = true).assertTextEquals("刚刚")
        compose.onNodeWithTag("record:time").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun historyDetailAlsoReplacesTheTimeInsteadOfAddingAnotherLine() {
        val timestamp = Instant.now().plusSeconds(3600).toString()
        val record = RecordSnapshot(RecordRef(RecordKind.REVISION, "detail-time"), 1,
            mapOf("created_at" to timestamp, "entity_label" to "餐厅"), timestamp)
        compose.setContent { QuietGridTheme(true) {
            RevisionDetailScreen(DetailUiState(CachedDetail(record)), {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("history-time").assertTextEquals("刚刚").performClick()
        compose.onNodeWithTag("absolute-time", useUnmergedTree = true).assertTextEquals(formatAbsoluteTime(timestamp))
        compose.onAllNodesWithText("刚刚", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("history-time").performClick().assertTextEquals("刚刚")
    }

    @Test fun loadedStatsLabelTheirPageScopeAndFollowPaginationMissingCountsAndEmptyCache() {
        val all = (1..21).map { id -> RecordSummary(RecordRef(RecordKind.RESTAURANT, "stats-$id"), "餐厅 $id", 1,
            "2026-11-01T00:00:00Z", mapOf("dish_count" to if (id == 21) "2" else "0")) }
        var rows by mutableStateOf(all.take(20))
        compose.setContent { QuietGridTheme(true) { LoadedStats(rows) } }
        for (label in listOf("已加载餐厅", "已加载均分", "已加载菜品")) compose.onNodeWithText(label).assertExists()
        compose.onNodeWithText("20 家").assertExists()
        compose.onNodeWithText("0 道").assertExists()
        compose.runOnIdle { rows = all }
        compose.onNodeWithText("21 家").assertExists()
        compose.onNodeWithText("2 道").assertExists()
        compose.runOnIdle { rows = all + all.first().copy(fields = emptyMap()) }
        compose.onNodeWithText("未提供").assertExists()
        compose.runOnIdle { rows = emptyList() }
        for (label in listOf("已加载餐厅", "已加载均分", "已加载菜品")) compose.onNodeWithText(label).assertExists()
        compose.onNodeWithText("0 家").assertExists()
    }

    @Test fun restaurantAndDishChipsUseTheirLabelsAndExactInclusiveBoundaries() {
        var kind by mutableStateOf(RecordKind.RESTAURANT)
        var filters by mutableStateOf(ListFilters())
        compose.setContent { QuietGridTheme(true) {
            val rows = listOf(31, 32, 45, 46, null).map { rating ->
                RecordSummary(RecordRef(kind, "rating-${rating ?: "null"}"), "边界 $rating", 1,
                    "2026-11-01T00:00:00Z", mapOf("rating_tenths" to rating?.toString()))
            }
            val query = ListQuery(kind, filters = filters)
            RecordListScreen(ListUiState(items = localSearch(query, rows, emptyList()), query = query, localScope = false),
                "", filters, {}, { filters = it }, {}, {}, {}, onBack = {})
        } }
        for (nextKind in listOf(RecordKind.RESTAURANT, RecordKind.DISH)) {
            compose.runOnIdle { kind = nextKind; filters = ListFilters() }
            val top = if (nextKind == RecordKind.DISH) "惊艳 ★4.6+" else "封神必吃 ★4.6+"
            val low = if (nextKind == RecordKind.DISH) "踩雷 ★3.1−" else "踩雷预警 ★3.1−"
            compose.onNodeWithText(top).performScrollTo().performClick()
            compose.runOnIdle { assertEquals(ListFilters(minRating = 46), filters) }
            compose.onNodeWithTag("record:rating-46").assertExists()
            for (excluded in listOf("31", "32", "45", "null")) compose.onNodeWithTag("record:rating-$excluded").assertDoesNotExist()
            compose.onNodeWithText(low).performScrollTo().performClick()
            compose.runOnIdle { assertEquals(ListFilters(maxRating = 31), filters) }
            compose.onNodeWithTag("record:rating-31").assertExists()
            for (excluded in listOf("32", "45", "46", "null")) compose.onNodeWithTag("record:rating-$excluded").assertDoesNotExist()
        }
    }

    @Test fun currentSessionConfirmationSaysLogoutAndOtherSessionsStillSayRevoke() {
        val sessions = listOf(
            DeviceSession("current", "当前手机", "2026-11-01", "2026-11-01", "2026-12-01", true),
            DeviceSession("other", "其他手机", "2026-11-01", "2026-11-01", "2026-12-01", false))
        var revoked: String? = null
        compose.setContent { QuietGridTheme(true) {
            SettingsScreen(SettingsUiState(sessions = sessions), "pr16_owner", ThemeChoice.DARK, "fixture",
                {}, { revoked = it }, { _, _ -> }, {}, {}, {})
        } }
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("revoke:current"))
        compose.onNodeWithTag("revoke:current").performClick()
        compose.onNodeWithText("退出登录？").assertIsDisplayed()
        compose.onNodeWithText("确认退出").performClick()
        compose.runOnIdle { assertEquals("current", revoked) }
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("revoke:other"))
        compose.onNodeWithTag("revoke:other").performClick()
        compose.onNodeWithText("撤销此会话？").assertIsDisplayed()
        compose.onNodeWithText("确认撤销").performClick()
        compose.runOnIdle { assertEquals("other", revoked) }
    }

    private fun scrollEditor(tag: String) {
        compose.onNodeWithTag("edit-scroll").performScrollToNode(hasTestTag(tag))
    }

    private class EditorRepository : ProductRepository {
        var submissions = 0
        override val connectivity = flowOf(true)
        override fun observeList(accountId: String, query: ListQuery) = flowOf(CachedPage())
        override suspend fun refreshList(accountId: String, query: ListQuery, loadMore: Boolean) { }
        override fun observeDetail(accountId: String, ref: RecordRef) = flowOf(CachedDetail())
        override suspend fun refreshDetail(accountId: String, ref: RecordRef): RecordSnapshot = error("Unexpected fixture read")
        override suspend fun submit(input: SaveInput): SaveOutcome {
            submissions++
            return SaveOutcome.Saved(RecordSnapshot(input.ref, 1, input.localFields, "2026-11-01T00:00:00Z"))
        }
    }

    private class EditorFixture {
        val repository = EditorRepository()
        private val file = object : AtomicTokenFile {
            private var data: ByteArray? = null
            override fun read() = data
            override fun replace(bytes: ByteArray) { data = bytes }
            override fun delete() { data = null }
        }
        private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        private val keys = object : KeyManager { override fun existingKey() = key; override fun encryptionKey() = key }
        private val account = AccountSession(SecureTokenStore(file, AesGcmTokenCipher(keys, "pr16-public-fixture")), AccountMemory())
        private val security = object : AccountSecurity {
            override suspend fun profile() = AccountInfo("pr16-owner", "pr16_owner", "食记用户", "2026-11-01")
            override suspend fun sessions() = emptyList<DeviceSession>()
            override suspend fun revokeSession(id: String) { }
            override suspend fun changePassword(current: String, replacement: String) { }
        }
        val vm = ProductViewModel("pr16-owner", repository, security, account)
        init {
            vm.openEditor(RecordRef(RecordKind.RESTAURANT, "pr16-first-visit"), true)
            vm.editField("name", "首访验证小馆")
        }
        fun close() { runBlocking { account.clear() } }
    }
}
