#!/usr/bin/env bash
# BUILD.sh: building and releasing TestTube from one place.
#
# No arguments opens the menu. Same menu and options as NiiX's and niixfliix's BUILD.sh where
# they fit.
#
#   --release                  signed release APKs: dist/testtube<version>.apk and
#                              dist/testtubeplus<version>.apk
#   --publish                  build both and publish to GitHub Releases (APKs + SHA256SUMS.txt),
#                              verified so the updater accepts them
#   --upload-only              publish what's in dist/ without building
#   --debug                    debug APKs (debug key, installs next to release as com.testtube.app.debug)
#   --install                  install an APK from dist/ over USB with all links enabled (12+)
#   --test                     run the tests
#   --audit-zip                source zip in dist/ without keys, logs or build output
#   --write-dependency-hashes  record every library's SHA-256; Gradle then refuses changed ones
#   --push-source              push the source to GitHub as a normal commit (no release); README,
#                              screenshots and logo there stay
#   --set-version              set version name and code for the next release
#   --create-keys              create the signing key (the first release build does it too)
#   --show-keys                show key fingerprints and locations
#   --import-key DIR           use the TestTube key in DIR from now on
#   --use-shared-key           switch to the Android key all your apps share (~/.keys/android),
#                              for good; installed copies update normally
#   --backup-keys[=DIR]        copy ~/.keys to DIR/.keys (a USB stick, say) and verify
#   --clean                    delete build output
#   --fix-permissions          take the folder back after a sudo run
#
# Build options:
#   --install       install on the connected phone after building, with the key matching the
#                   TestTube already on it if that key is here
#   --reinstall     like --install but uninstalls a TestTube with an unknown key first (loses its
#                   settings, history and login)
#   --clean         clean before building
#   --verbose       more Gradle output in the log
#   --version=X.Y.Z build, publish or push as this version (written into app/build.gradle.kts);
#                   without it the version stays
#   --edition=E     standard, plus or both (default). Publishing always does both.
#
# Menu options 1 and 4 ask for the edition (Enter: both). Options 1, 2, 4, 9 and 10 ask for the
# version first; Enter takes one above GitHub's latest release (3.0.3 suggests 3.0.4), or the
# project's version if that's higher.
#
# Logs (live: tail -f build.log): build.log, build.previous.log, and build-errors.log with just
# the errors when a build fails.
#
# Environment:
#   KEYS_HOME              signing keys (default ~/.keys)
#   TESTTUBE_HOME          JDK and Android SDK (default ~/.testtube)
#   TESTTUBE_RELEASE_TAG   tag to publish as instead of v<version>
#
# Publishing needs gh, installed and logged in on first use. The repo is the one the updater
# downloads from (DOWNLOAD_PREFIX in UpdateManager.java), so a fork publishes to its own.

set -Eeuo pipefail
umask 022

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TESTTUBE_HOME_OVERRIDE="${TESTTUBE_HOME:-}"
KEYS_HOME_OVERRIDE="${KEYS_HOME:-}"
TESTTUBE_HOME="${TESTTUBE_HOME:-$HOME/.testtube}"
JDK_MAJOR=21
JDK_DIR="$TESTTUBE_HOME/jdk-$JDK_MAJOR"
SDK_DIR="$TESTTUBE_HOME/android-sdk"
# every key for every app lives in ~/.keys (see README.txt there and scripts/android-keys.sh)
KEYS_HOME="${KEYS_HOME:-$HOME/.keys}"
if [[ ! -f "$ROOT/scripts/android-keys.sh" ]]; then
	printf 'error: scripts/android-keys.sh is missing. Unzip the whole project into an empty folder.\n' >&2
	exit 1
fi
# shellcheck source=scripts/android-keys.sh
source "$ROOT/scripts/android-keys.sh"
ANDROID_KEYS="$AK_SHARED_DIR"
SHARED_KEYSTORE="$AK_SHARED_KS"
SHARED_ENV="$AK_SHARED_ENV"
# TestTube's own key, plus lineage.bin once switched to the shared key
SIGNING_DIR="$KEYS_HOME/testtube"
SIGNING_ENV="$SIGNING_DIR/signing.env"
KEYSTORE="$SIGNING_DIR/testtube-release.p12"
LINEAGE="$SIGNING_DIR/lineage.bin"
# where versions before 3.0.2 kept it, moved on first run
LEGACY_SIGNING_DIR="$TESTTUBE_HOME/signing"
DIST_DIR="$ROOT/dist"
LOG_FILE="$ROOT/build.log"
GRADLE_FILE="$ROOT/app/build.gradle.kts"
CHANGELOG="$ROOT/CHANGELOG.md"
UPDATE_MANAGER="$ROOT/app/src/main/java/com/testtube/app/update/UpdateManager.java"
APP_ID="com.testtube.app"
TMP_DIR=""

log() { printf '==> %s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# output goes through tee into build.log, and that pipe can hold a question back so the terminal
# shows nothing while we wait. Questions go straight to the terminal. Without one (CI, piped
# input) the answer comes from stdin.
ask() {
	local __prompt="$1" __var="$2" __reply=""
	if { true > /dev/tty; } 2>/dev/null; then
		printf '%s' "$__prompt" > /dev/tty
		IFS= read -r __reply < /dev/tty || __reply=""
	else
		printf '%s' "$__prompt"
		IFS= read -r __reply || __reply=""
	fi
	printf -v "$__var" '%s' "$__reply"
}

# yes/no question, Enter picks the default (y or n)
confirm() {
	local answer default="${2:-n}" hint="[y/N]"
	[[ "$default" == "y" ]] && hint="[Y/n]"
	ask "$1 $hint " answer
	answer="${answer:-$default}"
	[[ "$answer" =~ ^[Yy] ]]
}

has_tty() {
	{ true > /dev/tty; } 2>/dev/null
}

owner_of() {
	stat -c '%u:%g' "$1" 2>/dev/null || stat -f '%u:%g' "$1"
}

restore_project_access() {
	local paths=("$ROOT/.gradle" "$ROOT/build" "$ROOT/app/build" "$ROOT/dist" "$ROOT/build.log" "$ROOT/build.previous.log" "$ROOT/build-errors.log"
		"$ROOT/local.properties" "$ROOT/newpipe-extractor/.gradle" "$ROOT/newpipe-extractor/build"
		"$ROOT/newpipe-extractor/extractor/build" "$ROOT/.kotlin")
	local existing=()
	local path
	for path in "${paths[@]}"; do
		[[ -e "$path" ]] && existing+=("$path")
	done
	[[ ${#existing[@]} -gt 0 ]] || return 0
	if [[ "$(id -u)" -eq 0 ]]; then
		local owner
		owner="$(owner_of "$ROOT" || true)"
		if [[ -n "$owner" && "$owner" != "0:0" ]]; then
			chown -R "$owner" "${existing[@]}" 2>/dev/null || true
		fi
	fi
	chmod -R u+rwX,go+rX "${existing[@]}" 2>/dev/null || true
}

cleanup() {
	if [[ -n "$TMP_DIR" && -d "$TMP_DIR" ]]; then
		rm -rf "$TMP_DIR"
	fi
	restore_project_access
	# give the log writer a moment to flush
	if [[ -s "$LOG_FILE" ]]; then
		sleep 1
	fi
	return 0
}
on_error() {
	local code=$?
	printf 'error: failed (exit %s) at line %s. Full log: %s\n' "$code" "$1" "$LOG_FILE" >&2
	exit "$code"
}
trap cleanup EXIT
trap 'on_error $LINENO' ERR

usage() {
	awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
}

# ---- What to do --------------------------------------------------------------------------------

MODE=""
CLEAN=0
VARIANT="release"
VERBOSE=0
WRITE_CHECKSUMS=0
INSTALL=0
REINSTALL=0
APK_OUT=""
IMPORT_KEY=""
BACKUP_KEY=""
MENU_CHOICE=""
VERSION_OVERRIDE=""
EDITION=""
APK_OUTS=()

set_mode() {
	[[ -z "$MODE" || "$MODE" == "$1" ]] || die "Pick one action at a time ($MODE and $1 were both given)"
	MODE="$1"
}

show_menu() {
	local choice
	ask "$(printf 'What would you like to do?\n  1) Build signed release APKs (asks for the version and edition)\n  2) Build both editions and publish them to GitHub Releases (asks for the version)\n  3) Publish already-built release APKs to GitHub Releases\n  4) Build debug APKs (no signing key needed; asks for the version and edition)\n  5) Install the built APK to a connected phone (shows the real error if it fails)\n  6) Run the test suite\n  7) Package a source archive for a reviewer (no keys)\n  8) Record dependency hashes (supply-chain verification)\n  9) Push source to GitHub (no release; asks for the version)\n 10) Set the version for the next release\n 11) Create signing keys (once, before your first signed build)\n 12) Show the signing keys\n 13) Use a signing key from another folder\n 14) Switch to the shared Android key (one key for all apps)\n 15) Back up all signing keys (~/.keys)\n 16) Delete all build output\n 17) Give the project folder back to you (after a run with sudo)\nEnter a number [1-17]: ')" choice
	apply_choice "$choice"
	MENU_CHOICE="$choice"
}

apply_choice() {
	case "$1" in
		1) set_mode build ;;
		2) set_mode publish ;;
		3) set_mode upload-only ;;
		4) set_mode build; VARIANT="debug" ;;
		5) set_mode install-only ;;
		6) set_mode test ;;
		7) set_mode source-zip ;;
		8) set_mode build; WRITE_CHECKSUMS=1 ;;
		9) set_mode push-source ;;
		10) set_mode set-version ;;
		11) set_mode create-keys ;;
		12) set_mode key-info ;;
		13) set_mode import-key ;;
		14) set_mode use-shared-key ;;
		15) set_mode backup-key ;;
		16) set_mode clean ;;
		17) set_mode fix-permissions ;;
		*) die "'$1' isn't one of the options above." ;;
	esac
}

EXPECT=""
for arg in "$@"; do
	if [[ -n "$EXPECT" ]]; then
		printf -v "$EXPECT" '%s' "$arg"
		EXPECT=""
		continue
	fi
	case "$arg" in
		--release) set_mode build ;;
		--debug) set_mode build; VARIANT="debug" ;;
		--publish) set_mode publish ;;
		--upload-only) set_mode upload-only ;;
		--install-only) set_mode install-only ;;
		--test) set_mode test ;;
		--set-version) set_mode set-version ;;
		--push-source) set_mode push-source ;;
		--audit-zip|--source-zip) set_mode source-zip ;;
		--create-keys) set_mode create-keys ;;
		--show-keys|--key-info) set_mode key-info ;;
		--use-shared-key) set_mode use-shared-key ;;
		--backup-keys) set_mode backup-key ;;
		--backup-keys=*) set_mode backup-key; BACKUP_KEY="${arg#--backup-keys=}" ;;
		--backup-key) set_mode backup-key; EXPECT="BACKUP_KEY" ;;
		--backup-key=*) set_mode backup-key; BACKUP_KEY="${arg#--backup-key=}" ;;
		--import-key) EXPECT="IMPORT_KEY" ;;
		--import-key=*) IMPORT_KEY="${arg#--import-key=}" ;;
		--write-dependency-hashes|--write-checksums) WRITE_CHECKSUMS=1 ;;
		--fix-permissions) set_mode fix-permissions ;;
		--install) INSTALL=1 ;;
		--reinstall) INSTALL=1; REINSTALL=1 ;;
		--clean) CLEAN=1 ;;
		--verbose) VERBOSE=1 ;;
		--version=*) VERSION_OVERRIDE="${arg#--version=}" ;;
		--edition=*) EDITION="${arg#--edition=}" ;;
		--edition) EXPECT="EDITION" ;;
		--version) EXPECT="VERSION_OVERRIDE" ;;
		--menu-choice=*) MENU_CHOICE="${arg#--menu-choice=}"; apply_choice "$MENU_CHOICE" ;;
		--help|-h) usage; exit 0 ;;
		*) usage; die "Unknown option: $arg" ;;
	esac
done
if [[ -n "$EXPECT" ]]; then
	[[ "$EXPECT" == "VERSION_OVERRIDE" ]] && die "--version needs a version after it, like --version=3.0.4"
	[[ "$EXPECT" == "EDITION" ]] && die "--edition needs standard, plus or both after it"
	die "${arg} needs a folder after it"
fi
# --import-key alone means switch to that key; with a build it's used for that build
if [[ -n "$IMPORT_KEY" && -z "$MODE" ]]; then
	MODE="import-key"
fi
# --clean alone deletes build output; with a build it cleans first
if [[ "$CLEAN" -eq 1 && -z "$MODE" ]]; then
	MODE="clean"
fi
# --install alone installs what's already built; with a build it installs the new APK
if [[ "$INSTALL" -eq 1 && "$WRITE_CHECKSUMS" -eq 0 && -z "$MODE" ]]; then
	MODE="install-only"
fi
if [[ -z "$MODE" ]]; then
	if [[ $# -eq 0 ]] && has_tty; then
		show_menu
	else
		# no terminal (CI) or only options like --install: release build, as before
		MODE="build"
	fi
fi

case "$EDITION" in
	""|standard|plus|both) ;;
	*) die "--edition takes standard (TestTube), plus (TestTube+) or both, not '$EDITION'" ;;
esac
# a release carries both editions, each updater looks for its own APK
if [[ "$MODE" == "publish" || "$MODE" == "upload-only" ]] && [[ -n "$EDITION" && "$EDITION" != "both" ]]; then
	die "Publishing always takes both editions: TestTube+ users get their updates from the same release"
fi
# only builds and pushes take a version, an existing APK keeps its own
if [[ -n "$VERSION_OVERRIDE" ]]; then
	case "$MODE" in
		build|publish|push-source|set-version) ;;
		*) die "--version goes with --release, --debug, --publish, --push-source or --set-version, not with $MODE" ;;
	esac
fi

if [[ "$(id -u)" -eq 0 && -n "${SUDO_USER:-}" && "$SUDO_USER" != "root" && "$MODE" != "fix-permissions" ]]; then
	log "Started with sudo; continuing as $SUDO_USER so every file stays yours"
	# menu already answered, pass it on instead of asking again
	if [[ -n "$MENU_CHOICE" && $# -eq 0 ]]; then
		exec sudo -u "$SUDO_USER" -H bash "$ROOT/BUILD.sh" "--menu-choice=$MENU_CHOICE"
	fi
	exec sudo -u "$SUDO_USER" -H bash "$ROOT/BUILD.sh" "$@"
fi

fix_permissions() {
	local uid gid home
	home="$HOME"
	if [[ "$(id -u)" -eq 0 && -n "${SUDO_USER:-}" ]]; then
		[[ "$SUDO_USER" =~ ^[A-Za-z0-9._-]+$ ]] || die "Unexpected SUDO_USER value"
		uid="$(id -u "$SUDO_USER")"
		gid="$(id -g "$SUDO_USER")"
		home="$(getent passwd "$SUDO_USER" 2>/dev/null | cut -d: -f6 || true)"
		if [[ -z "$home" ]]; then
			home="$(eval echo "~$SUDO_USER")"
		fi
	else
		uid="$(id -u)"
		gid="$(id -g)"
	fi
	local user_testtube_home="${TESTTUBE_HOME_OVERRIDE:-$home/.testtube}"
	local target
	for target in "$ROOT" "$user_testtube_home"; do
		[[ -e "$target" ]] || continue
		log "Giving $target back to user $uid"
		if [[ "$(id -u)" -eq 0 ]]; then
			chown -R "$uid:$gid" "$target"
		elif ! find "$target" ! -user "$uid" -print -quit 2>/dev/null | grep -q .; then
			:
		elif command -v sudo >/dev/null 2>&1; then
			sudo chown -R "$uid:$gid" "$target"
		else
			die "Some files in $target belong to another user and sudo is not available. Run as root: chown -R $uid:$gid \"$target\""
		fi
		chmod -R u+rwX "$target"
	done
	if [[ -d "$user_testtube_home/signing" ]]; then
		chmod 700 "$user_testtube_home/signing"
		find "$user_testtube_home/signing" -type f -exec chmod 600 {} +
	fi
	local user_keys_home="${KEYS_HOME_OVERRIDE:-$home/.keys}"
	if [[ -d "$user_keys_home" ]]; then
		log "Giving $user_keys_home back to user $uid (only you can open it)"
		if [[ "$(id -u)" -eq 0 ]]; then
			chown -R "$uid:$gid" "$user_keys_home"
		elif find "$user_keys_home" ! -user "$uid" -print -quit 2>/dev/null | grep -q . && command -v sudo >/dev/null 2>&1; then
			sudo chown -R "$uid:$gid" "$user_keys_home"
		fi
		find "$user_keys_home" -type d -exec chmod 700 {} +
		find "$user_keys_home" -type f -exec chmod 600 {} +
	fi
	log "Done. You can now move, change and delete the project folder normally."
}

if [[ "$MODE" == "fix-permissions" ]]; then
	fix_permissions
	exit 0
fi

[[ -f "$LOG_FILE" ]] && mv -f "$LOG_FILE" "$ROOT/build.previous.log"
rm -f "$ROOT/build-errors.log"
: > "$LOG_FILE" 2>/dev/null || die "Cannot write $LOG_FILE. Is the folder yours? Try: ./BUILD.sh --fix-permissions"
# from here output goes to the terminal and build.log (timestamped, no colours)
if command -v perl >/dev/null 2>&1; then
	exec > >(tee >(perl -MPOSIX -pe 'BEGIN { $| = 1 } s/\e\[[0-9;?]*[A-Za-z]//g; s/\r\n/\n/; s/\r/\n/g; $_ = strftime("[%H:%M:%S] ", localtime) . $_' >> "$LOG_FILE")) 2>&1
else
	exec > >(tee -a "$LOG_FILE") 2>&1
fi
log "Started $(date '+%Y-%m-%d %H:%M:%S'): $MODE"
log "Live log: tail -f $LOG_FILE"

# ---- Toolchain ---------------------------------------------------------------------------------

detect_platform() {
	case "$(uname -s)" in
		Linux) OS="linux"; ADOPTIUM_OS="linux"; SDK_OS="linux" ;;
		Darwin) OS="mac"; ADOPTIUM_OS="mac"; SDK_OS="mac" ;;
		*) die "Unsupported operating system $(uname -s). Use Linux, macOS or WSL2 on Windows." ;;
	esac
	case "$(uname -m)" in
		x86_64|amd64) ADOPTIUM_ARCH="x64" ;;
		aarch64|arm64) ADOPTIUM_ARCH="aarch64" ;;
		*) die "Unsupported CPU architecture $(uname -m)." ;;
	esac
	log "Platform: $OS / $ADOPTIUM_ARCH"
}

run_privileged() {
	if [[ "$(id -u)" -eq 0 ]]; then
		"$@"
	elif command -v sudo >/dev/null 2>&1; then
		sudo "$@"
	else
		die "Root rights are needed to install system packages ($*). Install them manually and re-run."
	fi
}

# installs packages with whatever package manager there is
install_packages() {
	if command -v apt-get >/dev/null 2>&1; then
		run_privileged apt-get update
		run_privileged env DEBIAN_FRONTEND=noninteractive apt-get install -y "$@"
	elif command -v dnf >/dev/null 2>&1; then
		run_privileged dnf install -y "$@"
	elif command -v pacman >/dev/null 2>&1; then
		run_privileged pacman -Sy --noconfirm --needed "$@"
	elif command -v zypper >/dev/null 2>&1; then
		run_privileged zypper --non-interactive install "$@"
	elif command -v brew >/dev/null 2>&1; then
		brew install "$@"
	else
		return 1
	fi
}

ensure_system_tools() {
	local missing=()
	local tool
	for tool in curl unzip tar awk sed grep; do
		command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
	done
	if ! command -v sha256sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1; then
		missing+=("coreutils")
	fi
	if ! command -v sha1sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1; then
		missing+=("coreutils")
	fi
	if [[ ${#missing[@]} -eq 0 ]]; then
		return
	fi
	log "Installing missing system tools: ${missing[*]}"
	if command -v brew >/dev/null 2>&1; then
		install_packages curl unzip coreutils || die "Please install: ${missing[*]}"
	else
		install_packages curl unzip tar gawk sed grep coreutils ca-certificates || die "Please install: ${missing[*]}"
	fi
}

sha256_of() {
	if command -v sha256sum >/dev/null 2>&1; then
		sha256sum "$1" | awk '{print $1}'
	else
		shasum -a 256 "$1" | awk '{print $1}'
	fi
}

sha1_of() {
	if command -v sha1sum >/dev/null 2>&1; then
		sha1sum "$1" | awk '{print $1}'
	else
		shasum -a 1 "$1" | awk '{print $1}'
	fi
}

size_of() {
	stat -c %s "$1" 2>/dev/null || stat -f %z "$1"
}

download() {
	curl --proto '=https' --tlsv1.2 -fL --retry 5 --retry-delay 3 --retry-connrefused \
		--connect-timeout 30 -o "$2" "$1"
}

install_jdk() {
	if [[ -x "$JDK_DIR/bin/java" ]] && "$JDK_DIR/bin/java" -version 2>&1 | grep "version \"$JDK_MAJOR" >/dev/null; then
		log "JDK $JDK_MAJOR already installed"
		return
	fi
	log "Downloading Eclipse Temurin JDK $JDK_MAJOR"
	local api="https://api.adoptium.net/v3/binary/latest/$JDK_MAJOR/ga/$ADOPTIUM_OS/$ADOPTIUM_ARCH/jdk/hotspot/normal/eclipse"
	local url
	url="$(curl --proto '=https' --tlsv1.2 -fsS -o /dev/null -w '%{redirect_url}' "$api")"
	[[ "$url" == https://github.com/adoptium/* ]] || die "Unexpected JDK download location: $url"
	local archive="$TMP_DIR/jdk.tar.gz"
	download "$url" "$archive"
	local expected
	expected="$(curl --proto '=https' --tlsv1.2 -fsSL --retry 5 "$url.sha256.txt" | awk '{print $1}')"
	[[ "$expected" =~ ^[0-9a-f]{64}$ ]] || die "Could not obtain the JDK checksum"
	[[ "$(sha256_of "$archive")" == "$expected" ]] || die "JDK checksum mismatch, download corrupted or tampered with"
	log "JDK checksum verified"
	local unpack="$TMP_DIR/jdk"
	mkdir -p "$unpack"
	tar -xzf "$archive" -C "$unpack"
	local home
	home="$(find "$unpack" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
	[[ -n "$home" ]] || die "JDK archive is empty"
	if [[ -d "$home/Contents/Home" ]]; then
		home="$home/Contents/Home"
	fi
	rm -rf "$JDK_DIR"
	mkdir -p "$(dirname "$JDK_DIR")"
	mv "$home" "$JDK_DIR"
	"$JDK_DIR/bin/java" -version
}

install_android_sdk() {
	local sdkmanager="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
	if [[ ! -x "$sdkmanager" ]]; then
		log "Downloading Android command-line tools"
		local repo="$TMP_DIR/repository.xml"
		download "https://dl.google.com/android/repository/repository2-3.xml" "$repo"
		local best
		best="$(tr -d '\r\n' < "$repo" | sed -E 's/>[[:space:]]+</></g' \
			| awk '{ gsub(/<\/archive>/, "</archive>\n"); print }' \
			| grep -o "<checksum[^>]*>[0-9a-f]\{40\}</checksum><url>commandlinetools-$SDK_OS-[0-9]*_latest\.zip</url>" \
			| sed -E 's#<checksum[^>]*>([0-9a-f]{40})</checksum><url>(commandlinetools-[a-z]+-([0-9]+)_latest\.zip)</url>#\3 \1 \2#' \
			| sort -n | tail -n 1 || true)"
		[[ -n "$best" ]] || die "Could not find the Android command-line tools in Google's repository index"
		local sha1 file
		sha1="$(awk '{print $2}' <<< "$best")"
		file="$(awk '{print $3}' <<< "$best")"
		local zip="$TMP_DIR/cmdline-tools.zip"
		download "https://dl.google.com/android/repository/$file" "$zip"
		[[ "$(sha1_of "$zip")" == "$sha1" ]] || die "Android command-line tools checksum mismatch"
		log "Android command-line tools checksum verified ($file)"
		rm -rf "$TMP_DIR/cmdline"
		mkdir -p "$TMP_DIR/cmdline" "$SDK_DIR/cmdline-tools"
		unzip -q "$zip" -d "$TMP_DIR/cmdline"
		rm -rf "$SDK_DIR/cmdline-tools/latest"
		mv "$TMP_DIR/cmdline/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
	else
		log "Android command-line tools already installed"
	fi
	log "Accepting Android SDK licenses"
	{ yes || true; } | "$sdkmanager" --sdk_root="$SDK_DIR" --licenses >/dev/null
	log "Installing Android platform tools"
	"$sdkmanager" --sdk_root="$SDK_DIR" --install "platform-tools" >/dev/null
	if "$sdkmanager" --sdk_root="$SDK_DIR" --install "platforms;android-37" >/dev/null 2>&1; then
		log "Android platform 37 installed"
	else
		warn "platforms;android-37 is not listed under that name; Gradle will download the platform it needs"
	fi
}

write_local_properties() {
	local escaped="${SDK_DIR//\\/\\\\}"
	escaped="${escaped//:/\\:}"
	printf 'sdk.dir=%s\n' "$escaped" > "$ROOT/local.properties"
}

# JDK and Android SDK, downloaded into TESTTUBE_HOME the first time
setup_toolchain() {
	detect_platform
	ensure_system_tools
	mkdir -p "$TESTTUBE_HOME"
	install_jdk
	export JAVA_HOME="$JDK_DIR"
	export PATH="$JAVA_HOME/bin:$PATH"
	export ANDROID_HOME="$SDK_DIR"
	export ANDROID_SDK_ROOT="$SDK_DIR"
	install_android_sdk
	write_local_properties
}

# ---- Signing key -------------------------------------------------------------------------------

random_secret() {
	od -An -tx1 -N32 /dev/urandom | tr -d ' \n'
}

read_protected() {
	if [[ -r "$1" ]]; then
		cat "$1"
	elif [[ "$(id -u)" -ne 0 ]] && command -v sudo >/dev/null 2>&1; then
		sudo cat "$1"
	else
		return 1
	fi
}

protected_exists() {
	if [[ -e "$1" ]]; then
		return 0
	fi
	[[ "$(id -u)" -ne 0 ]] && command -v sudo >/dev/null 2>&1 && sudo test -e "$1"
}

# password in a signing.env: TESTTUBE_KEYSTORE_PASSWORD= (TestTube's own) or KEYSTORE_PASSWORD=
# (shared key, other apps)
signing_password_from() {
	local line value=""
	while IFS= read -r line; do
		if [[ "$line" =~ ^(TESTTUBE_)?KEYSTORE_PASSWORD=([^[:space:]]+)$ ]]; then
			value="${BASH_REMATCH[2]}"
		fi
	done
	[[ -n "$value" ]] || return 1
	printf '%s' "$value"
}

# key alias in a signing.env, or the given default
signing_alias_from() {
	local line value="$1"
	while IFS= read -r line; do
		if [[ "$line" =~ ^(TESTTUBE_)?KEY_ALIAS=([A-Za-z0-9._-]+)$ ]]; then
			value="${BASH_REMATCH[2]}"
		fi
	done
	printf '%s' "$value"
}

# writes a signing.env: file, password, alias, variable prefix (TESTTUBE_ or nothing)
write_env_file() {
	(umask 077; {
		printf '%sKEYSTORE_PASSWORD=%s\n' "$4" "$2"
		printf '%sKEY_ALIAS=%s\n' "$4" "$3"
	} > "$1")
	chmod 600 "$1"
}

write_signing_env() {
	write_env_file "$SIGNING_ENV" "$1" testtube TESTTUBE_
}

# key SHA-256 like keytool prints it (AA:BB:...), or nothing. Args: keystore, password, alias.
key_fingerprint() {
	TESTTUBE_FP_PASSWORD="$2" "$JDK_DIR/bin/keytool" -list -v -keystore "$1" -storetype PKCS12 \
		-storepass:env TESTTUBE_FP_PASSWORD -alias "${3:-testtube}" 2>/dev/null \
		| awk -F': ' '/SHA256:/ {print $2; exit}' || true
}

# same, lower case without colons like apksigner. Args: keystore, its signing.env, default alias.
keystore_digest() {
	local password alias
	password="$(read_protected "$2" | signing_password_from)" || return 1
	alias="$(read_protected "$2" | signing_alias_from "$3")"
	local copy="$TMP_DIR/candidate.p12"
	(umask 077; read_protected "$1" > "$copy") || return 1
	key_fingerprint "$copy" "$password" "$alias" | tr -d ':' | tr 'A-F' 'a-f'
	rm -f "$copy"
}

# ~/.keys holds every key for every app, only you can open it (scripts/android-keys.sh)
secure_keys_home() {
	mkdir -p "$SIGNING_DIR"
	ak_secure || die "Could not set up $KEYS_HOME"
}

# moves TestTube's key to ~/.keys/testtube from where older versions kept it
migrate_keys() {
	local legacy
	for legacy in "$KEYS_HOME/android/old/testtube" "$LEGACY_SIGNING_DIR"; do
		[[ -f "$legacy/testtube-release.p12" ]] || continue
		ak_move_dir "$legacy" "$SIGNING_DIR" || die "Could not move TestTube's signing key from $legacy to $SIGNING_DIR"
		rmdir "$KEYS_HOME/android/old" 2>/dev/null || true
		log "Moved TestTube's signing key from $legacy to $SIGNING_DIR"
	done
}

rotated() {
	[[ -f "$LINEAGE" ]]
}

shared_key_exists() {
	ak_shared_exists
}

# digest updates must carry: the shared key once switched, TestTube's own until then
release_digest() {
	if rotated; then
		keystore_digest "$SHARED_KEYSTORE" "$SHARED_ENV" release
	else
		keystore_digest "$KEYSTORE" "$SIGNING_ENV" testtube
	fi
}

load_signing_key() {
	local password
	password="$(signing_password_from < "$SIGNING_ENV")" || die "$SIGNING_ENV is damaged"
	export TESTTUBE_KEYSTORE_FILE="$KEYSTORE"
	export TESTTUBE_KEYSTORE_PASSWORD="$password"
	export TESTTUBE_KEY_ALIAS="testtube"
	export TESTTUBE_KEY_PASSWORD="$password"
	local fingerprint
	fingerprint="$(key_fingerprint "$KEYSTORE" "$password" testtube)"
	[[ -n "$fingerprint" ]] || die "The signing key in $SIGNING_DIR cannot be opened with its password"
	if rotated; then
		shared_key_exists || die "TestTube uses the shared Android key, but $SHARED_KEYSTORE is missing. Restore ~/.keys from your backup."
		local shared_password shared_alias
		shared_password="$(signing_password_from < "$SHARED_ENV")" || die "$SHARED_ENV is damaged"
		shared_alias="$(signing_alias_from release < "$SHARED_ENV")"
		local shared
		shared="$(key_fingerprint "$SHARED_KEYSTORE" "$shared_password" "$shared_alias")"
		[[ -n "$shared" ]] || die "The shared Android key in $ANDROID_KEYS cannot be opened with its password"
		log "Signing with the shared Android key (SHA-256): $shared"
		log "  and TestTube's original key for Android 8 (SHA-256): $fingerprint"
	else
		log "Signing key fingerprint (SHA-256): $fingerprint"
	fi
}

import_signing_key() {
	local source="${1%/}"
	protected_exists "$source/testtube-release.p12" || die "No testtube-release.p12 in $source"
	protected_exists "$source/signing.env" || die "No signing.env in $source"
	local password
	password="$(read_protected "$source/signing.env" | signing_password_from)" || die "Could not read the password from $source/signing.env"
	secure_keys_home
	if [[ -e "$KEYSTORE" || -e "$SIGNING_ENV" ]]; then
		rotated && die "TestTube already switched to the shared Android key; replacing its original key would break updates for Android 8 phones"
		local backup
		backup="$SIGNING_DIR/replaced-$(date +%Y%m%d-%H%M%S)"
		mkdir -p "$backup"
		chmod 700 "$backup"
		[[ -e "$KEYSTORE" ]] && mv "$KEYSTORE" "$backup/"
		[[ -e "$SIGNING_ENV" ]] && mv "$SIGNING_ENV" "$backup/"
		warn "The key that was in use was moved to $backup"
	fi
	(umask 077; read_protected "$source/testtube-release.p12" > "$KEYSTORE")
	chmod 600 "$KEYSTORE"
	write_signing_env "$password"
	if protected_exists "$source/lineage.bin"; then
		(umask 077; read_protected "$source/lineage.bin" > "$LINEAGE")
		chmod 600 "$LINEAGE"
	fi
	log "Imported the signing key from $source"
}

ensure_signing_key() {
	migrate_keys
	secure_keys_home
	if [[ -f "$KEYSTORE" && -f "$SIGNING_ENV" ]]; then
		log "Using TestTube's signing key in $SIGNING_DIR"
	elif [[ -f "$KEYSTORE" || -f "$SIGNING_ENV" ]]; then
		die "Signing key files in $SIGNING_DIR are incomplete. Restore them from your backup, or delete that folder to create a new key (a new key cannot update an installed TestTube)."
	else
		local old="/root/.testtube/signing"
		if [[ "$HOME" != "/root" ]] && protected_exists "$old/testtube-release.p12"; then
			log "Found the signing key of an earlier sudo build in $old"
			import_signing_key "$old"
		else
			log "Creating a new release signing key in $SIGNING_DIR"
			local password
			password="$(random_secret)"
			TESTTUBE_KEYSTORE_PASSWORD="$password"
			export TESTTUBE_KEYSTORE_PASSWORD
			(umask 077; "$JDK_DIR/bin/keytool" -genkeypair -noprompt \
				-keystore "$KEYSTORE" -storetype PKCS12 \
				-storepass:env TESTTUBE_KEYSTORE_PASSWORD -keypass:env TESTTUBE_KEYSTORE_PASSWORD \
				-alias testtube -keyalg RSA -keysize 4096 -validity 36500 \
				-dname "CN=TestTube, O=TestTube")
			chmod 600 "$KEYSTORE"
			write_signing_env "$password"
			warn "Back up $KEYS_HOME (option 15). Without it future builds cannot update the installed app."
		fi
	fi
	load_signing_key
}

show_key_info() {
	migrate_keys
	[[ -f "$KEYSTORE" && -f "$SIGNING_ENV" ]] || die "No signing key yet in $SIGNING_DIR. The first release build creates one."
	load_signing_key
	log "All keys are kept in: $KEYS_HOME"
	if rotated; then
		log "TestTube uses the shared Android key. Phones with TestTube signed by its original key update"
		log "to it by themselves; Android 8 phones keep checking the original key."
	elif shared_key_exists; then
		log "There is a shared Android key in $ANDROID_KEYS that TestTube doesn't use yet (option 14)."
	fi
	log "Every update has to be signed with these keys; the app refuses anything else."
}

# copies all of ~/.keys to DIR/.keys and verifies every file
backup_signing_key() {
	migrate_keys
	if [[ ! -d "$KEYS_HOME" ]] || ! find "$KEYS_HOME" -type f \( -name '*.p12' -o -name '*.jks' -o -name '*.pem' \) -print -quit | grep -q .; then
		die "No keys yet in $KEYS_HOME. The first release build creates TestTube's."
	fi
	local target="$BACKUP_KEY"
	if [[ -z "$target" ]]; then
		ask "Folder to copy all signing keys to (a USB stick, for example): " target
	fi
	[[ -n "$target" ]] || die "No folder given"
	target="${target%/}"
	case "$target" in
		"$ROOT"|"$ROOT"/*) die "Not inside the project folder: it gets zipped and pushed, and the keys would go with it" ;;
	esac
	ak_backup "$target" || die "Nothing was backed up"
}

# ---- One Android key for all apps ---------------------------------------------------------------

# points scripts/android-keys.sh at this toolchain
key_tools() {
	AK_KEYTOOL="$JDK_DIR/bin/keytool"
	AK_JAVA_HOME="$JDK_DIR"
	AK_APKSIGNER="$(apksigner_bin)"
}

# first TestTube whose updater accepts a key change
ROTATION_AWARE_CODE=30002
APP_MIN_SDK=26

# switches TestTube to the shared key for good (see scripts/android-keys.sh)
switch_to_shared_key() {
	setup_toolchain
	ensure_signing_key
	ensure_build_tools
	key_tools
	if rotated; then
		log "TestTube already uses the shared Android key"
		return
	fi
	# TestTube before 3.0.2 has an updater that only takes the exact old key. Android itself accepts
	# the switch, so those people have to grab the APK by hand once.
	local slug published="" code=""
	if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
		slug="$(repo_slug || true)"
		[[ -n "$slug" ]] && published="$(gh api "repos/$slug/releases/latest" --jq .tag_name 2>/dev/null || true)"
		code="$(version_code_for "${published#v}")"
	fi
	if [[ -z "$code" ]]; then
		warn "Couldn't check GitHub. Only switch once a release from 3.0.2 on, signed with the old key, is out: older versions' in-app updater refuses the new key."
	elif (( code < ROTATION_AWARE_CODE )); then
		warn "The latest release on GitHub is $published. Its in-app updater refuses a new key, so people would have to download the next update by hand."
		warn "Better: first publish 3.0.2 or newer with the old key (option 2), give people time to update, then switch."
	else
		log "The latest release on GitHub ($published) already accepts the switch"
	fi
	local password
	password="$(signing_password_from < "$SIGNING_ENV")"
	ak_switch TestTube "$KEYSTORE" "$password" "$password" testtube "$LINEAGE" || die "TestTube was not switched"
	log "Back up $KEYS_HOME now (option 15)."
}

# re-signs a release APK with TestTube's key and the shared one, then checks it
sign_rotated() {
	local apk="$1" password
	key_tools
	password="$(signing_password_from < "$SIGNING_ENV")"
	ak_sign "$apk" "$KEYSTORE" "$password" "$password" testtube "$LINEAGE" || die "Could not sign $(basename "$apk") with the shared key"
	check_rotated "$apk"
	log "Signed with the shared Android key (Android 9+) and TestTube's own key (Android 8)"
}

# a switched APK: Android 8 sees TestTube's own key, 9+ the shared one
check_rotated() {
	key_tools
	ak_check "$1" "$(keystore_digest "$KEYSTORE" "$SIGNING_ENV" testtube)" "$APP_MIN_SDK" || die "$(basename "$1") isn't signed the way the switch needs"
}

pin_gradle_checksum() {
	local props="$ROOT/gradle/wrapper/gradle-wrapper.properties"
	if grep -qE '^distributionSha256Sum=[0-9a-f]{64}$' "$props"; then
		return
	fi
	local url
	url="$(grep -E '^distributionUrl=' "$props" | head -n 1 | cut -d= -f2- | sed 's#\\:#:#g')"
	[[ "$url" == https://services.gradle.org/distributions/* ]] || die "Unexpected Gradle distribution URL: $url"
	local expected
	expected="$(curl --proto '=https' --tlsv1.2 -fsSL --retry 5 "$url.sha256" | tr -d '[:space:]')"
	[[ "$expected" =~ ^[0-9a-f]{64}$ ]] || die "Could not obtain the Gradle checksum"
	grep -v '^distributionSha256Sum=' "$props" > "$props.tmp"
	printf 'distributionSha256Sum=%s\n' "$expected" >> "$props.tmp"
	mv "$props.tmp" "$props"
	log "Pinned the Gradle download to its published checksum"
}

# ---- Gradle ------------------------------------------------------------------------------------

gradle_heap_mb() {
	local total_mb=4096
	if [[ -r /proc/meminfo ]]; then
		total_mb=$(( $(awk '/MemTotal/ {print $2}' /proc/meminfo) / 1024 ))
	elif command -v sysctl >/dev/null 2>&1; then
		total_mb=$(( $(sysctl -n hw.memsize) / 1024 / 1024 ))
	fi
	local heap=$(( total_mb / 2 ))
	if (( heap > 4096 )); then
		heap=4096
	fi
	if (( heap < 1536 )); then
		heap=1536
	fi
	if (( total_mb < 4096 )); then
		warn "Only ${total_mb} MB RAM detected. The build may be slow or run out of memory."
	fi
	echo "$heap"
}

# copies the lines explaining a failed Gradle run into build-errors.log and shows them
extract_errors() {
	local src="$1" out="$ROOT/build-errors.log"
	{
		echo "Errors from the run of $(date '+%Y-%m-%d %H:%M:%S')"
		echo "(the complete output is in $LOG_FILE)"
		echo
		grep -nE '(^|[[:space:]])error:|^e: |cannot find symbol|incompatible types|FAILED$|\* What went wrong|Execution failed|Caused by:|AAPT|BUILD FAILED|expected:<|AssertionError' -A4 "$src" 2>/dev/null | head -n 250 || true
	} > "$out"
	printf -- '---- What went wrong ----\n'
	head -n 70 "$out"
	printf -- '-------------------------\n'
	log "Errors only: $out"
}

gradle_args() {
	local heap
	heap="$(gradle_heap_mb)"
	GRADLE_ARGS=(--no-daemon --stacktrace --console=plain
		"-Dorg.gradle.jvmargs=-Xmx${heap}m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8")
	[[ "$VERBOSE" -eq 1 ]] && GRADLE_ARGS+=(--info)
	[[ "$WRITE_CHECKSUMS" -eq 1 ]] && GRADLE_ARGS+=(--write-verification-metadata sha256)
	chmod +x "$ROOT/gradlew"
}

# runs Gradle, retrying only network failures
run_gradle_tasks() {
	gradle_args
	local attempt gradle_out
	gradle_out="$(mktemp)"
	for attempt in 1 2 3; do
		log "Running Gradle $* (attempt $attempt of 3)"
		if (cd "$ROOT" && ./gradlew "${GRADLE_ARGS[@]}" "$@" 2>&1 | tee "$gradle_out"); then
			rm -f "$gradle_out"
			return 0
		fi
		# compile errors and failing tests fail the same way every time
		if ! grep -qiE 'Could not (resolve|GET|HEAD|download)|timed out|Connection (reset|refused)|UnknownHost|SSLHandshake|Premature end' "$gradle_out"; then
			extract_errors "$gradle_out"
			rm -f "$gradle_out"
			die "Gradle failed (not a network problem, so it was not retried). See $ROOT/build-errors.log"
		fi
		if [[ "$attempt" -lt 3 ]]; then
			warn "Gradle failed on the network, retrying in 15 seconds"
			sleep 15
		fi
	done
	extract_errors "$gradle_out"
	rm -f "$gradle_out"
	die "Gradle failed three times. See $ROOT/build-errors.log and $LOG_FILE"
}

run_gradle_build() {
	if [[ "$CLEAN" -eq 1 ]]; then
		log "Cleaning previous outputs"
		rm -rf "$DIST_DIR"
		gradle_args
		(cd "$ROOT" && ./gradlew "${GRADLE_ARGS[@]}" clean)
	fi
	local build="Release" edition
	local tasks=()
	[[ "$VARIANT" == "debug" ]] && build="Debug"
	while IFS= read -r edition; do
		tasks+=(":app:assemble$(edition_task_name "$edition")$build")
	done < <(selected_editions)
	run_gradle_tasks "${tasks[@]}"
}

# ---- Editions ----------------------------------------------------------------------------------

# standard = TestTube (YouTube), plus = TestTube+ (Spotify, Facebook, SoundCloud too). One id and
# key so either installs over the other. A release has both APKs; each updater only takes its own
# (Edition.APK_PREFIX).

edition_label() {
	case "$1" in
		standard) printf 'TestTube' ;;
		plus) printf 'TestTube+' ;;
		*) die "Unknown edition $1" ;;
	esac
}

# APK name before the version (app/build.gradle.kts uses the same)
edition_prefix() {
	case "$1" in
		standard) printf 'testtube' ;;
		plus) printf 'testtubeplus' ;;
		*) die "Unknown edition $1" ;;
	esac
}

# flavor name as it appears in task names: assembleStandardRelease
edition_task_name() {
	case "$1" in
		standard) printf 'Standard' ;;
		plus) printf 'Plus' ;;
		*) die "Unknown edition $1" ;;
	esac
}

# editions this run builds, TestTube first: both unless one was picked
selected_editions() {
	case "${EDITION:-both}" in
		both) printf '%s\n' standard plus ;;
		*) printf '%s\n' "$EDITION" ;;
	esac
}

# menu: which edition, Enter for both
pick_edition() {
	[[ -n "$EDITION" ]] && return 0
	if [[ -n "$MENU_CHOICE" ]] && has_tty; then
		local answer
		ask "$(printf 'Which edition?\n  1) TestTube (YouTube)\n  2) TestTube+ (YouTube, Spotify links, Facebook videos)\n  3) Both\nEnter a number [3]: ')" answer
		case "${answer:-3}" in
			1) EDITION="standard" ;;
			2) EDITION="plus" ;;
			3) EDITION="both" ;;
			*) die "'$answer' isn't one of the options above." ;;
		esac
	fi
	EDITION="${EDITION:-both}"
}

# ---- Versions ----------------------------------------------------------------------------------

# version name without the "v", from app/build.gradle.kts (3.0.2)
read_version_name() {
	local name
	name="$(grep -E '^val appVersionName[[:space:]]*=' "$GRADLE_FILE" | head -n 1 | sed -E 's/.*"(.*)".*/\1/')"
	printf '%s' "${name#v}"
}

read_version_code() {
	grep -E '^val appVersionCode[[:space:]]*=' "$GRADLE_FILE" | head -n 1 | sed -E 's/[^0-9]*([0-9]+).*/\1/'
}

# 3.0.2 -> 30002, the scheme every release used. Empty if unreadable.
version_code_for() {
	if [[ "$1" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
		local major=$((10#${BASH_REMATCH[1]})) minor=$((10#${BASH_REMATCH[2]})) patch=$((10#${BASH_REMATCH[3]}))
		(( minor < 100 && patch < 100 )) || return 0
		printf '%s' $(( major * 10000 + minor * 100 + patch ))
	fi
}

# a newer than b, numerically (3.0.10 beats 3.0.9)
version_newer() {
	[[ "$1" != "$2" && "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n 1)" == "$1" ]]
}

# plain version (3.0.4), or nothing if it isn't three dotted numbers
normalize_version() {
	local name="${1#v}"
	if [[ "$name" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
		printf '%s.%s.%s' "$((10#${BASH_REMATCH[1]}))" "$((10#${BASH_REMATCH[2]}))" "$((10#${BASH_REMATCH[3]}))"
	fi
}

# next version: 3.0.3 -> 3.0.4, 3.0.99 -> 3.1.0 since the code has two digits each
next_version() {
	if [[ "$1" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
		local major=$((10#${BASH_REMATCH[1]})) minor=$((10#${BASH_REMATCH[2]})) patch=$((10#${BASH_REMATCH[3]}))
		patch=$((patch + 1))
		if (( patch > 99 )); then
			patch=0
			minor=$((minor + 1))
		fi
		if (( minor > 99 )); then
			minor=0
			major=$((major + 1))
		fi
		printf '%s.%s.%s' "$major" "$minor" "$patch"
	fi
}

# latest GitHub release (3.0.3), or nothing. Via gh when logged in, else the public API (the repo
# is public, the app downloads updates there).
latest_release_version() {
	local slug tag=""
	slug="$(repo_slug || true)"
	[[ -n "$slug" ]] || return 0
	if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
		tag="$(gh api "repos/$slug/releases/latest" --jq .tag_name 2>/dev/null || true)"
	fi
	if [[ -z "$tag" ]] && command -v curl >/dev/null 2>&1; then
		tag="$(curl --proto '=https' --tlsv1.2 -fsSL --retry 2 --connect-timeout 10 --max-time 30 \
			-H 'Accept: application/vnd.github+json' "https://api.github.com/repos/$slug/releases/latest" 2>/dev/null \
			| sed -n 's/^[[:space:]]*"tag_name":[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1 || true)"
	fi
	normalize_version "$tag"
}

# writes name and code into app/build.gradle.kts. For a release also adds the empty CHANGELOG.md
# section and the F-Droid notes file, to fill in before publishing.
write_version() {
	local name="$1" code="$2" purpose="$3" current current_code
	current="$(read_version_name)"
	current_code="$(read_version_code)"
	if [[ "$name" == "$current" && "$code" == "$current_code" ]]; then
		log "Version: $name (code $code), as it was"
	else
		sed -i.bak -E "s/^val appVersionName[[:space:]]*=.*/val appVersionName = \"v$name\"/; s/^val appVersionCode[[:space:]]*=.*/val appVersionCode = $code/" "$GRADLE_FILE"
		rm -f "$GRADLE_FILE.bak"
		[[ "$(read_version_name)" == "$name" && "$(read_version_code)" == "$code" ]] || die "Could not write the version into $GRADLE_FILE"
		log "Version set: $current ($current_code) -> $name ($code)"
	fi
	case "$purpose" in
		set|build|publish) ;;
		*) return 0 ;;
	esac
	if [[ -f "$CHANGELOG" ]] && ! grep -qxF "## $name" "$CHANGELOG" && ! grep -qxF "## v$name" "$CHANGELOG"; then
		awk -v v="$name" 'done == 0 && /^## / { print "## " v "\n"; done = 1 } { print }' "$CHANGELOG" > "$CHANGELOG.tmp"
		mv "$CHANGELOG.tmp" "$CHANGELOG"
		log "Added an empty '## $name' section to CHANGELOG.md. Write what changed there before publishing."
	fi
	local fastlane="$ROOT/fastlane/metadata/android/en-US/changelogs/$code.txt"
	if [[ -d "$(dirname "$fastlane")" && ! -f "$fastlane" ]]; then
		: > "$fastlane"
		log "Created $fastlane for the F-Droid notes (500 characters at most)"
	fi
}

# asks for the version, suggesting one above GitHub's latest (or the project's if higher), checks
# it and writes it. --version=X.Y.Z skips the question. What it's for decides refuse vs warn:
#   set, publish  must beat GitHub's latest or the app won't offer it
#   build, source warns if not newer: phones with GitHub's version won't take it
#   debug         separate app (.debug), GitHub's version doesn't matter
choose_version() {
	local purpose="$1" current current_code published suggested name code entered
	current="$(normalize_version "$(read_version_name)")"
	current_code="$(read_version_code)"
	[[ -n "$current" && -n "$current_code" ]] || die "Could not read the version from $GRADLE_FILE"
	published="$(latest_release_version)"
	if [[ -n "$published" ]]; then
		suggested="$(next_version "$published")"
		if version_newer "$current" "$suggested"; then
			suggested="$current"
		fi
	else
		warn "Couldn't read the latest release from GitHub, so the suggestion is one above the project's version"
		suggested="$(next_version "$current")"
	fi
	if [[ -n "$VERSION_OVERRIDE" ]]; then
		entered="$VERSION_OVERRIDE"
	else
		ask "Version (GitHub: ${published:-unknown}, project: $current) [$suggested]: " entered
		entered="${entered:-$suggested}"
	fi
	name="$(normalize_version "$entered")"
	[[ -n "$name" ]] || die "'$entered' isn't a version. A version looks like 3.0.4: three numbers with dots"
	code="$(version_code_for "$name")"
	[[ -n "$code" ]] || die "In $name the middle and last number have to stay below 100"
	if [[ -n "$published" ]] && ! version_newer "$name" "$published"; then
		case "$purpose" in
			set|publish) die "Version $name isn't newer than $published, the latest release on GitHub. The app wouldn't offer it as an update." ;;
			build|source) warn "Version $name isn't newer than $published on GitHub: phones that have $published won't take it as an update" ;;
		esac
	fi
	if (( code < current_code )); then
		warn "Version $name is lower than the project's $current: a phone with a $current build refuses it until that is uninstalled"
	fi
	write_version "$name" "$code" "$purpose"
}

# menu or --version=X.Y.Z: pick the version first. Otherwise (other options, CI) use what's in
# app/build.gradle.kts.
pick_version() {
	if [[ -n "$VERSION_OVERRIDE" ]] || { [[ -n "$MENU_CHOICE" ]] && has_tty; }; then
		choose_version "$1"
	fi
}

# notes under "## <version>" in CHANGELOG.md, up to the next version
changelog_notes() {
	[[ -f "$CHANGELOG" ]] || return 0
	awk -v v="$1" '
		$0 ~ "^## " { if (found) exit; if ($0 == "## " v || $0 == "## v" v) { found = 1; next } }
		found { print }
	' "$CHANGELOG" | sed -e '/./,$!d' | sed -e ':a' -e '/^\n*$/{$d;N;ba' -e '}'
}

# ---- The APK -----------------------------------------------------------------------------------

apksigner_bin() {
	find "$SDK_DIR/build-tools" -maxdepth 2 -name apksigner -type f 2>/dev/null | sort -V | tail -n 1 || true
}

aapt2_bin() {
	find "$SDK_DIR/build-tools" -maxdepth 2 -name aapt2 -type f 2>/dev/null | sort -V | tail -n 1 || true
}

ensure_build_tools() {
	[[ -n "$(apksigner_bin)" ]] && return 0
	local sdkmanager="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
	local package
	package="$("$sdkmanager" --sdk_root="$SDK_DIR" --list 2>/dev/null | awk '{print $1}' \
		| grep -E '^build-tools;[0-9]+\.[0-9]+\.[0-9]+$' | sort -t';' -k2 -V | tail -n 1 || true)"
	[[ -n "$package" ]] || die "Could not find Android build tools to read APK signatures"
	log "Installing $package"
	"$sdkmanager" --sdk_root="$SDK_DIR" --install "$package" >/dev/null
}

# path of an edition's APK for a version: apk_path_for 3.0.4 plus
apk_path_for() {
	local version="$1" prefix
	prefix="$(edition_prefix "${2:-standard}")"
	if [[ "$VARIANT" == "debug" ]]; then
		printf '%s' "$DIST_DIR/$prefix$version-debug.apk"
	else
		printf '%s' "$DIST_DIR/$prefix$version.apk"
	fi
}

# SHA-256 of the APK's signing cert, lower case, no colons
apk_digest() {
	JAVA_HOME="$JDK_DIR" "$(apksigner_bin)" verify --print-certs "$1" 2>/dev/null \
		| awk -F': ' '/Signer #1 certificate SHA-256 digest/ {print $2; exit}' | tr 'A-F' 'a-f'
}

apk_version_name() {
	local aapt2
	aapt2="$(aapt2_bin)"
	[[ -n "$aapt2" ]] || return 0
	"$aapt2" dump badging "$1" 2>/dev/null | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -n 1 || true
}

# checks every APK built this run (signature, version), drops that edition's older ones from dist/
# and lists them in APK_OUTS, TestTube first
collect_apk() {
	local version edition out built prefix
	version="$(read_version_name)"
	[[ -n "$version" ]] || die "Could not read appVersionName from $GRADLE_FILE"
	ensure_build_tools
	APK_OUTS=()
	while IFS= read -r edition; do
		out="$(apk_path_for "$version" "$edition")"
		[[ -f "$out" ]] || die "APK not found at $out"
		if [[ "$VARIANT" == "release" ]] && rotated; then
			sign_rotated "$out"
		fi
		JAVA_HOME="$JDK_DIR" "$(apksigner_bin)" verify "$out"
		log "$(edition_label "$edition"): signature verified"
		built="$(apk_version_name "$out")"
		if [[ -n "$built" ]]; then
			[[ "$built" == "v$version" ]] || die "$(basename "$out") reports version '$built' but $GRADLE_FILE says v$version"
			log "$(edition_label "$edition"): version $built"
		fi
		# only this edition's: testtube[0-9]* never matches testtubeplus…
		prefix="$(edition_prefix "$edition")"
		if [[ "$VARIANT" == "debug" ]]; then
			find "$DIST_DIR" -maxdepth 1 -name "${prefix}[0-9]*-debug.apk" ! -name "$(basename "$out")" -delete 2>/dev/null || true
		else
			find "$DIST_DIR" -maxdepth 1 -name "${prefix}[0-9]*.apk" ! -name '*-debug.apk' ! -name "$(basename "$out")" -delete 2>/dev/null || true
		fi
		log "$(edition_label "$edition") SHA-256: $(sha256_of "$out")"
		log "Done. $(edition_label "$edition"): $out"
		APK_OUTS+=("$out")
	done < <(selected_editions)
}

# which fresh APK goes on the phone: the only one, or the one asked for (TestTube without a terminal)
choose_install_apk() {
	[[ ${#APK_OUTS[@]} -gt 0 ]] || die "No APK was built"
	APK_OUT="${APK_OUTS[0]}"
	[[ ${#APK_OUTS[@]} -gt 1 ]] || return 0
	local answer="1"
	if has_tty; then
		ask "$(printf 'Install which on the phone?\n  1) TestTube\n  2) TestTube+\nEnter a number [1]: ')" answer
	fi
	case "${answer:-1}" in
		1) APK_OUT="${APK_OUTS[0]}" ;;
		2) APK_OUT="${APK_OUTS[1]}" ;;
		*) die "'$answer' isn't one of the options above." ;;
	esac
}

# ---- Phone -------------------------------------------------------------------------------------

adb_bin() {
	printf '%s' "$SDK_DIR/platform-tools/adb"
}

device_ready() {
	[[ -x "$(adb_bin)" ]] && [[ "$("$(adb_bin)" get-state 2>/dev/null | tr -d '\r')" == "device" ]]
}

installed_digest() {
	local path
	path="$("$(adb_bin)" shell pm path "$APP_ID" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | grep 'base\.apk$' | head -n 1 || true)"
	[[ -n "$path" ]] || return 1
	"$(adb_bin)" pull "$path" "$TMP_DIR/installed.apk" >/dev/null 2>&1 || return 1
	apk_digest "$TMP_DIR/installed.apk"
}

key_digest() {
	local dir="$1" password
	password="$(read_protected "$dir/signing.env" | signing_password_from)" || return 1
	local copy="$TMP_DIR/candidate.p12"
	(umask 077; read_protected "$dir/testtube-release.p12" > "$copy") || return 1
	key_fingerprint "$copy" "$password" | tr -d ':' | tr 'A-F' 'a-f'
	rm -f "$copy"
}

key_folders() {
	local base
	for base in "$SIGNING_DIR" "$LEGACY_SIGNING_DIR" "/root/.testtube/signing"; do
		if [[ -d "$base" ]]; then
			find "$base" -maxdepth 2 -name testtube-release.p12 -exec dirname {} \; 2>/dev/null || true
		elif [[ "$base" == /root/* && "$(id -u)" -ne 0 ]] && command -v sudo >/dev/null 2>&1; then
			sudo find "$base" -maxdepth 2 -name testtube-release.p12 -exec dirname {} \; 2>/dev/null || true
		fi
	done
}

match_installed_key() {
	if ! device_ready; then
		warn "No phone with USB debugging found yet; the APK will be built, the install step needs the phone"
		return
	fi
	ensure_build_tools
	local wanted
	wanted="$(installed_digest || true)"
	if [[ -z "$wanted" ]]; then
		log "TestTube is not installed on the phone yet"
		return
	fi
	log "The phone's TestTube is signed with $wanted"
	local current
	current="$(key_digest "$SIGNING_DIR" || true)"
	if [[ "$current" == "$wanted" ]]; then
		log "The current signing key matches the installed app"
		return
	fi
	if shared_key_exists && [[ "$(keystore_digest "$SHARED_KEYSTORE" "$SHARED_ENV" release || true)" == "$wanted" ]]; then
		if rotated; then
			log "The installed app already uses the shared Android key"
			return
		fi
		die "The phone's TestTube uses the shared Android key, but $LINEAGE is missing, so this build can't update it. Restore ~/.keys from your backup."
	fi
	log "Looking for the matching signing key on this computer (your password may be needed)"
	local dir
	while IFS= read -r dir; do
		[[ -n "$dir" && "$dir" != "$SIGNING_DIR" ]] || continue
		if [[ "$(key_digest "$dir" || true)" == "$wanted" ]]; then
			log "Found it in $dir"
			import_signing_key "$dir"
			load_signing_key
			return
		fi
	done < <(key_folders)
	if [[ "$REINSTALL" -eq 1 ]]; then
		warn "The key used for the installed TestTube no longer exists; it will be uninstalled first"
	else
		warn "The key used for the installed TestTube is not on this computer. The new APK cannot update it; use --reinstall to replace it (settings, history and login on the phone are lost)"
	fi
}

installed_version() {
	"$(adb_bin)" shell dumpsys package "$1" 2>/dev/null | tr -d '\r' \
		| sed -n 's/.*versionName=\(.*\)/\1/p' | head -n 1 || true
}

# 12+ only opens links in an app allowed in "Open by default". Over USB we can set that directly,
# so every link works straight after installing.
enable_app_links() {
	local package="$1" sdk out
	sdk="$("$(adb_bin)" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
	[[ "$sdk" =~ ^[0-9]+$ ]] && (( sdk >= 31 )) || return 0
	out="$("$(adb_bin)" shell pm set-app-links-allowed --user cur --package "$package" true 2>&1 \
		&& "$(adb_bin)" shell pm set-app-links-user-selection --user cur --package "$package" true all 2>&1)" || out="failed: $out"
	if [[ -n "$(tr -d '[:space:]' <<< "$out")" ]] && grep -qiE 'fail|error|exception|unknown' <<< "$out"; then
		warn "Couldn't set the app's links to open in it ($out). The app asks about it on its first start."
		return 0
	fi
	log "Links set to open in the app on the phone (Settings > Apps > Open by default: all ticked)"
}

install_apk() {
	device_ready || die "No phone found. Turn on USB debugging, connect the phone, accept the prompt on it and run again"
	local package="$APP_ID" output
	[[ "$APK_OUT" == *-debug.apk ]] && package="$APP_ID.debug"
	log "Installing $APK_OUT"
	output="$("$(adb_bin)" install -r "$APK_OUT" 2>&1 || true)"
	if grep -q '^Success' <<< "$output"; then
		log "Installed on the phone: $(installed_version "$package")"
		enable_app_links "$package"
		return
	fi
	printf '%s\n' "$output"
	if grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' <<< "$output"; then
		if [[ "$REINSTALL" -eq 1 ]]; then
			warn "Uninstalling the old TestTube (signed with a different key)"
			"$(adb_bin)" uninstall "$package" >/dev/null 2>&1 || true
			output="$("$(adb_bin)" install "$APK_OUT" 2>&1 || true)"
			grep -q '^Success' <<< "$output" || die "Installation failed: $output"
			log "Installed on the phone: $(installed_version "$package")"
			enable_app_links "$package"
			return
		fi
		die "The phone's TestTube was signed with a different key. Run ./BUILD.sh --reinstall to replace it (its settings, history and login are lost)"
	fi
	if grep -q 'INSTALL_FAILED_VERSION_DOWNGRADE' <<< "$output"; then
		die "The phone has a newer TestTube than this build. Build it again with a higher version"
	fi
	die "Installation failed"
}

# picks the APK in dist/ to install: the only one, or the one asked for (release first).
# --edition narrows it down.
find_dist_apk() {
	local version path name i answer menu
	local candidates=()
	version="$(read_version_name)"
	for path in "$DIST_DIR/testtube$version.apk" "$DIST_DIR/testtubeplus$version.apk" \
		"$DIST_DIR/testtube$version-debug.apk" "$DIST_DIR/testtubeplus$version-debug.apk"; do
		[[ -f "$path" ]] || continue
		name="$(basename "$path")"
		case "$EDITION" in
			standard) [[ "$name" == testtube[0-9]* ]] || continue ;;
			plus) [[ "$name" == testtubeplus[0-9]* ]] || continue ;;
		esac
		candidates+=("$path")
	done
	[[ ${#candidates[@]} -gt 0 ]] || die "No APK for version $version in $DIST_DIR. Build it first (option 1)"
	APK_OUT="${candidates[0]}"
	if [[ ${#candidates[@]} -gt 1 ]] && has_tty; then
		menu="Install which APK?"$'\n'
		for i in "${!candidates[@]}"; do
			menu+="  $((i + 1))) $(basename "${candidates[$i]}")"$'\n'
		done
		ask "${menu}Enter a number [1]: " answer
		answer="${answer:-1}"
		if ! [[ "$answer" =~ ^[0-9]+$ ]] || (( answer < 1 || answer > ${#candidates[@]} )); then
			die "'$answer' isn't one of the options above."
		fi
		APK_OUT="${candidates[$((answer - 1))]}"
	fi
	log "APK: $APK_OUT"
}

# ---- GitHub ------------------------------------------------------------------------------------

# owner/repo the updater downloads from
repo_slug() {
	grep -oE 'https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/download/' "$UPDATE_MANAGER" 2>/dev/null \
		| head -n 1 | sed -E 's#https://github\.com/([^/]+/[^/]+)/releases/download/#\1#'
}

ensure_gh() {
	if ! command -v gh >/dev/null 2>&1; then
		log "Installing the GitHub CLI (gh)"
		install_packages gh || die "Please install the GitHub CLI: https://cli.github.com/ (then run this again)"
		command -v gh >/dev/null 2>&1 || die "The GitHub CLI didn't install. Get it from https://cli.github.com/"
	fi
	if ! gh auth status >/dev/null 2>&1; then
		has_tty || die "The GitHub CLI isn't logged in. Run: gh auth login"
		log "Logging in to GitHub (once)"
		gh auth login < /dev/tty > /dev/tty 2>&1 || die "GitHub login failed"
	fi
	gh auth status >/dev/null 2>&1 || die "The GitHub CLI isn't logged in. Run: gh auth login"
}

# folders mirrored exactly on GitHub (old files deleted)
MIRRORED_DIRS=(app newpipe-extractor gradle)
# never deleted by --push-source, left as GitHub has them if we have no copy
KEEP_ON_GITHUB=(README.md screens testtube.png .github)

is_mirrored() {
	local dir
	for dir in "${MIRRORED_DIRS[@]}"; do
		[[ "$1" == "$dir/"* ]] && return 0
	done
	return 1
}

# files that must never leave this computer
SECRET_PATTERNS=('*.p12' '*.jks' '*.keystore' '*.pem' '*.key' 'signing.env' 'keystore.properties' 'local.properties')

# copies the source to $1 without git data, build output, logs or secrets
copy_source_tree() {
	local target="$1"
	mkdir -p "$target"
	local excludes=(--exclude=./.git --exclude=./.gradle --exclude=./.kotlin --exclude=./.idea
		--exclude=./build --exclude=./app/build --exclude=./dist
		--exclude=./newpipe-extractor/.gradle --exclude=./newpipe-extractor/build
		--exclude=./newpipe-extractor/extractor/build
		--exclude='./*.log' --exclude='*.iml' --exclude=.DS_Store)
	local pattern
	for pattern in "${SECRET_PATTERNS[@]}"; do
		excludes+=(--exclude="$pattern")
	done
	(cd "$ROOT" && tar -cf - "${excludes[@]}" .) | (cd "$target" && tar -xf -)
	# checked, not trusted: nothing secret in the copy
	local found=""
	for pattern in "${SECRET_PATTERNS[@]}"; do
		found+="$(find "$target" -name "$pattern" -not -path '*/.git/*' 2>/dev/null)"
	done
	[[ -z "$found" ]] || die "Secret files ended up in the copy, stopping: $found"
}

push_source() {
	ensure_gh
	command -v git >/dev/null 2>&1 || install_packages git || die "Please install git"
	local slug version
	slug="$(repo_slug)"
	[[ -n "$slug" ]] || die "Could not find the GitHub repo in $UPDATE_MANAGER"
	version="$(read_version_name)"
	local clone="$TMP_DIR/repo"
	log "Fetching $slug"
	gh auth setup-git >/dev/null 2>&1 || true
	git clone --quiet "https://github.com/$slug.git" "$clone" || die "Could not clone $slug"
	# one normal commit on top, no force push. Only code folders are mirrored exactly (a file deleted
	# here goes there too or Gradle would still compile it); everything else is added or updated, never
	# deleted. KEEP_ON_GITHUB stays as GitHub has it unless we have our own copy.
	local dir
	for dir in "${MIRRORED_DIRS[@]}"; do
		rm -rf "${clone:?}/$dir"
	done
	copy_source_tree "$clone"
	local path
	for path in "${KEEP_ON_GITHUB[@]}"; do
		if [[ ! -e "$ROOT/$path" ]] && (cd "$clone" && git cat-file -e "HEAD:$path" 2>/dev/null); then
			rm -rf "${clone:?}/$path"
			(cd "$clone" && git checkout --quiet HEAD -- "$path")
		fi
	done
	(cd "$clone" && git add -A)
	# checked, not trusted: nothing outside the code folders gets deleted
	local deleted bad=""
	deleted="$(cd "$clone" && git diff --cached --name-only --diff-filter=D)"
	while IFS= read -r path; do
		[[ -n "$path" ]] || continue
		is_mirrored "$path" || bad+="$path"$'\n'
	done <<< "$deleted"
	[[ -z "$bad" ]] || die "Stopped, nothing was pushed: this would delete or change on GitHub:"$'\n'"$bad"
	if (cd "$clone" && git diff --cached --quiet); then
		log "GitHub already has exactly this source; nothing to push"
		return
	fi
	log "Changes compared with GitHub:"
	(cd "$clone" && git diff --cached --stat | tail -n 25)
	if [[ -n "$deleted" ]]; then
		log "Deleted on GitHub because they're gone from the code here:"
		printf '    %s\n' "${deleted//$'\n'/$'\n'    }"
	fi
	local kept
	kept="$(cd "$clone" && git ls-files | while IFS= read -r f; do if [[ ! -e "$ROOT/$f" ]]; then printf '%s\n' "$f"; fi; done)"
	if [[ -n "$kept" ]]; then
		log "Kept as they are, only on GitHub: $(printf '%s' "$kept" | awk -F/ '{print $1}' | sort -u | tr '\n' ' ')"
	fi
	local message="TestTube $version"
	if has_tty; then
		local answer
		ask "Commit message [$message]: " answer
		message="${answer:-$message}"
		confirm "Push this to $slug?" y || die "Nothing was pushed"
	fi
	local name email
	name="$(git config --global user.name 2>/dev/null || true)"
	email="$(git config --global user.email 2>/dev/null || true)"
	if [[ -z "$name" || -z "$email" ]]; then
		local login
		login="$(gh api user --jq .login 2>/dev/null || true)"
		login="${login:-testtube}"
		name="${name:-$login}"
		email="${email:-$login@users.noreply.github.com}"
	fi
	(cd "$clone" && git -c user.name="$name" -c user.email="$email" commit --quiet -m "$message")
	(cd "$clone" && git push --quiet origin HEAD) || die "GitHub refused the push (did something change there in the meantime?). Nothing was overwritten."
	log "Source pushed to https://github.com/$slug"
}

# publishes both editions as v<version> (both APKs + SHA256SUMS.txt), then checks like the updaters
# will: complete files, GitHub's SHA-256 matches, it's the latest release, and TestTube's APK is
# first (updaters before 3.0.4 take the first APK, whatever edition).
publish_release() {
	ensure_gh
	local slug version tag standard plus
	slug="$(repo_slug)"
	[[ -n "$slug" ]] || die "Could not find the GitHub repo in $UPDATE_MANAGER"
	version="$(read_version_name)"
	tag="${TESTTUBE_RELEASE_TAG:-v$version}"
	standard="$(apk_path_for "$version" standard)"
	plus="$(apk_path_for "$version" plus)"
	[[ -f "$standard" ]] || die "No TestTube APK for $version at $standard. Build both editions first (option 1, Both)"
	[[ -f "$plus" ]] || die "No TestTube+ APK for $version at $plus. Build both editions first (option 1, Both): TestTube+ users get their updates from the same release"
	ensure_build_tools

	# the updater only installs APKs with the installed app's key, so one signed with anything else
	# would be offered to everyone and refused by everyone
	[[ -f "$KEYSTORE" && -f "$SIGNING_ENV" ]] || die "No signing key in $SIGNING_DIR to check the APKs against"
	local expected apk signed built
	expected="$(release_digest || true)"
	[[ -n "$expected" ]] || die "Could not read your signing key's fingerprint"
	for apk in "$standard" "$plus"; do
		signed="$(apk_digest "$apk")"
		[[ -n "$signed" && "$signed" == "$expected" ]] || die "$(basename "$apk") isn't signed with your signing key ($KEYS_HOME). The app would refuse it as an update."
		if rotated; then
			check_rotated "$apk"
		fi
		built="$(apk_version_name "$apk")"
		[[ -z "$built" || "$built" == "v$version" ]] || die "$(basename "$apk") is version '$built', not v$version"
	done
	if rotated; then
		log "Both signed with the shared Android key, and TestTube's own key for Android 8"
	else
		log "Both signed with your key"
	fi

	local prerelease=0
	[[ "$tag" =~ (alpha|beta|rc) ]] && prerelease=1
	if [[ "$prerelease" -eq 0 ]]; then
		local latest
		latest="$(gh api "repos/$slug/releases/latest" --jq .tag_name 2>/dev/null || true)"
		if [[ -n "$latest" && "$latest" != "$tag" ]] && ! version_newer "${tag#v}" "${latest#v}"; then
			die "$tag isn't newer than $latest, the latest release on GitHub. Set a new version first (option 10)"
		fi
	fi

	local notes
	notes="$(changelog_notes "$version")"
	[[ -n "$(tr -d '[:space:]' <<< "$notes")" ]] || die "CHANGELOG.md has nothing under '## $version'. Write what changed there: it becomes the release notes and the update dialog in the app."
	local notes_file="$TMP_DIR/notes.md"
	printf '%s\n' "$notes" > "$notes_file"
	log "Release notes for $tag:"
	sed 's/^/    /' "$notes_file"

	local sums="$TMP_DIR/SHA256SUMS.txt"
	{
		printf '%s  %s\n' "$(sha256_of "$standard")" "$(basename "$standard")"
		printf '%s  %s\n' "$(sha256_of "$plus")" "$(basename "$plus")"
	} > "$sums"

	local exists=0
	gh release view "$tag" --repo "$slug" >/dev/null 2>&1 && exists=1
	if has_tty; then
		local what="Publish $tag (TestTube and TestTube+) to $slug"
		[[ "$exists" -eq 1 ]] && what="$tag already exists on $slug. Replace its APKs and notes"
		confirm "$what?" y || die "Nothing was published"
	fi
	if [[ "$exists" -eq 1 ]]; then
		upload_in_order "$tag" "$slug" "$standard" "$plus" "$sums"
		gh release edit "$tag" --repo "$slug" --notes-file "$notes_file" >/dev/null
		if [[ "$prerelease" -eq 0 ]]; then
			gh release edit "$tag" --repo "$slug" --latest >/dev/null 2>&1 || true
		fi
	else
		local flags=(--repo "$slug" --title "$tag" --notes-file "$notes_file")
		if [[ "$prerelease" -eq 1 ]]; then
			flags+=(--prerelease)
		else
			# explicit: /releases/latest is what the update check asks for
			flags+=(--latest)
		fi
		# TestTube's APK first, TestTube+'s in its own upload after: the order old updaters see
		gh release create "$tag" "$standard" "$sums" "${flags[@]}"
		gh release upload "$tag" "$plus" --repo "$slug"
	fi

	# a failed upload can leave a broken file behind and the app refuses a mismatched SHA-256. So
	# check GitHub's size and SHA-256 of both APKs and that TestTube's is first; re-upload (in order)
	# if anything's off.
	local attempt state
	for attempt in 1 2 3; do
		state="$(release_state "$tag" "$slug" "$standard" "$plus")"
		[[ "$state" == "ok" ]] && break
		if [[ "$attempt" -lt 3 ]]; then
			if [[ "$state" == "pending" ]]; then
				# complete, GitHub just hasn't shown the SHA-256 yet
				warn "Waiting for GitHub to report the APKs' SHA-256"
				sleep 10
			else
				warn "The release on GitHub isn't right yet ($state); uploading again"
				sleep 5
				upload_in_order "$tag" "$slug" "$standard" "$plus" "" >/dev/null 2>&1 || true
			fi
		fi
	done
	[[ "$state" == "ok" ]] || die "The release on GitHub isn't right: $state. Run ./BUILD.sh --upload-only to try again."
	for apk in "$standard" "$plus"; do
		log "On GitHub: $(basename "$apk"), $(size_of "$apk") bytes, SHA-256 $(sha256_of "$apk") (what the app checks)"
	done
	if [[ "$prerelease" -eq 0 ]]; then
		local latest_now
		latest_now="$(gh api "repos/$slug/releases/latest" --jq .tag_name 2>/dev/null || true)"
		[[ "$latest_now" == "$tag" ]] || die "$tag is published, but GitHub still shows ${latest_now:-nothing} as the latest release, so the app wouldn't offer it. Fix: gh release edit $tag --repo $slug --latest"
		log "$tag is the latest release, so both apps will offer it"
	else
		log "$tag is a pre-release: offered only to people with Pre-releases on"
	fi
	log "Published: https://github.com/$slug/releases/tag/$tag"
}

# uploads TestTube's APK (and SHA256SUMS.txt if given) before TestTube+'s; TestTube+'s gets deleted
# first so a replaced TestTube APK doesn't land after it
upload_in_order() {
	local tag="$1" slug="$2" standard="$3" plus="$4" sums="$5"
	gh release delete-asset "$tag" "$(basename "$plus")" --repo "$slug" --yes >/dev/null 2>&1 || true
	if [[ -n "$sums" ]]; then
		gh release upload "$tag" "$standard" "$sums" --repo "$slug" --clobber
	else
		gh release upload "$tag" "$standard" --repo "$slug" --clobber
	fi
	gh release upload "$tag" "$plus" --repo "$slug" --clobber
}

# "ok" when both APKs are complete with matching SHA-256 and TestTube's is first, "pending" when
# only GitHub's SHA-256 is missing, otherwise what's wrong
release_state() {
	local tag="$1" slug="$2" standard="$3" plus="$4"
	local json apk name size sha remote_size remote_digest first pending=0
	json="$(gh api "repos/$slug/releases/tags/$tag" 2>/dev/null || true)"
	[[ -n "$json" ]] || { printf 'release not found'; return 0; }
	for apk in "$standard" "$plus"; do
		name="$(basename "$apk")"
		size="$(size_of "$apk")"
		sha="$(sha256_of "$apk")"
		remote_size="$(gh api "repos/$slug/releases/tags/$tag" --jq ".assets[] | select(.name == \"$name\") | .size" 2>/dev/null || true)"
		remote_digest="$(gh api "repos/$slug/releases/tags/$tag" --jq ".assets[] | select(.name == \"$name\") | .digest // \"\"" 2>/dev/null || true)"
		if [[ "$remote_size" != "$size" ]]; then
			printf '%s is %s bytes there, %s here' "$name" "${remote_size:-missing}" "$size"
			return 0
		fi
		if [[ -z "$remote_digest" ]]; then
			pending=1
		elif [[ "$remote_digest" != "sha256:$sha" ]]; then
			printf '%s has SHA-256 %s there, %s here' "$name" "$remote_digest" "$sha"
			return 0
		fi
	done
	first="$(gh api "repos/$slug/releases/tags/$tag" --jq '[.assets[] | select(.name | endswith(".apk")) | .name] | first // ""' 2>/dev/null || true)"
	if [[ "$first" != "$(basename "$standard")" ]]; then
		printf '%s comes first instead of %s' "${first:-nothing}" "$(basename "$standard")"
		return 0
	fi
	if [[ "$pending" -eq 1 ]]; then
		printf 'pending'
	else
		printf 'ok'
	fi
}

# ---- Other actions -----------------------------------------------------------------------------

source_zip() {
	local version stage out
	version="$(read_version_name)"
	stage="$TMP_DIR/testtube"
	copy_source_tree "$stage"
	mkdir -p "$DIST_DIR"
	if ! command -v zip >/dev/null 2>&1; then
		install_packages zip >/dev/null 2>&1 || true
	fi
	if command -v zip >/dev/null 2>&1; then
		out="$DIST_DIR/testtube$version-source.zip"
		rm -f "$out"
		(cd "$TMP_DIR" && zip -qr -X "$out" testtube)
	else
		out="$DIST_DIR/testtube$version-source.tar.gz"
		(cd "$TMP_DIR" && tar -czf "$out" testtube)
	fi
	log "Source (no keys, logs or build output): $out"
	log "SHA-256: $(sha256_of "$out")"
}

run_tests() {
	# both editions: each compiles its own Edition and ExtraLinks
	run_gradle_tasks ":app:testStandardDebugUnitTest" ":app:testPlusDebugUnitTest"
	local edition reports tests failures errors skipped
	for edition in standard plus; do
		reports="$ROOT/app/build/test-results/test$(edition_task_name "$edition")DebugUnitTest"
		[[ -d "$reports" ]] || continue
		tests="$(cat "$reports"/*.xml 2>/dev/null | grep -o 'tests="[0-9]*"' | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}')"
		failures="$(cat "$reports"/*.xml 2>/dev/null | grep -o 'failures="[0-9]*"' | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}')"
		errors="$(cat "$reports"/*.xml 2>/dev/null | grep -o 'errors="[0-9]*"' | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}')"
		skipped="$(cat "$reports"/*.xml 2>/dev/null | grep -o 'skipped="[0-9]*"' | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}')"
		log "$(edition_label "$edition") tests: $tests run, $failures failed, $errors errors, $skipped skipped"
	done
	log "All tests passed"
}

clean_outputs() {
	log "Deleting build output"
	rm -rf "$DIST_DIR" "$ROOT/build" "$ROOT/app/build" "$ROOT/.gradle" "$ROOT/.kotlin" \
		"$ROOT/newpipe-extractor/.gradle" "$ROOT/newpipe-extractor/build" "$ROOT/newpipe-extractor/extractor/build"
	log "Done. The next build starts from scratch (the JDK and SDK stay in $TESTTUBE_HOME, the keys in $KEYS_HOME)."
}

# files removed in newer versions. Unzipping over an old copy leaves them, and Gradle would
# compile them.
remove_retired_files() {
	local main="app/src/main"
	local retired=(
		"$main/java/com/testtube/app/gallery"
		"$main/java/com/testtube/app/extension"
		"$main/java/com/testtube/app/cache"
		"$main/java/com/testtube/app/browser"
		"$main/java/com/testtube/app/util/WebResourceUtils.java"
		"$main/java/com/testtube/app/ui/widget/LoadingProgressBar.java"
		"$main/assets/bridge" "$main/assets/script" "$main/assets/style" "$main/assets/page"
		"$main/res/layout/activity_gallery.xml" "$main/res/layout/fragment_webview.xml"
		"$main/res/menu/gallery_context_menu.xml" "$main/res/drawable/ic_cancel.xml"
		"$main/res/font/jetbrains_mono_extrabold.ttf"
		"$main/res/drawable-nodpi/ic_testtube_logo.png"
		"$main/res/mipmap-mdpi" "$main/res/mipmap-hdpi" "$main/res/mipmap-xhdpi"
		"$main/res/mipmap-xxhdpi" "$main/res/mipmap-xxxhdpi"
		"app/src/plus/java/com/testtube/app/plus/LinkPlayerActivity.java"
		"app/src/plus/java/com/testtube/app/plus/LinkPlayerService.java"
		"app/src/plus/res/layout/activity_link_player.xml"
		"app/src/test/java/com/testtube/app/browser"
	)
	local path
	for path in "${retired[@]}"; do
		if [[ -e "$ROOT/$path" ]]; then
			rm -rf "${ROOT:?}/$path"
			log "Removed leftover from an older version: $path"
		fi
	done
}

build_apk() {
	setup_toolchain
	if [[ -n "$IMPORT_KEY" ]]; then
		import_signing_key "$IMPORT_KEY"
	fi
	if [[ "$VARIANT" == "release" ]]; then
		ensure_signing_key
		if [[ "$INSTALL" -eq 1 ]]; then
			match_installed_key
		fi
	fi
	remove_retired_files
	pin_gradle_checksum
	run_gradle_build
	collect_apk
	if [[ "$INSTALL" -eq 1 ]]; then
		choose_install_apk
		install_apk
	fi
}

main() {
	TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/testtube-build.XXXXXX")"
	case "$MODE" in
		build)
			# recording dependency hashes (option 8) isn't a release, don't ask
			if [[ "$VARIANT" == "debug" ]]; then
				pick_version debug
				pick_edition
			elif [[ "$WRITE_CHECKSUMS" -eq 0 || -n "$VERSION_OVERRIDE" ]]; then
				pick_version build
				pick_edition
			fi
			build_apk
			;;
		publish)
			VARIANT="release"
			EDITION="both"
			pick_version publish
			# before the build, not after: publishing stops without release notes
			local notes
			notes="$(changelog_notes "$(read_version_name)")"
			[[ -n "$(tr -d '[:space:]' <<< "$notes")" ]] || die "CHANGELOG.md has nothing under '## $(read_version_name)'. Write what changed there and run this again: it becomes the release notes and the update dialog in the app."
			build_apk
			if has_tty && confirm "Also put the current source on GitHub first?" n; then
				push_source
			fi
			publish_release
			;;
		upload-only)
			# no build needed, but check the APK's signature and version
			setup_toolchain
			ensure_build_tools
			VARIANT="release"
			EDITION="both"
			publish_release
			;;
		install-only)
			setup_toolchain
			find_dist_apk
			install_apk
			;;
		test)
			setup_toolchain
			remove_retired_files
			pin_gradle_checksum
			run_tests
			;;
		set-version)
			choose_version set
			;;
		push-source)
			pick_version source
			push_source
			;;
		source-zip)
			source_zip
			;;
		key-info)
			setup_toolchain
			show_key_info
			;;
		use-shared-key)
			switch_to_shared_key
			;;
		backup-key)
			ensure_system_tools
			backup_signing_key
			;;
		create-keys)
			setup_toolchain
			ensure_signing_key
			log "Kept in $SIGNING_DIR. Back them up with option 15."
			;;
		import-key)
			setup_toolchain
			if [[ -z "$IMPORT_KEY" ]]; then
				ask "Folder that holds testtube-release.p12 and signing.env: " IMPORT_KEY
			fi
			[[ -n "$IMPORT_KEY" ]] || die "No folder given"
			import_signing_key "$IMPORT_KEY"
			load_signing_key
			;;
		clean)
			clean_outputs
			;;
		*)
			die "Unknown action $MODE"
			;;
	esac
}

main
