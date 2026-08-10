(function () {
  "use strict";

  function installTheme() {
    if (document.getElementById("mdColorTheme")) return;

    const style = document.createElement("style");
    style.id = "mdColorTheme";
    style.textContent = `
      :root{
        --md-teal:#35c7b5;
        --md-blue:#5aa9ff;
        --md-orange:#ff9f43;
        --md-purple:#a77bff;
        --md-green:#55d68a;
        --md-pink:#ff6f91;
        --md-yellow:#ffd166;
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

      .md-url-card .card-title{color:var(--md-teal)}
      .md-media-card .card-title{color:var(--md-blue)}
      .md-queue-card .card-title{color:var(--md-purple)}
      .md-engine-card .card-title{color:var(--md-green)}

      /* Section colours */
      .md-section-formats{color:var(--md-blue)!important}
      .md-section-download{color:var(--md-orange)!important}
      .md-section-options{color:var(--md-purple)!important}

      /* Buttons */
      .paste{
        border-color:#68459b!important;
        background:#282038!important;
        color:var(--md-purple)!important;
      }
      .primary{background:var(--md-teal)!important}
      .md-analyze-button{background:var(--md-blue)!important;color:#07131f!important}
      .md-download-button{background:var(--md-orange)!important;color:#241507!important}

      .mode:nth-child(1){border-color:#2d8378!important;color:var(--md-teal)!important}
      .mode:nth-child(2){border-color:#a05b7a!important;color:var(--md-pink)!important}
      .mode:nth-child(3){border-color:#7053a8!important;color:var(--md-purple)!important}
      .mode.active:nth-child(1){background:#112e2a!important}
      .mode.active:nth-child(2){background:#321e2a!important}
      .mode.active:nth-child(3){background:#281d3b!important}

      .time-grid{
        padding:12px;
        border:1px solid #754f31;
        border-radius:14px;
        background:#251d18;
      }
      .time-grid label{color:#ffb66b!important}

      #cutSectionButton{
        border-color:#a7652c!important;
        background:#352217!important;
        color:var(--md-orange)!important;
      }
      #cutSectionToggle[data-state="on"]{
        border-color:#367b57!important;
        background:#173025!important;
        color:var(--md-green)!important;
      }

      .checks .check:nth-child(1) input{accent-color:var(--md-blue)}
      .checks .check:nth-child(2) input{accent-color:var(--md-purple)}
      .checks .check:nth-child(3) input{accent-color:var(--md-orange)}

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
