package top.alanvampuke.dininglog

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.core.auth.*
import top.alanvampuke.dininglog.data.*
import top.alanvampuke.dininglog.data.auth.*
import top.alanvampuke.dininglog.data.local.*
import top.alanvampuke.dininglog.data.remote.*

@Module @InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): ProductDatabase = Room.databaseBuilder(context, ProductDatabase::class.java, "dining-cache.db").build()
    @Provides @Singleton fun owner(database: ProductDatabase) = OwnerCache(database)
    @Provides @Singleton fun tokens(@ApplicationContext context: Context) = secureTokenStore(context)
    @Provides @Singleton fun account(store: SecureTokenStore, owner: OwnerCache) = AccountSession(store, owner)
    @Provides @Singleton fun auth(): AuthGateway = RetrofitAuthGateway.create()
    @Provides @Singleton fun refresh(store: SecureTokenStore, account: AccountSession, gateway: AuthGateway) = RefreshCoordinator(store, account, gateway)
    @Provides @Singleton fun remote(refresh: RefreshCoordinator) = ProductRemote.create(ApiConfiguration.BASE_URL, refresh)
    @Provides @Singleton fun products(@ApplicationContext context: Context, database: ProductDatabase, owner: OwnerCache, account: AccountSession, remote: ProductRemote): ProductRepository =
        ProductRepositoryImpl(database, owner, account, remote, connectivity(context, CoroutineScope(SupervisorJob() + Dispatchers.Default)))
    @Provides @Singleton fun security(refresh: RefreshCoordinator, account: AccountSession): AccountSecurity = RetrofitAccountSecurity.create(ApiConfiguration.BASE_URL, refresh, account)
    @Provides @Singleton fun preferences(@ApplicationContext context: Context) = AndroidThemePreferences(context)
    @Provides fun themePreferences(preferences: AndroidThemePreferences): ThemePreferences = preferences
    @Provides fun updatePreferences(preferences: AndroidThemePreferences): UpdatePreferences = preferences
    @Provides @Singleton fun versions(): AppVersionGateway = PublicAppVersionGateway.create(ApiConfiguration.BASE_URL)
    @Provides @Singleton fun updates(versions: AppVersionGateway, preferences: UpdatePreferences) =
        AppUpdateChecker(versions, preferences, BuildConfig.VERSION_CODE, CoroutineScope(SupervisorJob() + Dispatchers.IO))
}
