#!/usr/bin/env bash
# Fetch the official ZeroClaw aarch64-linux-android release and stage it for the APK:
#   - the executable becomes app/src/main/jniLibs/arm64-v8a/libzeroclaw.so
#   - the web dashboard bundle becomes app/src/main/assets/web-dist/
# Set ZEROCLAW_TAG (e.g. v0.8.6) to pin a release; default is the latest.
# Set ZEROCLAW_BINARY to a locally built executable (e.g. the 16 KB-aligned CI build) to use it instead of
# the release binary; the dashboard bundle still comes from the release archive of the same tag.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tag="${ZEROCLAW_TAG:-latest}"
asset="zeroclaw-aarch64-linux-android.tar.gz"
if [ "$tag" = "latest" ]; then
  url="https://github.com/zeroclaw-labs/zeroclaw/releases/latest/download/$asset"
else
  url="https://github.com/zeroclaw-labs/zeroclaw/releases/download/$tag/$asset"
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "Downloading $url"
curl -fSL --retry 4 --retry-delay 3 -o "$work/$asset" "$url"
sha="$(sha256sum "$work/$asset" | cut -d' ' -f1)"

mkdir "$work/x"
tar -xzf "$work/$asset" -C "$work/x"

[ -f "$work/x/zeroclaw" ] || { echo "zeroclaw binary missing from archive" >&2; exit 1; }
[ "$(head -c4 "$work/x/zeroclaw" | od -An -c | tr -d ' ')" = '177ELF' ] || { echo "not an ELF file" >&2; exit 1; }
[ -f "$work/x/web/dist/index.html" ] || { echo "web/dist/index.html missing from archive" >&2; exit 1; }

jni="$root/app/src/main/jniLibs/arm64-v8a"
assets="$root/app/src/main/assets"
mkdir -p "$jni" "$assets"
if [ -n "${ZEROCLAW_BINARY:-}" ]; then
  [ -f "$ZEROCLAW_BINARY" ] || { echo "ZEROCLAW_BINARY not found: $ZEROCLAW_BINARY" >&2; exit 1; }
  cp "$ZEROCLAW_BINARY" "$jni/libzeroclaw.so"
  sha="local-build:$(sha256sum "$ZEROCLAW_BINARY" | cut -d' ' -f1)"
else
  cp "$work/x/zeroclaw" "$jni/libzeroclaw.so"
fi
chmod 755 "$jni/libzeroclaw.so"
rm -rf "$assets/web-dist"
cp -r "$work/x/web/dist" "$assets/web-dist"
printf 'tag=%s\nsha256=%s\nurl=%s\n' "$tag" "$sha" "$url" > "$assets/zeroclaw-release.txt"

echo "Staged ZeroClaw ($tag, sha256 $sha)"
ls -l "$jni/libzeroclaw.so"
