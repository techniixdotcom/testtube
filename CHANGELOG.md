# Changelog

Version 2.1.2

## Theme
- System default is now the default theme, so the app follows the phone's light or dark mode. The choices are listed as System default, Light, Dark.
- A phone set to dark mode no longer darkens the light theme by itself (dialogs, settings cards, switches and icons showed inverted colours).
- The close button on the picture in a video's menu has a dark round backing, so it shows on any thumbnail in both themes.

## Playlists and the queue
- A video that's private, removed, age-restricted, blocked in your country or paid no longer stops a playlist or the queue with an error. A short message says why ("Private video, skipped") and the next video plays. This worked before 2.1.0 but only for plain "unavailable" videos, and the clearer error kinds in 2.1.0 broke it.
- The same goes for a video whose stream can't be played at all (broken, unsupported format or DRM). Network errors still stop and offer a retry, so a bad connection doesn't skip through the whole list.
- A single video opened on its own still shows the error, since there's nothing to move on to.

## Up next
- The "Up next" notice at the end of a video is gone. The next video simply starts, as before 2.1.0.

## Fixes and cleanup
- Exporting followed channels no longer writes the file on the main thread, so saving to a slow place like Google Drive can't freeze the app. Import and export both skip their message if the screen was closed meanwhile.
- The share menu entry "Download" is translated like the rest of the app.
- YouTube links no longer ask Android to verify them as the app's own domain, a check that could only fail.
- Download dialogs stop their background work properly when closed.
- Removed an unneeded library entry (Okio comes with OkHttp) and renamed an internal download listener.

Version 2.1.1

## Light theme
- New "Theme" setting under Appearance, at the top of the settings: Dark (the default), Light or System default to follow the phone. It switches right away; the screen is built again and an open video reopens where it was.
- The light colours cover every screen, list, dialog, sheet, menu and the bottom bar, with a deeper red so text and switches in the accent stay readable on white. The player, the floating window and Shorts stay dark in both, since they sit on the video.
- The swipe-to-queue background and the sign-in logo now follow the theme instead of being fixed black and white.

## Updates
- Release tags written as "v.2.1.1" (or "2.1.1", "V2.1.1") are now read correctly; before, such a release was never offered.
- The "Pre-releases" switch takes effect right away, so "Check for updates" in the same dialog already includes test versions.

Version 2.1.0

## Floating window and gestures
- In fullscreen, swiping up or down always changes the brightness (left half) and the volume (right half), whatever the gesture settings; it no longer drops the video into the small player. Back or the button in the corner leave fullscreen. A brightness set there is given back to the system when fullscreen ends, unless brightness is also one of the normal gestures.
- New setting "Video cache" under Playback, with three sizes. Large (the default, up to 150 MB) and Medium (up to 100 MB) keep watched video for going back and download the start of the next video ahead so it starts at once. Small (up to 50 MB) downloads nothing ahead (only the next video's details, a few kilobytes), so the next video takes a moment to start. A smaller choice frees the space right away. Whoever had picked the earlier Space saver gets Small.
- Tapping the "Up next" notice anywhere but Cancel plays that video right away, as if the current one had ended.
- New setting "Minimize to": Bottom bar or Floating window. With the floating window, swiping a video down (or going back) shrinks it into a small window that stays on top while you browse the app; it also stays on screen when you leave the app. With the bottom bar it works as before, and leaving the app keeps only the sound. This replaces the separate floating player switch.
- The floating window can be dragged anywhere and settles in the nearest corner, which is remembered; fling it off the side to close it. A tap shows expand, play/pause and close; a double tap expands it. Expanding grows the video back from the window.
- New "Custom gestures" switch. Off (the default): swipe down minimizes (to the bar or the window), up opens fullscreen, sideways seeks, and double-tapping the sides skips 10 seconds. On: choose what a swipe on the left half and on the right half does (brightness, volume, minimize / fullscreen or nothing), what a sideways swipe does (seek or nothing) and what a double tap does (skip 10 seconds, play / pause or nothing). Whoever had "Swipe down to minimize" switched off gets custom gestures with brightness on the left and volume on the right, as before.
- The settings headings no longer look cut off while scrolling.

## Staying reachable
- NewPipe Extractor updated to the newest version (20 October), with YouTube's new signature parsing and client version detection. TestTube's own changes to it were carried over, and a changed client version layout now falls back safely instead of failing.
- One consistent identity: requests to www.youtube.com present themselves as desktop Chrome and m.youtube.com as mobile Chrome, both with the phone's own WebView version, matching what the requests say inside (they used to mix a mobile browser with desktop requests). The BotGuard token request uses the same version (it was stuck on an old one). Requests carry the content language like a browser does.
- YouTube's cookies are kept in one place shared with the app's web pages, so the app is one consistent visitor instead of a new one on every request, and refreshed sign-in cookies are no longer thrown away. Followed channels' feeds now use the same connection, cookies and identity as everything else.
- Requests are paced like a person using the site: at most a few at a time, short bursts, and a small random gap for background work. What you tap always goes first; refreshing Subscriptions, prefetching Shorts and downloading a whole playlist wait their turn. After YouTube says "slow down" (HTTP 429 or "confirm you're not a bot"), requests pause for a while (following YouTube's Retry-After), longer if it happens again soon.
- Subscriptions fetch at most four channels at a time and remember each channel's videos for 15 minutes, also across restarts. Channels that don't answer in time show their last known videos.
- When a video fails because of YouTube's bot check or a timeout, TestTube waits out the pause, starts over as a new signed-out visitor and tries once more by itself, with a short notice. If that fails too, a clear message explains what happened (offline, age-restricted, not available in your country, private, removed, …) with Try again and Details buttons, instead of an error with a stack trace. Lists show the same kind of message and retry once by themselves.
- These messages, which were English only, are translated.
- The Shorts tab says when it can't load because you're offline, YouTube asked for a pause, or it took too long, instead of a general error.
- The labels of copied debug details are translated too.

## Library, settings and look
- Every setting has a short explanation of a few words, and the choice next to it shows just its name (for example "Space saver"; the sizes are in the list you pick from), so the settings read cleanly.
- New "Clear cache" row under Playback. It shows how much space the cache takes (saved video, pictures and web data) and empties it after asking. Downloads in progress, downloaded updates, history, the queue, your sign-in and settings stay. It is safe while a video plays.
- Pictures now load through the app's own connection and share its cache, instead of keeping a second one.
- The History tab in the bottom bar is now Library, with its own icon. Along its top are three browser-style tabs: Queue, History and Downloads. The open tab joins the page below it and has a red line along its top; the app remembers which one was open.
- The Queue/History switch is gone. The bin at the end of the tab row clears whichever tab is open (queue, watch history or the list of downloads; downloaded files stay on the phone). It is greyed out when there is nothing to clear.
- Downloads now live in the Library instead of a separate screen; the download notification opens them there. A downloaded playlist still opens its own page with its items.
- Queue, History and Downloads rows share one look: same thumbnail size, text and spacing, and the playing queue video is highlighted. Empty tabs show an icon with their message.
- The settings (and the welcome dialog) are sorted into groups on rounded cards: Content, Watched videos, Playback, Comments and Updates. Every setting is one row with its explanation underneath and the switch on the right; tapping anywhere on the row switches it. The sliders show their value next to the title. The header shows the app icon with the version, and "Check for updates" is a row in Updates.
- One colour scheme across the app: a single red accent for switches, sliders, tabs, buttons, links and progress bars, and dark grey layers that get lighter the higher something sits (page, bottom bar, mini player, dialogs). Tabs under the player and on channel pages share one style.
- Dialogs fade in and grow slightly instead of popping up, with the app dimmed behind them; on Android 12 and newer the app is also blurred behind dialogs. Switching bottom tabs or Library tabs fades the new screen in.
- Download and player notifications, and their channels in Android's settings, are translated like the rest of the app. Percentages follow the language (for example "70 %" in French, "%70" in Turkish).
- Lists are lighter on the phone: the queue only redraws rows that changed, and blocked channels are prepared once instead of being read again for every video in every list.

Version 2.0.2

## First start and settings
- Live chat follows the app's dark theme: no more white background with unreadable messages.
- The Subscriptions tab can be hidden (welcome dialog or the TestTube logo menu). With it hidden, links that would open Subscriptions open Home.
- Grey out watched videos can be switched off, and a slider sets how much of a video has to be played for it to count as watched (50–100 %, 75 % to start with).
- A second slider sets how dark watched videos are, from 20 % (black and white only) to 90 % (almost black), 70 % to start with. The pictures get darker and the text fainter, and a change shows as soon as the settings close. The slider is greyed out itself while greying out is switched off.
- The new app icon sits at the top left of Home, next to the TestTube name; tapping either opens the settings.
- The queue has no on/off switch any more: when videos are in the queue, they play next.
- New app icon: a white test tube with bubbles running corner to corner on black, with a red play in front. It is drawn as a vector, so it stays sharp at every size, fits round and rounded-square launchers, and has a one-colour version for Android 13's themed icons. The old logo stays on the login screen, on thumbnail placeholders and behind the lists.
- Hide AI videos (AiSList) is back. It was in v1.9.6 and got lost afterwards. When switched on, the app downloads AiSList, a community list of about 29,000 channels that post AI-generated videos, once a day from GitHub and hides those channels everywhere: Home, Subscriptions, search, channel pages, suggestions and autoplay. Most entries are handles, so the app also learns the matching channel ids from YouTube's own responses. The switch is in the welcome dialog and behind the TestTube logo, and the setting carries over from v1.9.6.
- A welcome dialog on the first start sets up Shorts, full channel pages, the video quality on Wi-Fi and on mobile data, the floating player, what swiping down does, commenting and the automatic update check. The same choices are behind the TestTube logo, so everything can be changed later. The dialog shows once more after this update because of the new choices.
- Minimizing works like in YouTube: drag the video down and it follows your finger, staying centred and shrinking to half its size, then fading into the bottom bar; the description and comments fade out where they are and the previous screen shows through. Let go past halfway (or flick it down) and it settles in the bar; let go earlier and it slides back up. Drag the bar up to pull the video back out the same way, or tap the bar and it grows back. Back still moves the video to the bar straight away. The floating player is for leaving the app: with it switched on, going home or to the recent apps while a video plays keeps it in a small window on top of other apps (Android 12 and newer slide it there smoothly). The window shows only the video, with previous, play/pause and next; Android adds the buttons to expand it back into the app and to close it. Closing pauses the video and keeps it in the bottom bar. With the floating player off, leaving the app keeps only the sound playing, as before.
- Video quality on Wi-Fi and on mobile data are chosen separately (720p and 480p to start with). When the phone moves between Wi-Fi and mobile data, the video switches quality by itself, also in the middle of a video, at the same spot and with the same audio language; a paused video stays paused. A quality picked in the player becomes the one for the network you are on.
- On channel and playlist pages, swipe a video to the right to add it to the queue, as on Home.
- On channel and playlist pages, a long press on a video opens its menu, as on Home. "Mark as watched" (or "Mark as unwatched" for a watched video) greys the row out, or back in, right away.
- Write comments: when switched on and you are signed in, the Comments tab gets a box to post a comment on the video. It only shows when YouTube allows your account to comment there.
- Swipe down to minimize can be turned off; vertical swipes on the video then change the brightness (left half) and the volume (right half). The brightness goes back to normal when the player is minimized or closed.
- "Check automatically" now reads "Check for updates automatically".
- New "Pre-releases" switch under Updates. When on, the update check also offers test versions published on GitHub. It is off by default.
- The update check now compares versions properly, including test versions such as "v2.1.0-beta2", so an older version is never offered as an update.
- With Shorts on, they appear on Home, in Subscriptions and search (marked SHORTS) and as a Shorts tab on channels. The suggestions under the player stay without Shorts.
- With Shorts on, the bottom bar gets a Shorts tab next to Home: full-screen Shorts, swipe up or down for the next one. Each Short loops, a tap pauses it, and the channel row opens the channel or follows it. The Shorts come from Home, the followed channels and searches based on what was watched. Watching a Short pauses the regular video and the other way round. Turning Shorts off removes the tab.
- With full channel pages off, a channel opens the way it did before: picture, name, subscribers, description and its videos.
- Shorts from followed channels are now left out of Subscriptions when Shorts are off; before, the channel feeds let them through.

## Using the app without an account
- Signing in is no longer required; the login screen can be closed and Home, search, queue and history work without it.
- Home works when you are signed out: if YouTube returns nothing, it is built from what you watch (your most watched channels and last titles) plus popular searches.
- Home and Subscriptions no longer show an old list that then gets swapped; they show a loading indicator and then the fresh videos. The last list is only used when nothing new can be loaded (offline, say).
- A faint dark-red TestTube logo sits behind the video lists.
- Channel pictures show next to the videos in Subscriptions and search too, not only on Home.
- The suggestions under a playing video are endless: more related videos are added as you scroll (playlists still end where they end).
- Placeholder logos shown while thumbnails load are now solid red.
- Follow channels without an account: follow from a video, swipe a video left, add by link or @handle, or import a Google Takeout, NewPipe or OPML list.
- Export your followed channels to a csv file.
- Subscriptions shows the latest videos of your followed channels, newest first. "Use my YouTube account" switches to your own subscriptions (also newest first), and a Log out button brings you back.
- If a channel's feed is empty or fails, it is read another way; if nothing loads, the page shows an error you can tap to retry.
- Subscriptions loads much faster: channels are read in parallel with a short overall wait, slow channels no longer hold up the list,.
- Subscriptions fills in as each followed channel answers, so the first videos appear right away instead of after all channels have loaded (without an account, the list is then in arrival order rather than strictly newest first).
- Home shows its first videos sooner: it no longer chains several requests to fill the first page; the rest loads as you scroll.

## Watching
- Leaving the app keeps playing the sound in the background (with the notification controls) instead of opening a picture-in-picture window; the picture is switched off while you are away so only audio is downloaded. Picture-in-picture is still available from the player menu.
- Videos start in 480p by default (or the closest lower quality a video has); the quality you pick in the player is remembered as before.
- Ads and promotions such as "Upgrade to YouTube Premium" are filtered out of every list.
- Suggested videos under the player, with an enqueue option on every row.
- Description and Comments tabs under the video (live chat for live streams and premieres).
- Comments load page by page as you scroll.
- A next video always plays: extractor suggestions, then YouTube's own, then a search for similar videos, then Home.
- Autoplay avoids repeating the same channel, prefers videos you have not watched, and shows an "Up next" notice you can cancel.
- The next video (the next one in the queue, or the suggestion autoplay picked) is prepared two minutes before the end: its details are loaded and its first 30 seconds are downloaded, so it starts at once. The download waits until the current video is buffered well ahead, so it never makes that one stall. Pressing next uses the prepared video too.
- Queued videos always play when the current video ends. Before, the queue was skipped for a suggestion when its first video had been opened in the last 24 hours.
- When YouTube answers with "confirm you're not a bot", the app asks again twice with a fresh visitor id before giving up; the check often clears that way. The error now explains that it is about the network (signing in doesn't help here).
- Watched videos are greyed out much more clearly (dimmed and black-and-white thumbnails).
- Player hints (locked, loop mode, subtitles, jumped to chapter) stay up for their full three seconds; a newer hint no longer gets hidden early by the timer of the one before it.

## Lists and navigation
- Home and the suggestions under the player show the uploader's profile picture next to each title, like YouTube; tap it to open the channel, long-press it to follow the channel. Subscriptions, search, channel and playlist pages stay without pictures. A picture YouTube leaves out is looked up once in the background and remembered on the phone.
- Channel pages look like YouTube's: banner, profile picture, name with the verified mark, @handle, subscriber and video counts, a Follow button, and tabs for Videos, Live, Playlists, Podcasts, Courses and Releases (whichever the channel has). Each tab keeps its own list and loads more as you scroll; a tab that fails to load can be retried with a tap.
- YouTube is asked in your phone's language and country (it always got English before): titles, descriptions, dates and suggestions come in your language whenever the video has a translation, on Home, search, channels, playlists and the video page. View, like and subscriber counts are read correctly in every language. Subscriptions without an account still show titles as the channel wrote them.
- When a video is in the bottom bar, lists end above it, so the last video is no longer covered.
- Swipe a video right to add it to the queue (black panel with the logo and "Added").
- Swipe a video left to follow its channel; the first three times a popup asks first and counts the reminders down.
- Queue and History are two full pages with a red switch between them and one clear button that follows the page.
- Search results, History and Library links open the native screens instead of web pages.
- Lighter list thumbnails (320x180) and a limited thumbnail memory cache for faster scrolling.
- Your logo shows as the placeholder while thumbnails load.
- The search box says "Search TestTube".

## Build, security and cleanup
- The app starts a little faster: the History and Queue screen is built when you first open it, not at startup.
- Dislike counts removed completely (no lookups to a third-party service, script and setting deleted).
- Live build log (build.log) with timestamps, an errors-only file on failure, no pointless retries and a --verbose option.
- Hardened web views (https only, no file access), plain-text traffic blocked, file sharing narrowed to the gallery and download folders, the share-to-download screen only passes on the shared link, and file names are cleaned of backslashes, control characters and "..".
- A written security audit with the open items listed.
- Rebranded to TestTube: old images, screenshots and text removed, new store icon and descriptions.
- Code cleaned: unused code, imports and resources removed, full names replaced by imports. Stream details no longer carry fields that were filled in but never read (protocol, PO token flags, track lock, subtitle copies), the saved position only stores what is used, and a second class called Constant is gone.
