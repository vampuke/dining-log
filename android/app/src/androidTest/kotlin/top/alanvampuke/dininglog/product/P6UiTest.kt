package top.alanvampuke.dininglog.product

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.pow
import top.alanvampuke.dininglog.R
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.core.auth.*
import top.alanvampuke.dininglog.feature.*
import top.alanvampuke.dininglog.feature.components.*
import top.alanvampuke.dininglog.feature.restaurants.*
import top.alanvampuke.dininglog.feature.theme.*

@RunWith(AndroidJUnit4::class)
class P6UiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val rows = (1..30).map { index -> RecordSummary(RecordRef(RecordKind.RESTAURANT, "p6-record-$index"),
        "兰记小馆 $index", 1, "2026-11-01T08:00:00.000Z", mapOf("category" to "restaurant", "cuisine" to "川菜",
            "address_excerpt" to "愚园路 $index 号", "dish_count" to "3", "last_visit_date" to "2026-10-31", "rating_tenths" to "43")) }

    @Test fun first360By640DpScreenShowsAtLeastThreeCompleteRecordItemsInBothThemes() {
        var dark by mutableStateOf(false)
        compose.setContent { QuietGridTheme(dark) {
            Box(Modifier.requiredSize(360.dp, 640.dp).clipToBounds().background(LocalQuietGridColors.current.bg).testTag("p6-viewport")) {
                RecordListScreen(ListUiState(items = rows, localScope = false), "", ListFilters(), {}, {}, {}, {}, {},
                    onAdd = {}, onTrash = {}, bottomBar = { BottomTabs("restaurants") {} })
            }
        } }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            compose.onNodeWithTag("p6-viewport").assertWidthIsEqualTo(360.dp).assertHeightIsEqualTo(640.dp)
            val viewport = compose.onNodeWithTag("record-list").getUnclippedBoundsInRoot()
            val nodes = compose.onAllNodes(SemanticsMatcher("record-list item") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("record:") == true
            }, useUnmergedTree = true).fetchSemanticsNodes()
            val visible = nodes.map { it.config[SemanticsProperties.TestTag] }.filter { tag ->
                val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                bounds.bottom > bounds.top && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom
            }
            assertTrue("360x640 ${if (night) "dark" else "light"} first screen has ${visible.size} complete records", visible.size >= 3)
            visible.forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
            println("P6 viewport=360x640dp theme=${if (night) "dark" else "light"} complete_record_items=${visible.size}")
            saveImage("p6-${if (night) "dark" else "light"}-360x640", "p6-viewport")
        }
    }

    @Test fun selectedTabsPaintMeasuredContrastAndChangeTheirActualSilhouettesInBothThemes() {
        var dark by mutableStateOf(false)
        var selected by mutableStateOf("restaurants")
        compose.setContent { QuietGridTheme(dark) { BottomTabs(selected) { selected = it } } }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            val colors = if (night) QuietGridDark else QuietGridLight
            for (id in listOf("restaurants", "settings")) {
                val other = if (id == "restaurants") "settings" else "restaurants"
                compose.runOnIdle { selected = other }
                val icon = compose.onNodeWithTag("tab-icon:$id", useUnmergedTree = true)
                icon.assert(SemanticsMatcher.expectValue(TabIconFilled, false))
                val outline = silhouette(icon.captureToImage().toPixelMap(), colors.secondary.toArgb())
                compose.onNodeWithTag("tab:$id").performClick().assertIsSelected()
                icon.assert(SemanticsMatcher.expectValue(TabIconFilled, true))
                icon.assert(SemanticsMatcher.expectValue(ForegroundArgb, colors.accentInk.toArgb()))
                val filled = silhouette(icon.captureToImage().toPixelMap(), colors.accentInk.toArgb())
                assertFalse("$id selection must change shape independently of color", outline.contentEquals(filled))
                val pixels = compose.onNodeWithTag("tab:$id").captureToImage().toPixelMap()
                assertTrue("selected glyph is actually painted", count(pixels, colors.accentInk.toArgb()) > 5)
                assertTrue("selected indicator is actually painted", count(pixels, colors.accentSoft.toArgb()) > 20)
                val glyph = contrast(colors.accentInk.toArgb(), colors.accentSoft.toArgb())
                val label = contrast(colors.accentInk.toArgb(), colors.surface.toArgb())
                assertTrue("selected icon contrast $glyph", glyph >= 4.5)
                assertTrue("selected label contrast $label", label >= 4.5)
                println("P6 theme=${if (night) "dark" else "light"} tab=$id icon_contrast=$glyph label_contrast=$label")
            }
        }
    }

    @Test fun emptyOptionalAddressReviewAndNoteAreOmittedAndMinimalEditorCanSave() {
        var editing by mutableStateOf(false)
        var saved = 0
        val ref = RecordRef(RecordKind.RESTAURANT, "p6-minimal")
        val fields = mapOf("name" to "只填名称的小馆", "category" to "restaurant", "address" to null, "cuisine" to null, "review" to null, "note" to null)
        val record = RecordSnapshot(ref, 1, fields, "2026-11-01T08:00:00.000Z")
        val input = SaveInput("p6-owner", ref, WriteAction.CREATE, null, fields)
        assertTrue(validateInput(input).isEmpty())
        compose.setContent { QuietGridTheme(true) {
            if (editing) EditScreen(EditorUiState(input), { _, _ -> }, {}, { saved++ }, {})
            else RecordDetailScreen(DetailUiState(CachedDetail(record)), {}, {}, {}, {}, {}, {})
        } }
        for (field in listOf("address", "cuisine", "review", "note")) compose.onNodeWithTag("detail:$field").assertDoesNotExist()
        compose.onAllNodes(hasText("null", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("--", substring = true)).assertCountEquals(0)
        compose.runOnIdle { editing = true }
        compose.onNodeWithTag("edit-scroll", useUnmergedTree = true).performScrollToNode(hasTestTag("field:address"))
        compose.onNodeWithText("地址 · 选填", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("放弃本机草稿").assertDoesNotExist()
        compose.onNodeWithTag("save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saved) }
    }

    @Test fun bothTabReselectionsKeepTheSameBackStackEntryAndScrollToTop() {
        val fixture = Fixture()
        try {
            compose.setContent { QuietGridTheme(true) {
                ProductRoot(fixture.vm, "p6_owner", ThemeChoice.DARK, {}, {}, "0.6.0")
            } }
            waitForRows(fixture, RecordKind.RESTAURANT)
            for (tab in listOf("restaurants", "settings")) {
                compose.onNodeWithTag("tab:$tab").performClick()
                if (tab == "settings") compose.waitUntil(5000) { fixture.vm.settings.value.sessions.size == 12 && !fixture.vm.settings.value.loading }
                else waitForRows(fixture, if (tab == "restaurants") RecordKind.RESTAURANT else RecordKind.VISIT)
                val listTag = if (tab == "settings") "settings-scroll" else "record-list"
                compose.onNodeWithTag(listTag).performScrollToIndex(10)
                val entry = compose.onNodeWithTag("product-navigation").fetchSemanticsNode().config[NavigationEntryId]
                var refreshes = 0; var profiles = 0
                compose.runOnIdle { refreshes = fixture.repository.refreshes.get(); profiles = fixture.security.profiles.get() }
                repeat(4) {
                    compose.onNodeWithTag("tab:$tab").performClick()
                    compose.onNodeWithTag("page-title").assertIsDisplayed()
                    assertEquals("$tab must reuse its existing entry", entry,
                        compose.onNodeWithTag("product-navigation").fetchSemanticsNode().config[NavigationEntryId])
                    compose.runOnIdle {
                        assertEquals(refreshes, fixture.repository.refreshes.get())
                        assertEquals(profiles, fixture.security.profiles.get())
                    }
                }
                println("P6 tab=$tab reselections=4 entry_preserved=true scrolled_to_top=true")
            }
            compose.onNodeWithTag("tab:restaurants").performClick()
            waitForRows(fixture, RecordKind.RESTAURANT)
            compose.onNodeWithTag("record:${rows.first().ref.id}").performClick()
            compose.onNodeWithTag("screen:detail").assertIsDisplayed()
            compose.onNodeWithTag("tab:restaurants").performClick()
            compose.onNodeWithTag("screen:restaurant:list").assertIsDisplayed()
        } finally { fixture.close() }
    }

    @Test fun systemRemoveAnimationsIsObservedAndDarkNavigationFramesNeverPaintWhite() {
        val resolver = compose.activity.contentResolver
        val previous = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)?.toFloatOrNull()
        val fixture = Fixture()
        try {
            animationScale(1f)
            compose.setContent { QuietGridTheme(true) { ProductRoot(fixture.vm, "p6_owner", ThemeChoice.DARK, {}, {}, "0.6.0") } }
            waitForRows(fixture, RecordKind.RESTAURANT)
            compose.onNodeWithTag("product-navigation").assert(SemanticsMatcher.expectValue(NavigationAnimationsEnabled, true))
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("tab:settings").performClick()
            repeat(15) { frame ->
                if (frame > 0) compose.mainClock.advanceTimeBy(16)
                val bitmap = compose.onNodeWithTag("product-navigation").captureToImage().asAndroidBitmap()
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("frame $frame retains dark background", pixels.count { it == QuietGridDark.bg.toArgb() } > 250)
                assertEquals("frame $frame must have no white flash", 0, pixels.count { it == Color.WHITE })
            }
            compose.mainClock.autoAdvance = true
            animationScale(0f)
            compose.waitUntil(5000) { compose.onNodeWithTag("product-navigation").fetchSemanticsNode().config[NavigationAnimationsEnabled] == false }
            compose.onNodeWithTag("tab:settings").performClick()
            compose.onNodeWithTag("screen:settings").assertIsDisplayed()
            compose.onAllNodesWithTag("screen:visit:list").assertCountEquals(0)
            println("P6 dark_navigation_frames=15 sample_interval_ms=16 white_pixels=0 remove_animations_observed=true")
        } finally { compose.mainClock.autoAdvance = true; animationScale(previous); fixture.close() }
    }

    @Test fun actualAdaptiveRoundAndMonochromeIconsAndNativeNightThemeResolveCorrectly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (resource in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val icon = context.getDrawable(resource) as AdaptiveIconDrawable
            if (Build.VERSION.SDK_INT >= 33) assertNotNull(icon.monochrome)
            for (wallpaper in listOf(Color.WHITE, Color.rgb(15, 15, 14))) {
                val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap); canvas.drawColor(wallpaper)
                icon.setBounds(24, 24, 136, 136); icon.draw(canvas)
                val foreground = Color.rgb(17, 17, 17); val background = Color.rgb(255, 214, 10)
                val pixels = IntArray(160 * 160); bitmap.getPixels(pixels, 0, 160, 0, 0, 160, 160)
                assertTrue(pixels.count { it == foreground } > 50); assertTrue(pixels.count { it == background } > 100)
                val ratio = contrast(foreground, background); assertTrue(ratio >= 4.5)
                println("P6 adaptive_icon=$resource wallpaper=$wallpaper contrast=$ratio")
            }
        }
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        val night = context.createConfigurationContext(configuration)
        val theme = night.resources.newTheme().apply { applyStyle(R.style.Theme_DiningLog, true) }
        val background = TypedValue()
        assertTrue(theme.resolveAttribute(android.R.attr.windowBackground, background, true))
        assertEquals(QuietGridDark.bg.toArgb(), background.data)
        if (Build.VERSION.SDK_INT >= 31) {
            val splash = TypedValue()
            assertTrue(theme.resolveAttribute(android.R.attr.windowSplashScreenBackground, splash, true))
            assertEquals(QuietGridDark.bg.toArgb(), splash.data)
        }
    }

    private fun animationScale(value: Float?) {
        val command = if (value == null) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $value"
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }
    private fun count(pixels: androidx.compose.ui.graphics.PixelMap, argb: Int): Int {
        var result = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y].toArgb() == argb) result++
        return result
    }
    private fun silhouette(pixels: androidx.compose.ui.graphics.PixelMap, ink: Int) = BooleanArray(pixels.width * pixels.height) {
        pixels[it % pixels.width, it / pixels.width].toArgb() == ink
    }
    private fun contrast(foreground: Int, background: Int): Double {
        fun luminance(argb: Int): Double {
            fun channel(shift: Int): Double { val value = ((argb shr shift) and 255) / 255.0; return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4) }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        val a = luminance(foreground); val b = luminance(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
    private fun saveImage(name: String, tag: String) {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val folder = ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir("p4-screenshots")!!; folder.mkdirs()
        File(folder, "$name-api${Build.VERSION.SDK_INT}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun waitForRows(fixture: Fixture, kind: RecordKind) {
        compose.waitUntil(5000) { fixture.vm.list.value.query.kind == kind && fixture.vm.list.value.items.size == 30 && !fixture.vm.list.value.isRefreshing }
        compose.waitForIdle()
    }
    private inner class Repository : ProductRepository {
        val refreshes = AtomicInteger()
        override val connectivity = flowOf(true)
        override fun observeList(accountId: String, query: ListQuery) = flowOf(CachedPage(if (query.kind == RecordKind.VISIT) rows.map {
            it.copy(ref = it.ref.copy(kind = RecordKind.VISIT, restaurantId = "p6-parent"), title = "2026-10-31", fields = mapOf("restaurant_name" to it.title))
        } else rows, localScope = false))
        override suspend fun refreshList(accountId: String, query: ListQuery, loadMore: Boolean) { refreshes.incrementAndGet() }
        override fun observeDetail(accountId: String, ref: RecordRef) = flowOf(CachedDetail(RecordSnapshot(ref, 1, mapOf("name" to "餐厅", "category" to "restaurant"), "2026-11-01T08:00:00.000Z")))
        override suspend fun refreshDetail(accountId: String, ref: RecordRef) = RecordSnapshot(ref, 1, mapOf("name" to "餐厅", "category" to "restaurant"), "2026-11-01T08:00:00.000Z")
        override suspend fun submit(input: SaveInput): SaveOutcome = error("Unexpected UI fixture write")
    }
    private class Security : AccountSecurity {
        val profiles = AtomicInteger()
        override suspend fun profile(): AccountInfo { profiles.incrementAndGet(); return AccountInfo("p6-owner", "p6_owner", "食记用户", "2026-11-01") }
        override suspend fun sessions() = (1..12).map { DeviceSession("p6-session-$it", "测试设备 $it", "2026-11-01", "2026-11-01", "2026-12-01", it == 1) }
        override suspend fun revokeSession(id: String) { }
        override suspend fun changePassword(current: String, replacement: String) { }
    }
    private inner class Fixture {
        // Public synthetic values kept only in memory; no production session or Keystore.
        val repository = Repository(); val security = Security()
        private val bytes = object : AtomicTokenFile {
            private var data: ByteArray? = null
            override fun read() = data
            override fun replace(bytes: ByteArray) { data = bytes }
            override fun delete() { data = null }
        }
        private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        private val keys = object : KeyManager { override fun existingKey() = key; override fun encryptionKey() = key }
        val account = AccountSession(SecureTokenStore(bytes, AesGcmTokenCipher(keys, "p6-public-fixture")), AccountMemory()).also {
            runBlocking { it.install(AuthSession("p6-owner", "p6_owner", "p6-session", "synthetic-access", "synthetic-refresh")) }
        }
        val vm = ProductViewModel("p6-owner", repository, security, account)
        fun close() { runBlocking { account.clear() } }
    }
}
