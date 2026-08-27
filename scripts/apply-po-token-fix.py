from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "app/src/main/java/com/ipsuman/mediadownloader/LocalEngineServer.kt"

text = SERVER.read_text(encoding="utf-8")

if 'private val preferences = context.getSharedPreferences("media_downloader", Context.MODE_PRIVATE)' not in text:
    marker = '    private val executor = Executors.newCachedThreadPool()\n'
    if marker not in text: raise SystemExit("engine field insertion point not found")
    text = text.replace(marker, marker + '    private val preferences = context.getSharedPreferences("media_downloader", Context.MODE_PRIVATE)\n', 1)

if 'private val jobBuilders = ConcurrentHashMap<String, () -> YoutubeDLRequest>()' not in text:
    marker = '    private val jobCookieRequests = ConcurrentHashMap<String, YoutubeDLRequest>()\n'
    if marker not in text: raise SystemExit("job request insertion point not found")
    text = text.replace(marker, marker + '    private val jobBuilders = ConcurrentHashMap<String, () -> YoutubeDLRequest>()\n    private val jobYoutube = ConcurrentHashMap<String, Boolean>()\n    private val jobCookieBuilders = ConcurrentHashMap<String, () -> YoutubeDLRequest>()\n', 1)

old_auth = '''    private fun addAuthenticationOptions(request: YoutubeDLRequest) {
        val cookies = youtubeCookiesFile()
        if (cookies != null) { request.addOption("--cookies", cookies.absolutePath); log("Using imported YouTube cookies for yt-dlp authentication") }
        addYoutubePoToken(request)
    }

    @Synchronized private fun addYoutubePoToken(request: YoutubeDLRequest) {
        try {
            val token = poTokenProvider.getMwebGvsToken()
            val visitorData = poTokenProvider.visitorData()
            if (!visitorData.isNullOrBlank()) { request.addOption("--extractor-args", "youtube:visitor_data=$visitorData"); log("Attached Innertube visitorData to YouTube request") }
            if (!token.isNullOrBlank()) { request.addOption("--extractor-args", "youtube:player-client=mweb;visitor_data=$visitorData;po_token=mweb.gvs+$token"); request.addOption("--extractor-args", "youtube:pot_trace=true"); log("Generated and attached mweb GVS PO Token for YouTube") }
            else log("PO Token provider unavailable; continuing without PO Token: ${poTokenProvider.lastError() ?: "unknown"}")
        } catch (e: Exception) { log("PO Token generation failed; continuing without PO Token", e) }
    }
'''
new_auth = '''    private fun addAuthenticationOptions(request: YoutubeDLRequest) {
        val cookies = youtubeCookiesFile()
        if (cookies != null) {
            request.addOption("--cookies", cookies.absolutePath)
            log("Using imported YouTube cookies for yt-dlp authentication")
        }
        addYoutubePoToken(request)
    }

    @Synchronized private fun addYoutubePoToken(request: YoutubeDLRequest, forceRefresh: Boolean = false) {
        try {
            val result = if (forceRefresh) poTokenProvider.refreshToken() else {
                val token = poTokenProvider.getMwebGvsToken()
                if (token.isNullOrBlank()) null else YoutubePoTokenProvider.PoTokenResult(token, poTokenProvider.visitorData())
            }
            if (result != null && result.poToken.isNotBlank() && !result.visitorData.isNullOrBlank()) {
                request.addOption("--extractor-args", "youtube:player-client=mweb;visitor_data=${result.visitorData};po_token=mweb.gvs+${result.poToken}")
                request.addOption("--extractor-args", "youtube:pot_trace=true")
                log("Attached paired mweb PO Token + visitorData with explicit mweb client")
            } else {
                log("PO Token provider unavailable; continuing without PO Token: ${poTokenProvider.lastError() ?: "unknown"}")
            }
        } catch (e: Exception) {
            log("PO Token generation failed; continuing without PO Token", e)
        }
    }
'''
if old_auth not in text: raise SystemExit("authentication block not found")
text = text.replace(old_auth, new_auth, 1)

old_sig = 'private fun buildRequest(jobId: String, url: String, format: String, start: String, end: String, audioOnly: Boolean, audioFormat: String, audioQuality: String, container: String, useCookies: Boolean = true): YoutubeDLRequest {'
new_sig = 'private fun buildRequest(jobId: String, url: String, format: String, start: String, end: String, audioOnly: Boolean, audioFormat: String, audioQuality: String, container: String, useCookies: Boolean = true, includePoToken: Boolean = true): YoutubeDLRequest {'
if old_sig not in text: raise SystemExit("buildRequest signature not found")
text = text.replace(old_sig, new_sig, 1)
old_line = '            if (useCookies) addAuthenticationOptions(this) else addYoutubePoToken(this)\n'
new_line = '            if (useCookies) { if (includePoToken) addAuthenticationOptions(this) else { youtubeCookiesFile()?.let { addOption("--cookies", it.absolutePath) } } } else if (isYoutubeUrl(url)) addYoutubePoToken(this)\n'
if old_line not in text: raise SystemExit("buildRequest auth line not found")
text = text.replace(old_line, new_line, 1)

save_pattern = re.compile(r'    private fun saveToDownloads\(source: File, name: String\): Pair<String, String> \{.*?\n    \}\n    private fun writeStatus', re.S)
save_replacement = '''    private fun saveToDownloads(source: File, name: String): Pair<String, String> {
        val safe = name.replace(Regex("[\\\\/:*?\\\"<>|]"), "_")
        val mime = when (safe.substringAfterLast('.', "").lowercase(Locale.US)) {
            "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
            "m4a" -> "audio/mp4"; "opus" -> "audio/ogg"; "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"; else -> "application/octet-stream"
        }
        val resolver = context.contentResolver
        val treeUri = preferences.getString("download_tree_uri", null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (treeUri != null) {
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            if (tree != null && tree.canWrite()) {
                val target = tree.createFile(mime, safe) ?: throw IllegalStateException("Could not create file in selected download folder")
                try {
                    resolver.openOutputStream(target.uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                        ?: throw IllegalStateException("Could not open selected-folder output")
                    log("Saved completed download to selected folder: ${target.uri}")
                    return safe to target.uri.toString()
                } catch (e: Exception) { target.delete(); throw e }
            }
            log("Selected download folder is unavailable; falling back to Downloads")
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safe); put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS); put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Could not create Downloads entry")
        try {
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: throw IllegalStateException("Could not open Downloads output")
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); resolver.update(uri, values, null, null)
            return safe to uri.toString()
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }
    private fun writeStatus'''
if not save_pattern.search(text): raise SystemExit("saveToDownloads implementation not found")
text = save_pattern.sub(save_replacement, text, count=1)

run_pattern = re.compile(r'            try \{ YoutubeDL\.getInstance\(\)\.execute\(request, jobId\) \{ progress, eta, line -> val state = jobStates\[jobId\] \?: "running"; writeProgress\(dir, state, progress\.toDouble\(\), eta, line\) \} \}\n            catch \(firstError: Exception\) \{.*?\n            \}\n            when \(jobStates\[jobId\]\)', re.S)
run_replacement = '''            try {
                YoutubeDL.getInstance().execute(request, jobId) { progress, eta, line ->
                    val state = jobStates[jobId] ?: "running"
                    writeProgress(dir, state, progress.toDouble(), eta, line)
                }
            } catch (firstError: Exception) {
                if (jobYoutube[jobId] != true || !isYoutubeAuthFailure(firstError)) throw firstError
                var poRetrySucceeded = false
                var lastPoError: Exception = firstError
                for (attempt in 1..3) {
                    if (jobStates[jobId] == "paused" || jobStates[jobId] == "cancelled") return
                    try {
                        log("YouTube PO-token authentication failure; invalidating token and refreshing WebView (attempt $attempt/3)", lastPoError)
                        poTokenProvider.invalidateToken()
                        val freshBuilder = jobBuilders[jobId] ?: throw IllegalStateException("Missing request builder for $jobId")
                        val freshRequest = freshBuilder.invoke()
                        writeProgress(dir, "retrying", readPercent(dir).toDouble(), null, "Refreshing YouTube authentication ($attempt/3)…")
                        YoutubeDL.getInstance().execute(freshRequest, jobId) { progress, eta, line ->
                            val state = jobStates[jobId] ?: "running"
                            writeProgress(dir, state, progress.toDouble(), eta, line)
                        }
                        log("YouTube PO-token retry $attempt succeeded")
                        poRetrySucceeded = true
                        break
                    } catch (retryError: Exception) {
                        lastPoError = retryError
                        log("YouTube PO-token retry $attempt failed", retryError)
                    }
                }
                if (!poRetrySucceeded) {
                    val cookieBuilder = jobCookieBuilders[jobId]
                    if (cookieBuilder != null && youtubeCookiesFile() != null) {
                        log("Three successive PO-token retries failed; falling back to cookie-only authentication", lastPoError)
                        val cookieRequest = cookieBuilder.invoke()
                        jobStates[jobId] = "retrying"
                        writeProgress(dir, "retrying", readPercent(dir).toDouble(), null, "Retrying with imported YouTube cookies…")
                        YoutubeDL.getInstance().execute(cookieRequest, jobId) { progress, eta, line ->
                            val state = jobStates[jobId] ?: "running"
                            writeProgress(dir, state, progress.toDouble(), eta, line)
                        }
                        log("Cookie-only fallback succeeded")
                    } else throw lastPoError
                }
            }
            when (jobStates[jobId])'''
if not run_pattern.search(text): raise SystemExit("download execution block not found")
text = run_pattern.sub(run_replacement, text, count=1)

if 'private fun isYoutubeAuthFailure(error: Throwable): Boolean' not in text:
    marker = '    private fun readPercent(dir: File): Int = '
    helper = '''    private fun isYoutubeAuthFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth++ < 5) {
            val message = (current.message ?: "").lowercase(Locale.US)
            if (listOf("403", "forbidden", "bot detection", "sign in to confirm", "po token", "potoken", "visitor_data").any { message.contains(it) }) return true
            current = current.cause
        }
        return false
    }

    private fun freshCookieRequest(jobId: String): YoutubeDLRequest? = jobCookieBuilders[jobId]?.invoke()

'''
    if marker not in text: raise SystemExit("readPercent marker not found")
    text = text.replace(marker, helper + marker, 1)

start_pattern = re.compile(r'            val format = req\.optString\("format", ""\)\.trim\(\); val start = req\.optString\("start", ""\)\.trim\(\); val end = req\.optString\("end", ""\)\.trim\(\); val audioOnly = req\.optBoolean\("audio_only", false\); val audioFormat = req\.optString\("audio_format", ""\)\.trim\(\)\.lowercase\(Locale\.US\); val audioQuality = req\.optString\("audio_quality", ""\)\.trim\(\); val container = req\.optString\("merge_output_format", ""\)\.trim\(\); jobs\[jobId\] = dir; jobStates\[jobId\] = "running"; val youtube = isYoutubeUrl\(url\); jobRequests\[jobId\] = buildRequest\(jobId, url, format, start, end, audioOnly, audioFormat, audioQuality, container, useCookies = !youtube\); if \(youtube && youtubeCookiesFile\(\) != null\) jobCookieRequests\[jobId\] = buildRequest\(jobId, url, format, start, end, audioOnly, audioFormat, audioQuality, container, useCookies = true\);', re.S)
start_replacement = '''            val format = req.optString("format", "").trim(); val start = req.optString("start", "").trim(); val end = req.optString("end", "").trim(); val audioOnly = req.optBoolean("audio_only", false); val audioFormat = req.optString("audio_format", "").trim().lowercase(Locale.US); val audioQuality = req.optString("audio_quality", "").trim(); val container = req.optString("merge_output_format", "").trim(); jobs[jobId] = dir; jobStates[jobId] = "running"; val youtube = isYoutubeUrl(url); jobYoutube[jobId] = youtube; jobBuilders[jobId] = { buildRequest(jobId, url, format, start, end, audioOnly, audioFormat, audioQuality, container, useCookies = false, includePoToken = true) }; jobRequests[jobId] = jobBuilders[jobId]!!.invoke(); if (youtube && youtubeCookiesFile() != null) { jobCookieBuilders[jobId] = { buildRequest(jobId, url, format, start, end, audioOnly, audioFormat, audioQuality, container, useCookies = true, includePoToken = false) }; jobCookieRequests[jobId] = jobCookieBuilders[jobId]!!.invoke() };'''
if not start_pattern.search(text): raise SystemExit("startDownload request construction not found")
text = start_pattern.sub(start_replacement, text, count=1)

text = text.replace('jobCookieRequests.remove(jobId); jobBuilders.remove(jobId); jobYoutube.remove(jobId); jobStates.remove(jobId)', 'jobCookieRequests.remove(jobId); jobBuilders.remove(jobId); jobYoutube.remove(jobId); jobCookieBuilders.remove(jobId); jobStates.remove(jobId)', 1)
SERVER.write_text(text, encoding="utf-8")
print("Applied source-aligned PO-token refresh, cookie fallback, and selected-folder handling")
