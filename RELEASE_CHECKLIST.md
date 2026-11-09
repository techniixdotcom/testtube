# Before a release

Static checks don't see how Android treats the app in the background. These do, on a real
phone with Android 12 or newer (and on an old one, Android 8–9, when possible).

## Background playback
- [ ] Play a playlist, screen off: let at least 3 videos change over by themselves
- [ ] Same with the app in the background (another app open)
- [ ] Pause from the notification, wait 2 minutes, resume, let the next video start
- [ ] Pause, leave the app: after a while it stops; the notification can be swiped away
- [ ] Swipe the app away from recents while playing
- [ ] Clear cache on exit on: watch a video, leave with Back, Close and a swipe from recents; each time App info → Storage shows Cache 0 B. Settings → Clear cache shows the same size as App info

## Downloads
- [ ] Start a playlist download, leave the app: all parts finish
- [ ] Single download while the app is in the background

## Screens
- [ ] Theme Light → Dark → System, from Settings and on first start (clear app data)
- [ ] Rotate during a video; minimize; floating window
- [ ] Shorts: swipe fast through 10+

## After testing
- [ ] Open the app once more: no "closed unexpectedly" report
- [ ] `./BUILD.sh --test` passes (both editions)

## TestTube+ (testtubeplus APK)
- [ ] A Spotify song link (from the Spotify app's Share, and a spotify.link short link) plays the right song
- [ ] A Spotify album or playlist: the first song plays, the rest land in the queue
- [ ] A Spotify artist link shows YouTube's search results for the artist
- [ ] A public Facebook video and a reel play in the player: minimize, floating window, screen off, notification
- [ ] When a Facebook video ends, the queue goes on; with an empty queue a YouTube video like it plays
- [ ] A SoundCloud track plays with its cover; a set plays the first song and queues the rest
- [ ] An on.soundcloud.com short link and a SoundCloud artist link open (artist: YouTube search)
- [ ] A SoundCloud Go+ song (30 s preview) plays from YouTube instead, with a notice
- [ ] A Facebook video in a private group says it isn't public instead of failing silently
- [ ] Installing TestTube+ over TestTube (and back) keeps history and settings
- [ ] Settings > Open links in this app shows "All" after allowing them in Open by default
