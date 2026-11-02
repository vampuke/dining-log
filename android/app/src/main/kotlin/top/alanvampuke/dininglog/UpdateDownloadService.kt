package top.alanvampuke.dininglog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import top.alanvampuke.dininglog.core.UpdateInstallState

@AndroidEntryPoint
class UpdateDownloadService : Service() {
    @Inject lateinit var updates: InAppUpdates
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    companion object {
        const val CANCEL = "top.alanvampuke.dininglog.CANCEL_UPDATE"
        private const val CHANNEL = "app-updates"
        private const val NOTIFICATION = 8
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "应用更新", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { work?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        try {
            val notification = notification(updates.state.value)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, notification)
        } catch (_: Exception) { updates.fail("无法启动下载，请打开下载页"); stopSelf(); return START_NOT_STICKY }
        if (work?.isActive != true) work = scope.launch {
            val progress = launch { updates.state.collect {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(it))
            } }
            try { withContext(Dispatchers.IO) { updates.download() } }
            finally { progress.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun notification(state: UpdateInstallState): Notification {
        val cancel = PendingIntent.getService(this, 0, Intent(this, UpdateDownloadService::class.java).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("食记 · 正在下载更新")
            .setContentText("${state.percent?.let { "$it% · " } ?: "进度待确认 · "}${state.downloaded} / ${state.total?.toString() ?: "未知"} 字节")
            .setProgress(100, state.percent ?: 0, state.percent == null).setOnlyAlertOnce(true).setOngoing(true)
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "取消", cancel).build()).build()
    }
    override fun onDestroy() { work?.cancel(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
