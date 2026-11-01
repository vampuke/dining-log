package top.alanvampuke.dininglog.core

import java.text.Normalizer
import java.util.Locale

fun normalizedSearch(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

// This is a deliberately partial search: callers must label its cache-only scope.
fun localSearch(query: ListQuery, summaries: List<RecordSummary>, details: List<RecordSnapshot>): List<RecordSummary> {
    val full = details.associateBy { it.ref }
    fun snapshotFor(summary: RecordSummary) = full[summary.ref] ?: details.firstOrNull {
        it.ref.kind == summary.ref.kind && it.ref.id == summary.ref.id
    }
    fun searchValue(summary: RecordSummary, snapshot: RecordSnapshot?, field: String, excerpt: String = field): String? = when {
        snapshot != null && snapshot.version >= summary.version && snapshot.fields.containsKey(field) -> snapshot.fields[field]
        summary.fields.containsKey(excerpt) -> summary.fields[excerpt]
        summary.fields.containsKey(field) -> summary.fields[field]
        else -> snapshot?.fields?.get(field)
    }
    val knownDishIds = summaries.filter { it.ref.kind == RecordKind.DISH }.map { it.ref.id }.toSet()
    val deletedParents = summaries.filter { it.ref.kind == RecordKind.RESTAURANT && it.deleted }.map { it.ref.id }.toSet()
    val tokens = if (query.exactVisitDate != null) emptyList() else normalizedSearch(query.query).split(' ').filter(String::isNotEmpty)
    val filtered = summaries.filter { s ->
        s.ref.kind == query.kind && s.deleted == (query.deleted == DeletedFilter.ONLY) &&
            (query.historyEntityKind == null || s.fields["entity_type"] == query.historyEntityKind.name.lowercase()) &&
            (query.historyEntityId == null || s.fields["entity_id"] == query.historyEntityId) &&
            (query.restaurantId == null || s.ref.restaurantId == query.restaurantId) &&
            (s.ref.kind !in setOf(RecordKind.DISH, RecordKind.VISIT) || s.ref.restaurantId !in deletedParents)
    }.mapNotNull { s ->
        val snapshot = snapshotFor(s)
        val fields = if (snapshot != null && s.version > snapshot.version) snapshot.fields + s.fields
            else s.fields + (snapshot?.fields ?: emptyMap())
        val groups = if (s.ref.kind == RecordKind.RESTAURANT) {
            val dishes = summaries.filter { it.ref.kind == RecordKind.DISH && it.ref.restaurantId == s.ref.id && !it.deleted }
            // A newer summary supersedes its older detail, including deletion.
            // Full current values (even null) supersede truncated excerpts.
            val dishDetails = details.filter { it.ref.kind == RecordKind.DISH && it.ref.restaurantId == s.ref.id && it.deletedAt == null && it.ref.id !in knownDishIds }
            mapOf(MatchType.RESTAURANT to listOf(searchValue(s, snapshot, "name") ?: s.title, fields["category"], fields["cuisine"], searchValue(s, snapshot, "address", "address_excerpt")).filterNotNull().joinToString(" "),
                MatchType.DISH to (dishes.map { searchValue(it, snapshotFor(it), "name") ?: it.title } + dishDetails.map { it.title }).joinToString(" "),
                MatchType.REVIEW to (listOf(searchValue(s, snapshot, "review", "review_excerpt")) + dishes.map { searchValue(it, snapshotFor(it), "review", "review_excerpt") } + dishDetails.map { it.fields["review"] }).filterNotNull().joinToString(" "),
                MatchType.NOTE to (searchValue(s, snapshot, "note", "note_excerpt") ?: ""))
        } else {
            val textFields = setOf("name", "review", "review_excerpt", "note", "note_excerpt", "address", "address_excerpt")
            mapOf(MatchType.RESTAURANT to (listOf(searchValue(s, snapshot, "name") ?: s.title,
                searchValue(s, snapshot, "review", "review_excerpt"), searchValue(s, snapshot, "note", "note_excerpt"),
                searchValue(s, snapshot, "address", "address_excerpt")) + fields.filterKeys { it !in textFields }.values +
                (snapshot?.diff?.flatMap { listOf(it.before, it.after) } ?: emptyList())).filterNotNull().joinToString(" "))
        }
        val normalized = groups.mapValues { normalizedSearch(it.value) }
        if (!tokens.all { token -> normalized.values.any { token in it } }) return@mapNotNull null
        val f = query.effectiveFilters
        val rating = fields["rating_tenths"]?.toIntOrNull()
        if (f.unrated && rating != null || !f.unrated && (f.minRating != null && (rating == null || rating < f.minRating) || f.maxRating != null && (rating == null || rating > f.maxRating))) return@mapNotNull null
        if (query.kind == RecordKind.RESTAURANT) {
            if (f.category != null && f.category != fields["category"] || f.cuisine.isNotBlank() && normalizedSearch(f.cuisine) != normalizedSearch(fields["cuisine"] ?: "")) return@mapNotNull null
            if (f.visited != null && f.visited != ((fields["visit_count"]?.toIntOrNull() ?: 0) > 0)) return@mapNotNull null
            if (f.from.isNotBlank() || f.to.isNotBlank()) {
                val dates = summaries.filter { it.ref.kind == RecordKind.VISIT && it.ref.restaurantId == s.ref.id && !it.deleted }.mapNotNull { it.fields["dining_date"] } + listOfNotNull(fields["last_visit_date"])
                if (dates.none { (f.from.isBlank() || it >= f.from) && (f.to.isBlank() || it <= f.to) }) return@mapNotNull null
            }
        } else if (query.kind == RecordKind.VISIT) {
            val date = fields["dining_date"] ?: ""
            if (f.from.isNotBlank() && date < f.from || f.to.isNotBlank() && date > f.to) return@mapNotNull null
        }
        s.copy(matches = if (tokens.isEmpty()) emptyList() else normalized.filterValues { text -> tokens.any { it in text } }.keys.toList())
    }
    return deduplicate(filtered).sortedWith(when {
        query.kind == RecordKind.VISIT -> compareByDescending<RecordSummary> { it.fields["dining_date"] }.thenByDescending { it.ref.id }
        query.filters.sort == "rating_desc" -> compareByDescending<RecordSummary> { it.fields["rating_tenths"]?.toIntOrNull() ?: -1 }.thenByDescending { it.ref.id }
        else -> compareByDescending<RecordSummary> { it.updatedAt }.thenByDescending { it.ref.id }
    })
}
