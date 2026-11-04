#!/usr/bin/env bash
# BUILD.sh -- everything around building and releasing TestTube, from one place.
#
# Run it without arguments for a menu, or pick an action directly:
#
#   ./BUILD.sh --release           build the signed release APK (dist/testtube<version>.apk)
#   ./BUILD.sh --publish           build it and publish it to GitHub Releases (APK + SHA256SUMS.txt),
#                                  checked so the app's own updater will accept it
#   ./BUILD.sh --upload-only       publish the APK that is already in dist/, without building
#   ./BUILD.sh --debug             build a debug APK (Android's own debug key, installs next to
#                                  the release app as "com.testtube.app.debug")
#   ./BUILD.sh --install-only      install the APK in dist/ on the phone connected over USB
#   ./BUILD.sh --test              run the unit tests
#   ./BUILD.sh --set-version       set the version name and code for the next release
#   ./BUILD.sh --push-source       put the current source on GitHub as a normal commit (no release)
#   ./BUILD.sh --source-zip        pack the source into dist/, without keys, logs or build output
#   ./BUILD.sh --key-info          show the signing key's fingerprint and where it is kept
#   ./BUILD.sh --backup-key DIR    copy the signing key to DIR (a USB stick, for example)
#   ./BUILD.sh --import-key DIR    use the signing key in DIR from now on
#   ./BUILD.sh --write-checksums   record the SHA-256 of every library; Gradle refuses any that change
#   ./BUILD.sh --fix-permissions   give the project folder back to you after a run with sudo
#   ./BUILD.sh --clean             delete all build output
#
# Options that go with the build actions:
#   --install       after building, install on the connected phone; picks the signing key that
#                   matches the TestTube already on it, if that key is on this computer
#   --reinstall     like --install, but uninstalls a TestTube signed with an unknown key first
#                   (its settings, history and login are lost)
#   --clean         delete earlier build output before building
#   --verbose       more detailed Gradle output in the log
#
# Logs (written live: tail -f build.log): build.log for this run, build.previous.log for the one
# before, build-errors.log with only the errors when a build fails.
#
# Environment:
#   TESTTUBE_HOME          where the JDK, Android SDK and signing key are kept (default ~/.testtube)
#   TESTTUBE_RELEASE_TAG   tag to publish as, instead of v<version from app/build.gradle.kts>
#
# Publishing needs the GitHub CLI ("gh"); it is installed and logged in on first use. The repo is
# the one the app's updater downloads from (DOWNLOAD_PREFIX in UpdateManager.java), so a fork that
# changed that publishes to its own repo.

set -Eeuo pipefail
umask 022

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TESTTUBE_HOME_OVERRIDE="${TESTTUBE_HOME:-}"
TESTTUBE_HOME="${TESTTUBE_HOME:-$HOME/.testtube}"
JDK_MAJOR=21
JDK_DIR="$TESTTUBE_HOME/jdk-$JDK_MAJOR"
SDK_DIR="$TESTTUBE_HOME/android-sdk"
SIGNING_DIR="$TESTTUBE_HOME/signing"
SIGNING_ENV="$SIGNING_DIR/signing.env"
KEYSTORE="$SIGNING_DIR/testtube-release.p12"
DIST_DIR="$ROOT/dist"
LOG_FILE="$ROOT/build.log"
GRADLE_FILE="$ROOT/app/build.gradle.kts"
CHANGELOG="$ROOT/CHANGELOG.md"
UPDATE_MANAGER="$ROOT/app/src/main/java/com/testtube/app/update/UpdateManager.java"
APP_ID="com.testtube.app"
TMP_DIR=""

log() { printf '\033[1;32m[TestTube]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[TestTube] WARNING:\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31m[TestTube] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# Everything printed goes through tee into build.log, and that pipe can hold a question back so
# the terminal shows nothing while the script waits for an answer. Questions go straight to the
# terminal instead. Without one (CI, piped input) the answer comes from stdin.
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

# Yes/no question; the default (y or n) is what Enter picks.
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
	# Give the log writer a moment to flush the final lines.
	if [[ -s "$LOG_FILE" ]]; then
		sleep 1
	fi
	return 0
}
on_error() {
	local code=$?
	printf '\033[1;31m[TestTube] Failed (exit %s) at line %s. Full log: %s\033[0m\n' "$code" "$1" "$LOG_FILE" >&2
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

set_mode() {
	[[ -z "$MODE" || "$MODE" == "$1" ]] || die "Pick one action at a time ($MODE and $1 were both given)"
	MODE="$1"
}

show_menu() {
	local choice
	ask "$(cat <<'EOF'
What would you like to do?
  Build
   1) Build the signed release APK
   2) Build the signed release APK and install it on the connected phone
   3) Build a debug APK
   4) Install the APK in dist/ on the connected phone
  Release
   5) Build the release APK and publish it to GitHub Releases
   6) Publish the APK already in dist/ to GitHub Releases
   7) Set the version for the next release
   8) Put the current source on GitHub (no release)
   9) Pack the source into a zip (no keys or build output)
  Check
  10) Run the unit tests
  11) Record library checksums (Gradle then refuses any library that changes)
  Signing key
  12) Show the signing key's fingerprint
  13) Back up the signing key to another folder
  14) Use a signing key from another folder
  Maintenance
  15) Delete all build output
  16) Give the project folder back to you (after a run with sudo)
Enter a number [1-16]:
EOF
) " choice
	apply_choice "$choice"
	MENU_CHOICE="$choice"
}

apply_choice() {
	case "$1" in
		1) set_mode build ;;
		2) set_mode build; INSTALL=1 ;;
		3) set_mode build; VARIANT="debug" ;;
		4) set_mode install-only ;;
		5) set_mode publish ;;
		6) set_mode upload-only ;;
		7) set_mode set-version ;;
		8) set_mode push-source ;;
		9) set_mode source-zip ;;
		10) set_mode test ;;
		11) set_mode build; WRITE_CHECKSUMS=1 ;;
		12) set_mode key-info ;;
		13) set_mode backup-key ;;
		14) set_mode import-key ;;
		15) set_mode clean ;;
		16) set_mode fix-permissions ;;
		*) die "'$1' isn't one of the options" ;;
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
		--source-zip) set_mode source-zip ;;
		--key-info) set_mode key-info ;;
		--backup-key) set_mode backup-key; EXPECT="BACKUP_KEY" ;;
		--backup-key=*) set_mode backup-key; BACKUP_KEY="${arg#--backup-key=}" ;;
		--import-key) EXPECT="IMPORT_KEY" ;;
		--import-key=*) IMPORT_KEY="${arg#--import-key=}" ;;
		--write-checksums) WRITE_CHECKSUMS=1 ;;
		--fix-permissions) set_mode fix-permissions ;;
		--install) INSTALL=1 ;;
		--reinstall) INSTALL=1; REINSTALL=1 ;;
		--clean) CLEAN=1 ;;
		--verbose) VERBOSE=1 ;;
		--menu-choice=*) MENU_CHOICE="${arg#--menu-choice=}"; apply_choice "$MENU_CHOICE" ;;
		--help|-h) usage; exit 0 ;;
		*) usage; die "Unknown option: $arg" ;;
	esac
done
[[ -z "$EXPECT" ]] || die "${arg} needs a folder after it"
# --import-key on its own means "switch to that key"; with a build it's used for that build.
if [[ -n "$IMPORT_KEY" && -z "$MODE" ]]; then
	MODE="import-key"
fi
# --clean on its own deletes the build output; with a build it cleans first.
if [[ "$CLEAN" -eq 1 && -z "$MODE" ]]; then
	MODE="clean"
fi
if [[ -z "$MODE" ]]; then
	if [[ $# -eq 0 ]] && has_tty; then
		show_menu
	else
		# Without a terminal (CI) or with only options like --install: the release build, as before
		MODE="build"
	fi
fi

if [[ "$(id -u)" -eq 0 && -n "${SUDO_USER:-}" && "$SUDO_USER" != "root" && "$MODE" != "fix-permissions" ]]; then
	log "Started with sudo; continuing as $SUDO_USER so every file stays yours"
	# The menu has been answered already; pass the answer on instead of asking again
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
	log "Done. You can now move, change and delete the project folder normally."
}

if [[ "$MODE" == "fix-permissions" ]]; then
	fix_permissions
	exit 0
fi

[[ -f "$LOG_FILE" ]] && mv -f "$LOG_FILE" "$ROOT/build.previous.log"
rm -f "$ROOT/build-errors.log"
: > "$LOG_FILE" 2>/dev/null || die "Cannot write $LOG_FILE. Is the folder yours? Try: ./BUILD.sh --fix-permissions"
# From here on, output goes to the terminal and to build.log (timestamped, without colours).
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

# Installs packages with whatever package manager the system has.
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

# JDK and Android SDK, downloaded into TESTTUBE_HOME the first time.
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

signing_password_from() {
	local line value
	while IFS= read -r line; do
		if [[ "$line" =~ ^TESTTUBE_KEYSTORE_PASSWORD=([0-9A-Za-z]+)$ ]]; then
			value="${BASH_REMATCH[1]}"
		fi
	done
	[[ -n "${value:-}" ]] || return 1
	printf '%s' "$value"
}

write_signing_env() {
	(umask 077; {
		printf 'TESTTUBE_KEYSTORE_PASSWORD=%s\n' "$1"
		printf 'TESTTUBE_KEY_ALIAS=testtube\n'
	} > "$SIGNING_ENV")
	chmod 600 "$SIGNING_ENV"
}

# The key's SHA-256 fingerprint as keytool prints it (AA:BB:...), or nothing.
key_fingerprint() {
	TESTTUBE_FP_PASSWORD="$2" "$JDK_DIR/bin/keytool" -list -v -keystore "$1" -storetype PKCS12 \
		-storepass:env TESTTUBE_FP_PASSWORD -alias testtube 2>/dev/null \
		| awk -F': ' '/SHA256:/ {print $2; exit}' || true
}

load_signing_key() {
	local password
	password="$(signing_password_from < "$SIGNING_ENV")" || die "$SIGNING_ENV is damaged"
	export TESTTUBE_KEYSTORE_FILE="$KEYSTORE"
	export TESTTUBE_KEYSTORE_PASSWORD="$password"
	export TESTTUBE_KEY_ALIAS="testtube"
	export TESTTUBE_KEY_PASSWORD="$password"
	local fingerprint
	fingerprint="$(key_fingerprint "$KEYSTORE" "$password")"
	[[ -n "$fingerprint" ]] || die "The signing key in $SIGNING_DIR cannot be opened with its password"
	log "Signing key fingerprint (SHA-256): $fingerprint"
}

import_signing_key() {
	local source="${1%/}"
	protected_exists "$source/testtube-release.p12" || die "No testtube-release.p12 in $source"
	protected_exists "$source/signing.env" || die "No signing.env in $source"
	local password
	password="$(read_protected "$source/signing.env" | signing_password_from)" || die "Could not read the password from $source/signing.env"
	mkdir -p "$SIGNING_DIR"
	chmod 700 "$SIGNING_DIR"
	if [[ -e "$KEYSTORE" || -e "$SIGNING_ENV" ]]; then
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
	log "Imported the signing key from $source"
}

ensure_signing_key() {
	mkdir -p "$SIGNING_DIR"
	chmod 700 "$SIGNING_DIR"
	if [[ -f "$KEYSTORE" && -f "$SIGNING_ENV" ]]; then
		log "Using existing signing key $KEYSTORE"
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
			warn "Back up $SIGNING_DIR (menu option 13). Without it future builds cannot update the installed app."
		fi
	fi
	load_signing_key
}

show_key_info() {
	[[ -f "$KEYSTORE" && -f "$SIGNING_ENV" ]] || die "No signing key yet in $SIGNING_DIR. The first release build creates one."
	load_signing_key
	log "Kept in: $SIGNING_DIR"
	log "Every update has to be signed with this key; the app refuses anything else."
}

backup_signing_key() {
	[[ -f "$KEYSTORE" && -f "$SIGNING_ENV" ]] || die "No signing key yet in $SIGNING_DIR. The first release build creates one."
	local target="$BACKUP_KEY"
	if [[ -z "$target" ]]; then
		ask "Folder to copy the signing key to (a USB stick, for example): " target
	fi
	[[ -n "$target" ]] || die "No folder given"
	target="${target%/}"
	case "$target" in
		"$ROOT"|"$ROOT"/*) die "Not inside the project folder: it gets zipped and pushed, and the key would go with it" ;;
	esac
	mkdir -p "$target"
	if [[ -e "$target/testtube-release.p12" || -e "$target/signing.env" ]]; then
		confirm "$target already holds a TestTube key. Replace it?" n || die "Nothing copied"
	fi
	(umask 077
		cp "$KEYSTORE" "$target/testtube-release.p12"
		cp "$SIGNING_ENV" "$target/signing.env")
	chmod 600 "$target/testtube-release.p12" "$target/signing.env" 2>/dev/null || true
	[[ "$(sha256_of "$KEYSTORE")" == "$(sha256_of "$target/testtube-release.p12")" ]] || die "The copy in $target doesn't match. Check the drive and try again."
	log "Signing key copied to $target and checked"
	log "Keep it somewhere safe and private: anyone with these two files can sign TestTube updates"
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

# Copies the lines that explain a failed Gradle run into build-errors.log and shows them.
extract_errors() {
	local src="$1" out="$ROOT/build-errors.log"
	{
		echo "Errors from the run of $(date '+%Y-%m-%d %H:%M:%S')"
		echo "(the complete output is in $LOG_FILE)"
		echo
		grep -nE '(^|[[:space:]])error:|^e: |cannot find symbol|incompatible types|FAILED$|\* What went wrong|Execution failed|Caused by:|AAPT|BUILD FAILED|expected:<|AssertionError' -A4 "$src" 2>/dev/null | head -n 250 || true
	} > "$out"
	printf '\033[1;31m[TestTube] ---- What went wrong ----\033[0m\n'
	head -n 70 "$out"
	printf '\033[1;31m[TestTube] -------------------------\033[0m\n'
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

# Runs Gradle tasks, retrying only failures caused by the network.
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
		# A compile error or a failing test fails the same way every time.
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
	local task=":app:assembleRelease"
	[[ "$VARIANT" == "debug" ]] && task=":app:assembleDebug"
	run_gradle_tasks "$task"
}

# ---- Versions ----------------------------------------------------------------------------------

# The version name without its "v", from app/build.gradle.kts (3.0.2).
read_version_name() {
	local name
	name="$(grep -E '^val appVersionName[[:space:]]*=' "$GRADLE_FILE" | head -n 1 | sed -E 's/.*"(.*)".*/\1/')"
	printf '%s' "${name#v}"
}

read_version_code() {
	grep -E '^val appVersionCode[[:space:]]*=' "$GRADLE_FILE" | head -n 1 | sed -E 's/[^0-9]*([0-9]+).*/\1/'
}

# 3.0.2 -> 30002, the scheme every release so far used. Empty for names it can't read.
version_code_for() {
	if [[ "$1" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
		local major=$((10#${BASH_REMATCH[1]})) minor=$((10#${BASH_REMATCH[2]})) patch=$((10#${BASH_REMATCH[3]}))
		(( minor < 100 && patch < 100 )) || return 0
		printf '%s' $(( major * 10000 + minor * 100 + patch ))
	fi
}

# Whether version a is newer than b, by number (3.0.10 is newer than 3.0.9).
version_newer() {
	[[ "$1" != "$2" && "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n 1)" == "$1" ]]
}

set_version() {
	local current current_code suggested name code published=""
	current="$(read_version_name)"
	current_code="$(read_version_code)"
	[[ -n "$current" && -n "$current_code" ]] || die "Could not read the version from $GRADLE_FILE"
	if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
		local slug
		slug="$(repo_slug || true)"
		[[ -n "$slug" ]] && published="$(gh api "repos/$slug/releases/latest" --jq .tag_name 2>/dev/null || true)"
		published="${published#v}"
		[[ -n "$published" ]] && log "Latest release on GitHub: $published"
	fi
	local base="$current"
	if [[ -n "$published" ]] && version_newer "$published" "$base"; then
		base="$published"
	fi
	if [[ "$base" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
		suggested="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.$((10#${BASH_REMATCH[3]} + 1))"
	else
		suggested="$base"
	fi
	log "Current version: $current (code $current_code)"
	ask "New version [$suggested]: " name
	name="${name:-$suggested}"
	name="${name#v}"
	[[ "$name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "A version looks like 3.0.3: three numbers with dots"
	code="$(version_code_for "$name")"
	[[ -n "$code" ]] || die "The middle and last number have to stay below 100"
	if (( code <= current_code )); then
		die "Version $name (code $code) isn't newer than $current (code $current_code). Android won't install it as an update."
	fi
	if [[ -n "$published" ]] && ! version_newer "$name" "$published"; then
		die "Version $name isn't newer than $published, which is already on GitHub. The app wouldn't offer it."
	fi
	sed -i.bak -E "s/^val appVersionName[[:space:]]*=.*/val appVersionName = \"v$name\"/; s/^val appVersionCode[[:space:]]*=.*/val appVersionCode = $code/" "$GRADLE_FILE"
	rm -f "$GRADLE_FILE.bak"
	[[ "$(read_version_name)" == "$name" && "$(read_version_code)" == "$code" ]] || die "Could not write the version into $GRADLE_FILE"
	log "Version set: $current ($current_code) -> $name ($code)"
	if [[ -f "$CHANGELOG" ]] && ! grep -qxF "## $name" "$CHANGELOG"; then
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

# The notes under "## <version>" in CHANGELOG.md, up to the next version.
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

apk_path_for() {
	local version="$1"
	if [[ "$VARIANT" == "debug" ]]; then
		printf '%s' "$DIST_DIR/testtube$version-debug.apk"
	else
		printf '%s' "$DIST_DIR/testtube$version.apk"
	fi
}

# The SHA-256 of the APK's signing certificate, lower case without colons.
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

collect_apk() {
	local version
	version="$(read_version_name)"
	[[ -n "$version" ]] || die "Could not read appVersionName from $GRADLE_FILE"
	local out
	out="$(apk_path_for "$version")"
	APK_OUT="$out"
	[[ -f "$out" ]] || die "APK not found at $out"
	ensure_build_tools
	JAVA_HOME="$JDK_DIR" "$(apksigner_bin)" verify "$out"
	log "Signature verified"
	local built
	built="$(apk_version_name "$out")"
	if [[ -n "$built" ]]; then
		[[ "$built" == "v$version" ]] || die "The APK reports version '$built' but $GRADLE_FILE says v$version"
		log "APK version: $built"
	fi
	if [[ "$VARIANT" == "debug" ]]; then
		find "$DIST_DIR" -maxdepth 1 -name 'testtube*-debug.apk' ! -name "$(basename "$out")" -delete 2>/dev/null || true
	else
		find "$DIST_DIR" -maxdepth 1 -name 'testtube*.apk' ! -name '*-debug.apk' ! -name "$(basename "$out")" -delete 2>/dev/null || true
	fi
	log "SHA-256: $(sha256_of "$out")"
	log "Done. Your APK: $out"
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
	for base in "$SIGNING_DIR" "/root/.testtube/signing"; do
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

install_apk() {
	device_ready || die "No phone found. Turn on USB debugging, connect the phone, accept the prompt on it and run again"
	local package="$APP_ID" output
	[[ "$APK_OUT" == *-debug.apk ]] && package="$APP_ID.debug"
	log "Installing $APK_OUT"
	output="$("$(adb_bin)" install -r "$APK_OUT" 2>&1 || true)"
	if grep -q '^Success' <<< "$output"; then
		log "Installed on the phone: $(installed_version "$package")"
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
			return
		fi
		die "The phone's TestTube was signed with a different key. Run ./BUILD.sh --reinstall to replace it (its settings, history and login are lost)"
	fi
	if grep -q 'INSTALL_FAILED_VERSION_DOWNGRADE' <<< "$output"; then
		die "The phone has a newer TestTube than this build. Set a higher version first (menu option 7)"
	fi
	die "Installation failed"
}

# Picks the APK in dist/: the release one unless only a debug one is there.
find_dist_apk() {
	local version release debug
	version="$(read_version_name)"
	release="$DIST_DIR/testtube$version.apk"
	debug="$DIST_DIR/testtube$version-debug.apk"
	if [[ -f "$release" ]]; then
		APK_OUT="$release"
	elif [[ -f "$debug" ]]; then
		APK_OUT="$debug"
	else
		die "No APK for version $version in $DIST_DIR. Build it first (menu option 1)"
	fi
	log "APK: $APK_OUT"
}

# ---- GitHub ------------------------------------------------------------------------------------

# owner/repo the app's updater downloads from.
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

# Files that must never leave this computer.
SECRET_PATTERNS=('*.p12' '*.jks' '*.keystore' '*.pem' '*.key' 'signing.env' 'keystore.properties' 'local.properties')

# Copies the source tree to $1, leaving out git data, build output, logs and anything secret.
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
	# Checked, not trusted: nothing secret may be in the copy.
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
	# Everything in the repo is replaced by the current tree, as one normal commit on top of the
	# history: files deleted here are deleted there, nothing is force-pushed.
	find "$clone" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
	copy_source_tree "$clone"
	(cd "$clone" && git add -A)
	if (cd "$clone" && git diff --cached --quiet); then
		log "GitHub already has exactly this source; nothing to push"
		return
	fi
	log "Changes compared with GitHub:"
	(cd "$clone" && git diff --cached --stat | tail -n 25)
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
		login="$(gh api user --jq .login 2>/dev/null || echo testtube)"
		name="${name:-$login}"
		email="${email:-$login@users.noreply.github.com}"
	fi
	(cd "$clone" && git -c user.name="$name" -c user.email="$email" commit --quiet -m "$message")
	(cd "$clone" && git push --quiet origin HEAD) || die "GitHub refused the push (did something change there in the meantime?). Nothing was overwritten."
	log "Source pushed to https://github.com/$slug"
}

# Publishes $APK_OUT as release v<version>: the APK and SHA256SUMS.txt, then checks it the way the
# app's updater will: the file is all there, GitHub's SHA-256 matches, it is the latest release.
publish_release() {
	ensure_gh
	local slug version tag apk_name sha size
	slug="$(repo_slug)"
	[[ -n "$slug" ]] || die "Could not find the GitHub repo in $UPDATE_MANAGER"
	[[ "$APK_OUT" != *-debug.apk ]] || die "That's a debug APK; only a release APK can be published"
	[[ -f "$APK_OUT" ]] || die "APK not found at $APK_OUT"
	version="$(read_version_name)"
	tag="${TESTTUBE_RELEASE_TAG:-v$version}"
	apk_name="$(basename "$APK_OUT")"
	ensure_build_tools

	# The updater installs only APKs signed with the same key as the installed app, so a release
	# signed with any other key would be offered to everyone and then refused by everyone.
	[[ -f "$KEYSTORE" && -f "$SIGNING_ENV" ]] || die "No signing key in $SIGNING_DIR to check the APK against"
	local signed expected
	signed="$(apk_digest "$APK_OUT")"
	expected="$(key_digest "$SIGNING_DIR" || true)"
	[[ -n "$signed" && "$signed" == "$expected" ]] || die "$apk_name isn't signed with your signing key ($SIGNING_DIR). The app would refuse it as an update."
	log "Signed with your key"
	local built
	built="$(apk_version_name "$APK_OUT")"
	[[ -z "$built" || "$built" == "v$version" ]] || die "$apk_name is version '$built', not v$version"

	local prerelease=0
	[[ "$tag" =~ (alpha|beta|rc) ]] && prerelease=1
	if [[ "$prerelease" -eq 0 ]]; then
		local latest
		latest="$(gh api "repos/$slug/releases/latest" --jq .tag_name 2>/dev/null || true)"
		if [[ -n "$latest" && "$latest" != "$tag" ]] && ! version_newer "${tag#v}" "${latest#v}"; then
			die "$tag isn't newer than $latest, the latest release on GitHub. Set a new version first (menu option 7)"
		fi
	fi

	local notes
	notes="$(changelog_notes "$version")"
	[[ -n "$(tr -d '[:space:]' <<< "$notes")" ]] || die "CHANGELOG.md has nothing under '## $version'. Write what changed there: it becomes the release notes and the update dialog in the app."
	local notes_file="$TMP_DIR/notes.md"
	printf '%s\n' "$notes" > "$notes_file"
	log "Release notes for $tag:"
	sed 's/^/    /' "$notes_file"

	sha="$(sha256_of "$APK_OUT")"
	size="$(size_of "$APK_OUT")"
	local sums="$TMP_DIR/SHA256SUMS.txt"
	printf '%s  %s\n' "$sha" "$apk_name" > "$sums"

	local exists=0
	gh release view "$tag" --repo "$slug" >/dev/null 2>&1 && exists=1
	if has_tty; then
		local what="Publish $tag to $slug"
		[[ "$exists" -eq 1 ]] && what="$tag already exists on $slug. Replace its APK and notes"
		confirm "$what?" y || die "Nothing was published"
	fi
	if [[ "$exists" -eq 1 ]]; then
		gh release upload "$tag" "$APK_OUT" "$sums" --repo "$slug" --clobber
		gh release edit "$tag" --repo "$slug" --notes-file "$notes_file" >/dev/null
		[[ "$prerelease" -eq 0 ]] && gh release edit "$tag" --repo "$slug" --latest >/dev/null 2>&1 || true
	else
		local flags=(--repo "$slug" --title "$tag" --notes-file "$notes_file")
		if [[ "$prerelease" -eq 1 ]]; then
			flags+=(--prerelease)
		else
			# Explicit: /releases/latest is what the app's update check asks for
			flags+=(--latest)
		fi
		gh release create "$tag" "$APK_OUT" "$sums" "${flags[@]}"
	fi

	# A failed upload can leave the name behind with a broken file, and the app refuses an update
	# whose SHA-256 doesn't match. So check GitHub's own size and SHA-256, and upload again if off.
	local attempt remote_size remote_digest
	for attempt in 1 2 3; do
		remote_size="$(gh api "repos/$slug/releases/tags/$tag" --jq ".assets[] | select(.name == \"$apk_name\") | .size" 2>/dev/null || true)"
		remote_digest="$(gh api "repos/$slug/releases/tags/$tag" --jq ".assets[] | select(.name == \"$apk_name\") | .digest // \"\"" 2>/dev/null || true)"
		if [[ "$remote_size" == "$size" && "$remote_digest" == "sha256:$sha" ]]; then
			break
		fi
		if [[ "$attempt" -lt 3 ]]; then
			if [[ "$remote_size" == "$size" && -z "$remote_digest" ]]; then
				# Complete, but GitHub hasn't shown its SHA-256 yet
				warn "Waiting for GitHub to report the APK's SHA-256"
				sleep 10
			else
				warn "The APK on GitHub doesn't match (size ${remote_size:-none}, ${remote_digest:-no SHA-256}); uploading again"
				sleep 5
				gh release upload "$tag" "$APK_OUT" --repo "$slug" --clobber >/dev/null 2>&1 || true
			fi
		fi
	done
	[[ "$remote_size" == "$size" ]] || die "The APK on GitHub is ${remote_size:-missing} bytes, the file here is $size. Run ./BUILD.sh --upload-only to try again."
	[[ "$remote_digest" == "sha256:$sha" ]] || die "GitHub reports SHA-256 '${remote_digest:-none}' for the APK, expected $sha. The app refuses updates without a matching SHA-256."
	log "On GitHub: $apk_name, $size bytes, SHA-256 $sha (what the app checks)"
	if [[ "$prerelease" -eq 0 ]]; then
		local latest_now
		latest_now="$(gh api "repos/$slug/releases/latest" --jq .tag_name 2>/dev/null || true)"
		[[ "$latest_now" == "$tag" ]] || die "$tag is published, but GitHub still shows ${latest_now:-nothing} as the latest release, so the app wouldn't offer it. Fix: gh release edit $tag --repo $slug --latest"
		log "$tag is the latest release, so the app will offer it"
	else
		log "$tag is a pre-release: offered only to people with Pre-releases on"
	fi
	log "Published: https://github.com/$slug/releases/tag/$tag"
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
	run_gradle_tasks ":app:testDebugUnitTest"
	local reports="$ROOT/app/build/test-results/testDebugUnitTest"
	if [[ -d "$reports" ]]; then
		local tests failures errors skipped
		tests="$(cat "$reports"/*.xml 2>/dev/null | grep -o 'tests="[0-9]*"' | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}')"
		failures="$(cat "$reports"/*.xml 2>/dev/null | grep -o 'failures="[0-9]*"' | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}')"
		errors="$(cat "$reports"/*.xml 2>/dev/null | grep -o 'errors="[0-9]*"' | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}')"
		skipped="$(cat "$reports"/*.xml 2>/dev/null | grep -o 'skipped="[0-9]*"' | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}')"
		log "Tests: $tests run, $failures failed, $errors errors, $skipped skipped"
	fi
	log "All tests passed"
}

clean_outputs() {
	log "Deleting build output"
	rm -rf "$DIST_DIR" "$ROOT/build" "$ROOT/app/build" "$ROOT/.gradle" "$ROOT/.kotlin" \
		"$ROOT/newpipe-extractor/.gradle" "$ROOT/newpipe-extractor/build" "$ROOT/newpipe-extractor/extractor/build"
	log "Done. The next build starts from scratch (the JDK, SDK and signing key stay in $TESTTUBE_HOME)."
}

# Files removed in newer versions. Unzipping over an old copy leaves them behind, and Gradle
# would try to compile them.
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
		install_apk
	fi
}

main() {
	TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/testtube-build.XXXXXX")"
	case "$MODE" in
		build)
			build_apk
			;;
		publish)
			VARIANT="release"
			build_apk
			if has_tty && confirm "Also put the current source on GitHub first?" n; then
				push_source
			fi
			publish_release
			;;
		upload-only)
			# Building isn't needed, but checking the APK's signature and version is
			setup_toolchain
			ensure_build_tools
			VARIANT="release"
			APK_OUT="$(apk_path_for "$(read_version_name)")"
			[[ -f "$APK_OUT" ]] || die "No release APK for version $(read_version_name) at $APK_OUT. Build it first (menu option 1)"
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
			set_version
			;;
		push-source)
			push_source
			;;
		source-zip)
			source_zip
			;;
		key-info)
			setup_toolchain
			show_key_info
			;;
		backup-key)
			ensure_system_tools
			backup_signing_key
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
