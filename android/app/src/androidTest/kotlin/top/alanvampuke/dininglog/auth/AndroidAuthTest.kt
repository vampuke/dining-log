package top.alanvampuke.dininglog.auth

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import top.alanvampuke.dininglog.core.auth.*
import top.alanvampuke.dininglog.data.auth.*
import top.alanvampuke.dininglog.feature.auth.*
import top.alanvampuke.dininglog.feature.theme.*

@RunWith(AndroidJUnit4::class)
class AndroidAuthTest {
    @get:Rule val compose = createComposeRule()
    @Test fun realAndroidKeystoreRoundTripCorruptionAndMissingKeyReturnLogin() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alias = "dining-log.p2-test.${java.util.UUID.randomUUID()}"
        val path = File(context.noBackupFilesDir, "p2-test.enc")
        val file = AndroidAtomicTokenFile(path)
        val keys = KeystoreKeyManager(alias)
        val store = SecureTokenStore(file, AesGcmTokenCipher(keys, context.packageName))
        val saved = StoredSession("account-a", "session-a", "secret-refresh", "stable-request", 100L)
        try {
            store.write(saved); assertEquals(saved, store.read())
            val bytes = path.readBytes(); assertFalse(String(bytes).contains("secret-refresh"))
            bytes[20] = (bytes[20].toInt() xor 1).toByte(); path.writeBytes(bytes)
            assertNull(store.read()); assertFalse(path.exists())
            store.write(saved)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            assertNull(store.read()); assertFalse(path.exists())
        } finally {
            file.delete(); KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
    private fun form(page: AuthPage, dark: Boolean, scale: Float, error: String? = null) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                QuietGridTheme(dark) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                        AuthForm(page, false, error) { _, _, _ -> }
                    }
                }
            }
        }
    }
    @Test fun loginLightLargeFontShowsPersistent429AndPasswordToggle() {
        form(AuthPage.LOGIN, false, 2f, authErrorText(AuthFailure("RATE_LIMITED", 90)))
        compose.onNodeWithText("用户名", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("显示密码").performScrollTo().performClick()
        compose.onNodeWithText("隐藏密码").assertIsDisplayed()
        compose.onNodeWithText("尝试过于频繁，请在 90 秒后重试。").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("登录").onLast().performScrollTo().assertIsNotEnabled()
    }
    @Test fun registrationDarkLargeFontKeepsInputsAndSubmitReachable() {
        form(AuthPage.REGISTER, true, 2f)
        compose.onNodeWithText("用户名", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("密码", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("注册账号").onLast().performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }
    @Test fun recoveryLightLargeFontKeepsRecoveryInputAndSubmitReachable() {
        form(AuthPage.RECOVER, false, 2f)
        compose.onNodeWithText("一次性恢复码", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("新密码", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("恢复账号").onLast().performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }
    @Test fun loginDarkLargeFontKeepsSubmitReachable() {
        form(AuthPage.LOGIN, true, 2f)
        compose.onAllNodesWithText("登录").onLast().performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }
    @Test fun registrationLightLargeFontKeepsSubmitReachable() {
        form(AuthPage.REGISTER, false, 2f)
        compose.onAllNodesWithText("注册账号").onLast().performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }
    @Test fun recoveryDarkUsesApprovedTokens() {
        assertEquals(0xFF0F0F0E.toInt(), QuietGridDark.bg.toArgb())
        form(AuthPage.RECOVER, true, 1f)
        compose.onNodeWithText("一次性恢复码", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("恢复账号").onLast().performScrollTo().assertIsDisplayed()
    }
}
