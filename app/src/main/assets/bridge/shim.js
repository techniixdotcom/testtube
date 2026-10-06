(function () {
    const bridge = window.testtubeBridge;
    if (!bridge || window.testtube?.__bridge) return;

    const send = (name, args) => {
        try {
            bridge.postMessage(JSON.stringify({ m: name, a: args }));
        } catch {
            // The bridge only exists on YouTube pages; nothing to do elsewhere.
        }
    };
    const state = () => window.__testtubeState || {};

    const api = { __bridge: true };
    for (const name of [
        "finishRefresh", "setRefreshLayoutEnabled", "download", "downloadPlaylist",
        "play", "showHint", "hideHint", "goBack", "addToQueue", "playNext", "openWith", "showMediaItemMenu",
        "showQueueItemUnavailable", "hidePlayer", "setPlayerHeight", "onPosterLongPress", "openTab",
        "openWatchHistory"
    ]) {
        api[name] = (...args) => send(name, args);
    }
    api.seekLoadedVideo = (url, positionMs) => {
        send("seekLoadedVideo", [url, positionMs]);
        return true;
    };
    api.getPreferences = () => state().preferences || "{}";
    api.getContentFilters = () => state().contentFilters || "{}";
    api.getWatchLog = () => state().watchLog || "{}";
    api.isQueueEnabled = () => !!state().queueEnabled;
    api.getResumePosition = (videoId) => Number(state().resume?.[videoId]) || 0;

    Object.defineProperty(window, "testtube", { value: Object.freeze(api), configurable: false, writable: false });
})();
