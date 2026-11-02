package top.alanvampuke.dininglog.product

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
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
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.*
import top.alanvampuke.dininglog.feature.components.*
import top.alanvampuke.dininglog.feature.restaurants.*
import top.alanvampuke.dininglog.feature.settings.*
import top.alanvampuke.dininglog.feature.theme.*

@RunWith(AndroidJUnit4::class)
class P8VisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ref = RecordRef(RecordKind.RESTAURANT, "p8-parent")
    private val record = RecordSnapshot(ref, 2, mapOf("name" to "兰巴赫", "category" to "restaurant", "cuisine" to "川菜",
        "review" to "文字记录每一口", "rating_tenths" to "43"), "2026-11-02T08:00:00Z")
    private val rows = (1..20).map { RecordSummary(ref.copy(id = "p8-$it"), "餐厅 $it", 1, record.updatedAt) }
    private val release = AppRelease(BuildConfig.VERSION_CODE + 1, "下一版本", "https://github.com/vampuke/dining-log/releases/download/v8/app-release.apk",
        "0".repeat(64), "新的交互与视觉修正", 1793620800)

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
                        assertEquals(capsule.center.x.value, plus.center.x.value, 0.5f)
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
                    compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertTrue(layouts.isNotEmpty()); layouts.forEach { assertFalse(it.hasVisualOverflow) }
                }
            }
        }
    }
    @Test fun emptyFocusedDisabledErrorAndMultilineFieldsUseLegibleRolesInBothThemes() {
        var dark by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        var error by mutableStateOf(false)
        var lines by mutableStateOf(1)
        var value by mutableStateOf("")
        lateinit var focus: androidx.compose.ui.focus.FocusManager
        compose.setContent { QuietGridTheme(dark) {
            focus = androidx.compose.ui.platform.LocalFocusManager.current
            FormField("name", value, { value = it }, enabled, if (error) "请填写名称" else null,
                label = "字段名称", minLines = lines)
        } }
        for (night in listOf(false, true)) for (active in listOf(true, false)) for (invalid in listOf(false, true)) for (minLines in listOf(1, 3)) {
            compose.runOnIdle { focus.clearFocus(force = true); dark = night; enabled = active; error = invalid; lines = minLines; value = "" }
            val c = if (night) QuietGridDark else QuietGridLight
            compose.onNodeWithText("例如：兰巴赫", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(ForegroundArgb, c.tertiary.toArgb()))
            compose.onNodeWithText("字段名称", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(ForegroundArgb, if (invalid) c.danger.toArgb() else c.secondary.toArgb()))
            if (active) {
                compose.onNodeWithTag("field:name").performTextReplacement("兰巴赫")
                compose.onNodeWithText("例如：兰巴赫", useUnmergedTree = true).assertDoesNotExist()
                compose.onNodeWithText("字段名称", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(ForegroundArgb, if (invalid) c.danger.toArgb() else c.ink.toArgb()))
            } else compose.onNodeWithTag("field:name").assertIsNotEnabled()
        }
    }
    private fun assertTail(tag: String) {
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
