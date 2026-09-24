#!/usr/bin/env bash
set -euo pipefail

PKG="com.german.portaljarviswake"
JARVIS="com.portal.assistant"
ACTIVITY="$PKG/.SettingsActivity"
SERVICE="$PKG/.WakeService"
ASSET="portal-jarvis-wake-gen2.apk"
REPO="${PORTAL_JARVIS_REPO:-}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CACHE_DIR="${XDG_CACHE_HOME:-$SCRIPT_DIR/.cache}/portal-jarvis-wake-gen2"
ADB_BIN="${ADB:-}"

usage() {
  cat <<'EOF'
Usage:
  ./install.sh                     newest local APK, otherwise latest release
  ./install.sh --local [APK]       install an APK built on this machine
  ./install.sh --latest            download latest GitHub Release APK
  ./install.sh --status            print installation/service state
  ./install.sh --uninstall         remove the helper
  ./install.sh --help

Set PORTAL_JARVIS_REPO=OWNER/portal-jarvis-wake-gen2 for --latest until this
checkout has an origin remote. ADB is used from PATH first.
EOF
}

resolve_adb() {
  if [[ -n "$ADB_BIN" && -x "$ADB_BIN" ]]; then return; fi
  if command -v adb >/dev/null 2>&1; then ADB_BIN="$(command -v adb)"; return; fi
  command -v curl >/dev/null 2>&1 || { echo "ERROR: curl is required to download adb." >&2; exit 1; }
  command -v unzip >/dev/null 2>&1 || { echo "ERROR: unzip is required to download adb." >&2; exit 1; }

  local os arch url
  os="$(uname -s)"; arch="$(uname -m)"
  case "$os/$arch" in
    Darwin/*) url="https://dl.google.com/android/repository/platform-tools-latest-darwin.zip" ;;
    Linux/x86_64|Linux/amd64) url="https://dl.google.com/android/repository/platform-tools-latest-linux.zip" ;;
    Linux/aarch64|Linux/arm64)
      echo "ERROR: Google does not publish Linux arm64 platform-tools." >&2
      echo "Install adb through your distro/NixOS and put it on PATH, then rerun." >&2
      exit 1
      ;;
    *) echo "ERROR: unsupported host $os/$arch; install adb on PATH." >&2; exit 1 ;;
  esac

  mkdir -p "$CACHE_DIR"
  if [[ ! -x "$CACHE_DIR/platform-tools/adb" ]]; then
    echo "Downloading official Android platform-tools..."
    curl -fL --retry 3 "$url" -o "$CACHE_DIR/platform-tools.zip"
    rm -rf "$CACHE_DIR/platform-tools"
    unzip -q "$CACHE_DIR/platform-tools.zip" -d "$CACHE_DIR"
  fi
  ADB_BIN="$CACHE_DIR/platform-tools/adb"
}

wait_for_portal() {
  echo "Waiting for Portal over USB-C (accept the debugging prompt on screen)..."
  "$ADB_BIN" start-server >/dev/null
  "$ADB_BIN" wait-for-device
  if [[ "$("$ADB_BIN" get-state 2>/dev/null || true)" != "device" ]]; then
    echo "ERROR: Portal is offline or unauthorized. Check 'adb devices'." >&2
    exit 1
  fi
}

newest_local_apk() {
  local candidate newest=""
  shopt -s nullglob
  for candidate in \
    "$SCRIPT_DIR"/app/build/outputs/apk/release/*.apk \
    "$SCRIPT_DIR"/app/build/outputs/apk/debug/*.apk \
    "$SCRIPT_DIR"/*.apk; do
    [[ -z "$newest" || "$candidate" -nt "$newest" ]] && newest="$candidate"
  done
  shopt -u nullglob
  printf '%s' "$newest"
}

download_latest() {
  [[ -n "$REPO" ]] || {
    echo "ERROR: set PORTAL_JARVIS_REPO=OWNER/portal-jarvis-wake-gen2 for release downloads." >&2
    exit 1
  }
  mkdir -p "$CACHE_DIR"
  local apk="$CACHE_DIR/$ASSET"
  echo "Downloading latest release from $REPO..."
  curl -fL --retry 3 "https://github.com/$REPO/releases/latest/download/$ASSET" -o "$apk"
  printf '%s' "$apk"
}

status() {
  wait_for_portal
  echo "Helper package:"
  "$ADB_BIN" shell pm path "$PKG" 2>/dev/null || echo "  NOT INSTALLED"
  echo "Jarvis package:"
  "$ADB_BIN" shell pm path "$JARVIS" 2>/dev/null || echo "  NOT INSTALLED"
  echo "Listener service:"
  "$ADB_BIN" shell dumpsys activity services "$SERVICE" 2>/dev/null || true
  echo "Recent diagnostics:"
  "$ADB_BIN" logcat -d -t 80 -s PortalJarvisWake 2>/dev/null || true
}

resolve_adb
MODE="${1:-auto}"
APK=""
case "$MODE" in
  --help|-h) usage; exit 0 ;;
  --status) status; exit 0 ;;
  --uninstall)
    wait_for_portal
    "$ADB_BIN" uninstall "$PKG" || true
    exit 0
    ;;
  --latest) APK="$(download_latest)" ;;
  --local)
    APK="${2:-$(newest_local_apk)}"
    [[ -n "$APK" ]] || { echo "ERROR: no local APK found; run ./gradlew assembleDebug." >&2; exit 1; }
    ;;
  auto)
    APK="$(newest_local_apk)"
    [[ -n "$APK" ]] || APK="$(download_latest)"
    ;;
  *) APK="$MODE" ;;
esac

[[ -f "$APK" ]] || { echo "ERROR: APK not found: $APK" >&2; exit 1; }
wait_for_portal

echo "Installing $(basename -- "$APK")..."
"$ADB_BIN" install -r -d -g "$APK"
"$ADB_BIN" shell pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
"$ADB_BIN" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
"$ADB_BIN" shell dumpsys deviceidle whitelist "+$PKG" >/dev/null 2>&1 || true

JARVIS_OK=no
if "$ADB_BIN" shell pm path "$JARVIS" 2>/dev/null | tr -d '\r' | grep -q '^package:'; then JARVIS_OK=yes; fi
"$ADB_BIN" shell am start -n "$ACTIVITY" >/dev/null
"$ADB_BIN" shell am start-foreground-service -n "$SERVICE" >/dev/null 2>&1 || true
HELPER_OK=no
if "$ADB_BIN" shell pm path "$PKG" 2>/dev/null | tr -d '\r' | grep -q '^package:'; then HELPER_OK=yes; fi

echo
echo "Installation checklist"
echo "  Helper installed: $HELPER_OK"
echo "  Jarvis installed:  $JARVIS_OK"
echo "  Microphone grant:   requested"
echo "  Overlay app-op:     requested (Portal Android 10 foreground workaround)"
echo "  Battery allowlist:  requested (best effort)"
echo
echo "On the Portal, enable Wake listener and wait for the one-time model download."
echo "Diagnostics: $ADB_BIN logcat -s PortalJarvisWake"
[[ "$JARVIS_OK" == yes ]] || echo "WARNING: install com.portal.assistant before testing handoff."
