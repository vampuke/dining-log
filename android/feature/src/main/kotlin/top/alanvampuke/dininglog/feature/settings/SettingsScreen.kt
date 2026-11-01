package top.alanvampuke.dininglog.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.*
import top.alanvampuke.dininglog.feature.components.*
import top.alanvampuke.dininglog.feature.theme.*

@Composable fun SettingsScreen(state: SettingsUiState, username: String, drafts: List<SavedDraft>, theme: ThemeChoice,
    version: String, onRefresh: () -> Unit, onRevoke: (String) -> Unit, onPassword: (String, String) -> Unit,
    onLogout: () -> Unit, onDraft: (SavedDraft) -> Unit, onTheme: (ThemeChoice) -> Unit, bottomBar: @Composable () -> Unit,
    onPasswordInput: (String, String) -> Unit = { _, _ -> }, listState: LazyListState = rememberLazyListState(),
    updateState: AppUpdateState = AppUpdateState(), onUpdateToggle: (Boolean) -> Unit = {}, onOpenUpdate: (AppRelease) -> Unit = {}) {
    var revoke by remember { mutableStateOf<DeviceSession?>(null) }
    var password by remember { mutableStateOf(false) }
    var logout by remember { mutableStateOf(false) }
    LaunchedEffect(state.passwordRevision) { if (state.passwordRevision > 0) password = false }
    ScreenFrame("screen:settings", bottomBar) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("settings-scroll"), state = listState, contentPadding = PaddingValues(QuietGridDimensions.edge), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                PageHeader("我的", subtitle = username)
                GridText("账号 ${state.profile?.displayName ?: username}", style = MaterialTheme.typography.titleMedium)
                state.profile?.let { GridText("注册于 ${it.createdAt}", style = QuietGridText.date, color = LocalQuietGridColors.current.secondary) }
                TextButton(onClick = onRefresh, enabled = !state.loading) { Text(if (state.loading) "正在刷新账号…" else "刷新账号与会话") }
                if (state.message != null) ErrorMessage(state.message, "settings-message")
            }
            val release = updateState.release
            if (updateState.enabled && release != null) item {
                TextButton(onClick = { onOpenUpdate(release) }, modifier = Modifier.fillMaxWidth().testTag("app-update")) {
                    Text("有新版本 ${release.versionName}")
                }
            }
            item { GridText("登录会话", style = MaterialTheme.typography.headlineSmall) }
            items(state.sessions, key = { it.id }) { session ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GridText("${session.deviceName}${if (session.current) " · 当前设备" else ""}", style = MaterialTheme.typography.titleMedium)
                    GridText("建立 ${session.createdAt}\n最近刷新 ${session.lastSeenAt}\n到期 ${session.expiresAt}", style = QuietGridText.date, color = LocalQuietGridColors.current.secondary)
                    TextButton(onClick = { revoke = session }, enabled = !state.busy, modifier = Modifier.testTag("revoke:${session.id}")) { Text("撤销会话", color = LocalQuietGridColors.current.danger) }
                    HorizontalDivider(color = LocalQuietGridColors.current.border)
                }
            }
            if (state.sessions.isEmpty() && !state.loading) item { GridText("尚未取得会话列表，可联网后刷新", color = LocalQuietGridColors.current.secondary) }
            item {
                Button(onClick = { password = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("change-password")) { Text("修改密码") }
            }
            item {
                GridText("未提交草稿 · ${drafts.size}", style = MaterialTheme.typography.headlineSmall)
                GridText("仅保存在本机。结果未知的请求请先重试确认，联网后不会自动上传。", color = LocalQuietGridColors.current.secondary)
            }
            items(drafts, key = { it.id }) { draft ->
                TextButton(onClick = { onDraft(draft) }, modifier = Modifier.fillMaxWidth().testTag("draft:${draft.id}")) {
                    Text("${draft.localFields["name"] ?: draft.localFields["dining_date"] ?: "草稿"} · ${when (draft.status) { DraftStatus.RESULT_UNKNOWN, DraftStatus.SAVING -> "结果未知"; DraftStatus.EXPIRED_REVIEW -> "等待比较"; else -> "未提交" }}")
                }
            }
            item {
                GridText("显示主题", style = MaterialTheme.typography.titleMedium)
                ChoiceChips(listOf("SYSTEM" to "跟随系统", "LIGHT" to "浅色", "DARK" to "深色"), theme.name, { if (it != null) onTheme(ThemeChoice.valueOf(it)) })
            }
            item {
                GridText("关于食记", style = MaterialTheme.typography.titleMedium)
                GridText("版本 $version\n以文字记下餐厅、菜品与每一次到店。", color = LocalQuietGridColors.current.secondary)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    GridText("启动时检查更新")
                    Switch(checked = updateState.enabled, onCheckedChange = onUpdateToggle,
                        modifier = Modifier.testTag("update-checks").semantics { contentDescription = "启动时检查更新" })
                }
                TextButton(onClick = { logout = true }, modifier = Modifier.testTag("logout")) { Text("退出登录", color = LocalQuietGridColors.current.danger) }
            }
        }
    }
    val revoking = revoke
    if (revoking != null) ConfirmAction("撤销此会话？", if (revoking.current) "这是当前设备。撤销后会退出，并清理本机缓存与未提交草稿。" else "该设备的旧 access 与 refresh 将不可用。", "确认撤销", { revoke = null; onRevoke(revoking.id) }, { revoke = null })
    if (logout) ConfirmAction("退出登录？", "将清理此账号的本机缓存和 ${drafts.size} 个未提交草稿；结果未知的请求可能已保存，建议先确认。服务器已保存记录仍属于账号。", "确认退出", { logout = false; onLogout() }, { logout = false })
    if (password) PasswordDialog(state, onPassword, onPasswordInput) { if (!state.busy) password = false }
}
@Composable private fun PasswordDialog(state: SettingsUiState, onPassword: (String, String) -> Unit,
    onInput: (String, String) -> Unit, onDismiss: () -> Unit) {
    // Kept only in the account-owned ViewModel's memory so an unknown request
    // can be confirmed after closing/reopening. Never Room or SavedStateHandle.
    val (current, replacement, repeated) = state.passwordInputs
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        ScreenFrame("screen:password", bottomBar = {
            Surface(color = LocalQuietGridColors.current.surface) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    Button(onClick = { onPassword(current, replacement) }, enabled = !state.busy && current.isNotEmpty() && replacement.codePointCount(0, replacement.length) in 12..128 && replacement == repeated,
                        modifier = Modifier.fillMaxWidth().testTag("password-save")) { Text(if (state.busy) "正在修改…" else if (state.passwordUnknown) "重试原输入确认结果" else "确认修改密码") }
                    TextButton(onClick = onDismiss, enabled = !state.busy) { Text("关闭") }
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PageHeader("修改密码")
                GridText("修改成功后其他会话退出。新密码需 12–128 个字符，不能使用常见弱密码。")
                listOf(Triple("current-password", "当前密码", current), Triple("new-password", "新密码", replacement), Triple("repeat-password", "确认新密码", repeated)).forEach { (tag, label, value) ->
                    OutlinedTextField(value, { onInput(tag, it) },
                        modifier = Modifier.fillMaxWidth().testTag(tag), label = { GridText(label) }, enabled = !state.busy && !state.passwordUnknown,
                        textStyle = QuietGridText.input, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
                if (state.message != null) ErrorMessage(state.message)
            }
        }
    }
}
