TestTube
============

in a world full of excess we give you 4.5 mb of simplicity 

TestTube is an ad-free YouTube client for Android. No account needed: Home, search, subscriptions, queue and history all work without logging in. Signing in is optional and only used if you want your own YouTube subscriptions.

Requires Android 8.0 (API 26) or later.

## Features
* [x] **Works without login** (optional sign-in for your YouTube subscriptions)
* [x] **Subscriptions without an account**: follow channels from a video, by swiping a video left, by link or @handle, or import/export a list (Google Takeout, NewPipe or OPML); newest uploads first
* [x] **Your YouTube subscriptions** with "Use my YouTube account", and a Log out button; your followed channels stay
* [x] **Home for discovering**: relevant but random, without the channels you follow; built from your history when signed out, and the last Home shows instantly on start
* [x] **Ad-free playback**
* [x] **Suggested videos under the player, with a next video that always plays**
* [x] **Smarter autoplay**: avoids repeating the same channel, with an "Up next" notice 15 seconds before the end (cancelable) and an instant switch
* [x] **Swipe a video right to add it to the queue**
* [x] **Description and Comments tabs (live chat for live streams)**
* [x] **Sponsor-block**
* [x] **In-app updates** from this repository's releases: a popup, a download with a progress bar, then install
* [x] **Mini-player support**
* [x] **Local queue support**: Queue and History are two full pages with a switch between them
* [x] **Background & Picture-in-Picture support**
* [x] **Built-in video and queue downloader**
* [x] **Watched videos greyed out**
* [x] **Local history**
* [x] **Light on data**: small thumbnails in lists and a limited image cache

## How it works

Almost everything is native: the player, Home, Subscriptions, search, channel and playlist pages (every channel tab), History, the queue, the screen under the video (Description/Comments tabs, suggestions) and the downloader are drawn by the app itself and get their data directly from YouTube through the NewPipe extractor. This is faster, smoother and fully under our control.

Only two small web views are left: the Google sign-in page, and the live chat of live streams. There is no YouTube website layer, no injected scripts and no page bridge.

Subscriptions come from one of two places. By default the app shows the latest videos of the channels you follow, read from YouTube's public channel feeds, so no account is involved. "Use my YouTube account" switches to your account's own subscriptions.

## Privacy

* No analytics, crash reporting or ads in the app. History, queue and followed channels stay on the phone, and backups are off.
* YouTube and Google still see your IP address and the videos you watch, as with any YouTube client.
* The only other service the app contacts is Sponsor-block, which sends the first 4 characters of a hash of the video ID to sponsor.ajay.app. There are no dislike-count lookups.
* Update checks ask GitHub (api.github.com) for the latest release 5 seconds after the app starts. Nothing about you is sent, and you can switch it off by tapping the TestTube logo on Home and unticking "Check automatically".

## Screenshots

<p align="center">
<img src="screens/1.png" alt="" width="200">
<img src="screens/2.png" alt="" width="200">
<img src="screens/3.png" alt="" width="200">
</p>

## Building

Requirements: Linux or macOS with `bash`, `curl` and `unzip`. Everything else (JDK 21, Android SDK, signing key) is installed by the script into `~/.testtube`.

```
./BUILD.sh              # signed release APK in dist/
./BUILD.sh --install    # build and install on a USB-connected phone
./BUILD.sh --debug      # debug APK
./BUILD.sh --clean      # clean build
./BUILD.sh --verbose    # more detailed log
```

The build log is written live to `build.log`; errors are collected in `build-errors.log`.

## Contributing

If you encounter a bug, please check whether an issue has already been reported. If not, feel free to open a new one. Pull requests are always welcome!

## License

GPL-3.0. NewPipe Extractor is GPL-3.0-or-later.


Peace and Love
by
techniix / cuteLiLi / QuacK
