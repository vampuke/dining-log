package top.alanvampuke.dininglog.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin

// Debug-only, instance-owned instrumentation fixture. The data module already
// owns the locked DataStore dependency and its internal constructor. This keeps
// that constructor internal and adds no app dependency or release API.
class Pr18PreferenceFile(val file: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val preferences = AndroidThemePreferences.fromDataStore(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
    )
    suspend fun closeDataStore() { scope.coroutineContext[Job]!!.cancelAndJoin() }
}
