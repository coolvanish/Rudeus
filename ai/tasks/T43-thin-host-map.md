# T43 — thin-host-map

status: todo
created: 2026-11-11
phase: v2 wave 1 (ai/build-plan-v2.md, "iOS readiness")
gate: G1 foundation — the map decides what T47 moves and what T51 (Swift host) must implement
cut-if-slips: never cut; if it runs long, deliver the per-file table and step 1 only

## Goal
A design, not code. `:androidApp` holds about 3 000 lines; part of it is AR glue that must stay per-platform, part is
logic an iOS host would have to rewrite in Swift. This brief reads the host and writes down, file by file, which is
which, and turns the result into the next brief (`T47`) so that after it the Android host is an adapter over common
code and an iOS host is the same adapter written once.

## Owns (exact paths)
- ai/tasks/T47-thin-host-step-1.md (new: a complete brief in the TEMPLATE.md shape, ready to dispatch)
- ai/tasks/T43-thin-host-map.md (the Result section: the per-file table)

Read-only everywhere else. No `.kt`, no Gradle.

## What to read
- androidApp/src/main/kotlin/kz/rudy/ar/**  (RudeusArView 929, AndroidCaptureHost 530, ScanCapture 296, AndroidImages 263,
  CharacterNode 252, GameLoop 243, PhotoIntake 170, GateProbes, CastTouch, PlaneSettle, PlaneCoords, Occluders,
  TableScanner, FootprintOverlay, Sounds, VoiceRecorder, MainActivity)
- shared/src/commonMain/kotlin/kz/rudy/ar/ArSlot.kt, CaptureHost.kt, VoiceHost.kt, App.kt (how the slot is driven)
- core: `physics/Cast.kt`, `emote/EmotePlayer.kt`, `pipeline/TableScan.kt`, `pipeline/AutoScan.kt`, `scenario/*`, `io/*`
- ai/build-plan-v2.md, CLAUDE.md (module boundaries), .claude/skills/ar-sceneview/SKILL.md, .claude/skills/hand-review/SKILL.md

## Contract of the output
1. **Per-file table** (in this brief's Result): file · lines · AR/Android-bound lines (ARCore, SceneView, Filament,
   Android SDK types) · platform-free lines (state machines, timing, maths on `:core` types, orchestration of
   `:core` calls, retry/buffer logic) · where the platform-free part should live (`:core` package or `:shared`) · risk
   of moving it without the Pixel (low / medium / high) with one line of why.
2. **The seam**: the plain-data ports a host implements and the common engine drives, e.g. (names are yours to fix)
   `FrameInput(dtMs, tracking, cameraPosePlane, taps as plane hits)` in, `NodePose(id, u, v, h, yawDeg, tiltDeg,
   scaleX, scaleY, picture key, mirrored)` + sounds + HUD events out. No ARCore/SceneView/UIKit/RealityKit type crosses
   it; `:core` stays in plane coordinates; one anchor per session; PAUSED freezes physics. Say what `ArSlot` keeps and
   what moves behind the engine. The duel (ai/tasks/T42, T45) and the sprite views (T41: `ViewPicker` needs the camera
   forward/right in the plane) must fit the same seam without another host change.
3. **Migration order**: behaviour-preserving steps, smallest first, each with the JVM tests that pin today's behaviour
   before the move and the single thing a human checks on the Pixel after it. Step 1 becomes `T47`.
4. **What stays per-platform for good** (the list the Swift host must implement), and the `expect/actual` leaf
   services worth having (image decode/encode, file roots, audio, haptics, identity hash): only ones with a real
   implementation on both sides, no stubs.
5. **iOS blockers in `:shared` commonMain**: every JVM/Android-only API used there today (`System.identityHashCode`
   in ArSlot.kt is one), with the common replacement. This is input for T46.

Rules: quote file:line for every claim about what the code does; do not guess a SceneView or ARCore API (Context7:
`/sceneview/sceneview`, `/websites/developers_google_ar`); if a piece cannot be judged without running it, say so.

## Done when
- The table covers every file under `androidApp/src/main/kotlin`
- `ai/tasks/T47-thin-host-step-1.md` names exact files, the contract with signatures, the tests, and the Pixel check
- Nothing but the two owned files changed

## Result (filled on acceptance)
- per-file table: ...
