package top.alanvampuke.dininglog.product

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import top.alanvampuke.dininglog.*
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.core.auth.*
import top.alanvampuke.dininglog.data.Pr18PreferenceFile
import top.alanvampuke.dininglog.feature.components.BackgroundArgb
import top.alanvampuke.dininglog.feature.ProductViewModel
import top.alanvampuke.dininglog.feature.theme.*

internal fun pr18Release(code: Int = BuildConfig.VERSION_CODE + 1, name: String = "下一版本",
    bytes: ByteArray = "PR18 controlled package fixture".toByteArray()) = AppRelease(code, name,
    "https://github.com/vampuke/dining-log/releases/download/pr18-fixture-$code/app-release.apk",
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
    "修复输入与更新体验。\n".repeat(3), 1794038400)

internal class Pr18Versions : AppVersionGateway {
    val requests = AtomicInteger()
    private val responses = Channel<CompletableDeferred<AppRelease>>(Channel.UNLIMITED)
    fun enqueue(release: AppRelease? = null, failure: Exception? = null): CompletableDeferred<AppRelease> {
        val gate = CompletableDeferred<AppRelease>()
        if (release != null) gate.complete(release)
        if (failure != null) gate.completeExceptionally(failure)
        check(responses.trySend(gate).isSuccess)
        return gate
    }
    override suspend fun latest(): AppRelease { requests.incrementAndGet(); return responses.receive().await() }
}

internal class Pr18UpdateContext(base: Context) : ContextWrapper(base) {
    val directory = File(base.cacheDir, "pr18-update-${UUID.randomUUID()}").apply { mkdirs() }
    val downloads = AtomicInteger()
    val cancels = AtomicInteger()
    var onDownload: (() -> Unit)? = null
    override fun getCacheDir() = directory
    override fun startForegroundService(service: Intent): ComponentName {
        downloads.incrementAndGet(); onDownload?.invoke()
        return ComponentName(packageName, UpdateDownloadService::class.java.name)
    }
    override fun startService(service: Intent): ComponentName {
        cancels.incrementAndGet()
        return ComponentName(packageName, UpdateDownloadService::class.java.name)
    }
    override fun stopService(service: Intent) = true
}

internal class Pr18Transport(private val bytes: ByteArray) : UpdateTransport {
    val requests = AtomicInteger()
    override fun open(address: String): UpdateConnection {
        requests.incrementAndGet()
        return object : UpdateConnection {
            override val responseCode = 200
            override val contentLengthLong = bytes.size.toLong()
            override val inputStream get() = ByteArrayInputStream(bytes)
            override fun getHeaderField(name: String): String? = null
            override fun disconnect() {}
        }
    }
}

internal class Pr18Installer : UpdateInstallPlatform {
    val verifications = AtomicInteger()
    val completedVerifications = AtomicInteger()
    val installed = CopyOnWriteArrayList<File>()
    @Volatile var permission = false
    @Volatile var verificationGate: CompletableDeferred<Unit>? = null
    override suspend fun verify(file: File, expected: String): Boolean {
        verifications.incrementAndGet()
        verificationGate?.await()
        val result = verifyApkSha256(file, expected)
        completedVerifications.incrementAndGet()
        return result
    }
    override fun canRequestPackageInstalls(activity: Activity) = permission
    override fun startInstaller(activity: Activity, file: File) { installed += file }
}

internal fun pr18TokenStore(): SecureTokenStore {
    val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
    val keys = object : KeyManager {
        override fun encryptionKey() = key
        override fun existingKey() = key
    }
    val file = object : AtomicTokenFile {
        private var value: ByteArray? = null
        override fun read() = value?.clone()
        override fun replace(bytes: ByteArray) { value = bytes.clone() }
        override fun delete() { value = null }
    }
    return SecureTokenStore(file, AesGcmTokenCipher(keys, "pr18-instrumentation"))
}

internal class Pr18Auth : AuthGateway {
    val logins = AtomicInteger()
    val registrations = AtomicInteger()
    val recoveries = AtomicInteger()
    val refreshes = AtomicInteger()
    private val session = AuthSession("pr18-owner", "食记用户", "pr18-session", "pr18-access-fixture", "pr18-refresh-fixture")
    override suspend fun login(username: String, password: String): AuthSession { logins.incrementAndGet(); return session.copy(username = username) }
    override suspend fun register(username: String, password: String): AuthSession { registrations.incrementAndGet(); return session.copy(username = username, recoveryCodes = (1..8).map { "pr18-public-fixture-code-$it" }) }
    override suspend fun recover(username: String, code: String, password: String): AuthSession { recoveries.incrementAndGet(); return session.copy(username = username, recoveryCodes = (1..8).map { "pr18-public-fixture-code-$it" }) }
    override suspend fun refresh(token: String, requestId: String): AuthSession { refreshes.incrementAndGet(); return session }
    override suspend fun logout(token: String) {}
}

internal class Pr18Repository : ProductRepository {
    val parent = RecordRef(RecordKind.RESTAURANT, "pr18-restaurant")
    val dish = RecordRef(RecordKind.DISH, "pr18-dish", parent.id)
    val visit = RecordRef(RecordKind.VISIT, "pr18-visit", parent.id)
    val revision = RecordRef(RecordKind.REVISION, "pr18-revision", parent.id)
    private val time = "2026-11-07T08:00:00Z"
    val writes = CopyOnWriteArrayList<SaveInput>()
    val refreshes = AtomicInteger()
    val detailRequests = CopyOnWriteArrayList<RecordRef>()
    override val connectivity = MutableStateFlow(true)
    private val records = MutableStateFlow(listOf(
        RecordSnapshot(parent, 2, mapOf("name" to "兰巴赫街角小馆", "category" to "restaurant", "cuisine" to "川菜",
            "address" to "中山路 123 号", "rating_tenths" to "40", "review" to "记录完整评价。", "note" to "和朋友到店。",
            "dish_count" to "1", "visit_count" to "1", "last_visit_date" to "2026-11-07"), time),
        RecordSnapshot(dish, 1, mapOf("name" to "招牌披萨", "rating_tenths" to null, "review" to "花椒香"), time),
        RecordSnapshot(visit, 1, mapOf("dining_date" to "2026-11-07", "meal" to "dinner", "note" to "同日第二次到店"), time),
        RecordSnapshot(revision, 1, mapOf("entity_label" to "兰巴赫街角小馆", "entity_type" to "restaurant",
            "entity_id" to parent.id, "entity_version_before" to "1", "entity_version_after" to "2", "note" to "到店后改分"), time,
            diff = listOf(Change("rating_tenths", "39", "40"), Change("review", "原评价", "记录完整评价。"))),
    ) + (2..20).map { index -> RecordSnapshot(RecordRef(RecordKind.RESTAURANT, "pr18-row-$index"), 1,
        mapOf("name" to "小馆 $index", "category" to "restaurant", "address" to "街角 $index 号", "rating_tenths" to "39"), time) })

    fun snapshot(ref: RecordRef) = records.value.single { it.ref == ref }
    override fun observeList(accountId: String, query: ListQuery): Flow<CachedPage> = records.map { rows ->
        val items = rows.filter { it.ref.kind == query.kind &&
            (query.restaurantId == null || it.ref.restaurantId == query.restaurantId || it.ref.id == query.restaurantId) &&
            (if (query.deleted == DeletedFilter.ONLY) it.deletedAt != null else it.deletedAt == null) &&
            (query.query.isBlank() || it.fields.values.any { text -> text?.contains(query.query) == true })
        }.map { record -> RecordSummary(record.ref, record.title, record.version, record.updatedAt,
            record.fields + mapOf("address_excerpt" to record.fields["address"], "review_excerpt" to record.fields["review"],
                "note_excerpt" to record.fields["note"], "restaurant_name" to "兰巴赫街角小馆"),
            matches = if (query.query.isNotEmpty()) listOf(MatchType.RESTAURANT) else emptyList(),
            changedFields = record.diff.map { it.field }, cachedDiff = record.diff) }
        CachedPage(items, fetchedAt = 1794038400000, localScope = false)
    }
    override suspend fun refreshList(accountId: String, query: ListQuery, loadMore: Boolean) { refreshes.incrementAndGet() }
    override fun observeDetail(accountId: String, ref: RecordRef): Flow<CachedDetail> = records.map { rows -> CachedDetail(rows.find { it.ref == ref }) }
    override suspend fun refreshDetail(accountId: String, ref: RecordRef): RecordSnapshot { refreshes.incrementAndGet(); detailRequests += ref; return snapshot(ref) }
    override suspend fun submit(input: SaveInput): SaveOutcome {
        writes += input
        val before = records.value.find { it.ref == input.ref }
        val next = RecordSnapshot(input.ref, (before?.version ?: 0) + 1,
            (before?.fields ?: emptyMap()) + input.localFields, time,
            deletedAt = if (input.action == WriteAction.DELETE) time else null, diff = before?.diff ?: emptyList())
        records.value = records.value.filterNot { it.ref == input.ref } + next
        return SaveOutcome.Saved(next)
    }
}

internal class Pr18Runtime(
    context: Context,
    enabled: Boolean = true,
    style: ThemeStyle? = null,
    choice: ThemeChoice? = null,
    authenticated: Boolean = false,
    val preferencePath: File = File(context.cacheDir, "pr18-display-${UUID.randomUUID()}.preferences_pb"),
    val store: SecureTokenStore = pr18TokenStore(),
) {
    val preferenceFile = Pr18PreferenceFile(preferencePath)
    val preferences get() = preferenceFile.preferences
    val themeWrites = AtomicInteger()
    val styleWrites = AtomicInteger()
    private val uiPreferences = object : ThemePreferences {
        override val theme get() = preferences.theme
        override val style get() = preferences.style
        override suspend fun setTheme(choice: ThemeChoice) { themeWrites.incrementAndGet(); preferences.setTheme(choice) }
        override suspend fun setStyle(style: ThemeStyle) { styleWrites.incrementAndGet(); preferences.setStyle(style) }
    }
    val versions = Pr18Versions()
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val installScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val updateContext = Pr18UpdateContext(context)
    val bytes = "PR18 controlled package fixture".toByteArray()
    val transport = Pr18Transport(bytes)
    val installer = Pr18Installer()
    val account = AccountSession(store, AccountMemory())
    val auth = Pr18Auth()
    val refresh = RefreshCoordinator(store, account, auth)
    val repository = Pr18Repository()
    val passwordChanges = AtomicInteger()
    val security = object : AccountSecurity {
        override suspend fun profile() = AccountInfo("pr18-owner", "食记用户", "食记用户", "2026-11-07T08:00:00Z")
        override suspend fun changePassword(current: String, replacement: String) { passwordChanges.incrementAndGet() }
    }
    val permissionRequests = CopyOnWriteArrayList<Intent>()
    val updates: AppUpdateChecker
    val manager: InAppUpdates
    init {
        runBlocking {
            if (!enabled) preferences.setUpdateChecksEnabled(false)
            if (style != null) preferences.setStyle(style)
            if (choice != null) preferences.setTheme(choice)
            if (authenticated) account.install(auth.login("食记用户", "Pr18 fixture passphrase"))
        }
        updates = AppUpdateChecker(versions, preferences, BuildConfig.VERSION_CODE, ioScope)
        manager = InAppUpdates(updateContext, versions, transport, installer, installScope)
        updateContext.onDownload = { ioScope.launch { manager.download() } }
    }
    fun render(evidence: Pr18Evidence, adaptive: Boolean = false) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            MainActivityContent(evidence.compose.activity, store, account, auth, refresh, repository, security,
                uiPreferences, updates, manager, { permissionRequests += it })
        }
        if (adaptive) evidence.renderAdaptiveProduction(content) else evidence.renderProduction(content)
    }
    fun model(evidence: Pr18Evidence): ProductViewModel {
        var model: ProductViewModel? = null
        evidence.compose.waitUntil(15_000) {
            model = evidence.compose.activity.viewModelStore.get("product:pr18-owner:${account.generation}") as? ProductViewModel
            model != null
        }
        return model!!
    }
    fun assertPalette(evidence: Pr18Evidence, expectedStyle: ThemeStyle, night: Boolean, authenticated: Boolean = true) {
        val expected = when (expectedStyle) {
            ThemeStyle.INK_PAPER -> if (night) InkPaperDark else InkPaperLight
            ThemeStyle.QUIET_GRID -> if (night) QuietGridDark else QuietGridLight
        }
        evidence.compose.waitUntil(15_000) {
            (evidence.compose.activity.window.decorView.background as? ColorDrawable)?.color == expected.bg.toArgb()
        }
        val actualWindow = (evidence.compose.activity.window.decorView.background as ColorDrawable).color
        val actualNavigation = if (authenticated) evidence.target("product-navigation").fetchSemanticsNode().config[BackgroundArgb] else null
        evidence.observedPalette(expectedStyle, night)
        evidence.record("production-render requestedStyle=$expectedStyle night=$night windowArgb=$actualWindow navigationArgb=$actualNavigation " +
            "expectedBgArgb=${expected.bg.toArgb()}")
        assertEquals("production Activity window palette", expected.bg.toArgb(), actualWindow)
        if (authenticated) assertEquals("production navigation palette", expected.bg.toArgb(), actualNavigation)
    }
    fun close(deletePreference: Boolean = true) = runBlocking {
        ioScope.coroutineContext[Job]!!.cancelAndJoin()
        installScope.coroutineContext[Job]!!.cancelAndJoin()
        account.ownerScope.coroutineContext[Job]!!.cancelAndJoin()
        preferenceFile.closeDataStore()
        updateContext.directory.deleteRecursively()
        if (deletePreference) preferencePath.delete()
    }
}
