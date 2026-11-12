# T54 — thin-host-step-4

status: todo
created: 2026-11-12
phase: v2 wave 3 (ai/build-plan-v2.md, "iOS readiness": thin host). Step S4 of the map in ai/tasks/T43-thin-host-map.md
gate: G1 foundation (`scripts/verify.sh` green, `:core` and `:shared` still compile for iOS); a precondition of T50
cut-if-slips: never cut. Two revert points, see "Done when": commit C alone, then commits B and A together
depends: T56 merged and its Pixel check ("walk, cover, grow, replay") reported fine by the user
tier: rudeus-opus-extra recommended; the orchestrator decides (every design choice is taken below; what is left is a
  line-exact move proved by script, projection maths and 42 tests, the job that tier did for T52, T53 and T56)

**Every `file:line` below is a line of the T56 branch at a2dae59** (`ar/RudeusArView.kt` has 804 lines there,
`ar/CastTouch.kt` 124, `core/.../session/TableEngine.kt` 344). T56's review may still change a few host lines, so
check before trusting the rest: run
`sed -n '254p;309p;316p;401p;408p;494p;522p;695p;730p;751p' androidApp/src/main/kotlin/kz/rudy/ar/ar/RudeusArView.kt`
and compare with: `private class ScreenProjector {`, `val touch = remember { CastTouch() }`, `val loop = game.loop`,
`fun activeId(): String? = game.activeId()`, `fun confirmSurface(uv: Vec2) {`, `fun tableHit(hits: List<HitResult>,
a: Anchor?): HitResult? {`, `val table = remember {`, `val camera = CameraFrame(`, `val tapped =
table.grabbable(event.x, event.y)`, `loop.walkTo(active, uv) //`. In `TableEngine.kt`, lines 86, 213, 246: `val loop =
GameLoop(`, `onMemberRemoved(id)`, `fun spriteBuilt(`. Lines that only moved: shift the numbers of that region and say
so in the Result. A moved body whose text changed: stop and report.
Paths without a module are under `androidApp/src/main/kotlin/kz/rudy/ar/`.

## Goal
What a finger does after the anchor exists is decided in `core.session`: `TableEngine.onTap` (pick a character or walk
the active one, the depth edge on the way, the boundary growth) and `TableEngine.onTouch` (the grab, the lead, the
release). The Android host turns a `MotionEvent` into plain values, answers two questions through `ArPort` (the table
under a pixel, the depth readings along a walk) and hands the engine the camera as a screen once per frame. Today
those lines are closures in `ar/RudeusArView.kt` (254-280, 402-416, 520-592, 725-753) and `ar/CastTouch.kt`, and none
has ever run in a test. After this brief no host line reads `engine.loop`, an iOS host writes none of this, and a duel
stage can read the same tap as `DuelInput.MoveTo` without a host change. The player sees no difference.

## Decisions taken by the orchestrator (not open)
- Scope is everything a finger does after the anchor: the table tap (walk target, `confirmSurface`, the depth-gap
  check), picking a character on screen, the grab and the lead, `unbindMember` and T53's `onMemberRemoved`. The
  placement tap and the pre-anchor gate stay in the host: they create the one anchor.
- After T54 no host line reads `engine.loop` and `loop` is not public API: the precondition for a second stage.
- The seam is stage-neutral plain data: the host says what was touched, the engine decides what it means. `ArPort`
  arrives with `hitTable` and `depthGapsOnWay` only. `CameraFrame` gains what on-screen picking needs, with an
  as-today default; nothing T56 built changes shape. No `Stage` interface, no duel code, no scan (S5), no capture (S6).
- Proof as T52 / T53 / T56: P, A, script and old-line table, pin tests, B with the same tests, C. A red test against
  the host-local copy means the test is wrong or it found something: report it, never change a moved body.

## Decisions taken here (not open)
1. **The tap's hit test is the host's; the way and the lead's hit are the engine's questions.** The tap handler makes
   one `frame.hitTest(event)` before it knows whether an anchor exists, and the placement tap needs the same result
   (709-724). So the host keeps those lines and passes the hit in: `onTap(xPx, yPx, TableHit)`; a tap that misses
   the table is not reported, as today (725). `depthGapsOnWay` needs where the member stands and the clamped target
   (736, 576), and the lead's hit test runs only after the 60 ms throttle (`ar/CastTouch.kt:85`): the engine asks
   both at the moment they are asked today. Answering first would cost a hit test per MOVE.
2. **Nothing is queued.** Taps and touches call the engine from their callback, between frames, as today; ARCore
   answers a hit test only on the latest frame (`/websites/developers_google_ar`, `Frame.hitTest`).
3. **`hitTable` carries the tolerance** (T43's signature): the port must exist before the engine is built (315), and
   the host's `tableHit` reads the profile through `slotState` (331, 495). The hit rule becomes a top-level
   `surfaceHit(hits, a, toleranceM)`; the local `tableHit` calls it, so its pre-anchor callers (649, 710) are untouched.
4. **The picking camera is `CameraFrame.screen: ScreenView?`, two matrices, not one `planeToClip`.** Line 279 needs
   the projection's `[0]` and line 274 the eye depth; a product gives neither. `ScreenView.of` multiplies in `:core`,
   tested, so the host makes no matrix product. The engine keeps the last frame's `screen`, the frame `latestFrame[0]`
   holds at touch time (630, 696). The price is two camera reads per frame (see "ARCore calls"). Null, or a view not
   laid out, is the old table-point fallback (543).
5. **The picture's size reaches the engine by `spriteBuilt(id, facesRight, widthM, heightM)`**, and `spriteGone(id)`
   where the rig leaves the map (349), so the engine's sizes have the same keys as `rigs` at every moment (551: a
   member without a rig is not picked). No `pictureAspect` question; `CharacterSprite`'s exact floats are kept.
6. **`loop` becomes `internal`, not private**: T53's and T56's 55 tests and the new ones read physics state through
   it. From commit C a host line with `game.loop` does not compile.
7. **`onMemberRemoved` goes**; the engine owns the `CastTouch` and unbinds in `syncCast` at the same place (213). The
   constructor gains `port`, `walkDepthSamples`, `leadDepthSamples`, no defaults: the host keeps its numbers (233,
   187, the clip planes 190-191, `GRAB_MARGIN_DP` 184 as `ScreenView.pickMarginPx`). The `GRAB_*` move with `CastTouch`.
8. **`TouchAction` has `OTHER`**: any other `MotionEvent` action returns the captured flag today (`ar/CastTouch.kt:108`).
   `nowMs` stays the host clock at the callback (591), not the event's time.
9. **Left in the host, with the line**: `probe.onTap` (727, needs the world pose); the Compose `level` the overlay and
   the occluders read (413), mirrored after `onTap` by an identity compare; the `CAST_TOUCH_ENABLED` switch and the
   anchor check (591); which gesture is a tap (755-758, a Pixel finding); the `MotionEvent` mapping; the view size (596).
10. **The projection cannot be proved by substitution or bit for bit**: `android.opengl.Matrix` and ARCore's `Pose` do
    not run on the JVM, and the engine multiplies once where the host composed a world point per member (552-553).
    Results may differ in the last bits; a pick changes only when the finger is within about 0.001 px of a threshold.
    Covered by `pickAgreesWithTheOldWorldSpaceProjection`, `projectionMatchesAPinholeCamera` and the Pixel check.
11. **Revert points are T56's: C alone, then B and A together.** B changes what the T56 host calls (`port` for
    `onMemberRemoved`, `loop` internal), so A cannot be reverted while B stays. P can stay.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- core/src/commonMain/kotlin/kz/rudy/ar/core/session/ArPort.kt, TouchEvent.kt (new, P); FrameInput.kt (P)
- core/src/commonTest/kotlin/kz/rudy/ar/core/session/ScreenViewTest.kt (new, P)
- core/src/commonMain/kotlin/kz/rudy/ar/core/session/TableEngine.kt (B), CastTouch.kt (new, B)
- core/src/commonTest/kotlin/kz/rudy/ar/core/session/TableTouchTest.kt (new, B); TableEngineTest.kt (B: lines 55, 65,
  93, and the four `released` assertions 191, 311, 319, 338 go); TableFrameTest.kt (B: the constructor call, 76-79)
- shared/src/commonMain/kotlin/kz/rudy/ar/TableEngineHost.kt, shared/src/commonTest/kotlin/kz/rudy/ar/TableSlotTest.kt (B)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/TableEngine.kt, TableEngineHost.kt (HAND-REVIEW: new in A, deleted in C)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/CastTouch.kt (HAND-REVIEW: edited in A, deleted in C)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/RudeusArView.kt (HAND-REVIEW: A and C, only the edits below)
- androidApp/src/test/kotlin/kz/rudy/ar/ar/TableTouchPinTest.kt (new in A, deleted in C)
- ai/tasks/T54-thin-host-step-4.md (the Result section)

Does not move and must not touch: the pre-anchor gate and `placeAnchor` (506-519, 635-655, 704-724), the scan block
and `startScan` (378-400, 417-489, 662-690), `cameraRight` (501-505), `ar/PlaneCoords.kt`, `ar/CharacterNode.kt`,
`ar/GateProbes.kt`, `GameLoop.kt`, `BillboardPoser.kt`, `core/pipeline/**`, `core/physics/**`, `core/duel/**`,
`ArSlot.kt`, `App.kt`, every Gradle file.

## Contract
```kotlin
package kz.rudy.ar.core.session

// ArPort.kt (P). What only the AR session can answer: main thread, after the anchor, about the latest frame.
interface ArPort {
    // The anchor's surface under a view pixel: a plane hit inside its polygon, within toleranceM of the anchor
    // plane, with the depth reading there. Null: the ray misses it, or the camera is not tracking.
    fun hitTable(xPx: Float, yPx: Float, toleranceM: Float): TableHit?                 // <- 527-534
    // Depth readings at `samples` points of the walk (point i at from + (to - from) * i / samples), as height above
    // the plane in metres, null where there is none. No depth sensor: all nulls, which never refuses a walk.
    fun depthGapsOnWay(from: Vec2, to: Vec2, samples: Int): List<Float?>               // <- ar/PlaneCoords.kt:32-46
}
class TableHit(val uv: Vec2, val depthGapM: Float?)                                    // from ar/CastTouch.kt:33-34

// TouchEvent.kt (P). View pixels, origin top-left, y down. UP carries the lifting pointer at index 0.
class TouchEvent(val action: TouchAction, val actionIndex: Int, val pointers: List<TouchPoint>, val nowMs: Long)
enum class TouchAction { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL, OTHER }
class TouchPoint(val id: Int, val xPx: Float, val yPx: Float)

// FrameInput.kt (P). Column-major 4x4, as OpenGL, ARCore and ARKit give them.
class ScreenView(
    val planeToEye: FloatArray,    // 16: (u, h, v, 1) in plane coordinates -> eye space (-Z ahead), display-oriented
    val eyeToClip: FloatArray,     // 16: this view's projection; [0] is 2 * focal / width
    val widthPx: Int, val heightPx: Int,   // the AR view; 0 until laid out
    val pickMarginPx: Float,       // a pick rectangle grows by this on every side (Android: GRAB_MARGIN_DP * density)
) { companion object { fun of(worldToEye: FloatArray, planeToWorld: FloatArray, eyeToClip: FloatArray, widthPx: Int,
    heightPx: Int, pickMarginPx: Float): ScreenView } }   // planeToEye = worldToEye x planeToWorld; fresh arrays
// CameraFrame(pos, heightM, right, val screen: ScreenView? = null)   // null = no on-screen picking, as before T54

// TableEngine.kt (A host-local, B :core). Appended to TableSlot, each default meaning "nobody listens":
//   val onActiveChanged: (String) -> Unit = {}     // a finger or a tap chose this member (585, 732)
//   val onLevel: (Level) -> Unit = {}              // a tap grew the boundary (414); the host's scans call the slot's
class TableEngine(
    current, surface, playEmoteSound,                 // as T53
    private val port: ArPort,                         // takes the place of onMemberRemoved
    log, feetDepthBias, reappearAfterTrackingMs,      // as T56
    private val walkDepthSamples: Int, private val leadDepthSamples: Int,   // the host's two numbers; no defaults
    separate: Boolean = true,
) {
    internal val loop: GameLoop
    fun spriteBuilt(id: String, facesRight: Boolean, widthM: Float = 0f, heightM: Float = 0f)  // both > 0: pickable
    fun spriteGone(id: String)                      // that picture's size is forgotten; poser and facing stay (T56)
    fun onTap(xPx: Float, yPx: Float, hit: TableHit)                                   // <- 728-752, 408-416
    fun onTouch(e: TouchEvent): Boolean             // true = this stream is the engine's   <- 591, ar/CastTouch.kt
}
// CastTouch.kt (A edited in place, B :core): internal class CastTouch(private val log: (String) -> Unit) with
// fun onTouch(event: TouchEvent, table: CastTable): Boolean and unbindMember; CastTable and the GRAB_* as they are.
```
Private state of the engine, plain fields: `touch = CastTouch(log)`, `screen: ScreenView? = null`, `quads:
HashMap<String, Quad>` (`private class Quad(val widthM: Float, val heightM: Float)`), three `FloatArray(4)` for the
projection, and `table`, an `object : CastTable` holding old 523-586 with `hitUv(x, y) = port.hitTable(x, y,
profile.tapHeightToleranceM)`. Three T56 bodies change by one line each: `onFrame` gains `screen =
input.camera.screen` right after its anchor guard (also on untracked frames); `syncCast` calls
`touch.unbindMember(id)` where it called `onMemberRemoved(id)`; `spriteBuilt` also stores or drops the `Quad`.
`onTap` and `onTouch` return at once before the anchor (new guards, silent; neither can fire on Android).

`rememberTableEngine(slot, members, playEmoteSound, port, log, feetDepthBias, reappearAfterTrackingMs,
walkDepthSamples, leadDepthSamples, separate = true)`: `port` goes straight into the engine at the first composition,
like the Pixel numbers (the host remembers it without keys); the `currentRemoved` line goes. `toTableSlot` fills the
two new fields from `ArSlot.onActiveChanged` and `ArSlot.onLevel`. No sixth effect.

**What must stay exactly as it is**: every rule under "Tests required", in the order the old lines apply it (a tap
picks first, then the way's readings with the tap's own last, the `tap_depth_gap=` line, then BLOCKED or growth and
walk; a grab binds, leads under its throttle, leads once more on lifting, releases). Nothing here looks at tracking:
PAUSED still freezes and never resets, and a walk set during a loss waits for it. One anchor per session: neither
entry point nor `ArPort` can create one. Log lines byte for byte (`tap_depth_gap=`, `tap_past_edge`,
`boundary_grown`, `lead_past_edge`, `grab pointer=`, `release pointer=`), no new line.

**What is not identical, and why it is harmless.** (a) Decision 10: last-bit differences in the projection. (b) A
pick uses the camera and the view size of the last `onFrame`, not values read at touch time: the same frame, except
between the anchor and the first frame after it, where `screen` is still null and the table-point fallback picks
(one frame, before the characters are drawn). (c) The tap's depth reading (739) is taken before `onTap` on every
table tap, before only on a tap that reached the walk. (d) The Compose `level` is written after `onLevel` and
`walkTo` instead of before them; nothing observes it in between. (e) The `frame` / `anchor` null returns of 538-539
and 573-574 are gone: unreachable after the anchor. (f) Per frame the host allocates one `ScreenView` and two
`FloatArray(16)` more, and one `TouchEvent` per touch event after the anchor.

**ARCore / SceneView calls made differently** (T56 had one such place; T54 has three, all reads):
1. `Camera.getViewMatrix` and `Camera.getProjectionMatrix`: on every anchored frame; before only inside a pick (a
   DOWN or a tap with two members and a laid-out view, 544).
2. `Anchor.getPose` + `Pose.toMatrix` (a new call; in ARCore 1.54.0 by T43's `javap`, documented column-major): once
   per anchored frame; they replace two `Anchor.getPose` + `Pose.compose` per ground member per pick (552-553).
3. `HitResult.getTrackable` / `getHitPose` over the tap's own hit list (739): see (c). No new session query.
Unchanged in count and moment: `Frame.hitTest(event)` once per tap (709), `Frame.hitTest(x, y)` once per lead and per
fallback pick (530), the 6 or 2 ray tests of `depthGapsOnWay`. No SceneView call is added, removed or made differently.

### Host diff, commit A (HAND-REVIEW)
New `ar/TableEngine.kt` and `ar/TableEngineHost.kt` (step 3), each opening with one
`// HAND-REVIEW: T54 — host-local copy for one brief ...` note. Then twenty edits. In `ar/RudeusArView.kt`:
1. Imports: 30, 31, 55, 56, 69, 70 go (`Pose`, `GlMatrix`, `CharacterEvent`, `Mode`, `core.session.TableEngine`,
   `rememberTableEngine`); `core.session.ArPort`, `ScreenView`, `TableHit`, `TouchAction`, `TouchEvent`, `TouchPoint` come.
2. Header, after 181: `// HAND-REVIEW: T54 — what a finger does after the anchor is TableEngine.onTap / onTouch: this`
   `// file maps the MotionEvent, answers ArPort (the table under a pixel, the depth on the way) and sends the camera as`
   `// a ScreenView each frame. The T32 notes on CastTable above describe code that is the engine's now. One anchor per`
   `// session (only the placement tap creates it), PAUSED still freezes and never resets, scans are untouched.`
3. `ScreenProjector` (250-280): `world`, `eye`, `clip` (257-259), `project` (266-276) and `pxPerM` (278-279) go; a
   `private val model = FloatArray(16)` comes; the KDoc (250-253) says what is left; `load` (261-264) becomes
   ```kotlin
   fun screen(frame: Frame, a: Anchor, w: Int, h: Int, marginPx: Float): ScreenView {
       frame.camera.getViewMatrix(view, 0)
       frame.camera.getProjectionMatrix(proj, 0, PROJECTION_NEAR_M, PROJECTION_FAR_M)
       a.pose.toMatrix(model, 0)
       return ScreenView.of(worldToEye = view, planeToWorld = model, eyeToClip = proj, widthPx = w, heightPx = h, pickMarginPx = marginPx)
   }
   ```
4. New, after that class and before 282, each under one `// HAND-REVIEW: T54 —` note: `private fun surfaceHit(hits:
   List<HitResult>, a: Anchor?, toleranceM: Float): HitResult?`, its body old 496-499 with `toleranceM` for
   `tolerance`; then
   ```kotlin
   private class SessionPort(private val latestFrame: Array<Frame?>, private val anchorRef: Array<Anchor?>) : ArPort {
       override fun hitTable(xPx: Float, yPx: Float, toleranceM: Float): TableHit? {
           // old 528-533 as they are, but `frame.hitTest(xPx, yPx)` and `surfaceHit(hits, a, toleranceM)`
       }
       override fun depthGapsOnWay(from: Vec2, to: Vec2, samples: Int): List<Float?> {
           val frame = latestFrame[0] ?: return List(samples) { null }
           val a = anchorRef[0] ?: return List(samples) { null }
           return kz.rudy.ar.ar.depthGapsOnWay(frame, a, from, to, samples) // PlaneCoords.kt's, by its full name
       }
   }
   ```
   and `private fun MotionEvent.toTouch(nowMs: Long): TouchEvent`: `actionMasked` mapped name for name by a `when`
   (`ACTION_DOWN`, `ACTION_POINTER_DOWN`, `ACTION_MOVE`, `ACTION_POINTER_UP`, `ACTION_UP`, `ACTION_CANCEL`; `else ->
   TouchAction.OTHER`), then `actionIndex`, `List(pointerCount) { TouchPoint(getPointerId(it), getX(it), getY(it)) }`
   and `nowMs`.
5. After 307: `val port = remember { SessionPort(latestFrame, anchorRef) }`. Line 309 (`touch`) goes.
6. Line 315: `onMemberRemoved = touch::unbindMember` -> `port = port`; `walkDepthSamples = WALK_DEPTH_SAMPLES,
   leadDepthSamples = LEAD_DEPTH_SAMPLES,` before `separate`. Line 316 (`val loop`) goes.
7. Line 344: `game.spriteBuilt(c.id, sprite.facesRight, sprite.widthM, sprite.heightM)`; its comment gains
   `; T54: and its size, for picking`.
8. Line 349: the `if (rigs[c.id] === rig)` gets a block with `rigs.remove(c.id)` and
   `game.spriteGone(c.id) // T54: no rig, no pick, as before`.
9. Lines 401-416 (`activeId`, `confirmSurface` and its KDoc) go.
10. Lines 490-500: the KDoc moves onto `surfaceHit`; the function becomes
    `fun tableHit(hits: List<HitResult>, a: Anchor?): HitResult? = surfaceHit(hits, a, slotState.profile.tapHeightToleranceM)`.
11. Lines 520-587 (the `CastTable` object and its note) go.
12. Line 591: `touch.onTouch(event, SystemClock.elapsedRealtime(), table)` ->
    `game.onTouch(event.toTouch(SystemClock.elapsedRealtime()))`; 588-589 gain `T54: the bookkeeping is TableEngine's`.
13. Line 695 gains a fourth argument, `projector.screen(frame, a, viewSize[0], viewSize[1], grabMarginPx[0])`, and
    the note 691-694 one sentence: the two camera matrices are now read on every anchored frame, for picking.
14. The tap, 725-753: 725-727 stay; 728-736 go; 737-739 (the depth reading and its note) stay, now right after 727;
    740-752 become
    ```kotlin
    // HAND-REVIEW: T54 — what this tap means is TableEngine.onTap (pick a member, the depth edge on the way, the
    // boundary growth, the walk). The hit test stays here: the placement tap shares it. `level` mirrors a grown
    // boundary for the overlay and the occluders, as confirmSurface wrote it. This branch cannot create an anchor.
    val before = game.level
    game.onTap(event.x, event.y, TableHit(uv, depthGap))
    game.level.let { if (it !== before) level = it }
    ```
15. Line 760: the comment's `(CastTouch.kt)` -> `(TableEngine.onTouch)`.

In `ar/CastTouch.kt`:
16. Imports 3-4 (`Log`, `MotionEvent`) go; `core.session.TableHit`, `TouchAction`, `TouchEvent` come. After 22:
    `// HAND-REVIEW: T54 — on a plain TouchEvent and a log function, owned by TableEngine; nothing else changed.`
17. Lines 33-34 (`TableHit`) go: it is `:core`'s.
18. Line 51: `internal class CastTouch(private val log: (String) -> Unit) {`.
19. Line 67: `fun onTouch(event: TouchEvent, table: CastTable): Boolean {` and a first line `val nowMs = event.nowMs`.
20. Lines 68-123 by `touch.sed` (step 3), plus one branch after CANCEL's: `TouchAction.OTHER -> Unit` (a `when` over an
    enum must be exhaustive; any other action falls through to `return captured`, as before).

### Host diff, commit C (HAND-REVIEW; the whole of it)
Delete `ar/TableEngine.kt`, `ar/TableEngineHost.kt`, `ar/CastTouch.kt`, `TableTouchPinTest.kt`; `ar/RudeusArView.kt`
gets back `import kz.rudy.ar.core.session.TableEngine` and `import kz.rudy.ar.rememberTableEngine`, and the T54 note
gains "(now `core.session`, pinned by JVM tests)".

## Tests required
P: `ScreenViewTest` in `:core`: `ofMultipliesViewByModelColumnMajor`, `ofKeepsNoReferenceToItsArguments`.
A: `TableTouchPinTest` (package `kz.rudy.ar.ar`, `:androidApp:testDebugUnitTest`); B: the same file as
`TableTouchTest` in `:core`, only the package line and the class name changed. No test of picking exists today
(`androidApp/src/test` holds `NodeRotationTest` only). Harness: T56's `FrameHost` (`TableFrameTest.kt:61-134`, here
`TouchHost`) with slots that carry the two new callbacks, plus (1) a pinhole camera in plain numbers (eye, look-at
point, vertical field of view, view size, margin) built into a `ScreenView` through `ScreenView.of`, with an anchor
that is not the identity; (2) a `FakePort` that un-projects a pixel through that camera onto the plane, answers null
outside a rectangle (the real table) and while its `tracking` flag is false, reads depth from a `(Vec2) -> Float?`,
and records every question in order. Floats in log lines: key and integer fields only. The tap:
- `aTapWalksTheActiveMemberAndAsksThePortOnceForTheWay` — STARTED_WALKING on the next frame; no `hitTable` asked
- `aTapInsideTheBoundaryLeavesTheLevelAlone` — the same `Level` instance, no `onLevel`, no line
- `aTapOutsideTheBoundaryGrowsItThenWalks` — `onLevel` once, before the walk; objects and spawn kept; nobody falls
- `theBoundaryGrowsByTheTapsSquareAndNeverShrinks` — hull of the old boundary and the 2 cm square; twice = once
- `withoutABoundaryNothingGrows` — the floor, and the table before its first scan
- `aTapPastADepthEdgeIsBlockedAtOnceAndGrowsNothing` — BLOCKED from the tap, not from the frame; no target
- `noDepthReadingNeverRefuses`, `aTapWithNobodyOnTheTableDoesNothing`, `tapAndTouchBeforeTheAnchorDoNothing`,
  `theTapsOwnReadingIsTheLastOfTheWay` (`way=` has `walkDepthSamples + 1` entries)
- `aTapDuringALossSetsTheTargetAndKeepsThePause` — camera tracking, anchor not: the only way a hit exists then
- `theWalkOfATapMadeDuringALossIsReportedAsResumedOnly` — T47's finding 1 as found: no STARTED_WALKING
- `tapsAndTouchesNeverPlaceAnAnchor` — 100 of each: `onAnchorPlaced` once, no `anchor_refused`
Picking:
- `aTapOnAMemberOnlyMakesItActive` — and a tap on the active one does nothing at all, no question asked
- `aTapBesideAMemberWalksTheActiveOne`, `pickRectangleIsTheQuadPlusTheMargin` — one pixel outside and one inside the
  rectangle on each of its four sides; the width from the feet depth; sizes 0.5 and 2
- `theNearestCentreWinsAlsoThroughAMemberInFront` — overlapping quads; nearest in pixels, not nearest to the camera
- `oneMemberIsNeverPicked` — a tap on it walks it, a DOWN on it is not captured
- `aMemberInTheAirOrPausedIsNotPicked` — a tap on it walks the active one to the point behind it (as found)
- `aMemberWithoutAPictureIsNotPicked` — never announced with a size, and after `spriteGone`
- `beforeAScreenTheNearestFeetWithinReachArePicked` — `screen` null or 0 x 0: one `hitTable`, `max(1.5 r, 3 cm x scale)`
- `aMemberBehindTheCameraIsNotPicked`, `otherActionsChangeNothing`
- `pickUsesTheLastFramesCameraAndTheLatestSize` — the camera moved after the frame; also on an untracked frame
- `pickAgreesWithTheOldWorldSpaceProjection` — 10 000 seeded cases against old 544-562 transcribed (world point,
  view, projection): the same member, except touches within 0.01 px of a threshold, which are counted (under 1 %)
- `projectionMatchesAPinholeCamera` — feet, head and pixels per metre to 0.01 px of closed-form numbers, both orientations
The grab:
- `aFingerOnAMemberBindsItAndMakesItActive` — `grab pointer=`, true, no question; also when it is already active
- `aFingerOnTheTableIsNotCaptured` — false for the whole stream, so the gesture detector still gets its tap
- `aTapOnAMemberThroughTheGrabWalksItToThePointUnderTheFinger` — DOWN then UP: one hit, one lead (as found)
- `leadIsThrottledToSixtyMsAndFiveMillimetres` — nothing asked at 59 ms, also right after DOWN; under 5 mm a hit, no way
- `aLeadIsClampedIntoTheBoundaryAndGrowsNothing`, `aFingerOffTheTableLeavesTheLastTarget`
- `aLeadPastADepthEdgeKeepsTheLastTarget` — and the refused point still counts as the last one (as found)
- `liftingLeadsOnceMoreThenReleases` — the throttle does not apply, the 5 mm rule does; UP returns true once
- `theStreamStaysCapturedUntilTheLastFingerLifts` — a second finger on the table walks nobody; POINTER_UP releases one
- `twoFingersLeadTwoMembersOneFingerPerMember`, `cancelReleasesEverythingWithoutALead`, `touchLogLinesKeepTheirKeys`,
  `aNewDownDropsWhatTheLastStreamLeft` (a lost UP)
- `aGrabHeldThroughALossLeadsNowhereAndGoesOnAfterIt` — the port answers null; still bound on the frame back
- `aReleaseDuringALossStillReleases` — the member keeps its last target, PAUSED, and finishes after the loss
- `aGrabbedMemberRemovedFromTheCastIsReleased` — `release pointer=` before `cast_remove`; its later MOVE does nothing
That is 42. In `:core` at B, `TableEngineTest` loses `released` (the test above replaces it) and both older harnesses
pass a port that answers nothing; `TableSlotTest` checks the two new fields and their defaults.

Tests not required: `rememberTableEngine`, `RudeusArView`, `SessionPort`, `MotionEvent.toTouch`, anything on a device.
T53's 27 and T56's 28 are not copied to the host: they run against the changed `:core` engine at B.

## Steps
`S` is a scratch directory. Every Gradle call: `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk
./gradlew <tasks> --console=plain -q`. Without `/opt/android-sdk` nothing here can be compiled: say so and stop.
1. Read `ar/RudeusArView.kt` whole, `ar/CastTouch.kt`, `ar/PlaneCoords.kt`, `core/session/*.kt`,
   `core/pipeline/SurfaceBoundary.kt`, `shared/.../TableEngineHost.kt`, the Results of T47, T53 and T56, the
   `hand-review` skill. Run the header's line checks.
2. **Commit P** `core: ArPort, TouchEvent, ScreenView and CameraFrame.screen (T54)`: types only, and the one pure
   function `ScreenView.of` with its two tests. Tasks: `:core:jvmTest :core:compileKotlinIosSimulatorArm64
   :shared:testAndroidHostTest :androidApp:assembleDebug`.
3. **Commit A** `androidApp: taps, picking and the grab move into a host-local table engine and are pinned (T54; hand-review)`.
   Save the old files first (the worktree guard refuses `git` inside `<(...)`): `git show HEAD:<path> > $S/old.kt` for
   `RudeusArView.kt`, `$S/oldtouch.kt` for `CastTouch.kt`. Make the two copies with T56's two `sed` commands (its step
   3), fix their imports and header notes, add the contract's members, make the twenty edits. `$S/t54.sed`, 16
   commands on 9 lines:
   ```
   s/HAND-REVIEW: //; s/Log\.i(GATE_TAG, /log(/; s/currentSlot\./current()./g
   /val profile = slotState\.profile/d; s/slotState\.profile/profile/g; s/event\.x, event\.y/xPx, yPx/
   s/depthGapsOnWay(frame, a, from, uv, WALK_DEPTH_SAMPLES)/port.depthGapsOnWay(from, uv, walkDepthSamples)/
   s/depthGapsOnWay(frame, a, from, target, LEAD_DEPTH_SAMPLES)/port.depthGapsOnWay(from, target, leadDepthSamples)/
   s/hits\.firstOrNull { it\.trackable is DepthPoint }?\.let { planeHeight(a, it\.hitPose) }/hit.depthGapM/
   s/viewSize\[0\]/view?.widthPx ?: 0/; s/viewSize\[1\]/view?.heightPx ?: 0/; s/if (w <= 0/if (view == null || w <= 0/
   s/grabMarginPx\[0\]/view.pickMarginPx/; s/rigs\[m\.id\]?\.sprite/quads[m.id]/
   s/projector\.project(toWorld(a, s\.pos, \(.*\)), w, h, /project(view, s.pos, \1, /
   s/projector\.pxPerM(feet\[2\], w)/pxPerM(view, feet[2])/
   ```
   `$S/touch.sed`, 7 commands on 3 lines:
   ```
   s/MotionEvent\.ACTION_/TouchAction./g; s/event\.actionMasked/event.action/g; s/event\.pointerCount/event.pointers.size/
   s/event\.getX(\([a-z0-9]*\))/event.pointers[\1].xPx/g; s/event\.getY(\([a-z0-9]*\))/event.pointers[\1].yPx/g
   s/event\.getPointerId(\([a-z0-9]*\))/event.pointers[\1].id/g; s/Log\.i(GATE_TAG, /log(/
   ```
   For each row, `sed -n '<old lines>p' $S/old.kt | sed -f $S/t54.sed | diff -w - <(sed -n '<body lines>p' <host TableEngine.kt>)`
   (run against a2dae59 while this brief was written; the hunks listed are the only ones):

   | Engine code | Old lines | May differ |
   |---|---|---|
   | `confirmSurface` | 408-416 | two hunks: `private`; the `level = grown` line is gone (the host's mirror) |
   | `onTap`, after its guard and `val uv = hit.uv` | 728-752 | one hunk: the comment lines 737-738 stay in the host |
   | `table.grabbable` | 536-563 | two hunks: 538-539 are `val view = screen`; 544 (`projector.load`) is gone |
   | `table.lead` | 572-583 | one hunk: 573-574 are gone |
   | `table`: `feet` / `head`, `nearestOnGround`, `onGrabbed` | 524-525, 565-570, 585 | nothing (the script drops `val profile`) |
   | `table.hitUv` | 527-534 | by eye, one line: the port's question with `profile.tapHeightToleranceM` |
   | `project`, `pxPerM`, `mulMV` | 267-276, 279 | by eye: the plane point for the pose, `mulMV(out, m, v)` for the two `multiplyMV`, `view.widthPx` / `heightPx`, `view.eyeToClip[0]` for `proj[0]` |
   | `onTouch`, `spriteGone`, the three one-line changes | none | by eye, against the contract |

   `sed -n '51,124p' $S/oldtouch.kt | sed -f $S/touch.sed | diff -w - <(the class to the end of the new ar/CastTouch.kt)`:
   three hunks, the constructor, the signature with `val nowMs`, the `OTHER` branch. The host's own move,
   `sed -n '528,533p' $S/old.kt` against `SessionPort.hitTable`: the two lines of edit 4's comment. The copies, with
   `strip() { grep -vE '^\s*(//|/\*|\*)' "$1"; }`: `diff <(strip <core TableEngine.kt>) <(strip <host TableEngine.kt>)`
   may show the package line, `internal` on three classes and on `loop`, import lines, `onMemberRemoved` replaced
   (parameter and call), and otherwise **only added lines**; the same for `TableEngineHost.kt` (package, `internal`
   twice, imports, the parameter and its `rememberUpdatedState` line replaced, added lines). Then
   `:androidApp:testDebugUnitTest :androidApp:assembleDebug`.
4. **Commit B** `core, shared: taps, picking and the grab in TableEngine with the same tests (T54)`. `CastTouch.kt`
   goes to `:core` by `sed` of the package line, without its `core.session` imports and `HAND-REVIEW:` prefixes. The
   `strip` diffs of step 3, now three with `CastTouch.kt`, may show only package, `internal` and import lines;
   `TableTouchPinTest.kt` against `TableTouchTest.kt` only the package line and the class name. Tasks: `:core:jvmTest
   :core:compileKotlinIosSimulatorArm64 :core:compileTestKotlinIosSimulatorArm64 :shared:testAndroidHostTest
   :shared:compileKotlinIosSimulatorArm64 :shared:compileTestKotlinIosSimulatorArm64 :androidApp:testDebugUnitTest`.
   The same 42 assertions are green against both engines at this commit.
5. **Commit C** `androidApp: taps, picking and the grab come from :core and :shared (T54; hand-review)`: host diff C only.
6. `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk scripts/verify.sh`.
7. Mutation check on the `:core` files, one at a time, each restored byte for byte (`cmp`): the pick without its
   margin; the first member in the rectangle instead of the nearest; PAUSED members pickable; a tap that reports the
   active member again; `walkTo` before `confirmSurface`; a lead that grows the boundary; a lead without
   `nearestInside`; the way's readings without the tap's own; no throttle; UP without its last lead; a stream that is
   captured per pointer; `syncCast` without the unbind. Each must turn a named test red; list them in the Result.
8. Fill the Result in its own commit, set `status: review`. The final message names the four commits and the host
   files, and says "needs your Pixel run" with the check below.

## Done when
- `scripts/verify.sh` exit 0; core tests up by at least 44 (42 + 2), shared unchanged, android back to its old count
- the diffs of steps 3 and 4 were as described (paste their output into the Result); 12 of 12 mutations caught
- `grep -n "loop" androidApp/src/main/kotlin/kz/rudy/ar/ar/RudeusArView.kt | grep -vE '^[0-9]+:\s*//'` prints nothing
  (only comment lines are left), `grep -rn "\.loop\b" androidApp/src/main shared/src/commonMain` prints nothing, and
  `loop` is `internal` in `core/.../session/TableEngine.kt`, so a host line that reads it does not compile
- `git diff <A>^..<C> --stat -- androidApp/src/main`: no `ar/TableEngine*.kt`, no `ar/CastTouch.kt`; `RudeusArView.kt`
  about 70 lines in and 150 out (804 -> about 725)
- `grep -rn "HAND-REVIEW" core/src shared/src` prints nothing; no file outside "Owns" changed
- **The one Pixel check (human, about two minutes), "tap, pick, drag, grow".** Two characters on a table with a cup,
  scanned once, footprints on.
  1. Tap the table to the right of the active character, then far to its left: it walks to each tap, the other stays.
  2. Tap the other character on its body, head included: its chip lights and it takes at most a small step toward
     the finger; the first one does not move. Tap the table: now the second one walks.
  3. Put a finger on a character and drag it slowly 30 cm across the table, then lift: it walks after the finger,
     never jumps, and stops where the finger left.
  4. Hold one character, put a second finger on the other and move both: each follows its own finger. Lift both.
     Then hold one and tap the bare table with another finger: nobody walks to that tap.
  5. Drag a character past the table edge: it stops at the edge and does not fall. Tap the floor beyond: no walk.
  6. Tap the table just outside the drawn boundary loop: the loop grows to hold the tap, here and on the mini-map,
     and the character walks there. Tap inside the loop: it does not change.
  7. Hold a character, cover the lens for 2 seconds and lift the finger under the cover. Uncover: it finishes its
     last walk, and a new drag works at once.
  In `scripts/device.sh log`: each walking tap is a `tap u=` line followed by `tap_depth_gap=.. way=[7 values] id=`;
  step 2 gives `grab pointer=0 id=`, `active id=` and `release pointer=0 id=`, and no `tap_depth_gap`; step 4 a
  `grab pointer=1 id=`; step 5 `lead_past_edge id=` or `tap_past_edge`; step 6 a `boundary_grown n=` for the tap
  outside and none for the tap inside; every `grab` has its `release`, the one of step 7 during the cover; one
  `anchor t_ms=0` line and no `anchor_refused`. What a fault means: a walk to the wrong place, or none, is the
  `TableHit` of edit 14 or the port's `depthGapsOnWay`; a character that cannot be picked, or is picked through
  another, is the `ScreenView` (edits 3 and 13) or the sizes of edit 7; a grab that sticks is UP / CANCEL in
  `toTouch`, one that jumps is the pointer index, one that drops is the pointer id or the clock; a loop that does not
  grow on screen while the character walks is the `level` mirror, one that grows wrongly is the engine. Revert C and
  look again: if it is now right, the fault is in what differs between the host copies and the common ones (imports
  and visibility only, so report it). If it is still wrong, revert B and A together (decision 11) and report.

## What the duel stage gets from this seam (not built here)
The host now says only "this pixel was tapped and this is the table under it" and "these pointers did this"; it names
no member, no level and no loop. A duel stage therefore needs no host change: it replaces the body of `onTap` (the
same `TableHit.uv` becomes `DuelInput.MoveTo` for the player's fighter, inside the arena instead of a growing
boundary) and the four answers of `CastTable` behind `onTouch` (`grabbable` answers null, so no stream is captured
and every finger stays a tap; a swipe that becomes a Dash can be read from the same `TouchEvent` later). `CastTouch`,
`ArPort`, `ScreenView` and the picture sizes are stage-neutral and stay. The stage interface, when T50's brief
extracts it, holds `onTap`, the `CastTable` answers and T56's part (steps 4-5 of `onFrame`, `level`, `standing()`):
a `:core`-only change pinned by this brief's tests and T56's.

## After T54 (not built here)
- `ar/RudeusArView.kt` is about 725 lines, about 385 of them code: about 95 scan orchestration (`answerOwed`, the
  request effect, `startScan`, the frame's scan block: S5, which also takes the early `trackingEdge` call, the
  `level` mirror and `SlotState`), about 30 the pre-anchor gate (S8, optional), about 260 platform glue (session
  configuration, the anchor, `FrameInput` with its `ScreenView`, node writes, `SessionPort`, the touch mapping, rigs,
  overlays, probes). `ar/CastTouch.kt` is gone. S6 (capture) and S7 (sounds) are other files.
- `ArPort` gains `grabScanFrame` in S5, and `pictureAspect` only if T50's pictures need it. Open, not fixed here: the
  engine keeps a removed member's poser and drawn facing (T56's note; `spriteGone` is where they could go).

## Result (filled on acceptance)
- commits, measured counts, the step-3 and step-4 diffs, the mutations: ...
- found by the characterization tests; deviations from the letter of the brief: ...
- Pixel check ("tap, pick, drag, grow"): ...
- cut/deferred: ...
