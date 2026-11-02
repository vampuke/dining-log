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

object VersionCodeSerializer : kotlinx.serialization.KSerializer<Int> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("VersionCode", kotlinx.serialization.descriptors.PrimitiveKind.INT)
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Int {
        val value = (decoder as kotlinx.serialization.json.JsonDecoder).decodeJsonElement() as? kotlinx.serialization.json.JsonPrimitive
            ?: throw kotlinx.serialization.SerializationException("version_code must be an integer")
        val parsed = value.content.toIntOrNull()
        if (value.isString || parsed == null || parsed <= 0) throw kotlinx.serialization.SerializationException("version_code must be a positive integer")
        return parsed
    }
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Int) = encoder.encodeInt(value)
}
@Serializable data class AppReleaseDto(
    @SerialName("version_code") @Serializable(with = VersionCodeSerializer::class) val versionCode: Int,
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
class PublicAppVersionGateway(private val api: AppVersionApi, private val baseUrl: String) : AppVersionGateway {
    override suspend fun latest() = api.version().latest.domain().also {
        require(top.alanvampuke.dininglog.core.isAllowedUpdateUrl(it.url, baseUrl))
    }
    companion object {
        fun create(baseUrl: String): PublicAppVersionGateway {
            // This separate client has no authentication, cookies, user identifiers
            // or RefreshCoordinator; update checks also work before login.
            val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
            return PublicAppVersionGateway(Retrofit.Builder().baseUrl(baseUrl).client(client)
                .addConverterFactory(ProductJson.asConverterFactory("application/json".toMediaType()))
                .build().create(AppVersionApi::class.java), baseUrl)
        }
    }
}
