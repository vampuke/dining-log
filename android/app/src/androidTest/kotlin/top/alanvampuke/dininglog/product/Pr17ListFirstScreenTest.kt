package top.alanvampuke.dininglog.product

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import top.alanvampuke.dininglog.P9VisualActivity
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.BottomTabs
import top.alanvampuke.dininglog.feature.restaurants.RecordListScreen
import top.alanvampuke.dininglog.feature.theme.*

internal data class FirstScreenCards(val visibleArea: Rect, val visibleCards: Map<String, Rect>, val completeCards: List<String>) {
    val count get() = completeCards.size
}

internal fun measureListFirstScreen(compose: AndroidComposeTestRule<*, *>): FirstScreenCards {
    compose.waitForIdle()
    // boundsInRoot is intentionally used for the *clipped viewport*, never
    // for card completeness. positionInRoot + size preserves partial cards.
    val viewport = compose.onNodeWithTag("pr17:viewport", true).fetchSemanticsNode().boundsInRoot
    val list = compose.onNodeWithTag("record-list", true).fetchSemanticsNode().boundsInRoot
    val tabs = compose.onNodeWithTag("floating-tabs", true).fetchSemanticsNode().boundsInRoot
    val area = Rect(maxOf(viewport.left, list.left), maxOf(viewport.top, list.top),
        minOf(viewport.right, list.right), minOf(viewport.bottom, list.bottom, tabs.top))
    assertTrue("list has an unobscured visible area: $area", area.width > 0 && area.height > 0)
    val cards = compose.onAllNodes(SemanticsMatcher("record card") {
        it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("record:")
    }, useUnmergedTree = true).fetchSemanticsNodes().associate { node ->
        node.config[SemanticsProperties.TestTag] to Rect(node.positionInRoot, Size(node.size.width.toFloat(), node.size.height.toFloat()))
    }.filterValues { it.width > 0 && it.height > 0 && it.overlaps(area) }
    val complete = cards.filterValues { it.left >= area.left && it.top >= area.top && it.right <= area.right && it.bottom <= area.bottom }.keys.toList()
    return FirstScreenCards(area, cards, complete)
}

// This class can be copied unchanged onto eaa8e42: it uses only APIs/tags
// present on that real baseline, and never reimplements production layout.
@RunWith(AndroidJUnit4::class)
class Pr17ListFirstScreenTest {
    @get:Rule val compose = createAndroidComposeRule<P9VisualActivity>()

    @Test fun fiveListsHaveAtLeastTheMeasuredBaselineCompleteCards() {
        var kind by mutableStateOf(RecordKind.RESTAURANT)
        var search by mutableStateOf(false)
        var dark by mutableStateOf(false)
        val args = InstrumentationRegistry.getArguments()
        val baselineMode = args.getString("BASELINE_MODE") == "true"
        val sourceSha = args.getString("SOURCE_SHA") ?: "review-fix"
        val folder = ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir("p4-screenshots")!!.apply { mkdirs() }
        var density = 0f
        compose.setContent {
            val native = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(native.density, 1f)) {
                SideEffect { density = native.density }
                QuietGridTheme(dark) {
                    Box(Modifier.requiredSize(360.dp, 640.dp).clipToBounds().background(LocalQuietGridColors.current.bg).testTag("pr17:viewport")) {
                        key(kind, search, dark) {
                            val rows = (1..20).map { RecordSummary(RecordRef(kind, "pr17-row-$it", if (kind == RecordKind.RESTAURANT) null else "pr17-parent"),
                                "记录 $it", 2, "2026-11-02T08:00:00Z", mapOf("rating_tenths" to "47", "dish_count" to "2", "note" to "摘要")) }
                            RecordListScreen(ListUiState(items = rows, query = ListQuery(kind), localScope = false), if (search) "记录" else "", ListFilters(),
                                {}, {}, {}, {}, {}, onAdd = {}, onBack = if (kind != RecordKind.RESTAURANT) ({}) else null,
                                bottomBar = { BottomTabs("restaurants") {} })
                        }
                    }
                }
            }
        }
        val cases = listOf("restaurant" to RecordKind.RESTAURANT, "search" to RecordKind.RESTAURANT,
            "dish" to RecordKind.DISH, "visit" to RecordKind.VISIT, "history" to RecordKind.REVISION)
        for (night in listOf(false, true)) for ((name, type) in cases) {
            compose.runOnIdle { kind = type; search = name == "search"; dark = night }
            compose.waitForIdle()
            assertEquals("M1 baseline fixture uses Pixel 2 at 420dpi", 2.625f, density, 0.001f)
            val result = measureListFirstScreen(compose)
            val theme = if (night) "dark" else "light"
            val prefix = if (sourceSha.startsWith("eaa8e42")) "before" else "after"
            val filename = "pr17-list-firstscreen-$prefix-$name-font1.0-$theme-api${Build.VERSION.SDK_INT}.png"
            compose.onNodeWithTag("pr17:viewport", true).captureToImage().asAndroidBitmap().let { bitmap ->
                File(folder, filename).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            File(folder, "pr17-firstscreen-api${Build.VERSION.SDK_INT}.txt").appendText(
                "source=$sourceSha mode=$prefix case=$name font=1.0 theme=$theme viewportDp=360x640 density=$density " +
                    "device=${Build.MODEL} complete=${result.count} screenshot=$filename $result\n")
            if (!baselineMode) {
                // Real baseline eaa8e42 measurements, API26/35 pixel_2,
                // density 2.625, 360x640dp, font 1.0, same 20 rows and both themes.
                // API26 restaurant/search/dish/visit/history = 4/4/4/6/2;
                // API35 = 2/3/3/4/2. Captured in Actions run 41941966360.
                // Original logs/screenshots: design/pr17-discussion/evidence/M1-list-firstscreen.md.
                val baseline = measuredBaseline(name)
                assertTrue("M1 $name api=${Build.VERSION.SDK_INT} theme=$theme: after=${result.count} >= baseline eaa8e42=$baseline; $result",
                    result.count >= baseline)
            }
        }
    }

    private fun measuredBaseline(name: String): Int = when (Build.VERSION.SDK_INT) {
        26 -> mapOf("restaurant" to 4, "search" to 4, "dish" to 4, "visit" to 6, "history" to 2)
        35 -> mapOf("restaurant" to 2, "search" to 3, "dish" to 3, "visit" to 4, "history" to 2)
        else -> error("No measured eaa8e42 baseline for API ${Build.VERSION.SDK_INT}")
    }.getValue(name)
}
