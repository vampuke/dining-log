package top.alanvampuke.dininglog.feature.revisions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.*
import top.alanvampuke.dininglog.feature.components.*
import top.alanvampuke.dininglog.feature.theme.*

@Composable fun RevisionDetailScreen(state: DetailUiState, onBack: () -> Unit, onRefresh: () -> Unit,
    onNote: (RecordSnapshot) -> Unit, onHide: (RecordSnapshot) -> Unit, onRestore: (RecordSnapshot) -> Unit,
    onApply: (RecordSnapshot) -> Unit, bottomBar: @Composable () -> Unit = {}) {
    val record = state.cached.snapshot
    var absolute by remember { mutableStateOf(false) }
    var hide by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf(false) }
    ScreenFrame("screen:history", bottomBar) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("history-scroll"), contentPadding = PaddingValues(QuietGridDimensions.edge), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { PageHeader("修改详情", onBack, record?.fields?.get("entity_label")) }
            item {
                CacheStatus(state.offline, state.cached.fetchedAt, state.cached.stale)
                if (state.error != null) ErrorMessage(state.error)
                TextButton(onClick = onRefresh, enabled = !state.refreshing) { Text(if (state.refreshing) "正在刷新…" else "刷新历史详情") }
            }
            if (record != null) {
                item {
                    GridText(relativeTime(record.fields["created_at"] ?: record.updatedAt), style = QuietGridText.date, color = LocalQuietGridColors.current.secondary)
                    TextButton(onClick = { absolute = !absolute }) { Text(if (absolute) "收起绝对时间" else "显示绝对时间") }
                    if (absolute) GridText(record.fields["created_at"] ?: record.updatedAt, Modifier.testTag("absolute-time"), style = QuietGridText.date)
                    GridText("版本 ${record.fields["entity_version_before"] ?: "0"} → ${record.fields["entity_version_after"] ?: "1"}", style = QuietGridText.date, color = LocalQuietGridColors.current.secondary)
                }
                record.diff.forEach { change -> item(key = change.field) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GridText(fieldLabel(change.field), style = MaterialTheme.typography.titleMedium)
                        GridText("原值", color = LocalQuietGridColors.current.secondary)
                        ExpandableValue(change.field, change.before, "before:${change.field}")
                        GridText("新值", color = LocalQuietGridColors.current.accentInk)
                        ExpandableValue(change.field, change.after, "after:${change.field}")
                        HorizontalDivider(color = LocalQuietGridColors.current.border)
                    }
                } }
                item {
                    GridText("历史说明", style = MaterialTheme.typography.titleMedium)
                    ExpandableValue("note", record.fields["note"], "history:note")
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (record.deletedAt == null) {
                            TextButton(onClick = { onNote(record) }, modifier = Modifier.testTag("history-note")) { Text("加说明 / 修改说明") }
                            TextButton(onClick = { hide = true }, modifier = Modifier.testTag("hide-history")) { Text("隐藏此条历史", color = LocalQuietGridColors.current.danger) }
                        } else {
                            GridText("此条历史已隐藏，普通列表不显示", color = LocalQuietGridColors.current.secondary)
                            Button(onClick = { restore = true }, modifier = Modifier.fillMaxWidth().testTag("restore-history")) { Text("取消隐藏") }
                        }
                        Button(onClick = { onApply(record) }, modifier = Modifier.fillMaxWidth().testTag("reapply")) { Text("重新应用字段") }
                    }
                }
            } else item { GridText("尚无完整 diff 缓存，请联网后重试") }
        }
    }
    if (record != null && hide) ConfirmAction("隐藏这条历史？", "隐藏后普通列表不再显示，管理页仍可查看并取消隐藏。历史的旧值和新值不会被修改。", "确认隐藏", { hide = false; onHide(record) }, { hide = false })
    if (record != null && restore) ConfirmAction("恢复这条历史？", "恢复后重新显示在普通修改历史列表中。", "确认恢复", { restore = false; onRestore(record) }, { restore = false })
}

@Composable private fun ReviewDialog(title: String, tag: String, onDismiss: () -> Unit,
    footer: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(color = LocalQuietGridColors.current.surface, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.94f).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp).testTag(tag)) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GridText(title, Modifier.testTag("dialog-title"), style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
                footer()
                TextButton(onClick = onDismiss) { Text("保留草稿，返回") }
            }
        }
    }
}
@Composable fun ComparisonColumns(fields: List<ConflictField>, baseVersion: Int?, serverVersion: Int?) {
    val width = if (LocalDensity.current.fontScale > 1.3f) 220.dp else 170.dp
    Column(Modifier.horizontalScroll(rememberScrollState()).width(width * 3), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row {
            listOf("开始编辑时 · v${baseVersion ?: "新建"}" to "base_snapshot", "服务器当前 · v${serverVersion ?: "未找到"}" to "server_current", "本地草稿" to "local_draft").forEach { (label, tag) ->
                Column(Modifier.width(width).padding(8.dp).testTag(tag)) { GridText(label, style = MaterialTheme.typography.titleMedium) }
            }
        }
        fields.forEach { field -> Row(Modifier.height(IntrinsicSize.Min)) {
            listOf(field.base, field.server, field.local).forEachIndexed { index, value ->
                Column(Modifier.width(width).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GridText(fieldLabel(field.name), style = MaterialTheme.typography.titleSmall, color = LocalQuietGridColors.current.secondary)
                    ExpandableValue(field.name, value, "compare:${field.name}:$index")
                }
            }
        } }
    }
}
@Composable fun ConflictDialog(comparison: ConflictComparison, busy: Boolean, onConfirm: (Map<String, ConflictChoice>) -> Unit, onDismiss: () -> Unit) {
    var choices by remember(comparison) { mutableStateOf(emptyMap<String, ConflictChoice>()) }
    ReviewDialog("版本冲突", "screen:conflict", onDismiss, footer = {
        Button(onClick = { onConfirm(choices) }, enabled = !busy && comparison.mergeAllowed && choices.keys.containsAll(comparison.needsChoice),
            modifier = Modifier.fillMaxWidth().testTag("confirm-merge")) { Text(if (busy) "正在保存…" else "确认选择并保存") }
    }) {
        GridText(if (comparison.mergeAllowed) "横向滑动比较三栏。不同字段已提供合并建议，同一字段必须选择。确认后使用服务器当前版本保存。" else "删除冲突或记录不可用，不能强制合并保存；请先恢复或刷新，草稿保留。")
        ComparisonColumns(comparison.fields, comparison.baseSnapshot?.version, comparison.serverCurrent.version)
        comparison.fields.filter { it.requiresChoice }.forEach { field ->
            GridText("${fieldLabel(field.name)}发生冲突，请选择", style = MaterialTheme.typography.titleMedium)
            ChoiceChips(listOf("server" to "使用服务器", "local" to "使用草稿"), choices[field.name]?.name?.lowercase(), { choice ->
                if (choice != null) choices = choices + (field.name to if (choice == "server") ConflictChoice.SERVER else ConflictChoice.LOCAL)
            })
        }
    }
}
@Composable fun ExpiredRequestDialog(review: SaveOutcome.Expired, busy: Boolean, onConfirm: () -> Unit, onAcceptServer: () -> Unit, onDismiss: () -> Unit) {
    val draft = review.draft
    val server = review.serverCurrent
    val fields = editableFields(draft.ref.kind).map { ConflictField(it, draft.baseSnapshot?.fields?.get(it), server?.fields?.get(it), draft.localFields[it], false) }
    val allow = server?.deletedAt == null && draft.action !in setOf(WriteAction.DELETE, WriteAction.RESTORE) && (server != null || draft.action == WriteAction.CREATE)
    ReviewDialog("原请求已超过 7 天", "screen:expired", onDismiss, footer = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (server != null) TextButton(onClick = onAcceptServer, enabled = !busy) { Text("保留服务器值，清除本机草稿") }
            Button(onClick = onConfirm, enabled = !busy && allow, modifier = Modifier.fillMaxWidth().testTag("confirm-new-save")) { Text("确认新保存") }
        }
    }) {
        GridText("已读取服务器当前值。原请求没有重放，请核对后确认新的保存。")
        if (server == null) GridText("服务器未找到此记录；现有记录的编辑草稿需先恢复或刷新。")
        if (draft.action == WriteAction.CREATE && server != null && draft.firstVisit != null) GridText("请核对餐厅的首访记录；本次新保存只修改餐厅字段，首访可在到店页单独管理。")
        if (!allow) ErrorMessage("记录不可用，请先恢复或刷新；草稿保留")
        ComparisonColumns(fields, draft.baseVersion, server?.version)
    }
}
@Composable fun ApplyFieldsDialog(state: ApplyUiState, onSide: (String) -> Unit, onField: (String, Boolean) -> Unit,
    onSave: () -> Unit, onDismiss: () -> Unit) {
    ReviewDialog("重新应用历史字段", "screen:apply", onDismiss, footer = {
        Button(onClick = onSave, enabled = state.selected.isNotEmpty() && state.current.deletedAt == null, modifier = Modifier.fillMaxWidth().testTag("apply-save")) { Text("确认选中字段并保存") }
    }) {
        GridText("当前版本 ${state.current.version}。只修改选中的字段，保存时会重新检查版本。")
        ChoiceChips(listOf("before" to "应用旧值", "after" to "应用新值"), state.side, { if (it != null) onSide(it) })
        if (state.current.deletedAt != null) ErrorMessage("此记录已删除，请先恢复；不能重新应用字段")
        state.eligible.forEach { change ->
            val proposed = state.proposed(change)
            val allowed = proposed != null || change.field !in setOf("name", "category", "address", "dining_date")
            Row(Modifier.fillMaxWidth()) {
                Checkbox(change.field in state.selected, { onField(change.field, it) }, enabled = allowed && state.current.deletedAt == null, modifier = Modifier.testTag("apply-field:${change.field}"))
                GridText(fieldLabel(change.field), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
            }
            GridText("当前值", color = LocalQuietGridColors.current.secondary)
            ExpandableValue(change.field, state.current.fields[change.field], "apply-current:${change.field}")
            GridText("拟修改值", color = LocalQuietGridColors.current.accentInk)
            ExpandableValue(change.field, proposed, "apply-proposed:${change.field}")
            if (!allowed) GridText("必填字段不能恢复为空", color = LocalQuietGridColors.current.secondary)
            HorizontalDivider(color = LocalQuietGridColors.current.border)
        }
    }
}

@Composable fun OperationDialog(state: EditorUiState, onRetry: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!state.saving) onDismiss() },
        modifier = Modifier.testTag("screen:operation"), containerColor = LocalQuietGridColors.current.surface,
        title = { GridText(if (state.saving) "正在保存" else "操作未完成", style = MaterialTheme.typography.headlineSmall) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GridText(state.message ?: "正在确认服务器结果…")
            if (state.draft != null) GridText("请求与草稿已保留在本机，也可在「我的」中查看。", color = LocalQuietGridColors.current.secondary)
        } },
        confirmButton = { if (state.draft != null) TextButton(onClick = onRetry, enabled = !state.saving,
            modifier = Modifier.testTag("retry-operation")) { Text(if (state.draft.status == DraftStatus.RESULT_UNKNOWN) "重试原请求确认结果" else "重试保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("关闭") } })
}
