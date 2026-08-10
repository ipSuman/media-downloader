(function () {
  "use strict";

  function parseTime(value) {
    const parts = String(value || "").trim().split(":");
    if (parts.length !== 2 || !/^\d{1,2}$/.test(parts[0]) || !/^\d{1,2}$/.test(parts[1])) return NaN;
    const minutes = Number(parts[0]);
    const seconds = Number(parts[1]);
    if (seconds > 59) return NaN;
    return minutes * 60 + seconds;
  }

  function install() {
    const grid = document.querySelector(".time-grid");
    const toggle = document.getElementById("cutSectionToggle");
    const cutButton = document.getElementById("cutSectionButton");
    if (!grid || !toggle || !cutButton) return false;

    if (grid.dataset.cutInputFixInstalled === "1") return true;
    grid.dataset.cutInputFixInstalled = "1";

    const inputs = Array.from(grid.querySelectorAll("input")).slice(0, 2);
    if (inputs.length < 2) return true;

    // Force normal text input instead of Android's time/numeric keyboard.
    inputs.forEach(input => {
      input.type = "text";
      input.inputMode = "text";
      input.removeAttribute("pattern");
      input.setAttribute("autocomplete", "off");
      input.setAttribute("spellcheck", "false");
      input.placeholder = "00:00";
      input.addEventListener("focus", () => {
        if (!input.value.trim()) input.value = "00:00";
      });
    });

    let cutEnabled = false;
    let cutCommitted = false;
    const originalToggle = toggle.onclick;
    const originalCutButton = cutButton.onclick;
    const originalAddDownload = window.addDownload;

    function setDefaults() {
      inputs[0].value = "00:00";
      inputs[1].value = "00:00";
    }

    function updateToggle(on) {
      cutEnabled = on;
      toggle.dataset.state = on ? "on" : "off";
      toggle.textContent = on ? "✂ Cut: ON" : "✂ Cut: OFF";
      if (on) {
        toggle.style.color = "var(--md-green, var(--accent))";
        toggle.style.borderColor = "#367b57";
        toggle.style.background = "#173025";
      } else {
        toggle.style.color = "var(--muted)";
        toggle.style.borderColor = "var(--border)";
        toggle.style.background = "var(--card2)";
      }
    }

    function commitCut() {
      const start = inputs[0].value.trim();
      const end = inputs[1].value.trim();
      const startSeconds = parseTime(start);
      const endSeconds = parseTime(end);

      if (!Number.isFinite(startSeconds) || !Number.isFinite(endSeconds)) {
        alert("Enter Start and End as MM:SS. Example: 01:30 and 03:45");
        return false;
      }
      if (endSeconds <= startSeconds) {
        alert("End time must be greater than Start time.");
        return false;
      }

      // Let the existing bridge create the real download command.
      if (typeof originalCutButton === "function") originalCutButton.call(cutButton);
      cutCommitted = true;
      updateToggle(true);
      return true;
    }

    // ON = prepare editable 00:00 / 00:00 fields. The user can type ':' normally.
    // OFF = disable cutting; if a cut command was already committed, clear it using
    // the existing bridge handler when possible.
    toggle.onclick = function () {
      if (!cutEnabled) {
        setDefaults();
        cutCommitted = false;
        updateToggle(true);
        inputs[0].focus();
        return;
      }

      if (cutCommitted && typeof originalToggle === "function") {
        originalToggle.call(toggle);
      }
      cutCommitted = false;
      updateToggle(false);
    };

    cutButton.onclick = function () {
      if (!cutEnabled) {
        setDefaults();
        updateToggle(true);
        inputs[0].focus();
        return;
      }
      commitCut();
    };

    // If the user edits the times and directly presses Add to Download Queue,
    // commit the range first so the existing downloader receives the selection.
    if (typeof originalAddDownload === "function") {
      window.addDownload = async function () {
        if (cutEnabled) {
          if (!commitCut()) return;
        }
        return originalAddDownload.apply(this, arguments);
      };
    }

    updateToggle(false);
    return true;
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => {
      setTimeout(install, 100);
      setTimeout(install, 700);
    }, { once: true });
  } else {
    setTimeout(install, 100);
    setTimeout(install, 700);
  }
})();
