package top.alanvampuke.dininglog.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable enum class DraftStatus { EDITING, SAVING, RESULT_UNKNOWN, EXPIRED_REVIEW }
@Serializable enum class WriteAction { CREATE, UPDATE, DELETE, RESTORE, METADATA, APPLY }
@Serializable data class FirstVisitInput(val id: String, val date: String, val meal: String? = null, val note: String? = null)
@Serializable data class OriginalRequest(val method: String, val path: String, val payload: String, val key: String, val startedAt: Long)
@Serializable data class SavedDraft(
    val accountId: String, val id: String, val ref: RecordRef, val action: WriteAction,
    val baseSnapshot: RecordSnapshot?, val localFields: Map<String, String?>,
    val firstVisit: FirstVisitInput? = null, val sourceRevisionId: String? = null,
    val applySide: String = "before", val applyFields: List<String> = emptyList(),
    val status: DraftStatus = DraftStatus.EDITING, val original: OriginalRequest? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val baseVersion: Int? get() = baseSnapshot?.version
    val locked: Boolean get() = status != DraftStatus.EDITING
    val changedFields: List<String> get() = localFields.keys.filter { localFields[it] != baseSnapshot?.fields?.get(it) }
}
object DraftRules {
    const val RETRY_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    fun recovered(draft: SavedDraft) = if (draft.status == DraftStatus.SAVING) draft.copy(status = DraftStatus.RESULT_UNKNOWN) else draft
    fun edit(draft: SavedDraft, fields: Map<String, String?>, now: Long): SavedDraft {
        check(!draft.locked) { "Resolve the original request before changing content" }
        return draft.copy(localFields = fields, original = null, updatedAt = now)
    }
    fun needsReview(draft: SavedDraft, now: Long) = draft.original?.let { now - it.startedAt >= RETRY_WINDOW_MS } == true
    fun prepare(draft: SavedDraft, method: String, path: String, payload: String, now: Long,
        newKey: () -> String = { UUID.randomUUID().toString() }): SavedDraft {
        check(!needsReview(draft, now)) { "Read the server and ask for a new save after seven days" }
        val original = draft.original
        if (original != null) {
            check(original.method == method && original.path == path && original.payload == payload)
            return draft.copy(status = DraftStatus.SAVING)
        }
        return draft.copy(status = DraftStatus.SAVING, original = OriginalRequest(method, path, payload, newKey(), now), updatedAt = now)
    }
}
@OptIn(FlowPreview::class)
class DraftAutosaver(scope: CoroutineScope, private val save: suspend (SavedDraft) -> Unit) {
    private val pending = MutableStateFlow<SavedDraft?>(null)
    private val lock = Mutex()
    private val job: Job = scope.launch { pending.filterNotNull().debounce(500).collect { draft ->
        lock.withLock { if (pending.value == draft) save(draft) }
    } }
    fun schedule(draft: SavedDraft) { pending.value = draft }
    suspend fun flush() = lock.withLock { pending.value?.let { save(it) } }
    // Stop before submitting, so delayed input cannot overwrite a pending request.
    suspend fun stopAndFlush() { job.cancel(); flush(); pending.value = null }
    fun cancel() { job.cancel(); pending.value = null }
}
data class ConflictField(val name: String, val base: String?, val server: String?, val local: String?, val requiresChoice: Boolean)
enum class ConflictChoice { SERVER, LOCAL }
data class ConflictComparison(val baseSnapshot: RecordSnapshot?, val serverCurrent: RecordSnapshot, val localDraft: SavedDraft,
    val fields: List<ConflictField>, val mergeAllowed: Boolean) {
    val needsChoice: Set<String> get() = fields.filter { it.requiresChoice }.map { it.name }.toSet()
    fun confirmed(choices: Map<String, ConflictChoice>, now: Long): SavedDraft {
        check(mergeAllowed); check(choices.keys.containsAll(needsChoice))
        val merged = fields.associate { row -> row.name to when (choices[row.name]) {
            ConflictChoice.SERVER -> row.server
            ConflictChoice.LOCAL -> row.local
            null -> if (row.local != row.base) row.local else row.server
        } }
        return localDraft.copy(baseSnapshot = serverCurrent, localFields = merged, original = null,
            applyFields = localDraft.applyFields.filter { merged[it] != serverCurrent.fields[it] },
            status = DraftStatus.EDITING, updatedAt = now)
    }
}
fun compareConflict(draft: SavedDraft, server: RecordSnapshot): ConflictComparison {
    val fields = editableFields(draft.ref.kind).map { field ->
        val base = draft.baseSnapshot?.fields?.get(field)
        val current = server.fields[field]; val local = draft.localFields[field]
        ConflictField(field, base, current, local, local != base && current != base && current != local)
    }
    return ConflictComparison(draft.baseSnapshot, server, draft, fields,
        server.deletedAt == null && draft.action !in setOf(WriteAction.DELETE, WriteAction.RESTORE))
}
sealed interface SaveOutcome {
    data class Saved(val snapshot: RecordSnapshot) : SaveOutcome
    data class Kept(val draft: SavedDraft, val message: String) : SaveOutcome
    data class Conflict(val draft: SavedDraft, val comparison: ConflictComparison) : SaveOutcome
    data class Expired(val draft: SavedDraft, val serverCurrent: RecordSnapshot?) : SaveOutcome
}
class ProductFailure(val code: String, val status: Int = 0, val current: RecordSnapshot? = null,
    val retryAfterSeconds: Long? = null, val fieldErrors: Map<String, List<String>> = emptyMap()) : Exception(code) {
    val offline: Boolean get() = code == "NETWORK_ERROR"
}
