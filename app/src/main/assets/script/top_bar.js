(function () {
    if (window.testtubeTopBar?.update) {
        window.testtubeTopBar.update();
        return;
    }

    const STYLE_ID = "testtube-top-bar-style";
    const LOGO_SRC = "data:image/png;base64,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";
    let scheduled = false;

    function ensureStyle() {
        if (document.getElementById(STYLE_ID)) return;
        const target = document.head || document.documentElement;
        if (!target) return;
        const style = document.createElement("style");
        style.id = STYLE_ID;
        style.textContent = `
/* YouTube's own bottom bar is replaced by the app's native bar. */
ytm-pivot-bar-renderer{display:none!important;}
/* Only the logo and search stay in the top bar. */
ytm-mobile-topbar-renderer button:has(img),
ytm-mobile-topbar-renderer a[href^="/feed/notifications"]{display:none!important;}
.testtube-logo{display:flex;align-items:center;gap:8px;text-decoration:none;color:var(--yt-spec-text-primary,#f1f1f1);}
.testtube-logo img{width:26px;height:30px;object-fit:contain;}
.testtube-logo span{font-family:Roboto,Arial,sans-serif;font-size:18px;font-weight:700;letter-spacing:.2px;}`;
        target.appendChild(style);
    }

    function renderLogo() {
        const host = document.querySelector("ytm-home-logo");
        if (!(host instanceof Element)) return;
        if (host.querySelector(".testtube-logo")) return;
        const link = host.querySelector("a");
        const container = link || host;
        container.textContent = "";
        container.classList.add("testtube-logo");
        container.removeAttribute("aria-label");
        const img = document.createElement("img");
        img.src = LOGO_SRC;
        img.alt = "TestTube";
        const text = document.createElement("span");
        text.textContent = "TestTube";
        container.append(img, text);
    }

    function fixSearchHint() {
        // The search box must say "Search TestTube", in any language.
        for (const el of document.querySelectorAll("input[placeholder], textarea[placeholder]")) {
            if (el.placeholder.includes("YouTube")) {
                el.placeholder = el.placeholder.replace(/YouTube/g, "TestTube");
            }
        }
    }

    function update() {
        scheduled = false;
        if (!document.body) return;
        ensureStyle();
        renderLogo();
        fixSearchHint();
    }

    function schedule() {
        if (scheduled || document.visibilityState === "hidden") return;
        scheduled = true;
        setTimeout(update, 100);
    }

    const observer = new MutationObserver(schedule);
    function start() {
        const root = document.body;
        if (!root) {
            setTimeout(start, 100);
            return;
        }
        observer.observe(root, { childList: true, subtree: true });
        update();
    }

    for (const name of ["onPageFinished", "doUpdateVisitedHistory", "yt-navigate-finish", "state-navigateend"]) {
        window.addEventListener(name, schedule, true);
    }

    window.testtubeTopBar = { update: schedule };
    start();
})();
