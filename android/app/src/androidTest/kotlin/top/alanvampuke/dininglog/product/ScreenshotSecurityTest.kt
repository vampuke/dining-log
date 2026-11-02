package top.alanvampuke.dininglog.product

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.alanvampuke.dininglog.BuildConfig
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.*
import top.alanvampuke.dininglog.feature.restaurants.RecordListScreen
import top.alanvampuke.dininglog.feature.settings.SettingsScreen
import top.alanvampuke.dininglog.feature.theme.QuietGridTheme

@RunWith(AndroidJUnit4::class)
class ScreenshotSecurityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun passwordDismissalAndNavigationRestoreScreenshotsInBothThemes() {
        var dark by mutableStateOf(false)
        var tab by mutableStateOf("restaurants")
        compose.setContent {
            QuietGridTheme(dark) {
                Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                    Box(Modifier.weight(1f)) {
                        if (tab == "settings") {
                            SettingsScreen(SettingsUiState(), "screenshot_test",
                                if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT, BuildConfig.VERSION_NAME,
                                {}, {}, { _, _ -> }, {}, {}, bottomBar = {})
                        } else {
                            RecordListScreen(ListUiState(localScope = false), "", ListFilters(),
                                {}, {}, {}, {}, {})
                        }
                    }
                    // ScreenFrame omits its floating tabs while IME insets are nonzero.
                    // Keep navigation available independently of dialog dismissal insets.
                    BottomTabs(tab) { tab = it }
                }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            compose.onNodeWithTag("screen:restaurant:list").assertExists()
            assertSecureWindow(false, "restaurant list, dark=$night")
            tapTab("tab:settings")
            assertSecureWindow(false, "ordinary settings, dark=$night")
            repeat(2) {
                compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("change-password"))
                compose.onNodeWithTag("change-password").performClick()
                compose.onNodeWithTag("screen:password").assertExists()
                assertSecureWindow(true, "password dialog, dark=$night")
                compose.onNodeWithText("关闭").performClick()
                compose.onNodeWithTag("screen:password").assertDoesNotExist()
                assertSecureWindow(false, "settings after password dismissal, dark=$night")
            }
            tapTab("tab:restaurants")
            compose.onNodeWithTag("screen:restaurant:list").assertExists()
            assertSecureWindow(false, "restaurant list after sensitive flow, dark=$night")
        }
    }

    private fun tapTab(tag: String) {
        compose.onNodeWithTag(tag).assertExists().assertIsDisplayed().performClick()
    }

    private fun assertSecureWindow(expected: Boolean, screen: String) {
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { activity ->
            assertEquals("FLAG_SECURE on $screen",
                if (expected) WindowManager.LayoutParams.FLAG_SECURE else 0,
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
