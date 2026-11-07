package top.alanvampuke.dininglog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.data.ApiConfiguration

@Singleton
class InAppUpdates internal constructor(
    private val context: Context,
    private val versions: AppVersionGateway,
    private val transport: UpdateTransport,
    private val installPlatform: UpdateInstallPlatform,
    private val installScope: CoroutineScope,
) {
    @Inject constructor(@ApplicationContext context: Context, versions: AppVersionGateway) : this(
        context, versions, HttpUpdateTransport, AndroidUpdateInstallPlatform(context),
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )
    private val mutableState = MutableStateFlow(UpdateInstallState())
    val state = mutableState.asStateFlow()
    private val ownershipLock = Any()
    private var generation = 0L
    private var installJob: Job? = null
    // Retain an outstanding Settings request even if its selection is canceled.
    // Its eventual callback belongs to that generation, never a later release.
    private var permissionGeneration: Long? = null
    private var permissionContinuationGeneration: Long? = null
    @Volatile private var cancelled = false
    @Volatile private var connection: UpdateConnection? = null
    private val directory = File(context.cacheDir, "updates")
    init { sweepUpdateOrphans(directory) }
    private fun apk(release: AppRelease) = File(directory, "app-${release.versionCode}.apk")

    fun open(release: AppRelease) = open(release, automatic = false)

    internal fun open(release: AppRelease, automatic: Boolean) {
        synchronized(ownershipLock) {
            val selected = state.value
            if (selected.busy || selected.release == release || (automatic && selected.release != null)) return
            if (!release.valid || !isNewerVersion(release.versionCode, BuildConfig.VERSION_CODE) ||
                !isAllowedUpdateUrl(release.url, ApiConfiguration.BASE_URL)) return
            invalidateInstallation()
            cancelled = false
            sweepUpdateOrphans(directory, release.versionCode)
            mutableState.value = UpdateInstallState(release)
        }
    }
    fun startDownload() {
        synchronized(ownershipLock) {
            val release = state.value.release ?: return
            if (state.value.busy) return
            invalidateInstallation()
            val owner = generation
            cancelled = false
            mutableState.value = UpdateInstallState(release, UpdateStage.DOWNLOADING)
            try { context.startForegroundService(Intent(context, UpdateDownloadService::class.java)) }
            catch (_: Exception) { if (owns(owner, release)) fail("无法启动下载，请打开下载页") }
        }
    }
    fun markCanceled() {
        synchronized(ownershipLock) {
            invalidateInstallation()
            cancelled = true; connection?.disconnect()
            // Keep retry disabled until the service has closed and deleted the partial
            // file. Otherwise its cleanup can delete a newly retried download.
            mutableState.value = state.value.copy(message = "正在取消下载…", packageVerified = false)
        }
    }
    fun finishCancellation() {
        synchronized(ownershipLock) {
            if (cancelled) mutableState.value = state.value.copy(stage = UpdateStage.CANCELED,
                message = "下载已取消，可重试或打开下载页", packageVerified = false)
        }
    }
    fun cancel() {
        synchronized(ownershipLock) {
            val downloading = state.value.busy
            markCanceled()
            if (!downloading) { finishCancellation(); return }
            try { context.startService(Intent(context, UpdateDownloadService::class.java).setAction(UpdateDownloadService.CANCEL)) }
            catch (_: Exception) { context.stopService(Intent(context, UpdateDownloadService::class.java)) }
        }
    }
    fun fail(message: String) {
        synchronized(ownershipLock) { mutableState.value = state.value.copy(stage = UpdateStage.FAILED, message = message) }
    }

    // Called under ownershipLock; Core's busy remains a download-only UI flag.
    private fun invalidateInstallation() {
        generation++
        permissionContinuationGeneration = null
        installJob?.cancel()
        installJob = null
    }
    private fun owns(owner: Long, release: AppRelease) = generation == owner && !cancelled && state.value.release == release
    private fun ensureOwned(owner: Long, release: AppRelease) {
        synchronized(ownershipLock) { if (!owns(owner, release)) throw CancellationException() }
    }
    private fun updateOwned(owner: Long, release: AppRelease, transform: (UpdateInstallState) -> UpdateInstallState) {
        synchronized(ownershipLock) {
            if (!owns(owner, release)) throw CancellationException()
            mutableState.value = transform(state.value)
        }
    }
    private fun deleteDownloadFiles(owner: Long, release: AppRelease, partial: File, file: File) {
        synchronized(ownershipLock) {
            if (generation == owner || (cancelled && state.value.release == release)) {
                partial.delete(); file.delete()
            }
        }
    }

    suspend fun download() {
        val (owner, release) = synchronized(ownershipLock) {
            if (!state.value.busy || cancelled) return
            generation to (state.value.release ?: return)
        }
        val file = apk(release)
        val partial = File(directory, "${file.name}.part")
        var activeConnection: UpdateConnection? = null
        try {
            // Fetch from the injected /app/version gateway again, never from Intent extras or user input.
            val current = versions.latest()
            check(current == release && current.valid && isNewerVersion(current.versionCode, BuildConfig.VERSION_CODE) &&
                isAllowedUpdateUrl(current.url, ApiConfiguration.BASE_URL)) { "版本信息已更新，请重新检查或打开下载页" }
            currentCoroutineContext().ensureActive()
            ensureOwned(owner, release)
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建下载目录，请打开下载页" }
            check(!file.exists() || file.delete()) { "无法清理旧安装包，请打开下载页" }
            var address = current.url
            var redirects = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                ensureOwned(owner, release)
                val request = transport.open(address)
                activeConnection = request
                synchronized(ownershipLock) {
                    if (!owns(owner, release)) throw CancellationException()
                    connection = request
                }
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
                updateOwned(owner, release) { it.copy(total = total) }
                request.inputStream.use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        ensureOwned(owner, release)
                        val count = input.read(buffer); if (count < 0) break
                        ensureOwned(owner, release)
                        output.write(buffer, 0, count); bytes += count
                        updateOwned(owner, release) { it.copy(downloaded = bytes) }
                    }
                    if (total != null && bytes != total) throw IOException("下载不完整，请重试或打开下载页")
                } }
                request.disconnect(); activeConnection = null
                synchronized(ownershipLock) { if (connection === request) connection = null }
                break
            }
            currentCoroutineContext().ensureActive()
            updateOwned(owner, release) { it.copy(stage = UpdateStage.VERIFYING) }
            promoteVerifiedApk(partial, file, current.sha256)
            currentCoroutineContext().ensureActive()
            updateOwned(owner, release) { it.copy(stage = UpdateStage.READY, packageVerified = true) }
        } catch (e: CancellationException) {
            deleteDownloadFiles(owner, release, partial, file)
            throw e
        } catch (e: Exception) {
            deleteDownloadFiles(owner, release, partial, file)
            synchronized(ownershipLock) {
                if (owns(owner, release)) fail(e.message?.takeIf { e is IllegalStateException || e is IOException } ?: "下载失败，请检查网络后重试或打开下载页")
            }
        } finally {
            activeConnection?.let { request ->
                request.disconnect()
                synchronized(ownershipLock) { if (connection === request) connection = null }
            }
        }
    }

    fun install(activity: Activity, requestPermission: (Intent) -> Unit, afterPermission: Boolean = false) {
        synchronized(ownershipLock) {
            val owner = if (afterPermission) {
                val pending = permissionGeneration ?: return
                if (pending != generation || state.value.stage != UpdateStage.NEED_PERMISSION) {
                    permissionGeneration = null
                    return
                }
                val activeJob = installJob?.takeUnless { it.isCompleted }
                if (activeJob != null) {
                    // A synchronous Settings return can reenter its requesting job.
                    // Retain the token and queue only one continuation after completion.
                    if (permissionContinuationGeneration == pending) return
                    permissionContinuationGeneration = pending
                    activeJob.invokeOnCompletion {
                        synchronized(ownershipLock) {
                            if (permissionContinuationGeneration != pending) return@invokeOnCompletion
                            permissionContinuationGeneration = null
                            if (generation == pending && permissionGeneration == pending) {
                                install(activity, requestPermission, afterPermission = true)
                            }
                        }
                    }
                    return
                }
                permissionGeneration = null
                pending
            } else generation
            val release = state.value.release ?: return
            if (!owns(owner, release) || state.value.busy || !state.value.packageVerified || installJob?.isCompleted == false) return
            if (!afterPermission && permissionGeneration == owner) return
            val file = apk(release)
            // Publish the job before Main.immediate can run it or a callback reenters.
            val job = installScope.launch(start = CoroutineStart.LAZY) {
                try {
                    // Recheck the file even on return from Settings; never hand an unchecked package to the installer.
                    val valid = withContext(Dispatchers.IO) { installPlatform.verify(file, release.sha256) }
                    currentCoroutineContext().ensureActive()
                    synchronized(ownershipLock) {
                        if (!owns(owner, release)) return@launch
                        if (!valid) {
                            file.delete()
                            mutableState.value = state.value.copy(stage = UpdateStage.FAILED, packageVerified = false,
                                message = "安装包校验失败，请重试或打开下载页")
                            return@launch
                        }
                        if (activity.isFinishing || activity.isDestroyed) { fail("安装未启动，请重新打开更新或下载页"); return@launch }
                        val allowed = installPlatform.canRequestPackageInstalls(activity)
                        if (!owns(owner, release)) return@launch
                        if (!allowed) {
                            if (afterPermission) { fail("未允许安装未知应用，可开启权限后重试或打开下载页"); return@launch }
                            mutableState.value = state.value.copy(stage = UpdateStage.NEED_PERMISSION, message = "请允许食记安装未知应用，返回后继续安装")
                            if (!owns(owner, release)) return@launch
                            if (permissionGeneration == null) {
                                permissionGeneration = owner
                                try {
                                    requestPermission(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                                } catch (e: Exception) {
                                    if (permissionGeneration == owner) permissionGeneration = null
                                    throw e
                                }
                                // MainActivity reports a synchronous launch failure through fail().
                                if (owns(owner, release) && state.value.stage == UpdateStage.FAILED && permissionGeneration == owner) permissionGeneration = null
                            }
                            return@launch
                        }
                        if (!owns(owner, release)) return@launch
                        installPlatform.startInstaller(activity, file)
                        if (owns(owner, release)) mutableState.value = state.value.copy(message = "请在系统安装器中确认安装；也可打开下载页")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    synchronized(ownershipLock) { if (owns(owner, release)) fail("无法打开安装器或权限设置，请打开下载页") }
                }
            }
            installJob = job
            mutableState.value = state.value.copy(stage = UpdateStage.INSTALLING, message = null)
            job.start()
        }
    }
}
