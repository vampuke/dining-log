package top.alanvampuke.dininglog

import android.content.Intent
import android.net.Uri
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Window
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowInsetsControllerCompat
import dagger.hilt.android.AndroidEntryPoint
import top.alanvampuke.dininglog.core.auth.*
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.theme.QuietGridColors
import top.alanvampuke.dininglog.feature.theme.QuietGridDark
import top.alanvampuke.dininglog.feature.theme.QuietGridLight
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
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent {
            MainActivityContent(this, store, account, gateway, refresh, repository,
                security, preferences, updates, inAppUpdates, ::installPermissionLaunch)
        }
    }
}

@Suppress("DEPRECATION")
fun applySystemBarTheme(window: Window, dark: Boolean, colors: QuietGridColors = if (dark) QuietGridDark else QuietGridLight) {
    // API 26 otherwise keeps the light scrim chosen before an in-app theme
    // switch. Let the approved Compose background paint both system bars.
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = android.graphics.Color.TRANSPARENT
    window.setBackgroundDrawable(ColorDrawable(colors.bg.toArgb()))
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
