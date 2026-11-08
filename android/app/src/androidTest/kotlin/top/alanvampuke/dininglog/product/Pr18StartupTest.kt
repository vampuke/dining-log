package top.alanvampuke.dininglog.product

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import top.alanvampuke.dininglog.BuildConfig
import top.alanvampuke.dininglog.P9VisualActivity
import top.alanvampuke.dininglog.core.*

@RunWith(Parameterized::class)
class Pr18StartupTest(private val style: ThemeStyle, private val dark: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-dark={1}")
        fun cases() = pr18StyleCases()
    }
    @get:Rule val compose = createAndroidComposeRule<P9VisualActivity>()
    private lateinit var evidence: Pr18Evidence
    private var runtime: Pr18Runtime? = null
    private val choice get() = if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT
    @Before fun prepare() { evidence = Pr18Evidence(compose, style, dark); evidence.configure(1f) }
    @After fun restore() { evidence.close(); runtime?.close() }

    private fun launch(enabled: Boolean = true, authenticated: Boolean = true): Pr18Runtime {
        val next = Pr18Runtime(ApplicationProvider.getApplicationContext<Context>(), enabled, style, choice, authenticated)
        runtime = next
        return next
    }
    private fun awaitPopup(next: Pr18Runtime, release: AppRelease, protectedEvidence: Boolean = false): Pr18CaptureReceipt? {
        compose.waitUntil(15_000) { next.updates.state.value.release == release && !next.updates.state.value.checking }
        val count = compose.onAllNodesWithTag("screen:update", true).fetchSemanticsNodes().size
        evidence.record("startup popupCount=$count release=${next.manager.state.value.release?.versionCode} " +
            "expectedRelease=${release.versionCode} requests=${next.versions.requests.get()}")
        assertEquals("one startup popup", 1, count)
        assertEquals(release, next.manager.state.value.release)
        noDownload(next)
        assertEquals("startup checker requested once before evidence", 1, next.versions.requests.get())
        if (protectedEvidence) return evidence.probeProtected("startup-popup")
        evidence.save("startup-popup", dialog = true)
        return null
    }
    private fun noDownload(next: Pr18Runtime) {
        evidence.record("download serviceStarts=${next.updateContext.downloads.get()} transportRequests=${next.transport.requests.get()} " +
            "installVerifications=${next.installer.verifications.get()}")
        assertEquals("no automatic download service", 0, next.updateContext.downloads.get())
        assertEquals("no automatic APK connection", 0, next.transport.requests.get())
        assertEquals("no automatic install verification", 0, next.installer.verifications.get())
    }
    private fun dismiss() {
        evidence.target("update-close").performScrollTo()
        evidence.minimumTarget("update-close")
        evidence.target("update-close").pr18Click(evidence, "update-close")
        evidence.target("screen:update").assertDoesNotExist()
    }
    private fun assertNoPopup(next: Pr18Runtime, case: String) {
        compose.waitForIdle()
        val count = compose.onAllNodesWithTag("screen:update", true).fetchSemanticsNodes().size
        evidence.record("case=$case popupCount=$count requests=${next.versions.requests.get()} install=${next.manager.state.value}")
        assertEquals("$case startup popup count", 0, count)
    }
    private fun settings() {
        evidence.target("tab:settings").pr18Click(evidence, "tab:settings")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("settings-scroll", true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun f8NewerEnabledStartupOnLoginShowsOnePopupWithZeroDownloads() {
        val next = launch(authenticated = false)
        val release = pr18Release()
        next.versions.enqueue(release)
        next.render(evidence)
        val popupFrame = requireNotNull(awaitPopup(next, release, protectedEvidence = true))
        evidence.record("login startup actual font=${evidence.actualFont}")
        next.assertPalette(evidence, style, dark, authenticated = false)
        assertEquals(1, next.versions.requests.get())
        noDownload(next)
        dismiss()
        evidence.target("auth-username").assertExists()
        assertNoPopup(next, "login-dismiss")
        popupFrame.requireValid()
    }

    @Test fun f5DismissRotateAndRecreateRetainsTheLaunchAcknowledgement() {
        val next = launch()
        val release = pr18Release()
        next.versions.enqueue(release)
        next.render(evidence)
        awaitPopup(next, release); noDownload(next); dismiss()
        // P9VisualActivity handles orientation itself; explicit recreate ensures
        // this executes the same new-owner path as MainActivity rotation.
        evidence.landscape()
        evidence.recreate()
        next.assertPalette(evidence, style, dark)
        evidence.save("startup-dismiss-rotate", dialog = true)
        noDownload(next)
        assertEquals("rotation never rechecks the singleton checker", 1, next.versions.requests.get())
        assertNoPopup(next, "F5 dismiss-rotate-recreate")
    }

    @Test fun f5DismissActualSystemFontChangeRetainsTheLaunchAcknowledgementOnLogin() {
        val next = launch(authenticated = false)
        val release = pr18Release()
        next.versions.enqueue(release)
        next.render(evidence)
        val popupFrame = requireNotNull(awaitPopup(next, release, protectedEvidence = true)); dismiss()
        evidence.configure(2f)
        evidence.record("F5 actual system font after dismissal resourcesFont=${compose.activity.resources.configuration.fontScale}")
        val fontFrame = evidence.probeProtected("startup-dismiss-system-font")
        noDownload(next)
        assertEquals("system font change never requests again", 1, next.versions.requests.get())
        assertNoPopup(next, "F5 dismiss-actual-system-font")
        popupFrame.requireValid()
        fontFrame.requireValid()
    }

    @Test fun f5DismissThemeStyleRecompositionDoesNotShowAgain() {
        val next = launch()
        val release = pr18Release()
        next.versions.enqueue(release); next.render(evidence)
        awaitPopup(next, release); dismiss()
        val otherStyle = if (style == ThemeStyle.INK_PAPER) ThemeStyle.QUIET_GRID else ThemeStyle.INK_PAPER
        runBlocking { next.preferences.setStyle(otherStyle); next.preferences.setTheme(if (dark) ThemeChoice.LIGHT else ThemeChoice.DARK) }
        compose.waitForIdle()
        next.assertPalette(evidence, otherStyle, !dark)
        assertNoPopup(next, "recomposition-style-theme")
        assertEquals(1, next.versions.requests.get()); noDownload(next)
    }

    @Test fun f5InitiallyOptedOutLaunchCanEnableCheckingWithoutAnInterruptingStartupPopup() {
        val next = launch(enabled = false)
        val response = next.versions.enqueue()
        next.render(evidence)
        compose.waitUntil(15_000) { !next.updates.state.value.enabled }
        assertEquals(0, next.versions.requests.get())
        settings()
        evidence.tap("settings-scroll", "update-checks")
        compose.waitUntil(15_000) { next.versions.requests.get() == 1 && next.updates.state.value.checking }
        val release = pr18Release()
        response.complete(release)
        compose.waitUntil(15_000) { next.updates.state.value.release == release && !next.updates.state.value.checking }
        evidence.record("late opt-in background status=${next.updates.state.value}")
        evidence.save("startup-initial-opt-out-late-enable", dialog = true)
        noDownload(next)
        assertNoPopup(next, "F5 opt-in after opted-out launch")
        evidence.scroll("settings-scroll", "app-update")
        evidence.target("app-update").assertIsDisplayed()
    }

    @Test fun f8DisabledStartupIssuesNoRequestAndShowsNoPopup() {
        val next = launch(enabled = false)
        next.render(evidence)
        compose.waitUntil(15_000) { !next.updates.state.value.enabled }
        evidence.record("disabled startup requests=${next.versions.requests.get()}")
        assertEquals(0, next.versions.requests.get()); assertNoPopup(next, "disabled"); noDownload(next)
        next.assertPalette(evidence, style, dark)
    }
    @Test fun f8FailedStartupShowsNoPopup() { rejectedStartup(null) }
    @Test fun f8EqualStartupShowsNoPopup() { rejectedStartup(BuildConfig.VERSION_CODE) }
    @Test fun f8OlderStartupShowsNoPopup() { rejectedStartup(BuildConfig.VERSION_CODE - 1) }

    private fun rejectedStartup(code: Int?) {
        val next = launch()
        if (code == null) next.versions.enqueue(failure = IOException("Controlled startup failure"))
        else next.versions.enqueue(pr18Release(code))
        next.render(evidence)
        compose.waitUntil(15_000) { next.versions.requests.get() == 1 && !next.updates.state.value.checking }
        evidence.record("rejected startup candidate=$code state=${next.updates.state.value}")
        assertNull(next.updates.state.value.release)
        assertNoPopup(next, "failed-equal-older-$code"); noDownload(next)
    }

    @Test fun f8ManualCheckWhileOptedOutRequiresExplicitOpenAndDownload() { manualCheck(false) }
    @Test fun f8ManualCheckWhileOptedInRequiresExplicitOpenAndDownload() { manualCheck(true) }
    private fun manualCheck(enabled: Boolean) {
        val next = launch(enabled)
        if (enabled) next.versions.enqueue(pr18Release(BuildConfig.VERSION_CODE))
        next.render(evidence)
        val startupRequests = if (enabled) 1 else 0
        compose.waitUntil(15_000) { next.updates.state.value.enabled == enabled &&
            next.versions.requests.get() == startupRequests && !next.updates.state.value.checking }
        assertEquals(startupRequests, next.versions.requests.get())
        assertNoPopup(next, "manual-before-check")
        settings()
        val release = pr18Release()
        next.versions.enqueue(release)
        evidence.tap("settings-scroll", "check-update")
        compose.waitUntil(15_000) { next.updates.state.value.manual && next.updates.state.value.release == release }
        assertEquals(startupRequests + 1, next.versions.requests.get())
        assertNoPopup(next, "manual-before-open"); noDownload(next)
        evidence.tap("settings-scroll", "app-update")
        evidence.target("screen:update").assertExists()
        assertEquals(release, next.manager.state.value.release)
        noDownload(next)
        // The service is controlled, but download(), version revalidation,
        // trusted URL checks, SHA promotion and install() are production paths.
        next.versions.enqueue(release)
        evidence.target("update-now").performScrollTo()
        evidence.minimumTarget("update-now")
        evidence.target("update-now").pr18Click(evidence, "update-now")
        compose.waitUntil(15_000) { next.manager.state.value.stage == UpdateStage.NEED_PERMISSION }
        evidence.record("explicit-download state=${next.manager.state.value} starts=${next.updateContext.downloads.get()} " +
            "connections=${next.transport.requests.get()} permissions=${next.permissionRequests.size}")
        assertEquals(1, next.updateContext.downloads.get())
        assertEquals(1, next.transport.requests.get())
        assertEquals(1, next.permissionRequests.size)
        assertTrue(next.manager.state.value.packageVerified)
        assertEquals(release, next.manager.state.value.release)
        assertEquals(startupRequests + 2, next.versions.requests.get())
    }
}
