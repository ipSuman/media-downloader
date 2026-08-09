import yt_dlp


def get_version():
    return yt_dlp.version.__version__


def status():
    return {
        "ok": True,
        "ytdlp": get_version()
    }