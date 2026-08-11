(function () {
  "use strict";

  if (window.__mdDownloadFixInstalled) return;
  window.__mdDownloadFixInstalled = true;

  const ENGINE = "http://127.0.0.1:8765";

  function byId(id) { return document.getElementById(id); }
  function esc(value) {
    const d = document.createElement("div");
    d.textContent = String(value ?? "");
    return d.innerHTML;
  }

  function currentMode() {
    const audio = byId("audioOptions");
    const advanced = byId("advancedOptions");
    if (audio && !audio.classList.contains("hidden")) return "audio";
    if (advanced && !advanced.classList.contains("hidden")) return "advanced";
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
      default: return "bestaudio/best";
    }
  }

  function engineReady() {
    window.engineBase = ENGINE;
    window.__mdNativeEngineBase = ENGINE;
    return ENGINE;
  }

  function cutSelection() {
    const grid = document.querySelector(".time-grid");
    if (!grid) return null;
    const inputs = grid.querySelectorAll("input");
    const start = inputs[0]?.value.trim() || "";
    const end = inputs[1]?.value.trim() || "";
    const toggle = byId("cutSectionToggle");
    const button = byId("cutSectionButton");
    const active = (toggle && /Cut:\s*ON/i.test(toggle.textContent || "")) ||
                   (button && /Section Set:/i.test(button.textContent || ""));
    return active && start && end ? { start, end } : null;
  }

  function addControls(item, jobId) {
    if (item.querySelector(".md-download-controls")) return;
    const controls = document.createElement("div");
    controls.className = "md-download-controls";
    controls.innerHTML = '<button class="pause" type="button">⏸ Pause</button><button class="cancel" type="button">⛔ Terminate</button>';
    item.appendChild(controls);

    const pause = controls.querySelector(".pause");
    const cancel = controls.querySelector(".cancel");

    pause.onclick = async () => {
      const paused = pause.dataset.state === "paused";
      const action = paused ? "resume" : "pause";
      pause.disabled = true;
      try {
        const r = await fetch(`${ENGINE}/download/${encodeURIComponent(jobId)}/${action}`, { method: "POST", cache: "no-store" });
        const d = await r.json();
        if (!r.ok || !d.ok) throw new Error(d.error || `${action} failed`);
      } catch (e) {
        alert(e.message || `${action} failed`);
      } finally {
        pause.disabled = false;
      }
    };

    cancel.onclick = async () => {
      if (!confirm("Terminate this download? The partial file will be discarded.")) return;
      cancel.disabled = true;
      try {
        const r = await fetch(`${ENGINE}/download/${encodeURIComponent(jobId)}/cancel`, { method: "POST", cache: "no-store" });
        const d = await r.json();
        if (!r.ok || !d.ok) throw new Error(d.error || "Terminate failed");
      } catch (e) {
        cancel.disabled = false;
        alert(e.message || "Terminate failed");
      }
    };
  }

  window.monitorDownload = async function (jobId, item) {
    addControls(item, jobId);
    try {
      const r = await fetch(`${ENGINE}/download/${encodeURIComponent(jobId)}`, { cache: "no-store" });
      if (!r.ok) throw new Error(`Status HTTP ${r.status}`);
      const d = await r.json();
      const status = item.querySelector(".queue-status");
      const bar = item.querySelector(".progress-bar");
      const pct = Math.max(0, Math.min(100, Number(d.percent || 0)));
      if (bar) bar.style.width = `${pct}%`;
      if (status) status.textContent = `${d.status || "working"}${d.speed ? ` • ⚡ ${d.speed}` : ""}${d.eta != null ? ` • ETA ${d.eta}s` : ""}`;

      const controls = item.querySelector(".md-download-controls");
      if (controls) {
        const pause = controls.querySelector(".pause");
        const cancel = controls.querySelector(".cancel");
        const s = String(d.status || "").toLowerCase();
        if (s === "paused") { pause.textContent = "▶ Resume"; pause.dataset.state = "paused"; }
        else if (s === "completed" || s === "cancelled" || s.startsWith("failed")) controls.style.display = "none";
        else { pause.textContent = "⏸ Pause"; pause.dataset.state = "running"; }
        if (cancel) cancel.disabled = s === "completed" || s === "cancelled" || s.startsWith("failed");
      }

      const normalized = String(d.status || "").toLowerCase();
      if (normalized !== "completed" && normalized !== "cancelled" && !normalized.startsWith("failed")) {
        setTimeout(() => window.monitorDownload(jobId, item), 800);
      }
    } catch (e) {
      const status = item.querySelector(".queue-status");
      if (status) status.textContent = `⚠ Waiting for engine… ${e.message || "status unavailable"}`;
      setTimeout(() => window.monitorDownload(jobId, item), 1500);
    }
  };

  window.addDownload = async function () {
    const url = byId("url")?.value.trim() || "";
    if (!url) { alert("Analyze a URL first."); return; }

    const mode = currentMode();
    let format = "bv*+ba/b";
    let audioOnly = false;
    let audioFormat = "";
    let audioQuality = "";
    let mergeOutputFormat = "";

    if (mode === "video") {
      const selects = byId("videoOptions")?.querySelectorAll("select") || [];
      const quality = selects[0]?.value || "Best available";
      const container = selects[1]?.value || "Auto";
      format = videoSelector(quality);
      if (container !== "Auto") mergeOutputFormat = container.toLowerCase();
    } else if (mode === "audio") {
      const selects = byId("audioOptions")?.querySelectorAll("select") || [];
      audioFormat = selects[0]?.value || "Best available";
      audioQuality = selects[1]?.value || "Best";
      audioOnly = true;
      format = audioSelector(audioFormat);
    } else {
      format = byId("advancedOptions")?.querySelector("input")?.value.trim() || "bv*+ba/b";
    }

    const section = cutSelection();
    const checks = document.querySelectorAll(".checks input[type=checkbox]");
    const subtitles = !!checks[0]?.checked;
    const thumbnail = !!checks[1]?.checked;
    const metadata = !!checks[2]?.checked;

    engineReady();

    const queue = byId("queue");
    const empty = queue?.querySelector(".empty");
    if (empty) empty.remove();

    const item = document.createElement("div");
    item.className = "queue-item";
    item.innerHTML = `<div class="queue-name">${esc(url)}</div><div class="queue-status">🚀 Starting download…</div><div class="progress"><div class="progress-bar"></div></div>`;
    queue.appendChild(item);

    try {
      const payload = {
        url, format,
        start: section?.start || "",
        end: section?.end || "",
        audio_only: audioOnly,
        audio_format: audioFormat,
        audio_quality: audioQuality,
        merge_output_format: mergeOutputFormat,
        subtitles, thumbnail, metadata
      };
      console.log("Media Downloader: sending download request", payload);

      const response = await fetch(`${ENGINE}/download`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        cache: "no-store",
        body: JSON.stringify(payload)
      });

      const text = await response.text();
      let data;
      try { data = JSON.parse(text); } catch (_) { throw new Error(`Engine returned invalid response (HTTP ${response.status})`); }
      if (!response.ok || !data.ok || !data.job_id) throw new Error(data.error || `Download request rejected (HTTP ${response.status})`);

      item.querySelector(".queue-status").textContent = `🟢 Started • Job ${data.job_id}`;
      window.monitorDownload(data.job_id, item);
    } catch (e) {
      item.querySelector(".queue-status").textContent = `❌ Download failed to start: ${e.message || e}`;
      console.error("Media Downloader: download start failed", e);
    }
  };

  console.log("Media Downloader: robust download bridge installed");
})();
