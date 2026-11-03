package top.alanvampuke.dininglog.core

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesTest {
    private val release = AppRelease(6, "0.6.0", "https://github.com/vampuke/dining-log/releases/download/v0.6.0/app-release.apk",
        "0".repeat(64), "新增账号管理与交互优化", 1793544038)
    private class Preferences(enabled: Boolean = true) : UpdatePreferences {
        val stored = MutableStateFlow(enabled)
        override val updateChecksEnabled = stored
        override suspend fun setUpdateChecksEnabled(enabled: Boolean) { stored.value = enabled }
    }
    @Test fun comparesVersionCodesEvenWhenVersionNamesSortInTheOppositeOrder() = runTest {
        for ((installed, latest, expected) in listOf(Triple(5, 6, true), Triple(6, 6, false), Triple(10, 9, false), Triple(9, 10, true))) {
            val candidate = release.copy(versionCode = latest, versionName = if (latest == 10) "0.1.0" else "9.99.0")
            val checker = AppUpdateChecker(object : AppVersionGateway { override suspend fun latest() = candidate }, Preferences(), installed, backgroundScope)
            runCurrent()
            assertEquals(if (expected) candidate else null, checker.state.value.release)
        }
    }
    @Test fun waitsForPersistedSettingAndMakesNoRequestWhenItIsOff() = runTest {
        val setting = MutableSharedFlow<Boolean>()
        val preferences = object : UpdatePreferences {
            override val updateChecksEnabled = setting
            override suspend fun setUpdateChecksEnabled(enabled: Boolean) { setting.emit(enabled) }
        }
        var calls = 0
        val checker = AppUpdateChecker(object : AppVersionGateway {
            override suspend fun latest(): AppRelease { calls++; return release }
        }, preferences, 5, backgroundScope)
        runCurrent(); assertEquals(0, calls)
        setting.emit(false); runCurrent()
        assertEquals(AppUpdateState(false), checker.state.value); assertEquals(0, calls)
        setting.emit(true); runCurrent()
        assertEquals(1, calls); assertEquals(release, checker.state.value.release)
    }
    @Test fun disablingPersistsCancelsTheInFlightRequestAndRemovesTheEntry() = runTest {
        val preferences = Preferences()
        val gate = CompletableDeferred<AppRelease>()
        var calls = 0; var cancelled = 0
        val checker = AppUpdateChecker(object : AppVersionGateway {
            override suspend fun latest(): AppRelease {
                calls++
                try { return gate.await() } catch (error: CancellationException) { cancelled++; throw error }
            }
        }, preferences, 5, backgroundScope)
        runCurrent(); assertEquals(1, calls)
        checker.setEnabled(false); runCurrent()
        assertFalse(preferences.stored.value); assertEquals(1, cancelled)
        gate.complete(release); runCurrent()
        assertEquals(AppUpdateState(false), checker.state.value)
        checker.setEnabled(true); runCurrent()
        assertTrue(preferences.stored.value); assertEquals(2, calls)
        assertEquals(release, checker.state.value.release)
        checker.setEnabled(false); runCurrent()
        assertNull(checker.state.value.release)
    }
    @Test fun backgroundFailureIsSilentAndDoesNotDisableTheUserSetting() = runTest {
        val checker = AppUpdateChecker(object : AppVersionGateway {
            override suspend fun latest(): AppRelease = throw IOException("Public fixture transport failure")
        }, Preferences(), 5, backgroundScope)
        runCurrent()
        assertEquals(AppUpdateState(true), checker.state.value)
    }
    @Test fun manualCheckFindsAnUpdateWithStartupChecksDisabled() = runTest {
        val preferences = Preferences(false)
        var calls = 0
        val checker = AppUpdateChecker(object : AppVersionGateway {
            override suspend fun latest(): AppRelease { calls++; return release }
        }, preferences, 5, backgroundScope)
        runCurrent()
        assertEquals(0, calls)
        checker.checkNow()
        assertEquals(1, calls)
        assertEquals(release, checker.state.value.release)
        assertTrue(checker.state.value.manual)
        assertFalse(checker.state.value.enabled)
        assertFalse(checker.state.value.checking)
        assertFalse(checker.state.value.upToDate)
        assertFalse(checker.state.value.failed)
    }
    @Test fun repeatedManualCallsShareOneInFlightRequest() = runTest {
        val gate = CompletableDeferred<AppRelease>()
        var calls = 0
        val checker = AppUpdateChecker(object : AppVersionGateway {
            override suspend fun latest(): AppRelease { calls++; return gate.await() }
        }, Preferences(false), 5, backgroundScope)
        runCurrent()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { checker.checkNow() }
        checker.checkNow()
        assertEquals(1, calls)
        assertTrue(checker.state.value.checking)
        assertFalse(first.isCompleted)
        gate.complete(release); first.join()
        assertEquals(1, calls)
        assertFalse(checker.state.value.checking)
        assertEquals(release, checker.state.value.release)
    }
    @Test fun cancellingABackgroundRecheckKeepsAnExistingManualUpdateVisibleWithTheSettingOff() = runTest {
        val preferences = Preferences(false)
        val gate = CompletableDeferred<AppRelease>()
        var calls = 0
        val checker = AppUpdateChecker(object : AppVersionGateway {
            override suspend fun latest(): AppRelease {
                calls++
                return if (calls == 1) release else gate.await()
            }
        }, preferences, 5, backgroundScope)
        runCurrent(); checker.checkNow()
        checker.setEnabled(true); runCurrent()
        assertEquals(2, calls)
        assertTrue(checker.state.value.checking)
        checker.setEnabled(false); runCurrent()
        assertFalse(checker.state.value.enabled)
        assertFalse(checker.state.value.checking)
        assertTrue(checker.state.value.manual)
        assertEquals(release, checker.state.value.release)
    }
    @Test fun manualAndBackgroundChecksUseTheSameGuardAndCancellationAllowsRetry() = runTest {
        val preferences = Preferences()
        var calls = 0
        val gate = CompletableDeferred<AppRelease>()
        val checker = AppUpdateChecker(object : AppVersionGateway {
            override suspend fun latest(): AppRelease { calls++; return gate.await() }
        }, preferences, 5, backgroundScope)
        runCurrent()
        checker.checkNow()
        assertEquals(1, calls)
        assertTrue(checker.state.value.checking)
        checker.setEnabled(false); runCurrent()
        assertFalse(checker.state.value.checking)
        val manual = launch(start = CoroutineStart.UNDISPATCHED) { checker.checkNow() }
        assertEquals(2, calls)
        manual.cancelAndJoin()
        assertFalse(checker.state.value.checking)
        gate.complete(release)
        checker.checkNow()
        assertEquals(3, calls)
        assertEquals(release, checker.state.value.release)
        assertFalse(checker.state.value.checking)
    }
    @Test fun manualFailuresAndInvalidMetadataNeverReportUpToDateAndCanBeRetried() = runTest {
        var candidate = release.copy(versionCode = 5)
        var failure: Exception? = null
        val checker = AppUpdateChecker(object : AppVersionGateway {
            override suspend fun latest(): AppRelease {
                failure?.let { throw it }
                return candidate
            }
        }, Preferences(false), 5, backgroundScope)
        runCurrent()
        checker.checkNow()
        assertTrue(checker.state.value.upToDate)
        failure = IOException("Public fixture network failure")
        checker.checkNow()
        assertTrue(checker.state.value.failed)
        assertFalse(checker.state.value.upToDate)
        assertFalse(checker.state.value.checking)
        failure = null; candidate = release.copy(sha256 = "invalid")
        checker.checkNow()
        assertTrue(checker.state.value.failed)
        assertFalse(checker.state.value.upToDate)
        assertNull(checker.state.value.release)
        candidate = release
        checker.checkNow()
        assertFalse(checker.state.value.failed)
        assertEquals(release, checker.state.value.release)
        assertFalse(checker.state.value.checking)
    }
    @Test fun validSameOrOlderManualResponseConfirmsUpToDate() = runTest {
        for (latestCode in listOf(4, 5)) {
            val checker = AppUpdateChecker(object : AppVersionGateway {
                override suspend fun latest() = release.copy(versionCode = latestCode)
            }, Preferences(false), 5, backgroundScope)
            runCurrent(); checker.checkNow()
            assertTrue(checker.state.value.upToDate)
            assertFalse(checker.state.value.failed)
            assertFalse(checker.state.value.checking)
            assertNull(checker.state.value.release)
        }
    }
    @Test fun manualCompletionKeepsTheLatestPreferenceInBothDirections() = runTest {
        for (initiallyEnabled in listOf(true, false)) {
            val preferences = Preferences(initiallyEnabled)
            val gate = CompletableDeferred<AppRelease>()
            var calls = 0
            val checker = AppUpdateChecker(object : AppVersionGateway {
                override suspend fun latest(): AppRelease {
                    calls++
                    if (initiallyEnabled && calls == 1) return release.copy(versionCode = 5)
                    return gate.await()
                }
            }, preferences, 5, backgroundScope)
            runCurrent()
            val request = launch(start = CoroutineStart.UNDISPATCHED) { checker.checkNow() }
            val expectedCalls = if (initiallyEnabled) 2 else 1
            checker.setEnabled(!initiallyEnabled); runCurrent()
            assertEquals(!initiallyEnabled, checker.state.value.enabled)
            assertTrue(checker.state.value.checking)
            assertEquals(expectedCalls, calls)
            gate.complete(release); request.join()
            assertEquals(preferences.stored.value, checker.state.value.enabled)
            assertEquals(!initiallyEnabled, checker.state.value.enabled)
            assertEquals(release, checker.state.value.release)
            assertTrue(checker.state.value.manual)
            assertFalse(checker.state.value.checking)
        }
    }
    @Test fun rejectsInvalidReleaseMetadataAndUnsafeDownloadLinks() = runTest {
        for (candidate in listOf(release.copy(versionCode = 0), release.copy(versionName = ""), release.copy(sha256 = "invalid"),
            release.copy(releasedAt = 0), release.copy(url = "http://example.invalid/app.apk"),
            release.copy(url = "https://userinfo@example.invalid/app.apk"), release.copy(url = "file:///app.apk"))) {
            assertFalse(candidate.valid)
            val checker = AppUpdateChecker(object : AppVersionGateway { override suspend fun latest() = candidate }, Preferences(), 5, backgroundScope)
            runCurrent(); assertNull(checker.state.value.release)
        }
    }
    @Test fun sha256AcceptsExactBytesAndRejectsCorruptionMalformedDigestAndEmptyFiles() {
        val file = java.io.File.createTempFile("p8-package", ".apk")
        try {
            file.writeText("abc")
            val digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            assertTrue(verifyApkSha256(file, digest)); assertTrue(verifyApkSha256(file, digest.uppercase()))
            file.appendText("changed"); assertFalse(verifyApkSha256(file, digest))
            assertFalse(verifyApkSha256(file, "bad"))
            file.writeBytes(byteArrayOf()); assertFalse(verifyApkSha256(file, digest))
            file.delete(); assertFalse(verifyApkSha256(file, digest))
        } finally { file.delete() }
    }
    @Test fun interruptedPackagesAreSweptAndOnlyVerifiedBytesReceiveTheFinalName() {
        val directory = java.nio.file.Files.createTempDirectory("p9-updates").toFile()
        val digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        fun packageFile(name: String, bytes: String = "abc") = java.io.File(directory, name).apply { writeText(bytes) }
        try {
            val partial = packageFile("app-8.apk.part", "interrupted")
            val otherPartial = packageFile("app-9.apk.part", "interrupted")
            val legacy = packageFile("app-6.apk", "old partial APK")
            val current = packageFile("app-8.apk")
            val unrelated = packageFile("readme.txt")
            val subdirectory = java.io.File(directory, "keep.part").apply { mkdir() }
            sweepUpdateOrphans(directory, 8)
            assertFalse(partial.exists()); assertFalse(otherPartial.exists()); assertFalse(legacy.exists())
            assertTrue(current.exists()); assertTrue(unrelated.exists()); assertTrue(subdirectory.isDirectory)
            // A new controller has no retained installation state, so all old APKs are orphaned.
            sweepUpdateOrphans(directory)
            assertFalse(current.exists()); assertTrue(unrelated.exists())
            partial.writeText("ab")
            assertFalse(current.exists())
            assertFailsWith<IOException> { promoteVerifiedApk(partial, current, digest) }
            assertFalse(partial.exists()); assertFalse(current.exists())
            partial.writeText("abc")
            promoteVerifiedApk(partial, current, digest)
            assertFalse(partial.exists()); assertEquals("abc", current.readText())
            assertTrue(verifyApkSha256(current, digest))
            sweepUpdateOrphans(directory, 9)
            assertFalse(current.exists())
            // Failed renames must not turn a partial file into a ready package.
            partial.writeText("abc"); current.mkdir()
            java.io.File(current, "block-replacement").writeText("fixture")
            assertFailsWith<IOException> { promoteVerifiedApk(partial, current, digest) }
            assertTrue(partial.exists()); assertTrue(current.isDirectory)
        } finally { directory.deleteRecursively() }
    }
    @Test fun downloadWhitelistRejectsArbitraryHostsCredentialsTraversalAndInsecureRedirects() {
        val api = "https://dining.alanvampuke.top/api/v1/"
        assertTrue(isAllowedUpdateUrl(release.url, api))
        assertTrue(isAllowedUpdateUrl("https://dining.alanvampuke.top/updates/app.apk", api))
        for (url in listOf("http://github.com/vampuke/dining-log/releases/download/v8/app.apk",
            "https://github.com/attacker/repo/releases/download/v8/app.apk",
            "https://dining.alanvampuke.top.evil.invalid/app.apk", "https://evil.invalid/app.apk",
            "https://user@dining.alanvampuke.top/app.apk", "https://dining.alanvampuke.top:444/app.apk",
            "https://github.com/vampuke/dining-log/releases/download/../app.apk",
            "https://github.com/vampuke/dining-log/releases/download/v8/%2e%2e/app.apk")) {
            assertFalse(isAllowedUpdateUrl(url, api), url)
        }
        val signed = "https://release-assets.githubusercontent.com/github-production-release-asset/123?sig=public-fixture"
        assertTrue(isAllowedUpdateRedirect(signed, release.url, api))
        assertFalse(isAllowedUpdateRedirect(signed, "https://dining.alanvampuke.top/app.apk", api))
        assertFalse(isAllowedUpdateRedirect("http://release-assets.githubusercontent.com/app.apk", release.url, api))
        assertFalse(isAllowedUpdateRedirect("https://evil.invalid/app.apk", release.url, api))
    }
    @Test fun comparisonUsesPositiveIntegerVersionCodesAndProgressTracksBytes() {
        assertTrue(isNewerVersion(Int.MAX_VALUE, 7)); assertFalse(isNewerVersion(-1, 7))
        assertFalse(isNewerVersion(7, 7)); assertFalse(isNewerVersion(6, 7))
        assertEquals(25, UpdateInstallState(downloaded = 25, total = 100).percent)
        assertNull(UpdateInstallState(downloaded = 25).percent)
    }

}
