(function () {
  "use strict";

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

      /* =========================================================
         MATERIAL 3 GLOBAL SURFACES & TYPOGRAPHY
         ========================================================= */

      body{
        background:var(--md-surface)!important;
        color:var(--md-on-surface)!important;
      }

      .app{
        color:var(--md-on-surface)!important;
      }

      .card{
        background:var(--md-surface-container)!important;
        border-color:var(--md-outline-variant)!important;
        border-radius:20px!important;
        box-shadow:none!important;
      }

      input,
      select,
      textarea{
        background:var(--md-surface-container-high)!important;
        color:var(--md-on-surface)!important;
        border:1px solid var(--md-outline)!important;
        border-radius:14px!important;
      }

      input:focus,
      select:focus,
      textarea:focus{
        outline:none!important;
        border-color:var(--md-primary)!important;
        box-shadow:0 0 0 2px rgba(168,199,250,.18)!important;
      }

      label,
      .hint,
      .small,
      .muted{
        color:var(--md-on-surface-variant)!important;
      }

      .section-title{
        color:var(--md-on-surface)!important;
        font-weight:700;
      }

      /* =========================================================
         APP NAME PILL
         ========================================================= */

      .md-app-pill{
        display:flex;
        align-items:center;
        justify-content:center;
        gap:9px;
        width:max-content;
        max-width:100%;
        margin:0 auto 14px;
        padding:7px 16px 7px 9px;
        border:1px solid var(--md-outline-variant);
        border-radius:999px;
        background:var(--md-surface-container-high);
        color:var(--md-on-surface);
        font-size:13px;
        font-weight:950;
        letter-spacing:1.2px;
        text-align:center;
      }

      .md-app-pill img{
        width:32px;
        height:32px;
        border-radius:9px;
        flex:0 0 32px;
      }

      /* =========================================================
         SETTINGS BUTTON
         ========================================================= */

      .settings{
        width:63px!important;
        height:63px!important;
        min-width:63px!important;
        border-radius:18px!important;
        font-size:30px!important;
        border:1px solid var(--md-outline)!important;
        background:var(--md-surface-container-high)!important;
        color:var(--md-primary)!important;
        box-shadow:none!important;
      }

      .settings:active{
        transform:scale(.96);
      }

      /* =========================================================
         DIAGNOSTIC LOG BUTTON
         ========================================================= */

      .md-log-button{
        width:63px!important;
        height:63px!important;
        min-width:63px!important;
        padding:0!important;
        border-radius:18px!important;
        border:1px solid var(--md-outline)!important;
        background:var(--md-surface-container-high)!important;
        color:var(--md-primary)!important;
        font-size:28px!important;
        box-shadow:none!important;
      }

      .md-log-button:active{
        transform:scale(.96);
      }

      /* =========================================================
         APP LOGO
         ========================================================= */

      .logo-icon{
        width:46px!important;
        height:46px!important;
        padding:0!important;
        background:transparent!important;
        border-radius:14px!important;
        overflow:hidden!important;
      }

      .logo-icon img{
        width:100%;
        height:100%;
        display:block;
        border-radius:14px;
      }

      /* =========================================================
         MATERIAL 3 CARDS
         ========================================================= */

      .md-url-card,
      .md-media-card,
      .md-queue-card,
      .md-engine-card{
        border:1px solid var(--md-outline-variant)!important;
        background:var(--md-surface-container)!important;
        box-shadow:none!important;
        border-radius:20px!important;
      }

      .md-url-card .card-title,
      .md-media-card .card-title,
      .md-queue-card .card-title,
      .md-engine-card .card-title{
        color:var(--md-on-surface)!important;
      }

      /* =========================================================
         SECTION TITLES
         ========================================================= */

      .md-section-formats,
      .md-section-download,
      .md-section-options{
        color:var(--md-on-surface)!important;
      }

      /* =========================================================
         MATERIAL 3 BUTTONS
         ========================================================= */

      button,
      button.primary,
      .paste,
      .mode,
      .md-analyze-button,
      .md-download-button{
        min-height:48px;
        border-radius:24px!important;
        border:1px solid var(--md-outline)!important;
        font-weight:700;
        letter-spacing:.1px;
        transition:
          background .15s ease,
          border-color .15s ease,
          transform .08s ease,
          opacity .15s ease;
      }

      button:active{
        transform:scale(.97);
      }

      button:disabled{
        opacity:.38!important;
      }

      /* Primary */

      .primary,
      .md-download-button{
        border-color:var(--md-primary)!important;
        background:var(--md-primary)!important;
        color:var(--md-on-primary)!important;
      }

      /* Secondary / tonal */

      .paste,
      .md-analyze-button{
        border-color:var(--md-secondary)!important;
        background:var(--md-surface-container-high)!important;
        color:var(--md-on-surface)!important;
      }

      /* Segmented mode controls */

      .mode{
        background:transparent!important;
        color:var(--md-on-surface-variant)!important;
      }

      .mode.active{
        border-color:var(--md-primary)!important;
        background:rgba(168,199,250,.16)!important;
        color:var(--md-primary)!important;
      }

      /* =========================================================
         PAUSE / RESUME / TERMINATE
         ========================================================= */

      .md-download-controls .pause{
        border-color:var(--md-success)!important;
        background:rgba(165,214,167,.12)!important;
        color:var(--md-success)!important;
      }

      .md-download-controls .cancel{
        border-color:var(--md-error)!important;
        background:rgba(255,180,171,.12)!important;
        color:var(--md-error)!important;
      }

      /* =========================================================
         TIME SELECTOR
         ========================================================= */

      .time-grid{
        padding:16px!important;
        border:1px solid var(--md-outline-variant)!important;
        border-radius:20px!important;
        background:var(--md-surface-container-high)!important;
      }

      .time-grid label{
        color:var(--md-on-surface-variant)!important;
        font-weight:600;
      }

      /* =========================================================
         CUT SECTION
         ========================================================= */

      #cutSectionButton{
        border-color:var(--md-outline)!important;
        background:transparent!important;
        color:var(--md-on-surface)!important;
      }

      #cutSectionToggle[data-state="on"]{
        border-color:var(--md-primary)!important;
        background:rgba(168,199,250,.16)!important;
        color:var(--md-primary)!important;
      }

      /* =========================================================
         CHECKBOXES
         ========================================================= */

      .checks input{
        accent-color:var(--md-primary);
      }

      /* =========================================================
         QUEUE
         ========================================================= */

      .queue-item{
        border:1px solid var(--md-outline-variant)!important;
        background:var(--md-surface-container-high)!important;
        border-radius:16px!important;
      }

      .progress-bar{
        background:var(--md-primary)!important;
      }

      /* =========================================================
         SETTINGS PANEL
         ========================================================= */

      .md-settings{
        border:1px solid var(--md-outline-variant)!important;
        background:var(--md-surface-container)!important;
        border-radius:28px!important;
        box-shadow:0 12px 32px rgba(0,0,0,.35)!important;
      }

      .md-settings-title{
        color:var(--md-on-surface)!important;
      }

      .md-folder{
        border:1px solid var(--md-outline)!important;
        background:var(--md-surface-container-high)!important;
        color:var(--md-on-surface)!important;
        border-radius:16px!important;
      }

      #mdChooseFolder{
        background:var(--md-primary)!important;
        color:var(--md-on-primary)!important;
        border-color:var(--md-primary)!important;
      }

      #mdResetFolder{
        border-color:var(--md-outline)!important;
        background:transparent!important;
        color:var(--md-on-surface)!important;
      }

      .md-close{
        border-color:var(--md-outline)!important;
        background:transparent!important;
        color:var(--md-on-surface)!important;
      }

      /* =========================================================
         MOBILE
         ========================================================= */

      @media(max-width:420px){

        .settings{
          width:57px!important;
          height:57px!important;
          min-width:57px!important;
          font-size:27px!important;
        }

        .md-log-button{
          width:57px!important;
          height:57px!important;
          min-width:57px!important;
          font-size:25px!important;
        }
      }
    `;

    document.head.appendChild(style);

    const app = document.querySelector(".app");
    if (!app) return;

    /* =========================================================
       APP NAME
       ========================================================= */

    const pill = document.createElement("div");
    pill.className = "md-app-pill";
    pill.innerHTML =
      `<img src="gemini-svg.svg" alt=""> <span>MEDIA DOWNLOADER</span>`;

    app.insertBefore(pill, app.firstChild);

    /* =========================================================
       LOGO
       ========================================================= */

    const logoIcon = document.querySelector(".logo-icon");

    if (logoIcon) {
      logoIcon.innerHTML =
        `<img src="gemini-svg.svg" alt="Media Downloader">`;
    }

    /* =========================================================
       CARD CLASSIFICATION
       ========================================================= */

    document.querySelectorAll(".app > .card").forEach(card => {

      const title =
        card.querySelector(".card-title")?.textContent
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

    /* =========================================================
       SECTION CLASSIFICATION
       ========================================================= */

    document.querySelectorAll(".section-title").forEach(section => {

      const text = section.textContent.toLowerCase();

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

    /* =========================================================
       PRIMARY BUTTON CLASSIFICATION
       ========================================================= */

    document.querySelectorAll("button.primary").forEach(button => {

      const text = button.textContent.toLowerCase();

      if (text.includes("analyze")) {
        button.classList.add("md-analyze-button");
      }

      if (text.includes("download")) {
        button.classList.add("md-download-button");
      }
    });
  }

  if (document.readyState === "loading") {

    document.addEventListener(
      "DOMContentLoaded",
      installTheme,
      { once:true }
    );

  } else {

    installTheme();
  }

  setTimeout(installTheme, 100);
  setTimeout(installTheme, 600);

})();