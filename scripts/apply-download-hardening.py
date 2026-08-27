#!/usr/bin/env python3
"""Apply download/authentication hardening to the committed Android sources at CI time.

The patch is intentionally fail-fast and idempotent: every expected source marker must
be present, so a future source change cannot silently produce a broken APK.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / "app/src/main/java/com/ipsuman/mediadownloader/LocalEngineServer.kt"
MAIN = ROOT / "app/src/main/java/com/ipsuman/mediadownloader/MainActivity.kt"
INDEX = ROOT / "index.html"
JS = ROOT / "app/src/main/assets/download-fix.js"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source marker, found {count}")
    return text.replace(old, new, 1)


# ---------- Native engine ----------
text = LOCAL.read_text()
text = replace_once(
    text,
    "import android.provider.MediaStore\n",
    "import android.provider.MediaStore\nimport androidx.documentfile.provider.DocumentFile\n",
    "DocumentFile import",
)
text = replace_once(
    text,
    '    private val jobCookieRequests = ConcurrentHashMap<String, YoutubeDLRequest>()\n',
    '    private val jobCookieRequests = ConcurrentHashMap<String, YoutubeDLRequest>()\n    private val jobRequestBuilders = ConcurrentHashMap<String, () -> YoutubeDLRequest>()\n    private val jobCookieRequestBuilders = ConcurrentHashMap<String, () -> YoutubeDLRequest>()\n    private val jobDestinationUris = ConcurrentHashMap<String, Uri>()\n',
    "job maps",
)
text = replace_once(
    text,
    '    private fun addAuthenticationOptions(request: YoutubeDLRequest) {\n',
    '''    private fun addCookieOnlyAuthentication(request: YoutubeDLRequest) {\n        val cookies = youtubeCookiesFile()\n        if (cookies != null) {\n            request.addOption("--cookies", cookies.absolutePath)\n            log("Using imported YouTube cookies (cookie-only authentication)")\n        }\n    }\n\n    private fun addAuthenticationOptions(request: YoutubeDLRequest) {\n''',
    "cookie-only helper",
)
# Analysis should not force mweb/SABR. Authentication hardening remains on downloads.
text = replace_once(
    text,
    '            if (isYoutubeUrl(url)) { addAuthenticationOptions(request); log("YouTube analysis request prepared with cookies/visitorData/PO token authentication") }\n',
    '            if (isYoutubeUrl(url)) { addCookieOnlyAuthentication(request); log("YouTube analysis request prepared without forced mweb client; download authentication remains PO-token hardened") }\n',
    "analysis client binding",
)
# Add a strict 403/bot detector and replace runJob with fresh-token retries.
run_start = text.index('    private fun runJob(jobId: String) {')
run_end = text.index('    private fun readPercent(dir: File): Int', run_start)
new_run = '''    private fun isPoAuthFailure(error: Throwable): Boolean {\n        val message = buildString {\n            var x: Throwable? = error\n            var depth = 0\n            while (x != null && depth++ < 5) { append(x.message ?: "").append('\\n'); x = x.cause }\n        }.lowercase(Locale.US)\n        return message.contains("http error 403") ||\n            message.contains("error 403") ||\n            message.contains("forbidden") ||\n            message.contains("bot detection") ||\n            message.contains("sign in to confirm") ||\n            message.contains("po_token") ||\n            message.contains("po token")\n    }\n\n    private fun executeJob(jobId: String, request: YoutubeDLRequest, dir: File) {\n        YoutubeDL.getInstance().execute(request, jobId) { progress, eta, line ->\n            val state = jobStates[jobId] ?: "running"\n            writeProgress(dir, state, progress.toDouble(), eta, line)\n        }\n    }\n\n    private fun runJob(jobId: String) {\n        val dir = jobs[jobId] ?: return\n        val initialBuilder = jobRequestBuilders[jobId] ?: return\n        try {\n            val stateBeforeEngine = jobStates[jobId] ?: return\n            if (stateBeforeEngine != "running") return\n            ensureEngine()\n            if (jobStates[jobId] != "running") return\n            writeProgress(dir, "starting", 0.0, null, "Starting download…")\n\n            var lastError: Exception? = null\n            try {\n                executeJob(jobId, initialBuilder(), dir)\n            } catch (firstError: Exception) {\n                lastError = firstError\n                val youtube = jobCookieRequestBuilders.containsKey(jobId)\n                if (!youtube || !isPoAuthFailure(firstError)) throw firstError\n\n                var succeeded = false\n                for (attempt in 1..3) {\n                    if (jobStates[jobId] == "paused" || jobStates[jobId] == "cancelled") return\n                    log("PO-token authentication retry $attempt/3 for job=$jobId; invalidating cached pair and creating a fresh WebView token")\n                    writeProgress(dir, "retrying", readPercent(dir).toDouble(), null, "Refreshing YouTube authentication… attempt $attempt/3")\n                    try {\n                        poTokenProvider.invalidateToken()\n                        poTokenProvider.refreshToken(25)\n                        val freshRequest = initialBuilder()\n                        executeJob(jobId, freshRequest, dir)\n                        succeeded = true\n                        log("PO-token authentication retry $attempt/3 succeeded for job=$jobId")\n                        break\n                    } catch (retryError: Exception) {\n                        lastError = retryError\n                        log("PO-token authentication retry $attempt/3 failed for job=$jobId", retryError)\n                    }\n                }\n                if (!succeeded) {\n                    val cookieBuilder = jobCookieRequestBuilders[jobId]\n                    if (cookieBuilder != null) {\n                        log("All 3 PO-token retries failed; switching to cookie-only YouTube retry for job=$jobId", lastError)\n                        writeProgress(dir, "retrying", readPercent(dir).toDouble(), null, "Retrying with imported YouTube cookies…")\n                        executeJob(jobId, cookieBuilder(), dir)\n                    } else {\n                        throw lastError ?: firstError\n                    }\n                }\n            }\n\n            when (jobStates[jobId]) {\n                "paused" -> { writeStatus(dir, "{\\"status\\":\\"paused\\",\\"percent\\":${readPercent(dir)}}"); return }\n                "cancelled" -> { writeStatus(dir, "{\\"status\\":\\"cancelled\\",\\"percent\\":0}"); cleanupJob(jobId, true); return }\n            }\n\n            val source = dir.walkTopDown()\n                .filter { it.isFile && !it.name.endsWith(".part") && it.name != "android_status.json" }\n                .maxByOrNull { it.lastModified() }\n                ?: throw IllegalStateException("yt-dlp completed but no output file was found")\n            val saved = saveToDownloads(source, source.name, jobDestinationUris[jobId])\n            writeStatus(dir, JSONObject().apply {\n                put("status", "completed")\n                put("percent", 100)\n                put("filename", saved.first)\n                put("uri", saved.second)\n                put("size", source.length())\n                put("speed", JSONObject.NULL)\n            }.toString())\n            log("Download $jobId completed: ${saved.first} destination=${saved.second}")\n            source.delete()\n        } catch (e: Exception) {\n            val state = jobStates[jobId]\n            if (state == "paused") { writeStatus(dir, "{\\"status\\":\\"paused\\",\\"percent\\":${readPercent(dir)}}"); return }\n            if (state == "cancelled") { writeStatus(dir, "{\\"status\\":\\"cancelled\\",\\"percent\\":0}"); cleanupJob(jobId, true); return }\n            val msg = diagnostic(e)\n            log("Download $jobId FAILED: $msg", e)\n            exportLogToDownloads()\n            writeStatus(dir, JSONObject().apply { put("status", "failed: $msg"); put("percent", 0); put("error", msg); put("exception", e::class.java.name) }.toString())\n        }\n    }\n\n'''
text = text[:run_start] + new_run + text[run_end:]
# Store builders and destination URI at job creation.
old_start = '            val format = req.optString("format", "").trim(); val start = req.optString("start", "").trim(); val end = req.optString("end", "").trim(); val audioOnly = req.optBoolean("audio_only", false); val audioFormat = req.optString("audio_format", "").trim().lowercase(Locale.US); val audioQuality = req.optString("audio_quality", "").trim(); val container = req.optString("merge_output_format", "").trim(); jobs[jobId] = dir; jobStates[jobId] = "running"; val youtube = isYoutubeUrl(url); jobRequests[jobId] = buildRequest(jobId, url, format, start, end, audioOnly, audioFormat, audioQuality, container, useCookies = !youtube); if (youtube && youtubeCookiesFile() != null) jobCookieRequests[jobId] = buildRequest(jobId, url, format, start, end, audioOnly, audioFormat, audioQuality, container, useCookies = true); writeStatus(dir, "{\"status\":\"starting\",\"percent\":0,\"speed\":null}"); log("Starting download $jobId: format=$format audioOnly=$audioOnly section=$start-$end"); executor.execute { runJob(jobId) }; json(Response.Status.OK, JSONObject().apply { put("ok", true); put("job_id", jobId) }.toString())'
new_start = '            val format = req.optString("format", "").trim(); val start = req.optString("start", "").trim(); val end = req.optString("end", "").trim(); val audioOnly = req.optBoolean("audio_only", false); val audioFormat = req.optString("audio_format", "").trim().lowercase(Locale.US); val audioQuality = req.optString("audio_quality", "").trim(); val container = req.optString("merge_output_format", "").trim(); val destinationUri = req.optString("destination_uri", "").trim(); jobs[jobId] = dir; jobStates[jobId] = "running"; val youtube = isYoutubeUrl(url); val requestBuilder: () -> YoutubeDLRequest = { buildRequest(jobId, url, format, start, end, audioOnly, audioFormat, audioQuality, container, useCookies = !youtube) }; jobRequestBuilders[jobId] = requestBuilder; jobRequests[jobId] = requestBuilder(); if (youtube && youtubeCookiesFile() != null) { val cookieBuilder: () -> YoutubeDLRequest = { buildRequest(jobId, url, format, start, end, audioOnly, audioFormat, audioQuality, container, useCookies = true) }; jobCookieRequestBuilders[jobId] = cookieBuilder; jobCookieRequests[jobId] = cookieBuilder() }; if (destinationUri.isNotEmpty()) jobDestinationUris[jobId] = Uri.parse(destinationUri); writeStatus(dir, "{\"status\":\"starting\",\"percent\":0,\"speed\":null}"); log("Starting download $jobId: format=$format audioOnly=$audioOnly section=$start-$end destination=${destinationUri.ifEmpty { "Downloads" }}"); executor.execute { runJob(jobId) }; json(Response.Status.OK, JSONObject().apply { put("ok", true); put("job_id", jobId) }.toString())'
text = replace_once(text, old_start, new_start, "download job setup")
# Cleanup maps.
text = replace_once(text, '    private fun cleanupJob(jobId: String, deleteFiles: Boolean) { if (deleteFiles) jobs[jobId]?.deleteRecursively(); jobs.remove(jobId); jobRequests.remove(jobId); jobCookieRequests.remove(jobId) ; jobStates.remove(jobId) }', '    private fun cleanupJob(jobId: String, deleteFiles: Boolean) { if (deleteFiles) jobs[jobId]?.deleteRecursively(); jobs.remove(jobId); jobRequests.remove(jobId); jobCookieRequests.remove(jobId); jobRequestBuilders.remove(jobId); jobCookieRequestBuilders.remove(jobId); jobDestinationUris.remove(jobId); jobStates.remove(jobId) }', "cleanup maps")
# The exact cleanup line in current source has no accidental space before semicolon.
if 'jobRequestBuilders.remove(jobId)' not in text:
    text = replace_once(text, '    private fun cleanupJob(jobId: String, deleteFiles: Boolean) { if (deleteFiles) jobs[jobId]?.deleteRecursively(); jobs.remove(jobId); jobRequests.remove(jobId); jobCookieRequests.remove(jobId); jobStates.remove(jobId) }', '    private fun cleanupJob(jobId: String, deleteFiles: Boolean) { if (deleteFiles) jobs[jobId]?.deleteRecursively(); jobs.remove(jobId); jobRequests.remove(jobId); jobCookieRequests.remove(jobId); jobRequestBuilders.remove(jobId); jobCookieRequestBuilders.remove(jobId); jobDestinationUris.remove(jobId); jobStates.remove(jobId) }', "cleanup maps")
# Replace fixed Downloads writer with SAF-aware writer.
save_start = text.index('    private fun saveToDownloads(')
save_end = text.index('    private fun writeStatus', save_start)
new_save = '''    private fun saveToDownloads(source: File, name: String, destinationUri: Uri?): Pair<String, String> {\n        val safe = name.replace(Regex("[\\\\\\\\/:*?\\\"<>|]"), "_")\n        val mime = when (safe.substringAfterLast('.', "").lowercase(Locale.US)) {\n            "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm";\n            "m4a" -> "audio/mp4"; "opus" -> "audio/ogg"; "mp3" -> "audio/mpeg";\n            "flac" -> "audio/flac"; else -> "application/octet-stream"\n        }\n        if (destinationUri != null) {\n            val tree = DocumentFile.fromTreeUri(context, destinationUri)\n                ?: throw IllegalStateException("Selected download folder is no longer accessible")\n            if (!tree.canWrite()) throw IllegalStateException("Selected download folder is not writable")\n            var targetName = safe\n            var counter = 1\n            while (tree.findFile(targetName) != null) {\n                val dot = safe.lastIndexOf('.')\n                targetName = if (dot > 0) safe.substring(0, dot) + " (" + counter++ + ")" + safe.substring(dot) else "$safe (${counter++})"\n            }\n            val target = tree.createFile(mime, targetName)\n                ?: throw IllegalStateException("Could not create output in selected folder")\n            try {\n                context.contentResolver.openOutputStream(target.uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }\n                    ?: throw IllegalStateException("Could not open selected-folder output")\n                return targetName to target.uri.toString()\n            } catch (e: Exception) {\n                target.delete()\n                throw e\n            }\n        }\n        val resolver = context.contentResolver\n        val values = ContentValues().apply {\n            put(MediaStore.Downloads.DISPLAY_NAME, safe)\n            put(MediaStore.Downloads.MIME_TYPE, mime)\n            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)\n            put(MediaStore.Downloads.IS_PENDING, 1)\n        }\n        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)\n            ?: throw IllegalStateException("Could not create Downloads entry")\n        try {\n            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }\n                ?: throw IllegalStateException("Could not open Downloads output")\n            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); resolver.update(uri, values, null, null)\n            return safe to uri.toString()\n        } catch (e: Exception) {\n            resolver.delete(uri, null, null)\n            throw e\n        }\n    }\n'''
text = text[:save_start] + new_save + text[save_end:]
LOCAL.write_text(text)

# ---------- MainActivity: expose the persisted SAF URI to JS ----------
text = MAIN.read_text()
text = replace_once(
    text,
    '        @JavascriptInterface fun getDownloadFolderName(): String = prefs.getString("download_tree_name", "") ?: ""\n',
    '        @JavascriptInterface fun getDownloadFolderName(): String = prefs.getString("download_tree_name", "") ?: ""\n        @JavascriptInterface fun getDownloadFolderUri(): String = prefs.getString("download_tree_uri", "") ?: ""\n',
    "folder URI bridge",
)
MAIN.write_text(text)

# ---------- Analysis state: make exact analyzed formats available to the download bridge ----------
text = INDEX.read_text()
text = replace_once(
    text,
    'function showMediaInfo(data){\n\n  document',
    'function showMediaInfo(data){\n\n  window.__mdAnalysisFormats = Array.isArray(data.formats) ? data.formats.slice() : [];\n\n  document',
    "analysis format cache",
)
INDEX.write_text(text)

# ---------- Web download bridge: selected folder + exact best audio ID ----------
text = JS.read_text()
text = replace_once(
    text,
    '  function audioSelector(format) {',
    '''  function selectedDestinationUri() {\n    try {\n      if (window.Android && typeof window.Android.getDownloadFolderUri === "function") {\n        return String(window.Android.getDownloadFolderUri() || "");\n      }\n    } catch (e) { console.warn("Media Downloader: folder URI unavailable", e); }\n    return "";\n  }\n\n  function bestAudioFormatId() {\n    const formats = Array.isArray(window.__mdAnalysisFormats) ? window.__mdAnalysisFormats : [];\n    const audio = formats.filter(f => f && f.format_id && f.acodec && f.acodec !== "none" && (!f.vcodec || f.vcodec === "none"));\n    audio.sort((a, b) => Number(b.abr || 0) - Number(a.abr || 0) || Number(b.tbr || 0) - Number(a.tbr || 0));\n    return audio.length ? String(audio[0].format_id) : "";\n  }\n\n  function audioSelector(format) {''',
    "folder/audio helpers",
)
# Replace selected video format construction.
text = replace_once(
    text,
    '      const shouldMergeVp9 = chosen.codec.toLowerCase().includes("vp9") && mergeEnabled();\n      format = shouldMergeVp9 ? `${chosen.id}+251` : chosen.id;\n',
    '''      const bestAudioId = bestAudioFormatId();\n      if (!bestAudioId) { alert("Analysis did not return a usable audio format ID."); return; }\n      format = `${chosen.id}+${bestAudioId}`;\n      console.log("Media Downloader: selected video paired with analyzed best audio", { videoId: chosen.id, audioId: bestAudioId, format });\n''',
    "exact video+audio pairing",
)
# Remove obsolete VP9 separate-track branch and make payload carry destination URI.
vp9_start = text.find('      if (mode === "video" && videoCodec.toLowerCase().includes("vp9") && !mergeEnabled()) {')
if vp9_start >= 0:
    vp9_end = text.index('      const item = makeQueueItem', vp9_start)
    text = text[:vp9_start] + text[vp9_end:]
text = replace_once(
    text,
    '    const common = { url, start: section?.start || "", end: section?.end || "", subtitles, thumbnail, metadata };\n',
    '    const destination_uri = selectedDestinationUri();\n    const common = { url, start: section?.start || "", end: section?.end || "", subtitles, thumbnail, metadata, destination_uri };\n',
    "destination URI payload",
)
JS.write_text(text)

print("Download hardening applied: SAF destination, exact video+audio pairing, 3 fresh PO-token retries, cookie-only fallback, and analysis client de-forcing.")
