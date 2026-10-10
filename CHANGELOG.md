# Changelog

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
- Watched videos are greyed out much more clearly (dimmed and black-and-white thumbnails).

## Lists and navigation
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
