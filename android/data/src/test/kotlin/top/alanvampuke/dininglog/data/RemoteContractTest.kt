package top.alanvampuke.dininglog.data

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import kotlin.test.*
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.core.auth.*
import top.alanvampuke.dininglog.data.remote.*
import top.alanvampuke.dininglog.data.auth.RetrofitAuthGateway

class RemoteContractTest {
    @Test fun authDefaultDeviceNameIsSentForRegistrationLoginAndRecovery() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val gateway = RetrofitAuthGateway.create(server.url("/api/v1/").toString())
            fun enqueue() { server.enqueue(MockResponse().setBody("""{"data":{"user":{"id":"account","username":"alice"},"session_id":"session","access_token":"access","refresh_token":"refresh"}}""")) }
            enqueue(); gateway.register("alice", "registration-fixture-password")
            enqueue(); gateway.login("alice", "registration-fixture-password")
            enqueue(); gateway.recover("alice", "recovery-code", "replacement-fixture-password")
            for (path in listOf("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/recover")) {
                val request = server.takeRequest()
                assertEquals(path, request.path)
                assertEquals("Android", Json.parseToJsonElement(request.body.readUtf8()).jsonObject["device_name"]!!.jsonPrimitive.content)
                assertNotNull(request.getHeader("Idempotency-Key"))
            }
        } finally { server.shutdown() }
    }
    private fun draft(kind: RecordKind = RecordKind.RESTAURANT, action: WriteAction = WriteAction.CREATE): SavedDraft {
        val ref = RecordRef(kind, "10000000-0000-4000-8000-000000000001", if (kind != RecordKind.RESTAURANT) "20000000-0000-4000-8000-000000000001" else null)
        val fields = mapOf("name" to "兰记", "category" to "restaurant", "address" to "愚园路", "rating_tenths" to "43", "review" to "花椒香", "note" to null)
        val base = RecordSnapshot(ref, 2, fields + ("rating_tenths" to "40"), "2026-11-01T00:00:00Z")
        return SavedDraft("A", "draft", ref, action, if (action == WriteAction.CREATE) null else base, fields)
    }
    @Test fun restaurantAndFirstVisitAreOneRequestAndDishNeverHasVisitId() {
        val restaurant = requestFor(draft().copy(firstVisit = FirstVisitInput("30000000-0000-4000-8000-000000000001", "2026-11-01")), 0)
        val body = Json.parseToJsonElement(restaurant.original!!.payload).jsonObject
        assertEquals("4.3", body["rating"]!!.jsonPrimitive.content)
        assertEquals("2026-11-01", body["first_visit"]!!.jsonObject["dining_date"]!!.jsonPrimitive.content)
        val dish = requestFor(draft(RecordKind.DISH), 0)
        val dishBody = Json.parseToJsonElement(dish.original!!.payload).jsonObject
        assertEquals(setOf("id", "name", "rating", "review"), dishBody.keys)
        assertFalse(dishBody.containsKey("visit_id")); assertFalse(dishBody.containsKey("restaurant_id"))
    }
    @Test fun patchSendsOnlyChangedFieldsAndUnknownKeepsExactWirePayloadAndKey() {
        val prepared = requestFor(draft(action = WriteAction.UPDATE), 0)
        val body = Json.parseToJsonElement(prepared.original!!.payload).jsonObject
        assertEquals(setOf("expected_version", "rating"), body.keys)
        assertEquals("2", body["expected_version"]!!.jsonPrimitive.content)
        assertEquals(prepared.original, requestFor(prepared.copy(status = DraftStatus.RESULT_UNKNOWN), 100).original)
    }
    @Test fun metadataAndHistoryApplyUseTheirOwnCasAndSelectedFieldsOnly() {
        val base = draft(RecordKind.REVISION, WriteAction.METADATA)
        val metadata = requestFor(base.copy(localFields = base.localFields + ("note" to "说明")), 0)
        val metaBody = Json.parseToJsonElement(metadata.original!!.payload).jsonObject
        assertEquals(setOf("expected_metadata_version", "note"), metaBody.keys)
        val apply = requestFor(draft(action = WriteAction.APPLY).copy(sourceRevisionId = "source", applyFields = listOf("rating_tenths")), 0)
        val applyBody = Json.parseToJsonElement(apply.original!!.payload).jsonObject
        assertEquals("revisions", apply.original!!.path); assertEquals("2", applyBody["expected_version"]!!.jsonPrimitive.content)
        assertEquals(listOf("rating_tenths"), applyBody["fields"]!!.jsonArray.map { it.jsonPrimitive.content })
        val scopedHistory = ListQuery(RecordKind.REVISION, "parent", historyEntityKind = RecordKind.DISH, historyEntityId = "dish")
        assertEquals("dish", queryParameters(scopedHistory)["entity_type"])
        assertEquals("dish", queryParameters(scopedHistory)["entity_id"])
        assertEquals("parent", queryParameters(scopedHistory)["restaurant_id"])
        assertNotEquals(scopedHistory.fingerprint, scopedHistory.copy(historyEntityId = "other-dish").fingerprint)
    }
    @Test fun timelineDateSearchUsesTheServersClosedDateRangeWithoutChangingTextSearch() {
        val date = ListQuery(RecordKind.VISIT, query = "2026-11-01")
        val parameters = queryParameters(date)
        assertFalse("q" in parameters)
        assertEquals("2026-11-01", parameters["from"])
        assertEquals("2026-11-01", parameters["to"])
        val ranged = queryParameters(date.copy(filters = ListFilters(from = "2026-10-01", to = "2026-12-01")))
        assertEquals("2026-11-01", ranged["from"]); assertEquals("2026-11-01", ranged["to"])
        assertEquals("兰记", queryParameters(date.copy(query = "兰记"))["q"])
        assertEquals("同日第二次", queryParameters(date.copy(query = "同日第二次"))["q"])
    }
    @Test fun getRetriesAtMostTwiceWithJitterAndHonorsRetryAfter() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2").setBody("{\"error\":{\"code\":\"RATE_LIMITED\"}}"))
            server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":{\"code\":\"TEMPORARILY_UNAVAILABLE\"}}"))
            server.enqueue(MockResponse().setBody("{\"data\":{\"items\":[]},\"meta\":{\"has_more\":false,\"next_cursor\":null}}"))
            val memoryFile = object : AtomicTokenFile { var bytes: ByteArray? = null; override fun read() = bytes; override fun replace(bytes: ByteArray) { this.bytes = bytes }; override fun delete() { bytes = null } }
            val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
            val keys = object : KeyManager { override fun encryptionKey() = key; override fun existingKey() = key }
            val store = SecureTokenStore(memoryFile, AesGcmTokenCipher(keys, "test"))
            val account = AccountSession(store, AccountMemory()); account.install(AuthSession("A", "alice", "S", "access", "refresh"))
            val gateway = object : AuthGateway {
                override suspend fun login(username: String, password: String): AuthSession = error("unexpected")
                override suspend fun register(username: String, password: String): AuthSession = error("unexpected")
                override suspend fun recover(username: String, code: String, password: String): AuthSession = error("unexpected")
                override suspend fun refresh(token: String, requestId: String): AuthSession = error("unexpected")
                override suspend fun logout(token: String) { }
            }
            val api = Retrofit.Builder().baseUrl(server.url("/api/v1/")).client(OkHttpClient()).addConverterFactory(ProductJson.asConverterFactory("application/json".toMediaType())).build().create(ProductApi::class.java)
            val waits = mutableListOf<Long>()
            val remote = ProductRemote(api, RefreshCoordinator(store, account, gateway), pause = { waits += it }, jitter = { 100 })
            assertTrue(remote.list(ListQuery(), null).first.isEmpty()); assertEquals(3, server.requestCount)
            assertEquals(listOf(2100L, 500L), waits)
            repeat(3) { server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":{\"code\":\"TEMPORARILY_UNAVAILABLE\"}}")) }
            assertFailsWith<ProductFailure> { remote.list(ListQuery(), null) }; assertEquals(6, server.requestCount)
        } finally { server.shutdown() }
    }
}
