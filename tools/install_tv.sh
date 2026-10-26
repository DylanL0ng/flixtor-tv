#!/usr/bin/env bash
#
# Install the newest GitHub release of the FlixTor TV app onto a local TV.
#
# The release is signed with the newest signing key (personal.p12, see
# ~/.local/share/flixtor-tv/signing.json). Because that key differs from the
# key that signed the previously installed app, `adb install -r` would fail
# with a signature mismatch, so the old app is uninstalled first (fresh
# install; local app data is not preserved).
#
# Usage:
#   TV_HOST=192.168.68.59:5555 ./tools/install_tv.sh
#   ./tools/install_tv.sh 192.168.68.59:5555
#
set -euo pipefail

REPO="DylanL0ng/flixtor-tv"
PACKAGE="dev.personal.flixtortv"
ADB="${ADB:-/Users/dylan/Android/Sdk/platform-tools/adb}"
TV_HOST="${1:-${TV_HOST:-192.168.68.59:5555}}"
API="https://api.github.com/repos/${REPO}"

have() { command -v "$1" >/dev/null 2>&1; }

if ! have adb && ! [[ -x "$ADB" ]]; then
  echo "error: adb not found at $ADB (set ADB env var or install Android platform-tools)" >&2
  exit 1
fi
if ! have curl && ! have wget; then
  echo "error: curl or wget required to download the release" >&2
  exit 1
fi

echo "==> Resolving newest GitHub release for ${REPO} ..."
if have curl; then
  LATEST="$(curl -sS --fail --max-time 30 "${API}/releases/latest")"
else
  LATEST="$(wget -qO- --timeout=30 "${API}/releases/latest")"
fi

TAG="$(printf '%s' "$LATEST" | grep -m1 -o '"tag_name": *"[^"]*"' | sed -E 's/.*"tag_name": *"([^"]*)"/\1/')"

if [[ -z "$TAG" ]]; then
  echo "error: could not parse newest release tag" >&2
  exit 1
fi

# Stable download URLs derived from the resolved tag (matches the CI release
# publishing in .github/workflows/android.yml and the release.json asset).
APK_URL="https://github.com/${REPO}/releases/download/${TAG}/app-release.apk"
RELEASE_JSON_URL="https://github.com/${REPO}/releases/download/${TAG}/release.json"

echo "==> Newest release: ${TAG}  APK: ${APK_URL}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
APK="${TMP_DIR}/app-release.apk"
RELEASE_JSON="${TMP_DIR}/release.json"

echo "==> Downloading APK and release metadata ..."
if have curl; then
  curl -sS --fail --max-time 300 -L "$APK_URL" -o "$APK"
  curl -sS --fail --max-time 60 -L "$RELEASE_JSON_URL" -o "$RELEASE_JSON"
else
  wget -q --timeout=300 -O "$APK" "$APK_URL"
  wget -q --timeout=60 -O "$RELEASE_JSON" "$RELEASE_JSON_URL"
fi

EXPECTED_SHA="$(grep -m1 -o '"sha256": *"[^"]*"' "$RELEASE_JSON" | sed -E 's/.*"sha256": *"([^"]*)"/\1/')"
if [[ -z "$EXPECTED_SHA" ]]; then
  echo "error: release.json has no sha256 field" >&2
  exit 1
fi

echo "==> Verifying sha256 ${EXPECTED_SHA} ..."
ACTUAL="$(shasum -a 256 "$APK" | awk '{print $1}')"
if [[ "$ACTUAL" != "$EXPECTED_SHA" ]]; then
  echo "error: sha256 mismatch: expected ${EXPECTED_SHA} got ${ACTUAL}" >&2
  exit 1
fi
echo "==> sha256 OK"

echo "==> Connecting to TV ${TV_HOST} ..."
"$ADB" connect "$TV_HOST" >/dev/null 2>&1 || true
if ! "$ADB" -s "$TV_HOST" get-state >/dev/null 2>&1; then
  echo "error: TV ${TV_HOST} is not reachable via adb. Is it on the network and is adb debugging enabled?" >&2
  exit 1
fi

echo "==> Uninstalling old app ${PACKAGE} (fresh install required due to signing-key rotation) ..."
"$ADB" -s "$TV_HOST" uninstall "$PACKAGE" >/dev/null 2>&1 && echo "    old app removed" || echo "    no previous install (continuing)"

echo "==> Installing newest release ..."
"$ADB" -s "$TV_HOST" install -t -r "$APK" >/dev/null 2>&1 && echo "    installed" || {
  echo "error: adb install failed" >&2
  exit 1
}

echo "==> Verifying installed version ..."
"$ADB" -s "$TV_HOST" shell pm dump "$PACKAGE" 2>/dev/null | grep -E "versionCode|versionName" || \
"$ADB" -s "$TV_HOST" shell dumpsys package "$PACKAGE" 2>/dev/null | grep -E "versionCode|versionName" || true

echo "==> Done: ${TAG} installed on ${TV_HOST}"
