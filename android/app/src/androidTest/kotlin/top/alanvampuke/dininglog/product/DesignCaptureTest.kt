package top.alanvampuke.dininglog.product

import top.alanvampuke.dininglog.BuildConfig

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.*
import top.alanvampuke.dininglog.feature.restaurants.*
import top.alanvampuke.dininglog.feature.revisions.*
import top.alanvampuke.dininglog.feature.settings.*
import top.alanvampuke.dininglog.feature.theme.*
import top.alanvampuke.dininglog.applySystemBarTheme

// Design capture only: renders the four design-preview screens in both styles
// (Ink & Paper / classic QuietGrid) and both modes (light / dark) and saves
// them as PNGs under p4-screenshots for visual comparison against the design.
// Deliberately asserts nothing about colours or contrast: the known F1-F6
// contrast regressions would fail any such assertion. Each capture only
// asserts the screen node exists, then saves the rendered bitmap.
@RunWith(AndroidJUnit4::class)
class DesignCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ref = RecordRef(RecordKind.RESTAURANT, "visual-restaurant")
    private val snapshot = RecordSnapshot(ref, 1, mapOf("name" to "兰记川味小馆", "category" to "restaurant", "cuisine" to "川菜", "address" to "上海愚园路 123 号", "rating_tenths" to "43", "review" to "花椒香，鸡块酥脆。", "note" to "和朋友一起到店。"), "2026-11-01T08:00:00.000Z")
    private val summary = RecordSummary(ref, snapshot.title, 1, snapshot.updatedAt, snapshot.fields + ("address_excerpt" to "上海愚园路 123 号"), matches = listOf(MatchType.DISH, MatchType.REVIEW))
    private val dish = RecordSnapshot(RecordRef(RecordKind.DISH, "visual-dish", ref.id), 1,
        mapOf("name" to "辣子鸡", "rating_tenths" to "43", "review" to "花椒香，鸡块酥脆。"), "2026-11-01T08:00:00.000Z")
    private val revision = RecordSnapshot(RecordRef(RecordKind.REVISION, "visual-revision", ref.id), 1,
        mapOf("entity_label" to snapshot.title, "entity_type" to "restaurant", "entity_id" to ref.id, "entity_version_before" to "1", "entity_version_after" to "2", "created_at" to snapshot.updatedAt, "note" to "再次到店后改分"), snapshot.updatedAt,
        diff = listOf(Change("rating_tenths", "40", "43"), Change("review", "原来的评价", "新评价，花椒香")))

    @Test fun captureFourScreensInBothStylesAndModes() {
        var dark by mutableStateOf(false)
        var style by mutableStateOf(ThemeStyle.QUIET_GRID)
        var screen by mutableStateOf("list")
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        compose.setContent { QuietGridTheme(dark, style) {
            SideEffect { applySystemBarTheme(compose.activity.window, dark) }
            when (screen) {
                "list" -> listScreen(ListUiState(items = listOf(summary), localScope = false))
                "detail" -> RecordDetailScreen(DetailUiState(CachedDetail(dish)), {}, {}, {}, {}, {}, {})
                "history-list" -> listScreen(ListUiState(items = listOf(RecordSummary(revision.ref, snapshot.title, 1, revision.updatedAt,
                    revision.fields, changedFields = listOf("rating_tenths"), cachedDiff = revision.diff)), query = ListQuery(RecordKind.REVISION, ref.id), localScope = false))
                "history" -> RevisionDetailScreen(DetailUiState(CachedDetail(revision)), {}, {}, {}, {}, {}, {})
                "settings" -> SettingsScreen(SettingsUiState(), "design-capture", if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT,
                    BuildConfig.VERSION_NAME, onPassword = { _, _ -> }, onLogout = {}, onTheme = {}, bottomBar = {}, style = style)
            }
        } }
        for (s in listOf(ThemeStyle.INK_PAPER, ThemeStyle.QUIET_GRID)) for (night in listOf(false, true)) for (page in listOf("list", "detail", "history-list", "history", "settings")) {
            compose.runOnIdle { style = s; dark = night; screen = page }
            compose.waitForIdle()
            val tag = when (page) {
                "list" -> "screen:restaurant:list"
                "history-list" -> "screen:revision:list"
                "detail" -> "screen:detail"
                "history" -> "screen:history"
                else -> "screen:settings"
            }
            val prefix = if (s == ThemeStyle.INK_PAPER) "ink" else "classic"
            capture(tag, "$prefix-${if (night) "dark" else "light"}-$page")
        }
    }
    @Composable private fun listScreen(state: ListUiState) {
        RecordListScreen(state, state.query.query, state.query.filters, {}, {}, {}, {}, {})
    }
    private fun capture(tag: String, name: String) {
        compose.onNodeWithTag(tag).assertExists()
        val bitmap = compose.onNodeWithTag(tag).captureToImage()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = context.getExternalFilesDir("p4-screenshots")!!; folder.mkdirs()
        File(folder, "$name-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use { bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
