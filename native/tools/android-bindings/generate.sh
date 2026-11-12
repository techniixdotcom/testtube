#!/usr/bin/env bash
# Regenerates rquickjs-sys's Android bindings in native/vendor/rquickjs-sys/src/bindings.
# Needs libclang (e.g. apt install libclang-dev, or LIBCLANG_PATH=/usr/lib/llvm-18/lib).
# Only the declarations of QuickJS's API are generated, so tiny stand-ins for stdio.h,
# string.h and math.h (stubs/) replace the NDK's libc headers; clang's own headers and
# --target give each ABI's real sizes and alignments.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
vendor="$here/../../vendor/rquickjs-sys"
resource="$(clang -print-resource-dir 2>/dev/null || true)/include"
[[ -f "$resource/stdint.h" ]] || resource="$(ls -d /usr/lib/llvm-*/lib/clang/*/include | sort -V | tail -n 1)"
headers="$(mktemp -d)"
trap 'rm -rf "$headers"' EXIT
cp "$vendor/quickjs/quickjs.h" "$vendor/quickjs/quickjs-atom.h" "$vendor/quickjs.bind.h" "$headers/"
(cd "$here" && cargo build --quiet)
for target in aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android; do
	out="$vendor/src/bindings/$target.rs"
	"$here/target/debug/android-bindings" "$target" "$headers/quickjs.bind.h" "$here/stubs" "$resource" "$out.tmp"
	{ echo "// TestTube: generated with native/tools/android-bindings (same bindgen settings as"
	  echo "// rquickjs-sys's build.rs, clang --target=$target). rquickjs ships none for Android."
	  cat "$out.tmp"; } > "$out"
	rm -f "$out.tmp"
	echo "wrote $out"
done
