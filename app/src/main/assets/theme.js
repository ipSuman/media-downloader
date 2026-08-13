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

      /* App name pill */
      .md-app-pill{
        display:flex;
        align-items:center;
        justify-content:center;
        gap:9px;
        width:max-content;
        max-width:100%;
        margin:0 auto 14px;
        padding:7px 16px 7px 9px;
        border:1px solid #4b5eb1;
        border-radius:999px;
        background:linear-gradient(135deg,#101a32,#211d4b);
        color:#f4f7ff;
        font-size:13px;
        font-weight:950;
        letter-spacing:1.2px;
        text-align:center;
        box-shadow:0 5px 18px rgba(0,0,0,.25);
      }
      .md-app-pill img{
        width:32px;
        height:32px;
        border-radius:9px;
        flex:0 0 32px;
      }

      /* Larger settings button: 1.5x from 42px to 63px */
      .settings{
        width:63px!important;
        height:63px!important;
        min-width:63px!important;
        border-radius:18px!important;
        font-size:30px!important;
        border-color:#416b91!important;
        background:#17283a!important;
        color:var(--md-blue)!important;
        box-shadow:0 5px 18px rgba(0,0,0,.25);
      }
      .settings:active{transform:scale(.96)}

      /* Use the supplied SVG inside the app instead of the old down-arrow */
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

      /* Colour-coded cards */
      .md-url-card{border-color:#287c72!important;box-shadow:0 5px 22px rgba(53,199,181,.07)}
      .md-media-card{border-color:#315f91!important;box-shadow:0 5px 22px rgba(90,169,255,.07)}
      .md-queue-card{border-color:#69479b!important;box-shadow:0 5px 22px rgba(167,123,255,.07)}
      .md-engine-card{border-color:#367b57!important;box-shadow:0 5px 22px rgba(85,214,138,.07)}

      /* Material 3 surfaces */
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

      /* Section colours */
      .md-section-formats{color:var(--md-blue)!important}
      .md-section-download{color:var(--md-orange)!important}
      .md-section-options{color:var(--md-purple)!important}

      /* Buttons */
      /* Material 3 buttons */
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

      /* Pause / Resume */
      .md-download-controls .pause{
        border-color:var(--md-success)!important;
        background:rgba(165,214,167,.12)!important;
        color:var(--md-success)!important;
      }

      /* Terminate */
      .md-download-controls .cancel{
        border-color:var(--md-error)!important;
        background:rgba(255,180,171,.12)!important;
        color:var(--md-error)!important;
      }

      /* Material 3 time selector */
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

      /* Material 3 cut-section control */
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

      .checks input{
        accent-color:var(--md-primary);
      }

      .queue-item{border-color:#49356a!important;background:#211b2d!important}
      .progress-bar{background:linear-gradient(90deg,var(--md-purple),var(--md-blue))!important}
      .md-download-controls .pause{color:var(--md-green)!important;border-color:#367b57!important;background:#172b22!important}
      .md-download-controls .cancel{color:#ff7777!important;border-color:#713838!important;background:#301d1d!important}

      .md-settings{border-color:#416b91!important}
      .md-settings-title{color:var(--md-blue)}
      .md-folder{border-color:#426a91!important;background:#17283a!important}
      #mdChooseFolder{background:var(--md-blue)!important;color:#07131f!important}
      #mdResetFolder{border-color:#754f31!important;color:var(--md-orange)!important;background:#2b211b!important}
      .md-close{color:var(--md-pink)!important;border-color:#713b4d!important}

      @media(max-width:420px){
        .settings{
          width:57px!important;
          height:57px!important;
          min-width:57px!important;
          font-size:27px!important;
        }
      }
    `;
    document.head.appendChild(style);

    const app = document.querySelector(".app");
    if (!app) return;

    const pill = document.createElement("div");
    pill.className = "md-app-pill";
    pill.innerHTML = `<img src="gemini-svg.svg" alt=""> <span>MEDIA DOWNLOADER</span>`;
    app.insertBefore(pill, app.firstChild);

    const logoIcon = document.querySelector(".logo-icon");
    if (logoIcon) {
      logoIcon.innerHTML = `<img src="gemini-svg.svg" alt="Media Downloader">`;
    }

    document.querySelectorAll(".app > .card").forEach(card => {
      const title = card.querySelector(".card-title")?.textContent.trim().toLowerCase() || "";
      if (title.includes("media url")) card.classList.add("md-url-card");
      else if (card.id === "media") card.classList.add("md-media-card");
      else if (card.querySelector("#queue")) card.classList.add("md-queue-card");
      else if (title.includes("engine")) card.classList.add("md-engine-card");
    });

    document.querySelectorAll(".section-title").forEach(section => {
      const text = section.textContent.toLowerCase();
      if (text.includes("available formats")) section.classList.add("md-section-formats");
      if (text.includes("download section")) section.classList.add("md-section-download");
      if (text.includes("options")) section.classList.add("md-section-options");
    });

    document.querySelectorAll("button.primary").forEach(button => {
      const text = button.textContent.toLowerCase();
      if (text.includes("analyze")) button.classList.add("md-analyze-button");
      if (text.includes("download")) button.classList.add("md-download-button");
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", installTheme, { once:true });
  } else {
    installTheme();
  }
  setTimeout(installTheme, 100);
  setTimeout(installTheme, 600);
})();
