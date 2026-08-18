/*
 * Android API compatibility bridge.
 *
 * The Android LocalEngineServer exposes the engine at the root
 * (/health, /versions, /analyze, /download, etc.), while older frontend
 * code may still use the /api prefix. Rewrite those requests locally.
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

  function setEngineOnline(data){
    window.__mdEngineDetected = true;
    window.engineBase = ENGINE;
    window.__mdNativeEngineBase = ENGINE;
    try {
      if (typeof window.setEngineStatus === "function") {
        window.setEngineStatus("🟢 Local engine connected", "online");
      }
      const yt = document.getElementById("ytVersion");
      const ff = document.getElementById("ffVersion");
      if (yt) yt.textContent = (data && data.ytdlp && data.ytdlp.installed) || "Bundled";
      if (ff) ff.textContent = (data && data.ffmpeg && data.ffmpeg.installed) || "Bundled";
    } catch (_) {}
  }

  /*
   * /versions deliberately calls the native ensureEngine() path.
   * This is the reliable initialization probe; /health is only a cheap
   * server-liveness check and must not be used as proof that yt-dlp/FFmpeg
   * have finished initializing.
   */
  async function forceInitializeEngines(source){
    try {
      if (typeof window.setEngineStatus === "function") {
        window.setEngineStatus("⏳ Initializing engines…", "offline");
      }
      console.log("Engine initialization requested", source || "probe");

      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 10 * 60 * 1000);
      const response = await originalFetch(ENGINE + "/versions", {
        method: "GET",
        cache: "no-store",
        signal: controller.signal
      });
      clearTimeout(timer);

      if (!response.ok) throw new Error("HTTP " + response.status);
      const data = await response.json();
      if (!data || !data.ytdlp || data.ytdlp.installed === "Error") {
        throw new Error((data && data.error) || "Engine initialization failed");
      }

      setEngineOnline(data);
      console.log("Engine initialization completed", data);
      return true;
    } catch (error) {
      try {
        if (typeof window.setEngineStatus === "function") {
          window.setEngineStatus("🔴 Engine initialization failed", "offline");
        }
      } catch (_) {}
      console.error("Engine initialization failed", error);
      return false;
    }
  }

  window.forceInitializeEngines = function(){
    return forceInitializeEngines("header button");
  };

  function installForceInitializeButton(){
    if (document.getElementById("mdForceEngineButton")) return true;
    const settings = document.querySelector(".settings");
    if (!settings || !settings.parentElement) return false;

    const button = document.createElement("button");
    button.id = "mdForceEngineButton";
    button.type = "button";
    button.className = "md-force-engine-button";
    button.setAttribute("aria-label", "Initialize engines");
    button.title = "Initialize engines";
    button.textContent = "⚡";

    button.addEventListener("click", async function(){
      if (button.dataset.busy === "1") return;
      button.dataset.busy = "1";
      button.disabled = true;
      button.textContent = "⏳";
      try {
        await forceInitializeEngines("header button");
      } finally {
        button.disabled = false;
        button.dataset.busy = "0";
        button.textContent = "⚡";
      }
    });

    settings.parentElement.insertBefore(button, settings);
    return true;
  }

  function installForceButtonStyle(){
    if (document.getElementById("mdForceEngineButtonStyle")) return;
    const style = document.createElement("style");
    style.id = "mdForceEngineButtonStyle";
    style.textContent = `
      .header > .md-force-engine-button{
        width:42px;
        height:42px;
        flex:0 0 42px;
        margin-left:auto;
        margin-right:8px;
        border:1px solid var(--md-outline-variant,var(--border,#293338));
        border-radius:13px;
        background:var(--md-surface-container-high,var(--card,#151b1e));
        color:var(--md-primary,var(--accent,#35c7b5));
        display:grid;
        place-items:center;
        font-size:20px;
        font-weight:800;
        line-height:1;
        box-shadow:none;
        -webkit-tap-highlight-color:transparent;
      }
      .header > .md-force-engine-button:active{
        transform:scale(.94);
      }
      .header > .md-force-engine-button:disabled{
        opacity:.65;
      }
    `;
    document.head.appendChild(style);
  }

  async function lateEngineProbe(){
    return forceInitializeEngines("automatic startup probe");
  }

  function install(){
    installForceButtonStyle();
    installForceInitializeButton();
  }

  setTimeout(install, 0);
  setTimeout(install, 250);
  setTimeout(install, 1000);

  /* Initialize automatically, but also expose the same operation through
   * the header button so the user can explicitly retry at any time. */
  setTimeout(function(){
    try {
      if (typeof window.discoverEngine === "function") window.discoverEngine();
    } catch (_) {}
    lateEngineProbe();
  }, 100);

  const retryTimer = setInterval(function(){
    if (window.__mdEngineDetected) { clearInterval(retryTimer); return; }
    lateEngineProbe();
  }, 5000);

  setTimeout(function(){ clearInterval(retryTimer); }, 60000);
})();
