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
    @Test fun showsOnlyAnInlineEntryOpensHttpsAndAllowsOptOutWithoutAnInstallPermission() {
        val release = AppRelease(BuildConfig.VERSION_CODE + 1, "0.7.0", "https://github.com/vampuke/dining-log/releases/download/v0.7.0/app-release.apk",
            "0".repeat(64), "新的发布说明", 1793544038)
        var state by mutableStateOf(AppUpdateState(true, release))
        var opened: AppRelease? = null
        var toggled: Boolean? = null
        compose.setContent { QuietGridTheme(true) {
            SettingsScreen(SettingsUiState(), "p6_owner", emptyList(), ThemeChoice.DARK, BuildConfig.VERSION_NAME,
                {}, {}, { _, _ -> }, {}, {}, {}, { BottomTabs("settings") {} },
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
        assertFalse(permissions.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
    }
}
