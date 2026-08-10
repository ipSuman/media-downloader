(function () {
  "use strict";

  const ENGINE_PORTS = [8765, 8787, 8080];
  let localEngineBase = null;

  function byId(id) { return document.getElementById(id); }

  async function findEngine() {
    for (const port of ENGINE_PORTS) {
      const base = `http://127.0.0.1:${port}/api`;
      try {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 1200);
        const response = await fetch(`${base}/status`, {
          method: "GET",
          signal: controller.signal
        });
        clearTimeout(timeout);
        if (!response.ok) continue;
        const data = await response.json();
        if (data.ok) {
          localEngineBase = base;
          return true;
        }
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

  window.addDownload = async function () {
    const url = byId("url").value.trim();
    if (!url) {
      alert("Analyze a URL first.");
      return;
    }

    if (!localEngineBase && !(await findEngine())) {
      alert("Local engine is not running yet.");
      return;
    }

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
          start,
          end,
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
      if (!response.ok || !data.ok) {
        throw new Error(data.error || "Download request failed");
      }

      item.querySelector(".queue-status").textContent =
        `Queued • ${mode}${audioFormat ? " • " + audioFormat : ""}${format ? " • " + format : ""} • Job ${data.job_id}`;

      if (typeof window.monitorDownload === "function") {
        window.monitorDownload(data.job_id, item);
      }
    } catch (error) {
      item.querySelector(".queue-status").textContent = `❌ ${error.message || "Failed to start"}`;
      console.error(error);
    }
  };

  window._mediaDownloaderSelectionBridge = true;
})();
