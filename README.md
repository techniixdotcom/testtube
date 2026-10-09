<p align="center">
  <img src="screens/header.png" alt="" width="400">
</p>

in a world full of excess we give you 4.5 mb of simplicity 

TestTube is an ad-free YouTube client for Android. No account needed: Home, search, subscriptions, queue and history all work without logging in. Signing in is optional and only used if you want your own YouTube subscriptions.

Requires Android 8.0 (API 26) or later.

## Features
* [x] **No login needed** 
* [x] **Subscriptions without an account**
* [x] **Your YouTube subscriptions** optional
* [x] **Home for discovering**
* [x] **No ads**
* [x] **Smarter autoplay**
* [x] **Swipe right to queue a video**
* [x] **Description and Comments tabs**
* [x] **SponsorBlock**
* [x] **AI video blocker**
* [x] **Mini player**
* [x] **Local queue** 
* [x] **Background play**
* [x] **Downloader**
* [x] **Watched videos greyed out**
* [x] **Local history**
* [x] **Easy on data**

## How it works

Pretty much everything is native. Player, Home, Subscriptions, search, channel and playlist pages, History, queue, the stuff under the video and the downloader are all drawn by the app and get their data straight from YouTube through the NewPipe extractor. No YouTube website underneath, no injected scripts.

There are only two web views left: Google sign-in and live chat.

Subscriptions either come from the channels you follow in the app (public channel feeds, no account) or, if you turn on "Use my YouTube account", from your actual YouTube subs.

## Privacy

* No analytics, no crash reporting, no ads. History, queue and followed channels stay on your phone, and backups are off.
* YouTube/Google still see your IP and what you watch, same as with any other client so use a VPN.
* The only other service is SponsorBlock: it gets the first 4 characters of a hash of the video ID (sponsor.ajay.app).
* 5 seconds after start the app asks GitHub (api.github.com) if there's a new release. Nothing about you is sent. To turn it off tap the TestTube logo on Home and untick "Check automatically".

## Screenshots

<p align="center">
<img src="screens/1.jpeg" alt="" width="200">
<img src="screens/2.jpeg" alt="" width="200">
<img src="screens/3.jpeg" alt="" width="200">
</p>

## Contributing

Found a bug? Check the issues first, if it's not there open one. PRs welcome.

## License

GPL-3.0. NewPipe Extractor is GPL-3.0-or-later.

#BY

techniix / cuteLiLi / QuacK
