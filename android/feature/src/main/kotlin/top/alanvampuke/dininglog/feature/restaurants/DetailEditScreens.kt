package top.alanvampuke.dininglog.feature.restaurants

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
    val c = LocalQuietGridColors.current
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    ScreenFrame("screen:detail", bottomBar, floatingBottomBar = true) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(listViewportPadding(padding)).testTag("detail-scroll"), contentPadding = listContentPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PageHeader("探店小票", onBack) }
            item {
                CacheStatus(state.offline, state.cached.fetchedAt, state.cached.stale)
                if (state.error != null) ErrorMessage(state.error)
                BiteTextButton(onClick = onRefresh, enabled = !state.refreshing, modifier = Modifier.testTag("detail-refresh")) { Text(if (state.refreshing) "正在刷新…" else "刷新详情") }
                if (record == null) GridText(if (state.refreshing) "正在读取详情…" else "尚无完整详情缓存，请联网后重试", Modifier.testTag("detail-empty"))
            }
            if (record != null) {
                item {
                    ReceiptEdge(true)
                    BitePanel {
                        GridText("BITE! 食记 · 探店小票", style = QuietGridText.date, color = c.secondary)
                        GridText(record.title, style = MaterialTheme.typography.headlineMedium)
                        if (record.ref.kind in setOf(RecordKind.RESTAURANT, RecordKind.DISH)) {
                            Score(record.fields["rating_tenths"], prominent = true, meter = true)
                            VerdictSticker(record.fields["rating_tenths"], record.ref.kind == RecordKind.DISH)
                        }
                        val date = record.fields["dining_date"] ?: record.fields["last_visit_date"]
                        if (!date.isNullOrBlank()) Sticker("到店 $date", c.mint)
                        if (record.version > 1) GridText("已修订 · 版本 ${record.version}", Modifier.revisedStamp(c.danger).padding(7.dp),
                            style = QuietGridText.caption, color = c.danger)
                        GridText("版本 ${record.version} · 更新于 ${record.updatedAt}", style = QuietGridText.date, color = c.secondary)
                    }
                }
                item {
                    BitePanel(fill = c.paper) {
                        editableFields(record.ref.kind).filter { it !in setOf("name", "rating_tenths", "review", "note") && !record.fields[it].isNullOrBlank() }.forEach { field ->
                            if (field == "address") Sticker("地址", c.klein)
                            else GridText(fieldLabel(field), style = MaterialTheme.typography.titleSmall, color = c.secondary)
                            GridText(displayValue(field, record.fields[field]), Modifier.testTag("detail:$field"))
                        }
                    }
                }
                if ("review" in editableFields(record.ref.kind) && !record.fields["review"].isNullOrBlank()) item {
                    BitePanel(fill = c.paper) {
                        GridText(if (record.ref.kind == RecordKind.DISH) "短评" else "我的评价", style = MaterialTheme.typography.titleMedium)
                        ExpandableValue("review", record.fields["review"], "detail:review")
                    }
                }
                if ("note" in editableFields(record.ref.kind) && !record.fields["note"].isNullOrBlank()) item {
                    BitePanel { GridText("备注", style = MaterialTheme.typography.titleMedium); ExpandableValue("note", record.fields["note"], "detail:note") }
                }
                item {
                    BitePanel {
                        if (record.deletedAt == null) {
                            BiteButton(onClick = { onEdit(record) }, modifier = Modifier.fillMaxWidth().testTag("edit")) { Text("编辑") }
                            BiteTextButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("delete")) { Text("删除记录", color = c.danger) }
                        } else {
                            GridText("此记录在回收站 · 删除于 ${record.deletedAt}", color = c.danger)
                            BiteButton(onClick = { confirmRestore = true }, modifier = Modifier.fillMaxWidth().testTag("restore")) { Text("恢复记录") }
                        }
                    }
                }
                if (record.ref.kind == RecordKind.RESTAURANT && record.deletedAt == null) {
                    item {
                        BitePanel {
                            GridText("菜品测评清单", style = MaterialTheme.typography.headlineSmall)
                            record.fields["dish_count"]?.let { GridText("店内菜品 · $it 道", style = QuietGridText.caption) }
                            if (onAddDish != null) BiteButton(onClick = onAddDish, modifier = Modifier.fillMaxWidth().testTag("add-dish")) { Text("新增菜品") }
                            if (onDishes != null) BiteTextButton(onClick = onDishes) { Text("管理店内菜品") }
                            if (dishes.items.isEmpty()) GridText("菜品在本餐厅内管理", color = c.secondary)
                        }
                    }
                    items(dishes.items, key = { "dish:${it.ref.id}" }) { RecordRow(it, onChild, number = dishes.items.indexOf(it) + 1) }
                    item {
                        BitePanel {
                            GridText("到店记录", style = MaterialTheme.typography.headlineSmall)
                            record.fields["visit_count"]?.let { GridText("已记录 $it 次", style = QuietGridText.caption) }
                            record.fields["last_visit_date"]?.takeIf { it.isNotBlank() }?.let { Sticker("最近到店 $it", c.mint) }
                            if (onNewVisit != null) BiteButton(onClick = onNewVisit, modifier = Modifier.fillMaxWidth().testTag("add-visit")) { Text("再次到店") }
                            if (onVisits != null) BiteTextButton(onClick = onVisits, modifier = Modifier.testTag("visit-history")) { Text("查看全部到店记录") }
                        }
                    }
                    items(visits.items, key = { "visit:${it.ref.id}" }) { RecordRow(it, onChild) }
                }
                item {
                    BitePanel {
                        GridText("修改历史", style = MaterialTheme.typography.headlineSmall)
                        Column(Modifier.revisionRail(c.border).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GridText("当前版本 ${record.version}", style = MaterialTheme.typography.titleMedium)
                            GridText(record.updatedAt, style = QuietGridText.date, color = c.secondary)
                            BiteTextButton(onClick = { onHistory(record) }, modifier = Modifier.testTag("history")) { Text("查看修改历史") }
                        }
                        ReceiptBarcode()
                        GridText("食记 · 每一口都值得记录", style = QuietGridText.caption, color = c.secondary)
                    }
                    ReceiptEdge(false)
                }
            }
        }
    }
    if (record != null && confirmDelete) ConfirmAction("删除这条记录？", if (record.ref.kind == RecordKind.RESTAURANT) "餐厅删除后，菜品与到店将暂时不可用。可在回收站恢复餐厅。" else "记录将进入回收站，可在保留窗口内恢复。", "确认删除", { confirmDelete = false; onDelete(record) }, { confirmDelete = false })
    if (record != null && confirmRestore) ConfirmAction("恢复这条记录？", "将使用当前版本恢复；父餐厅需要处于可用状态。", "确认恢复", { confirmRestore = false; onRestore(record) }, { confirmRestore = false })
}

@Composable fun EditScreen(state: EditorUiState, onField: (String, String?) -> Unit, onFirstVisit: (FirstVisitInput?) -> Unit,
    onSave: () -> Unit, onBack: () -> Unit, dishes: List<RecordSummary> = emptyList(),
    onDish: (RecordSummary) -> Unit = {}, onAddDish: (() -> Unit)? = null, onManageDishes: (() -> Unit)? = null) {
    val input = state.input
    val kind = input?.ref?.kind ?: RecordKind.RESTAURANT
    val enabled = input != null && !state.saving && input.action in setOf(WriteAction.CREATE, WriteAction.UPDATE, WriteAction.METADATA)
    val dirty = input?.let { it.action in setOf(WriteAction.CREATE, WriteAction.DELETE, WriteAction.RESTORE, WriteAction.APPLY) || it.changedFields.isNotEmpty() } == true
    ScreenFrame("screen:edit", bottomBar = { SaveBar(input != null && dirty, state.saving,
        onSave, state.message) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("edit-scroll"), contentPadding = PaddingValues(QuietGridDimensions.edge), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { PageHeader(if (input?.action == WriteAction.CREATE) when (kind) { RecordKind.RESTAURANT -> "新增餐厅"; RecordKind.DISH -> "新增菜品"; RecordKind.VISIT -> "新增到店"; else -> "新增" }
                else if (kind == RecordKind.REVISION) "修改历史说明" else "编辑${when (kind) { RecordKind.RESTAURANT -> "餐厅"; RecordKind.DISH -> "菜品"; RecordKind.VISIT -> "到店"; else -> "记录" }}", onBack) }
            item {
                GridText("点击保存后才会提交", style = MaterialTheme.typography.bodySmall, color = LocalQuietGridColors.current.secondary)
                if (state.loading) GridText("正在打开编辑页…")
            }
            if (input != null) {
                editableFields(kind).forEach { field -> item(key = field) {
                    BitePanel {
                    when (field) {
                        "rating_tenths" -> RatingEditor(input.localFields[field], { onField(field, it) }, enabled)
                        "category" -> {
                            GridText("类型", style = MaterialTheme.typography.titleMedium)
                            ChoiceChips(Category.entries.map { it.code to categoryLabel(it.code) }, input.localFields[field], { onField(field, it) }, enabled)
                        }
                        "meal" -> {
                            GridText("餐次 · 选填", style = MaterialTheme.typography.titleMedium)
                            ChoiceChips(listOf(null to "未填写") + Meal.entries.map { it.code to mealLabel(it.code) }, input.localFields[field], { onField(field, it) }, enabled)
                        }
                        else -> FormField(field, input.localFields[field], { onField(field, it) }, enabled, state.errors[field],
                            label = (if (field == "review" && kind == RecordKind.DISH) "短评" else if (field == "note" && kind == RecordKind.REVISION) "历史说明" else fieldLabel(field)) +
                                if (field in setOf("cuisine", "address", "review", "note")) " · 选填" else "",
                            minLines = if (field in setOf("review", "note")) 4 else if (field == "address") 2 else 1,
                            supporting = if (field == "dining_date") "YYYY-MM-DD，同日多次到店分别保留" else null)
                    }
                    }
                } }
                if (kind == RecordKind.RESTAURANT && input.action == WriteAction.CREATE) item {
                    BitePanel {
                        Row { Checkbox(input.firstVisit != null, { onFirstVisit(if (it) FirstVisitInput(UUID.randomUUID().toString(), LocalDate.now().toString()) else null) }, enabled = enabled)
                            GridText("同时新增首访", Modifier.padding(top = 12.dp)) }
                        val visit = input.firstVisit
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
                        if (input.baseSnapshot == null) GridText("保存餐厅后即可在这里新增、编辑、删除菜品", color = LocalQuietGridColors.current.secondary)
                        else {
                            if (onAddDish != null) BiteButton(onClick = onAddDish, enabled = !state.saving, modifier = Modifier.fillMaxWidth().testTag("edit-add-dish")) { Text("新增菜品") }
                            if (onManageDishes != null) BiteTextButton(onClick = onManageDishes, enabled = !state.saving, modifier = Modifier.testTag("edit-manage-dishes")) { Text("菜品列表与回收站") }
                        }
                    }
                    items(dishes, key = { "edit-dish:${it.ref.id}" }) { RecordRow(it, onDish) }
                }
            }
        }
    }
}
