package top.alanvampuke.dininglog.product

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import top.alanvampuke.dininglog.BuildConfig
import top.alanvampuke.dininglog.MainActivityContent
import top.alanvampuke.dininglog.P9VisualActivity
import top.alanvampuke.dininglog.core.*

// Separate from the existing parameterized acceptance suite: the probe selects
// exactly this class, using AndroidJUnitRunner 1.6.2's supported "class" argument.
@RunWith(AndroidJUnit4::class)
class Pr18ReviewRegressionTest {
    // Compose 1.8.2's unconfined default can resume window SideEffects on an
    // I/O emitter. Queue composition work for the rule's UI-thread clock.
    @OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
    @get:Rule val compose = createAndroidComposeRule<P9VisualActivity>(
        effectContext = StandardTestDispatcher(),
    )
    private lateinit var evidence: Pr18Evidence
    private var runtime: Pr18Runtime? = null
    private var checkerInComposition: AppUpdateChecker? = null
    private lateinit var compositionScope: CoroutineScope
    private val a = pr18Release(name = "手动选择 A")
    private val b = pr18Release(a.versionCode + 1, "启动结果 B")

    @Before fun prepare() {
        evidence = Pr18Evidence(compose, ThemeStyle.INK_PAPER, true)
        evidence.configure(1f)
    }

    @After fun restore() {
        try { evidence.close() } finally { runtime?.close() }
    }

    private fun launch(): Pr18Runtime = Pr18Runtime(
        ApplicationProvider.getApplicationContext<Context>(), enabled = false,
        style = ThemeStyle.INK_PAPER, choice = ThemeChoice.DARK,
    ).also {
        runtime = it
        it.updateContext.onDownload = null
        compose.waitUntil(15_000) { !it.updates.state.value.enabled }
    }

    private fun diagnose(case: String, block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            runtime?.let { record(it, "failure-$case") }
            if (checkerInComposition != null) runCatching { recordF5Ui("failure-$case") }
            evidence.diagnose(case, failure)
            throw failure
        }
    }

    private fun record(next: Pr18Runtime, case: String) {
        evidence.record("review-regression case=$case checker=${(checkerInComposition ?: next.updates).state.value} " +
            "selected=${next.manager.state.value} requests=${next.versions.requests.get()} " +
            "downloads=${next.updateContext.downloads.get()} connections=${next.transport.requests.get()} " +
            "verifications=${next.installer.verifications.get()} " +
            "completedVerifications=${next.installer.completedVerifications.get()} " +
            "permissions=${next.permissionRequests.size} installed=${next.installer.installed.map { it.name }}")
    }

    private fun recordF5Ui(case: String) {
        val rows = compose.onAllNodesWithTag("app-update").fetchSemanticsNodes()
        val checks = compose.onAllNodesWithTag("check-update").fetchSemanticsNodes()
        evidence.record("F5-001 observation=$case lifecycle=${compose.activityRule.scenario.state} " +
            "clockMs=${compose.mainClock.currentTime} " +
            "appUpdate=${rows.map { "${it.config} bounds=${it.boundsInRoot}" }} " +
            "checkUpdate=${checks.map { it.config }}")
    }

    private fun awaitSettingsBAndOfferHandling() {
        recordF5Ui("await-settings-B")
        // Source-state B can precede the queued lifecycle collector. Unlike an
        // idle check, this wait pumps the UI clock until the real row updates.
        // Existence also permits an erroneous dialog to reach the popup assertion.
        compose.waitUntil("F5-001 real Settings applies completed B", 15_000) {
            compose.onAllNodes(
                hasText("有新版本 ${b.versionName}") and hasAnyAncestor(hasTestTag("app-update")),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size == 1 &&
                compose.onAllNodesWithTag("check-update", true).fetchSemanticsNodes()
                    .singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
        }
        // The startup-offer effect is synchronous on this composition dispatcher.
        // A frame receipt after real B, then idle/layout, drains its queued work
        // before observing the popup. This scope adds no product state or input.
        val frameApplied = CompletableDeferred<Unit>()
        compose.runOnUiThread {
            compositionScope.launch {
                withFrameNanos { }
                frameApplied.complete(Unit)
            }
        }
        compose.waitUntil("F5-001 composition frame after Settings B", 15_000) { frameApplied.isCompleted }
        compose.waitForIdle()
        recordF5Ui("B-offer-frame-completed")
    }

    private fun secureWindow(expected: Boolean) {
        compose.activityRule.scenario.onActivity { activity ->
            assertEquals("shared composition retains screen screenshot protection",
                if (expected) WindowManager.LayoutParams.FLAG_SECURE else 0,
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun closePopup() {
        evidence.target("update-close").performScrollTo().performClick()
        evidence.target("screen:update").assertDoesNotExist()
    }

    private fun tapSettings(tag: String) {
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag(tag))
        evidence.target(tag).performClick()
    }

    @Test fun f5DelayedFirstPreferenceRestoreKeepsDismissedManualAClosedAndSettingsBAvailable() = diagnose("F5-001") {
        val next = launch()
        // Controlled preference I/O: no initial emission until restoration.
        // Both manual and startup results still pass through the real checker.
        val restored = MutableSharedFlow<Boolean>(replay = 1)
        val delayedPreferences = object : UpdatePreferences {
            override val updateChecksEnabled = restored
            override suspend fun setUpdateChecksEnabled(enabled: Boolean) { restored.emit(enabled) }
        }
        val checker = AppUpdateChecker(next.versions, delayedPreferences, BuildConfig.VERSION_CODE, next.ioScope)
        checkerInComposition = checker
        evidence.renderProduction {
            val scope = rememberCoroutineScope()
            SideEffect { compositionScope = scope }
            MainActivityContent(compose.activity, next.store, next.account, next.auth, next.refresh,
                next.repository, next.security, next.preferences, checker, next.manager,
                { next.permissionRequests += it })
        }
        evidence.target("auth-username").assertExists()
        compose.waitUntil(15_000) {
            !evidence.target("auth-username").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
        secureWindow(true)
        runBlocking { next.account.install(next.auth.login("食记用户", "Pr18 fixture passphrase")) }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("tab:settings", true).fetchSemanticsNodes().isNotEmpty() }
        evidence.target("tab:settings").performClick()
        compose.waitForIdle()
        secureWindow(false)
        assertEquals("one controlled authentication callback", 1, next.auth.logins.get())
        assertEquals("restoration has not issued a startup request", 0, next.versions.requests.get())

        next.versions.enqueue(a)
        tapSettings("check-update")
        compose.waitUntil(15_000) { checker.state.value.release == a && !checker.state.value.checking }
        assertTrue(checker.state.value.manual)
        assertNull(checker.state.value.startupRelease)
        evidence.target("screen:update").assertDoesNotExist()
        tapSettings("app-update")
        evidence.target("screen:update").assertExists()
        assertEquals(a, next.manager.state.value.release)
        val title = compose.onNodeWithText("发现新版本 ${a.versionName}", useUnmergedTree = true)
        val layouts = evidence.layouts(title)
        assertTrue("real dialog exposes its font layout", layouts.isNotEmpty())
        layouts.forEach { assertEquals("dialog retains exact system font", 1f, it.layoutInput.density.fontScale, .001f) }
        secureWindow(false)
        closePopup()

        val automaticB = next.versions.enqueue()
        runBlocking { restored.emit(true) }
        compose.waitUntil(15_000) { next.versions.requests.get() == 2 && checker.state.value.checking }
        assertEquals("dismissal retains selected A while B is pending", a, next.manager.state.value.release)
        automaticB.complete(b)
        compose.waitUntil(15_000) { checker.state.value.startupRelease == b && !checker.state.value.checking }
        awaitSettingsBAndOfferHandling()
        val popupCount = compose.onAllNodesWithTag("screen:update", true).fetchSemanticsNodes().size
        evidence.record("F5-001 checker=${checker.state.value} popupCount=$popupCount selected=${next.manager.state.value}")
        assertEquals("automatic B cannot replace explicitly selected A", a, next.manager.state.value.release)
        assertEquals(b, checker.state.value.release)
        assertFalse(checker.state.value.manual)
        // Preserve the observed count before closing any erroneous popup so the
        // same run can also inspect B's actual Settings source behind that window.
        if (popupCount > 0) {
            runCatching { evidence.save("f5-reopened-dismissed-A", dialog = true) }
            closePopup()
        }
        evidence.scroll("settings-scroll", "app-update")
        compose.onNodeWithText("有新版本 ${b.versionName}", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("manual A plus first restored automatic B", 2, next.versions.requests.get())
        assertEquals(0, next.updateContext.downloads.get())
        assertEquals(0, next.transport.requests.get())
        assertEquals(0, next.installer.verifications.get())
        assertTrue(next.permissionRequests.isEmpty())
        secureWindow(false)
        assertEquals("F5-001 rejected startup B must not reopen dismissed A", 0, popupCount)
    }

    private fun readyA(): Pr18Runtime {
        val next = launch()
        evidence.render { Text("安装权限重入回归") }
        next.manager.open(a)
        next.versions.enqueue(a)
        next.manager.startDownload()
        runBlocking { withContext(Dispatchers.IO) { next.manager.download() } }
        assertEquals(UpdateStage.READY, next.manager.state.value.stage)
        assertEquals(a, next.manager.state.value.release)
        assertTrue(next.manager.state.value.packageVerified)
        assertTrue(verifyApkSha256(apk(next, a), a.sha256))
        assertFalse(next.manager.state.value.busy)
        return next
    }

    private fun apk(next: Pr18Runtime, release: AppRelease) = File(next.updateContext.cacheDir, "updates/app-${release.versionCode}.apk")

    private fun awaitInstallJobs(next: Pr18Runtime) {
        // Observe the scope, including a continuation queued by owner completion;
        // joining only the initial snapshot would miss that new job.
        compose.waitUntil(15_000) { next.installScope.coroutineContext[Job]!!.children.none() }
        compose.waitForIdle()
    }

    private fun synchronousReturn(duplicates: Boolean) {
        val next = readyA()
        var callbacksInsideOwner = 0
        lateinit var onPermission: (Intent) -> Unit
        onPermission = { intent ->
            next.permissionRequests += intent
            if (next.installScope.coroutineContext[Job]!!.children.any { !it.isCompleted }) callbacksInsideOwner++
            evidence.record("F6-001 synchronous permission callback stage=${next.manager.state.value.stage} ownerActive=$callbacksInsideOwner")
            next.installer.permission = true
            next.manager.install(compose.activity, onPermission, afterPermission = true)
            if (duplicates) next.manager.install(compose.activity, onPermission, afterPermission = true)
        }
        compose.runOnIdle { next.manager.install(compose.activity, onPermission) }
        awaitInstallJobs(next)
        // A later duplicate must also be harmless after the continuation finishes.
        if (duplicates) {
            compose.runOnIdle { next.manager.install(compose.activity, onPermission, afterPermission = true) }
            awaitInstallJobs(next)
        }
        record(next, "synchronous-return-duplicates=$duplicates")
        assertEquals("callback really reentered before owner completion", 1, callbacksInsideOwner)
        assertEquals("one permission request", 1, next.permissionRequests.size)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, next.permissionRequests.single().action)
        assertEquals("package:${next.updateContext.packageName}", next.permissionRequests.single().data.toString())
        assertEquals("A remains the installation owner", a, next.manager.state.value.release)
        assertTrue(verifyApkSha256(apk(next, a), a.sha256))
        assertFalse("B has no downloaded package", apk(next, b).exists())
        assertEquals(1, next.updateContext.downloads.get())
        assertEquals(1, next.transport.requests.get())
        assertEquals("F6-001 one initial SHA check and exactly one permission continuation", 2, next.installer.verifications.get())
        assertEquals(2, next.installer.completedVerifications.get())
        assertEquals("exactly one installer receives the owned A package", listOf(apk(next, a)), next.installer.installed.toList())
        assertEquals(UpdateStage.INSTALLING, next.manager.state.value.stage)
        assertTrue(next.manager.state.value.packageVerified)
    }

    @Test fun f6SynchronousPermissionReturnContinuesOwnedA() = diagnose("F6-001") { synchronousReturn(duplicates = false) }

    @Test fun f6DuplicateSynchronousAndCompletedPermissionReturnsContinueOwnedAOnce() = diagnose("F6-001-duplicates") {
        synchronousReturn(duplicates = true)
    }

    @Test fun f6SynchronousPermissionReturnAfterManualBSelectionRejectsStaleA() = diagnose("F6-001-stale-selection") {
        val next = readyA()
        var callbacksInsideOwner = 0
        lateinit var onPermission: (Intent) -> Unit
        onPermission = { intent ->
            next.permissionRequests += intent
            if (next.installScope.coroutineContext[Job]!!.children.any { !it.isCompleted }) callbacksInsideOwner++
            // Select B while A's permission callback is still on the owner stack.
            next.manager.open(b)
            next.installer.permission = true
            next.manager.install(compose.activity, onPermission, afterPermission = true)
            next.manager.install(compose.activity, onPermission, afterPermission = true)
        }
        compose.runOnIdle { next.manager.install(compose.activity, onPermission) }
        awaitInstallJobs(next)
        compose.runOnIdle { next.manager.install(compose.activity, onPermission, afterPermission = true) }
        awaitInstallJobs(next)
        record(next, "stale-A-return-after-manual-B")
        assertEquals(1, callbacksInsideOwner)
        assertEquals(1, next.permissionRequests.size)
        assertEquals("stale A must not claim B", b, next.manager.state.value.release)
        assertEquals(UpdateStage.IDLE, next.manager.state.value.stage)
        assertFalse(next.manager.state.value.packageVerified)
        assertEquals("stale selection never starts a continuation SHA check", 1, next.installer.verifications.get())
        assertEquals(1, next.installer.completedVerifications.get())
        assertTrue("neither A nor undownloaded B reaches the installer", next.installer.installed.isEmpty())
        assertFalse(apk(next, b).exists())
        assertEquals(1, next.updateContext.downloads.get())
        assertEquals(1, next.transport.requests.get())
    }
}
