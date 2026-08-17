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

/** Keeps the local engine process protected by a foreground service. */
class DownloadService : Service() {
    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 4100
        @Volatile var instance: DownloadService? = null
    }
    private lateinit var logFile: java.io.File

    override fun onCreate() {
        super.onCreate()
        instance = this
        logFile = java.io.File(filesDir, "media-downloader-engine.log")
        log("BACKGROUND: DownloadService.onCreate()")
        createNotificationChannel()
        val notification = buildNotification("Background engine active", 0, false)
        log("NOTIFICATION: calling startForeground(id=$NOTIFICATION_ID)")
        try {
            startForeground(NOTIFICATION_ID, notification)
            log("NOTIFICATION: startForeground() returned successfully")
        } catch (e: Exception) {
            log("NOTIFICATION: startForeground() FAILED", e)
            throw e
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("BACKGROUND: onStartCommand(startId=$startId, action=${intent?.action ?: "null"})")
        return START_STICKY
    }

    fun postDownloadStatus(jobId: String, text: String, percent: Int = 0, completed: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        log("NOTIFICATION: posting job=$jobId percent=${percent.coerceIn(0,100)} text=${text.take(180)}")
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(text, percent, completed))
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
        val pending = PendingIntent.getActivity(
            this, 4101,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
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
        if (!completed) builder.setProgress(100, percent.coerceIn(0,100), false)
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
        log("BACKGROUND: DownloadService.onDestroy()")
        instance = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
