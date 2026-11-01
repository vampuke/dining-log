package top.alanvampuke.dininglog

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import top.alanvampuke.dininglog.core.auth.*
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.data.auth.*
import top.alanvampuke.dininglog.feature.auth.AuthRoot
import top.alanvampuke.dininglog.feature.theme.QuietGridTheme
import top.alanvampuke.dininglog.feature.ProductRoot
import top.alanvampuke.dininglog.feature.ProductViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var store: SecureTokenStore
    @Inject lateinit var account: AccountSession
    @Inject lateinit var gateway: AuthGateway
    @Inject lateinit var refresh: RefreshCoordinator
    @Inject lateinit var repository: ProductRepository
    @Inject lateinit var security: AccountSecurity
    @Inject lateinit var preferences: ThemePreferences
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent {
            val choice by preferences.theme.collectAsStateWithLifecycle(ThemeChoice.SYSTEM)
            val dark = when (choice) { ThemeChoice.SYSTEM -> isSystemInDarkTheme(); ThemeChoice.DARK -> true; ThemeChoice.LIGHT -> false }
            val scope = rememberCoroutineScope()
            SideEffect {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
                }
            }
            QuietGridTheme(dark) {
                AuthRoot(account, gateway,
                    onRestore = { withContext(Dispatchers.IO) {
                        val saved = store.read()
                        if (saved != null) { account.restoreCachedSession(saved); refresh.refreshAfter401(null) } else account.clear()
                    } },
                    onLogout = {
                        withContext(Dispatchers.IO) {
                            val token = account.session.value?.refreshToken
                            var warning: String? = null
                            try { if (token != null) withTimeout(3000) { gateway.logout(token) } }
                            catch (_: Exception) { warning = "本地已退出；服务器会话未能撤销，可在其他设备的会话管理中撤销。" }
                            finally { account.clear() }
                            warning
                        }
                    },
                    onCopyRecoveryCodes = { codes ->
                        val clip = ClipData.newPlainText("食记恢复码", codes.joinToString("\n"))
                        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                    }, onAuthenticated = { session, logout ->
                        val generation = account.generation
                        val factory = remember(session.userId, generation) { object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                                ProductViewModel(session.userId, repository, security, account, extras.createSavedStateHandle()) as T
                        } }
                        val vm: ProductViewModel = viewModel(key = "product:${session.userId}:$generation", factory = factory)
                        ProductRoot(vm, session.username, choice, onTheme = { next -> scope.launch { preferences.setTheme(next) } }, onLogout = logout)
                    })
            }
        }
    }
}
