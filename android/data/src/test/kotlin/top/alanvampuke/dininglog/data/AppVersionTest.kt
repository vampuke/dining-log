package top.alanvampuke.dininglog.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import kotlin.test.*
import retrofit2.HttpException
import top.alanvampuke.dininglog.data.remote.PublicAppVersionGateway

class AppVersionTest {
    private val response = """{"latest":{"version_code":6,"version_name":"0.6.0","url":"https://github.com/vampuke/dining-log/releases/download/v0.6.0/app-release.apk","sha256":"${"0".repeat(64)}","notes":"账号与交互优化","released_at":1793544038}}"""
    @Test fun publicClientSendsNoAuthorizationCookieAccountIdOrQueryAndReadsRawContract() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response))
            val release = PublicAppVersionGateway.create(server.url("/api/v1/").toString()).latest()
            assertEquals(6, release.versionCode); assertEquals("0.6.0", release.versionName)
            assertEquals(64, release.sha256.length); assertTrue(release.valid)
            val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("GET", request.method); assertEquals("/api/v1/app/version", request.path)
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            assertNull(request.getHeader("X-User-Id")); assertNull(request.getHeader("Idempotency-Key"))
            assertEquals(0L, request.bodySize)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun rejectsInvalidMetadataAndForwardsServerFailureToTheSilentChecker() = runBlocking {
        MockWebServer().use { server ->
            val gateway = PublicAppVersionGateway.create(server.url("/api/v1/").toString())
            server.enqueue(MockResponse().setBody(response.replace("https://github.com", "http://github.com")))
            assertFailsWith<IllegalArgumentException> { gateway.latest() }
            server.enqueue(MockResponse().setBody(response.replace("\"version_code\":6", "\"version_code\":0")))
            assertFailsWith<IllegalArgumentException> { gateway.latest() }
            server.enqueue(MockResponse().setResponseCode(503))
            assertFailsWith<HttpException> { gateway.latest() }
            assertEquals(3, server.requestCount)
        }
    }
}
