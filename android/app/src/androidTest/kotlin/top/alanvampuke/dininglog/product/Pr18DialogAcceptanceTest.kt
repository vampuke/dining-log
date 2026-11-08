package top.alanvampuke.dininglog.product

import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import top.alanvampuke.dininglog.P9VisualActivity
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.*
import top.alanvampuke.dininglog.feature.components.*
import top.alanvampuke.dininglog.feature.revisions.*
import top.alanvampuke.dininglog.feature.settings.*

@RunWith(Parameterized::class)
class Pr18DialogAcceptanceTest(private val style: ThemeStyle, private val dark: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-dark={1}")
        fun cases() = pr18StyleCases()
    }
    @get:Rule val compose = createAndroidComposeRule<P9VisualActivity>()
    private lateinit var evidence: Pr18Evidence
    private val fonts = listOf(1f, 1.3f, 1.31f, 2f)
    private val ref = RecordRef(RecordKind.RESTAURANT, "pr18-dialog-record")
    private val time = "2026-11-07T08:00:00Z"
    private val address = "中山路沿江街区，经过桥边的咖啡店后向右转。".repeat(16)
    private val base = RecordSnapshot(ref, 1, mapOf("name" to "街角小馆", "category" to "restaurant", "address" to "中山路 123 号",
        "rating_tenths" to "39", "review" to "原来的评价"), time)
    private val input = SaveInput("pr18-owner", ref, WriteAction.UPDATE, base,
        base.editable + mapOf("rating_tenths" to "40", "address" to address))
    @Before fun prepare() { evidence = Pr18Evidence(compose, style, dark); evidence.configure(1f); evidence.render {} }
    @After fun restore() { evidence.close() }

    private fun dialog(tag: String?, title: String? = "dialog-title") {
        val count = compose.onAllNodes(isDialog(), true).fetchSemanticsNodes().size
        val panel = if (tag == null) compose.onNode(isDialog(), true).fetchSemanticsNode().layoutInfo.coordinates
            else evidence.target(tag).fetchSemanticsNode().layoutInfo.coordinates
        evidence.record("dialog tag=$tag actualWindowCount=$count resourcesFont=${compose.activity.resources.configuration.fontScale} " +
            "measuredSize=${panel.size}")
        assertEquals("one real dialog window", 1, count)
        evidence.assertRendered(titleTag = null)
        if (title != null) {
            val text = compose.onNode(hasTestTag(title) and hasAnyAncestor(isDialog()), true)
            val actual = text.fetchSemanticsNode().config[ForegroundArgb]
            evidence.record("dialog title=$title actualForegroundArgb=$actual expectedArgb=${evidence.colors.ink.toArgb()}")
            assertEquals(evidence.colors.ink.toArgb(), actual)
            assertTrue("title belongs to the dialog owner", hasAnyAncestor(isDialog()).matches(text.fetchSemanticsNode()))
            evidence.textFits(text)
        }
        evidence.visibleTextFits()
    }

    private fun tap(tag: String) {
        evidence.target(tag).performScrollTo()
        evidence.minimumTarget(tag)
        evidence.target(tag).assertIsDisplayed().pr18Click(evidence, tag)
    }

    private fun mergedButton(tag: String): SemanticsNodeInteraction {
        val physical = evidence.target(tag).fetchSemanticsNode()
        val merged = compose.onNodeWithTag(tag, useUnmergedTree = false)
        val label = merged.fetchSemanticsNode()
        assertEquals("label belongs to the same physical Button", physical.id, label.id)
        assertTrue("label belongs to the same owner root", physical.root === label.root)
        assertEquals("merged Button retains physical bounds", pr18FullBounds(physical), pr18FullBounds(label))
        assertEquals(Role.Button, physical.config[SemanticsProperties.Role])
        evidence.record("button-label tag=$tag physical=${physical.id} unmerged=${physical.config} merged=${label.config}")
        return merged
    }

    private fun scrollComparisonVerticallyTo(tag: String) {
        // performScrollTo in Compose 1.8.2 only drives the nearest scroll
        // parent: ComparisonColumns' horizontal scroll, not ReviewDialog's Y.
        val scroller = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            hasAnyDescendant(hasTestTag(tag)), true)
        val viewport = scroller.fetchSemanticsNode().boundsInRoot
        val full = evidence.taggedBounds(tag)
        val range = scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val delta = (full.top - viewport.top) * if (range.reverseScrolling) -1f else 1f
        evidence.record("comparison-scroll tag=$tag full=$full viewport=$viewport verticalDeltaPx=$delta")
        scroller.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
    }

    @Test fun f7UpdateAllStagesUseRealSystemFontAndReachableActionsAtEveryBranch() {
        val release = pr18Release(name = "下一版本").copy(notes = "详细更新说明，继续记录每一次到店。\n".repeat(45))
        val stages = listOf(UpdateInstallState(release),
            UpdateInstallState(release, UpdateStage.DOWNLOADING, 512, 1024),
            UpdateInstallState(release, UpdateStage.DOWNLOADING, 512, null),
            UpdateInstallState(release, UpdateStage.VERIFYING, 1024, 1024),
            UpdateInstallState(release, UpdateStage.READY, packageVerified = true),
            UpdateInstallState(release, UpdateStage.NEED_PERMISSION, message = "请允许安装未知应用后返回", packageVerified = true),
            UpdateInstallState(release, UpdateStage.INSTALLING, packageVerified = true),
            UpdateInstallState(release, UpdateStage.FAILED, message = "下载失败，请重试或打开下载页"),
            UpdateInstallState(release, UpdateStage.FAILED, message = "安装失败，可继续安装", packageVerified = true),
            UpdateInstallState(release, UpdateStage.CANCELED))
        for (font in fonts) {
            evidence.configure(font)
            for ((index, state) in stages.withIndex()) {
                var shown by mutableStateOf(true)
                val actions = mutableListOf<String>()
                evidence.render {
                    if (shown) UpdateDialog(state, { actions += "download" }, { actions += "cancel" },
                        { actions += "install" }, { actions += "page" }, { actions += "dismiss"; shown = false })
                }
                try {
                    dialog("screen:update", null)
                    val title = compose.onNode(hasText("发现新版本 ${release.versionName}") and hasAnyAncestor(isDialog()), true)
                    evidence.textFits(title)
                    val range = evidence.target("update-scroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                    evidence.record("update stage=${state.stage} scroll=${range.value()} max=${range.maxValue()}")
                    assertTrue("long notes require real scroll reachability", range.maxValue() > 0)
                    if (state.busy) {
                        val before = actions.size
                        tap("update-cancel")
                        evidence.record("update-cancel callbackDelta=${actions.size - before}")
                        assertEquals(before + 1, actions.size); assertEquals("cancel", actions.last())
                        evidence.target("update-progress").assertTextEquals(if (state.stage == UpdateStage.VERIFYING) "正在校验安装包…"
                            else if (state.percent == null) "正在下载 · 百分比待确认" else "正在下载 · 50%")
                    } else evidence.target("update-cancel").assertDoesNotExist()
                    val primary = when (state.stage) {
                        UpdateStage.IDLE -> "update-now" to "download"
                        UpdateStage.FAILED, UpdateStage.CANCELED -> if (state.packageVerified) "update-install" to "install" else "update-retry" to "download"
                        UpdateStage.READY, UpdateStage.NEED_PERMISSION, UpdateStage.INSTALLING -> "update-install" to "install"
                        else -> null
                    }
                    if (primary != null) {
                        val before = actions.size
                        tap(primary.first)
                        evidence.record("update primary=${primary.first} callbackDelta=${actions.size - before}")
                        assertEquals(before + 1, actions.size); assertEquals(primary.second, actions.last())
                    }
                    val beforePage = actions.size
                    tap("update-download-page")
                    assertEquals(beforePage + 1, actions.size); assertEquals("page", actions.last())
                    evidence.target("update-close").performScrollTo()
                    // Reaching the button does not expose the trailing padding:
                    // performScrollTo stops at the button's edge. Measure the
                    // footer only after the real scroller reaches its end.
                    val beforeFooterScroll = range.value()
                    evidence.target("update-scroll").performSemanticsAction(SemanticsActions.ScrollBy) {
                        it(0f, range.maxValue() - range.value())
                    }
                    val page = evidence.taggedBounds("update-download-page")
                    val close = evidence.taggedBounds("update-close")
                    val panel = evidence.taggedBounds("screen:update")
                    val contentWidth = panel.width / evidence.density - 32
                    val horizontal = font <= 1.3f && contentWidth >= 240
                    evidence.record("update-pair stage=${state.stage} contentDp=$contentWidth horizontal=$horizontal " +
                        "page=$page close=$close panel=$panel bottomGapDp=${(panel.bottom - close.bottom) / evidence.density}")
                    evidence.record("update-pair-inner page=${evidence.bounds("update-download-page")} " +
                        "close=${evidence.bounds("update-close")} panel=${evidence.bounds("screen:update")}")
                    evidence.record("update-footer-scroll before=$beforeFooterScroll after=${range.value()} max=${range.maxValue()}")
                    assertEquals("footer measured at actual scroll end", range.maxValue(), range.value(), .5f * evidence.density)
                    evidence.save("update-stage-$index-footer", dialog = true)
                    evidence.visibleTextFits()
                    if (horizontal) {
                        assertTrue("update actions share row at <=1.3", page.top < close.bottom && close.top < page.bottom)
                        assertTrue("close follows page", close.left >= page.right)
                    } else assertTrue("update actions stack above 1.3", close.top >= page.bottom)
                    assertEquals("retained 16dp dialog footer clearance", 16f,
                        (panel.bottom - close.bottom) / evidence.density, .5f)
                    mergedButton("update-close").assertTextEquals(if (state.busy) "后台继续下载" else "稍后")
                    evidence.minimumTarget("update-close")
                    val beforeDismiss = actions.size
                    evidence.touch(evidence.target("update-close"), "update-close", "update-scroll")
                    evidence.record("update-close callbackDelta=${actions.size - beforeDismiss}")
                    assertEquals(beforeDismiss + 1, actions.size); assertEquals("dismiss", actions.last())
                    evidence.target("screen:update").assertDoesNotExist()
                } catch (failure: Throwable) { evidence.diagnose("update-stage-$index", failure); throw failure }
            }
        }
    }

    @Test fun f7PasswordDialogUsesRealFontAndKeepsInputCallbacksSaveAndSecurity() {
        for (font in fonts) {
            evidence.configure(font)
            var state by mutableStateOf(SettingsUiState())
            var inputs = 0
            var saves = 0
            evidence.render {
                SettingsScreen(state, "食记用户", ThemeChoice.SYSTEM, "测试版本",
                    onPassword = { _, _ -> saves++; state = state.copy(passwordRevision = state.passwordRevision + 1) },
                    onLogout = {}, onTheme = {}, bottomBar = {}, style = style,
                    onPasswordInput = { tag, value ->
                        inputs++
                        state = state.copy(passwordInputs = when (tag) {
                            "current-password" -> state.passwordInputs.copy(current = value)
                            "new-password" -> state.passwordInputs.copy(replacement = value)
                            else -> state.passwordInputs.copy(repeated = value)
                        })
                    })
            }
            try {
                evidence.tap("settings-scroll", "change-password")
                dialog("screen:password", "page-title")
                evidence.target("password-save").assertIsNotEnabled()
                for ((tag, value) in listOf("current-password" to "Pr18 current fixture",
                    "new-password" to "Pr18 replacement fixture", "repeat-password" to "Pr18 replacement fixture")) {
                    val before = inputs
                    evidence.target(tag).performScrollTo()
                    evidence.minimumTarget(tag)
                    evidence.target(tag).performTextReplacement(value)
                    evidence.record("password-input tag=$tag callbackDelta=${inputs - before}")
                    assertEquals(before + 1, inputs)
                    evidence.textFits(evidence.target(tag))
                }
                evidence.hideKeyboard()
                evidence.minimumTarget("password-save")
                val secure = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                evidence.record("password secureHost=$secure inputCallbacks=$inputs saveCallbacks=$saves")
                val protectedFrame = evidence.probeProtected("password-real-window")
                evidence.visibleTextFits()
                assertTrue("password keeps production screenshot protection", secure)
                val before = saves
                evidence.target("password-save").assertIsEnabled().pr18Click(evidence, "password-save")
                compose.runOnIdle { evidence.record("password-save callbackDelta=${saves - before}"); assertEquals(before + 1, saves) }
                evidence.target("screen:password").assertDoesNotExist()
                protectedFrame.requireValid()
            } catch (failure: Throwable) { evidence.diagnose("password", failure); throw failure }
        }
    }

    @Test fun f7ConflictComparisonChoiceAndSaveRemainScrollableAndExplicitAtAllFonts() {
        val server = base.copy(version = 2, fields = base.fields + ("rating_tenths" to "50"))
        val comparison = compareConflict(input, server)
        for (font in fonts) {
            evidence.configure(font)
            var shown by mutableStateOf(true)
            var saves = 0
            var chosen = emptyMap<String, ConflictChoice>()
            evidence.render { if (shown) ConflictDialog(comparison, false, { choices -> saves++; chosen = choices; shown = false }, { shown = false }) }
            try {
                dialog("screen:conflict")
                evidence.target("confirm-merge").assertIsNotEnabled()
                evidence.minimumTarget("confirm-merge")
                val beforeDisabled = saves
                evidence.target("confirm-merge").pr18Click(evidence, "disabled-confirm-merge")
                assertEquals("disabled merge callback delta is zero", beforeDisabled, saves)
                val local = compose.onNodeWithText("使用未提交值").performScrollTo()
                evidence.minimumTarget(local, "使用未提交值"); local.pr18Click(evidence, "使用未提交值")
                evidence.target("confirm-merge").assertIsEnabled()
                val expander = evidence.target("expand:compare:address:1")
                mergedButton("expand:compare:address:1").assertTextEquals("展开全文")
                evidence.minimumTarget(expander, "expand:compare:address:1")
                evidence.touch(expander, "expand:compare:address:1", horizontal = true)
                compose.waitForIdle()
                evidence.record("after-touch name=expand:compare:address:1 ${pr18TouchDetails(expander.fetchSemanticsNode())}")
                mergedButton("expand:compare:address:1").assertTextEquals("收起全文")
                compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange), true)
                    .performSemanticsAction(SemanticsActions.ScrollBy) { it(100_000f, 0f) }
                scrollComparisonVerticallyTo("local_input")
                evidence.target("local_input").performScrollTo().assertIsDisplayed()
                scrollComparisonVerticallyTo("compare:address:1")
                evidence.target("compare:address:1").performScrollTo().assertTextEquals(address)
                evidence.textFits(evidence.target("compare:address:1"))
                val addressNode = evidence.target("compare:address:1")
                val addressLayout = evidence.layouts(addressNode).single()
                assertEquals("expanded layout contains the exact full address", address, addressLayout.layoutInput.text.text)
                val lastLine = addressLayout.lineCount - 1
                assertTrue("expanded address has a final line", lastLine >= 0)
                assertEquals("final line reaches the full address end", address.length, addressLayout.getLineEnd(lastLine))
                val addressScroller = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                    hasAnyDescendant(hasTestTag("compare:address:1")), true)
                val beforeEnd = pr18FullBounds(addressNode.fetchSemanticsNode())
                val beforeViewport = addressScroller.fetchSemanticsNode().boundsInRoot
                val endDelta = beforeEnd.top + addressLayout.getLineBottom(lastLine) - beforeViewport.bottom
                val endRange = addressScroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                addressScroller.performSemanticsAction(SemanticsActions.ScrollBy) {
                    it(0f, endDelta * if (endRange.reverseScrolling) -1f else 1f)
                }
                compose.waitForIdle()
                val end = pr18FullBounds(addressNode.fetchSemanticsNode())
                val viewport = addressScroller.fetchSemanticsNode().boundsInRoot
                val lineTop = end.top + addressLayout.getLineTop(lastLine)
                val lineBottom = end.top + addressLayout.getLineBottom(lastLine)
                evidence.record("conflict-expanded-address finalLine=$lastLine lineTop=$lineTop lineBottom=$lineBottom " +
                    "full=$end viewport=$viewport endDelta=$endDelta scroll=${endRange.value()} max=${endRange.maxValue()}")
                assertTrue("expanded address full final line is reachable",
                    lineTop >= viewport.top - .5f * evidence.density && lineBottom <= viewport.bottom + .5f * evidence.density)
                evidence.save("conflict-expanded-local", dialog = true)
                evidence.visibleTextFits()
                val before = saves
                evidence.target("confirm-merge").pr18Click(evidence, "confirm-merge")
                evidence.record("conflict-save callbackDelta=${saves - before} choices=$chosen")
                assertEquals(before + 1, saves); assertEquals(ConflictChoice.LOCAL, chosen["rating_tenths"])
                evidence.target("screen:conflict").assertDoesNotExist()
            } catch (failure: Throwable) { evidence.diagnose("conflict", failure); throw failure }
        }
    }

    @Test fun f7ApplyCurrentProposedFieldsAndFooterUseActualFontAndMeasuredTargets() {
        val revision = RecordSnapshot(RecordRef(RecordKind.REVISION, "pr18-dialog-revision", ref.id), 1,
            mapOf("entity_type" to "restaurant", "entity_id" to ref.id), time,
            diff = listOf(Change("rating_tenths", "39", "40"), Change("address", "中山路", address)))
        for (font in fonts) {
            evidence.configure(font)
            var state by mutableStateOf(ApplyUiState(revision, base.copy(version = 2)))
            var shown by mutableStateOf(true)
            var sides = 0; var fields = 0; var saves = 0
            evidence.render {
                if (shown) ApplyFieldsDialog(state, { sides++; state = state.copy(side = it) },
                    { field, checked -> fields++; state = state.copy(selected = if (checked) state.selected + field else state.selected - field) },
                    { saves++; shown = false }, { shown = false })
            }
            try {
                dialog("screen:apply")
                evidence.target("apply-save").assertIsNotEnabled()
                val beforeSide = sides
                val side = compose.onNodeWithText("应用新值").performScrollTo()
                evidence.minimumTarget(side, "应用新值"); side.pr18Click(evidence, "应用新值")
                evidence.record("apply-side callbackDelta=${sides - beforeSide} side=${state.side}")
                assertEquals(beforeSide + 1, sides); assertEquals("after", state.side)
                evidence.target("apply-field:rating_tenths").performScrollTo()
                evidence.minimumTarget("apply-field:rating_tenths")
                val beforeField = fields
                evidence.target("apply-field:rating_tenths").pr18Click(evidence, "apply-field:rating_tenths")
                evidence.record("apply-field callbackDelta=${fields - beforeField} selected=${state.selected}")
                assertEquals(beforeField + 1, fields); assertEquals(setOf("rating_tenths"), state.selected)
                evidence.target("apply-current:rating_tenths").assertTextEquals("★ 3.9")
                evidence.target("apply-proposed:rating_tenths").assertTextEquals("★ 4.0")
                evidence.target("expand:apply-proposed:address").performScrollTo().pr18Click(evidence, "expand:apply-proposed:address")
                evidence.target("apply-proposed:address").performScrollTo().assertTextEquals(address)
                evidence.textFits(evidence.target("apply-proposed:address"))
                val scroller = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                    hasAnyDescendant(hasTestTag("apply-proposed:address")), true)
                scroller.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
                val full = evidence.bounds("apply-proposed:address")
                val viewport = scroller.fetchSemanticsNode().boundsInRoot
                evidence.record("apply-expanded-address full=$full visible=$viewport footer=${evidence.bounds("apply-save")}")
                evidence.save("apply-expanded-address-end", dialog = true)
                assertTrue("expanded address end is reachable", full.bottom <= viewport.bottom + .5f * evidence.density && full.bottom >= viewport.top)
                evidence.minimumTarget("apply-save")
                evidence.visibleTextFits()
                val before = saves
                evidence.target("apply-save").assertIsEnabled().pr18Click(evidence, "apply-save")
                evidence.record("apply-save callbackDelta=${saves - before}")
                assertEquals(before + 1, saves); evidence.target("screen:apply").assertDoesNotExist()
            } catch (failure: Throwable) { evidence.diagnose("apply", failure); throw failure }
        }
    }

    @Test fun f7ConfirmationAndOperationDialogActionsKeepCallbacksAndRealFonts() {
        for (font in fonts) {
            evidence.configure(font)
            for (accept in listOf(false, true)) {
                var shown by mutableStateOf(true)
                var confirmed = 0; var dismissed = 0
                evidence.render { if (shown) ConfirmAction("确认删除记录？", "记录将进入回收站，可在保留窗口内恢复。", "确认删除",
                    { confirmed++; shown = false }, { dismissed++; shown = false }) }
                dialog(null, null)
                val title = compose.onNode(hasText("确认删除记录？") and hasAnyAncestor(isDialog()), true)
                evidence.textFits(title)
                evidence.minimumTarget("confirm")
                evidence.save("confirmation-$accept", dialog = true)
                if (accept) evidence.target("confirm").pr18Click(evidence, "confirm") else {
                    val cancel = compose.onNodeWithText("取消")
                    evidence.minimumTarget(cancel, "取消"); cancel.pr18Click(evidence, "取消")
                }
                evidence.record("confirmation accepted=$accept confirmCallbackDelta=$confirmed dismissCallbackDelta=$dismissed")
                assertEquals(if (accept) 1 else 0, confirmed); assertEquals(if (accept) 0 else 1, dismissed)
            }
            var shown by mutableStateOf(true)
            var retried = 0
            evidence.render { if (shown) OperationDialog(EditorUiState(input, message = "服务器结果未知，请重试原请求确认结果。"),
                { retried++; shown = false }, { shown = false }) }
            dialog("screen:operation", null)
            evidence.minimumTarget("retry-operation")
            evidence.save("operation-retry", dialog = true)
            evidence.target("retry-operation").pr18Click(evidence, "retry-operation")
            evidence.record("operation retryCallbackDelta=$retried")
            assertEquals(1, retried)
            evidence.target("screen:operation").assertDoesNotExist()
        }
    }
}
