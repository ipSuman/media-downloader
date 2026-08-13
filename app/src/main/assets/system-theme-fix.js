(function () {
  "use strict";

  if (window.__mdSystemThemeFixInstalled) return;
  window.__mdSystemThemeFixInstalled = true;

  function applySystemTheme() {
    const root = document.documentElement;
    if (root.dataset.mdTheme !== "system") return;

    const prefersLight = window.matchMedia
      ? window.matchMedia("(prefers-color-scheme: light)").matches
      : false;

    root.classList.toggle("md-theme-light", prefersLight);
    root.classList.toggle("md-theme-dark", !prefersLight);
  }

  function install() {
    applySystemTheme();

    if (!window.matchMedia) return;

    const media = window.matchMedia("(prefers-color-scheme: light)");
    const listener = applySystemTheme;

    if (media.addEventListener) {
      media.addEventListener("change", listener);
    } else if (media.addListener) {
      media.addListener(listener);
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", install, { once: true });
  } else {
    install();
  }

  setTimeout(install, 100);
})();
