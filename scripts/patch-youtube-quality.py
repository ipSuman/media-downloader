from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "app/src/main/java/com/ipsuman/mediadownloader/LocalEngineServer.kt"
JS = ROOT / "app/src/main/assets/download-fix.js"

engine = ENGINE.read_text(encoding="utf-8")
js = JS.read_text(encoding="utf-8")

# Keep analysis cookie-free so the format catalogue is not reduced by the
# imported browser session. Cookies remain available for the download retry.
old = '    private val jobRequests = ConcurrentHashMap<String, YoutubeDLRequest>()\n'
new = old + '    private val jobCookieRequests = ConcurrentHashMap<String, YoutubeDLRequest>()\n'
if 'private val jobCookieRequests' not in engine:
    if old not in engine:
        raise SystemExit("Could not locate jobRequests declaration")
    engine = engine.replace(old, new, 1)

old = '            val request = YoutubeDLRequest(url).apply { addAuthenticationOptions(this) }\n'
new = ('            // Analyze without imported cookies so YouTube exposes the full public format catalogue.\n'
       '            // Cookies remain available for the download retry path below.\n'
       '            val request = YoutubeDLRequest(url)\n')
if old in engine:
    engine = engine.replace(old, new, 1)

old = '''    private fun buildRequest(
        jobId: String,
        url: String,
        format: String,
        start: String,
        end: String,
        audioOnly: Boolean,
        audioFormat: String,
        audioQuality: String,
        container: String
    ): YoutubeDLRequest {
'''
new = '''    private fun buildRequest(
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
'''
if old in engine:
    engine = engine.replace(old, new, 1)

old = '            addAuthenticationOptions(this)\n            addOption("-o", File(dir, "%(title)s [%(id)s].%(ext)s").absolutePath)\n'
new = '            if (useCookies) addAuthenticationOptions(this)\n            addOption("-o", File(dir, "%(title)s [%(id)s].%(ext)s").absolutePath)\n'
if old in engine:
    engine = engine.replace(old, new, 1)

# First attempt is cookie-free for YouTube. If that fails, retry the exact
# same format with the imported cookie file. No quality downgrade is added.
old = '''            YoutubeDL.getInstance().execute(request, jobId) { progress, eta, line ->
                val state = jobStates[jobId] ?: "running"
                writeProgress(dir, state, progress.toDouble(), eta, line)
            }
'''
new = '''            try {
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
'''
if old in engine:
    engine = engine.replace(old, new, 1)

old = '''            jobs[jobId] = dir
            jobStates[jobId] = "running"
            jobRequests[jobId] = buildRequest(jobId, url, format, start, end, audioOnly, audioFormat, audioQuality, container)
            writeStatus(dir, """{\"status\":\"starting\",\"percent\":0,\"speed\":null}""")
'''
new = '''            jobs[jobId] = dir
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
            writeStatus(dir, """{\"status\":\"starting\",\"percent\":0,\"speed\":null}""")
'''
if old in engine:
    engine = engine.replace(old, new, 1)

marker = '    private fun cleanupJob(jobId: String, deleteFiles: Boolean) {\n'
insert = '''    private fun isYoutubeUrl(url: String): Boolean {
        return try {
            val host = java.net.URI(url).host?.lowercase(Locale.US) ?: return false
            host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtu.be" || host.endsWith(".youtu.be")
        } catch (_: Exception) { false }
    }

'''
if 'private fun isYoutubeUrl' not in engine:
    if marker not in engine:
        raise SystemExit("Could not locate cleanupJob")
    engine = engine.replace(marker, insert + marker, 1)

old = '        jobRequests.remove(jobId)\n        jobStates.remove(jobId)\n'
new = '        jobRequests.remove(jobId)\n        jobCookieRequests.remove(jobId)\n        jobStates.remove(jobId)\n'
if old in engine:
    engine = engine.replace(old, new, 1)

# Strict selected-quality semantics: selecting 1440p must not silently become 1080p.
old = '    return `bv*[height<=${h}]+ba/b[height<=${h}]`;\n'
new = '    return `bv*[height=${h}]+ba/b[height=${h}]`;\n'
if old in js:
    js = js.replace(old, new, 1)

ENGINE.write_text(engine, encoding="utf-8")
JS.write_text(js, encoding="utf-8")
print("YouTube quality fallback patch applied")
