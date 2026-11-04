package top.alanvampuke.dininglog.product

import top.alanvampuke.dininglog.BuildConfig

import android.content.Context
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.core.auth.*
import top.alanvampuke.dininglog.data.*
import top.alanvampuke.dininglog.data.auth.*
import top.alanvampuke.dininglog.data.local.*
import top.alanvampuke.dininglog.data.remote.*
import top.alanvampuke.dininglog.feature.*
import top.alanvampuke.dininglog.feature.auth.*
import top.alanvampuke.dininglog.feature.components.ImeBottomPx
import top.alanvampuke.dininglog.feature.theme.*

// This test uses the real, unmodified Worker and isolated local D1 running on
// GitHub Actions. Offline and response loss are explicit transport injections.
@RunWith(AndroidJUnit4::class)
class ProductEndToEndTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val baseUrl = "http://10.0.2.2:8787/api/v1/"
    private val username = "p4_" + UUID.randomUUID().toString().replace("-", "").take(20)
    private val password = "CITest-Dining-P4!2026"
    private val newPassword = "CITest-New-Dining-P4!2026"
    private lateinit var vm: ProductViewModel
    private lateinit var account: AccountSession
    private lateinit var direct: ProductRemote
    private val online = MutableStateFlow(true)
    private val dropResponse = AtomicBoolean(false)
    private data class WireWrite(val path: String, val key: String?, val payload: String)
    private val writes = CopyOnWriteArrayList<WireWrite>()
    private var phase = "registration"

    @Test fun plan104RegistrationThroughOfflineConflictRestorePasswordAndLogin() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ProductDatabase::class.java).build()
        val alias = "dining-log.p4-e2e.${UUID.randomUUID()}"
        val tokenFile = AndroidAtomicTokenFile(File(context.noBackupFilesDir, "p4-e2e.enc"))
        val store = SecureTokenStore(tokenFile, AesGcmTokenCipher(KeystoreKeyManager(alias), context.packageName))
        val owner = OwnerCache(db)
        account = AccountSession(store, owner)
        val gateway = RetrofitAuthGateway.create(baseUrl)
        val refresh = RefreshCoordinator(store, account, gateway)
        direct = ProductRemote.create(baseUrl, refresh)
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) {
                    android.util.Log.e("DiningP4", "transport ${call.request().method} ${call.request().url.encodedPath}: ${ioe.javaClass.simpleName}: ${ioe.message}; cause=${ioe.cause}")
                }
            })
            .addInterceptor { chain ->
                // Wrangler's local HTTP preview closes idle keep-alive sockets.
                // A fresh fixture connection avoids that proxy EOF race while
                // retaining the deliberate response-loss test and no write retry.
                val request = chain.request().newBuilder().header("Connection", "close").build()
                if (!online.value) throw IOException("P4 injected offline transport")
                val write = request.method in setOf("POST", "PATCH", "DELETE")
                if (write) {
                    val buffer = Buffer(); request.body?.writeTo(buffer)
                    writes += WireWrite(request.url.encodedPath, request.header("Idempotency-Key"), buffer.readUtf8())
                }
                val response = chain.proceed(request)
                if (write && dropResponse.compareAndSet(true, false)) { response.close(); throw IOException("P4 injected response loss after server commit") }
                response
            }.build()
        val api = Retrofit.Builder().baseUrl(baseUrl).client(client).addConverterFactory(ProductJson.asConverterFactory("application/json".toMediaType())).build().create(ProductApi::class.java)
        val actualRepository = ProductRepositoryImpl(db, owner, account, ProductRemote(api, refresh), online)
        val security = RetrofitAccountSecurity.create(baseUrl, refresh, account)
        var copied = emptyList<String>()
        try {
            compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
            compose.setContent { QuietGridTheme(true) {
                AuthRoot(account, gateway, onRestore = {}, onLogout = {
                    try { account.session.value?.refreshToken?.let { gateway.logout(it) }; null }
                    finally { account.clear() }
                }, onCopyRecoveryCodes = { copied = it }, onAuthenticated = { session, logout ->
                    val model = remember(session.userId, account.generation) { ProductViewModel(session.userId, actualRepository, security, account) }
                    vm = model
                    ProductRoot(model, session.username, ThemeChoice.DARK, {}, logout, version = BuildConfig.VERSION_NAME + " debug")
                })
            } }
            compose.onNodeWithTag("auth-submit").assertTextEquals("登录")
            assertSecureWindow(true, "login")
            compose.onNodeWithText("恢复账号").performScrollTo().performClick()
            compose.onNodeWithTag("auth-recovery").assertExists()
            assertSecureWindow(true, "account recovery")
            compose.onNodeWithText("注册账号").performScrollTo().performClick()
            compose.onNodeWithTag("auth-submit").assertTextEquals("注册账号")
            assertSecureWindow(true, "registration")
            compose.onNodeWithTag("auth-username").performScrollTo().performTextInput(username)
            compose.onNodeWithTag("auth-password").performScrollTo().performTextInput(password)
            compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
            waitFor { account.session.value != null }
            compose.onNodeWithText("复制恢复码").performScrollTo().performClick()
            assertEquals(8, copied.size); assertEquals(8, copied.toSet().size)
            assertSecureWindow(true, "one-time recovery codes")
            compose.onNodeWithText("我已安全保存").performScrollTo().performClick()
            waitFor { ::vm.isInitialized && !vm.list.value.isRefreshing && !vm.list.value.localScope }
            // Server data can be ready while registration IME resizing still
            // leaves this lazy item outside the viewport on API 26.
            scroll("empty-state")
            compose.onNodeWithTag("empty-state").assertExists().assertIsDisplayed()
                .assertTextEquals("还没有餐厅，记下第一家吧")
            compose.onNodeWithTag("screen:restaurant:list").assertExists()
            assertSecureWindow(false, "restaurant list after recovery-code acknowledgement")

            // New restaurant + first visit is an atomic create, no visit binding on dishes.
            phase = "create restaurant and first visit"
            tap("add"); waitFor { vm.editor.value.input != null }
            assertSecureWindow(false, "restaurant editor")
            type("field:name", "P4兰记川味小馆")
            type("field:cuisine", "川菜")
            type("field:address", "上海愚园路123号")
            type("field:review", "餐厅评价鸡块酥脆")
            type("field:note", "笔记和朋友一起到店")
            progress("rating", 4.0f)
            tap("first-visit-toggle")
            type("field:first-visit-date", "2026-11-01")
            tap("save"); waitFor { vm.detail.value.cached.snapshot?.title == "P4兰记川味小馆" && !vm.detail.value.refreshing }
            val restaurantId = vm.detail.value.cached.snapshot!!.ref.id
            val restaurantRef = RecordRef(RecordKind.RESTAURANT, restaurantId)
            assertSecureWindow(false, "restaurant detail")
            assertEquals("1", server(restaurantRef).fields["visit_count"])
            assertEquals("40", server(restaurantRef).fields["rating_tenths"])

            tap("add-dish"); waitFor { vm.editor.value.input?.ref?.kind == RecordKind.DISH }
            type("field:name", "辣子鸡"); type("field:review", "菜品短评花椒香"); progress("rating", 4.7f)
            tap("save"); waitFor { vm.detail.value.cached.snapshot?.ref?.kind == RecordKind.DISH && !vm.detail.value.refreshing }
            val dishRef = vm.detail.value.cached.snapshot!!.ref
            assertEquals(restaurantId, vm.detail.value.cached.snapshot!!.ref.restaurantId)
            assertFalse(writes.last().payload.contains("visit_id"))
            tap("back"); waitFor { vm.detail.value.cached.snapshot?.ref == restaurantRef && !vm.detail.value.refreshing }
            tap("add-visit"); waitFor { vm.editor.value.input?.ref?.kind == RecordKind.VISIT }
            type("field:dining_date", "2026-11-01"); type("field:note", "同一天第二次到店")
            tap("save"); waitFor { vm.detail.value.cached.snapshot?.ref?.kind == RecordKind.VISIT && !vm.detail.value.refreshing }
            val visitRef = vm.detail.value.cached.snapshot!!.ref
            tap("back"); waitFor { vm.detail.value.cached.snapshot?.ref == restaurantRef && !vm.detail.value.refreshing }
            assertEquals("2", server(restaurantRef).fields["visit_count"])

            // Aggregated home search returns restaurants and explicit hit types.
            phase = "aggregated search and filters"
            tap("tab:restaurants"); waitFor { vm.list.value.query.kind == RecordKind.RESTAURANT && !vm.list.value.isRefreshing }
            for ((term, match) in listOf("P4兰记" to MatchType.RESTAURANT, "川菜" to MatchType.RESTAURANT, "愚园路" to MatchType.RESTAURANT,
                "辣子鸡" to MatchType.DISH, "花椒香" to MatchType.REVIEW, "鸡块酥脆" to MatchType.REVIEW, "和朋友" to MatchType.NOTE)) {
                type("search", term)
                waitFor { vm.list.value.query.query == term && !vm.list.value.isRefreshing && vm.list.value.items.any { it.ref.id == restaurantId } }
                val result = vm.list.value.items.single { it.ref.id == restaurantId }
                assertEquals(RecordKind.RESTAURANT, result.ref.kind); assertTrue(match in result.matches)
                scroll("matches:$restaurantId"); compose.onNodeWithTag("matches:$restaurantId", useUnmergedTree = true).assertTextContains(match.label)
            }
            type("search", ""); waitFor { vm.list.value.query.query.isEmpty() && !vm.list.value.isRefreshing }
            tap("filters"); choose("咖啡馆")
            waitFor { vm.list.value.query.filters.category == "cafe" && !vm.list.value.isRefreshing && vm.list.value.items.isEmpty() }
            compose.onNodeWithTag("record-list", useUnmergedTree = true).performScrollToNode(hasText("没有符合条件的结果"))
            compose.onNodeWithText("没有符合条件的结果").assertIsDisplayed()
            tap("filter-clear"); type("field:filter-cuisine", "川菜")
            waitFor { vm.list.value.query.filters.cuisine == "川菜" && !vm.list.value.isRefreshing && vm.list.value.items.singleOrNull()?.ref?.id == restaurantId }
            tap("filter-clear"); tap("filter-rating-range"); progress("filter-min", 4.5f)
            waitFor { vm.list.value.query.filters.minRating == 45 && !vm.list.value.isRefreshing && vm.list.value.items.isEmpty() }
            progress("filter-min", 4.0f); progress("filter-max", 4.0f)
            waitFor { vm.list.value.query.filters.maxRating == 40 && !vm.list.value.isRefreshing && vm.list.value.items.singleOrNull()?.ref?.id == restaurantId }
            tap("filter-unrated")
            waitFor { vm.list.value.query.filters.unrated && !vm.list.value.isRefreshing && vm.list.value.items.isEmpty() }
            tap("filter-clear"); choose("未到店")
            waitFor { vm.list.value.query.filters.visited == false && !vm.list.value.isRefreshing && vm.list.value.items.isEmpty() }
            choose("已到店")
            waitFor { vm.list.value.query.filters.visited == true && !vm.list.value.isRefreshing && vm.list.value.items.singleOrNull()?.ref?.id == restaurantId }
            type("field:filter-from", "2026-11-01"); type("field:filter-to", "2026-11-01")
            waitFor { vm.list.value.query.filters.to == "2026-11-01" && !vm.list.value.isRefreshing && vm.list.value.items.singleOrNull()?.ref?.id == restaurantId }
            tap("filter-clear"); type("field:filter-from", "2026-11-02")
            waitFor { vm.list.value.query.filters.from == "2026-11-02" && !vm.list.value.isRefreshing && vm.list.value.items.isEmpty() }
            tap("filter-clear"); tap("filters")
            waitFor { vm.list.value.query.filters == ListFilters() && !vm.list.value.isRefreshing && vm.list.value.items.singleOrNull()?.ref?.id == restaurantId }
            tap("tab:restaurants"); waitFor { vm.list.value.query.kind == RecordKind.RESTAURANT && !vm.list.value.isRefreshing }
            type("search", ""); waitFor { vm.list.value.query.query.isEmpty() && !vm.list.value.isRefreshing }
            tap("record:$restaurantId"); waitFor { vm.detail.value.cached.snapshot?.ref == restaurantRef && !vm.detail.value.refreshing }
            tap("visit-history"); waitFor { vm.list.value.query.kind == RecordKind.VISIT && !vm.list.value.isRefreshing }
            type("search", "2026-11-01")
            waitFor { vm.list.value.query.query == "2026-11-01" && !vm.list.value.isRefreshing && vm.list.value.items.size == 2 }
            assertEquals(2, vm.list.value.items.map { it.ref.id }.toSet().size)
            tap("tab:restaurants"); type("search", "")
            waitFor { vm.list.value.query.query.isEmpty() && vm.list.value.query.kind == RecordKind.RESTAURANT && !vm.list.value.isRefreshing }
            tap("record:$restaurantId"); waitFor { vm.detail.value.cached.snapshot?.ref == restaurantRef && !vm.detail.value.refreshing }
            tap("edit"); waitFor { vm.editor.value.input?.baseVersion != null }
            phase = "score history note hide restore and reapply"
            progress("rating", 4.3f); tap("save")
            waitFor { vm.detail.value.cached.snapshot?.fields?.get("rating_tenths") == "43" && !vm.detail.value.refreshing }
            tap("history"); waitFor { vm.list.value.query.kind == RecordKind.REVISION && !vm.list.value.isRefreshing }
            val scoreRevision = vm.list.value.items.first { it.fields["action"] == "update" && "rating_tenths" in it.changedFields }
            scroll("record-summary:${scoreRevision.ref.id}")
            compose.onNodeWithTag("record-summary:${scoreRevision.ref.id}", useUnmergedTree = true).assertTextContains("★ 4.0 → ★ 4.3", substring = true)
            tap("record:${scoreRevision.ref.id}"); waitFor { vm.detail.value.cached.snapshot?.ref?.id == scoreRevision.ref.id && !vm.detail.value.refreshing }
            scroll("before:rating_tenths"); compose.onNodeWithTag("before:rating_tenths").assertTextEquals("★ 4.0")
            scroll("after:rating_tenths"); compose.onNodeWithTag("after:rating_tenths").assertTextEquals("★ 4.3")
            tap("history-note"); waitFor { vm.editor.value.input?.ref?.kind == RecordKind.REVISION }
            type("field:note", "第一次调整评分的说明"); tap("save")
            waitFor { vm.detail.value.cached.snapshot?.fields?.get("note") == "第一次调整评分的说明" && !vm.detail.value.refreshing }
            tap("hide-history"); tap("confirm"); waitFor { vm.detail.value.cached.snapshot?.deletedAt != null && !vm.detail.value.refreshing }
            tap("back"); waitFor { !vm.list.value.isRefreshing && vm.list.value.items.none { it.ref.id == scoreRevision.ref.id } }
            tap("trash"); waitFor { vm.list.value.query.deleted == DeletedFilter.ONLY && !vm.list.value.isRefreshing && vm.list.value.items.any { it.ref.id == scoreRevision.ref.id } }
            tap("record:${scoreRevision.ref.id}"); waitFor { vm.detail.value.cached.snapshot?.ref?.id == scoreRevision.ref.id && !vm.detail.value.refreshing }
            tap("restore-history"); tap("confirm"); waitFor { vm.detail.value.cached.snapshot?.deletedAt == null && !vm.detail.value.refreshing }
            tap("reapply"); waitFor { vm.apply.value != null }
            compose.onNodeWithTag("apply-current:rating_tenths").assertTextEquals("★ 4.3")
            compose.onNodeWithTag("apply-proposed:rating_tenths").assertTextEquals("★ 4.0")
            compose.onNodeWithTag("apply-field:rating_tenths").performScrollTo().performClick(); tap("apply-save")
            waitFor { vm.editor.value.message == "已保存到服务器" && !vm.editor.value.saving }
            assertEquals("40", server(restaurantRef).fields["rating_tenths"])
            assertEquals("餐厅评价鸡块酥脆", server(restaurantRef).fields["review"])

            tap("tab:restaurants"); type("search", ""); waitFor { vm.list.value.query.kind == RecordKind.RESTAURANT && !vm.list.value.isRefreshing }
            tap("record:$restaurantId"); waitFor { vm.detail.value.cached.snapshot?.ref == restaurantRef && !vm.detail.value.refreshing }
            online.value = false; compose.runOnIdle { vm.refreshDetail() }
            phase = "offline cache and manual input save"
            waitFor { vm.detail.value.offline && !vm.detail.value.refreshing }
            scroll("offline"); compose.onNodeWithTag("offline").assertIsDisplayed()
            tap("edit"); waitFor { vm.editor.value.input?.ref == restaurantRef }
            type("field:note", "离线未提交值")
            waitFor { vm.editor.value.input?.localFields?.get("note") == "离线未提交值" }
            tap("save"); waitFor { vm.editor.value.message == "保存失败，请检查网络后重试" }
            assertEquals("笔记和朋友一起到店", server(restaurantRef).fields["note"])
            online.value = true
            waitFor { !vm.detail.value.offline && !vm.detail.value.refreshing }
            assertEquals("笔记和朋友一起到店", server(restaurantRef).fields["note"]) // Recovery never auto-uploads.
            tap("save"); waitFor { vm.detail.value.cached.snapshot?.fields?.get("note") == "离线未提交值" && !vm.detail.value.refreshing }

            // A lost response keeps editable input. Refreshing detail confirms the acknowledged save.
            phase = "lost response and detail confirmation"
            tap("edit"); waitFor { vm.editor.value.input?.ref == restaurantRef }
            type("field:note", "响应丢失后确认的备注")
            val beforeVersion = server(restaurantRef).version
            dropResponse.set(true); tap("save")
            waitFor { vm.editor.value.message == "保存失败，请检查网络后重试" && !vm.editor.value.saving }
            scroll("field:note"); compose.onNodeWithTag("field:note").assertIsEnabled()
            assertEquals("响应丢失后确认的备注", vm.editor.value.input!!.localFields["note"])
            tap("back"); tap("detail-refresh")
            waitFor { vm.detail.value.cached.snapshot?.fields?.get("note") == "响应丢失后确认的备注" && !vm.detail.value.refreshing }
            assertNull(vm.editor.value.input)
            assertEquals(beforeVersion + 1, server(restaurantRef).version)

            // A second real login session edits the same field before the local CAS.
            phase = "cross-session CAS conflict"
            tap("edit"); waitFor { vm.editor.value.input?.ref == restaurantRef }
            type("field:note", "本机冲突值")
            val local = vm.editor.value.input!!
            val other = runBlocking { gateway.login(username, password) }
            patchWithOtherSession(other, restaurantId, local.baseVersion!!, "另一设备备注")
            tap("save"); waitFor { vm.editor.value.conflict != null }
            val comparison = vm.editor.value.conflict!!
            assertEquals("响应丢失后确认的备注", comparison.baseSnapshot!!.fields["note"])
            assertEquals("另一设备备注", comparison.serverCurrent.fields["note"])
            assertEquals("本机冲突值", comparison.localInput.localFields["note"])
            compose.onNodeWithTag("confirm-merge").assertIsNotEnabled()
            compose.onNodeWithText("使用未提交值").performScrollTo().performClick(); tap("confirm-merge")
            waitFor { vm.detail.value.cached.snapshot?.fields?.get("note") == "本机冲突值" && !vm.detail.value.refreshing }
            val conflictWrite = writes[writes.size - 2]; val mergedWrite = writes.last()
            assertNotEquals(conflictWrite.key, mergedWrite.key)
            assertEquals(comparison.serverCurrent.version.toString(), Json.parseToJsonElement(mergedWrite.payload).jsonObject["expected_version"]!!.jsonPrimitive.content)

            phase = "restaurant deletion and restore"
            tap("delete"); tap("confirm"); waitFor { vm.detail.value.cached.snapshot?.deletedAt != null && !vm.detail.value.refreshing }
            tap("tab:restaurants"); waitFor { vm.list.value.query.kind == RecordKind.RESTAURANT && !vm.list.value.isRefreshing && vm.list.value.items.none { it.ref.id == restaurantId } }
            tap("trash"); waitFor { vm.list.value.query.deleted == DeletedFilter.ONLY && !vm.list.value.isRefreshing && vm.list.value.items.any { it.ref.id == restaurantId } }
            tap("record:$restaurantId"); waitFor { vm.detail.value.cached.snapshot?.ref == restaurantRef && !vm.detail.value.refreshing }
            tap("restore"); tap("confirm"); waitFor { vm.detail.value.cached.snapshot?.deletedAt == null && !vm.detail.value.refreshing }
            assertEquals("2", server(restaurantRef).fields["visit_count"]); assertEquals("1", server(restaurantRef).fields["dish_count"])

            // Dishes are managed inside restaurant editing, never in a global tab.
            phase = "dish editing history deletion and restore inside restaurant"
            tap("edit"); waitFor { vm.editor.value.input?.ref == restaurantRef }
            tap("record:${dishRef.id}"); waitFor { vm.detail.value.cached.snapshot?.ref == dishRef && !vm.detail.value.refreshing }
            tap("edit"); waitFor { vm.editor.value.input?.ref == dishRef }
            type("field:review", "编辑后的菜品短评"); tap("save")
            waitFor { vm.detail.value.cached.snapshot?.ref == dishRef && vm.detail.value.cached.snapshot?.fields?.get("review") == "编辑后的菜品短评" && !vm.detail.value.refreshing }
            tap("history"); waitFor { vm.list.value.query.historyEntityId == dishRef.id && !vm.list.value.isRefreshing && vm.list.value.items.isNotEmpty() }
            assertTrue(vm.list.value.items.all { it.fields["entity_type"] == "dish" && it.fields["entity_id"] == dishRef.id })
            tap("back"); waitFor { vm.detail.value.cached.snapshot?.ref == dishRef && !vm.detail.value.refreshing }
            tap("delete"); tap("confirm"); waitFor { vm.detail.value.cached.snapshot?.deletedAt != null && !vm.detail.value.refreshing }
            tap("back"); waitFor { vm.editor.value.input?.ref == restaurantRef }
            tap("edit-manage-dishes"); waitFor { vm.list.value.query.kind == RecordKind.DISH && !vm.list.value.isRefreshing }
            assertTrue(vm.list.value.items.none { it.ref.id == dishRef.id })
            tap("trash"); waitFor { vm.list.value.query.deleted == DeletedFilter.ONLY && !vm.list.value.isRefreshing && vm.list.value.items.any { it.ref.id == dishRef.id } }
            tap("record:${dishRef.id}"); waitFor { vm.detail.value.cached.snapshot?.ref == dishRef && !vm.detail.value.refreshing }
            tap("restore"); tap("confirm"); waitFor { vm.detail.value.cached.snapshot?.deletedAt == null && !vm.detail.value.refreshing }
            assertEquals("1", server(restaurantRef).fields["dish_count"])

            phase = "restaurant visit history edit delete and restore"
            tap("tab:restaurants"); waitFor { vm.list.value.query.kind == RecordKind.RESTAURANT && !vm.list.value.isRefreshing }
            type("search", ""); waitFor { vm.list.value.query.query.isEmpty() && !vm.list.value.isRefreshing }
            tap("record:$restaurantId"); waitFor { vm.detail.value.cached.snapshot?.ref == restaurantRef && !vm.detail.value.refreshing }
            tap("visit-history"); waitFor { vm.list.value.query.kind == RecordKind.VISIT && !vm.list.value.isRefreshing }
            type("search", ""); waitFor { vm.list.value.query.query.isEmpty() && !vm.list.value.isRefreshing && vm.list.value.items.size == 2 }
            tap("record:${visitRef.id}"); waitFor { vm.detail.value.cached.snapshot?.ref == visitRef && !vm.detail.value.refreshing }
            tap("edit"); waitFor { vm.editor.value.input?.ref == visitRef }
            type("field:dining_date", "2026-11-02"); type("field:note", "编辑后的到店备注"); tap("save")
            waitFor { vm.detail.value.cached.snapshot?.ref == visitRef && vm.detail.value.cached.snapshot?.fields?.get("dining_date") == "2026-11-02" && !vm.detail.value.refreshing }
            tap("delete"); tap("confirm"); waitFor { vm.detail.value.cached.snapshot?.deletedAt != null && !vm.detail.value.refreshing }
            tap("back"); waitFor { vm.list.value.query.deleted == DeletedFilter.EXCLUDE && !vm.list.value.isRefreshing && vm.list.value.items.none { it.ref.id == visitRef.id } }
            tap("trash"); waitFor { vm.list.value.query.deleted == DeletedFilter.ONLY && !vm.list.value.isRefreshing && vm.list.value.items.any { it.ref.id == visitRef.id } }
            tap("record:${visitRef.id}"); waitFor { vm.detail.value.cached.snapshot?.ref == visitRef && !vm.detail.value.refreshing }
            tap("restore"); tap("confirm"); waitFor { vm.detail.value.cached.snapshot?.deletedAt == null && !vm.detail.value.refreshing }
            tap("back"); tap("back"); waitFor { vm.list.value.query.deleted == DeletedFilter.EXCLUDE && !vm.list.value.isRefreshing }
            assertEquals(2, vm.list.value.items.size)
            assertTrue(vm.list.value.items.all { it.ref.restaurantId == restaurantId })
            compose.onNodeWithTag("tab:visits").assertDoesNotExist()

            phase = "sessions password logout and fresh login"
            tap("tab:settings"); waitFor { vm.settings.value.profile != null && !vm.settings.value.loading }
            assertSecureWindow(false, "ordinary settings")
            runBlocking { withContext(Dispatchers.IO) {
                val key = UUID.randomUUID().toString()
                refresh.authenticated { token ->
                    val request = Request.Builder().url(baseUrl + "auth/sessions/" + other.sessionId)
                        .header("Authorization", "Bearer $token").header("Idempotency-Key", key).delete().build()
                    client.newCall(request).execute().use { assertEquals(204, it.code) }
                }
                val revokedAccess = Request.Builder().url(baseUrl + "auth/session")
                    .header("Authorization", "Bearer ${other.accessToken}").build()
                client.newCall(revokedAccess).execute().use { response ->
                    assertEquals(401, response.code)
                }
                try {
                    gateway.refresh(other.refreshToken, UUID.randomUUID().toString())
                    fail("Revoked session must not refresh")
                } catch (failure: AuthFailure) { assertEquals("SESSION_REVOKED", failure.code) }
                assertEquals(account.session.value!!.userId, security.profile().id)
            } }
            tap("change-password")
            compose.onNodeWithTag("screen:password").assertExists()
            assertSecureWindow(true, "change password")
            type("current-password", password); type("new-password", newPassword); type("repeat-password", newPassword)
            tap("password-save"); waitFor { vm.settings.value.passwordRevision > 0 && !vm.settings.value.loading }
            compose.onNodeWithTag("screen:password").assertDoesNotExist()
            assertSecureWindow(false, "settings after successful password change")
            assertEquals(account.session.value!!.sessionId, store.read()!!.sessionId)
            tap("logout"); tap("confirm"); waitFor { account.session.value == null }
            assertNull(store.read())
            runBlocking { withContext(Dispatchers.IO) {
                for (table in listOf("cached_summaries", "cached_details", "cached_queries", "page_members")) {
                    db.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use { cursor ->
                        assertTrue(cursor.moveToFirst()); assertEquals("Logout must clear $table", 0, cursor.getInt(0))
                    }
                }
            } }
            compose.onNodeWithTag("tab:restaurants").assertDoesNotExist()
            assertSecureWindow(true, "login after logout")
            compose.onNodeWithTag("auth-username").performScrollTo().performTextInput(username)
            compose.onNodeWithTag("auth-password").performScrollTo().performTextInput(newPassword)
            compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
            waitFor { account.session.value != null && ::vm.isInitialized && vm.list.value.query.kind == RecordKind.RESTAURANT && !vm.list.value.isRefreshing }
            compose.onNodeWithTag("screen:restaurant:list").assertExists()
            assertSecureWindow(false, "restaurant list after login")
            compose.onNodeWithTag("screen:edit").assertDoesNotExist(); compose.onNodeWithTag("screen:history").assertDoesNotExist()
        } catch (failure: Throwable) {
            val folder = context.getExternalFilesDir("p4-screenshots")!!
            folder.mkdirs()
            val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (screenshot != null) File(folder, "e2e-failure-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            if (::vm.isInitialized) android.util.Log.e("DiningP4", "P4 E2E diagnostic: phase=$phase, query=${vm.list.value.query}, listError=${vm.list.value.error}, " +
                "detail=${vm.detail.value.cached.snapshot?.ref}, detailError=${vm.detail.value.error}, " +
                "items=${vm.list.value.items.map { it.ref.id to it.fields["dining_date"] }}, " +
                "input=${vm.editor.value.input?.ref}, " +
                "saving=${vm.editor.value.saving}, message=${vm.editor.value.message}, fields=${vm.editor.value.errors}")
            throw failure
        } finally {
            online.value = true
            runBlocking { account.clear() }
            tokenFile.delete(); java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            db.close()
        }
    }
    private fun assertSecureWindow(expected: Boolean, screen: String) {
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { activity ->
            assertEquals("FLAG_SECURE on $screen",
                if (expected) WindowManager.LayoutParams.FLAG_SECURE else 0,
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    private fun server(ref: RecordRef) = runBlocking { direct.detail(ref) }
    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(45_000) {
            if (!condition()) false
            else if (!::vm.isInitialized) true
            else {
                val editor = vm.editor.value
                // Room publishes an acknowledged write before the parent stats
                // GET completes and the navigation event is consumed. The old
                // detail state alone cannot prove the editor has been left.
                !editor.saving && (editor.message != "已保存到服务器" ||
                    compose.onAllNodesWithTag("screen:edit").fetchSemanticsNodes().isEmpty())
            }
        }
        compose.waitForIdle()
    }
    private fun scroll(tag: String) {
        if (tag in setOf("save", "confirm", "apply-save", "confirm-merge", "password-save") || tag.startsWith("tab:")) return
        if (tag in setOf("refresh", "trash")) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) {
                scroll("list-more")
                compose.onNodeWithTag("list-more").performClick()
            }
            compose.onNodeWithTag(tag).assertIsDisplayed()
            compose.onNodeWithTag("choose-restaurant").assertDoesNotExist()
            compose.onNodeWithTag("clear-restaurant").assertDoesNotExist()
            return
        }
        val dialog = listOf("screen:apply", "screen:conflict", "screen:password").any { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
        if (dialog) { compose.onNodeWithTag(tag).performScrollTo(); return }
        val lazy = listOf("edit-scroll", "record-list", "detail-scroll", "history-scroll", "settings-scroll").firstOrNull { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
        if (lazy != null) {
            val scroller = compose.onNodeWithTag(lazy, useUnmergedTree = true)
            scroller.performScrollToNode(hasTestTag(tag))
            if (compose.onAllNodesWithTag("floating-tabs").fetchSemanticsNodes().isNotEmpty()) {
                val capsule = compose.onNodeWithTag("floating-tabs").getUnclippedBoundsInRoot()
                val target = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
                val overlap = target.bottom.value - capsule.top.value + 8f
                if (overlap > 0f) scroller.performSemanticsAction(SemanticsActions.ScrollBy) {
                    it(0f, overlap * compose.activity.resources.displayMetrics.density)
                }
                assertTrue("$tag must be above floating navigation before tapping",
                    compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot().bottom <=
                        compose.onNodeWithTag("floating-tabs").getUnclippedBoundsInRoot().top)
            }
        }
    }
    private fun tap(tag: String) {
        if (tag.startsWith("tab:")) {
            // P8 intentionally hides navigation while typing. Dismiss only the IME, preserving the page.
            compose.activityRule.scenario.onActivity {
                androidx.core.view.WindowInsetsControllerCompat(it.window, it.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        }
        scroll(tag); compose.onNodeWithTag(tag).performClick()
    }
    private fun choose(text: String) {
        compose.onNodeWithTag("record-list", useUnmergedTree = true).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).performClick()
    }
    private fun type(tag: String, value: String) {
        scroll(tag)
        // Tapping also reopens the IME when this field retained focus after tab
        // navigation. Wait for ScreenFrame to observe it before scrolling again;
        // Compose idleness alone does not wait for the platform keyboard.
        compose.onNodeWithTag(tag).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(ImeBottomPx), useUnmergedTree = true)
                .fetchSemanticsNodes().any { it.config[ImeBottomPx] > 0 }
        }
        compose.waitForIdle()
        scroll(tag)
        compose.onNodeWithTag(tag).assertIsFocused().performTextReplacement(value)
    }
    private fun progress(tag: String, value: Float) { scroll(tag); compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) { it(value) } }
    private fun patchWithOtherSession(session: AuthSession, id: String, version: Int, note: String) = runBlocking {
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject { put("expected_version", version); put("note", note) }.toString()
            val request = Request.Builder().url(baseUrl + "restaurants/$id").header("Authorization", "Bearer ${session.accessToken}")
                .header("Idempotency-Key", UUID.randomUUID().toString()).patch(payload.toRequestBody("application/json".toMediaType())).build()
            OkHttpClient().newCall(request).execute().use { assertEquals(200, it.code) }
        }
    }
}
