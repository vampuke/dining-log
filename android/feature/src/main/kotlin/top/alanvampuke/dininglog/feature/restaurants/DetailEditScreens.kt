package top.alanvampuke.dininglog.feature.restaurants

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.util.UUID
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.*
import top.alanvampuke.dininglog.feature.components.*
import top.alanvampuke.dininglog.feature.theme.*

@Composable fun RecordDetailScreen(state: DetailUiState, onBack: () -> Unit, onRefresh: () -> Unit,
    onEdit: (RecordSnapshot) -> Unit, onDelete: (RecordSnapshot) -> Unit, onRestore: (RecordSnapshot) -> Unit,
    onHistory: (RecordSnapshot) -> Unit, dishes: CachedPage = CachedPage(), visits: CachedPage = CachedPage(),
    onChild: (RecordSummary) -> Unit = {}, onDishes: (() -> Unit)? = null, onVisits: (() -> Unit)? = null,
    onAddDish: (() -> Unit)? = null, onNewVisit: (() -> Unit)? = null, bottomBar: @Composable () -> Unit = {}) {
    val record = state.cached.snapshot
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    ScreenFrame("screen:detail", bottomBar) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("detail-scroll"), contentPadding = PaddingValues(QuietGridDimensions.edge), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { PageHeader(record?.title ?: "记录详情", onBack) }
            item {
                CacheStatus(state.offline, state.cached.fetchedAt, state.cached.stale)
                if (state.error != null) ErrorMessage(state.error)
                TextButton(onClick = onRefresh, enabled = !state.refreshing, modifier = Modifier.testTag("detail-refresh")) { Text(if (state.refreshing) "正在刷新…" else "刷新详情") }
                if (record == null) GridText(if (state.refreshing) "正在读取详情…" else "尚无完整详情缓存，请联网后重试", Modifier.testTag("detail-empty"))
            }
            if (record != null) {
                if (record.ref.kind in setOf(RecordKind.RESTAURANT, RecordKind.DISH)) item { Score(record.fields["rating_tenths"], prominent = true, meter = true) }
                item {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        editableFields(record.ref.kind).filter { it !in setOf("name", "rating_tenths", "review", "note") && !record.fields[it].isNullOrBlank() }.forEach { field ->
                            GridText(fieldLabel(field), style = MaterialTheme.typography.titleSmall, color = LocalQuietGridColors.current.secondary)
                            GridText(displayValue(field, record.fields[field]), Modifier.testTag("detail:$field"))
                        }
                        GridText("版本 ${record.version} · 更新于 ${record.updatedAt}", style = QuietGridText.date, color = LocalQuietGridColors.current.secondary)
                    }
                }
                if ("review" in editableFields(record.ref.kind) && !record.fields["review"].isNullOrBlank()) item {
                    GridText(if (record.ref.kind == RecordKind.DISH) "短评" else "我的评价", style = MaterialTheme.typography.titleMedium)
                    ExpandableValue("review", record.fields["review"], "detail:review")
                }
                if ("note" in editableFields(record.ref.kind) && !record.fields["note"].isNullOrBlank()) item {
                    GridText("备注", style = MaterialTheme.typography.titleMedium)
                    ExpandableValue("note", record.fields["note"], "detail:note")
                }
                item {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (record.deletedAt == null) {
                            Button(onClick = { onEdit(record) }, modifier = Modifier.fillMaxWidth().testTag("edit")) { Text("编辑") }
                            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("delete")) { Text("删除记录", color = LocalQuietGridColors.current.danger) }
                        } else {
                            GridText("此记录在回收站 · 删除于 ${record.deletedAt}", color = LocalQuietGridColors.current.danger)
                            Button(onClick = { confirmRestore = true }, modifier = Modifier.fillMaxWidth().testTag("restore")) { Text("恢复记录") }
                        }
                        TextButton(onClick = { onHistory(record) }, modifier = Modifier.testTag("history")) { Text("查看修改历史") }
                    }
                }
                if (record.ref.kind == RecordKind.RESTAURANT && record.deletedAt == null) {
                    item {
                        GridText("店内菜品 · ${record.fields["dish_count"] ?: "0"} 道", style = MaterialTheme.typography.headlineSmall)
                        if (onAddDish != null) Button(onClick = onAddDish, modifier = Modifier.fillMaxWidth().testTag("add-dish")) { Text("新增菜品") }
                        if (onDishes != null) TextButton(onClick = onDishes) { Text("管理店内菜品") }
                        if (dishes.items.isEmpty()) GridText("菜品在本餐厅内管理", color = LocalQuietGridColors.current.secondary)
                    }
                    items(dishes.items, key = { "dish:${it.ref.id}" }) { RecordRow(it, onChild) }
                    item {
                        GridText("到店记录 · ${record.fields["visit_count"] ?: "0"} 次", style = MaterialTheme.typography.headlineSmall)
                        GridText("最近到店 ${record.fields["last_visit_date"] ?: "还未到店"}", color = LocalQuietGridColors.current.secondary)
                        if (onNewVisit != null) Button(onClick = onNewVisit, modifier = Modifier.fillMaxWidth().testTag("add-visit")) { Text("再次到店") }
                        if (onVisits != null) TextButton(onClick = onVisits) { Text("查看全部到店记录") }
                    }
                    items(visits.items, key = { "visit:${it.ref.id}" }) { RecordRow(it, onChild) }
                }
            }
        }
    }
    if (record != null && confirmDelete) ConfirmAction("删除这条记录？", if (record.ref.kind == RecordKind.RESTAURANT) "餐厅删除后，菜品与到店将暂时不可用。可在回收站恢复餐厅。" else "记录将进入回收站，可在保留窗口内恢复。", "确认删除", { confirmDelete = false; onDelete(record) }, { confirmDelete = false })
    if (record != null && confirmRestore) ConfirmAction("恢复这条记录？", "将使用当前版本恢复；父餐厅需要处于可用状态。", "确认恢复", { confirmRestore = false; onRestore(record) }, { confirmRestore = false })
}

@Composable fun EditScreen(state: EditorUiState, onField: (String, String?) -> Unit, onFirstVisit: (FirstVisitInput?) -> Unit,
    onSave: () -> Unit, onBack: () -> Unit, onDiscard: () -> Unit, dishes: List<RecordSummary> = emptyList(),
    onDish: (RecordSummary) -> Unit = {}, onAddDish: (() -> Unit)? = null, onManageDishes: (() -> Unit)? = null) {
    val draft = state.draft
    val kind = draft?.ref?.kind ?: RecordKind.RESTAURANT
    val enabled = draft != null && !draft.locked && !state.saving && draft.action in setOf(WriteAction.CREATE, WriteAction.UPDATE, WriteAction.METADATA)
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = draft?.let { it.action in setOf(WriteAction.CREATE, WriteAction.DELETE, WriteAction.RESTORE, WriteAction.APPLY) || it.changedFields.isNotEmpty() } == true
    ScreenFrame("screen:edit", bottomBar = { SaveBar(draft != null && (dirty || draft.locked), state.saving,
        draft?.status == DraftStatus.RESULT_UNKNOWN, onSave, state.message) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("edit-scroll"), contentPadding = PaddingValues(QuietGridDimensions.edge), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { PageHeader(if (draft?.action == WriteAction.CREATE) when (kind) { RecordKind.RESTAURANT -> "新增餐厅"; RecordKind.DISH -> "新增菜品"; RecordKind.VISIT -> "新增到店"; else -> "新增" }
                else if (kind == RecordKind.REVISION) "修改历史说明" else "编辑${when (kind) { RecordKind.RESTAURANT -> "餐厅"; RecordKind.DISH -> "菜品"; RecordKind.VISIT -> "到店"; else -> "记录" }}", onBack) }
            item {
                GridText("输入会自动保留为本机草稿；点击保存后才会提交", style = MaterialTheme.typography.bodySmall, color = LocalQuietGridColors.current.secondary)
                if (state.loading) GridText("正在打开草稿…")
                if (draft?.status == DraftStatus.RESULT_UNKNOWN) ErrorMessage("保存结果未知。请先重试原请求确认结果，再修改内容。", "result-unknown")
                if (draft?.status == DraftStatus.EXPIRED_REVIEW) ErrorMessage("原请求超过 7 天，需先读取服务器当前值并确认新保存。", "expired-draft")
            }
            if (draft != null) {
                editableFields(kind).forEach { field -> item(key = field) {
                    when (field) {
                        "rating_tenths" -> RatingEditor(draft.localFields[field], { onField(field, it) }, enabled)
                        "category" -> {
                            GridText("类型", style = MaterialTheme.typography.titleMedium)
                            ChoiceChips(Category.entries.map { it.code to categoryLabel(it.code) }, draft.localFields[field], { onField(field, it) }, enabled)
                        }
                        "meal" -> {
                            GridText("餐次 · 选填", style = MaterialTheme.typography.titleMedium)
                            ChoiceChips(listOf(null to "未填写") + Meal.entries.map { it.code to mealLabel(it.code) }, draft.localFields[field], { onField(field, it) }, enabled)
                        }
                        else -> FormField(field, draft.localFields[field], { onField(field, it) }, enabled, state.errors[field],
                            label = (if (field == "review" && kind == RecordKind.DISH) "短评" else if (field == "note" && kind == RecordKind.REVISION) "历史说明" else fieldLabel(field)) +
                                if (field in setOf("cuisine", "address", "review", "note")) " · 选填" else "",
                            minLines = if (field in setOf("review", "note")) 4 else if (field == "address") 2 else 1,
                            supporting = if (field == "dining_date") "YYYY-MM-DD，同日多次到店分别保留" else null)
                    }
                } }
                if (kind == RecordKind.RESTAURANT && draft.action == WriteAction.CREATE) item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row { Checkbox(draft.firstVisit != null, { onFirstVisit(if (it) FirstVisitInput(UUID.randomUUID().toString(), LocalDate.now().toString()) else null) }, enabled = enabled)
                            GridText("同时新增首访", Modifier.padding(top = 12.dp)) }
                        val visit = draft.firstVisit
                        if (visit != null) {
                            FormField("first-visit-date", visit.date, { onFirstVisit(visit.copy(date = it)) }, enabled, state.errors["first_visit"], label = "首访日期", supporting = "YYYY-MM-DD")
                            ChoiceChips(listOf(null to "未填写") + Meal.entries.map { it.code to mealLabel(it.code) }, visit.meal, { onFirstVisit(visit.copy(meal = it)) }, enabled)
                            FormField("first-visit-note", visit.note, { onFirstVisit(visit.copy(note = it)) }, enabled, state.errors["first_visit_note"], label = "首访备注 · 选填", minLines = 2)
                        }
                    }
                }
                if (kind == RecordKind.RESTAURANT) {
                    item {
                        GridText("店内菜品", style = MaterialTheme.typography.headlineSmall)
                        if (draft.baseSnapshot == null) GridText("保存餐厅后即可在这里新增、编辑、删除菜品", color = LocalQuietGridColors.current.secondary)
                        else {
                            if (onAddDish != null) Button(onClick = onAddDish, enabled = !state.saving, modifier = Modifier.fillMaxWidth().testTag("edit-add-dish")) { Text("新增菜品") }
                            if (onManageDishes != null) TextButton(onClick = onManageDishes, enabled = !state.saving, modifier = Modifier.testTag("edit-manage-dishes")) { Text("菜品列表与回收站") }
                        }
                    }
                    items(dishes, key = { "edit-dish:${it.ref.id}" }) { RecordRow(it, onDish) }
                }
                item { TextButton(onClick = { confirmDiscard = true }, enabled = !state.saving) { Text("放弃本机草稿", color = LocalQuietGridColors.current.danger) } }
            }
        }
    }
    if (confirmDiscard) ConfirmAction("放弃本机草稿？", if (draft?.status == DraftStatus.RESULT_UNKNOWN) "保存结果尚未确认。删除草稿不会撤销可能已经完成的服务端保存。" else "此草稿将从本机移除，服务器已保存记录不受影响。", "放弃草稿", { confirmDiscard = false; onDiscard() }, { confirmDiscard = false })
}
