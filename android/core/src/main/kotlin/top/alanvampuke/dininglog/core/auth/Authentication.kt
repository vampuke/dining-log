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
// Every account transition clears these states, including future list/draft consumers.
class AccountMemory : AccountLocalData {
    val lists = mutableListOf<String>()
    val drafts = mutableMapOf<String, String>()
    val navigation = mutableListOf<String>()
    override suspend fun clear() { lists.clear(); drafts.clear(); navigation.clear() }
}
class AccountSession(private val store: SecureTokenStore, private val local: AccountLocalData) {
    private val _session = MutableStateFlow<AuthSession?>(null)
    val session = _session.asStateFlow()
    private var ownerJob = SupervisorJob()
    var ownerScope = CoroutineScope(ownerJob + Dispatchers.Default); private set
    var generation = 0L; private set
    suspend fun clear() {
        generation++
        _session.value = null
        ownerJob.cancel()
        store.clear()
        local.clear()
        ownerJob = SupervisorJob(); ownerScope = CoroutineScope(ownerJob + Dispatchers.Default)
    }
    suspend fun install(value: AuthSession) {
        clear()
        try {
            store.write(StoredSession(value.userId, value.sessionId, value.refreshToken))
            _session.value = value
        } catch (error: Exception) { clear(); throw error }
    }
    internal fun publishRefresh(value: AuthSession) { _session.value = value }
}
class RefreshCoordinator(private val store: SecureTokenStore, private val account: AccountSession,
    private val gateway: AuthGateway, private val nowMillis: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var lastNetworkFailure: Pair<String?, Long>? = null
    suspend fun refreshAfter401(failedAccessToken: String?): String = mutex.withLock {
        val current = account.session.value
        if (current != null && current.accessToken != failedAccessToken) return@withLock current.accessToken
        val saved = store.read() ?: run { account.clear(); throw AuthFailure("SESSION_REVOKED") }
        if (lastNetworkFailure?.let { it.first == failedAccessToken && nowMillis() - it.second < 1000 } == true) throw AuthFailure("NETWORK_ERROR")
        val pending = if (saved.pendingRequestId == null) saved.copy(pendingRequestId = UUID.randomUUID().toString(), pendingStartedAt = nowMillis()) else saved
        if (nowMillis() - pending.pendingStartedAt!! >= 120_000) { account.clear(); throw AuthFailure("SESSION_REVOKED") }
        val generation = account.generation
        try {
            // Stable request id is durable BEFORE the request; new token BEFORE publishing access.
            store.write(pending)
            val next = gateway.refresh(pending.refreshToken, pending.pendingRequestId!!)
            if (generation != account.generation) throw AuthFailure("SESSION_REVOKED")
            require(next.userId == saved.userId && next.sessionId == saved.sessionId)
            store.write(StoredSession(next.userId, next.sessionId, next.refreshToken))
            account.publishRefresh(next)
            lastNetworkFailure = null
            next.accessToken
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (generation != account.generation) throw AuthFailure("SESSION_REVOKED")
            if (error is AuthFailure && error.code in setOf("NETWORK_ERROR", "TEMPORARILY_UNAVAILABLE", "RATE_LIMITED")) {
                lastNetworkFailure = failedAccessToken to nowMillis(); throw error
            }
            account.clear(); throw error
        }
    }
    // Each API operation is retried at most once; revoked sessions never enter a refresh loop.
    suspend fun <T> authenticated(call: suspend (String) -> T): T {
        val generation = account.generation
        val access = account.session.value?.accessToken ?: refreshAfter401(null)
        if (generation != account.generation) throw AuthFailure("SESSION_REVOKED")
        try { return call(access) } catch (error: AuthFailure) {
            if (error.code == "TOKEN_EXPIRED") {
                val next = refreshAfter401(access)
                if (generation != account.generation) throw AuthFailure("SESSION_REVOKED")
                try { return call(next) } catch (retry: AuthFailure) {
                    if (retry.code in setOf("SESSION_REVOKED", "REFRESH_REUSED") && generation == account.generation) account.clear()
                    throw retry
                }
            }
            if (error.code in setOf("SESSION_REVOKED", "REFRESH_REUSED") && generation == account.generation) account.clear()
            throw error
        }
    }
}
