package top.alanvampuke.dininglog.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*
import top.alanvampuke.dininglog.core.ThemeChoice
import top.alanvampuke.dininglog.core.ThemeStyle

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdatePreferencesTest {
    @Test fun updateOptOutAndThemeSurviveClosingAndReopeningTheActualDataStoreFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("p6-settings-", ".preferences_pb", context.cacheDir).also { it.delete() }
        suspend fun close(scope: CoroutineScope) { scope.coroutineContext[Job]!!.cancelAndJoin() }
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = AndroidThemePreferences.fromDataStore(PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file }))
        try {
            assertTrue(first.updateChecksEnabled.first())
            first.setUpdateChecksEnabled(false); first.setTheme(ThemeChoice.DARK)
            assertTrue(file.isFile); assertTrue(file.length() > 0)
        } finally { close(firstScope) }
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val next = AndroidThemePreferences.fromDataStore(PreferenceDataStoreFactory.create(scope = nextScope, produceFile = { file }))
        try {
            assertFalse(next.updateChecksEnabled.first()); assertEquals(ThemeChoice.DARK, next.theme.first())
            next.setUpdateChecksEnabled(true)
            assertTrue(next.updateChecksEnabled.first()); assertEquals(ThemeChoice.DARK, next.theme.first())
        } finally { close(nextScope); file.delete() }
    }

    @Test fun themeStyleDefaultsToInkPaperAndSurvivesClosingAndReopeningTheActualDataStoreFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("p6-style-", ".preferences_pb", context.cacheDir).also { it.delete() }
        suspend fun close(scope: CoroutineScope) { scope.coroutineContext[Job]!!.cancelAndJoin() }
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = AndroidThemePreferences.fromDataStore(PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file }))
        try {
            assertEquals(ThemeStyle.INK_PAPER, first.style.first())
            first.setStyle(ThemeStyle.QUIET_GRID)
            assertEquals(ThemeStyle.QUIET_GRID, first.style.first())
        } finally { close(firstScope) }
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val next = AndroidThemePreferences.fromDataStore(PreferenceDataStoreFactory.create(scope = nextScope, produceFile = { file }))
        try {
            assertEquals(ThemeStyle.QUIET_GRID, next.style.first())
            next.setStyle(ThemeStyle.INK_PAPER)
            assertEquals(ThemeStyle.INK_PAPER, next.style.first())
        } finally { close(nextScope); file.delete() }
    }
}
