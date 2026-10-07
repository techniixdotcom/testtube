(function () {
    if (window.testtubeLocalHistory?.refresh) {
        window.testtubeLocalHistory.refresh();
        return;
    }

    const SECTION_ID = "testtube-local-history";
    const STYLE_ID = "testtube-local-history-style";
    const SHELF_SELECTOR = "ytm-shelf-renderer, ytm-item-section-renderer, ytm-rich-section-renderer, " +
        "ytm-horizontal-card-list-renderer, ytm-reel-shelf-renderer";
    let scheduled = false;
    let renderedKey = "";

    function isYouPage() {
        const path = location.pathname;
        return path.startsWith("/feed/library") || path.startsWith("/feed/you");
    }

    function readLog() {
        try {
            const data = JSON.parse(testtube.getWatchLog?.() || "{}");
            return {
                title: typeof data.title === "string" ? data.title : "",
                viewAll: typeof data.viewAll === "string" ? data.viewAll : "",
                items: Array.isArray(data.items)
                    ? data.items.filter(item => typeof item?.id === "string" && /^[A-Za-z0-9_-]{11}$/.test(item.id))
                    : []
            };
        } catch {
            return { title: "", viewAll: "", items: [] };
        }
    }

    function ensureStyle() {
        if (document.getElementById(STYLE_ID)) return;
        const target = document.head || document.documentElement;
        if (!target) return;
        const style = document.createElement("style");
        style.id = STYLE_ID;
        style.textContent = `
#${SECTION_ID}{display:block;padding:12px 0 16px;font-family:Roboto,Arial,sans-serif;color:var(--yt-spec-text-primary,#f1f1f1);}
#${SECTION_ID} .yh-head{display:flex;align-items:center;justify-content:space-between;padding:0 12px 12px;}
#${SECTION_ID} .yh-title{font-size:18px;line-height:24px;font-weight:700;margin:0;}
#${SECTION_ID} .yh-all{border:1px solid var(--yt-spec-10-percent-layer,rgba(255,255,255,.2));background:transparent;color:inherit;
border-radius:18px;padding:0 16px;height:36px;font:500 14px/36px Roboto,Arial,sans-serif;}
#${SECTION_ID} .yh-row{display:flex;gap:12px;overflow-x:auto;padding:0 12px;scrollbar-width:none;-webkit-overflow-scrolling:touch;}
#${SECTION_ID} .yh-row::-webkit-scrollbar{display:none;}
#${SECTION_ID} .yh-card{flex:0 0 auto;width:min(39vw,260px);color:inherit;text-decoration:none;}
#${SECTION_ID} .yh-thumb{display:block;width:100%;aspect-ratio:16/9;object-fit:cover;border-radius:8px;background:var(--yt-spec-10-percent-layer,#272727);}
#${SECTION_ID} .yh-name{font-size:14px;line-height:20px;margin:8px 0 2px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}
#${SECTION_ID} .yh-meta{font-size:12px;line-height:18px;color:var(--yt-spec-text-secondary,#aaa);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}`;
        target.appendChild(style);
    }

    function relativeTime(timestamp) {
        const seconds = Math.round((timestamp - Date.now()) / 1000);
        const units = [["year", 31536000], ["month", 2592000], ["week", 604800], ["day", 86400], ["hour", 3600], ["minute", 60]];
        try {
            const format = new Intl.RelativeTimeFormat(document.documentElement.lang || navigator.language, { numeric: "auto" });
            for (const [unit, size] of units) {
                if (Math.abs(seconds) >= size) return format.format(Math.round(seconds / size), unit);
            }
            return format.format(0, "minute");
        } catch {
            return "";
        }
    }

    function build(log) {
        const section = document.createElement("section");
        section.id = SECTION_ID;

        const head = document.createElement("div");
        head.className = "yh-head";
        const title = document.createElement("h2");
        title.className = "yh-title";
        title.textContent = log.title;
        const all = document.createElement("button");
        all.type = "button";
        all.className = "yh-all";
        all.textContent = log.viewAll;
        all.addEventListener("click", event => {
            event.preventDefault();
            event.stopPropagation();
            testtube.openWatchHistory?.();
        });
        head.append(title, all);

        const row = document.createElement("div");
        row.className = "yh-row";
        for (const item of log.items) {
            const card = document.createElement("a");
            card.className = "yh-card";
            card.href = "/watch?v=" + item.id;
            const thumb = document.createElement("img");
            thumb.className = "yh-thumb";
            thumb.loading = "lazy";
            thumb.alt = "";
            thumb.src = "https://i.ytimg.com/vi/" + item.id + "/mqdefault.jpg";
            const name = document.createElement("div");
            name.className = "yh-name";
            name.textContent = item.title || item.id;
            const meta = document.createElement("div");
            meta.className = "yh-meta";
            const when = typeof item.at === "number" ? relativeTime(item.at) : "";
            meta.textContent = [item.author, when].filter(Boolean).join(" · ");
            card.append(thumb, name, meta);
            row.appendChild(card);
        }
        section.append(head, row);
        return section;
    }

    // First shelf on the page: just above YouTube's own History, or at the top when there is none.
    function anchorPoint() {
        const historyLink = document.querySelector('a[href^="/feed/history"]');
        const shelf = historyLink?.closest(SHELF_SELECTOR);
        if (shelf?.parentElement) return { parent: shelf.parentElement, before: shelf };
        const list = document.querySelector("ytm-section-list-renderer, ytm-browse .page-container, ytm-browse");
        if (list) return { parent: list, before: list.firstChild };
        return null;
    }

    function render() {
        scheduled = false;
        let section = document.getElementById(SECTION_ID);
        const log = isYouPage() ? readLog() : { items: [] };
        if (!log.items.length) {
            section?.remove();
            renderedKey = "";
            return;
        }
        ensureStyle();
        const key = log.title + "|" + log.viewAll + "|" + log.items.map(item => item.id + ":" + item.at).join(",");
        if (section && key !== renderedKey) {
            section.remove();
            section = null;
        }
        const point = anchorPoint();
        if (!point) return;
        if (!section) {
            section = build(log);
            renderedKey = key;
        }
        const wanted = point.before === section ? section.nextSibling : point.before;
        if (section.parentElement !== point.parent || section.nextSibling !== wanted) {
            point.parent.insertBefore(section, wanted);
        }
    }

    function schedule() {
        if (scheduled || document.visibilityState === "hidden") return;
        scheduled = true;
        setTimeout(render, 250);
    }

    const observer = new MutationObserver(schedule);
    function start() {
        if (!document.body) {
            setTimeout(start, 100);
            return;
        }
        observer.observe(document.body, { childList: true, subtree: true });
        schedule();
    }

    for (const name of ["testtubeStateChanged", "onPageFinished", "doUpdateVisitedHistory", "yt-navigate-finish", "state-navigateend"]) {
        window.addEventListener(name, schedule, true);
    }
    document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "visible") schedule();
    }, true);

    window.testtubeLocalHistory = { refresh: schedule };
    start();
})();
