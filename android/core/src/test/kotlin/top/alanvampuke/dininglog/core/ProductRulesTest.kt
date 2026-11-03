package top.alanvampuke.dininglog.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProductRulesTest {
    private val ref = RecordRef(RecordKind.RESTAURANT, "restaurant-1")
    private fun snapshot(version: Int = 1, review: String? = "旧评价", rating: String? = "40", note: String? = null) =
        RecordSnapshot(ref, version, mapOf("name" to "兰记", "category" to "restaurant", "address" to "愚园路", "review" to review, "rating_tenths" to rating, "note" to note), "2026-11-01T00:00:00Z")
    private fun input() = SaveInput("A", ref, WriteAction.UPDATE, snapshot(), snapshot().editable + ("rating_tenths" to "43"))
    private fun summary(id: String = "restaurant-1", title: String = "兰记") = RecordSummary(ref.copy(id = id), title, 1, "2026-11-01T00:00:00Z")

    @Test fun refreshingAndFailureRetainItemsAndPaginationDeduplicatesById() {
        val state = ListUiState(items = listOf(summary()))
        assertEquals(state.items, state.loading().items)
        assertEquals(state.items, state.loading(true).items)
        val failed = state.loading().failed("离线", true)
        assertEquals(state.items, failed.items); assertTrue(failed.isOffline); assertFalse(failed.isRefreshing)
        val appended = appendPage(state.items, listOf(summary(title = "兰记新名"), summary("2")))
        assertEquals(listOf("restaurant-1", "2"), appended.map { it.ref.id }); assertEquals("兰记新名", appended.first().title)
    }
    @Test fun independentFieldsSuggestMergeButRequireConfirmation() {
        val pending = input()
        val comparison = compareConflict(pending, snapshot(2, review = "服务器评价"))
        assertEquals(pending.baseSnapshot, comparison.baseSnapshot); assertEquals("43", comparison.localInput.localFields["rating_tenths"])
        assertTrue(comparison.mergeAllowed); assertTrue(comparison.needsChoice.isEmpty())
        val merged = comparison.confirmed(emptyMap())
        assertEquals(2, merged.baseVersion); assertEquals("服务器评价", merged.localFields["review"])
        assertEquals("43", merged.localFields["rating_tenths"])
    }
    @Test fun sameFieldMustBeChosenAndDeleteOrDeletedParentCannotBeMerged() {
        val conflict = compareConflict(input(), snapshot(2, rating = "46"))
        assertEquals(setOf("rating_tenths"), conflict.needsChoice)
        assertFailsWith<IllegalStateException> { conflict.confirmed(emptyMap()) }
        assertEquals("46", conflict.confirmed(mapOf("rating_tenths" to ConflictChoice.SERVER)).localFields["rating_tenths"])
        assertEquals("43", conflict.confirmed(mapOf("rating_tenths" to ConflictChoice.LOCAL)).localFields["rating_tenths"])
        assertFalse(compareConflict(input().copy(action = WriteAction.DELETE), snapshot(2)).mergeAllowed)
        assertFalse(compareConflict(input(), snapshot(2).copy(deletedAt = "2026-11-01T01:00:00Z")).mergeAllowed)
    }
    @Test fun cachedSearchIncludesDishReviewAndRestaurantNoteUnderTheParentRestaurant() {
        val restaurant = summary().copy(fields = mapOf("cuisine" to "川菜", "visit_count" to "2", "last_visit_date" to "2026-11-01"))
        val dishRef = RecordRef(RecordKind.DISH, "dish-1", ref.id)
        val dish = RecordSummary(dishRef, "辣子鸡", 1, restaurant.updatedAt, mapOf("review_excerpt" to "花椒香"))
        val full = snapshot(note = "下次再来")
        for ((text, type) in listOf("兰记" to MatchType.RESTAURANT, "川菜" to MatchType.RESTAURANT, "辣子鸡" to MatchType.DISH, "花椒香" to MatchType.REVIEW, "下次再来" to MatchType.NOTE)) {
            val results = localSearch(ListQuery(query = text), listOf(restaurant, dish), listOf(full))
            assertEquals(listOf(ref.id), results.map { it.ref.id }); assertTrue(type in results.single().matches)
        }
        assertTrue(localSearch(ListQuery(query = "未缓存的远端记录"), listOf(restaurant, dish), listOf(full)).isEmpty())
        assertEquals(listOf(ref.id), localSearch(ListQuery(filters = ListFilters(from = "2026-11-01", to = "2026-11-01")), listOf(restaurant), listOf(full)).map { it.ref.id })
    }
    @Test fun ratingNullDateAndHistoryValuesRemainExact() {
        assertEquals("★ 4.3", displayValue("rating_tenths", "43")); assertEquals("未评分", displayValue("rating_tenths", null))
        assertTrue(validDate("2024-02-29")); assertFalse(validDate("2023-02-29")); assertFalse(validDate("2101-01-01"))
        val detail = snapshot().copy(diff = listOf(Change("rating_tenths", "40", "43")))
        assertTrue(historySummary(summary().copy(changedFields = listOf("rating_tenths")), detail).contains("★ 4.0 → ★ 4.3"))
        assertTrue(historySummary(summary().copy(cachedDiff = detail.diff), null).contains("★ 4.0 → ★ 4.3"))
    }
    @Test fun bothRestaurantAndDishRatingFiltersIncludeExactChipBoundariesAndExcludeNull() {
        // queries.ts ratingWhere uses >= min_rating*10 and <= max_rating*10
        // for both kinds. These expected sets also apply to cached searches.
        for (kind in listOf(RecordKind.RESTAURANT, RecordKind.DISH)) {
            val rows = listOf(31, 32, 45, 46, null).map { rating ->
                RecordSummary(RecordRef(kind, "rating-${rating ?: "null"}", if (kind == RecordKind.DISH) ref.id else null),
                    "边界 $rating", 1, "2026-11-01T00:00:00Z", mapOf("rating_tenths" to rating?.toString()))
            }
            for (withDetails in listOf(false, true)) {
                val details = if (withDetails) rows.map { RecordSnapshot(it.ref, it.version, it.fields, it.updatedAt) } else emptyList()
                val top = localSearch(ListQuery(kind, filters = ListFilters(minRating = 46)), rows, details)
                val low = localSearch(ListQuery(kind, filters = ListFilters(maxRating = 31)), rows, details)
                assertEquals(setOf("rating-46"), top.map { it.ref.id }.toSet(), "$kind high tier")
                assertEquals(setOf("rating-31"), low.map { it.ref.id }.toSet(), "$kind low tier")
            }
        }
    }
    @Test fun historyChangeSummaryCanBeRenderedWithoutDuplicatingTheTimeToggle() {
        val row = summary().copy(fields = mapOf("action" to "create"))
        assertEquals("新增记录", historySummary(row, null, includeTime = false))
        assertEquals("${relativeTime(row.updatedAt)} · 新增记录", historySummary(row, null))
        val rating = row.copy(cachedDiff = listOf(Change("rating_tenths", null, "46")))
        assertEquals("评分 未评分 → ★ 4.6", historySummary(rating, null, includeTime = false))
    }
    @Test fun newerSummaryRatingWinsOverStaleDetailAndDeletedParentKeepsHistoryVisible() {
        val newer = summary().copy(version = 2, fields = mapOf("rating_tenths" to "46"))
        assertEquals(listOf(ref.id), localSearch(ListQuery(filters = ListFilters(minRating = 45)), listOf(newer), listOf(snapshot())).map { it.ref.id })
        assertTrue(localSearch(ListQuery(filters = ListFilters(maxRating = 44)), listOf(newer), listOf(snapshot())).isEmpty())
        val parent = summary().copy(deleted = true)
        val history = summary().copy(ref = RecordRef(RecordKind.REVISION, "history", ref.id))
        val dish = summary().copy(ref = RecordRef(RecordKind.DISH, "dish", ref.id))
        assertEquals(listOf("history"), localSearch(ListQuery(RecordKind.REVISION, ref.id), listOf(parent, history, dish), emptyList()).map { it.ref.id })
        assertTrue(localSearch(ListQuery(RecordKind.DISH, ref.id), listOf(parent, history, dish), emptyList()).isEmpty())
    }
    @Test fun aDishHistoryQueryOnlyIncludesThatDishInTheCache() {
        val dishHistory = summary().copy(ref = RecordRef(RecordKind.REVISION, "dish-history", ref.id), fields = mapOf("entity_type" to "dish", "entity_id" to "dish-one"))
        val sibling = dishHistory.copy(ref = dishHistory.ref.copy(id = "sibling-history"), fields = mapOf("entity_type" to "dish", "entity_id" to "dish-two"))
        val restaurantHistory = dishHistory.copy(ref = dishHistory.ref.copy(id = "restaurant-history"), fields = mapOf("entity_type" to "restaurant", "entity_id" to ref.id))
        val query = ListQuery(RecordKind.REVISION, ref.id, historyEntityKind = RecordKind.DISH, historyEntityId = "dish-one")
        assertEquals(listOf("dish-history"), localSearch(query, listOf(dishHistory, sibling, restaurantHistory), emptyList()).map { it.ref.id })
    }
    @Test fun cachedTimelineDateSearchKeepsAllSameDayVisitsAndIntersectsDateFilters() {
        val first = summary().copy(ref = RecordRef(RecordKind.VISIT, "first", ref.id), fields = mapOf("dining_date" to "2026-11-01"))
        val second = first.copy(ref = first.ref.copy(id = "second"))
        val otherDay = first.copy(ref = first.ref.copy(id = "other"), fields = mapOf("dining_date" to "2026-11-02", "note" to "提到 2026-11-01"))
        val query = ListQuery(RecordKind.VISIT, query = "2026-11-01")
        assertEquals(setOf("first", "second"), localSearch(query, listOf(first, second, otherDay), emptyList()).map { it.ref.id }.toSet())
        assertTrue(localSearch(query.copy(filters = ListFilters(from = "2026-11-02")), listOf(first, second, otherDay), emptyList()).isEmpty())
    }
    @Test fun currentFullValuesSupersedeOldExcerptsIncludingExplicitlyClearedFields() {
        val brief = summary().copy(fields = mapOf("review_excerpt" to "旧评价片段", "note_excerpt" to "旧备注片段"))
        val current = snapshot(2, review = "新评价全文", note = null)
        for (term in listOf("旧评价片段", "旧备注片段", "null")) {
            assertTrue(localSearch(ListQuery(query = term), listOf(brief), listOf(current)).isEmpty())
        }
        val result = localSearch(ListQuery(query = "新评价全文"), listOf(brief), listOf(current)).single()
        assertEquals(listOf(MatchType.REVIEW), result.matches)
    }
    @Test fun newerDishSummariesAndDeletionSupersedeOldDishDetailsInAggregatedSearch() {
        val restaurant = summary()
        val childRef = RecordRef(RecordKind.DISH, "dish", ref.id)
        val brief = RecordSummary(childRef, "新菜名", 2, restaurant.updatedAt,
            mapOf("name" to "新菜名", "review_excerpt" to "新短评"))
        val oldDetail = RecordSnapshot(childRef, 1, mapOf("name" to "旧菜名", "review" to "旧短评"), restaurant.updatedAt)
        for (term in listOf("旧菜名", "旧短评")) {
            assertTrue(localSearch(ListQuery(query = term), listOf(restaurant, brief), listOf(oldDetail)).isEmpty())
            assertTrue(localSearch(ListQuery(RecordKind.DISH, ref.id, query = term), listOf(restaurant, brief), listOf(oldDetail)).isEmpty())
        }
        assertEquals(listOf(MatchType.DISH), localSearch(ListQuery(query = "新菜名"), listOf(restaurant, brief), listOf(oldDetail)).single().matches)
        assertEquals(listOf(MatchType.REVIEW), localSearch(ListQuery(query = "新短评"), listOf(restaurant, brief), listOf(oldDetail)).single().matches)
        for (term in listOf("旧菜名", "旧短评", "新菜名", "新短评")) {
            assertTrue(localSearch(ListQuery(query = term), listOf(restaurant, brief.copy(deleted = true)), listOf(oldDetail)).isEmpty())
        }
    }
}
