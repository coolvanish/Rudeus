# T28 — cast-physics

status: done
created: 2026-11-04
day: Day 9 (Nov 4, v1.0 minimal cut; see ai/plan-cast-emotes.md)
gate: v1.0 gate "two characters walk to two fingers at once, never through each other, never off the edge"; the Day 7 single-character physics stays byte-identical
cut-if-slips: cut line 4 (separation off: `CastPhysics.step(separate = false)`), then the cast itself (the host keeps one member)

## Goal
Several characters live on one table. `:core` steps all of them in one call, keeps them from walking through each
other, never pushes one over a cliff or into a cup, reports physics events per character, and finds a free spot for
a newcomer. A cast of one behaves exactly like today's single `PhysicsBody`.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- core/src/commonMain/kotlin/kz/rudy/ar/core/physics/Cast.kt (new)
- core/src/commonMain/kotlin/kz/rudy/ar/core/domain/Model.kt — ONLY to add `Level.nearestInside`
- core/src/commonTest/kotlin/kz/rudy/ar/core/physics/CastTest.kt (new)
Must NOT change: `Physics.kt`, `PhysicsParams.kt`, `CharacterEvent.kt`, any existing test. Single-body behaviour is
a regression contract (`castOfOneStepsExactlyLikeASingleBody`).

## Contract
```kotlin
package kz.rudy.ar.core.physics

/** One character on the table as physics sees it. */
@Serializable data class CastMember(val id: String, val body: PhysicsBody)

/**
 * Every character on the table, in the order they were added (the first is the one Play started with). Immutable:
 * every operation returns a new Cast. Ids are unique; at most [MAX] members.
 */
@Serializable data class Cast(val members: List<CastMember> = emptyList()) {
    init { require(members.size <= MAX); require(members.map { it.id }.distinct().size == members.size) }
    val ids: List<String>
    operator fun get(id: String): PhysicsBody?            // null for an unknown id
    fun with(id: String, body: PhysicsBody): Cast          // replaces the member in place, or appends (IllegalArgumentException past MAX)
    fun without(id: String): Cast                           // unknown id: this
    companion object { const val MAX = 4 }
}

object CastPhysics {
    /** Physics.step on every member (same level, dt, params), then [separate] when [separate] is true. */
    fun step(cast: Cast, level: Level, dtMs: Long, params: PhysicsParams = PhysicsParams.DEFAULT, separate: Boolean = true): Cast
    fun walkTo(cast: Cast, id: String, target: Vec2): Cast        // Physics.walkTo(body, target); unknown id -> cast
    fun jump(cast: Cast, id: String, params: PhysicsParams = PhysicsParams.DEFAULT): Cast
    fun pause(cast: Cast): Cast                                     // Physics.pause on every member (idempotent)
    fun resume(cast: Cast): Cast
    /**
     * Keeps collision circles apart. Only members on the ground (IDLE or WALKING) take part; JUMPING, FALLING,
     * RESPAWNING and PAUSED members neither push nor move (a jump passes over a friend). Pairs in id order, one pass.
     * For an overlapping pair (dist < r1 + r2) each moves half the overlap along the centre line (dist == 0: along +u).
     * A member whose half-push would land over a cliff (Physics.isOverCliff) or inside a blocking object
     * (Physics.blocks(o, feetH) && o.footprint.contains(p)) stays put and the other takes the whole overlap if it can,
     * else both stay. Only `pos` changes: `h`, mode, target, facing, `blocked` are untouched (a friend is not a cup).
     */
    fun separate(cast: Cast, level: Level, params: PhysicsParams = PhysicsParams.DEFAULT): Cast
    /**
     * A free spot for a new member of radius [radiusM]: candidates at `near + dir * d` for the 8 directions starting at
     * [preferred] (unit vector in plane coords, rotated by 45° steps), first with d = 2.5 * radiusM, then d = 5 * radiusM.
     * A candidate is free when Physics.groundHeight(level, p, 0f, params) != null (inside the boundary, not a cliff), no
     * blocking object (Physics.blocks(o, 0f)) contains it, and it is at least radiusM + member.radiusM from every member.
     * None free: level.spawn. Pure.
     */
    fun spawnNear(level: Level, cast: Cast, radiusM: Float, near: Vec2, preferred: Vec2 = Vec2(1f, 0f), params: PhysicsParams = PhysicsParams.DEFAULT): Vec2
}

object CastEvents {
    /**
     * Per member, in [next]'s order: CharacterEvents.between(prevBody, nextBody) (the PhysicsBody overload, so BLOCKED
     * is included) for every id present in both casts. A member only in [next] yields nothing (the host announces
     * PLACED itself); one only in [prev] yields nothing.
     */
    fun between(prev: Cast, next: Cast): List<Pair<String, CharacterEvent>>
}
```
In `domain/Model.kt`, on `Level`:
```kotlin
/** [p] when there is no boundary or it contains [p]; else the closest point on the boundary's edges, nudged 1 mm toward the boundary centroid so that `boundary.contains(result)` holds. */
fun nearestInside(p: Vec2): Vec2
```
Units: metres, ms. Everything pure and deterministic. `@Serializable` on both data classes (a cast lands in logs).

## Tests required (CastTest.kt)
- `castOfOneStepsExactlyLikeASingleBody` — drive `Scenarios.DAY5` on `Scenarios.day5Level()` for 15 s at 16 ms twice: once with `Physics.step` on a `PhysicsBody`, once with `CastPhysics.step` on a one-member cast (walkTo / jump through CastPhysics at the same beats); every frame's body is equal.
- `twoWalkersMeetingHeadOnStopTouching` — after they meet, dist >= r1 + r2 - 1e-4 on every later frame, both still inside the boundary, both still WALKING or IDLE (never FALLING).
- `separationNeverPushesAMemberOverTheCliff` — a member standing at the boundary edge is not moved; the other takes the whole overlap.
- `separationNeverPushesAMemberIntoACup`
- `aJumpingMemberPassesOverAFriend` — the airborne member's pos follows its own path exactly as alone; the friend does not move.
- `pausedCastIsUnchangedByStep`
- `eventsAreKeyedByMemberId` — two members, one jumps: JUMPED under its id only; BLOCKED under the one that walks into the cup.
- `aNewMemberEmitsNoEventOnItsFirstFrame`
- `spawnNearPicksAFreeSpotInsideTheBoundary` — clear of the cup, of the existing member, inside the boundary; deterministic (same input, same spot).
- `spawnNearFallsBackToTheLevelSpawn` — every candidate blocked.
- `castRefusesAFifthMember`
- `nearestInsideReturnsAPointTheBoundaryContains` — for a point outside each edge of the ±0.35 square and far outside a corner.
Tests not required: rendering, Compose, the host.

## Steps
1. Read `Physics.kt`, `CharacterEvent.kt`, `Scenario.kt` and `PhysicsTest.kt` (how the existing tests build levels).
2. Write `CastTest.kt` first, then `Cast.kt` and `Level.nearestInside` until green: `./gradlew :core:jvmTest --console=plain -q`.
3. `scripts/verify.sh` once at the end (full; the Android build must stay green).

## Done when
- `scripts/verify.sh` exit 0; every test above green; `git diff --stat` touches only the owned paths.
- Commit on the task branch: `core: cast physics — Cast, CastPhysics.step/separate/spawnNear, CastEvents (T28)`.

## Result (filled on acceptance)
- measured: `CastTest` 13 tests (12 required plus `castRoundTripsThroughJson`), all green. Regression: a one-member cast
  matches `Physics.step` exactly (`==`) on every one of the 938 frames of `DAY5` (15 s at 16 ms) for each of the 4
  abilities, and that single-body trace equals `ScenarioRunner.run(...).bodies`. Head-on walkers (r 3 cm each, ±0.10 m
  apart): first contact at frame 87, then centre distance exactly 0.0600 m on every later frame (error 0 within float),
  both still WALKING at u = ±0.03. Walkers 1 cm off-centre slide past each other (minimum distance 0.059999995 m) and
  both arrive. Four walkers converging on one point: worst residual overlap after the single pass is 2.8 µm. Mutation
  check: letting airborne members take part in separation breaks `aJumpingMemberPassesOverAFriend` at frame 1.
- decisions: "pairs in id order" means ids sorted lexicographically (so the result does not depend on who joined
  first); pushes within the pass are sequential (each pair sees the ones before it). At dist == 0 the later id goes +u.
  `spawnNear` rotates counter-clockwise by 45° steps and normalizes [preferred] (zero length: +u).
  `Level.nearestInside` falls back to the centroid if the 1 mm nudge does not land inside (only a boundary under ~1 mm
  across could do that, since the boundary is a convex hull).
- cut/deferred: none. Separation is one pass, so three or more members jammed together can keep a few-µm overlap for a
  frame (measured above); acceptable for the gate.
- review (rudeus-reviewer, 2026-11-04): no blockers, MERGE. Minor, left as is for Day 9: (1) the cup guard in `isFree` checks the
  centre only, so a member pressed against a cup by a friend dips ~0.4 mm per frame and Physics pushes it back (a visible
  jitter at most); (2) a friend can nudge a member off a book: it goes FALLING and the HUD says FELL / LANDED; (3) two
  walkers exactly head-on with swapped targets press forever as WALKING (1 cm off the line they slide past; real fingers
  are never collinear); (4) `nearestInside` promises containment only for a convex boundary, which every current source
  produces. Watch (2) and (3) on the Pixel; fixes are one line each if they show.
