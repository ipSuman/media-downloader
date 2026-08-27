/*
 * Android API compatibility bridge.
 * Also records the analyzed best audio-only format and binds it to a
 * user-selected video format before the native /download request is sent.
 */
(function(){
  if (window.__mdApiCompatInstalled) return;
  window.__mdApiCompatInstalled = true;

  const originalFetch = window.fetch.bind(window);
  const ENGINE = "http://127.0.0.1:8765";
  const bestAudioByUrl = new Map();
  window.__mdEngineDetected = false;

  function rewrite(url){
    try {
      const u = new URL(String(url), window.location.href);
      if ((u.hostname === "127.0.0.1" || u.hostname === "localhost") && u.pathname.startsWith("/api/")) {
        u.pathname = u.pathname.substring(4);
        return u.toString();
      }
    } catch (_) {}
    return url;
  }

  function isPath(url, path){
    try { return new URL(String(url), window.location.href).pathname === path; } catch (_) { return false; }
  }

  function findBestAudio(formats){
    const candidates = (formats || []).filter(f => {
      const id = String(f?.format_id ?? "");
      const acodec = String(f?.acodec ?? "none").toLowerCase();
      const vcodec = String(f?.vcodec ?? "none").toLowerCase();
      return id && acodec !== "none" && acodec !== "" && (vcodec === "none" || vcodec === "" || vcodec === "unknown");
    });
    candidates.sort((a,b) => Number(b.abr ?? b.tbr ?? 0) - Number(a.abr ?? a.tbr ?? 0));
    return candidates.length ? String(candidates[0].format_id) : "";
  }

  async function fetchWithHardening(input, init){
    let url = typeof input === "string" ? rewrite(input) : (input && input.url ? rewrite(input.url) : input);
    let requestInit = init;
    const method = String(init?.method || (input && input.method) || "GET").toUpperCase();

    if (method === "POST" && isPath(url, "/download")) {
      try {
        const raw = typeof init?.body === "string" ? init.body : "";
        if (raw) {
          const payload = JSON.parse(raw);
          if (!payload.audio_only && /^\d+$/.test(String(payload.format || ""))) {
            const audioId = bestAudioByUrl.get(String(payload.url || ""));
            if (audioId) {
              payload.format = `${payload.format}+${audioId}`;
              console.log("Media Downloader: bound selected video to analyzed best audio", { videoId: payload.format.split("+")[0], audioId });
            } else {
              console.warn("Media Downloader: best audio ID unavailable for selected video; download not rewritten", payload.url);
            }
          }
          if (window.Android && typeof window.Android.getDownloadFolderName === "function") {
            payload.destination_hint = String(window.Android.getDownloadFolderName() || "");
          }
          requestInit = Object.assign({}, init, { body: JSON.stringify(payload) });
        }
      } catch (error) { console.warn("Media Downloader: download request hardening failed", error); }
    }

    const response = await originalFetch(url, requestInit);

    if (method === "POST" && isPath(url, "/analyze") && response.ok) {
      try {
        const data = await response.clone().json();
        const audioId = findBestAudio(data.formats);
        if (audioId) {
          const key = String(data.webpage_url || data.url || "");
          if (key) bestAudioByUrl.set(key, audioId);
          if (data.id) window.__mdBestAudioId = audioId;
          console.log("Media Downloader: analyzed best audio ID", { audioId, url: key });
        }
      } catch (error) { console.warn("Media Downloader: could not record best audio ID", error); }
    }
    return response;
  }

  window.fetch = fetchWithHardening;

  function setEngineOnline(data){
    window.__mdEngineDetected = true;
    window.engineBase = ENGINE;
    window.__mdNativeEngineBase = ENGINE;
    try {
      if (typeof window.setEngineStatus === "function") window.setEngineStatus("🟢 Local engine connected", "online");
      const yt = document.getElementById("ytVersion");
      const ff = document.getElementById("ffVersion");
      if (yt) yt.textContent = (data && data.ytdlp && data.ytdlp.installed) || "Bundled";
      if (ff) ff.textContent = (data && data.ffmpeg && data.ffmpeg.installed) || "Bundled";
    } catch (_) {}
  }

  async function forceInitializeEngines(source){
    try {
      if (typeof window.setEngineStatus === "function") window.setEngineStatus("⏳ Initializing engines…", "offline");
      console.log("Engine initialization requested", source || "probe");
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 10 * 60 * 1000);
      const response = await originalFetch(ENGINE + "/versions", { method: "GET", cache: "no-store", signal: controller.signal });
      clearTimeout(timer);
      if (!response.ok) throw new Error("HTTP " + response.status);
      const data = await response.json();
      if (!data || !data.ytdlp || data.ytdlp.installed === "Error") throw new Error((data && data.error) || "Engine initialization failed");
      setEngineOnline(data);
      console.log("Engine initialization completed", data);
      return true;
    } catch (error) {
      try { if (typeof window.setEngineStatus === "function") window.setEngineStatus("🔴 Engine initialization failed", "offline"); } catch (_) {}
      console.error("Engine initialization failed", error);
      return false;
    }
  }

  window.forceInitializeEngines = function(){ return forceInitializeEngines("header button"); };

  function installForceInitializeButton(){
    if (document.getElementById("mdForceEngineButton")) return true;
    const settings = document.querySelector(".settings");
    if (!settings || !settings.parentElement) return false;
    const button = document.createElement("button");
    button.id = "mdForceEngineButton"; button.type = "button"; button.className = "md-force-engine-button";
    button.setAttribute("aria-label", "Initialize engines"); button.title = "Initialize engines"; button.textContent = "⚡";
    button.addEventListener("click", async function(){
      if (button.dataset.busy === "1") return;
      button.dataset.busy = "1"; button.disabled = true; button.textContent = "⏳";
      try { await forceInitializeEngines("header button"); } finally { button.disabled = false; button.dataset.busy = "0"; button.textContent = "⚡"; }
    });
    settings.parentElement.insertBefore(button, settings);
    return true;
  }

  function installForceButtonStyle(){
    if (document.getElementById("mdForceEngineButtonStyle")) return;
    const style = document.createElement("style"); style.id = "mdForceEngineButtonStyle";
    style.textContent = `
      .header > .md-force-engine-button{width:42px;height:42px;flex:0 0 42px;margin-left:auto;margin-right:8px;border:1px solid var(--md-outline-variant,var(--border,#293338));border-radius:13px;background:var(--md-surface-container-high,var(--card,#151b1e));color:var(--md-primary,var(--accent,#35c7b5));display:grid;place-items:center;font-size:20px;font-weight:800;line-height:1;box-shadow:none;-webkit-tap-highlight-color:transparent}
      .header > .md-force-engine-button:active{transform:scale(.94)}
      .header > .md-force-engine-button:disabled{opacity:.65}
    `;
    document.head.appendChild(style);
  }

  function install(){ installForceButtonStyle(); installForceInitializeButton(); }
  setTimeout(install, 0); setTimeout(install, 250); setTimeout(install, 1000);
  setTimeout(function(){ try { if (typeof window.discoverEngine === "function") window.discoverEngine(); } catch (_) {} forceInitializeEngines("automatic startup probe"); }, 100);
  const retryTimer = setInterval(function(){ if (window.__mdEngineDetected) { clearInterval(retryTimer); return; } forceInitializeEngines("automatic retry"); }, 5000);
  setTimeout(function(){ clearInterval(retryTimer); }, 60000);
})();
