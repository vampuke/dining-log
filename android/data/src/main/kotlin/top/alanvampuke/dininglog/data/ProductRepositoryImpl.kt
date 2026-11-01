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
        if (cached != null) {
            val parents = summaries.filter { it.ref.kind == RecordKind.RESTAURANT && it.deleted }.map { it.ref.id }.toSet()
            val rows = page.map { ProductJson.decodeFromString<RecordSummary>(it.payload) }.filter {
                it.deleted == (query.deleted == DeletedFilter.ONLY) && it.ref.restaurantId !in parents
            }
            CachedPage(deduplicate(rows), cached.cursor, cached.hasMore, cached.fetchedAt, false)
        } else CachedPage(localSearch(query, summaries, details.map { ProductJson.decodeFromString<RecordSnapshot>(it.payload) }),
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
            dao.putMembers(ids.mapIndexed { position, id -> PageMember(accountId, query.fingerprint, query.kind.name, id, position) })
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
        if (DraftRules.needsReview(pending, now()) || pending.status == DraftStatus.EXPIRED_REVIEW) {
            return@withLock reviewExpired(pending)
        }
        pending = requestFor(pending, now())
        owner.write(account, pending.accountId, generation) { putDraft(pending) }
        try {
            val data = remote.write(pending)
            val raw = (data["entity"] as? JsonObject) ?: data
            val partial = snapshot(pending.ref.kind, raw, pending.ref.restaurantId)
            val baseline = pending.baseSnapshot
            val saved = partial.copy(fields = (baseline?.fields ?: emptyMap()) + partial.fields,
                diff = if (partial.diff.isEmpty()) baseline?.diff ?: emptyList() else partial.diff)
            owner.write(account, pending.accountId, generation) {
                cacheSnapshot(pending.accountId, saved)
                (data["first_visit"] as? JsonObject)?.let { cacheSnapshot(pending.accountId, snapshot(RecordKind.VISIT, it, saved.ref.id)) }
                (data["revisions"] as? JsonArray)?.forEach { cacheSummary(pending.accountId, summary(RecordKind.REVISION, it.jsonObject)) }
                (data["revision"] as? JsonObject)?.let { cacheSnapshot(pending.accountId, snapshot(RecordKind.REVISION, it)) }
                dao.deleteDraft(pending.accountId, pending.id)
                dao.invalidateQueries(pending.accountId); dao.invalidateMembers(pending.accountId)
            }
            // Child writes don't increment restaurant.version; stats must be re-read.
            val parent = if (pending.ref.kind == RecordKind.RESTAURANT) pending.ref.id else pending.ref.restaurantId
            if (parent != null && saved.deletedAt == null && pending.ref.kind != RecordKind.REVISION) {
                try { refreshDetail(pending.accountId, RecordRef(RecordKind.RESTAURANT, parent)) }
                catch (e: CancellationException) { throw e }
                catch (e: AuthFailure) { throw e }
                catch (_: ProductFailure) { /* Primary write is committed; keep the cache and allow manual refresh. */ }
            }
            SaveOutcome.Saved(saved)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { owner.write(account, pending.accountId, generation) { putDraft(pending.copy(status = DraftStatus.RESULT_UNKNOWN)) } } }
            throw e
        } catch (e: ProductFailure) {
            if (e.code == "VERSION_CONFLICT" && e.current != null) {
                val preserved = pending.copy(status = DraftStatus.EDITING, original = null)
                storeDraft(preserved)
                SaveOutcome.Conflict(preserved, compareConflict(preserved, e.current.copy(ref = pending.ref)))
            } else {
                val unknown = e.offline || e.status >= 500
                val kept = pending.copy(status = if (unknown) DraftStatus.RESULT_UNKNOWN else DraftStatus.EDITING,
                    original = if (unknown) pending.original else null)
                storeDraft(kept)
                val message = when {
                    unknown -> "保存结果未知；草稿已保留，请用原请求重试确认结果"
                    e.code == "PARENT_UNAVAILABLE" || e.code == "NOT_FOUND" -> "记录或父餐厅不可用；请先恢复或刷新，草稿已保留"
                    e.code == "RATE_LIMITED" -> "请求过于频繁，请在 ${e.retryAfterSeconds ?: 60} 秒后再保存"
                    e.fieldErrors.isNotEmpty() -> "请检查输入：${e.fieldErrors.keys.joinToString("、", transform = ::fieldLabel)}；草稿已保留"
                    else -> "保存失败（${e.code}）；草稿已保留"
                }
                SaveOutcome.Kept(kept, message)
            }
        } catch (e: AuthFailure) {
            if (e.code in setOf("NETWORK_ERROR", "TEMPORARILY_UNAVAILABLE", "RATE_LIMITED")) {
                val kept = pending.copy(status = DraftStatus.RESULT_UNKNOWN)
                storeDraft(kept); SaveOutcome.Kept(kept, "保存结果未知；请联网后用原请求重试")
            } else throw e
        }
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
        if (current?.deletedAt != null || draft.action in setOf(WriteAction.DELETE, WriteAction.RESTORE)) return SaveOutcome.Kept(draft, "请先恢复或刷新，再从当前详情发起操作；草稿保留")
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
        dao.cacheSummary(SummaryRow(accountId, record.ref.kind.name, record.ref.id, record.version, ProductJson.encodeToString(record), now()))
    }
    private suspend fun cacheSnapshot(accountId: String, record: RecordSnapshot) {
        val old = dao.summary(accountId, record.ref.kind.name, record.ref.id)?.let { ProductJson.decodeFromString<RecordSummary>(it.payload) }
        val fields = record.fields.toMutableMap()
        fields.remove("review"); fields.remove("note"); fields.remove("address")
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
