(function () {
    if (window.testtubeContentFilters?.refresh) {
        window.testtubeContentFilters.refresh();
        return;
    }

    const WATCHED_ATTR = "data-testtube-watched";
    const BLOCKED_ATTR = "data-testtube-blocked";
    const STYLE_ID = "testtube-content-filters-style";
    const CARD_SELECTOR = [
        "ytm-rich-item-renderer",
        "ytm-media-item",
        "yt-lockup-view-model",
        ".ytLockupViewModelHost",
        "ytm-video-with-context-renderer",
        "ytm-compact-video-renderer",
        "ytm-video-card-renderer",
        "ytm-playlist-video-renderer",
        "ytm-playlist-panel-video-renderer",
        "ytm-reel-item-renderer",
        "ytm-shorts-lockup-view-model",
        "ytm-compact-autoplay-renderer"
    ].join(",");
    const AUTHOR_SELECTORS = [
        ".YtmBadgeAndBylineRendererItemByline",
        "ytm-badge-and-byline-renderer .yt-core-attributed-string",
        "ytm-badge-and-byline-renderer span[dir=\"auto\"]",
        "ytm-badge-and-byline-renderer",
        ".media-item-metadata .media-item-byline",
        ".media-item-byline .yt-core-attributed-string",
        ".media-item-byline",
        ".ytLockupViewModelMetadata .yt-core-attributed-string",
        ".yt-lockup-metadata-view-model-wiz__metadata",
        ".secondary-text .yt-core-attributed-string",
        ".secondary-text",
        ".compact-media-item-byline",
        ".subhead .yt-core-attributed-string"
    ];
    const CHANNEL_LINK_SELECTOR = "a[href^=\"/@\"], a[href^=\"/channel/\"], a[href^=\"/c/\"], a[href^=\"/user/\"], a[href*=\"youtube.com/@\"], a[href*=\"youtube.com/channel/\"]";
    const VIDEO_LINK_SELECTOR = "a[href*=\"/watch\"][href*=\"v=\"], a[href*=\"/shorts/\"]";

    let data = { greyWatched: false, watched: new Set(), blockedNames: new Set(), blockedPaths: new Set() };
    let scheduled = false;

    function ensureStyle() {
        if (document.getElementById(STYLE_ID)) return;
        const target = document.head || document.documentElement;
        if (!target) return;
        const style = document.createElement("style");
        style.id = STYLE_ID;
        style.textContent =
            "[" + WATCHED_ATTR + "=\"true\"]{opacity:.45!important;filter:grayscale(100%)!important;}" +
            "[" + BLOCKED_ATTR + "=\"true\"]{display:none!important;}";
        target.appendChild(style);
    }

    function normalizeName(value) {
        if (typeof value !== "string") return "";
        return value.split(/[•·|]/)[0].replace(/\s+/g, " ").trim().toLowerCase();
    }

    function channelPath(href) {
        if (typeof href !== "string" || !href) return null;
        let path;
        try {
            path = new URL(href, "https://m.youtube.com").pathname;
        } catch {
            return null;
        }
        const parts = path.split("/");
        for (let i = 0; i < parts.length; i += 1) {
            const part = parts[i];
            if (part.startsWith("@") && part.length > 1) return "/" + part.toLowerCase();
            if ((part === "channel" || part === "c" || part === "user") && parts[i + 1]) {
                return "/" + part + "/" + parts[i + 1].toLowerCase();
            }
        }
        return null;
    }

    function videoIdOf(href) {
        if (typeof href !== "string" || !href) return null;
        try {
            const url = new URL(href, "https://m.youtube.com");
            const v = url.searchParams.get("v");
            if (v && /^[A-Za-z0-9_-]{11}$/.test(v)) return v;
            const shorts = url.pathname.match(/\/shorts\/([A-Za-z0-9_-]{11})/);
            return shorts ? shorts[1] : null;
        } catch {
            return null;
        }
    }

    function readData() {
        try {
            const raw = JSON.parse(testtube.getContentFilters?.() || "{}");
            const blockedNames = new Set();
            const blockedPaths = new Set();
            for (const entry of Array.isArray(raw.blocked) ? raw.blocked : []) {
                if (entry && typeof entry.name === "string" && entry.name) blockedNames.add(entry.name);
                for (const path of Array.isArray(entry?.paths) ? entry.paths : []) {
                    if (typeof path === "string" && path) blockedPaths.add(path);
                }
            }
            return {
                greyWatched: !!raw.greyWatched,
                watched: new Set(Array.isArray(raw.watched) ? raw.watched : []),
                blockedNames,
                blockedPaths
            };
        } catch {
            return { greyWatched: false, watched: new Set(), blockedNames: new Set(), blockedPaths: new Set() };
        }
    }

    function authorOf(card) {
        for (const selector of AUTHOR_SELECTORS) {
            const element = card.querySelector(selector);
            const text = element?.textContent;
            if (typeof text === "string" && text.trim()) return text;
        }
        return "";
    }

    function isBlocked(card) {
        if (data.blockedNames.size === 0 && data.blockedPaths.size === 0) return false;
        if (data.blockedPaths.size > 0) {
            for (const link of card.querySelectorAll(CHANNEL_LINK_SELECTOR)) {
                const path = channelPath(link.getAttribute("href") || link.href);
                if (path && data.blockedPaths.has(path)) return true;
            }
        }
        if (data.blockedNames.size > 0) {
            const name = normalizeName(authorOf(card));
            if (name && data.blockedNames.has(name)) return true;
        }
        return false;
    }

    function setFlag(element, attr, on) {
        if (on) {
            if (element.getAttribute(attr) !== "true") element.setAttribute(attr, "true");
        } else if (element.hasAttribute(attr)) {
            element.removeAttribute(attr);
        }
    }

    function apply() {
        scheduled = false;
        ensureStyle();
        const seen = new Set();
        for (const card of document.querySelectorAll(CARD_SELECTOR)) {
            const outer = card.closest("ytm-rich-item-renderer") || card;
            if (seen.has(outer)) continue;
            seen.add(outer);
            const link = outer.matches(VIDEO_LINK_SELECTOR) ? outer : outer.querySelector(VIDEO_LINK_SELECTOR);
            const videoId = videoIdOf(link?.getAttribute("href") || link?.href);
            setFlag(outer, BLOCKED_ATTR, isBlocked(outer));
            setFlag(outer, WATCHED_ATTR, data.greyWatched && !!videoId && data.watched.has(videoId));
        }
        for (const element of document.querySelectorAll("[" + WATCHED_ATTR + "],[" + BLOCKED_ATTR + "]")) {
            if (!seen.has(element)) {
                element.removeAttribute(WATCHED_ATTR);
                element.removeAttribute(BLOCKED_ATTR);
            }
        }
    }

    function schedule() {
        // Hidden tabs do no work; they refresh when they become visible again.
        if (scheduled || document.visibilityState === "hidden") return;
        scheduled = true;
        setTimeout(apply, 150);
    }

    function refresh() {
        data = readData();
        schedule();
    }

    const observer = new MutationObserver(schedule);
    function observe() {
        const root = document.body || document.documentElement;
        if (!root) {
            setTimeout(observe, 100);
            return;
        }
        observer.observe(root, { childList: true, subtree: true });
        refresh();
    }

    for (const name of ["testtubePreferencesChanged", "onPageFinished", "doUpdateVisitedHistory", "yt-navigate-finish", "state-navigateend"]) {
        window.addEventListener(name, refresh, true);
    }
    document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "visible") refresh();
    }, true);

    window.testtubeContentFilters = { refresh };
    observe();
})();
