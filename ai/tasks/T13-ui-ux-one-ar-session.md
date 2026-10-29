# T13 — ui-ux-one-ar-session

status: doing
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
