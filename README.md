TestTube
============

by: techniix / cuteLiLi / QuacK

A continuation of the Litube project: https://github.com/HydeYYHH/litube

TestTube is an advanced webview wrapper for YouTube.


## Features
* [x] **Ad-free playback**
* [x] **Sponsor-block**
* [x] **Mini-player support**
* [x] **Local queue support**
* [x] **Background & Picture-in-Picture support**
* [x] **Built-in video and playlist downloader**
* [x] **Live stream chat support, etc**
* [x] **Watched videos greyed out (75% watched)**
* [x] **Local History**


## Screenshots
<p align="center">
<img title="" src="screens/1.png" alt="" width="200"><img title="" src="screens/2.png" alt="" width="200"><img title="" src="screens/3.png" alt="" width="200"><img title="" src="screens/4.png" alt="" width="200">
</p>

## Building

Requirements: Linux or macOS with `bash`, `curl` and `unzip`. Everything else (JDK 21, Android SDK, signing key) is installed by the script into `~/.testtube`.

```
./BUILD.sh              # signed release APK in dist/
./BUILD.sh --install    # build and install on a USB-connected phone (USB debugging on)
./BUILD.sh --debug      # debug APK
./BUILD.sh --clean      # clean build
```

## Contributing

If you encounter a bug, please check the GitHub repository to see if an issue has already been reported. If not, feel free to open a new one. Code contributions and pull requests are always welcome!


## License

GPL-3.0. TestTube is derived from Litube by HydeYYHH (GPL-3.0); this notice is required by the license. NewPipe Extractor is GPL-3.0-or-later.
