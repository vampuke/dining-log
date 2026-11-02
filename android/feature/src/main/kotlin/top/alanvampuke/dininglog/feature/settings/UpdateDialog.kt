package top.alanvampuke.dininglog.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.components.*
import top.alanvampuke.dininglog.feature.theme.*
import java.util.Locale

@Composable fun UpdateDialog(state: UpdateInstallState, onDownload: () -> Unit, onCancel: () -> Unit,
    onInstall: () -> Unit, onDownloadPage: () -> Unit, onDismiss: () -> Unit) {
    val release = state.release ?: return
    val c = LocalQuietGridColors.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = c.surface, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).testTag("screen:update")) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GridText("发现新版本 ${release.versionName}", style = MaterialTheme.typography.headlineSmall)
                GridText(release.notes.ifBlank { "更新食记，继续记录每一口。" }, color = c.secondary)
                if (state.busy) {
                    GridText(if (state.stage == UpdateStage.VERIFYING) "正在校验安装包…" else "正在下载 · ${state.percent?.let { "$it%" } ?: "百分比待确认"}", Modifier.testTag("update-progress"))
                    GridText("${sizeText(state.downloaded)} / ${state.total?.let(::sizeText) ?: "总大小未知"}", color = c.secondary)
                    if (state.percent != null) LinearProgressIndicator(progress = { (state.percent ?: 0) / 100f },
                        color = c.ink, trackColor = c.paper, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(color = c.ink, trackColor = c.paper, modifier = Modifier.fillMaxWidth())
                    BiteTextButton(onClick = onCancel, modifier = Modifier.testTag("update-cancel")) { Text("取消下载") }
                }
                if (state.message != null) ErrorMessage(state.message, "update-message")
                when (state.stage) {
                    UpdateStage.IDLE -> BiteButton(onClick = onDownload, modifier = Modifier.fillMaxWidth().testTag("update-now")) { Text("立即更新") }
                    UpdateStage.FAILED, UpdateStage.CANCELED -> BiteButton(onClick = onDownload, modifier = Modifier.fillMaxWidth().testTag("update-retry")) { Text("重新下载") }
                    UpdateStage.READY, UpdateStage.NEED_PERMISSION, UpdateStage.INSTALLING -> BiteButton(onClick = onInstall,
                        modifier = Modifier.fillMaxWidth().testTag("update-install")) { Text("继续安装") }
                    else -> Unit
                }
                BiteTextButton(onClick = onDownloadPage, modifier = Modifier.fillMaxWidth().testTag("update-download-page")) { Text("打开下载页") }
                BiteTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("update-close")) { Text(if (state.busy) "后台继续下载" else "稍后") }
            }
        }
    }
}
private fun sizeText(bytes: Long) = String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0))
