package com.ipsuman.mediadownloader

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.chaquo.python.Python
import fi.iki.elonen.NanoHTTPD
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

    private val logFile: File = File(context.filesDir, "media-downloader-engine.log")
    private val executor = Executors.newCachedThreadPool()
    private val jobs = ConcurrentHashMap<String, File>()

    private fun log(message: String, throwable: Throwable? = null) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val text = buildString {
            append("[").append(time).append("] ").append(message).append("\n")
            if (throwable != null) {
                append(throwable::class.java.name).append(": ")
                    .append(throwable.message ?: "").append("\n")
                append(throwable.stackTraceToString()).append("\n")
            }
        }
        try { logFile.appendText(text) } catch (_: Exception) {}
        android.util.Log.e("MediaDownloader", message, throwable)
    }

    private fun exportLogToDownloads() {
        try {
            if (!logFile.exists() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "media-downloader-engine.log")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    logFile.inputStream().use { input -> input.copyTo(output) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } catch (e: Exception) {
            log("Could not export diagnostic log to Downloads", e)
        }
    }

    private fun cors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return response
    }

    private fun jsonResponse(status: Response.Status, body: String): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", body)
    }

    private fun pythonStatus(): String {
        log("Checking yt-dlp Python engine")
        return try {
            val py = Python.getInstance()
            log("Python.getInstance() succeeded")
            val engine = py.getModule("engine")
            log("Imported Python module: engine")
            val version = engine.callAttr("get_version").toString()
            log("yt-dlp version detected: $version")
            JSONObject().apply {
                put("ytdlp", JSONObject().apply {
                    put("installed", version)
                    put("latest", version)
                })
                put("ffmpeg", JSONObject().apply {
                    put("installed", "Not installed")
                    put("latest", "Unknown")
                })
            }.toString()
        } catch (e: Exception) {
            log("yt-dlp engine check FAILED", e)
            exportLogToDownloads()
            JSONObject().apply {
                put("ytdlp", JSONObject().apply {
                    put("installed", "Error")
                    put("latest", "Unknown")
                })
                put("ffmpeg", JSONObject().apply {
                    put("installed", "Not installed")
                    put("latest", "Unknown")
                })
                put("error", e.message ?: "Python engine error")
            }.toString()
        }
    }

    private fun analyzeUrl(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val body = files["postData"] ?: "{}"
            val request = JSONObject(body)
            val url = request.optString("url", "").trim()

            if (url.isEmpty()) {
                return jsonResponse(
                    Response.Status.BAD_REQUEST,
                    """{"ok":false,"error":"URL is required"}"""
                )
            }

            log("Analyzing URL: $url")
            val py = Python.getInstance()
            val engine = py.getModule("engine")
            val result = engine.callAttr("analyze_json", url).toString()
            log("URL analysis completed")
            jsonResponse(Response.Status.OK, result)
        } catch (e: Exception) {
            log("URL analysis FAILED", e)
            exportLogToDownloads()
            jsonResponse(
                Response.Status.INTERNAL_ERROR,
                JSONObject().apply {
                    put("ok", false)
                    put("error", e.message ?: "Unable to analyze URL")
                }.toString()
            )
        }
    }

    private fun startDownload(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val body = files["postData"] ?: "{}"
            val request = JSONObject(body)
            val url = request.optString("url", "").trim()
            if (url.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, """{"ok":false,"error":"URL is required"}""")
            }

            val jobId = UUID.randomUUID().toString().replace("-", "").take(12)
            val jobDir = File(context.cacheDir, "media-downloads/$jobId")
            if (!jobDir.mkdirs() && !jobDir.isDirectory) {
                throw IllegalStateException("Could not create temporary download directory")
            }
            jobs[jobId] = jobDir
            writeJobStatus(jobDir, """{"status":"starting","percent":0}""")

            val format = request.optString("format", "")
            val audioOnly = request.optBoolean("audio_only", false)
            val audioFormat = request.optString("audio_format", "")
            val audioQuality = request.optString("audio_quality", "")
            val mergeOutputFormat = request.optString("merge_output_format", "")

            log("Starting download job $jobId: $url | format=$format | audioOnly=$audioOnly | audioFormat=$audioFormat | audioQuality=$audioQuality | container=$mergeOutputFormat")

            executor.execute {
                try {
                    val py = Python.getInstance()
                    val engine = py.getModule("engine")
                    val resultJson = engine.callAttr(
                        "download_json",
                        url,
                        jobDir.absolutePath,
                        format,
                        audioOnly,
                        jobId,
                        audioFormat,
                        audioQuality,
                        mergeOutputFormat
                    ).toString()
                    val result = JSONObject(resultJson)
                    val sourcePath = File(result.getString("path"))
                    val destination = saveToDownloads(
                        sourcePath,
                        result.optString("filename", sourcePath.name)
                    )
                    val completed = JSONObject().apply {
                        put("status", "completed")
                        put("percent", 100)
                        put("filename", destination.first)
                        put("uri", destination.second)
                        put("size", result.optLong("size", sourcePath.length()))
                    }.toString()
                    writeJobStatus(jobDir, completed)
                    log("Download job $jobId completed: ${destination.first}")
                    sourcePath.delete()
                } catch (e: Exception) {
                    log("Download job $jobId FAILED", e)
                    writeJobStatus(
                        jobDir,
                        JSONObject().apply {
                            put("status", "failed")
                            put("percent", 0)
                            put("error", e.message ?: "Download failed")
                        }.toString()
                    )
                }
            }

            jsonResponse(
                Response.Status.OK,
                JSONObject().apply {
                    put("ok", true)
                    put("job_id", jobId)
                }.toString()
            )
        } catch (e: Exception) {
            log("Could not start download", e)
            jsonResponse(
                Response.Status.INTERNAL_ERROR,
                JSONObject().apply {
                    put("ok", false)
                    put("error", e.message ?: "Unable to start download")
                }.toString()
            )
        }
    }

    private fun writeJobStatus(jobDir: File, json: String) {
        try {
            File(jobDir, "android_status.json").writeText(json)
        } catch (e: Exception) {
            log("Could not write job status", e)
        }
    }

    private fun saveToDownloads(source: File, requestedName: String): Pair<String, String> {
        if (!source.exists()) throw IllegalStateException("Downloaded file does not exist")

        val cleanName = requestedName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, cleanName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType(cleanName))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create Downloads entry")
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(source).use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("Could not open Downloads output")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return Pair(cleanName, uri.toString())
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }

        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists()) downloads.mkdirs()
        val target = File(downloads, cleanName)
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        return Pair(target.name, target.toURI().toString())
    }

    private fun mimeType(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }
    }

    private fun downloadStatus(jobId: String): Response {
        val jobDir = jobs[jobId]
            ?: return jsonResponse(Response.Status.NOT_FOUND, """{"ok":false,"error":"Unknown job"}""")

        val progressFile = File(jobDir, "progress.json")
        val androidStatus = File(jobDir, "android_status.json")

        return try {
            val raw = when {
                androidStatus.exists() -> androidStatus.readText()
                progressFile.exists() -> progressFile.readText()
                else -> """{"status":"starting","percent":0}"""
            }
            jsonResponse(Response.Status.OK, raw)
        } catch (e: Exception) {
            jsonResponse(
                Response.Status.INTERNAL_ERROR,
                JSONObject().apply {
                    put("status", "failed")
                    put("error", e.message ?: "Status unavailable")
                }.toString()
            )
        }
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
        }

        val response = when {
            session.method == Method.GET && session.uri == "/api/status" ->
                jsonResponse(
                    Response.Status.OK,
                    """{"ok":true,"engine":"media-downloader","platform":"android","version":"0.1.0"}"""
                )

            session.method == Method.GET && session.uri == "/api/versions" ->
                jsonResponse(Response.Status.OK, pythonStatus())

            session.method == Method.POST && session.uri == "/api/analyze" ->
                analyzeUrl(session)

            session.method == Method.POST && session.uri == "/api/download" ->
                startDownload(session)

            session.method == Method.GET && session.uri.startsWith("/api/download/") ->
                downloadStatus(session.uri.substringAfterLast('/'))

            session.method == Method.GET && session.uri == "/api/log" ->
                newFixedLengthResponse(
                    Response.Status.OK,
                    "text/plain; charset=utf-8",
                    if (logFile.exists()) logFile.readText() else "No diagnostic log yet."
                )

            else ->
                jsonResponse(
                    Response.Status.NOT_FOUND,
                    """{"ok":false,"error":"Endpoint not found"}"""
                )
        }

        return cors(response)
    }

    override fun stop() {
        executor.shutdownNow()
        super.stop()
    }
}
