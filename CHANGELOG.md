# Changelog

Version 2.0.0

## First start and settings
- Hide AI videos (AiSList) is back. It was in v1.9.6 and got lost afterwards. When switched on, the app downloads AiSList, a community list of about 29,000 channels that post AI-generated videos, once a day from GitHub and hides those channels everywhere: Home, Subscriptions, search, channel pages, suggestions and autoplay. Most entries are handles, so the app also learns the matching channel ids from YouTube's own responses. The switch is in the welcome dialog and behind the TestTube logo, and the setting carries over from v1.9.6.
- A welcome dialog on the first start sets up Shorts, full channel pages, the floating player, what swiping down does and the automatic update check. The same switches are behind the TestTube logo, so everything can be changed later. The dialog shows once more after this update because of the new choices.
- Floating player: back or swipe down on a video puts it in a small picture-in-picture window that stays on top of other apps. Tap it to come back, close it to pause. Opening a channel or another tab while watching still uses the bottom bar. Without picture-in-picture (or with it turned off for the app) the bottom bar is used too.
- Swipe down to minimize can be turned off; vertical swipes on the video then change the brightness (left half) and the volume (right half). The brightness goes back to normal when the player is minimized or closed.
- "Check automatically" now reads "Check for updates automatically".
- With Shorts on, they appear on Home, in Subscriptions and search (marked SHORTS) and as a Shorts tab on channels. The suggestions under the player stay without Shorts.
- With Shorts on, the bottom bar gets a Shorts tab next to Home: full-screen Shorts, swipe up or down for the next one. Each Short loops, a tap pauses it, and the channel row opens the channel or follows it. The Shorts come from Home, the followed channels and searches based on what was watched. Watching a Short pauses the regular video and the other way round. Turning Shorts off removes the tab.
- With full channel pages off, a channel opens the way it did before: picture, name, subscribers, description and its videos.
- Shorts from followed channels are now left out of Subscriptions when Shorts are off; before, the channel feeds let them through.

## Using the app without an account
- Signing in is no longer required; the login screen can be closed and Home, search, queue and history work without it.
- Home works when you are signed out: if YouTube returns nothing, it is built from what you watch (your most watched channels and last titles) plus popular searches.
- Home and Subscriptions no longer show an old list that then gets swapped; they show a loading indicator and then the fresh videos. The last list is only used when nothing new can be loaded (offline, say).
- A faint dark-red TestTube logo sits behind the video lists.
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
- Code cleaned: unused code, imports and resources removed, full names replaced by imports.
