package top.alanvampuke.dininglog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.Context
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.data.ApiConfiguration

@Singleton
class InAppUpdates @Inject constructor(@ApplicationContext private val context: Context, private val versions: AppVersionGateway) {
    private val mutableState = MutableStateFlow(UpdateInstallState())
    val state = mutableState.asStateFlow()
    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null
    private fun apk(release: AppRelease) = File(File(context.cacheDir, "updates"), "app-${release.versionCode}.apk")

    fun open(release: AppRelease) {
        if (mutableState.value.busy || mutableState.value.release == release) return
        if (!release.valid || !isNewerVersion(release.versionCode, BuildConfig.VERSION_CODE) ||
            !isAllowedUpdateUrl(release.url, ApiConfiguration.BASE_URL)) return
        mutableState.value = UpdateInstallState(release)
    }
    fun startDownload() {
        val release = state.value.release ?: return
        if (state.value.busy) return
        cancelled = false
        mutableState.value = UpdateInstallState(release, UpdateStage.DOWNLOADING)
        try { context.startForegroundService(Intent(context, UpdateDownloadService::class.java)) }
        catch (_: Exception) { fail("无法启动下载，请打开下载页") }
    }
    fun cancel() {
        cancelled = true; connection?.disconnect()
        mutableState.value = state.value.copy(stage = UpdateStage.CANCELED, message = "下载已取消，可重试或打开下载页")
        try { context.startService(Intent(context, UpdateDownloadService::class.java).setAction(UpdateDownloadService.CANCEL)) }
        catch (_: Exception) { context.stopService(Intent(context, UpdateDownloadService::class.java)) }
    }
    fun fail(message: String) { mutableState.value = state.value.copy(stage = UpdateStage.FAILED, message = message) }

    suspend fun download() {
        val release = state.value.release ?: return
        val file = apk(release)
        try {
            // Fetch from the injected /app/version gateway again, never from Intent extras or user input.
            val current = versions.latest()
            check(current == release && current.valid && isNewerVersion(current.versionCode, BuildConfig.VERSION_CODE) &&
                isAllowedUpdateUrl(current.url, ApiConfiguration.BASE_URL)) { "版本信息已更新，请重新检查或打开下载页" }
            currentCoroutineContext().ensureActive()
            if (cancelled) throw CancellationException()
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建下载目录，请打开下载页" }
            var address = current.url
            var redirects = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                if (cancelled) throw CancellationException()
                val request = URL(address).openConnection() as HttpURLConnection
                connection = request
                request.instanceFollowRedirects = false
                request.connectTimeout = 15_000; request.readTimeout = 15_000
                request.setRequestProperty("Accept", "application/vnd.android.package-archive")
                val code = request.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val location = request.getHeaderField("Location") ?: throw IOException("下载地址无效")
                    address = URL(URL(address), location).toString()
                    request.disconnect()
                    check(++redirects <= 5 && isAllowedUpdateRedirect(address, current.url, ApiConfiguration.BASE_URL)) { "下载地址不受信任，请打开下载页" }
                    continue
                }
                if (code != 200) throw IOException("下载失败（HTTP $code），请重试或打开下载页")
                val total = request.contentLengthLong.takeIf { it > 0 }
                // Missing Content-Length is legal. Show bytes and an indeterminate total in that case.
                mutableState.value = state.value.copy(total = total)
                request.inputStream.use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (cancelled) throw CancellationException()
                        val count = input.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count); bytes += count
                        mutableState.value = state.value.copy(downloaded = bytes)
                    }
                    if (total != null && bytes != total) throw IOException("下载不完整，请重试或打开下载页")
                } }
                request.disconnect(); connection = null
                break
            }
            currentCoroutineContext().ensureActive()
            if (cancelled) throw CancellationException()
            mutableState.value = state.value.copy(stage = UpdateStage.VERIFYING)
            if (!verifyApkSha256(file, current.sha256)) {
                file.delete(); fail("安装包校验失败，请重试或打开下载页"); return
            }
            currentCoroutineContext().ensureActive()
            if (cancelled) throw CancellationException()
            mutableState.value = state.value.copy(stage = UpdateStage.READY)
        } catch (e: CancellationException) {
            file.delete()
            if (state.value.busy) mutableState.value = state.value.copy(stage = UpdateStage.CANCELED, message = "下载已取消，可重试或打开下载页")
            throw e
        } catch (e: Exception) {
            file.delete()
            if (!cancelled) fail(e.message?.takeIf { e is IllegalStateException || e is IOException } ?: "下载失败，请检查网络后重试或打开下载页")
        } finally { connection?.disconnect(); connection = null }
    }

    fun install(activity: Activity, requestPermission: (Intent) -> Unit, afterPermission: Boolean = false) {
        val release = state.value.release ?: return
        if (state.value.busy) return
        mutableState.value = state.value.copy(stage = UpdateStage.INSTALLING, message = null)
        installScope.launch {
            try {
                // Recheck the file even on return from Settings; never hand an unchecked package to the installer.
                val valid = withContext(Dispatchers.IO) { verifyApkSha256(apk(release), release.sha256) }
                if (!valid) { apk(release).delete(); fail("安装包校验失败，请重试或打开下载页"); return@launch }
                if (activity.isFinishing || activity.isDestroyed) { fail("安装未启动，请重新打开更新或下载页"); return@launch }
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    if (afterPermission) { fail("未允许安装未知应用，可开启权限后重试或打开下载页"); return@launch }
                    mutableState.value = state.value.copy(stage = UpdateStage.NEED_PERMISSION, message = "请允许食记安装未知应用，返回后继续安装")
                    requestPermission(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    return@launch
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk(release))
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                activity.startActivity(intent)
                mutableState.value = state.value.copy(message = "请在系统安装器中确认安装；也可打开下载页")
            } catch (_: Exception) { fail("无法打开安装器或权限设置，请打开下载页") }
        }
    }
}
