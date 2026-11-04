# T37 — ar-host-auto-scan (hand-review)

status: review (needs a Pixel run: ai/day10-human.md §3; review fixes 2026-11-04: first level on the button via firstLevelScanned, switch-off drops a pending quiet scan, a joined button press is recorded as manual)
created: 2026-11-04
day: Day 10 (submission day)
gate: on the Pixel, after the button scan, walking to a new side of the table produces one `auto_scan` line, no overlay,
no speech, and objects on the far side stay in the level; `ArSlot.autoScan = false` gives today's behaviour exactly
cut-if-slips: the HUD switch (T36) ships off by default; the host code stays behind it

## Goal
The AR host feeds `AutoScanPolicy` (T35) once per anchored frame and, when it returns a sector, runs a **quiet** scan:
the same grab → SAM → `TableScan.build` path as the button, but no `onScanned` (no overlay, no lines, no error toast),
`onLevel` still fires, the metrics record it as `background`. The merge (T34) gets the characters' positions so the
object under a standing character never changes. Every edit is in the hand-review zone: minimal diff, `// HAND-REVIEW:`
comments, the commit suffix `(hand-review)`. Load the `hand-review` and `ar-sceneview` skills first.

## Owns
- androidApp/src/main/kotlin/kz/rudy/ar/ar/RudeusArView.kt
- androidApp/src/main/kotlin/kz/rudy/ar/scan/ScanCapture.kt (`runTableScan` gains `standing`)
- androidApp/src/main/kotlin/kz/rudy/ar/scan/TableScanner.kt (`recordScan` gains `background`)
- androidApp/src/main/kotlin/kz/rudy/ar/di/AndroidCaptureHost.kt (`recordScan` passes `background` through)
Not yours: `:core`, `:shared`, Gradle files, docs.

## Contract you code against (being implemented concurrently by T34/T35/T36 — read their briefs in ai/tasks/)
- T34: `TableScan.build(input, failedPrompts, previous, profile, standing: List<Vec2> = emptyList()): Result`;
  `Result` adds `removed`, `replaced`, `kept` (`List<String>` of ids).
- T35: `kz.rudy.ar.core.pipeline.AutoScanPolicy` with `onFrame(nowMs, cameraPose: Pose3, plane: PlaneFrame, centre: Vec2,
  tracking: Boolean, busy: Boolean): Int?`, `markScanned(cameraUv: Vec2, nowMs: Long, centre: Vec2 = Vec2.ZERO)`,
  `reset()`; `ScanSectors.sectorOf`. `MetricsLog.recordScan(objects, ms, withinCm, background: Boolean = false)`.
- T36: `ArSlot.autoScan: Boolean` (default true).
If a symbol is not there yet when you compile, write against the brief's signature and say so in the final message; the
orchestrator runs the full build after all four land.

## Host behaviour
1. `TableScanner.recordScan(objects: Int, ms: Long, background: Boolean = false)`; `AndroidCaptureHost` forwards it to
   `metrics.recordScan(objects, ms, withinCm = emptyList(), background = background)`. `scans++` counts both.
2. `runTableScan(..., ref, standing: List<Vec2> = emptyList())` passes `standing` to `TableScan.build`; log one line
   `scan_merge removed=[..] replaced=[..] kept=[..] carried=[..] ref=..` next to the existing `scan_ms` line.
3. `RudeusArView`:
   - `val policy = remember { AutoScanPolicy() }`; `policy.reset()` where the anchor is created (`placeAnchor`).
   - A plain holder `scanQuiet = booleanArrayOf(false)` beside `scanPending`.
   - In `onSessionUpdated`, anchored branch, AFTER the existing `if (scanPending[0]) scanPending[0] = startScan(...)`:
     when `currentSlot.autoScan && scanner != null && scanCount[0] > 0 && !scanPending[0] && !scanBusy[0]`, call
     `policy.onFrame(now, frame.camera.pose.toPose3(), PlaneFrame.fromAnchorPose(a.pose.toPose3()), Vec2.ZERO, tracking, busy = false)`;
     a non-null sector → `Log.i(GATE_TAG, "auto_scan sector=$s fired=${policy.fired}")`, `scanQuiet[0] = true`,
     `scanPending[0] = true`, `scanTries[0] = 0`. (The scan then runs on the NEXT frame through the existing pending
     path, so the grab-retry machinery is reused; `scanCount[0] > 0` keeps the first scan on the button.)
     While `!currentSlot.autoScan` the policy is not fed (it just waits).
   - `startScan(frame, a, tracking, quiet: Boolean = scanQuiet[0])`: on the quiet path never increment `scanOwed`, and the
     early exits (`scan_skipped`, `scan_too_steep where=camera`, `no_image`) only log and return false without
     `answerOwed`. Keep the existing `finally { if (isActive) answerOwed(answer) }`: it answers nothing when nothing is
     owed, and a button request that joined a quiet scan still gets its answer. Clear `scanQuiet[0]` whenever
     `scanPending` goes false. Ref `"auto-NN"` for quiet scans (same counter), `host.recordScan(n, ms, background = quiet)`.
   - On a successful **button** scan launch (non-quiet, right after the grab) call
     `policy.markScanned(planeFrame.toPlane(grab.cameraPose.position), now)`.
   - `standing` for `runTableScan`: `loop.cast.members.map { it.body.state.pos }.ifEmpty { listOf(loop.state.pos) }`,
     read on the main thread at grab time (same source the boundary fold uses).
   - Keep the header comment block up to date: add a `HAND-REVIEW: T37` paragraph; amend the "scan stays a button" line
     in the locked-decisions comment to the new rule (first scan on the button; quiet scans behind `slot.autoScan`).
4. Nothing else changes: PAUSED, one anchor, tap-to-walk, the boundary fold after a scan (`SurfaceBoundary.afterScan`)
   runs for quiet scans too.

## Tests required
- None in `:androidApp` (policy and merge are tested in `:core`). The gate above is the Pixel check (human).
Tests not required: anything Compose/AR.

## Steps
1. Load `hand-review` + `ar-sceneview`. Read the T04/T25 scan paths in `RudeusArView.kt` (lines ~340–370, ~430–500,
   ~660–720), `ScanCapture.kt`, `TableScanner.kt`, `AndroidCaptureHost.recordScan`.
2. Make the four edits; keep the diff minimal and every new block marked `// HAND-REVIEW: T37`.
3. Build: `ANDROID_HOME=/Users/kuanyshsalyk/Library/Android/sdk ./gradlew :androidApp:assembleDebug --console=plain -q`
   (worktree: no SDK location file; never read or copy `local.properties`). If `:core`/`:shared` symbols from T34–T36
   are missing, stop there and report which — do not stub them.

## Done when
- The diff compiles once T34–T36 are in; `git diff --stat` touches only the four files under Owns
- Final message: the diff summary, what to look for on the Pixel (logcat keys: `auto_scan`, `scan_merge`, `scan_ms ... ref=auto-`),
  and anything that deviates from the contract
