import json

import yt_dlp


def get_version():
    return yt_dlp.version.__version__


def status():
    return {
        "ok": True,
        "ytdlp": get_version()
    }


def analyze(url):
    """Extract metadata and available formats without downloading media."""
    if not isinstance(url, str) or not url.strip():
        raise ValueError("URL is required")

    options = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
    }

    with yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info(url.strip(), download=False)

    formats = []
    for fmt in info.get("formats") or []:
        formats.append({
            "format_id": fmt.get("format_id"),
            "ext": fmt.get("ext"),
            "format_note": fmt.get("format_note"),
            "height": fmt.get("height"),
            "width": fmt.get("width"),
            "fps": fmt.get("fps"),
            "vcodec": fmt.get("vcodec"),
            "acodec": fmt.get("acodec"),
            "abr": fmt.get("abr"),
            "vbr": fmt.get("vbr"),
            "tbr": fmt.get("tbr"),
            "filesize": fmt.get("filesize"),
            "filesize_approx": fmt.get("filesize_approx"),
            "protocol": fmt.get("protocol"),
        })

    return {
        "ok": True,
        "id": info.get("id"),
        "title": info.get("title"),
        "uploader": info.get("uploader") or info.get("channel"),
        "channel": info.get("channel"),
        "duration": info.get("duration"),
        "thumbnail": info.get("thumbnail"),
        "webpage_url": info.get("webpage_url"),
        "extractor": info.get("extractor_key") or info.get("extractor"),
        "is_live": info.get("is_live"),
        "formats": formats,
    }


def analyze_json(url):
    return json.dumps(analyze(url), ensure_ascii=False)
