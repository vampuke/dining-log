package top.alanvampuke.dininglog.core

import java.time.Instant
import java.time.LocalDate

// API uses snake_case; DTO mapping belongs to data, keeping core pure Kotlin.
enum class Category(val code: String) {
    RESTAURANT("restaurant"), CAFE("cafe"), BAKERY("bakery"),
    BAR("bar"), STREET_FOOD("street_food"), OTHER("other")
}
enum class Meal(val code: String) { BREAKFAST("breakfast"), LUNCH("lunch"), DINNER("dinner"), OTHER("other") }
data class Restaurant(
    val id: String,
    val name: String,
    val category: Category,
    val address: String,
    val cuisine: String?,
    val rating: Rating?,
    val review: String?,
    val note: String?,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)
data class Dish(
    val id: String,
    val restaurantId: String,
    val name: String,
    val rating: Rating?,
    val review: String?,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)
data class Visit(
    val id: String,
    val restaurantId: String,
    val diningDate: LocalDate,
    val meal: Meal?,
    val note: String?,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
) {
    init { require(diningDate in LocalDate.of(1900, 1, 1)..LocalDate.of(2100, 12, 31)) }
}
enum class EntityType { RESTAURANT, DISH, VISIT }
enum class RevisionAction { CREATE, UPDATE, DELETE, RESTORE }
// rating_tenths diffs retain integers; display conversion never changes stored values.
sealed interface RevisionValue {
    data object Empty : RevisionValue
    data class Text(val value: String) : RevisionValue
    data class Integer(val value: Long) : RevisionValue
}
data class FieldDiff(val field: String, val before: RevisionValue, val after: RevisionValue)
data class Revision(
    val id: String,
    val changeSetId: String,
    val restaurantId: String,
    val entityType: EntityType,
    val entityId: String,
    val action: RevisionAction,
    val entityVersionBefore: Int,
    val entityVersionAfter: Int,
    val diff: List<FieldDiff>,
    val sourceRevisionId: String?,
    val note: String?,
    val metadataVersion: Int,
    val metadataUpdatedAt: Instant,
    val createdAt: Instant,
    val deletedAt: Instant?,
)
