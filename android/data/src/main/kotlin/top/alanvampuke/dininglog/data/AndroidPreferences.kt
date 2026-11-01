package top.alanvampuke.dininglog.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import top.alanvampuke.dininglog.core.*

private val Context.displaySettings by preferencesDataStore("display-settings")
class AndroidThemePreferences(context: Context) : ThemePreferences {
    private val data = context.applicationContext.displaySettings
    private val themeKey = stringPreferencesKey("theme")
    override val theme = data.data.map { preferences -> ThemeChoice.entries.firstOrNull { it.name == preferences[themeKey] } ?: ThemeChoice.SYSTEM }
    override suspend fun setTheme(choice: ThemeChoice) { data.edit { it[themeKey] = choice.name } }
}
fun connectivity(context: Context, scope: CoroutineScope): StateFlow<Boolean> {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    fun online(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    return callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(online()) }
            override fun onLost(network: Network) { trySend(online()) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(online()) }
        }
        manager.registerDefaultNetworkCallback(callback); trySend(online())
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, online())
}
