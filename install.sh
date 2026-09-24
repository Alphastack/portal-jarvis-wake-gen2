#!/usr/bin/env bash
set -euo pipefail
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"; PKG=com.german.portaljarviswake; JARVIS=com.portal.assistant
ADB="${ADB:-adb}"; command -v "$ADB" >/dev/null || { echo "adb not found; install Android platform-tools and put adb on PATH."; exit 1; }
test -f "$APK" || { echo "APK not found: $APK"; exit 1; }
"$ADB" install -r "$APK"
"$ADB" shell pm grant "$PKG" android.permission.RECORD_AUDIO || true
"$ADB" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true
"$ADB" shell dumpsys deviceidle whitelist +"$PKG" || true
"$ADB" shell pm path "$JARVIS" || echo "WARNING: Jarvis ($JARVIS) is not installed"
"$ADB" shell am start -n "$PKG/.SettingsActivity" || true
"$ADB" shell am start-foreground-service -n "$PKG/.WakeService" || true
echo "Diagnostics: adb logcat -s PortalJarvisWake"
