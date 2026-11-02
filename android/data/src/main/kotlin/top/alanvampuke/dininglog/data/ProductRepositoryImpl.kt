package top.alanvampuke.dininglog.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.core.auth.AccountSession
import top.alanvampuke.dininglog.core.auth.AuthFailure
import top.alanvampuke.dininglog.data.local.*
import top.alanvampuke.dininglog.data.remote.*

class ProductRepositoryImpl(private val db: ProductDatabase, private val owner: OwnerCache,
    private val account: AccountSession, private val remote: ProductRemote,
    override val connectivity: StateFlow<Boolean>, private val now: () -> Long = System::currentTimeMillis) : ProductRepository {
    private val dao = db.products()
    private val saveLocks = ConcurrentHashMap<String, Mutex>()
    override fun observeList(accountId: String, query: ListQuery): Flow<CachedPage> = combine(
        dao.observeQuery(accountId, query.fingerprint), dao.observePage(accountId, query.fingerprint),
        dao.observeSummaries(accountId), dao.observeDetails(accountId),
    ) { cached, page, all, details ->
        val summaries = all.map { ProductJson.decodeFromString<RecordSummary>(it.payload) }
        val snapshots = details.map { ProductJson.decodeFromString<RecordSnapshot>(it.payload) }
        fun previews(rows: List<RecordSummary>) = rows.map { row ->
            if (row.ref.kind == RecordKind.REVISION) row.copy(cachedDiff = snapshots.firstOrNull {
                it.ref.kind == RecordKind.REVISION && it.ref.id == row.ref.id
            }?.diff ?: row.cachedDiff) else row
        }
        if (cached != null) {
            val parents = summaries.filter { it.ref.kind == RecordKind.RESTAURANT && it.deleted }.map { it.ref.id }.toSet()
            val rows = page.map { ProductJson.decodeFromString<RecordSummary>(it.summary.payload).copy(
                matches = ProductJson.decodeFromString<List<MatchType>>(it.matchesPayload)) }.filter {
                it.deleted == (query.deleted == DeletedFilter.ONLY) &&
                    (it.ref.kind !in setOf(RecordKind.DISH, RecordKind.VISIT) || it.ref.restaurantId !in parents)
            }
            CachedPage(previews(deduplicate(rows)), cached.cursor, cached.hasMore, cached.fetchedAt, false)
        } else CachedPage(previews(localSearch(query, summaries, snapshots)),
            fetchedAt = all.maxOfOrNull { it.fetchedAt }, localScope = true)
    }.distinctUntilChanged()

    override suspend fun refreshList(accountId: String, query: ListQuery, loadMore: Boolean) {
        val generation = account.generation
        val old = if (loadMore) dao.query(accountId, query.fingerprint) else null
        if (loadMore && (old == null || !old.hasMore || old.cursor == null)) return
        val (items, meta) = remote.list(query, old?.cursor)
        owner.write(account, accountId, generation) {
            val previous = if (loadMore) dao.members(accountId, query.fingerprint) else emptyList()
            if (!loadMore) dao.removeMembers(accountId, query.fingerprint)
            items.forEach { cacheSummary(accountId, it) }
            val ids = (previous.map { it.entityId } + items.map { it.ref.id }).distinct()
            val hits = previous.associate { it.entityId to it.matchesPayload } + items.associate { it.ref.id to ProductJson.encodeToString(it.matches) }
            dao.putMembers(ids.mapIndexed { position, id -> PageMember(accountId, query.fingerprint, query.kind.name, id, position, hits[id] ?: "[]") })
            dao.putQuery(QueryRow(accountId, query.fingerprint, meta.nextCursor, meta.hasMore, now()))
        }
    }
    override fun observeDetail(accountId: String, ref: RecordRef): Flow<CachedDetail> = dao.observeDetail(accountId, ref.kind.name, ref.id).map { row ->
        if (row == null) CachedDetail() else CachedDetail(ProductJson.decodeFromString<RecordSnapshot>(row.payload), row.fetchedAt, row.stale)
    }
    override suspend fun refreshDetail(accountId: String, ref: RecordRef): RecordSnapshot {
        val generation = account.generation
        val current = remote.detail(ref)
        owner.write(account, accountId, generation) { cacheSnapshot(accountId, current) }
        return current
    }
    override suspend fun submit(input: SaveInput): SaveOutcome = saveLocks.getOrPut("${input.accountId}:${input.ref.kind}:${input.ref.id}") { Mutex() }.withLock {
        val generation = account.generation
        val pending = input
        if (!connectivity.value) return@withLock SaveOutcome.Failed(input, "保存失败，请检查网络后重试")
        val saved = try {
            val data = remote.write(pending.ref.kind, requestFor(pending, now()))
            val raw = (data["entity"] as? JsonObject) ?: data
            val partial = snapshot(pending.ref.kind, raw, pending.ref.restaurantId)
            val baseline = dao.detail(pending.accountId, pending.ref.kind.name, pending.ref.id)?.let { ProductJson.decodeFromString<RecordSnapshot>(it.payload) } ?: pending.baseSnapshot
            val saved = partial.copy(fields = (baseline?.fields ?: emptyMap()) + partial.fields,
                diff = if (partial.diff.isEmpty()) baseline?.diff ?: emptyList() else partial.diff)
            owner.write(account, pending.accountId, generation) {
                cacheSnapshot(pending.accountId, saved)
                (data["first_visit"] as? JsonObject)?.let { cacheSnapshot(pending.accountId, snapshot(RecordKind.VISIT, it, saved.ref.id)) }
                (data["revisions"] as? JsonArray)?.forEach {
                    val revision = summary(RecordKind.REVISION, it.jsonObject)
                    // The acknowledged entity values let this device summarize
                    // its own score change without fetching every history diff.
                    val score = if (revision.fields["action"] == "update" && "rating_tenths" in revision.changedFields &&
                        revision.fields["entity_id"] == saved.ref.id) listOf(Change("rating_tenths", pending.baseSnapshot?.fields?.get("rating_tenths"), saved.fields["rating_tenths"])) else emptyList()
                    cacheSummary(pending.accountId, revision.copy(cachedDiff = score))
                }
                (data["revision"] as? JsonObject)?.let { cacheSnapshot(pending.accountId, snapshot(RecordKind.REVISION, it)) }
                dao.invalidateQueries(pending.accountId); dao.invalidateMembers(pending.accountId)
            }
            saved
        } catch (e: CancellationException) { throw e }
        catch (e: ProductFailure) {
            if (e.code == "VERSION_CONFLICT" && e.current != null) {
                return@withLock SaveOutcome.Conflict(input, compareConflict(input, requireNotNull(e.current).copy(ref = input.ref)))
            }
            val message = when {
                e.code == "PARENT_UNAVAILABLE" || e.code == "NOT_FOUND" -> "记录或父餐厅不可用，请恢复或刷新后重试"
                e.status == 429 -> "请求过于频繁，请在 ${e.retryAfterSeconds ?: 60} 秒后重试"
                e.fieldErrors.isNotEmpty() -> "请检查输入：${e.fieldErrors.keys.joinToString("、", transform = ::fieldLabel)}"
                else -> "保存失败，请检查网络后重试"
            }
            return@withLock SaveOutcome.Failed(input, message)
        } catch (e: AuthFailure) {
            if (e.code !in setOf("NETWORK_ERROR", "TEMPORARILY_UNAVAILABLE", "RATE_LIMITED")) throw e
            return@withLock SaveOutcome.Failed(input, "保存失败，请检查网络后重试")
        }
        // Parent statistics cannot turn an acknowledged write into a failed save.
        val parent = if (pending.ref.kind == RecordKind.RESTAURANT) pending.ref.id else pending.ref.restaurantId
        if (parent != null && pending.ref.kind != RecordKind.REVISION &&
            (pending.ref.kind != RecordKind.RESTAURANT || saved.deletedAt == null)) {
            try { refreshDetail(pending.accountId, RecordRef(RecordKind.RESTAURANT, parent)) }
            catch (e: CancellationException) { throw e }
            catch (e: AuthFailure) { if (e.code !in setOf("NETWORK_ERROR", "TEMPORARILY_UNAVAILABLE", "RATE_LIMITED")) throw e }
            catch (_: ProductFailure) { /* Keep the acknowledged write; the parent can be refreshed manually. */ }
        }
        SaveOutcome.Saved(saved)
    }
    private suspend fun cacheSummary(accountId: String, record: RecordSummary) {
        val previous = dao.summary(accountId, record.ref.kind.name, record.ref.id)?.let { ProductJson.decodeFromString<RecordSummary>(it.payload) }
        val brief = record.copy(matches = emptyList(), cachedDiff = record.cachedDiff.ifEmpty { previous?.cachedDiff ?: emptyList() })
        dao.cacheSummary(SummaryRow(accountId, record.ref.kind.name, record.ref.id, record.version, ProductJson.encodeToString(brief), now()))
    }
    private suspend fun cacheSnapshot(accountId: String, record: RecordSnapshot) {
        val old = dao.summary(accountId, record.ref.kind.name, record.ref.id)?.let { ProductJson.decodeFromString<RecordSummary>(it.payload) }
        val fields = record.fields.toMutableMap()
        fields.remove("review"); fields.remove("note"); fields.remove("address")
        if (record.ref.kind == RecordKind.REVISION) fields["note"] = record.fields["note"]
        for (field in listOf("address", "review", "note")) {
            if (record.fields.containsKey(field)) fields["${field}_excerpt"] = record.fields[field]?.take(120)
        }
        val brief = RecordSummary(record.ref, record.title, record.version,
            if (record.ref.kind == RecordKind.REVISION) record.fields["created_at"] ?: record.updatedAt else record.updatedAt,
            (old?.fields ?: emptyMap()) + fields, old?.matches ?: emptyList(), record.diff.map { it.field }.ifEmpty { old?.changedFields ?: emptyList() }, record.deletedAt != null)
        cacheSummary(accountId, brief)
        dao.cacheDetail(DetailRow(accountId, record.ref.kind.name, record.ref.id, record.version, ProductJson.encodeToString(record), now()))
    }
}
