from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / 'app/src/main/java/com/ipsuman/mediadownloader/LocalEngineServer.kt'
PROVIDER = ROOT / 'app/src/main/java/com/ipsuman/mediadownloader/YoutubePoTokenProvider.kt'

text = SERVER.read_text()
changed = False

field_marker = '    private val preferences = context.getSharedPreferences("media_downloader", Context.MODE_PRIVATE)\n'
if 'private val poTokenProvider = YoutubePoTokenProvider(context)' not in text:
    if field_marker not in text:
        raise SystemExit('PO-token field insertion point not found')
    text = text.replace(field_marker, field_marker + '    private val poTokenProvider = YoutubePoTokenProvider(context)\n', 1)
    changed = True

helper_marker = '    private fun cors(r: Response): Response {\n'
helper = '''    private fun addYoutubePoToken(request: YoutubeDLRequest) {\n        try {\n            val token = poTokenProvider.getMwebGvsToken()\n            if (!token.isNullOrBlank()) {\n                request.addOption(\n                    "--extractor-args",\n                    "youtube:player-client=default,mweb;po_token=mweb.gvs+$token"\n                )\n                request.addOption("--extractor-args", "youtube:pot_trace=true")\n                log("Generated and attached mweb GVS PO Token for YouTube")\n            } else {\n                log("PO Token provider unavailable; continuing without PO Token: ${poTokenProvider.lastError() ?: "unknown"}")\n            }\n        } catch (e: Exception) {\n            log("PO Token generation failed; continuing without PO Token", e)\n        }\n    }\n\n'''
if 'private fun addYoutubePoToken(request: YoutubeDLRequest)' not in text:
    if helper_marker not in text:
        raise SystemExit('PO-token helper insertion point not found')
    text = text.replace(helper_marker, helper + helper_marker, 1)
    changed = True

old_auth = '''    private fun addAuthenticationOptions(request: YoutubeDLRequest) {\n        val cookies = youtubeCookiesFile()\n        if (cookies != null) {\n            request.addOption("--cookies", cookies.absolutePath)\n            log("Using imported YouTube cookies for yt-dlp authentication")\n        }\n    }\n'''
new_auth = '''    private fun addAuthenticationOptions(request: YoutubeDLRequest) {\n        val cookies = youtubeCookiesFile()\n        if (cookies != null) {\n            request.addOption("--cookies", cookies.absolutePath)\n            log("Using imported YouTube cookies for yt-dlp authentication")\n        }\n        addYoutubePoToken(request)\n    }\n'''
if old_auth in text:
    text = text.replace(old_auth, new_auth, 1)
    changed = True

old_build = '            if (useCookies) addAuthenticationOptions(this)\n            addOption("-o", File(dir, "%(title)s [%(id)s].%(ext)s").absolutePath)\n'
new_build = '            if (useCookies) addAuthenticationOptions(this) else addYoutubePoToken(this)\n            addOption("-o", File(dir, "%(title)s [%(id)s].%(ext)s").absolutePath)\n'
if old_build in text:
    text = text.replace(old_build, new_build, 1)
    changed = True

old_part = '''                return YoutubeDLRequest(url).apply {\n                    if (cookies) addAuthenticationOptions(this)\n                    addOption("-o", output.absolutePath)\n'''
new_part = '''                return YoutubeDLRequest(url).apply {\n                    if (cookies) addAuthenticationOptions(this) else addYoutubePoToken(this)\n                    addOption("-o", output.absolutePath)\n'''
if old_part in text:
    text = text.replace(old_part, new_part, 1)
    changed = True

# apply-vp9-fix.py is intentionally re-run by CI and is not fully idempotent.
# Collapse the duplicate assignments it can create before compiling.
duplicated = '''            val videoCodec = req.optString("video_codec", "").trim()\n            val videoCodec = req.optString("video_codec", "").trim()\n'''
single = '''            val videoCodec = req.optString("video_codec", "").trim()\n'''
if duplicated in text:
    text = text.replace(duplicated, single, 1)
    changed = True

duplicated_jobs = '''            jobVideoCodecs[jobId] = videoCodec\n            jobFormats[jobId] = format\n            jobUrls[jobId] = url\n            jobVideoCodecs[jobId] = videoCodec\n            jobFormats[jobId] = format\n            jobUrls[jobId] = url\n'''
single_jobs = '''            jobVideoCodecs[jobId] = videoCodec\n            jobFormats[jobId] = format\n            jobUrls[jobId] = url\n'''
if duplicated_jobs in text:
    text = text.replace(duplicated_jobs, single_jobs, 1)
    changed = True

if changed:
    SERVER.write_text(text)
    print('Applied/normalized YouTube PO-token integration')
else:
    print('YouTube PO-token integration already clean; nothing to change')

if not PROVIDER.exists():
    raise SystemExit('YoutubePoTokenProvider.kt is missing')
