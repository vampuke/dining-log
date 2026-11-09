package top.alanvampuke.dininglog.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import java.time.Instant
import java.time.ZoneId
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.*
import top.alanvampuke.dininglog.feature.components.*
import top.alanvampuke.dininglog.feature.theme.*

@Composable fun SettingsScreen(state: SettingsUiState, username: String, theme: ThemeChoice,
    version: String, onPassword: (String, String) -> Unit,
    onLogout: () -> Unit, onTheme: (ThemeChoice) -> Unit, bottomBar: @Composable () -> Unit,
    onPasswordInput: (String, String) -> Unit = { _, _ -> }, listState: LazyListState = rememberLazyListState(),
    updateState: AppUpdateState = AppUpdateState(), onUpdateToggle: (Boolean) -> Unit = {}, onOpenUpdate: (AppRelease) -> Unit = {},
    onCheckUpdate: () -> Unit = {}, style: ThemeStyle = ThemeStyle.QUIET_GRID, onStyle: (ThemeStyle) -> Unit = {}) {
    var password by remember { mutableStateOf(false) }
    var logout by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    val c = LocalQuietGridColors.current
    val inkPaper = LocalThemeStyle.current.isInkPaper
    // Ink & Paper settings header: avatar + name + "since" line, per the
    // design preview's me page. The year comes from profile.createdAt and
    // the line is omitted when there is no profile to read it from.
    val who = state.profile?.displayName ?: username
    val registrationYear = state.profile?.createdAt?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).year }.getOrNull() }
    // The design shows the current appearance as one row; tapping it cycles
    // 浅色 → 深色 → 跟随系统 (ThemeChoice order is SYSTEM, LIGHT, DARK).
    val themeName = when (theme) { ThemeChoice.SYSTEM -> "跟随系统"; ThemeChoice.LIGHT -> "浅色"; ThemeChoice.DARK -> "深色" }
    val nextTheme = when (theme) { ThemeChoice.SYSTEM -> ThemeChoice.LIGHT; ThemeChoice.LIGHT -> ThemeChoice.DARK; ThemeChoice.DARK -> ThemeChoice.SYSTEM }
    // Ink & Paper groups with quiet 11sp grey headers instead of bold titles.
    val sectionStyle = if (inkPaper) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleMedium
    val sectionColor = if (inkPaper) LocalQuietGridColors.current.secondary else LocalQuietGridColors.current.ink
    LaunchedEffect(state.passwordRevision) { if (state.passwordRevision > 0) password = false }
    ScreenFrame("screen:settings", bottomBar, floatingBottomBar = true) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(listViewportPadding(padding)).testTag("settings-scroll"), state = listState, contentPadding = listContentPadding(padding), verticalArrangement = if (inkPaper) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy(16.dp)) {
            if (inkPaper) {
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(44.dp).background(c.paper, CircleShape), contentAlignment = Alignment.Center) {
                            GridText(who.take(1).ifEmpty { "食" }, style = MaterialTheme.typography.titleMedium, color = c.ink)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            GridText(who, Modifier.testTag("page-title"), style = MaterialTheme.typography.headlineSmall)
                            if (registrationYear != null) GridText("记录从 $registrationYear 年开始 · 本机已同步",
                                style = QuietGridText.caption, color = c.secondary)
                        }
                    }
                    if (state.message != null) ErrorMessage(state.message, "settings-message")
                }
                item {
                    Column(Modifier.fillMaxWidth()) {
                        SettingsGroupTitle("账号", first = true)
                        SettingsRow("账号", value = username)
                        SettingsRow("修改密码", chevron = true, tag = "change-password", onClick = { password = true })
                        SettingsRow("当前设备", value = "本机", last = true)
                    }
                }
                item {
                    val release = updateState.release
                    val updateStatus = when {
                        updateState.checking -> "正在检查…"
                        release != null -> "有新版本 ${release.versionName}"
                        else -> null
                    }
                    val showUpdateFeedback = updateState.failed || (!updateState.checking && updateState.upToDate && updateState.release == null)
                    Column(Modifier.fillMaxWidth()) {
                        SettingsGroupTitle("应用")
                        if ((updateState.enabled || updateState.manual) && release != null)
                            SettingsRow("有新版本", value = release.versionName, tag = "app-update", onClick = { onOpenUpdate(release) })
                        SettingsRow("检查更新", value = updateStatus, tag = "check-update", last = showUpdateFeedback,
                            onClick = { if (!updateState.checking) onCheckUpdate() })
                        if (updateState.failed) Column(Modifier.fillMaxWidth()) {
                            ErrorMessage("检查失败，请重试", "update-check-error")
                            HorizontalDivider(color = c.border, thickness = 1.dp, modifier = Modifier.padding(top = 12.dp))
                        } else if (!updateState.checking && updateState.upToDate && updateState.release == null) Column(Modifier.fillMaxWidth()) {
                            GridText("已是最新版本", Modifier.testTag("update-up-to-date").padding(vertical = 12.dp),
                                style = QuietGridText.caption, color = c.secondary)
                            HorizontalDivider(color = c.border, thickness = 1.dp)
                        }
                        SettingsRow("外观", value = themeName, tag = "appearance", onClick = { onTheme(nextTheme) })
                        Column(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GridText("启动时检查更新", Modifier.weight(1f))
                                // Functional thumb/border colors are separate from decorative paper rules.
                                Switch(checked = updateState.enabled, onCheckedChange = onUpdateToggle,
                                    colors = if (inkPaper) SwitchDefaults.colors(
                                        uncheckedThumbColor = LocalQuietGridColors.current.secondary,
                                        uncheckedBorderColor = LocalQuietGridColors.current.secondary,
                                        uncheckedTrackColor = LocalQuietGridColors.current.surface,
                                    ) else SwitchDefaults.colors(),
                                    modifier = Modifier.testTag("update-checks").semantics { contentDescription = "启动时检查更新" })
                            }
                            HorizontalDivider(color = c.border, thickness = 1.dp)
                        }
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GridText("主题风格", style = QuietGridText.caption, color = c.secondary)
                            ChoiceChips(listOf(ThemeStyle.INK_PAPER.name to "纸墨", ThemeStyle.QUIET_GRID.name to "经典"), style.name,
                                { if (it != null) onStyle(ThemeStyle.valueOf(it)) })
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth()) {
                        SettingsGroupTitle("关于")
                        SettingsRow("版本", value = "v$version")
                        SettingsRow("关于食记", chevron = true, last = true, onClick = { about = true })
                    }
                }
                item {
                    SettingsRow("退出登录", danger = true, tag = "logout", last = true, onClick = { logout = true })
                }
            } else {
            item {
                PageHeader("我的", subtitle = username)
                // Was "账号 <displayName>" right under the header subtitle,
                // duplicating it whenever displayName == username. Show the
                // account line only when it actually adds a different name.
                val displayName = state.profile?.displayName
                if (displayName != null && displayName != username) GridText("账号 $displayName", style = MaterialTheme.typography.titleMedium)
                state.profile?.let { GridText("注册于 ${formatAbsoluteTime(it.createdAt)}", style = QuietGridText.date, color = LocalQuietGridColors.current.secondary) }
                if (state.message != null) ErrorMessage(state.message, "settings-message")
            }
            val release = updateState.release
            if ((updateState.enabled || updateState.manual) && release != null) item {
                BiteTextButton(onClick = { onOpenUpdate(release) }, modifier = Modifier.fillMaxWidth().testTag("app-update")) {
                    Text("有新版本 ${release.versionName}")
                }
            }
            item {
                BiteButton(onClick = { password = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("change-password")) { Text("修改密码") }
            }
            item {
                GridText("显示主题", style = sectionStyle, color = sectionColor)
                ChoiceChips(listOf("SYSTEM" to "跟随系统", "LIGHT" to "浅色", "DARK" to "深色"), theme.name, { if (it != null) onTheme(ThemeChoice.valueOf(it)) })
            }
            item {
                GridText("主题风格", style = sectionStyle, color = sectionColor)
                ChoiceChips(listOf(ThemeStyle.INK_PAPER.name to "纸墨", ThemeStyle.QUIET_GRID.name to "经典"), style.name,
                    { if (it != null) onStyle(ThemeStyle.valueOf(it)) })
            }
            item {
                GridText("关于食记", style = sectionStyle, color = sectionColor)
                GridText("版本 $version\n以文字记下餐厅、菜品与每一次到店。", color = LocalQuietGridColors.current.secondary)
                ResponsiveHeading(title = { modifier ->
                    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GridText("启动时检查更新", Modifier.weight(1f))
                        // Functional thumb/border colors are separate from decorative paper rules.
                        Switch(checked = updateState.enabled, onCheckedChange = onUpdateToggle,
                            colors = if (inkPaper) SwitchDefaults.colors(
                                uncheckedThumbColor = LocalQuietGridColors.current.secondary,
                                uncheckedBorderColor = LocalQuietGridColors.current.secondary,
                                uncheckedTrackColor = LocalQuietGridColors.current.surface,
                            ) else SwitchDefaults.colors(),
                            modifier = Modifier.testTag("update-checks").semantics { contentDescription = "启动时检查更新" })
                    }
                }, trailing = { modifier ->
                    BiteTextButton(onClick = onCheckUpdate, enabled = !updateState.checking, modifier = modifier.testTag("check-update")) {
                        Text(if (updateState.checking) "正在检查…" else "检查更新")
                    }
                })
                if (updateState.failed) ErrorMessage("检查失败，请重试", "update-check-error")
                else if (!updateState.checking && updateState.upToDate && updateState.release == null)
                    GridText("已是最新版本", Modifier.testTag("update-up-to-date"), style = QuietGridText.caption, color = LocalQuietGridColors.current.secondary)
            }
            item {
                BiteTextButton(onClick = { logout = true }, modifier = Modifier.fillMaxWidth().testTag("logout")) { Text("退出登录", color = LocalQuietGridColors.current.danger) }
            }
            }
        }
    }
    if (logout) ConfirmAction("退出登录？", "将清理此账号的本机缓存。服务器已保存记录仍属于账号。", "确认退出", { logout = false; onLogout() }, { logout = false })
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("关于食记") },
        text = { Text("以文字记下餐厅、菜品与每一次到店。") },
        confirmButton = { BiteTextButton(onClick = { about = false }) { Text("关闭") } })
    if (password) PasswordDialog(state, onPassword, onPasswordInput) { if (!state.busy) password = false }
}
@Composable private fun PasswordDialog(state: SettingsUiState, onPassword: (String, String) -> Unit,
    onInput: (String, String) -> Unit, onDismiss: () -> Unit) {
    SecureScreen(enabled = true)
    // Kept only in the account-owned ViewModel's memory so an unknown request
    // can be confirmed after closing/reopening. Never Room or SavedStateHandle.
    val (current, replacement, repeated) = state.passwordInputs
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        ScreenFrame("screen:password", bottomBar = {
            Surface(color = LocalQuietGridColors.current.surface) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    BiteButton(onClick = { onPassword(current, replacement) }, enabled = !state.busy && current.isNotEmpty() && replacement.codePointCount(0, replacement.length) in 12..128 && replacement == repeated,
                        modifier = Modifier.fillMaxWidth().testTag("password-save")) { Text(if (state.busy) "正在修改…" else if (state.passwordUnknown) "重试原输入确认结果" else "确认修改密码") }
                    BiteTextButton(onClick = onDismiss, enabled = !state.busy) { Text("关闭") }
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PageHeader("修改密码")
                GridText("修改成功后其他会话退出。新密码需 12–128 个字符，不能使用常见弱密码。")
                listOf(Triple("current-password", "当前密码", current), Triple("new-password", "新密码", replacement), Triple("repeat-password", "确认新密码", repeated)).forEach { (tag, label, value) ->
                    BiteTextField(value, { onInput(tag, it) },
                        modifier = Modifier.fillMaxWidth().testTag(tag), label = { GridText(label) }, accessibleLabel = label, placeholder = { GridText(if (tag == "new-password") "12–128 个字符" else "必填") }, enabled = !state.busy && !state.passwordUnknown,
                        textStyle = QuietGridText.input, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
                if (state.message != null) ErrorMessage(state.message)
            }
        }
    }
}

// Ink & Paper settings group title: 11sp uppercase warm grey, per the design.
@Composable private fun SettingsGroupTitle(text: String, first: Boolean = false) {
    GridText(text.uppercase(), Modifier.fillMaxWidth().padding(top = if (first) 10.dp else 24.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelSmall, color = LocalQuietGridColors.current.secondary)
}

// One settings row: label left, value/chevron right, 1px divider below
// unless it is the last row of its group. The whole row is the tap target
// when onClick is given.
@Composable private fun SettingsRow(label: String, value: String? = null, chevron: Boolean = false,
    danger: Boolean = false, tag: String? = null, last: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = LocalQuietGridColors.current
    val tagModifier = if (tag != null) Modifier.testTag(tag) else Modifier
    val clickModifier = if (onClick != null) Modifier.clickable(role = Role.Button) { onClick() } else Modifier
    Column(Modifier.fillMaxWidth().then(clickModifier).then(tagModifier)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            GridText(label, Modifier.weight(1f), color = if (danger) c.danger else c.ink)
            if (value != null) GridText(value, style = QuietGridText.caption, color = c.secondary)
            if (chevron) GridText("›", Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.titleMedium, color = c.secondary)
        }
        if (!last) HorizontalDivider(color = c.border, thickness = 1.dp)
    }
}
