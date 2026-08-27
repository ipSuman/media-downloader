#!/usr/bin/env python3
"""Make the final YouTube fallback genuinely cookie-only."""
from pathlib import Path

p = Path(__file__).resolve().parents[1] / "app/src/main/java/com/ipsuman/mediadownloader/LocalEngineServer.kt"
s = p.read_text()

def once(old, new, label):
    global s
    if new in s:
        return
    n = s.count(old)
    if n != 1:
        raise SystemExit(f"{label}: expected 1 match, found {n}")
    s = s.replace(old, new, 1)

once(
    'container: String, useCookies: Boolean = true): YoutubeDLRequest {',
    'container: String, useCookies: Boolean = true, cookieOnly: Boolean = false): YoutubeDLRequest {',
    "buildRequest signature",
)
once(
    '            if (useCookies) addAuthenticationOptions(this) else addYoutubePoToken(this)',
    '            if (cookieOnly) addCookieOnlyAuthentication(this) else if (useCookies) addAuthenticationOptions(this) else addYoutubePoToken(this)',
    "cookie-only selection",
)
once(
    'buildRequest(jobId, url, format, start, end, audioOnly, audioFormat, audioQuality, container, useCookies = true) };',
    'buildRequest(jobId, url, format, start, end, audioOnly, audioFormat, audioQuality, container, useCookies = true, cookieOnly = true) };',
    "cookie fallback builder",
)
p.write_text(s)
print("OK: final YouTube fallback is cookie-only")
