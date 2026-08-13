(function () {
  "use strict";

  const THEME_MODE_KEY = "md-theme-mode";
  const THEME_COLOR_KEY = "md-theme-color";
  const DEFAULT_THEME_COLOR = "#a8c7fa";

  function hexToRgb(hex) {
    const value = hex.replace("#", "");
    if (value.length !== 6) return null;
    const n = parseInt(value, 16);
    if (Number.isNaN(n)) return null;

    return {
      r: (n >> 16) & 255,
      g: (n >> 8) & 255,
      b: n & 255
    };
  }

  function readableTextColor(hex) {
    const rgb = hexToRgb(hex);
    if (!rgb) return "#0f1d33";

    const luminance =
      (0.299 * rgb.r + 0.587 * rgb.g + 0.114 * rgb.b) / 255;

    return luminance > 0.62 ? "#18202a" : "#ffffff";
  }

  function applyAccentColor(color) {
    const value =
      /^#[0-9a-f]{6}$/i.test(color)
        ? color
        : DEFAULT_THEME_COLOR;

    const root = document.documentElement;

    root.style.setProperty("--md-primary", value);
    root.style.setProperty(
      "--md-on-primary",
      readableTextColor(value)
    );
    root.style.setProperty("--accent", value);
    root.style.setProperty("--accent-dark", value);

    const rgb = hexToRgb(value);

    if (rgb) {
      root.style.setProperty(
        "--md-primary-rgb",
        `${rgb.r},${rgb.g},${rgb.b}`
      );

      root.style.setProperty(
        "--md-primary-soft",
        `rgba(${rgb.r},${rgb.g},${rgb.b},.16)`
      );
    }
  }

  /*
   * Apply the selected theme.
   *
   * system:
   *   Follow Android/browser light/dark preference.
   *
   * day:
   *   Force light mode.
   *
   * night:
   *   Force dark mode.
   */
  function applyThemeMode(mode) {
    const selected =
      ["system", "day", "night"].includes(mode)
        ? mode
        : "system";

    const root = document.documentElement;

    if (selected === "day") {
      root.classList.add("md-theme-light");
      root.classList.remove("md-theme-dark");
    } else if (selected === "night") {
      root.classList.remove("md-theme-light");
      root.classList.add("md-theme-dark");
    } else {
      /*
       * SYSTEM MODE
       *
       * Android/browser exposes the current appearance through
       * prefers-color-scheme.
       *
       * Light system preference:
       *   add md-theme-light
       *
       * Dark system preference:
       *   remove md-theme-light/md-theme-dark so the existing
       *   dark defaults remain active.
       */
      const systemDark =
        window.matchMedia &&
        window.matchMedia("(prefers-color-scheme: dark)").matches;

      root.classList.remove("md-theme-dark");

      if (systemDark) {
        root.classList.remove("md-theme-light");
      } else {
        root.classList.add("md-theme-light");
      }
    }

    /*
     * Keep the user's actual selection as "system",
     * "day", or "night".
     *
     * This is important because the Settings buttons use
     * this value to determine which button is active.
     */
    root.dataset.mdTheme = selected;
  }

  function loadThemePreferences() {
    let mode = "system";
    let color = DEFAULT_THEME_COLOR;

    try {
      mode =
        localStorage.getItem(THEME_MODE_KEY) || "system";

      color =
        localStorage.getItem(THEME_COLOR_KEY) ||
        DEFAULT_THEME_COLOR;
    } catch (_) {}

    applyThemeMode(mode);
    applyAccentColor(color);

    return { mode, color };
  }

  function saveThemePreference(mode, color) {
    try {
      localStorage.setItem(THEME_MODE_KEY, mode);
      localStorage.setItem(THEME_COLOR_KEY, color);
    } catch (_) {}
  }

  /*
   * Follow Android/system theme changes while the app is running.
   *
   * Example:
   *
   * Android switches:
   *   Light → Dark
   *
   * The app automatically changes:
   *   Light → Dark
   *
   * without requiring the user to reopen the app.
   */
  const systemThemeMedia =
    window.matchMedia
      ? window.matchMedia("(prefers-color-scheme: dark)")
      : null;

  function handleSystemThemeChange() {
    const root = document.documentElement;

    /*
     * Only react when the user selected SYSTEM.
     * Manual Day/Night selections must remain untouched.
     */
    if (root.dataset.mdTheme === "system") {
      applyThemeMode("system");
    }
  }

  if (systemThemeMedia) {
    if (typeof systemThemeMedia.addEventListener === "function") {
      systemThemeMedia.addEventListener(
        "change",
        handleSystemThemeChange
      );
    } else if (
      typeof systemThemeMedia.addListener === "function"
    ) {
      /*
       * Older WebView compatibility.
       */
      systemThemeMedia.addListener(
        handleSystemThemeChange
      );
    }
  }

  function installTheme() {
    if (document.getElementById("mdColorTheme")) return;

    const style = document.createElement("style");
    style.id = "mdColorTheme";

    style.textContent = `
      :root{
        --md-primary:#a8c7fa;
        --md-on-primary:#0f1d33;
        --md-secondary:#bec6dc;
        --md-on-secondary:#283044;
        --md-tertiary:#dfb8f5;
        --md-on-tertiary:#3d234b;
        --md-surface:#101318;
        --md-surface-container:#1d2025;
        --md-surface-container-high:#272a30;
        --md-outline:#8e9099;
        --md-outline-variant:#44474f;
        --md-success:#a5d6a7;
        --md-error:#ffb4ab;
        --md-on-surface:#e2e2e9;
        --md-on-surface-variant:#c4c6d0;
      }

      html.md-theme-light{
        --bg:#f8f9ff;
        --card:#ffffff;
        --card2:#eef0f7;
        --text:#1a1b20;
        --muted:#5f6368;
        --border:#c5c7d0;
        --md-log-button-bg:#ffffff;

        --md-surface:#f8f9ff;
        --md-surface-container:#eef0f7;
        --md-surface-container-high:#e4e7ef;
        --md-outline:#747780;
        --md-outline-variant:#c5c7d0;
        --md-on-surface:#1a1b20;
        --md-on-surface-variant:#44464f;
        --md-secondary:#5b6070;
        --md-on-secondary:#ffffff;
        --md-tertiary:#73567c;
        --md-on-tertiary:#ffffff;
        --md-success:#2e7d32;
        --md-error:#ba1a1a;

       #manualLogButton{
        background:var(--md-log-button-bg)!important;
        color:var(--md-primary)!important;
        border-color:var(--md-primary)!important;
       }
      }

      html.md-theme-light body{
        color:var(--md-on-surface)!important;
      }

      html.md-theme-light .md-app-pill{
        background:var(--md-surface-container)!important;
      }

      html.md-theme-light .card,
      html.md-theme-light .md-url-card,
      html.md-theme-light .md-media-card,
      html.md-theme-light .md-queue-card,
      html.md-theme-light .md-engine-card,
      html.md-theme-light .md-settings{
        box-shadow:0 2px 8px rgba(20,25,35,.08)!important;
      }

      html.md-theme-light .primary,
      html.md-theme-light .md-download-button,
      html.md-theme-light #mdChooseFolder{
        color:var(--md-on-primary)!important;
      }

      html.md-theme-light .mode.active,
      html.md-theme-light #cutSectionToggle[data-state="on"]{
        background:rgba(
          var(--md-primary-rgb,168,199,250),
          .16
        )!important;
      }

      html.md-theme-dark,
      html:not(.md-theme-light){
        color-scheme:dark;
      }

      html.md-theme-light{
        color-scheme:light;
      }

      .md-theme-settings{
        margin-top:18px;
        padding-top:16px;
        border-top:1px solid var(--md-outline-variant);
      }

      .md-theme-settings-title{
        margin:0 0 5px;
        color:var(--md-on-surface)!important;
        font-size:16px;
        font-weight:800;
      }

      .md-theme-settings-subtitle{
        margin:0 0 13px;
        color:var(--md-on-surface-variant)!important;
        font-size:12px;
      }

      .md-theme-mode-group{
        display:grid;
        grid-template-columns:repeat(3,1fr);
        gap:7px;
        margin-bottom:15px;
      }

      .md-theme-mode{
        min-height:46px!important;
        padding:8px 6px!important;
        border:1px solid var(--md-outline)!important;
        border-radius:16px!important;
        background:transparent!important;
        color:var(--md-on-surface-variant)!important;
        font-size:12px!important;
        font-weight:750!important;
      }

      .md-theme-mode.active{
        border-color:var(--md-primary)!important;
        background:rgba(
          var(--md-primary-rgb,168,199,250),
          .16
        )!important;
        color:var(--md-primary)!important;
      }

      .md-theme-mode:active{
        transform:scale(.97);
      }

      .md-theme-color-row{
        display:flex;
        align-items:center;
        justify-content:space-between;
        gap:12px;
        padding:11px 12px;
        border:1px solid var(--md-outline-variant);
        border-radius:16px;
        background:var(--md-surface-container-high);
      }

      .md-theme-color-copy{
        min-width:0;
      }

      .md-theme-color-label{
        display:block;
        margin:0 0 3px;
        color:var(--md-on-surface)!important;
        font-size:13px;
        font-weight:750;
      }

      .md-theme-color-value{
        color:var(--md-on-surface-variant)!important;
        font-size:11px;
        font-family:
          ui-monospace,
          SFMono-Regular,
          Menlo,
          monospace;
      }

      .md-theme-color-input{
        width:52px!important;
        height:42px!important;
        min-width:52px!important;
        padding:3px!important;
        border:1px solid var(--md-outline)!important;
        border-radius:14px!important;
        background:var(--md-surface-container)!important;
        cursor:pointer;
      }

      .md-theme-color-input::-webkit-color-swatch-wrapper{
        padding:0;
      }

      .md-theme-color-input::-webkit-color-swatch{
        border:0;
        border-radius:10px;
      }

      @media(max-width:420px){
        .md-theme-mode{
          font-size:11px!important;
        }
      }
    `;

    document.head.appendChild(style);

    const app = document.querySelector(".app");
    if (!app) return;

    const pill = document.createElement("div");
    pill.className = "md-app-pill";
    pill.innerHTML =
      `<img src="gemini-svg.svg" alt="">
       <span>MEDIA DOWNLOADER</span>`;

    app.insertBefore(pill, app.firstChild);

    const logoIcon =
      document.querySelector(".logo-icon");

    if (logoIcon) {
      logoIcon.innerHTML =
        `<img src="gemini-svg.svg"
              alt="Media Downloader">`;
    }

    document
      .querySelectorAll(".app > .card")
      .forEach(card => {
        const title =
          card
            .querySelector(".card-title")
            ?.textContent
            .trim()
            .toLowerCase() || "";

        if (title.includes("media url")) {
          card.classList.add("md-url-card");
        } else if (card.id === "media") {
          card.classList.add("md-media-card");
        } else if (card.querySelector("#queue")) {
          card.classList.add("md-queue-card");
        } else if (title.includes("engine")) {
          card.classList.add("md-engine-card");
        }
      });

    document
      .querySelectorAll(".section-title")
      .forEach(section => {
        const text =
          section.textContent.toLowerCase();

        if (text.includes("available formats")) {
          section.classList.add("md-section-formats");
        }

        if (text.includes("download section")) {
          section.classList.add("md-section-download");
        }

        if (text.includes("options")) {
          section.classList.add("md-section-options");
        }
      });

    document
      .querySelectorAll("button.primary")
      .forEach(button => {
        const text =
          button.textContent.toLowerCase();

        if (text.includes("analyze")) {
          button.classList.add("md-analyze-button");
        }

        if (text.includes("download")) {
          button.classList.add("md-download-button");
        }
      });
  }

  function installThemeSettings() {
    if (
      document.getElementById("mdThemeSettings")
    ) {
      return true;
    }

    const panel =
      document.querySelector(".md-settings");

    if (!panel) return false;

    const prefs = loadThemePreferences();

    const section =
      document.createElement("section");

    section.id = "mdThemeSettings";
    section.className = "md-theme-settings";

    section.innerHTML = `
      <h3 class="md-theme-settings-title">
        🎨 Theme
      </h3>

      <p class="md-theme-settings-subtitle">
        Choose appearance and accent colour
      </p>

      <div
        class="md-theme-mode-group"
        role="group"
        aria-label="Theme mode">

        <button
          type="button"
          class="md-theme-mode"
          data-theme-mode="day">
          ☀️ Day
        </button>

        <button
          type="button"
          class="md-theme-mode"
          data-theme-mode="night">
          🌙 Night
        </button>

        <button
          type="button"
          class="md-theme-mode"
          data-theme-mode="system">
          ⚙️ System
        </button>
      </div>

      <div class="md-theme-color-row">
        <div class="md-theme-color-copy">
          <span class="md-theme-color-label">
            Accent colour
          </span>

          <span
            class="md-theme-color-value"
            id="mdThemeColorValue">
          </span>
        </div>

        <input
          id="mdThemeColor"
          class="md-theme-color-input"
          type="color"
          value="${prefs.color}"
          aria-label="Choose accent colour">
      </div>
    `;

    panel.appendChild(section);

    const colorInput =
      section.querySelector("#mdThemeColor");

    const colorValue =
      section.querySelector("#mdThemeColorValue");

    const modeButtons =
      section.querySelectorAll(
        "[data-theme-mode]"
      );

    function refreshModeButtons(mode) {
      modeButtons.forEach(button => {
        const active =
          button.dataset.themeMode === mode;

        button.classList.toggle(
          "active",
          active
        );

        button.setAttribute(
          "aria-pressed",
          String(active)
        );
      });
    }

    function refreshColorValue(color) {
      colorValue.textContent =
        color.toUpperCase();
    }

    refreshModeButtons(prefs.mode);
    refreshColorValue(prefs.color);

    modeButtons.forEach(button => {
      button.addEventListener("click", () => {
        const mode =
          button.dataset.themeMode;

        const color =
          colorInput.value;

        applyThemeMode(mode);
        applyAccentColor(color);
        saveThemePreference(mode, color);

        refreshModeButtons(mode);
      });
    });

    colorInput.addEventListener("input", () => {
      const color =
        colorInput.value;

      const mode =
        document.documentElement.dataset.mdTheme ||
        "system";

      applyAccentColor(color);
      saveThemePreference(mode, color);
      refreshColorValue(color);
    });

    return true;
  }

  loadThemePreferences();
  installTheme();

  if (document.readyState === "loading") {
    document.addEventListener(
      "DOMContentLoaded",
      () => {
        installTheme();
        installThemeSettings();
      },
      { once:true }
    );
  } else {
    installThemeSettings();
  }

  const themeSettingsObserver =
    new MutationObserver(() => {
      installThemeSettings();
    });

  if (document.body) {
    themeSettingsObserver.observe(
      document.body,
      {
        childList:true,
        subtree:true
      }
    );
  } else {
    document.addEventListener(
      "DOMContentLoaded",
      () => {
        themeSettingsObserver.observe(
          document.body,
          {
            childList:true,
            subtree:true
          }
        );

        installThemeSettings();
      },
      { once:true }
    );
  }

  setTimeout(installTheme, 100);
  setTimeout(installTheme, 600);
  setTimeout(installThemeSettings, 700);

})();