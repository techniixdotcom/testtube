#!/usr/bin/env bash
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
TMP_DIR=""

log() { printf '\033[1;32m[TestTube]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[TestTube] WARNING:\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31m[TestTube] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

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
	[[ -s "$LOG_FILE" ]] && sleep 1
}
on_error() {
	local code=$?
	printf '\033[1;31m[TestTube] Build failed (exit %s) at line %s. Full log: %s\033[0m\n' "$code" "$1" "$LOG_FILE" >&2
	exit "$code"
}
trap cleanup EXIT
trap 'on_error $LINENO' ERR

usage() {
	cat <<'EOF'
Usage: ./BUILD.sh [--clean] [--debug] [--install] [--reinstall] [--verbose] [--fix-permissions] [--import-key DIR] [--help]

  --clean            delete previous build outputs before building
  --debug            build a debug APK instead of the signed release APK
  --install          install the APK on the phone connected over USB (USB debugging on);
                     if the phone has TestTube signed with an older key that is still on
                     this computer, that key is picked automatically
  --reinstall        like --install, but if the phone's TestTube was signed with a key
                     that no longer exists, uninstall it first (its settings, history
                     and login are lost)
  --verbose          more detailed Gradle output in the log (Gradle --info)
  --fix-permissions  give the project folder back to you if an earlier run (for
                     example with sudo) left files you cannot change or delete
  --import-key DIR   use the signing key in DIR (testtube-release.p12 + signing.env),
                     for example /root/.testtube/signing, so the APK can update an
                     installed TestTube that was signed with that key
  --help             show this help

Logs (written live, so you can follow along with: tail -f build.log):
  build.log          this run, with timestamps and no colour codes
  build.previous.log the run before
  build-errors.log   only the errors, written when a build fails

Environment:
  TESTTUBE_HOME   where the JDK, Android SDK and signing key are kept (default: ~/.testtube)
EOF
}

CLEAN=0
VARIANT="release"
FIX_PERMISSIONS=0
VERBOSE=0
INSTALL=0
REINSTALL=0
APK_OUT=""
IMPORT_KEY=""
EXPECT_KEY_DIR=0
for arg in "$@"; do
	if [[ "$EXPECT_KEY_DIR" -eq 1 ]]; then
		IMPORT_KEY="$arg"
		EXPECT_KEY_DIR=0
		continue
	fi
	case "$arg" in
		--import-key) EXPECT_KEY_DIR=1 ;;
		--import-key=*) IMPORT_KEY="${arg#--import-key=}" ;;
		--clean) CLEAN=1 ;;
		--install) INSTALL=1 ;;
		--reinstall) INSTALL=1; REINSTALL=1 ;;
		--debug) VARIANT="debug" ;;
		--verbose) VERBOSE=1 ;;
		--fix-permissions) FIX_PERMISSIONS=1 ;;
		--help|-h) usage; exit 0 ;;
		*) usage; die "Unknown option: $arg" ;;
	esac
done
if [[ "$EXPECT_KEY_DIR" -eq 1 ]]; then
	usage
	die "--import-key needs the folder that holds testtube-release.p12 and signing.env"
fi

if [[ "$(id -u)" -eq 0 && -n "${SUDO_USER:-}" && "$SUDO_USER" != "root" && "$FIX_PERMISSIONS" -eq 0 ]]; then
	log "Started with sudo; continuing as $SUDO_USER so every file stays yours"
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

if [[ "$FIX_PERMISSIONS" -eq 1 ]]; then
	fix_permissions
	exit 0
fi

[[ -f "$LOG_FILE" ]] && mv -f "$LOG_FILE" "$ROOT/build.previous.log"
rm -f "$ROOT/build-errors.log"
: > "$LOG_FILE"
# Everything printed from here on goes to the terminal and, line by line as it happens, to
# build.log (timestamped, colour codes removed).
if command -v perl >/dev/null 2>&1; then
	exec > >(tee >(perl -MPOSIX -pe 'BEGIN { $| = 1 } s/\e\[[0-9;?]*[A-Za-z]//g; s/\r\n/\n/; s/\r/\n/g; $_ = strftime("[%H:%M:%S] ", localtime) . $_' >> "$LOG_FILE")) 2>&1
else
	exec > >(tee -a "$LOG_FILE") 2>&1
fi
log "Build started $(date '+%Y-%m-%d %H:%M:%S')"
log "Live log: tail -f $LOG_FILE"

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
	if command -v apt-get >/dev/null 2>&1; then
		run_privileged apt-get update
		run_privileged env DEBIAN_FRONTEND=noninteractive apt-get install -y curl unzip tar gawk sed grep coreutils ca-certificates
	elif command -v dnf >/dev/null 2>&1; then
		run_privileged dnf install -y curl unzip tar gawk sed grep coreutils ca-certificates
	elif command -v pacman >/dev/null 2>&1; then
		run_privileged pacman -Sy --noconfirm --needed curl unzip tar gawk sed grep coreutils ca-certificates
	elif command -v zypper >/dev/null 2>&1; then
		run_privileged zypper --non-interactive install curl unzip tar gawk sed grep coreutils ca-certificates
	elif command -v brew >/dev/null 2>&1; then
		brew install curl unzip coreutils
	else
		die "Please install: ${missing[*]}"
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

load_signing_key() {
	local password
	password="$(signing_password_from < "$SIGNING_ENV")" || die "$SIGNING_ENV is damaged"
	export TESTTUBE_KEYSTORE_FILE="$KEYSTORE"
	export TESTTUBE_KEYSTORE_PASSWORD="$password"
	export TESTTUBE_KEY_ALIAS="testtube"
	export TESTTUBE_KEY_PASSWORD="$password"
	local fingerprint
	fingerprint="$("$JDK_DIR/bin/keytool" -list -v -keystore "$KEYSTORE" -storetype PKCS12 \
		-storepass:env TESTTUBE_KEYSTORE_PASSWORD -alias testtube 2>/dev/null \
		| awk -F': ' '/SHA256:/ {print $2; exit}')" || true
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
			warn "Back up $SIGNING_DIR. Without it future builds cannot update the installed app."
		fi
	fi
	load_signing_key
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
		echo "Errors from the build of $(date '+%Y-%m-%d %H:%M:%S')"
		echo "(the complete output is in $LOG_FILE)"
		echo
		grep -nE '(^|[[:space:]])error:|^e: |cannot find symbol|incompatible types|FAILED$|\* What went wrong|Execution failed|Caused by:|AAPT|BUILD FAILED' -A4 "$src" 2>/dev/null | head -n 250 || true
	} > "$out"
	printf '\033[1;31m[TestTube] ---- What went wrong ----\033[0m\n'
	head -n 70 "$out"
	printf '\033[1;31m[TestTube] -------------------------\033[0m\n'
	log "Errors only: $out"
}

run_gradle() {
	local heap
	heap="$(gradle_heap_mb)"
	local task=":app:assembleRelease"
	[[ "$VARIANT" == "debug" ]] && task=":app:assembleDebug"
	local args=(--no-daemon --stacktrace --console=plain
		"-Dorg.gradle.jvmargs=-Xmx${heap}m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8")
	[[ "$VERBOSE" -eq 1 ]] && args+=(--info)
	chmod +x "$ROOT/gradlew"
	if [[ "$CLEAN" -eq 1 ]]; then
		log "Cleaning previous outputs"
		rm -rf "$DIST_DIR"
		(cd "$ROOT" && ./gradlew "${args[@]}" clean)
	fi
	local attempt gradle_out
	gradle_out="$(mktemp)"
	for attempt in 1 2 3; do
		log "Running Gradle $task (attempt $attempt of 3)"
		if (cd "$ROOT" && ./gradlew "${args[@]}" "$task" 2>&1 | tee "$gradle_out"); then
			rm -f "$gradle_out"
			return 0
		fi
		# Only network trouble is worth another try; a code error will fail the same way again.
		if ! grep -qiE 'Could not (resolve|GET|HEAD|download)|timed out|Connection (reset|refused)|UnknownHost|SSLHandshake|Premature end' "$gradle_out"; then
			extract_errors "$gradle_out"
			rm -f "$gradle_out"
			die "Gradle build failed (not a network problem, so it was not retried). See $ROOT/build-errors.log"
		fi
		if [[ "$attempt" -lt 3 ]]; then
			warn "Gradle failed on the network, retrying in 15 seconds"
			sleep 15
		fi
	done
	extract_errors "$gradle_out"
	rm -f "$gradle_out"
	die "Gradle build failed three times. See $ROOT/build-errors.log and $LOG_FILE"
}

collect_apk() {
	local version
	version="$(grep -E '^val appVersionName[[:space:]]*=' "$ROOT/app/build.gradle.kts" | head -n 1 | sed -E 's/.*"(.*)".*/\1/')"
	version="${version#v}"
	[[ -n "$version" ]] || die "Could not read appVersionName from app/build.gradle.kts"
	local out="$DIST_DIR/testtube$version.apk"
	if [[ "$VARIANT" == "debug" ]]; then
		out="$DIST_DIR/testtube$version-debug.apk"
	fi
	APK_OUT="$out"
	[[ -f "$out" ]] || die "APK not found at $out"
	local apksigner
	apksigner="$(find "$SDK_DIR/build-tools" -maxdepth 2 -name apksigner -type f 2>/dev/null | sort -V | tail -n 1 || true)"
	if [[ -n "$apksigner" ]]; then
		JAVA_HOME="$JDK_DIR" "$apksigner" verify "$out"
		log "Signature verified"
	else
		warn "apksigner not found, signature not verified"
	fi
	local aapt2 built
	aapt2="$(find "$SDK_DIR/build-tools" -maxdepth 2 -name aapt2 -type f 2>/dev/null | sort -V | tail -n 1 || true)"
	if [[ -n "$aapt2" ]]; then
		built="$("$aapt2" dump badging "$out" 2>/dev/null | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -n 1 || true)"
		[[ "$built" == "v$version" ]] || die "The APK reports version '$built' but app/build.gradle.kts says v$version"
		log "APK version: $built"
	fi
	
	find "$DIST_DIR" -maxdepth 1 -name 'testtube*.apk' ! -name "$(basename "$out")" -delete 2>/dev/null || true
	log "SHA-256: $(sha256_of "$out")"
	log "Done. Your APK: $out"
}

APP_ID="com.testtube.app"

adb_bin() {
	printf '%s' "$SDK_DIR/platform-tools/adb"
}

device_ready() {
	[[ -x "$(adb_bin)" ]] && [[ "$("$(adb_bin)" get-state 2>/dev/null | tr -d '\r')" == "device" ]]
}

apksigner_bin() {
	find "$SDK_DIR/build-tools" -maxdepth 2 -name apksigner -type f 2>/dev/null | sort -V | tail -n 1 || true
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

apk_digest() {
	"$(apksigner_bin)" verify --print-certs "$1" 2>/dev/null \
		| awk -F': ' '/Signer #1 certificate SHA-256 digest/ {print $2; exit}' | tr 'A-F' 'a-f'
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
	TESTTUBE_CANDIDATE_PASSWORD="$password" "$JDK_DIR/bin/keytool" -list -v -keystore "$copy" -storetype PKCS12 \
		-storepass:env TESTTUBE_CANDIDATE_PASSWORD -alias testtube 2>/dev/null \
		| awk -F': ' '/SHA256:/ {print $2; exit}' | tr -d ':' | tr 'A-F' 'a-f'
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
	"$(adb_bin)" shell dumpsys package "$APP_ID" 2>/dev/null | tr -d '\r' \
		| sed -n 's/.*versionName=\(.*\)/\1/p' | head -n 1 || true
}

install_apk() {
	device_ready || die "No phone found. Turn on USB debugging, connect the phone, accept the prompt on it and run again"
	local output
	log "Installing $APK_OUT"
	output="$("$(adb_bin)" install -r "$APK_OUT" 2>&1 || true)"
	if grep -q '^Success' <<< "$output"; then
		log "Installed on the phone: $(installed_version)"
		return
	fi
	printf '%s\n' "$output"
	if grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' <<< "$output"; then
		if [[ "$REINSTALL" -eq 1 ]]; then
			warn "Uninstalling the old TestTube (signed with a different key)"
			"$(adb_bin)" uninstall "$APP_ID" >/dev/null 2>&1 || true
			output="$("$(adb_bin)" install "$APK_OUT" 2>&1 || true)"
			grep -q '^Success' <<< "$output" || die "Installation failed: $output"
			log "Installed on the phone: $(installed_version)"
			return
		fi
		die "The phone's TestTube was signed with a different key. Run ./BUILD.sh --reinstall to replace it (its settings, history and login are lost)"
	fi
	if grep -q 'INSTALL_FAILED_VERSION_DOWNGRADE' <<< "$output"; then
		die "The phone has a newer TestTube than this build. Raise appVersionCode in app/build.gradle.kts"
	fi
	die "Installation failed"
}

main() {
	detect_platform
	ensure_system_tools
	TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/testtube-build.XXXXXX")"
	mkdir -p "$TESTTUBE_HOME"
	install_jdk
	export JAVA_HOME="$JDK_DIR"
	export PATH="$JAVA_HOME/bin:$PATH"
	export ANDROID_HOME="$SDK_DIR"
	export ANDROID_SDK_ROOT="$SDK_DIR"
	install_android_sdk
	write_local_properties
	if [[ -n "$IMPORT_KEY" ]]; then
		import_signing_key "$IMPORT_KEY"
	fi
	if [[ "$VARIANT" == "release" ]]; then
		ensure_signing_key
		if [[ "$INSTALL" -eq 1 ]]; then
			match_installed_key
		fi
	fi
	pin_gradle_checksum
	run_gradle
	collect_apk
	if [[ "$INSTALL" -eq 1 ]]; then
		install_apk
	fi
}

main
