# T13 — ui-ux-one-ar-session

status: review
created: 2026-10-29
day: Day 3 (UI/UX + the open T02/T03 items)
gate: Day 3 — 60 s walking without drift (the anchor must survive Scanning -> Play); capture -> character < 60 s
cut-if-slips: polish items (g) first; never the single AR slot (a) or the Paused wiring (d)

## Goal
The app feels like one product for a child, not a demo shell: one AR session from "find the table" to play, the
character talks when something happens, bundled characters are real (with pictures), the floor/outdoors is a choice,
tracking loss shows "Paused", the Metrics screen shows this phone's real run and Export really saves. Everything
visible follows DESIGN.md; where this brief changes behaviour, DESIGN.md is updated in the same change.

## Owns
- shared/** (App.kt, CaptureHost.kt, ArSlot.kt, ui/**, commonTest/**)
- DESIGN.md (sections (d) Screens and (e) Copy table)
Not yours: core/**, androidApp/** (T14 implements the host side of the same contract in parallel — the contract in
`ArSlot.kt` / `CaptureHost.kt` is frozen; add fields only with defaults and say so in the Result), cli/**.

## Contract
(a) **One AR slot across Scanning and Play.** `App` composes `arView(Modifier.fillMaxSize(), slot)` exactly once, in a
    `Box` underneath, for as long as `screen` is SCANNING or PLAY (same call site, not inside the `when`), and passes
    `arView = {}` (no-op) to `ScanningScreen`/`PlayScreen`. Leaving to CAPTURE/METRICS disposes it (new session next
    time; Back from Play to Metrics must NOT dispose it: keep the slot alive while screen ∈ {SCANNING, PLAY, METRICS-from-PLAY}
    is not required — simplest honest rule: Metrics from Play keeps it alive by drawing Metrics on top, if that is a
    small change; otherwise document that Metrics ends the AR session).
    The `ArSlot` passed down is remembered with stable callbacks (`rememberUpdatedState`-style) so changing a HUD
    state does not re-create the slot's identity needlessly; `spritePng`/`surface` changes do create a new slot.
(b) **Scanning flow.** `ScanState` gains `surfaceReady: Boolean`, `anchored: Boolean`, `surface: Surface`.
    - not ready: pills "Look at the table" (or "Look at the floor") + "Move the phone slowly"; Scan disabled.
    - ready, not anchored: one pill "Tap the table — it stands there" (floor variant); Scan disabled.
    - anchored: title pills gone; Scan enabled (fake scan stays until T04 — but honest: `objectsFound = 0`, the Done
      banner shows only "Table ready!" and no count when 0).
    - `Surface` chips (Table | Floor) top-centre, visible until anchored; stored in App state and sent in the slot.
      Floor copy for the no-plane banner mentions daylight/grass/pavement (outdoors).
    - Back resets `scan` and `surface` stays as chosen.
(c) **Speech from events.** New pure `ui/screens/HudSpeech.kt`: `HudSpeech.lineFor(event: CharacterEvent, lines: List<String>, lang)`
    + a policy `HudSpeech.next(state, event, nowMs): HudSpeech.State` with: PLACED -> line 0; BLOCKED -> `StringId.blocked`;
    FELL -> `fell`; RESPAWNED -> `respawn`; JUMPED -> line 1; ARRIVED -> every 3rd arrival a random-but-deterministic
    sheet line (index = arrivals % lines.size); STARTED_WALKING/LANDED/STEPPED_UP/PAUSED/RESUMED -> nothing.
    Minimum 2500 ms between bubbles; FELL and RESPAWNED always replace. App feeds `slot.onCharacterEvent` into it.
(d) **Paused.** `slot.onTracking(false)` -> `hud.trackingLost = true` (PausedOverlay) and `scan.status = PAUSED` while on
    Scanning; `true` clears both. The "Tap — it walks" pill shows only after `onAnchorPlaced` and hides 5 s after the
    first STARTED_WALKING.
(e) **Capture.** `bundled = capture?.bundled()?.takeIf { it.isNotEmpty() } ?: DemoData.bundled`. `CharacterCard` draws
    `spritePng` when present via `org.jetbrains.compose.resources.decodeToImageBitmap()` (commonMain, already a
    dependency; confirm the API on Context7 for CMP 1.12), else the host `thumbnail`, else the glyph. Picking a bundled
    character sets `sheet` and `spritePng = c.spritePng` (null keeps the host's dino). Selected card = Sun outline.
    Processing "Done!" shows the result sprite at 160 dp with the crayon outline (DESIGN.md already asks for it).
(f) **Metrics.** `MetricsScreen(run: MetricsRun?, …)` takes core's `kz.rudy.ar.core.metrics.MetricsRun` directly;
    delete `MetricsRunView` (map DemoData's demo run to a `MetricsRun` for the host-less shell). `run = capture?.latestRun()`
    read when the screen opens. Export: `capture.exportRun()` in a coroutine -> `ExportStatus.SAVED` with the returned
    location shown under the toast (mono), or `FAILED` with Retry. Without a host Export is disabled.
(g) **Polish pass against DESIGN.md** (only what is visibly off; do not redesign): every tappable ≥ 56 dp; no text
    on the camera feed without a scrim; the Rescan button reads "Rescan" only after a scan happened, "Scan" before;
    language switch only on Capture; the Processing stage rows reflect real events (already); Metrics empty state when
    `run == null`. Remove the stale `TODO(core)` comments in ProcessingScreen/MetricsScreen. Keep the chrome quiet.
(h) **Strings** (kk first): `scan_title_floor`, `scan_tap_to_place`, `scan_tap_to_place_floor`, `scan_no_plane_hint_floor`,
    `surface_table`, `surface_floor`, `ar_wait_floor`, `metrics_export_where` (prefix for the location), and any you need.
    Add each to DESIGN.md's copy table too (ids match `StringId` one-to-one; there is a diff-able invariant).

## Tests required
- `HudSpeechTest` (commonTest): event -> line mapping, the 2500 ms gap, FELL overrides, every-3rd-arrival index.
- `ProcessingEventsTest` keeps passing. Existing `shared` host tests: 7 before.
Tests not required: screens, the AR slot, Compose layout.

## Steps
1. Read CLAUDE.md, DESIGN.md, App.kt, ArSlot.kt, CaptureHost.kt, all of ui/screens and ui/components, Strings.kt.
2. (a) first — it is the Day 3 gate fix — then (d), (b), (c), (e), (f), (g), (h); keep each step compiling:
   `./gradlew :shared:compileAndroidMain :shared:testAndroidHostTest`.
3. Update DESIGN.md (d)/(e) for what changed. Report the StringIds you added and any ArSlot/CaptureHost field you added.

## Done when
- `./gradlew :shared:testAndroidHostTest` green; `:androidApp:compileDebugKotlin` still compiles (T14 may be mid-flight;
  if it fails only inside androidApp/, say so — the orchestrator integrates).
- Walking through the code: Scanning -> Play never recomposes the slot into a different parent.

## Result

### What was built (2026-10-29)
Files (all under `shared/src/` plus `DESIGN.md`):
- `commonMain/kotlin/kz/rudy/ar/App.kt`: (a) one `arView(Modifier.fillMaxSize(), slot)` call site in a root `Box`, before
  the `when`, guarded by `arOn = screen in {SCANNING, PLAY} || (METRICS && lastScreen == PLAY)`. Scanning/Play get a
  no-op `arView`. Metrics from Play is drawn on top (paper, `swallowTouches()` so taps don't walk the character).
  Leaving the AR (`arOn` false) resets scan/hud/speech (new session next time; surface kept). The slot is
  `remember(spritePng, surface, debug)`; its four callbacks go through `rememberUpdatedState` handlers.
  (d) tracking -> `hud.trackingLost` and `scan.status = PAUSED` on Scanning (true -> back to IDLE); tap hint on after
  `onAnchorPlaced`, off 5 s after the first STARTED_WALKING. (c) `onCharacterEvent` -> `HudSpeech.next`, bubble
  dismissed after 2500 ms keyed on `speech.serial`. (e) `bundled = remember(capture) { capture?.bundled()?.takeIf { it.isNotEmpty() } ?: DemoData.bundled }`,
  picking sets `sheet`, `spritePng = c.spritePng`, `selectedId`. (f) `metricsRun = capture?.latestRun()` on open
  (DemoData run for no host), Export via `rememberCoroutineScope` -> SAVING -> SAVED + location / FAILED.
  `playEntered()` only when Play is entered from Scanning (not on Back from Metrics). Fake scan: `objectsFound = 0`.
  `DemoData.metrics` is now a core `MetricsRun` (summary from `MetricsLog.summaryOf`).
- `ui/screens/HudSpeech.kt` (new, pure): `lineFor(event, lines, lang, arrivals = 0)`, `next(state, event, nowMs, lines, lang)`,
  `say(state, text, nowMs)` (ability chip), `dismissed(state)`; `State(text, shownAtMs, arrivals, serial)`.
  Deviation from the brief: `next` also takes `lines, lang` (it needs them to pick the line; language can't change
  during play).
- `ui/screens/ScanningScreen.kt`: `ScanState` + `surfaceReady`, `anchored`, `surface`; Table|Floor chips until anchored;
  pill flow (look -> tap to place -> none); Scan enabled only when anchored; floor variants of title/no-plane/done/paused;
  Done banner shows the count only when > 0; new params `onSurfaceChange: (Surface) -> Unit = {}`, `speech: String? = null`
  (the PLACED greeting happens on this screen).
- `ui/screens/PlayScreen.kt`: `HudState.showTapHint` default now false; + `scanned` (button reads Scan until a scan ran,
  then Rescan), + `surface` (Paused hint).
- `ui/screens/CaptureScreen.kt`: `CharacterCard` draws `spritePng` (decoded off-main) > `thumbnail` > glyph.
- `ui/screens/ProcessingScreen.kt`: new param `sprite: ByteArray? = null`, shown at 160 dp with crayon outline on
  Done / Not passed; middle content scrolls so the buttons never fall off a short screen; stale TODO(core) removed.
- `ui/screens/MetricsScreen.kt`: `MetricsScreen(run: MetricsRun?, exportStatus, onExportJson, onBack, modifier,
  exportLocation: String? = null, canExport: Boolean = true)`; `MetricsRunView` deleted; `ExportStatus.SAVING` added;
  location shown under "Saved" in mono; tiles only when `summary.n > 0`; stale TODO(core) removed.
- `ui/components/SpriteImage.kt` (new): `rememberSpriteBitmap(bytes)` (`org.jetbrains.compose.resources.decodeToImageBitmap()`
  on `Dispatchers.Default`, null on bad bytes) + `SpriteImage(bitmap, …)`. API confirmed in the CMP 1.12.2
  components-resources sources jar (`commonMain/.../ImageDecoders.kt`: `fun ByteArray.decodeToImageBitmap(): ImageBitmap`);
  Context7 only had the skiko actual.
- `ui/components/StatusBanner.kt`: `PausedOverlay(…, hint: StringId = StringId.paused_hint)`.
- `ui/components/Chips.kt`: language switch items 48 -> 56 dp min height (tap targets ≥ 56 dp).
- `ui/Strings.kt` + `DESIGN.md` (d) screens / (e) copy table / (f) exclamation list.
- `commonTest/kotlin/kz/rudy/ar/HudSpeechTest.kt` (new).

StringIds added (11): `scan_title_floor`, `scan_no_plane_title_floor`, `scan_no_plane_hint_floor`, `scan_tap_to_place`,
`scan_tap_to_place_floor`, `scan_done_floor`, `surface_table`, `surface_floor`, `ar_wait_floor`, `paused_hint_floor`,
`metrics_export_where`. The DESIGN.md copy table also gained the 6 ids that existed in code but not in the table
(`capture_pick_gallery`, `processing_pick_ready`, `ar_wait_table`, `camera_permission_title/hint/button`); a script
diff of table vs `StringId` (ids and all three texts) is now empty.

Contract additions: **none** to `ArSlot` or `CaptureHost`.

Tests: `:shared:testAndroidHostTest` 11 tests, 0 failures, 0 errors (before 7): HudSpeechTest 4 (event mapping,
2500 ms gap, FELL/RESPAWNED override, every-3rd-arrival index), ProcessingEventsTest 5, SharedCommonTest 1,
SharedLogicAndroidHostTest 1. `:shared:compileAndroidMain` clean.

`:androidApp:compileDebugKotlin` currently fails only inside T14's in-flight code:
`androidApp/.../ar/RudeusArView.kt:98` — `spriteFromBytes(...)` "No value passed for parameter 'facesRight'" and
"Boolean but Float expected". No androidApp call site of a shared API changed by T13 (androidApp uses `App(arView, capture)`,
`StringId`, `t`, `ScrimPill`, `PermissionScreen`, `BundledCharacter(...)` — all unchanged).

Walking the code for "Done when": `arView` is called at exactly one place in `App` (the `if (arOn)` before `when`), and
`arOn` stays true across SCANNING -> PLAY and PLAY <-> METRICS, so the slot's group never moves parent.

### Risks / notes
- Scan stays disabled until the host calls `onAnchorPlaced`. If T14 does not fire it, the child is stuck on Scanning
  (Back still works). That is the contract, but it is the first thing to check on the Pixel.
- `capture.bundled()` runs synchronously on the first Capture composition (T14 loads + caches the 7.6 MB of assets
  on that first call): a one-time startup hitch is possible.
- Bundled sprites are decoded full-size for the 96 dp cards; fine for a handful, revisit if the row grows.

### Pixel checklist for a human
1. Capture shows the bundled cards with their own sprites; tap one -> Sun outline, Scanning opens.
2. Scanning: chips Table|Floor top-centre; "Look at the table" + "Move the phone slowly"; Scan disabled.
   Switch to Floor -> the copy says floor. Back -> Capture -> Scanning: the choice is kept.
3. When the plane settles: one pill "Tap the table — it stands there". Tap -> character appears, greeting bubble,
   chips and pills disappear, Scan enabled.
4. Scan -> looking-around 4 s -> "Table ready!" with no object count -> Play. **The character is still in the same
   spot (same anchor, no new session, no camera flicker).**
5. Play: "Tap — it walks" pill visible; tap to walk; pill goes 5 s after the first walk. Button reads "Rescan".
   Walk into a cup -> "Blocked!"; off the edge -> "Oops! Fell" then "Back up"; bubbles never closer than ~2.5 s.
6. Cover the camera -> full Paused scrim (floor mode: "…at the floor"); uncover -> it leaves, nothing reset.
   Do the same during a scan on Scanning -> scan cancelled, Scan available again after tracking returns.
7. Play -> Metrics -> Back: the character is still there (AR kept alive). Taps on Metrics don't move the character.
8. Metrics shows this phone's run after one capture; Export -> "Saved" + "Where: Download/Rudeus/<runId>.json";
   check the file in Files > Downloads > Rudeus. Before any capture: "Nothing yet", Export disabled.
9. Capture a drawing: Processing "Done!" shows the sprite at 160 dp; Next -> Scanning.
10. Language switch: 56 dp buttons, KK/RU/EN on Capture only.
