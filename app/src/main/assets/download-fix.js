(function () {
  "use strict";

  if (window.__mdDownloadFixInstalled) return;
  window.__mdDownloadFixInstalled = true;

  const ENGINE = "http://127.0.0.1:8765";

  function byId(id) { return document.getElementById(id); }
  function esc(value) { const d = document.createElement("div"); d.textContent = String(value ?? ""); return d.innerHTML; }

  function currentMode() {
    const audio = byId("audioOptions");
    const advanced = byId("advancedOptions");
    if (audio && !audio.classList.contains("hidden")) return "audio";
    if (advanced && !advanced.classList.contains("hidden")) return "advanced";
    return "video";
  }

  function selectedVideoFormat() {
    const select = byId("videoQualitySelect");
    if (!select || !select.value) return { id: "", codec: "" };
    const option = select.options[select.selectedIndex];
    return { id: String(select.value), codec: String(option?.dataset?.vcodec || "") };
  }

  function audioSelector(format) {
    switch (String(format || "Best available")) {
      case "M4A": return "bestaudio[ext=m4a]/bestaudio";
      case "Opus": return "bestaudio[ext=opus]/bestaudio";
      case "FLAC": return "bestaudio[ext=flac]/bestaudio";
      default: return "bestaudio/best";
    }
  }

  function engineReady() { window.engineBase = ENGINE; window.__mdNativeEngineBase = ENGINE; return ENGINE; }

  function cutSelection() {
    const grid = document.querySelector(".time-grid");
    if (!grid) return null;
    const inputs = grid.querySelectorAll("input");
    const start = inputs[0]?.value.trim() || "";
    const end = inputs[1]?.value.trim() || "";
    const toggle = byId("cutSectionToggle");
    const button = byId("cutSectionButton");
    const active = (toggle && /Cut:\s*ON/i.test(toggle.textContent || "")) || (button && /Section Set:/i.test(button.textContent || ""));
    return active && start && end ? { start, end } : null;
  }

  function mergeEnabled() {
    const toggle = byId("mergeTracksToggle");
    return toggle ? !!toggle.checked : true;
  }

  function installMergeToggle() {
    const select = byId("videoQualitySelect");
    if (!select || byId("mergeTracksToggle")) return;
    const row = select.closest(".option-row");
    if (!row) return;
    const wrap = document.createElement("label");
    wrap.className = "check";
    wrap.style.marginTop = "10px";
    wrap.innerHTML = '<input id="mergeTracksToggle" type="checkbox" checked><span>Merge video + audio</span>';
    row.appendChild(wrap);
  }

  function installManualLogButton() {
    if (byId("manualLogButton")) return;
    const header = document.querySelector(".header");
    if (!header) return;

    const settings = header.querySelector(".settings");
    if (!settings) {
      setTimeout(installManualLogButton, 250);
      return;
    }

    const actions = document.createElement("div");
    actions.className = "md-header-actions";
    actions.style.cssText = "display:flex;align-items:center;gap:8px;margin-left:auto;flex:0 0 auto";

    const button = document.createElement("button");
    button.id = "manualLogButton";
    button.type = "button";
    button.textContent = "📄";
    button.title = "Generate diagnostic log now";
    button.setAttribute("aria-label", "Generate diagnostic log");
    button.style.cssText = "width:63px;height:63px;min-width:63px;padding:0;border:1px solid #35c7b5;border-radius:18px;background:#112421;color:#35c7b5;font-size:24px;font-weight:800;display:grid;place-items:center";

    button.onclick = () => {
      if (!window.Android || typeof window.Android.generateDiagnosticLog !== "function") {
        alert("Diagnostic log bridge is not available.");
        return;
      }
      button.disabled = true;
      button.dataset.oldText = button.textContent;
      button.textContent = "⏳";
      window.onDiagnosticLogSaved = (ok) => {
        button.textContent = ok ? "✓" : "✕";
        if (!ok) alert("Could not generate the diagnostic log. Check Android logcat.");
        setTimeout(() => {
          button.disabled = false;
          button.textContent = button.dataset.oldText || "📄";
        }, 1800);
      };
      try {
        window.Android.generateDiagnosticLog();
      } catch (e) {
        console.error("Media Downloader: diagnostic log export failed", e);
        button.disabled = false;
        button.textContent = button.dataset.oldText || "📄";
        alert(e.message || "Could not generate diagnostic log");
      }
    };

    const style = document.createElement("style");
    style.id = "mdManualLogButtonStyle";
    style.textContent = `
      #manualLogButton:active { transform:scale(.96); }
      #manualLogButton:disabled { opacity:.7; }
      @media(max-width:420px){
        #manualLogButton { width:57px!important; height:57px!important; min-width:57px!important; border-radius:18px!important; }
      }
    `;
    document.head.appendChild(style);

    header.insertBefore(actions, settings);
    actions.appendChild(button);
    actions.appendChild(settings);
  }

  function watchForQualitySelector() {
    installMergeToggle();
    const observer = new MutationObserver(installMergeToggle);
    observer.observe(document.body, { childList: true, subtree: true });
    setTimeout(installMergeToggle, 300);
  }

  function controlJob(jobId, action) {
    if (window.Android && typeof window.Android.controlDownload === "function") {
      return new Promise((resolve, reject) => {
        const requestId = `ctl_${Date.now()}_${Math.random().toString(36).slice(2)}`;
        if (!window.__mdControlCallbacks) window.__mdControlCallbacks = {};
        window.__mdControlCallbacks[requestId] = { resolve, reject };
        try {
          console.log("Media Downloader: native control request", { jobId, action, requestId });
          window.Android.controlDownload(String(jobId), String(action), requestId);
        } catch (e) {
          delete window.__mdControlCallbacks[requestId];
          reject(e);
        }
      });
    }
    return fetch(`${ENGINE}/download/${encodeURIComponent(jobId)}/control`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      cache: "no-store",
      body: JSON.stringify({ action })
    }).then(async response => {
      const text = await response.text();
      let data; try { data = JSON.parse(text); } catch (_) { throw new Error(`Engine returned invalid control response (HTTP ${response.status})`); }
      if (!response.ok || !data.ok) throw new Error(data.error || `${action} failed (HTTP ${response.status})`);
      return data;
    });
  }

  window.onNativeControlResult = function (requestId, ok, payload) {
    const cb = window.__mdControlCallbacks && window.__mdControlCallbacks[requestId];
    if (!cb) return;
    delete window.__mdControlCallbacks[requestId];
    if (ok) cb.resolve(payload || { ok: true });
    else cb.reject(new Error((payload && payload.error) || "Control request failed"));
  };

  function addControls(item, jobId) {
    if (item.querySelector(".md-download-controls")) return;
    const controls = document.createElement("div");
    controls.className = "md-download-controls";
    controls.dataset.jobId = String(jobId);
    controls.innerHTML = '<button class="pause" type="button">⏸ Pause</button><button class="cancel" type="button">⛔ Terminate</button>';
    item.appendChild(controls);
  }

  if (!window.__mdControlClickHandlerInstalled) {
    window.__mdControlClickHandlerInstalled = true;
    document.addEventListener("click", async (event) => {
      const target = event.target && event.target.closest ? event.target.closest(".md-download-controls button") : null;
      if (!target) return;
      event.preventDefault();
      event.stopPropagation();
      const controls = target.closest(".md-download-controls");
      const jobId = controls && controls.dataset.jobId;
      if (!jobId) return;
      const isPause = target.classList.contains("pause");
      const isCancel = target.classList.contains("cancel");
      if (!isPause && !isCancel) return;
      if (isCancel) console.log("Media Downloader: terminate click", { jobId });
      const action = isPause ? (target.dataset.state === "paused" ? "resume" : "pause") : "cancel";
      if (target.disabled) return;
      target.disabled = true;
      try {
        console.log("Media Downloader: control click", { jobId, action });
        await controlJob(jobId, action);
      } catch (e) {
        console.error("Media Downloader: control request failed", { jobId, action, error: e });
        alert(e.message || `${action} failed`);
      } finally {
        if (action !== "cancel") target.disabled = false;
      }
    }, true);
  }

  window.monitorDownload = async function (jobId, item) {
    addControls(item, jobId);
    try {
      const r = await fetch(`${ENGINE}/status/${encodeURIComponent(jobId)}`, { cache: "no-store" });
      if (!r.ok) throw new Error(`Status HTTP ${r.status}`);
      const d = await r.json();
      const status = item.querySelector(".queue-status");
      const bar = item.querySelector(".progress-bar");
      const pct = Math.max(0, Math.min(100, Number(d.percent || 0)));
      if (bar) bar.style.width = `${pct}%`;
      if (status) status.textContent = `${d.status || "working"}${d.speed ? ` • ⚡ ${d.speed}` : ""}${d.eta != null ? ` • ETA ${d.eta}s` : ""}`;
      const controls = item.querySelector(".md-download-controls");
      if (controls) {
        const pause = controls.querySelector(".pause"); const cancel = controls.querySelector(".cancel"); const s = String(d.status || "").toLowerCase();
        if (s === "paused") { pause.textContent = "▶ Resume"; pause.dataset.state = "paused"; }
        else if (s === "completed" || s === "cancelled" || s.startsWith("failed")) controls.style.display = "none";
        else { pause.textContent = "⏸ Pause"; pause.dataset.state = "running"; }
        if (cancel) cancel.disabled = s === "completed" || s === "cancelled" || s.startsWith("failed");
      }
      const normalized = String(d.status || "").toLowerCase();
      if (normalized !== "completed" && normalized !== "cancelled" && !normalized.startsWith("failed")) setTimeout(() => window.monitorDownload(jobId, item), 800);
    } catch (e) {
      const status = item.querySelector(".queue-status"); if (status) status.textContent = `⚠ Waiting for engine… ${e.message || "status unavailable"}`; setTimeout(() => window.monitorDownload(jobId, item), 1500);
    }
  };

  function makeQueueItem(url, label) {
    const queue = byId("queue"); const empty = queue?.querySelector(".empty"); if (empty) empty.remove();
    const item = document.createElement("div"); item.className = "queue-item";
    item.innerHTML = `<div class="queue-name">${esc(label || url)}</div><div class="queue-status">🚀 Starting download…</div><div class="progress"><div class="progress-bar"></div></div>`;
    queue.appendChild(item); return item;
  }

  async function submitJob(payload, item) {
    const response = await fetch(`${ENGINE}/download`, { method: "POST", headers: { "Content-Type": "application/json" }, cache: "no-store", body: JSON.stringify(payload) });
    const text = await response.text(); let data;
    try { data = JSON.parse(text); } catch (_) { throw new Error(`Engine returned invalid response (HTTP ${response.status})`); }
    if (!response.ok || !data.ok || !data.job_id) throw new Error(data.error || `Download request rejected (HTTP ${response.status})`);
    item.querySelector(".queue-status").textContent = `🟢 Started • Job ${data.job_id}`;
    window.monitorDownload(data.job_id, item);
    return data.job_id;
  }

  window.addDownload = async function () {
    const url = byId("url")?.value.trim() || "";
    if (!url) { alert("Analyze a URL first."); return; }
    const mode = currentMode();
    let format = "bv*+ba/b", audioOnly = false, audioFormat = "", audioQuality = "", mergeOutputFormat = "", videoCodec = "";

    if (mode === "video") {
      const selects = byId("videoOptions")?.querySelectorAll("select") || [];
      const chosen = selectedVideoFormat();
      if (!chosen.id) { alert("Please select a video format ID from the Video quality selector."); return; }
      const container = selects[1]?.value || "Auto";
      const shouldMergeVp9 = chosen.codec.toLowerCase().includes("vp9") && mergeEnabled();
      format = shouldMergeVp9 ? `${chosen.id}+251` : chosen.id;
      videoCodec = chosen.codec;
      if (container !== "Auto") mergeOutputFormat = container.toLowerCase();
    } else if (mode === "audio") {
      const selects = byId("audioOptions")?.querySelectorAll("select") || [];
      audioFormat = selects[0]?.value || "Best available"; audioQuality = selects[1]?.value || "Best"; audioOnly = true; format = audioSelector(audioFormat);
    } else {
      format = byId("advancedOptions")?.querySelector("input")?.value.trim() || "bv*+ba/b";
    }

    const section = cutSelection();
    const checks = document.querySelectorAll(".checks input[type=checkbox]");
    const subtitles = !!checks[0]?.checked, thumbnail = !!checks[1]?.checked, metadata = !!checks[2]?.checked;
    engineReady();

    const common = { url, start: section?.start || "", end: section?.end || "", subtitles, thumbnail, metadata };

    try {
      if (mode === "video" && videoCodec.toLowerCase().includes("vp9") && !mergeEnabled()) {
        const videoItem = makeQueueItem(url, `🎬 Video • ID ${format}`);
        const audioItem = makeQueueItem(url, "🎵 Audio • ID 251");
        const videoPayload = { ...common, format, audio_only: false, audio_format: "", audio_quality: "", merge_output_format: mergeOutputFormat, video_codec: "separate-video" };
        const audioPayload = { ...common, format: "251", audio_only: false, audio_format: "", audio_quality: "", merge_output_format: "", video_codec: "separate-audio" };
        console.log("Media Downloader: VP9 separate-track mode", { videoPayload, audioPayload });
        await Promise.all([submitJob(videoPayload, videoItem), submitJob(audioPayload, audioItem)]);
        return;
      }

      const item = makeQueueItem(url, mode === "video" ? `🎬 Video • ID ${format}` : url);
      const payload = { ...common, format, audio_only: audioOnly, audio_format: audioFormat, audio_quality: audioQuality, merge_output_format: mergeOutputFormat, video_codec: videoCodec };
      console.log("Media Downloader: sending download request", payload);
      await submitJob(payload, item);
    } catch (e) {
      console.error("Media Downloader: download start failed", e);
      alert(e.message || "Download failed to start");
    }
  };

  watchForQualitySelector();
  installManualLogButton();
  console.log("Media Downloader: robust download bridge with merge toggle installed");
})();
