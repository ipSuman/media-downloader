from pathlib import Path

p = Path('app/src/main/java/com/ipsuman/mediadownloader/LocalEngineServer.kt')
s = p.read_text()

old = '''    private fun resolveBundledFfmpeg(root: File): File? {
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
new = '''    private fun resolveBundledFfmpeg(root: File): File? {
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
if old not in s:
    raise SystemExit('FFmpeg resolver block not found')
s = s.replace(old, new, 1)

old = '''            ffmpeg.setExecutable(true, false)
            val output = File(dir, "${videoFile.name.substringBeforeLast('.')}.webm")
            val libDir = ffmpeg.parentFile ?: File(context.applicationInfo.nativeLibraryDir)
'''
new = '''            ffmpeg.setExecutable(true, false)
            // Keep the merged output separate from the downloaded video input.
            val output = File(dir, "merged.webm")
            val extractedLibDir = File(ffmpegRoot, "usr/lib")
            val libDir = if (extractedLibDir.isDirectory) extractedLibDir else (ffmpeg.parentFile ?: ffmpegRoot)
'''
if old not in s:
    raise SystemExit('FFmpeg output block not found')
s = s.replace(old, new, 1)

old = '''            log("FFmpeg executable resolved: ${ffmpeg.absolutePath}")
            log("FFmpeg executable exists=${ffmpeg.exists()} executable=${ffmpeg.canExecute()} size=${ffmpeg.length()}")
            log("FFmpeg concat started")
'''
new = '''            log("FFmpeg executable resolved: ${ffmpeg.absolutePath}")
            log("FFmpeg executable exists=${ffmpeg.exists()} executable=${ffmpeg.canExecute()} size=${ffmpeg.length()}")
            log("FFmpeg library directory: ${libDir.absolutePath}")
            log("FFmpeg library directory exists=${libDir.isDirectory}")
            if (libDir.isDirectory) {
                val libs = libDir.listFiles()?.filter { it.isFile && it.name.endsWith(".so") }?.take(30)?.joinToString(",") { it.name } ?: "<none>"
                log("FFmpeg bundled libraries: $libs")
            }
            log("FFmpeg concat started")
'''
if old not in s:
    raise SystemExit('FFmpeg logging block not found')
s = s.replace(old, new, 1)

p.write_text(s)
print('FFmpeg runtime fix applied')
