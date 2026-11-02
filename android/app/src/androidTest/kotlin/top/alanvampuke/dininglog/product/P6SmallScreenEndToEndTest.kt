package top.alanvampuke.dininglog.product

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.alanvampuke.dininglog.BuildConfig
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.core.auth.*
import top.alanvampuke.dininglog.data.*
import top.alanvampuke.dininglog.data.auth.*
import top.alanvampuke.dininglog.data.local.*
import top.alanvampuke.dininglog.data.remote.*
import top.alanvampuke.dininglog.feature.*
import top.alanvampuke.dininglog.feature.theme.*

// Real records are created through the unmodified Worker and isolated CI D1.
// All generated authentication and encryption material stays in process memory.
@RunWith(AndroidJUnit4::class)
class P6SmallScreenEndToEndTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun realApiRecordsFitTheSmallFirstScreenAndTheMinimalEditorSavesInBothThemes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val baseUrl = "http://10.0.2.2:8787/api/v1/"
        val db = Room.inMemoryDatabaseBuilder(context, ProductDatabase::class.java).build()
        val owner = OwnerCache(db)
        val bytes = object : AtomicTokenFile {
            private var data: ByteArray? = null
            override fun read() = data
            override fun replace(bytes: ByteArray) { data = bytes }
            override fun delete() { data = null }
        }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val keys = object : KeyManager { override fun existingKey() = key; override fun encryptionKey() = key }
        val store = SecureTokenStore(bytes, AesGcmTokenCipher(keys, context.packageName))
        val account = AccountSession(store, owner)
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).addInterceptor { chain ->
                // Local Wrangler closes idle keep-alive sockets; writes still have no retry.
                // Give this synthetic client its own documentation IP in local CI,
                // preserving the real one-registration-per-minute limit for P4.
                chain.proceed(chain.request().newBuilder().header("Connection", "close")
                    .header("CF-Connecting-IP", "192.0.2.6").build())
            }.build()
        val gateway = RetrofitAuthGateway(Retrofit.Builder().baseUrl(baseUrl).client(client)
            .addConverterFactory(ProductJson.asConverterFactory("application/json".toMediaType()))
            .build().create(AuthApi::class.java))
        val refresh = RefreshCoordinator(store, account, gateway)
        val remote = ProductRemote.create(baseUrl, refresh, client)
        val repository = ProductRepositoryImpl(db, owner, account, remote, MutableStateFlow(true))
        try {
            val username = "p6_" + UUID.randomUUID().toString().replace("-", "").take(20)
            val session = runBlocking { gateway.register(username, "P6!" + UUID.randomUUID()) }
            runBlocking { account.install(session) }
            val createdIds = runBlocking {
                (1..5).map { index ->
                    val ref = RecordRef(RecordKind.RESTAURANT, UUID.randomUUID().toString())
                    val input = SaveInput(session.userId, ref, WriteAction.CREATE, null,
                        mapOf("name" to "小屏验收餐厅 $index", "category" to "restaurant"))
                    val outcome = repository.submit(input)
                    assertTrue("Name and category alone must save through the real API", outcome is SaveOutcome.Saved)
                    val saved = remote.detail(ref)
                    assertEquals(ref.id, saved.ref.id)
                    for (field in listOf("address", "cuisine", "review", "note")) {
                        assertTrue("API must include the nullable $field", saved.fields.containsKey(field))
                        assertNull(saved.fields[field])
                    }
                    ref.id
                }.toSet()
            }
            val vm = ProductViewModel(session.userId, repository, RetrofitAccountSecurity.create(baseUrl, refresh, account), account)
            var dark by mutableStateOf(false)
            compose.setContent { QuietGridTheme(dark) {
                Box(Modifier.requiredSize(360.dp, 640.dp).clipToBounds()
                    .background(LocalQuietGridColors.current.bg).testTag("p6-real-viewport")) {
                    ProductRoot(vm, username, if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT, {}, {}, BuildConfig.VERSION_NAME)
                }
            } }
            compose.waitUntil(45_000) { !vm.list.value.isRefreshing && !vm.list.value.localScope &&
                vm.list.value.items.map { it.ref.id }.toSet() == createdIds }
            for (night in listOf(false, true)) {
                compose.runOnIdle { dark = night }
                compose.onNodeWithTag("p6-real-viewport").assertWidthIsEqualTo(360.dp).assertHeightIsEqualTo(640.dp)
                val viewport = compose.onNodeWithTag("record-list").getUnclippedBoundsInRoot()
                val complete = createdIds.filter { id ->
                    val node = compose.onAllNodesWithTag("record:$id").fetchSemanticsNodes().singleOrNull()
                    if (node == null) false else {
                        val bounds = compose.onNodeWithTag("record:$id").getUnclippedBoundsInRoot()
                        bounds.bottom > bounds.top && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom
                    }
                }
                assertTrue("360x640 real API first screen must show >= 3 complete records", complete.size >= 3)
                complete.forEach { compose.onNodeWithTag("record:$it").assertIsDisplayed() }
                compose.onAllNodes(hasText("null", substring = true)).assertCountEquals(0)
                println("P6 real_api viewport=360x640dp theme=${if (night) "dark" else "light"} complete_record_items=${complete.size}")
                val image = compose.onNodeWithTag("p6-real-viewport").captureToImage().asAndroidBitmap()
                val folder = context.getExternalFilesDir("p4-screenshots")!!.apply { mkdirs() }
                File(folder, "p6-real-${if (night) "dark" else "light"}-360x640-api${Build.VERSION.SDK_INT}.png")
                    .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }

            for (night in listOf(false, true)) {
                compose.runOnIdle { dark = night }
                // Exercise the actual editor, ViewModel, Room and remote write with no optional input.
                compose.onNodeWithTag("add").performClick()
                compose.waitUntil(10_000) { vm.editor.value.input != null && !vm.editor.value.loading }
                val ref = vm.editor.value.input!!.ref
                compose.onNodeWithTag("field:name").performTextReplacement("只填名称的${if (night) "深色" else "浅色"}小馆")
                compose.onNodeWithTag("save").assertIsEnabled().performClick()
                compose.waitUntil(45_000) { vm.detail.value.cached.snapshot?.ref == ref && !vm.detail.value.refreshing &&
                    !vm.editor.value.saving && compose.onAllNodesWithTag("screen:edit").fetchSemanticsNodes().isEmpty() }
                val saved = runBlocking { remote.detail(ref) }
                assertEquals("restaurant", saved.fields["category"])
                for (field in listOf("address", "cuisine", "review", "note")) assertNull(saved.fields[field])
                compose.onNodeWithTag("detail:address").assertDoesNotExist()
                compose.onNodeWithTag("detail:note").assertDoesNotExist()
                compose.onNodeWithTag("tab:restaurants").performClick()
                compose.waitUntil(45_000) { !vm.list.value.isRefreshing && vm.list.value.query.kind == RecordKind.RESTAURANT &&
                    vm.list.value.items.any { it.ref.id == ref.id } }
                compose.waitForIdle()
            }
        } finally {
            runBlocking { account.clear() }
            db.close()
        }
    }
}
