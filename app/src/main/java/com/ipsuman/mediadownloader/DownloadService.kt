package com.ipsuman.mediadownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps the local engine alive independently of MainActivity/WebView. */
class DownloadService : Service() {
    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 4100
        @Volatile var instance: DownloadService? = null
    }

    private var engineServer: LocalEngineServer? = null
    private lateinit var logFile: java.io.File

    override fun onCreate() {
        super.onCreate()
        instance = this
        logFile = java.io.File(filesDir, "media-downloader-engine.log")
        log("BACKGROUND: DownloadService.onCreate()")
        createNotificationChannel()
        val notification = buildNotification("Engine starting…", 0, false)
        log("NOTIFICATION: calling startForeground(id=$NOTIFICATION_ID)")
        try {
            startForeground(NOTIFICATION_ID, notification)
            log("NOTIFICATION: startForeground() returned successfully")
        } catch (e: Exception) {
            log("NOTIFICATION: startForeground() FAILED", e)
            throw e
        }
        try {
            engineServer = LocalEngineServer(applicationContext) { jobId, state, percent, title ->
                updateDownloadNotification(jobId, state, percent, title)
            }
            engineServer?.start()
            log("BACKGROUND: LocalEngineServer started; alive=${engineServer?.isAlive}")
        } catch (e: Exception) {
            log("BACKGROUND: LocalEngineServer start FAILED", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("BACKGROUND: onStartCommand(startId=$startId, action=${intent?.action ?: "null"})")
        return START_STICKY
    }

    fun updateDownloadNotification(jobId: String, state: String, percent: Int, title: String?) {
        val manager = getSystemService(NotificationManager::class.java)
        val safePercent = percent.coerceIn(0, 100)
        val text = title?.takeIf { it.isNotBlank() } ?: state
        log("NOTIFICATION: posting job=$jobId state=$state percent=$safePercent text=${text.take(180)}")
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(text, safePercent, state == "completed"))
            log("NOTIFICATION: posted job=$jobId")
        } catch (e: Exception) {
            log("NOTIFICATION: post FAILED for job=$jobId", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Media Downloader background download status"
            setShowBadge(false)
        })
        log("NOTIFICATION: channel created id=$CHANNEL_ID")
    }

    private fun buildNotification(text: String, percent: Int, completed: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 4101, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (completed) "Download complete" else "Media Downloader")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(!completed)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (!completed) builder.setProgress(100, percent, false)
        return builder.build()
    }

    private fun log(message: String, error: Throwable? = null) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        try {
            logFile.appendText("[$time] $message\n")
            if (error != null) logFile.appendText(error.stackTraceToString() + "\n")
        } catch (_: Exception) {}
        android.util.Log.d("MediaDownloader", message, error)
    }

    override fun onDestroy() {
        log("BACKGROUND: DownloadService.onDestroy(); stopping engine server")
        try { engineServer?.stop() } catch (e: Exception) { log("BACKGROUND: server stop failed", e) }
        engineServer = null
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
