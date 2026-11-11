# T43 — thin-host-map

status: done
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

## Result (filled 2026-11-11, read at 1b52cbb; design only, nothing was built or run)

Paths below are under `androidApp/src/main/kotlin/kz/rudy/ar/` unless they start with a module name. `file:line` is the
line in this commit.

### How to read the numbers
- **Lines** = `wc -l`. **Code** = lines that are not blank, not a comment, not `package` / `import`.
- **Bound** = code lines that name an ARCore / SceneView / Filament / Android SDK type or only exist to feed one.
  **Free** = code lines in ranges marked while reading as state machine, timing, maths on `:core` types, orchestration
  of `:core` calls or retry/buffer logic. The split inside a mixed function is a judgement, good to about +-10 %.
- Compose runtime (`remember`, `LaunchedEffect`) is not counted as bound: it runs on iOS, but it belongs in `:shared`.

### 1. Per-file table

| File | Lines | Code | Bound | Free | Where the free part goes | Risk of moving it without the Pixel |
|---|---|---|---|---|---|---|
| `ar/RudeusArView.kt` | 929 | 576 | 231 | 345 | `core/session` `TableEngine`: frame step 738-821, commands 414-444, scenario 446-458 + 776-795, cast sync 567-581, taps 850-878, grab test 617-651, lead 653-664, boundary growth 465-473. `core/session` `ScanSession`: 384-405, 480-546, 746-772. Slot edge triggers (334, 392, 410, 414, 446) -> `:shared` | mixed: commands and cast sync low; frame step medium; grab and lead medium-high (finger feel); pre-anchor 716-736 + 836-849 high (Pixel-tuned), leave it |
| `di/AndroidCaptureHost.kt` | 530 | 325 | 101 | 224 | `:core` common capture session: pipelines 218-284, run file 287-293, saved characters 305-326, scan metrics 333-356, voice orchestration 194-216, `DeferredModels` 517-530, bundled and style-ref loading 439-452 + 484-514 over `FixtureSource` | low-medium: no AR, but the JVM-only concurrency (107-112, 155, 289, 351) has to be re-expressed with `Mutex` |
| `scan/ScanCapture.kt` | 296 | 196 | 113 | 83 | `core/session`: `runTableScan` 156-211, `scaledTo` 222-227, replay log 135-148, `ScanGrab` 57-67 (becomes `ScanFrame`) | low-medium: orchestration of `:core` calls; needs the JPEG size from the encoder instead of `BitmapFactory` (167-170). The grab and the YUV copy (83-129, 233-296) stay |
| `capture/AndroidImages.kt` | 263 | 150 | 130 | 20 | stays: it is the image codec leaf. The pure bits (mask loop 75-80, `sampleFor` 172-177) are not worth a move on their own | high: pixel-exactness feeds the 0.7 gate (161-163) and `android.graphics` cannot run on the JVM |
| `ar/CharacterNode.kt` | 252 | 132 | 86 | 46 | `core/session` `BillboardPoser` -> `NodePose`: 201-250 minus the three node writes (207, 225, 250), constants 52-76 | medium: pure maths, but the result is only visible; quaternion order and bow sign were already fixed on the Pixel once (82-84, 221-224) |
| `ar/GameLoop.kt` | 243 | 120 | 0 | 120 | `core/session/GameLoop.kt`, verbatim (T47) | low: it imports only `:core` (3-18) |
| `capture/PhotoIntake.kt` | 170 | 103 | 88 | 15 | stays (ActivityResult launchers). Its buffered channels (62-65) already are the `CaptureHost.photos` contract | not moved |
| `ar/GateProbes.kt` | 137 | 79 | 79 | 0 | stays, Android-only debug (its header asks for its own deletion, 23-25). `GATE_TAG` (28) is used by every file | not moved |
| `voice/VoiceRecorder.kt` | 129 | 102 | 96 | 6 | stays. The feature is cut (`voice = null`, `di/AndroidCaptureHost.kt:185`) and "voice in the duel" is on the Not-now list | not moved |
| `ar/CastTouch.kt` | 124 | 74 | 19 | 55 | `core/session/CastTouch.kt` over a plain `TouchEvent`: bindings, throttle 83-88, lift lead 89-99, stream capture | medium: the logic is pure; the `MotionEvent` -> `TouchEvent` mapping (74-77, 95) is where a slip would hide |
| `MainActivity.kt` | 113 | 57 | 51 | 6 | the sound-gating wrapper (79-84) goes with `SoundCues` | low |
| `ar/Occluders.kt` | 100 | 47 | 27 | 20 | `core/session` `OccluderShape.of(LevelObject)` (63, 71-81). `occlusionFor` (55-60) stays as the host's switch | low, but invisible on the Pixel: depth is supported there, so the boxes never run unless `FORCE_OCCLUDER_BOXES` is flipped |
| `audio/Sounds.kt` | 99 | 70 | 41 | 29 | `core/session` `SoundCues`: event / emote -> wav name + volume (53-75), the 150 ms gap (77-83) | low |
| `scan/FootprintOverlay.kt` | 69 | 32 | 31 | 1 | stays (debug chrome) | not moved |
| `ar/PlaneCoords.kt` | 56 | 24 | 20 | 4 | stays: it is the Pose conversion. The on-ray filter (42-44) is 4 lines | not moved |
| `ar/PlaneSettle.kt` | 47 | 21 | 13 | 8 | optional, last: a generic `PlaneSettle<K>` in `core/session` | medium-high: the 10 s gate is a Pixel drift measurement (7-18) |
| `scan/TableScanner.kt` | 39 | 13 | 0 | 13 | `core/pipeline`: `TableScanner`, `ScanPrompts` | low: an interface and two lists, and the CLI keeps its own copy of the lists (`cli/src/main/kotlin/kz/rudy/ar/cli/ScanCommand.kt:50-51`) |
| **Total** | **3 596** | **2 121** | **1 126** | **995** | | |

1 475 of the 3 596 lines (41 %) are comments, imports and blanks: the host carries 122 `HAND-REVIEW` notes. Of the
2 121 code lines, 995 (47 %) are platform-free. The 1 126 bound lines are two different things: about 619 are AR glue
(RudeusArView 231, ScanCapture 113, CharacterNode 86, GateProbes 79, FootprintOverlay 31, Occluders 27, PlaneCoords 20,
CastTouch 19, PlaneSettle 13) and about 507 are Android SDK leaf services (AndroidImages 130, AndroidCaptureHost 101,
VoiceRecorder 96, PhotoIntake 88, MainActivity 51, Sounds 41). A Swift host that gets the 995 for free still writes
the equivalent of about 540 lines of AR glue (619 minus the debug probes) plus the leaf services it wants.

### 2. The seam

**Decision.** A common engine in `:core` (`kz.rudy.ar.core.session`, no Compose, no AR type) owns the game from the
moment the anchor exists. The host pushes frames and touches into it and pulls node poses out; for the few things only
the AR session can answer (hit tests, depth readings, a camera image) the engine calls back through one small port.
`:shared` keeps `ArSlot` and gains one composable that turns slot edges into engine calls. The AR view stays an
injected slot on both platforms.

```kotlin
package kz.rudy.ar.core.session

// ---- host -> engine, once per AR frame after the anchor exists ----
class FrameInput(
    val nowMs: Long,              // host monotonic clock (Android: SystemClock.elapsedRealtime, as today)
    val tracking: Boolean,        // camera AND the session anchor tracking; false = physics pauses, nothing resets
    val camera: CameraFrame?,     // null while not tracking (the pose is meaningless then, RudeusArView.kt:804)
)
class CameraFrame(
    val pose: Pose3,              // the camera in the ANCHOR frame (x = u, y = h, z = v); -Z is the optical axis
    val right: Vec2,              // screen-right projected onto the plane (display-oriented +X), any length
    val planeToClip: FloatArray,  // 16, column-major: projection * view * anchorModel; (u, h, v, 1) -> clip
    val viewWidthPx: Int,
    val viewHeightPx: Int,
)

// ---- engine -> host, the return value of onFrame ----
data class NodePose(
    val id: String,
    val u: Float, val v: Float, val h: Float,  // the quad's origin (the feet), metres, plane coordinates
    val yawDeg: Float,                         // about the plane normal; 0 = the quad's front along +v
    val tiltDeg: Float,                        // about the quad's own front axis, applied after the yaw, already signed
    val scaleX: Float, val scaleY: Float,      // > 0, on a quad of SurfaceProfile.characterHeightM x the picture's aspect
    val mirrored: Boolean,                     // flip horizontally (Android: negative X scale, as today)
    val picture: String,                       // "base", "hello-1", "view-back": a key into the member's pictures
    val visible: Boolean,
)

// ---- engine -> host, synchronous queries; main thread, only while the anchor exists ----
interface ArPort {
    fun hitTable(xPx: Float, yPx: Float, toleranceM: Float): TableHit?         // RudeusArView.kt:551-557, 608-615
    fun depthGapsOnWay(from: Vec2, to: Vec2, samples: Int): List<Float?>       // PlaneCoords.kt:32-46; no depth = nulls
    fun pictureAspect(id: String, picture: String): Float?                     // width / height once the texture exists
    fun grabScanFrame(withBoundary: Boolean): ScanGrab                         // ScanCapture.kt:83-129
}
class TableHit(val uv: Vec2, val depthGapM: Float?)                            // CastTouch.kt:34, unchanged
sealed interface ScanGrab { class Ready(val frame: ScanFrame) : ScanGrab; data object NotYet : ScanGrab; data object Failed : ScanGrab }
class ScanFrame(val image: ScanImage, val intrinsics: Intrinsics, val cameraPose: Pose3, val anchorPose: Pose3,
                val planePolygonWorld: List<Vec3>?, val startedMs: Long)       // world poses, as ScanGrab today (57-67)
interface ScanImage { fun toJpeg(maxSide: Int, quality: Int): EncodedImage }   // called off the main thread
class EncodedImage(val bytes: ByteArray, val width: Int, val height: Int)

class TouchEvent(val action: TouchAction, val actionIndex: Int, val pointers: List<TouchPoint>, val nowMs: Long)
enum class TouchAction { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }
class TouchPoint(val id: Int, val xPx: Float, val yPx: Float)

fun interface SoundSink { fun play(name: String, volume: Float) }              // "jump", "land", "hello", ...
interface TableListener {                                                      // 1:1 with today's ArSlot callbacks
    fun onTracking(ok: Boolean); fun onCastEvent(id: String, e: CharacterEvent)
    fun onCastState(states: Map<String, CharacterState>); fun onLevel(level: Level)
    fun onScanned(answer: ScanAnswer); fun onActiveChanged(id: String)
}

class Member(val id: String, val ability: Ability, val emotes: EmoteSet, val pictures: Set<String>)  // no bytes

class TableEngine(port: ArPort, scanner: TableScanner?, listener: TableListener, sounds: SoundSink,
                  log: (String) -> Unit, scope: CoroutineScope) {
    // from the UI (the :shared driver turns ArSlot edges into these)
    fun configure(surface: Surface, characterSize: Float)
    fun setMembers(members: List<Member>); fun setActive(id: String?)
    var autoScan: Boolean; var soundsOn: Boolean; var debug: Boolean
    fun command(c: CharacterCommand); fun requestScan(); fun requestScenario()
    // from the host
    fun anchorPlaced(nowMs: Long, cameraRight: Vec2)   // the session's ONE anchor; a second call is ignored and logged
    fun onFrame(input: FrameInput): List<NodePose>
    fun onTap(xPx: Float, yPx: Float)
    fun onTouch(e: TouchEvent): Boolean                // true = consumed, as CastTouch.onTouch today
    val level: Level                                   // for the host's occluders and debug overlay
}
```

**Locked decisions in this shape.** One anchor per session: the host creates it and tells the engine once; the engine
never sees an anchor and cannot ask for another. PAUSED: `tracking = false` reaches `GameLoop.onFrame`, which pauses
and never resets (`ar/GameLoop.kt:221-223`). Scans: only `ScanSession` calls `grabScanFrame`, for a button request or
for a sector `AutoScanPolicy` returned; never per frame. `:core` in plane coordinates: every game-side value above is
`(u, v, h)`; the two world-space `Pose3` in `ScanFrame` are what `ScanInput.fromAnchor` and the `scan_replay` log take
today (`scan/ScanCapture.kt:119-120, 135-143, 181`), unchanged. Log keys: the engine gets `log: (String) -> Unit`
and writes the same `RudeusGate` lines byte for byte (they are the device evidence); the host passes `Log.i(GATE_TAG, it)`.

**What `ArSlot` keeps.** Everything, as App's contract with "the AR side" (`shared/.../ArSlot.kt:63-148`). What changes
is who reads it. Today the host reads all of it (`ar/RudeusArView.kt:290-929`). After step 3 a `:shared` composable
(`rememberTableEngine(slot, port, scanner, sounds)`) reads `scanRequest`, `command`, `scenarioRequest`, `characterSize`,
`activeId`, `autoScan`, `soundsOn` and calls `onScanned`, `onLevel`, `onTracking`, `onCastEvent`, `onCastState`,
`onActiveChanged`. The host reads only what a renderer needs: `characters[].spritePng / frames / views` (pictures),
`surface` (quad height), `debug` (overlay), and still reports `onSurfaceReady` / `onAnchorPlaced` itself. The legacy
mirror fields (`spritePng`, `ability`, `jumpRequest`, `walkForwardRequest`, `onCharacterEvent`: `ArSlot.kt:65, 87, 102,
107, 117`) are already dead for this host (`RudeusArView.kt:137-139, 275-279`; App fills `characters` before the slot
is composed, `shared/.../App.kt:369-370, 629-630`) and can be deleted from `ArSlot` after step 3. `ScanAnswer`,
`CharacterAction` and `CharacterCommand` (`ArSlot.kt:13-25, 46-53`) move to `core/session` in step 3, because `:core`
cannot see `:shared`.

**The duel fits** without a host change. The engine gets a second stage next to the sandbox one: T45's match and bot
over `DuelPhysics.step`. Both stages hand the poser the same thing (`CharacterState`, vertical velocity, clocks; a
`DuelFighter` carries a `PhysicsBody`, T42), so the host still receives `List<NodePose>` with two ids. A tap is still
`onTap` -> `port.hitTable` -> plane coordinates; the engine decides it is a `DuelInput.MoveTo`. Dash and Special are
new `CharacterAction`s through `command` (`:shared`). `DuelEvent`s go to the HUD through `TableListener` and to
`SoundSink` by name (new wav files, no code). "Never a scan mid-round" is the engine refusing `requestScan`. One rule
for T45: **the 60 s round clock must run on the simulated clock** (`GameLoop.simMs`, `ar/GameLoop.kt:83-85`), never on
wall time, or a tracking loss runs the round down while the fighters are frozen.

**The sprite views fit** through `NodePose.picture` and `mirrored`. `ViewPicker.pick(facing, cameraForward, cameraRight,
previous, views)` is called inside the engine's poser stage; the host maps a key to a texture. Recommendation for T50:
feed it the **bearing from the camera to that character** (`forward = pos - cameraUv`, `right` as the flip computes it
today, `ar/CharacterNode.kt:211-215`), and fall back to `CameraFrame.pose` / `right` only when the camera is straight
above. The optical axis the T41 doc names differs from that bearing by up to half the field of view at the screen
edges (a few tens of degrees on a phone; not measured here), several times the 10 degree hysteresis: a character near
the edge of the screen would show the wrong side, and the SIDE mirror would no longer be "today's flip rule".

**The other seams and what they cost.**
- *Taps as data inside `FrameInput`* (the example in this brief). The walk rule needs depth samples along the way from
  the member's own position (`RudeusArView.kt:869`), which only the engine knows, and the finger lead needs a hit test
  per move (`CastTouch.kt:112-118`): a query back into the host is needed either way. Queueing taps into the next
  frame would add that queue and a frame of latency to a `walkTo` that today happens inside the tap handler (876).
- *A common scene API* (`createNode`, `setMaterial`, `createAnchor` behind an interface, or an `expect` AR view). It
  re-encodes every SceneView / Filament gotcha behind an abstraction nobody here can run, the iOS side is Swift anyway
  because RealityKit is not reachable from Kotlin, and it cannot land in small steps: the 929-line view would be
  rewritten in one diff. It also breaks "the AR view stays a slot".
- *The engine in `:shared`*. Its tests would run only through `:shared:testAndroidHostTest`, which needs the Android
  SDK (not in every cloud session), and the CLI could not drive the loop headless. Only the Compose edge adapter
  (about 80 lines) belongs in `:shared`.
- *Moving the pre-anchor phase too* (settle gate, held tap, `RudeusArView.kt:716-736, 836-849`, `ar/PlaneSettle.kt`).
  It is logic, but logic about how ARCore planes behave, tuned on one Pixel. ARKit needs its own measurement. It stays
  in the host until everything else is done (step 8); a Swift host rewrites about 40 lines.

### 3. Migration order

Every step is three commits, so a step that fails on the Pixel is undone by reverting the last one alone:
**A** pin today's behaviour with JVM tests against the code where it is (`androidApp/src/test`, run by
`:androidApp:testDebugUnitTest`, already in `scripts/verify.sh:21`); **B** add the code and the same tests to `:core`
(`:shared` where said); **C** the hand-review commit: the host deletes its copy and calls the common one. Steps S1-S4
must land before T50, S5-S6 before the real Swift host; S7-S8 any time.

| Step | Moves | JVM tests that pin it first | The one Pixel check | Risk |
|---|---|---|---|---|
| **S1 = T47** | `ar/GameLoop.kt` -> `core/session/GameLoop.kt`, verbatim | `GameLoopTest` (21 pinned cases plus one for the new switch, listed in T47): dt clamp, pause / resume, simulated clock, event diff, member clocks, scenario equivalence | cover the lens for 3 s while one of two characters walks: both freeze, reappear in place, the walker finishes | low |
| **S2** | poser maths (`ar/CharacterNode.kt:52-76, 201-250`) -> `BillboardPoser` returning `NodePose`. Commit A first extracts a pure `computePose` inside the host (three node writes stay) | `billboardFacesTheCameraYawOnly`, `flipFollowsFacingWithDeadband`, `walkBobPeriodAndAmplitude`, `jumpStretchAndLandingSquash`, `landingSquashWinsOverTheBob`, `emoteScalesMultiplyAndStopTheBob`, `bowLeansTheWayTheSpriteLooks` (facesRight x flip), `hopIsHopTimesHeightTimesSize`, `feetDepthBiasKeepsThePixelAndShrinksDepth`, `cameraStraightAboveGivesNoNaN` | walk a character left then right across the view and press Hello: one flip each way, the bow leans the way it looks, the feet stay on the table | medium |
| **S3a** | commands, scenario start, cast sync, surface lock (`RudeusArView.kt:228-234, 275-279, 334-337, 407-458, 567-581`) -> `TableEngine`; the `LaunchedEffect`s -> `:shared` `rememberTableEngine`; `ScanAnswer` / `CharacterCommand` -> core | `commandBeforeTheAnchorIsIgnored`, `eachSerialIsAppliedOnce`, `walkForwardIsTenCmTimesScaleAlongFacing`, `walkForwardOutsideTheBoundaryIsSkipped`, `emoteSoundOnlyWhenSoundsOn`, `membersWaitForTheAnchor`, `laterMembersSpawnTowardCameraRight`, `placedIsAnnouncedOncePerMember`, `anchorCallbacksKeepTodaysOrder`, `surfaceIsLockedAtTheAnchor`, `logKeysAreUnchanged` | Jump, Hello and adding a second character each act once on the right member; the second appears beside the first toward screen-right | low-medium |
| **S3b** | the frame step (`RudeusArView.kt:738-743, 775-821`): tracking edge, scenario feed, `loop.onFrame`, cast-state report, reappear timer, `List<NodePose>` out; host builds `FrameInput` | `trackingEdgeIsReportedOnce`, `hiddenFor800MsAfterTrackingReturns`, `castStateAtMostEvery100MsAndOnlyInDebug`, `scenarioFeedMatchesScenarioRunner`, `nodePosesFollowCastOrder` | debug Scenario button: the Day 5 script plays to the end as before and logs `scenario_done ms=` just over 15000 (the script's length in simulated time) | medium |
| **S4** | taps and fingers: `RudeusArView.kt:465-473, 603-668, 850-878`, `ar/CastTouch.kt` on `TouchEvent`, `ScreenProjector` maths (259-271) on `planeToClip` | `tapWalksTheActiveMember`, `tapPastADepthEdgeIsBlocked`, `noDepthReadingNeverRefuses`, `acceptedTapGrowsTheBoundary`, `tapOnAMemberOnlySelectsIt`, `oneMemberNeverGrabs`, `grabPicksTheNearestQuadOnScreen`, `fallbackGrabBeforeLayout`, `leadThrottledToSixtyMsAndFiveMm`, `leadPastAnEdgeKeepsTheLastTarget`, `liftLeadsOnceMore`, `streamIsConsumedOnceBound`, `cancelReleasesEverything` | two characters: lead one with a finger while tapping the table to walk the other | medium-high |
| **S5** | scan: `RudeusArView.kt:384-405, 480-546, 746-772` -> `ScanSession`; `scan/ScanCapture.kt:156-211, 222-227` and `scan/TableScanner.kt` -> core; the grab stays behind `ArPort.grabScanFrame` | `everyRequestIsAnsweredExactlyOnce`, `requestWithoutAnchorOrScannerFailsAtOnce`, `requestDuringAScanJoinsIt`, `noImageRetriesUpToTenFrames`, `tooSteepAnswersBeforeTheGrab`, `quietScanAnswersNobody`, `buttonJoiningAQuietScanMakesItManual`, `autoScanOffDropsAPendingQuietScan`, `firstLevelMustComeFromTheButton`, `swapNeverShrinksTheBoundaryUnderAMember`, `busyIsClearedOnFailureAndCancel`, `timeoutIsAFailureNotACancellation`, `anchorFramePosesFireThePolicyOnTheSameFrames` | Scan, walk to the far side of the table, hold still: one `auto_scan sector=` line and the run file shows one manual and one background scan | medium |
| **S6** | capture host: `di/AndroidCaptureHost.kt` splits into a common session in `:core` and an Android leaf (photo intake, codec, key, export, prefs, assets as a `FixtureSource`); bundled index through `Bundle.decodeIndex` | `refsCountAcrossSingleAndBatch`, `runFileRewrittenAfterEveryCharacterAlsoOnFailure`, `batchLogsAppendToTheSession`, `savesAreSerialised`, `bundledAreGatePassedFirstInIndexOrder`, `bundledSkipsAnEntryWithoutSpriteOrSheet`, `styleRefsFollowCullOrderThenName`, `missingKeyFailsOnTheFirstCallNotAtConstruction`, `deferredModelsForwardEveryCall` | one photo to Play, restart the app: the character is under Mine and Metrics shows the run | low-medium |
| **S7** | sounds: `audio/Sounds.kt:53-83` and `MainActivity.kt:79-84` -> `SoundCues`; `Sounds` keeps the SoundPool only | `eventsMapToTheSameWavAndVolume`, `sameCueWithin150MsIsDropped`, `soundsOffIsSilentButEventsFire`, `emoteSoundNoneIsSilent` | jump twice quickly: two jump sounds, one landing each; Sounds off: silence | low |
| **S8** | leftovers: `OccluderShape.of` (`ar/Occluders.kt:63, 71-81`); optional `PlaneSettle<K>` + held-tap gate (`RudeusArView.kt:716-736, 836-849`) | `roundFootprintIsACylinderOfEqualArea`, `bookIsABoxOnItsBounds`, `cliffHasNoOccluder`; `planeSettlesAfterTenSecondsOfTracking`, `aLostPlaneRestartsItsClock`, `heldTapLandsOnceThePlaneSettles` | with `FORCE_OCCLUDER_BOXES = true` a cup hides the character behind it; (gate) an early tap still lands by itself | low; the gate medium-high, do it last or not at all |

What S2-S8 are numbered as, and who runs them, is the orchestrator's: this brief owns only T47.

### 4. What stays per-platform, and the `expect/actual` verdicts

The Swift host implements, for good:
1. The AR session: horizontal plane finding, depth when the device has it, autofocus, run / pause with the view, the
   camera permission screen.
2. The surface-ready signal and the session's one anchor (created on a tap, never recreated).
3. Per frame: tracking state, the camera pose in the anchor frame, screen-right, `planeToClip`; `engine.onFrame`; one
   billboard quad per `NodePose` (position, yaw then tilt, scale, mirror, visibility).
4. `ArPort.hitTable` (ray against the anchor's surface within the tolerance) and `depthGapsOnWay` (nulls without depth:
   "no reading never refuses", `RudeusArView.kt:865-868`, so a phone without LiDAR degrades honestly, no stub).
5. Pictures: an unlit, alpha-blended texture per key per member, and their lifetime.
6. Occlusion: depth, or one invisible shape per `OccluderShape`; decided once per session.
7. The scan grab: the camera image as JPEG, intrinsics in that image's pixel grid, camera pose in the image's
   orientation, anchor pose, the plane polygon in world points.
8. Touch plumbing: raw touches -> `TouchEvent`, taps -> `onTap`.
9. Leaf services: sound playback (`SoundSink`), photo intake (camera and picker -> upright JPEG <= 2048 px), the image
   codec, the API key, export / share, the storage root. No microphone until voice comes back.
Android-only and not ported: `ar/GateProbes.kt`, the ARCore recorder, `scan/FootprintOverlay.kt`, `voice/VoiceRecorder.kt`.

| Candidate | Verdict | Why |
|---|---|---|
| HTTP engine | no `expect/actual` | Ktor picks the engine from the source set already (`core/.../meta/MetaApi.kt:250-253`); `ktor-client-darwin` is in `iosMain` (`core/build.gradle.kts:47-49`) |
| identity hash | no | `override fun hashCode() = super.hashCode()` is identity on JVM and Native (see 5) |
| clock, locale | no | `kotlin.time` (`core/.../metrics/MetricsLog.kt:154`, `shared/.../App.kt:231`) and Compose `LocaleList` (`App.kt:169`); frame time comes in through `FrameInput.nowMs` |
| file roots | no | the host passes a path into T39's `Io*` classes |
| haptics | no | the app has none today (no `haptic` / `vibrat` anywhere in `shared`, `androidApp`, `core`); Compose's `LocalHapticFeedback` is common if one is wanted |
| audio | no, an interface | SoundPool needs a `Context` and AVAudioPlayer does not, so an `expect class` could not be constructed from common code anyway; `SoundSink` passed by the host is the same thing with less ceremony |
| log sink | no, a parameter | `log: (String) -> Unit`, so tests can assert the `RudeusGate` keys |
| **image codec** | **yes, the only one, and not before T51** | three real implementations (BitmapFactory / ImageDecoder, javax.imageio, CoreGraphics / ImageIO), no common library in `:core`. An `expect` needs its iOS `actual` to keep `:core:compileKotlinIosSimulatorArm64` green, and that `actual` can only be run on the Mac. Until then the contract is the four functions `CharacterPipeline` already takes (`di/AndroidCaptureHost.kt:232-235`, `core/src/jvmMain/.../JvmPipeline.kt:27-30`) |

### 5. iOS blockers in `:shared` `commonMain` (input for T46)

Found by reading and by grep for JVM-only APIs over `shared/src/commonMain` and `shared/src/commonTest`; `:shared` has
no iOS target yet (`shared/build.gradle.kts:11-30`), so none of this was compiled for iOS.
1. `shared/.../ArSlot.kt:42` and `:147`: `System.identityHashCode(this)`. Replace both with `super.hashCode()`.
   `equals` is already `this === other` (41, 146); `ArSlot` must stay a `data class` (`MainActivity.kt:80` uses `copy`).
2. `shared/.../Platform.kt:7`: `expect fun getPlatform()` has only an Android `actual`
   (`shared/src/androidMain/.../Platform.android.kt:9`). It is the project-wizard template, used only by `Greeting.kt`.
   Delete `Platform.kt`, `Platform.android.kt`, `Greeting.kt`, `GreetingUtil.kt` rather than write an iOS actual.
3. Not blockers, checked: `LocalLocaleList` (`App.kt:22, 169`) is in the Compose UI 1.12.2 iOS klib; `e::class.simpleName`
   (`App.kt:743`) exists on Native; `TimeSource.Monotonic`, `Dispatchers.Default`, `rememberSaveable`,
   `ByteArray.decodeToImageBitmap()` (`ui/components/SpriteImage.kt:29`) are common; `backHandler` defaults to a no-op.
4. Not in `commonMain` but T46 will meet it: `BundledCharacter` (`ui/screens/CaptureScreen.kt:100-111`) holds a
   `@Composable` thumbnail slot, so the common capture session of step S6 needs a Compose-free card type in `:core`.

### Findings beyond the brief
1. **The loop that implements "PAUSED freezes, never resets" on the device has no test.** `ar/GameLoop.kt` has no
   Android import, and the only test near it re-implements it by hand inside a core test
   (`core/src/commonTest/.../domain/CharacterEventsTest.kt:225-244`). T47 fixes exactly this.
2. **Pose frames (T33) never reach the screen, and the "host only swaps the texture" mechanism does not exist.**
   Nothing under `androidApp/src/main/kotlin` reads `ArCharacter.frames` or calls `EmotePlayer.frameAt`; the capture
   host builds cards and pipelines without frames (`di/AndroidCaptureHost.kt:228-240, 429, 507`); a rig has one
   texture, rebuilt only when `spritePng` changes (`RudeusArView.kt:343-347`). The T33 host draft (commit 1b94204,
   `setMaterialInstanceAt`) lost to the day9 host files at merge b367873 and never ran on the Pixel; T33 is still
   `review`. 1.9 MB of `toy_dino/frames/*.png` ship in the APK unused. So T50 has to build "texture by key" from
   nothing. It is the one new AR capability the views need; the duel itself needs none.
3. **The plan's table is one brief short of its own claim.** T50 and T51 depend on "T47", but one small step cannot
   make the host thin. Without S2-S4 before T50 the duel wiring gets written inside `RudeusArView` and moved again.
   G6 itself (camera-less placeholder) needs none of this, only T46.
4. The host breaks its own module rule in three places: it parses the bundle index with `org.json` and decides
   "gate-passed first" (`di/AndroidCaptureHost.kt:484-514`) although `:core` has `Bundle.decodeIndex`
   (`core/.../bundle/Bundle.kt:95`); the SAM prompt lists live in the host and again in the CLI; plane conversion
   exists twice (`ar/PlaneCoords.kt:17-23, 52-53` and `PlaneFrame`, `core/.../geometry/Plane.kt:29-38, 58-66`).
5. Sounds are played from two places (`MainActivity.kt:79-84` for events, `RudeusArView.kt:440` for emotes) on two
   clocks (`elapsedRealtime` in the view, `uptimeMillis` in `audio/Sounds.kt:79`). Harmless today; S7 makes it one.

### Verified, inferred, not judged
- **Verified by reading**: every `file:line` above. Counts come from `wc -l` and a line classifier over ranges marked
  while reading. ARCore 1.54.0 has `Pose.toMatrix / getXAxis / getZAxis / inverse / compose` and
  `Camera.getViewMatrix / getProjectionMatrix / getDisplayOrientedPose` (`javap` on the cached `core-1.54.0.aar`).
  The Compose UI 1.12.2 iOS klib contains `LocalLocaleList`, `UIKitView`, `ComposeUIViewController`,
  `LocalHapticFeedback` (symbol search, not a compile).
- **Could not confirm**: Context7 was unreachable, and the SceneView 4.31.0 sources jar is not in `~/.gradle/caches`
  (only the AARs). Every SceneView line number quoted in the host's comments is that comment's own record, not
  re-read. No step above uses a SceneView API the host does not already call, except "texture by key" in T50.
- **Inferred**: that `anchor.pose.inverse().compose(camera.pose)` fed to `AutoScanPolicy` fires on the same frames as
  today's world poses (equal up to float rounding; thresholds are 3 cm and 0.1 rad); that one `planeToClip` multiply
  picks the same member as today's two `multiplyMV`; that `super.hashCode()` in a data class is the identity hash on
  Kotlin/Native as it is on the JVM (not compiled here); that `UIKitView` can host a RealityKit view under the Compose
  HUD with touches passing through as on Android; that the GameLoop + `ScenarioPlayer` event sequence equals
  `ScenarioRunner`'s (T47's last pinned test finds out).
- **Not judged without a device**: finger-grab feel after S4; whether a per-frame material swap flickers on the
  transparent quad; whether the stale-transform workaround (`RudeusArView.kt:705-710`) is still needed once node writes
  come from `NodePose`; everything ARKit (plane settle time, depth without LiDAR, image orientation of the intrinsics,
  mirroring an unlit quad in RealityKit).
- cut/deferred: nothing cut. Not done here: S2-S8 briefs (written when their turn comes, from the rows above).
