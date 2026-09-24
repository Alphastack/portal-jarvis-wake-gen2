# Portal Jarvis Wake Gen2

A focused, standalone wake-word companion for **Meta Portal 10" Gen 2 (2019), Android 10/API 29**. It keeps a local microphone listener alive independently of Immortal, its photo-frame Dream/screensaver, Spotify, a browser, or another foreground app. A confirmed **“hey jarvis”** releases the microphone, foregrounds [portal-assistant](https://github.com/rudysev/portal-assistant), and invokes its public `portal-wake` handoff.

**No Home Assistant. No MQTT. No cloud audio. No root.**

> [!IMPORTANT]
> The app, tests, and APK build are implemented. The final appliance acceptance test still requires real Meta Portal Gen 2 hardware. Android firmware variants can differ in background-launch and microphone behavior; this repository does not claim hardware validation that has not happened.

## How it works

```text
foreground WakeService + tiny non-touch overlay
  -> AudioRecord (16 kHz mono PCM)
  -> local Vosk grammar: ["hey jarvis", "[unk]"]
  -> finalized-result-only exact matcher
  -> release AudioRecord
  -> wake display if screen-off mode was opted in
  -> launch com.portal.assistant into the Android 10 foreground
  -> wait 350 ms for Activity resume
  -> explicit com.portal.wake.action.WAKE broadcast
  -> observe a non-helper AudioRecordingConfiguration
  -> wait until Jarvis has released recording continuously for 5 s
  -> 3 s echo/cooldown window
  -> reacquire microphone and resume Vosk
```

The helper intentionally does **not** attempt to restore an arbitrary previous app or DreamService in v1. Reliability is more important than a seamless animation, and generic task restoration can disrupt Immortal’s screensaver. Jarvis may remain visible after a turn.

## Recognition policy

The recognizer is intentionally stricter than a substring matcher:

- constrained Vosk grammar with an `[unk]` escape;
- finalized recognition results only (`acceptWaveForm == true`);
- `setWords(true)` per-word confidence output is required;
- exact complete normalized token sequence—no prefix, suffix, or contamination;
- `[unk]` is rejected;
- default balanced thresholds: **hey ≥ 0.80**, **jarvis ≥ 0.60**;
- Strict, Balanced, and Relaxed presets;
- 3-second trigger debounce;
- microphone-yield tracking plus post-turn cooldown to reduce speaker retriggers.

Ambient microphone PCM is processed in memory and is never uploaded or saved.

## Compatibility

| Component | Support |
|---|---|
| Meta Portal 10" Gen 2 / Android 10 | Primary target; hardware validation pending |
| Android API 29 / arm64-v8a | Primary build |
| Android 9 / API 28 | Built/minSdk compatible; not hardware tested |
| armeabi-v7a | Packaged for community-device compatibility |
| Immortal launcher and Dream/screensaver | Service is independent of the displayed Activity/Dream |
| `com.portal.assistant` / Jarvis | Uses its stable public wake broadcast contract |
| Google Mobile Services | Not required |
| Home Assistant / MQTT | Not used |

## Install

### Release install

Download and install the latest published release:

```bash
./install.sh --latest
```

With an APK already built or downloaded:

```bash
./install.sh --local app/build/outputs/apk/debug/app-debug.apk
```

The installer:

1. uses `adb` from `PATH` first;
2. downloads official Google platform-tools on macOS or Linux x86_64 if needed;
3. waits for an authorized USB device;
4. installs with `-r -d -g`;
5. grants microphone and Portal overlay app-op where ADB permits;
6. requests the device-idle allowlist best-effort;
7. checks whether Jarvis is installed;
8. opens the settings screen and prints a checklist.

**NixOS/Linux arm64:** Google does not publish Linux arm64 platform-tools. Install `adb` through Nixpkgs/your distribution and put it on `PATH`; the script reports this rather than trying an incompatible x86 binary.

Windows PowerShell:

```powershell
.\install.ps1 -Mode Latest
```

`install.bat` is a double-click wrapper. Other useful modes:

```bash
./install.sh --status
./install.sh --uninstall
```

## Portal setup

1. Install and configure Jarvis (`com.portal.assistant`) first.
2. Run the installer over USB-C and accept **Allow USB debugging** on the Portal.
3. In **Portal Jarvis Wake**, enable **Wake listener**.
4. Leave the Portal online for the one-time official Vosk small English model download (roughly 40 MB compressed).
5. Keep **Listen while screen is off** disabled for the initial test.
6. Return to Immortal and allow its photo-frame screensaver to start.
7. Say: **“Hey Jarvis, what time is it?”**

The tiny transparent `SYSTEM_ALERT_WINDOW` overlay is non-touchable and non-focusable. On Portal’s Android 10 firmware it helps keep the foreground service in a process state allowed to launch Jarvis. The ADB installer grants this app-op; without it, the listener may hear the phrase but Android may block the foreground switch.

## Screen-off mode

Screen-off listening is **off by default**.

- **Off:** `SCREEN_OFF` releases AudioRecord; `SCREEN_ON` reacquires it.
- **On:** the service keeps a `PARTIAL_WAKE_LOCK` only while actively listening. After a confirmed phrase it briefly uses the API-29 `ACQUIRE_CAUSES_WAKEUP | SCREEN_BRIGHT_WAKE_LOCK` behavior to light the display before foregrounding Jarvis.

This consumes more power. Meta firmware may still gate the far-field microphone in a deeper vendor sleep state. If repeated on-device testing shows that, leave this mode off; the project does not use periodic wake hacks.

## Status and diagnostics

The settings page reports Disabled, model download/progress/failure, Listening, screen-paused, Yielding, Jarvis active, Cooldown, and microphone errors. Logs use one tag:

```bash
adb logcat -s PortalJarvisWake
adb shell dumpsys audio
adb shell dumpsys media.audio_flinger
adb shell dumpsys activity services com.german.portaljarviswake/.WakeService
adb shell appops get com.german.portaljarviswake RECORD_AUDIO SYSTEM_ALERT_WINDOW
```

If wake is detected but Jarvis does not listen, inspect active recording configurations around the handoff. If the model fails, tap **Download/retry model**; a valid installed model is not unnecessarily redownloaded.

## Build and test

Requirements: JDK 17 and Android SDK 35.

```bash
./gradlew test lint assembleDebug assembleRelease
bash -n install.sh
```

CI performs these gates on x86_64 Ubuntu and verifies both Portal ARM ABI directories in the APK. Tagged `v*` builds publish an installable debug-signed preview as `portal-jarvis-wake-gen2.apk`. Before catalog distribution, replace preview signing with a backed-up stable release keystore.

## Real Portal acceptance checklist

- [ ] Cold boot; Immortal launches normally; listener returns to **Listening**.
- [ ] Immortal launcher visible: “Hey Jarvis, what time is it?” succeeds.
- [ ] Immortal photo-frame Dream active: the same phrase succeeds without touch.
- [ ] Spotify/browser foreground: the same phrase succeeds.
- [ ] Jarvis foreground: phrase still starts a turn.
- [ ] Jarvis response does not immediately retrigger the helper.
- [ ] Listener automatically becomes ready after each turn.
- [ ] Repeat at least 20 times with no manual app restart.
- [ ] Reboot and repeat without manually opening the helper.
- [ ] Toggle display off with screen-off listening disabled: microphone is released.
- [ ] Opt in to screen-off listening: phrase wakes display and starts Jarvis, if firmware permits.

## Licensing and clean-room provenance

This repository is MIT licensed and independently implemented.

References used for interoperability and behavior:

- `rudysev/portal-assistant`: public `com.portal.wake.action.WAKE` handoff contract and target package.
- `RoadRunner-1024/portal-ha-bridge`: proof that a foreground Portal process, local Vosk detection, deliberate mic release, and Android-10 foreground handoff can work. That project is PolyForm Noncommercial 1.0.0; **its source code was not copied into this repository**.
- `starbrightlab/immortal`: target launcher/screensaver environment and installation UX reference (MIT).

Runtime dependencies retain their own licenses, notably Vosk Android/Kaldi (Apache-2.0 components), JNA (Apache-2.0/LGPL dual-license terms), AndroidX (Apache-2.0), and the separately downloaded Vosk model’s published model license. See dependency metadata before redistribution.

## Security and contributing

See [SECURITY.md](SECURITY.md) and [CONTRIBUTING.md](CONTRIBUTING.md). Please include the Portal model/API version, Jarvis version, and a filtered `PortalJarvisWake` log with hardware bug reports. Do not attach ambient recordings without the consent of everyone captured.
