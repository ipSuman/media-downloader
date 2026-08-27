package com.ipsuman.mediadownloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
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
    private val jobDestinations = ConcurrentHashMap<String, String>()
    private val jobMeta = ConcurrentHashMap<String, JobMeta>()
    private val poTokenProvider = YoutubePoTokenProvider(context)
    @Volatile private var engineReady = false
    @Volatile private var updateAttempted = false

    private data class JobMeta(
        val url: String, val format: String, val start: String, val end: String,
        val audioOnly: Boolean, val audioFormat: String, val audioQuality: String, val container: String
    )

    private fun log(message: String, error: Throwable? = null) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val text = buildString { append("[").append(time).append("] ").append(message).append("\n"); if (error != null) append(error.stackTraceToString()).append("\n") }
        try { logFile.appendText(text) } catch (_: Exception) {}
        android.util.Log.e("MediaDownloader", message, error)
    }

    @Synchronized private fun ensureEngine(): String {
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

    private fun youtubeCookiesFile(): File? {
        val file = File(context.filesDir, "youtube-cookies.txt")
        return if (file.isFile && file.length() > 0L) file else null
    }

    private fun addCookies(request: YoutubeDLRequest): Boolean {
        val cookies = youtubeCookiesFile() ?: return false
        request.addOption("--cookies", cookies.absolutePath)
        return true
    }

    private fun addCookieOnlyOptions(request: YoutubeDLRequest) {
        if (!addCookies(request)) throw IllegalStateException("Cookie fallback requested but no imported YouTube cookies are available")
        log("Using imported YouTube cookies for final cookie-only fallback")
    }

    @Synchronized private fun addYoutubePoToken(request: YoutubeDLRequest) {
        try {
            val token = poTokenProvider.getMwebGvsToken()
            val visitorData = poTokenProvider.visitorData()
            if (!visitorData.isNullOrBlank()) request.addOption("--extractor-args", "youtube:visitor_data=$visitorData")
            if (!token.isNullOrBlank()) {
                request.addOption("--extractor-args", "youtube:player-client=mweb;visitor_data=${visitorData.orEmpty()};po_token=mweb.gvs+$token")
                request.addOption("--extractor-args", "youtube:pot_trace=true")
                log("Generated and attached paired mweb GVS PO Token + visitorData")
            } else {
                log("PO Token unavailable: ${poTokenProvider.lastError() ?: "unknown"}")
            }
        } catch (e: Exception) { log("PO Token generation failed", e) }
    }

    private fun addAuthenticationOptions(request: YoutubeDLRequest) {
        if (addCookies(request)) log("Using imported YouTube cookies for yt-dlp authentication")
        addYoutubePoToken(request)
    }

    private fun cors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return r
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)

    private fun versions(): String {
        return try {
            val installed = ensureEngine()
            JSONObject().apply {
                put("ytdlp", JSONObject().apply { put("installed", installed); put("latest", installed) })
                put("ffmpeg", JSONObject().apply { put("installed", "Bundled"); put("latest", "Bundled") })
            }.toString()
        } catch (e: Exception) {
            log("Engine check FAILED", e)
            exportLogToDownloads()
            JSONObject().apply {
                put("ytdlp", JSONObject().apply { put("installed", "Error") })
                put("ffmpeg", JSONObject().apply { put("installed", "Error") })
                put("error", e.message ?: "Engine error")
            }.toString()
        }
    }

    private fun isYoutubeUrl(url: String): Boolean {
        return try {
            val host = Uri.parse(url).host?.lowercase(Locale.US).orEmpty()
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" || host.endsWith(".youtu.be")
        } catch (_: Exception) { false }
    }

    private fun analyzeUrl(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val url = JSONObject(files["postData"] ?: "{}").optString("url", "").trim()
            if (url.isEmpty()) return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"URL is required"}""")
            log("Analyzing URL with Android yt-dlp: $url")
            ensureEngine()
            val request = YoutubeDLRequest(url)
            if (isYoutubeUrl(url)) {
                addAuthenticationOptions(request)
                log("YouTube analysis request prepared with cookies/visitorData/PO token authentication")
            }
            val info: VideoInfo = YoutubeDL.getInstance().getInfo(request)
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
            log("URL analysis completed: ${formats.length()} formats")
            json(Response.Status.OK, JSONObject().apply {
                put("ok", true); put("id", info.id ?: ""); put("title", info.title ?: info.fulltitle ?: "")
                put("uploader", info.uploader ?: ""); put("channel", info.uploader ?: ""); put("duration", info.duration)
                put("thumbnail", info.thumbnail ?: ""); put("webpage_url", info.webpageUrl ?: url)
                put("extractor", info.extractorKey ?: info.extractor ?: ""); put("is_live", false); put("formats", formats)
            }.toString())
        } catch (e: Exception) {
            log("URL analysis FAILED", e); exportLogToDownloads()
            json(Response.Status.INTERNAL_ERROR, JSONObject().apply { put("ok", false); put("error", diagnostic(e)); put("exception", e::class.java.name) }.toString())
        }
    }

    private fun diagnostic(e: Throwable): String {
        val parts = ArrayList<String>(); var x: Throwable? = e; var n = 0
        while (x != null && n++ < 4) { parts += "${x::class.java.simpleName}: ${x.message ?: ""}"; x = x.cause }
        return parts.joinToString(" | ").replace(Regex("\\s+"), " ").take(1500)
    }

    private fun chooseBestAudioId(url: String): String? {
        return try {
            val req = YoutubeDLRequest(url)
            addAuthenticationOptions(req)
            val info = YoutubeDL.getInstance().getInfo(req)
            val audioOnly = info.formats.orEmpty().filter {
                (it.vcodec ?: "none").equals("none", true) &&
                    !(it.acodec ?: "none").equals("none", true) && !it.formatId.isNullOrBlank()
            }
            val selected = audioOnly.maxByOrNull { (it.abr ?: 0.0).toDouble() }
                ?: info.formats.orEmpty().filter {
                    !(it.acodec ?: "none").equals("none", true) && !it.formatId.isNullOrBlank()
                }.maxByOrNull { (it.abr ?: 0.0).toDouble() }
            selected?.formatId
        } catch (e: Exception) {
            log("Could not resolve best audio format", e); null
        }
    }

    private fun normalizeYoutubeFormat(url: String, format: String, audioOnly: Boolean): String {
        if (!isYoutubeUrl(url) || audioOnly || format.isBlank()) return format
        if (format.contains("+") || format.contains("/") || format.contains("[") || format.contains("*") || format.contains("(") || format.contains(" ")) return format
        val audioId = chooseBestAudioId(url) ?: return format
        val paired = "$format+$audioId"
        log("Resolved selected video format $format with best audio format $audioId -> $paired")
        return paired
    }

    private fun buildRequest(jobId: String, meta: JobMeta, authMode: String): YoutubeDLRequest {
        val dir = jobs[jobId] ?: throw IllegalStateException("Unknown download job")
        return YoutubeDLRequest(meta.url).apply {
            if (authMode == "cookie") addCookieOnlyOptions(this) else addAuthenticationOptions(this)
            addOption("-o", File(dir, "%(title)s [%(id)s].%(ext)s").absolutePath)
            addOption("--no-mtime"); addOption("--no-playlist"); addOption("--retries", "3")
            addOption("--fragment-retries", "3"); addOption("--socket-timeout", "30"); addOption("--force-ipv4"); addOption("--continue")
            addOption("-f", if (meta.format.isNotEmpty()) meta.format else if (meta.audioOnly) "bestaudio/best" else "bv*+ba/b")
            if (meta.start.isNotEmpty() && meta.end.isNotEmpty()) {
                addOption("--download-sections", "*${meta.start}-${meta.end}"); addOption("--force-keyframes-at-cuts")
            }
            if (meta.container.isNotEmpty() && meta.container != "auto") addOption("--merge-output-format", meta.container)
            if (meta.audioOnly) {
                addOption("-x")
                if (meta.audioFormat.isNotEmpty()) addOption("--audio-format", meta.audioFormat)
                if (meta.audioQuality.isNotEmpty() && !meta.audioQuality.equals("best", true)) addOption("--audio-quality", meta.audioQuality)
            }
        }
    }

    private fun isAuthFailure(e: Exception): Boolean {
        val s = diagnostic(e).lowercase(Locale.US)
        return s.contains("403") || s.contains("forbidden") || s.contains("sign in to confirm") ||
            s.contains("not a bot") || s.contains("po token") || s.contains("visitor data") || s.contains("429")
    }

    private fun extractSpeed(line: String?): String? {
        if (line.isNullOrBlank()) return null
        val m = Regex("(?:at\\s+|\\s)(\\d+(?:\\.\\d+)?\\s*[KMGTP]?i?B/s)", RegexOption.IGNORE_CASE).find(line)
        return m?.groupValues?.getOrNull(1)?.replace(" ", "")
    }

    private fun writeStatus(dir: File, json: String) {
        try { File(dir, "android_status.json").writeText(json) } catch (e: Exception) { log("Could not write job status", e) }
    }

    private fun readPercent(dir: File): Int = try { JSONObject(File(dir, "android_status.json").readText()).optInt("percent", 0) } catch (_: Exception) { 0 }

    private fun writeProgress(dir: File, state: String, progress: Double, eta: Long?, line: String?) {
        writeStatus(dir, JSONObject().apply {
            put("status", state); put("percent", if (state == "completed") 100 else progress.toInt().coerceIn(0, 99))
            put("eta", eta ?: JSONObject.NULL); put("speed", extractSpeed(line) ?: JSONObject.NULL); put("message", line ?: "")
        }.toString())
    }

    private fun runJob(jobId: String) {
        val dir = jobs[jobId] ?: return
        val meta = jobMeta[jobId] ?: return
        var lastError: Exception? = null
        try {
            if (jobStates[jobId] != "running") return
            ensureEngine(); writeProgress(dir, "starting", 0.0, null, "Starting download…")
            try {
                YoutubeDL.getInstance().execute(jobRequests[jobId] ?: buildRequest(jobId, meta, "auth"), jobId) { p, eta, line ->
                    writeProgress(dir, jobStates[jobId] ?: "running", p.toDouble(), eta, line)
                }
            } catch (firstError: Exception) {
                lastError = firstError
                if (!isYoutubeUrl(meta.url) || !isAuthFailure(firstError)) throw firstError
                var success = false
                for (attempt in 1..3) {
                    if (jobStates[jobId] == "paused" || jobStates[jobId] == "cancelled") return
                    log("PO-token recovery attempt $attempt/3 for job=$jobId", lastError)
                    jobStates[jobId] = "retrying"
                    writeProgress(dir, "retrying", readPercent(dir).toDouble(), null, "Refreshing YouTube authentication ($attempt/3)…")
                    val fresh = poTokenProvider.refreshToken(30)
                    if (fresh == null) { log("PO-token refresh $attempt/3 failed: ${poTokenProvider.lastError()}"); continue }
                    val retry = buildRequest(jobId, meta, "auth")
                    try {
                        YoutubeDL.getInstance().execute(retry, jobId) { p, eta, line -> writeProgress(dir, "retrying", p.toDouble(), eta, line) }
                        success = true; break
                    } catch (retryError: Exception) { lastError = retryError; log("PO-token recovery attempt $attempt/3 failed", retryError) }
                }
                if (!success) {
                    if (youtubeCookiesFile() == null) throw (lastError ?: IllegalStateException("Download failed after 3 PO-token retries"))
                    log("All 3 PO-token recovery attempts failed; starting cookie-only fallback for job=$jobId")
                    jobStates[jobId] = "retrying"
                    writeProgress(dir, "retrying", readPercent(dir).toDouble(), null, "Retrying with cookies only…")
                    val cookie = buildRequest(jobId, meta, "cookie")
                    YoutubeDL.getInstance().execute(cookie, jobId) { p, eta, line -> writeProgress(dir, "retrying", p.toDouble(), eta, line) }
                }
            }
            when (jobStates[jobId]) {
                "paused" -> { writeStatus(dir, """{"status":"paused","percent":${readPercent(dir)}}"""); return }
                "cancelled" -> { cleanupJob(jobId, true); return }
            }
            val source = dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") && it.name != "android_status.json" }.maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException("yt-dlp completed but no output file was found")
            val saved = saveToDestination(jobId, source, source.name)
            writeStatus(dir, JSONObject().apply { put("status", "completed"); put("percent", 100); put("filename", saved.first); put("uri", saved.second); put("size", source.length()) }.toString())
            log("Download $jobId completed: ${saved.first}")
            source.delete()
        } catch (e: Exception) {
            val state = jobStates[jobId]
            if (state == "paused" || state == "cancelled") return
            val msg = diagnostic(e); log("Download $jobId FAILED: $msg", e); exportLogToDownloads()
            writeStatus(dir, JSONObject().apply { put("status", "failed: $msg"); put("percent", 0); put("error", msg); put("exception", e::class.java.name) }.toString())
        }
    }

    private fun startDownload(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>(); session.parseBody(files)
            val req = JSONObject(files["postData"] ?: "{}"); val url = req.optString("url", "").trim()
            if (url.isEmpty()) return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"URL is required"}""")
            val jobId = UUID.randomUUID().toString().replace("-", "").take(12)
            val dir = File(context.filesDir, "media-downloads/$jobId")
            if (!dir.mkdirs() && !dir.isDirectory) throw IllegalStateException("Could not create download directory")
            val requestedFormat = req.optString("format", "").trim()
            val start = req.optString("start", "").trim(); val end = req.optString("end", "").trim()
            val audioOnly = req.optBoolean("audio_only", false); val audioFormat = req.optString("audio_format", "").trim().lowercase(Locale.US)
            val audioQuality = req.optString("audio_quality", "").trim(); val container = req.optString("merge_output_format", "").trim()
            jobs[jobId] = dir; jobStates[jobId] = "running"
            val format = normalizeYoutubeFormat(url, requestedFormat, audioOnly)
            val destination = req.optString("destination_uri", "").trim().ifEmpty {
                context.getSharedPreferences("media_downloader", Context.MODE_PRIVATE).getString("download_tree_uri", "") ?: ""
            }
            if (destination.isNotEmpty()) { jobDestinations[jobId] = destination; log("Download $jobId selected SAF destination: $destination") }
            else log("Download $jobId has no selected SAF destination; using Downloads fallback")
            jobMeta[jobId] = JobMeta(url, format, start, end, audioOnly, audioFormat, audioQuality, container)
            val youtube = isYoutubeUrl(url)
            jobRequests[jobId] = buildRequest(jobId, jobMeta[jobId]!!, if (youtube) "auth" else "cookie")
            writeStatus(dir, """{"status":"starting","percent":0,"speed":null}""")
            log("Starting download $jobId: format=$format audioOnly=$audioOnly section=$start-$end")
            executor.execute { runJob(jobId) }
            json(Response.Status.OK, JSONObject().apply { put("ok", true); put("job_id", jobId) }.toString())
        } catch (e: Exception) {
            val msg = diagnostic(e); log("Could not start download: $msg", e); exportLogToDownloads()
            json(Response.Status.INTERNAL_ERROR, JSONObject().apply { put("ok", false); put("error", msg); put("exception", e::class.java.name) }.toString())
        }
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "m4a" -> "audio/mp4"; "opus" -> "audio/ogg"; "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; else -> "application/octet-stream"
    }

    private fun saveToDestination(jobId: String, source: File, name: String): Pair<String, String> {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_"); val resolver = context.contentResolver; val treeString = jobDestinations[jobId]
        if (treeString.isNullOrBlank()) {
            val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, safe); put(MediaStore.Downloads.MIME_TYPE, mimeFor(safe)); put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS); put(MediaStore.Downloads.IS_PENDING, 1) }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Could not create Downloads entry")
            try {
                resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: throw IllegalStateException("Could not open Downloads output")
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); resolver.update(uri, values, null, null)
                return safe to uri.toString()
            } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
        }
        val tree = Uri.parse(treeString); val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)); var candidate = safe
        for (index in 1..99) {
            val uri = DocumentsContract.createDocument(resolver, parent, mimeFor(candidate), candidate)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: throw IllegalStateException("Could not open selected-folder output")
                    log("Saved download $jobId to selected SAF folder: $uri"); return candidate to uri.toString()
                } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
            }
            val base = safe.substringBeforeLast('.', safe); val ext = safe.substringAfterLast('.', "")
            candidate = if (ext.isEmpty()) "$base ($index)" else "$base ($index).$ext"
        }
        throw IllegalStateException("Could not create a unique file in selected folder")
    }

    private fun forceStopJobProcess(jobId: String) {
        try {
            val marker = jobs[jobId]?.absolutePath ?: jobId
            val appPid = android.os.Process.myPid()
            for (entry in File("/proc").listFiles().orEmpty()) {
                val pid = entry.name.toIntOrNull() ?: continue
                if (pid == appPid) continue
                try {
                    val cmd = File(entry, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ').trim()
                    if (cmd.contains(marker, true) || cmd.contains("yt-dlp", true) || cmd.contains("libpython", true)) android.os.Process.killProcess(pid)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) { log("CONTROL: process cleanup failed for job=$jobId", e) }
    }

    private fun cleanupJob(jobId: String, deleteFiles: Boolean) {
        if (deleteFiles) jobs[jobId]?.deleteRecursively()
        jobs.remove(jobId); jobRequests.remove(jobId); jobStates.remove(jobId); jobDestinations.remove(jobId); jobMeta.remove(jobId)
    }

    private fun handleControl(session: IHTTPSession): Response {
        return try {
            val parts = session.uri.split('/'); val jobId = parts.getOrNull(2).orEmpty(); val files = HashMap<String, String>(); session.parseBody(files)
            val action = JSONObject(files["postData"] ?: "{}").optString("action", "").lowercase(Locale.US)
            if (jobId.isBlank() || !jobs.containsKey(jobId)) return json(Response.Status.NOT_FOUND, """{"ok":false,"error":"Unknown job"}""")
            when (action) {
                "pause" -> { jobStates[jobId] = "paused"; forceStopJobProcess(jobId); writeStatus(jobs[jobId]!!, """{"status":"paused","percent":${readPercent(jobs[jobId]!!)}}"""); log("CONTROL: paused job=$jobId"); json(Response.Status.OK, """{"ok":true,"status":"paused"}""") }
                "resume" -> { if (jobStates[jobId] == "paused") { jobStates[jobId] = "running"; writeStatus(jobs[jobId]!!, """{"status":"running","percent":${readPercent(jobs[jobId]!!)}}"""); executor.execute { runJob(jobId) }; log("CONTROL: resumed job=$jobId") }; json(Response.Status.OK, """{"ok":true,"status":"running"}""") }
                "cancel" -> { jobStates[jobId] = "cancelled"; forceStopJobProcess(jobId); writeStatus(jobs[jobId]!!, """{"status":"cancelled","percent":0}"""); log("CONTROL: cancelled job=$jobId"); cleanupJob(jobId, true); json(Response.Status.OK, """{"ok":true,"status":"cancelled"}""") }
                else -> json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"Unknown control action"}""")
            }
        } catch (e: Exception) {
            log("Control request failed", e); json(Response.Status.INTERNAL_ERROR, """{"ok":false,"error":${JSONObject.quote(e.message ?: "Control request failed")}}""")
        }
    }

    override fun serve(session: IHTTPSession): Response = when {
        session.method == Method.OPTIONS -> cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
        session.method == Method.GET && session.uri == "/health" -> cors(json(Response.Status.OK, """{"ok":true}"""))
        session.method == Method.GET && session.uri == "/versions" -> cors(json(Response.Status.OK, versions()))
        session.method == Method.POST && session.uri == "/analyze" -> cors(analyzeUrl(session))
        session.method == Method.POST && session.uri == "/download" -> cors(startDownload(session))
        session.method == Method.GET && session.uri.startsWith("/status/") -> {
            val jobId = session.uri.removePrefix("/status/").substringBefore('/'); val dir = jobs[jobId]
            if (dir == null || !dir.isDirectory) cors(json(Response.Status.NOT_FOUND, """{"ok":false,"error":"Unknown job"}"""))
            else cors(newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", File(dir, "android_status.json").takeIf { it.isFile }?.readText() ?: """{"status":"unknown","percent":0}"""))
        }
        session.method == Method.POST && session.uri.startsWith("/download/") && session.uri.endsWith("/control") -> cors(handleControl(session))
        else -> cors(json(Response.Status.NOT_FOUND, """{"ok":false,"error":"Not found"}"""))
    }
}
