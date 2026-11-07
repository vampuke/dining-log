package top.alanvampuke.dininglog

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.core.auth.*
import top.alanvampuke.dininglog.feature.auth.AuthRoot
import top.alanvampuke.dininglog.feature.theme.*
import top.alanvampuke.dininglog.feature.settings.UpdateDialog
import top.alanvampuke.dininglog.feature.ProductRoot
import top.alanvampuke.dininglog.feature.ProductViewModel

// Shared production composition. Its injected collaborators let instrumentation
// exercise the real startup/persistence path in the existing non-exported host,
// without Hilt testing dependencies, global hooks or alternate network policy.
@Composable
internal fun MainActivityContent(
    activity: ComponentActivity,
    store: SecureTokenStore,
    account: AccountSession,
    gateway: AuthGateway,
    refresh: RefreshCoordinator,
    repository: ProductRepository,
    security: AccountSecurity,
    preferences: ThemePreferences,
    updates: AppUpdateChecker,
    inAppUpdates: InAppUpdates,
    requestInstallPermission: (Intent) -> Unit,
) {
    val choice by preferences.theme.collectAsStateWithLifecycle(ThemeChoice.SYSTEM)
    val style by preferences.style.collectAsStateWithLifecycle(ThemeStyle.INK_PAPER)
    val updateState by updates.state.collectAsStateWithLifecycle()
    val installState by inAppUpdates.state.collectAsStateWithLifecycle()
    var showUpdate by rememberSaveable { mutableStateOf(false) }
    var launchUpdateAcknowledged by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(installState.stage) {
        if (installState.stage == UpdateStage.READY) {
            showUpdate = true
            inAppUpdates.install(activity, requestInstallPermission)
        }
    }
    // Only the first persisted preference's startup offer may interrupt this
    // launch. Consume it even if a later manual check or opt-out suppresses it.
    // Saved acknowledgement and visibility survive Activity configuration changes.
    LaunchedEffect(updateState.startupRelease, updateState.manual, updateState.enabled) {
        val release = updateState.startupRelease
        if (!launchUpdateAcknowledged && release != null) {
            launchUpdateAcknowledged = true
            if (updateState.enabled && !updateState.manual && inAppUpdates.open(release, automatic = true)) {
                showUpdate = true
            }
        }
    }
    val dark = when (choice) { ThemeChoice.SYSTEM -> isSystemInDarkTheme(); ThemeChoice.DARK -> true; ThemeChoice.LIGHT -> false }
    val colors = if (style == ThemeStyle.INK_PAPER) (if (dark) InkPaperDark else InkPaperLight) else (if (dark) QuietGridDark else QuietGridLight)
    val scope = rememberCoroutineScope()
    SideEffect { applySystemBarTheme(activity.window, dark, colors) }
    QuietGridTheme(dark, style) {
        if (showUpdate) UpdateDialog(installState, inAppUpdates::startDownload, inAppUpdates::cancel,
            onInstall = { inAppUpdates.install(activity, requestInstallPermission) },
            onDownloadPage = {
                try { installState.release?.let { activity.startActivity(appUpdateIntent(it)) } }
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
                (activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
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
                    onCheckUpdate = { scope.launch { updates.checkNow() } },
                    style = style, onStyle = { next -> scope.launch { preferences.setStyle(next) } },
                    onOpenUpdate = { release -> inAppUpdates.open(release); showUpdate = true })
            })
    }
}
