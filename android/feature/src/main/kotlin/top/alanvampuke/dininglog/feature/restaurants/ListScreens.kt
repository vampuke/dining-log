package top.alanvampuke.dininglog.feature.restaurants

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.rotate
import top.alanvampuke.dininglog.feature.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.components.*
import top.alanvampuke.dininglog.feature.theme.*

@Composable fun RecordListScreen(state: ListUiState, searchText: String, filters: ListFilters,
    onSearch: (String) -> Unit, onFilters: (ListFilters) -> Unit, onRecord: (RecordSummary) -> Unit,
    onRefresh: () -> Unit, onMore: () -> Unit, onAdd: (() -> Unit)? = null, onTrash: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null, titleOverride: String? = null,
    bottomBar: @Composable () -> Unit = {}, listState: LazyListState = rememberLazyListState()) {
    val kind = state.query.kind
    val isSearch = searchText.isNotBlank()
    val title = titleOverride ?: if (state.query.deleted == DeletedFilter.ONLY) if (kind == RecordKind.REVISION) "隐藏的修改历史" else "回收站" else when (kind) {
        RecordKind.RESTAURANT -> if (isSearch) "搜索结果" else "我的餐厅"
        RecordKind.DISH -> "店内菜品"; RecordKind.VISIT -> "到店时间线"; RecordKind.REVISION -> "修改历史"
    }
    val tag = if (isSearch && kind == RecordKind.RESTAURANT) "screen:search" else "screen:${kind.name.lowercase()}:list"
    var expandedFilters by rememberSaveable(kind) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    ScreenFrame(tag, bottomBar, floatingBottomBar = true) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(listViewportPadding(padding)).testTag("record-list"), state = listState,
            contentPadding = PaddingValues(start = QuietGridDimensions.edge, top = 8.dp, end = QuietGridDimensions.edge, bottom = 8.dp + padding.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                if (kind == RecordKind.RESTAURANT && onBack == null && titleOverride == null && state.query.deleted != DeletedFilter.ONLY) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Image(painterResource(if (LocalQuietGridColors.current == QuietGridDark) R.drawable.bite_v1_burger_dark else R.drawable.bite_v1_burger), null, Modifier.size(38.dp).rotate(-3f))
                        Column(Modifier.weight(1f)) {
                            GridText(if (LocalDensity.current.fontScale > 1.3f) "食记" else "BITE! 食记", Modifier.fillMaxWidth().testTag("page-title"), style = MaterialTheme.typography.headlineSmall)
                            GridText(title, Modifier.fillMaxWidth(), style = QuietGridText.caption, color = LocalQuietGridColors.current.secondary)
                        }
                        if (onAdd != null) BiteButton(onAdd, Modifier.testTag("add")) { Text("记一笔") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { PageHeader(title, onBack) }
                        if (onAdd != null) BiteButton(onAdd, Modifier.testTag("add")) {
                            Text(if (kind == RecordKind.DISH) "新增菜品" else if (kind == RecordKind.VISIT) "新增到店" else "新增")
                        }
                    }
                }
            }
            if (kind == RecordKind.RESTAURANT && onBack == null && titleOverride == null && !isSearch && state.query.deleted != DeletedFilter.ONLY) item { LoadedStats(state.items) }
            item {
                BiteTextField(searchText, onSearch, Modifier.fillMaxWidth().testTag("search"), label = { GridText(when (kind) {
                    RecordKind.RESTAURANT -> "搜索店名、菜品或评价"
                    RecordKind.DISH -> "搜索本店菜品与短评"; RecordKind.VISIT -> "搜索餐厅、日期、备注"; RecordKind.REVISION -> "搜索修改与说明"
                }) }, accessibleLabel = "搜索", textStyle = QuietGridText.input, singleLine = true,
                    leadingIcon = { Icon(QuietGridIcons.search, null, tint = LocalQuietGridColors.current.ink) })
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (kind in setOf(RecordKind.RESTAURANT, RecordKind.DISH)) Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("全部" to ListFilters(), "封神必吃" to filters.copy(minRating = 46, maxRating = null, unrated = false),
                            "踩雷预警" to filters.copy(minRating = null, maxRating = 31, unrated = false)).forEach { (label, next) ->
                            FilterChip(selected = filters == next, onClick = { onFilters(next) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.heightIn(min = 48.dp), border = androidx.compose.foundation.BorderStroke(1.5.dp, LocalQuietGridColors.current.border),
                                colors = FilterChipDefaults.filterChipColors(containerColor = LocalQuietGridColors.current.surface,
                                    labelColor = LocalQuietGridColors.current.ink, selectedContainerColor = LocalQuietGridColors.current.accentSoft,
                                    selectedLabelColor = LocalQuietGridColors.current.accentInk))
                        }
                    } else Spacer(Modifier.weight(1f))
                    if (kind != RecordKind.REVISION) IconButton(onClick = { expandedFilters = !expandedFilters }, modifier = Modifier.testTag("filters")) {
                        Icon(QuietGridIcons.filter, contentDescription = if (expandedFilters) "收起筛选" else "筛选与排序",
                            tint = if (expandedFilters || filters != ListFilters()) LocalQuietGridColors.current.accentInk else LocalQuietGridColors.current.secondary)
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("list-more")) {
                            Icon(QuietGridIcons.more, contentDescription = "更多操作", tint = LocalQuietGridColors.current.secondary)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(if (state.isRefreshing) "正在刷新…" else "刷新") },
                                onClick = { menu = false; onRefresh() }, enabled = !state.isRefreshing, modifier = Modifier.testTag("refresh"))
                            if (onTrash != null) DropdownMenuItem(text = { Text(if (kind == RecordKind.REVISION) "管理隐藏历史" else "回收站") },
                                onClick = { menu = false; onTrash() }, modifier = Modifier.testTag("trash"))
                        }
                    }
                }
            }
            if (expandedFilters) item { ListFilterPanel(kind, filters, onFilters) }
            if (state.isOffline || (state.localScope && isSearch) || state.error != null) item {
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
            items(state.items, key = { it.ref.id }) { record -> RecordRow(record, onRecord, tilt = if (state.items.indexOf(record) % 2 == 0) -1f else 1f) }
            if (state.hasMore) item { BiteButton(onClick = onMore, enabled = !state.isLoadingMore && !state.isRefreshing,
                modifier = Modifier.fillMaxWidth().testTag("load-more")) { Text(if (state.isLoadingMore) "正在加载更多…" else "加载更多") } }
        }
    }
}
@Composable fun LoadedStats(records: List<RecordSummary>) {
    val c = LocalQuietGridColors.current
    val rated = records.mapNotNull { it.fields["rating_tenths"]?.toIntOrNull() }
    val counts = records.map { it.fields["dish_count"]?.toIntOrNull() }
    val stats = listOf("已加载" to "${records.size} 家", "已加载均分" to
        (if (rated.isEmpty()) "未评分" else String.format(java.util.Locale.ROOT, "%.1f", rated.average() / 10)),
        "已加载菜品" to (if (counts.any { it == null }) "未提供" else "${counts.filterNotNull().sum()} 道"))
    Row(Modifier.fillMaxWidth().brutalBox(c.surface, c.border, c.shadow, 10.dp).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        stats.forEachIndexed { index, (label, value) ->
            if (index > 0) Box(Modifier.width(2.dp).height(28.dp).background(c.border))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                GridText(label, style = QuietGridText.caption, color = c.secondary)
                GridText(value, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
@Composable fun RecordRow(record: RecordSummary, onRecord: (RecordSummary) -> Unit, tilt: Float = 0f, number: Int? = null) {
    val c = LocalQuietGridColors.current
    val large = LocalDensity.current.fontScale > 1.3f
    val history = record.ref.kind == RecordKind.REVISION
    val outside = if (history) Modifier.revisionRail(c.border).padding(start = 16.dp) else Modifier
    Column(Modifier.fillMaxWidth().then(outside).rotate(tilt).brutalBox(c.surface, c.border, c.shadow)
        .heightIn(min = 48.dp).clickable { onRecord(record) }.testTag("record:${record.ref.id}").padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (large) {
            if (number != null) Sticker("%02d".format(number), c.butter)
            RowText(record)
            if (record.ref.kind in setOf(RecordKind.RESTAURANT, RecordKind.DISH)) {
                Score(record.fields["rating_tenths"]); VerdictSticker(record.fields["rating_tenths"], record.ref.kind == RecordKind.DISH)
            }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            if (number != null) Sticker("%02d".format(number), c.butter)
            Column(Modifier.weight(1f)) { RowText(record) }
            if (record.ref.kind in setOf(RecordKind.RESTAURANT, RecordKind.DISH)) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Score(record.fields["rating_tenths"])
                VerdictSticker(record.fields["rating_tenths"], record.ref.kind == RecordKind.DISH)
            }
        }
        if (record.matches.isNotEmpty() && record.ref.kind == RecordKind.RESTAURANT) GridText(record.matches.joinToString(" · ") { it.label }, Modifier.testTag("matches:${record.ref.id}"), style = MaterialTheme.typography.bodySmall, color = c.accentInk)
    }
}
@Composable private fun RowText(record: RecordSummary) {
    var absolute by remember(record.ref.id) { mutableStateOf(false) }
    val large = LocalDensity.current.fontScale > 1.3f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        GridText(record.title, Modifier.weight(1f).testTag("title:${record.ref.id}"), style = MaterialTheme.typography.titleMedium,
            maxLines = if (large) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
        if (record.ref.kind == RecordKind.RESTAURANT && record.version > 1)
            GridText("已修订", Modifier.revisedStamp(LocalQuietGridColors.current.danger).padding(3.dp),
                style = QuietGridText.caption, color = LocalQuietGridColors.current.danger)
    }
    val fields = record.fields
    val secondary = when (record.ref.kind) {
        RecordKind.RESTAURANT -> listOf(
            listOfNotNull(categoryLabel(fields["category"]), fields["cuisine"]?.takeIf { it.isNotBlank() },
                fields["dish_count"]?.let { "$it 道菜" }).joinToString(" · "),
            listOfNotNull(fields["address_excerpt"]?.takeIf { it.isNotBlank() },
                fields["last_visit_date"]?.takeIf { it.isNotBlank() }?.let { "到店 $it" } ?: record.updatedAt.takeIf { it.isNotBlank() }?.let { "更新 ${it.take(10)}" }).joinToString(" · ")
        ).joinToString("\n")
        RecordKind.DISH -> fields["review_excerpt"]?.takeIf { it.isNotBlank() } ?: ""
        RecordKind.VISIT -> listOfNotNull(
            listOfNotNull(fields["restaurant_name"] ?: "餐厅", fields["meal"]?.let(::mealLabel)).joinToString(" · "),
            fields["note_excerpt"]?.takeIf { it.isNotBlank() }).joinToString("\n")
        RecordKind.REVISION -> listOfNotNull(fields["entity_label"] ?: "记录", historySummary(record, null),
            fields["note"]?.takeIf { it.isNotBlank() }).joinToString("\n")
    }
    if (secondary.isNotEmpty()) GridText(secondary, Modifier.testTag("record-summary:${record.ref.id}"), color = LocalQuietGridColors.current.secondary,
        style = QuietGridText.metadata, maxLines = if (large) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
    if (record.ref.kind == RecordKind.REVISION) {
        BiteTextButton(onClick = { absolute = !absolute }, modifier = Modifier.testTag("history-time:${record.ref.id}")) {
            Text(if (absolute) "收起绝对时间" else "显示绝对时间")
        }
        if (absolute) GridText(formatAbsoluteTime(record.updatedAt), Modifier.testTag("history-absolute:${record.ref.id}"), style = QuietGridText.date)
    }
}
@Composable fun ListFilterPanel(kind: RecordKind, filters: ListFilters, onChange: (ListFilters) -> Unit) {
    Column(Modifier.fillMaxWidth().brutalBox(LocalQuietGridColors.current.paper, LocalQuietGridColors.current.border, LocalQuietGridColors.current.shadow).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        BiteTextButton(onClick = { onChange(ListFilters()) }, modifier = Modifier.testTag("filter-clear")) { Text("清除筛选") }
    }
}
