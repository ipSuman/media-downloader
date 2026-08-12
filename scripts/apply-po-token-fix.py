from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / 'app/src/main/java/com/ipsuman/mediadownloader/LocalEngineServer.kt'
PROVIDER = ROOT / 'app/src/main/java/com/ipsuman/mediadownloader/YoutubePoTokenProvider.kt'

text = SERVER.read_text()
original = text

field_marker = '    private val preferences = context.getSharedPreferences("media_downloader", Context.MODE_PRIVATE)\n'
if 'private val poTokenProvider = YoutubePoTokenProvider(context)' not in text:
    if field_marker not in text:
        raise SystemExit('PO-token field insertion point not found')
    text = text.replace(
        field_marker,
        field_marker + '    private val poTokenProvider = YoutubePoTokenProvider(context)\n',
        1,
    )

helper_marker = '    private fun cors(r: Response): Response {\n'
helper = '''    private fun addYoutubePoToken(request: YoutubeDLRequest) {\n        try {\n            val token = poTokenProvider.getMwebGvsToken()\n            if (!token.isNullOrBlank()) {\n                request.addOption(\n                    "--extractor-args",\n                    "youtube:player-client=default,mweb;po_token=mweb.gvs+$token"\n                )\n                request.addOption("--extractor-args", "youtube:pot_trace=true")\n                log("Generated and attached mweb GVS PO Token for YouTube")\n            } else {\n                log("PO Token provider unavailable; continuing without PO Token: ${poTokenProvider.lastError() ?: "unknown"}")\n            }\n        } catch (e: Exception) {\n            log("PO Token generation failed; continuing without PO Token", e)\n        }\n    }\n\n'''
if 'private fun addYoutubePoToken(request: YoutubeDLRequest)' not in text:
    if helper_marker not in text:
        raise SystemExit('PO-token helper insertion point not found')
    text = text.replace(helper_marker, helper + helper_marker, 1)

old_auth = '''    private fun addAuthenticationOptions(request: YoutubeDLRequest) {\n        val cookies = youtubeCookiesFile()\n        if (cookies != null) {\n            request.addOption("--cookies", cookies.absolutePath)\n            log("Using imported YouTube cookies for yt-dlp authentication")\n        }\n    }\n'''
new_auth = '''    private fun addAuthenticationOptions(request: YoutubeDLRequest) {\n        val cookies = youtubeCookiesFile()\n        if (cookies != null) {\n            request.addOption("--cookies", cookies.absolutePath)\n            log("Using imported YouTube cookies for yt-dlp authentication")\n        }\n        addYoutubePoToken(request)\n    }\n'''
if old_auth not in text:
    raise SystemExit('Expected addAuthenticationOptions block not found')
text = text.replace(old_auth, new_auth, 1)

old_build = '            if (useCookies) addAuthenticationOptions(this)\n            addOption("-o", File(dir, "%(title)s [%(id)s].%(ext)s").absolutePath)\n'
new_build = '            if (useCookies) addAuthenticationOptions(this) else addYoutubePoToken(this)\n            addOption("-o", File(dir, "%(title)s [%(id)s].%(ext)s").absolutePath)\n'
if old_build not in text:
    raise SystemExit('Expected buildRequest auth block not found')
text = text.replace(old_build, new_build, 1)

old_part = '''                return YoutubeDLRequest(url).apply {\n                    if (cookies) addAuthenticationOptions(this)\n                    addOption("-o", output.absolutePath)\n'''
new_part = '''                return YoutubeDLRequest(url).apply {\n                    if (cookies) addAuthenticationOptions(this) else addYoutubePoToken(this)\n                    addOption("-o", output.absolutePath)\n'''
if old_part not in text:
    raise SystemExit('Expected VP9 partRequest auth block not found')
text = text.replace(old_part, new_part, 1)

if text == original:
    raise SystemExit('PO-token patch made no changes')

SERVER.write_text(text)
PROVIDER.parent.mkdir(parents=True, exist_ok=True)
if not PROVIDER.exists():
    raise SystemExit('Provider file must be created by the workflow tree commit')
print('Applied YouTube PO-token integration to LocalEngineServer.kt')
