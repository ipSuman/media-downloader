from pathlib import Path

p = Path("app/src/main/java/com/ipsuman/mediadownloader/LocalEngineServer.kt")
s = p.read_text()
old = '''    private fun addYoutubePoToken(request: YoutubeDLRequest) {
        try {
            val token = poTokenProvider.getMwebGvsToken()
            if (!token.isNullOrBlank()) {
                request.addOption(
                    "--extractor-args",
                    "youtube:player-client=default,mweb;po_token=mweb.gvs+$token"
                )
                request.addOption("--extractor-args", "youtube:pot_trace=true")
                log("Generated and attached mweb GVS PO Token for YouTube")
            } else {
                log("PO Token provider unavailable; continuing without PO Token: ${poTokenProvider.lastError() ?: "unknown"}")
            }
        } catch (e: Exception) {
            log("PO Token generation failed; continuing without PO Token", e)
        }
    }
'''
new = '''    private fun addYoutubePoToken(request: YoutubeDLRequest) {
        try {
            val token = poTokenProvider.getMwebGvsToken()
            val visitorData = poTokenProvider.visitorData()
            if (!visitorData.isNullOrBlank()) {
                request.addOption(
                    "--extractor-args",
                    "youtube:visitor_data=$visitorData"
                )
                log("Attached Innertube visitorData to YouTube request")
            }
            if (!token.isNullOrBlank()) {
                request.addOption(
                    "--extractor-args",
                    "youtube:player-client=default,mweb;po_token=mweb.gvs+$token"
                )
                request.addOption("--extractor-args", "youtube:pot_trace=true")
                log("Generated and attached mweb GVS PO Token for YouTube")
            } else {
                log("PO Token provider unavailable; continuing without PO Token: ${poTokenProvider.lastError() ?: "unknown"}")
            }
        } catch (e: Exception) {
            log("PO Token generation failed; continuing without PO Token", e)
        }
    }
'''
if old not in s:
    raise SystemExit("target block not found; source may already be patched")
p.write_text(s.replace(old, new, 1))
print("patched LocalEngineServer.kt")
