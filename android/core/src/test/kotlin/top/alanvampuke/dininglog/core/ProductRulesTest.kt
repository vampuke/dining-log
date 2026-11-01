package top.alanvampuke.dininglog.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProductRulesTest {
    private val ref = RecordRef(RecordKind.RESTAURANT, "restaurant-1")
    private fun snapshot(version: Int = 1, review: String? = "旧评价", rating: String? = "40", note: String? = null) =
        RecordSnapshot(ref, version, mapOf("name" to "兰记", "category" to "restaurant", "address" to "愚园路", "review" to review, "rating_tenths" to rating, "note" to note), "2026-11-01T00:00:00Z")
    private fun draft() = SavedDraft("A", "edit:restaurant-1", ref, WriteAction.UPDATE, snapshot(), snapshot().editable + ("rating_tenths" to "43"))
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
    @Test fun draftDebouncesFor500msAndFlushesLatestInput() = runTest {
        val saved = mutableListOf<SavedDraft>()
        val autosaver = DraftAutosaver(backgroundScope) { saved += it }
        autosaver.schedule(draft()); runCurrent(); advanceTimeBy(300)
        val latest = DraftRules.edit(draft(), draft().localFields + ("note" to "最新输入"), 300)
        autosaver.schedule(latest); runCurrent(); advanceTimeBy(499); runCurrent()
        assertTrue(saved.isEmpty()); advanceTimeBy(1); runCurrent(); assertEquals(listOf(latest), saved)
        val final = DraftRules.edit(latest, latest.localFields + ("note" to "返回前输入"), 800)
        autosaver.schedule(final); autosaver.stopAndFlush(); assertEquals(final, saved.last())
    }
    @Test fun unknownRequestUsesOriginalKeyAndPayloadAndCannotBeEdited() {
        val prepared = DraftRules.prepare(draft(), "PATCH", "restaurants/restaurant-1", "{\"rating\":4.3}", 100) { "key-one" }
        val unknown = prepared.copy(status = DraftStatus.RESULT_UNKNOWN)
        val retried = DraftRules.prepare(unknown, "PATCH", "restaurants/restaurant-1", "{\"rating\":4.3}", 200) { "must-not-use" }
        assertEquals(prepared.original, retried.original)
        assertFailsWith<IllegalStateException> { DraftRules.edit(unknown, unknown.localFields + ("note" to "变更"), 200) }
        assertFailsWith<IllegalStateException> { DraftRules.prepare(unknown, "PATCH", "restaurants/restaurant-1", "{\"rating\":4.4}", 200) }
        val edited = DraftRules.edit(prepared.copy(status = DraftStatus.EDITING, original = null), prepared.localFields + ("note" to "新内容"), 201)
        val fresh = DraftRules.prepare(edited, "PATCH", "restaurants/restaurant-1", "new payload", 201) { "key-two" }
        assertEquals("key-two", fresh.original?.key)
    }
    @Test fun savingOnRestartBecomesUnknownAndSevenDayBoundaryRequiresComparison() {
        val prepared = DraftRules.prepare(draft(), "PATCH", "path", "payload", 100)
        assertEquals(DraftStatus.RESULT_UNKNOWN, DraftRules.recovered(prepared).status)
        assertFalse(DraftRules.needsReview(prepared, 100 + DraftRules.RETRY_WINDOW_MS - 1))
        assertTrue(DraftRules.needsReview(prepared, 100 + DraftRules.RETRY_WINDOW_MS))
        assertFailsWith<IllegalStateException> { DraftRules.prepare(prepared, "PATCH", "path", "payload", 100 + DraftRules.RETRY_WINDOW_MS) }
    }
    @Test fun independentFieldsSuggestMergeButRequireConfirmationAndANewKey() {
        val pending = draft().copy(original = OriginalRequest("PATCH", "path", "payload", "old-key", 0))
        val comparison = compareConflict(pending, snapshot(2, review = "服务器评价"))
        assertEquals(pending.baseSnapshot, comparison.baseSnapshot); assertEquals("43", comparison.localDraft.localFields["rating_tenths"])
        assertTrue(comparison.mergeAllowed); assertTrue(comparison.needsChoice.isEmpty())
        val merged = comparison.confirmed(emptyMap(), 100)
        assertEquals(2, merged.baseVersion); assertEquals("服务器评价", merged.localFields["review"])
        assertEquals("43", merged.localFields["rating_tenths"]); assertNull(merged.original)
        assertEquals("fresh", DraftRules.prepare(merged, "PATCH", "path", "new", 100) { "fresh" }.original?.key)
    }
    @Test fun sameFieldMustBeChosenAndDeleteOrDeletedParentCannotBeMerged() {
        val conflict = compareConflict(draft(), snapshot(2, rating = "46"))
        assertEquals(setOf("rating_tenths"), conflict.needsChoice)
        assertFailsWith<IllegalStateException> { conflict.confirmed(emptyMap(), 100) }
        assertEquals("46", conflict.confirmed(mapOf("rating_tenths" to ConflictChoice.SERVER), 100).localFields["rating_tenths"])
        assertEquals("43", conflict.confirmed(mapOf("rating_tenths" to ConflictChoice.LOCAL), 100).localFields["rating_tenths"])
        assertFalse(compareConflict(draft().copy(action = WriteAction.DELETE), snapshot(2)).mergeAllowed)
        assertFalse(compareConflict(draft(), snapshot(2).copy(deletedAt = "2026-11-01T01:00:00Z")).mergeAllowed)
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
    }
}
