package top.alanvampuke.dininglog.core

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

data class AppRelease(val versionCode: Int, val versionName: String, val url: String,
    val sha256: String, val notes: String, val releasedAt: Long) {
    val valid: Boolean get() = versionCode > 0 && versionName.isNotBlank() && versionName.length <= 80 &&
        sha256.matches(Regex("[a-fA-F0-9]{64}")) && notes.length <= 4000 && releasedAt > 0 && runCatching {
            val address = URI(url)
            address.scheme == "https" && !address.host.isNullOrBlank() && address.userInfo == null
        }.getOrDefault(false)
}
// The startup preference does not hide results explicitly requested by the user.
// startupRelease retains only the first persisted preference's automatic offer.
data class AppUpdateState(val enabled: Boolean = true, val release: AppRelease? = null,
    val checking: Boolean = false, val upToDate: Boolean = false,
    val failed: Boolean = false, val manual: Boolean = false,
    val startupRelease: AppRelease? = null)
interface AppVersionGateway { suspend fun latest(): AppRelease }
interface UpdatePreferences {
    val updateChecksEnabled: Flow<Boolean>
    suspend fun setUpdateChecksEnabled(enabled: Boolean)
}

class AppUpdateChecker(private val gateway: AppVersionGateway, private val preferences: UpdatePreferences,
    private val installedVersionCode: Int, scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(AppUpdateState())
    private val requestLock = Mutex()
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    init {
        scope.launch {
            var firstPreference = true
            // Wait for persisted preferences before making any network request.
            preferences.updateChecksEnabled.catch { emit(false) }.distinctUntilChanged().collectLatest { enabled ->
                val startup = firstPreference
                firstPreference = false
                mutableState.update { current ->
                    if (current.manual) current.copy(enabled = enabled)
                    else current.copy(enabled = enabled, release = null, upToDate = false, failed = false)
                }
                if (enabled) check(manual = false, startup = startup)
            }
        }
    }
    suspend fun setEnabled(enabled: Boolean) { preferences.setUpdateChecksEnabled(enabled) }
    suspend fun checkNow() { check(manual = true) }

    // Both sources share one guard. A duplicate call returns without owning or
    // clearing the active request's state; preference changes cancel only the
    // collector's background request, never an independently launched manual one.
    private suspend fun check(manual: Boolean, startup: Boolean = false) {
        if (!requestLock.tryLock()) return
        try {
            // A pending background request does not change the provenance of
            // an existing manual result. Only a valid response replaces it.
            mutableState.update { it.copy(checking = true, upToDate = false, failed = false, manual = it.manual || manual) }
            val release = gateway.latest()
            currentCoroutineContext().ensureActive()
            require(release.valid) { "Invalid app release metadata" }
            val newer = isNewerVersion(release.versionCode, installedVersionCode)
            mutableState.update { it.copy(release = if (newer) release else null,
                checking = false, upToDate = manual && !newer, failed = false, manual = manual,
                startupRelease = if (startup && newer) release else it.startupRelease) }
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(checking = false, upToDate = false) }
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(checking = false, upToDate = false, failed = manual) }
        } finally { requestLock.unlock() }
    }
}
