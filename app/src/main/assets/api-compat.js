/*
 * Android API compatibility bridge.
 *
 * The current Android LocalEngineServer exposes the engine at the root
 * (for example /health and /analyze), while older frontend builds use
 * the documented /api prefix. Keep the frontend backward-compatible by
 * transparently removing /api for requests sent to the local engine.
 */
(function(){
  if (window.__mdApiCompatInstalled) return;
  window.__mdApiCompatInstalled = true;

  const originalFetch = window.fetch.bind(window);

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
        try {
          return originalFetch(new Request(rewritten, input), init);
        } catch (_) {}
      }
    }
    return originalFetch(input, init);
  };

  // The page's first discovery may have happened before this bridge was
  // injected. Re-run it now so the engine status changes immediately.
  setTimeout(function(){
    try {
      if (typeof window.discoverEngine === "function") window.discoverEngine();
    } catch (_) {}
  }, 50);
})();
