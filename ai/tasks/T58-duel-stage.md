# T58 — duel-stage

status: review
created: 2026-11-12
phase: v2 wave 4 (ai/build-plan-v2.md, "The game" and "iOS readiness"). Takes the duel half of the plan's T50 row; 2.5D
  views and the per-frame picture swap stay in T50 (a host brief, later)
gate: G5 duel on the Pixel (this brief is the `:core` half; `ai/tasks/T59-duel-app-flow.md` is the `:shared` half); keeps G1
cut-if-slips: commit D's "level" group (a level that arrives during a duel is then ignored until the duel ends, and T59
  drops its Rescan step); never the seam, the input mapping or pause / resume
depends: **T54 merged** (it owns `TableEngine.kt` while `doing`), then commit K below. T59 runs in parallel from K
tier: rudeus-opus-extra (a line-exact move pinned by about 100 existing tests, then physics timing: sub-step grid, pause /
  resume, determinism at two frame lengths; the job that tier did for T53, T56 and T54)

**Re-check after T54 merges** (this brief was written against T54's contract block and its uncommitted host-local
engine): (1) `TableEngine.onTap` is: anchor guard, `table.grabbable` pick, `activeId()`, `from`, `depthGapM()`,
`port.depthGapsOnWay(from, uv, walkDepthSamples) + depthGap`, the `tap_depth_gap=` line, `SurfaceBoundary.crossesEdge`,
then BLOCKED or `confirmSurface` + `loop.walkTo`; (2) `onTouch` is `if (!anchored) return false; return
touch.onTouch(e, table)`; (3) `CastTouch` has `unbindMember(id)`; (4) `loop` is `internal`; (5) `TableSlot` ends with
`onActiveChanged`, `onLevel`; (6) the constructor is `(current, surface, playEmoteSound, port, log, feetDepthBias,
reappearAfterTrackingMs, walkDepthSamples, leadDepthSamples, separate = true)`. Where one differs, keep the rule below
and adapt the wording; where a rule below cannot hold, stop and report.

## Goal
`core.session.TableEngine` can run a duel instead of the walking game, on the same anchor, with the same cast, nodes,
taps and buttons: the slot names a `DuelSetup`, the engine starts a `DuelMatch` on the scanned level, steps it with
`DuelRunner` (the child's side from taps and commands, the other side a `DuelBot`), poses both fighters through the
posers it already has, and reports the `DuelMatchState` to the slot. When the slot drops the setup the walking game goes
on where it stood. No host line changes: `RudeusArView` already hands the engine every frame, tap, touch and slot.
After this brief nothing is visible on the phone (no UI sets a duel until T59); everything is proved on the JVM.

## Decisions taken by the orchestrator (not open)
- No edit in `androidApp/**` or Gradle. Fighters are cast members; the engine decides what a tap and a command mean.
- The stage seam is extracted now, `:core` only. The walking game becomes the first stage with no behaviour change:
  the test files of T53, T56 and T54 stay green **untouched**. The tracking edge, cast sync, reappear timer, posers
  and `NodePose` stay engine-level, shared by both stages.
- The duel wraps the match as `DuelRunner` does: fixed sub-steps on simulated time, `dt` clamped and the frame clock
  forgotten on a tracking loss as `GameLoop` does (PAUSED freezes, never resets), bot decisions on their tick grid with
  their own seed, state read from `DuelMatchState`, never from per-action events (T47's finding).
- A table tap is `DuelInput.MoveTo` for the child's fighter; Dash, Jump, Special are commands; grabbing or picking a
  character does nothing in a duel. Stats come from each member (`Member.stats`). `DuelParams.DEFAULT` rules and the
  default `BotTuning`; none of `ai/v2-human.md` §4.3 is decided here.

## Decisions taken here (not open)
1. **Three commits and a contract.** K (the shared contract, about 45 lines, inert) is committed first, by the
   orchestrator on main right after T54 merges (the plan's wave-0 precedent) or by this brief's agent as its first
   commit; T59 branches from K. Then S (the seam, a move) and D (the duel). Revert points: D alone, then S; K can stay.
2. **The stage is chosen per frame from `TableSlot.duel`, like the cast sync, not by a Compose effect.** A setup is
   state ("this match is wanted"), compared by its `serial` at the start of every anchored frame, after `syncCast`
   (the opponent may join in the same frame). So `rememberTableEngine` gains no effect and no host line reads it.
3. **The cast stays in the walking `GameLoop` in both stages.** A duel does not step the loop and does not pause it:
   every member keeps its body, target and clocks, and gets them back when the duel ends (the loop's first step
   after it has `dt <= MAX_STEP_DT_MS`, its own clamp; `GameLoop.kt` is not touched). The two fighters are drawn from
   their `DuelFighter.body`; every other member is posed hidden (`visible = false`). After the duel the fighters
   stand where they stood before it. Smallest thing that needs no new loop API; the jump back is seen when T59's
   result card closes, or at once on Back.
4. **`level` stays one engine property, the scanned level** (`loop.level`), in both stages: the host's scan merges
   against it and mirrors it by identity (`RudeusArView.kt` `startScan`, the tap's `game.level !== before`). The
   duel keeps its own `arena`, taken from `level` at the start. This is narrower than T56's "`level` is what a stage
   replaces": only what a *write* means differs (decision 9).
5. **A duel needs a level with a boundary; `DuelArena.arena`'s ring is not used.** Without a boundary (the floor, a
   table before its first scan) the setup is refused (`NO_BOUNDARY`). The ring would be an edge nobody can see: the
   host draws a boundary only in debug chrome, from its own `level` mirror. A floor duel waits for a host that
   draws the ring (T50).
6. **The duel calls `DuelRunner.step`, it does not re-implement it.** Mine is slot `a`, the opponent slot `b` with
   `DuelBot.mind(foeLevel, 2 * seed + 1)`; a watched match gives mine `DuelBot.mind(mineBot, 2 * seed)` (`DuelSim`'s
   convention, so a watched match is the simulator's match, tested). `dt` is clamped to `MAX_STEP_DT_MS` (50 ms)
   before the runner's own 250 ms clamp.
7. **Marks.** `DuelArena.marks(arena, loop.radiusM, DuelArenas.SEPARATION_M * profile.scale * size, loop.params)`;
   none = refused (`NO_ROOM`). Mine takes the mark nearer the camera (plane distance at that frame; a tie = the
   first), so the child's fighter starts on the child's side. Radius, `DuelParams.forProfile(profile, size)` and
   `loop.params` are read once at the start: a size change during a duel reaches the loop only (T59 shows no size
   buttons in a duel). The search is "tens of ms on the JVM" (`DuelArena.marks` KDoc) and runs inside one AR frame:
   its time is logged (`marks_ms=`, `kotlin.time.TimeSource.Monotonic`) for the Pixel.
8. **Input.** Between frames the stage keeps at most one move and one action. `onTap`: no pick; in FIGHT only (else
   nothing is asked of the port); the same depth refusal as free play, in the same order (`depthGapM()`, then
   `port.depthGapsOnWay(from, uv, walkDepthSamples) + depthGap`, `crossesEdge`), then the target is
   `arena.nearestInside(uv)`: clamped instead of grown, so a tap can neither change the arena nor walk the fighter
   off it. Commands for `mineId`: Jump, Dash (`DuelInput.Dash()`: along `facing`, the plan's rule), Special; the
   last pressed wins. The first tracked frame with `dt > 0` sends the action if there is one (the move then waits
   one frame), else the move. An untracked frame drops both (T53's rule: a Jump pressed under the cover does
   nothing). Inputs outside FIGHT are `DuelMatch`'s to ignore. `onTouch` returns false before `CastTouch` is asked;
   bindings held when a duel starts are released.
9. **Never a scan mid-round, and a rescan between rounds needs no host edit.** The host's scan ends in `engine.level
   = built`. In a duel the write is kept (`level` returns it at once) and the *arena* follows on the first tracked
   frame that starts in COUNTDOWN or POINT: `DuelMatch.rearrange` with the new level's marks, each fighter taking
   the new mark nearer its old one. No boundary or no marks on the new level: the old arena stays, logged. Because a
   scan (3-5 s) outlasts POINT + COUNTDOWN (4.5 s), `DuelSetup.hold` stops the countdown clock while T59 has a scan
   running. For the host's merge `standing()` stays every member in cast order, a fighter with its duel state; an
   out fighter needs no special case (`SurfaceBoundary.afterScan` skips a state past the boundary).
10. **Reports: every 100 ms, and at once when something a child reads changes.** `DUEL_REPORT_PERIOD_MS = 100`, the
    cast-state report's period: a frame arrives every 16 ms and must not recompose the HUD. At once: the start, a new
    phase, round or score, a frame that applied the child's input (the cooldown must show under the thumb), the
    first frame back from a loss, a rearranged arena. Never the same `DuelMatchState` instance twice (RESULT and a
    paused match report once). Cost of the period: a button can look ready up to 100 ms late, 4 % of a dash
    cooldown; the cooldown fill moves in 25 steps.
11. **Body transitions of both fighters go to `onCastEvent`** (`CharacterEvents.between(previousBody, body)` per
    frame, mine first): the host's wrapper plays jump, landing and fall sounds from them with no new line
    (`MainActivity.kt:82`), and LANDED sets the landing clock for the poser's squash. No duel decision reads them.
    Match events (`RunnerStep.events`) are logged, one line each, for the Pixel evidence; nothing reads them either.
12. **A refusal never throws.** `DuelMatch.start` and `rearrange` `require` valid marks; the stage catches
    `IllegalArgumentException` around both and answers `NO_ROOM` / keeps the old arena. A fighter that leaves the
    cast ends the duel (`NO_FIGHTER`). A refused serial is not retried.
13. **No constructor change, no new public method.** Everything arrives through `TableSlot`. Tests read the match
    through `internal val duelMatch: DuelMatchState?`, as they read `loop`.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- core/src/commonMain/kotlin/kz/rudy/ar/core/session/Commands.kt (K), DuelContract.kt (new, K)
- core/src/commonMain/kotlin/kz/rudy/ar/core/session/TableEngine.kt (K: three places; S; D)
- core/src/commonMain/kotlin/kz/rudy/ar/core/session/Stage.kt (new, S), DuelStage.kt (new, D)
- core/src/commonTest/kotlin/kz/rudy/ar/core/session/DuelStageTest.kt (new, D)
- ai/tasks/T58-duel-stage.md (the Result section)

Must not touch: `GameLoop.kt`, `BillboardPoser.kt`, `CastTouch.kt`, `ArPort.kt`, `TouchEvent.kt`, `FrameInput.kt`,
`core/duel/**`, `core/physics/**`, `core/domain/**`, every existing test file (`TableEngineTest`, `TableFrameTest`,
`TableTouchTest`, `ScreenViewTest`, `GameLoopTest`, the duel tests), `shared/**` (T59's), `androidApp/**`, Gradle.

## Contract
### Commit K (the contract both briefs build on; inert: nothing reads the new fields yet)
```kotlin
// Commands.kt — appended to CharacterAction
    /** Duel only: a dash along the fighter's facing. Ignored outside a duel. */
    data object Dash : CharacterAction
    /** Duel only: the fighter's special (stomp, lift-off, ram, dodge). Ignored outside a duel. */
    data object Special : CharacterAction

// DuelContract.kt (new), package kz.rudy.ar.core.session
/** The duel the UI wants on the table; `TableSlot.duel == null` is the walking game. Immutable, one per slot. */
class DuelSetup(
    val serial: Int,               // rising; a serial the engine has not seen starts a new match, a seen one never restarts
    val mineId: String,            // the child's fighter: a member id
    val foeId: String,             // the opponent: another member id, played by DuelBot
    val foeLevel: BotLevel,
    val seed: Long,                // the bots' PRNG: 2 * seed + 1 for the opponent, 2 * seed for mine when it is a bot
    val mineBot: BotLevel? = null, // "Watch": my side is a bot too; taps and commands then do nothing
    val hold: Boolean = false,     // read on every frame: while true a COUNTDOWN's clock does not run
)
/** What the engine tells the slot about the setup with [serial]. */
sealed interface DuelReport {
    val serial: Int
    /** The match as it stands, with everything `duelHud(...)` needs to draw it. */
    class Running(override val serial: Int, val state: DuelMatchState, val arena: Level, val rules: DuelRules, val params: DuelParams) : DuelReport
    /** The match did not start, or ended before RESULT; the table is the walking game's again. */
    class Refused(override val serial: Int, val reason: DuelRefusal) : DuelReport
}
enum class DuelRefusal { NO_BOUNDARY, NO_ROOM, NO_FIGHTER }

// TableEngine.kt
class Member(val id: String, val ability: Ability, val emotes: EmoteSet,
             val stats: FighterStats = FighterStats.defaultFor(ability))   // what a duel reads; no pictures
class TableSlot(/* ... T54's fields ..., then: */
    val duel: DuelSetup? = null,
    val onDuel: (DuelReport) -> Unit = {},   // main thread, from onFrame; cheap, must not call back into the engine
)
// command(): the `when` gains one branch, so it stays exhaustive
//   CharacterAction.Dash, CharacterAction.Special -> log("duel_command_ignored serial=${c.serial} id=${c.id} duel=false")
```
### Commit S (the seam; a move)
```kotlin
// Stage.kt — what differs between the walking game and a duel. Main thread, between and inside frames, as the engine.
internal interface Stage {
    val fingers: Boolean                                   // may a finger pick, grab and lead members (CastTouch)
    fun tap(xPx: Float, yPx: Float, uv: Vec2, depthGapM: () -> Float?)   // after the engine's anchor guard
    fun command(c: CharacterCommand, from: TableSlot)      // after the serial, anchor and membership guards
    fun step(input: FrameInput)                            // old steps 4-5 of onFrame; events go to the slot from here
    fun standing(): List<CharacterState>                   // cast order
    // What the pose loop reads for member [id] of loop.cast (the loop stays one loop, in the engine):
    fun shows(id: String): Boolean                         // false = posed with visible = false
    fun body(id: String): PhysicsBody
    fun walkPhaseMs(id: String): Long
    fun landedAtMs(id: String): Long
    fun emotePose(id: String): EmotePose?
}
```
In `TableEngine.kt`: `private inner class Walk : Stage` holds, **moved and not edited**, the body of `onTap` after its
guard, the `when` of `command`, the scenario feed with `loop.onFrame` (T56's steps 4-5), and `standing()`; its other
members return the loop's values (`fingers = true`, `shows = true`). An inner class, so `table`, `confirmSurface`,
`replay` and the rest stay the engine's privates and no moved line needs a substitution. The engine keeps `private
var stage: Stage = walk` and delegates: `onTap` (guard, then `stage.tap`), `command` (guards, then `stage.command`),
`onTouch` (`if (!anchored || !stage.fingers) return false`), `standing()`, and in `onFrame` `stage.step(input)` plus a
pose loop that reads `stage.` where it read `loop.` / `m.body`. `scenario()`, `configure`, `abilities`, `syncCast`,
`level`, the cast-state report and the reappear timer are not the stage's. With only `Walk` this commit changes no
behaviour and allocates nothing new per frame.

### Commit D (the duel)
`DuelStage.kt`: `internal class DuelStage(setup, loop, current, profile, port, log, walkDepthSamples, camera: Vec2) :
Stage`, built by the engine's new private `syncDuel(input)`, called in `onFrame` right after `syncCast`:
- `current().duel == null` and a duel runs: `duel_end serial= phase= score=A:B`, `stage = walk`.
- on a tracked frame (the camera decides the sides), a setup whose serial differs from the last one seen (also
  during a match: a rematch): remember the serial; refuse
  (`duel_refused serial= reason=`, `onDuel(Refused)`, `stage = walk`) when `mineId == foeId` or either is not in the
  loop (`NO_FIGHTER`), `loop.level.boundary == null` (`NO_BOUNDARY`), no marks or `start` throws (`NO_ROOM`); else
  start: `DuelMatch.start(Contestant(mine), Contestant(foe), arena, marks, DuelRules.DEFAULT, params, physics,
  radius)` (stats and ability from `current().members`), the minds of decision 6, every `CastTouch` binding released,
  `duel_start serial= mine= foe= level= seed= marks_ms=`, the frame clock empty, one report.
- a fighter no longer in the loop: `duel_refused .. reason=NO_FIGHTER`, `onDuel(Refused)`, `stage = walk`.

`DuelStage.step(input)`, in this order:
1. Not tracking: `DuelRunner.step(.., dtMs = 0, tracking = false)` (both bodies PAUSED, no clock, no bot), the frame
   clock forgotten, the kept move and action dropped. Return.
2. A level that arrived (decision 9) and the phase is COUNTDOWN or POINT: rearrange, `duel_arena marks=..` or
   `duel_arena_kept reason=`.
3. `dt = lastMs?.let { (nowMs - it).coerceIn(0, MAX_STEP_DT_MS) } ?: 0`; `lastMs = nowMs`; `dt = 0` while
   `current().duel?.hold == true` and the phase is COUNTDOWN.
4. `DuelRunner.step(runner, arena, dt, inputs, tracking = true, rules, params, physics, tuning)`; `inputs` is
   decision 8's one entry for `mineId` when `dt > 0` and mine is not a bot (a `dt = 0` call only resumes).
5. Per fighter: `walkPhaseMs += dt` while its body is WALKING; `CharacterEvents.between(before, after)` to
   `current().onCastEvent`; LANDED sets `landedAtMs = nowMs` (host clock, as `GameLoop`).
6. One log line per match event: `duel_round n= at_ms=`, `duel_point round= winner= reason= at_ms= score=A:B`,
   `duel_over winner= at_ms= rounds=`, `duel_fight <the DuelEvent's own text>`.
7. The report rule of decision 10.

The other answers: `shows(id)` = a fighter whose body is not RESPAWNING; `body(id)` = the fighter's `DuelFighter.body`,
a bench member's loop body; `emotePose` null; `fingers = false`; `command`: decision 8, anything else (WalkForward,
an emote, another id, a watched match) logs `duel_command_ignored serial= id= duel=true`; `tap`: decision 8, its
refusal logs `duel_tap_past_edge u= v=` and reports no BLOCKED. Engine-level in a duel: `level`'s setter also tells
the stage; `scenario()` logs `scenario_rejected duel=true` and is not retried; no cast-state report; `configure` and
`abilities` reach the loop as always.

**What must stay exactly as it is.** Every rule T53's, T56's and T54's tests pin, with their files untouched; every
existing log line byte for byte; one anchor per session (no stage can create or refuse one); PAUSED freezes and never
resets, in both stages; nothing is scanned or asked of the network; no ARCore, Compose or Android type in `:core`;
without a `duel` in the slot the engine asks the port, calls the slot and allocates exactly as after T54, plus one
null check per frame. `DuelMatch`, `DuelRunner`, `DuelBot`, `DuelPhysics` and their numbers are used, not changed.

## Tests required
S: none new. The proof is that `TableEngineTest`, `TableFrameTest`, `TableTouchTest` pass with no changed line, and
step 3's diff.
D: `DuelStageTest`. Harness `DuelHost`: the engine with T54's constructor, a port that answers `depthGapsOnWay` from
a `(Vec2) -> Float?` (all nulls by default) and counts every question, slots that carry `duel` and record every
`onDuel`, `onCastEvent`, `onLevel`, `onActiveChanged`, members with stats, `engine.level = DuelArenas.EMPTY` (or
`CUP_AND_BOOK`, `SMALL`) after the anchor, frames of a given length on the host clock. Floats in log lines: keys only.
Start and stop:
- `aDuelStartsOnTheFrameItsSerialAppears` — COUNTDOWN, round 1, 0:0, both on `DuelArena.marks` of the level facing
  each other; `duel_start`; one report
- `myFighterTakesTheMarkNearerTheCamera` — from both sides of the table
- `statsAbilityRadiusAndParamsComeFromTheMembersTheSurfaceAndTheSize` — a member without stats fights with its archetype
- `withoutABoundaryTheDuelIsRefusedOnceAndWalkingGoesOn`, `withoutRoomForTwoTheDuelIsRefused` (a 10 cm table),
  `aFighterThatIsNotOnTheTableRefusesTheDuel` (unknown id; `mineId == foeId`)
- `aNewSerialIsANewMatchAndTheSameSerialNeverRestarts` — mid-match and after RESULT; a new `DuelSetup` instance with
  the old serial changes nothing; another seed gives other bot decisions
- `leavingTheDuelGivesTheWalkingGameBackWhereItStood` — every loop body equal to before, a walk set before the duel
  finishes after it, `duel_end`
- `aFighterLeavingTheCastEndsTheDuel`, `aDuelNeverTouchesTheAnchor` (`onAnchorPlaced` once, no `anchor_refused`)
Frame:
- `benchMembersAreHiddenAndFrozenAndComeBack` — four members: one pose each in cast order, two visible
- `fightersArePosedFromTheirDuelBodies` — and a RESPAWNING fighter is hidden
- `dtIsClampedToFiftyMs`, `theFirstFrameAndTheFrameBackStepNothing`
- `aLossFreezesTheMatchAndNothingResets` — 70 s untracked in FIGHT: `timeMs`, the round clock, positions, score and
  cooldowns as before; bodies PAUSED meanwhile; no poses; the match then goes on
- `aWatchedMatchIsTheSimulatorsMatch` — through the engine at 16 ms frames = `DuelSim.match` for the same
  contestants, level, levels and seed: equal final `DuelMatchState`
- `aHumanMatchIsTheRunnersMatch` — taps and commands at match times k x 528 ms through the engine = the same inputs
  given to `DuelRunner.step` directly: equal states at every k x 528 ms and at the end
- `theSameSeedAndInputsGiveTheSameMatchAt16And33MsFrames` — 528 = 16 x 33, so both runs start a call there; also
  with a 2 s loss in one of them
- `aFullMatchRunsToResultThroughTheEngine` — RESULT, the winner has two points, `duel_over`
Input:
- `aTapIsMoveToForMyFighterOnly` — no `onActiveChanged`, no `onLevel`, the same `Level` instance
- `aTapOutsideTheArenaIsClampedInside`, `aTapPastADepthEdgeIsRefused` (no reading never refuses)
- `tapsOutsideAFightAskNothingAndDoNothing` — COUNTDOWN, POINT, RESULT
- `dashJumpAndSpecialAreCommandsForMyFighter` — each visible in the state after the next frame, for all four abilities
- `anActionWinsOverATapOfTheSameFrameAndTheTapFollows`, `inputsGivenDuringALossAreDropped`
- `otherCommandsAreIgnoredInADuelAndDuelCommandsOutsideOne` — both `duel_command_ignored` lines
- `fingersGrabNobodyInADuel` — DOWN on a fighter: false, `screenView` not asked; a binding held at the start is released
- `inAWatchedDuelTapsAndCommandsDoNothing`
Level:
- `standingIsEveryMemberInCastOrderWithTheFightersDuelStates`
- `aLevelThatArrivesInAFightWaitsForTheRoundsEnd` — `engine.level` returns it at once, the arena changes in POINT
- `aLevelThatArrivesBetweenRoundsMovesTheMarksAndKeepsTheSides`, `aLevelWithoutRoomKeepsTheOldArena`
- `aHeldCountdownWaitsAndGoesOnWhereItStopped` — FIGHT and POINT are not held
Reports and events:
- `reportsComeEvery100MsAndAtOnceOnAChange` — each of decision 10's cases; RESULT reports once
- `aReportCarriesTheArenaRulesAndParamsTheMatchRunsWith`
- `fightersTransitionsReachOnCastEvent` — JUMPED and LANDED for a jump, FELL for a ring-out; the landing clock
- `matchEventsAreLoggedOnceEach`, `noCastStateReportDuringADuel`
That is 39. Tests not required: anything in `:shared` (T59), `rememberTableEngine`, the host, a device; no test
re-proves `DuelMatch`, `DuelRunner` or `DuelBot` (theirs exist).

## Steps
Every Gradle call: `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk ./gradlew <tasks> --console=plain
-q`. `S` is a scratch directory.
1. Read `core/session/*.kt`, `core/duel/DuelRunner.kt`, `DuelMatch.kt`, `DuelState.kt`, `DuelArena.kt`, `DuelSim.kt`,
   `DuelRunnerTest.kt`, the Results of T53, T56 and T54, T56's "After T56", T54's last two sections. Do the header's
   re-check. Record the baseline counts (`scripts/verify.sh`).
2. **Commit K** `core: the duel contract (DuelSetup, DuelReport, Member.stats, Dash and Special) (T58)`, if the
   orchestrator has not made it: the contract block, nothing else. Tasks: `:core:jvmTest
   :core:compileKotlinIosSimulatorArm64 :shared:testAndroidHostTest :androidApp:assembleDebug`. Counts unchanged.
3. **Commit S** `core: the walking game is the table engine's first stage (T58)`. Save the engine first: `git show
   HEAD:core/src/commonMain/kotlin/kz/rudy/ar/core/session/TableEngine.kt > $S/old.kt`. For each moved body, `diff -w
   <(sed -n '<old lines>p' $S/old.kt) <(sed -n '<new lines>p' <TableEngine.kt>)` prints nothing; the pose loop's diff
   shows only `stage.` for `loop.` / `m.body`. Paste the four diffs into the Result. `git diff --stat HEAD --
   core/src/commonTest` is empty. Tasks: `:core:jvmTest :core:compileKotlinIosSimulatorArm64
   :core:compileTestKotlinIosSimulatorArm64`.
4. **Commit D** `core: a duel as the table engine's second stage (T58)`: `DuelStage.kt`, `syncDuel`, the `level`
   setter, the `scenario` guard, `duelMatch`, `DuelStageTest`. Same tasks as step 3.
5. `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk scripts/verify.sh`.
6. Mutation check, one at a time, each restored byte for byte (`cmp`); each must turn a named test red, listed in
   the Result. Seam: the pose loop reads the loop while a duel runs; `onTouch` without the `fingers` check. Duel:
   `dt` unclamped; the frame clock kept across a loss; a kept input surviving a loss; `2 * seed` for the opponent;
   mine on the far mark; a tap that calls `confirmSurface`; a tap that is not clamped; the tap's depth read outside
   FIGHT; a level applied in FIGHT; `rearrange` that swaps the sides; `hold` that also stops FIGHT; a report on every
   frame; no report on a phase change; the loop stepped during a duel; a bench member shown; `Walk` not restored
   when the setup goes; a refusal that is retried on the next frame.
7. Fill the Result in its own commit, set `status: review`. The final message names the commits and says "no host
   file changed; nothing to see on the phone until T59; needs your Pixel run" with the check below.

## Done when
- `scripts/verify.sh` exit 0; core tests up by at least 39, shared and android unchanged
- step 3's diffs were empty as described; `git diff --stat <K>^..HEAD` shows only files under "Owns", none under
  `core/src/commonTest` but `DuelStageTest.kt`, none under `androidApp/` or `shared/`
- 19 of 19 mutations caught
- `grep -rn "HAND-REVIEW" core/src` prints nothing; `grep -rnE "import (android|androidx|com\.google\.ar|io\.github\.sceneview)" core/src/commonMain` prints nothing
- **The one Pixel check (human, about two minutes), "free play is what it was"**: the seam moved every walking body,
  and no duel can be started yet. Two characters on a table with a cup, scanned once, footprints on.
  1. Tap right and left of the active character: it walks to each tap. Tap just outside the loop: the loop grows.
  2. Tap the other character: its chip lights. Drag it 20 cm and lift: it follows the finger and stops there.
  3. Press Jump, then Hello: one jump, one wave, one sound each.
  4. Cover the lens 3 s while one walks: Paused, it stays put, comes back a little under a second after, finishes.
  5. Press Scenario: the first character plays the script to the end.
  In `scripts/device.sh log`: the lines of T54's and T56's checks (`tap_depth_gap=`, `boundary_grown n=`, `grab
  pointer=`, `release pointer=`, `jump id=`, `emote id=`, `scenario_done ms=` just over 15000), no line starting
  `duel_`, one `anchor t_ms=0`, no `anchor_refused`. If anything is off: revert D and look again; if still wrong,
  revert S and report which step failed.

## What the duel still lacks after T58 and T59 (not built here; none needs a host edit to play)
- A floor duel (decision 5) and the 2.5D views with the picture swap: T50, in the host.
- Sounds for a dash, a hit, a wall slam: `Sounds` maps `CharacterEvent`s only (`androidApp/.../audio/Sounds.kt:55-60`).
- A tap during 3-2-1 is dropped by `DuelMatch`; keeping the last one for the first FIGHT frame is two lines here if
  the Pixel says a child expects it.
- Dash goes along `facing`, where the fighter last walked; aiming it at the opponent is `DuelInput.Dash(dir)`, one
  line here, a rule decision for the user.

## Result (filled 2026-11-12, cloud container with /opt/android-sdk, no device)
- commits (on the branch `stage-duel` at K `847d116`, made by the orchestrator after the T54 merge `b99c9e0`; this
  brief's step 2 was skipped): S `de5b875` (the seam), D `7a5c2a0` (the duel), then this Result. Revert points as
  decided: D alone, then S; K can stay.
- K against the contract block: the same shapes, word for word (`CharacterAction.Dash` / `Special` with their KDoc;
  `DuelSetup`, `DuelReport.Running` / `Refused`, `DuelRefusal`; `Member.stats = FighterStats.defaultFor(ability)`;
  `TableSlot.duel` / `onDuel` appended after `onLevel`; the `command` branch logging
  `duel_command_ignored serial= id= duel=false`). Nothing differs; K was not touched.
- re-check after T54 (against K's `TableEngine.kt`): all six hold as written. (1) `onTap` 406-431: anchor guard,
  `table.grabbable`, `activeId()`, `from`, `depthGapM()`, `port.depthGapsOnWay(from, uv, walkDepthSamples) +
  depthGap`, `tap_depth_gap=`, `crossesEdge`, BLOCKED or `confirmSurface` + `loop.walkTo`; (2) `onTouch` 438-441;
  (3) `CastTouch.unbindMember(id)` (`CastTouch.kt:58`); (4) `internal val loop` (117); (5) `TableSlot` ends
  `onActiveChanged`, `onLevel` (79, 81), then K's two fields; (6) the constructor 94-115 as listed. No rule needed
  adapting.
- measured (test XML; every Gradle call under `flock /tmp/rudeus-gradle.lock`):
  - baseline at K: `scripts/verify.sh` exit 0: core 814, shared 43, android 4.
  - S: `:core:jvmTest :core:compileKotlinIosSimulatorArm64 :core:compileTestKotlinIosSimulatorArm64` green; core 814
    (`TableEngineTest` 27, `TableFrameTest` 28, `TableTouchTest` 47, `ScreenViewTest` and `GameLoopTest` as before),
    0 failed; `git diff --stat HEAD -- core/src/commonTest` empty; no compiler warning in a session file.
  - D: the same tasks green; core 853 = 814 + 39 `DuelStageTest`, 0 failed; `scripts/verify.sh` exit 0: core 853,
    shared 43, android 4, apk built. Up by 39 in core, shared and android unchanged.
  - `DuelStageTest` runs in about 0.6 s on the JVM (39 tests, several full matches at 16 ms frames). Its printed
    `marks_ms=` for LARGE (120 x 60 cm) at size 1.5 is 2 ms (warm JVM); a full idle-vs-HARD match on SMALL is
    0:2 at 15 528 ms of match time with 16 log lines.
  - `grep -rn "HAND-REVIEW" core/src`: empty. `grep -rnE "import (android|androidx|com\.google\.ar|io\.github\.sceneview)"
    core/src/commonMain`: empty. `git diff --stat 847d116..HEAD`: `Stage.kt`, `DuelStage.kt`, `TableEngine.kt`,
    `DuelStageTest.kt` and this file; nothing under `androidApp/`, `shared/`, no Gradle file.
- step-3 diffs (S; the engine at K saved with `git show HEAD:...` before editing; old lines on the left, the moved
  body on the right, line numbers of `TableEngine.kt` at S; `diff -w`):
  ```
  ### onTap after its guard: old 408,430 vs Walk.tap 371,393
  (no difference)
  ### the `when` of command: old 207,232 vs Walk.command 397,422
  (no difference)
  ### the scenario feed with loop.onFrame: old 345-346 + 348,368 vs Walk.step 429,451
  (no difference)
  ### the pose loop: old 381,391 vs onFrame 343,353
  4,6c4,6
  <                 m.body.state, cameraLocal, cameraHeightM, loop.walkPhaseMs(m.id),
  <                 verticalVelocity = m.body.verticalVelocity,
  <                 sinceLandedMs = now - loop.landedAtMs(m.id),
  ---
  >                 stage.body(m.id).state, cameraLocal, cameraHeightM, stage.walkPhaseMs(m.id),
  >                 verticalVelocity = stage.body(m.id).verticalVelocity,
  >                 sinceLandedMs = now - stage.landedAtMs(m.id),
  8c8
  <                 emotePose = loop.emotePose(m.id),
  ---
  >                 emotePose = stage.emotePose(m.id),
  10c10
  <             poses += if (visible) p else p.copy(visible = false)
  ---
  >             poses += if (visible && stage.shows(m.id)) p else p.copy(visible = false)
  ### standing(): old 242 vs Walk.standing 454
  1c1
  <     fun standing(): List<CharacterState> = loop.cast.members.map { it.body.state }.ifEmpty { listOf(loop.state) }
  ---
  >         override fun standing(): List<CharacterState> = loop.cast.members.map { it.body.state }.ifEmpty { listOf(loop.state) }
  ```
  The new wrapper lines around the moved bodies: `Walk.step` opens with `val now = input.nowMs` and `val tracking =
  input.tracking` (onFrame keeps its own two); `onFrame` reads `val cast = loop.cast` after `stage.step(input)` (the
  instance `loop.onFrame` returned). No moved body looked like it needed a change; none was changed.
- mutation check (one at a time, the file restored byte for byte after each, `cmp` true every time;
  `kz.rudy.ar.core.session.*` run; the script and its log are scratch files outside the repo): **19 of 19 caught**.
  1. the pose loop reads the loop (`loop.body(m.id)!!`, `loop.walkPhaseMs`, `loop.landedAtMs`) -> `benchMembersAreHiddenAndFrozenAndComeBack`,
     `fightersArePosedFromTheirDuelBodies`, `fightersTransitionsReachOnCastEvent`
  2. `onTouch` without the `fingers` check -> `fingersGrabNobodyInADuel`
  3. `dt` unclamped (`coerceAtLeast(0)`) -> `dtIsClampedToFiftyMs`
  4. the frame clock kept across a loss -> `aLossFreezesTheMatchAndNothingResets`, `theFirstFrameAndTheFrameBackStepNothing`,
     `theSameSeedAndInputsGiveTheSameMatchAt16And33MsFrames`
  5. a kept input surviving a loss -> `inputsGivenDuringALossAreDropped`
  6. `2 * seed` for the opponent -> `aHumanMatchIsTheRunnersMatch`, `aWatchedMatchIsTheSimulatorsMatch`, `matchEventsAreLoggedOnceEach`
  7. mine on the far mark -> `myFighterTakesTheMarkNearerTheCamera`, `aLevelThatArrivesBetweenRoundsMovesTheMarksAndKeepsTheSides`
  8. a tap that calls `confirmSurface` (in `onTap` after `stage.tap`, duel only) -> `aTapOutsideTheArenaIsClampedInside`
  9. a tap that is not clamped -> `aTapOutsideTheArenaIsClampedInside`
  10. the tap's depth read outside FIGHT (the phase check moved below the readings) -> `tapsOutsideAFightAskNothingAndDoNothing`
  11. a level applied in FIGHT (no phase condition; `rearrange` refuses, the level is used up as kept) ->
      `aLevelThatArrivesInAFightWaitsForTheRoundsEnd`
  12. `rearrange` that swaps the sides -> `aLevelThatArrivesBetweenRoundsMovesTheMarksAndKeepsTheSides`
  13. `hold` that also stops FIGHT (and POINT) -> `aHeldCountdownWaitsAndGoesOnWhereItStopped`
  14. a report on every frame -> `reportsComeEvery100MsAndAtOnceOnAChange`
  15. no report on a phase change -> `reportsComeEvery100MsAndAtOnceOnAChange`, `aFullMatchRunsToResultThroughTheEngine`
  16. the loop stepped during a duel -> `benchMembersAreHiddenAndFrozenAndComeBack`, `fightersTransitionsReachOnCastEvent`,
      `leavingTheDuelGivesTheWalkingGameBackWhereItStood`
  17. a bench member shown -> `benchMembersAreHiddenAndFrozenAndComeBack`
  18. `Walk` not restored when the setup goes -> `benchMembersAreHiddenAndFrozenAndComeBack`, `leavingTheDuelGivesTheWalkingGameBackWhereItStood`,
      `noCastStateReportDuringADuel`, `otherCommandsAreIgnoredInADuelAndDuelCommandsOutsideOne`
  19. a refusal retried on the next frame (`duelSerial = setup.serial` removed) -> 34 of the 39, among them
      `withoutABoundaryTheDuelIsRefusedOnceAndWalkingGoesOn`, `withoutRoomForTwoTheDuelIsRefused`,
      `aFighterThatIsNotOnTheTableRefusesTheDuel` (the same line also stops a started serial from being remembered, so
      every match restarts on every frame; that run took about 25 minutes, a marks search per frame)
- deviations from the letter of the brief (none changes a contract shape or a host line):
  1. `DuelStage`'s constructor is private; `DuelStage.open(setup, loop, current, profile, port, log, walkDepthSamples,
     camera)`, the brief's eight arguments, builds it and answers null for NO_ROOM (no marks, or `forProfile`, `marks`
     or `DuelMatch.start` threw `IllegalArgumentException`, all caught). A constructor cannot refuse without throwing.
     NO_FIGHTER and NO_BOUNDARY are decided by `syncDuel` before it; NO_FIGHTER also needs the id in the slot's members
     (the stats come from there).
  2. Step 1's "Return" skips steps 2-4 and 6; step 5's transitions and step 7's report still run on an untracked frame.
     So the loss frame reports PAUSED for both fighters and the frame back RESUMED (mine first), as `GameLoop` reports
     them in free play, and decision 10's "a paused match reports once" has a report to make. Pinned by
     `fightersTransitionsReachOnCastEvent` and `reportsComeEvery100MsAndAtOnceOnAChange`.
  3. The pose loop's diff has one token more than "`stage.` for `loop.` / `m.body`": `visible && stage.shows(m.id)`,
     where the Stage contract's `shows` is read (true for every member in `Walk`).
  4. `Walk.step` carries `@Suppress("UNUSED_VARIABLE")`: its moved last line keeps `val cast = loop.onFrame(...)`
     unedited. `Walk.body` is an index loop over `loop.cast.members` (no iterator per call; the pose loop asks twice per
     member per frame where it read `m.body`).
  5. `standing()`'s moved line differs by `override`; the old comment above `syncCast` (old 345-346, about the scripted
     taps) moved with the scenario feed into `Walk.step`.
  6. "Each fighter taking the new mark nearer its old one" is the pairing with the smaller sum of distances (a tie keeps
     the search's order): the per-fighter rule can give both the same mark (on the test's moved table both old marks are
     nearer (0.02, 0.05)).
  7. Log lines the brief left open: `duel_arena marks=<u,v,u,v> phase=<phase> marks_ms=<n>` (mine's mark first),
     `duel_arena_kept reason=NO_BOUNDARY` and `reason=NO_ROOM marks_ms=<n>`, `duel_point .. winner=null` for a replay,
     `duel_start .. level=<the foe's BotLevel>`. A duel tap logs no `tap_depth_gap=` line, only its refusal.
  8. `DuelParams.forProfile(profile, size)` and the separation use the slot's `characterSize` at the start; the radius
     and physics are the loop's (`loop.radiusM`, `loop.params`), as the brief says.
- found, not fixed (nothing under `core/duel` was touched):
  1. Balance, `DuelBot`: on `DuelArenas.CUP_AND_BOOK` a NORMAL or HARD bot of any ability never scores against a
     fighter that never moves (JUMP, archetype stats): the child wins 2:0 on two 60 s timeouts, 3 of 3 seeds, from
     either mark; an EASY bot wins 3 of 3. On EMPTY and SMALL every level wins 3 of 3 (0:2 in about 22 s and 15 s). A
     temporary probe (DuelRunner at 16 ms, deleted before commit) measured it; nothing pins it. A scanned table with
     a cup and a book is the duel's normal case, so a child who stands still beats Normal and Hard there: for the
     balance brief (`docs/duel-balance.md`), and worth one Pixel match before T59's flow is judged.
  2. Frame cost on a phone: `DuelArena.marks` runs inside one AR frame at the start and at every rearrange (2 ms warm
     on the JVM for LARGE; its KDoc says tens of ms cold for up to 1.3 million pairs; an ARCore hull with many vertices
     adds a segment test per vertex per grid point). Expect one or a few dropped frames at a duel's start and after a
     rescan; `marks_ms=` measures it on the Pixel. Per frame the match is immutable data: every 8 ms sub-step copies
     `DuelState`, both `DuelFighter`s, their `PhysicsBody` and `CharacterState`; `DuelRunner` adds a `RunnerState`, the
     `MatchStep` lists and a `HashMap` per bot input; the stage adds one input map per input and one `Running` per
     report. Tens of short-lived objects per frame, nothing retained.
  3. Nothing catches an exception from `DuelRunner.step` inside `onFrame`: one would reach the host's frame callback
     (Android: a crash). None was found on valid input, but every arena the duel was tuned on is a rectangle from
     `DuelArenas`; a real ARCore hull has not been through `DuelBot`.
  4. Starting a duel releases every binding (`release pointer= id=` lines) but `CastTouch`'s `captured` flag stays
     until the next DOWN: a finger held through the start and the end of a duel gets its later MOVE / UP consumed
     (T54's finding 3 in a new place). Harmless, not pinned.
  5. A duel never steps the walking loop, so its scenario clock, emotes and walks stand still and the first walking
     frame after a duel steps the clamped 50 ms (decision 3, as designed).
- for T59, what the contract does not say:
  1. `state.duel.a` is always `mineId`, `b` the opponent; `scoreA` is mine.
  2. Reports: `Running` on the start frame, then on the first frame at least 100 ms after the last (112 ms at 16 ms
     frames), and at once on a new phase, round or score, an applied input, the first frame back from a loss, a
     rearranged arena. One paused report per loss, one RESULT report, then silence: keep the last `Running`.
  3. A serial is taken once, on the first tracked frame it is seen: a refused one is never retried, so a retry (after a
     rescan for NO_BOUNDARY / NO_ROOM) needs a new serial. A new serial while a match runs, RESULT included, is a
     rematch at once. `duel = null` ends any phase at once; the fighters jump back to where they stood.
  4. `Refused(NO_FIGHTER)` mid-match means the duel is over and the table is the walking game's; the setup may stay in
     the slot (it is not restarted) until T59 drops it.
  5. Taps outside FIGHT are dropped, not kept; commands outside FIGHT are sent and ignored by `DuelMatch`. In a watched
     match both do nothing (commands log `duel_command_ignored`).
  6. `onCastEvent` gets the fighters' body transitions in a duel (JUMPED, LANDED, FELL, RESPAWNED, PAUSED, RESUMED,
     STARTED_WALKING, ARRIVED, BLOCKED, STEPPED_UP), so `HudSpeech`'s bubble reacts as in free play unless T59 mutes it.
  7. No `onCastState` (the mini-map freezes) and no `onLevel` during a duel; `onTracking` as always. A Scenario request
     during a duel is rejected and not retried: hide the button. A size change reaches the loop only.
  8. `hold` stops only a COUNTDOWN. A level written by the scan becomes the arena on the next tracked frame that starts
     in COUNTDOWN or POINT; written during FIGHT it waits for the point. `Running.arena` is then the new level.
  9. A member that joins the slot during a duel is placed (PLACED) and stays hidden on the bench.
- Pixel check ("free play is what it was"): **needs the user's run** (no device in the cloud container). No host file
  changed; nothing to see on the phone until T59. The five steps and the log lines under "Done when" as written: two
  characters on a table with a cup, scanned once, footprints on; tap right and left, tap just outside the loop; tap the
  other character and drag it 20 cm; Jump then Hello; cover the lens 3 s while one walks; Scenario. In
  `scripts/device.sh log`: `tap_depth_gap=`, `boundary_grown n=`, `grab pointer=`, `release pointer=`, `jump id=`,
  `emote id=`, `scenario_done ms=` just over 15000, no line starting `duel_`, one `anchor t_ms=0`, no `anchor_refused`.
  If anything is off: revert D and look again; if still wrong, revert S and report which step failed.
- cut/deferred: nothing cut (the "level" group is in). Not built, as the brief lists: the floor duel, sounds for a
  dash / hit / slam, keeping a 3-2-1 tap, aiming the dash.
