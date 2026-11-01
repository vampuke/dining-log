package top.alanvampuke.dininglog.data.remote

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.*
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.core.auth.AuthFailure
import top.alanvampuke.dininglog.core.auth.RefreshCoordinator

val ProductJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
@Serializable data class ApiMeta(@SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false)
@Serializable data class PageData(val items: List<JsonObject>)
@Serializable data class PageEnvelope(val data: PageData, val meta: ApiMeta)
@Serializable data class ObjectEnvelope(val data: JsonObject)
@Serializable data class ErrorData(val code: String, val current: JsonObject? = null,
    @SerialName("field_errors") val fieldErrors: Map<String, List<String>> = emptyMap())
@Serializable data class ErrorEnvelope(val error: ErrorData)
interface ProductApi {
    @GET suspend fun list(@Url path: String, @Header("Authorization") authorization: String, @QueryMap query: Map<String, String>): Response<PageEnvelope>
    @GET suspend fun detail(@Url path: String, @Header("Authorization") authorization: String, @QueryMap query: Map<String, String> = emptyMap()): Response<ObjectEnvelope>
    @HTTP(method = "POST", hasBody = true) suspend fun post(@Url path: String, @Header("Authorization") authorization: String, @Header("Idempotency-Key") key: String, @Body body: RequestBody): Response<ObjectEnvelope>
    @HTTP(method = "PATCH", hasBody = true) suspend fun patch(@Url path: String, @Header("Authorization") authorization: String, @Header("Idempotency-Key") key: String, @Body body: RequestBody): Response<ObjectEnvelope>
    @HTTP(method = "DELETE", hasBody = true) suspend fun delete(@Url path: String, @Header("Authorization") authorization: String, @Header("Idempotency-Key") key: String, @Body body: RequestBody): Response<ObjectEnvelope>
}
class ProductRemote(private val api: ProductApi, private val refresh: RefreshCoordinator,
    private val pause: suspend (Long) -> Unit = { delay(it) }, private val jitter: () -> Long = { Random.nextLong(80, 240) }) {
    suspend fun list(query: ListQuery, cursor: String?): Pair<List<RecordSummary>, ApiMeta> = read {
        val path = if (query.restaurantId != null && query.kind == RecordKind.DISH) "restaurants/${query.restaurantId}/dishes" else query.kind.path
        val params = queryParameters(query).toMutableMap().apply { if (cursor != null) put("cursor", cursor) }
        val envelope = authenticated(query.kind) { token -> api.list(path, "Bearer $token", params) }
        envelope.data.items.map { summary(query.kind, it, query.deleted == DeletedFilter.ONLY) } to envelope.meta
    }
    suspend fun detail(ref: RecordRef): RecordSnapshot = read {
        val result = authenticated(ref.kind) { token -> api.detail("${ref.kind.path}/${ref.id}", "Bearer $token", mapOf("deleted" to "include")) }
        snapshot(ref.kind, result.data, ref.restaurantId)
    }
    suspend fun write(draft: SavedDraft): JsonObject {
        val original = requireNotNull(draft.original)
        val body = original.payload.toRequestBody("application/json".toMediaType())
        return authenticated(draft.ref.kind) { token -> when (original.method) {
            "POST" -> api.post(original.path, "Bearer $token", original.key, body)
            "PATCH" -> api.patch(original.path, "Bearer $token", original.key, body)
            "DELETE" -> api.delete(original.path, "Bearer $token", original.key, body)
            else -> error("Unsupported write method")
        } }.data
    }
    private suspend fun <T> authenticated(kind: RecordKind, block: suspend (String) -> Response<T>): T = try {
        refresh.authenticated { token -> checked(block(token), kind) }
    } catch (e: IOException) { throw ProductFailure("NETWORK_ERROR") }
    private suspend fun <T> read(block: suspend () -> T): T {
        repeat(3) { attempt -> try { return block() } catch (e: ProductFailure) {
            val retryable = e.code == "NETWORK_ERROR" || e.status == 429 || e.status >= 500
            if (!retryable || attempt == 2) throw e
            // Retry-After is a minimum, never truncated to a shorter wait.
            pause((e.retryAfterSeconds?.times(1000) ?: (200L shl attempt)) + jitter())
        } }
        error("unreachable")
    }
    companion object {
        fun create(baseUrl: String, refresh: RefreshCoordinator): ProductRemote = create(baseUrl, refresh, OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build())
        fun create(baseUrl: String, refresh: RefreshCoordinator, client: OkHttpClient): ProductRemote = ProductRemote(
            Retrofit.Builder().baseUrl(baseUrl).client(client)
                .addConverterFactory(ProductJson.asConverterFactory("application/json".toMediaType()))
                .build().create(ProductApi::class.java), refresh)
    }
}
fun <T> checked(response: Response<T>, kind: RecordKind? = null): T {
    if (response.isSuccessful) return response.body() ?: throw ProductFailure("INVALID_RESPONSE", response.code())
    val error = runCatching { ProductJson.decodeFromString<ErrorEnvelope>(response.errorBody()?.string() ?: "{}").error }.getOrNull()
    val code = error?.code ?: if (response.code() == 429) "RATE_LIMITED" else "TEMPORARILY_UNAVAILABLE"
    if (response.code() == 401) throw AuthFailure(code)
    val after = response.headers()["Retry-After"]?.let { value -> value.toLongOrNull() ?: runCatching {
        (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().epochSecond - java.time.Instant.now().epochSecond).coerceAtLeast(0)
    }.getOrNull() }
    throw ProductFailure(code, response.code(), if (kind != null) error?.current?.let { snapshot(kind, it) } else null, after, error?.fieldErrors ?: emptyMap())
}
fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
fun snapshot(kind: RecordKind, value: JsonObject, parent: String? = null): RecordSnapshot {
    val fields = value.entries.filter { it.value is JsonPrimitive }.associate { it.key to (it.value as JsonPrimitive).contentOrNull }.toMutableMap()
    if (value.containsKey("rating") && kind in setOf(RecordKind.RESTAURANT, RecordKind.DISH)) fields["rating_tenths"] = value["rating"]?.jsonPrimitive?.doubleOrNull?.let { kotlin.math.round(it * 10).toInt().toString() }
    (value["stats"] as? JsonObject)?.forEach { (key, v) -> fields[key] = v.jsonPrimitive.contentOrNull }
    val changes = (value["diff"] as? JsonArray)?.map { item ->
        val change = item.jsonObject; Change(requireNotNull(change.text("field")), change.text("before"), change.text("after"))
    } ?: emptyList()
    val id = value.text("id") ?: "unknown"
    return RecordSnapshot(RecordRef(kind, id, value.text("restaurant_id") ?: parent),
        (value.text(if (kind == RecordKind.REVISION) "metadata_version" else "version") ?: "1").toInt(), fields,
        value.text("updated_at") ?: value.text("metadata_updated_at") ?: value.text("created_at") ?: "1970-01-01T00:00:00Z", value.text("deleted_at"), changes)
}
fun summary(kind: RecordKind, value: JsonObject, deleted: Boolean = false): RecordSummary {
    val entity = snapshot(kind, value)
    val matches = (value["matched_fields"] as? JsonArray)?.mapNotNull { item -> MatchType.entries.firstOrNull { it.name.lowercase() == item.jsonPrimitive.content } } ?: emptyList()
    val fields = (value["changed_fields"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
    return RecordSummary(entity.ref, entity.title, entity.version, if (kind == RecordKind.REVISION) value.text("created_at") ?: entity.updatedAt else entity.updatedAt,
        entity.fields, matches, fields, deleted || entity.deletedAt != null)
}
fun queryParameters(query: ListQuery): Map<String, String> = buildMap {
    put("limit", "20"); put("deleted", if (query.deleted == DeletedFilter.ONLY) "only" else "exclude")
    // The server's visit q searches names/notes; a date entered in the Android
    // timeline is an exact date filter, intersected with any explicit range.
    if (query.query.isNotBlank() && query.exactVisitDate == null) put("q", query.query.trim())
    if (query.restaurantId != null && query.kind in setOf(RecordKind.VISIT, RecordKind.REVISION)) put("restaurant_id", requireNotNull(query.restaurantId))
    if (query.kind == RecordKind.REVISION) {
        query.historyEntityKind?.let { put("entity_type", it.name.lowercase()) }
        query.historyEntityId?.let { put("entity_id", it) }
    }
    val f = query.effectiveFilters
    if (query.kind in setOf(RecordKind.RESTAURANT, RecordKind.DISH)) {
        put("sort", f.sort)
        if (f.unrated) put("unrated", "true") else {
            f.minRating?.let { put("min_rating", Rating.fromTenths(it).display) }
            f.maxRating?.let { put("max_rating", Rating.fromTenths(it).display) }
        }
    }
    if (query.kind == RecordKind.RESTAURANT) {
        f.category?.let { put("category", it) }; if (f.cuisine.isNotBlank()) put("cuisine", f.cuisine.trim())
        if (query.deleted != DeletedFilter.ONLY) {
            f.visited?.let { put("visited", it.toString()) }
            if (f.from.isNotBlank()) put("visited_from", f.from)
            if (f.to.isNotBlank()) put("visited_to", f.to)
        }
    } else if (query.kind == RecordKind.VISIT) {
        if (f.from.isNotBlank()) put("from", f.from); if (f.to.isNotBlank()) put("to", f.to)
    }
}
fun requestFor(draft: SavedDraft, now: Long): SavedDraft {
    draft.original?.let { return DraftRules.prepare(draft, it.method, it.path, it.payload, now) }
    val ref = draft.ref
    val method = when (draft.action) { WriteAction.CREATE, WriteAction.RESTORE, WriteAction.APPLY -> "POST"; WriteAction.DELETE -> "DELETE"; else -> "PATCH" }
    val path = when (draft.action) {
        WriteAction.APPLY -> "revisions"
        WriteAction.CREATE -> if (ref.kind == RecordKind.RESTAURANT) "restaurants" else "restaurants/${requireNotNull(ref.restaurantId)}/${ref.kind.path}"
        WriteAction.RESTORE -> "${ref.kind.path}/${ref.id}/restore"
        else -> "${ref.kind.path}/${ref.id}"
    }
    val payload = buildJsonObject {
        if (draft.action == WriteAction.APPLY) {
            put("source_revision_id", requireNotNull(draft.sourceRevisionId)); put("side", draft.applySide)
            put("fields", JsonArray(draft.applyFields.map(::JsonPrimitive))); put("expected_version", requireNotNull(draft.baseVersion))
        } else {
            if (draft.action == WriteAction.CREATE) put("id", ref.id)
            else put(if (ref.kind == RecordKind.REVISION) "expected_metadata_version" else "expected_version", requireNotNull(draft.baseVersion))
            if (draft.action in setOf(WriteAction.CREATE, WriteAction.UPDATE, WriteAction.METADATA)) {
                val fields = if (draft.action == WriteAction.CREATE) draft.localFields else draft.localFields.filterKeys { it in draft.changedFields }
                fields.filterKeys { it in editableFields(ref.kind) }.forEach { (key, value) ->
                    if (key == "rating_tenths") put("rating", value?.toInt()?.let { JsonPrimitive(it / 10.0) } ?: JsonNull)
                    else put(key, value?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
            if (draft.action == WriteAction.CREATE && ref.kind == RecordKind.RESTAURANT) draft.firstVisit?.let { visit ->
                put("first_visit", buildJsonObject { put("id", visit.id); put("dining_date", visit.date)
                    put("meal", visit.meal?.let(::JsonPrimitive) ?: JsonNull); put("note", visit.note?.let(::JsonPrimitive) ?: JsonNull) })
            }
        }
    }.toString()
    return DraftRules.prepare(draft, method, path, payload, now)
}
