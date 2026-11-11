# T42 — duel-physics

status: done
created: 2026-11-11
phase: v2 wave 1 (ai/build-plan-v2.md)
gate: G4 — matches end, 30 Hz and 60 Hz agree, no archetype dominates (the last one is measured in T48)
cut-if-slips: never cut (it is the game)

## Goal
Two fighters on a scanned level can walk, dash into each other, get knocked back according to their stats, slam into
a cup, fall off the table. Pure `:core`, deterministic, frame-rate independent, with every number in one tunable
params class. No match rules, no bot, no specials: T45 builds those on this.

## Owns (exact paths)
- core/src/commonMain/kotlin/kz/rudy/ar/core/duel/DuelParams.kt, DuelState.kt, DuelPhysics.kt (new package)
- core/src/commonTest/kotlin/kz/rudy/ar/core/duel/*.kt (new)

Do not touch `physics/*` (the walking game and its 60+ tests must behave exactly as today). Build on its public API:
`Physics.step / walkTo / jump / groundHeight / isOverCliff / blocks`, `circlePolygonPushOut`, `PhysicsParams`. If a
private piece of `Physics` is truly needed, stop and say which one in the final message instead of editing it.
Do not touch `domain/*`, `meta/*`, `pipeline/*`, Gradle.

## Contract
```kotlin
@Serializable data class DuelParams(...)   // every tunable, defaults for the table; forProfile(profile, size) scales like PhysicsParams.forProfile
@Serializable data class DuelFighter(
    val id: String,
    val body: PhysicsBody,
    val stats: FighterStats,
    val knock: Vec2 = Vec2.ZERO,          // knockback velocity, m/s, plane coords
    val stunMs: Long = 0,                 // > 0: ignores input
    val dashMs: Long = 0,                 // > 0: dashing along dashDir
    val dashDir: Vec2 = Vec2.ZERO,
    val dashCooldownMs: Long = 0,
)
@Serializable data class DuelState(val a: DuelFighter, val b: DuelFighter, val timeMs: Long = 0)

sealed interface DuelInput {              // one per fighter per step; absent = None
    data object None : DuelInput
    data class MoveTo(val target: Vec2) : DuelInput
    data class Dash(val dir: Vec2? = null) : DuelInput   // null = along facing
    data object Jump : DuelInput
    data object Special : DuelInput       // reserved for T45: accepted, does nothing here
}
sealed interface DuelEvent {
    data class DashStarted(val id: String) : DuelEvent
    data class Hit(val attacker: String, val victim: String, val speedMps: Float, val fromAbove: Boolean) : DuelEvent
    data class Clash(val a: String, val b: String) : DuelEvent              // both dashing
    data class WallSlam(val id: String, val objectId: String) : DuelEvent
    data class RingOut(val id: String) : DuelEvent                          // once per fall
}
data class DuelStep(val state: DuelState, val events: List<DuelEvent>)

object DuelPhysics {
    fun step(state: DuelState, level: Level, dtMs: Long, inputs: Map<String, DuelInput> = emptyMap(),
             params: DuelParams = DuelParams.DEFAULT, physics: PhysicsParams = PhysicsParams.DEFAULT): DuelStep
}
```
Behaviour (numbers are starting points, all in `DuelParams`):
- **Stats -> motion.** Walk speed = `physics.walkSpeedMps * duelSpeedScale(2.0) * (0.70 + 0.06 * SPEED)`. Dash: 250 ms
  at 0.30 m/s, cooldown `1500 ms * (1.30 - 0.06 * AGILITY)`. Knockback speed given to the victim =
  `0.25 m/s * (0.55 + 0.09 * attacker.POWER) / (0.55 + 0.09 * victim.GUARD)`, `* 1.25` when the attacker's feet are
  more than 1 cm above the victim's (`fromAbove`). Stun after a hit = `300 ms * (1.30 - 0.06 * victim.AGILITY)`, plus
  for as long as `|knock|` is above a small threshold.
- **Hit.** A dashing fighter whose circle overlaps the other's hits once per dash: the victim gets the knockback along
  the attacker's `dashDir`, the attacker's dash ends. Both dashing: `Clash`, each takes the other's knockback.
  A stunned or airborne victim can still be hit; an airborne attacker cannot start a dash.
- **Knockback** decays exponentially (time constant 180 ms) and moves the body through the same obstacle rules walking
  uses: an object that `Physics.blocks` stops it, zeroes `knock`, adds a 400 ms stun and emits `WallSlam`; the fighter
  never ends a step inside a blocking footprint.
- **Ring-out.** A fighter knocked (or walked) past `level.boundary` falls exactly as `Physics` makes anyone fall;
  `RingOut(id)` is emitted once, on the step the fall over the cliff starts. What happens next (respawn, score) is
  T45's; this step function just keeps stepping the body. A level with `boundary == null` has no ring-outs.
- **Fighters separate** like `CastPhysics.separate` (no overlap after a step, nobody pushed into an obstacle or over
  the edge by separation alone).
- **Frame-rate independence.** `step` integrates in fixed internal sub-steps (<= 8 ms) so one 33 ms call and two 16/17 ms
  calls land within 1 mm and produce the same events.
- **Input rules.** Stunned, dashing or PAUSED fighters ignore input. `MoveTo` is `Physics.walkTo`. `Jump` is
  `Physics.jump`. `Dash` on cooldown is ignored (no event).
- **Purity.** No clocks, no randomness, no mutation; `timeMs` advances by `dtMs`; `dtMs <= 0` returns the state unchanged with no events.

## Tests required
- `knockbackGrowsWithPowerAndShrinksWithGuard` — monotonic over 1..10 for both
- `dashHitsOncePerDash`, `clashKnocksBothBack`, `dashOnCooldownIsIgnored`, `cooldownShortensWithAgility`
- `hitFromAPlatformIsStronger` — attacker on a 3 cm PLATFORM
- `knockbackStopsAtAnObstacleWithAWallSlam` — and the fighter is outside the footprint afterwards
- `knockedPastTheBoundaryIsARingOutOnce`
- `walkingOffTheEdgeIsAlsoARingOut`
- `stunnedFighterIgnoresInput`, `stunShortensWithAgility`
- `mirroredFightersGetMirroredOutcomes` — equal stats, symmetric positions, symmetric inputs
- `thirtyAndSixtyHertzAgree` — a scripted exchange stepped at 33 ms and at 16/17 ms: positions within 1 mm, same event sequence
- `fuzzNeverProducesNaNOrOverlapOrEscapes` — seeded random inputs for ~10 000 steps on a level with objects: positions finite, no fighter inside a blocking footprint, every boundary exit has its `RingOut`
- `zeroDtIsIdentity`, `specialIsAcceptedAndDoesNothing`
- `duelParamsScaleWithTheSurface` — floor profile scales speeds and distances, not the dimensionless factors
Tests not required: balance (T48), rendering, anything Android.

## Done when
- `./gradlew :core:jvmTest` green, the existing `physics` tests untouched and passing
- No file outside "Owns" changed

## Result (filled on acceptance)
- measured (2026-11-11, JVM, cloud container, after the review fixes): `:core:jvmTest` 417 tests, 0 failures
  (388 before + 29 in `duel`); `:core:compileKotlinIosSimulatorArm64` green, the klib carries `kz.rudy.ar.core.duel`.
  - Defaults (`DuelParams`, all as the brief): walk = `walkSpeedMps * 2.0 * (0.70 + 0.06 SPEED)` (7.6 .. 13 cm/s);
    dash 250 ms at 0.30 m/s (7.5 cm), cooldown `1500 * (1.30 - 0.06 AGILITY)` (1860 .. 1050 ms, from the dash start);
    knockback `0.25 * (0.55 + 0.09 POWER) / (0.55 + 0.09 GUARD)` m/s, x1.25 from > 1 cm above, tau 180 ms;
    stun `300 * (1.30 - 0.06 AGILITY)` (372 .. 210 ms); wall slam +400 ms; sub-step 8 ms. Added tunables (no number
    in the brief): `knockStunMps` 0.02, `knockStopMps` 0.003, `wallSlamMinMps` 0.02, `rollSpeedScale` 1.
  - Knockback slide (victim from the dash to rest, open table, includes ~1 mm of separation at the hit), cm:

    | POWER \ GUARD | 1 | 5 | 10 |
    |---|---|---|---|
    | 1 | 4.5 (v0 0.25 m/s) | 2.8 (0.16) | 2.0 (0.11) |
    | 5 | 7.0 (0.39) | 4.5 (0.25) | 3.1 (0.17) |
    | 10 | 10.2 (0.57) | 6.5 (0.36) | 4.5 (0.25) |

    Stun for those hits (victim AGILITY 5): 616 .. 912 ms (a harder hit stuns longer: the timer starts once the
    knockback is below 2 cm/s). Measured before the review fixes; the path a hit takes (dash, knockback, separation
    along the centre line) did not change.
  - 30 vs 16/17 Hz: worst difference 0.0 m, identical events with identical `atMs` (sub-steps sit on a grid of
    `timeMs`, so any split of the same time runs the same sub-steps; random 1..17 ms splits are checked equal too).
  - Speed: fuzz test 90 000 steps/s after the review fixes (101 000 before; 10 000 random 16..50 ms frames, mean 33 ms;
    best of two replays, test JVM). Warm steady state in a scratch benchmark before the fixes: 270 000 steps/s at 16 ms
    frames, 120 000 at 33 ms, 75 .. 88 000 at 50 ms; `Physics.step` is about 70 % of it.
  - Review fixes (T42 review: MERGE AFTER FIXES):
    1. Ring-out: a centre past `level.boundary` is out at once, grounded or airborne, any ability or height; the
       feet-below-the-plane rule is kept only for CLIFF holes inside the boundary (a FLY glides across a hole).
    2. Separation is the duel's own (`DuelPhysics.separate`, no longer `CastPhysics.separate`): a candidate counts only
       if the whole circle is clear of what blocks at its feet height, its centre is on the table and off any hole, and
       the straight path to it crosses no blocking or CLIFF footprint; 16 directions x (both move / one goes round the
       other) are tried. Review scenarios now: (a) spawn -0.0425: b stays at (-0.0425, 0), a goes to (-0.0655, -0.0554);
       (b) spawn -0.042: b stays at (-0.042, 0), a goes to (-0.0650, -0.0554); (c) b stays against the cup at (0.03, 0),
       a takes the whole overlap to (-0.03, 0). A body that came down in a sub-step is pushed out at its new feet height
       at once (Physics would do it a sub-step later), so no circle ever ends a step inside a blocking footprint.
    3. Every event has `atMs` (end of its sub-step on the grid; DashStarted: the grid time of its input); a step's list
       is in canonical order (time, kind, ids), Clash ids sorted: slot-independent.
    4. `DuelParams.init` rejects non-positive stat factors at stat 1 and 10, negative speeds, `knockStunMps < 0`,
       `knockStopMps <= 0`, non-positive scales and NaN, naming the parameter.
    5. A non-finite MoveTo target or Dash direction is ignored like no input.
    6. The collision copy (`moveThrough` / `pushOut`, a rebuild of the private `Physics.resolveCollisions`) is pinned
       against `Physics.step` on 800 seeded cases (522 free, 267 pushed out, 11 stopped by the cliff guard): positions
       equal bit for bit. A later physics brief should expose one resolve function in `Physics` and delete the copy.
- deviations: the stun timer runs only once `|knock| <= knockStunMps` (otherwise the knockback phase outlasts the
  timer and agility would not shorten a stun); a knockback hitting a blocking object slower than `wallSlamMinMps` into
  its surface slides along it instead of slamming; crossing the boundary is the ring-out for everyone (a FLY body
  floating past the edge too), a fall into a CLIFF hole is one when the body is FALLING over it or JUMPING with the feet
  below the plane; an out fighter loses its walk target, ignores input and stays where it left the level until Physics
  respawns it; ROLL's walking-game x2 does not stack on the SPEED formula; the bodies trail `timeMs` by < 8 ms;
  `DuelInput` / `DuelEvent` / `DuelStep` are `@Serializable`; `acceptsInput` / `canDash` take the level.
- cut/deferred: nothing. Balance (T48) and what follows a ring-out (respawn, score: T45) are not here by design.

### What T45 must know
- Hits ignore height: a jump never dodges a dash, a fighter on a 4 cm platform within 3 cm of its edge is hit from the
  table, and `fromAbove` only happens when the attacker's feet are > 1 cm higher (on a platform, or dashing off one).
- A dash is the only hit source, and there is no immunity hook yet: the JUMP stomp and the HIDE dodge need both.
- Pausing only one fighter lets the other dash and walk through it (PAUSED fighters neither hit, are hit nor separate):
  a match pauses both. `isOut` reads a paused body by its mode before the pause, so it stays true for a fighter paused
  mid-fall.
- Stun and dash cooldown carry over a respawn (Physics respawns the body; the duel timers keep counting).
- `dtMs` is unbounded: a 5 s hitch is 625 sub-steps. The caller clamps (the host's `GameLoop` does at 50 ms).
- `DuelParams.forProfile` always scales `DEFAULT`; tuned params need `tuned.scaled(profile, size)`.
- `isStunned` / `acceptsInput` / `canDash` default to `DuelParams.DEFAULT`: pass the params in use.
- Every RingOut has a simulated time (`atMs`): two ring-outs with equal `atMs` happened in the same 8 ms sub-step, at
  any frame rate.
