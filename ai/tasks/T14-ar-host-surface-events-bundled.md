# T14 — ar-host-surface-events-bundled

status: doing
created: 2026-10-29
day: Day 3 (AR host side of the Day 3 contract; floor/outdoor; T03 leftovers)
gate: Day 3 — 60 s walking around a real cup without drift; capture -> character < 60 s
cut-if-slips: (f) tech-debt items first, then (c) bundled assets; never (a)/(b)

## Goal
`RudeusArView` implements the `ArSlot` contract: the character is sized for the chosen surface (table or
floor/outdoors), the host reports surface-ready / anchor / tracking / character events to the HUD, bundled characters
ship in the APK with sprites, the Metrics export writes a real file, and the T03 review leftovers are closed.
Hand-review zone: keep the diff minimal, `// HAND-REVIEW:` comments on every non-obvious decision, and a Pixel checklist
in the Result for the human.

## Owns
- androidApp/src/main/kotlin/kz/rudy/ar/** (ar/, capture/, di/, MainActivity.kt)
- androidApp/src/main/assets/bundled/** (new), assets/sprites/
- androidApp/src/main/AndroidManifest.xml only if export needs it (it should not on API ≥ 29; minSdk 24: fall back to
  the app's external files dir below 29 — no new permission)
Not yours: core/**, shared/** (T13 owns them in parallel; the contract in `shared/.../ArSlot.kt` and `CaptureHost.kt`
is frozen; the `PermissionScreen` composable and `StringId.ar_wait_floor` exist/will exist in :shared — if a StringId
you need is missing at compile time, add a one-line fallback and say so), every `*.gradle.kts`.

## Contract
(a) **`RudeusArView(modifier, slot: ArSlot)`** replaces the `spritePng` parameter (MainActivity passes the slot).
    - `val profile = SurfaceProfile.of(slot.surface)`: sprite height = `profile.characterHeightM` (CharacterNode's
      `CHARACTER_HEIGHT_M` becomes a parameter), `GameLoop(params = PhysicsParams.forSurface(slot.surface))` and the body's
      `radiusM = profile.radiusM`; walk-tap tolerance = `profile.tapHeightToleranceM`. The ARSceneView itself is NOT
      recreated when the slot changes (hold callbacks in `rememberUpdatedState`; key the sprite on
      `(spritePng, surface)`; a surface change after the anchor exists is ignored with a log line — the HUD hides the
      chips once anchored).
    - Callbacks (main thread, cheap, edge-triggered): `onSurfaceReady(true)` the first frame `PlaneSettle` has any
      settled upward plane (add `PlaneSettle.anySettled(now)`), `false` if it goes away before the anchor;
      `onAnchorPlaced()` after the anchor is created, followed by `onCharacterEvent(PLACED)`;
      `onTracking(b)` only when the tracking boolean changes (initial true after the anchor);
      `onCharacterEvent(e)` for every event from `CharacterEvents.between(prevBody, newBody)` after each `Physics.step`
      (T12 adds the `PhysicsBody` overload; until it lands use the `CharacterState` overload and leave a `// T12` note).
    - Hint pill: `ar_wait_table` / `ar_wait_floor` by surface. The ARCore Rec button only when `slot.debug`.
(b) **Sprite lifetime.** When the sprite is rebuilt (new bytes or surface) destroy the previous `Texture` and
    `MaterialInstance` (`engine.destroyTexture`, `materialLoader`/`engine.destroyMaterialInstance` — check SceneView 4.31
    on Context7 `/sceneview/sceneview` for the owner) in a `DisposableEffect`; never destroy the one in use.
(c) **Bundled characters.** Copy `docs/bundled/*/{sprite.png,sheet.json}` and `docs/bundled/index.json` to
    `androidApp/src/main/assets/bundled/` (7.6 MB; note it in the Result). `AndroidCaptureHost.bundled()` parses
    `index.json` (org.json, like cull.json) + each `sheet.json` into `BundledCharacter(id = ref, sheet, spritePng, thumbnail = null)`,
    `gatePassed` ones first then the rest in index order; a character whose files are missing is skipped, never a crash.
    Cache the list after first load (IO thread; `bundled()` may be called from composition, so load lazily and
    synchronously only on first call — document it).
(d) **Metrics.** `latestRun()` = `metrics.toRun()` once at least one character was started, else null.
    `exportRun()` writes `<runId>.json` via `MediaStore.Downloads` into `Download/Rudeus/` on API ≥ 29 (returns
    "Download/Rudeus/<runId>.json"), else `getExternalFilesDir(null)/runs/` (returns that path); null on any failure,
    logged. Runs on `Dispatchers.IO`.
(e) **Permission screen.** `WithCameraPermission` shows `kz.rudy.ar.ui.screens.PermissionScreen(onAllow = launch)` instead
    of the Material `Button("Camera")`.
(f) **T03 leftovers.** (1) `AndroidImages.exifOrientation`: HEIC/HEIF photos — read EXIF via a `File` path or
    `ExifInterface(InputStream)` on API ≥ 29 where it supports HEIF; verify in the AndroidX/platform docs and fix if the
    stream path returns 0 for HEIF. (2) `preparePhoto`: make sure `inSampleSize` really bounds the first decode to
    ≤ 2× the 2048 px target (log `decode_px=`), so a 12 MP photo is never fully decoded. (3) `GateProbes`: keep the
    logging; only the Rec button moves behind `slot.debug`.
(g) **Nothing else changes**: anchor gate (10 s settle), one anchor per session, PAUSED freezes, planeCoords, the
    billboard maths. No new dependency.

## Tests required
None (AR host). The events, physics and metrics it drives are tested in :core (T12).
Tests not required: anything in androidApp.

## Steps
1. Read CLAUDE.md, the `ar-sceneview` skill, ArSlot.kt, CaptureHost.kt, BundledCharacter (ui/screens/CaptureScreen.kt),
   Surface.kt, CharacterEvent.kt, PhysicsParams.kt, and every file under androidApp/src/main/kotlin.
2. (a) → (e) → (c) → (d) → (b) → (f). Compile after each: `./gradlew :androidApp:compileDebugKotlin`.
3. Write the Result: files, decisions, and the Pixel checklist (numbered steps + a table to fill).

## Done when
- `./gradlew :androidApp:assembleDebug` succeeds (T13 may be mid-flight in shared/; if the failure is in shared/, say so).
- Pixel checklist written (the human runs it): table mode as before; floor mode: character ~24 cm, walks 20 cm/s,
  taps on the floor accepted; Paused scrim on camera cover; bubble on place/blocked; a bundled character with its
  own sprite on the table; Export -> file visible in Files > Downloads > Rudeus.

## Result
