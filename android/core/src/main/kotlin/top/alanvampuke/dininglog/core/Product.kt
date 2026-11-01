package top.alanvampuke.dininglog.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

@Serializable enum class RecordKind(val path: String) {
    RESTAURANT("restaurants"), DISH("dishes"), VISIT("visits"), REVISION("revisions")
}
@Serializable data class RecordRef(val kind: RecordKind, val id: String, val restaurantId: String? = null)
@Serializable enum class MatchType(val label: String) {
    RESTAURANT("餐厅匹配"), DISH("菜品匹配"), REVIEW("评价中含"), NOTE("备注中含")
}
@Serializable data class Change(val field: String, val before: String?, val after: String?)
// Lossless values; rating_tenths stays an integer string. API conversion belongs to data.
@Serializable data class RecordSnapshot(
    val ref: RecordRef, val version: Int, val fields: Map<String, String?>,
    val updatedAt: String, val deletedAt: String? = null, val diff: List<Change> = emptyList(),
) {
    val title: String get() = fields["name"] ?: fields["dining_date"] ?: fields["entity_label"] ?: "修改记录"
    val editable: Map<String, String?> get() = fields.filterKeys { it in editableFields(ref.kind) }
}
@Serializable data class RecordSummary(
    val ref: RecordRef, val title: String, val version: Int, val updatedAt: String,
    val fields: Map<String, String?> = emptyMap(), val matches: List<MatchType> = emptyList(),
    val changedFields: List<String> = emptyList(), val deleted: Boolean = false,
    val cachedDiff: List<Change> = emptyList(),
)
@Serializable enum class DeletedFilter { EXCLUDE, ONLY }
@Serializable data class ListFilters(
    val category: String? = null, val cuisine: String = "", val minRating: Int? = null,
    val maxRating: Int? = null, val unrated: Boolean = false, val visited: Boolean? = null,
    val from: String = "", val to: String = "", val sort: String = "updated_desc",
)
@Serializable data class ListQuery(
    val kind: RecordKind = RecordKind.RESTAURANT, val restaurantId: String? = null,
    val query: String = "", val filters: ListFilters = ListFilters(), val deleted: DeletedFilter = DeletedFilter.EXCLUDE,
    val historyEntityKind: RecordKind? = null, val historyEntityId: String? = null,
) {
    val exactVisitDate: String? get() = query.trim().takeIf { kind == RecordKind.VISIT && validDate(it) }
    val effectiveFilters: ListFilters get() = exactVisitDate?.let { day -> filters.copy(
        from = maxOf(day, filters.from), to = if (filters.to.isBlank()) day else minOf(day, filters.to),
    ) } ?: filters
    val fingerprint: String get() = listOf(kind.name, restaurantId, query.trim(), filters.category,
        filters.cuisine.trim(), filters.minRating, filters.maxRating, filters.unrated, filters.visited,
        filters.from, filters.to, filters.sort, deleted.name, historyEntityKind?.name, historyEntityId).joinToString("\u001f")
}
data class CachedPage(val items: List<RecordSummary> = emptyList(), val nextCursor: String? = null,
    val hasMore: Boolean = false, val fetchedAt: Long? = null, val localScope: Boolean = true)
data class CachedDetail(val snapshot: RecordSnapshot? = null, val fetchedAt: Long? = null, val stale: Boolean = false)
data class ListUiState(
    val items: List<RecordSummary> = emptyList(), val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false, val isOffline: Boolean = false, val error: String? = null,
    val query: ListQuery = ListQuery(), val nextCursor: String? = null, val hasMore: Boolean = false,
    val fetchedAt: Long? = null, val localScope: Boolean = true,
) {
    fun cached(page: CachedPage) = copy(items = deduplicate(page.items), nextCursor = page.nextCursor,
        hasMore = page.hasMore, fetchedAt = page.fetchedAt, localScope = page.localScope)
    fun loading(more: Boolean = false) = copy(isRefreshing = !more, isLoadingMore = more, error = null)
    fun failed(message: String, offline: Boolean) = copy(isRefreshing = false, isLoadingMore = false, isOffline = offline, error = message)
}
fun deduplicate(items: List<RecordSummary>): List<RecordSummary> = items.associateBy { it.ref.id }.values.toList()
fun appendPage(old: List<RecordSummary>, next: List<RecordSummary>) = deduplicate(old + next)

fun editableFields(kind: RecordKind): List<String> = when (kind) {
    RecordKind.RESTAURANT -> listOf("name", "category", "cuisine", "address", "rating_tenths", "review", "note")
    RecordKind.DISH -> listOf("name", "rating_tenths", "review")
    RecordKind.VISIT -> listOf("dining_date", "meal", "note")
    RecordKind.REVISION -> listOf("note")
}
fun fieldLabel(field: String): String = when (field) {
    "name" -> "名称"; "category" -> "类型"; "cuisine" -> "菜系"; "address" -> "地址"
    "rating_tenths" -> "评分"; "review" -> "评价"; "note" -> "备注"; "dining_date" -> "到店日期"
    "meal" -> "餐次"; "deleted_at" -> "删除时间"; else -> field
}
fun categoryLabel(code: String?) = when (code) {
    "restaurant" -> "餐厅"; "cafe" -> "咖啡馆"; "bakery" -> "烘焙店"; "bar" -> "酒吧"
    "street_food" -> "街头小吃"; "other" -> "其他"; else -> "未填写"
}
fun mealLabel(code: String?) = when (code) {
    "breakfast" -> "早餐"; "lunch" -> "午餐"; "dinner" -> "晚餐"; "other" -> "其他"; else -> "未填写"
}
fun displayValue(field: String, value: String?): String = when {
    value == null || value.isEmpty() -> if (field == "rating_tenths") "未评分" else "未填写"
    field == "rating_tenths" -> "★ ${Rating.fromTenths(value.toInt()).display}"
    field == "category" -> categoryLabel(value)
    field == "meal" -> mealLabel(value)
    else -> value
}
fun relativeTime(iso: String, now: Instant = Instant.now()): String {
    val seconds = runCatching { java.time.Duration.between(Instant.parse(iso), now).seconds.coerceAtLeast(0) }.getOrDefault(0)
    return when { seconds < 60 -> "刚刚"; seconds < 3600 -> "${seconds / 60} 分钟前"; seconds < 86400 -> "${seconds / 3600} 小时前"; else -> "${seconds / 86400} 天前" }
}
fun historySummary(summary: RecordSummary, detail: RecordSnapshot?): String {
    val score = (detail?.diff ?: summary.cachedDiff).firstOrNull { it.field == "rating_tenths" }
    val change = if (score != null) "评分 ${displayValue(score.field, score.before)} → ${displayValue(score.field, score.after)}"
        else when (summary.fields["action"]) {
            "create" -> "新增记录"; "delete" -> "删除记录"; "restore" -> "恢复记录"
            else -> "修改了 ${summary.changedFields.joinToString("、", transform = ::fieldLabel)}"
        }
    return "${relativeTime(summary.updatedAt)} · $change"
}
fun validDate(value: String) = runCatching {
    val date = LocalDate.parse(value)
    value == date.toString() && date in LocalDate.of(1900, 1, 1)..LocalDate.of(2100, 12, 31)
}.getOrDefault(false)
