from pathlib import Path

p = Path("app/src/main/java/com/ipsuman/mediadownloader/LocalEngineServer.kt")
s = p.read_text()
old = '''                if (current != "running") {
                    return json(
                        Response.Status.CONFLICT,
                        """{"ok":false,"error":"Job is not running","status":"$current"}"""
                    )
                }
'''
new = '''                // A YouTube cookie retry is still an active download. It must remain
                // controllable while yt-dlp is between the first request and retry.
                if (current != "running" && current != "retrying") {
                    return json(
                        Response.Status.CONFLICT,
                        """{"ok":false,"error":"Job is not running","status":"$current"}"""
                    )
                }
'''
if old not in s:
    raise SystemExit("pause state guard not found")
s = s.replace(old, new, 1)
old = '''                log("PO Token and visitorData ready before cookie retry")
                YoutubeDL.getInstance().execute(retryRequest, jobId) { progress, eta, line ->
'''
new = '''                log("PO Token and visitorData ready before cookie retry")
                // The user may have paused/cancelled while authentication was being prepared.
                // Never start the retry process after a control request has changed the state.
                if (jobStates[jobId] != "retrying") {
                    log("Cookie retry suppressed for $jobId: state=${jobStates[jobId]}")
                    return
                }
                YoutubeDL.getInstance().execute(retryRequest, jobId) { progress, eta, line ->
'''
if old not in s:
    raise SystemExit("retry execute marker not found")
s = s.replace(old, new, 1)
p.write_text(s)
