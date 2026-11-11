package kz.rudy.ar.core.duel

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.duel.DuelEvent.Clash
import kz.rudy.ar.core.duel.DuelEvent.DashStarted
import kz.rudy.ar.core.duel.DuelEvent.Hit
import kz.rudy.ar.core.duel.DuelEvent.RingOut
import kz.rudy.ar.core.duel.DuelEvent.WallSlam
import kz.rudy.ar.core.duel.DuelInput.Dash
import kz.rudy.ar.core.duel.DuelInput.Jump
import kz.rudy.ar.core.duel.DuelInput.MoveTo
import kz.rudy.ar.core.duel.DuelInput.None
import kz.rudy.ar.core.duel.DuelInput.Special
import kz.rudy.ar.core.physics.Physics
import kz.rudy.ar.core.physics.PhysicsParams
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val P = DuelParams.DEFAULT

/** Attacker "a" at the origin facing +u, victim "b" [NEAR] ahead of it; "a" dashes on the first step. */
private fun hitSetup(attacker: FighterStats = FighterStats.BALANCED, victim: FighterStats = FighterStats.BALANCED) =
    DuelState(fighter("a", Vec2.ZERO, attacker), fighter("b", Vec2(NEAR, 0f), victim))

/** How far "b" slides from a single "a" dash (start to rest), and the hit. */
private fun knockback(
    attacker: FighterStats,
    victim: FighterStats,
    level: Level = OPEN,
    params: DuelParams = P,
    physics: PhysicsParams = PhysicsParams.DEFAULT,
    radiusM: Float = R,
): Pair<Float, Hit> {
    val start = DuelState(fighter("a", Vec2.ZERO, attacker, radiusM = radiusM), fighter("b", Vec2(2 * radiusM + 0.001f, 0f), victim, radiusM = radiusM))
    val t = runDuel(start, level, 2500L, params = params, physics = physics, script = atStart("a" to Dash()))
    val hits = t.all.filterIsInstance<Hit>()
    assertEquals(1, hits.size, "one hit, got ${t.all}")
    val end = t.last.b
    assertEquals(Vec2.ZERO, end.knock, "at rest after 2.5 s")
    return (end.pos.u - start.b.pos.u) to hits.single()
}

class DuelPhysicsTest {

    @Test
    fun knockbackGrowsWithPowerAndShrinksWithGuard() {
        val byPower = (1..10).map { p -> knockback(stats(power = p), FighterStats.BALANCED) }
        val byGuard = (1..10).map { g -> knockback(FighterStats.BALANCED, stats(guard = g)) }
        for (i in 1 until 10) {
            assertTrue(byPower[i].first > byPower[i - 1].first + 1e-3f, "power ${i + 1} slides ${byPower[i].first} vs ${byPower[i - 1].first}")
            assertTrue(byGuard[i].first < byGuard[i - 1].first - 1e-3f, "guard ${i + 1} slides ${byGuard[i].first} vs ${byGuard[i - 1].first}")
        }
        for ((p, r) in byPower.withIndex()) {
            assertEquals(P.knockSpeedMpsFor(stats(power = p + 1), FighterStats.BALANCED), r.second.speedMps, "power ${p + 1}")
            // 1 mm apart: the first 8 ms sub-step (2.4 mm of dash) already overlaps.
            assertEquals(Hit("a", "b", r.second.speedMps, fromAbove = false, atMs = 8L), r.second)
        }
        // 5 vs 5: 0.25 m/s decaying with tau 180 ms slides about 0.25 * 0.18 = 4.5 cm.
        val balanced = byPower[4].first
        assertTrue(balanced in 0.040f..0.050f, "balanced knockback $balanced")
        assertEquals(0.25f, byPower[4].second.speedMps, 1e-6f)
    }

    @Test
    fun dashHitsOncePerDash() {
        val start = DuelState(fighter("a", Vec2.ZERO), fighter("b", Vec2(0.07f, 0f)))
        // A 16 ms frame boundary at least 100 ms after the cooldown (rounded up since T48 made the cooldown 2500 ms).
        val again = (P.cooldownMsFor(FighterStats.BALANCED) + 100L + 15L) / 16L * 16L
        assertEquals(0L, again % 16L)
        val t = runDuel(start, OPEN, 3000L) { s ->
            if (s.timeMs == 0L || s.timeMs == again) inputs("a" to Dash()) else emptyMap()
        }
        // DashStarted is stamped with the grid time its input was applied at.
        assertEquals(listOf(DashStarted("a", 0L), DashStarted("a", again)), t.all.filterIsInstance<DashStarted>())
        val hits = t.all.filterIsInstance<Hit>()
        assertEquals(2, hits.size, "one hit per dash: ${t.all}")
        assertTrue(hits.all { it.attacker == "a" && it.victim == "b" && !it.fromAbove })
        val first = t.indexOf { it is Hit }
        val hitState = t.states[first]
        assertEquals(0L, hitState.a.dashMs, "the hit ends the dash")
        assertTrue(hitState.b.knock.u > 0f && abs(hitState.b.knock.v) < 1e-6f, "knocked along the dash: ${hitState.b.knock}")
        assertTrue(DuelPhysics.isStunned(hitState.b))
        // The attacker stops where it hit: no more dash motion afterwards.
        val stopped = hitState.a.pos
        for (s in t.states.subList(first, t.states.size).takeWhile { it.timeMs <= again }) {
            assertTrue(s.a.pos.distanceTo(stopped) < 1e-6f, "attacker moved after its hit at ${s.timeMs} ms")
        }

        // A dash that meets nobody runs its full 250 ms: 7.5 cm.
        val alone = DuelState(fighter("a", Vec2.ZERO), fighter("b", Vec2(0f, 0.5f)))
        val miss = runDuel(alone, OPEN, 600L, script = atStart("a" to Dash()))
        assertTrue(miss.all.none { it is Hit })
        assertEquals(P.dashSpeedMps * P.dashMs / 1000f, miss.last.a.pos.u, 1e-4f)
        assertEquals(0f, miss.last.a.pos.v, 1e-6f)
    }

    @Test
    fun clashKnocksBothBack() {
        val strong = FighterStats(power = 10, speed = 4, guard = 5, agility = 1)
        val weak = FighterStats(power = 1, speed = 5, guard = 5, agility = 9)
        val start = DuelState(
            fighter("a", Vec2(-0.04f, 0f), strong, facing = Vec2(1f, 0f)),
            fighter("b", Vec2(0.04f, 0f), weak, facing = Vec2(-1f, 0f)),
        )
        val t = runDuel(start, OPEN, 2500L, script = atStart("a" to Dash(), "b" to Dash()))
        assertEquals(listOf<DuelEvent>(clash("a", "b")), t.all.filter { it is Clash || it is Hit }.untimed(), "a clash, no hits")
        val k = t.indexOf { it is Clash }
        val at = t.states[k]
        assertEquals(0L, at.a.dashMs)
        assertEquals(0L, at.b.dashMs)
        assertTrue(at.a.knock.u < 0f && at.b.knock.u > 0f, "pushed apart: ${at.a.knock} ${at.b.knock}")
        assertTrue(DuelPhysics.isStunned(at.a) && DuelPhysics.isStunned(at.b))
        val expectedRatio = P.knockSpeedMpsFor(strong, weak) / P.knockSpeedMpsFor(weak, strong)
        assertEquals(expectedRatio, at.b.knock.length / at.a.knock.length, 1e-3f * expectedRatio)
        // The weak one flies farther.
        val aSlide = start.a.pos.u - t.last.a.pos.u
        val bSlide = t.last.b.pos.u - start.b.pos.u
        assertTrue(aSlide > 0f && bSlide > 2f * aSlide, "slides a $aSlide b $bSlide")
    }

    @Test
    fun dashOnCooldownIsIgnored() {
        val start = DuelState(fighter("a", Vec2.ZERO), fighter("b", Vec2(0f, 0.5f)))
        val cooldown = P.cooldownMsFor(FighterStats.BALANCED)
        assertEquals(2500L, cooldown) // T48 (1500 before)
        val t = runDuel(start, OPEN, cooldown + 500L) { s -> if (s.timeMs == 0L || s.timeMs >= 400L) inputs("a" to Dash()) else emptyMap() }
        val starts = t.events.withIndex().filter { (_, ev) -> ev.of<DashStarted>("a").isNotEmpty() }.map { t.states[it.index - 1].timeMs }
        assertEquals(2, starts.size, "the first dash and the first one after the cooldown: $starts")
        assertEquals(0L, starts[0])
        assertTrue(starts[1] in cooldown..cooldown + 16L, "second dash at ${starts[1]} ms, cooldown $cooldown")
        // Between them every Dash was ignored: no dash, no motion.
        val idle = t.states.filter { it.timeMs in 400L..starts[1] }
        assertTrue(idle.all { it.a.dashMs == 0L && it.a.pos == idle.first().a.pos })
    }

    @Test
    fun cooldownShortensWithAgility() {
        val waits = (1..10).map { agility ->
            val st = stats(agility = agility)
            val t = runDuel(DuelState(fighter("a", Vec2.ZERO, st), fighter("b", Vec2(0f, 0.5f))), OPEN, P.cooldownMsFor(stats(agility = 1)) + 500L, dtMs = 8L) {
                inputs("a" to Dash())
            }
            val times = t.all.of<DashStarted>("a").map { it.atMs }
            assertTrue(times.size >= 2, "agility $agility re-dashed: $times")
            val wait = times[1] - times[0]
            assertTrue(abs(wait - P.cooldownMsFor(st)) <= P.subStepMs, "agility $agility waited $wait, cooldown ${P.cooldownMsFor(st)}")
            wait
        }
        for (i in 1 until waits.size) assertTrue(waits[i] < waits[i - 1], "agility ${i + 1}: $waits")
        // T48: 2500 * (1.15 - 0.03 AGILITY) (1500 * (1.30 - 0.06 AGILITY) = 1860 .. 1050 before).
        assertEquals(2800L, P.cooldownMsFor(stats(agility = 1)))
        assertEquals(2125L, P.cooldownMsFor(stats(agility = 10)))
    }

    @Test
    fun hitFromAPlatformIsStronger() {
        // Book (3 cm platform) spans u in [-0.11, -0.01]; the attacker stands on it, the victim on the table beside it.
        val ledge = Level(listOf(book("book-1", Vec2(-0.06f, 0f))))
        fun attack(level: Level, attackerH: Float): Pair<Float, Hit> {
            val start = DuelState(fighter("a", Vec2(-0.03f, 0f), h = attackerH), fighter("b", Vec2(-0.03f + NEAR, 0f)))
            val t = runDuel(start, level, 2500L, script = atStart("a" to Dash()))
            val hit = t.all.filterIsInstance<Hit>().single()
            return (t.last.b.pos.u - start.b.pos.u) to hit
        }
        val (high, highHit) = attack(ledge, 0.03f)
        val (flat, flatHit) = attack(OPEN, 0f)
        assertTrue(highHit.fromAbove)
        assertFalse(flatHit.fromAbove)
        assertEquals(flatHit.speedMps * P.fromAboveScale, highHit.speedMps, 1e-6f)
        assertTrue(high > 1.2f * flat, "from above $high vs flat $flat")

        // The other way round (victim on the book) is not from above.
        val raised = Level(listOf(book("book-1", Vec2(0.09f, 0f))))
        val up = DuelState(fighter("a", Vec2.ZERO), fighter("b", Vec2(NEAR, 0f), h = 0.03f))
        val upHit = runDuel(up, raised, 400L, script = atStart("a" to Dash())).all.filterIsInstance<Hit>().single()
        assertFalse(upHit.fromAbove)
        assertEquals(flatHit.speedMps, upHit.speedMps)
    }

    @Test
    fun knockbackStopsAtAnObstacleWithAWallSlam() {
        // The cup's near edge is at u = 0.10: the victim's circle touches it at u = 0.07, 0.9 cm after the hit.
        val cup = cup("cup-1", Vec2(0.14f, 0f))
        val level = Level(listOf(cup))
        val t = runDuel(hitSetup(), level, 2500L, script = atStart("a" to Dash()))
        assertEquals(listOf<DuelEvent>(Hit("a", "b", 0.25f, false, 0L), wallSlam("b", "cup-1")), t.all.filter { it is Hit || it is WallSlam }.untimed())
        val slam = t.indexOf { it is WallSlam }
        val slammed = t.states[slam].b
        assertEquals(Vec2.ZERO, slammed.knock, "the slam zeroes the knockback")
        assertTrue(slammed.stunMs >= P.hitStunMsFor(FighterStats.BALANCED) + P.wallSlamStunMs - 16L, "stun ${slammed.stunMs}")
        for (s in t.states) {
            assertFalse(cup.footprint.contains(s.b.pos), "inside the cup at ${s.timeMs} ms")
            assertTrue(distanceToPolygon(s.b.pos, cup.footprint) >= R - 1e-3f, "overlaps the cup at ${s.timeMs} ms: ${s.b.pos}")
        }
        val rest = t.last.b.pos
        assertEquals(R, distanceToPolygon(rest, cup.footprint), 1e-3f, "rests against the cup")
        assertTrue(rest.u < 0.07f + 1e-3f)
        // Without the cup the same hit slides much farther.
        val free = runDuel(hitSetup(), OPEN, 2500L, script = atStart("a" to Dash())).last.b.pos.u
        assertTrue(free > rest.u + 0.02f, "free slide to $free")

        // A low book does not block: the knockback carries the victim onto it, no slam.
        val onto = runDuel(hitSetup(), Level(listOf(book("book-1", Vec2(0.12f, 0f)))), 2500L, script = atStart("a" to Dash()))
        assertTrue(onto.all.none { it is WallSlam })
        assertEquals(0.03f, onto.last.b.body.state.h)
        assertEquals(Mode.IDLE, onto.last.b.body.state.mode)
    }

    @Test
    fun aGlancingKnockSlidesAlongTheWall() {
        // A long wall 1 mm beyond the victim's circle, the knockback angled [deg] into it.
        fun knockAlongWall(deg: Double): Pair<Trace, LevelObject> {
            val rad = deg * kotlin.math.PI / 180.0
            val dir = Vec2(kotlin.math.cos(rad).toFloat(), kotlin.math.sin(rad).toFloat())
            val victim = dir * NEAR
            val bottom = victim.v + R + 0.001f
            val wall = LevelObject("wall-1", "box", ObjectKind.OBSTACLE, Polygon.rect(Vec2(0.1f, bottom + 0.05f), 0.6f, 0.1f), 0.09f)
            val start = DuelState(fighter("a", Vec2.ZERO), fighter("b", victim))
            return runDuel(start, Level(listOf(wall)), 2500L, script = atStart("a" to Dash(dir))) to wall
        }
        val (glance, wall) = knockAlongWall(3.0)
        assertEquals(1, glance.all.count { it is Hit })
        assertTrue(glance.all.none { it is WallSlam }, "3 degrees slides: ${glance.all}")
        val end = glance.last.b.pos
        assertEquals(R, distanceToPolygon(end, wall.footprint), 1e-3f, "rests against the wall")
        assertTrue(end.u - glance.states[0].b.pos.u > 0.035f, "slid along it to u ${end.u}")
        assertTrue(glance.states.all { !wall.footprint.contains(it.b.pos) && distanceToPolygon(it.b.pos, wall.footprint) >= R - 1e-3f })

        val (steep, _) = knockAlongWall(30.0)
        assertEquals(listOf<DuelEvent>(wallSlam("b", "wall-1")), steep.all.filterIsInstance<WallSlam>().untimed(), "30 degrees slams")
    }

    @Test
    fun knockedPastTheBoundaryIsARingOutOnce() {
        // The table edge is at u = 0.15; a POWER 10 dash slides a GUARD 1 victim about 10 cm.
        val spawn = Vec2(-0.1f, 0f)
        val level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.3f, 0.3f), spawn = spawn)
        val start = DuelState(
            fighter("a", Vec2(0.04f, 0f), FighterStats(10, 4, 5, 1)),
            fighter("b", Vec2(0.04f + NEAR, 0f), FighterStats(5, 5, 1, 9)),
        )
        val t = runDuel(start, level, 4000L, script = atStart("a" to Dash()))
        assertEquals(listOf<DuelEvent>(ringOut("b")), t.all.filterIsInstance<RingOut>().untimed(), "one ring-out, for b only")
        val k = t.indexOf { it is RingOut }
        assertTrue(t.indexOf { it is Hit } in 1 until k)
        val at = t.all.filterIsInstance<RingOut>().single().atMs
        assertTrue(at > t.states[k - 1].timeMs && at <= t.states[k].timeMs && at % P.subStepMs == 0L, "ring-out at $at ms")
        val out = t.states[k].b
        assertEquals(Mode.FALLING, out.body.state.mode, "the ring-out step is the step the fall starts")
        assertTrue(Physics.isOverCliff(level, out.pos))
        assertTrue(t.states[k - 1].b.body.state.mode.let { it == Mode.IDLE || it == Mode.WALKING })
        assertEquals(Vec2.ZERO, out.knock, "no knockback while falling off")
        // Falls straight down as Physics makes anyone fall, then Physics respawns it (what follows is T45's).
        val fall = t.states.drop(k).takeWhile { it.b.body.state.mode == Mode.FALLING }
        assertTrue(fall.size > 3)
        assertTrue(fall.all { it.b.pos == out.pos }, "no horizontal motion while falling off the table")
        assertTrue(fall.zipWithNext().all { (x, y) -> y.b.body.state.h < x.b.body.state.h })
        val end = t.last.b.body.state
        assertEquals(Mode.IDLE, end.mode)
        assertEquals(spawn, end.pos)
        assertTrue(level.boundary!!.contains(t.last.a.pos), "the attacker stays on the table")
    }

    @Test
    fun walkingOffTheEdgeIsAlsoARingOut() {
        val level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.3f, 0.3f), spawn = Vec2(-0.1f, 0f))
        val start = DuelState(fighter("a", Vec2(0.1f, 0f)), fighter("b", Vec2(-0.1f, 0.1f)))
        val t = runDuel(start, level, 4000L, script = atStart("a" to MoveTo(Vec2(0.3f, 0f))))
        assertEquals(listOf<DuelEvent>(ringOut("a")), t.all.filterIsInstance<RingOut>().untimed())
        val k = t.indexOf { it is RingOut }
        assertEquals(Mode.FALLING, t.states[k].a.body.state.mode)
        assertEquals(Mode.WALKING, t.states[k - 1].a.body.state.mode)
        assertEquals(level.spawn, t.last.a.pos, "respawned by Physics")

        // FLY floats over the edge (Physics never sets it FALLING there), but crossing the edge is the ring-out for
        // everyone: once, on the step its centre leaves the table, high in the air, and from then on it cannot steer back.
        val flyer = start.copy(a = fighter("a", Vec2(0.1f, 0f), ability = Ability.FLY))
        val back = MoveTo(Vec2(0.1f, 0f))
        var ringOutSeen = false
        val flight = runDuel(flyer, level, 5000L) { s ->
            when {
                s.timeMs == 0L -> inputs("a" to MoveTo(Vec2(0.3f, 0f)))
                s.timeMs == 16L -> inputs("a" to Jump)
                ringOutSeen || DuelPhysics.isOut(s.a, level) -> { ringOutSeen = true; inputs("a" to back) }
                else -> emptyMap()
            }
        }
        assertEquals(listOf<DuelEvent>(ringOut("a")), flight.all.filterIsInstance<RingOut>().untimed())
        val f = flight.indexOf { it is RingOut }
        assertTrue(flight.states.subList(0, f).all { level.boundary!!.contains(it.a.pos) }, "on the table until the ring-out step")
        assertTrue(flight.states.none { it.a.body.state.mode == Mode.FALLING })
        val leaving = flight.states[f].a
        assertEquals(Mode.JUMPING, leaving.body.state.mode)
        assertTrue(leaving.body.state.h > 0.05f && !level.boundary!!.contains(leaving.pos), "out while high in the air: ${leaving.body.state}")
        assertNull(leaving.body.state.target, "out: the walk target is gone")
        val untilRespawn = flight.states.drop(f).takeWhile { it.a.body.state.mode != Mode.IDLE }
        assertTrue(untilRespawn.size > 10)
        assertTrue(untilRespawn.all { it.a.pos == leaving.pos && it.a.body.state.target == null }, "the MoveTo back was ignored")
        val respawned = flight.states[f + untilRespawn.size].a
        assertEquals(level.spawn, respawned.pos, "respawned by Physics")
        assertTrue(DuelPhysics.acceptsInput(respawned, level), "back in the game after the respawn")

        // A level without a boundary has no edge to walk off.
        val endless = runDuel(start, OPEN, 4000L, script = atStart("a" to MoveTo(Vec2(0.3f, 0f))))
        assertTrue(endless.all.none { it is RingOut })
        assertEquals(Vec2(0.3f, 0f), endless.last.a.pos)
    }

    @Test
    fun flyKnockedPastTheEdgeIsOutEvenWhileAirborne() {
        // The review's case: edge at u = 0.15; FLY b jumps at t = 0, a's POWER 10 dash (t = 96) hits it in the air at
        // 0.51 m/s (0.566 before T48's stat factors), b floats past the edge and later tries to MoveTo back onto the table.
        val spawn = Vec2(-0.1f, 0f)
        val level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.3f, 0.3f), spawn = spawn)
        fun play(ability: Ability): Trace {
            val start = DuelState(
                fighter("a", Vec2(0.1f - NEAR - 0.01f, 0f), FighterStats(10, 1, 1, 8)),
                fighter("b", Vec2(0.1f, 0f), FighterStats(8, 1, 1, 10), ability = ability),
            )
            return runDuel(start, level, 4000L) { s ->
                when {
                    s.timeMs == 0L -> inputs("b" to Jump)
                    s.timeMs == 96L -> inputs("a" to Dash())
                    s.timeMs >= 600L -> inputs("b" to MoveTo(Vec2(0.1f, 0f)))
                    else -> emptyMap()
                }
            }
        }
        val fly = play(Ability.FLY)
        val hit = fly.all.filterIsInstance<Hit>().single()
        assertEquals(P.knockSpeedMpsFor(FighterStats(10, 1, 1, 8), FighterStats(8, 1, 1, 10)), hit.speedMps, 1e-6f)
        val rings = fly.all.filterIsInstance<RingOut>()
        assertEquals(listOf("b"), rings.map { it.id }, "${fly.all}")
        val k = fly.indexOf { it is RingOut }
        val boundary = level.boundary!!
        assertTrue(boundary.contains(fly.states[k - 1].b.pos) && !boundary.contains(fly.states[k].b.pos), "rung out on the step it crossed")
        assertEquals(Mode.JUMPING, fly.states[k].b.body.state.mode, "still in the air")
        assertTrue(fly.states[k].b.body.state.h > 0f)
        val away = fly.states.drop(k).takeWhile { it.b.body.state.mode != Mode.IDLE }
        assertTrue(away.all { !boundary.contains(it.b.pos) }, "never back over the table before the respawn")
        assertEquals(spawn, fly.states[k + away.size].b.pos, "respawned by Physics, not walked back")
        // A plain jumper in the same script rings out on the very same sub-step: the ability does not matter.
        val jump = play(Ability.JUMP)
        assertEquals(rings, jump.all.filterIsInstance<RingOut>())
    }

    @Test
    fun aFlyGlidingOverAHoleIsNotOut() {
        // A CLIFF hole inside the table, u in [-0.04, 0.04]: a FLY jump glides across it above the table plane.
        val hole = LevelObject("hole-1", "hole", ObjectKind.CLIFF, Polygon.rect(Vec2.ZERO, 0.08f, 0.1f), 0f)
        val level = Level(listOf(hole), boundary = Polygon.rect(Vec2.ZERO, 0.6f, 0.4f), spawn = Vec2(-0.2f, 0.1f))
        fun cross(ability: Ability) = runDuel(
            DuelState(fighter("a", Vec2(-0.06f, 0f), ability = ability), fighter("b", Vec2(0.2f, 0.15f))), level, 4000L,
        ) { s ->
            when (s.timeMs) {
                0L -> inputs("a" to MoveTo(Vec2(0.17f, 0f)))
                16L -> inputs("a" to Jump)
                else -> emptyMap()
            }
        }
        val glide = cross(Ability.FLY)
        assertTrue(glide.states.any { Physics.isOverCliff(level, it.a.pos) && it.a.body.state.mode == Mode.JUMPING }, "over the hole")
        assertTrue(glide.all.none { it is RingOut }, "${glide.all}")
        assertEquals(Vec2(0.17f, 0f), glide.last.a.pos)
        assertEquals(Mode.IDLE, glide.last.a.body.state.mode)
        // A plain jump does fall into it: one ring-out, then Physics respawns it.
        val drop = cross(Ability.JUMP)
        assertEquals(listOf<DuelEvent>(ringOut("a")), drop.all.filterIsInstance<RingOut>().untimed())
        assertEquals(level.spawn, drop.last.a.pos)
    }

    @Test
    fun nonFiniteInputIsIgnored() {
        val start = hitSetup()
        val none = DuelPhysics.step(start, OPEN, 16L)
        val bad = listOf(
            Dash(Vec2(Float.NaN, 0f)), Dash(Vec2(0f, Float.POSITIVE_INFINITY)),
            MoveTo(Vec2(Float.NaN, 0f)), MoveTo(Vec2(Float.NEGATIVE_INFINITY, 0.1f)),
        )
        for (input in bad) {
            assertEquals(none, DuelPhysics.step(start, OPEN, 16L, inputs("a" to input, "b" to input)), "$input")
        }
        val t = runDuel(start, OPEN, 500L) { inputs("a" to Dash(Vec2(Float.NaN, 0f)), "b" to MoveTo(Vec2(0f, Float.NaN))) }
        assertTrue(t.all.isEmpty(), "${t.all}")
        assertTrue(t.states.all { it.a.pos == start.a.pos && it.b.pos == start.b.pos })
        // A zero direction is still "along facing", not an error.
        assertEquals(listOf<DuelEvent>(dashStarted("a")), DuelPhysics.step(start, OPEN, 16L, inputs("a" to Dash(Vec2.ZERO))).events.filterIsInstance<DashStarted>().untimed())
    }

    @Test
    fun stunnedFighterIgnoresInput() {
        val noisy = listOf(MoveTo(Vec2(-0.2f, 0f)), Jump, Dash(Vec2(-1f, 0f)))
        var i = 0
        val t = runDuel(hitSetup(), OPEN, 2500L) { s ->
            when {
                s.timeMs == 0L -> inputs("a" to Dash())
                s.a.dashMs == 0L && DuelPhysics.isStunned(s.b) -> inputs("b" to noisy[i++ % noisy.size])
                else -> emptyMap()
            }
        }
        val hit = t.indexOf { it is Hit }
        assertTrue(hit > 0, "no hit: ${t.all}")
        val free = (hit until t.states.size).first { !DuelPhysics.isStunned(t.states[it].b) }
        assertTrue(free > hit + 20, "stunned from step $hit to $free")
        assertTrue(i > 20, "inputs were sent while stunned ($i)")
        for (s in t.states.subList(hit, free)) {
            assertNull(s.b.body.state.target, "took a MoveTo while stunned at ${s.timeMs} ms")
            assertTrue(s.b.body.state.mode != Mode.JUMPING, "jumped while stunned")
            assertEquals(0L, s.b.dashMs)
        }
        assertTrue(t.all.of<DashStarted>("b").isEmpty())
        // Once the stun is over the same inputs work.
        val after = t.states[free]
        assertFalse(DuelPhysics.isStunned(after.b))
        val moved = DuelPhysics.step(after, OPEN, 16L, inputs("b" to MoveTo(Vec2(0.3f, 0f))))
        assertEquals(Vec2(0.3f, 0f), moved.state.b.body.state.target)
        assertEquals(listOf<DuelEvent>(dashStarted("b")), DuelPhysics.step(after, OPEN, 16L, inputs("b" to Dash())).events.untimed())

        // A dashing fighter ignores input too.
        val dashing = DuelPhysics.step(DuelState(fighter("a", Vec2.ZERO), fighter("b", Vec2(0f, 0.5f))), OPEN, 16L, inputs("a" to Dash())).state
        assertTrue(dashing.a.dashMs > 0L)
        val tried = DuelPhysics.step(dashing, OPEN, 16L, inputs("a" to MoveTo(Vec2(-0.3f, 0f)))).state
        assertNull(tried.a.body.state.target)
        assertTrue(tried.a.pos.u > dashing.a.pos.u, "still dashing forward")
        assertEquals(Mode.IDLE, DuelPhysics.step(dashing, OPEN, 16L, inputs("a" to Jump)).state.a.body.state.mode)

        // PAUSED fighters ignore input and do not move, whatever their knockback or dash.
        val mid = t.states[hit + 1]
        val paused = mid.copy(a = mid.a.copy(body = Physics.pause(mid.a.body)), b = mid.b.copy(body = Physics.pause(mid.b.body)))
        val frozen = runDuel(paused, OPEN, 1000L) { inputs("a" to MoveTo(Vec2(0.3f, 0.3f)), "b" to Jump) }
        assertTrue(frozen.all.isEmpty())
        assertTrue(frozen.states.all { it.a == paused.a && it.b == paused.b }, "a paused duel is frozen")
        assertEquals(paused.timeMs + 1000L, frozen.last.timeMs)
    }

    @Test
    fun stunShortensWithAgility() {
        val stuns = (1..10).map { agility ->
            val t = runDuel(hitSetup(victim = stats(agility = agility)), OPEN, 2500L, dtMs = 8L, script = atStart("a" to Dash()))
            val hit = t.indexOf { it is Hit }
            val free = (hit until t.states.size).first { !DuelPhysics.isStunned(t.states[it].b) }
            t.states[free].timeMs - t.states[hit].timeMs
        }
        for (i in 1 until stuns.size) {
            val step = stuns[i - 1] - stuns[i]
            assertTrue(step in 16L..24L, "agility ${i + 1}: one point of agility is 18 ms of stun: $stuns")
        }
        // The timer only starts once the knockback has slowed down, so the whole stun is longer than the timer.
        assertTrue(stuns.last() > P.hitStunMsFor(stats(agility = 10)) + 200L, "$stuns")
        assertEquals(372L, P.hitStunMsFor(stats(agility = 1)))
        assertEquals(210L, P.hitStunMsFor(stats(agility = 10)))
    }

    @Test
    fun zeroDtIsIdentity() {
        val mid = runDuel(hitSetup(), OPEN, 64L, script = atStart("a" to Dash())).last
        assertTrue(mid.b.knock != Vec2.ZERO || mid.a.dashMs > 0L, "a state with something going on")
        val noisy = inputs("a" to MoveTo(Vec2(0.2f, 0.2f)), "b" to Dash())
        for (dt in listOf(0L, -16L)) {
            val out = DuelPhysics.step(mid, OPEN, dt, noisy)
            assertSame(mid, out.state, "dt $dt")
            assertTrue(out.events.isEmpty())
        }
    }

    @Test
    fun specialIsAcceptedAndDoesNothing() {
        val level = Level(listOf(cup("cup-1", Vec2(0.14f, 0f))), boundary = Polygon.rect(Vec2.ZERO, 0.4f, 0.4f))
        val trace = runDuel(hitSetup(), level, 1500L) { s -> if (s.timeMs == 0L) inputs("a" to Dash(), "b" to Jump) else emptyMap() }
        val samples = listOf(0, 1, 5, 20, 60, trace.states.size - 1).map { trace.states[it] }
        for (s in samples) {
            val none = DuelPhysics.step(s, level, 16L, inputs("a" to None, "b" to None))
            assertEquals(none, DuelPhysics.step(s, level, 16L, inputs("a" to Special, "b" to Special)), "at ${s.timeMs} ms")
            assertEquals(none, DuelPhysics.step(s, level, 16L))
            assertEquals(none, DuelPhysics.step(s, level, 16L, inputs("zz" to Dash())), "unknown ids are ignored")
        }
    }

    @Test
    fun walkSpeedFollowsTheSpeedStat() {
        val speeds = (1..10).map { sp ->
            val t = runDuel(DuelState(fighter("a", Vec2.ZERO, stats(speed = sp)), fighter("b", Vec2(0f, 0.5f))), OPEN, 1000L,
                script = atStart("a" to MoveTo(Vec2(5f, 0f))))
            val expected = P.walkSpeedMpsFor(stats(speed = sp))
            assertEquals(expected, t.last.a.pos.u, 1e-4f, "speed $sp")
            expected
        }
        assertEquals(PhysicsParams.DEFAULT.walkSpeedMps * 2f, speeds[4], 1e-6f, "SPEED 5 walks twice the walking game")
        for (i in 1 until speeds.size) assertTrue(speeds[i] > speeds[i - 1])
        // Every ability walks at its stats' speed (the walking game's ROLL x 2 does not stack on top).
        val roller = DuelState(fighter("a", Vec2.ZERO, ability = Ability.ROLL), fighter("b", Vec2(0f, 0.5f)))
        val rolled = runDuel(roller, OPEN, 1000L, script = atStart("a" to MoveTo(Vec2(5f, 0f))))
        assertEquals(speeds[4], rolled.last.a.pos.u, 1e-4f)
        assertNotNull(rolled.last.a.body.state.target)
    }
}
