package com.ipsuman.mediadownloader

/**
 * Keeps the local yt-dlp engine alive while a foreground download service is running.
 * All app components run in the same process unless explicitly configured otherwise.
 */
object EngineHolder {
    @Volatile
    var server: LocalEngineServer? = null
}
