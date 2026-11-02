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
    @Test fun inlineEntryAllowsOptOutAndInstallProviderExposesOnlyTheUpdatesSubdirectory() {
        val release = AppRelease(BuildConfig.VERSION_CODE + 1, "0.7.0", "https://github.com/vampuke/dining-log/releases/download/v0.7.0/app-release.apk",
            "0".repeat(64), "新的发布说明", 1793544038)
        var state by mutableStateOf(AppUpdateState(true, release))
        var opened: AppRelease? = null
        var toggled: Boolean? = null
        compose.setContent { QuietGridTheme(true) {
            SettingsScreen(SettingsUiState(), "p6_owner", ThemeChoice.DARK, BuildConfig.VERSION_NAME,
                {}, {}, { _, _ -> }, {}, {}, { BottomTabs("settings") {} },
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
