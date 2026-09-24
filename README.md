# Portal Jarvis Wake Gen2

An independent, on-device wake-word companion for Meta Portal Gen 2 (Android 10/API 29, arm64). It listens for the exact default phrase `hey jarvis`, releases its microphone, then asks the installed Jarvis app to wake.

## Architecture

`WakeService` is a sticky microphone foreground service using 16 kHz mono `VOICE_RECOGNITION` audio and Vosk grammar recognition. Only final Vosk results are evaluated. A pure core normalizes phrases, rejects `[unk]`, requires the lead word at 0.80 and remaining words at 0.60, and debounces wakes for three seconds. The handoff is an explicit broadcast to `com.portal.assistant`: action `com.portal.wake.action.WAKE`, extra `com.portal.wake.extra.ID=jarvis`.

At first run the official Vosk small US English archive is downloaded over HTTPS, unpacked into a staging directory with zip-slip checks, and atomically placed in app storage. No speech leaves the Portal; this project has no Home Assistant, MQTT, account, or provisioning integration.

The states are LISTENING → YIELDING (mic released) → ASSISTANT_ACTIVE → COOLDOWN. Launch is delayed about 350 ms after release. Android’s recording callback yields to another recorder and waits five seconds after it disappears before retrying, with capped backoff and a 120-second assistant safety timeout.

## Install

Build with `./gradlew assembleDebug`, then run `./install.sh app/build/outputs/apk/debug/app-debug.apk`. The scripts grant microphone/overlay permissions where ADB permits, request battery optimization exclusion best-effort, verify Jarvis, start the listener, and print diagnostics. Windows: `./install.ps1` or `install.bat`.

## Diagnostics and known limits

`adb logcat -s PortalJarvisWake` shows state, model, and handoff logs. Android 10/Portal background launch policy is device-specific; this app requests an unobtrusive, non-touch overlay to improve its allowance. Hardware audio, overlay behavior, and actual Jarvis response require Portal testing. v1 intentionally does not restore the prior UI: Jarvis may remain visible after a handoff.

## Portal acceptance checklist

- [ ] Portal Gen 2 Android 10/API 29, microphone and overlay grants accepted
- [ ] Model downloads and shows `am` directory while online
- [ ] Exact “hey jarvis” wakes once; near matches and `[unk]` do not
- [ ] Screen-off default stops; opt-in remains listening
- [ ] Handoff reaches installed `com.portal.assistant`
- [ ] Another recorder causes yield and listener returns after it ends
- [ ] Reboot/package update restarts only when enabled and autostart is selected
