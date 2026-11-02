---
name: hand-review
description: How an agent changes the hand-review zone (androidApp/src/main, *.gradle.kts, libs.versions.toml) so a human can approve it - the HAND-REVIEW comment, minimal diff, API confirmation in the shipped SceneView sources, the commit suffix, and the Filament/ARCore gotchas already hit on the Pixel. Load before any edit under androidApp/ or a Gradle file; pair with ar-sceneview for the AR patterns themselves.
---

# Hand-review changes

The Edit/Write hook asks for approval on every file in the zone and the user reads the diff, not the summary.
Make the diff readable in one screen.

## The contract (every change, no exceptions)
1. **One intent per `// HAND-REVIEW:` comment**, at the line that carries the decision, in the form
   `// HAND-REVIEW: Txx — <what and why in one or two lines>` or `// HAND-REVIEW: review fix — ...` or
   `// HAND-REVIEW: Pixel <date> — <what was seen on the device, then what this does>`. There are ~58 of them in
   `androidApp/src`; read two before writing yours. XML gets `<!-- HAND-REVIEW: ... -->`.
2. **No core/shared/Gradle/manifest change rides along.** If the host needs a new contract, change `:core`/`:shared`
   first in its own commit; the hand-review commit touches only the zone.
3. **A locked decision stays locked and the comment says so** ("PAUSED still freezes and never resets"; "one anchor
   per session"; "scan is a button"). If the change is a debug path that looks like it bends one, say it is debug-only.
4. **Switches, not branches:** a behaviour the user may want to flip on the Pixel is a named constant
   (`FORCE_OCCLUDER_BOXES`, `OCCLUDER_BOXES_WHEN_NO_DEPTH`, `FOOTPRINT_LIFT_M`, `REAPPEAR_AFTER_TRACKING_MS`,
   `samConcurrency`) or a one-line override (`override val voice: VoiceHost? get() = null`). Cut lines are flipped
   this way (load `cut-line`).
5. **Verify** with `./gradlew :androidApp:assembleDebug` (Compose preview is not a build); then `scripts/verify.sh`.
6. **Commit** `androidApp: <what> (Txx; hand-review)` or `(…; hand-review)` at the end of the subject. The final
   message to the user names the files and says "needs your Pixel run".
7. **Brief status** stays `review` until the user ran it; the `Result` section records the decisions and which API
   each line relies on.

## Confirm APIs in the shipped sources, not the docs
SceneView is pinned to 4.31.0; Context7's `/sceneview/sceneview` describes the main branch and has listed APIs that
do not exist in 4.31.0. Before using any API you have not seen compile here, find it in the Gradle cache:
```
find ~/.gradle/caches/modules-2 -path '*sceneview*4.31.0*' -name '*sources.jar'
unzip -p <sources.jar> 'io/github/sceneview/ar/ARSceneView.kt' | grep -n 'fun rememberARCameraStream'
```
Record file:line in the brief's Result (T21 did: `ARSceneView.kt:1941`, `ARCameraStream.kt:399`, `SceneScope.kt:631`).
Filament is 1.72.1 in that build; `MaterialInstance.setColorWrite/setDepthWrite` exist, and SceneView ships
`materialLoader.createOcclusionInstance()` (depth-only material) so there is no need to build one.

## Gotchas already paid for on the Pixel (do not rediscover)
- **Debug chrome vs depth occlusion:** with `depthMode = AUTOMATIC` the camera writes real-world depth; 2 mm-high
  overlays were depth-tested against noisy depth-from-motion and vanished. Draw debug chrome with
  `setDepthCulling(false)` and lift it `FOOTPRINT_LIFT_M = 0.005f` (FootprintOverlay.kt, 2026-11-03).
- **Occluder-box mode must draw the camera first**, otherwise the occluders show as black cup shapes (T21 review).
- **Occluder shape:** cylinder when `area / bboxArea >= CUP_ROUNDNESS (0.85)`, box otherwise; cylinder axis is local Y
  and `center` is the axis midpoint, so base-on-plane means `center.y = heightM / 2`. Occluder nodes sit in the anchor
  frame directly (X = u, Z = v), unlike FootprintOverlay's Shape path which flips `v`.
- **Decide occlusion once**, in `sessionConfiguration` where depth support is known; a `creator` lambda runs before
  the session exists and cannot know it.
- **Tracking returns with a correction:** hide the character for `REAPPEAR_AFTER_TRACKING_MS = 800` after `TRACKING`
  resumes so the anchor correction does not show as a slide.
- **Y-only billboard is edge-on from straight above** (by design); a lean toward a steep camera is a one-line option
  the user has not asked for.
- **Scripted taps must go through the same path as real taps** but bypass the tap boundary (script coordinates are
  anchor-relative); feed them in `onSessionUpdated` while tracking, before `loop.onFrame`; drive the script on the
  simulated physics clock, not wall time (a 0.7 s stall on wall time makes FLY land in the gap).
- **Frame holders are not state:** `remember { arrayOfNulls<Frame>(1) }`, never `mutableStateOf` per frame.
- **Permission launchers are registered in composition** next to the photo ones (T24 did `RECORD_AUDIO` that way);
  the manifest already declares CAMERA and RECORD_AUDIO, so a missing runtime grant throws `SecurityException`.
- **Heavy bundled assets parse off the main thread** (the 7.6 MB bundled set froze the first frame once).
- **Log keys are the evidence.** Every device-checkable behaviour logs one `RudeusGate` key with values
  (`occlusion=depth depth_supported=true`, `scan_ms=`, `scenario_step idx= at_ms=`, `jump_ignored mode= ability=`,
  `voice_ms= text_len=`). Never log a transcript, a photo path with user content, or the key.

## What is not a hand-review change
Pure `:core`/`:shared` work, tests, `ai/` notes, `docs/evidence`, `runs/` pulled from the device, scripts. Do not
add `HAND-REVIEW` comments there; the user greps for them.
