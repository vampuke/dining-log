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
// checking/upToDate support a manual "检查更新" button: without them a
// manual check that finds nothing would show no feedback at all. Both
// default to false, so existing AppUpdateState(...) constructions and
// equality in tests are unchanged.
data class AppUpdateState(val enabled: Boolean = true, val release: AppRelease? = null,
    val checking: Boolean = false, val upToDate: Boolean = false)
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
    // Manual check, independent of the startup-check preference: a user who
    // turned startup checks off still needs a way to ask. Runs the same
    // gateway and validity rules as the background check; a failure leaves
    // the previous state alone instead of surfacing a blocking error.
    suspend fun checkNow() {
        val enabled = mutableState.value.enabled
        mutableState.value = mutableState.value.copy(checking = true, upToDate = false)
        try {
            val release = gateway.latest()
            mutableState.value = if (release.valid && isNewerVersion(release.versionCode, installedVersionCode))
                AppUpdateState(enabled, release)
            else AppUpdateState(enabled, null, checking = false, upToDate = true)
        } catch (cancelled: CancellationException) {
            mutableState.value = mutableState.value.copy(checking = false); throw cancelled
        } catch (_: Exception) { mutableState.value = mutableState.value.copy(checking = false) }
    }
}
