# T56 — thin-host-step-3b

status: doing
created: 2026-11-12
phase: v2 wave 3 (ai/build-plan-v2.md, "iOS readiness": thin host). Step S3b of the map in ai/tasks/T43-thin-host-map.md
gate: G1 foundation (`scripts/verify.sh` green, `:core` and `:shared` still compile for iOS); a precondition of T54 and T50
cut-if-slips: never cut. Two revert points, see "Done when": commit C alone, then commits B and A together
depends: T53 merged (f7f2c07) and its Pixel check reported fine by the user (2026-11-12)
tier: rudeus-opus-extra (the orchestrator's decision: every design choice is taken below; what is left is a line-exact
  move proved by script and bit-for-bit tests, the job that tier did for T52 and T53)

**Every `file:line` below is a line of the T53 branch at 6d157cc** (`ar/RudeusArView.kt` has 875 lines there, App.kt
and the engine as T53 left them). T53's review changed no line and the merged tree equals 6d157cc, but check before
trusting the rest: run
`sed -n '316p;408p;473p;529p;540p;686p;721p;749p;767p' androidApp/src/main/kotlin/kz/rudy/ar/ar/RudeusArView.kt` and
compare with: `val game = rememberTableEngine(`, `LaunchedEffect(slot.scenarioRequest) {`, `val previous = loop.level`,
`fun syncCast(a: Anchor, frame: Frame) =`, `slotState.tracking = true`, `if (tracking != slotState.tracking) {`,
`syncCast(a, frame) //`, `if (!tracking) reappearAtMs[0] =`, the `}` that closes `if (tracking) {`. Lines that only
moved: shift the numbers of that region and say so in the Result. A moved body whose text changed: stop and report.
Paths without a module are under `androidApp/src/main/kotlin/kz/rudy/ar/`.

## Goal
The per-frame step of the game is `TableEngine.onFrame(FrameInput): List<NodePose>` in `core.session`: who is on the
table, the debug scenario, physics, the cast-state report, the reappear timer and one billboard pose per member. The
Android host computes `tracking`, builds a `FrameInput` from the ARCore camera, calls the engine and writes each pose
into its node. Its scan code reads the level and where the members stand through the engine. Today those lines are
in `onSessionUpdated` and a `LaunchedEffect` (`ar/RudeusArView.kt:407-420, 686-689, 719-767`) and have never run in a
test as a whole. After this brief an iOS host does not write them, and "PAUSED freezes, never resets", "hidden for
0.8 s after tracking returns" and the scenario replay are tested in common code. The player sees no difference.

## Decisions taken by the orchestrator (not open)
- Scope is T53's outline of S3b: the tracking edge, the scenario start and feed, `loop.onFrame`, the cast-state
  report, the reappear timer, the pose loop with the per-member `BillboardPoser`.
- The scan's reads of the level and of where the members stand, and its one write of the level, go behind the engine,
  so the duel stage (T50) can replace what is on the table. When to scan and the ARCore grab stay in the host until S5.
- `syncCast` checks `anchored` inside the engine. Seam: `FrameInput(nowMs, tracking, camera)`, `CameraFrame(pos,
  heightM, right)`; appended fields default to "as today"; no `ArPort` (T54), no `Stage` interface, no duel code.
- Proof as T52 and T53: host-local first (A), substitution script and old-line table, pin tests, the common copy with
  the same tests (B), the switch (C). Pixel numbers stay in the host and enter through the constructor, no defaults.
- Characterization tests: a red test against the host-local copy means the test is wrong or it found something.
  Report it; never change a moved body to fit a test.

## Decisions taken here (not open)
1. **The tracking edge moves, its call does not.** `onTracking` (686-689) runs before the host's scan block
   (690-718), and the order is visible: on Scanning, `onTracking(false)` sets PAUSED and the `onScanned(Failed)` that
   `startScan` sends on the same frame (444-447) is then ignored (`shared/.../App.kt:472, 495`). The other way round
   the answer counts first: `failures + 1` (App.kt:498), and Skip appears one failure early. Calling `onFrame` above
   the scan block instead would let the scan read positions after the step (476) and put `onScanned` after the cast
   events (both write `speech`). So the engine gets `trackingEdge(tracking)`, the old four lines, the host calls it
   where they were, and `onFrame` calls it again as its first step (a no-op on Android: the value is already
   reported; a host without a scan block needs only `onFrame`). The early call goes when S5 moves the scan in.
2. **The host-local copy is the whole engine, not a wrapper.** Two T53 bodies change (`anchorPlaced` reports
   tracking, `syncCast` gains its check), and they must change in the same commit as the host lines they replace
   (540, 545), while no `:core` change may ride with a zone commit. So commit A copies `TableEngine.kt` and
   `TableEngineHost.kt` back into `ar/` by `sed`, adds the frame step there, and the host runs on that copy until C.
   Consequence: B changes a signature the T53 host could not call, so the second revert point is **B and A together**.
3. **Inert types land first, in commit P** (`:core` only): `FrameInput`, `CameraFrame`, `NodePose.visible = true`.
   They have no behaviour, and the host-local engine in A needs them (`BillboardPoser` returns the `:core` `NodePose`).
4. **The picture's facts reach the engine by one call, `spriteBuilt(id, facesRight)`**, not by `Member.facesRight`
   (the outline's idea). The drawn facing belongs to the sprite the host built (`ar/CharacterNode.kt:44`,
   `ar/RudeusArView.kt:229`, both Pixel switches that must stay live), and a rebuilt sprite starts a new poser today
   (343-347 build a new `MemberRig`, 247), which a `Member` cannot say. The call sits in the rig's `remember` block,
   whose lifetime is the old `CharacterPoser`'s. Not in the node's `apply`: whether SceneView runs `apply` once per
   node could not be confirmed (no sources jar in the Gradle cache). A member never announced is posed as
   `facesRight = false`. The poser's height is `profile.characterHeightM` of the locked surface, the number every
   sprite is built with (345-346).
5. **`scenario(request: Int)`, no `TableSlot.scenarioRequest`.** The effect hands over its key, as `configure(size)`
   does; the last-seen guard is inside (T53 decision 3).
6. **The scan seam is `var level: Level` (`core.domain.Level`, a plain get/set of `loop.level`) and `standing()`**:
   five host lines, each a textual substitution. The tap's boundary growth (`confirmSurface`, 428-431) is tap code
   and keeps writing `loop.level` until T54, so the setter must stay a plain assignment until then.
7. **`FrameInput.camera` is not nullable and is built on every anchored frame.** Two ARCore reads are therefore made
   more often than today: `Camera.getPose` also on untracked frames (752-753 ran only while tracking) and
   `Camera.getDisplayOrientedPose` every frame (522 ran only when a member joined). Google's reference says both
   poses are "only useful" while TRACKING; neither call fails. The engine uses `pos` / `heightM` only while tracking
   and `right` only for a member that joins, as today. This is the one thing the host does differently toward ARCore.
8. **`syncCast` stays public** (T53's tests call it) and returns at once before the anchor; `onFrame` and
   `trackingEdge` do the same. None of the three guards can fire from the Android host.
9. **Pixel check**: one two-minute sequence under "Done when", built from what S3b can break (a slide, a freeze, a
   vanished character, a wrong turn, a missing reappear, a dead mini-map), not from T53's buttons.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- core/src/commonMain/kotlin/kz/rudy/ar/core/session/FrameInput.kt (new, P)
- core/src/commonMain/kotlin/kz/rudy/ar/core/session/BillboardPoser.kt (P: the one appended field of `NodePose`)
- core/src/commonMain/kotlin/kz/rudy/ar/core/session/TableEngine.kt (B)
- core/src/commonTest/kotlin/kz/rudy/ar/core/session/TableFrameTest.kt (new, B)
- core/src/commonTest/kotlin/kz/rudy/ar/core/session/TableEngineTest.kt (B: line 65, two constructor arguments)
- shared/src/commonMain/kotlin/kz/rudy/ar/TableEngineHost.kt, shared/src/commonTest/kotlin/kz/rudy/ar/TableSlotTest.kt (B)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/TableEngine.kt, TableEngineHost.kt (HAND-REVIEW: new in A, deleted in C)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/RudeusArView.kt, CharacterNode.kt (HAND-REVIEW: A and C, only the edits below)
- androidApp/src/test/kotlin/kz/rudy/ar/ar/TableFramePinTest.kt (new in A, deleted in C)
- ai/tasks/T56-thin-host-step-3b.md (the Result section)

Does not move and must not touch: the scan block and `startScan` except the five lines of edit 9 (383-405, 436-508,
690-718), taps, the grab and `confirmSurface` (421-435, 509-519, 547-619, 771-831), the pre-anchor gate (662-682),
`cameraRight` (520-524), `ar/CastTouch.kt`, `ar/PlaneCoords.kt`, `core/session/GameLoop.kt`, `BillboardPoser.pose`,
`core/scenario/**`, `core/duel/**`, `core/physics/**`, `ArSlot.kt`, `App.kt`, every Gradle file.

## Contract
```kotlin
package kz.rudy.ar.core.session

// FrameInput.kt (P)
/** One AR frame after the anchor exists. Plain values; the engine keeps none of them. */
class FrameInput(
    val nowMs: Long,          // the host's monotonic clock (Android: SystemClock.elapsedRealtime, line 660)
    val tracking: Boolean,    // camera AND session anchor TRACKING (684-685); false = physics pauses, nothing resets
    val camera: CameraFrame,  // read on every frame, trusted only while [tracking]
)
class CameraFrame(
    val pos: Vec2,            // camera position in plane coordinates, metres (`planeCoords`, 752)
    val heightM: Float,       // its height above the plane, metres (`planeHeight`, 753)
    val right: Vec2,          // screen-right in the plane (`cameraRight`, 521-524), any length
)
// BillboardPoser.kt (P): data class NodePose(..., val visible: Boolean = true)   // false = the node stays hidden

// TableEngine.kt (A host-local, B :core). Appended to TableSlot, each default meaning "as before T56":
//   val characterSize: Float = CharacterSize.DEFAULT                       // the pose's size, read every frame (762)
//   val debug: Boolean = false                                             // cast-state reports only while true (745)
//   val onTracking: (Boolean) -> Unit = {}                                 // true at the anchor, then every change
//   val onCastState: (Map<String, CharacterState>) -> Unit = {}            // 747
class TableEngine(
    current, surface, playEmoteSound, onMemberRemoved, log,                 // as T53
    private val feetDepthBias: Float,             // the host's FEET_DEPTH_BIAS (ar/CharacterNode.kt:52); no default
    private val reappearAfterTrackingMs: Long,    // the host's REAPPEAR_AFTER_TRACKING_MS (214-219); no default
    separate: Boolean = true,
) {
    val loop: GameLoop                            // after T56 read only by the host's tap and grab code, until T54
    var level: Level                              // get() = loop.level; set(v) { loop.level = v }  <- 473, 487, 489
    fun standing(): List<CharacterState>          // members' states in cast order; one default when empty  <- 486
    fun spriteBuilt(id: String, facesRight: Boolean)  // composition time; that member's pose starts unflipped again
    fun scenario(request: Int)                    // effect (scenarioRequest); 0 = nothing asked     <- 409-419
    fun trackingEdge(tracking: Boolean)           // reports a change once                           <- 686-689
    fun onFrame(input: FrameInput): List<NodePose>                                                // <- 719-767
    fun syncCast(cameraRight: () -> Vec2)         // T53's, plus a first line `if (!anchored) return`
    fun anchorPlaced(cameraRight: () -> Vec2)     // T53's, plus `tracking = true` after `anchored = true` (540)
}                                                 //   and `s.onTracking(true)` as its last line (545)
internal const val CAST_STATE_PERIOD_MS = 100L    // from ar/RudeusArView.kt:221-222, with its KDoc
```
Private state, all plain fields: `tracking: Boolean? = null`, `replay: ScenarioPlayer? = null`, `replayStartMs = 0L`,
`lastScenario = 0`, `reappearAtMs = 0L`, `castStateAtMs = 0L`, `posers: HashMap<String, BillboardPoser>`,
`drawnRight: HashMap<String, Boolean>`. `spriteBuilt` stores `drawnRight[id]` and drops `posers[id]`; a poser is made
on first use as `BillboardPoser(id, profile.characterHeightM, drawnRight[id] ?: false, feetDepthBias)`.

`onFrame`, in this order (lines 686-767 without the host's scan block):
1. Not anchored: return `emptyList()`, nothing else (new guard).
2. `trackingEdge(input.tracking)`.
3. `syncCast { input.camera.right }`: also while not tracking, as today.
4. While a replay runs, the scripted closure of 723-741 as `beforeStep`: due steps go to the FIRST member.
5. `loop.onFrame(now, tracking, { id, e -> current().onCastEvent(id, e) }, beforeStep)`.
6. In debug, at most every `CAST_STATE_PERIOD_MS` on the host clock, also during a loss: `onCastState`.
7. `if (!tracking) reappearAtMs = now + reappearAfterTrackingMs`; then, not tracking: return `emptyList()`.
8. One pose per member in cast order from its poser, fed as 758-764 (host clock for `sinceLandedMs`, the latest
   slot's `characterSize`); `visible = now >= reappearAtMs`, written as `if (visible) p else p.copy(visible = false)`.

```kotlin
// shared/.../TableEngineHost.kt (A host-local, B :shared)
fun rememberTableEngine(slot, members, playEmoteSound, onMemberRemoved, log,
    feetDepthBias: Float, reappearAfterTrackingMs: Long, separate: Boolean = true): TableEngine
```
It gains a fifth effect, declared last: `LaunchedEffect(slot.scenarioRequest) { game.scenario(slot.scenarioRequest) }`
(the key of line 408). `toTableSlot` fills the four new fields from the slot's `characterSize`, `debug`, `onTracking`,
`onCastState`.

**What must stay exactly as it is** (the rules the tests pin):
- Order in a frame: the edge, the cast sync (PLACED of a joiner), scripted taps after resume and before the step,
  physics, events in cast order, the cast-state report, then poses. At the anchor: `onAnchorPlaced`, PLACED per
  member, `onTracking(true)`; the first tracked frame after it reports no edge.
- A loss pauses every member, steps nothing, freezes the simulated clock (scenario, emotes) and returns no pose.
  Nothing is reset: positions, targets, the replay and the flips survive it.
- Hidden until `reappearAfterTrackingMs` after the LAST untracked frame, on the host clock; poses are still computed
  while hidden. Before any loss a member is visible from its first pose.
- Scenario: request 0 does nothing; before the anchor `scenario_rejected anchored=false`, not retried; a request
  restarts from `level.spawn` (`restartAtSpawn`), `scenario_start id=.. steps=.. surface=.. sim_ms=..`; each step
  logs `scenario_step idx=.. at_ms=..`; an ignored jump `jump_ignored mode=.. ability=.. scenario=true`; the end
  `scenario_done ms=..` in simulated time. A level swap meanwhile changes only the level.
- Log lines byte for byte. No new line: the three new guards are silent.

**What is not identical, and why it is harmless.** (a) Decision 7: the camera is read every anchored frame.
(b) The engine poses every member on the table; the host drops a pose whose node is not composed yet (756-757 skipped
it before posing). So a poser can see the one or two frames between the anchor tap and the node's composition, and
its flip may already be set on them. It differs from today only if the camera crosses the flip deadband in those
frames while the member stands still: a mirrored idle sprite until its first step; not expected on a device. (c) The
scenario effect now runs with the engine's effects, before the scan-request effect (392) instead of after it; they
share no state. (d) `tracking = true` is set a few lines later inside `placeAnchor`; nothing reads it in between.
(e) Per frame the host now allocates one `FrameInput`, one `CameraFrame`, a list, and about four `Pose` more.

### Host diff, commit A (HAND-REVIEW)
New `ar/TableEngine.kt` and `ar/TableEngineHost.kt` (step 3 says how they are made), each opening with one
`// HAND-REVIEW: T56 — host-local copy for one brief ...` note. Then fifteen edits and nothing else. In
`ar/RudeusArView.kt`:
1. Imports: lines 67-71 go (`ScenarioPlayer`, `ScenarioStep`, `Scenarios`, `core.session.TableEngine`,
   `rememberTableEngine`: the last two resolve to the host-local copies); `core.session.CameraFrame` and
   `core.session.FrameInput` come.
2. Header, after 177: `// HAND-REVIEW: T56 — the frame step (tracking edge, scenario, physics, cast-state report,`
   `// reappear timer, poses) is TableEngine.onFrame: this file builds a FrameInput and writes each NodePose into its`
   `// node; the scan reads the level and the members through the engine. PAUSED still freezes and never resets, one`
   `// anchor per session, scans stay a button plus the quiet ones and are never per frame.`
3. Lines 221-222 (`CAST_STATE_PERIOD_MS` and its KDoc) go. `REAPPEAR_AFTER_TRACKING_MS` (214-219) stays.
4. Line 240 (`SlotState.tracking`) goes. Line 247 (`MemberRig.poser`) goes.
5. Line 316: `feetDepthBias = FEET_DEPTH_BIAS, reappearAfterTrackingMs = REAPPEAR_AFTER_TRACKING_MS,` before `separate`.
6. Lines 331-332 (`reappearAtMs`, `castStateAtMs`) and 367-369 (the scenario holders and their note) go.
7. Before 347, inside the same `remember` block:
   `game.spriteBuilt(c.id, sprite.facesRight) // T56: this picture's pose starts unflipped, as a new CharacterPoser did`
8. Lines 407-420 (the scenario effect and its note) go.
9. The scan seam, five lines, nothing else on them: 473 `val previous = game.level`; 476
   `val standingNow = game.standing().map { it.pos }`; 486 `val standing = game.standing()`; 487 and 489
   `loop.level` -> `game.level`. The comments around them stay as they are.
10. Lines 525-529 (the `syncCast` wrapper and its KDoc) go.
11. `placeAnchor`: 540, 543 and 545 go (`slotState.tracking = true`, `val s = currentSlot`, `s.onTracking(true)`).
12. Lines 686-689 become `game.trackingEdge(tracking)` under
    `// HAND-REVIEW: T56 — reported here, before the scan block, as before: App drops a scan answer that comes after a`
    `// loss (shared/.../App.kt:472, 495). onFrame reports it too; this early call goes when S5 moves the scan.`
13. Lines 719-767 become, under a note that says what the engine now does, that PAUSED still never resets, and
    decision 7 in one line:
    `val camera = CameraFrame(planeCoords(a, frame.camera.pose), planeHeight(a, frame.camera.pose), cameraRight(a, frame))`
    `for (p in game.onFrame(FrameInput(now, tracking, camera))) rigs[p.id]?.node?.writePose(p)`

In `ar/CharacterNode.kt`:
14. Line 52: `private const val` -> `internal const val` (the view passes it to the engine).
15. Lines 135-159 (`CharacterPoser`) become, under the T52 note and a one-line T56 note, with the imports to match:
    ```kotlin
    internal fun PlaneNode.writePose(p: NodePose) {
        position = localPosition(p.pos, p.h)
        quaternion = p.rotation.toNode()
        scale = Scale(x = if (p.mirrored) -p.scaleX else p.scaleX, y = p.scaleY, z = 1f)
        if (isVisible != p.visible) isVisible = p.visible
    }
    ```
    The same four writes in the same order as old 155-157 and `ar/RudeusArView.kt:765`.
No SceneView call is added, removed or made differently. ARCore: decision 7 only.

### Host diff, commit C (HAND-REVIEW; the whole of it)
Delete `ar/TableEngine.kt`, `ar/TableEngineHost.kt`, `TableFramePinTest.kt`; `ar/RudeusArView.kt` gets back
`import kz.rudy.ar.core.session.TableEngine` and `import kz.rudy.ar.rememberTableEngine`, and the T56 note gains
"(now `core.session.TableEngine.onFrame`, pinned by JVM tests)".

## Tests required
One file: `TableFramePinTest` in commit A (package `kz.rudy.ar.ar`, `:androidApp:testDebugUnitTest`), and in commit B
the same file as `TableFrameTest` in `:core` with only the package line and the class name changed. `kotlin.test`,
no explicit import of the engine's types. Its harness is T53's `Host` (`TableEngineTest.kt:52-97`) with a camera that
moves, slots that carry the four new fields, and `frame()` calling `engine.onFrame`. Floats in log lines: key and
integer fields only. Tracking and anchor:
- `trackingEdgeIsReportedOnce` — through `onFrame` alone: false once, nothing while it lasts, true once
- `anchorThenTrackingKeepTodaysOrder` — anchor, PLACED per member, `tracking true`; no edge on the next frame
- `anEdgeReportedEarlyIsNotReportedAgainByTheFrame` — the Android call pattern: `trackingEdge`, then `onFrame`
- `onFrameBeforeTheAnchorDoesNothing` — also `trackingEdge`: no callback, no log, nobody placed, empty list
- `syncCastBeforeTheAnchorPlacesNobody`
Frame order:
- `oneFrameReportsTheEdgeThenPlacedThenEventsThenCastState`
- `aMemberAddedBetweenFramesIsPosedOnTheFrameItJoins`
- `aMemberThatJoinsDuringALossIsPlacedWithThatFramesCameraRight`
PAUSED:
- `aTrackingLossFreezesEveryMemberAndNeverResets` — positions, targets, simulated clock, a running emote, the replay
- `noPoseWhileNotTracking`
- `hiddenFor800MsAfterTrackingReturns` — the exact boundary, counted from the last untracked frame; delay 0 = at once
Poses:
- `posesFollowCastOrderAndSkipNobody`
- `engineAndHostPoserAgreeBitForBit` — 2 000 frames (walks, a jump and its landing, an emote, a size change, a
  moving camera, one loss), table and floor, both drawn facings: every `NodePose` equals (`==`) the one a bare
  `BillboardPoser` gives when fed as `ar/RudeusArView.kt:758-764` feeds it
- `sinceLandedIsOnTheHostClock` — a 200 ms hitch after a landing ends the squash though physics stepped 50 ms (T52)
- `aRebuiltSpriteStartsUnflippedAgain` — `spriteBuilt` inside the deadband
- `theDrawnFacingComesFromSpriteBuiltAndDefaultsToLeft`
- `sizeIsTheLatestSlotsAndTheHeightTheLockedSurfaces`
Cast state:
- `castStateAtMostEvery100MsAndOnlyInDebug` — at once on the first debug frame; also during a loss
Scenario:
- `scenarioRequestBeforeTheAnchorIsRejected` — and 0 does nothing, and it is not retried after the anchor
- `aNewRequestRestartsTheReplayFromSpawn` — mid-replay too; another member's unreported transition is dropped (T47's
  finding 2, as found)
- `theSameRequestOfferedTwiceStartsOnce`
- `scenarioFeedMatchesScenarioRunner` — DAY5 on `day5Level`, JUMP and FLY: the events of `ScenarioRunner`; with a
  700 ms loss mid-walk the same plus PAUSED / RESUMED; `scenario_done ms=` in 15000..15016 both times
- `scenarioDrivesTheFirstMemberOnly`
- `aBeatDueOnTheResumeFrameIsAppliedButItsEventIsLost` — a replay asked under a loss: on the frame back the first
  walk is applied and logged, the frame reports RESUMED only (T47's finding 1, as found, no fix)
- `aScriptedJumpThatPhysicsIgnoresIsLogged` — ROLL
The level seam:
- `levelSwapBetweenFramesTakesEffectOnTheNextStepWithoutAReset` — tracked and during a loss; the replay goes on
- `standingIsEveryMembersStateInCastOrder` — and one default state when nobody is on the table
Evidence:
- `frameLogLinesKeepTheirKeys`
That is 28. In `:shared` (B): `TableSlotTest` also checks the four new fields and their defaults, and its anchor
sequence ends with `tracking true` (line 35's comment "the host still calls it" goes).

Tests not required: `rememberTableEngine`, `RudeusArView`, `writePose` (three setters; `NodeRotationTest` stays),
anything on a device. T53's 27 tests are not copied to the host: they run against the changed `:core` engine at B.

## Steps
`S` is a scratch directory. Every Gradle call: `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk
./gradlew <tasks> --console=plain -q`. Without `/opt/android-sdk` nothing here can be compiled: say so and stop.
1. Read `ar/RudeusArView.kt` whole, `ar/CharacterNode.kt`, `core/session/*.kt`, `core/scenario/Scenario.kt`,
   `shared/.../TableEngineHost.kt`, `App.kt:461-530`, the Results of T47, T52 and T53, the `hand-review` skill. Run
   the header's line check.
2. **Commit P** `core: FrameInput, CameraFrame and NodePose.visible (T56)`. Tasks: `:core:jvmTest
   :core:compileKotlinIosSimulatorArm64 :shared:testAndroidHostTest :androidApp:assembleDebug`.
3. **Commit A** `androidApp: the frame step moves into a host-local table engine and is pinned (T56; hand-review)`.
   First save the old files (the worktree guard refuses `git` inside `<(...)`):
   `git show HEAD:androidApp/src/main/kotlin/kz/rudy/ar/ar/RudeusArView.kt > $S/old.kt`, the same for
   `CharacterNode.kt` as `$S/oldnode.kt`. Make the copies:
   `sed -e 's/^package kz\.rudy\.ar\.core\.session$/package kz.rudy.ar.ar/' -e 's/^class /internal class /' core/src/commonMain/kotlin/kz/rudy/ar/core/session/TableEngine.kt > androidApp/src/main/kotlin/kz/rudy/ar/ar/TableEngine.kt`
   `sed -e 's/^package kz\.rudy\.ar$/package kz.rudy.ar.ar/' -e 's/^fun /internal fun /' shared/src/commonMain/kotlin/kz/rudy/ar/TableEngineHost.kt > androidApp/src/main/kotlin/kz/rudy/ar/ar/TableEngineHost.kt`
   then fix their imports and header notes by hand, add the contract's members to the copies, and make the fifteen
   edits. Write `$S/t56.sed` with exactly these 16 lines:
   ```
   s/HAND-REVIEW: //
   s/Log\.i(GATE_TAG, /log(/
   s/return@LaunchedEffect/return/
   s/slot\.scenarioRequest/request/
   s/anchorRef\[0\] == null/!anchored/
   s/slotState\.tracking/this.tracking/g
   s/slotState\.//g
   s/currentSlot\./current()./g
   s/scenarioStartMs\[0\]/replayStartMs/g
   s/scenario\[0\]/replay/g
   s/castStateAtMs\[0\]/castStateAtMs/g
   s/reappearAtMs\[0\]/reappearAtMs/g
   s/REAPPEAR_AFTER_TRACKING_MS/reappearAfterTrackingMs/
   s/syncCast(a, frame)/syncCast { input.camera.right }/
   s/planeCoords(a, frame\.camera\.pose)/input.camera.pos/
   s/planeHeight(a, frame\.camera\.pose)/input.camera.heightM/
   ```
   For each row, `sed -n '<old lines>p' $S/old.kt | sed -f $S/t56.sed | diff -w - <(sed -n '<body lines>p' androidApp/src/main/kotlin/kz/rudy/ar/ar/TableEngine.kt)`:

   | Engine code | Old lines | May differ |
   |---|---|---|
   | `trackingEdge` | 686-689 | one added line at the top: `if (!anchored) return` |
   | `scenario` | 409-419 | two added lines at the top: the last-seen guard |
   | `onFrame`, steps 3-7 | 719-749 | nothing (the comments move with the code, without the `HAND-REVIEW:` prefix) |
   | `onFrame`, step 8 | 750-767 | five hunks, by eye: `if (tracking) {` is the early return; the `poses` list is new; the `rig` / `node` / `apply(node,` lines are `val p = <poser>.pose(`; the `isVisible` line is the `poses +=` line; the closing brace is `return poses` |
   | `onFrame`, steps 1-2 | none | by eye: the guard, `trackingEdge(input.tracking)`, `val now` / `val tracking` |
   | `standing` | 486 | by eye, one line (`val standing =` is the return) |
   | `anchorPlaced`, `syncCast` | 540, 545 | by eye: two added lines, one added line |
   | the poser's constructor call | `$S/oldnode.kt` 140 | by eye: `profile.characterHeightM`, `drawnRight[id] ?: false`, `feetDepthBias` |

   The host's scan lines: `sed -n '473p;476p;486,489p' $S/old.kt | sed -e 's/loop\.cast\.members\.map { it\.body\.state\.pos }\.ifEmpty { listOf(loop\.state\.pos) }/game.standing().map { it.pos }/' -e 's/loop\.cast\.members\.map { it\.body\.state }\.ifEmpty { listOf(loop\.state) }/game.standing()/' -e 's/loop\.level/game.level/g'`
   against the same six lines of the new file: no difference. The copies, with `strip() { grep -vE '^\s*(//|/\*|\*)' "$1"; }`:
   `diff <(strip core/.../session/TableEngine.kt) <(strip androidApp/.../ar/TableEngine.kt)` may show the package
   line, `internal` on three classes, import lines, and otherwise **only added lines**; the same for
   `TableEngineHost.kt` against the `:shared` file (package, `internal` twice, imports, added lines, and the
   `TableEngine(` call's two arguments). Then `:androidApp:testDebugUnitTest :androidApp:assembleDebug`.
4. **Commit B** `core, shared: the frame step in TableEngine with the same tests (T56)`. The two `strip` diffs of
   step 3 may now show only the package line, `internal` and import lines; `TableFramePinTest.kt` against
   `TableFrameTest.kt` only the package line and the class name. Tasks: `:core:jvmTest
   :core:compileKotlinIosSimulatorArm64 :core:compileTestKotlinIosSimulatorArm64 :shared:testAndroidHostTest
   :shared:compileKotlinIosSimulatorArm64 :shared:compileTestKotlinIosSimulatorArm64 :androidApp:testDebugUnitTest`.
   The same 28 assertions are green against both engines at this commit.
5. **Commit C** `androidApp: the frame step comes from :core and :shared (T56; hand-review)`: host diff C only.
6. `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk scripts/verify.sh`.
7. Mutation check on the `:core` engine, one at a time, the file restored byte for byte (`cmp`): `visible` always
   true; `reappearAtMs` also set on tracked frames; the scripted taps after the step; simulated time for
   `sinceLandedMs`; `scenario` without `restartAtSpawn`; `syncCast` after the step; `trackingEdge` without its
   compare; the cast state only while tracking. Each must turn a named test red; list them in the Result.
8. Fill the Result in its own commit, set `status: review`. The final message names the four commits and the host
   files, and says "needs your Pixel run" with the check below.

## Done when
- `scripts/verify.sh` exit 0; core tests up by at least 28, shared unchanged, android back to its old count
- the diffs of steps 3 and 4 were as described (paste their output into the Result); 8 of 8 mutations caught
- `git diff <A>^..<C> --stat -- androidApp/src/main`: no `ar/TableEngine*.kt` left; `RudeusArView.kt` about 20 lines
  in and 95 out, `CharacterNode.kt` about 10 in and 30 out
- `grep -n "loop\." androidApp/src/main/kotlin/kz/rudy/ar/ar/RudeusArView.kt` shows only `val loop = game.loop`
  and tap and grab code (old 317, 428-431, 564, 567, 602-603, 609, 807, 816, 822)
- `grep -rn "HAND-REVIEW" core/src shared/src` prints nothing; no file outside "Owns" changed
- **The one Pixel check (human, about two minutes), "walk, cover, grow, replay".** One character on a table with a
  cup, scanned once, footprints on.
  1. Tap to its right, then to its left: it walks and bobs, turns once each way, and the mini-map dot goes with it.
  2. Tap far away and cover the lens for 3 seconds while it walks: the Paused scrim comes at once and the character
     stays put. Uncover: the scrim goes, the character is hidden for a little under a second, then stands where it
     stopped, without sliding, and finishes the walk.
  3. Add a second character: it appears beside the first within a moment. Press Bigger: both grow, feet on the table.
  4. Press Jump: it stretches going up and squashes as it lands.
  5. Press Scan again: "found", and the character still cannot walk through the cup.
  6. Press Scenario and cover the lens for 2 seconds half-way: the first character starts from the spawn point,
     freezes under the cover, goes on, and plays to the end.
  In `scripts/device.sh log`: `scenario_start id=day5 steps=5`, `scenario_step idx=0` to `idx=4` in order, one
  `scenario_done ms=` just over 15000 in spite of the cover, one `cast_add` with `n=2`, no `scenario_rejected`, no
  `anchor_refused`. What a fault means: a slide or a character that never comes back is the reappear timer or
  `visible`; a sprite standing still while the dot moves is the node write; a walk that looks backwards is
  `spriteBuilt`; a scrim that is late, missing or stuck is `trackingEdge`; a dead dot is the cast-state report; a
  replay that ends early or skips a beat is the scenario clock. Revert commit C and look again: if it is now right,
  the fault is in what differs between the host copies and the common ones (imports and visibility only, so report
  it). If it is still wrong, revert B and then A (B alone cannot stay: the T53 host cannot call its signatures; P
  can) and report which step failed.

## After T56 (not built here)
- The host still reads `engine.loop` at the tap and grab lines listed under "Done when"; T54 takes them, moves
  `confirmSurface` behind `engine.level`, and `val loop` then stops being public. The scan block, `startScan` and the
  early `trackingEdge` call go in S5. `SlotState` keeps `surfaceReady` and its two delegates until then.
- The duel stage (T50's brief) extracts the stage interface as a `:core`-only change pinned by this brief's tests:
  steps 4-5 of `onFrame` and `level` / `standing()` are what a second stage replaces; the edge, the cast sync, the
  report, the timer and the posers stay.
- S4 appends `planeToClip` and the view size to `CameraFrame`; T50 appends `NodePose.picture`. Nothing here changes.

## Result (filled on acceptance)
- commits P, A, B, C; measured: test counts per commit, the mutation list, the host diff stat
- line check against 6d157cc; the step-3 and step-4 diffs: ...
- found by the characterization tests; deviations from the letter of the brief: ...
- Pixel check ("walk, cover, grow, replay"): ...
- cut/deferred: ...
