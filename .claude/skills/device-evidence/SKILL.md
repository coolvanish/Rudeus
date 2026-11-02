---
name: device-evidence
description: Getting gate evidence from the Pixel 9 Pro XL over wireless adb (or the Pixel_7_Pro emulator when the phone is away) - the online+unlocked check before any QA, logcat-first ordering so one-shot RudeusGate keys are not lost, evidence file naming under docs/evidence, pulling runs, and what each device can and cannot prove. Load before dispatching rudeus-device-qa, before claiming a device PASS, or when the Pixel shows offline.
---

# Device evidence

The Pixel is the only place the AR gates can pass. It is paired over **wireless adb** and is often `offline` or
absent when the user is away; the Android emulator covers the non-AR screens only.

## 0. Is the device usable? (run this before dispatching anything)
```
adb devices                                                # Pixel shows as adb-…_adb-tls-connect; "offline" = not usable
adb shell dumpsys window | grep -m1 isKeyguardShowing      # true = locked; QA cannot unlock, do not try a PIN
adb shell dumpsys package com.google.ar.core | grep -m1 versionName
```
- `offline`/absent -> every AR criterion is `UNTESTED (Pixel offline)`; switch to the emulator for UI checks.
- `isKeyguardShowing=true` -> install and launch still work, so "no crash at launch" is the only signal. Skip screen
  steps, tell the user to unlock first. A lock-screen screenshot is not evidence; delete it.
- Never grant permissions through system dialogs you were not told about; report them.

## 1. Order of operations on the Pixel
```
scripts/device.sh log              # terminal 1 FIRST: no `logcat -c`, the gate keys are logged once at startup
./gradlew :androidApp:installDebug && scripts/device.sh run   # terminal 2: cold start (force-stop + am start)
scripts/device.sh shot docs/evidence/dayN-<topic>.png
scripts/device.sh pull-runs        # runs/<ts>.json written by MetricsLog on the phone (source: "app")
scripts/device.sh pull-recordings  # ARCore Recording API MP4s -> docs/recordings/ (never committed)
```
The logcat buffer rotates; a 15 s scenario run three times fits, a long play session does not. When a count matters
for the README (three `scenario_done`), keep `scripts/device.sh log` piped to a file in `docs/evidence/` for the run.

## 2. What the log keys mean (tag `RudeusGate`)
| Key | Proves |
|---|---|
| `session_resumed`, `plane_detected_ms=` | Day 1 plane < 10 s |
| `occlusion=depth|boxes|none depth_supported=` | Day 5 occlusion decision on this device |
| `scan_ms=` (and `segment_ms=`, `segment[...]` 429s) | Day 4 scan < 6 s; 429 -> drop `samConcurrency` to 3 |
| `scenario_start`, `scenario_step idx=0..4 at_ms=`, `scenario_done ms≈15000`, `scenario_rejected` | Day 5 scenario 3x |
| `jump_ignored mode=JUMPING` / `ability=ROLL` | jump guards |
| `voice_ms= text_len=`, `record_audio_granted` | voice path (never the transcript) |
| `AndroidRuntime:E` lines | a crash; paste the tail into the brief |

## 3. Evidence files
- Screenshots: `docs/evidence/<dayN|ui>-<date>-<topic>[-<variant>].png`, under 300 KB (`sips -Z 1080 file.png`).
  Existing names to follow: `t02-dino-on-table-2026-10-28.jpg`, `ui-2026-10-29-capture-kk.png`,
  `day5-occlusion-{depth,boxes}.png`.
- Logs: `docs/evidence/<t|day>N-<topic>-<date>.log` (gate probe logs live there: `t01-gate-2026-10-28.log`).
- Metrics: `runs/*.json` are pulled, never edited; the README numbers come from them (load `metrics`).
- Each file is referenced from the brief's Result or the day's human checklist, otherwise it is noise.

## 4. Emulator fallback (UI screens only)
AVD `Pixel_7_Pro`, SDK at `~/Library/Android/sdk`:
```
~/Library/Android/sdk/emulator/emulator -avd Pixel_7_Pro -no-snapshot -no-boot-anim -gpu swiftshader_indirect -no-audio &
adb wait-for-device && ./gradlew :androidApp:installDebug
```
It proves: Capture (kk/ru/en), Processing, Metrics (empty and with a run), PermissionScreen, system/predictive Back,
chip and card layout (it caught a clipped ability chip and a Back that exited the app). It cannot prove: anything on
the ARSceneView, the pipeline (no key in the build), SoundPool levels, voice. Say so in the report.

## 5. Dispatching `rudeus-device-qa`
Give it: the brief path, the "Done when" list, the evidence names you want, and the result of step 0. It returns a
`| step | action | expected | observed | screenshot |` table; it cannot judge tracking quality and says so. Everything
AR-related goes into `ai/dayN-human.md` for the user (load `human-checklist`).

## Never
- Never claim a device PASS from a JVM test; write `PASS (JVM)` / `UNTESTED (device)` as two cells.
- Never change device settings, Wi-Fi state, or language from adb; the Day 6 "Wi-Fi off halfway" step is the user's.
- Never commit MP4s, raw camera photos, or a lock-screen screenshot.
