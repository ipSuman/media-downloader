import json
import os
import re

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


def _write_progress(progress_path, **values):
    data = {"status": "working"}
    data.update(values)
    try:
        tmp = progress_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as handle:
            json.dump(data, handle, ensure_ascii=False)
        os.replace(tmp, progress_path)
    except Exception:
        pass


def _safe_job_id(job_id):
    return re.sub(r"[^A-Za-z0-9_-]", "", str(job_id)) or "job"


def download(
    url,
    output_dir,
    format_selector="",
    audio_only=False,
    job_id="job",
    audio_format="",
    audio_quality="",
    merge_output_format="",
):
    """Download according to the selector supplied by the web UI.

    No silent quality downgrade is performed.  If a selected format requires
    FFmpeg (for example video+audio merging or MP3 conversion), yt-dlp's
    error is returned to the Android job log instead of substituting a lower
    quality combined stream.
    """
    if not isinstance(url, str) or not url.strip():
        raise ValueError("URL is required")

    output_dir = os.path.abspath(output_dir)
    os.makedirs(output_dir, exist_ok=True)
    job_id = _safe_job_id(job_id)
    progress_path = os.path.join(output_dir, "progress.json")

    _write_progress(progress_path, status="starting", percent=0)

    selector = str(format_selector or "").strip()
    if not selector:
        selector = "bestaudio/best" if audio_only else "bv*+ba/b"

    # MP3 is a conversion target, not a normal YouTube source stream.
    # Refuse it explicitly until the optional FFmpeg engine is installed.
    if audio_only and str(audio_format).strip().lower() == "mp3":
        raise RuntimeError("MP3 conversion requires FFmpeg. Choose M4A/Opus/FLAC for a direct audio download.")

    def hook(data):
        state = data.get("status")
        if state == "downloading":
            total = data.get("total_bytes") or data.get("total_bytes_estimate")
            current = data.get("downloaded_bytes") or 0
            percent = (current * 100.0 / total) if total else None
            speed = data.get("speed")
            eta = data.get("eta")
            _write_progress(
                progress_path,
                status="downloading",
                percent=percent,
                speed=f"{speed / 1024 / 1024:.2f} MB/s" if speed else None,
                eta=eta,
            )
        elif state == "finished":
            _write_progress(progress_path, status="processing", percent=100)

    options = {
        "format": selector,
        "outtmpl": os.path.join(output_dir, "%(title)s [%(id)s].%(ext)s"),
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "progress_hooks": [hook],
        "retries": 3,
        "continuedl": True,
        "overwrites": False,
    }

    if merge_output_format and merge_output_format != "auto":
        options["merge_output_format"] = merge_output_format

    if audio_only and audio_quality and str(audio_quality).lower() != "best":
        match = re.search(r"(\d+)", str(audio_quality))
        if match:
            options["format_sort"] = [f"abr:{match.group(1)}"]

    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(url.strip(), download=True)
            prepared = ydl.prepare_filename(info)

        candidates = []
        if os.path.isfile(prepared):
            candidates.append(prepared)
        base, _ = os.path.splitext(prepared)
        for name in os.listdir(output_dir):
            path = os.path.join(output_dir, name)
            if os.path.isfile(path) and (
                name == os.path.basename(prepared)
                or name.startswith(os.path.basename(base))
            ):
                candidates.append(path)

        candidates = list(dict.fromkeys(candidates))
        if not candidates:
            raise FileNotFoundError("yt-dlp completed but no output file was found")

        output_file = max(candidates, key=os.path.getmtime)
        size = os.path.getsize(output_file)
        _write_progress(
            progress_path,
            status="completed",
            percent=100,
            size=size,
            filename=os.path.basename(output_file),
        )
        return {
            "ok": True,
            "job_id": job_id,
            "path": output_file,
            "filename": os.path.basename(output_file),
            "size": size,
            "format": selector,
            "audio_only": audio_only,
            "audio_format": audio_format,
            "audio_quality": audio_quality,
        }
    except Exception as exc:
        _write_progress(progress_path, status="failed", percent=0, error=str(exc))
        raise


def download_json(
    url,
    output_dir,
    format_selector="",
    audio_only=False,
    job_id="job",
    audio_format="",
    audio_quality="",
    merge_output_format="",
):
    return json.dumps(
        download(
            url,
            output_dir,
            format_selector,
            audio_only,
            job_id,
            audio_format,
            audio_quality,
            merge_output_format,
        ),
        ensure_ascii=False,
    )
