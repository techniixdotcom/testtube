# Changelog

## 3.0.5

- Faster video starts: YouTube's player code now runs in QuickJS through a small Rust library (`native/testtube-core`) instead of the Rhino script engine. Rhino read and ran the whole player file again (about 3 MB of JavaScript) for every new video, which took around a second on a fast computer and longer on a phone. Now it's read once per player version, ideally in the background right after the app starts, and each video after that takes a few microseconds for this step. If the library can't load, the app uses Rhino as before
- Home shows up much sooner after starting the app:
  - The videos from last time appear right away, and the new ones replace them when they arrive. If you've already started scrolling, your place is kept and the new videos go below
  - Signed out, YouTube's Home is often empty, so the app looks up related videos and searches instead. Those lookups now run at the same time rather than one after another, and if Home was empty last time they start immediately instead of after three failed tries
  - The YouTube version number the app sends is remembered between launches, so the first request no longer waits for a separate lookup
  - Signed in, the account settings read from m.youtube.com are kept between launches (for 6 hours, as before) instead of being fetched again on every start
  - The WebView cookie store, which every YouTube request needs, starts in the background while the first screen is drawn instead of just before Home's first request
- When YouTube releases a new player version, reading what the app needs out of it is about 3-7x faster: the regex searches through the player file (and other big pages) now run in the Rust library, with exactly the same results as before
- The stored player functions are loaded into QuickJS in the background shortly after the app starts, so the first video doesn't wait for them. When Android needs memory back while the app is in the background, that memory (about 30 MB) is freed again

## 3.0.4

## 3.0.3

- New: TestTube+, a second edition next to TestTube. It's TestTube plus links people send you from Spotify, Facebook and SoundCloud. A Spotify song, album or playlist plays from YouTube (the first song plays, the rest go in the queue). Public Facebook videos and SoundCloud songs, sets and albums play in the player itself, like YouTube videos: minimized, in the floating window, in the background, with the notification and the queue; when one ends the queue goes on, or autoplay picks a YouTube video like it. A SoundCloud song that's only a 30-second preview there plays from YouTube. No browsing, search or account on those sites. Either edition installs over the other and keeps history and settings; each one updates to its own
- Changed: Back on the video screen now works like a real back button: it plays the video you watched before, and from the first one it closes the player and goes back to the screen you came from. Swipe the player down to minimize it
- New: on Android 12 and newer, the app offers once to let links open in it, and takes you to the screen where Android asks (Settings, Open links in this app, shows how many are on)
- Fixed: Clear cache on exit didn't work. Swiping TestTube away before a video had played never cleared anything, and when it did start Android usually closed the app before it finished. Now the app stays open until the cache is empty, and anything a closed app couldn't clear (swiped away, force-stopped, crashed) is cleared the next time it starts
- Leaving with Back now also clears the web data and the pictures kept in memory when Clear cache on exit is on
- Clear cache now really reaches zero: with Clear cache on exit on, closing the app (Back, Close, or swiping it or its notification away) empties everything Android's own Clear cache in App info does, also the index files and folders the caches keep while the app runs and Android's graphics cache, and then ends the app, so App info shows 0 B right away. A download that's still running or paused keeps the app open; the rest is then cleared on the next start. Settings → Clear cache also empties Android's graphics cache and the external cache, and deletes half-finished downloads and update files nothing uses any more; only the few folders the open caches need stay while the app runs
- The cache size in Settings is now Android's own number, the same as Cache in App info. It used to count only the files' contents, not the disk space they take, the graphics cache or the external cache
- Fixed: counts in Russian (and the other languages) now use the right plural form, and "1 items added" style messages are gone
- Translations checked line by line: Traditional Chinese now uses Taiwanese terms throughout, Spanish says "vídeo" everywhere, and a few French, Japanese, Turkish and Chinese wordings were corrected
- New: playlists and Watch later, kept on the phone. Long-press any video and tap Save to tick the playlists it goes in, or make a new one. Library has a fourth tab, Playlists: Watch later on top, Play all and Shuffle, tap to play, the cross removes, long press drags to reorder, and the bin removes watched videos. Playing one works like a YouTube playlist, with the list under the player and next, previous and shuffle. Playlists can be exported and imported. The Library tabs now only label the open one, so four fit
- Faster, lighter playback: videos now use VP9 or AV1 when the phone decodes them in hardware, instead of always H.264 at 1080p and below. Same quality in roughly a third less data, so videos start sooner and buffer less. AV1 the phone could only decode in software (a battery drain) is skipped. Prefer H.264 still forces H.264; downloads still pick H.264 as before
- Videos start without waiting for YouTube's second details request; the upload date fills in a moment later
- Live chat only runs while you can see it: switching to the description, minimizing or leaving the app shuts it down, and it reloads when you come back
- NewPipe Extractor updated to v0.27.0 (was a development snapshot from 20 October). Brings YouTube's fix for reading the speed-limit parameter of stream links, which otherwise can make videos load slowly. Gson 2.15.0, OkHttp 5.5.1 and jsoup 1.24.1
- Smaller APK: font hinting Android never uses is stripped, unused launcher bitmaps are gone, the logo is WebP, and the unused Rhino script engine and Rhino's JVM compiler are no longer bundled

## 3.0.2

- New player notification, also on the lock screen: previous video, back 10 seconds, play/pause, forward 10 seconds, next video and Close. Expanded it shows the picture, title and channel too. Close stops the video, closes the app and clears the cache. The card is in the app's theme (Dark, Light or NiiX, with NiiX's pink and font), paused too on Android 14 and newer
- Swiping the player notification away (in the notification shade or on the lock screen) closes the app, playing or paused, on screen or in the background. Android's own media player is gone: swiping that one only hid it and let the video play on. On Android 13 and older a playing notification stays pinned; pause first, or tap Close
- Closing (swipe, Close, or recent apps) also takes TestTube out of the recent apps
- The player notification stays visible on lock screens set to show only new notifications (GrapheneOS, Pixel)
- Fixed: swiping TestTube away from the recent apps now stops the video. It used to keep playing with no way to stop it except opening the app again
- Library: the touch highlight of the tabs follows their rounded corners too
- Fixed: blue skin and washed-out colours on Samsung and some other phones (video tunneling is off)
- New: Allow HDR video (Settings → Playback, off by default: SDR is played whenever there is one) and Prefer H264 video (off by default, for phones that still show wrong colours). A broken HDR stream falls straight back to H.264
- Sign-in page can only go to Google's and YouTube's own sites
- Opening downloads only shares the app's own Downloads/TestTube folder, nothing else in Downloads
- Updates: refused without GitHub's SHA-256 of the file, the download has to match it exactly, and only an APK signed with the same key as the installed app is offered to Android's installer (now on every Android version, not just 9+). A switch to a new signing key is accepted only the way Android itself allows it: the new key has to carry the old key's signed approval
- About 240 KB smaller: the NiiX font only carries the characters text needs, and the unused ExtraBold weight is gone. It looks exactly the same

## 3.0.1

- Faster first video after a start: what's needed from YouTube's player script is kept between launches, so it's only downloaded again when YouTube changes it
- Videos stream over HTTP/3 (QUIC) on phones with Android's built-in network stack (Android 14, or 11+ with system updates), like YouTube's own app; other phones work as before
- Shorts: the two after the next one get their first seconds loaded too (unless Video cache is Small)
- Thumbnails loaded ahead are used straight from memory instead of being decoded again
- Leaner release build: only what really needs it is kept out of R8's optimisations
- Low-RAM phones use half the memory for pictures without transparency

## 3.0.0

- New look: one consistent style everywhere. Buttons, icons and labels share one colour; the long-press menu is a single list of actions with icons and names; fewer background shades
- New: three themes to choose from in Settings → Appearance → Theme: Dark, Light and NiiX (black, dark glass with a pink edge, pink accents and JetBrains Mono, like niixa.org). System default follows the phone between Light and Dark
- The mini player and bottom bar are frosted glass with the lists scrolling under them (Android 12+), dialogs and sheets blur what is behind them
- Library: the selected tab's edge follows its rounded corners
- Swiping a video right now says "Add"
- Download dialogs: Cancel is a plain button, the option buttons stay readable in every theme
- Fixed: a comment without a name showed "null"
- Loading spinners follow the theme

## 2.5.0

- New: Search-only Home (Settings → Content). Home shows just the logo, the name and a search bar, no suggested videos and no top bar; nothing is loaded for it. Tap the logo for the settings.

## 2.4.2

- Fixed: the app closed when the next video started while playing in the background (Android 12+ doesn't let a background app start its player service again, and 2.4.1 let go of it between videos). Only a real pause lets go of it now.
- Same kind of crash prevented for downloads: the next part of a playlist download no longer closes the app when it starts in the background, and Android 15's 6-hour limit for downloads is handled
- The update installer and its permission screen can't close the app on phones that lack them

## 2.4.1

- Light theme stays light on phones with Android 16's "expanded dark theme" (it was being inverted into an odd dark look)
- Fixed a crash right after switching the theme
- New: Clear cache on exit (Settings → Playback, also in the welcome dialog, which shows once more for it)
- Paused videos no longer keep the app running in the background; the notification can be swiped away then
- If the app ever closes unexpectedly, the next start shows the error to copy into a bug report

## 2.4.0

- Videos start playing before likes, suggestions and chapters are in; those fill in a moment later
- Shorts: next 3 extracted right away, the next one already loading in a second player, so a swipe starts it at once. No likes/suggestions request for Shorts. Quality capped at 720p.
- BotGuard and the connections to YouTube get set up shortly after start, not on the first tap
- Home: first page shown right away, more loaded right after instead of before
- Baseline profile covers Glide and OkHttp too
- Requests waiting for a free slot start the moment one frees up instead of polling; the AI-list scan no longer holds a slot
- Video details aren't copied through JSON twice more on every start
- Offline copy of the lists is written in the background, not on the main thread
- Download sizes in your language and units; removed unused code and duplicate helpers

## 2.3.1

- Pictures load much faster: Glide instead of Picasso, thumbnails as WebP (about a third smaller), the next rows' pictures load ahead, and pictures stay cached at their real size
- The notification picture comes from the picture cache instead of being downloaded again
- Missing channel pictures are looked up in the background instead of holding up the list
- Subscriptions: old videos jumped to the top after loading more (videos without a readable date were treated as brand new)
- Pull to refresh now really reloads every channel, and channel feeds are never served from cache
- Slow channels are waited for (up to 9 s) instead of cut off after 2.5 s
- Subscriptions with a YouTube account: sorting no longer gives up when one video has no date; live streams on top

## 2.3.0

- Subscriptions sorted by upload date, newest first (also while channels are still loading and when scrolling further down). Home unchanged.
- Original audio track by default (AI dubs last); a track picked in the menu only sticks for that video
- Titles, channel names, descriptions and chapters in the creator's original language instead of YouTube's translations
- Channel pages only hide the Playlists tab now; Podcasts, Courses and Releases are back
- Fixed: original audio not detected in some languages

## 2.2.2

### New
- Theme follows the system by default
- Channel pages: compact rows, newest videos first
- Private/removed/age-restricted/blocked videos get skipped in playlists and the queue, with a short note why
- Removed the "Up next" popup

### Fixed
- Light theme got darkened by Android's force dark when the phone was in dark mode
- Close button on the video menu thumbnail was hard to see
- 60 fps videos downloaded in lower quality in playlist downloads ("1080p60" was read as 108060)
- Playlist download errors showed raw English text
- Exporting channels could freeze the app on slow storage (Drive etc.)
- Download dialogs kept working in the background after closing
- "Download" share entry wasn't translated; French and Traditional Chinese "threads"

### Other
- Code cleanup, removed dead code and duplicate helpers
- Dropped the unverifiable YouTube link verification

## 2.1.1

- Light theme (Settings → Appearance)
- Update check reads tags like `v.2.1.1`
- Pre-release switch works without closing the dialog first

## 2.1.0

### Player
- Floating window inside the app, or minimize to the bottom bar (setting)
- Custom gestures: pick what left/right swipes, sideways swipes and double taps do
- Fullscreen: vertical swipes always do brightness and volume
- Video cache setting: Large 150 MB / Medium 100 MB / Small 50 MB
- Clear cache button

### Connection
- Updated NewPipe Extractor
- One consistent browser identity and shared cookies
- Requests are paced; backs off after 429s and bot checks, retries once by itself
- Readable error messages with Try again instead of stack traces
- Subscriptions cache channels for 15 minutes

### Library & look
- Library tab with Queue / History / Downloads tabs, one bin to clear the open tab
- Settings grouped into cards, shorter descriptions
- One colour scheme, dialog animations, blur on Android 12+
- Pre-release updates switch; better version comparison
- Notifications and error messages translated

## 2.0.2

### Settings
- Welcome dialog on first start
- Subscriptions tab can be hidden
- Watched videos: on/off, how dark, and when a video counts as watched
- Separate quality for Wi-Fi and mobile data, switches mid-video
- Write comments when signed in
- Hide AI videos (AiSList) is back
- New app icon

### No account needed
- Sign-in optional; Home built from your history when signed out
- Follow channels locally, import (Takeout, NewPipe, OPML) and export
- Faster Subscriptions, loads as channels answer

### Watching
- YouTube-style drag to minimize
- Floating player when leaving the app, background audio otherwise
- Suggestions, description and comments tabs under the video
- Autoplay that avoids repeats; next video is preloaded
- Shorts tab (optional)
- Ads and Premium upsells filtered out

### Lists
- Channel pages like YouTube's (banner, follow button, tabs)
- Channel pictures next to titles
- Swipe right to queue, left to follow
- Content in your phone's language and country

### Other
- Dislike counts removed
- Build script with live log
- Security hardening (web views, file sharing, file names)
- Rebranded to TestTube
- Cleanup
