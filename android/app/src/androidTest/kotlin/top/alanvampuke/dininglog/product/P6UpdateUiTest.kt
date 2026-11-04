package top.alanvampuke.dininglog.product

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.alanvampuke.dininglog.BuildConfig
import top.alanvampuke.dininglog.appUpdateIntent
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.*
import top.alanvampuke.dininglog.feature.settings.*
import top.alanvampuke.dininglog.feature.theme.*

@RunWith(AndroidJUnit4::class)
class P6UpdateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun manualCheckWithStartupDisabledShowsAnEntryThatOpensTheDiscoveredRelease() {
        val release = AppRelease(BuildConfig.VERSION_CODE + 1, "next", "https://github.com/vampuke/dining-log/releases/download/next/app-release.apk",
            "0".repeat(64), "公开测试版本", 1793544038)
        val preferences = object : UpdatePreferences {
            override val updateChecksEnabled = MutableStateFlow(false)
            override suspend fun setUpdateChecksEnabled(enabled: Boolean) { updateChecksEnabled.value = enabled }
        }
        var calls = 0
        var opened: AppRelease? = null
        lateinit var checker: AppUpdateChecker
        compose.setContent { QuietGridTheme(true) {
            val scope = rememberCoroutineScope()
            val updates = remember { AppUpdateChecker(object : AppVersionGateway {
                override suspend fun latest(): AppRelease { calls++; return release }
            }, preferences, BuildConfig.VERSION_CODE, scope).also { checker = it } }
            val state by updates.state.collectAsState()
            SettingsScreen(SettingsUiState(), "p6_owner", ThemeChoice.DARK, BuildConfig.VERSION_NAME,
                onPassword = { _, _ -> }, onLogout = {}, onTheme = {}, bottomBar = {}, updateState = state,
                onCheckUpdate = { scope.launch { updates.checkNow() } }, onOpenUpdate = { opened = it })
        } }
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("check-update"))
        compose.onNodeWithTag("check-update").performClick()
        compose.runOnIdle {
            assertEquals(1, calls)
            assertFalse(checker.state.value.enabled)
            assertFalse(checker.state.value.checking)
            assertTrue(checker.state.value.manual)
        }
        compose.onNodeWithTag("update-checks").assertIsOff()
        compose.onNodeWithTag("settings-scroll").performScrollToIndex(0)
        compose.onNodeWithTag("app-update").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(release, opened) }
        compose.onNodeWithTag("update-up-to-date").assertDoesNotExist()
    }
    @Test fun failedManualCheckReplacesTheLatestVersionMessageAndAllowsRetry() {
        var calls = 0
        val release = AppRelease(BuildConfig.VERSION_CODE, "current", "https://github.com/vampuke/dining-log/releases/download/current/app-release.apk",
            "0".repeat(64), "公开测试版本", 1793544038)
        val preferences = object : UpdatePreferences {
            override val updateChecksEnabled = MutableStateFlow(false)
            override suspend fun setUpdateChecksEnabled(enabled: Boolean) { updateChecksEnabled.value = enabled }
        }
        compose.setContent { QuietGridTheme(true) {
            val scope = rememberCoroutineScope()
            val checker = remember { AppUpdateChecker(object : AppVersionGateway {
                override suspend fun latest(): AppRelease {
                    calls++
                    if (calls == 2) throw IOException("Public UI fixture failure")
                    return release
                }
            }, preferences, BuildConfig.VERSION_CODE, scope) }
            val state by checker.state.collectAsState()
            SettingsScreen(SettingsUiState(), "p6_owner", ThemeChoice.DARK, BuildConfig.VERSION_NAME,
                onPassword = { _, _ -> }, onLogout = {}, onTheme = {}, bottomBar = {}, updateState = state,
                onCheckUpdate = { scope.launch { checker.checkNow() } })
        } }
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("check-update"))
        compose.onNodeWithTag("check-update").performClick()
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("update-up-to-date"))
        compose.onNodeWithTag("update-up-to-date").assertTextEquals("已是最新版本")
        compose.onNodeWithTag("check-update").performClick()
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("update-check-error"))
        compose.onNodeWithTag("update-check-error").assertTextEquals("检查失败，请重试")
        compose.onNodeWithTag("update-up-to-date").assertDoesNotExist()
        compose.onNodeWithTag("check-update").assertIsEnabled().performClick()
        compose.onNodeWithTag("update-up-to-date").assertExists()
        compose.onNodeWithTag("update-check-error").assertDoesNotExist()
        compose.runOnIdle { assertEquals(3, calls) }
    }
    @Test fun inlineEntryAllowsOptOutAndInstallProviderExposesOnlyTheUpdatesSubdirectory() {
        val release = AppRelease(BuildConfig.VERSION_CODE + 1, "0.7.0", "https://github.com/vampuke/dining-log/releases/download/v0.7.0/app-release.apk",
            "0".repeat(64), "新的发布说明", 1793544038)
        var state by mutableStateOf(AppUpdateState(true, release))
        var opened: AppRelease? = null
        var toggled: Boolean? = null
        compose.setContent { QuietGridTheme(true) {
            SettingsScreen(SettingsUiState(), "p6_owner", ThemeChoice.DARK, BuildConfig.VERSION_NAME,
                onPassword = { _, _ -> }, onLogout = {}, onTheme = {}, bottomBar = { BottomTabs("settings") {} },
                updateState = state, onUpdateToggle = { enabled -> toggled = enabled; state = AppUpdateState(enabled) }, onOpenUpdate = { opened = it })
        } }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("app-update").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(release, opened) }
        val intent = appUpdateIntent(release)
        assertEquals(Intent.ACTION_VIEW, intent.action); assertEquals(release.url, intent.data.toString())
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("update-checks"))
        compose.onNodeWithTag("update-checks").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertEquals(false, toggled) }
        compose.onNodeWithTag("settings-scroll").performScrollToIndex(0)
        compose.onNodeWithTag("app-update").assertDoesNotExist()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(permissions.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        val authority = "${context.packageName}.fileprovider"
        val provider = context.packageManager.resolveContentProvider(authority, 0)!!
        assertFalse(provider.exported); assertTrue(provider.grantUriPermissions)
        val folder = java.io.File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = java.io.File(folder, "app-public-fixture.apk").apply { writeText("public fixture") }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, apk)
            assertEquals(authority, uri.authority)
            assertEquals("content", uri.scheme)
            try {
                androidx.core.content.FileProvider.getUriForFile(context, authority, java.io.File(context.cacheDir, "private.txt"))
                fail("FileProvider must reject cache root files")
            } catch (_: IllegalArgumentException) { }
            val paths = context.resources.getXml(top.alanvampuke.dininglog.R.xml.file_paths)
            var entries = 0
            paths.use {
                while (it.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (it.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && it.name != "paths") {
                        entries++
                        assertEquals("cache-path", it.name)
                        assertEquals("updates/", it.getAttributeValue(null, "path"))
                    }
                }
            }
            assertEquals(1, entries)
        } finally { apk.delete() }
    }
}
