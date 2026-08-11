(function () {
  "use strict";

  // MainActivity's native discovery bridge exposes the engine as a window
  // property. The web app itself keeps its engineBase as a top-level `let`,
  // so synchronize that lexical binding before analyze()/download code uses it.
  function syncNativeEngineBase() {
    try {
      const nativeBase = window.__mdNativeEngineBase || window.engineBase;
      if (nativeBase) engineBase = nativeBase;
    } catch (_) {}
  }

  syncNativeEngineBase();
  setTimeout(syncNativeEngineBase, 100);
  setTimeout(syncNativeEngineBase, 300);
  setTimeout(syncNativeEngineBase, 800);

  function install() {
    const grid = document.querySelector(".time-grid");
    const toggle = document.getElementById("cutSectionToggle");
    if (!grid || !toggle) return false;

    const inputs = Array.from(grid.querySelectorAll("input")).slice(0, 2);
    if (inputs.length < 2) return true;
    if (grid.dataset.cutKeyboardFixInstalled === "1") return true;
    grid.dataset.cutKeyboardFixInstalled = "1";

    // Restore the previous cut-field keyboard behavior only.
    // These remain normal text fields so ':' can be entered on Android.
    inputs.forEach(input => {
      input.type = "text";
      input.inputMode = "text";
      input.removeAttribute("pattern");
      input.setAttribute("autocomplete", "off");
      input.setAttribute("spellcheck", "false");
      input.placeholder = "00:00";
    });

    const originalToggle = toggle.onclick;
    toggle.onclick = function () {
      if (typeof originalToggle === "function") originalToggle.call(toggle);
      if (toggle.textContent.includes("Cut: ON")) {
        inputs.forEach(input => {
          if (!input.value.trim()) input.value = "00:00";
        });
      }
    };

    return true;
  }

  function retry() {
    syncNativeEngineBase();
    if (!install()) setTimeout(retry, 300);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", retry, { once: true });
  } else {
    retry();
  }

  // Load the hardened download bridge after the existing selection/cut bridge.
  // This keeps the established cut workflow intact while making the actual
  // download request use the native engine directly and report start errors.
  setTimeout(function () {
    try {
      if (window.__mdDownloadFixInstalled) return;
      const script = document.createElement("script");
      script.src = "file:///android_asset/download-fix.js";
      script.async = false;
      script.onload = function () {
        console.log("Media Downloader: download-fix.js loaded");

        // The native engine exposes job status at /status/<jobId>.
        // The hardened bridge historically polled /download/<jobId>, which
        // is not a GET endpoint and therefore produced the misleading
        // "Waiting for engine... Status HTTP 404" message. Rewrite only
        // these GET status polls; POST download/control requests are untouched.
        try {
          if (window.__mdDownloadStatusFixInstalled) return;
          const nativeFetch = window.fetch.bind(window);
          window.fetch = function (input, init) {
            try {
              const method = String((init && init.method) || (input && input.method) || "GET").toUpperCase();
              const rawUrl = typeof input === "string" ? input : (input && input.url);
              if (method === "GET" && rawUrl) {
                const u = new URL(rawUrl, window.location.href);
                const match = u.pathname.match(/^\/download\/([^/]+)$/);
                if (match && (u.hostname === "127.0.0.1" || u.hostname === "localhost")) {
                  u.pathname = `/status/${match[1]}`;
                  if (typeof input === "string") return nativeFetch(u.toString(), init);
                  return nativeFetch(new Request(u.toString(), input), init);
                }
              }
            } catch (_) {}
            return nativeFetch(input, init);
          };
          window.__mdDownloadStatusFixInstalled = true;
          console.log("Media Downloader: download status endpoint fixed");
        } catch (e) {
          console.error("Media Downloader: could not install status endpoint fix", e);
        }
      };
      script.onerror = function (e) { console.error("Media Downloader: download-fix.js failed to load", e); };
      document.documentElement.appendChild(script);
    } catch (e) {
      console.error("Media Downloader: could not load download fix", e);
    }
  }, 0);
})();