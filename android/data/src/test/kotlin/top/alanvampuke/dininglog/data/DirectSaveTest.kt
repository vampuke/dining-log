package top.alanvampuke.dininglog.data

import android.content.Context
import java.io.IOException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.core.auth.*
import top.alanvampuke.dininglog.data.local.*
import top.alanvampuke.dininglog.data.remote.*

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28]) @OptIn(ExperimentalCoroutinesApi::class)
class DirectSaveTest {
    private val ref = RecordRef(RecordKind.RESTAURANT, "10000000-0000-4000-8000-000000000001")
    private fun base() = RecordSnapshot(ref, 1, mapOf("name" to "兰记", "category" to "restaurant", "address" to "愚园路", "rating_tenths" to "40", "review" to null, "note" to null), "2026-11-01T00:00:00.000Z")
    private fun input() = SaveInput("A", ref, WriteAction.UPDATE, base(), base().editable + ("rating_tenths" to "43"))
    private fun entity(version: Int = 2, rating: String = "4.3") = "{\"id\":\"${ref.id}\",\"name\":\"兰记\",\"category\":\"restaurant\",\"address\":\"愚园路\",\"cuisine\":null,\"rating\":$rating,\"review\":null,\"note\":null,\"version\":$version,\"created_at\":\"2026-11-01T00:00:00.000Z\",\"updated_at\":\"2026-11-01T00:00:01.000Z\",\"deleted_at\":null}"
    private fun mutation(version: Int = 2) = MockResponse().setBody("{\"data\":{\"entity\":${entity(version)},\"changed\":true,\"revisions\":[]}}")
    private fun detail(version: Int = 2, rating: String = "4.3") = MockResponse().setBody("{\"data\":${entity(version, rating)}}")
    private class Fixture : AutoCloseable {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ProductDatabase::class.java).allowMainThreadQueries().build()
        val owner = OwnerCache(db)
        val file = object : AtomicTokenFile { var bytes: ByteArray? = null; override fun read() = bytes; override fun replace(bytes: ByteArray) { this.bytes = bytes }; override fun delete() { bytes = null } }
        val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val keys = object : KeyManager { override fun existingKey() = key; override fun encryptionKey() = key }
        val store = SecureTokenStore(file, AesGcmTokenCipher(keys, "test"))
        val account = AccountSession(store, owner)
        val gateway = object : AuthGateway {
            override suspend fun login(username: String, password: String): AuthSession = error("unexpected")
            override suspend fun register(username: String, password: String): AuthSession = error("unexpected")
            override suspend fun recover(username: String, code: String, password: String): AuthSession = error("unexpected")
            override suspend fun refresh(token: String, requestId: String): AuthSession = error("unexpected")
            override suspend fun logout(token: String) { }
        }
        val server = MockWebServer().apply { start() }
        // MockWebServer listens on IPv4 here. After a disconnect, OkHttp can
        // select localhost's unbound IPv6 route; that tests DNS, not replay.
        val baseUrl = server.url("/api/v1/").newBuilder().host("127.0.0.1").build().toString()
        val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) { failures += "${ioe.javaClass.simpleName}: ${ioe.message}" }
            }).build()
        val online = MutableStateFlow(true)
        var now = 1000L
        val repository = ProductRepositoryImpl(db, owner, account, ProductRemote.create(baseUrl, RefreshCoordinator(store, account, gateway), client), online) { now }
        init { runBlocking { account.install(AuthSession("A", "alice", "S", "access-A", "refresh-A")) } }
        override fun close() { runBlocking { account.clear() }; db.close(); server.shutdown() }
    }
    @Test fun lostResponseMakesOneAttemptRetainsInputAndNeverPersistsIt() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val failed = assertIs<SaveOutcome.Failed>(f.repository.submit(input()))
            assertEquals(input(), failed.input)
            assertEquals("保存失败，请检查网络后重试", failed.message)
            assertEquals(1, f.server.requestCount)
            assertNotNull(f.server.takeRequest(5, TimeUnit.SECONDS))
            f.db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                val names = mutableListOf<String>(); while (cursor.moveToNext()) names += cursor.getString(0)
                assertEquals(setOf("cached_summaries", "cached_details", "cached_queries", "page_members"), names.filter { it.startsWith("cached_") || it == "page_members" }.toSet())
                assertEquals(4, names.count { it !in setOf("android_metadata", "room_master_table", "sqlite_sequence") })
            }
        }
    }
    @Test fun conflictAndUnavailableParentKeepMemoryValuesWithoutForcedWrite() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(409).setBody("{\"error\":{\"code\":\"VERSION_CONFLICT\",\"current\":${entity(2, "4.6")}}}"))
            val conflict = assertIs<SaveOutcome.Conflict>(f.repository.submit(input()))
            assertEquals(setOf("rating_tenths"), conflict.comparison.needsChoice)
            assertEquals("40", conflict.comparison.baseSnapshot!!.fields["rating_tenths"])
            assertEquals("46", conflict.comparison.serverCurrent.fields["rating_tenths"])
            assertEquals("43", conflict.comparison.localInput.localFields["rating_tenths"])
            f.server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\":{\"code\":\"PARENT_UNAVAILABLE\"}}"))
            val child = input().copy(ref = RecordRef(RecordKind.DISH, ref.id, "parent"), localFields = mapOf("name" to "菜品", "rating_tenths" to "43", "review" to null))
            val unavailable = assertIs<SaveOutcome.Failed>(f.repository.submit(child))
            assertEquals(child.localFields, unavailable.input.localFields)
            assertTrue(unavailable.message.contains("恢复或刷新")); assertEquals(2, f.server.requestCount)
        }
    }
    @Test fun deletingAChildAlsoRefreshesItsParentsCachedCounts() = runBlocking {
        Fixture().use { f ->
            val childRef = RecordRef(RecordKind.DISH, "20000000-0000-4000-8000-000000000001", ref.id)
            val child = RecordSnapshot(childRef, 1, mapOf("name" to "辣子鸡", "rating_tenths" to "43", "review" to null), base().updatedAt)
            val pending = SaveInput("A", childRef, WriteAction.DELETE, child, child.editable)
            f.server.enqueue(MockResponse().setBody("{\"data\":{\"entity\":{\"id\":\"${childRef.id}\",\"version\":2,\"deleted_at\":\"2026-11-01T00:00:01.000Z\"},\"changed\":true,\"revisions\":[]}}"))
            val parent = Json.parseToJsonElement(entity(1, "4.0")).jsonObject
            f.server.enqueue(MockResponse().setBody(buildJsonObject {
                put("data", JsonObject(parent + ("stats" to buildJsonObject { put("dish_count", 0); put("visit_count", 2) })))
            }.toString()))
            assertIs<SaveOutcome.Saved>(f.repository.submit(pending))
            assertEquals("DELETE", f.server.takeRequest().method)
            val refresh = f.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("GET", refresh.method); assertEquals("/api/v1/restaurants/${ref.id}?deleted=include", refresh.path)
            val cached = f.repository.observeDetail("A", ref).first().snapshot!!
            assertEquals("0", cached.fields["dish_count"]); assertEquals("2", cached.fields["visit_count"])
        }
    }
    @Test fun acknowledgedSaveDoesNotBecomeUnknownWhenParentStatisticsGetFails() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(mutation())
            repeat(3) { f.server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":{\"code\":\"TEMPORARILY_UNAVAILABLE\"}}")) }
            assertIs<SaveOutcome.Saved>(f.repository.submit(input()))
            assertEquals("43", f.repository.observeDetail("A", ref).first().snapshot!!.fields["rating_tenths"])
            assertEquals(4, f.server.requestCount)
        }
    }
    @Test fun cachedSearchPagesKeepTheirOwnHitTypesForTheSameRestaurant() = runBlocking {
        Fixture().use { f ->
            fun searchPage(match: String) = MockResponse().setBody(buildJsonObject {
                put("data", buildJsonObject { put("items", JsonArray(listOf(Json.parseToJsonElement(entity()).jsonObject +
                    ("matched_fields" to JsonArray(listOf(JsonPrimitive(match))))).map(::JsonObject))) })
                put("meta", buildJsonObject { put("has_more", false); put("next_cursor", JsonNull) })
            }.toString())
            val dishQuery = ListQuery(query = "辣子鸡")
            val reviewQuery = ListQuery(query = "花椒香")
            f.server.enqueue(searchPage("dish")); f.repository.refreshList("A", dishQuery)
            f.server.enqueue(searchPage("review")); f.repository.refreshList("A", reviewQuery)
            assertEquals(listOf(MatchType.DISH), f.repository.observeList("A", dishQuery).first().items.single().matches)
            assertEquals(listOf(MatchType.REVIEW), f.repository.observeList("A", reviewQuery).first().items.single().matches)
            assertFalse(f.repository.observeList("A", dishQuery).first().localScope)
        }
    }
    @Test fun scoreHistorySummarySurvivesARefreshWithoutFetchingEveryDiff() = runBlocking {
        Fixture().use { f ->
            val revision = "{\"id\":\"history\",\"restaurant_id\":\"${ref.id}\",\"entity_type\":\"restaurant\",\"entity_id\":\"${ref.id}\",\"entity_label\":\"兰记\",\"action\":\"update\",\"changed_fields\":[\"rating_tenths\"],\"metadata_version\":1,\"created_at\":\"2026-11-01T00:00:01.000Z\"}"
            f.server.enqueue(MockResponse().setBody("{\"data\":{\"entity\":${entity()},\"changed\":true,\"revisions\":[$revision]}}"))
            f.server.enqueue(detail())
            assertIs<SaveOutcome.Saved>(f.repository.submit(input()))
            val query = ListQuery(RecordKind.REVISION, ref.id)
            val before = f.repository.observeList("A", query).first().items.single()
            assertTrue(historySummary(before, null).contains("★ 4.0 → ★ 4.3"))
            f.server.enqueue(MockResponse().setBody("{\"data\":{\"items\":[$revision]},\"meta\":{\"has_more\":false,\"next_cursor\":null}}"))
            f.repository.refreshList("A", query)
            assertTrue(historySummary(f.repository.observeList("A", query).first().items.single(), null).contains("★ 4.0 → ★ 4.3"))
            assertEquals(3, f.server.requestCount, "Opening history should not download all full diff details")
        }
    }
    @Test fun roomPaginationAppendsDistinctIdsAndFirstPageRefreshReplacesMembership() = runBlocking {
        Fixture().use { f ->
            val secondId = "10000000-0000-4000-8000-000000000002"
            val second = JsonObject(Json.parseToJsonElement(entity(1)).jsonObject + ("id" to JsonPrimitive(secondId)) + ("name" to JsonPrimitive("第二家")))
            fun page(items: List<JsonElement>, cursor: String? = null) = MockResponse().setBody(buildJsonObject {
                put("data", buildJsonObject { put("items", JsonArray(items)) })
                put("meta", buildJsonObject { put("has_more", cursor != null); put("next_cursor", cursor?.let(::JsonPrimitive) ?: JsonNull) })
            }.toString())
            f.server.enqueue(page(listOf(Json.parseToJsonElement(entity(1))), "opaque-cursor"))
            f.repository.refreshList("A", ListQuery())
            assertTrue(f.repository.observeList("A", ListQuery()).first().hasMore)
            assertNull(f.server.takeRequest().requestUrl!!.queryParameter("cursor"))
            f.server.enqueue(page(listOf(Json.parseToJsonElement(entity(2)), second)))
            f.repository.refreshList("A", ListQuery(), loadMore = true)
            assertEquals("opaque-cursor", f.server.takeRequest().requestUrl!!.queryParameter("cursor"))
            val appended = f.repository.observeList("A", ListQuery()).first()
            assertEquals(listOf(ref.id, secondId), appended.items.map { it.ref.id })
            assertEquals(2, appended.items.first().version)
            assertFalse(appended.hasMore)
            f.server.enqueue(page(listOf(second)))
            f.repository.refreshList("A", ListQuery())
            assertEquals(listOf(secondId), f.repository.observeList("A", ListQuery()).first().items.map { it.ref.id })
        }
    }
    @Test fun lateAHttpResultCannotRepopulateCacheAfterLoginB() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setBody("{\"data\":{\"items\":[${entity()}]},\"meta\":{\"has_more\":false,\"next_cursor\":null}}").setBodyDelay(1, TimeUnit.SECONDS))
            val request = async(Dispatchers.IO) { assertFailsWith<AuthFailure> { f.repository.refreshList("A", ListQuery()) } }
            assertNotNull(f.server.takeRequest(5, TimeUnit.SECONDS))
            f.account.install(AuthSession("B", "bob", "T", "access-B", "refresh-B")); request.await()
            assertTrue(f.repository.observeList("B", ListQuery()).first().items.isEmpty())
            assertTrue(f.db.products().observeSummaries("A").first().isEmpty())
            f.db.openHelper.readableDatabase.query("SELECT count(*) FROM cached_queries").use { cursor ->
                cursor.moveToFirst(); assertEquals(0, cursor.getInt(0))
            }
        }
    }
    @Test fun clearingReviewAndNoteClearsCachedExcerptsAndTheirOfflineSearchMatches() = runBlocking {
        Fixture().use { f ->
            val previous = JsonObject(Json.parseToJsonElement(entity(1, "4.0")).jsonObject +
                ("review" to JsonPrimitive("已清空的旧评价")) + ("note" to JsonPrimitive("已清空的旧备注")))
            f.server.enqueue(MockResponse().setBody(buildJsonObject { put("data", previous) }.toString()))
            val before = f.repository.refreshDetail("A", ref)
            val editing = SaveInput("A", ref, WriteAction.UPDATE, before,
                before.editable + ("rating_tenths" to "43") + ("review" to null) + ("note" to null))
            f.server.enqueue(mutation()); f.server.enqueue(detail())
            assertIs<SaveOutcome.Saved>(f.repository.submit(editing))
            val brief = f.repository.observeList("A", ListQuery()).first().items.single()
            assertTrue(brief.fields.containsKey("review_excerpt")); assertNull(brief.fields["review_excerpt"])
            assertTrue(brief.fields.containsKey("note_excerpt")); assertNull(brief.fields["note_excerpt"])
            for (term in listOf("已清空的旧评价", "已清空的旧备注")) {
                assertTrue(f.repository.observeList("A", ListQuery(query = term)).first().items.isEmpty())
            }
            assertEquals(3, f.server.requestCount)
        }
    }
}
