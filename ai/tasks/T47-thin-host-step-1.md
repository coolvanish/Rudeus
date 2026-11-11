# T47 — thin-host-step-1

status: todo
created: 2026-11-11
phase: v2 wave 2 (ai/build-plan-v2.md, "iOS readiness": thin host). Step S1 of the map in ai/tasks/T43-thin-host-map.md
gate: G1 foundation (`scripts/verify.sh` green, `:core` still compiles for iOS); protects G5 ("no tracking reset")
cut-if-slips: never cut. If the Pixel check fails, revert commit C alone: `:core` keeps the class and its tests
depends: T43 (the map). Disjoint from T44, T45, T46
tier: rudeus-opus-high is enough (a verbatim move plus characterization tests; the host diff is about 6 lines)

## Goal
The class that turns AR frames into physics steps, `GameLoop`, lives in `:core` with JVM tests, and the Android host
imports it instead of owning it. It is the piece that pauses every character when tracking is lost and never resets
them, clamps a frame hitch to 50 ms, keeps the simulated clock the scenario and the emotes run on, and tells the HUD
what happened since the last frame. Today it sits in `:androidApp` with no Android import and no test. After this
brief an iOS host gets it for free, the duel (T45) can build its match loop next to it, and the locked decision
"PAUSED freezes physics, never resets" is pinned by tests for the first time. The player sees no difference.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- core/src/commonMain/kotlin/kz/rudy/ar/core/session/GameLoop.kt (new: the moved code; `session` is a new package)
- core/src/commonTest/kotlin/kz/rudy/ar/core/session/GameLoopTest.kt (new)
- androidApp/src/test/kotlin/kz/rudy/ar/ar/GameLoopPinTest.kt (new in commit A, deleted in commit C)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/GameLoop.kt (HAND-REVIEW: deleted in commit C)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/RudeusArView.kt (HAND-REVIEW: only the four edits named under "Host diff")
- ai/tasks/T47-thin-host-step-1.md (the Result section)

Does not move and must not touch: `ar/CharacterNode.kt` (its KDoc still names `GameLoop.walkPhaseMs`; a stale KDoc
link is not worth a hand-review line, step S2 rewrites that file), `ar/CastTouch.kt`, the scan state and the scenario
feed inside `RudeusArView.kt`, `shared/**`, `core/physics/**`, `core/emote/**`, `core/scenario/**`, `core/domain/**`,
`core/duel/**` (T42 / T45), every Gradle file. No `TableEngine`, `FrameInput` or `NodePose` yet: each of those types
arrives with the step that first uses it, so no untested contract lands here.

## Contract
`androidApp/src/main/kotlin/kz/rudy/ar/ar/GameLoop.kt` (243 lines at 1b52cbb) moves as it is. Public surface in
`:core`, same names, same parameter order, same defaults as lines 47-243 of that file:

```kotlin
package kz.rudy.ar.core.session

const val NEVER_LANDED: Long = Long.MIN_VALUE / 2      // landedAtMs before any landing
const val MAX_STEP_DT_MS: Long = 50L                   // one frame never steps more than this

class GameLoop(
    var level: Level = Level(objects = emptyList(), boundary = null, spawn = Vec2.ZERO),
    /** T32 cut line 4: false steps the cast without CastPhysics.separate. The host passes its own switch. */
    private val separate: Boolean = true,
) {
    var cast: Cast            // private set
    var params: PhysicsParams // private set
    var simMs: Long           // private set; ms physics has stepped (clamped dt, tracking only)
    val ids: List<String>
    val firstId: String?
    val state: CharacterState                 // the first member's; a default state while the cast is empty
    val radiusM: Float
    fun has(id: String): Boolean
    fun body(id: String): PhysicsBody?
    fun state(id: String): CharacterState?
    fun configure(profile: SurfaceProfile, size: Float = CharacterSize.DEFAULT)
    fun spawnFor(near: Vec2, preferred: Vec2): Vec2
    fun add(id: String, pos: Vec2, ability: Ability)
    fun remove(id: String)
    fun walkTo(id: String, target: Vec2)
    fun jump(id: String): Boolean
    fun setAbility(id: String, ability: Ability)
    fun emote(id: String, c: Choreography)
    fun emotePose(id: String): EmotePose?
    fun walkPhaseMs(id: String): Long
    fun landedAtMs(id: String): Long
    fun restartAtSpawn()
    fun onFrame(
        nowMs: Long,                                             // host monotonic clock, ms
        tracking: Boolean,                                       // camera AND session anchor tracking
        onEvent: (String, CharacterEvent) -> Unit = { _, _ -> },
        beforeStep: (() -> Unit)? = null,
    ): Cast
}
```
`MemberClocks` stays `internal` (to `:core` now). Units: metres in plane coordinates `(u, v, h)`, times in ms as `Long`.

The only differences allowed between the host file and the core file, comments aside:
1. `package kz.rudy.ar.ar` -> `package kz.rudy.ar.core.session`.
2. `internal` dropped from `NEVER_LANDED`, `MAX_STEP_DT_MS` and `class GameLoop`.
3. The file-level `SEPARATION_ENABLED` (line 54) becomes the constructor parameter `separate`, used at the one
   `CastPhysics.step(...)` call (line 229). The switch itself stays in the host, where the user greps for it.
4. Comments: every `HAND-REVIEW:` marker in the file (the header at lines 20-45 and the notes at 53, 110, 204, 218)
   becomes an ordinary comment without the marker (`.claude/skills/hand-review/SKILL.md`: no `HAND-REVIEW` comments
   outside the zone; `core/src` has none today). Keep what each one says.

What must stay exactly as it is (these are the rules the tests pin):
- Not tracking: `CastPhysics.pause`, the frame clock is forgotten, nothing steps, `simMs` and every member clock
  stand still, `beforeStep` is not called. Nothing is ever reset: ids, positions, targets and `level` survive.
- Tracking: `CastPhysics.resume`, then `beforeStep`, then one step with `dt = (now - last)` clamped to `0..50` ms,
  and `dt = 0` on the first frame ever and on the first frame after a pause; `simMs += dt`; a member that is WALKING
  after the step adds `dt` to its walk phase.
- Events: the diff of the cast last reported against the current one (`CastEvents.between`), in cast order, so a
  `walkTo` made between two frames is reported by the next frame, once. `LANDED` stamps that member's `landedAtMs`
  with this frame's `nowMs`.
- `add` and `restartAtSpawn` produce no event. `jump` returns false when physics ignored it.

### Host diff (commit C, the whole of it)
1. Delete `androidApp/src/main/kotlin/kz/rudy/ar/ar/GameLoop.kt` and `androidApp/src/test/kotlin/kz/rudy/ar/ar/GameLoopPinTest.kt`.
2. `RudeusArView.kt`: add `import kz.rudy.ar.core.session.GameLoop`.
3. `RudeusArView.kt`, next to the other cut switches (after `CAST_TOUCH_ENABLED`, line 186): the switch moves here
   with its note, `private const val SEPARATION_ENABLED = true`, and line 306 becomes
   `val loop = remember { GameLoop(separate = SEPARATION_ENABLED) }`.
4. `RudeusArView.kt`, one new header line in the house form:
   `// HAND-REVIEW: T47 — GameLoop (the physics driver) comes from :core (core.session), moved as it was and pinned by`
   `// JVM tests. PAUSED still freezes every member and never resets; one anchor per session; nothing else changed here.`
Nothing else in `androidApp/src/main`. No SceneView or ARCore API is added, removed or called differently.

## Tests required
Written first against the host class (commit A, `GameLoopPinTest`, package `kz.rudy.ar.ar`, so it sees the `internal`
class; it runs under `:androidApp:testDebugUnitTest` because `GameLoop` touches no `android.*`). The same file, with
only its package and imports changed, becomes `GameLoopTest` in `:core` (commit B). `kotlin.test` only, camelCase
names (the file must also compile for iOS: `:core:compileTestKotlinIosSimulatorArm64`).
- `firstFrameStepsWithZeroDt` — the first `onFrame` moves nobody and leaves `simMs` at 0
- `dtIsTheFrameGapClampedToFiftyMs` — a 16 ms gap steps 16, a 1000 ms gap steps 50, a clock that went back steps 0
- `simClockIsTheSumOfSteppedDt`
- `notTrackingPausesEveryMemberAndStepsNothing` — every member PAUSED, positions, `simMs` and walk phases unchanged, `beforeStep` not called
- `resumeGivesBackModeAndTargetAndNeverResets` — 10 s of untracked frames, then tracking: same ids, same positions (dt = 0), same target, the same `level` instance, WALKING again, and it arrives at the original target
- `pauseAndResumeAreReportedOncePerBlip` — PAUSED then RESUMED per member, in cast order, and nothing more
- `walkToBetweenFramesIsReportedByTheNextFrame` — STARTED_WALKING exactly once
- `eventsCarryMemberIdsInCastOrder`
- `addYieldsNoEventAndRemoveForgetsTheMember` — a second `add` of the same id changes nothing; after `remove` its clocks answer 0 / `NEVER_LANDED` / null
- `spawnForTheFirstMemberIsTheOriginThenSpawnNear` — empty cast: `Vec2.ZERO` whatever is asked; otherwise equal to `CastPhysics.spawnNear(level, cast, radiusM, near, preferred, params)`
- `jumpReturnsFalseWhenPhysicsIgnoresIt` — ROLL, airborne, paused, unknown id; true for an idle JUMP character
- `landedStampsTheHostClockOfThatFrame` — and `now - NEVER_LANDED` does not overflow
- `walkPhaseCountsOnlySteppedWalkingTime`
- `emoteRunsOnTheSimulatedClockAndFreezesWithIt` — equals `EmotePlayer.sample(c, simMs - start)`; unchanged across a pause; null once finished; a second emote replaces the first; unknown id is a no-op
- `beforeStepRunsAfterResumeAndBeforeTheStep` — a jump issued from the hook on the first frame back from a pause is accepted, and that frame reports JUMPED
- `restartAtSpawnPutsTheFirstMemberBackWithoutEvents` — spawn, h = 0, no target, IDLE, ability and radius kept, the second member untouched; a paused first member stays PAUSED and resumes to IDLE
- `configureResizesEveryMemberAndKeepsMotion` — radius = `profile.radiusAt(size)` for all, `params == PhysicsParams.forProfile(profile, size)`, a jump in the air carries on, a member added later gets the new radius
- `setAbilityChangesOnlyTheAbility`
- `levelSwapTakesEffectOnTheNextStepWithoutAReset` — assigning `loop.level` between frames (what a scan does) changes the next step and no member's position
- `overlappingMembersAreSeparated`
- `scenarioOnTheSimulatedClockMatchesScenarioRunner` — `ScenarioPlayer` fed from `beforeStep` on `simMs`, 16 ms frames, `Scenarios.day5Level()`, for JUMP and for FLY: the event names equal `ScenarioRunner.run(Scenarios.DAY5, level, ability, loop.params, loop.radiusM, 16).eventNames()`; with a 700 ms untracked stall in the middle the only extra events are PAUSED and RESUMED
- `separationCanBeSwitchedOff` — `:core` only (the new constructor parameter): `GameLoop(separate = false)` leaves two overlapping members where they are

These are characterization tests. If one cannot be made green against the unmodified host class, the test is wrong or
it has found something: write down what the loop really does, assert that, and report it in the Result. Do not change
`GameLoop` to fit a test.

Tests not required: Compose, AR rendering, `RudeusArView`, `MemberClocks` on its own, anything that needs a device.

## Steps
1. Read `ai/tasks/T43-thin-host-map.md` (sections 2 and 3), `.claude/skills/hand-review/SKILL.md`, the host
   `GameLoop.kt`, `core/.../physics/Cast.kt`, `core/.../scenario/Scenario.kt` and `ScenarioRunner.kt`.
2. **Commit A** `androidApp: pin GameLoop behaviour before the move (T47)`: `GameLoopPinTest.kt` only.
   `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk ./gradlew :androidApp:testDebugUnitTest --console=plain -q`
3. **Commit B** `core: GameLoop in core.session with its tests (T47)`: the core file and `GameLoopTest.kt`. Both
   classes exist now, in different packages; the host still uses its own. Check the move is a move:
   `diff <(grep -vE '^\s*(//|/\*|\*)' androidApp/src/main/kotlin/kz/rudy/ar/ar/GameLoop.kt) <(grep -vE '^\s*(//|/\*|\*)' core/src/commonMain/kotlin/kz/rudy/ar/core/session/GameLoop.kt)`
   may show only the differences 1-3 listed under Contract.
   `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk ./gradlew :core:jvmTest :core:compileKotlinIosSimulatorArm64 :core:compileTestKotlinIosSimulatorArm64 :androidApp:testDebugUnitTest --console=plain -q`
   (the same assertions are green against both classes at this commit: that is the proof of equivalence).
4. **Commit C** `androidApp: GameLoop comes from :core (T47; hand-review)`: the host diff above, nothing else.
5. `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk scripts/verify.sh`. Without `/opt/android-sdk`
   only the core half can run: say so and leave the status at `doing`.
6. Fill the Result, set `status: review`. The final message names the three commits and the host files and says
   "needs your Pixel run" with the check below.

## Done when
- `scripts/verify.sh` exit 0; the core test count is up by at least 22 and the android count is back to what it was before commit A
- `git diff <A>^..<C> --stat -- androidApp/src/main` shows `GameLoop.kt` deleted and `RudeusArView.kt` with at most 10 changed lines
- the `diff` of step 3 was clean at commit B (paste its output into the Result)
- `grep -rn "HAND-REVIEW" core/src` prints nothing
- No file outside "Owns" changed
- **The one Pixel check (human), "hand over the lens":** put two characters on the table, tap so one of them walks
  across it, cover the camera lens for about 3 seconds, uncover. Expected: the Paused scrim while covered; both
  characters reappear where they stood about 0.8 s after tracking returns; the walker finishes its walk to the spot
  that was tapped; nobody jumps, slides or returns to the middle. In `scripts/device.sh log` there is no `cast_add`
  after the blip. If any of that fails, revert commit C and report what was seen.

## Result (filled on acceptance)
- measured: ...
- step-3 diff at commit B: ...
- Pixel check: ...
- cut/deferred: ...
