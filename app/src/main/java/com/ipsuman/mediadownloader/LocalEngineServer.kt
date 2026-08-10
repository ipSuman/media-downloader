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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalEngineServer(private val context: Context) : NanoHTTPD(8765) {

    private val logFile: File = File(context.filesDir, "media-downloader-engine.log")

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
            """
            {
              "ytdlp": {"installed": "$version", "latest": "$version"},
              "ffmpeg": {"installed": "Not installed", "latest": "Unknown"}
            }
            """.trimIndent()
        } catch (e: Exception) {
            log("yt-dlp engine check FAILED", e)
            exportLogToDownloads()
            """
            {
              "ytdlp": {"installed": "Error", "latest": "Unknown"},
              "ffmpeg": {"installed": "Not installed", "latest": "Unknown"},
              "error": "${JSONObject.quote(e.message ?: "Python engine error")}" 
            }
            """.trimIndent().replace("\"${JSONObject.quote(e.message ?: "Python engine error")}\"", JSONObject.quote(e.message ?: "Python engine error"))
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
}
