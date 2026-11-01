package top.alanvampuke.dininglog.feature.restaurants

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.components.*
import top.alanvampuke.dininglog.feature.theme.*

@Composable fun RecordListScreen(state: ListUiState, searchText: String, filters: ListFilters,
    onSearch: (String) -> Unit, onFilters: (ListFilters) -> Unit, onRecord: (RecordSummary) -> Unit,
    onRefresh: () -> Unit, onMore: () -> Unit, onAdd: (() -> Unit)? = null, onTrash: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null, titleOverride: String? = null, onChooseRestaurant: (() -> Unit)? = null,
    onClearRestaurant: (() -> Unit)? = null, bottomBar: @Composable () -> Unit = {}) {
    val kind = state.query.kind
    val isSearch = searchText.isNotBlank()
    val title = titleOverride ?: if (state.query.deleted == DeletedFilter.ONLY) if (kind == RecordKind.REVISION) "隐藏的修改历史" else "回收站" else when (kind) {
        RecordKind.RESTAURANT -> if (isSearch) "搜索结果" else "我的餐厅"
        RecordKind.DISH -> "店内菜品"; RecordKind.VISIT -> "到店时间线"; RecordKind.REVISION -> "修改历史"
    }
    val tag = if (isSearch && kind == RecordKind.RESTAURANT) "screen:search" else "screen:${kind.name.lowercase()}:list"
    var expandedFilters by rememberSaveable(kind) { mutableStateOf(false) }
    ScreenFrame(tag, bottomBar) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("record-list"), contentPadding = PaddingValues(QuietGridDimensions.edge), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { PageHeader(title, onBack) }
            item {
                OutlinedTextField(searchText, onSearch, Modifier.fillMaxWidth().testTag("search"), label = { GridText(when (kind) {
                    RecordKind.RESTAURANT -> "搜索餐厅、菜品、评价、备注"
                    RecordKind.DISH -> "搜索本店菜品与短评"; RecordKind.VISIT -> "搜索餐厅、日期、备注"; RecordKind.REVISION -> "搜索修改与说明"
                }) }, textStyle = QuietGridText.input)
            }
            if (kind == RecordKind.RESTAURANT) item { GridText("可搜店名 · 类型 · 菜系 · 地址 · 菜品 · 短评 · 评价 · 备注", style = MaterialTheme.typography.bodySmall, color = LocalQuietGridColors.current.secondary) }
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (onAdd != null) Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().testTag("add")) { Text(when (kind) { RecordKind.RESTAURANT -> "新增餐厅"; RecordKind.DISH -> "新增菜品"; RecordKind.VISIT -> "新增到店"; else -> "新增" }) }
                    TextButton(onClick = onRefresh, enabled = !state.isRefreshing, modifier = Modifier.testTag("refresh")) { Text(if (state.isRefreshing) "正在刷新…" else "刷新第一页") }
                    if (kind != RecordKind.REVISION) TextButton(onClick = { expandedFilters = !expandedFilters }, modifier = Modifier.testTag("filters")) { Text(if (expandedFilters) "收起筛选" else "筛选与排序") }
                    if (onChooseRestaurant != null) TextButton(onClick = onChooseRestaurant, modifier = Modifier.testTag("choose-restaurant")) { Text("按餐厅筛选") }
                    if (onClearRestaurant != null) {
                        GridText("当前只显示所选餐厅的到店记录", color = LocalQuietGridColors.current.secondary)
                        TextButton(onClick = onClearRestaurant, modifier = Modifier.testTag("clear-restaurant")) { Text("显示全部餐厅") }
                    }
                    if (onTrash != null) TextButton(onClick = onTrash, modifier = Modifier.testTag("trash")) { Text(if (kind == RecordKind.REVISION) "管理隐藏历史" else "打开回收站") }
                }
            }
            if (expandedFilters) item { ListFilterPanel(kind, filters, onFilters) }
            item {
                CacheStatus(state.isOffline, state.fetchedAt, localSearch = (state.isOffline || state.localScope) && isSearch)
                if (state.error != null) ErrorMessage(state.error!!, "load-error")
            }
            if (state.items.isEmpty()) item {
                val text = when {
                    state.isRefreshing -> "正在读取记录…"
                    state.isOffline || state.localScope -> when {
                        isSearch -> "已缓存内容中没有匹配；服务器范围未确认"
                        state.isOffline -> "此设备没有可用缓存，请联网后重试"
                        else -> "此设备还没有缓存，服务器范围尚未确认；可刷新重试"
                    }
                    state.error != null -> "加载失败，请重试"
                    isSearch || filters != ListFilters() -> "没有符合条件的结果"
                    state.query.deleted == DeletedFilter.ONLY -> "这里没有可恢复的记录"
                    else -> when (kind) { RecordKind.RESTAURANT -> "还没有餐厅，记下第一家吧"; RecordKind.DISH -> "这家餐厅还没有菜品"; RecordKind.VISIT -> "还没有到店记录"; RecordKind.REVISION -> "还没有修改历史" }
                }
                val emptyTag = when { state.isOffline || state.localScope -> "cache-empty"; isSearch && state.error == null -> "no-results"; else -> "empty-state" }
                GridText(text, Modifier.fillMaxWidth().testTag(emptyTag), color = LocalQuietGridColors.current.secondary)
            }
            items(state.items, key = { it.ref.id }) { record -> RecordRow(record, onRecord) }
            if (state.hasMore) item { Button(onClick = onMore, enabled = !state.isLoadingMore && !state.isRefreshing,
                modifier = Modifier.fillMaxWidth().testTag("load-more")) { Text(if (state.isLoadingMore) "正在加载更多…" else "加载更多") } }
        }
    }
}
@Composable fun RecordRow(record: RecordSummary, onRecord: (RecordSummary) -> Unit) {
    val c = LocalQuietGridColors.current
    val large = LocalDensity.current.fontScale > 1.3f
    Column(Modifier.fillMaxWidth().clickable { onRecord(record) }.testTag("record:${record.ref.id}").padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (large) {
            RowText(record)
            if (record.ref.kind in setOf(RecordKind.RESTAURANT, RecordKind.DISH)) Score(record.fields["rating_tenths"])
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { RowText(record) }
            if (record.ref.kind in setOf(RecordKind.RESTAURANT, RecordKind.DISH)) Score(record.fields["rating_tenths"])
        }
        if (record.matches.isNotEmpty() && record.ref.kind == RecordKind.RESTAURANT) GridText(record.matches.joinToString(" · ") { it.label }, Modifier.testTag("matches:${record.ref.id}"), style = MaterialTheme.typography.bodySmall, color = c.accentInk)
        HorizontalDivider(color = c.border)
    }
}
@Composable private fun RowText(record: RecordSummary) {
    var absolute by remember(record.ref.id) { mutableStateOf(false) }
    GridText(record.title, Modifier.testTag("title:${record.ref.id}"), style = MaterialTheme.typography.titleMedium)
    val fields = record.fields
    val secondary = when (record.ref.kind) {
        RecordKind.RESTAURANT -> "${categoryLabel(fields["category"])} · ${fields["cuisine"] ?: "菜系未填写"}\n${fields["address_excerpt"] ?: ""} · ${fields["dish_count"] ?: "0"} 道菜\n最近到店 ${fields["last_visit_date"] ?: "还未到店"}"
        RecordKind.DISH -> fields["review_excerpt"] ?: "还没有短评"
        RecordKind.VISIT -> "${fields["restaurant_name"] ?: "餐厅"} · ${mealLabel(fields["meal"])}\n${fields["note_excerpt"] ?: ""}"
        RecordKind.REVISION -> "${fields["entity_label"] ?: "记录"}\n${historySummary(record, null)}${fields["note"]?.let { "\n$it" } ?: ""}"
    }
    GridText(secondary, Modifier.testTag("record-summary:${record.ref.id}"), color = LocalQuietGridColors.current.secondary, style = MaterialTheme.typography.bodySmall)
    if (record.ref.kind == RecordKind.REVISION) {
        TextButton(onClick = { absolute = !absolute }, modifier = Modifier.testTag("history-time:${record.ref.id}")) {
            Text(if (absolute) "收起绝对时间" else "显示绝对时间")
        }
        if (absolute) GridText(record.updatedAt, Modifier.testTag("history-absolute:${record.ref.id}"), style = QuietGridText.date)
    }
}
@Composable fun ListFilterPanel(kind: RecordKind, filters: ListFilters, onChange: (ListFilters) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (kind == RecordKind.RESTAURANT) {
            GridText("类型", style = MaterialTheme.typography.titleMedium)
            ChoiceChips(listOf(null to "全部") + Category.entries.map { it.code to categoryLabel(it.code) }, filters.category, { onChange(filters.copy(category = it)) })
            FormField("filter-cuisine", filters.cuisine, { onChange(filters.copy(cuisine = it)) }, label = "菜系")
            GridText("到店状态", style = MaterialTheme.typography.titleMedium)
            ChoiceChips(listOf(null to "全部", "true" to "已到店", "false" to "未到店"), filters.visited?.toString(), { onChange(filters.copy(visited = it?.toBooleanStrictOrNull())) })
        }
        if (kind in setOf(RecordKind.RESTAURANT, RecordKind.DISH)) {
            ChoiceChips(listOf("updated_desc" to "最近更新", "rating_desc" to "评分高到低"), filters.sort, { onChange(filters.copy(sort = it ?: "updated_desc")) })
            Row { Checkbox(filters.unrated, { onChange(filters.copy(unrated = it, minRating = null, maxRating = null)) }, modifier = Modifier.testTag("filter-unrated")); GridText("只看未评分", Modifier.padding(top = 12.dp)) }
            Row { Checkbox(filters.minRating != null || filters.maxRating != null, { onChange(filters.copy(unrated = false, minRating = if (it) 10 else null, maxRating = if (it) 50 else null)) }, modifier = Modifier.testTag("filter-rating-range")); GridText("限定评分区间", Modifier.padding(top = 12.dp)) }
            if (filters.minRating != null || filters.maxRating != null) {
                GridText("最低评分"); RatingEditor((filters.minRating ?: 10).toString(), { onChange(filters.copy(minRating = it?.toInt() ?: 10)) }, true, "filter-min")
                GridText("最高评分"); RatingEditor((filters.maxRating ?: 50).toString(), { onChange(filters.copy(maxRating = it?.toInt() ?: 50)) }, true, "filter-max")
            }
        }
        if (kind in setOf(RecordKind.RESTAURANT, RecordKind.VISIT)) {
            FormField("filter-from", filters.from, { onChange(filters.copy(from = it)) }, label = "到店起始日期", supporting = "YYYY-MM-DD，可留空")
            FormField("filter-to", filters.to, { onChange(filters.copy(to = it)) }, label = "到店截止日期", supporting = "YYYY-MM-DD，可留空")
        }
        TextButton(onClick = { onChange(ListFilters()) }, modifier = Modifier.testTag("filter-clear")) { Text("清除筛选") }
    }
}
