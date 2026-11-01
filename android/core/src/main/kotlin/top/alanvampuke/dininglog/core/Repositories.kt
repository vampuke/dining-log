package top.alanvampuke.dininglog.core

import kotlinx.coroutines.flow.Flow

interface RestaurantRepository {
    fun observeList(accountId: String, query: ListQuery): Flow<CachedPage>
    suspend fun refreshList(accountId: String, query: ListQuery, loadMore: Boolean = false)
}
interface DishRepository {
    fun observeDetail(accountId: String, ref: RecordRef): Flow<CachedDetail>
    suspend fun refreshDetail(accountId: String, ref: RecordRef): RecordSnapshot
}
interface VisitRepository {
    fun observeDrafts(accountId: String): Flow<List<SavedDraft>>
    suspend fun findDraft(accountId: String, draftId: String): SavedDraft?
    suspend fun storeDraft(draft: SavedDraft)
    suspend fun discardDraft(accountId: String, draftId: String)
}
interface RevisionRepository {
    suspend fun submit(draft: SavedDraft): SaveOutcome
    suspend fun confirmExpired(draft: SavedDraft, current: RecordSnapshot?): SaveOutcome
}
interface ProductRepository : RestaurantRepository, DishRepository, VisitRepository, RevisionRepository {
    val connectivity: Flow<Boolean>
}
data class AccountInfo(val id: String, val username: String, val displayName: String, val createdAt: String)
data class DeviceSession(val id: String, val deviceName: String, val createdAt: String,
    val lastSeenAt: String, val expiresAt: String, val current: Boolean)
interface AccountSecurity {
    suspend fun profile(): AccountInfo
    suspend fun sessions(): List<DeviceSession>
    suspend fun revokeSession(id: String)
    suspend fun changePassword(current: String, replacement: String)
}
enum class ThemeChoice { SYSTEM, LIGHT, DARK }
interface ThemePreferences {
    val theme: Flow<ThemeChoice>
    suspend fun setTheme(choice: ThemeChoice)
}
