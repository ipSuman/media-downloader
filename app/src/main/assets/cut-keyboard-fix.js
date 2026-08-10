(function () {
  "use strict";

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
    if (!install()) setTimeout(retry, 300);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", retry, { once: true });
  } else {
    retry();
  }
})();
