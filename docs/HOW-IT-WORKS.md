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

## Privacy

- **No analytics, crash reporting or ads.** History, the queue and the channels you follow stay on
  the phone, and Android backups are off.
- **SponsorBlock** only gets the first 4 characters of a SHA-256 hash of the video ID
  (sponsor.ajay.app), so it can't tell which video you're watching. There are no dislike-count
  lookups.
- **Hide AI videos** (off by default) downloads the community AiSList from
  raw.githubusercontent.com once a day. Nothing about you is sent.
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
  1) Build a signed release APK
  2) Build a signed release APK and publish it to GitHub Releases
  3) Publish an already-built release APK to GitHub Releases
  4) Build a debug APK (no signing key needed)
  5) Install the built APK to a connected phone (shows the real error if it fails)
  6) Run the test suite
  7) Package a source archive for a reviewer (no keys)
  8) Record dependency hashes (supply-chain verification)
  9) Push source to GitHub (no release)
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
./BUILD.sh --release              # signed release APK
./BUILD.sh --release --install    # and install it on the connected phone
./BUILD.sh --debug                # debug APK
./BUILD.sh --test                 # the test suite
./BUILD.sh --publish              # signed APK, published to GitHub Releases
```

It downloads the JDK and Android SDK it needs into `~/.testtube` (checking their checksums),
creates your signing key the first time, and puts the APK in `dist/`. The log of each run is in
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

1. Set the new version with `./BUILD.sh --set-version` (option 10). It changes
   `app/build.gradle.kts` and adds the version's section to `CHANGELOG.md`.
2. Write what changed under it in `CHANGELOG.md`: it becomes the release notes and the text of the
   update dialog.
3. Run `./BUILD.sh --publish` (option 2). It builds the APK, checks that it is signed with your
   key, publishes it with `SHA256SUMS.txt`, and checks GitHub's size and SHA-256 of the APK and
   that it is the latest release, which is what the app's update check asks for. Publishing needs
   the GitHub CLI (`gh`); it is installed and logged in the first time.

The app only installs an update when its file matches GitHub's SHA-256 exactly and it is signed
with the same key as the app already on the phone (or that key approved the new one).
