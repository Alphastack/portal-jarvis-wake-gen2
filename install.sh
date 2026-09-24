#!/usr/bin/env bash
set -euo pipefail
PKG=com.german.portaljarviswake; JARVIS=com.portal.assistant
usage(){ echo "Usage: $0 [--local APK|--status|--uninstall|--help]"; }
ADB_BIN="${ADB:-adb}"
if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
  case "$(uname -s)-$(uname -m)" in Darwin-*) URL=https://dl.google.com/android/repository/platform-tools-latest-darwin.zip;; Linux-aarch64|Linux-arm64) echo "Google has no Linux arm64 platform-tools; install adb via distro/NixOS PATH."; exit 1;; Linux-*) URL=https://dl.google.com/android/repository/platform-tools-latest-linux.zip;; *) echo "Install adb on PATH."; exit 1;; esac
  CACHE="${XDG_CACHE_HOME:-$PWD/.cache}/portal-platform-tools"; mkdir -p "$CACHE"; curl -fsSL "$URL" -o "$CACHE/tools.zip"; unzip -qo "$CACHE/tools.zip" -d "$CACHE"; ADB_BIN="$CACHE/platform-tools/adb"
fi
case "${1:---local}" in --help) usage; exit;; --status) "$ADB_BIN" wait-for-device; "$ADB_BIN" shell pm path "$PKG"; "$ADB_BIN" shell dumpsys activity services "$PKG/.WakeService"; exit;; --uninstall) "$ADB_BIN" uninstall "$PKG"; exit;; --local) APK="${2:?APK required}";; *) APK="$1";; esac
if [[ ! -f "${APK:-}" ]]; then APK=$(find app/build/outputs/apk -type f -name '*.apk' -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -1 | cut -d' ' -f2-); fi
[[ -f "${APK:-}" ]] || { echo "No local APK. Build one; future releases use PORTAL_JARVIS_REPO=OWNER/portal-jarvis-wake-gen2."; exit 1; }
"$ADB_BIN" wait-for-device; [[ "$("$ADB_BIN" get-state)" == device ]] || { echo "Authorize adb on the Portal."; exit 1; }
"$ADB_BIN" install -r -d -g "$APK"; "$ADB_BIN" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true; "$ADB_BIN" shell pm path "$JARVIS" || echo "WARNING: Jarvis missing"; "$ADB_BIN" shell am start -n "$PKG/.SettingsActivity" || true
echo "Checklist: enable listener, grant overlay if desired, then adb logcat -s PortalJarvisWake"
