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
    private val jobCookieRequests = ConcurrentHashMap<String, YoutubeDLRequest>()
    private val jobVideoCodecs = ConcurrentHashMap<String, String>()
    private val jobFormats = ConcurrentHashMap<String, String>()
    private val jobUrls = ConcurrentHashMap<String, String>()
    private val ffmpegProcesses = ConcurrentHashMap<String, Process>()
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
            if (useCookies) addAuthenticationOptions(this)
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

    private fun resolveBundledFfmpeg(root: File): File? {
        // libffmpeg.zip.so in nativeLibraryDir is the ZIP container, not the
        // executable. FFmpeg.init() extracts its payload under this root.
        // Always prefer the extracted executable so its bundled libav*.so
        // dependencies can be resolved from the extracted library directory.
        val preferred = listOf(
            File(root, "usr/bin/ffmpeg"),
            File(root, "usr/lib/ffmpeg"),
            File(root, "ffmpeg")
        )
        preferred.firstOrNull { it.isFile && it.canRead() }?.let { return it }
        if (root.exists()) {
            root.walkTopDown().firstOrNull {
                it.isFile && it.name == "ffmpeg" && it.canRead()
            }?.let { return it }
        }
        return null
    }

    private fun runVp9Job(jobId: String) {
        val dir = jobs[jobId] ?: return
        val url = jobUrls[jobId] ?: return
        val selectedFormat = jobFormats[jobId] ?: throw IllegalStateException("Selected format ID is missing")
        val hasCookies = jobCookieRequests[jobId] != null
        try {
            log("Format selected: $selectedFormat")
            log("Codec detected: VP9")
            log("Audio selected: 251")
            val videoPattern = File(dir, "video.%(ext)s")
            val audioPattern = File(dir, "audio.%(ext)s")

            fun partRequest(formatId: String, output: File, cookies: Boolean): YoutubeDLRequest {
                return YoutubeDLRequest(url).apply {
                    if (cookies) addAuthenticationOptions(this)
                    addOption("-o", output.absolutePath)
                    addOption("--no-mtime")
                    addOption("--no-playlist")
                    addOption("--retries", "3")
                    addOption("--fragment-retries", "3")
                    addOption("--socket-timeout", "30")
                    addOption("--force-ipv4")
                    addOption("--continue")
                    addOption("-f", formatId)
                }
            }

            fun executePart(request: YoutubeDLRequest, stage: String, base: Double, span: Double) {
                YoutubeDL.getInstance().execute(request, jobId) { progress, eta, line ->
                    writeProgress(dir, stage, base + progress.toDouble() * span, eta, line)
                }
            }

            log("Video download started")
            try {
                executePart(partRequest(selectedFormat, videoPattern, false), "video", 0.0, 50.0)
            } catch (first: Exception) {
                if (!hasCookies) throw first
                log("Video download failed; retrying exact format $selectedFormat with cookies", first)
                executePart(partRequest(selectedFormat, videoPattern, true), "video-retry", 0.0, 50.0)
            }
            val videoFile = dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("video.") && !it.name.endsWith(".part") }
                ?: throw IllegalStateException("Video download completed but no video file was found")
            log("Video download completed: ${videoFile.name}")

            log("Audio download started: format 251")
            try {
                executePart(partRequest("251", audioPattern, false), "audio", 50.0, 40.0)
            } catch (first: Exception) {
                if (!hasCookies) throw first
                log("Audio 251 download failed; retrying with cookies", first)
                executePart(partRequest("251", audioPattern, true), "audio-retry", 50.0, 40.0)
            }
            val audioFile = dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("audio.") && !it.name.endsWith(".part") }
                ?: throw IllegalStateException("Audio 251 download completed but no audio file was found")
            log("Audio 251 download completed: ${audioFile.name}")

            log("Waiting 3 seconds before FFmpeg concat")
            Thread.sleep(3000)
            if (jobStates[jobId] == "cancelled") return

            val ffmpegRoot = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
            val ffmpeg = resolveBundledFfmpeg(ffmpegRoot)
            if (ffmpeg == null) {
                val entries = if (ffmpegRoot.exists()) ffmpegRoot.walkTopDown().take(80).joinToString(",") { it.relativeTo(ffmpegRoot).path } else "<ffmpeg package root missing>"
                val nativeEntries = File(context.applicationInfo.nativeLibraryDir).listFiles()?.joinToString(",") { it.name } ?: "<none>"
                throw IllegalStateException("Bundled FFmpeg executable not found; root=${ffmpegRoot.absolutePath}; entries=$entries; nativeLibs=$nativeEntries")
            }
            ffmpeg.setExecutable(true, false)
            // Keep the merged output separate from the downloaded video input.
            val output = File(dir, "merged.webm")
            val extractedLibDir = File(ffmpegRoot, "usr/lib")
            val libDir = if (extractedLibDir.isDirectory) extractedLibDir else (ffmpeg.parentFile ?: ffmpegRoot)
            log("FFmpeg executable resolved: ${ffmpeg.absolutePath}")
            log("FFmpeg executable exists=${ffmpeg.exists()} executable=${ffmpeg.canExecute()} size=${ffmpeg.length()}")
            log("FFmpeg library directory: ${libDir.absolutePath}")
            log("FFmpeg library directory exists=${libDir.isDirectory}")
            if (libDir.isDirectory) {
                val libs = libDir.listFiles()?.filter { it.isFile && it.name.endsWith(".so") }?.take(30)?.joinToString(",") { it.name } ?: "<none>"
                log("FFmpeg bundled libraries: $libs")
            }
            log("FFmpeg concat started")
            log("FFmpeg command: ${ffmpeg.absolutePath} -y -i ${videoFile.absolutePath} -i ${audioFile.absolutePath} -map 0:v:0 -map 1:a:0 -c:v copy -c:a copy -shortest ${output.absolutePath}")
            val processBuilder = ProcessBuilder(ffmpeg.absolutePath, "-y", "-i", videoFile.absolutePath, "-i", audioFile.absolutePath, "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "copy", "-shortest", output.absolutePath)
                .redirectErrorStream(true)
            val env = processBuilder.environment()
            val oldLd = env["LD_LIBRARY_PATH"]
            env["LD_LIBRARY_PATH"] = if (oldLd.isNullOrBlank()) libDir.absolutePath else libDir.absolutePath + File.pathSeparator + oldLd
            val process = processBuilder.start()
            ffmpegProcesses[jobId] = process
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { log("FFmpeg: $it") } }
            val exitCode = process.waitFor()
            ffmpegProcesses.remove(jobId)
            if (exitCode != 0 || !output.exists()) throw IllegalStateException("FFmpeg failed with exit code $exitCode")
            log("FFmpeg concat completed")

            val saved = saveToDownloads(output, output.name)
            writeStatus(dir, JSONObject().apply {
                put("status", "completed"); put("percent", 100); put("filename", saved.first); put("uri", saved.second); put("size", output.length()); put("speed", JSONObject.NULL)
            }.toString())
            log("Final file saved: ${saved.first}")
            videoFile.delete(); audioFile.delete(); output.delete()
        } catch (e: Exception) {
            if (jobStates[jobId] == "cancelled") return
            log("VP9 pipeline FAILED", e)
            exportLogToDownloads()
            writeStatus(dir, JSONObject().apply { put("status", "failed: ${diagnostic(e)}"); put("percent", 0); put("error", diagnostic(e)) }.toString())
        } finally { ffmpegProcesses.remove(jobId) }
    }

    private fun runJob(jobId: String) {
        val dir = jobs[jobId] ?: return
        val request = jobRequests[jobId] ?: return
        try {
            ensureEngine()
            val codec = jobVideoCodecs[jobId]?.lowercase(Locale.US) ?: ""
            if (isYoutubeUrl(jobUrls[jobId] ?: "") && codec.contains("vp9")) {
                runVp9Job(jobId)
                return
            }
            jobStates[jobId] = "running"
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
                YoutubeDL.getInstance().execute(cookieRequest!!, jobId) { progress, eta, line ->
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
            val dir = File(context.cacheDir, "media-downloads/$jobId")
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
            jobVideoCodecs[jobId] = videoCodec
            jobFormats[jobId] = format
            jobUrls[jobId] = url
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

    private fun controlDownload(id: String, action: String): Response {
        val dir = jobs[id] ?: return json(Response.Status.NOT_FOUND, """{"ok":false,"error":"Unknown job"}""")
        val current = jobStates[id] ?: "unknown"
        return try {
            when (action) {
                "pause" -> {
                    if (current != "running") {
                        return json(Response.Status.CONFLICT, """{"ok":false,"error":"Job is not running"}""")
                    }
                    jobStates[id] = "paused"
                    YoutubeDL.getInstance().destroyProcessById(id)
                    ffmpegProcesses[id]?.destroy()
                    writeStatus(dir, """{"status":"paused","percent":${readPercent(dir)}}""")
                    log("Pause requested for download $id")
                }
                "resume" -> {
                    if (current != "paused") {
                        return json(Response.Status.CONFLICT, """{"ok":false,"error":"Job is not paused"}""")
                    }
                    jobStates[id] = "running"
                    writeStatus(dir, """{"status":"resuming","percent":${readPercent(dir)}}""")
                    executor.execute { runJob(id) }
                    log("Resume requested for download $id")
                }
                "cancel" -> {
                    if (current == "completed" || current == "cancelled") {
                        return json(Response.Status.CONFLICT, """{"ok":false,"error":"Job is already finished"}""")
                    }
                    jobStates[id] = "cancelled"
                    YoutubeDL.getInstance().destroyProcessById(id)
                    ffmpegProcesses[id]?.destroy()
                    writeStatus(dir, """{"status":"cancelled","percent":0}""")
                    log("Cancel requested for download $id")
                }
            }
            json(Response.Status.OK, """{"ok":true,"status":"${jobStates[id] ?: current}"}""")
        } catch (e: Exception) {
            log("Download control FAILED for $id/$action", e)
            json(Response.Status.INTERNAL_ERROR, """{"ok":false,"error":"${diagnostic(e).replace("\"", "'")}"}""")
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
        try { File(dir, "android_status.json").writeText(json) } catch (e: Exception) { log("Could not write job status", e) }
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
        jobVideoCodecs.remove(jobId)
        jobFormats.remove(jobId)
        jobUrls.remove(jobId)
        ffmpegProcesses.remove(jobId)
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
