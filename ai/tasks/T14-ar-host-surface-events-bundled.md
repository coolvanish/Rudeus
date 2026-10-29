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
status: draft for hand-review (compiles, `:androidApp:assembleDebug` green on 2026-10-29; not yet run on the Pixel)

### Files (all hand-review zone; every non-obvious choice carries a `// HAND-REVIEW:` comment)
- `androidApp/src/main/kotlin/kz/rudy/ar/ar/RudeusArView.kt` — (a) `RudeusArView(modifier, slot: ArSlot)`; surface
  locked at anchor time; callbacks via `rememberUpdatedState`, edge-triggered from a plain `SlotState` holder;
  (b) `key(sprite)` around the PlaneNode + `DisposableEffect(sprite)` destroy; hint by surface; Rec button behind `slot.debug`.
- `androidApp/src/main/kotlin/kz/rudy/ar/ar/GameLoop.kt` — `configure(SurfaceProfile)` (params + radius),
  `Physics.step(..., params)`, `onFrame(now, tracking, onEvent)` reports `CharacterEvents.between` (`// T12:` note).
- `androidApp/src/main/kotlin/kz/rudy/ar/ar/PlaneSettle.kt` — `anySettled(now)`.
- `androidApp/src/main/kotlin/kz/rudy/ar/ar/CharacterNode.kt` — height is a parameter; `CharacterSprite.destroy(engine, materialLoader)`.
- `androidApp/src/main/kotlin/kz/rudy/ar/MainActivity.kt` — passes the slot; (e) `PermissionScreen` replaces the Material button.
- `androidApp/src/main/kotlin/kz/rudy/ar/di/AndroidCaptureHost.kt` — (c) `bundled()`, (d) `latestRun()` / `exportRun()`.
- `androidApp/src/main/kotlin/kz/rudy/ar/capture/AndroidImages.kt` — (f)(1) HEIF via ImageDecoder, (f)(2) decode bound + `decode_px=` log.
- `androidApp/src/main/assets/bundled/**` — new: `index.json` + 16 × `{sprite.png, sheet.json}` copied from `docs/bundled/`.
- Untouched: every `*.gradle.kts`, `AndroidManifest.xml` (export needs no permission on API ≥ 29; below it uses the app's
  external files dir), `core/**`, `shared/**`, `GateProbes.kt` (logging kept; only the button moved behind `slot.debug`).
  No new dependency.

### Decisions
1. **Surface lock.** `SlotState.surface` follows `slot.surface` until the anchor exists, then it is frozen; a later
   request is logged once per value (`surface_change_ignored`) and ignored. Sprite height, `PhysicsParams.forProfile`,
   the body radius and the walk-tap tolerance all read the locked profile, so table and floor can never mix mid-game.
2. **Events.** `GameLoop.onFrame` diffs against the body it *last reported*, not the previous frame, so the
   `STARTED_WALKING` produced by `walkTo` in the tap handler is reported by the next frame instead of being lost.
   Pause/resume transitions are included (PAUSED / RESUMED events reach the HUD too). The `CharacterState` overload is
   used; T12's `PhysicsBody` overload (BLOCKED) is a one-line swap marked `// T12:`.
3. **Sprite lifetime (b).** Owner check in the 4.31 sources: `MaterialLoader.destroyMaterialInstance` (MaterialLoader.kt:541)
   removes the instance from the loader's list before `engine.destroyMaterialInstance`, so there is no double free at
   scene disposal; the Texture is ours (`engine.safeDestroyTexture`, SceneView's runCatching wrapper). The node is
   `key(sprite)`-ed instead of swapping the material on a live node: SceneView composes `content` inline (one
   composition), and Compose forgets deeper slots first, so the old node is detached from the Filament scene before the
   old material/texture are destroyed (SceneView's own SIGABRT note in SceneScope.kt:129). At full teardown the order is
   node → sprite → material loader → engine (declaration order reversed).
4. **Bundled (c).** `index.json` via org.json (ref, sprite, sheet, gatePassed); each `sheet.json` via :core's
   `SheetParser.parse` (same normalisation as the pipeline; `toy_fox` is `HIDE`, a real `Ability`, so it parses as
   such). Gate-passed first, then the rest, index order within each group (11 passed, 5 not).
   Missing/unreadable files skip the character with a `bundled skipped ref=` line; a broken index yields an empty list.
   Loaded synchronously on the first `bundled()` call (composition, main thread, ~7.6 MB once) and cached in a `lazy`.
5. **Metrics (d).** `latestRun()` = `metrics.toRun()` once `characters > 0` (started, including failed). `exportRun()`
   writes `metrics.toJson()` (kotlinx.serialization is not on :androidApp's classpath and no dependency was added; it is
   the same run). API ≥ 29: `MediaStore.Downloads` row with `RELATIVE_PATH = Download/Rudeus`, `IS_PENDING` 1 → write →
   0; a failed write deletes the pending row; returns `Download/Rudeus/<runId>.json`. A second export of the same run
   gets a MediaStore-unique name (`<runId> (1).json`) — fine for the demo. API 24–28: `getExternalFilesDir(null)/runs/`.
6. **HEIF (f)(1) — deviates from the brief's EXIF route, on purpose.** The platform `ExifInterface(InputStream)`
   cannot seek inside a HEIF container (it wraps the stream in a 5 KB-mark `BufferedInputStream`; the seek back fails
   and no tags are read), and a HEIF's authoritative rotation is the ISO-BMFF `irot` box, not EXIF — Android's camera
   HEIC writer stores the rotation there. So HEIF (`ftyp` at offset 4) is decoded with `ImageDecoder` (API 28+,
   `ALLOCATOR_SOFTWARE`, `setTargetSampleSize` = the same power of two), which applies the container rotation itself,
   and `exifOrientation` returns 1 for HEIF so nothing is rotated twice. Below API 28 Android cannot decode HEIF at all
   (null, as before). Only the gallery picker can deliver HEIC; the system camera returns JPEG.
7. **Decode bound (f)(2).** `sampleFor` now accepts a decode up to 25 % *under* the 2048 target when that halves the
   decode: a 4080 px Pixel photo decodes at 2040 px (6 MP, ~25 MB) instead of in full (12.5 MP, ~50 MB); worst case is
   now < 3072 px (~7 MP) instead of < 4096 px (16 MP). Output is ≥ 1536 px on the long side; SAM masks at that size
   are fine and the sprite's quality comes from Muse, not the photo. `decode_px=WxH photo_px=WxH heif=` is logged.

### 7.6 MB asset note
`androidApp/src/main/assets/bundled/` adds 7.6 MB (16 sprites, 180–800 KB each) to the APK; debug APK is now 61 MB.
It is the Day 6 offline mode ("never cut"), so it stays. If size ever matters: drop the 5 gate-failed sprites (~2 MB)
or PNG-quantize; both are a one-line change in the copy step, not code.

### Open risks (for the reviewer / the Pixel)
- ImageDecoder orientation for HEIC is verified from the platform sources' behaviour, not on the device yet — Pixel
  checklist step 9 is the proof. If a HEIC shows rotated, flip back to the ExifInterface-via-temp-file route.
- `onCharacterEvent` fires on the AR frame thread (main) up to once per transition; the HUD must be cheap (contract).
- The first `bundled()` call blocks the main thread for the asset read (tens of ms, once).
- `slot.debug` hides the Rec button; the Day 1 gate recordings now need the debug toggle on.
- T12/T13 landing: `GameLoop.onFrame` → swap to the `PhysicsBody` overload for BLOCKED; nothing else depends on them.

### Pixel checklist (human; `scripts/device.sh log` for tag RudeusGate)
1. Fresh install → Scanning: the paper `PermissionScreen` (not a bare button) appears; Allow → camera starts.
2. Table mode: point at the table ≥ 10 s; the HUD gets `onSurfaceReady(true)` (chips/hint enabled) before the first tap
   is accepted; a tap before that shows the "finding the table" pill.
3. Tap: anchor placed, `anchor_plane_age_ms=... surface=TABLE` in the log, the HUD gets `onAnchorPlaced` + PLACED bubble;
   dino is ~6 cm; tap elsewhere → walks ~5 cm/s, STARTED_WALKING / ARRIVED bubbles.
4. Cover the camera 3 s: Paused scrim (onTracking false) and the character freezes; uncover: scrim gone, character
   reappears ≤ 1 s later at the same spot, continues the walk if one was pending. Never resets.
5. Floor mode (restart the app, pick Floor on Scanning): the "finding the floor" pill; character ~24 cm tall, walks
   ~20 cm/s; taps on the floor up to ~12 cm height difference accepted; a tap on a table top from floor mode rejected.
6. Surface chip after the anchor (if the HUD still shows it): no change in size/speed, one `surface_change_ignored` line.
7. Capture → pick a bundled character (one with gatePassed first in the list) → Scanning → place: its own sprite is on
   the table, not the dino; its name/lines in the HUD bubbles.
8. Make a character (camera photo, JPEG): `decode_px=2040x1536`-ish and `photo_px=4080x3072`-ish in the log; the sprite
   shows and `capture_to_play_ms` < 60 000.
9. Gallery → pick a HEIC taken in portrait: `heif=true` in the log, the Processing preview is upright (not sideways,
   not upside down); then the sprite is upright too.
10. Metrics screen → Export: toast/path `Download/Rudeus/<runId>.json`; Files app → Downloads → Rudeus shows the file,
    its JSON opens and `summary.n` matches the number of characters made this session.
11. Debug toggle on → the Rec button appears at the right edge; off → gone.

| Step | Expected | Observed on Pixel | OK? |
|---|---|---|---|
| 1 | PermissionScreen, camera after Allow | | |
| 2 | onSurfaceReady after ~10 s, pill before | | |
| 3 | PLACED, ~6 cm, 5 cm/s, bubbles | | |
| 4 | Paused scrim, freeze, no reset | | |
| 5 | ~24 cm, 20 cm/s, floor taps accepted | | |
| 6 | surface change ignored (log once) | | |
| 7 | bundled sprite + name | | |
| 8 | decode_px ≤ 3072, capture→Play < 60 s | | |
| 9 | HEIC upright, heif=true | | |
| 10 | file in Downloads/Rudeus, n matches | | |
| 11 | Rec button only in debug | | |
