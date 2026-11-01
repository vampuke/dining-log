package top.alanvampuke.dininglog.data.auth

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.*
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.core.auth.*
import top.alanvampuke.dininglog.data.remote.*

@Serializable data class PasswordBody(@SerialName("current_password") val current: String,
    @SerialName("new_password") val replacement: String, @SerialName("device_name") val deviceName: String = "Android")
interface SecurityApi {
    @GET("me") suspend fun profile(@Header("Authorization") token: String): Response<ObjectEnvelope>
    @GET("auth/sessions") suspend fun sessions(@Header("Authorization") token: String): Response<ObjectEnvelope>
    @DELETE("auth/sessions/{id}") suspend fun revoke(@Path("id") id: String, @Header("Authorization") token: String, @Header("Idempotency-Key") key: String): Response<Unit>
    @POST("auth/password") suspend fun password(@Header("Authorization") token: String, @Header("Idempotency-Key") key: String, @Body body: PasswordBody): Response<AuthEnvelope>
}
class RetrofitAccountSecurity(private val api: SecurityApi, private val refresh: RefreshCoordinator,
    private val account: AccountSession, private val now: () -> Long = System::currentTimeMillis) : AccountSecurity {
    // Passwords and pending auth writes live ONLY in memory, never Room/SavedStateHandle.
    private data class PendingPassword(val body: PasswordBody, val key: String, val token: String, val generation: Long, val startedAt: Long)
    private var pendingPassword: PendingPassword? = null
    override suspend fun profile(): AccountInfo = read { token ->
        val value = checked(api.profile("Bearer $token")).data
        AccountInfo(requireNotNull(value.text("id")), requireNotNull(value.text("username")), value.text("display_name") ?: "", value.text("created_at") ?: "")
    }
    override suspend fun sessions(): List<DeviceSession> = read { token -> checked(api.sessions("Bearer $token")).data["items"]!!.jsonArray.map { item ->
        val s = item.jsonObject
        DeviceSession(requireNotNull(s.text("id")), s.text("device_name") ?: "Android", s.text("created_at") ?: "",
            s.text("last_refreshed_at") ?: "", s.text("expires_at") ?: "", s.text("is_current") == "true")
    } }
    override suspend fun revokeSession(id: String) {
        val key = UUID.randomUUID().toString()
        read { token ->
            val response = api.revoke(id, "Bearer $token", key)
            if (!response.isSuccessful) checked(response)
        }
        if (account.session.value?.sessionId == id) account.clear()
    }
    override suspend fun changePassword(current: String, replacement: String) {
        val body = PasswordBody(current, replacement)
        val old = pendingPassword
        if (old != null && old.generation != account.generation) { pendingPassword = null; throw AuthFailure("SESSION_REVOKED") }
        if (old != null && old.body != body) throw ProductFailure("AUTH_RESULT_UNKNOWN")
        if (old != null && now() - old.startedAt >= 120_000) {
            pendingPassword = null
            throw ProductFailure("AUTH_RETRY_EXPIRED")
        }
        val request = old ?: PendingPassword(body, UUID.randomUUID().toString(), requireNotNull(account.session.value).accessToken, account.generation, now())
        pendingPassword = request
        try {
            // Original access/key/body permit the auth endpoint's encrypted replay after revocation.
            val next = checked(api.password("Bearer ${request.token}", request.key, request.body)).data.domain()
            account.replaceAfterPassword(next, request.generation)
            pendingPassword = null
        } catch (_: IOException) { throw ProductFailure("AUTH_RESULT_UNKNOWN") }
        catch (e: ProductFailure) {
            if (e.status in 400..499) pendingPassword = null
            throw e
        }
    }
    private suspend fun <T> read(block: suspend (String) -> T): T = try { refresh.authenticated(block) }
        catch (_: IOException) { throw ProductFailure("NETWORK_ERROR") }
    companion object {
        fun create(baseUrl: String, refresh: RefreshCoordinator, account: AccountSession): RetrofitAccountSecurity = RetrofitAccountSecurity(
            Retrofit.Builder().baseUrl(baseUrl).client(OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(false).build())
                .addConverterFactory(ProductJson.asConverterFactory("application/json".toMediaType())).build().create(SecurityApi::class.java), refresh, account)
    }
}
