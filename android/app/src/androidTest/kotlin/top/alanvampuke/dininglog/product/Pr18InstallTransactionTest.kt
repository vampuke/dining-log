package top.alanvampuke.dininglog.product

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import top.alanvampuke.dininglog.P9VisualActivity
import top.alanvampuke.dininglog.core.*

@RunWith(AndroidJUnit4::class)
class Pr18InstallTransactionTest {
    @get:Rule val compose = createAndroidComposeRule<P9VisualActivity>()
    private lateinit var evidence: Pr18Evidence
    private lateinit var runtime: Pr18Runtime
    private lateinit var delayedB: CompletableDeferred<AppRelease>
    private val a = pr18Release(name = "已选择的版本 A")
    private val b = pr18Release(a.versionCode + 1, "延迟返回的版本 B")
    private val apkA get() = File(runtime.updateContext.cacheDir, "updates/app-${a.versionCode}.apk")
    private val apkB get() = File(runtime.updateContext.cacheDir, "updates/app-${b.versionCode}.apk")

    @Before fun prepare() {
        evidence = Pr18Evidence(compose, ThemeStyle.INK_PAPER, true)
        evidence.configure(1f); evidence.render { Text("安装事务回归") }
        runtime = Pr18Runtime(ApplicationProvider.getApplicationContext<Context>(), enabled = false)
        // Control service scheduling, without bypassing production download(),
        // version revalidation, URL/redirect policy or SHA promotion.
        runtime.updateContext.onDownload = null
        compose.waitUntil(15_000) { !runtime.updates.state.value.enabled }
        delayedB = runtime.versions.enqueue()
        runBlocking { runtime.updates.setEnabled(true) }
        compose.waitUntil(15_000) { runtime.versions.requests.get() == 1 && runtime.updates.state.value.checking }
        runtime.manager.open(a)
        runtime.versions.enqueue(a)
        runtime.manager.startDownload()
        runBlocking { withContext(Dispatchers.IO) { runtime.manager.download() } }
        record("A-downloaded-before-B")
        assertEquals(UpdateStage.READY, runtime.manager.state.value.stage)
        assertEquals(a, runtime.manager.state.value.release)
        assertTrue(verifyApkSha256(apkA, a.sha256))
        assertFalse("READY must remain installable rather than download-busy", runtime.manager.state.value.busy)
        assertEquals(1, runtime.updateContext.downloads.get())
        assertEquals(1, runtime.transport.requests.get())
    }
    @After fun restore() { evidence.close(); if (::runtime.isInitialized) runtime.close() }

    private fun record(case: String) {
        evidence.record("transaction case=$case state=${runtime.manager.state.value} " +
            "releaseA=${a.versionCode} releaseB=${b.versionCode} apkA=${apkA.name} existsA=${apkA.exists()} " +
            "verifiedA=${verifyApkSha256(apkA, a.sha256)} existsB=${apkB.exists()} " +
            "serviceStarts=${runtime.updateContext.downloads.get()} connections=${runtime.transport.requests.get()} " +
            "verifications=${runtime.installer.verifications.get()} permissions=${runtime.permissionRequests.size} " +
            "installerFiles=${runtime.installer.installed.map { it.name }}")
    }

    private fun deliverAutomaticB() {
        delayedB.complete(b)
        compose.waitUntil(15_000) { runtime.updates.state.value.release == b && !runtime.updates.state.value.checking }
        val result = runtime.updates.state.value
        evidence.record("delayed-checker-result manual=${result.manual} release=${result.release?.versionCode} requests=${runtime.versions.requests.get()}")
        assertFalse(result.manual)
        // This is the production automatic-selection entry used by the shared
        // MainActivity composition, with an actual delayed AppUpdateChecker result.
        compose.runOnIdle { runtime.manager.open(result.release!!, automatic = true) }
        record("automatic-B-delivered")
    }

    private fun install(afterPermission: Boolean = false) {
        runtime.manager.install(compose.activity, { runtime.permissionRequests += it }, afterPermission)
    }

    private fun assertOwnedByA(stage: UpdateStage) {
        record("assert-owned-A-$stage")
        assertEquals("F6 selected installation release must remain A", a, runtime.manager.state.value.release)
        assertEquals("F6 A transaction stage", stage, runtime.manager.state.value.stage)
        assertTrue("F6 verified A APK was not swept by B", verifyApkSha256(apkA, a.sha256))
        assertFalse("B has no automatically downloaded APK", apkB.exists())
        assertEquals("B requires another explicit download action", 1, runtime.updateContext.downloads.get())
        assertEquals("B creates no APK connection", 1, runtime.transport.requests.get())
    }

    @Test fun f6DelayedAutomaticBDoesNotReplaceReadyAOrPreventInstall() {
        deliverAutomaticB()
        assertOwnedByA(UpdateStage.READY)
        compose.runOnIdle { install() }
        compose.waitUntil(15_000) { runtime.manager.state.value.stage == UpdateStage.NEED_PERMISSION }
        assertOwnedByA(UpdateStage.NEED_PERMISSION)
        assertEquals("READY can still invoke install", 1, runtime.installer.verifications.get())
        assertEquals(1, runtime.permissionRequests.size)
    }

    @Test fun f6DelayedAutomaticBDoesNotReplacePermissionTransactionAOnReturn() {
        compose.runOnIdle { install() }
        compose.waitUntil(15_000) { runtime.manager.state.value.stage == UpdateStage.NEED_PERMISSION }
        assertEquals(1, runtime.permissionRequests.size)
        deliverAutomaticB()
        // Return uses the production afterPermission path in the newly RESUMED
        // owner, just as MainActivity's registered ActivityResult callback does.
        evidence.recreate()
        runtime.installer.permission = true
        compose.runOnIdle { install(afterPermission = true) }
        val jobs = runtime.installScope.coroutineContext[Job]!!.children.toList()
        runBlocking { withTimeout(15_000) { jobs.joinAll() } }
        record("permission-return-complete")
        assertOwnedByA(UpdateStage.INSTALLING)
        assertEquals(listOf(apkA), runtime.installer.installed.toList())
        assertFalse("A permission return must never fail B", runtime.manager.state.value.release == b &&
            runtime.manager.state.value.stage == UpdateStage.FAILED)
    }

    @Test fun f6DelayedAutomaticBDoesNotSweepInstallingAOrReceiveAsynchronousFailureFromA() {
        val verify = CompletableDeferred<Unit>()
        runtime.installer.verificationGate = verify
        runtime.installer.permission = true
        compose.runOnIdle { install() }
        compose.waitUntil(15_000) { runtime.installer.verifications.get() == 1 }
        val jobs = runtime.installScope.coroutineContext[Job]!!.children.toList()
        assertEquals(UpdateStage.INSTALLING, runtime.manager.state.value.stage)
        deliverAutomaticB()
        record("A-verification-still-held")
        verify.complete(Unit)
        runBlocking { withTimeout(15_000) { jobs.joinAll() } }
        record("A-asynchronous-installer-completed")
        assertOwnedByA(UpdateStage.INSTALLING)
        assertFalse("A asynchronous completion cannot mark B FAILED", runtime.manager.state.value.release == b &&
            runtime.manager.state.value.stage == UpdateStage.FAILED)
        assertEquals("installer receives exactly A's owned APK", listOf(apkA), runtime.installer.installed.toList())
    }

    @Test fun f6RepeatedInstallTapsOwnOneJobAndOnePermissionRequest() {
        val verify = CompletableDeferred<Unit>()
        runtime.installer.verificationGate = verify
        compose.runOnIdle { install(); install(); install() }
        val jobs = runtime.installScope.coroutineContext[Job]!!.children.toList()
        evidence.record("repeated-install activeJobs=${jobs.size}")
        verify.complete(Unit)
        runBlocking { withTimeout(15_000) { jobs.joinAll() } }
        record("repeated-install-complete")
        assertEquals("F6 repeated taps start one install job", 1, jobs.size)
        assertEquals("one SHA recheck", 1, runtime.installer.verifications.get())
        assertEquals("one permission launch", 1, runtime.permissionRequests.size)
        assertOwnedByA(UpdateStage.NEED_PERMISSION)
    }

    @Test fun f6RepeatedPermissionReturnInRecreatedOwnerOwnsOneJobAndInstallerIntent() {
        compose.runOnIdle { install() }
        compose.waitUntil(15_000) { runtime.manager.state.value.stage == UpdateStage.NEED_PERMISSION }
        evidence.recreate()
        runtime.installer.permission = true
        val verify = CompletableDeferred<Unit>()
        runtime.installer.verificationGate = verify
        compose.runOnIdle { install(afterPermission = true); install(afterPermission = true); install() }
        val jobs = runtime.installScope.coroutineContext[Job]!!.children.toList()
        evidence.record("permission-return-repeat activeJobs=${jobs.size}")
        verify.complete(Unit)
        runBlocking { withTimeout(15_000) { jobs.joinAll() } }
        record("permission-return-repeat-complete")
        assertEquals("F6 one permission-return install job", 1, jobs.size)
        assertEquals("one initial and one return SHA recheck", 2, runtime.installer.verifications.get())
        assertEquals(1, runtime.permissionRequests.size)
        assertEquals(listOf(apkA), runtime.installer.installed.toList())
        assertOwnedByA(UpdateStage.INSTALLING)
    }

    @Test fun f6ExplicitCancelAndManualBSelectionCannotReceiveStaleAInstallerCompletion() {
        val verify = CompletableDeferred<Unit>()
        runtime.installer.verificationGate = verify
        runtime.installer.permission = true
        compose.runOnIdle { install() }
        compose.waitUntil(15_000) { runtime.installer.verifications.get() == 1 }
        val jobs = runtime.installScope.coroutineContext[Job]!!.children.toList()
        compose.runOnIdle {
            runtime.manager.cancel()
            runtime.manager.finishCancellation()
            runtime.manager.open(b)
        }
        record("explicit-cancel-manual-B-before-A-completion")
        assertEquals("manual selection after cancel remains usable", b, runtime.manager.state.value.release)
        assertEquals(UpdateStage.IDLE, runtime.manager.state.value.stage)
        verify.complete(Unit)
        runBlocking { withTimeout(15_000) { jobs.joinAll() } }
        record("stale-A-completion-after-explicit-B")
        assertEquals("F6 B remains selected after A completes", b, runtime.manager.state.value.release)
        assertEquals("F6 stale A cannot mutate B's IDLE stage", UpdateStage.IDLE, runtime.manager.state.value.stage)
        assertFalse("B is not marked verified by A", runtime.manager.state.value.packageVerified)
        assertTrue("canceled A never reaches the installer", runtime.installer.installed.isEmpty())
        assertFalse("B waits for an explicit download", apkB.exists())
        assertEquals(1, runtime.updateContext.downloads.get())
        assertEquals(1, runtime.transport.requests.get())
    }

    @Test fun cancelRetryAndExplicitManualSelectionRemainUsableWithoutAutomaticDownload() {
        runtime.manager.cancel(); runtime.manager.finishCancellation()
        assertEquals(UpdateStage.CANCELED, runtime.manager.state.value.stage)
        runtime.manager.open(b)
        record("explicit-manual-B-after-cancel")
        assertEquals(b, runtime.manager.state.value.release)
        assertEquals(UpdateStage.IDLE, runtime.manager.state.value.stage)
        assertEquals(1, runtime.updateContext.downloads.get())
        assertEquals(1, runtime.transport.requests.get())
        runtime.versions.enqueue(b)
        runtime.manager.startDownload()
        runBlocking { withContext(Dispatchers.IO) { runtime.manager.download() } }
        record("explicit-manual-B-downloaded")
        assertEquals(UpdateStage.READY, runtime.manager.state.value.stage)
        assertEquals(b, runtime.manager.state.value.release)
        assertTrue(verifyApkSha256(apkB, b.sha256))
        assertEquals(2, runtime.updateContext.downloads.get())
        assertEquals(2, runtime.transport.requests.get())
    }
}
