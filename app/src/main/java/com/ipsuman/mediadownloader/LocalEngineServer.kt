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
    private val jobCookieRequests = ConcurrentHashMap<String, YoutubeDLRequest>()
    private val jobStates = ConcurrentHashMap<String, String>()
    private val preferences = context.getSharedPreferences("media_downloader", Context.MODE_PRIVATE)
    private val poTokenProvider = YoutubePoTokenProvider(context)
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
                val updateStatus = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)
                log("yt-dlp update result: $updateStatus")
            } catch (e: Exception) {
                log("yt-dlp update check failed; keeping bundled binary", e)
            }
        }
        val version = try {
            YoutubeDL.getInstance().version(context) ?: "bundled"
        } catch (_: Exception) {
            "bundled"
        }
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

    private fun addAuthenticationOptions(request: YoutubeDLRequest) {
        val cookies = youtubeCookiesFile()
        if (cookies != null) {
            request.addOption("--cookies", cookies.absolutePath)
            log("Using imported YouTube cookies for yt-dlp authentication")
        }
        addYoutubePoToken(request)
    }

    @Synchronized
    private fun addYoutubePoToken(request: YoutubeDLRequest) {
        try {
            val token = poTokenProvider.getMwebGvsToken()
            val visitorData = poTokenProvider.visitorData()
            if (!visitorData.isNullOrBlank()) {
                request.addOption(
                    "--extractor-args",
                    "youtube:visitor_data=$visitorData"
                )
                log("Attached Innertube visitorData to YouTube request")
            }
            if (!token.isNullOrBlank()) {
                request.addOption(
                    "--extractor-args",
                    "youtube:player-client=mweb;visitor_data=$visitorData;po_token=mweb.gvs+$token"
                )
                request.addOption("--extractor-args", "youtube:pot_trace=true")
                log("Generated and attached mweb GVS PO Token for YouTube")
            } else {
                log("PO Token provider unavailable; continuing without PO Token: ${poTokenProvider.lastError() ?: "unknown"}")
            }
        } catch (e: Exception) {
            log("PO Token generation failed; continuing without PO Token", e)
        }
    }

    private fun cors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return r
    }

    private fun json(status: Response.Status, body: String) =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)

    private fun versions(): String {
        return try {
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
    }

    private fun analyzeUrl(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>(); session.parseBody(files)
            val body = files["postData"] ?: "{}"
            val url = JSONObject(body).optString("url", "").trim()
            if (url.isEmpty()) return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"URL is required"}""")
            log("Analyzing URL with Android yt-dlp: $url")
            ensureEngine()
            // Analyze without imported cookies so YouTube exposes the full public format catalogue.
            // Cookies remain available for the download retry path below.
            val request = YoutubeDLRequest(url)
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
            val result = JSONObject().apply {
                put("ok", true)
                put("id", info.id ?: "")
                put("title", info.title ?: info.fulltitle ?: "")
                put("uploader", info.uploader ?: "")
                put("channel", info.uploader ?: "")
                put("duration", info.duration)
                put("thumbnail", info.thumbnail ?: "")
                put("webpage_url", info.webpageUrl ?: url)
                put("extractor", info.extractorKey ?: info.extractor ?: "")
                put("is_live", false)
                put("formats", formats)
            }.toString()
            log("URL analysis completed: ${formats.length()} formats")
            json(Response.Status.OK, result)
        } catch (e: Exception) {
            log("URL analysis FAILED", e); exportLogToDownloads()
            json(Response.Status.INTERNAL_ERROR, JSONObject().apply {
                put("ok", false)
                put("error", diagnostic(e))
                put("exception", e::class.java.name)
            }.toString())
        }
    }

    private fun diagnostic(e: Throwable): String {
        val parts = ArrayList<String>(); var x: Throwable? = e; var n = 0
        while (x != null && n++ < 4) {
            parts += "${x::class.java.simpleName}: ${x.message ?: ""}"
            x = x.cause
        }
        return parts.joinToString(" | ").replace(Regex("\\s+"), " ").take(1500)
    }

    private fun buildRequest(
        jobId: String,
        url: String,
        format: String,
        start: String,
        end: String,
        audioOnly: Boolean,
        audioFormat: String,
        audioQuality: String,
        container: String,
        useCookies: Boolean = true
    ): YoutubeDLRequest {
        val dir = jobs[jobId] ?: throw IllegalStateException("Unknown download job")
        return YoutubeDLRequest(url).apply {
            if (useCookies) addAuthenticationOptions(this) else addYoutubePoToken(this)
            addOption("-o", File(dir, "%(title)s [%(id)s].%(ext)s").absolutePath)
            addOption("--no-mtime")
            addOption("--no-playlist")
            addOption("--retries", "3")
            addOption("--fragment-retries", "3")
            addOption("--socket-timeout", "30")
            addOption("--force-ipv4")
            addOption("--continue")
            addOption("-f", if (format.isNotEmpty()) format else if (audioOnly) "bestaudio/best" else "bv*+ba/b")
            if (start.isNotEmpty() && end.isNotEmpty()) {
                addOption("--download-sections", "*$start-$end")
                addOption("--force-keyframes-at-cuts")
            }
            if (container.isNotEmpty() && container != "auto") {
                addOption("--merge-output-format", container)
            }
            if (audioOnly) {
                addOption("-x")
                if (audioFormat.isNotEmpty()) addOption("--audio-format", audioFormat)
                if (audioQuality.isNotEmpty() && !audioQuality.equals("best", true)) {
                    addOption("--audio-quality", audioQuality)
                }
            }
        }
    }

    private fun extractSpeed(line: String?): String? {
        if (line.isNullOrBlank()) return null
        val match = Regex("(?:at\\s+|\\s)(\\d+(?:\\.\\d+)?\\s*[KMGTP]?i?B/s)", RegexOption.IGNORE_CASE)
            .find(line)
        return match?.groupValues?.getOrNull(1)?.replace(" ", "")
    }

    private fun writeProgress(dir: File, state: String, progress: Double, eta: Long?, line: String?) {
        val speed = extractSpeed(line)
        writeStatus(dir, JSONObject().apply {
            put("status", state)
            val safeProgress = progress.toInt().coerceIn(0, 100)
            put("percent", if (state == "completed") 100 else safeProgress.coerceAtMost(99))
            put("eta", eta ?: JSONObject.NULL)
            put("speed", speed ?: JSONObject.NULL)
            put("message", line ?: "")
        }.toString())
    }

    private fun runJob(jobId: String) {
        val dir = jobs[jobId] ?: return
        val request = jobRequests[jobId] ?: return
        try {
            val stateBeforeEngine = jobStates[jobId] ?: return
            if (stateBeforeEngine != "running") {
                log("Download $jobId start suppressed before engine init: state=$stateBeforeEngine")
                return
            }
            ensureEngine()
            if (jobStates[jobId] != "running") {
                log("Download $jobId start suppressed after engine init: state=${jobStates[jobId]}")
                return
            }
            writeProgress(dir, "starting", 0.0, null, "Starting download…")
            try {
                YoutubeDL.getInstance().execute(request, jobId) { progress, eta, line ->
                    val state = jobStates[jobId] ?: "running"
                    writeProgress(dir, state, progress.toDouble(), eta, line)
                }
            } catch (firstError: Exception) {
                val cookieRequest = jobCookieRequests[jobId]
                val canRetryWithCookies = cookieRequest != null &&
                    jobStates[jobId] != "paused" && jobStates[jobId] != "cancelled"
                if (!canRetryWithCookies) throw firstError
                log("Initial YouTube download failed; retrying the same selected format with imported cookies", firstError)
                jobStates[jobId] = "retrying"
                writeProgress(dir, "retrying", readPercent(dir).toDouble(), null,
                    "Retrying with imported YouTube cookies…")
                val retryRequest = cookieRequest!!
                addAuthenticationOptions(retryRequest)
                log("PO Token and visitorData ready before cookie retry")
                // The user may have paused/cancelled while authentication was being prepared.
                // Never start the retry process after a control request has changed the state.
                if (jobStates[jobId] != "retrying") {
                    log("Cookie retry suppressed for $jobId: state=${jobStates[jobId]}")
                    return
                }
                YoutubeDL.getInstance().execute(retryRequest, jobId) { progress, eta, line ->
                    val state = jobStates[jobId] ?: "running"
                    writeProgress(dir, state, progress.toDouble(), eta, line)
                }
            }
            when (jobStates[jobId]) {
                "paused" -> {
                    writeStatus(dir, """{"status":"paused","percent":${readPercent(dir)}}""")
                    return
                }
                "cancelled" -> {
                    writeStatus(dir, """{"status":"cancelled","percent":0}""")
                    cleanupJob(jobId, deleteFiles = true)
                    return
                }
            }
            val source = dir.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(".part") && it.name != "android_status.json" }
                .maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException("yt-dlp completed but no output file was found")
            val saved = saveToDownloads(source, source.name)
            writeStatus(dir, JSONObject().apply {
                put("status", "completed")
                put("percent", 100)
                put("filename", saved.first)
                put("uri", saved.second)
                put("size", source.length())
                put("speed", JSONObject.NULL)
            }.toString())
            log("Download $jobId completed: ${saved.first}")
            source.delete()
        } catch (e: Exception) {
            val state = jobStates[jobId]
            if (state == "paused") {
                writeStatus(dir, """{"status":"paused","percent":${readPercent(dir)}}""")
                log("Download $jobId paused")
                return
            }
            if (state == "cancelled") {
                writeStatus(dir, """{"status":"cancelled","percent":0}""")
                log("Download $jobId cancelled")
                cleanupJob(jobId, deleteFiles = true)
                return
            }
            val msg = diagnostic(e)
            log("Download $jobId FAILED: $msg", e)
            exportLogToDownloads()
            writeStatus(dir, JSONObject().apply {
                put("status", "failed: $msg")
                put("percent", 0)
                put("error", msg)
                put("exception", e::class.java.name)
            }.toString())
        }
    }

    private fun readPercent(dir: File): Int {
        return try {
            JSONObject(File(dir, "android_status.json").readText()).optInt("percent", 0)
        } catch (_: Exception) { 0 }
    }

    private fun startDownload(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>(); session.parseBody(files)
            val req = JSONObject(files["postData"] ?: "{}")
            val url = req.optString("url", "").trim()
            if (url.isEmpty()) return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"URL is required"}""")
            val jobId = UUID.randomUUID().toString().replace("-", "").take(12)
            // Jobs contain active media and must not live in Android's cache directory,
            // which the OS may delete while a long download is still running.
            val dir = File(context.filesDir, "media-downloads/$jobId")
            if (!dir.mkdirs() && !dir.isDirectory) throw IllegalStateException("Could not create download directory")
            val format = req.optString("format", "").trim()
            val start = req.optString("start", "").trim()
            val end = req.optString("end", "").trim()
            val audioOnly = req.optBoolean("audio_only", false)
            val audioFormat = req.optString("audio_format", "").trim().lowercase(Locale.US)
            val audioQuality = req.optString("audio_quality", "").trim()
            val container = req.optString("merge_output_format", "").trim()
            val videoCodec = req.optString("video_codec", "").trim()
            jobs[jobId] = dir
            jobStates[jobId] = "running"
            val youtube = isYoutubeUrl(url)
            jobRequests[jobId] = buildRequest(
                jobId, url, format, start, end, audioOnly, audioFormat, audioQuality, container,
                useCookies = !youtube
            )
            if (youtube && youtubeCookiesFile() != null) {
                jobCookieRequests[jobId] = buildRequest(
                    jobId, url, format, start, end, audioOnly, audioFormat, audioQuality, container,
                    useCookies = true
                )
            }
            writeStatus(dir, """{"status":"starting","percent":0,"speed":null}""")
            log("Starting download $jobId: format=$format audioOnly=$audioOnly section=$start-$end")
            executor.execute { runJob(jobId) }
            json(Response.Status.OK, JSONObject().apply {
                put("ok", true)
                put("job_id", jobId)
            }.toString())
        } catch (e: Exception) {
            val msg = diagnostic(e)
            log("Could not start download: $msg", e)
            exportLogToDownloads()
            json(Response.Status.INTERNAL_ERROR, JSONObject().apply {
                put("ok", false)
                put("error", msg)
                put("exception", e::class.java.name)
            }.toString())
        }
    }

    private fun forceStopJobProcess(jobId: String) {
        val jobDir = jobs[jobId]
        val jobMarker = jobDir?.absolutePath ?: jobId
        log("CONTROL: locating download processes for job=$jobId marker=$jobMarker")

        try {
            val appPid = android.os.Process.myPid()
            val parentByPid = HashMap<Int, Int>()
            val commandByPid = HashMap<Int, String>()
            val exeByPid = HashMap<Int, String>()

            for (entry in File("/proc").listFiles().orEmpty()) {
                val pid = entry.name.toIntOrNull() ?: continue
                if (pid == appPid) continue
                try {
                    val status = File(entry, "status").readText()
                    val ppid = status.lineSequence()
                        .firstOrNull { it.startsWith("PPid:") }
                        ?.substringAfter(":")?.trim()?.toIntOrNull() ?: continue
                    parentByPid[pid] = ppid
                    val cmd = File(entry, "cmdline").readBytes()
                        .toString(Charsets.UTF_8).replace('\u0000', ' ').trim()
                    commandByPid[pid] = cmd
                    try { exeByPid[pid] = File(entry, "exe").canonicalPath } catch (_: Exception) {}
                } catch (_: Exception) {}
            }

            val roots = commandByPid.keys.filter { pid ->
                val cmd = commandByPid[pid].orEmpty()
                val exe = exeByPid[pid].orEmpty()
                cmd.contains(jobMarker, ignoreCase = true) ||
                    cmd.contains("yt-dlp", ignoreCase = true) ||
                    cmd.contains("libpython.so", ignoreCase = true) ||
                    exe.contains("libpython.so", ignoreCase = true) ||
                    exe.contains("yt-dlp", ignoreCase = true)
            }.toSet()

            fun belongsToRoot(pid: Int): Boolean {
                var current = pid
                val seen = HashSet<Int>()
                while (seen.add(current) && current != appPid) {
                    if (current in roots) return true
                    current = parentByPid[current] ?: return false
                }
                return current in roots
            }

            val targets = (roots + parentByPid.keys.filter { belongsToRoot(it) })
                .filter { it != appPid }
                .distinct()
                .sortedDescending()

            log("CONTROL: found ${targets.size} candidate process(es) for job=$jobId")
            for (pid in targets) {
                val cmd = commandByPid[pid].orEmpty()
                log("CONTROL: killing pid=$pid cmd=${cmd.take(240)}")
                try {
                    android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL)
                } catch (e: Exception) {
                    log("CONTROL: sendSignal failed for pid=$pid", e)
                    try { android.os.Process.killProcess(pid) } catch (_: Exception) {}
                }
            }

            // Also ask youtubedl-android to cancel its registered Process. Its public
            // API is the canonical cancellation path; our /proc scan above covers
            // child processes such as Python/FFmpeg that the library may miss.
            try {
                val stopped = YoutubeDL.getInstance().destroyProcessById(jobId)
                log("CONTROL: youtubedl destroyProcessById($jobId)=$stopped")
            } catch (e: Exception) {
                log("CONTROL: youtubedl destroyProcessById failed for $jobId", e)
            }
        } catch (e: Exception) {
            log("CONTROL: process scan failed for job=$jobId", e)
        }
    }

    private fun controlDownload(id: String, action: String): Response {
    val dir = jobs[id]
        ?: return json(
            Response.Status.NOT_FOUND,
            """{"ok":false,"error":"Unknown job"}"""
        )

    return try {
        when (action.lowercase(Locale.US)) {

            "pause" -> {
                val current = jobStates[id] ?: "unknown"

                // A YouTube cookie retry is still an active download. It must remain
                // controllable while yt-dlp is between the first request and retry.
                if (current != "running" && current != "retrying") {
                    return json(
                        Response.Status.CONFLICT,
                        """{"ok":false,"error":"Job is not running","status":"$current"}"""
                    )
                }

                // Set the state BEFORE killing yt-dlp.
                // This prevents runJob() from entering the retry path.
                jobStates[id] = "paused"
                log("Pause requested for download $id")

                try {
                    forceStopJobProcess(id)
                } catch (e: Exception) {
                    log("Pause process termination warning for $id", e)
                }

                writeStatus(
                    dir,
                    """{"status":"paused","percent":${readPercent(dir)}}"""
                )

                json(
                    Response.Status.OK,
                    """{"ok":true,"status":"paused"}"""
                )
            }

            "resume" -> {
                val current = jobStates[id] ?: "unknown"

                if (current != "paused") {
                    return json(
                        Response.Status.CONFLICT,
                        """{"ok":false,"error":"Job is not paused","status":"$current"}"""
                    )
                }

                jobStates[id] = "running"

                writeStatus(
                    dir,
                    """{"status":"resuming","percent":${readPercent(dir)}}"""
                )

                log("Resume requested for download $id")

                executor.execute {
                    runJob(id)
                }

                json(
                    Response.Status.OK,
                    """{"ok":true,"status":"resuming"}"""
                )
            }

            "cancel" -> {
                val current = jobStates[id] ?: "unknown"

                if (current == "completed" || current == "cancelled") {
                    return json(
                        Response.Status.CONFLICT,
                        """{"ok":false,"error":"Job is already finished","status":"$current"}"""
                    )
                }

                // IMPORTANT:
                // Set cancelled BEFORE destroying yt-dlp.
                // runJob() will then refuse to retry after the process is killed.
                jobStates[id] = "cancelled"

                log("Terminate requested for download $id")

                try {
                    forceStopJobProcess(id)
                } catch (e: Exception) {
                    log("Terminate process warning for $id", e)
                }

                writeStatus(
                    dir,
                    """{"status":"cancelled","percent":0}"""
                )

                json(
                    Response.Status.OK,
                    """{"ok":true,"status":"cancelled"}"""
                )
            }

            else -> {
                json(
                    Response.Status.BAD_REQUEST,
                    """{"ok":false,"error":"Unknown control action: ${action.replace("\"", "'")}"}"""
                )
            }
        }
    } catch (e: Exception) {
        log("Download control FAILED for $id/$action", e)

        json(
            Response.Status.INTERNAL_ERROR,
            """{"ok":false,"error":"${diagnostic(e).replace("\"", "'")}"}"""
        )
    }
}

    private fun saveToDownloads(source: File, displayName: String): Pair<String, String> {
        val treeUri = preferences.getString("download_tree_uri", null)
        if (!treeUri.isNullOrBlank()) {
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                if (tree != null && tree.canWrite()) {
                    val safeName = displayName.ifBlank { source.name }
                    val target = tree.createFile("video/*", safeName)
                    if (target != null) {
                        context.contentResolver.openOutputStream(target.uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                        return safeName to target.uri.toString()
                    }
                }
            } catch (e: Exception) { log("Custom download folder failed; falling back to Downloads", e) }
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName.ifBlank { source.name })
            put(MediaStore.Downloads.MIME_TYPE, guessMime(source.name))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create Downloads entry")
        resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException("Could not open Downloads output")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return displayName.ifBlank { source.name } to uri.toString()
    }

    private fun guessMime(name: String): String {
        if (name.startsWith("audio.", ignoreCase = true)) return "audio/webm"
        if (name.startsWith("video.", ignoreCase = true)) return "video/webm"
        return when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    private fun writeStatus(dir: File, json: String) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                log("Could not create job directory for status: ${dir.absolutePath}")
                return
            }
            File(dir, "android_status.json").writeText(json)
        } catch (e: Exception) { log("Could not write job status", e) }
    }

    private fun isYoutubeUrl(url: String): Boolean {
        return try {
            val host = java.net.URI(url).host?.lowercase(Locale.US) ?: return false
            host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtu.be" || host.endsWith(".youtu.be")
        } catch (_: Exception) { false }
    }

    private fun cleanupJob(jobId: String, deleteFiles: Boolean) {
        val dir = jobs.remove(jobId)
        jobRequests.remove(jobId)
        jobCookieRequests.remove(jobId)
        jobStates.remove(jobId)
        if (deleteFiles) dir?.deleteRecursively()
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (session.method == Method.OPTIONS) return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
            val path = session.uri.substringBefore('?')
            val response = when {
                path == "/health" -> json(Response.Status.OK, versions())
                path == "/analyze" && session.method == Method.POST -> analyzeUrl(session)
                path == "/download" && session.method == Method.POST -> startDownload(session)
                path.startsWith("/download/") && path.endsWith("/control") && session.method == Method.POST -> {
                    val id = path.removePrefix("/download/").removeSuffix("/control").trim('/')
                    val files = HashMap<String, String>(); session.parseBody(files)
                    val action = JSONObject(files["postData"] ?: "{}").optString("action", "")
                    log("CONTROL HTTP request received: job=$id action=$action")
                    controlDownload(id, action)
                }
                path.startsWith("/status/") -> {
                    val id = path.removePrefix("/status/").trim('/')
                    val dir = jobs[id]
                    if (dir == null) json(Response.Status.NOT_FOUND, """{"ok":false,"error":"Unknown job"}""")
                    else {
                        val file = File(dir, "android_status.json")
                        json(Response.Status.OK, if (file.isFile) file.readText() else """{"status":"starting","percent":0}""")
                    }
                }
                path == "/logs" -> {
                    if (logFile.isFile) newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", logFile.readText())
                    else newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "No log yet")
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
            cors(response)
        } catch (e: Exception) {
            log("HTTP request FAILED", e); exportLogToDownloads()
            cors(json(Response.Status.INTERNAL_ERROR, JSONObject().apply {
                put("ok", false); put("error", diagnostic(e))
            }.toString()))
        }
    }
}
