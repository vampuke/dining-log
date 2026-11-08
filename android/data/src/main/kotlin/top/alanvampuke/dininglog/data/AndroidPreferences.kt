package top.alanvampuke.dininglog.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import top.alanvampuke.dininglog.core.*

private val Context.displaySettings by preferencesDataStore("display-settings")
class AndroidThemePreferences private constructor(private val data: DataStore<Preferences>) : ThemePreferences, UpdatePreferences {
    constructor(context: Context) : this(context.applicationContext.displaySettings)
    companion object {
        internal fun fromDataStore(data: DataStore<Preferences>) = AndroidThemePreferences(data)
    }
    private val themeKey = stringPreferencesKey("theme")
    private val styleKey = stringPreferencesKey("theme-style")
    private val updatesKey = booleanPreferencesKey("update-checks")
    override val theme = data.data.map { preferences -> ThemeChoice.entries.firstOrNull { it.name == preferences[themeKey] } ?: ThemeChoice.SYSTEM }
    override suspend fun setTheme(choice: ThemeChoice) { data.edit { it[themeKey] = choice.name } }
    override val style = data.data.map { preferences -> ThemeStyle.entries.firstOrNull { it.name == preferences[styleKey] } ?: ThemeStyle.INK_PAPER }
    override suspend fun setStyle(style: ThemeStyle) { data.edit { it[styleKey] = style.name } }
    override val updateChecksEnabled = data.data.map { it[updatesKey] ?: true }
    override suspend fun setUpdateChecksEnabled(enabled: Boolean) { data.edit { it[updatesKey] = enabled } }
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
