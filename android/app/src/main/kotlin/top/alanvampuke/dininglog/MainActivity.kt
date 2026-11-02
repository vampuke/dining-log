package top.alanvampuke.dininglog

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
import android.view.Window
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
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
import top.alanvampuke.dininglog.feature.theme.QuietGridDark
import top.alanvampuke.dininglog.feature.theme.QuietGridLight
import top.alanvampuke.dininglog.feature.settings.UpdateDialog
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
    @Inject lateinit var updates: AppUpdateChecker
    @Inject lateinit var inAppUpdates: InAppUpdates
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        inAppUpdates.install(this, { intent -> installPermissionLaunch(intent) }, afterPermission = true)
    }
    private fun installPermissionLaunch(intent: Intent) {
        try { installPermission.launch(intent) }
        catch (_: Exception) { inAppUpdates.fail("无法打开安装权限设置，请打开下载页") }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent {
            val choice by preferences.theme.collectAsStateWithLifecycle(ThemeChoice.SYSTEM)
            val updateState by updates.state.collectAsStateWithLifecycle()
            val installState by inAppUpdates.state.collectAsStateWithLifecycle()
            var showUpdate by remember { mutableStateOf(false) }
            LaunchedEffect(installState.stage) {
                if (installState.stage == UpdateStage.READY) {
                    showUpdate = true
                    inAppUpdates.install(this@MainActivity, ::installPermissionLaunch)
                }
            }
            val dark = when (choice) { ThemeChoice.SYSTEM -> isSystemInDarkTheme(); ThemeChoice.DARK -> true; ThemeChoice.LIGHT -> false }
            val scope = rememberCoroutineScope()
            SideEffect { applySystemBarTheme(window, dark) }
            QuietGridTheme(dark) {
                if (showUpdate) UpdateDialog(installState, inAppUpdates::startDownload, inAppUpdates::cancel,
                    onInstall = { inAppUpdates.install(this@MainActivity, ::installPermissionLaunch) },
                    onDownloadPage = {
                        try { installState.release?.let { startActivity(appUpdateIntent(it)) } }
                        catch (_: Exception) { inAppUpdates.fail("无法打开下载页，请安装或启用浏览器后重试") }
                    }, onDismiss = { showUpdate = false })
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
                        ProductRoot(vm, session.username, choice, onTheme = { next -> scope.launch { preferences.setTheme(next) } }, onLogout = logout,
                            version = BuildConfig.VERSION_NAME + if (BuildConfig.DEBUG) " 调试版" else "",
                            updateState = updateState, onUpdateToggle = { enabled -> scope.launch { updates.setEnabled(enabled) } },
                            onOpenUpdate = { release -> inAppUpdates.open(release); showUpdate = true })
                    })
            }
        }
    }
}

@Suppress("DEPRECATION")
fun applySystemBarTheme(window: Window, dark: Boolean) {
    // API 26 otherwise keeps the light scrim chosen before an in-app theme
    // switch. Let the approved Compose background paint both system bars.
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = android.graphics.Color.TRANSPARENT
    window.setBackgroundDrawable(ColorDrawable((if (dark) QuietGridDark else QuietGridLight).bg.toArgb()))
    if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
    }
}

fun appUpdateIntent(release: AppRelease): Intent {
    require(release.valid && isAllowedUpdateUrl(release.url, top.alanvampuke.dininglog.data.ApiConfiguration.BASE_URL))
    return Intent(Intent.ACTION_VIEW, Uri.parse(release.url))
}
