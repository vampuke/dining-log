package top.alanvampuke.dininglog.core.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class AuthFailure(val code: String, val retryAfter: Int? = null) : Exception(code)
data class AuthSession(val userId: String, val username: String, val sessionId: String, val accessToken: String, val refreshToken: String,
    val recoveryCodes: List<String> = emptyList())
interface AuthGateway {
    suspend fun login(username: String, password: String): AuthSession
    suspend fun register(username: String, password: String): AuthSession
    suspend fun recover(username: String, code: String, password: String): AuthSession
    suspend fun refresh(token: String, requestId: String): AuthSession
    suspend fun logout(token: String)
}
interface AccountLocalData { suspend fun clear() }
// Every account transition clears these states, including future list/input consumers.
class AccountMemory : AccountLocalData {
    val lists = mutableListOf<String>()
    val navigation = mutableListOf<String>()
    override suspend fun clear() { lists.clear(); navigation.clear() }
}
class AccountSession(private val store: SecureTokenStore, private val local: AccountLocalData) {
    private val transition = Mutex()
    private val _session = MutableStateFlow<AuthSession?>(null)
    val session = _session.asStateFlow()
    private var ownerJob = SupervisorJob()
    var ownerScope = CoroutineScope(ownerJob + Dispatchers.Default); private set
    @Volatile var generation = 0L; private set
    private suspend fun clearLocked() {
        generation++
        _session.value = null
        ownerJob.cancel()
        store.clear()
        local.clear()
        ownerJob = SupervisorJob(); ownerScope = CoroutineScope(ownerJob + Dispatchers.Default)
    }
    suspend fun clear() = transition.withLock { clearLocked() }
    internal suspend fun clearIfGeneration(expected: Long) = transition.withLock {
        if (generation == expected) clearLocked()
    }
    internal suspend fun clearIfAccess(expected: Long, token: String) = transition.withLock {
        if (generation == expected && _session.value?.accessToken == token) clearLocked()
    }
    suspend fun restoreCachedSession(value: StoredSession) = transition.withLock {
        if (_session.value == null) _session.value = AuthSession(value.userId, "已保存账号", value.sessionId, "", value.refreshToken)
    }
    suspend fun install(value: AuthSession) = transition.withLock {
        clearLocked()
        try {
            store.write(StoredSession(value.userId, value.sessionId, value.refreshToken))
            _session.value = value
        } catch (error: Exception) { clearLocked(); throw error }
    }
    suspend fun replaceAfterPassword(value: AuthSession, expected: Long) = transition.withLock {
        if (generation != expected || _session.value?.userId != value.userId) throw AuthFailure("SESSION_REVOKED")
        store.write(StoredSession(value.userId, value.sessionId, value.refreshToken))
        _session.value = value
    }
    internal suspend fun persistPending(value: StoredSession, expected: Long) = transition.withLock {
        if (generation != expected) throw AuthFailure("SESSION_REVOKED")
        store.write(value)
    }
    internal suspend fun commitRefresh(value: AuthSession, expected: Long) = transition.withLock {
        if (generation != expected) throw AuthFailure("SESSION_REVOKED")
        store.write(StoredSession(value.userId, value.sessionId, value.refreshToken))
        _session.value = value
    }
}
class RefreshCoordinator(private val store: SecureTokenStore, private val account: AccountSession,
    private val gateway: AuthGateway, private val nowMillis: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var lastNetworkFailure: Pair<String?, Long>? = null
    suspend fun refreshAfter401(failedAccessToken: String?): String = mutex.withLock {
        val generation = account.generation
        val current = account.session.value
        if (current != null && current.accessToken.isNotEmpty() && current.accessToken != failedAccessToken) return@withLock current.accessToken
        val saved = store.read() ?: run { account.clearIfGeneration(generation); throw AuthFailure("SESSION_REVOKED") }
        if (lastNetworkFailure?.let { it.first == failedAccessToken && nowMillis() - it.second < 1000 } == true) throw AuthFailure("NETWORK_ERROR")
        val pending = if (saved.pendingRequestId == null) saved.copy(pendingRequestId = UUID.randomUUID().toString(), pendingStartedAt = nowMillis()) else saved
        if (nowMillis() - pending.pendingStartedAt!! >= 120_000) { account.clearIfGeneration(generation); throw AuthFailure("SESSION_REVOKED") }
        try {
            // Stable request id is durable BEFORE the request; new token BEFORE publishing access.
            account.persistPending(pending, generation)
            val next = gateway.refresh(pending.refreshToken, pending.pendingRequestId!!)
            if (generation != account.generation) throw AuthFailure("SESSION_REVOKED")
            require(next.userId == saved.userId && next.sessionId == saved.sessionId)
            account.commitRefresh(next, generation)
            lastNetworkFailure = null
            next.accessToken
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (generation != account.generation) throw AuthFailure("SESSION_REVOKED")
            if (error is AuthFailure && error.code in setOf("NETWORK_ERROR", "TEMPORARILY_UNAVAILABLE", "RATE_LIMITED")) {
                lastNetworkFailure = failedAccessToken to nowMillis(); throw error
            }
            account.clearIfGeneration(generation); throw error
        }
    }
    // Each API operation is retried at most once; revoked sessions never enter a refresh loop.
    suspend fun <T> authenticated(call: suspend (String) -> T): T {
        val generation = account.generation
        val access = account.session.value?.accessToken?.takeIf { it.isNotEmpty() } ?: refreshAfter401(null)
        if (generation != account.generation) throw AuthFailure("SESSION_REVOKED")
        try { return call(access) } catch (error: AuthFailure) {
            if (error.code == "TOKEN_EXPIRED") {
                val next = refreshAfter401(access)
                if (generation != account.generation) throw AuthFailure("SESSION_REVOKED")
                try { return call(next) } catch (retry: AuthFailure) {
                    if (retry.code in setOf("SESSION_REVOKED", "REFRESH_REUSED")) account.clearIfAccess(generation, next)
                    throw retry
                }
            }
            if (error.code in setOf("SESSION_REVOKED", "REFRESH_REUSED")) {
                val next = account.session.value?.accessToken
                if (next != null && next.isNotEmpty() && next != access && generation == account.generation) {
                    try { return call(next) } catch (retry: AuthFailure) {
                        if (retry.code in setOf("SESSION_REVOKED", "REFRESH_REUSED")) account.clearIfAccess(generation, next)
                        throw retry
                    }
                }
                account.clearIfAccess(generation, access)
            }
            throw error
        }
    }
}
