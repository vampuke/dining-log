package top.alanvampuke.dininglog.core

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AppRelease(val versionCode: Int, val versionName: String, val url: String,
    val sha256: String, val notes: String, val releasedAt: Long) {
    val valid: Boolean get() = versionCode > 0 && versionName.isNotBlank() && versionName.length <= 80 &&
        sha256.matches(Regex("[a-fA-F0-9]{64}")) && notes.length <= 4000 && releasedAt > 0 && runCatching {
            val address = URI(url)
            address.scheme == "https" && !address.host.isNullOrBlank() && address.userInfo == null
        }.getOrDefault(false)
}
data class AppUpdateState(val enabled: Boolean = true, val release: AppRelease? = null)
interface AppVersionGateway { suspend fun latest(): AppRelease }
interface UpdatePreferences {
    val updateChecksEnabled: Flow<Boolean>
    suspend fun setUpdateChecksEnabled(enabled: Boolean)
}

class AppUpdateChecker(gateway: AppVersionGateway, private val preferences: UpdatePreferences,
    installedVersionCode: Int, scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    init {
        scope.launch {
            // Wait for persisted preferences before making any network request.
            preferences.updateChecksEnabled.catch { emit(false) }.distinctUntilChanged().collectLatest { enabled ->
                mutableState.value = AppUpdateState(enabled)
                if (enabled) {
                    try {
                        val release = gateway.latest()
                        if (release.valid && isNewerVersion(release.versionCode, installedVersionCode)) {
                            mutableState.value = AppUpdateState(true, release)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* A background version check never interrupts the UI. */ }
                }
            }
        }
    }
    suspend fun setEnabled(enabled: Boolean) { preferences.setUpdateChecksEnabled(enabled) }
}
