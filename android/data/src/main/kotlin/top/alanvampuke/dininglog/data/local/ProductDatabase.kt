package top.alanvampuke.dininglog.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.alanvampuke.dininglog.core.auth.AccountLocalData
import top.alanvampuke.dininglog.core.auth.AccountSession
import top.alanvampuke.dininglog.core.auth.AuthFailure

@Entity(tableName = "cached_summaries", primaryKeys = ["account_id", "kind", "entity_id"])
data class SummaryRow(@ColumnInfo(name = "account_id") val accountId: String, val kind: String,
    @ColumnInfo(name = "entity_id") val entityId: String, val version: Int, val payload: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long)
@Entity(tableName = "cached_details", primaryKeys = ["account_id", "kind", "entity_id"])
data class DetailRow(@ColumnInfo(name = "account_id") val accountId: String, val kind: String,
    @ColumnInfo(name = "entity_id") val entityId: String, val version: Int, val payload: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long, @ColumnInfo(name = "is_stale") val stale: Boolean = false)
@Entity(tableName = "cached_queries", primaryKeys = ["account_id", "fingerprint"])
data class QueryRow(@ColumnInfo(name = "account_id") val accountId: String, val fingerprint: String,
    val cursor: String?, @ColumnInfo(name = "has_more") val hasMore: Boolean,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long)
@Entity(tableName = "page_members", primaryKeys = ["account_id", "fingerprint", "kind", "entity_id"])
data class PageMember(@ColumnInfo(name = "account_id") val accountId: String, val fingerprint: String,
    val kind: String, @ColumnInfo(name = "entity_id") val entityId: String, val position: Int)
@Entity(tableName = "saved_drafts", primaryKeys = ["account_id", "draft_id"])
data class DraftRow(@ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "draft_id") val draftId: String, val kind: String,
    @ColumnInfo(name = "entity_id") val entityId: String, @ColumnInfo(name = "base_version") val baseVersion: Int?,
    @ColumnInfo(name = "base_snapshot") val baseSnapshot: String?,
    @ColumnInfo(name = "editable_payload") val editablePayload: String,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String?,
    val status: String, @ColumnInfo(name = "updated_at") val updatedAt: Long, val payload: String)

@Dao abstract class ProductDao {
    @Query("SELECT * FROM cached_summaries WHERE account_id=:accountId")
    abstract fun observeSummaries(accountId: String): Flow<List<SummaryRow>>
    @Query("SELECT * FROM cached_details WHERE account_id=:accountId")
    abstract fun observeDetails(accountId: String): Flow<List<DetailRow>>
    @Query("SELECT * FROM cached_details WHERE account_id=:accountId AND kind=:kind AND entity_id=:id")
    abstract fun observeDetail(accountId: String, kind: String, id: String): Flow<DetailRow?>
    @Query("SELECT * FROM cached_queries WHERE account_id=:accountId AND fingerprint=:fingerprint")
    abstract fun observeQuery(accountId: String, fingerprint: String): Flow<QueryRow?>
    @Query("SELECT * FROM cached_queries WHERE account_id=:accountId AND fingerprint=:fingerprint")
    abstract suspend fun query(accountId: String, fingerprint: String): QueryRow?
    @Query("SELECT s.* FROM cached_summaries s JOIN page_members p ON s.account_id=p.account_id AND s.kind=p.kind AND s.entity_id=p.entity_id WHERE p.account_id=:accountId AND p.fingerprint=:fingerprint ORDER BY p.position")
    abstract fun observePage(accountId: String, fingerprint: String): Flow<List<SummaryRow>>
    @Query("SELECT * FROM page_members WHERE account_id=:accountId AND fingerprint=:fingerprint ORDER BY position")
    abstract suspend fun members(accountId: String, fingerprint: String): List<PageMember>
    @Query("SELECT * FROM cached_details WHERE account_id=:accountId AND kind=:kind AND entity_id=:id")
    abstract suspend fun detail(accountId: String, kind: String, id: String): DetailRow?
    @Query("SELECT * FROM cached_summaries WHERE account_id=:accountId AND kind=:kind AND entity_id=:id")
    abstract suspend fun summary(accountId: String, kind: String, id: String): SummaryRow?
    @Upsert abstract suspend fun putSummary(row: SummaryRow)
    @Upsert abstract suspend fun putDetail(row: DetailRow)
    @Upsert abstract suspend fun putQuery(row: QueryRow)
    @Upsert abstract suspend fun putMembers(rows: List<PageMember>)
    @Query("DELETE FROM page_members WHERE account_id=:accountId AND fingerprint=:fingerprint")
    abstract suspend fun removeMembers(accountId: String, fingerprint: String)
    @Query("UPDATE cached_details SET is_stale=1 WHERE account_id=:accountId AND kind=:kind AND entity_id=:id AND version<:version")
    abstract suspend fun markDetailStale(accountId: String, kind: String, id: String, version: Int)
    @Query("DELETE FROM cached_queries WHERE account_id=:accountId")
    abstract suspend fun invalidateQueries(accountId: String)
    @Query("DELETE FROM page_members WHERE account_id=:accountId")
    abstract suspend fun invalidateMembers(accountId: String)
    @Query("SELECT * FROM saved_drafts WHERE account_id=:accountId ORDER BY updated_at DESC")
    abstract fun observeDrafts(accountId: String): Flow<List<DraftRow>>
    @Query("SELECT * FROM saved_drafts WHERE account_id=:accountId AND draft_id=:id")
    abstract suspend fun draft(accountId: String, id: String): DraftRow?
    @Upsert abstract suspend fun putDraft(row: DraftRow)
    @Query("DELETE FROM saved_drafts WHERE account_id=:accountId AND draft_id=:id")
    abstract suspend fun deleteDraft(accountId: String, id: String)
    @Query("DELETE FROM cached_summaries") abstract suspend fun clearSummaries()
    @Query("DELETE FROM cached_details") abstract suspend fun clearDetails()
    @Query("DELETE FROM cached_queries") abstract suspend fun clearQueries()
    @Query("DELETE FROM page_members") abstract suspend fun clearMembers()
    @Query("DELETE FROM saved_drafts") abstract suspend fun clearDrafts()
    @Transaction open suspend fun clearAccountData() {
        clearDrafts(); clearMembers(); clearQueries(); clearDetails(); clearSummaries()
    }
    @Transaction open suspend fun cacheSummary(row: SummaryRow) {
        val previous = summary(row.accountId, row.kind, row.entityId)
        if (previous == null || row.version >= previous.version) putSummary(row)
        markDetailStale(row.accountId, row.kind, row.entityId, row.version)
    }
    @Transaction open suspend fun cacheDetail(row: DetailRow) {
        val previous = detail(row.accountId, row.kind, row.entityId)
        if (previous == null || row.version >= previous.version) {
            val newer = summary(row.accountId, row.kind, row.entityId)?.version ?: row.version
            putDetail(row.copy(stale = newer > row.version))
        }
    }
}
@Database(entities = [SummaryRow::class, DetailRow::class, QueryRow::class, PageMember::class, DraftRow::class], version = 1, exportSchema = true)
abstract class ProductDatabase : RoomDatabase() { abstract fun products(): ProductDao }

// Shared between account cleanup and cache writes. Generation is checked under
// the same lock, so a late A response cannot repopulate Room after A logs out.
class OwnerCache(private val db: ProductDatabase) : AccountLocalData {
    private val mutex = Mutex()
    suspend fun <T> write(account: AccountSession, id: String, generation: Long, block: suspend () -> T): T = mutex.withLock {
        if (account.generation != generation || account.session.value?.userId != id) throw AuthFailure("SESSION_REVOKED")
        db.withTransaction { block() }
    }
    override suspend fun clear() = mutex.withLock { db.products().clearAccountData() }
}
