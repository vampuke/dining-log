package top.alanvampuke.dininglog.product

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import top.alanvampuke.dininglog.P9VisualActivity
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.SettingsUiState
import top.alanvampuke.dininglog.feature.components.*
import top.alanvampuke.dininglog.feature.settings.SettingsScreen
import top.alanvampuke.dininglog.feature.theme.*

@RunWith(Parameterized::class)
class Pr18ComponentTest(private val style: ThemeStyle, private val dark: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-dark={1}")
        fun cases() = pr18StyleCases()
    }
    @get:Rule val compose = createAndroidComposeRule<P9VisualActivity>()
    private lateinit var evidence: Pr18Evidence
    @Before fun prepare() { evidence = Pr18Evidence(compose, style, dark); evidence.configure(1f); evidence.render {} }
    @After fun restore() { evidence.close() }

    private data class FieldCase(val name: String, val value: String = "", val focused: Boolean = false,
        val enabled: Boolean = true, val error: Boolean = false, val supporting: Boolean = false,
        val multiline: Boolean = false, val password: Boolean = false)

    @Test fun f3InputRuleIsPaintedAboveTheContainerBeforeSupportingTextForEveryFieldState() {
        val cases = listOf(FieldCase("empty"), FieldCase("search", focused = true),
            FieldCase("password", password = true), FieldCase("populated", "已填写"),
            FieldCase("disabled", enabled = false), FieldCase("multiline", "第一行\n第二行\n第三行", multiline = true),
            FieldCase("supporting", supporting = true), FieldCase("supporting-error", error = true, supporting = true),
            FieldCase("focused-error", focused = true, error = true, supporting = true))
        for (font in listOf(1f, 1.3f, 1.31f, 2f)) {
            evidence.configure(font)
            for (case in cases) {
                var value by mutableStateOf(case.value)
                var changes = 0
                val idleFocus = FocusRequester()
                val fieldFocus = FocusRequester()
                evidence.render { key(case.name) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        // As in P8VisualTest, give API26 a harmless focus target so
                        // the only text field cannot refocus itself between cases.
                        Box(Modifier.size(1.dp).focusRequester(idleFocus).focusable())
                        BiteTextField(value, { value = it; changes++ }, Modifier.focusRequester(fieldFocus).testTag("pr18:field"),
                            enabled = case.enabled, accessibleLabel = "字段标签", label = { GridText("字段标签") },
                            placeholder = { GridText("例如：兰巴赫", Modifier.testTag("pr18:placeholder")) },
                            singleLine = !case.multiline, minLines = if (case.multiline) 3 else 1,
                            isError = case.error,
                            supportingText = if (case.supporting) ({ Text(if (case.error) "请检查输入内容" else "保留完整输入",
                                Modifier.testTag("pr18:support"), style = MaterialTheme.typography.bodySmall) }) else null,
                            visualTransformation = if (case.password) PasswordVisualTransformation() else VisualTransformation.None)
                    }
                } }
                try {
                    compose.runOnIdle { if (case.focused) fieldFocus.requestFocus() else idleFocus.requestFocus() }
                    evidence.hideKeyboard()
                    compose.waitForIdle()
                    val focused = evidence.target("pr18:field").fetchSemanticsNode().config
                        .getOrElse(SemanticsProperties.Focused) { false }
                    val c = evidence.colors
                    val expected = when { case.error -> c.danger; case.focused && style == ThemeStyle.INK_PAPER -> c.ink; else -> c.border }.toArgb()
                    val bitmap = evidence.target("pr18:field").captureToImage().asAndroidBitmap()
                    val field = evidence.bounds("pr18:field")
                    val support = if (case.supporting) evidence.bounds("pr18:support") else null
                    val limit = if (support == null) bitmap.height else
                        (support.top - field.top).roundToInt().coerceIn(1, bitmap.height)
                    fun fraction(y: Int, argb: Int): Float {
                        val xs = (bitmap.width / 4) until (bitmap.width * 3 / 4)
                        return xs.count { x -> similar(bitmap.getPixel(x, y), argb) }.toFloat() / xs.count()
                    }
                    val surfaceRows = (0 until limit).filter { fraction(it, c.surface.toArgb()) >= .9f }
                    val lastSurface = surfaceRows.lastOrNull() ?: -1
                    val ruleRows = (0 until bitmap.height).filter { fraction(it, expected) >= .95f }
                    evidence.record("field case=${case.name} field=$field support=$support bitmap=${bitmap.width}x${bitmap.height} " +
                        "inputLastSurfacePx=$lastSurface supportStartPx=$limit ruleRows=$ruleRows expectedRuleArgb=$expected " +
                        "requestedFocus=${case.focused} actualFocus=$focused error=${case.error} enabled=${case.enabled}")
                    evidence.save("field-${case.name}")
                    assertEquals("field focus precondition", case.focused, focused)
                    evidence.assertRendered(titleTag = null)
                    // The label slot is visual only; the field keeps exactly one spoken name.
                    compose.onAllNodesWithContentDescription("字段标签", true).assertCountEquals(1)
                    evidence.target("pr18:field").assertContentDescriptionEquals("字段标签")
                    assertTrue("input surface was actually painted", lastSurface >= 0)
                    if (style == ThemeStyle.INK_PAPER) {
                        val nearby = ruleRows.filter { it >= lastSurface && it < limit &&
                            it <= lastSurface + (2 * evidence.density).roundToInt() }
                        assertTrue("F3 ${case.name}: full-width input underline must be visible above the container, before support", nearby.isNotEmpty())
                        if (support != null) assertTrue("underline excludes supporting/error text", nearby.last() < limit)
                        else assertTrue("underline is at input bottom", nearby.last() >= bitmap.height - 2 * evidence.density)
                    } else assertTrue("classic outlined border still surrounds the whole field",
                        ruleRows.any { it >= bitmap.height - 3 * evidence.density })
                    evidence.textFits(evidence.target("pr18:field"))
                    if (support != null) {
                        val layout = evidence.layouts(evidence.target("pr18:support")).single()
                        val actual = layout.layoutInput.style.color.toArgb()
                        evidence.record("support case=${case.name} foregroundArgb=$actual")
                        assertEquals(if (case.error) c.danger.toArgb() else c.secondary.toArgb(), actual)
                        evidence.textFits(evidence.target("pr18:support"))
                    }
                    val before = changes
                    if (case.enabled) {
                        evidence.target("pr18:field").performTextInput("字")
                        compose.runOnIdle { assertEquals("one input callback", before + 1, changes) }
                    } else {
                        evidence.target("pr18:field").assertIsNotEnabled().performClick()
                        compose.runOnIdle { assertEquals("disabled input callback delta", before, changes) }
                    }
                    evidence.hideKeyboard()
                } catch (failure: Throwable) { evidence.diagnose("field-${case.name}", failure); throw failure }
            }
        }
    }

    @Test fun f1RenderedButtonAndPlaceholderUseAccessibleForegroundsAndPreserveCallbacks() {
        var clicks = 0
        evidence.render {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BiteButton({ clicks++ }, Modifier.testTag("pr18:button")) {
                    Text("立即更新", Modifier.testTag("pr18:button-label"))
                }
                BiteTextField("", {}, Modifier.testTag("pr18:field"), singleLine = true,
                    placeholder = { GridText("12–128 个字符", Modifier.testTag("pr18:placeholder")) })
                BiteButton({ clicks++ }, Modifier.testTag("pr18:disabled-button"), enabled = false) { Text("正在保存") }
            }
        }
        val c = evidence.colors
        val buttonText = evidence.layouts(evidence.target("pr18:button-label")).single().layoutInput.style.color.toArgb()
        val placeholder = evidence.target("pr18:placeholder").fetchSemanticsNode().config[ForegroundArgb]
        val buttonRatio = pr18Contrast(buttonText, c.cta.toArgb())
        val placeholderRatio = pr18Contrast(placeholder, c.surface.toArgb())
        evidence.record("F1 rendered buttonArgb=$buttonText ctaArgb=${c.cta.toArgb()} ratio=$buttonRatio " +
            "placeholderArgb=$placeholder surfaceArgb=${c.surface.toArgb()} ratio=$placeholderRatio")
        evidence.save("active-text-contrast")
        evidence.assertRendered(titleTag = null)
        evidence.minimumTarget("pr18:button")
        val before = clicks
        evidence.target("pr18:button").performClick()
        compose.runOnIdle { assertEquals(before + 1, clicks) }
        evidence.target("pr18:disabled-button").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(before + 1, clicks) }
        assertTrue("F1 actual enabled action text contrast", buttonRatio >= 4.5)
        assertTrue("F1 actual placeholder text contrast", placeholderRatio >= 4.5)
    }

    @Test fun f2ActualSettingsUncheckedSwitchThumbAndBorderContrastWithTrackAndSurface() {
        var state by mutableStateOf(AppUpdateState(enabled = false))
        var callbacks = 0
        evidence.render {
            SettingsScreen(SettingsUiState(), "食记用户", ThemeChoice.SYSTEM, "测试版本",
                { _, _ -> }, {}, {}, {}, updateState = state,
                onUpdateToggle = { callbacks++; state = state.copy(enabled = it) }, style = style)
        }
        evidence.assertRendered("screen:settings")
        evidence.scroll("settings-scroll", "update-checks")
        val bitmap = evidence.target("update-checks").captureToImage().asAndroidBitmap()
        val dp = evidence.density
        fun pixel(xDp: Float, y: Float) = bitmap.getPixel((xDp * dp).roundToInt().coerceIn(0, bitmap.width - 1),
            y.roundToInt().coerceIn(0, bitmap.height - 1))
        val thumb = pixel(16f, bitmap.height / 2f)
        val track = pixel(36f, bitmap.height / 2f)
        val border = pixel(26f, (bitmap.height - 32 * dp) / 2 + dp)
        val surface = evidence.colors.bg.toArgb()
        val ratios = listOf(pr18Contrast(thumb, track), pr18Contrast(thumb, surface),
            pr18Contrast(border, track), pr18Contrast(border, surface))
        evidence.record("F2 switch pixels thumb=$thumb border=$border track=$track adjoining=$surface " +
            "ratios=$ratios layout=${evidence.bounds("update-checks")}")
        evidence.save("settings-unchecked-switch")
        evidence.assertRendered("screen:settings", titleTag = null)
        evidence.target("update-checks").assertIsOff().assertContentDescriptionEquals("启动时检查更新")
        assertTrue("F2 enabled off-state thumb/border >=3:1 against track and adjoining surface", ratios.all { it >= 3 })
        evidence.minimumTarget("update-checks")
        val before = callbacks
        evidence.target("update-checks").performClick().assertIsOn()
        compose.runOnIdle { assertEquals(before + 1, callbacks) }
    }

    @Test fun f4ScoreVerdictAndFillEmphasize40And50WithNeutral39AndNull() {
        for (value in listOf("39", "40", "50", null)) {
            evidence.render {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Score(value, modifier = Modifier.testTag("pr18:score"))
                    Box(Modifier.testTag("pr18:verdict")) { VerdictSticker(value) }
                    val fill = verdictFill(value?.toInt())
                    Box(Modifier.size(48.dp).background(fill).testTag("pr18:fill").semantics { this[BackgroundArgb] = fill.toArgb() })
                }
            }
            val c = evidence.colors
            val high = value?.toInt()?.let { it >= 40 } == true
            val score = compose.onNode(hasAnyAncestor(hasTestTag("pr18:score")) and
                SemanticsMatcher.keyIsDefined(ForegroundArgb), true)
            val verdict = compose.onNode(hasAnyAncestor(hasTestTag("pr18:verdict")) and
                SemanticsMatcher.keyIsDefined(ForegroundArgb), true)
            val scoreArgb = score.fetchSemanticsNode().config[ForegroundArgb]
            val verdictArgb = verdict.fetchSemanticsNode().config[ForegroundArgb]
            val fillArgb = evidence.target("pr18:fill").fetchSemanticsNode().config[BackgroundArgb]
            evidence.record("F4 tenths=$value scoreArgb=$scoreArgb verdictArgb=$verdictArgb fillArgb=$fillArgb high=$high")
            evidence.save("rating-${value ?: "unrated"}")
            evidence.assertRendered(titleTag = null)
            score.assertTextEquals(if (value == null) if (style == ThemeStyle.INK_PAPER) "—" else "未评分"
                else if (style == ThemeStyle.INK_PAPER) Rating.fromTenths(value.toInt()).display else displayValue("rating_tenths", value))
            verdict.assertTextEquals(verdictLabel(value?.toInt()))
            if (style == ThemeStyle.INK_PAPER) {
                assertEquals("F4 score emphasis", when { value == null -> c.secondary; high -> c.accent; else -> c.ink }.toArgb(), scoreArgb)
                assertEquals("F4 verdict emphasis", (if (high) c.accent else c.secondary).toArgb(), verdictArgb)
                assertEquals("F4 fill emphasis", when { value == null -> c.paper; high -> c.accent; else -> c.ink }.toArgb(), fillArgb)
            } else {
                assertEquals((if (value == null) c.ink else c.stickerInk).toArgb(), scoreArgb)
                assertEquals(when { value == null -> c.paper; value.toInt() >= 46 -> c.butter;
                    value.toInt() >= 40 -> c.mint; value.toInt() >= 32 -> c.peach; else -> c.popRed }.toArgb(), fillArgb)
            }
        }
    }

    @Test fun explicitUnratedTrackRequiresAnActionAndSteppersKeepOneTenthAndBounds() {
        for (font in listOf(1f, 1.3f, 1.31f, 2f)) {
            evidence.configure(font)
            var value by mutableStateOf<String?>(null)
            var calls = 0
            evidence.render { RatingEditor(value, { calls++; value = it }, true) }
            evidence.target("rating").assertContentDescriptionEquals("未评分")
            evidence.target("rating").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
            evidence.target("rating:plus").assertDoesNotExist()
            val beforeTouch = calls
            evidence.target("rating").performTouchInput { click(center) }
            compose.runOnIdle {
                evidence.record("unrated-track touchCallbackDelta=${calls - beforeTouch} value=$value")
                assertEquals(beforeTouch, calls); assertNull(value)
            }
            val before = calls
            evidence.target("rating").performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
            compose.runOnIdle {
                evidence.record("unrated-track setProgressCallbackDelta=${calls - before} value=$value")
                assertEquals(before + 1, calls); assertEquals("40", value)
            }
            for ((tag, next) in listOf("rating:plus" to "41", "rating:minus" to "40")) {
                evidence.minimumTarget(tag)
                val round = calls
                evidence.target(tag).performClick()
                compose.runOnIdle {
                    evidence.record("stepper tag=$tag callbackDelta=${calls - round} value=$value")
                    assertEquals(round + 1, calls); assertEquals(next, value)
                }
            }
            for ((endpoint, tag) in listOf("10" to "rating:minus", "50" to "rating:plus")) {
                compose.runOnIdle { value = endpoint }
                evidence.minimumTarget(tag)
                val round = calls
                evidence.target(tag).performClick()
                compose.runOnIdle {
                    evidence.record("stepper-bound tag=$tag callbackDelta=${calls - round} value=$value endpoint=$endpoint")
                    assertEquals(round + 1, calls); assertEquals(endpoint, value)
                }
            }
            val clear = calls
            val clearAction = compose.onNodeWithText("清除评分")
            evidence.minimumTarget(clearAction, "清除评分")
            clearAction.performClick()
            compose.runOnIdle {
                evidence.record("clear-rating callbackDelta=${calls - clear} value=$value")
                assertEquals(clear + 1, calls); assertNull(value)
            }
            evidence.target("rating").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
            evidence.save("explicit-unrated")
        }
    }

    @Test fun pinnedTextRemeasurementStillRejectsWidthHeightAndMaxLinesClipping() {
        evidence.render {
            Column(Modifier.width(120.dp)) {
                GridText("短文本", Modifier.testTag("pr18:natural"))
                Text("LongUnbreakableText", softWrap = false, modifier = Modifier.width(24.dp).testTag("pr18:width-clip"))
                GridText("高度", Modifier.height(1.dp).testTag("pr18:height-clip"))
                GridText("第一行\n第二行", Modifier.testTag("pr18:max-lines"), maxLines = 1)
            }
        }
        evidence.textFits(evidence.target("pr18:natural"))
        for (tag in listOf("pr18:width-clip", "pr18:height-clip", "pr18:max-lines")) {
            val results = evidence.layouts(evidence.target(tag)).map(::pr18Remeasure)
            evidence.record("negative-overflow tag=$tag values=${results.map { it.didOverflowWidth to it.didOverflowHeight }}")
            assertTrue("$tag must expose real clipping", results.any {
                if (tag == "pr18:width-clip") it.didOverflowWidth else it.didOverflowHeight
            })
        }
    }

    @Test fun actualTargetMeasurementRejectsA24dpControlEvenWithAutomaticTouchExpansion() {
        evidence.render { Box(Modifier.size(24.dp).clickable {}.testTag("pr18:too-small")) }
        var failure: AssertionError? = null
        try { evidence.minimumTarget("pr18:too-small") } catch (error: AssertionError) { failure = error }
        evidence.record("negative-target 24dp rejected=${failure != null}")
        assertNotNull("automatic touch semantics cannot satisfy explicit 48dp layout", failure)
    }

    private fun similar(a: Int, b: Int) = listOf(16, 8, 0).all { shift ->
        abs(((a shr shift) and 255) - ((b shr shift) and 255)) <= 3
    }
}
