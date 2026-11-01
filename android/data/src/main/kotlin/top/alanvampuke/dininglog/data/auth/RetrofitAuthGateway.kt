package top.alanvampuke.dininglog.data.auth

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.HttpException
import top.alanvampuke.dininglog.core.auth.*
import top.alanvampuke.dininglog.data.ApiConfiguration

@Serializable data class UserDto(val id: String, val username: String)
@Serializable data class AuthDto(val user: UserDto, @SerialName("session_id") val sessionId: String,
    @SerialName("access_token") val accessToken: String, @SerialName("refresh_token") val refreshToken: String,
    @SerialName("recovery_codes") val recoveryCodes: List<String> = emptyList()) {
    fun domain() = AuthSession(user.id, user.username, sessionId, accessToken, refreshToken, recoveryCodes)
}
@Serializable data class AuthEnvelope(val data: AuthDto)
@Serializable data class LoginBody(val username: String, val password: String, @SerialName("device_name") val deviceName: String = "Android")
@Serializable data class RecoveryBody(val username: String, @SerialName("recovery_code") val recoveryCode: String,
    @SerialName("new_password") val newPassword: String, @SerialName("device_name") val deviceName: String = "Android")
@Serializable data class RefreshBody(@SerialName("refresh_token") val refreshToken: String)
interface AuthApi {
    @POST("auth/login") suspend fun login(@Body body: LoginBody, @Header("Idempotency-Key") key: String): AuthEnvelope
    @POST("auth/register") suspend fun register(@Body body: LoginBody, @Header("Idempotency-Key") key: String): AuthEnvelope
    @POST("auth/recover") suspend fun recover(@Body body: RecoveryBody, @Header("Idempotency-Key") key: String): AuthEnvelope
    @POST("auth/refresh") suspend fun refresh(@Body body: RefreshBody, @Header("X-Refresh-Request-Id") requestId: String): AuthEnvelope
    @POST("auth/logout") suspend fun logout(@Body body: RefreshBody, @Header("Idempotency-Key") key: String)
}
class RetrofitAuthGateway(private val api: AuthApi) : AuthGateway {
    override suspend fun login(username: String, password: String) = call { api.login(LoginBody(username, password), java.util.UUID.randomUUID().toString()).data.domain() }
    override suspend fun register(username: String, password: String) = call { api.register(LoginBody(username, password), java.util.UUID.randomUUID().toString()).data.domain() }
    override suspend fun recover(username: String, code: String, password: String) = call { api.recover(RecoveryBody(username, code, password), java.util.UUID.randomUUID().toString()).data.domain() }
    override suspend fun refresh(token: String, requestId: String) = call { api.refresh(RefreshBody(token), requestId).data.domain() }
    override suspend fun logout(token: String) = call { api.logout(RefreshBody(token), java.util.UUID.randomUUID().toString()) }
    private suspend fun <T> call(block: suspend () -> T): T = try { block() } catch (e: IOException) {
        throw AuthFailure("NETWORK_ERROR")
    } catch (e: HttpException) {
        val response = e.response()
        val code = try { Json.parseToJsonElement(response?.errorBody()?.string() ?: "{}").jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content } catch (_: Exception) { null }
        throw AuthFailure(code ?: if (e.code() == 429) "RATE_LIMITED" else if (e.code() >= 500) "TEMPORARILY_UNAVAILABLE" else "SESSION_REVOKED",
            response?.headers()?.get("Retry-After")?.toIntOrNull())
    }
    companion object {
        fun create(baseUrl: String = ApiConfiguration.BASE_URL): RetrofitAuthGateway {
            // device_name is required by the auth contract even when the caller
            // uses LoginBody/RecoveryBody's Android default.
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            return RetrofitAuthGateway(Retrofit.Builder().baseUrl(baseUrl)
                .client(OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false).build())
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build().create(AuthApi::class.java))
        }
    }
}
