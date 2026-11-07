package top.alanvampuke.dininglog.product

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import top.alanvampuke.dininglog.P9VisualActivity
import top.alanvampuke.dininglog.core.*

@RunWith(AndroidJUnit4::class)
class Pr18PreferencesOrchestrationTest {
    @get:Rule val compose = createAndroidComposeRule<P9VisualActivity>()
    private lateinit var evidence: Pr18Evidence
    private var runtime: Pr18Runtime? = null
    private var originalNight = false
    private var originalNightMode = "auto"
    @Before fun prepare() {
        evidence = Pr18Evidence(compose, ThemeStyle.INK_PAPER, false)
        originalNight = isSystemNight()
        originalNightMode = evidence.shell("cmd uimode night").substringAfter(":", "auto").trim()
        if (originalNightMode !in listOf("yes", "no", "auto")) originalNightMode = if (originalNight) "yes" else "no"
        evidence.configure(1f)
    }
    @After fun restore() {
        evidence.renderRaw {}
        runtime?.close()
        evidence.shell("cmd uimode night $originalNightMode")
        compose.waitUntil(15_000) { isSystemNight() == originalNight }
        evidence.waitResumed(); evidence.close()
    }
    private fun isSystemNight() = compose.activity.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun start(next: Pr18Runtime) {
        runtime = next; next.render(evidence)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("product-navigation", true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun settings() {
        evidence.target("tab:settings").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("settings-scroll", true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun choose(label: String) {
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasText(label))
        val choice = compose.onNodeWithText(label)
        evidence.minimumTarget(choice, label)
        choice.assertIsDisplayed().performClick()
    }
    private fun reopen(next: Pr18Runtime): Pr18Runtime {
        val path = next.preferencePath
        val tokens = next.store
        evidence.renderRaw {}
        next.close(deletePreference = false)
        compose.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        evidence.recreate()
        return Pr18Runtime(ApplicationProvider.getApplicationContext<Context>(), preferencePath = path, store = tokens)
            .also { start(it) }
    }

    @Test fun f8AbsentStyleRendersInkPaperAndPersistedClassicRestoresAfterRecreationAndReopeningDataStore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = Pr18Runtime(context, enabled = false, choice = ThemeChoice.LIGHT, authenticated = true)
        start(first)
        val defaultStyle = runBlocking { first.preferences.style.first() }
        evidence.record("absent-style DataStore style=$defaultStyle bytes=${first.preferencePath.length()}")
        assertEquals(ThemeStyle.INK_PAPER, defaultStyle)
        first.assertPalette(evidence, ThemeStyle.INK_PAPER, false)
        settings()
        val before = first.styleWrites.get()
        choose("经典")
        first.assertPalette(evidence, ThemeStyle.QUIET_GRID, false)
        assertEquals("style callback delta", before + 1, first.styleWrites.get())
        assertEquals(ThemeChoice.LIGHT, runBlocking { first.preferences.theme.first() })
        evidence.recreate()
        first.assertPalette(evidence, ThemeStyle.QUIET_GRID, false)
        val path = first.preferencePath
        val tokens = first.store
        evidence.renderRaw {}
        first.close(deletePreference = false)
        compose.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        // Fresh process-owned collaborators plus a genuinely closed/reopened
        // DataStore file. This is controlled owner restart, not an OS process kill.
        val reopened = Pr18Runtime(context, preferencePath = path, store = tokens)
        start(reopened)
        val restoredStyle = runBlocking { reopened.preferences.style.first() }
        val restoredTheme = runBlocking { reopened.preferences.theme.first() }
        evidence.record("fresh-owners-reopened-file style=$restoredStyle theme=$restoredTheme bytes=${path.length()}")
        assertTrue(path.isFile && path.length() > 0)
        assertEquals(ThemeStyle.QUIET_GRID, restoredStyle)
        assertEquals(ThemeChoice.LIGHT, restoredTheme)
        reopened.assertPalette(evidence, ThemeStyle.QUIET_GRID, false)
        evidence.save("preferences-classic-restored", dialog = true)
        assertEquals("persisted opt-out restored before startup requests", 0, reopened.versions.requests.get())
        assertEquals(0, reopened.updateContext.downloads.get())
    }

    @Test fun f8BothStyleAndSystemLightDarkChoicesStayOrthogonalThroughSystemNightAndRecreation() {
        var next = Pr18Runtime(ApplicationProvider.getApplicationContext<Context>(), enabled = false,
            style = ThemeStyle.INK_PAPER, choice = ThemeChoice.LIGHT, authenticated = true)
        start(next); settings()
        for (night in listOf(false, true)) {
            evidence.shell("cmd uimode night ${if (night) "yes" else "no"}")
            compose.waitUntil(15_000) { isSystemNight() == night }
            evidence.waitResumed(); next.render(evidence)
            for (style in ThemeStyle.entries) for (choice in ThemeChoice.entries) {
                val beforeStyle = next.styleWrites.get()
                val oldTheme = runBlocking { next.preferences.theme.first() }
                choose(if (style == ThemeStyle.INK_PAPER) "纸墨" else "经典")
                compose.waitUntil(15_000) { next.styleWrites.get() == beforeStyle + 1 }
                assertEquals("style changes preserve theme", oldTheme, runBlocking { next.preferences.theme.first() })
                val beforeTheme = next.themeWrites.get()
                choose(when (choice) { ThemeChoice.SYSTEM -> "跟随系统"; ThemeChoice.LIGHT -> "浅色"; ThemeChoice.DARK -> "深色" })
                compose.waitUntil(15_000) { next.themeWrites.get() == beforeTheme + 1 }
                val expectedDark = when (choice) { ThemeChoice.SYSTEM -> night; ThemeChoice.LIGHT -> false; ThemeChoice.DARK -> true }
                next.assertPalette(evidence, style, expectedDark)
                evidence.recreate()
                next.assertPalette(evidence, style, expectedDark)
                val actualStyle = runBlocking { next.preferences.style.first() }
                val actualTheme = runBlocking { next.preferences.theme.first() }
                evidence.record("orthogonal preferences style=$actualStyle theme=$actualTheme systemNight=${isSystemNight()} " +
                    "resolvedDark=$expectedDark styleCallbackDelta=${next.styleWrites.get() - beforeStyle} " +
                    "themeCallbackDelta=${next.themeWrites.get() - beforeTheme}")
                assertEquals(style, actualStyle); assertEquals(choice, actualTheme)
                assertEquals(night, isSystemNight())
                assertEquals(beforeStyle + 1, next.styleWrites.get()); assertEquals(beforeTheme + 1, next.themeWrites.get())
                evidence.save("preferences-${style.name.lowercase()}-${choice.name.lowercase()}-systemNight$night", dialog = true)
                // Every style/theme pair also reopens its actual persisted file
                // with fresh checker/account/install owners. No OS kill is claimed.
                next = reopen(next)
                val reopenedStyle = runBlocking { next.preferences.style.first() }
                val reopenedTheme = runBlocking { next.preferences.theme.first() }
                next.assertPalette(evidence, style, expectedDark)
                evidence.record("orthogonal-fresh-owners style=$reopenedStyle theme=$reopenedTheme systemNight=${isSystemNight()} " +
                    "resolvedDark=$expectedDark preferenceBytes=${next.preferencePath.length()} " +
                    "versionRequests=${next.versions.requests.get()} serviceStarts=${next.updateContext.downloads.get()}")
                assertEquals(style, reopenedStyle); assertEquals(choice, reopenedTheme)
                assertEquals(night, isSystemNight())
                assertEquals(0, next.versions.requests.get()); assertEquals(0, next.updateContext.downloads.get())
                evidence.save("preferences-reopened-${style.name.lowercase()}-${choice.name.lowercase()}-systemNight$night", dialog = true)
                settings()
            }
        }
    }

    @Test fun f5FreshLaunchOwnersCanCheckAgainWithoutDownloadingAfterThePreviousLaunchDismissed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = Pr18Runtime(context, style = ThemeStyle.INK_PAPER, choice = ThemeChoice.LIGHT, authenticated = true)
        val release = pr18Release()
        first.versions.enqueue(release); start(first)
        compose.waitUntil(15_000) { first.updates.state.value.release == release }
        evidence.target("update-close").performScrollTo().performClick()
        evidence.target("screen:update").assertDoesNotExist()
        val path = first.preferencePath; val tokens = first.store
        evidence.renderRaw {}; first.close(deletePreference = false)
        compose.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        val fresh = Pr18Runtime(context, preferencePath = path, store = tokens)
        fresh.versions.enqueue(release); start(fresh)
        compose.waitUntil(15_000) { fresh.updates.state.value.release == release && !fresh.updates.state.value.checking }
        val popups = compose.onAllNodesWithTag("screen:update", true).fetchSemanticsNodes().size
        evidence.record("controlled-fresh-launch popupCount=$popups versionRequests=${fresh.versions.requests.get()} " +
            "serviceStarts=${fresh.updateContext.downloads.get()} APKConnections=${fresh.transport.requests.get()}")
        assertEquals(1, popups); assertEquals(1, fresh.versions.requests.get())
        assertEquals(0, fresh.updateContext.downloads.get()); assertEquals(0, fresh.transport.requests.get())
    }
}
