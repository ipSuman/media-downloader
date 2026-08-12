from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
INDEX = ROOT / 'index.html'
JS = ROOT / 'app/src/main/assets/download-fix.js'
COMPAT = ROOT / 'app/src/main/assets/api-compat.js'
KT = ROOT / 'app/src/main/java/com/ipsuman/mediadownloader/LocalEngineServer.kt'

# Frontend: add an exact format-ID selector to the existing format table.
s = INDEX.read_text()
if 'id = "mdFormatSelect"' not in s and 'id="mdFormatSelect"' not in s:
    needle = '  formats.innerHTML = "";'
    block = '''  formats.innerHTML = "";

  if (!document.getElementById("mdFormatSelect")) {
    const label = document.createElement("div");
    label.textContent = "Video format (exact yt-dlp ID)";
    label.style.cssText = "margin:12px 0 6px;font-weight:800";
    const select = document.createElement("select");
    select.id = "mdFormatSelect";
    select.style.cssText = "width:100%;padding:10px;border-radius:10px;background:var(--panel,#151515);color:var(--text,#fff);border:1px solid var(--border,#333);font-weight:700";
    formats.parentNode.insertBefore(label, formats);
    formats.parentNode.insertBefore(select, formats);
  }
  const mdSelect = document.getElementById("mdFormatSelect");
  if (mdSelect) {
    mdSelect.innerHTML = "";
    const videoFormats = (data.formats || [])
      .filter(f => f.vcodec && f.vcodec !== "none" && Number(f.height || 0) > 0)
      .sort((a,b) => Number(b.height||0)-Number(a.height||0) || Number(b.fps||0)-Number(a.fps||0));
    videoFormats.forEach(f => {
      const o = document.createElement("option");
      o.value = String(f.format_id || "");
      o.dataset.vcodec = String(f.vcodec || "");
      o.textContent = `ID ${f.format_id} — ${f.height}p ${f.vcodec}${f.fps ? ` • ${f.fps}fps` : ""}${f.ext ? ` • ${f.ext}` : ""}`;
      mdSelect.appendChild(o);
    });
  }'''
    if needle not in s:
        raise SystemExit('index format insertion point missing')
    s = s.replace(needle, block, 1)
    INDEX.write_text(s)

# Download bridge: use the selected ID literally and send its codec to Android.
s = JS.read_text()
s = re.sub(r'''  function videoSelector\(label\) \{.*?\n  \}\n\n  function audioSelector''', '''  function videoSelector(label) { return String(label || "").trim() || "bv*+ba/b"; }

  function selectedVideoFormat() {
    const select = document.getElementById("mdFormatSelect");
    if (!select || !select.value) return { id: "bv*+ba/b", codec: "" };
    const option = select.options[select.selectedIndex];
    return { id: String(select.value), codec: String(option?.dataset?.vcodec || "") };
  }

  function audioSelector''', s, count=1, flags=re.S)
old = '''      const selects = byId("videoOptions")?.querySelectorAll("select") || [];
      const quality = selects[0]?.value || "Best available";
      const container = selects[1]?.value || "Auto";
      format = videoSelector(quality);
      if (container !== "Auto") mergeOutputFormat = container.toLowerCase();'''
new = '''      const selects = byId("videoOptions")?.querySelectorAll("select") || [];
      const chosen = selectedVideoFormat();
      const quality = chosen.id;
      const container = selects[1]?.value || "Auto";
      format = videoSelector(quality);
      videoCodec = chosen.codec;
      if (container !== "Auto") mergeOutputFormat = container.toLowerCase();'''
if old in s:
    s = s.replace(old, new, 1)
if 'let videoCodec = "";' not in s:
    s = s.replace('    let mergeOutputFormat = "";\n', '    let mergeOutputFormat = "";\n    let videoCodec = "";\n', 1)
s = s.replace('        merge_output_format: mergeOutputFormat,\n        subtitles,', '        merge_output_format: mergeOutputFormat,\n        video_codec: videoCodec,\n        subtitles,', 1)
JS.write_text(s)

# Engine detection: successful probe permanently stops the retry interval.
s = COMPAT.read_text()
if 'window.__mdEngineDetected' not in s:
    s = s.replace('  const ENGINE = "http://127.0.0.1:8765";\n', '  const ENGINE = "http://127.0.0.1:8765";\n  window.__mdEngineDetected = false;\n', 1)
    s = s.replace('      window.engineBase = ENGINE;\n      window.__mdNativeEngineBase = ENGINE;', '      window.__mdEngineDetected = true;\n      window.engineBase = ENGINE;\n      window.__mdNativeEngineBase = ENGINE;', 1)
    s = s.replace('  const retryTimer = setInterval(function(){\n    lateEngineProbe();\n  }, 3000);', '  const retryTimer = setInterval(function(){\n    if (window.__mdEngineDetected) { clearInterval(retryTimer); return; }\n    lateEngineProbe();\n  }, 3000);', 1)
COMPAT.write_text(s)

# Android engine: explicit YouTube VP9 -> video ID + audio 251 -> 3s -> FFmpeg.
s = KT.read_text()
if 'jobVideoCodecs' not in s:
    s = s.replace('private val jobCookieRequests = ConcurrentHashMap<String, YoutubeDLRequest>()', 'private val jobCookieRequests = ConcurrentHashMap<String, YoutubeDLRequest>()\n    private val jobVideoCodecs = ConcurrentHashMap<String, String>()\n    private val jobFormats = ConcurrentHashMap<String, String>()\n    private val jobUrls = ConcurrentHashMap<String, String>()\n    private val ffmpegProcesses = ConcurrentHashMap<String, Process>()', 1)
s = s.replace('val container = req.optString("merge_output_format", "").trim()', 'val container = req.optString("merge_output_format", "").trim()\n            val videoCodec = req.optString("video_codec", "").trim()', 1)
s = s.replace('jobs[jobId] = dir\n            jobStates[jobId] = "running"', 'jobs[jobId] = dir\n            jobStates[jobId] = "running"\n            jobVideoCodecs[jobId] = videoCodec\n            jobFormats[jobId] = format\n            jobUrls[jobId] = url', 1)
if 'private fun runVp9Job' not in s:
    marker = '    private fun runJob(jobId: String) {\n'
    fn = '''    private fun runVp9Job(jobId: String) {
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

            val ffmpeg = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/bin/ffmpeg")
            if (!ffmpeg.exists()) throw IllegalStateException("Bundled FFmpeg executable not found: ${ffmpeg.absolutePath}")
            val output = File(dir, "${videoFile.name.substringBeforeLast('.')}.webm")
            log("FFmpeg concat started")
            log("FFmpeg command: ${ffmpeg.absolutePath} -y -i ${videoFile.absolutePath} -i ${audioFile.absolutePath} -c:v copy -c:a copy ${output.absolutePath}")
            val process = ProcessBuilder(ffmpeg.absolutePath, "-y", "-i", videoFile.absolutePath, "-i", audioFile.absolutePath, "-c:v", "copy", "-c:a", "copy", output.absolutePath)
                .redirectErrorStream(true).start()
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

'''
    if marker not in s:
        raise SystemExit('runJob marker missing')
    s = s.replace(marker, fn + marker, 1)

needle = '        try {\n            ensureEngine()\n            jobStates[jobId] = "running"'
replacement = '        try {\n            ensureEngine()\n            val codec = jobVideoCodecs[jobId]?.lowercase(Locale.US) ?: ""\n            if (isYoutubeUrl(jobUrls[jobId] ?: "") && codec.contains("vp9")) {\n                runVp9Job(jobId)\n                return\n            }\n            jobStates[jobId] = "running"'
if needle in s:
    s = s.replace(needle, replacement, 1)

s = s.replace('jobCookieRequests.remove(jobId)\n        jobStates.remove(jobId)', 'jobCookieRequests.remove(jobId)\n        jobVideoCodecs.remove(jobId)\n        jobFormats.remove(jobId)\n        jobUrls.remove(jobId)\n        ffmpegProcesses.remove(jobId)\n        jobStates.remove(jobId)', 1)
s = s.replace('YoutubeDL.getInstance().destroyProcessById(id)\n                    writeStatus', 'YoutubeDL.getInstance().destroyProcessById(id)\n                    ffmpegProcesses[id]?.destroy()\n                    writeStatus')
KT.write_text(s)
print('VP9 quality implementation applied')
