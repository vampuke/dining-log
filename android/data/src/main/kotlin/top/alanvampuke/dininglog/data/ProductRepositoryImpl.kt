package top.alanvampuke.dininglog.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    override fun observeDrafts(accountId: String): Flow<List<SavedDraft>> = dao.observeDrafts(accountId).map { rows ->
        rows.map { DraftRules.recovered(ProductJson.decodeFromString<SavedDraft>(it.payload)) }
    }
    override suspend fun findDraft(accountId: String, draftId: String): SavedDraft? = dao.draft(accountId, draftId)?.let {
        DraftRules.recovered(ProductJson.decodeFromString<SavedDraft>(it.payload))
    }
    override suspend fun storeDraft(draft: SavedDraft) {
        owner.write(account, draft.accountId, account.generation) { putDraft(draft) }
    }
    private suspend fun putDraft(draft: SavedDraft) = dao.putDraft(DraftRow(draft.accountId, draft.id, draft.ref.kind.name, draft.ref.id,
        draft.baseVersion, draft.baseSnapshot?.let { ProductJson.encodeToString(it) }, ProductJson.encodeToString(draft.localFields),
        draft.original?.key, draft.status.name.lowercase(), draft.updatedAt, ProductJson.encodeToString(draft)))
    override suspend fun discardDraft(accountId: String, draftId: String) {
        owner.write(account, accountId, account.generation) { dao.deleteDraft(accountId, draftId) }
    }
    override suspend fun submit(draft: SavedDraft): SaveOutcome = saveLocks.getOrPut("${draft.accountId}:${draft.id}") { Mutex() }.withLock {
        val generation = account.generation
        var pending = DraftRules.recovered(draft)
        if (!connectivity.value) {
            storeDraft(pending)
            return@withLock SaveOutcome.Kept(pending, "已保留草稿，联网后再保存")
        }
        val waitMs = (pending.retryNotBefore ?: 0) - now()
        if (waitMs > 0) {
            storeDraft(pending)
            return@withLock SaveOutcome.Kept(pending, "请求过于频繁，请在 ${(waitMs + 999) / 1000} 秒后再保存；草稿已保留")
        }
        if (DraftRules.needsReview(pending, now()) || pending.status == DraftStatus.EXPIRED_REVIEW) {
            return@withLock reviewExpired(pending)
        }
        pending = requestFor(pending, now())
        owner.write(account, pending.accountId, generation) { putDraft(pending) }
        val saved = try {
            val data = remote.write(pending)
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
                dao.deleteDraft(pending.accountId, pending.id)
                dao.invalidateQueries(pending.accountId); dao.invalidateMembers(pending.accountId)
            }
            saved
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { owner.write(account, pending.accountId, generation) { putDraft(pending.copy(status = DraftStatus.RESULT_UNKNOWN)) } } }
            throw e
        } catch (e: ProductFailure) {
            if (e.code == "VERSION_CONFLICT" && e.current != null) {
                val preserved = pending.copy(status = DraftStatus.EDITING, original = null)
                storeDraft(preserved)
                return@withLock SaveOutcome.Conflict(preserved, compareConflict(preserved, requireNotNull(e.current).copy(ref = pending.ref)))
            } else {
                val unknown = e.offline || e.status >= 500
                val rateLimited = e.status == 429
                val kept = pending.copy(status = if (unknown) DraftStatus.RESULT_UNKNOWN else DraftStatus.EDITING,
                    original = if (unknown || rateLimited) pending.original else null,
                    retryNotBefore = if (rateLimited) now() + (e.retryAfterSeconds ?: 60).coerceAtLeast(0) * 1000 else pending.retryNotBefore)
                storeDraft(kept)
                val message = when {
                    unknown -> "保存结果未知；草稿已保留，请用原请求重试确认结果"
                    e.code == "PARENT_UNAVAILABLE" || e.code == "NOT_FOUND" -> "记录或父餐厅不可用；请先恢复或刷新，草稿已保留"
                    e.code == "RATE_LIMITED" -> "请求过于频繁，请在 ${e.retryAfterSeconds ?: 60} 秒后再保存"
                    e.fieldErrors.isNotEmpty() -> "请检查输入：${e.fieldErrors.keys.joinToString("、", transform = ::fieldLabel)}；草稿已保留"
                    else -> "保存失败（${e.code}）；草稿已保留"
                }
                return@withLock SaveOutcome.Kept(kept, message)
            }
        } catch (e: AuthFailure) {
            if (e.code in setOf("NETWORK_ERROR", "TEMPORARILY_UNAVAILABLE", "RATE_LIMITED")) {
                val kept = pending.copy(status = DraftStatus.RESULT_UNKNOWN)
                storeDraft(kept); return@withLock SaveOutcome.Kept(kept, "保存结果未知；请联网后用原请求重试")
            } else throw e
        }
        // A failed statistics GET cannot turn an acknowledged write into an
        // unknown result or reinsert a successfully deleted draft.
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
    private suspend fun reviewExpired(draft: SavedDraft): SaveOutcome {
        val current = try { refreshDetail(draft.accountId, draft.ref) } catch (e: ProductFailure) {
            if (e.code == "NOT_FOUND") null else {
                storeDraft(draft)
                return SaveOutcome.Kept(draft, "原请求已超过 7 天；暂时无法读取服务器，草稿保留且没有重放")
            }
        }
        val review = draft.copy(status = DraftStatus.EXPIRED_REVIEW)
        storeDraft(review)
        return SaveOutcome.Expired(review, current)
    }
    override suspend fun confirmExpired(draft: SavedDraft, current: RecordSnapshot?): SaveOutcome {
        check(draft.status == DraftStatus.EXPIRED_REVIEW)
        if (current?.deletedAt != null || draft.action in setOf(WriteAction.DELETE, WriteAction.RESTORE) || current == null && draft.action != WriteAction.CREATE) return SaveOutcome.Kept(draft, "请先恢复或刷新，再从当前详情发起操作；草稿保留")
        val next = draft.copy(baseSnapshot = current, original = null, status = DraftStatus.EDITING,
            action = if (draft.action == WriteAction.CREATE && current != null) WriteAction.UPDATE else draft.action,
            firstVisit = if (current == null) draft.firstVisit else null, updatedAt = now())
        if (current != null && next.changedFields.isEmpty()) {
            discardDraft(draft.accountId, draft.id); return SaveOutcome.Saved(current)
        }
        storeDraft(next)
        return submit(next)
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
        record.fields["address"]?.let { fields["address_excerpt"] = it.take(120) }
        record.fields["review"]?.let { fields["review_excerpt"] = it.take(120) }
        record.fields["note"]?.let { fields["note_excerpt"] = it.take(120) }
        val brief = RecordSummary(record.ref, record.title, record.version,
            if (record.ref.kind == RecordKind.REVISION) record.fields["created_at"] ?: record.updatedAt else record.updatedAt,
            (old?.fields ?: emptyMap()) + fields, old?.matches ?: emptyList(), record.diff.map { it.field }.ifEmpty { old?.changedFields ?: emptyList() }, record.deletedAt != null)
        cacheSummary(accountId, brief)
        dao.cacheDetail(DetailRow(accountId, record.ref.kind.name, record.ref.id, record.version, ProductJson.encodeToString(record), now()))
    }
}
