package top.alanvampuke.dininglog.core

import java.io.File
import java.net.URI
import java.security.MessageDigest

fun isNewerVersion(candidate: Int, installed: Int) = candidate > 0 && candidate > installed

// The configured API origin and this repository's release APKs are the only entry URLs.
// Hosts used by GitHub's signed asset redirects are permitted only after that entry URL.
fun isAllowedUpdateUrl(url: String, apiBaseUrl: String): Boolean = runCatching {
    val address = URI(url)
    val api = URI(apiBaseUrl)
    val safe = address.scheme == "https" && address.userInfo == null && address.fragment == null &&
        address.host != null && address.port in listOf(-1, 443) && address.normalize().path == address.path &&
        address.rawPath?.contains('%') != true
    safe && ((address.host.equals(api.host, ignoreCase = true) && api.scheme == "https") ||
        (address.host.equals("github.com", ignoreCase = true) &&
            address.path.startsWith("/vampuke/dining-log/releases/download/") && address.path.endsWith(".apk")))
}.getOrDefault(false)

fun isAllowedUpdateRedirect(url: String, initialUrl: String, apiBaseUrl: String): Boolean = runCatching {
    if (isAllowedUpdateUrl(url, apiBaseUrl)) return@runCatching true
    val target = URI(url)
    val initial = URI(initialUrl)
    initial.host == "github.com" && isAllowedUpdateUrl(initialUrl, apiBaseUrl) && target.scheme == "https" &&
        target.userInfo == null && target.fragment == null && target.port in listOf(-1, 443) &&
        target.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
}.getOrDefault(false)

fun verifyApkSha256(file: File, expected: String): Boolean {
    if (!expected.matches(Regex("[a-fA-F0-9]{64}")) || !file.isFile || file.length() == 0L) return false
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    val wanted = expected.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    return MessageDigest.isEqual(digest.digest(), wanted)
}

enum class UpdateStage { IDLE, DOWNLOADING, VERIFYING, READY, NEED_PERMISSION, INSTALLING, FAILED, CANCELED }
data class UpdateInstallState(val release: AppRelease? = null, val stage: UpdateStage = UpdateStage.IDLE,
    val downloaded: Long = 0, val total: Long? = null, val message: String? = null) {
    val busy: Boolean get() = stage in setOf(UpdateStage.DOWNLOADING, UpdateStage.VERIFYING)
    val percent: Int? get() = total?.takeIf { it > 0 }?.let { (downloaded.toDouble() / it * 100).toInt().coerceIn(0, 100) }
}
