TestTube
========

by: techniix / cuteLiLi / QuacK

TestTube is an ad-free YouTube client for Android. Home, search, queue and history work without an account; signing in is optional and only used for the Subscriptions tab.

## Features
* [x] **Works without login** (Home, search, queue and history); optional sign-in for Subscriptions
* [x] **Ad-free playback**
* [x] **Suggested videos under the player, always a next video to play**
* [x] **Description and Comments tabs (live chat for live streams)**
* [x] **Sponsor-block**
* [x] **Mini-player support**
* [x] **Local queue support**
* [x] **Background & Picture-in-Picture support**
* [x] **Built-in video and playlist downloader**
* [x] **Watched videos greyed out (75% watched)**
* [x] **Local History**

## Building

Requirements: Linux or macOS with `bash`, `curl` and `unzip`. Everything else (JDK 21, Android SDK, signing key) is installed by the script into `~/.testtube`.

```
./BUILD.sh              # signed release APK in dist/
./BUILD.sh --install    # build and install on a USB-connected phone (USB debugging on)
./BUILD.sh --debug      # debug APK
./BUILD.sh --clean      # clean build
./BUILD.sh --verbose    # more detailed log
```

The build writes `build.log` live (follow it with `tail -f build.log`); on failure the errors are collected in `build-errors.log`.

## Contributing

If you encounter a bug, please check the GitHub repository to see if an issue has already been reported. If not, feel free to open a new one. Code contributions and pull requests are always welcome!

## License

GPL-3.0. TestTube is derived from Litube by HydeYYHH (GPL-3.0); this notice is required by the license. NewPipe Extractor is GPL-3.0-or-later.
