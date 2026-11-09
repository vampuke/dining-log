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
import androidx.compose.foundation.lazy.LazyListScope
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
    val inkPaper = LocalThemeStyle.current.isInkPaper
    val title = titleOverride ?: if (state.query.deleted == DeletedFilter.ONLY) if (kind == RecordKind.REVISION) "隐藏的修改历史" else "回收站" else when (kind) {
        RecordKind.RESTAURANT -> if (isSearch) "搜索结果" else "我的餐厅"
        RecordKind.DISH -> "店内菜品"; RecordKind.VISIT -> "到店时间线"; RecordKind.REVISION -> "修改历史"
    }
    val tag = if (isSearch && kind == RecordKind.RESTAURANT) "screen:search" else "screen:${kind.name.lowercase()}:list"
    var expandedFilters by rememberSaveable(kind) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // Ink & Paper: one header switch drives every history row's time format,
    // like the design preview's timeToggle. Classic keeps per-row toggling.
    var historyAbsolute by rememberSaveable { mutableStateOf(false) }
    // Ink & Paper puts the add action in a full-width button above the bottom
    // bar (design .fab-wrap); classic keeps it in the header.
    val bottomAdd = inkPaper && onAdd != null
    val listContent: LazyListScope.() -> Unit = {
        item {
            if (kind == RecordKind.RESTAURANT && onBack == null && titleOverride == null && state.query.deleted != DeletedFilter.ONLY) {
                ResponsiveHeading(title = { modifier ->
                    // Ink & Paper drops the tilted burger and the "BITE!" wordmark:
                    // just the section name with a small loaded count.
                    if (inkPaper) Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GridText(title, Modifier.weight(1f).testTag("page-title"), style = MaterialTheme.typography.headlineSmall)
                        GridText("${state.items.size} 家", style = QuietGridText.caption, color = LocalQuietGridColors.current.secondary)
                    } else Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Image(painterResource(if (LocalQuietGridColors.current == QuietGridDark) R.drawable.bite_v1_burger_dark else R.drawable.bite_v1_burger), null, Modifier.size(38.dp).rotate(-3f))
                        Column(Modifier.weight(1f)) {
                            GridText(if (LocalDensity.current.fontScale > 1.3f) "食记" else "BITE! 食记", Modifier.fillMaxWidth().testTag("page-title"), style = MaterialTheme.typography.headlineSmall)
                            GridText(title, Modifier.fillMaxWidth(), style = QuietGridText.caption, color = LocalQuietGridColors.current.secondary)
                        }
                    }
                }, trailing = if (bottomAdd) null else onAdd?.let { callback -> { modifier ->
                    BiteButton(callback, modifier.testTag("add")) { Text("新增餐厅") }
                } })
            } else {
                PageHeader(title, onBack, trailing = if (kind == RecordKind.REVISION && inkPaper) { { modifier ->
                    BiteTextButton(onClick = { historyAbsolute = !historyAbsolute }, modifier = modifier.testTag("history-time-toggle")) {
                        Text(if (historyAbsolute) "相对时间" else "绝对时间", style = QuietGridText.caption)
                    }
                } } else if (bottomAdd) null else onAdd?.let { callback -> { modifier ->
                    BiteButton(callback, modifier.testTag("add")) {
                        Text(if (kind == RecordKind.DISH) "新增菜品" else if (kind == RecordKind.VISIT) "新增到店" else "新增")
                    }
                } })
            }
        }
        if (kind == RecordKind.RESTAURANT && onBack == null && titleOverride == null && !isSearch && state.query.deleted != DeletedFilter.ONLY) item { LoadedStats(state.items) }
        item {
            BiteTextField(searchText, onSearch, Modifier.fillMaxWidth().testTag("search"), label = { GridText(when (kind) {
                RecordKind.RESTAURANT -> "搜索店名、菜品或评价"
                RecordKind.DISH -> "搜索本店菜品与短评"; RecordKind.VISIT -> "搜索餐厅、日期、备注"; RecordKind.REVISION -> "搜索修改与说明"
            }) }, placeholder = { GridText(when (kind) {
                RecordKind.RESTAURANT -> "例如：兰巴赫"
                RecordKind.DISH -> "例如：披萨"; RecordKind.VISIT -> "例如：2026-11-01"; RecordKind.REVISION -> "例如：评分"
            }) }, accessibleLabel = "搜索", textStyle = QuietGridText.input, singleLine = true,
                leadingIcon = { Icon(QuietGridIcons.search, null, tint = LocalQuietGridColors.current.ink) })
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (kind in setOf(RecordKind.RESTAURANT, RecordKind.DISH)) Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    // Thresholds were invisible: the top tier is minRating 46
                    // and the low tier maxRating 31, and the dish verdicts are
                    // "惊艳"/"踩雷" rather than the restaurant ones. Classic
                    // keeps the threshold on the chip itself; Ink & Paper
                    // shows the bare label and leaves the thresholds in the
                    // filter panel ("评分档位" line below).
                    val topLabel = (if (kind == RecordKind.DISH) "惊艳" else "封神必吃") + if (inkPaper) "" else " ★4.6+"
                    val lowLabel = (if (kind == RecordKind.DISH) "踩雷" else "踩雷预警") + if (inkPaper) "" else " ★3.1−"
                    // Ink & Paper uses the design's four segments; the low tier
                    // (maxRating 31) moves into the filter panel so it stays
                    // reachable. Classic keeps the three threshold chips.
                    val quick = if (inkPaper) listOf("全部" to ListFilters(),
                        topLabel to filters.copy(minRating = 46, maxRating = null, unrated = false),
                        "4 分以上" to filters.copy(minRating = 40, maxRating = null, unrated = false),
                        "未评分" to filters.copy(minRating = null, maxRating = null, unrated = true))
                    else listOf("全部" to ListFilters(), topLabel to filters.copy(minRating = 46, maxRating = null, unrated = false),
                        lowLabel to filters.copy(minRating = null, maxRating = 31, unrated = false))
                    if (inkPaper) quick.forEach { (label, next) -> SegmentItem(label, filters == next, true) { onFilters(next) } }
                    else quick.forEach { (label, next) ->
                        FilterChip(selected = filters == next, onClick = { onFilters(next) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.heightIn(min = 48.dp), border = androidx.compose.foundation.BorderStroke(1.5.dp, LocalQuietGridColors.current.border),
                            colors = FilterChipDefaults.filterChipColors(containerColor = LocalQuietGridColors.current.surface,
                                labelColor = LocalQuietGridColors.current.ink, selectedContainerColor = LocalQuietGridColors.current.accentSoft,
                                selectedLabelColor = LocalQuietGridColors.current.accentInk))
                    }
                } else Spacer(Modifier.weight(1f))
                // Ink & Paper uses the design's "筛选" text entry; classic
                // keeps the funnel icon. The ⋮ menu (refresh / trash) stays.
                if (kind != RecordKind.REVISION) if (inkPaper) {
                    BiteTextButton(onClick = { expandedFilters = !expandedFilters }, modifier = Modifier.testTag("filters")) {
                        Text(if (expandedFilters) "收起筛选" else "筛选", style = QuietGridText.caption)
                    }
                } else IconButton(onClick = { expandedFilters = !expandedFilters }, modifier = Modifier.testTag("filters")) {
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
        items(state.items, key = { it.ref.id }) { record -> RecordRow(record, onRecord, tilt = if (state.items.indexOf(record) % 2 == 0) -1f else 1f,
            historyAbsolute = if (kind == RecordKind.REVISION && inkPaper) historyAbsolute else null) }
        if (state.hasMore) item { BiteButton(onClick = onMore, enabled = !state.isLoadingMore && !state.isRefreshing,
            modifier = Modifier.fillMaxWidth().testTag("load-more")) { Text(if (state.isLoadingMore) "正在加载更多…" else "加载更多") } }
    }
    ScreenFrame(tag, bottomBar, floatingBottomBar = true) { padding ->
        if (inkPaper && onAdd != null) {
            // The design pins the add action above the bottom bar; the list
            // scrolls in the remaining viewport above it.
            Column(Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("record-list"), state = listState,
                    contentPadding = PaddingValues(start = LocalGridDimensions.current.edge, top = 8.dp, end = LocalGridDimensions.current.edge, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp), content = listContent)
                BiteButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = LocalGridDimensions.current.edge, vertical = 8.dp).testTag("add")) {
                    Text(when (kind) { RecordKind.DISH -> "新增菜品"; RecordKind.VISIT -> "新增到店"; RecordKind.REVISION -> "新增"; else -> "新增餐厅" })
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(listViewportPadding(padding)).testTag("record-list"), state = listState,
                contentPadding = PaddingValues(start = LocalGridDimensions.current.edge, top = 8.dp, end = LocalGridDimensions.current.edge, bottom = 8.dp + padding.calculateBottomPadding()),
                verticalArrangement = if (inkPaper) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy(7.dp), content = listContent)
        }
    }
}
@Composable fun LoadedStats(records: List<RecordSummary>) {
    val c = LocalQuietGridColors.current
    val rated = records.mapNotNull { it.fields["rating_tenths"]?.toIntOrNull() }
    val counts = records.map { it.fields["dish_count"]?.toIntOrNull() }
    val avg = if (rated.isEmpty()) "未评分" else String.format(java.util.Locale.ROOT, "%.1f", rated.average() / 10)
    val dishes = if (counts.any { it == null }) "未提供" else "${counts.filterNotNull().sum()} 道"
    // Ink & Paper: one quiet line instead of the three-cell stats box.
    // These aggregates cover only the loaded results, including any filters.
    if (LocalThemeStyle.current.isInkPaper) {
        GridText("${records.size} 家 · 均分 $avg · 菜品 $dishes", Modifier.fillMaxWidth().testTag("loaded-stats"),
            style = QuietGridText.metadata, color = c.secondary)
        return
    }
    val stats = listOf("已加载餐厅" to "${records.size} 家", "已加载均分" to avg, "已加载菜品" to dishes)
    Row(Modifier.fillMaxWidth().testTag("loaded-stats").brutalBox(c.surface, c.border, c.shadow, 10.dp).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        stats.forEachIndexed { index, (label, value) ->
            if (index > 0) Box(Modifier.width(2.dp).height(28.dp).background(c.border))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                GridText(label, style = QuietGridText.caption, color = c.secondary)
                GridText(value, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
@Composable fun RecordRow(record: RecordSummary, onRecord: (RecordSummary) -> Unit, tilt: Float = 0f, number: Int? = null,
    historyAbsolute: Boolean? = null) {
    val c = LocalQuietGridColors.current
    val large = LocalDensity.current.fontScale > 1.3f
    val history = record.ref.kind == RecordKind.REVISION
    val outside = if (history) Modifier.revisionRail(c.border).padding(start = 16.dp) else Modifier
    val body: @Composable () -> Unit = {
        if (large) {
            if (number != null) Sticker("%02d".format(number), c.butter)
            RowText(record, historyAbsolute)
            if (record.ref.kind in setOf(RecordKind.RESTAURANT, RecordKind.DISH)) {
                Score(record.fields["rating_tenths"]); VerdictSticker(record.fields["rating_tenths"], record.ref.kind == RecordKind.DISH)
            }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            if (number != null) Sticker("%02d".format(number), c.butter)
            Column(Modifier.weight(1f)) { RowText(record, historyAbsolute) }
            if (record.ref.kind in setOf(RecordKind.RESTAURANT, RecordKind.DISH)) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Score(record.fields["rating_tenths"])
                VerdictSticker(record.fields["rating_tenths"], record.ref.kind == RecordKind.DISH)
            }
        }
        if (record.matches.isNotEmpty() && record.ref.kind == RecordKind.RESTAURANT) GridText(record.matches.joinToString(" · ") { it.label }, Modifier.testTag("matches:${record.ref.id}"), style = MaterialTheme.typography.bodySmall, color = c.accentInk)
    }
    // Ink & Paper: a flat row with a 1px divider — no tilt, no bordered card.
    if (LocalThemeStyle.current.isInkPaper) {
        Column(Modifier.fillMaxWidth().then(outside).clickable { onRecord(record) }.testTag("record:${record.ref.id}").padding(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            body()
            HorizontalDivider(color = c.border, thickness = 1.dp)
        }
        return
    }
    Column(Modifier.fillMaxWidth().then(outside).rotate(tilt).brutalBox(c.surface, c.border, c.shadow)
        .heightIn(min = 48.dp).clickable { onRecord(record) }.testTag("record:${record.ref.id}").padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        body()
    }
}
@Composable private fun RowText(record: RecordSummary, historyAbsolute: Boolean? = null) {
    // Ink & Paper: the list header switch drives every row (historyAbsolute
    // != null); classic keeps the per-row toggle on the time text itself.
    var localAbsolute by rememberSaveable(record.ref.id) { mutableStateOf(false) }
    val absolute = historyAbsolute ?: localAbsolute
    val large = LocalDensity.current.fontScale > 1.3f
    val isHistoryInk = record.ref.kind == RecordKind.REVISION && LocalThemeStyle.current.isInkPaper
    // Ink & Paper history rows lead with the time, like the design preview:
    // time on top, change below, source line under it. Classic keeps the
    // time at the bottom of the row.
    if (isHistoryInk) HistoryTimeText(record.updatedAt, absolute, null,
        Modifier.testTag("history-time:${record.ref.id}"),
        if (absolute) Modifier.testTag("history-absolute:${record.ref.id}")
        else Modifier.testTag("history-relative:${record.ref.id}"))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        GridText(record.title, Modifier.weight(1f).testTag("title:${record.ref.id}"),
            style = if (LocalThemeStyle.current.isInkPaper) InkPaperText.recordTitle else MaterialTheme.typography.titleMedium,
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
                fields["last_visit_date"]?.takeIf { it.isNotBlank() }?.let { "到店 $it" } ?: record.updatedAt.takeIf { it.isNotBlank() }?.let { "更新 ${formatAbsoluteTime(it).take(10)}" }).joinToString(" · ")
        ).joinToString("\n")
        RecordKind.DISH -> fields["review_excerpt"]?.takeIf { it.isNotBlank() } ?: ""
        RecordKind.VISIT -> listOfNotNull(
            listOfNotNull(fields["restaurant_name"] ?: "餐厅", fields["meal"]?.let(::mealLabel)).joinToString(" · "),
            fields["note_excerpt"]?.takeIf { it.isNotBlank() }).joinToString("\n")
        // The entity label used to repeat the title verbatim ("披萨 / 披萨",
        // "2026-11-01 / 2026-11-01"). Keep it only when it adds context, i.e.
        // when it differs from the title; otherwise the summary line is enough.
        RecordKind.REVISION -> listOfNotNull(fields["entity_label"]?.takeIf { it != record.title } ?: if (fields["entity_label"] == null) "记录" else null, historySummary(record, null, includeTime = false),
            fields["note"]?.takeIf { it.isNotBlank() }).joinToString("\n")
    }
    if (secondary.isNotEmpty()) GridText(secondary, Modifier.testTag("record-summary:${record.ref.id}"), color = LocalQuietGridColors.current.secondary,
        style = QuietGridText.metadata, maxLines = if (large) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
    // Source line, design "老街面馆 · 菜品评分" style. The backend returns
    // restaurant_name on revision summaries; skip it when it only repeats
    // the title (a restaurant's own revision), where it adds nothing.
    if (isHistoryInk) record.fields["restaurant_name"]?.takeIf { it.isNotBlank() && it != record.title }?.let { where ->
        val kindLabel = when (record.fields["entity_type"]) { "dish" -> "菜品"; "restaurant" -> "餐厅"; "visit" -> "到店"; else -> "" }
        GridText(listOfNotNull(where, kindLabel.takeIf { it.isNotEmpty() }).joinToString(" · "),
            Modifier.fillMaxWidth(), color = LocalQuietGridColors.current.secondary, style = QuietGridText.metadata)
    }
    if (record.ref.kind == RecordKind.REVISION && !isHistoryInk) {
        HistoryTimeText(record.updatedAt, absolute, if (historyAbsolute == null) ({ localAbsolute = !localAbsolute }) else null,
            Modifier.testTag("history-time:${record.ref.id}"),
            if (absolute) Modifier.testTag("history-absolute:${record.ref.id}")
            else Modifier.testTag("history-relative:${record.ref.id}"))
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
            GridText(if (kind == RecordKind.DISH) "评分档位：★4.6+ 为惊艳，★3.1− 为踩雷" else "评分档位：★4.6+ 为封神必吃，★3.1− 为踩雷预警",
                style = QuietGridText.caption, color = LocalQuietGridColors.current.secondary)
            ChoiceChips(listOf("updated_desc" to "最近更新", "rating_desc" to "评分高到低"), filters.sort, { onChange(filters.copy(sort = it ?: "updated_desc")) })
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val unrated: @Composable (Modifier) -> Unit = { modifier ->
                    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(filters.unrated, { onChange(filters.copy(unrated = it, minRating = null, maxRating = null)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("filter-unrated"))
                        GridText("只看未评分", Modifier.weight(1f))
                    }
                }
                val range: @Composable (Modifier) -> Unit = { modifier ->
                    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(filters.minRating != null || filters.maxRating != null, { onChange(filters.copy(unrated = false, minRating = if (it) 10 else null, maxRating = if (it) 50 else null)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("filter-rating-range"))
                        GridText("限定评分区间", Modifier.weight(1f))
                    }
                }
                if (LocalDensity.current.fontScale <= 1.3f && maxWidth >= 320.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    unrated(Modifier.weight(1f)); range(Modifier.weight(1f))
                } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    unrated(Modifier.fillMaxWidth()); range(Modifier.fillMaxWidth())
                }
            }
            // Ink & Paper: the low tier left the quick segments for the panel,
            // so it needs a home here. Classic keeps the chip on the row.
            if (LocalThemeStyle.current.isInkPaper) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(filters.maxRating == 31 && filters.minRating == null,
                    { onChange(filters.copy(unrated = false, minRating = null, maxRating = if (it) 31 else null)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("filter-low-tier"))
                GridText(if (kind == RecordKind.DISH) "只看踩雷" else "只看踩雷预警", Modifier.weight(1f))
            }
            if (filters.minRating != null || filters.maxRating != null) BoxWithConstraints(Modifier.fillMaxWidth()) {
                val minimum: @Composable () -> Unit = {
                    RatingEditor((filters.minRating ?: 10).toString(), { onChange(filters.copy(minRating = it?.toInt() ?: 10)) }, true, "filter-min", framed = false, label = "最低评分")
                }
                val maximum: @Composable () -> Unit = {
                    RatingEditor((filters.maxRating ?: 50).toString(), { onChange(filters.copy(maxRating = it?.toInt() ?: 50)) }, true, "filter-max", framed = false, label = "最高评分")
                }
                if (LocalDensity.current.fontScale <= 1.3f && maxWidth >= 492.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) { minimum() }
                    Column(Modifier.weight(1f)) { maximum() }
                } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    minimum(); maximum()
                }
            }
        }
        if (kind in setOf(RecordKind.RESTAURANT, RecordKind.VISIT)) {
            FormField("filter-from", filters.from, { onChange(filters.copy(from = it)) }, label = "到店起始日期", supporting = "YYYY-MM-DD，可留空")
            FormField("filter-to", filters.to, { onChange(filters.copy(to = it)) }, label = "到店截止日期", supporting = "YYYY-MM-DD，可留空")
        }
        BiteTextButton(onClick = { onChange(ListFilters()) }, modifier = Modifier.testTag("filter-clear")) { Text("清除筛选") }
    }
}
