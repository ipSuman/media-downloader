/*
 * Android API compatibility bridge.
 *
 * The Android LocalEngineServer exposes the engine at the root
 * (/health, /analyze, /download, etc.), while older frontend code
 * may still use the /api prefix. Rewrite those requests locally.
 */
(function(){
  if (window.__mdApiCompatInstalled) return;
  window.__mdApiCompatInstalled = true;

  const originalFetch = window.fetch.bind(window);
  const ENGINE = "http://127.0.0.1:8765";
  window.__mdEngineDetected = false;

  function rewrite(url){
    try {
      const u = new URL(String(url), window.location.href);
      if ((u.hostname === "127.0.0.1" || u.hostname === "localhost") &&
          u.pathname.startsWith("/api/")) {
        u.pathname = u.pathname.substring(4);
        return u.toString();
      }
    } catch (_) {}
    return url;
  }

  window.fetch = function(input, init){
    if (typeof input === "string") {
      return originalFetch(rewrite(input), init);
    }
    if (input && input.url) {
      const rewritten = rewrite(input.url);
      if (rewritten !== input.url) {
        try { return originalFetch(new Request(rewritten, input), init); } catch (_) {}
      }
    }
    return originalFetch(input, init);
  };

  /*
   * Keep probing after the initial WebView discovery attempt.
   * The native server's first /health call can take longer while the
   * bundled yt-dlp engine initializes. The old 2.5s one-shot probe could
   * therefore report OFFLINE even though the server was starting normally.
   */
  async function lateEngineProbe(){
    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 12000);
      const response = await originalFetch(ENGINE + "/health", {
        method: "GET",
        cache: "no-store",
        signal: controller.signal
      });
      clearTimeout(timer);
      if (!response.ok) return;
      const data = await response.json();
      if (!data || !data.ytdlp) return;

      window.__mdEngineDetected = true;
      window.engineBase = ENGINE;
      window.__mdNativeEngineBase = ENGINE;

      try {
        if (typeof window.setEngineStatus === "function") {
          window.setEngineStatus("🟢 Local engine connected", "online");
        }
        const yt = document.getElementById("ytVersion");
        const ff = document.getElementById("ffVersion");
        if (yt) yt.textContent = data.ytdlp.installed || "Bundled";
        if (ff) ff.textContent = (data.ffmpeg && data.ffmpeg.installed) || "Bundled";
      } catch (_) {}
    } catch (_) {
      // MainActivity's discovery code owns the visible offline state.
      // Keep retrying rather than permanently declaring the engine dead.
    }
  }

  setTimeout(function(){
    try {
      if (typeof window.discoverEngine === "function") window.discoverEngine();
    } catch (_) {}
    lateEngineProbe();
  }, 100);

  const retryTimer = setInterval(function(){
    if (window.__mdEngineDetected) { clearInterval(retryTimer); return; }
    lateEngineProbe();
  }, 3000);

  setTimeout(function(){ clearInterval(retryTimer); }, 30000);
})();
