Media Downloader — Local Engine API

Purpose

The HTML frontend communicates with a local engine through HTTP.

The frontend must not depend directly on Python, Android, yt-dlp, or FFmpeg.

The local engine is responsible for translating API requests into yt-dlp and FFmpeg operations.

---

Base URL

http://127.0.0.1:<PORT>/api

The port may be different on Ubuntu and Android.

The frontend should discover the engine rather than hard-code a fixed port.

---

1. Engine Status

GET "/status"

Returns whether the local engine is available.

Example response:

{
  "ok": true,
  "engine": "media-downloader",
  "platform": "android",
  "version": "0.1.0"
}

---

2. Engine Versions

GET "/versions"

Returns installed engine versions.

Example:

{
  "ytdlp": {
    "installed": "unknown",
    "latest": "unknown"
  },
  "ffmpeg": {
    "installed": "unknown",
    "latest": "unknown"
  }
}

---

3. Analyze URL

POST "/analyze"

Request:

{
  "url": "https://example.com/video"
}

Response:

{
  "ok": true,
  "title": "Example Video",
  "uploader": "Example Channel",
  "duration": 1234,
  "thumbnail": "",
  "formats": []
}

The "formats" array will contain the formats returned by yt-dlp.

---

4. Start Download

POST "/download"

Example:

{
  "url": "https://example.com/video",
  "format": "bv*+ba/b",
  "start": "00:01:30",
  "end": "00:03:45",
  "audio_only": false,
  "audio_format": "",
  "subtitles": false,
  "thumbnail": false,
  "metadata": false
}

When both `start` and `end` are supplied, the Android engine translates them to yt-dlp's download-section operation:

`--download-sections "*START-END"`

and enables `--force-keyframes-at-cuts` for accurate output cuts.

If either value is empty, the full media is downloaded.

Response:

{
  "ok": true,
  "job_id": "unique-download-id"
}

---

5. Download Progress

GET "/download/{job_id}"

Example:

{
  "job_id": "unique-download-id",
  "status": "downloading",
  "percent": 47.2,
  "speed": "8.4 MiB/s",
  "eta": "00:31",
  "filename": "Example Video.mp4"
}

Possible statuses:

queued
analyzing
downloading
processing
completed
failed
cancelled

---

6. Cancel Download

POST "/download/{job_id}/cancel"

Response:

{
  "ok": true
}

---

7. Update Check

GET "/updates"

Checks official release information for yt-dlp and FFmpeg.

Example:

{
  "ytdlp": {
    "update_available": false,
    "installed": "",
    "latest": ""
  },
  "ffmpeg": {
    "update_available": false,
    "installed": "",
    "latest": ""
  }
}

---

8. Update Engine

POST "/updates/apply"

Request:

{
  "ytdlp": true,
  "ffmpeg": true
}

The local engine downloads compatible versions, verifies them, installs them, and reports the result.

---

Design Rule

The frontend must never assume:

- Linux
- Android
- Python
- a particular yt-dlp version
- a particular FFmpeg version
- a fixed executable location

Only the API contract is shared.

This allows:

HTML frontend
      │
      ├── Ubuntu engine
      │      ├── yt-dlp
      │      └── FFmpeg
      │
      └── Android engine
             ├── yt-dlp
             └── FFmpeg

The same frontend can therefore run on both platforms.