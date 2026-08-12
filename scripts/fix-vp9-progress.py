from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "app/src/main/java/com/ipsuman/mediadownloader/LocalEngineServer.kt"
INDEX = ROOT / "index.html"

s = ENGINE.read_text(encoding="utf-8")

# The youtubedl-android FFmpeg package is a ZIP stored as libffmpeg.zip.so.
# Its extracted payload contains usr/lib/*.so, while the executable itself
# is the native libffmpeg.so shipped in nativeLibraryDir. Android's dynamic
# linker must be given the extracted usr/lib directory through LD_LIBRARY_PATH.
old = '''    private fun resolveBundledFfmpeg(root: File): File? {
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
'''
new = '''    private fun resolveBundledFfmpeg(root: File): File? {
        // youtubedl-android stores the FFmpeg package as libffmpeg.zip.so and
        // extracts only its usr/lib payload. The executable is libffmpeg.so
        // in Android's nativeLibraryDir. It is an executable despite the .so
        // suffix; its shared FFmpeg libraries live in the extracted usr/lib.
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val executable = File(nativeDir, "libffmpeg.so")
        if (executable.isFile && executable.canRead()) return executable
        return null
    }
'''
if old not in s:
    raise SystemExit("FFmpeg resolver block not found")
s = s.replace(old, new, 1)

old = '''            val env = processBuilder.environment()
            val oldLd = env["LD_LIBRARY_PATH"]
            env["LD_LIBRARY_PATH"] = if (oldLd.isNullOrBlank()) libDir.absolutePath else libDir.absolutePath + File.pathSeparator + oldLd
            val process = processBuilder.start()
'''
new = '''            val env = processBuilder.environment()
            val oldLd = env["LD_LIBRARY_PATH"]
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val requiredLd = listOf(libDir.absolutePath, nativeDir.absolutePath)
                .plus(if (oldLd.isNullOrBlank()) emptyList() else oldLd.split(File.pathSeparator))
                .distinct()
                .joinToString(File.pathSeparator)
            env["LD_LIBRARY_PATH"] = requiredLd
            log("FFmpeg LD_LIBRARY_PATH: $requiredLd")
            val process = processBuilder.start()
'''
if old not in s:
    raise SystemExit("FFmpeg environment block not found")
s = s.replace(old, new, 1)

# Make status writes resilient if a transient cleanup removed/recreated the job directory.
old = '''    private fun writeStatus(dir: File, json: String) {
        try { File(dir, "android_status.json").writeText(json) } catch (e: Exception) { log("Could not write job status", e) }
    }
'''
new = '''    private fun writeStatus(dir: File, json: String) {
        try {
            if (!dir.exists()) dir.mkdirs()
            File(dir, "android_status.json").writeText(json)
        } catch (e: Exception) {
            log("Could not write job status", e)
        }
    }
'''
if old not in s:
    raise SystemExit("Status writer block not found")
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

print("Android FFmpeg executable/library and progress fixes applied")
