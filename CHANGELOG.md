# Changelog

## Using the app without an account
- Signing in is no longer required; the login screen can be closed and Home, search, queue and history work without it.
- Home works when you are signed out: if YouTube returns nothing, it is built from what you watch (your most watched channels and last titles) plus popular searches.
- The last Home is saved and shows instantly on the next start while the new one loads.
- Follow channels without an account: follow from a video, swipe a video left, add by link or @handle, or import a Google Takeout, NewPipe or OPML list.
- Export your followed channels to a csv file.
- Subscriptions shows the latest videos of your followed channels, newest first. "Use my YouTube account" switches to your own subscriptions (also newest first), and a Log out button brings you back.
- If a channel's feed is empty or fails, it is read another way; if nothing loads, the page shows an error you can tap to retry.

## Watching
- Ads and promotions such as "Upgrade to YouTube Premium" are filtered out of every list.
- Suggested videos under the player, with an enqueue option on every row.
- Description and Comments tabs under the video (live chat for live streams and premieres).
- Comments load page by page as you scroll.
- A next video always plays: extractor suggestions, then YouTube's own, then a search for similar videos, then Home.
- Autoplay avoids repeating the same channel, prefers videos you have not watched, and shows an "Up next" notice you can cancel.
- Watched videos are greyed out much more clearly (dimmed and black-and-white thumbnails).

## Lists and navigation
- Swipe a video right to add it to the queue (black panel with the logo and "Added").
- Swipe a video left to follow its channel; the first three times a popup asks first and counts the reminders down.
- Queue and History are two full pages with a red switch between them and one clear button that follows the page.
- Search results, History and Library links open the native screens instead of web pages.
- Lighter list thumbnails (320x180) and a limited thumbnail memory cache for faster scrolling.
- Your logo shows as the placeholder while thumbnails load.
- The search box says "Search TestTube".

## Build, security and cleanup
- Live build log (build.log) with timestamps, an errors-only file on failure, no pointless retries and a --verbose option.
- Hardened web views (https only, no file access), plain-text traffic blocked, file sharing narrowed to the gallery and download folders, the share-to-download screen only passes on the shared link, and file names are cleaned of backslashes, control characters and "..".
- A written security audit with the open items listed.
- Rebranded to TestTube: old images, screenshots and text removed, new store icon and descriptions.
- Code cleaned: unused code, imports and resources removed, full names replaced by imports.
