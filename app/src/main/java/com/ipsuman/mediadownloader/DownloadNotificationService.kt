package com.ipsuman.mediadownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Foreground service used by user-initiated downloads.
 * It keeps the app process important after the Activity leaves the foreground
 * and publishes progress notifications by reading the existing engine status API.
 */
class DownloadNotificationService : Service() {

    private data class Job(val id: String, val title: String)

    private val jobs = ConcurrentHashMap<String, Job>()
    @Volatile private var polling = false
    private var pollThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            FOREGROUND_ID,
            foregroundNotification("Preparing download…"),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobId = intent?.getStringExtra(EXTRA_JOB_ID)
        val title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Media download"
        if (!jobId.isNullOrBlank()) {
            jobs[jobId] = Job(jobId, title)
            ensurePolling()
            updateForeground("${jobs.size} download${if (jobs.size == 1) "" else "s"} active")
        }
        return START_NOT_STICKY
    }

    private fun ensurePolling() {
        if (polling) return
        polling = true
        pollThread = thread(name = "MediaDownloaderNotificationPoll") {
            try {
                while (polling && jobs.isNotEmpty()) {
                    val snapshot = jobs.values.toList()
                    for (job in snapshot) pollJob(job)
                    Thread.sleep(800)
                }
            } catch (_: InterruptedException) {
                // Service is stopping.
            } finally {
                polling = false
            }
        }
    }

    private fun pollJob(job: Job) {
        try {
            val encoded = URLEncoder.encode(job.id, "UTF-8")
            val connection = (URL("http://127.0.0.1:8765/status/$encoded").openConnection() as HttpURLConnection).apply {
                connectTimeout = 1500
                readTimeout = 2500
                requestMethod = "GET"
                setRequestProperty("Cache-Control", "no-store")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()

            val json = org.json.JSONObject(body)
            val status = json.optString("status", "working")
            val percent = json.optInt("percent", 0).coerceIn(0, 100)
            val speed = json.optString("speed", "").takeIf { it.isNotBlank() && it != "null" }
            val eta = if (json.isNull("eta")) null else json.optLong("eta")
            val text = buildString {
                append(status.replaceFirstChar { it.uppercase() })
                if (speed != null) append(" • ").append(speed)
                if (eta != null) append(" • ETA ").append(eta).append("s")
            }

            when {
                status.equals("completed", true) -> {
                    postJobNotification(job, "Download complete", "✓ ${job.title}", 100, false)
                    jobs.remove(job.id)
                }
                status.equals("cancelled", true) -> {
                    postJobNotification(job, "Download cancelled", job.title, percent, false)
                    jobs.remove(job.id)
                }
                status.startsWith("failed", true) -> {
                    postJobNotification(job, "Download failed", job.title, percent, false)
                    jobs.remove(job.id)
                }
                else -> postJobNotification(job, text, job.title, percent, true)
            }

            if (jobs.isNotEmpty()) {
                updateForeground("${jobs.size} download${if (jobs.size == 1) "" else "s"} active")
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } catch (_: Exception) {
            // The engine may briefly be unavailable during startup/restart. Keep polling.
        }
    }

    private fun postJobNotification(job: Job, text: String, content: String, percent: Int, ongoing: Boolean) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(text)
            .setContentText(content)
            .setContentIntent(contentIntent())
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        manager.notify(notificationId(job.id), notification)
    }

    private fun foregroundNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Media Downloader")
            .setContentText(text)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateForeground(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(FOREGROUND_ID, foregroundNotification(text))
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            9001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notificationId(jobId: String): Int = 1000 + (jobId.hashCode() and 0x7fffffff) % 8000

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media Downloader progress and completion notifications"
                setShowBadge(true)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        polling = false
        pollThread?.interrupt()
        jobs.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        polling = false
        pollThread?.interrupt()
        pollThread = null
        jobs.clear()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_TITLE = "title"
        private const val CHANNEL_ID = "downloads"
        private const val FOREGROUND_ID = 900

        @Volatile
        var isRunning: Boolean = false

        fun start(context: Context, jobId: String, title: String) {
            val intent = Intent(context, DownloadNotificationService::class.java).apply {
                putExtra(EXTRA_JOB_ID, jobId)
                putExtra(EXTRA_TITLE, title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
