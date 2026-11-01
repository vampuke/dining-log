package top.alanvampuke.dininglog.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
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
        val online = MutableStateFlow(true)
        var now = 1000L
        val repository = ProductRepositoryImpl(db, owner, account, ProductRemote.create(server.url("/api/v1/").toString(), RefreshCoordinator(store, account, gateway)), online) { now }
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
            assertIs<SaveOutcome.Saved>(f.repository.submit(restored))
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
