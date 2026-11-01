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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import top.alanvampuke.dininglog.core.auth.*
import top.alanvampuke.dininglog.data.auth.*
import top.alanvampuke.dininglog.feature.auth.AuthRoot
import top.alanvampuke.dininglog.feature.theme.QuietGridTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val store = secureTokenStore(applicationContext)
        val account = AccountSession(store, AndroidAccountLocalData(applicationContext, AccountMemory()))
        val gateway = RetrofitAuthGateway.create()
        val refresh = RefreshCoordinator(store, account, gateway)
        setContent {
            QuietGridTheme {
                AuthRoot(account, gateway,
                    onRestore = { withContext(Dispatchers.IO) { if (store.read() != null) refresh.refreshAfter401(null) else account.clear() } },
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
                    })
            }
        }
    }
}
