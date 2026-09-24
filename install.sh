#!/usr/bin/env bash
set -euo pipefail
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"; PKG=com.german.portaljarviswake; JARVIS=com.portal.assistant
ADB="${ADB:-adb}"
if ! command -v "$ADB" >/dev/null; then
  TOOLS="${XDG_CACHE_HOME:-$PWD/.cache}/portal-platform-tools"; mkdir -p "$TOOLS"
  if [ ! -x "$TOOLS/platform-tools/adb" ]; then
    echo "Downloading official Android platform-tools…"
    curl -fsSL -o "$TOOLS/tools.zip" https://dl.google.com/android/repository/platform-tools-latest-linux.zip
    command -v unzip >/dev/null || { echo "adb absent and unzip is required for automatic platform-tools install"; exit 1; }
    unzip -qo "$TOOLS/tools.zip" -d "$TOOLS"
  fi
  ADB="$TOOLS/platform-tools/adb"
fi
test -f "$APK" || { echo "APK not found: $APK"; exit 1; }
"$ADB" install -r "$APK"
"$ADB" shell pm grant "$PKG" android.permission.RECORD_AUDIO || true
"$ADB" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true
"$ADB" shell dumpsys deviceidle whitelist +"$PKG" || true
"$ADB" shell pm path "$JARVIS" || echo "WARNING: Jarvis ($JARVIS) is not installed"
"$ADB" shell am start -n "$PKG/.SettingsActivity" || true
"$ADB" shell am start-foreground-service -n "$PKG/.WakeService" || true
echo "Diagnostics: adb logcat -s PortalJarvisWake"
