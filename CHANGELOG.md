# Changelog

## No account needed
- Login isn't required anymore. You can close the login screen and Home, search, queue and history all work
- Signed-out Home: if YouTube returns nothing it's built from what you watch (top channels, recent titles) + popular searches
- Home/Subscriptions don't flash an old list anymore, you get a spinner and then fresh videos. The old list is only used when nothing loads (e.g. offline)
- Faint dark red TestTube logo behind the lists
- Suggestions under the player never run out, more related videos load as you scroll (playlists still end normally)
- Thumbnail placeholder logo is solid red now
- Follow channels without an account: from a video, swipe left, add by link/@handle, or import a Google Takeout / NewPipe / OPML list
- Export followed channels as csv
- Subscriptions = latest videos of your followed channels, newest first. "Use my YouTube account" switches to your real subs, Log out switches back
- If a channel feed is empty or fails it's fetched another way. If nothing loads you get an error, tap to retry
- Subscriptions is a lot faster: channels load in parallel and slow ones don't hold up the list
- Videos show up as each channel answers instead of after all of them (without an account that means arrival order, not strictly newest first)
- Home's first videos show up sooner, the rest loads as you scroll

## Watching
- Leaving the app keeps the audio playing in the background (with notification controls) instead of opening PiP. Video is switched off meanwhile so only audio gets downloaded. PiP is still in the player menu
- Default quality is 480p (or the next lower one). The quality you pick is still remembered
- Ads and "Upgrade to YouTube Premium" type stuff filtered out of all lists
- Suggestions under the player, each with an enqueue button
- Description and Comments tabs (live chat for streams and premieres)
- Comments load page by page
- There's always a next video: extractor suggestions → YouTube's → a search for similar videos → Home
- Autoplay avoids the same channel twice, prefers stuff you haven't seen, and shows a cancelable "Up next"
- Watched videos are way more obviously greyed out (dimmed + black and white thumbs)

## Lists and navigation
- Lists stop above the bottom bar, so the last video isn't hidden behind it
- Swipe right to queue (black panel, logo + "Added")
- Swipe left to follow the channel. First 3 times it asks and counts down the reminders
- Queue and History are two full pages with a red switch, one clear button for whichever is open
- Search results, History and Library links open the native screens instead of web pages
- Smaller list thumbnails (320x180) + capped thumbnail memory cache, smoother scrolling
- Logo as placeholder while thumbnails load
- Search box says "Search TestTube"

## Build / security / cleanup
- Slightly faster start, History and Queue get built on first open instead of at launch
- Dislike counts gone for good (no third-party lookups, script and setting deleted)
- build.log is written live with timestamps, errors-only file on failure, no pointless retries, --verbose flag
- Web views locked down (https only, no file access), cleartext blocked, file sharing limited to gallery and download folders, share-to-download only forwards the link, file names stripped of backslashes, control chars and ".."
- Security audit written up, open items listed
- Rebrand to TestTube: old images/screenshots/text removed, new store icon and descriptions
- Code cleanup: dead code, unused imports and resources removed, fully qualified names replaced by imports
