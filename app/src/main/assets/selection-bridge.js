(function () {
  "use strict";

  const ENGINE_PORTS = [8765, 8787, 8080];
  let localEngineBase = null;
  let cutSection = null;

  function byId(id) { return document.getElementById(id); }

  async function findEngine() {
    for (const port of ENGINE_PORTS) {
      const base = `http://127.0.0.1:${port}/api`;
      try {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 1200);
        const response = await fetch(`${base}/status`, { method: "GET", signal: controller.signal });
        clearTimeout(timeout);
        if (!response.ok) continue;
        const data = await response.json();
        if (data.ok) { localEngineBase = base; return true; }
      } catch (_) {}
    }
    return false;
  }

  function currentMode() {
    if (!byId("audioOptions").classList.contains("hidden")) return "audio";
    if (!byId("advancedOptions").classList.contains("hidden")) return "advanced";
    return "video";
  }

  function videoSelector(label) {
    const match = String(label || "").match(/(2160|1440|1080|720|480|360)/);
    if (!match) return "bv*+ba/b";
    const h = match[1];
    return `bv*[height<=${h}]+ba/b[height<=${h}]`;
  }

  function audioSelector(format) {
    switch (String(format || "Best available")) {
      case "M4A": return "bestaudio[ext=m4a]/bestaudio";
      case "Opus": return "bestaudio[ext=opus]/bestaudio";
      case "FLAC": return "bestaudio[ext=flac]/bestaudio";
      case "MP3": return "bestaudio/best";
      default: return "bestaudio/best";
    }
  }

  function timeToSeconds(value) {
    const parts = String(value || "").trim().split(":");
    if (!parts.length || parts.length > 3 || parts.some(p => !/^\d+(\.\d+)?$/.test(p))) return NaN;
    let seconds = 0;
    for (const p of parts) seconds = seconds * 60 + Number(p);
    return seconds;
  }

  function formatTime(value) {
    const total = Math.max(0, Math.floor(value));
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    return [h, m, s].map(v => String(v).padStart(2, "0")).join(":");
  }

  function installCutButton() {
    const grid = document.querySelector(".time-grid");
    if (!grid || document.getElementById("cutSectionButton")) return;

    const button = document.createElement("button");
    button.id = "cutSectionButton";
    button.type = "button";
    button.textContent = "✂ Cut Section";
    button.style.cssText = "width:100%;margin-top:10px;padding:12px;border:1px solid var(--accent);border-radius:12px;background:#112421;color:var(--accent);font-weight:900;";

    const toggle = document.createElement("button");
    toggle.id = "cutSectionToggle";
    toggle.type = "button";
    toggle.textContent = "✂ Cut: OFF";
    toggle.style.cssText = "width:100%;margin-top:8px;padding:10px;border:1px solid var(--border);border-radius:12px;background:var(--card2);color:var(--muted);font-weight:900;";

    button.onclick = () => {
      const inputs = grid.querySelectorAll("input");
      const start = inputs[0]?.value.trim() || "";
      const end = inputs[1]?.value.trim() || "";
      const startSeconds = timeToSeconds(start);
      const endSeconds = timeToSeconds(end);

      if (!start || !end || !Number.isFinite(startSeconds) || !Number.isFinite(endSeconds)) {
        alert("Enter both Start and End times first. Example: 00:01:30 and 00:03:45");
        return;
      }
      if (startSeconds < 0 || endSeconds <= startSeconds) {
        alert("End time must be greater than Start time.");
        return;
      }

      const normalizedStart = formatTime(startSeconds);
      const normalizedEnd = formatTime(endSeconds);
      inputs[0].value = normalizedStart;
      inputs[1].value = normalizedEnd;
      cutSection = { start: normalizedStart, end: normalizedEnd };
      button.textContent = `✂ Section Set: ${normalizedStart} → ${normalizedEnd}`;
      button.style.background = "#17342f";
      toggle.textContent = "✂ Cut: ON";
      toggle.style.color = "var(--accent)";
      toggle.style.borderColor = "#286d63";
      toggle.style.background = "#112421";
    };

    toggle.onclick = () => {
      if (cutSection) {
        cutSection = null;
        toggle.textContent = "✂ Cut: OFF";
        toggle.style.color = "var(--muted)";
        toggle.style.borderColor = "var(--border)";
        toggle.style.background = "var(--card2)";
        button.textContent = "✂ Cut Section";
        button.style.background = "#112421";
        return;
      }

      const inputs = grid.querySelectorAll("input");
      const start = inputs[0]?.value.trim() || "";
      const end = inputs[1]?.value.trim() || "";
      const startSeconds = timeToSeconds(start);
      const endSeconds = timeToSeconds(end);
      if (!start || !end || !Number.isFinite(startSeconds) || !Number.isFinite(endSeconds) || endSeconds <= startSeconds) {
        alert("Enter a valid Start and End time first.");
        return;
      }
      cutSection = { start: formatTime(startSeconds), end: formatTime(endSeconds) };
      toggle.textContent = "✂ Cut: ON";
      toggle.style.color = "var(--accent)";
      toggle.style.borderColor = "#286d63";
      toggle.style.background = "#112421";
      button.textContent = `✂ Section Set: ${cutSection.start} → ${cutSection.end}`;
      button.style.background = "#17342f";
    };

    grid.parentNode.insertBefore(button, grid.nextSibling);
    button.parentNode.insertBefore(toggle, button.nextSibling);
  }

  function installSettings() {
    const button = document.querySelector(".settings");
    if (!button || button.dataset.settingsInstalled) return;
    button.dataset.settingsInstalled = "1";

    const style = document.createElement("style");
    style.textContent = `
      .md-overlay{position:fixed;inset:0;background:rgba(0,0,0,.72);display:none;align-items:flex-end;justify-content:center;z-index:9999;padding:14px}
      .md-overlay.show{display:flex}
      .md-settings{width:min(100%,700px);background:var(--card);border:1px solid var(--border);border-radius:20px;padding:18px;box-shadow:0 18px 60px rgba(0,0,0,.45)}
      .md-settings-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:15px}
      .md-settings-title{font-size:18px;font-weight:900}
      .md-close{border:1px solid var(--border);background:var(--card2);color:var(--text);border-radius:11px;width:38px;height:38px;font-size:18px}
      .md-folder{background:var(--card2);border:1px solid var(--border);border-radius:13px;padding:13px;margin-bottom:12px}
      .md-folder-name{font-size:13px;font-weight:800;word-break:break-word}
      .md-folder-path{color:var(--muted);font-size:11px;margin-top:4px}
      .md-settings-actions{display:grid;grid-template-columns:1fr 1fr;gap:9px}
      .md-action{padding:12px;border-radius:12px;border:1px solid var(--border);background:var(--card2);color:var(--text);font-weight:800}
      .md-action.primary{background:var(--accent);color:#061311;border:0;margin:0}
      .md-download-controls{display:flex;gap:8px;margin-top:10px}
      .md-download-controls button{flex:1;padding:9px 7px;border-radius:10px;border:1px solid var(--border);font-size:11px;font-weight:900;background:#20292d;color:var(--text)}
      .md-download-controls .pause{color:var(--accent);border-color:#286d63}
      .md-download-controls .cancel{color:var(--red);border-color:#713838}
      .md-progress-line{display:flex;justify-content:space-between;gap:8px;margin-top:7px;font-size:11px;color:var(--muted)}
      .md-percent{font-weight:900;color:var(--text)}
    `;
    document.head.appendChild(style);

    const overlay = document.createElement("div");
    overlay.className = "md-overlay";
    overlay.id = "mdSettingsOverlay";
    overlay.innerHTML = `
      <div class="md-settings" role="dialog" aria-modal="true">
        <div class="md-settings-head">
          <div class="md-settings-title">⚙ Settings</div>
          <button class="md-close" type="button" aria-label="Close">✕</button>
        </div>
        <div class="md-folder">
          <div class="md-folder-name" id="mdFolderName">Downloads</div>
          <div class="md-folder-path">Download destination</div>
        </div>
        <div class="md-settings-actions">
          <button class="md-action primary" id="mdChooseFolder">📁 Choose Folder</button>
          <button class="md-action" id="mdResetFolder">↩ Use Downloads</button>
        </div>
      </div>
    `;
    document.body.appendChild(overlay);

    const close = () => overlay.classList.remove("show");
    const openSettings = (event) => {
      if (event) event.preventDefault();
      updateFolderLabel();
      overlay.classList.add("show");
    };
    button.onclick = openSettings;
    button.ontouchend = (event) => { event.preventDefault(); openSettings(event); };
    overlay.querySelector(".md-close").onclick = close;
    overlay.addEventListener("click", e => { if (e.target === overlay) close(); });
    overlay.querySelector("#mdChooseFolder").onclick = () => {
      try {
        if (window.Android && typeof window.Android.chooseDownloadFolder === "function") {
          window.Android.chooseDownloadFolder();
          return;
        }
      } catch (error) {
        console.error("Folder picker bridge failed", error);
      }
      alert("Folder selection is available inside the Android app.");
    };
    overlay.querySelector("#mdResetFolder").onclick = () => {
      try {
        if (window.Android && typeof window.Android.clearDownloadFolder === "function") {
          window.Android.clearDownloadFolder();
        }
      } catch (error) {
        console.error("Folder reset bridge failed", error);
      }
      updateFolderLabel("Downloads");
    };

    window.onNativeFolderSelected = function(uri, name) {
      updateFolderLabel(name || "Downloads");
      overlay.classList.add("show");
    };

    function updateFolderLabel(value) {
      const el = byId("mdFolderName");
      if (!el) return;
      if (value !== undefined) { el.textContent = value || "Downloads"; return; }
      try {
        const name = window.Android?.getDownloadFolderName?.();
        el.textContent = name || "Downloads";
      } catch (_) {
        el.textContent = "Downloads";
      }
    }
  }

  function addDownloadControls(item, jobId) {
    if (item.querySelector(".md-download-controls")) return;
    const controls = document.createElement("div");
    controls.className = "md-download-controls";
    controls.innerHTML = `
      <button class="pause" type="button">⏸ Pause</button>
      <button class="cancel" type="button">⛔ Terminate</button>
    `;
    item.appendChild(controls);

    const pauseButton = controls.querySelector(".pause");
    const cancelButton = controls.querySelector(".cancel");

    pauseButton.onclick = async () => {
      if (!localEngineBase) return;
      const isPaused = pauseButton.dataset.state === "paused";
      const action = isPaused ? "resume" : "pause";
      pauseButton.disabled = true;
      try {
        const response = await fetch(`${localEngineBase}/download/${encodeURIComponent(jobId)}/${action}`, { method: "POST" });
        const data = await response.json();
        if (!response.ok || !data.ok) throw new Error(data.error || `${action} failed`);
      } catch (error) {
        alert(error.message || `${action} failed`);
      } finally {
        pauseButton.disabled = false;
      }
    };

    cancelButton.onclick = async () => {
      if (!localEngineBase) return;
      if (!confirm("Terminate this download? The partial file will be discarded.")) return;
      cancelButton.disabled = true;
      try {
        const response = await fetch(`${localEngineBase}/download/${encodeURIComponent(jobId)}/cancel`, { method: "POST" });
        const data = await response.json();
        if (!response.ok || !data.ok) throw new Error(data.error || "Terminate failed");
      } catch (error) {
        cancelButton.disabled = false;
        alert(error.message || "Terminate failed");
      }
    };
  }

  function updateDownloadControls(item, data) {
    const controls = item.querySelector(".md-download-controls");
    if (!controls) return;
    const pauseButton = controls.querySelector(".pause");
    const cancelButton = controls.querySelector(".cancel");
    const status = String(data.status || "").toLowerCase();

    if (status === "paused") {
      pauseButton.textContent = "▶ Resume";
      pauseButton.dataset.state = "paused";
      pauseButton.disabled = false;
    } else if (status === "completed" || status === "cancelled" || status.startsWith("failed")) {
      controls.style.display = "none";
    } else {
      pauseButton.textContent = "⏸ Pause";
      pauseButton.dataset.state = "running";
      pauseButton.disabled = false;
      cancelButton.disabled = false;
    }
  }

  function updateProgressText(item, data) {
    let line = item.querySelector(".md-progress-line");
    if (!line) {
      line = document.createElement("div");
      line.className = "md-progress-line";
      line.innerHTML = `<span class="md-speed">—</span><span class="md-percent">0%</span>`;
      const progress = item.querySelector(".progress");
      if (progress) progress.parentNode.insertBefore(line, progress.nextSibling);
    }
    line.querySelector(".md-speed").textContent = data.speed ? `⚡ ${data.speed}` : "⚡ —";
    line.querySelector(".md-percent").textContent = `${Number(data.percent || 0).toFixed(0)}%`;
  }

  window.monitorDownload = async function (jobId, item) {
    if (!localEngineBase) return;
    addDownloadControls(item, jobId);

    try {
      const response = await fetch(`${localEngineBase}/download/${encodeURIComponent(jobId)}`);
      if (!response.ok) return;
      const data = await response.json();

      const status = item.querySelector(".queue-status");
      const bar = item.querySelector(".progress-bar");
      const percent = Math.max(0, Math.min(100, Number(data.percent || 0)));
      bar.style.width = `${percent}%`;

      status.textContent = data.status || "working";
      if (data.eta != null && data.status !== "completed" && data.status !== "cancelled") {
        status.textContent += ` • ETA ${data.eta}s`;
      }

      updateProgressText(item, data);
      updateDownloadControls(item, data);

      const normalized = String(data.status || "").toLowerCase();
      const finished = normalized === "completed" || normalized === "cancelled" || normalized.startsWith("failed");

      if (!finished) {
        setTimeout(() => window.monitorDownload(jobId, item), 800);
      }
    } catch (error) {
      console.log("Progress check failed", error);
      setTimeout(() => window.monitorDownload(jobId, item), 1500);
    }
  };

  window.addEventListener("load", () => {
    installCutButton();
    installSettings();
  });
  setTimeout(installCutButton, 50);
  setTimeout(installCutButton, 500);
  setTimeout(installSettings, 50);
  setTimeout(installSettings, 500);

  window.addDownload = async function () {
    const url = byId("url").value.trim();
    if (!url) { alert("Analyze a URL first."); return; }
    if (!localEngineBase && !(await findEngine())) { alert("Local engine is not running yet."); return; }

    const mode = currentMode();
    let format = "bv*+ba/b";
    let audioOnly = false;
    let audioFormat = "";
    let audioQuality = "";
    let mergeOutputFormat = "";

    if (mode === "video") {
      const selects = byId("videoOptions").querySelectorAll("select");
      const quality = selects[0]?.value || "Best available";
      const container = selects[1]?.value || "Auto";
      format = videoSelector(quality);
      if (container !== "Auto") mergeOutputFormat = container.toLowerCase();
    } else if (mode === "audio") {
      const selects = byId("audioOptions").querySelectorAll("select");
      audioFormat = selects[0]?.value || "Best available";
      audioQuality = selects[1]?.value || "Best";
      audioOnly = true;
      format = audioSelector(audioFormat);
    } else {
      const input = byId("advancedOptions").querySelector("input");
      format = input?.value.trim() || "bv*+ba/b";
    }

    const timeInputs = document.querySelectorAll(".time-grid input");
    const start = timeInputs[0]?.value.trim() || "";
    const end = timeInputs[1]?.value.trim() || "";
    const section = cutSection && start && end ? cutSection : null;

    const checks = document.querySelectorAll(".checks input[type=checkbox]");
    const subtitles = !!checks[0]?.checked;
    const thumbnail = !!checks[1]?.checked;
    const metadata = !!checks[2]?.checked;

    const queue = byId("queue");
    const empty = queue.querySelector(".empty");
    if (empty) empty.remove();

    const item = document.createElement("div");
    item.className = "queue-item";
    item.innerHTML = `
      <div class="queue-name">${escapeHTML(url)}</div>
      <div class="queue-status">Sending selected settings to local engine…</div>
      <div class="progress"><div class="progress-bar"></div></div>
    `;
    queue.appendChild(item);

    try {
      const response = await fetch(`${localEngineBase}/download`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          url,
          format,
          start: section?.start || "",
          end: section?.end || "",
          audio_only: audioOnly,
          audio_format: audioFormat,
          audio_quality: audioQuality,
          merge_output_format: mergeOutputFormat,
          subtitles,
          thumbnail,
          metadata
        })
      });

      const data = await response.json();
      if (!response.ok || !data.ok) throw new Error(data.error || "Download request failed");

      item.querySelector(".queue-status").textContent =
        `Queued • ${mode}${audioFormat ? " • " + audioFormat : ""}${section ? ` • ✂ ${section.start} → ${section.end}` : ""} • Job ${data.job_id}`;

      window.monitorDownload(data.job_id, item);
    } catch (error) {
      item.querySelector(".queue-status").textContent = `❌ ${error.message || "Failed to start"}`;
      console.error(error);
    }
  };

  window._mediaDownloaderSelectionBridge = true;
})();
