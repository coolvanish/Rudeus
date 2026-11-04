# T36 — auto-scan-toggle-and-docs

status: done (JVM)
created: 2026-11-04
day: Day 10 (submission day)
gate: the player can switch background scans off in the HUD; CLAUDE.md, build-plan, README say what the app now does
cut-if-slips: the toggle stays, defaulting to off, and the docs describe it as off by default

## Goal
Background scans (T35/T37) send camera frames to Meta without a button press, and the player is a child. The HUD gets a
switch "auto scan" (kk first, then ru, en), **on by default**, carried to the AR host through `ArSlot.autoScan`. The
locked decision "scan = explicit button, never per-frame" changes: the FIRST scan stays a button; after it the host may
run at most four quiet scans per session when the camera settles in a new sector, 8 s apart, never per frame. The
documents that record the decision are updated, and README gets a short privacy paragraph and a metrics note.

## Owns
- shared/src/commonMain/kotlin/kz/rudy/ar/ArSlot.kt (`autoScan` field + KDoc)
- shared/src/commonMain/kotlin/kz/rudy/ar/App.kt (state, plumbing into the slot, reset with the session like `scanRequest`)
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/Strings.kt, ui/screens/SettingsSheet.kt and/or PlayScreen.kt (the switch,
  next to the sounds / footprints switches, per DESIGN.md), ui/components/* if a shared switch row exists
- CLAUDE.md (line 18), ai/build-plan.md (the locked-decision line "Scan = explicit button…"), README.md,
  docs/architecture.md (scan section, if the file exists)
Not yours: anything in `:core` (T34/T35 own it) or `:androidApp` (T37). Do not add a persistence call to `CaptureHost`
(the host interface is not in your Owns); the switch resets to on with each app start.

## Contract
```kotlin
// ArSlot.kt
/**
 * T36: background scans. True (default) lets the host scan on its own ONLY after the first button scan of the session,
 * when the camera first settles in a sector around the anchor it has not scanned yet (`AutoScanPolicy`: <= 4 per
 * session, >= 8 s apart, never steeper than 75°, never while another scan runs). A background scan shows no overlay and
 * never calls [onScanned]; [onLevel] still fires. False = today's behaviour: the button only. The HUD switch is the
 * player's privacy switch: frames leave the phone only when they press Scan.
 */
val autoScan: Boolean = true,
```
- The switch label, kk / ru / en, short for a child: e.g. kk «Өзі сканерлейді», ru «Авто-скан», en "Auto scan" (pick
  better wording if DESIGN.md's voice says so; keep every string under ~16 characters).
- `App.kt`: `var autoScan by remember { mutableStateOf(true) }`, passed into the `ArSlot` with the other HUD flags; the
  `remember` keys list that builds the slot must include it.

### Docs wording (edit, do not rewrite)
- CLAUDE.md line 18 → "Scan is an **explicit button** (3–5 s) for the first level; after it the host may run quiet
  background scans when the camera settles in a new sector (≤ 4 per session, 8 s apart, `ArSlot.autoScan`, HUD switch,
  on by default). Never per-frame. One anchor per session. `PAUSED` freezes physics, never resets."
- ai/build-plan.md locked decisions: same change on the "Scan = explicit button" line, with "(changed 2026-11-04, T34–T37)".
- README: under the scan / privacy area add a paragraph "Background scans": what fires them, the limits, that each sends
  one camera frame to Meta's SAM 3.1 like the button does, the cost (≈ $0.03 per scan at 11 prompts × $0.0025; ≈ $0.11
  per session for four), and the HUD switch. In the metrics section: `scans[].background = true` marks them and the
  README figures are over manual scans only (the `backgroundScans` count is separate). Do not invent measured numbers.
- docs/architecture.md: one paragraph in the scan section: merge rules (T34, in `TableScan`: out-of-frame and covered
  objects are carried; an empty, visible place is forgotten after two scans; a better view replaces a footprint; the
  object under a standing character never changes) and the policy (T35, `AutoScanPolicy`).

## Tests required
- None new. `./gradlew :shared:testAndroidHostTest --console=plain -q` must stay green (run it; other agents build in
  this worktree at the same time, Gradle serialises).
Tests not required: Compose UI tests for the switch.

## Steps
1. Read `ArSlot.kt`, `App.kt` (the slot construction around line 480–530 and the session reset around 525), `Strings.kt`,
   `SettingsSheet.kt`, `PlayScreen.kt`, DESIGN.md (switch rows), CLAUDE.md, the locked-decisions block of
   ai/build-plan.md, README.md.
2. Field + plumbing + switch + strings. 3. Docs. 4. The shared host test.

## Done when
- `./gradlew :shared:testAndroidHostTest` green; `git diff --stat` touches only the paths under Owns
- Final message: the exact field name/default, where the switch sits, the three strings, which doc lines changed
