package top.alanvampuke.dininglog.core

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class StartupUpdatesTest {
    private val release = AppRelease(6, "0.6.0", "https://github.com/vampuke/dining-log/releases/download/v0.6.0/app-release.apk",
        "0".repeat(64), "更新说明", 1793544038)
    private class Preferences(enabled: Boolean) : UpdatePreferences {
        val stored = MutableStateFlow(enabled)
        override val updateChecksEnabled = stored
        override suspend fun setUpdateChecksEnabled(enabled: Boolean) { stored.value = enabled }
    }

    @Test fun delayedStoredOptOutNeverOffersALaterOptInResultAtStartup() = runTest {
        val setting = MutableSharedFlow<Boolean>()
        val preferences = object : UpdatePreferences {
            override val updateChecksEnabled = setting
            override suspend fun setUpdateChecksEnabled(enabled: Boolean) { setting.emit(enabled) }
        }
        var calls = 0
        val checker = AppUpdateChecker(object : AppVersionGateway {
            override suspend fun latest(): AppRelease { calls++; return release }
        }, preferences, 5, backgroundScope)
        runCurrent()
        assertEquals(0, calls)
        assertNull(checker.state.value.startupRelease)
        setting.emit(false); runCurrent()
        assertFalse(checker.state.value.enabled)
        assertEquals(0, calls)
        setting.emit(true); runCurrent()
        assertEquals(1, calls)
        assertEquals(release, checker.state.value.release)
        assertNull(checker.state.value.startupRelease)
    }

    @Test fun firstEnabledStartupOfferIsRetainedAcrossLaterAutomaticAndManualResults() = runTest {
        val preferences = Preferences(true)
        val first = CompletableDeferred<AppRelease>()
        var response = release.copy(versionCode = 7)
        var calls = 0
        val checker = AppUpdateChecker(object : AppVersionGateway {
            override suspend fun latest(): AppRelease {
                calls++
                return if (calls == 1) first.await() else response
            }
        }, preferences, 5, backgroundScope)
        runCurrent()
        assertTrue(checker.state.value.checking)
        assertNull(checker.state.value.startupRelease)
        first.complete(release); runCurrent()
        assertEquals(release, checker.state.value.release)
        assertEquals(release, checker.state.value.startupRelease)
        checker.setEnabled(false); runCurrent()
        assertNull(checker.state.value.release)
        assertEquals(release, checker.state.value.startupRelease)
        checker.setEnabled(true); runCurrent()
        assertEquals(response, checker.state.value.release)
        assertEquals(release, checker.state.value.startupRelease)
        response = release.copy(versionCode = 8)
        checker.checkNow()
        assertTrue(checker.state.value.manual)
        assertEquals(response, checker.state.value.release)
        assertEquals(release, checker.state.value.startupRelease)
        assertEquals(3, calls)
    }

    @Test fun failedInvalidEqualAndOlderStartupOutcomesNeverOfferALaterAutomaticUpdate() = runTest {
        for (candidate in listOf(null, release.copy(sha256 = "invalid"),
            release.copy(versionCode = 5), release.copy(versionCode = 4))) {
            val preferences = Preferences(true)
            var response = candidate
            var calls = 0
            val checker = AppUpdateChecker(object : AppVersionGateway {
                override suspend fun latest(): AppRelease {
                    calls++
                    return response ?: throw IOException("Controlled startup failure")
                }
            }, preferences, 5, backgroundScope)
            runCurrent()
            assertNull(checker.state.value.release)
            assertNull(checker.state.value.startupRelease)
            assertFalse(checker.state.value.failed)
            assertFalse(checker.state.value.upToDate)
            assertFalse(checker.state.value.checking)
            checker.setEnabled(false); runCurrent()
            response = release
            checker.setEnabled(true); runCurrent()
            assertEquals(2, calls)
            assertEquals(release, checker.state.value.release)
            assertNull(checker.state.value.startupRelease)
        }
    }

    @Test fun cancellingTheFirstAutomaticCheckDoesNotMakeALaterOptInAStartupOffer() = runTest {
        val preferences = Preferences(true)
        val first = CompletableDeferred<AppRelease>()
        var calls = 0
        var cancelled = 0
        val checker = AppUpdateChecker(object : AppVersionGateway {
            override suspend fun latest(): AppRelease {
                calls++
                if (calls != 1) return release
                try { return first.await() }
                catch (error: CancellationException) { cancelled++; throw error }
            }
        }, preferences, 5, backgroundScope)
        runCurrent()
        assertTrue(checker.state.value.checking)
        checker.setEnabled(false); runCurrent()
        assertEquals(1, cancelled)
        assertFalse(checker.state.value.checking)
        first.complete(release)
        checker.setEnabled(true); runCurrent()
        assertEquals(2, calls)
        assertEquals(release, checker.state.value.release)
        assertNull(checker.state.value.startupRelease)
    }

    @Test fun manualRequestDuringPreferenceRestoreNeverBecomesAStartupOffer() = runTest {
        val setting = MutableSharedFlow<Boolean>()
        val preferences = object : UpdatePreferences {
            override val updateChecksEnabled = setting
            override suspend fun setUpdateChecksEnabled(enabled: Boolean) { setting.emit(enabled) }
        }
        val first = CompletableDeferred<AppRelease>()
        val later = release.copy(versionCode = 7)
        var calls = 0
        val checker = AppUpdateChecker(object : AppVersionGateway {
            override suspend fun latest(): AppRelease {
                calls++
                return if (calls == 1) first.await() else later
            }
        }, preferences, 5, backgroundScope)
        runCurrent()
        val manual = launch(start = CoroutineStart.UNDISPATCHED) { checker.checkNow() }
        setting.emit(true); runCurrent()
        assertEquals(1, calls)
        assertTrue(checker.state.value.manual)
        assertTrue(checker.state.value.checking)
        first.complete(release); manual.join()
        assertEquals(release, checker.state.value.release)
        assertNull(checker.state.value.startupRelease)
        setting.emit(false); runCurrent()
        setting.emit(true); runCurrent()
        assertEquals(2, calls)
        assertEquals(later, checker.state.value.release)
        assertFalse(checker.state.value.manual)
        assertNull(checker.state.value.startupRelease)
    }
}
