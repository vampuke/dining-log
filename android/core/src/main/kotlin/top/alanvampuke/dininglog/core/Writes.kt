package top.alanvampuke.dininglog.core

import kotlinx.serialization.Serializable

@Serializable enum class WriteAction { CREATE, UPDATE, DELETE, RESTORE, METADATA, APPLY }
// Used by restaurant creation, including the optional first visit.
data class FirstVisitInput(val id: String, val date: String, val meal: String? = null, val note: String? = null)
data class OriginalRequest(val method: String, val path: String, val payload: String, val key: String, val startedAt: Long)
// Editor values live only in the account-owned ViewModel; never Room or saved state.
data class SaveInput(
    val accountId: String, val ref: RecordRef, val action: WriteAction,
    val baseSnapshot: RecordSnapshot?, val localFields: Map<String, String?>,
    val firstVisit: FirstVisitInput? = null, val sourceRevisionId: String? = null,
    val applySide: String = "before", val applyFields: List<String> = emptyList(),
) {
    val baseVersion: Int? get() = baseSnapshot?.version
    val changedFields: List<String> get() = localFields.keys.filter { localFields[it] != baseSnapshot?.fields?.get(it) }
}
data class ConflictField(val name: String, val base: String?, val server: String?, val local: String?, val requiresChoice: Boolean)
enum class ConflictChoice { SERVER, LOCAL }
data class ConflictComparison(val baseSnapshot: RecordSnapshot?, val serverCurrent: RecordSnapshot, val localInput: SaveInput,
    val fields: List<ConflictField>, val mergeAllowed: Boolean) {
    val needsChoice: Set<String> get() = fields.filter { it.requiresChoice }.map { it.name }.toSet()
    fun confirmed(choices: Map<String, ConflictChoice>): SaveInput {
        check(mergeAllowed); check(choices.keys.containsAll(needsChoice))
        val merged = fields.associate { row -> row.name to when (choices[row.name]) {
            ConflictChoice.SERVER -> row.server
            ConflictChoice.LOCAL -> row.local
            null -> if (row.local != row.base) row.local else row.server
        } }
        return localInput.copy(baseSnapshot = serverCurrent, localFields = merged,
            applyFields = localInput.applyFields.filter { merged[it] != serverCurrent.fields[it] })
    }
}
fun compareConflict(input: SaveInput, server: RecordSnapshot): ConflictComparison {
    val fields = editableFields(input.ref.kind).map { field ->
        val base = input.baseSnapshot?.fields?.get(field)
        val current = server.fields[field]; val local = input.localFields[field]
        ConflictField(field, base, current, local, local != base && current != base && current != local)
    }
    return ConflictComparison(input.baseSnapshot, server, input, fields,
        server.deletedAt == null && input.action !in setOf(WriteAction.DELETE, WriteAction.RESTORE))
}
sealed interface SaveOutcome {
    data class Saved(val snapshot: RecordSnapshot) : SaveOutcome
    data class Failed(val input: SaveInput, val message: String) : SaveOutcome
    data class Conflict(val input: SaveInput, val comparison: ConflictComparison) : SaveOutcome
}
class ProductFailure(val code: String, val status: Int = 0, val current: RecordSnapshot? = null,
    val retryAfterSeconds: Long? = null, val fieldErrors: Map<String, List<String>> = emptyMap()) : Exception(code) {
    val offline: Boolean get() = code == "NETWORK_ERROR"
}
