package top.alanvampuke.dininglog.feature.restaurants

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.delay
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.*
import top.alanvampuke.dininglog.feature.components.*
import top.alanvampuke.dininglog.feature.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun RecordDetailScreen(state: DetailUiState, onBack: () -> Unit, onRefresh: () -> Unit,
    onEdit: (RecordSnapshot) -> Unit, onDelete: (RecordSnapshot) -> Unit, onRestore: (RecordSnapshot) -> Unit,
    onHistory: (RecordSnapshot) -> Unit, dishes: CachedPage = CachedPage(), visits: CachedPage = CachedPage(),
    onChild: (RecordSummary) -> Unit = {}, onDishes: (() -> Unit)? = null, onVisits: (() -> Unit)? = null,
    onAddDish: (() -> Unit)? = null, onNewVisit: (() -> Unit)? = null, bottomBar: @Composable () -> Unit = {}) {
    val record = state.cached.snapshot
    val c = LocalQuietGridColors.current
    val inkPaper = LocalThemeStyle.current.isInkPaper
    // The design dish page restructures the ticket; restaurant and visit
    // details keep the existing layout.
    val isDishInk = record?.ref?.kind == RecordKind.DISH && inkPaper
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var userRefresh by remember { mutableStateOf(false) }
    var justRefreshed by remember { mutableStateOf(false) }
    // The design shows "已是最新" briefly after a refresh — only after a
    // user-initiated one, never after the initial load.
    LaunchedEffect(state.refreshing) {
        if (!state.refreshing && userRefresh) {
            userRefresh = false
            if (state.error == null) { justRefreshed = true; delay(2500); justRefreshed = false }
        }
    }
    ScreenFrame("screen:detail", bottomBar, floatingBottomBar = true) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { userRefresh = true; onRefresh() }, modifier = Modifier.fillMaxSize().padding(listViewportPadding(padding))) {
        LazyColumn(Modifier.fillMaxSize().testTag("detail-scroll"), contentPadding = listContentPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PageHeader("探店小票", onBack, trailing = if (isDishInk) null else { modifier ->
                BiteTextButton(onClick = onRefresh, enabled = !state.refreshing, modifier = modifier.testTag("detail-refresh")) { Text(if (state.refreshing) "正在刷新…" else "刷新详情") }
            }) }
            if (state.offline || state.cached.stale || state.error != null || record == null) item {
                CacheStatus(state.offline, state.cached.fetchedAt, state.cached.stale)
                if (state.error != null) ErrorMessage(state.error)
                if (record == null) GridText(if (state.refreshing) "正在读取详情…" else "尚无完整详情缓存，请联网后重试", Modifier.testTag("detail-empty"))
            }
            if (record != null) {
                item {
                    ReceiptEdge(true)
                    BitePanel(Modifier.testTag("pr17:receipt-head"), fill = c.paper) {
                        if (isDishInk) {
                            // Design ticket head: parent restaurant line (only
                            // when the detail payload ever carries it — today
                            // it does not), dish name, big score, last-change
                            // note, review folded into the ticket. No mint
                            // sticker, no "已修订" stamp, no meter.
                            record.fields["restaurant_name"]?.takeIf { it.isNotBlank() }?.let {
                                GridText(it, style = QuietGridText.caption, color = c.secondary)
                            }
                            GridText(record.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineMedium)
                            val tenths = record.fields["rating_tenths"]?.toIntOrNull()
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Score(record.fields["rating_tenths"], prominent = true, meter = false)
                                GridText(if (tenths == null) "未评分" else "/ 5 · 我的评分", style = QuietGridText.caption,
                                    color = c.secondary, modifier = Modifier.padding(bottom = 4.dp))
                            }
                            ratingChangeNote(record)?.let { GridText(it, style = QuietGridText.caption, color = c.secondary) }
                            if (!record.fields["review"].isNullOrBlank()) {
                                DashedDivider()
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    GridText("短评", style = QuietGridText.caption, color = c.secondary)
                                    ExpandableValue("review", record.fields["review"], "detail:review")
                                }
                            }
                            // Tear row, design "店铺名 | NO. 009" style; only
                            // when the parent restaurant is known. The record
                            // version is the closest existing record number.
                            record.fields["restaurant_name"]?.takeIf { it.isNotBlank() }?.let { where ->
                                DashedDivider()
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    GridText(where, style = QuietGridText.caption, color = c.secondary)
                                    GridText("NO. " + "%03d".format(record.version), style = QuietGridText.caption, color = c.secondary)
                                }
                            }
                        } else {
                            GridText("BITE! 食记 · 探店小票 · 版本 ${record.version}", style = QuietGridText.date, color = c.secondary)
                            val ratedKind = record.ref.kind in setOf(RecordKind.RESTAURANT, RecordKind.DISH)
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                if (ratedKind && LocalDensity.current.fontScale <= 1.3f && maxWidth >= 280.dp) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        GridText(record.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                                        Score(record.fields["rating_tenths"], prominent = true, meter = false)
                                    }
                                } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    GridText(record.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineMedium)
                                    if (ratedKind) Score(record.fields["rating_tenths"], prominent = true, meter = false)
                                }
                            }
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (ratedKind) {
                                    RatingMeter(record.fields["rating_tenths"])
                                    VerdictSticker(record.fields["rating_tenths"], record.ref.kind == RecordKind.DISH)
                                }
                                val date = record.fields["dining_date"] ?: record.fields["last_visit_date"]
                                if (!date.isNullOrBlank()) Sticker("到店 $date", c.mint)
                                if (record.version > 1) GridText("已修订", Modifier.revisedStamp(c.danger).padding(7.dp),
                                    style = QuietGridText.caption, color = c.danger)
                                GridText("更新于 ${formatAbsoluteTime(record.updatedAt)}", style = QuietGridText.date, color = c.secondary)
                            }
                            }
                    }
                }
                val extraFields = editableFields(record.ref.kind).filter { it !in setOf("name", "rating_tenths", "review", "note") && !record.fields[it].isNullOrBlank() }
                if (extraFields.isNotEmpty()) item {
                    BitePanel(fill = c.paper) {
                        val shortFields = (if (record.ref.kind == RecordKind.RESTAURANT) listOf("category", "cuisine") else listOf("dining_date", "meal")).filter { it in extraFields }
                        val fieldValue: @Composable (String, Modifier) -> Unit = { field, modifier ->
                            val label: @Composable () -> Unit = {
                                if (field == "address") Sticker("地址", c.klein)
                                else GridText("${fieldLabel(field)}：", style = QuietGridText.caption, color = c.secondary)
                            }
                            if (LocalDensity.current.fontScale > 1.3f) Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                label()
                                GridText(displayValue(field, record.fields[field]), Modifier.fillMaxWidth().testTag("detail:$field"))
                            } else Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                label()
                                GridText(displayValue(field, record.fields[field]), Modifier.weight(1f).testTag("detail:$field"))
                            }
                        }
                        if (shortFields.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth()) {
                            if (shortFields.size == 2 && LocalDensity.current.fontScale <= 1.3f && maxWidth >= 280.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                shortFields.forEach { fieldValue(it, Modifier.weight(1f)) }
                            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                shortFields.forEach { fieldValue(it, Modifier.fillMaxWidth()) }
                            }
                        }
                        extraFields.filter { it !in shortFields }.forEach { fieldValue(it, Modifier.fillMaxWidth()) }
                    }
                }
                // Dish InkPaper folds the review into the ticket above.
                if (!isDishInk && "review" in editableFields(record.ref.kind) && !record.fields["review"].isNullOrBlank()) item {
                    BitePanel(fill = c.paper) {
                        GridText(if (record.ref.kind == RecordKind.DISH) "短评" else "我的评价", style = MaterialTheme.typography.titleMedium)
                        ExpandableValue("review", record.fields["review"], "detail:review")
                    }
                }
                if ("note" in editableFields(record.ref.kind) && !record.fields["note"].isNullOrBlank()) item {
                    BitePanel { GridText("备注", style = MaterialTheme.typography.titleMedium); ExpandableValue("note", record.fields["note"], "detail:note") }
                }
                item {
                    if (isDishInk) {
                        // The design does not depict edit/delete; keep them as
                        // flat rows with text buttons, delete in red.
                        Column(Modifier.fillMaxWidth()) {
                            if (record.deletedAt == null) {
                                BiteTextButton(onClick = { onEdit(record) }, modifier = Modifier.fillMaxWidth().testTag("edit")) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) { Text("编辑") }
                                }
                                HorizontalDivider(color = c.border, thickness = 1.dp)
                                BiteTextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().testTag("delete")) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) { Text("删除记录", color = c.danger) }
                                }
                            } else {
                                GridText("此记录在回收站 · 删除于 ${record.deletedAt?.let { formatAbsoluteTime(it) } ?: ""}", color = c.danger)
                                BiteTextButton(onClick = { confirmRestore = true }, modifier = Modifier.fillMaxWidth().testTag("restore")) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) { Text("恢复记录") }
                                }
                            }
                        }
                    } else BitePanel {
                        if (record.deletedAt == null) {
                            ResponsiveActions(first = { modifier ->
                                BiteButton(onClick = { onEdit(record) }, modifier = modifier.testTag("edit")) { Text("编辑") }
                            }, second = { modifier ->
                                BiteTextButton(onClick = { confirmDelete = true }, modifier = modifier.testTag("delete")) { Text("删除记录", color = c.danger) }
                            })
                        } else {
                            GridText("此记录在回收站 · 删除于 ${record.deletedAt?.let { formatAbsoluteTime(it) } ?: ""}", color = c.danger)
                            BiteButton(onClick = { confirmRestore = true }, modifier = Modifier.fillMaxWidth().testTag("restore")) { Text("恢复记录") }
                        }
                    }
                }
                if (record.ref.kind == RecordKind.RESTAURANT && record.deletedAt == null) {
                    item {
                        BitePanel {
                            GridText("菜品测评清单", style = MaterialTheme.typography.headlineSmall)
                            val count = record.fields["dish_count"]
                            val caption = when {
                                count != null -> "店内菜品 · $count 道" + if (dishes.items.isEmpty()) " · 菜品在本餐厅内管理" else ""
                                dishes.items.isEmpty() -> "菜品在本餐厅内管理"
                                else -> null
                            }
                            if (caption != null) GridText(caption, style = QuietGridText.caption, color = c.secondary)
                            ResponsiveActions(first = onAddDish?.let { callback -> { modifier ->
                                BiteButton(onClick = callback, modifier = modifier.testTag("add-dish")) { Text("新增菜品") }
                            } }, second = onDishes?.let { callback -> { modifier ->
                                BiteTextButton(onClick = callback, modifier = modifier) { Text("管理店内菜品") }
                            } })
                        }
                    }
                    items(dishes.items, key = { "dish:${it.ref.id}" }) { RecordRow(it, onChild, number = dishes.items.indexOf(it) + 1) }
                    item {
                        BitePanel {
                            val recent = record.fields["last_visit_date"]?.takeIf { it.isNotBlank() }
                            ResponsiveHeading(title = { modifier ->
                                GridText(record.fields["visit_count"]?.let { "到店记录 · $it 次" } ?: "到店记录", modifier, style = MaterialTheme.typography.headlineSmall)
                            }, trailing = recent?.let { date -> { modifier -> Sticker("最近到店 $date", c.mint, modifier) } }, trailingIsButton = false)
                            ResponsiveActions(first = onNewVisit?.let { callback -> { modifier ->
                                BiteButton(onClick = callback, modifier = modifier.testTag("add-visit")) { Text("再次到店") }
                            } }, second = onVisits?.let { callback -> { modifier ->
                                BiteTextButton(onClick = callback, modifier = modifier.testTag("visit-history")) { Text("查看全部到店记录") }
                            } })
                        }
                    }
                    items(visits.items, key = { "visit:${it.ref.id}" }) { RecordRow(it, onChild) }
                }
                item {
                    BitePanel(Modifier.testTag("pr17:detail-history-panel")) {
                        ResponsiveHeading(title = { modifier ->
                            GridText("修改历史", modifier, style = MaterialTheme.typography.headlineSmall)
                        }, trailing = { modifier ->
                            BiteTextButton(onClick = { onHistory(record) }, modifier = modifier.testTag("history")) { Text("查看修改历史") }
                        })
                        Column(Modifier.revisionRail(c.border).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GridText("当前版本 ${record.version}", style = MaterialTheme.typography.titleMedium)
                            GridText(formatAbsoluteTime(record.updatedAt), style = QuietGridText.date, color = c.secondary)
                        }
                    }
                }
                item {
                    if (isDishInk) {
                        // Design bottom: a centered "刷新详情" text link with a
                        // transient "已是最新" state; no barcode, no slogan.
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BiteTextButton(onClick = { userRefresh = true; onRefresh() }, enabled = !state.refreshing,
                                modifier = Modifier.testTag("detail-refresh")) { Text(if (state.refreshing) "正在刷新…" else "刷新详情") }
                            if (justRefreshed) GridText("已是最新", style = QuietGridText.caption, color = c.accent)
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().testTag("pr17:receipt-footer").brutalBox(if (LocalThemeStyle.current.isInkPaper) Color.Transparent else c.surface, c.border, c.shadow)
                            .padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ReceiptBarcode()
                            GridText("食记 · 每一口都值得记录", style = QuietGridText.caption, color = c.secondary)
                        }
                        ReceiptEdge(false)
                    }
                }
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
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("edit-scroll"), contentPadding = PaddingValues(LocalGridDimensions.current.edge), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        "rating_tenths" -> RatingEditor(input.localFields[field], { onField(field, it) }, enabled, framed = false)
                        "category" -> {
                            GridText("类型", style = MaterialTheme.typography.titleMedium)
                            ChoiceChips(Category.entries.map { it.code to categoryLabel(it.code) }, input.localFields[field], { onField(field, it) }, enabled)
                        }
                        "meal" -> {
                            GridText("餐次 · 选填", style = MaterialTheme.typography.titleMedium)
                            // The null chip is the "clear selection" action, not a
                            // meal type; label it as such instead of "未填写".
                            ChoiceChips(listOf(null to "不选餐次") + Meal.entries.map { it.code to mealLabel(it.code) }, input.localFields[field], { onField(field, it) }, enabled)
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
                            var firstVisitExpanded by rememberSaveable(visit.id) { mutableStateOf(false) }
                            val dateError = state.errors["first_visit"]
                            val noteError = state.errors["first_visit_note"]
                            LaunchedEffect(visit.id, dateError, noteError) {
                                if (dateError != null || noteError != null) firstVisitExpanded = true
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GridText("首访 ${visit.date}${visit.meal?.let { " · ${mealLabel(it)}" } ?: ""}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = LocalQuietGridColors.current.secondary)
                                BiteTextButton(onClick = { firstVisitExpanded = !firstVisitExpanded }, modifier = Modifier.testTag("first-visit-toggle")) {
                                    Text(if (firstVisitExpanded) "收起" else "展开详情")
                                }
                            }
                            if (firstVisitExpanded) {
                                FormField("first-visit-date", visit.date, { onFirstVisit(visit.copy(date = it)) }, enabled, dateError, label = "首访日期", supporting = if (visit.date.isBlank() || !validDate(visit.date)) "YYYY-MM-DD" else null)
                                ChoiceChips(listOf(null to "不选餐次") + Meal.entries.map { it.code to mealLabel(it.code) }, visit.meal, { onFirstVisit(visit.copy(meal = it)) }, enabled)
                                FormField("first-visit-note", visit.note, { onFirstVisit(visit.copy(note = it)) }, enabled, noteError, label = "首访备注 · 选填", minLines = 2)
                            } else {
                                // Errors stay visible if the user folds the section again.
                                if (dateError != null) ErrorMessage(dateError, "first-visit-date-error")
                                if (noteError != null) ErrorMessage(noteError, "first-visit-note-error")
                            }
                        }
                    }
                }
                if (kind == RecordKind.RESTAURANT) {
                    item {
                        GridText("店内菜品", style = MaterialTheme.typography.headlineSmall)
                        if (input.baseSnapshot == null) GridText("保存餐厅后即可在这里新增、编辑、删除菜品", color = LocalQuietGridColors.current.secondary)
                        else {
                            ResponsiveActions(first = onAddDish?.let { callback -> { modifier ->
                                BiteButton(onClick = callback, enabled = !state.saving, modifier = modifier.testTag("edit-add-dish")) { Text("新增菜品") }
                            } }, second = onManageDishes?.let { callback -> { modifier ->
                                BiteTextButton(onClick = callback, enabled = !state.saving, modifier = modifier.testTag("edit-manage-dishes")) { Text("菜品列表与回收站") }
                            } })
                        }
                    }
                    items(dishes, key = { "edit-dish:${it.ref.id}" }) { RecordRow(it, onDish) }
                }
            }
        }
    }
}

// A dashed rule inside the dish ticket, same dash as ReceiptEdge's InkPaper branch.
@Composable private fun DashedDivider() {
    val c = LocalQuietGridColors.current
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        drawLine(c.border, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
    }
}

private fun formatMonthDay(iso: String): String? = runCatching {
    DateTimeFormatter.ofPattern("M月d日").withZone(ZoneId.systemDefault()).format(Instant.parse(iso))
}.getOrNull()

// The design's "上次修改 M月D日，由 X 上调" line. A record detail carries a
// diff only right after a local save (ProductRepositoryImpl synthesizes it
// from the pending write), so this is null on ordinary loads and the line is
// omitted then — historical changes are not in the detail payload.
private fun ratingChangeNote(record: RecordSnapshot): String? {
    val change = record.diff.firstOrNull { it.field == "rating_tenths" } ?: return null
    val before = change.before?.toIntOrNull() ?: return null
    val after = change.after?.toIntOrNull() ?: return null
    if (before == after) return null
    val date = formatMonthDay(record.updatedAt) ?: return null
    return "上次修改 $date，由 ${Rating.fromTenths(before).display} " + if (after > before) "上调" else "下调"
}
