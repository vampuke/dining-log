package top.alanvampuke.dininglog.feature.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.theme.*

val BackgroundArgb = SemanticsPropertyKey<Int>("QuietGridBackgroundArgb")
val ForegroundArgb = SemanticsPropertyKey<Int>("QuietGridForegroundArgb")
val ImeBottomPx = SemanticsPropertyKey<Int>("ImeBottomPx")
@Composable fun GridText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = LocalQuietGridColors.current.ink) {
    Text(text, modifier.semantics { this[ForegroundArgb] = color.toArgb() }, color = color, style = style)
}
@Composable fun ScreenFrame(tag: String, bottomBar: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    val c = LocalQuietGridColors.current
    val ime = WindowInsets.ime.getBottom(LocalDensity.current)
    Scaffold(Modifier.fillMaxSize().imePadding().testTag(tag).semantics { this[BackgroundArgb] = c.bg.toArgb(); this[ImeBottomPx] = ime },
        containerColor = c.bg, contentColor = c.ink,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal), bottomBar = bottomBar, content = content)
}
@Composable fun PageHeader(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text("‹ 返回") }
        GridText("食记 / QUIET GRID", style = QuietGridText.date, color = LocalQuietGridColors.current.secondary)
        GridText(title, Modifier.testTag("page-title"), style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) GridText(subtitle, color = LocalQuietGridColors.current.secondary)
    }
}
@Composable fun ErrorMessage(message: String, tag: String = "error") {
    GridText(message, Modifier.fillMaxWidth().testTag(tag).semantics { liveRegion = LiveRegionMode.Polite }, color = LocalQuietGridColors.current.danger)
}
@Composable fun CacheStatus(offline: Boolean, fetchedAt: Long?, stale: Boolean = false, localSearch: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (offline) GridText("离线缓存 · 联网后可手动刷新", Modifier.testTag("offline"), color = LocalQuietGridColors.current.accentInk)
        if (localSearch) GridText("只搜索已缓存内容；未缓存的内容未参与搜索", Modifier.testTag("cache-scope"), color = LocalQuietGridColors.current.secondary)
        if (fetchedAt != null && (offline || stale)) GridText("上次更新 ${java.time.Instant.ofEpochMilli(fetchedAt)}", style = QuietGridText.date, color = LocalQuietGridColors.current.secondary)
        if (stale) GridText("此详情已有较新摘要，正在等待刷新", Modifier.testTag("stale-detail"), color = LocalQuietGridColors.current.secondary)
    }
}
@Composable fun Score(value: String?, prominent: Boolean = false, meter: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GridText(displayValue("rating_tenths", value), style = if (prominent) QuietGridText.detailRating else QuietGridText.listRating,
            color = LocalQuietGridColors.current.accentInk)
        if (meter && value != null) {
            val filled = (value.toInt() / 5.0).roundToInt()
            Row(Modifier.width(120.dp).semantics { contentDescription = "评分 ${Rating.fromTenths(value.toInt()).display}，满分 5.0" }, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(10) { index -> Box(Modifier.weight(1f).height(3.dp).background(if (index < filled) LocalQuietGridColors.current.accent else LocalQuietGridColors.current.borderStrong)) }
            }
        }
    }
}
@Composable fun RatingEditor(value: String?, onChange: (String?) -> Unit, enabled: Boolean, tag: String = "rating") {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GridText("评分", style = MaterialTheme.typography.titleMedium)
        Score(value, meter = true)
        Slider(value = (value?.toIntOrNull() ?: 40) / 10f, onValueChange = { onChange((it * 10).roundToInt().coerceIn(10, 50).toString()) },
            enabled = enabled, valueRange = 1f..5f, steps = 39, modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = "评分，0.1 步进" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onChange(((value?.toInt() ?: 40) - 1).coerceAtLeast(10).toString()) }, enabled = enabled, modifier = Modifier.testTag("$tag:minus")) { Text("− 0.1") }
            TextButton(onClick = { onChange(((value?.toInt() ?: 40) + 1).coerceAtMost(50).toString()) }, enabled = enabled, modifier = Modifier.testTag("$tag:plus")) { Text("+ 0.1") }
        }
        TextButton(onClick = { onChange(if (value == null) "40" else null) }, enabled = enabled) { Text(if (value == null) "设置评分" else "清除评分") }
    }
}
@Composable fun FormField(field: String, value: String?, onChange: (String) -> Unit, enabled: Boolean = true,
    error: String? = null, label: String = fieldLabel(field), minLines: Int = 1, supporting: String? = null) {
    OutlinedTextField(value ?: "", onChange, modifier = Modifier.fillMaxWidth().testTag("field:$field"), enabled = enabled,
        label = { GridText(label) }, textStyle = QuietGridText.input, minLines = minLines, isError = error != null,
        supportingText = { if (error != null) GridText(error, color = LocalQuietGridColors.current.danger) else if (supporting != null) GridText(supporting, style = MaterialTheme.typography.bodySmall, color = LocalQuietGridColors.current.secondary) })
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun ChoiceChips(options: List<Pair<String?, String>>, selected: String?, onSelect: (String?) -> Unit, enabled: Boolean = true) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (code, label) -> FilterChip(selected = selected == code, onClick = { onSelect(code) }, enabled = enabled, label = { Text(label) }) }
    }
}
@Composable fun SaveBar(enabled: Boolean, saving: Boolean, unknown: Boolean, onSave: () -> Unit, message: String? = null) {
    Surface(color = LocalQuietGridColors.current.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(QuietGridDimensions.edge), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (message != null) ErrorMessage(message, "save-message")
            Button(onClick = onSave, enabled = enabled && !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save"),
                colors = ButtonDefaults.buttonColors(containerColor = LocalQuietGridColors.current.cta, contentColor = LocalQuietGridColors.current.onAccent)) {
                Text(if (saving) "正在保存…" else if (unknown) "重试原请求，确认结果" else "保存")
            }
        }
    }
}
@Composable fun ExpandableValue(field: String, value: String?, tag: String) {
    var expanded by remember(value) { mutableStateOf(false) }
    val text = displayValue(field, value)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GridText(if (!expanded && text.length > 240) text.take(240) + "…" else text, Modifier.testTag(tag), style = QuietGridText.paragraph)
        if (text.length > 240) TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("expand:$tag")) { Text(if (expanded) "收起全文" else "展开全文") }
    }
}
@Composable fun ConfirmAction(title: String, message: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { GridText(title, style = MaterialTheme.typography.headlineSmall) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { GridText(message) } }, containerColor = LocalQuietGridColors.current.surface,
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm")) { Text(confirmText, color = LocalQuietGridColors.current.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
