# T54 — thin-host-step-4

status: review
created: 2026-11-12
phase: v2 wave 3 (ai/build-plan-v2.md, "iOS readiness": thin host). Step S4 of the map in ai/tasks/T43-thin-host-map.md
gate: G1 foundation (`scripts/verify.sh` green, `:core` and `:shared` still compile for iOS); a precondition of T50
cut-if-slips: never cut. Two revert points, see "Done when": commit C alone, then commits B and A together
depends: T56 merged (db28680); its Pixel check is with the user: T54 is built in a worktree meanwhile, merged after it
tier: rudeus-opus-extra (the orchestrator's decision: every design choice is taken below; what is left is a line-exact
  move proved by script, projection maths and characterization tests, the job that tier did for T52, T53 and T56)

**Every `file:line` below is a line of 4ab4100** (main after the T56 merge; `ar/RudeusArView.kt` has 804 lines,
`ar/CastTouch.kt` 124, `core/.../session/TableEngine.kt` 344). Check before trusting the rest: run
`sed -n '254p;309p;316p;401p;408p;494p;522p;544p;730p;751p' androidApp/src/main/kotlin/kz/rudy/ar/ar/RudeusArView.kt`
and compare with: `private class ScreenProjector {`, `val touch = remember { CastTouch() }`, `val loop = game.loop`,
`fun activeId(): String? = game.activeId()`, `fun confirmSurface(uv: Vec2) {`, `fun tableHit(hits: List<HitResult>,
a: Anchor?): HitResult? {`, `val table = remember {`, `projector.load(frame)`, `val tapped =
table.grabbable(event.x, event.y)`, `loop.walkTo(active, uv) //`. In `TableEngine.kt`, lines 86, 213, 246: `val loop =
GameLoop(`, `onMemberRemoved(id)`, `fun spriteBuilt(`. Lines that only moved: shift the numbers of that region and say
so in the Result. A moved body whose text changed: stop and report.
Paths without a module are under `androidApp/src/main/kotlin/kz/rudy/ar/`.

## Goal
What a finger does after the anchor exists is decided in `core.session`: `TableEngine.onTap` (pick a character or walk
the active one, the depth edge on the way, the boundary growth) and `TableEngine.onTouch` (the grab, the lead, the
release). The Android host turns a `MotionEvent` into plain values and answers three questions through `ArPort`: the
table under a pixel, the depth readings along a walk, the camera as a screen when a touch needs a pick. Today those
lines are closures in `ar/RudeusArView.kt` (254-280, 402-416, 520-592, 725-753) and `ar/CastTouch.kt`, and none has
ever run in a test. After this brief no host line reads `engine.loop`, an iOS host writes none of this, and a duel
stage can read the same tap as `DuelInput.MoveTo` without a host change. The player sees no difference.

## Decisions taken by the orchestrator (not open)
- Scope is everything a finger does after the anchor: the table tap (walk target, `confirmSurface`, the depth-gap
  check), picking a character on screen, the grab and the lead, `unbindMember` and T53's `onMemberRemoved`. The
  placement tap and the pre-anchor gate stay in the host: they create the one anchor.
- After T54 no host line reads `engine.loop` and `loop` is not public API: the precondition for a second stage.
- The seam is stage-neutral plain data: the host says what was touched, the engine decides what it means. No `Stage`
  interface, no duel code, no scan (S5), no capture (S6); `pictureAspect` and `grabScanFrame` belong to later steps.
- **The picking projection is a third `ArPort` question, asked at pick time; `CameraFrame` is not touched.** "Fewer
  ARCore calls made differently" wins over "picking data on `CameraFrame`": the view and projection matrices are
  read inside a pick, as at 544, not on every anchored frame; no frame after the anchor exists in which the
  table-point fallback would pick where the old code did not; the engine keeps no camera state between frames.
  Three questions instead of two is the smaller cost; an iOS host answers it from the current `ARFrame`.
- `spriteGone(id)` also drops that member's poser and drawn facing (T56's reviewer: the engine kept them for a
  removed member, and only Android's `spriteBuilt` on re-add hid it).
- Proof as T52 / T53 / T56: P, A, script and old-line table, pin tests, B with the same tests, C. A red test against
  the host-local copy means the test is wrong or it found something: report it, never change a moved body.

## Decisions taken here (not open)
1. **The tap's hit test is the host's; the way and the lead's hit are the engine's questions.** The tap handler makes
   one `frame.hitTest(event)` before it knows whether an anchor exists, and the placement tap needs the same result
   (709-724). So the host keeps those lines and passes the point in; a tap that misses the table is not reported, as
   today (725). `depthGapsOnWay` needs where the member stands and the clamped target (736, 576), and the lead's hit
   test runs only after the 60 ms throttle (`ar/CastTouch.kt:85`): the engine asks both when they are asked today.
2. **The tap's depth reading stays lazy**: `onTap(xPx, yPx, uv, depthGapM: () -> Float?)`. The host wraps line 739 in
   a lambda and the engine calls it where 739 ran, after the pick and only for a tap that walks (T53 passed
   `cameraRight` the same way), so no reading is taken that is not taken today.
3. **Nothing is queued.** Taps and touches call the engine from their callback, between frames, as today; ARCore
   answers a hit test only on the latest frame (`/websites/developers_google_ar`, `Frame.hitTest`).
4. **`hitTable` carries the tolerance** (T43's signature): the port must exist before the engine is built (315), and
   the host's `tableHit` reads the profile through `slotState` (331, 495). The hit rule becomes a top-level
   `surfaceHit(hits, a, toleranceM)`; the local `tableHit` calls it, so its pre-anchor callers (649, 710) are untouched.
5. **`screenView(): ScreenView?` is asked where 541-544 stood**, after the two-member check and the ground filter.
   `ScreenView` is two matrices, not one `planeToClip`: line 279 needs the projection's `[0]` and line 274 the eye
   depth, and a product gives neither. `ScreenView.of` multiplies in `:core`, tested, so the host makes no matrix
   product. Null means the table-point fallback of 543. The host's answer keeps the old checks in the old order (no
   frame, no anchor, a view not laid out: null, no matrix read). Without a frame the old code picked nobody
   (538-539); now the fallback's `hitTable` answers null for the same reason, with no ARCore call either way.
6. **The picture's size reaches the engine by `spriteBuilt(id, facesRight, widthM, heightM)`**, and `spriteGone(id)`
   where the rig leaves the map (349), so the engine's sizes have the same keys as `rigs` at every moment (551: a
   member without a rig is not picked). No `pictureAspect` question; `CharacterSprite`'s exact floats are kept.
7. **`loop` becomes `internal`, not private**: T53's and T56's 55 tests and the new ones read physics state through
   it. From commit C a host line with `game.loop` does not compile.
8. **`onMemberRemoved` goes**; the engine owns the `CastTouch` and unbinds in `syncCast` at the same place (213). The
   constructor gains `port`, `walkDepthSamples`, `leadDepthSamples`, no defaults: the host keeps its numbers (233,
   187, the clip planes 190-191, `GRAB_MARGIN_DP` 184 as `ScreenView.pickMarginPx`). The `GRAB_*` move with `CastTouch`.
9. **`TouchAction` has `OTHER`**: any other `MotionEvent` action returns the captured flag today (`ar/CastTouch.kt:108`).
   `nowMs` stays the host clock at the callback (591), not the event's time.
10. **Left in the host, with the line**: `probe.onTap` (727, needs the world pose); the Compose `level` of the overlay
    and the occluders (413), mirrored after `onTap` by an identity compare; the `CAST_TOUCH_ENABLED` switch and the
    anchor check (591); which gesture is a tap (755-758, a Pixel finding); the `MotionEvent` mapping.
11. **The projection cannot be proved by substitution or bit for bit**: `android.opengl.Matrix` does not run on the
    JVM, and the engine multiplies one matrix where the host composed a world point per member (552-553). Results
    differ in the last bits; a pick changes only when the finger is within about 0.001 px of a threshold. Three
    tests cover it: `ScreenMatrixTest` (ARCore's `Pose` runs on the JVM; tried while writing this: over 100 000 poses
    `toMatrix` times a point is within 6e-7 m of `compose`), `pickAgreesWithTheOldWorldSpaceProjection` and
    `projectionMatchesAPinholeCamera`; then the Pixel check.
12. **Revert points are T56's: C alone, then B and A together.** B changes what the T56 host calls (`port` for
    `onMemberRemoved`, `loop` internal), so A cannot be reverted while B stays. P can stay.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- core/src/commonMain/kotlin/kz/rudy/ar/core/session/ArPort.kt, TouchEvent.kt (new, P)
- core/src/commonTest/kotlin/kz/rudy/ar/core/session/ScreenViewTest.kt (new, P)
- core/src/commonMain/kotlin/kz/rudy/ar/core/session/TableEngine.kt (B), CastTouch.kt (new, B)
- core/src/commonTest/kotlin/kz/rudy/ar/core/session/TableTouchTest.kt (new, B); TableEngineTest.kt (B: lines 55, 65,
  93, and the four `released` assertions 191, 311, 319, 338 go); TableFrameTest.kt (B: the constructor call, 76-79)
- shared/src/commonMain/kotlin/kz/rudy/ar/TableEngineHost.kt, shared/src/commonTest/kotlin/kz/rudy/ar/TableSlotTest.kt (B)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/TableEngine.kt, TableEngineHost.kt (HAND-REVIEW: new in A, deleted in C)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/CastTouch.kt (HAND-REVIEW: edited in A, deleted in C)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/RudeusArView.kt (HAND-REVIEW: A and C, only the edits below)
- androidApp/src/test/kotlin/kz/rudy/ar/ar/TableTouchPinTest.kt (new in A, deleted in C), ScreenMatrixTest.kt (new in A, stays)
- ai/tasks/T54-thin-host-step-4.md (the Result section)

Does not move and must not touch: the pre-anchor gate and `placeAnchor` (506-519, 635-655, 704-724), the scan block
and `startScan` (378-400, 417-489, 662-690), the frame step (691-696), `cameraRight` (501-505), `ar/PlaneCoords.kt`,
`ar/CharacterNode.kt`, `ar/GateProbes.kt`, `FrameInput.kt`, `GameLoop.kt`, `BillboardPoser.kt`, `core/pipeline/**`,
`core/physics/**`, `core/duel/**`, `ArSlot.kt`, `App.kt`, every Gradle file.

## Contract
```kotlin
package kz.rudy.ar.core.session

// ArPort.kt (P). What only the AR session can answer: main thread, after the anchor, about the latest frame.
interface ArPort {
    // The anchor's surface under a view pixel: a plane hit inside its polygon, within toleranceM of the anchor
    // plane, with the depth reading there. Null: the ray misses it, no frame, or the camera is not tracking.
    fun hitTable(xPx: Float, yPx: Float, toleranceM: Float): TableHit?                 // <- 527-534
    // Depth readings at `samples` points of the walk (point i at from + (to - from) * i / samples), as height above
    // the plane in metres, null where there is none. No depth sensor: all nulls, which never refuses a walk.
    fun depthGapsOnWay(from: Vec2, to: Vec2, samples: Int): List<Float?>               // <- ar/PlaneCoords.kt:32-46
    // The latest frame's camera as a screen. Asked only when a touch needs a pick, never per frame. Null: no frame,
    // no anchor, or the view is not laid out; the engine then picks by the table point.
    fun screenView(): ScreenView?                                                      // <- 538-539, 541-544, 546
}
class TableHit(val uv: Vec2, val depthGapM: Float?)                                    // from ar/CastTouch.kt:33-34
class ScreenView(                  // column-major 4x4, as OpenGL, ARCore and ARKit give them
    val planeToEye: FloatArray,    // 16: (u, h, v, 1) in plane coordinates -> eye space (-Z ahead), display-oriented
    val eyeToClip: FloatArray,     // 16: this view's projection; [0] is 2 * focal / width
    val widthPx: Int, val heightPx: Int,   // the AR view, both > 0
    val pickMarginPx: Float,       // a pick rectangle grows by this on every side (Android: GRAB_MARGIN_DP * density)
) { companion object { fun of(worldToEye: FloatArray, planeToWorld: FloatArray, eyeToClip: FloatArray, widthPx: Int,
    heightPx: Int, pickMarginPx: Float): ScreenView } }   // planeToEye = worldToEye x planeToWorld; fresh arrays

// TouchEvent.kt (P). View pixels, origin top-left, y down. UP carries the lifting pointer at index 0.
class TouchEvent(val action: TouchAction, val actionIndex: Int, val pointers: List<TouchPoint>, val nowMs: Long)
enum class TouchAction { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL, OTHER }
class TouchPoint(val id: Int, val xPx: Float, val yPx: Float)

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
    fun spriteGone(id: String)                      // that member's size, poser and drawn facing are forgotten
    // uv = the table under the tap; depthGapM is asked at most once, only when the tap is about to walk
    fun onTap(xPx: Float, yPx: Float, uv: Vec2, depthGapM: () -> Float?)               // <- 728-752, 408-416
    fun onTouch(e: TouchEvent): Boolean             // true = this stream is the engine's   <- 591, ar/CastTouch.kt
}
// CastTouch.kt (A edited in place, B :core): internal class CastTouch(private val log: (String) -> Unit) with
// fun onTouch(event: TouchEvent, table: CastTable): Boolean and unbindMember; CastTable and the GRAB_* as they are.
```
Private state of the engine, plain fields: `touch = CastTouch(log)`, `quads: HashMap<String, Quad>` (`private class
Quad(val widthM: Float, val heightM: Float)`), three `FloatArray(4)` for the projection, and `table`, an `object :
CastTable` holding old 523-586 with `hitUv(x, y) = port.hitTable(x, y, profile.tapHeightToleranceM)`. No camera
state: `onFrame` and `FrameInput` are untouched. Two T56 bodies change: `syncCast` calls `touch.unbindMember(id)`
where it called `onMemberRemoved(id)`; `spriteBuilt` also stores or drops the `Quad`. `spriteGone` removes the id
from `quads`, `posers` and `drawnRight`. `onTap` and `onTouch` return at once before the anchor (new guards, silent;
neither can fire on Android).

`rememberTableEngine(slot, members, playEmoteSound, port, log, feetDepthBias, reappearAfterTrackingMs,
walkDepthSamples, leadDepthSamples, separate = true)`: `port` goes straight into the engine at the first composition,
like the Pixel numbers (the host remembers it without keys); the `currentRemoved` line goes. `toTableSlot` fills the
two new fields from `ArSlot.onActiveChanged` and `ArSlot.onLevel`. No sixth effect.

**What must stay exactly as it is**: every rule under "Tests required", in the order the old lines apply it (a tap
picks first, then reads its depth, then the way, the `tap_depth_gap=` line, then BLOCKED or growth and walk; a grab
binds, leads under its throttle, leads once more on lifting, releases). Nothing here looks at tracking: PAUSED still
freezes and never resets, and a walk set during a loss waits for it. One anchor per session: neither entry point nor
`ArPort` can create one. Log lines byte for byte (`tap_depth_gap=`, `tap_past_edge`, `boundary_grown`,
`lead_past_edge`, `grab pointer=`, `release pointer=`), no new line.

**What is not identical, and why it is harmless.** (a) Decision 11: last-bit differences in the projection. (b)
`spriteGone` drops the poser and the drawn facing: not observable on Android, where the rig leaves the map only when
the member left the slot, the next frame's `syncCast` removes it before any pose, and a member that comes back gets
a new rig and so a `spriteBuilt`. (c) The Compose `level` is written after `onLevel` and `walkTo` instead of before
them; nothing observes it in between. (d) The null returns of 538-539 are the port's answer (decision 5), those of
573-574 are gone: all unreachable after the anchor. (e) The host allocates one `TouchEvent` per touch event after
the anchor, and a `ScreenView` with two `FloatArray(16)` per pick.

**ARCore / SceneView calls made differently** (T56 had one such place; T54 has one, a read inside a pick):
- `Anchor.getPose` + `Pose.toMatrix` (new to the host; documented column-major; in 1.54.0) once per pick, where
  552-553 made `Anchor.getPose`, `Pose.makeTranslation` and `Pose.compose` twice per ground member. Unavoidable: the
  projection is computed in `:core` on plain numbers. It is the same projection: decision 11's three tests.
Unchanged in count and moment: `Camera.getViewMatrix` / `getProjectionMatrix` once per pick with two members, a frame,
an anchor and a laid-out view (544); `Frame.hitTest(event)` once per tap (709); the tap's depth reading only for a
tap that walks (739); `Frame.hitTest(x, y)` once per lead and per fallback pick (530); the 6 or 2 ray tests of
`depthGapsOnWay`. Nothing is read per frame. No SceneView call is added, removed or made differently.

### Host diff, commit A (HAND-REVIEW)
New `ar/TableEngine.kt` and `ar/TableEngineHost.kt` (step 3), each opening with one
`// HAND-REVIEW: T54 — host-local copy for one brief ...` note. Then nineteen edits. In `ar/RudeusArView.kt`:
1. Imports: 30, 31, 55, 56, 69, 70 go (`Pose`, `GlMatrix`, `CharacterEvent`, `Mode`, `core.session.TableEngine`,
   `rememberTableEngine`); `core.session.ArPort`, `ScreenView`, `TableHit`, `TouchAction`, `TouchEvent`, `TouchPoint` come.
2. Header, after 181: `// HAND-REVIEW: T54 — what a finger does after the anchor is TableEngine.onTap / onTouch: this`
   `// file maps the MotionEvent and answers ArPort (the table under a pixel, the depth on the way, the camera as a`
   `// ScreenView when a touch needs a pick). The T32 notes on CastTable above describe code that is the engine's now.`
   `// One anchor per session (only the placement tap creates it), PAUSED still freezes and never resets, scans untouched.`
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
   private class SessionPort(
       private val latestFrame: Array<Frame?>, private val anchorRef: Array<Anchor?>,
       private val viewSize: IntArray, private val grabMarginPx: FloatArray, private val projector: ScreenProjector,
   ) : ArPort {
       override fun hitTable(xPx: Float, yPx: Float, toleranceM: Float): TableHit? {
           // old 528-533 as they are, but `frame.hitTest(xPx, yPx)` and `surfaceHit(hits, a, toleranceM)`
       }
       override fun depthGapsOnWay(from: Vec2, to: Vec2, samples: Int): List<Float?> {
           // `frame` and `a` as in hitTable, but `?: return List(samples) { null }`; then PlaneCoords.kt's function
           // by its full name: `return kz.rudy.ar.ar.depthGapsOnWay(frame, a, from, to, samples)`
       }
       override fun screenView(): ScreenView? {
           val frame = latestFrame[0] ?: return null                  // old 538
           val a = anchorRef[0] ?: return null                        // old 539
           val w = viewSize[0]                                        // old 541
           val h = viewSize[1]                                        // old 542
           if (w <= 0 || h <= 0) return null                          // old 543: the engine picks by the table point
           return projector.screen(frame, a, w, h, grabMarginPx[0])   // old 544 and 546
       }
   }
   ```
   (the `// old` notes are this brief's, not the file's), and `private fun MotionEvent.toTouch(nowMs: Long):
   TouchEvent`: `actionMasked` mapped name for name by a `when`
   (`ACTION_DOWN`, `ACTION_POINTER_DOWN`, `ACTION_MOVE`, `ACTION_POINTER_UP`, `ACTION_UP`, `ACTION_CANCEL`; `else ->
   TouchAction.OTHER`), then `actionIndex`, `List(pointerCount) { TouchPoint(getPointerId(it), getX(it), getY(it)) }`
   and `nowMs`.
5. After 313: `val port = remember { SessionPort(latestFrame, anchorRef, viewSize, grabMarginPx, projector) }`. Line
   309 (`touch`) goes.
6. Line 315: `onMemberRemoved = touch::unbindMember` -> `port = port`; `walkDepthSamples = WALK_DEPTH_SAMPLES,
   leadDepthSamples = LEAD_DEPTH_SAMPLES,` before `separate`. Line 316 (`val loop`) goes.
7. Line 344: `game.spriteBuilt(c.id, sprite.facesRight, sprite.widthM, sprite.heightM)`; its comment gains
   `; T54: and its size, for picking`.
8. Line 349: the `if (rigs[c.id] === rig)` gets a block with `rigs.remove(c.id)` and
   `game.spriteGone(c.id) // T54: no rig, no pick, as before; the engine forgets this picture`.
9. Lines 401-416 (`activeId`, `confirmSurface` and its KDoc) go.
10. Lines 490-500: the KDoc moves onto `surfaceHit`; the function becomes
    `fun tableHit(hits: List<HitResult>, a: Anchor?): HitResult? = surfaceHit(hits, a, slotState.profile.tapHeightToleranceM)`.
11. Lines 520-587 (the `CastTable` object and its note) go.
12. Line 591: `touch.onTouch(event, SystemClock.elapsedRealtime(), table)` ->
    `game.onTouch(event.toTouch(SystemClock.elapsedRealtime()))`; 588-589 gain `T54: the bookkeeping is TableEngine's`.
13. The tap, 725-753: 725-727 stay; 728-736 go; 737-738 stay; 739 keeps its expression inside a lambda,
    `val depthGap = { hits.firstOrNull { it.trackable is DepthPoint }?.let { planeHeight(a, it.hitPose) } }`;
    740-752 become
    ```kotlin
    // HAND-REVIEW: T54 — what this tap means is TableEngine.onTap (pick a member, the depth edge on the way, the
    // boundary growth, the walk). The hit test stays here: the placement tap shares it. The engine calls `depthGap`
    // where line 739 ran, only for a tap that walks. `level` mirrors a grown boundary for the overlay and the
    // occluders, as confirmSurface wrote it. This branch cannot create an anchor.
    val before = game.level
    game.onTap(event.x, event.y, uv, depthGap)
    game.level.let { if (it !== before) level = it }
    ```
14. Line 760: the comment's `(CastTouch.kt)` -> `(TableEngine.onTouch)`.

In `ar/CastTouch.kt`:
15. Imports 3-4 (`Log`, `MotionEvent`) go; `core.session.TableHit`, `TouchAction`, `TouchEvent` come. After 22:
    `// HAND-REVIEW: T54 — on a plain TouchEvent and a log function, owned by TableEngine; nothing else changed.`
16. Lines 33-34 (`TableHit`) go: it is `:core`'s.
17. Line 51: `internal class CastTouch(private val log: (String) -> Unit) {`.
18. Line 67: `fun onTouch(event: TouchEvent, table: CastTable): Boolean {` and a first line `val nowMs = event.nowMs`.
19. Lines 68-123 by `touch.sed` (step 3), plus one branch after CANCEL's: `TouchAction.OTHER -> Unit` (a `when` over an
    enum must be exhaustive; any other action falls through to `return captured`, as before).
Line 695 and its note (691-694) are not touched: nothing is added to the frame.

### Host diff, commit C (HAND-REVIEW; the whole of it)
Delete `ar/TableEngine.kt`, `ar/TableEngineHost.kt`, `ar/CastTouch.kt`, `TableTouchPinTest.kt`; `ar/RudeusArView.kt`
gets back `import kz.rudy.ar.core.session.TableEngine` and `import kz.rudy.ar.rememberTableEngine`, and the T54 note
gains "(now `core.session`, pinned by JVM tests)".

## Tests required
P: `ScreenViewTest` in `:core`: `ofMultipliesViewByModelColumnMajor`, `ofKeepsNoReferenceToItsArguments`.
A, android only, stays: `ScreenMatrixTest.anchorMatrixTimesAPlanePointIsToWorld` — 10 000 seeded ARCore `Pose`s:
`toMatrix` times `(u, h, v, 1)` is within 1e-5 m of `pose.compose(Pose.makeTranslation(u, h, v))`, the expression of
`toWorld` (`ar/PlaneCoords.kt:52-53`).
A: `TableTouchPinTest` (package `kz.rudy.ar.ar`, `:androidApp:testDebugUnitTest`); B: the same file as
`TableTouchTest` in `:core`, only the package line and the class name changed. No test of picking exists today
(`androidApp/src/test` holds `NodeRotationTest` only). Harness: T56's `FrameHost` (`TableFrameTest.kt:61-134`, here
`TouchHost`) with slots that carry the two new callbacks, plus (1) a pinhole camera in plain numbers (eye, look-at
point, vertical field of view, view size, margin) built into a `ScreenView` through `ScreenView.of`, with an anchor
that is not the identity; (2) a `FakePort` that answers `screenView` with that camera (or null), un-projects a pixel
through it onto the plane, answers null outside a rectangle (the real table) and while its `tracking` flag is false,
reads depth from a `(Vec2) -> Float?`, and records every question in order. Floats in log lines: key and integer
fields only. The tap:
- `aTapWalksTheActiveMemberAndAsksThePortOnceForTheWay` — STARTED_WALKING on the next frame; no `hitTable` asked
- `aTapInsideTheBoundaryLeavesTheLevelAlone` — the same `Level` instance, no `onLevel`, no line
- `aTapOutsideTheBoundaryGrowsItThenWalks` — `onLevel` once, before the walk; objects and spawn kept; nobody falls
- `theBoundaryGrowsByTheTapsSquareAndNeverShrinks` — hull of the old boundary and the 2 cm square; twice = once
- `withoutABoundaryNothingGrows` — the floor, and the table before its first scan
- `aTapPastADepthEdgeIsBlockedAtOnceAndGrowsNothing` — BLOCKED from the tap, not from the frame; no target
- `noDepthReadingNeverRefuses`, `aTapWithNobodyOnTheTableDoesNothing`, `tapAndTouchBeforeTheAnchorDoNothing`,
  `theTapsOwnReadingIsTheLastOfTheWay` (`way=` has `walkDepthSamples + 1` entries)
- `theTapsDepthIsReadOnlyWhenTheTapWalks` — once, after the pick and before the way; never for a tap that selects
- `aTapDuringALossSetsTheTargetAndKeepsThePause` — camera tracking, anchor not: the only way a hit exists then
- `theWalkOfATapMadeDuringALossIsReportedAsResumedOnly` — T47's finding 1 as found: no STARTED_WALKING
- `tapsAndTouchesNeverPlaceAnAnchor` — 100 of each: `onAnchorPlaced` once, no `anchor_refused`
Picking:
- `aTapOnAMemberOnlyMakesItActive` — and a tap on the active one does nothing more: no callback, no depth, no way
- `aTapBesideAMemberWalksTheActiveOne`, `pickRectangleIsTheQuadPlusTheMargin` — one pixel outside and one inside the
  rectangle on each of its four sides; the width from the feet depth; sizes 0.5 and 2 of the latest slot
- `theNearestCentreWinsAlsoThroughAMemberInFront` — overlapping quads; nearest in pixels, not nearest to the camera
- `oneMemberIsNeverPicked` — a tap on it walks it, a DOWN on it is not captured, the screen is not asked
- `aMemberInTheAirOrPausedIsNotPicked` — a tap on it walks the active one to the point behind it (as found)
- `aMemberWithoutAPictureIsNotPicked` — never announced with a size, and after `spriteGone`
- `withoutAScreenTheNearestFeetWithinReachArePicked` — `screenView` null: one `hitTable`, `max(1.5 r, 3 cm x scale)`;
  a port without a frame answers both null and nobody is picked
- `pickAsksTheScreenOnlyWhenATouchNeedsIt` — once per DOWN, POINTER_DOWN and tap with two members; never on MOVE,
  UP, CANCEL or a frame
- `aPickRightAfterTheAnchorUsesTheScreen` — before any frame: the screen is asked and picks, no `hitTable`
- `aMemberBehindTheCameraIsNotPicked`, `otherActionsChangeNothing`
- `pickAgreesWithTheOldWorldSpaceProjection` — 10 000 seeded cases against old 544-562 transcribed (world point,
  view, projection): the same member, except touches within 0.01 px of a threshold, which are counted (under 1 %)
- `projectionMatchesAPinholeCamera` — feet, head and pixels per metre to 0.01 px of closed-form numbers, both orientations
- `spriteGoneAlsoDropsThePoserAndTheDrawnFacing` — called while the member stays: its next pose is unflipped, faces left
The grab:
- `aFingerOnAMemberBindsItAndMakesItActive` — `grab pointer=`, true, no table question; also when it is already active
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
That is 45. In `:core` at B, `TableEngineTest` loses `released` (the test above replaces it) and both older harnesses
pass a port that answers nothing; `TableSlotTest` checks the two new fields and their defaults.

Tests not required: `rememberTableEngine`, `RudeusArView`, `SessionPort`, `MotionEvent.toTouch`, anything on a device.
T53's 27 and T56's 28 are not copied to the host: they run against the changed `:core` engine at B.

## Steps
`S` is a scratch directory. Every Gradle call: `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk
./gradlew <tasks> --console=plain -q`. Without `/opt/android-sdk` nothing here can be compiled: say so and stop.
1. Read `ar/RudeusArView.kt` whole, `ar/CastTouch.kt`, `ar/PlaneCoords.kt`, `core/session/*.kt`,
   `core/pipeline/SurfaceBoundary.kt`, `shared/.../TableEngineHost.kt`, the Results of T47, T53 and T56, the
   `hand-review` skill. Run the header's line checks.
2. **Commit P** `core: ArPort, ScreenView and TouchEvent (T54)`: types only, and the one pure function
   `ScreenView.of` with its two tests. Tasks: `:core:jvmTest :core:compileKotlinIosSimulatorArm64
   :shared:testAndroidHostTest :androidApp:assembleDebug`.
3. **Commit A** `androidApp: taps, picking and the grab move into a host-local table engine and are pinned (T54; hand-review)`.
   Save the old files first (the worktree guard refuses `git` inside `<(...)`): `git show HEAD:<path> > $S/old.kt` for
   `RudeusArView.kt`, `$S/oldtouch.kt` for `CastTouch.kt`. Make the two copies with T56's two `sed` commands (its step
   3), fix their imports and header notes, add the contract's members, make the nineteen edits. `$S/t54.sed`, 14
   commands on 9 lines:
   ```
   s/HAND-REVIEW: //; s/Log\.i(GATE_TAG, /log(/; s/currentSlot\./current()./g
   /val profile = slotState\.profile/d; s/slotState\.profile/profile/g; s/event\.x, event\.y/xPx, yPx/
   s/depthGapsOnWay(frame, a, from, uv, WALK_DEPTH_SAMPLES)/port.depthGapsOnWay(from, uv, walkDepthSamples)/
   s/depthGapsOnWay(frame, a, from, target, LEAD_DEPTH_SAMPLES)/port.depthGapsOnWay(from, target, leadDepthSamples)/
   s/hits\.firstOrNull { it\.trackable is DepthPoint }?\.let { planeHeight(a, it\.hitPose) }/depthGapM()/
   s/if (w <= 0 || h <= 0) return/val view = port.screenView() ?: return/
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
   (run against 4ab4100 while this brief was written; the hunks listed are the only ones):

   | Engine code | Old lines | May differ |
   |---|---|---|
   | `confirmSurface` | 408-416 | two hunks: `private`; the `level = grown` line is gone (the host's mirror) |
   | `onTap`, after its guard | 728-752 | one hunk: the comment lines 737-738 stay in the host |
   | `table.grabbable` | 536-563 | three hunks, all deletions: 538-539, 541-542 and 544 (they are the host's `screenView`) |
   | `table.lead` | 572-583 | one hunk: 573-574 are gone |
   | `table`: `feet` / `head`, `nearestOnGround`, `onGrabbed` | 524-525, 565-570, 585 | nothing (the script drops `val profile`) |
   | `table.hitUv` | 527-534 | by eye, one line: the port's question with `profile.tapHeightToleranceM` |
   | `project`, `pxPerM`, `mulMV` | 267-276, 279 | by eye: the plane point for the pose, `mulMV(out, m, v)` for the two `multiplyMV`, `view.widthPx` / `heightPx`, `view.eyeToClip[0]` for `proj[0]` |
   | `onTouch`, `spriteGone`, the two one-line changes | none | by eye, against the contract |

   `sed -n '51,124p' $S/oldtouch.kt | sed -f $S/touch.sed | diff -w - <(the class to the end of the new ar/CastTouch.kt)`:
   three hunks, the constructor, the signature with `val nowMs`, the `OTHER` branch. The host's own moves, by eye:
   `SessionPort.hitTable` against old 528-533 (the two lines of edit 4's comment), `screenView` against 538-539 and
   541-544. The copies, with `strip() { grep -vE '^\s*(//|/\*|\*)' "$1"; }`: `diff <(strip <core TableEngine.kt>)
   <(strip <host TableEngine.kt>)` may show the package line, `internal` on three classes and on `loop`, import
   lines, `onMemberRemoved` replaced (parameter and call), the `spriteBuilt` signature, and otherwise **only added
   lines**; the same for `TableEngineHost.kt` (package, `internal` twice, imports, the parameter and its
   `rememberUpdatedState` line replaced, added lines). Then `:androidApp:testDebugUnitTest :androidApp:assembleDebug`.
4. **Commit B** `core, shared: taps, picking and the grab in TableEngine with the same tests (T54)`. `CastTouch.kt`
   goes to `:core` by `sed` of the package line, without its `core.session` imports and `HAND-REVIEW:` prefixes. The
   `strip` diffs of step 3, now three with `CastTouch.kt`, may show only package, `internal` and import lines;
   `TableTouchPinTest.kt` against `TableTouchTest.kt` only the package line and the class name. Tasks: `:core:jvmTest
   :core:compileKotlinIosSimulatorArm64 :core:compileTestKotlinIosSimulatorArm64 :shared:testAndroidHostTest
   :shared:compileKotlinIosSimulatorArm64 :shared:compileTestKotlinIosSimulatorArm64 :androidApp:testDebugUnitTest`.
   The same 45 assertions are green against both engines at this commit.
5. **Commit C** `androidApp: taps, picking and the grab come from :core and :shared (T54; hand-review)`: host diff C only.
6. `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk scripts/verify.sh`.
7. Mutation check on the `:core` files, one at a time, each restored byte for byte (`cmp`): the pick without its
   margin; the first member in the rectangle instead of the nearest; PAUSED members pickable; the screen asked
   before the two-member check; a tap that reports the active member again; the tap's depth read before the pick;
   `walkTo` before `confirmSurface`; a lead that grows the boundary; a lead without `nearestInside`; the way's
   readings without the tap's own; no throttle; UP without its last lead; a stream that is captured per pointer;
   `syncCast` without the unbind; `spriteGone` that keeps the poser. Each must turn a named test red; list them in
   the Result.
8. Fill the Result in its own commit, set `status: review`. The final message names the four commits and the host
   files, and says "needs your Pixel run" with the check below.

## Done when
- `scripts/verify.sh` exit 0; core tests up by at least 47 (45 + 2), shared unchanged, android up by one (`ScreenMatrixTest`)
- the diffs of steps 3 and 4 were as described (paste their output into the Result); 15 of 15 mutations caught
- `grep -n "loop" androidApp/src/main/kotlin/kz/rudy/ar/ar/RudeusArView.kt | grep -vE '^[0-9]+:\s*//'` prints nothing
  (only comment lines are left), `grep -rn "\.loop\b" androidApp/src/main shared/src/commonMain` prints nothing, and
  `loop` is `internal` in `core/.../session/TableEngine.kt`, so a host line that reads it does not compile
- `git diff <A>^..<C> --stat -- androidApp/src/main`: no `ar/TableEngine*.kt`, no `ar/CastTouch.kt`; `RudeusArView.kt`
  about 75 lines in and 150 out (804 -> about 730), and no changed line in 691-696
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
  `anchor t_ms=0` line and no `anchor_refused`. What a fault means: a walk to the wrong place, or none, is the `uv`
  or the `depthGap` of edit 13 or the port's `depthGapsOnWay`; a character that cannot be picked, or is picked
  through another, is the `ScreenView` (edits 3 and 4) or the sizes of edit 7; a grab that sticks is UP / CANCEL in
  `toTouch`, one that jumps is the pointer index, one that drops is the pointer id or the clock; a loop that does
  not grow on screen while the character walks is the `level` mirror, one that grows wrongly is the engine. Revert C
  and look again: if it is now right, the fault is in what differs between the host copies and the common ones
  (imports and visibility only, so report it). If it is still wrong, revert B and A together and report.

## Found, pinned as found, not fixed here
What the code does today and the tests assert as it is, with the line at 4ab4100 and the test, for a later fix brief.
- A tap on a character walks it to the table point under the finger, behind its feet when the finger was on its
  head: UP leads once more and a first lead skips the 5 mm rule (`ar/CastTouch.kt:95`, 114-115).
  `aTapOnAMemberThroughTheGrabWalksItToThePointUnderTheFinger`
- Picking sees only IDLE or WALKING members (540): a tap on a jumping or paused character walks the active one to
  the point behind it. `aMemberInTheAirOrPausedIsNotPicked`
- The nearest centre in pixels wins, not the nearest to the camera (559-560): a character can be picked through
  another. `theNearestCentreWinsAlsoThroughAMemberInFront`
- A refused lead still updates `lastUv` (`ar/CastTouch.kt:116-117`; the refusal is 578-581): a finger resting within
  5 mm of the refused point is not asked again. `aLeadPastADepthEdgeKeepsTheLastTarget`
- Nothing in taps or grabs checks tracking (590-592, 704-753): a walk can be set while PAUSED, its STARTED_WALKING is
  lost on the frame back (T47's finding 1), and a binding survives a loss. `aTapDuringALossSetsTheTargetAndKeepsThePause`
- Smaller: a grab reports the member active also when it already is (585; the tap checks first, 732); "the first move
  always leads" (`ar/CastTouch.kt:78`) is not what the throttle does (85); the tap hit-tests before it picks (709-730),
  so with `CAST_TOUCH_ENABLED` off a tap on a character whose ray misses the table selects nobody (host code, not pinned).

## What the duel stage gets from this seam (not built here)
The host now says only "this pixel was tapped and this is the table under it" and "these pointers did this"; it names
no member, no level and no loop. A duel stage therefore needs no host change: it replaces the body of `onTap` (the
same `uv` becomes `DuelInput.MoveTo` for the player's fighter, inside the arena instead of a growing boundary) and
the four answers of `CastTable` behind `onTouch` (`grabbable` answers null, so no stream is captured and every
finger stays a tap; a swipe that becomes a Dash can be read from the same `TouchEvent` later). `CastTouch`, `ArPort`,
`ScreenView` and the picture sizes are stage-neutral and stay. The stage interface, when T50's brief extracts it,
holds `onTap`, the `CastTable` answers and T56's part (steps 4-5 of `onFrame`, `level`, `standing()`), `:core` only.

## After T54 (not built here)
`ar/RudeusArView.kt` is about 730 lines, about 390 of them code: about 95 scan orchestration (`answerOwed`, the
request effect, `startScan`, the frame's scan block: S5, which also takes the early `trackingEdge` call, the `level`
mirror and `SlotState`), about 30 the pre-anchor gate (S8, optional), about 265 platform glue (session
configuration, the anchor, `FrameInput`, node writes, `SessionPort` with its `ScreenView`, the touch mapping, rigs,
overlays, probes). `ar/CastTouch.kt` is gone; S6 (capture) and S7 (sounds) are other files. `ArPort` gains
`grabScanFrame` in S5, and `pictureAspect` only if T50's pictures need it.

## Result (filled 2026-11-12, cloud container with /opt/android-sdk, no device)
- commits (on f3a8d88): P `7882b30` (inert types and `ScreenView.of`), A `cd793ae` (host-local engine + `TableTouchPinTest`
  + `ScreenMatrixTest`; hand-review), B `0de5704` (`:core` / `:shared` copies + the same tests), C `9cf7d56` (the host
  switches over; hand-review), then `80bd256` (test only: the `!!` warning in `TableTouchTest`, one hunk, 2 lines out
  and 3 in: both `grown.boundary!!` lines rewritten, one `assertNotNull` line added; see "deviations" 9), `74fb889`
  (this Result), and the review follow-up `core: T54 review — the release and onLevel order are pinned, stale
  comments (T54)` (two tests, two comments, a test name and these corrections; it cannot name its own hash here: find
  it with `git log -1 --format=%h --grep='^core: T54 review'`).
- revert points (corrected by the review; the old "C alone, then B and A together" no longer works as written:
  `80bd256` and the review commit edit `TableTouchTest.kt`, and the review commit `CastTouch.kt` and
  `TableEngineTest.kt`, all of them files B created or changed, so reverting B right after C stops on a modify/delete
  conflict). In this order, each from the tree the previous one left:
  ```
  # 1. the host only (the switch); B, the tests and the Result stay
  git revert --no-edit 9cf7d56
  # 2. then the common copies and the host-local split, together
  git revert --no-edit $(git log -1 --format=%h --grep='^core: T54 review')
  git revert --no-edit 80bd256
  git revert --no-edit 0de5704
  git revert --no-edit cd793ae
  ```
  Step 2's first revert also takes back these Result corrections (they ride in that commit); P (`7882b30`) and the
  first Result (`74fb889`) can stay. Run on this branch with `git revert --no-commit` in that order: no conflict, and
  afterwards `androidApp/`, `core/` and `shared/` equal P's tree (`git diff --cached 7882b30 -- androidApp core shared`
  empty); reverting step 1 alone leaves `core/` and `shared/` as they are.
- line check: the ten `ar/RudeusArView.kt` lines and the three `TableEngine.kt` lines of the header match at f3a8d88;
  `ar/RudeusArView.kt` there is `cmp`-equal to 4ab4100's and no file under `androidApp/`, `core/` or `shared/` changed
  between 4ab4100 and f3a8d88, so every line number of this brief held; nothing was shifted.
- measured (test XML; every Gradle call under `flock /tmp/rudeus-gradle.lock`):
  - baseline f3a8d88: core 765, shared 43, android 3.
  - P: `:core:jvmTest :core:compileKotlinIosSimulatorArm64 :shared:testAndroidHostTest :androidApp:assembleDebug` green;
    core 767 (+ `ScreenViewTest` 2), shared 43, android 3.
  - A: `:androidApp:testDebugUnitTest` 49 = 3 + 45 `TableTouchPinTest` + 1 `ScreenMatrixTest`, 0 failed;
    `:androidApp:assembleDebug` green.
  - B: core 812 = 767 + 45 `TableTouchTest` (`TableEngineTest` still 27, `TableFrameTest` 28), shared 43 (`TableSlotTest`
    still one test, now with the two new fields), android 49, all 0 failed; `:core` and `:shared` main and test klibs
    compile for iosSimulatorArm64. The same 45 assertions are green against both engines at this commit.
  - C: `scripts/verify.sh` exit 0: core 812, shared 43, android 4 (3 + `ScreenMatrixTest`), apk built. A forced
    `:core:jvmTest --rerun` after the mutation check: 812, 0 failed. Up by 47 in core (45 + 2), by one in android.
    `scripts/verify.sh` again at `80bd256`: exit 0, the same counts.
  - after the review follow-up: core 814 = 812 + `aMemberLeavingTheCastReleasesOnlyItsOwnFinger` +
    `onLevelIsReportedAfterTheEngineHoldsTheGrownLevel` (`TableTouchTest` 47), shared 43, android 4, all 0 failed;
    `scripts/verify.sh` exit 0.
  - `pickAgreesWithTheOldWorldSpaceProjection`: 10 000 seeded cases (anchor yaw + small tilt within 1 m, three members,
    some on books, random quads, sizes 0.5-2, portrait and landscape, margin 0-60 px), 3 365 of them pick someone; 1
    touch came within 0.01 px of a threshold; the other 9 999 picks are identical to the transcribed old code.
    `ScreenMatrixTest`: the worst `toMatrix x (u, h, v, 1)` against `compose(makeTranslation)` over 10 000 ARCore poses
    is 4.8e-7 m (bound 1e-5).
  - mutation check on the `:core` files (one at a time, the file restored byte for byte after each, `cmp` true every
    time; `kz.rudy.ar.core.session.*` run): 15 of 15 caught.
    1. the pick without its margin (`margin = 0f`) -> `pickRectangleIsTheQuadPlusTheMargin`, `projectionMatchesAPinholeCamera`,
       `pickAgreesWithTheOldWorldSpaceProjection`, `theNearestCentreWinsAlsoThroughAMemberInFront`
    2. the first member in the rectangle instead of the nearest -> `theNearestCentreWinsAlsoThroughAMemberInFront`, `pickAgrees...`
    3. PAUSED members pickable -> `aMemberInTheAirOrPausedIsNotPicked`
    4. the screen asked before the two-member check -> 16 tests, among them `oneMemberIsNeverPicked`,
       `pickAsksTheScreenOnlyWhenATouchNeedsIt`, `aTapWalksTheActiveMemberAndAsksThePortOnceForTheWay`
    5. a tap that reports the active member again -> `aTapOnAMemberOnlyMakesItActive`
    6. the tap's depth read before the pick (the line moved above `grabbable`) -> `theTapsDepthIsReadOnlyWhenTheTapWalks`,
       `aTapOnAMemberOnlyMakesItActive`, `aTapWithNobodyOnTheTableDoesNothing` and 3 more
    7. `walkTo` before `confirmSurface` -> `aTapOutsideTheBoundaryGrowsItThenWalks`
    8. a lead that grows the boundary (`confirmSurface(hit.uv)` before its `walkTo`) -> `aLeadIsClampedIntoTheBoundaryAndGrowsNothing`
    9. a lead without `nearestInside` -> `aLeadIsClampedIntoTheBoundaryAndGrowsNothing`
    10. the way's readings without the tap's own -> `theTapsOwnReadingIsTheLastOfTheWay`, `aTapPastADepthEdgeIsBlockedAtOnceAndGrowsNothing`,
        `noDepthReadingNeverRefuses`, `touchLogLinesKeepTheirKeys` and 2 more
    11. no throttle -> `leadIsThrottledToSixtyMsAndFiveMillimetres`
    12. UP without its last lead -> `liftingLeadsOnceMoreThenReleases`, `aTapOnAMemberThroughTheGrabWalksItToThePointUnderTheFinger`,
        `aFingerOffTheTableLeavesTheLastTarget`, `aReleaseDuringALossStillReleases`
    13. a stream that is captured per pointer (a DOWN / POINTER_DOWN that grabs nobody returns false) -> `theStreamStaysCapturedUntilTheLastFingerLifts`
    14. `syncCast` without the unbind -> `aGrabbedMemberRemovedFromTheCastIsReleased`
    15. `spriteGone` that keeps the poser -> `spriteGoneAlsoDropsThePoserAndTheDrawnFacing`
    The review ran 18 mutants of its own: 14 killed, 2 of the 4 survivors equivalent; the other two are pinned by
    the review follow-up, each checked red with the same runner and the file restored byte for byte (`cmp`):
    16. `unbindMember` releasing every finger (`bindings.filterValues { it.id == id }.keys` -> `bindings.keys.toList()`,
        `CastTouch.kt:58`; it passed all 812, since the T53 `released` assertions with their ids had gone) ->
        `aMemberLeavingTheCastReleasesOnlyItsOwnFinger`
    17. `current().onLevel(grown)` before `loop.level = grown` in `confirmSurface` -> `onLevelIsReportedAfterTheEngineHoldsTheGrownLevel`
  - host diff (`git diff 7882b30..9cf7d56 --stat -- androidApp/src/main`, i.e. `<A>^..<C>`): `RudeusArView.kt` 96 in / 148
    out (804 -> 752 lines), `CastTouch.kt` 124 out; no `ar/TableEngine*.kt`, no `ar/CastTouch.kt` left. Per commit: A
    `RudeusArView.kt` 96 / 150 (the fourteen edits), `CastTouch.kt` 24 / 23 (edits 15-19), plus the two new host files
    529 and 90 lines, the pin test 1 764 and `ScreenMatrixTest` 44; C `RudeusArView.kt` 4 / 2 (two imports back, the
    note re-wrapped to take its words), the three host-local files and the pin test deleted. Old 691-696 are unchanged
    (now 654-659), and so are the regions under "Does not move" (old 378-400, 417-489, 501-505, 506-519, 635-655,
    662-690, 704-724: each found verbatim in the new file); `PlaneCoords.kt`, `CharacterNode.kt`, `GateProbes.kt`
    untouched.
  - `grep -n "loop" ar/RudeusArView.kt | grep -vE '^[0-9]+:\s*//'`: empty. `grep -rn "\.loop\b" androidApp/src/main
    shared/src/commonMain`: empty. `core/.../session/TableEngine.kt:105`: `internal val loop = GameLoop(separate = separate)`.
    `grep -rn "HAND-REVIEW" core/src shared/src`: empty. `git diff --stat f3a8d88..HEAD`: only files under "Owns".
- step-3 diff, run on the committed A tree (old files saved with `git show HEAD:...` before editing; `$S/t54.sed` and
  `$S/touch.sed` exactly the brief's 9 and 3 lines; engine bodies at host `ar/TableEngine.kt` 449-456, 408-430,
  466-488, 496-505, 461-462, 490-494, 507, 464, 510-528):
  ```
  ### confirmSurface: old 408,416 vs body 449,456
  1c1
  <     fun confirmSurface(uv: Vec2) {
  ---
  >     private fun confirmSurface(uv: Vec2) {
  6d5
  <         level = grown
  ### onTap, after its guard: old 728,752 vs body 408,430
  10,11d9
  <                         // Pixel 2026-11-03 — for feet that sink or float: where the Depth API sees the table at this
  <                         // tap, relative to the plane the level stands on (+ = plane too low), and the feet's height.
  ### table.grabbable: old 536,563 vs body 466,488
  3,4d2
  <                 val frame = latestFrame[0] ?: return null
  <                 val a = anchorRef[0] ?: return null
  6,7d3
  <                 val w = viewSize[0]
  <                 val h = viewSize[1]
  9d4
  <                 projector.load(frame)
  ### table.lead: old 572,583 vs body 496,505
  2,3d1
  <                 val frame = latestFrame[0] ?: return
  <                 val a = anchorRef[0] ?: return
  ### table: feet / head: old 524,525 vs body 461,462
  (no difference)
  ### table: nearestOnGround: old 565,570 vs body 490,494
  (no difference)
  ### table: onGrabbed: old 585,585 vs body 507,507
  (no difference)
  ### table.hitUv: old 527-534 vs body 464 (by eye)
          override fun hitUv(x: Float, y: Float): TableHit? = port.hitTable(x, y, profile.tapHeightToleranceM)
  ### project, pxPerM, mulMV: old 266-276, 279 vs body 510-528 (by eye)
      plane[0] = pos.u; plane[1] = h; plane[2] = pos.v; plane[3] = 1f       (for world[...] = pose.tx() ...)
      mulMV(eye, view.planeToEye, plane); mulMV(clip, view.eyeToClip, eye) (for the two GlMatrix.multiplyMV)
      out[0] = ... * 0.5f * view.widthPx; out[1] = ... * 0.5f * view.heightPx   (for w, h)
      pxPerM(view, depth) = view.eyeToClip[0] * view.widthPx * 0.5f / depth     (for proj[0] * w * 0.5f / depth)
      mulMV: out[i] = m[i] * v[0] + m[4 + i] * v[1] + m[8 + i] * v[2] + m[12 + i] * v[3]
  ### CastTouch: old 51-124 through touch.sed vs the class to the end of the new ar/CastTouch.kt
  1c1
  < internal class CastTouch {
  ---
  > internal class CastTouch(private val log: (String) -> Unit) {
  17c17,18
  <     fun onTouch(event: MotionEvent, nowMs: Long, table: CastTable): Boolean {
  ---
  >     fun onTouch(event: TouchEvent, table: CastTable): Boolean {
  >         val nowMs = event.nowMs
  56a58
  >             TouchAction.OTHER -> Unit
  ```
  By eye, the host's own moves: `SessionPort.hitTable` is old 528-533 with `frame.hitTest(xPx, yPx)` and
  `surfaceHit(hits, a, toleranceM)`, nothing else; `screenView` is old 538-539 and 541-542 as they were, 543 with
  `return null` for its fallback, 544 + 546 as `projector.screen(frame, a, w, h, grabMarginPx[0])`. `onTouch`,
  `spriteGone`, the two one-line changes (`touch.unbindMember(id)`, the `Quad` line of `spriteBuilt`) as the contract has them.
  The copies at A (`strip` = `grep -vE '^\s*(//|/\*|\*)'`, `:core` / `:shared` file on the left): `TableEngine.kt` hunks
  `1c1` (package), `7a8`, `12a14`, `15a18,28` (imports), `20c33`, `22c35`, `35c50` (`internal` on `Member`,
  `TableSlot`, `TableEngine`), `39c54` (`onMemberRemoved` -> `port`), `45c62` (`internal val loop`), `140c164`
  (`onMemberRemoved(id)` -> `touch.unbindMember(id)`), `165c189` (the `spriteBuilt` signature), and otherwise only
  additions: `32a46,47` (the two slot fields), `42a58,59` (the two sample counts), `72a90,96` (the private state and
  `Quad`), `167a192,198` (the `Quad` line, `spriteGone`), `245a277,377` (`onTap`, `onTouch`, `confirmSurface`, `table`,
  `project`, `pxPerM`, `mulMV`). `TableEngineHost.kt`: `1c1` (package), `7a8,9` and `9,11c11` (imports), `14c14` and
  `28c30` (`internal` twice), `32c34` (the parameter `onMemberRemoved` -> `port`), `41d44` (its `rememberUpdatedState`
  line), `48c51` (the call's argument), and additions `24a25,26` (the two fields in `toTableSlot`), `35a38,39` (the two
  parameters), `51a55,56` (their two arguments).
- step-4 diffs at B (the same `strip`; the test files plain):
  ```
  ### core/.../session/TableEngine.kt vs androidApp/.../ar/TableEngine.kt
  1c1
  < package kz.rudy.ar.core.session
  ---
  > package kz.rudy.ar.ar
  17a18,27
  > import kz.rudy.ar.core.session.ArPort
  > import kz.rudy.ar.core.session.BillboardPoser
  > import kz.rudy.ar.core.session.CharacterAction
  > import kz.rudy.ar.core.session.CharacterCommand
  > import kz.rudy.ar.core.session.FrameInput
  > import kz.rudy.ar.core.session.GameLoop
  > import kz.rudy.ar.core.session.NodePose
  > import kz.rudy.ar.core.session.ScreenView
  > import kz.rudy.ar.core.session.TableHit
  > import kz.rudy.ar.core.session.TouchEvent
  23c33
  < class Member(val id: String, val ability: Ability, val emotes: EmoteSet)
  ---
  > internal class Member(val id: String, val ability: Ability, val emotes: EmoteSet)
  25c35
  < class TableSlot(
  ---
  > internal class TableSlot(
  40c50
  < class TableEngine(
  ---
  > internal class TableEngine(
  ### shared/.../TableEngineHost.kt vs androidApp/.../ar/TableEngineHost.kt
  1c1
  < package kz.rudy.ar
  ---
  > package kz.rudy.ar.ar
  7a8,9
  > import kz.rudy.ar.ArCharacter
  > import kz.rudy.ar.ArSlot
  10,12d11
  < import kz.rudy.ar.core.session.Member
  < import kz.rudy.ar.core.session.TableEngine
  < import kz.rudy.ar.core.session.TableSlot
  15c14
  < fun ArSlot.toTableSlot(members: List<ArCharacter>): TableSlot = TableSlot(
  ---
  > internal fun ArSlot.toTableSlot(members: List<ArCharacter>): TableSlot = TableSlot(
  31c30
  < fun rememberTableEngine(
  ---
  > internal fun rememberTableEngine(
  ### core/.../session/CastTouch.kt vs androidApp/.../ar/CastTouch.kt
  1c1
  < package kz.rudy.ar.core.session
  ---
  > package kz.rudy.ar.ar
  3a4,6
  > import kz.rudy.ar.core.session.TableHit
  > import kz.rudy.ar.core.session.TouchAction
  > import kz.rudy.ar.core.session.TouchEvent
  ### androidApp/.../TableTouchPinTest.kt vs core/.../TableTouchTest.kt (plain diff)
  1c1
  < package kz.rudy.ar.ar
  ---
  > package kz.rudy.ar.core.session
  531c531
  < class TableTouchPinTest {
  ---
  > class TableTouchTest {
  ```
  The `:core` / `:shared` files at B were made from the host copies by `sed` (package line, `internal` off the classes
  and functions, the `core.session` imports dropped, the copies' `HAND-REVIEW: T54 — host-local copy` lines dropped,
  `CastTouch.kt`'s `HAND-REVIEW: ` prefixes dropped).
- ARCore / SceneView calls made differently: exactly the brief's one. `Anchor.getPose` + `Pose.toMatrix` (1.54.0,
  `Pose.toMatrix(float[], int)` confirmed in the AAR's classes.jar; column-major per the ARCore reference) once per pick
  in `ScreenProjector.screen`, where old 552-553 made `Anchor.getPose`, `Pose.makeTranslation` and `Pose.compose` twice
  per ground member with a picture. Unchanged: `Camera.getViewMatrix` / `getProjectionMatrix` once per pick with two
  members, a frame, an anchor and a laid-out view (`screenView`, old 544); `Frame.hitTest(event)` once per tap (709);
  the tap's depth reading only through the lazy `depthGap` (739), called by the engine after the pick and only for a
  tap that walks; `Frame.hitTest(x, y)` once per lead and per fallback pick (`SessionPort.hitTable`, old 530); the 6 or
  2 ray tests of `depthGapsOnWay`; the 60 ms throttle. Nothing is read per frame that was not; `FrameInput` /
  `CameraFrame` untouched. No SceneView call added, removed or made differently. That `hitTable` answers null while
  the camera is not tracking rests on ARCore, as before (`ArFrame_hitTest`: "If not tracking, the hit_result_list will
  be empty"); `SessionPort` adds no tracking check.
- found by the characterization tests: **42 of 45 were green against the host-local engine on the first run; no moved
  body was changed.** The three reds were the tests' own mistakes, fixed in the test before commit A:
  `theBoundaryGrowsByTheTapsSquareAndNeverShrinks` asserted that every corner of the 2 cm square outside the old
  boundary is a hull vertex (a near corner can lie inside the hull; it now asserts every corner inside or on the hull
  and the far corners as vertices); `pickRectangleIsTheQuadPlusTheMargin`'s set-up check that the camera tells the feet
  depth from the head depth by more than 2 px failed at size 0.5 (1.2 px; the members now stand nearer the camera,
  2.0 px, and the check is > 1.5 px, still more than the 1-px probes); `pickAgreesWithTheOldWorldSpaceProjection`'s
  sanity bound on how many cases pick someone failed (1 357 of 10 000, with no disagreement; the aim was tightened to
  3 365, bound 3 000). Pinned as the code does it, beyond the "Found" list above, no fix proposed:
  1. With one member a DOWN asks the port nothing at all and is never captured (the two-member check comes before the
     screen); a tap asks only the depth and the way; with nobody on the table a tap asks nothing and logs nothing.
  2. The lead's throttle clock restarts on every MOVE that passes it, also one that leads nowhere (off the table, or
     under 5 mm): `lastLeadMs` is set before the hit test. The next lead is then 60 ms after that MOVE.
  3. A grabbed member removed by `syncCast` is released, but the stream stays the engine's: its later MOVE and UP
     return true and ask nothing.
  4. A finger lifted off the table makes no lead (the hit is null) and the member finishes its last walk; the same
     during a loss (the port answers no hit), where the member stays PAUSED with its target and walks after the loss.
  5. The fallback pick (no screen) and every lead ask `hitTable` with the profile's `tapHeightToleranceM` (3 cm on the
     table, 12 cm on the floor); the reach is 4.5 cm x scale at size 1 and the 3 cm x scale floor below size 2/3.
  6. A member 1.2 m behind a low camera, whose mirrored rectangle would still be a few pixels wide and on the screen,
     is kept out only by the `clip.w <= 1e-4` test.
  7. A tap that selects a member (or the active one) never reads the depth; a tap the depth refuses reads it once, like
     a tap that walks, and its BLOCKED comes from the tap itself, before any frame, with nothing grown.
  8. `lead_past_edge`'s `way=` has `leadDepthSamples + 1` readings, the finger's own last, as the tap's has
     `walkDepthSamples + 1`.
  Found by reading: `RudeusArView.kt`'s T32 header notes (3) still name `CastTouch.kt` and "`CastTable` below" (the
  T54 note says they describe the engine's code now; not changed, hand-review zone). Fixed by the review follow-up
  (comments and a test name only): `core/.../CastTouch.kt`'s `onTouch` KDoc said "[nowMs] = SystemClock.elapsedRealtime"
  (now: the event's `TouchEvent.nowMs`, the host's clock at the callback) and its moved T32 header said "RudeusArView
  implements" `CastTable` (now: TableEngine, since T54); the T53 test `aMemberGoneFromTheSlotLeavesTheLoopAndItsFingerIsReleased`
  is now `aMemberGoneFromTheSlotLeavesTheLoopWithOneLineAndNoEvent`, what it still asserts, with the T53 name in a
  comment above it. The release itself, with the id the T53 `released` assertions checked, is pinned by
  `aGrabbedMemberRemovedFromTheCastIsReleased` and, for a member that is not the held one,
  `aMemberLeavingTheCastReleasesOnlyItsOwnFinger` (both directions: b leaves while a is held, and a leaves while b is).
- deviations from the letter of the brief (none changes behaviour, the contract or the ARCore call list):
  1. Edit 16 also removes the blank line after the deleted `TableHit` (old 35), so no double blank line is left.
  2. Edit 4: each new piece has its own `// HAND-REVIEW: T54 —` line comment; `surfaceHit` carries old 490-493's KDoc
     under it (edit 10 moves it there), and its body is a block (`{ return hits.firstOrNull { ... } }`) as old 496-499.
  3. Edit 12: the words "T54: the bookkeeping is TableEngine's." are appended to line 589 (one line, 588 unchanged).
  4. `RudeusArView.kt` is 96 in / 148 out over `<A>^..<C>`, 752 lines (the brief estimated about 75 / 150 and 730): the
     three new top-level pieces carry their HAND-REVIEW notes and KDoc, and `SessionPort` / `toTouch` are written one
     statement per line. Commit C is 4 / 2: two imports back and the note re-wrapped to take its words.
  5. The A strip diff of `TableEngineHost.kt` shows the `rememberUpdatedState` line as a deletion (`41d44`) and the
     `TableEngine(` call's argument as a change (`48c51`); the brief words both as "replaced".
  6. The older harnesses' port: `TableEngineTest` declares a file-private `silentPort` above `Host`, `TableFrameTest`
     a file-private `quietPort` after `member` (outside 76-79, which only take it and the two sample counts);
     `TableSlotTest` builds one inline. They answer null / all nulls / null.
  7. Harness names in `TableTouchTest`: `TouchHost`, `FakePort`, `Pinhole` (the camera), `PickRect`, `D3`, `OldPick`
     (the transcription of old 544-562 with `ScreenProjector`, `toWorld` through ARCore's `Quaternion.rotateVector` as
     read from its 1.54.0 bytecode, and `android.opengl.Matrix.multiplyMV`'s sum order); none clashes in
     `kz.rudy.ar.core.session` tests.
  8. Mutation 4 is an extra `port.screenView()` call before the two-member check; 6 moves the depth line above the
     pick; 8 adds `confirmSurface(hit.uv)` before the lead's `walkTo`; 13 makes a DOWN / POINTER_DOWN that grabs
     nobody return false.
  9. Warning check after C: a forced non-incremental recompile (`--rerun-tasks -Pkotlin.incremental=false`) of
     `:core` JVM and iOS main and test, `:shared` iOS main and test, `:androidApp` main and unit test gave 64 warnings,
     two of them (one, reported twice) in a T54 file: `TableTouchTest.kt:591`, an unnecessary `!!` (in `:core`
     `grown.boundary` smart-casts after line 586's `!!`; in `:androidApp` it is another module's property and cannot).
     Fixed by `80bd256`, one hunk with 2 lines out and 3 in (both `grown.boundary!!` lines rewritten, one
     `val boundary = assertNotNull(grown.boundary)` added), no assertion changed; recompiled non-incrementally, no
     warning left in a T54 file, 812 green. So `TableTouchTest.kt` now differs from the B-time pin test by that hunk and
     by the review follow-up's two tests and harness field (`engineLevelAtLevel`); the step-4 identity above is B's.
- Pixel check ("tap, pick, drag, grow"): **needs the user's run** (no device in the cloud container). The seven steps
  under "Done when": two characters on a table with a cup, scanned once, footprints on. (1) Tap the table right of the
  active character, then far left: it walks to each tap, the other stays. (2) Tap the other character on its body,
  head included: its chip lights, it takes at most a small step toward the finger, the first does not move; tap the
  table: now the second one walks. (3) Drag a character slowly 30 cm across the table and lift: it follows, never
  jumps, stops where the finger left. (4) Hold one, put a second finger on the other and move both: each follows its
  own finger; lift both; hold one and tap the bare table with another finger: nobody walks to that tap. (5) Drag past
  the table edge: it stops at the edge and does not fall; tap the floor beyond: no walk. (6) Tap just outside the
  drawn boundary loop: the loop grows to hold the tap, here and on the mini-map, and the character walks there; tap
  inside: no change. (7) Hold a character, cover the lens 2 s, lift under the cover; uncover: it finishes its last
  walk and a new drag works at once. In `scripts/device.sh log`: each walking tap is `tap u=` then `tap_depth_gap=..
  way=[7 values] id=`; step 2 `grab pointer=0 id=`, `active id=`, `release pointer=0 id=` and no `tap_depth_gap`;
  step 4 a `grab pointer=1 id=`; step 5 `lead_past_edge id=` or `tap_past_edge`; step 6 one `boundary_grown n=` for the
  tap outside and none inside; every `grab` has its `release` (step 7's during the cover); one `anchor t_ms=0`, no
  `anchor_refused`. If anything is off, revert C first, then the rest in the order of "revert points" above.
- cut/deferred: nothing cut. The scan block, the early `trackingEdge` call and `SlotState` stay in the host until S5;
  `ArPort.grabScanFrame` is S5's.
