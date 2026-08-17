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
    @Volatile private var engineInitializing = false
    @Volatile private var engineError: String? = null
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

    fun warmUpEngine() {
        if (engineReady || engineInitializing) return
        engineInitializing = true
        executor.execute {
            try {
                log("ENGINE WARM-UP START")
                ensureEngine()
                log("ENGINE WARM-UP COMPLETE")
            } catch (e: Exception) {
                engineError = diagnostic(e)
                log("ENGINE WARM-UP FAILED: $engineError", e)
            } finally {
                engineInitializing = false
            }
        }
    }

    @Synchronized
    private fun ensureEngine(): String {
        if (engineReady) return currentVersion()
        engineInitializing = true
        engineError = null
        try {
            log("Initializing Android yt-dlp engine")
            YoutubeDL.getInstance().init(context.applicationContext)
            log("Android yt-dlp engine initialized")
            log("Initializing bundled FFmpeg")
            FFmpeg.getInstance().init(context.applicationContext)
            log("Bundled FFmpeg initialized")

            if (!updateAttempted) {
                updateAttempted = true
                try {
                    log("Checking for latest stable yt-dlp binary")
                    val updateStatus = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)
                    log("yt-dlp stable update result: $updateStatus")
                } catch (e: Exception) {
                    log("yt-dlp update check failed; retaining initialized bundled engine", e)
                }
            }

            engineReady = true
            val version = currentVersion()
            log("ENGINE READY: yt-dlp=$version; FFmpeg=bundled")
            return version
        } catch (e: Exception) {
            engineReady = false
            engineError = diagnostic(e)
            log("ENGINE INITIALIZATION FAILED: $engineError", e)
            throw e
        } finally {
            engineInitializing = false
        }
    }

    private fun currentVersion(): String = try {
        YoutubeDL.getInstance().version(context) ?: "bundled"
    } catch (e: Exception) {
        log("Could not read yt-dlp version", e)
        "bundled"
    }

    private fun engineStatus(): String = JSONObject().apply {
        put("ok", engineReady)
        put("state", when {
            engineReady -> "ready"
            engineInitializing -> "initializing"
            engineError != null -> "error"
            else -> "starting"
        })
        put("ytdlp", JSONObject().apply { put("installed", if (engineReady) currentVersion() else "Initializing…") })
        put("ffmpeg", JSONObject().apply { put("installed", if (engineReady) "Bundled" else "Initializing…") })
        put("error", engineError ?: JSONObject.NULL)
    }.toString()

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

    private fun youtubeCookiesFile(): File? {
        val file = File(context.filesDir, "youtube-cookies.txt")
        return if (file.isFile && file.length() > 0L) file else null
    }

    private fun isYoutubeUrl(url: String): Boolean = try {
        val host = Uri.parse(url).host?.lowercase(Locale.US).orEmpty()
        host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" || host.endsWith(".youtu.be")
    } catch (_: Exception) { false }

    private fun addAuthenticationOptions(request: YoutubeDLRequest, url: String) {
        if (!isYoutubeUrl(url)) return
        val cookies = youtubeCookiesFile()
        if (cookies != null) {
            request.addOption("--cookies", cookies.absolutePath)
            log("YouTube authentication: imported cookies attached to request")
        } else {
            log("YouTube authentication: no cookie file configured; using public/guest access")
        }
    }

    private fun json(status: Response.Status, body: String) = newFixedLengthResponse(status, "application/json; charset=utf-8", body)

    private fun diagnostic(e: Throwable): String {
        val parts = ArrayList<String>(); var x: Throwable? = e; var n = 0
        while (x != null && n++ < 5) { parts += "${x::class.java.simpleName}: ${x.message ?: ""}"; x = x.cause }
        return parts.joinToString(" | ").replace(Regex("\\s+"), " ").take(2000)
    }

    private fun analyzeUrl(session: IHTTPSession): Response = try {
        val files = HashMap<String, String>(); session.parseBody(files)
        val body = files["postData"] ?: "{}"; val url = JSONObject(body).optString("url", "").trim()
        if (url.isEmpty()) return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"URL is required"}""")
        log("Analysis requested: $url")
        ensureEngine()
        val request = YoutubeDLRequest(url).apply { addAuthenticationOptions(this, url) }
        val info: VideoInfo = YoutubeDL.getInstance().getInfo(request)
        val formats = JSONArray()
        for (fmt in info.formats.orEmpty()) formats.put(JSONObject().apply {
            put("format_id", fmt.formatId ?: ""); put("ext", fmt.ext ?: ""); put("format_note", fmt.formatNote ?: "")
            put("height", fmt.height); put("width", fmt.width); put("fps", fmt.fps); put("vcodec", fmt.vcodec ?: "none")
            put("acodec", fmt.acodec ?: "none"); put("abr", fmt.abr); put("tbr", fmt.tbr); put("filesize", fmt.fileSize); put("filesize_approx", fmt.fileSizeApproximate)
        })
        log("URL analysis completed: ${formats.length()} formats")
        json(Response.Status.OK, JSONObject().apply {
            put("ok", true); put("id", info.id ?: ""); put("title", info.title ?: info.fulltitle ?: ""); put("uploader", info.uploader ?: "")
            put("channel", info.uploader ?: ""); put("duration", info.duration); put("thumbnail", info.thumbnail ?: ""); put("webpage_url", info.webpageUrl ?: url)
            put("extractor", info.extractorKey ?: info.extractor ?: ""); put("is_live", false); put("formats", formats)
        }.toString())
    } catch (e: Exception) {
        log("URL analysis FAILED", e); exportLogToDownloads()
        json(Response.Status.INTERNAL_ERROR, JSONObject().apply { put("ok", false); put("error", diagnostic(e)); put("exception", e::class.java.name) }.toString())
    }

    private fun buildRequest(jobId: String, url: String, format: String, start: String, end: String, audioOnly: Boolean, audioFormat: String, audioQuality: String, container: String): YoutubeDLRequest {
        val dir = jobs[jobId] ?: throw IllegalStateException("Unknown download job")
        return YoutubeDLRequest(url).apply {
            addAuthenticationOptions(this, url)
            addOption("-o", File(dir, "%(title)s [%(id)s].%(ext)s").absolutePath)
            addOption("--no-mtime"); addOption("--no-playlist"); addOption("--retries", "3"); addOption("--fragment-retries", "3"); addOption("--socket-timeout", "30"); addOption("--force-ipv4"); addOption("--continue")
            addOption("-f", if (format.isNotEmpty()) format else if (audioOnly) "bestaudio/best" else "bv*+ba/b")
            if (start.isNotEmpty() && end.isNotEmpty()) { addOption("--download-sections", "*$start-$end"); addOption("--force-keyframes-at-cuts") }
            if (container.isNotEmpty() && container != "auto") addOption("--merge-output-format", container)
            if (audioOnly) { addOption("-x"); if (audioFormat.isNotEmpty()) addOption("--audio-format", audioFormat); if (audioQuality.isNotEmpty() && !audioQuality.equals("best", true)) addOption("--audio-quality", audioQuality) }
        }
    }

    private fun extractSpeed(line: String?): String? {
        if (line.isNullOrBlank()) return null
        val match = Regex("(?:at\\s+|\\s)(\\d+(?:\\.\\d+)?\\s*[KMGTP]?i?B/s)", RegexOption.IGNORE_CASE).find(line)
        return match?.groupValues?.getOrNull(1)?.replace(" ", "")
    }

    private fun writeProgress(dir: File, state: String, progress: Double, eta: Long?, line: String?) = writeStatus(dir, JSONObject().apply {
        put("status", state); put("percent", progress.toInt().coerceIn(0, 100)); put("eta", eta ?: JSONObject.NULL); put("speed", extractSpeed(line) ?: JSONObject.NULL); put("message", line ?: "")
    }.toString())

    private fun runJob(jobId: String) {
        val dir = jobs[jobId] ?: return; val request = jobRequests[jobId] ?: return
        try {
            ensureEngine(); if (jobStates[jobId] != "running") return
            writeProgress(dir, "starting", 0.0, null, "Starting download…")
            YoutubeDL.getInstance().execute(request, jobId) { progress, eta, line -> writeProgress(dir, jobStates[jobId] ?: "running", progress.toDouble(), eta, line) }
            when (jobStates[jobId]) {
                "paused" -> { writeStatus(dir, """{"status":"paused","percent":${readPercent(dir)}}""); return }
                "cancelled" -> { writeStatus(dir, """{"status":"cancelled","percent":0}""); cleanupJob(jobId, true); return }
            }
            val source = dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") && it.name != "android_status.json" }.maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException("yt-dlp completed but no output file was found")
            val saved = saveToDownloads(source, source.name)
            writeStatus(dir, JSONObject().apply { put("status", "completed"); put("percent", 100); put("filename", saved.first); put("uri", saved.second); put("size", source.length()) }.toString())
            log("Download $jobId completed: ${saved.first}"); source.delete()
        } catch (e: Exception) {
            if (jobStates[jobId] == "paused") { writeStatus(dir, """{"status":"paused","percent":${readPercent(dir)}}""); return }
            if (jobStates[jobId] == "cancelled") { writeStatus(dir, """{"status":"cancelled","percent":0}""); cleanupJob(jobId, true); return }
            val msg = diagnostic(e); log("Download $jobId FAILED: $msg", e); exportLogToDownloads()
            writeStatus(dir, JSONObject().apply { put("status", "failed"); put("percent", 0); put("error", msg); put("exception", e::class.java.name) }.toString())
        }
    }

    private fun readPercent(dir: File): Int = try { JSONObject(File(dir, "android_status.json").readText()).optInt("percent", 0) } catch (_: Exception) { 0 }

    private fun startDownload(session: IHTTPSession): Response = try {
        val files = HashMap<String, String>(); session.parseBody(files); val req = JSONObject(files["postData"] ?: "{}"); val url = req.optString("url", "").trim()
        if (url.isEmpty()) return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"URL is required"}""")
        ensureEngine()
        val jobId = UUID.randomUUID().toString().replace("-", "").take(12); val dir = File(context.filesDir, "media-downloads/$jobId")
        if (!dir.mkdirs() && !dir.isDirectory) throw IllegalStateException("Could not create download directory")
        val format = req.optString("format", "").trim(); val start = req.optString("start", "").trim(); val end = req.optString("end", "").trim(); val audioOnly = req.optBoolean("audio_only", false)
        val audioFormat = req.optString("audio_format", "").trim().lowercase(Locale.US); val audioQuality = req.optString("audio_quality", "").trim(); val container = req.optString("merge_output_format", "").trim()
        jobs[jobId] = dir; jobStates[jobId] = "running"; jobRequests[jobId] = buildRequest(jobId, url, format, start, end, audioOnly, audioFormat, audioQuality, container)
        writeStatus(dir, """{"status":"starting","percent":0,"speed":null}""); log("Starting download $jobId: format=$format audioOnly=$audioOnly section=$start-$end")
        executor.execute { runJob(jobId) }
        json(Response.Status.OK, JSONObject().apply { put("ok", true); put("job_id", jobId) }.toString())
    } catch (e: Exception) {
        val msg = diagnostic(e); log("Could not start download: $msg", e); exportLogToDownloads()
        json(Response.Status.INTERNAL_ERROR, JSONObject().apply { put("ok", false); put("error", msg); put("exception", e::class.java.name) }.toString())
    }

    private fun controlDownload(id: String, action: String): Response {
        val dir = jobs[id] ?: return json(Response.Status.NOT_FOUND, """{"ok":false,"error":"Unknown job"}""); val current = jobStates[id] ?: "unknown"
        return try {
            when (action) {
                "pause" -> { if (current != "running") return json(Response.Status.CONFLICT, """{"ok":false,"error":"Job is not running"}"); jobStates[id] = "paused"; YoutubeDL.getInstance().destroyProcessById(id); writeStatus(dir, """{"status":"paused","percent":${readPercent(dir)}}") }
                "resume" -> { if (current != "paused") return json(Response.Status.CONFLICT, """{"ok":false,"error":"Job is not paused"}"); jobStates[id] = "running"; writeStatus(dir, """{"status":"resuming","percent":${readPercent(dir)}}"); executor.execute { runJob(id) } }
                "cancel" -> { if (current == "completed" || current == "cancelled") return json(Response.Status.CONFLICT, """{"ok":false,"error":"Job is already finished"}"); jobStates[id] = "cancelled"; YoutubeDL.getInstance().destroyProcessById(id); writeStatus(dir, """{"status":"cancelled","percent":0}") }
            }
            json(Response.Status.OK, """{"ok":true,"status":"${jobStates[id] ?: current}"}")
        } catch (e: Exception) { log("Download control FAILED for $id/$action", e); json(Response.Status.INTERNAL_ERROR, """{"ok":false,"error":"${diagnostic(e).replace("\"", "'")}"}") ) }
    }

    private fun saveToDownloads(source: File, displayName: String): Pair<String, String> {
        val treeUri = preferences.getString("download_tree_uri", null)
        if (!treeUri.isNullOrBlank()) try {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            if (tree != null && tree.canWrite()) {
                val safeName = displayName.ifBlank { source.name }; val target = tree.createFile(guessMime(safeName), safeName)
                if (target != null) { context.contentResolver.openOutputStream(target.uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }; return safeName to target.uri.toString() }
            }
        } catch (e: Exception) { log("Custom download folder failed; falling back to Downloads", e) }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName.ifBlank { source.name }); put(MediaStore.Downloads.MIME_TYPE, guessMime(source.name))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS); put(MediaStore.Downloads.IS_PENDING, 1) }
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Could not create Downloads entry")
        context.contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: throw IllegalStateException("Could not open Downloads output")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); context.contentResolver.update(uri, values, null, null) }
        return displayName.ifBlank { source.name } to uri.toString()
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "mp4" -> "video/mp4"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "opus" -> "audio/ogg"; "wav" -> "audio/wav"; "flac" -> "audio/flac"; else -> "application/octet-stream"
    }

    private fun writeStatus(dir: File, json: String) { try { File(dir, "android_status.json").writeText(json) } catch (e: Exception) { log("Could not write job status", e) } }
    private fun cleanupJob(jobId: String, deleteFiles: Boolean) { val dir = jobs.remove(jobId); jobRequests.remove(jobId); jobStates.remove(jobId); if (deleteFiles) dir?.deleteRecursively() }

    override fun serve(session: IHTTPSession): Response = try {
        if (session.method == Method.OPTIONS) return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
        val path = session.uri.substringBefore('?')
        val response = when {
            path == "/health" -> json(Response.Status.OK, engineStatus())
            path == "/warmup" -> { warmUpEngine(); json(Response.Status.OK, engineStatus()) }
            path == "/logs" -> if (logFile.isFile) newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", logFile.readText()) else newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "No log yet")
            path == "/analyze" && session.method == Method.POST -> analyzeUrl(session)
            path == "/download" && session.method == Method.POST -> startDownload(session)
            path.startsWith("/download/") && path.endsWith("/control") && session.method == Method.POST -> { val id = path.removePrefix("/download/").removeSuffix("/control").trim('/'); val files = HashMap<String, String>(); session.parseBody(files); controlDownload(id, JSONObject(files["postData"] ?: "{}").optString("action", "")) }
            path.startsWith("/status/") -> { val id = path.removePrefix("/status/").trim('/'); val dir = jobs[id]; if (dir == null) json(Response.Status.NOT_FOUND, """{"ok":false,"error":"Unknown job"}") else { val file = File(dir, "android_status.json"); json(Response.Status.OK, if (file.isFile) file.readText() else """{"status":"starting","percent":0}""") } }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        cors(response)
    } catch (e: Exception) {
        log("HTTP request FAILED", e); exportLogToDownloads(); cors(json(Response.Status.INTERNAL_ERROR, JSONObject().apply { put("ok", false); put("error", diagnostic(e)) }.toString()))
    }

    private fun cors(r: Response): Response { r.addHeader("Access-Control-Allow-Origin", "*"); r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS"); r.addHeader("Access-Control-Allow-Headers", "Content-Type"); return r }
}
