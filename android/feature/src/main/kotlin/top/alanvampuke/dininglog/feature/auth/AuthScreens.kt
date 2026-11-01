package top.alanvampuke.dininglog.feature.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.alanvampuke.dininglog.core.auth.*
import top.alanvampuke.dininglog.feature.theme.*

enum class AuthPage(val title: String) { LOGIN("登录"), REGISTER("注册账号"), RECOVER("恢复账号") }
fun authErrorText(error: AuthFailure): String = when (error.code) {
    "RATE_LIMITED" -> "尝试过于频繁，请在 ${error.retryAfter ?: 60} 秒后重试。"
    "INVALID_CREDENTIALS" -> "用户名、密码或恢复码不正确，请检查后重试。"
    "USERNAME_TAKEN" -> "该用户名已被注册，请换一个用户名。"
    "NETWORK_ERROR", "TEMPORARILY_UNAVAILABLE" -> "暂时无法连接，请检查网络后重试。"
    "VALIDATION_ERROR" -> "请检查输入。新密码需 12–128 个字符，不能全为空白或使用常见弱密码。"
    "SESSION_REVOKED", "REFRESH_REUSED" -> "登录已失效，请重新登录。"
    else -> "无法完成操作，请稍后重试。"
}
@Composable
fun AuthRoot(account: AccountSession, gateway: AuthGateway, onRestore: suspend () -> Unit,
    onLogout: suspend () -> String?, onCopyRecoveryCodes: (List<String>) -> Unit,
    onAuthenticated: (@Composable (AuthSession, () -> Unit) -> Unit)? = null) {
    val session by account.session.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(AuthPage.LOGIN) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var acknowledgedFor by rememberSaveable { mutableStateOf<String?>(null) }
    fun launchAction(action: suspend () -> Unit) {
        if (loading) return
        scope.launch {
            loading = true; message = null
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: AuthFailure) { message = authErrorText(e) }
            catch (_: Exception) { message = "安全存储不可用，请重新登录。" }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) {
        loading = true
        try { onRestore() } catch (e: CancellationException) { throw e }
        catch (e: AuthFailure) { message = authErrorText(e) }
        catch (_: Exception) { message = "安全存储不可用，请重新登录。" }
        finally { loading = false }
    }
    val authenticated = session
    if (authenticated != null && (authenticated.recoveryCodes.isEmpty() || acknowledgedFor == authenticated.sessionId) && onAuthenticated != null) {
        key(authenticated.userId) { onAuthenticated(authenticated) { launchAction { message = onLogout(); page = AuthPage.LOGIN } } }
        return
    }
    Surface(color = LocalQuietGridColors.current.bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .verticalScroll(rememberScrollState()).padding(QuietGridDimensions.edge), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("食记 / 账号", style = QuietGridText.date, color = LocalQuietGridColors.current.secondary)
            val current = session
            if (current == null) {
                key(page) {
                    AuthForm(page, loading, message, onSubmit = { username, password, recoveryCode ->
                        launchAction {
                            val next = when (page) {
                                AuthPage.LOGIN -> gateway.login(username, password)
                                AuthPage.REGISTER -> gateway.register(username, password)
                                AuthPage.RECOVER -> gateway.recover(username, recoveryCode, password)
                            }
                            account.install(next)
                        }
                    })
                }
                // Stacked links stay reachable at large font sizes and narrow widths.
                for (target in AuthPage.entries.filter { it != page }) {
                    TextButton(onClick = { page = target; message = null }, enabled = !loading) { Text(target.title) }
                }
                if (message != null) TextButton(onClick = { launchAction { onRestore() } }, enabled = !loading) { Text("重试恢复已保存的登录") }
            } else {
                key(current.sessionId) {
                    Text("${current.username} 的账号", style = MaterialTheme.typography.headlineMedium)
                    Text("已登录", color = LocalQuietGridColors.current.accentInk)
                    if (current.recoveryCodes.isNotEmpty() && acknowledgedFor != current.sessionId) {
                        Text("请安全保存这 8 个一次性恢复码", style = MaterialTheme.typography.titleMedium)
                        Text("此页面只显示一次。恢复账号后，所有旧恢复码与旧会话都会失效。")
                        current.recoveryCodes.forEachIndexed { index, code ->
                            Text("${index + 1}. $code", style = QuietGridText.date, modifier = Modifier.fillMaxWidth())
                        }
                        TextButton(onClick = { onCopyRecoveryCodes(current.recoveryCodes) }) { Text("复制恢复码") }
                        Button(onClick = { acknowledgedFor = current.sessionId }, modifier = Modifier.fillMaxWidth()) { Text("我已安全保存") }
                    }
                    if (message != null) PersistentError(message!!)
                    TextButton(onClick = { launchAction { message = onLogout(); page = AuthPage.LOGIN } }, enabled = !loading) { Text(if (loading) "正在退出…" else "退出账号") }
                }
            }
        }
    }
}
@Composable
fun AuthForm(page: AuthPage, loading: Boolean, error: String?, onSubmit: (String, String, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var recoveryCode by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    Text(page.title, style = MaterialTheme.typography.headlineMedium)
    Text(if (page == AuthPage.LOGIN) "使用独立账号保存你的食记。" else if (page == AuthPage.REGISTER) "注册后会显示 8 个一次性恢复码，请安全保存。" else "输入一个有效恢复码，新密码生效后所有旧会话都会退出。",
        color = LocalQuietGridColors.current.secondary, style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(username, { username = it }, label = { Text("用户名") }, enabled = !loading,
        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("auth-username"), textStyle = QuietGridText.input,
        supportingText = { Text("3–32 位字母、数字或下划线，以字母开头。") })
    if (page == AuthPage.RECOVER) OutlinedTextField(recoveryCode, { recoveryCode = it }, label = { Text("一次性恢复码") },
        enabled = !loading, modifier = Modifier.fillMaxWidth().testTag("auth-recovery"), textStyle = QuietGridText.date)
    OutlinedTextField(password, { password = it }, label = { Text(if (page == AuthPage.RECOVER) "新密码" else "密码") },
        enabled = !loading, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("auth-password"), textStyle = QuietGridText.input,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    TextButton(onClick = { visible = !visible }, enabled = !loading) { Text(if (visible) "隐藏密码" else "显示密码") }
    if (page != AuthPage.LOGIN) Text("密码需 12–128 个字符；允许空格和 Unicode，不能全为空白或使用常见弱密码。", style = MaterialTheme.typography.bodySmall, color = LocalQuietGridColors.current.secondary)
    if (error != null) PersistentError(error)
    Button(onClick = { onSubmit(username, password, recoveryCode) }, enabled = !loading && username.isNotBlank() && password.isNotEmpty() && (page != AuthPage.RECOVER || recoveryCode.isNotBlank()),
        colors = ButtonDefaults.buttonColors(containerColor = LocalQuietGridColors.current.cta), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("auth-submit")) {
        Text(if (loading) "正在处理…" else page.title)
    }
}
@Composable
private fun PersistentError(message: String) {
    Text(message, color = LocalQuietGridColors.current.danger, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
}
