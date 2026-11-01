package top.alanvampuke.dininglog.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET

object ApiConfiguration { const val BASE_URL = "https://dining.alanvampuke.top/api/v1/" }
@Serializable
data class HealthResponse(val status: String, @SerialName("api_version") val apiVersion: String)
interface HealthApi { @GET("health") suspend fun health(): HealthResponse }
// Room cache/draft entities, owner-scoped DAOs and Keystore token store arrive in P2/P3.
