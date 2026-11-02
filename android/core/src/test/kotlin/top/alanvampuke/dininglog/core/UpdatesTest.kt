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
