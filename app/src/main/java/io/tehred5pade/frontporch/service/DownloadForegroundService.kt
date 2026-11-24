package io.tehred5pade.frontporch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.tehred5pade.frontporch.FrontPorchApp
import io.tehred5pade.frontporch.MainActivity
import io.tehred5pade.frontporch.data.download.ActiveDownloadItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps active downloads alive when the user swipes the app back to the launcher.
 *
 * The actual transfer work still lives in the shared [FrontPorchApp.downloadManager] so the
 * UI and the notification observe the same state. This service only gives that work a
 * process-prioritized owner and keeps the user informed of progress.
 */
class DownloadForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "frontporch_downloads"
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, DownloadForegroundService::class.java)
            )
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundStarted = false

    private val downloadManager
        get() = (application as FrontPorchApp).downloadManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        serviceScope.launch {
            downloadManager.activeDownloads.collect { downloads ->
                if (downloads.isEmpty()) {
                    if (foregroundStarted) stopForegroundAndSelf()
                } else {
                    postProgressNotification(downloads.values.toList())
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val downloads = downloadManager.activeDownloads.value.values.toList()
        if (downloads.isEmpty()) {
            stopForegroundAndSelf()
        } else {
            postProgressNotification(downloads)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do not stop for an active transfer when the app task is removed. The notification
        // remains the owner until the shared download state is empty.
        if (downloadManager.activeDownloads.value.isEmpty()) stopForegroundAndSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Video downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress for video downloads running in the background"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun postProgressNotification(downloads: List<ActiveDownloadItem>) {
        if (downloads.isEmpty()) {
            stopForegroundAndSelf()
            return
        }

        val first = downloads.first()
        val title = if (downloads.size == 1) {
            first.title.ifBlank { "Downloading video" }
        } else {
            "Downloading ${downloads.size} videos"
        }
        val overallProgress = (downloads.map { it.progress }.average() * 100).toInt().coerceIn(0, 100)
        val text = if (downloads.size == 1) {
            first.statusText
        } else {
            "Overall $overallProgress% • ${first.statusText}"
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, overallProgress, false)
            .build()

        if (foregroundStarted) {
            // Re-calling startForeground for every progress tick is an expensive binder
            // round trip to system_server. Once the service is foregrounded, a normal
            // notify updates the same notification far more cheaply.
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        } else {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            foregroundStarted = true
        }
    }

    private fun stopForegroundAndSelf() {
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }
}
