package top.alanvampuke.dininglog.data.remote

import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import top.alanvampuke.dininglog.core.AppRelease
import top.alanvampuke.dininglog.core.AppVersionGateway

@Serializable data class AppReleaseDto(
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String,
    val url: String, val sha256: String, val notes: String,
    @SerialName("released_at") val releasedAt: Long,
) {
    fun domain() = AppRelease(versionCode, versionName, url, sha256, notes, releasedAt).also { require(it.valid) }
}
@Serializable data class AppVersionResponse(val latest: AppReleaseDto)
interface AppVersionApi {
    @Headers("Accept: application/json")
    @GET("app/version") suspend fun version(): AppVersionResponse
}
class PublicAppVersionGateway(private val api: AppVersionApi) : AppVersionGateway {
    override suspend fun latest() = api.version().latest.domain()
    companion object {
        fun create(baseUrl: String): PublicAppVersionGateway {
            // This separate client has no authentication, cookies, user identifiers
            // or RefreshCoordinator; update checks also work before login.
            val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
            return PublicAppVersionGateway(Retrofit.Builder().baseUrl(baseUrl).client(client)
                .addConverterFactory(ProductJson.asConverterFactory("application/json".toMediaType()))
                .build().create(AppVersionApi::class.java))
        }
    }
}
