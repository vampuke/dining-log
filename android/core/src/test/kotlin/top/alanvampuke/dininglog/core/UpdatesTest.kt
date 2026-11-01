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
}
