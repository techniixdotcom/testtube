# How TestTube works

The full documentation: how the app works, what it sends where, and how to build and publish it.
The short version is in the [README](../README.md).

## How it works

Almost everything is native: the player, Home, Subscriptions, search, channel and playlist pages
(every channel tab), History, the queue, the screen under the video (description, comments and
suggestions) and the downloader are drawn by the app itself and get their data directly from
YouTube through the NewPipe Extractor. That makes it faster, smoother and fully under our control.

Only two small web views are left: the Google sign-in page, which can only open Google's and
YouTube's own sites, and the live chat of live streams. There is no YouTube website layer, no
injected scripts and no page bridge.

## Two editions

The code builds two apps (Gradle flavors in `app/build.gradle.kts`): **TestTube** (`standard`,
YouTube only) and **TestTube+** (`plus`). TestTube+ adds only what's in `app/src/plus`: opening
links from Spotify, Facebook and SoundCloud. Both have the same app id and signing key, so either installs
over the other with history and settings kept, and each one's updater only takes its own APK from
a release (`Edition.APK_PREFIX`).

- **Spotify links** (track, album, playlist, artist, podcast, spotify.link short links) are read
  from Spotify's public embed page, the one other websites show, without an account. Spotify's
  audio is encrypted and only plays in Spotify, so each song is searched on YouTube and the best
  match plays: same title and artist, about as long, and not a cover, live take or remix unless
  the song is one. An album or playlist plays its first song and queues the others (50 at most).
  An artist or a podcast show opens YouTube's search for it.
- **Facebook videos** (watch, reels, page videos, share and fb.watch links) are read from the
  video's public page, which carries direct MP4 links for Facebook's own player. There's no login,
  so private, friends-only and group videos don't play. Facebook changes its pages now and then,
  which can break this until it's updated. The rest of Facebook isn't opened by the app at all.
- **SoundCloud** (tracks, sets and albums, artists, on.soundcloud.com short links) is read through
  SoundCloud's own API with the public client id of its website, as NewPipe and yt-dlp do. A track
  plays as one MP3 file, or HLS in AAC or MP3; encrypted (DRM) formats are skipped. A set plays its
  first song and queues the others (100 at most); an artist opens YouTube's search for them. A song
  that SoundCloud only has as a 30-second preview (Go+) plays from YouTube instead.
- **Facebook videos and SoundCloud songs play in the same player as YouTube's**
  (`external/ExternalVideo`, `Engine.playExternal`): minimizing, the floating window, background
  play, the notification, the resume position, Back and the queue all work the same. When one ends,
  the queue goes on; with nothing queued, autoplay picks from YouTube's search for its title, as it
  does for a YouTube video without suggestions. The screen under it shows the title, uploader and
  description, with YouTube's search for the title as "Up next"; there are no comments, and they
  don't go into History (which opens videos on YouTube) or the downloader (YouTube's). Their ids
  have the site in front ("fb:…", "sc:…"), so they're never taken for a YouTube video's.

## Privacy

- **No analytics, crash reporting or ads.** History, the queue, your playlists and the channels you
  follow stay on the phone, and Android backups are off.
- **SponsorBlock** only gets the first 4 characters of a SHA-256 hash of the video ID
  (sponsor.ajay.app), so it can't tell which video you're watching. There are no dislike-count
  lookups.
- **Hide AI videos** (off by default) downloads the community AiSList from
  raw.githubusercontent.com once a day. Nothing about you is sent.
- **TestTube+ only:** opening a Spotify link asks open.spotify.com for that link's page; opening a
  Facebook video asks facebook.com for its page and Facebook's servers for the video; opening a
  SoundCloud link asks soundcloud.com (for its public client id) and SoundCloud's API and servers
  for the song. All like a browser without a login would. Nothing else is sent to Spotify, Facebook
  or SoundCloud, and nothing goes there unless you open such a link.
- **Update checks** ask GitHub (api.github.com) for new releases 5 seconds after the app starts,
  including test versions if "Pre-releases" is on, and updates download from github.com. Nothing
  about you is sent. To switch checks off, tap the TestTube logo on Home and turn off "Check for
  updates automatically" under Updates.

## Build

One script, `BUILD.sh`, builds, tests and publishes TestTube, on Linux, macOS or WSL2 on Windows.
Run it without options for its menu; it is the same menu as in the maintainer's other apps (NiiX,
niixfliix), with the options that fit this app:

```
./BUILD.sh
What would you like to do?
  1) Build signed release APKs (asks for the version and edition)
  2) Build both editions and publish them to GitHub Releases (asks for the version)
  3) Publish already-built release APKs to GitHub Releases
  4) Build debug APKs (no signing key needed; asks for the version and edition)
  5) Install the built APK to a connected phone (shows the real error if it fails)
  6) Run the test suite
  7) Package a source archive for a reviewer (no keys)
  8) Record dependency hashes (supply-chain verification)
  9) Push source to GitHub (no release; asks for the version)
 10) Set the version for the next release
 11) Create signing keys (once, before your first signed build)
 12) Show the signing keys
 13) Use a signing key from another folder
 14) Switch to the shared Android key (one key for all apps)
 15) Back up all signing keys (~/.keys)
 16) Delete all build output
 17) Give the project folder back to you (after a run with sudo)
```

Or say it up front (`./BUILD.sh --help` lists every option):

```
./BUILD.sh --release                      # signed release APKs, both editions
./BUILD.sh --release --edition=plus       # only TestTube+
./BUILD.sh --release --install            # and install one on the connected phone
./BUILD.sh --debug                        # debug APKs
./BUILD.sh --test                         # the test suite, both editions
./BUILD.sh --publish                      # both editions, published to GitHub Releases
```

It downloads the JDK and Android SDK it needs into `~/.testtube` (checking their checksums),
creates your signing key the first time, and puts the APKs in `dist/`: `testtube<version>.apk`
and `testtubeplus<version>.apk`. Installing over USB also sets every link of the app to open in it
(Android 12+), so nothing has to be ticked in Open by default on your own phones. The log of each run is in
`build.log`, and only the errors in `build-errors.log` when a build fails.

## Signing keys

Every signing key, for all your apps, is kept in one folder, `~/.keys`, that only you can open
(its `README.txt` says what each file is). TestTube's own key is in `~/.keys/testtube`; earlier
versions kept it in `~/.testtube/signing`, and the first run moves it.

`./BUILD.sh --use-shared-key` (option 14) moves TestTube to the Android key all your apps share
(`~/.keys/android`), for good. TestTube's own key signs, once, its approval of the shared key
(`lineage.bin`, Android's key rotation), and every APK built after that carries it: phones with
Android 9 or newer take it as a normal update and from then on only accept the shared key;
Android 8 keeps checking TestTube's own key. The in-app updater accepts the switch from 3.0.2 on,
so publish 3.0.2 or newer with the old key first; the script checks GitHub and warns otherwise.

`./BUILD.sh --backup-keys` (option 15) copies all of `~/.keys` to another folder and checks every
file. Keep two copies offline: anyone with these files can sign updates for your apps, and without
them no new version can update the app people have installed.

## Releases and updates

1. Set the new version with `./BUILD.sh --set-version` (option 10). It suggests one above the
   latest release on GitHub (3.0.3 there suggests 3.0.4; Enter takes it), changes
   `app/build.gradle.kts` and adds the version's section to `CHANGELOG.md`. Options 1, 2, 4 and 9
   ask the same question first, so the version can also be chosen right there; outside the menu,
   `--version=X.Y.Z` does it (for example `./BUILD.sh --publish --version=3.0.4`).
2. Write what changed under it in `CHANGELOG.md`: it becomes the release notes and the text of the
   update dialog. Publishing stops before building when that section is empty.
3. Run `./BUILD.sh --publish` (option 2). It builds both editions, checks that they are signed
   with your key, publishes them with `SHA256SUMS.txt`, and checks GitHub's size and SHA-256 of
   each APK, that TestTube's APK is listed first (updaters before 3.0.4 take a release's first
   APK) and that it is the latest release, which is what the apps' update check asks for.
   Publishing needs the GitHub CLI (`gh`); it is installed and logged in the first time.

The app only installs an update when its file matches GitHub's SHA-256 exactly and it is signed
with the same key as the app already on the phone (or that key approved the new one).
