from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "app/src/main/java/com/ipsuman/mediadownloader/LocalEngineServer.kt"
INDEX = ROOT / "index.html"

s = ENGINE.read_text(encoding="utf-8")
old = '''    private fun resolveBundledFfmpeg(root: File): File? {
        if (!root.exists()) return null
        val preferred = listOf(
            File(root, "usr/bin/ffmpeg"),
            File(root, "usr/lib/ffmpeg"),
            File(root, "ffmpeg")
        )
        preferred.firstOrNull { it.isFile }?.let { return it }
        return root.walkTopDown().firstOrNull { it.isFile && it.name == "ffmpeg" }
    }
'''
new = '''    private fun resolveBundledFfmpeg(root: File): File? {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val preferred = listOf(
            File(nativeDir, "libffmpeg.so"),
            File(root, "usr/bin/ffmpeg"),
            File(root, "usr/lib/ffmpeg"),
            File(root, "ffmpeg")
        )
        preferred.firstOrNull { it.isFile && it.canRead() }?.let { return it }
        if (root.exists()) {
            root.walkTopDown().firstOrNull { it.isFile && it.name == "ffmpeg" }?.let { return it }
        }
        return nativeDir.listFiles()?.firstOrNull { it.isFile && it.name.equals("libffmpeg.so", true) }
    }
'''
if old in s:
    s = s.replace(old, new, 1)

old = '''            val ffmpeg = resolveBundledFfmpeg(ffmpegRoot)
            if (ffmpeg == null) {
                val entries = ffmpegRoot.walkTopDown().take(80).joinToString(",") { it.relativeTo(ffmpegRoot).path }
                throw IllegalStateException("Bundled FFmpeg executable not found under ${ffmpegRoot.absolutePath}; entries=$entries")
            }
            ffmpeg.setExecutable(true, false)
            val output = File(dir, "${videoFile.name.substringBeforeLast('.')}.webm")
            val libDir = File(ffmpegRoot, "usr/lib")
'''
new = '''            val ffmpeg = resolveBundledFfmpeg(ffmpegRoot)
            if (ffmpeg == null) {
                val entries = if (ffmpegRoot.exists()) ffmpegRoot.walkTopDown().take(80).joinToString(",") { it.relativeTo(ffmpegRoot).path } else "<ffmpeg package root missing>"
                val nativeEntries = File(context.applicationInfo.nativeLibraryDir).listFiles()?.joinToString(",") { it.name } ?: "<none>"
                throw IllegalStateException("Bundled FFmpeg executable not found; root=${ffmpegRoot.absolutePath}; entries=$entries; nativeLibs=$nativeEntries")
            }
            ffmpeg.setExecutable(true, false)
            val output = File(dir, "${videoFile.name.substringBeforeLast('.')}.webm")
            val libDir = ffmpeg.parentFile ?: File(context.applicationInfo.nativeLibraryDir)
'''
if old not in s:
    raise SystemExit("FFmpeg process block not found")
s = s.replace(old, new, 1)

old = '''            log("FFmpeg executable resolved: ${ffmpeg.absolutePath}")
            log("FFmpeg concat started")
'''
new = '''            log("FFmpeg executable resolved: ${ffmpeg.absolutePath}")
            log("FFmpeg executable exists=${ffmpeg.exists()} executable=${ffmpeg.canExecute()} size=${ffmpeg.length()}")
            log("FFmpeg concat started")
'''
if old not in s:
    raise SystemExit("FFmpeg logging block not found")
s = s.replace(old, new, 1)

old = '''            put("percent", progress.toInt().coerceIn(0, 100))
'''
new = '''            val safeProgress = progress.toInt().coerceIn(0, 100)
            put("percent", if (state == "completed") 100 else safeProgress.coerceAtMost(99))
'''
if old not in s:
    raise SystemExit("Progress writer block not found")
s = s.replace(old, new, 1)
ENGINE.write_text(s, encoding="utf-8")

j = INDEX.read_text(encoding="utf-8")
old = '''    if(data.percent != null){

      bar.style.width =
        `${Math.max(
          0,
          Math.min(
            100,
            Number(data.percent)
          )
        )}%`;

    }


    status.textContent =
      `${data.status || "working"}`
      + (data.speed
        ? ` • ${data.speed}`
        : "")
      + (data.eta
        ? ` • ETA ${data.eta}`
        : "");
'''
new = '''    const terminal =
      data.status === "completed" ||
      data.status === "failed" ||
      data.status === "cancelled";

    const rawPercent = Number(data.percent);
    const percent = Number.isFinite(rawPercent)
      ? Math.max(0, Math.min(terminal && data.status === "completed" ? 100 : 99, rawPercent))
      : 0;

    bar.style.width = `${percent}%`;

    status.textContent =
      `${terminal && data.status === "completed" ? "completed" : (data.status || "working")} • ${percent}%`
      + (data.speed ? ` • ${data.speed}` : "")
      + (data.eta ? ` • ETA ${data.eta}` : "");
'''
if old not in j:
    raise SystemExit("Frontend progress block not found")
j = j.replace(old, new, 1)
INDEX.write_text(j, encoding="utf-8")

print("VP9 FFmpeg and progress fixes applied")
