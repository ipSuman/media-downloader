package com.ipsuman.mediadownloader

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
            JSONObject().apply { put("ytdlp", JSONObject().apply { put("installed", "Error") }); put("ffmpeg", JSONObject().apply { put("installed", "Error") }); put("error", e.message ?: "Engine error") }.toString()
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
            json(Response.Status.INTERNAL_ERROR, JSONObject().apply { put("ok", false); put("error", diagnostic(e)); put("exception", e::class.java.name) }.toString())
        }
    }

    private fun diagnostic(e: Throwable): String {
        val parts = ArrayList<String>(); var x: Throwable? = e; var n = 0
        while (x != null && n++ < 4) { parts += "${x::class.java.simpleName}: ${x.message ?: ""}"; x = x.cause }
        return parts.joinToString(" | ").replace(Regex("\\s+"), " ").take(1500)
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
            jobs[jobId] = dir
            writeStatus(dir, """{"status":"starting","percent":0}""")
            val format = req.optString("format", "").trim()
            val start = req.optString("start", "").trim()
            val end = req.optString("end", "").trim()
            val audioOnly = req.optBoolean("audio_only", false)
            val audioFormat = req.optString("audio_format", "").trim().lowercase(Locale.US)
            val audioQuality = req.optString("audio_quality", "").trim()
            val container = req.optString("merge_output_format", "").trim()
            log("Starting download $jobId: format=$format audioOnly=$audioOnly section=$start-$end")
            executor.execute {
                try {
                    ensureEngine()
                    val request = YoutubeDLRequest(url).apply {
                        addOption("-o", File(dir, "%(title)s [%(id)s].%(ext)s").absolutePath)
                        addOption("--no-mtime"); addOption("--no-playlist"); addOption("--retries", "3")
                        addOption("--fragment-retries", "3"); addOption("--socket-timeout", "30"); addOption("--force-ipv4")
                        addOption("-f", if (format.isNotEmpty()) format else if (audioOnly) "bestaudio/best" else "bv*+ba/b")
                        if (start.isNotEmpty() && end.isNotEmpty()) {
                            addOption("--download-sections", "*$start-$end")
                            addOption("--force-keyframes-at-cuts")
                        }
                        if (container.isNotEmpty() && container != "auto") addOption("--merge-output-format", container)
                        if (audioOnly) {
                            addOption("-x")
                            if (audioFormat.isNotEmpty()) addOption("--audio-format", audioFormat)
                            if (audioQuality.isNotEmpty() && !audioQuality.equals("best", true)) addOption("--audio-quality", audioQuality)
                        }
                    }
                    YoutubeDL.getInstance().execute(request, jobId) { progress, eta, line ->
                        writeStatus(dir, JSONObject().apply { put("status", if (progress >= 100) "processing" else "downloading"); put("percent", progress.toInt().coerceIn(0,100)); put("eta", eta); put("message", line ?: "") }.toString())
                    }
                    val source = dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") && it.name != "android_status.json" }.maxByOrNull { it.lastModified() } ?: throw IllegalStateException("yt-dlp completed but no output file was found")
                    val saved = saveToDownloads(source, source.name)
                    writeStatus(dir, JSONObject().apply { put("status", "completed"); put("percent", 100); put("filename", saved.first); put("uri", saved.second); put("size", source.length()) }.toString())
                    log("Download $jobId completed: ${saved.first}"); source.delete()
                } catch (e: Exception) {
                    val msg = diagnostic(e); log("Download $jobId FAILED: $msg", e); exportLogToDownloads()
                    writeStatus(dir, JSONObject().apply { put("status", "failed: $msg"); put("percent", 0); put("error", msg); put("exception", e::class.java.name) }.toString())
                }
            }
            json(Response.Status.OK, JSONObject().apply { put("ok", true); put("job_id", jobId) }.toString())
        } catch (e: Exception) {
            val msg = diagnostic(e); log("Could not start download: $msg", e); exportLogToDownloads()
            json(Response.Status.INTERNAL_ERROR, JSONObject().apply { put("ok", false); put("error", msg); put("exception", e::class.java.name) }.toString())
        }
    }

    private fun writeStatus(dir: File, text: String) { try { File(dir, "android_status.json").writeText(text) } catch (e: Exception) { log("Could not write job status", e) } }

    private fun saveToDownloads(source: File, requested: String): Pair<String,String> {
        val name = requested.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME,name); put(MediaStore.Downloads.MIME_TYPE,mimeType(name)); put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS); put(MediaStore.Downloads.IS_PENDING,1) }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Could not create Downloads entry")
            try { context.contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(source).use { it.copyTo(out) } } ?: throw IllegalStateException("Could not open Downloads output"); values.clear(); values.put(MediaStore.Downloads.IS_PENDING,0); context.contentResolver.update(uri,values,null,null); return Pair(name,uri.toString()) } catch (e: Exception) { context.contentResolver.delete(uri,null,null); throw e }
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS); if (!downloads.exists()) downloads.mkdirs(); val target=File(downloads,name); FileInputStream(source).use { input -> FileOutputStream(target).use { input.copyTo(it) } }; return Pair(target.name,target.toURI().toString())
    }

    private fun mimeType(name: String) = when (name.substringAfterLast('.',"").lowercase(Locale.US)) { "mp4","m4v"->"video/mp4"; "webm"->"video/webm"; "mkv"->"video/x-matroska"; "mp3"->"audio/mpeg"; "m4a"->"audio/mp4"; "opus"->"audio/opus"; "ogg"->"audio/ogg"; "flac"->"audio/flac"; else->"application/octet-stream" }

    private fun downloadStatus(id: String): Response {
        val dir=jobs[id] ?: return json(Response.Status.NOT_FOUND,"""{"ok":false,"error":"Unknown job"}""")
        return try { json(Response.Status.OK, if (File(dir,"android_status.json").exists()) File(dir,"android_status.json").readText() else """{"status":"starting","percent":0}""") } catch(e:Exception) { json(Response.Status.INTERNAL_ERROR,JSONObject().apply{put("status","failed: ${diagnostic(e)}");put("error",diagnostic(e))}.toString()) }
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) return cors(newFixedLengthResponse(Response.Status.OK,"text/plain",""))
        val response = when {
            session.method==Method.GET && session.uri=="/api/status" -> json(Response.Status.OK,"""{"ok":true,"engine":"media-downloader","platform":"android","version":"0.2.0"}""")
            session.method==Method.GET && session.uri=="/api/versions" -> json(Response.Status.OK,versions())
            session.method==Method.POST && session.uri=="/api/analyze" -> analyzeUrl(session)
            session.method==Method.POST && session.uri=="/api/download" -> startDownload(session)
            session.method==Method.GET && session.uri.startsWith("/api/download/") -> downloadStatus(session.uri.substringAfterLast('/'))
            session.method==Method.GET && session.uri=="/api/log" -> newFixedLengthResponse(Response.Status.OK,"text/plain; charset=utf-8",if(logFile.exists())logFile.readText() else "No diagnostic log yet.")
            else -> json(Response.Status.NOT_FOUND,"""{"ok":false,"error":"Endpoint not found"}""")
        }
        return cors(response)
    }

    override fun stop() { executor.shutdownNow(); super.stop() }
}
