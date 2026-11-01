package top.alanvampuke.dininglog.core

import java.text.Normalizer
import java.util.Locale

fun normalizedSearch(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

// This is a deliberately partial search: callers must label its cache-only scope.
fun localSearch(query: ListQuery, summaries: List<RecordSummary>, details: List<RecordSnapshot>): List<RecordSummary> {
    val full = details.associateBy { it.ref }
    val deletedParents = summaries.filter { it.ref.kind == RecordKind.RESTAURANT && it.deleted }.map { it.ref.id }.toSet()
    val tokens = normalizedSearch(query.query).split(' ').filter(String::isNotEmpty)
    val filtered = summaries.filter { s ->
        s.ref.kind == query.kind && s.deleted == (query.deleted == DeletedFilter.ONLY) &&
            (query.restaurantId == null || s.ref.restaurantId == query.restaurantId) && s.ref.restaurantId !in deletedParents
    }.mapNotNull { s ->
        val snapshot = full[s.ref] ?: details.firstOrNull { it.ref.kind == s.ref.kind && it.ref.id == s.ref.id }
        val fields = s.fields + (snapshot?.fields ?: emptyMap())
        val groups = if (s.ref.kind == RecordKind.RESTAURANT) {
            val dishes = summaries.filter { it.ref.kind == RecordKind.DISH && it.ref.restaurantId == s.ref.id && !it.deleted }
            val dishDetails = details.filter { it.ref.kind == RecordKind.DISH && it.ref.restaurantId == s.ref.id && it.deletedAt == null }
            mapOf(MatchType.RESTAURANT to listOf(s.title, fields["category"], fields["cuisine"], fields["address"], fields["address_excerpt"]).joinToString(" "),
                MatchType.DISH to (dishes.map { it.title } + dishDetails.map { it.title }).joinToString(" "),
                MatchType.REVIEW to (listOf(fields["review"]) + dishes.map { it.fields["review_excerpt"] } + dishDetails.map { it.fields["review"] }).joinToString(" "),
                MatchType.NOTE to listOf(fields["note"]).joinToString(" "))
        } else mapOf(MatchType.RESTAURANT to (listOf(s.title) + fields.values + (snapshot?.diff?.flatMap { listOf(it.before, it.after) } ?: emptyList())).joinToString(" "))
        val normalized = groups.mapValues { normalizedSearch(it.value) }
        if (!tokens.all { token -> normalized.values.any { token in it } }) return@mapNotNull null
        val f = query.filters
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
