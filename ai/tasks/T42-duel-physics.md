# T42 — duel-physics

status: todo
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
- measured: ...
- cut/deferred: ...
