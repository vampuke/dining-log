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
class DurableDraftTest {
    private val ref = RecordRef(RecordKind.RESTAURANT, "10000000-0000-4000-8000-000000000001")
    private fun base() = RecordSnapshot(ref, 1, mapOf("name" to "兰记", "category" to "restaurant", "address" to "愚园路", "rating_tenths" to "40", "review" to null, "note" to null), "2026-11-01T00:00:00.000Z")
    private fun draft() = SavedDraft("A", "edit:restaurant", ref, WriteAction.UPDATE, base(), base().editable + ("rating_tenths" to "43"))
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
    @Test fun draftDebouncePersistsIntoRoomAt500msWithoutHttpOrHistory() = runTest {
        Fixture().use { fixture ->
            val persisted = CompletableDeferred<Unit>()
            val saver = DraftAutosaver(backgroundScope) { fixture.repository.storeDraft(it); persisted.complete(Unit) }
            saver.schedule(draft()); runCurrent(); advanceTimeBy(300)
            val latest = draft().copy(localFields = draft().localFields + ("note" to "最新本机内容"))
            saver.schedule(latest); runCurrent(); advanceTimeBy(499); runCurrent()
            fixture.db.openHelper.readableDatabase.query("SELECT count(*) FROM saved_drafts").use { cursor -> cursor.moveToFirst(); assertEquals(0, cursor.getInt(0)) }
            advanceTimeBy(1); runCurrent(); persisted.await()
            assertEquals(latest, fixture.repository.findDraft("A", latest.id))
            assertEquals(0, fixture.server.requestCount)
            saver.stopAndFlush()
        }
    }
    @Test fun lostResponseHasOneAttemptAndRoomRestartReusesExactOriginalKeyAndPayload() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val failed = f.repository.submit(draft()) as SaveOutcome.Kept
            assertEquals(DraftStatus.RESULT_UNKNOWN, failed.draft.status)
            assertEquals(1, f.server.requestCount, "A lost write response must not trigger an automatic transport replay")
            val first = f.server.takeRequest(5, TimeUnit.SECONDS)!!
            val restored = f.repository.findDraft("A", draft().id)!!
            assertEquals(failed.draft.original, restored.original)
            f.server.enqueue(mutation()); f.server.enqueue(detail())
            val retryOutcome = f.repository.submit(restored)
            assertIs<SaveOutcome.Saved>(retryOutcome, "Manual retry outcome=$retryOutcome, transport failures=${f.failures}, requests=${f.server.requestCount}")
            val retry = f.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals(first.getHeader("Idempotency-Key"), retry.getHeader("Idempotency-Key"), "Manual retry must keep the original key")
            assertEquals(first.body.readUtf8(), retry.body.readUtf8(), "Manual retry must keep the original payload")
            assertNull(f.repository.findDraft("A", restored.id))
            assertEquals("43", f.repository.observeDetail("A", ref).first().snapshot!!.fields["rating_tenths"])
        }
    }
    @Test fun sevenDayExpiryReadsCurrentWithoutReplayThenConfirmedSaveUsesNewKeyAndCurrentVersion() = runBlocking {
        Fixture().use { f ->
            val prepared = requestFor(draft(), f.now).copy(status = DraftStatus.RESULT_UNKNOWN)
            f.repository.storeDraft(prepared); f.now += DraftRules.RETRY_WINDOW_MS
            f.server.enqueue(detail(9, "4.6"))
            val review = f.repository.submit(prepared) as SaveOutcome.Expired
            assertEquals(1, f.server.requestCount)
            assertEquals("GET", f.server.takeRequest().method)
            assertEquals(9, review.serverCurrent!!.version); assertEquals(DraftStatus.EXPIRED_REVIEW, f.repository.findDraft("A", prepared.id)!!.status)
            f.server.enqueue(mutation(10)); f.server.enqueue(detail(10))
            assertIs<SaveOutcome.Saved>(f.repository.confirmExpired(review.draft, review.serverCurrent))
            val confirmed = f.server.takeRequest()
            assertEquals("PATCH", confirmed.method); assertNotEquals(prepared.original!!.key, confirmed.getHeader("Idempotency-Key"))
            assertTrue(confirmed.body.readUtf8().contains("\"expected_version\":9"))
        }
    }
    @Test fun versionConflictAndDeletedParentRetainDraftWithoutForcedWrite() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(409).setBody("{\"error\":{\"code\":\"VERSION_CONFLICT\",\"current\":${entity(2, "4.6")}}}"))
            val conflict = f.repository.submit(draft()) as SaveOutcome.Conflict
            assertEquals(setOf("rating_tenths"), conflict.comparison.needsChoice)
            assertEquals("40", conflict.comparison.baseSnapshot!!.fields["rating_tenths"])
            assertEquals("46", conflict.comparison.serverCurrent.fields["rating_tenths"])
            assertEquals("43", conflict.comparison.localDraft.localFields["rating_tenths"])
            assertNull(f.repository.findDraft("A", draft().id)!!.original)
            f.server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\":{\"code\":\"PARENT_UNAVAILABLE\"}}"))
            val child = draft().copy(ref = RecordRef(RecordKind.DISH, ref.id, "parent"), localFields = mapOf("name" to "菜品", "rating_tenths" to "43", "review" to null))
            val unavailable = f.repository.submit(child) as SaveOutcome.Kept
            assertEquals(child.localFields, unavailable.draft.localFields); assertTrue(unavailable.message.contains("先恢复或刷新"))
            assertEquals(2, f.server.requestCount)
        }
    }
    @Test fun manualWriteHonorsRetryAfterWithoutTransportReplayAndKeepsOriginalKey() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2").setBody("{\"error\":{\"code\":\"RATE_LIMITED\"}}"))
            val limited = f.repository.submit(draft()) as SaveOutcome.Kept
            val first = f.server.takeRequest()
            assertEquals(3000L, limited.draft.retryNotBefore)
            assertNotNull(limited.draft.original)
            f.now = 2999
            assertIs<SaveOutcome.Kept>(f.repository.submit(limited.draft))
            assertEquals(1, f.server.requestCount, "Retry-After must prevent another write before its deadline")
            f.now = 3000
            f.server.enqueue(mutation()); f.server.enqueue(detail())
            assertIs<SaveOutcome.Saved>(f.repository.submit(limited.draft))
            val retry = f.server.takeRequest()
            assertEquals(first.getHeader("Idempotency-Key"), retry.getHeader("Idempotency-Key"))
            assertEquals(first.body.readUtf8(), retry.body.readUtf8())
        }
    }
    @Test fun acknowledgedSaveDoesNotBecomeUnknownWhenParentStatisticsGetFails() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(mutation())
            repeat(3) { f.server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":{\"code\":\"TEMPORARILY_UNAVAILABLE\"}}")) }
            assertIs<SaveOutcome.Saved>(f.repository.submit(draft()))
            assertNull(f.repository.findDraft("A", draft().id))
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
            assertIs<SaveOutcome.Saved>(f.repository.submit(draft()))
            val query = ListQuery(RecordKind.REVISION, ref.id)
            val before = f.repository.observeList("A", query).first().items.single()
            assertTrue(historySummary(before, null).contains("★ 4.0 → ★ 4.3"))
            f.server.enqueue(MockResponse().setBody("{\"data\":{\"items\":[$revision]},\"meta\":{\"has_more\":false,\"next_cursor\":null}}"))
            f.repository.refreshList("A", query)
            assertTrue(historySummary(f.repository.observeList("A", query).first().items.single(), null).contains("★ 4.0 → ★ 4.3"))
            assertEquals(3, f.server.requestCount, "Opening history should not download all full diff details")
        }
    }
    @Test fun closingRoomAndCreatingANewRepositoryPreservesTheExactUnknownRequest() = runBlocking {
        Fixture().use { f ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "p4-draft-${java.util.UUID.randomUUID()}.db"
            fun open() = Room.databaseBuilder(context, ProductDatabase::class.java, name).build()
            fun repository(db: ProductDatabase) = ProductRepositoryImpl(db, OwnerCache(db), f.account,
                ProductRemote.create(f.baseUrl, RefreshCoordinator(f.store, f.account, f.gateway), f.client), f.online) { f.now }
            var disk = open()
            try {
                val original = requestFor(draft(), f.now).copy(status = DraftStatus.RESULT_UNKNOWN)
                repository(disk).storeDraft(original)
                disk.close()
                disk = open()
                val restarted = repository(disk)
                val restored = restarted.findDraft("A", original.id)!!
                assertEquals(original, restored)
                assertEquals(0, f.server.requestCount, "Opening Room must not upload a draft")
                f.server.enqueue(mutation()); f.server.enqueue(detail())
                assertIs<SaveOutcome.Saved>(restarted.submit(restored))
                val request = f.server.takeRequest()
                assertEquals(original.original!!.key, request.getHeader("Idempotency-Key"))
                assertEquals(original.original!!.payload, request.body.readUtf8())
                assertNull(restarted.findDraft("A", original.id))
            } finally { disk.close(); context.deleteDatabase(name) }
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
}
