package top.alanvampuke.dininglog.core.auth

import kotlinx.coroutines.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.*

private class MemoryFile : AtomicTokenFile {
    var bytes: ByteArray? = null
    var failWrites = false
    @Volatile var beforeReadReturn: (() -> Unit)? = null
    override fun read(): ByteArray? {
        val snapshot = bytes?.clone()
        beforeReadReturn?.invoke()
        return snapshot
    }
    override fun replace(bytes: ByteArray) { if (failWrites) error("disk failure"); this.bytes = bytes.clone() }
    override fun delete() { bytes = null }
}
private class TestKeys : KeyManager {
    var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun existingKey() = key ?: error("missing key")
    override fun encryptionKey() = existingKey()
}
private class FakeGateway : AuthGateway {
    var refreshCalls = 0
    var failure: String? = null
    var pause: CompletableDeferred<Unit>? = null
    val requestIds = mutableListOf<String>()
    var next = AuthSession("a", "alice", "s", "new-access", "new-refresh")
    override suspend fun refresh(token: String, requestId: String): AuthSession {
        refreshCalls++; requestIds += requestId; delay(50); pause?.await()
        failure?.let { throw AuthFailure(it) }
        return next
    }
    override suspend fun login(username: String, password: String) = next
    override suspend fun register(username: String, password: String) = next
    override suspend fun recover(username: String, code: String, password: String) = next
    override suspend fun logout(token: String) {}
}
private class Fixture {
    val file = MemoryFile(); val keys = TestKeys()
    val store = SecureTokenStore(file, AesGcmTokenCipher(keys, "top.alanvampuke.dininglog"))
    val memory = AccountMemory(); val account = AccountSession(store, memory); val gateway = FakeGateway()
    var now = 10_000L
    val coordinator = RefreshCoordinator(store, account, gateway) { now }
    val old = AuthSession("a", "alice", "s", "old-access", "old-refresh")
}
class AuthTest {
    @Test fun secureStoreRoundTripUsesFreshNonceAndBindsApplicationId() {
        val f = Fixture(); val saved = StoredSession("a", "s", "secret-refresh", "request", 100L)
        f.store.write(saved); val first = f.file.bytes!!.clone(); assertEquals(saved, f.store.read())
        assertFalse(String(first, Charsets.ISO_8859_1).contains("secret-refresh"))
        f.store.write(saved); assertFalse(first.contentEquals(f.file.bytes!!))
        val other = SecureTokenStore(f.file, AesGcmTokenCipher(f.keys, "other.app"))
        assertNull(other.read()); assertNull(f.file.bytes)
    }
    @Test fun damagedCiphertextClearsSessionAndReturnsLogin() = runBlocking {
        val f = Fixture(); f.account.install(f.old)
        f.file.bytes!![20] = (f.file.bytes!![20].toInt() xor 1).toByte()
        assertFailsWith<AuthFailure> { f.coordinator.refreshAfter401("old-access") }
        assertNull(f.account.session.value); assertNull(f.file.bytes); assertEquals(0, f.gateway.refreshCalls)
    }
    @Test fun missingKeyNeverFallsBackToPlaintext() {
        val f = Fixture(); f.store.write(StoredSession("a", "s", "secret")); f.keys.key = null
        assertNull(f.store.read()); assertNull(f.file.bytes)
        assertFails { f.store.write(StoredSession("b", "t", "plain-secret")) }; assertNull(f.file.bytes)
    }
    @Test fun switchingAccountsClearsListsNavigationAndOwnerJobs() = runBlocking {
        val f = Fixture(); f.account.install(f.old)
        f.memory.lists += "A record"; f.memory.navigation += "A/detail"
        val oldJob = f.account.ownerScope.launch { delay(Long.MAX_VALUE) }
        f.account.clear(); f.account.install(AuthSession("b", "bob", "t", "B-access", "B-refresh"))
        assertTrue(oldJob.isCancelled); assertTrue(f.memory.lists.isEmpty()); assertTrue(f.memory.navigation.isEmpty())
        assertEquals("b", f.store.read()!!.userId); assertFalse(String(f.file.bytes!!).contains("old-refresh"))
    }
    @Test fun concurrent401OperationsRefreshOnceAndReplayEachRequestOnce() = runBlocking {
        val f = Fixture(); f.account.install(f.old)
        val calls = mutableListOf<String>()
        val results = coroutineScope { (1..20).map {
            async { f.coordinator.authenticated { token -> calls += token; if (token == "old-access") throw AuthFailure("TOKEN_EXPIRED"); "ok" } }
        }.awaitAll() }
        assertEquals(List(20) { "ok" }, results); assertEquals(1, f.gateway.refreshCalls)
        assertEquals(20, calls.count { it == "old-access" }); assertEquals(20, calls.count { it == "new-access" })
        assertEquals("new-refresh", f.store.read()!!.refreshToken); assertNull(f.store.read()!!.pendingRequestId)
    }
    @Test fun timeoutRetryReusesDurableRequestIdAndOldToken() = runBlocking {
        val f = Fixture(); f.account.install(f.old); f.gateway.failure = "NETWORK_ERROR"
        assertFailsWith<AuthFailure> { f.coordinator.refreshAfter401("old-access") }
        val pending = f.store.read()!!; assertNotNull(pending.pendingRequestId); assertEquals("old-refresh", pending.refreshToken)
        assertEquals(f.old, f.account.session.value)
        f.now += 1001; f.gateway.failure = null; f.coordinator.refreshAfter401("old-access")
        assertEquals(2, f.gateway.refreshCalls); assertEquals(1, f.gateway.requestIds.toSet().size)
    }
    @Test fun simulatedProcessRestartRecoversPendingRotationWithoutPersistingAccess() = runBlocking {
        val f = Fixture(); f.account.install(f.old); f.store.write(StoredSession("a", "s", "old-refresh", "stable-id", f.now))
        val process2 = AccountSession(f.store, AccountMemory()); val coordinator = RefreshCoordinator(f.store, process2, f.gateway) { f.now }
        assertNull(process2.session.value); assertEquals("new-access", coordinator.refreshAfter401(null))
        assertEquals(listOf("stable-id"), f.gateway.requestIds); assertEquals("a", process2.session.value!!.userId)
    }
    @Test fun expiredPendingRetryRequiresLoginWithoutSendingOldToken() = runBlocking {
        val f = Fixture(); f.store.write(StoredSession("a", "s", "old-refresh", "stable-id", f.now - 120_000))
        assertFailsWith<AuthFailure> { f.coordinator.refreshAfter401(null) }
        assertNull(f.file.bytes); assertEquals(0, f.gateway.refreshCalls)
    }
    @Test fun revokedRefreshClearsLocalStateAndDoesNotLoop() = runBlocking {
        val f = Fixture(); f.account.install(f.old); f.gateway.failure = "REFRESH_REUSED"
        assertFailsWith<AuthFailure> { f.coordinator.refreshAfter401("old-access") }
        assertNull(f.account.session.value); assertNull(f.file.bytes); assertTrue(f.memory.lists.isEmpty())
        assertFailsWith<AuthFailure> { f.coordinator.refreshAfter401(null) }; assertEquals(1, f.gateway.refreshCalls)
    }
    @Test fun diskFailureDoesNotPublishNewAccessOrLeaveSession() = runBlocking {
        val f = Fixture(); f.account.install(f.old); f.file.failWrites = true
        assertFails { f.coordinator.refreshAfter401("old-access") }
        assertNull(f.account.session.value); assertNull(f.file.bytes); assertEquals(0, f.gateway.refreshCalls)
    }
    @Test fun accountSwitchDuringRefreshCannotPublishPreviousAccount() = runBlocking {
        val f = Fixture(); f.account.install(f.old); f.gateway.pause = CompletableDeferred()
        val job = async { assertFailsWith<AuthFailure> { f.coordinator.refreshAfter401("old-access") } }
        while (f.gateway.refreshCalls == 0) yield()
        val bob = AuthSession("b", "bob", "t", "B-access", "B-refresh")
        f.account.install(bob); f.gateway.pause!!.complete(Unit); job.await()
        assertEquals(bob, f.account.session.value); assertEquals("b", f.store.read()!!.userId)
    }
    @Test fun concurrentNetworkFailuresAreCoalescedAndKeepEncryptedRetryState() = runBlocking {
        val f = Fixture(); f.account.install(f.old); f.gateway.failure = "NETWORK_ERROR"
        coroutineScope { (1..20).map { async { assertFailsWith<AuthFailure> { f.coordinator.refreshAfter401("old-access") } } }.awaitAll() }
        assertEquals(1, f.gateway.refreshCalls); assertNotNull(f.store.read()!!.pendingRequestId); assertEquals(f.old, f.account.session.value)
    }
    @Test fun accountSwitchBetweenRefreshReadAndPendingWriteCannotRestoreOldAccount() = runBlocking {
        withTimeout(5000) {
            val f = Fixture(); f.account.install(f.old)
            val readStarted = CountDownLatch(1); val releaseRead = CountDownLatch(1)
            val generation = f.account.generation
            f.file.beforeReadReturn = { readStarted.countDown(); assertTrue(releaseRead.await(5, TimeUnit.SECONDS)) }
            val refreshing = async(Dispatchers.Default) { assertFailsWith<AuthFailure> { f.coordinator.refreshAfter401("old-access") } }
            try {
                assertTrue(readStarted.await(5, TimeUnit.SECONDS))
                val bob = AuthSession("b", "bob", "t", "B-access", "B-refresh")
                val switching = async(Dispatchers.Default) { f.account.install(bob) }
                while (f.account.generation == generation) delay(1)
                f.file.beforeReadReturn = null; releaseRead.countDown()
                switching.await(); refreshing.await()
                assertEquals(bob, f.account.session.value); assertEquals("b", f.store.read()!!.userId)
                assertEquals(0, f.gateway.refreshCalls)
            } finally { f.file.beforeReadReturn = null; releaseRead.countDown() }
        }
    }
    @Test fun suspendedAccountCleanupSerializesLaterAccountInstall() = runBlocking {
        val f = Fixture(); val resumeCleanup = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        val local = object : AccountLocalData {
            override suspend fun clear() { if (calls.incrementAndGet() == 2) resumeCleanup.await() }
        }
        val account = AccountSession(f.store, local); account.install(f.old)
        val bob = AuthSession("b", "bob", "t", "B-access", "B-refresh")
        val carol = AuthSession("c", "carol", "u", "C-access", "C-refresh")
        val first = async(start = CoroutineStart.UNDISPATCHED) { account.install(bob) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { account.install(carol) }
        try { assertFalse(second.isCompleted) } finally { resumeCleanup.complete(Unit) }
        first.await(); second.await()
        assertEquals(carol, account.session.value); assertEquals("c", f.store.read()!!.userId)
    }

}
