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
interface RevisionRepository {
    suspend fun submit(input: SaveInput): SaveOutcome
}
interface ProductRepository : RestaurantRepository, DishRepository, RevisionRepository {
    val connectivity: Flow<Boolean>
}
data class AccountInfo(val id: String, val username: String, val displayName: String, val createdAt: String)
data class DeviceSession(val id: String, val deviceName: String, val createdAt: String,
    val lastSeenAt: String, val expiresAt: String, val current: Boolean)
interface AccountSecurity {
    suspend fun profile(): AccountInfo
    suspend fun changePassword(current: String, replacement: String)
}
enum class ThemeChoice { SYSTEM, LIGHT, DARK }
// InkPaper ("纸墨") is the default style; QuietGrid is the classic style.
// The style is orthogonal to ThemeChoice (light/dark): each style has a
// light and a dark palette, resolved by the theme entry point.
enum class ThemeStyle { INK_PAPER, QUIET_GRID }
interface ThemePreferences {
    val theme: Flow<ThemeChoice>
    suspend fun setTheme(choice: ThemeChoice)
    val style: Flow<ThemeStyle>
    suspend fun setStyle(style: ThemeStyle)
}
