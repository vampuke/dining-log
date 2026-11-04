package top.alanvampuke.dininglog.data.auth

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
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
    @GET("auth/session") suspend fun profile(@Header("Authorization") token: String): Response<ObjectEnvelope>
    @POST("auth/password") suspend fun password(@Header("Authorization") token: String, @Header("Idempotency-Key") key: String, @Body body: PasswordBody): Response<AuthEnvelope>
}
class RetrofitAccountSecurity(private val api: SecurityApi, private val refresh: RefreshCoordinator,
    private val account: AccountSession, private val now: () -> Long = System::currentTimeMillis) : AccountSecurity {
    // Passwords and pending auth writes live ONLY in memory, never Room/SavedStateHandle.
    private data class PendingPassword(val body: PasswordBody, val key: String, val token: String, val generation: Long, val startedAt: Long)
    private var pendingPassword: PendingPassword? = null
    override suspend fun profile(): AccountInfo = read { token ->
        val value = checked(api.profile("Bearer $token")).data["user"]!!.jsonObject
        AccountInfo(requireNotNull(value.text("id")), requireNotNull(value.text("username")), value.text("display_name") ?: "", value.text("created_at") ?: "")
    }
    override suspend fun changePassword(current: String, replacement: String) {
        val body = PasswordBody(current, replacement)
        var old = pendingPassword
        if (old != null && old.generation != account.generation) { pendingPassword = null; old = null }
        if (old != null && old.body != body) throw ProductFailure("AUTH_RESULT_UNKNOWN")
        if (old != null && now() - old.startedAt >= 120_000) {
            pendingPassword = null
            throw ProductFailure("AUTH_RETRY_EXPIRED")
        }
        val request = old ?: PendingPassword(body, UUID.randomUUID().toString(), requireNotNull(account.session.value).accessToken, account.generation, now())
        pendingPassword = request
        try {
            // Original access/key/body permit the auth endpoint's encrypted replay after revocation.
            val next = if (old == null) refresh.authenticated { token ->
                pendingPassword = request.copy(token = token)
                checked(api.password("Bearer $token", request.key, request.body)).data.domain()
            } else checked(api.password("Bearer ${request.token}", request.key, request.body)).data.domain()
            account.replaceAfterPassword(next, request.generation)
            pendingPassword = null
        } catch (_: IOException) { throw ProductFailure("AUTH_RESULT_UNKNOWN") }
        catch (e: ProductFailure) {
            if (e.status >= 500) throw ProductFailure("AUTH_RESULT_UNKNOWN")
            pendingPassword = null
            throw e
        } catch (e: CancellationException) { throw e }
        catch (e: AuthFailure) {
            if (e.code in setOf("NETWORK_ERROR", "TEMPORARILY_UNAVAILABLE")) throw ProductFailure("AUTH_RESULT_UNKNOWN")
            pendingPassword = null
            throw e
        } catch (_: Exception) {
            // A Keystore write can fail after the password has already changed.
            // Retain the exact request so its encrypted auth replay can recover it.
            throw ProductFailure("AUTH_RESULT_UNKNOWN")
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
