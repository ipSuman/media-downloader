package com.ipsuman.mediadownloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class LocalEngineServer(private val context: Context) : NanoHTTPD(8765) {
    private val logFile = File(context.filesDir, "media-downloader-engine.log")
    private val executor = Executors.newCachedThreadPool()
    private val jobs = ConcurrentHashMap<String, File>()
    private val jobRequests = ConcurrentHashMap<String, YoutubeDLRequest>()
    private val jobStates = ConcurrentHashMap<String, String>()
    private val preferences = context.getSharedPreferences("media_downloader", Context.MODE_PRIVATE)
    @Volatile private var engineReady = false
    @Volatile private var updateAttempted = false

    private fun log(message: String, error: Throwable? = null) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val text = buildString {
            append("[").append(time).append("] ").append(message).append("\n")
            if (error != null) append(error.stackTraceToString()).append("\n")
        }
        try { logFile.appendText(text) } catch (_: Exception) {}
        android.util.Log.e("MediaDownloader", message, error)
    }

    @Synchronized
    private fun ensureEngine(): String {
        if (!engineReady) {
            log("Initializing Android yt-dlp engine")
            YoutubeDL.getInstance().init(context.applicationContext)
            log("Initializing bundled FFmpeg")
            FFmpeg.getInstance().init(context.applicationContext)
            engineReady = true
        }
        if (!updateAttempted) {
            updateAttempted = true
            try {
                log("Checking for latest stable yt-dlp binary")
                val result = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)
                log("yt-dlp update result: $result")
            } catch (e: Exception) { log("yt-dlp update check failed; keeping bundled binary", e) }
        }
        val version = try { YoutubeDL.getInstance().version(context) ?: "bundled" } catch (_: Exception) { "bundled" }
        log("Android yt-dlp engine ready: $version")
        return version
    }

    private fun exportLogToDownloads() {
        try {
            if (!logFile.exists() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "media-downloader-engine.log")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            context.contentResolver.openOutputStream(uri)?.use { out -> logFile.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) { log("Could not export diagnostic log", e) }
    }

    private fun cors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return r
    }

    private fun json(status: Response.Status, body: String) =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)

    private fun versions(): String = try {
        val v = ensureEngine()
        JSONObject().apply {
            put("ytdlp", JSONObject().apply { put("installed", v); put("latest", v) })
            put("ffmpeg", JSONObject().apply { put("installed", "Bundled"); put("latest", "Bundled") })
        }.toString()
    } catch (e: Exception) {
        log("Engine check FAILED", e); exportLogToDownloads()
        JSONObject().apply {
            put("ytdlp", JSONObject().apply { put("installed", "Error") })
            put("ffmpeg", JSONObject().apply { put("installed", "Error") })
            put("error", e.message ?: "Engine error")
        }.toString()
    }

    private fun analyzeUrl(session: IHTTPSession): Response = try {
        val files = HashMap<String, String>(); session.parseBody(files)
        val url = JSONObject(files["postData"] ?: "{}").optString("url", "").trim()
        if (url.isEmpty()) return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"URL is required"}""")
        log("Analyzing URL with Android yt-dlp: $url")
        ensureEngine()
        val info: VideoInfo = YoutubeDL.getInstance().getInfo(url)
        val formats = JSONArray()
        for (fmt in info.formats.orEmpty()) {
            formats.put(JSONObject().apply {
                put("format_id", fmt.formatId ?: "")
                put("ext", fmt.ext ?: "")
                put("format_note", fmt.formatNote ?: "")
                put("height", fmt.height)
                put("width", fmt.width)
                put("fps", fmt.fps)
                put("vcodec", fmt.vcodec ?: "none")
                put("acodec", fmt.acodec ?: "none")
                put("abr", fmt.abr)
                put("vbr", JSONObject.NULL)
                put("tbr", fmt.tbr)
                put("filesize", fmt.fileSize)
                put("filesize_approx", fmt.fileSizeApproximate)
                put("protocol", JSONObject.NULL)
            })
        }
        val result = JSONObject().apply {
            put("ok", true); put("id", info.id ?: "")
            put("title", info.title ?: info.fulltitle ?: "")
            put("uploader", info.uploader ?: ""); put("channel", info.uploader ?: "")
            put("duration", info.duration); put("thumbnail", info.thumbnail ?: "")
            put("webpage_url", info.webpageUrl ?: url)
            put("extractor", info.extractorKey ?: info.extractor ?: "")
            put("is_live", false); put("formats", formats)
        }.toString()
        log("URL analysis completed: ${formats.length()} formats")
        json(Response.Status.OK, result)
    } catch (e: Exception) {
        log("URL analysis FAILED", e); exportLogToDownloads()
        json(Response.Status.INTERNAL_ERROR, JSONObject().apply {
            put("ok", false); put("error", diagnostic(e)); put("exception", e::class.java.name)
        }.toString())
    }

    private fun diagnostic(e: Throwable): String {
        val parts = ArrayList<String>(); var x: Throwable? = e; var n = 0
        while (x != null && n++ < 4) {
            parts += "${x::class.java.simpleName}: ${x.message ?: ""}"; x = x.cause
        }
        return parts.joinToString(" | ").replace(Regex("\\s+"), " ").take(1500)
    }

    private fun buildRequest(jobId: String, url: String, format: String, start: String, end: String,
                             audioOnly: Boolean, audioFormat: String, audioQuality: String,
                             container: String): YoutubeDLRequest {
        val dir = jobs[jobId] ?: throw IllegalStateException("Unknown download job")
        return YoutubeDLRequest(url).apply {
            addOption("-o", File(dir, "%(title)s [%(id)s].%(ext)s").absolutePath)
            addOption("--no-mtime"); addOption("--no-playlist")
            addOption("--retries", "3"); addOption("--fragment-retries", "3")
            addOption("--socket-timeout", "30"); addOption("--force-ipv4"); addOption("--continue")
            addOption("-f", if (format.isNotEmpty()) format else if (audioOnly) "bestaudio/best" else "bv*+ba/b")
            if (start.isNotEmpty() && end.isNotEmpty()) {
                addOption("--download-sections", "*$start-$end")
                // Exact section cuts require re-encoding; without this yt-dlp may
                // begin at an earlier keyframe and produce a longer/non-playable tail.
                addOption("--force-keyframes-at-cuts")
            }
            if (container.isNotEmpty() && container != "auto") addOption("--merge-output-format", container)
            if (audioOnly) {
                addOption("-x")
                if (audioFormat.isNotEmpty()) addOption("--audio-format", audioFormat)
                if (audioQuality.isNotEmpty() && !audioQuality.equals("best", true)) addOption("--audio-quality", audioQuality)
            }
        }
    }

    private fun extractSpeed(line: String?): String? {
        if (line.isNullOrBlank()) return null
        val match = Regex("(?:at\\s+|\\s)(\\d+(?:\\.\\d+)?\\s*[KMGTP]?i?B/s)", RegexOption.IGNORE_CASE).find(line)
        return match?.groupValues?.getOrNull(1)?.replace(" ", "")
    }

    private fun writeProgress(dir: File, state: String, progress: Double, eta: Long?, line: String?) {
        val speed = extractSpeed(line)
        writeStatus(dir, JSONObject().apply {
            put("status", state); put("percent", progress.toInt().coerceIn(0, 100))
            put("eta", eta ?: JSONObject.NULL); put("speed", speed ?: JSONObject.NULL)
            put("message", line ?: "")
        }.toString())
    }

    private fun runJob(jobId: String) {
        val dir = jobs[jobId] ?: return
        val request = jobRequests[jobId] ?: return
        try {
            ensureEngine(); jobStates[jobId] = "running"
            writeProgress(dir, "starting", 0.0, null, "Starting download…")
            YoutubeDL.getInstance().execute(request, jobId) { progress, eta, line ->
                val state = jobStates[jobId] ?: "running"
                writeProgress(dir, state, progress.toDouble(), eta, line)
            }
            when (jobStates[jobId]) {
                "paused" -> { writeStatus(dir, """{"status":"paused","percent":${readPercent(dir)}}"""); return }
                "cancelled" -> { writeStatus(dir, """{"status":"cancelled","percent":0}"""); cleanupJob(jobId, true); return }
            }
            val source = dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") && it.name != "android_status.json" }
                .maxByOrNull { it.lastModified() } ?: throw IllegalStateException("yt-dlp completed but no output file was found")
            val saved = saveToDownloads(source, source.name)
            writeStatus(dir, JSONObject().apply {
                put("status", "completed"); put("percent", 100); put("filename", saved.first)
                put("uri", saved.second); put("size", source.length()); put("speed", JSONObject.NULL)
            }.toString())
            log("Download $jobId completed: ${saved.first}"); source.delete()
        } catch (e: Exception) {
            val state = jobStates[jobId]
            if (state == "paused") { writeStatus(dir, """{"status":"paused","percent":${readPercent(dir)}}"""); log("Download $jobId paused"); return }
            if (state == "cancelled") { writeStatus(dir, """{"status":"cancelled","percent":0}"""); log("Download $jobId cancelled"); cleanupJob(jobId, true); return }
            val msg = diagnostic(e); log("Download $jobId FAILED: $msg", e); exportLogToDownloads()
            writeStatus(dir, JSONObject().apply { put("status", "failed: $msg"); put("percent", 0); put("error", msg); put("exception", e::class.java.name) }.toString())
        }
    }

    private fun readPercent(dir: File): Int = try {
        JSONObject(File(dir, "android_status.json").readText()).optInt("percent", 0)
    } catch (_: Exception) { 0 }

    private fun startDownload(session: IHTTPSession): Response = try {
        val files = HashMap<String, String>(); session.parseBody(files)
        val req = JSONObject(files["postData"] ?: "{}")
        val url = req.optString("url", "").trim()
        if (url.isEmpty()) return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"URL is required"}""")
        val jobId = UUID.randomUUID().toString().replace("-", "").take(12)
        val dir = File(context.cacheDir, "media-downloads/$jobId")
        if (!dir.mkdirs() && !dir.isDirectory) throw IllegalStateException("Could not create download directory")
        val format = req.optString("format", "").trim()
        val start = req.optString("start", "").trim(); val end = req.optString("end", "").trim()
        val audioOnly = req.optBoolean("audio_only", false)
        val audioFormat = req.optString("audio_format", "").trim().lowercase(Locale.US)
        val audioQuality = req.optString("audio_quality", "").trim()
        val container = req.optString("merge_output_format", "").trim()
        jobs[jobId] = dir; jobStates[jobId] = "running"
        jobRequests[jobId] = buildRequest(jobId, url, format, start, end, audioOnly, audioFormat, audioQuality, container)
        writeStatus(dir, """{"status":"starting","percent":0,"speed":null}""")
        log("Starting download $jobId: format=$format audioOnly=$audioOnly section=$start-$end")
        executor.execute { runJob(jobId) }
        json(Response.Status.OK, JSONObject().apply { put("ok", true); put("job_id", jobId) }.toString())
    } catch (e: Exception) {
        val msg = diagnostic(e); log("Could not start download: $msg", e); exportLogToDownloads()
        json(Response.Status.INTERNAL_ERROR, JSONObject().apply { put("ok", false); put("error", msg); put("exception", e::class.java.name) }.toString())
    }

    private fun controlDownload(id: String, action: String): Response {
        val dir = jobs[id] ?: return json(Response.Status.NOT_FOUND, """{"ok":false,"error":"Unknown job"}""")
        val current = jobStates[id] ?: "unknown"
        return try {
            when (action) {
                "pause" -> {
                    if (current != "running") return json(Response.Status.CONFLICT, """{"ok":false,"error":"Job is not running"}""")
                    jobStates[id] = "paused"; YoutubeDL.getInstance().destroyProcessById(id)
                    writeStatus(dir, """{"status":"paused","percent":${readPercent(dir)}}"""); log("Pause requested for download $id")
                }
                "resume" -> {
                    if (current != "paused") return json(Response.Status.CONFLICT, """{"ok":false,"error":"Job is not paused"}""")
                    jobStates[id] = "running"; writeStatus(dir, """{"status":"resuming","percent":${readPercent(dir)}}""")
                    executor.execute { runJob(id) }; log("Resume requested for download $id")
                }
                "cancel" -> {
                    if (current == "completed" || current == "cancelled") return json(Response.Status.CONFLICT, """{"ok":false,"error":"Job is already finished"}""")
                    jobStates[id] = "cancelled"; try { YoutubeDL.getInstance().destroyProcessById(id) } catch (_: Exception) {}
                    writeStatus(dir, """{"status":"cancelled","percent":0}"""); executor.execute { cleanupJob(id, true) }
                    log("Terminate requested for download $id")
                }
            }
            json(Response.Status.OK, JSONObject().apply { put("ok", true); put("status", jobStates[id] ?: action) }.toString())
        } catch (e: Exception) {
            val msg = diagnostic(e); log("Download control failed for $id ($action): $msg", e)
            json(Response.Status.INTERNAL_ERROR, JSONObject().apply { put("ok", false); put("error", msg) }.toString())
        }
    }

    private fun cleanupJob(id: String, deleteFiles: Boolean) {
        if (deleteFiles) try { jobs[id]?.deleteRecursively() } catch (_: Exception) {}
        jobRequests.remove(id); if (deleteFiles) jobs.remove(id)
    }

    private fun writeStatus(dir: File, text: String) {
        try { File(dir, "android_status.json").writeText(text) } catch (e: Exception) { log("Could not write job status", e) }
    }

    private fun saveToDownloads(source: File, requested: String): Pair<String, String> {
        val name = requested.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val selectedUri = preferences.getString("download_tree_uri", null)
        if (!selectedUri.isNullOrBlank()) {
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(selectedUri)) ?: throw IllegalStateException("Selected folder is no longer available")
                if (!tree.canWrite()) throw IllegalStateException("Selected folder is not writable")
                tree.findFile(name)?.delete()
                val target = tree.createFile(mimeType(name), name) ?: throw IllegalStateException("Could not create file in selected folder")
                context.contentResolver.openOutputStream(target.uri)?.use { out -> FileInputStream(source).use { input -> input.copyTo(out) } }
                    ?: throw IllegalStateException("Could not open selected folder output")
                return Pair(name, target.uri.toString())
            } catch (e: Exception) { log("Selected folder save failed; falling back to Downloads", e) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mimeType(name))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS); put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Could not create Downloads entry")
            try {
                context.contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(source).use { it.copyTo(out) } } ?: throw IllegalStateException("Could not open Downloads output")
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); context.contentResolver.update(uri, values, null, null)
                return Pair(name, uri.toString())
            } catch (e: Exception) { context.contentResolver.delete(uri, null, null); throw e }
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists()) downloads.mkdirs()
        val target = File(downloads, name); FileInputStream(source).use { input -> FileOutputStream(target).use { input.copyTo(it) } }
        return Pair(target.name, target.toURI().toString())
    }

    private fun mimeType(name: String) = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "mp4", "m4v" -> "video/mp4"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "opus" -> "audio/opus"; "ogg" -> "audio/ogg"; "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }

    private fun downloadStatus(id: String): Response {
        val dir = jobs[id] ?: return json(Response.Status.NOT_FOUND, """{"ok":false,"error":"Unknown job"}""")
        return try {
            json(Response.Status.OK, if (File(dir, "android_status.json").exists()) File(dir, "android_status.json").readText() else """{"status":"starting","percent":0,"speed":null}""")
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, JSONObject().apply { put("status", "failed: ${diagnostic(e)}"); put("error", diagnostic(e)) }.toString())
        }
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
        val response = when {
            session.method == Method.GET && session.uri == "/api/status" -> json(Response.Status.OK, """{"ok":true,"engine":"media-downloader","platform":"android","version":"0.2.0"}""")
            session.method == Method.GET && session.uri == "/api/versions" -> json(Response.Status.OK, versions())
            session.method == Method.POST && session.uri == "/api/analyze" -> analyzeUrl(session)
            session.method == Method.POST && session.uri == "/api/download" -> startDownload(session)
            session.method == Method.POST && session.uri.matches(Regex("/api/download/[^/]+/(pause|resume|cancel)")) -> {
                val parts = session.uri.split('/'); controlDownload(parts[3], parts[4])
            }
            session.method == Method.GET && session.uri.startsWith("/api/download/") -> downloadStatus(session.uri.substringAfterLast('/'))
            session.method == Method.GET && session.uri == "/api/log" -> newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", if (logFile.exists()) logFile.readText() else "No diagnostic log yet.")
            else -> json(Response.Status.NOT_FOUND, """{"ok":false,"error":"Endpoint not found"}""")
        }
        return cors(response)
    }

    override fun stop() {
        jobStates.keys.toList().forEach { id -> try { YoutubeDL.getInstance().destroyProcessById(id) } catch (_: Exception) {} }
        executor.shutdownNow(); super.stop()
    }
}
