# Changelog

## 2.2.2

### New
- Theme follows the system by default
- Channel pages: compact rows, newest videos first, playlists tab hidden
- Original audio track by default (AI dubs last); a track picked in the menu only sticks for that video
- Titles, channel names, descriptions and chapters in the creator's original language instead of YouTube's translations
- Private/removed/age-restricted/blocked videos get skipped in playlists and the queue, with a short note why
- Removed the "Up next" popup

### Fixed
- Light theme got darkened by Android's force dark when the phone was in dark mode
- Close button on the video menu thumbnail was hard to see
- 60 fps videos downloaded in lower quality in playlist downloads ("1080p60" was read as 108060)
- Playlist download errors showed raw English text
- Exporting channels could freeze the app on slow storage (Drive etc.)
- Original audio not detected in some languages
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
