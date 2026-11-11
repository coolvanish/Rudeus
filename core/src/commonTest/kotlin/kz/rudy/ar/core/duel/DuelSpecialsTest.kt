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
import kz.rudy.ar.core.duel.DuelEvent.SpecialStarted
import kz.rudy.ar.core.duel.DuelInput.Dash
import kz.rudy.ar.core.duel.DuelInput.Jump
import kz.rudy.ar.core.duel.DuelInput.MoveTo
import kz.rudy.ar.core.duel.DuelInput.Special
import kz.rudy.ar.core.physics.Physics
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

private val P = DuelParams.DEFAULT

/** [fighter] with [ability] on the body (the walking game) and as its duel special. */
internal fun gifted(
    id: String,
    pos: Vec2,
    ability: Ability,
    stats: FighterStats = FighterStats.BALANCED,
    facing: Vec2 = Vec2(1f, 0f),
) = fighter(id, pos, stats, facing = facing, ability = ability).copy(ability = ability)

private fun grounded(f: DuelFighter) = f.body.state.mode == Mode.IDLE || f.body.state.mode == Mode.WALKING

/** The trace's state at simulated time [t] (frames of 16 ms land on it when t % 16 == 0). */
private fun Trace.at(t: Long): DuelState = states.first { it.timeMs == t }

class DuelSpecialsTest {

    @Test
    fun stompKnocksEveryoneNearTheLandingAway() {
        fun stomp(victim: DuelFighter, script: (DuelState) -> Map<String, DuelInput> = atStart("a" to Special)) =
            runDuel(DuelState(gifted("a", Vec2.ZERO, Ability.JUMP), victim), OPEN, 1500L, script = script)

        // Touching on the right: nothing happens while a is in the air; its landing knocks b straight along +u.
        val side = stomp(fighter("b", Vec2(2 * R, 0f)))
        assertEquals(listOf<DuelEvent>(SpecialStarted("a", Ability.JUMP, 0L)), side.all.filterIsInstance<SpecialStarted>())
        val hit = side.all.filterIsInstance<Hit>().single()
        val stompSpeed = P.knockSpeedMpsFor(FighterStats.BALANCED, FighterStats.BALANCED) * P.stompScale
        assertEquals(Hit("a", "b", stompSpeed, fromAbove = true, atMs = hit.atMs), hit)
        assertEquals(0.3f, hit.speedMps, 1e-6f)
        val k = side.indexOf { it is Hit }
        assertEquals(Mode.JUMPING, side.states[k - 1].a.body.state.mode, "still in the air the step before")
        assertTrue(grounded(side.states[k].a), "the hit is the landing")
        assertTrue(hit.atMs in 600L..720L, "a 0.67 s jump: landed at ${hit.atMs} ms")
        assertTrue(side.states.subList(0, k).all { it.b == side.states[0].b }, "b untouched before the landing")
        assertTrue(side.states.subList(1, k).all { it.a.stomping } && !side.states[k].a.stomping)
        val pushed = side.states[k].b
        assertTrue(pushed.knock.normalized().distanceTo(Vec2(1f, 0f)) < 1e-5f, "straight away: ${pushed.knock}")
        assertTrue(DuelPhysics.isStunned(pushed))
        assertTrue(side.last.b.pos.u - 2 * R > 0.04f, "slid ${side.last.b.pos.u - 2 * R} m (0.3 m/s x 0.18 s)")

        // Any side: on a diagonal 7.1 cm away, along the diagonal; behind it, backwards. The stomp has no facing.
        val diagonal = stomp(fighter("b", Vec2(0.05f, 0.05f)))
        val d = diagonal.states[diagonal.indexOf { it is Hit }].b.knock.normalized()
        assertTrue(d.distanceTo(Vec2(0.70710677f, 0.70710677f)) < 1e-5f, "diagonal knock $d")
        val behind = stomp(fighter("b", Vec2(-0.08f, 0f)))
        assertTrue(behind.states[behind.indexOf { it is Hit }].b.knock.u < 0f)

        // Out of reach (3 radii = 9 cm between centres): nothing.
        assertTrue(stomp(fighter("b", Vec2(0.1f, 0f))).all.none { it is Hit })
        // A plain Jump lands the same way and hits nobody.
        assertTrue(stomp(fighter("b", Vec2(2 * R, 0f)), atStart("a" to Jump)).all.none { it is Hit })
        // A lifted FLY or a dodging HIDE next to the landing is not hit.
        val lifted = stomp(gifted("b", Vec2(2 * R, 0f), Ability.FLY), atStart("a" to Special, "b" to Special))
        assertTrue(lifted.all.none { it is Hit }, "${lifted.all}")
        val dodging = stomp(gifted("b", Vec2(2 * R, 0f), Ability.HIDE)) { s ->
            when (s.timeMs) {
                0L -> inputs("a" to Special)
                320L -> inputs("b" to Special) // dodging 320..824 ms, over the landing
                else -> emptyMap()
            }
        }
        assertEquals(2, dodging.all.count { it is SpecialStarted })
        assertTrue(dodging.all.none { it is Hit }, "${dodging.all}")
    }

    @Test
    fun liftedFighterCannotBeHitAndTheDashCarriesOn() {
        val start = DuelState(fighter("a", Vec2.ZERO), gifted("b", Vec2(NEAR, 0f), Ability.FLY, facing = Vec2(-1f, 0f)))
        val script = mapOf(0L to inputs("a" to Dash(), "b" to Special), 2000L to inputs("a" to Dash(Vec2(-1f, 0f))))
        val t = runDuel(start, OPEN, 3000L) { s -> script[s.timeMs] ?: emptyMap() }
        assertEquals(SpecialStarted("b", Ability.FLY, 0L), t.all.filterIsInstance<SpecialStarted>().single())
        assertTrue(t.all.filter { it.atMs < 2000L }.none { it is Hit || it is Clash }, "a dash passes under: ${t.all}")

        // Lifted 1.2 s: hovering 1.5 cm up (JUMPING, no vertical speed), in place, unknocked.
        for (s in t.states.filter { it.timeMs in 16L..P.liftMs }) {
            val b = s.b.body
            assertEquals(Mode.JUMPING, b.state.mode, "at ${s.timeMs} ms")
            assertEquals(P.liftHeightRadii * R, b.state.h, 1e-6f, "hover height at ${s.timeMs} ms")
            assertEquals(0f, b.verticalVelocity)
            assertEquals(start.b.pos, s.b.pos)
            assertEquals(Vec2.ZERO, s.b.knock)
            assertTrue(DuelPhysics.isLifted(s.b) == (s.timeMs < P.liftMs) && DuelPhysics.isImmune(s.b) == DuelPhysics.isLifted(s.b))
        }
        // The dash carried on for its full 7.5 cm: a ends past b's centre, under it.
        assertEquals(P.dashSpeedMps * P.dashMs / 1000f, t.at(256L).a.pos.u, 1e-4f)
        assertTrue(t.at(256L).a.pos.u > start.b.pos.u)
        // After the lift b comes down on top of a, and separation parts them the step it lands.
        val landed = t.states.indexOfFirst { it.timeMs > P.liftMs && grounded(it.b) }
        assertTrue(landed > 0 && t.states[landed].timeMs < 1600L, "came down at ${t.states.getOrNull(landed)?.timeMs} ms")
        assertTrue(t.states[landed - 1].b.body.state.h > 0f)
        for (s in t.states.drop(landed)) assertTrue(s.a.pos.distanceTo(s.b.pos) >= 2 * R - 1e-4f, "overlap at ${s.timeMs} ms")
        // Once down it can be hit again.
        val second = t.all.filterIsInstance<Hit>().single()
        assertEquals("a" to "b", second.attacker to second.victim)
        assertTrue(second.atMs > 2000L)

        // Lifted it walks at 1.5x, cannot dash, and can still walk off the edge (u = 0.15).
        val level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.3f, 0.4f), spawn = Vec2(-0.1f, 0f))
        val walker = DuelState(gifted("a", Vec2.ZERO, Ability.FLY), fighter("b", Vec2(-0.1f, 0.15f)))
        val w = runDuel(walker, level, 3000L) { s ->
            when {
                s.timeMs == 0L -> inputs("a" to MoveTo(Vec2(1f, 0f)))
                s.timeMs == 16L -> inputs("a" to Special)
                s.timeMs < 1100L -> inputs("a" to Dash())
                else -> emptyMap()
            }
        }
        val liftSpeed = (w.at(800L).a.pos.u - w.at(208L).a.pos.u) / 0.592f
        assertEquals(P.walkSpeedMpsFor(FighterStats.BALANCED) * P.liftSpeedScale, liftSpeed, 1e-3f)
        assertTrue(w.all.none { it is DashStarted }, "no dash while lifted: ${w.all}")
        val out = w.all.filterIsInstance<RingOut>().single()
        val k = w.indexOf { it is RingOut }
        assertTrue(DuelPhysics.isLifted(w.states[k - 1].a) && out.atMs < 16L + P.liftMs, "walked off while lifted, at ${out.atMs} ms")
        assertFalse(DuelPhysics.isLifted(w.states[k].a), "the lift ends at the ring-out")
        assertEquals(level.spawn, w.last.a.pos, "came down past the edge and respawned")
    }

    @Test
    fun ramHitsHarderAndCanRollOffTheEdge() {
        fun slide(start: DuelState, input: DuelInput): Pair<Float, Trace> {
            val t = runDuel(start, OPEN, 2500L, script = atStart("a" to input))
            return (t.last.b.pos.u - start.b.pos.u) to t
        }
        val (plainSlide, plain) = slide(DuelState(fighter("a", Vec2.ZERO), fighter("b", Vec2(NEAR, 0f))), Dash())
        val (ramSlide, ram) = slide(DuelState(gifted("a", Vec2.ZERO, Ability.ROLL), fighter("b", Vec2(NEAR, 0f))), Special)
        val plainHit = plain.all.filterIsInstance<Hit>().single()
        val ramHit = ram.all.filterIsInstance<Hit>().single()
        assertEquals(plainHit.speedMps * P.ramKnockScale, ramHit.speedMps, 1e-6f)
        assertEquals(0.375f, ramHit.speedMps, 1e-6f)
        assertTrue(ramSlide > 1.4f * plainSlide, "ram slide $ramSlide vs dash $plainSlide")
        assertEquals(listOf<DuelEvent>(SpecialStarted("a", Ability.ROLL, 0L)), ram.all.filter { it is SpecialStarted || it is DashStarted })
        assertTrue(DuelPhysics.isStunned(ram.states[ram.indexOf { it is Hit }].b))
        assertEquals(0L, ram.states[ram.indexOf { it is Hit }].a.dashMs, "the hit ends the ram")

        // A ram that meets nobody: 550 ms, 16.5 cm, and nothing steers it; the dash cooldown is untouched.
        assertEquals(550L, P.ramDashMs)
        val alone = DuelState(gifted("a", Vec2.ZERO, Ability.ROLL), fighter("b", Vec2(0f, 0.5f)))
        val miss = runDuel(alone, OPEN, 1000L) { s ->
            when {
                s.timeMs == 0L -> inputs("a" to Special)
                s.timeMs < 560L -> inputs("a" to MoveTo(Vec2(-0.3f, 0.3f)))
                s.timeMs == 560L -> inputs("a" to Dash())
                else -> emptyMap()
            }
        }
        assertEquals(P.dashSpeedMps * P.ramDashMs / 1000f, miss.at(560L).a.pos.u, 1e-4f)
        assertEquals(0f, miss.at(560L).a.pos.v, 1e-6f)
        assertTrue(miss.states.filter { it.timeMs in 16L..544L }.all { DuelPhysics.isRamming(it.a) })
        assertEquals(DashKind.PLAIN, miss.at(560L).a.dashKind)
        assertEquals(listOf<DuelEvent>(dashStarted("a")), miss.all.filterIsInstance<DashStarted>().untimed(), "a dash right after the ram")

        // 12 cm from the edge: a dash stops on the table, a ram carries the roller off it.
        val level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.3f, 0.3f), spawn = Vec2(-0.1f, 0f))
        val nearEdge = DuelState(gifted("a", Vec2(0.03f, 0f), Ability.ROLL), fighter("b", Vec2(-0.1f, 0.1f)))
        val rolled = runDuel(nearEdge, level, 2500L, script = atStart("a" to Special))
        val fell = rolled.all.filterIsInstance<RingOut>().single()
        assertEquals("a", fell.id)
        assertTrue(abs(fell.atMs - 400L) <= 2 * P.subStepMs, "off the edge 12 cm later at 0.3 m/s: ${fell.atMs} ms")
        val dashed = runDuel(nearEdge, level, 2500L, script = atStart("a" to Dash()))
        assertTrue(dashed.all.none { it is RingOut })
        assertEquals(0.105f, dashed.last.a.pos.u, 1e-4f)
    }

    @Test
    fun dodgeLetsTheHitThroughAndTheCounterHitsHarder() {
        val start = DuelState(fighter("a", Vec2.ZERO), gifted("b", Vec2(NEAR, 0f), Ability.HIDE, facing = Vec2(-1f, 0f)))
        val script = mapOf(0L to inputs("a" to Dash(), "b" to Special), 608L to inputs("b" to Dash()))
        val t = runDuel(start, OPEN, 2500L) { s -> script[s.timeMs] ?: emptyMap() }
        assertEquals(SpecialStarted("b", Ability.HIDE, 0L), t.all.filterIsInstance<SpecialStarted>().single())

        // The dodge: a's dash goes through no hit, b is not knocked, stunned or moved (a goes round it), and the dash is
        // not spent on b: it runs its whole 250 ms.
        val before = t.states.filter { it.timeMs < 608L }
        assertTrue(t.all.none { (it is Hit && it.victim == "b") || it is Clash }, "${t.all}")
        for (s in before) {
            assertEquals(Vec2.ZERO, s.b.knock)
            assertFalse(DuelPhysics.isStunned(s.b))
            assertEquals(start.b.pos, s.b.pos, "b stands firm at ${s.timeMs} ms")
            assertTrue(s.a.pos.distanceTo(s.b.pos) >= 2 * R - 1e-4f, "no overlap at ${s.timeMs} ms")
            if (s.timeMs > 0L) assertEquals(s.timeMs < P.dodgeMs, DuelPhysics.isDodging(s.b), "dodging at ${s.timeMs} ms")
        }
        assertTrue(before.filter { it.timeMs in 16L..240L }.all { it.a.dashMs > 0L }, "the dash carried on: ${before.take(18).map { it.a.dashMs }}")
        // The counter, 608 ms after the dodge: 1.5x the knockback.
        val counter = t.all.filterIsInstance<Hit>().single()
        assertEquals("b" to "a", counter.attacker to counter.victim)
        assertEquals(P.knockSpeedMpsFor(FighterStats.BALANCED, FighterStats.BALANCED) * P.counterKnockScale, counter.speedMps, 1e-6f)
        val countering = DuelPhysics.step(t.at(608L), OPEN, 4L, inputs("b" to Dash())).state.b // no sub-step ends in 4 ms
        assertEquals(DashKind.COUNTER, countering.dashKind)
        assertEquals(0L, countering.counterMs, "a counter is used once")
        assertEquals(DashKind.PLAIN, t.at(624L).b.dashKind, "the hit ended it")

        // The counter window is 1.5 s from the dodge: a dash at 1488 ms still counters, one at 1600 ms is plain. And once
        // the dodge is over b can be hit.
        fun dashAfterDodge(at: Long): Hit {
            val s = runDuel(start, OPEN, at + 600L) { st ->
                when (st.timeMs) {
                    0L -> inputs("b" to Special)
                    at -> inputs("b" to Dash())
                    else -> emptyMap()
                }
            }
            return s.all.filterIsInstance<Hit>().single()
        }
        assertEquals(0.375f, dashAfterDodge(1488L).speedMps, 1e-6f)
        assertEquals(0.25f, dashAfterDodge(1600L).speedMps, 1e-6f)
        val late = runDuel(start, OPEN, 1500L) { st ->
            when (st.timeMs) {
                0L -> inputs("b" to Special)
                512L -> inputs("a" to Dash())
                else -> emptyMap()
            }
        }
        assertEquals(listOf("a"), late.all.filterIsInstance<Hit>().map { it.attacker }, "hit right after the dodge: ${late.all}")
    }

    @Test
    fun specialOnCooldownOrWhileBusyIsIgnored() {
        // Pressed on every frame: each special starts at 0 and again exactly when its 6 s cooldown is over.
        for (ability in Ability.entries) {
            val t = runDuel(DuelState(gifted("a", Vec2.ZERO, ability), fighter("b", Vec2(0f, 0.5f))), OPEN, 7000L) {
                inputs("a" to Special)
            }
            assertEquals(listOf(0L, P.specialCooldownMsFor(FighterStats.BALANCED)), t.all.filterIsInstance<SpecialStarted>().map { it.atMs }, "$ability")
        }
        // Busy: stunned, dashing, airborne, out, PAUSED, on cooldown, its special still running. Special does exactly
        // what no input does.
        val level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.4f, 0.4f), spawn = Vec2(-0.1f, 0f))
        for (ability in Ability.entries) {
            val f = gifted("a", Vec2.ZERO, ability)
            val busy = mapOf(
                "stunned" to f.copy(stunMs = 200L),
                "knocked" to f.copy(knock = Vec2(0.1f, 0f)),
                "dashing" to f.copy(dashMs = 100L, dashDir = Vec2(1f, 0f)),
                "airborne" to f.copy(body = f.body.copy(state = f.body.state.copy(mode = Mode.JUMPING, h = 0.02f), verticalVelocity = 0.2f)),
                "out" to f.copy(body = f.body.copy(state = f.body.state.copy(pos = Vec2(0.25f, 0f)))),
                "paused" to f.copy(body = Physics.pause(f.body)),
                "cooling down" to f.copy(specialCooldownMs = 8L),
                "running" to f.copy(specialMs = 300L),
            )
            assertTrue(DuelPhysics.canSpecial(f, level), "$ability fresh")
            for ((why, g) in busy) {
                assertFalse(DuelPhysics.canSpecial(g, level), "$ability $why")
                val s = DuelState(g, fighter("b", Vec2(-0.1f, 0.15f)))
                assertEquals(DuelPhysics.step(s, level, 16L), DuelPhysics.step(s, level, 16L, inputs("a" to Special)), "$ability $why")
            }
        }
        // A JUMP special on a ROLL body (it cannot jump) does nothing either.
        val roller = fighter("a", Vec2.ZERO, ability = Ability.ROLL).copy(ability = Ability.JUMP)
        assertFalse(DuelPhysics.canSpecial(roller, OPEN))
    }

    @Test
    fun specialCooldownShortensWithAgility() {
        val cooldowns = (1..10).map { P.specialCooldownMsFor(stats(agility = it)) }
        for (i in 1 until cooldowns.size) assertTrue(cooldowns[i] < cooldowns[i - 1], "$cooldowns")
        assertEquals(7440L, cooldowns[0])
        assertEquals(6000L, cooldowns[4])
        assertEquals(4200L, cooldowns[9])
        // Behaviour: pressed on every 8 ms frame, the second special starts one cooldown after the first.
        for (agility in listOf(1, 5, 10)) {
            val st = stats(agility = agility)
            val t = runDuel(DuelState(gifted("a", Vec2.ZERO, Ability.HIDE, st), fighter("b", Vec2(0f, 0.5f))), OPEN, 8000L, dtMs = 8L) {
                inputs("a" to Special)
            }
            val times = t.all.filterIsInstance<SpecialStarted>().map { it.atMs }
            assertEquals(P.specialCooldownMsFor(st), times[1] - times[0], "agility $agility: $times")
        }
    }

    @Test
    fun hudReadsCooldownsAsFractions() {
        val start = DuelState(gifted("a", Vec2.ZERO, Ability.HIDE), fighter("b", Vec2(0f, 0.5f)))
        val t = runDuel(start, OPEN, 7000L) { s ->
            when (s.timeMs) {
                0L -> inputs("a" to Special)
                16L -> inputs("a" to Dash())
                else -> emptyMap()
            }
        }
        assertEquals(0f, DuelPhysics.dashCooldownLeft(start.a))
        assertEquals(0f, DuelPhysics.specialCooldownLeft(start.a))
        val at = t.at(768L).a
        assertEquals((1500f - 752f) / 1500f, DuelPhysics.dashCooldownLeft(at), 1e-6f)
        assertEquals((6000f - 768f) / 6000f, DuelPhysics.specialCooldownLeft(at), 1e-6f)
        val fractions = t.states.map { DuelPhysics.dashCooldownLeft(it.a) to DuelPhysics.specialCooldownLeft(it.a) }
        assertTrue(fractions.drop(2).zipWithNext().all { (x, y) -> y.first <= x.first && y.second <= x.second }, "they only run down")
        assertTrue(fractions.all { (d, s) -> d in 0f..1f && s in 0f..1f })
        assertEquals(0f to 0f, fractions.last())
        // The params in use decide the length: twice the cooldown, twice the fraction left.
        val slow = P.copy(specialCooldownMs = 12_000L)
        assertEquals(1f - 768f / 12_000f, DuelPhysics.specialCooldownLeft(at.copy(specialCooldownMs = 12_000L - 768L), slow), 1e-6f)
    }

    @Test
    fun fuzzWithSpecialsKeepsTheInvariants() {
        // T42's fuzz level and checks, with every fighter given a special it presses at random (and on purpose near the
        // other one): JUMP-FLY, ROLL-HIDE, FLY-ROLL, HIDE-JUMP, so every ability meets two others.
        val level = Level(
            objects = listOf(
                cup("cup", Vec2(0.15f, 0.08f)),
                LevelObject("bottle", "bottle", ObjectKind.OBSTACLE, Polygon.rect(Vec2(-0.2f, -0.12f), 0.07f, 0.07f), 0.2f),
                book("book", Vec2(-0.12f, 0.1f), 0.14f, 0.1f, 0.03f),
                LevelObject("lid", "lid", ObjectKind.OBSTACLE, Polygon.rect(Vec2(0.05f, -0.15f), 0.08f, 0.04f), 0.02f),
                LevelObject("box", "box", ObjectKind.PLATFORM, Polygon.rect(Vec2(0.26f, -0.1f), 0.08f, 0.08f), 0.06f),
            ),
            boundary = Polygon(listOf(Vec2(-0.4f, -0.25f), Vec2(0.35f, -0.28f), Vec2(0.42f, 0.2f), Vec2(-0.1f, 0.3f), Vec2(-0.42f, 0.15f))),
            spawn = Vec2(0f, 0.05f),
        )
        val pairs = listOf(Ability.JUMP to Ability.FLY, Ability.ROLL to Ability.HIDE, Ability.FLY to Ability.ROLL, Ability.HIDE to Ability.JUMP)
        val steps = 10_000
        val counts = HashMap<String, Int>()
        fun count(key: String) { counts[key] = (counts[key] ?: 0) + 1 }
        val ends = pairs.mapIndexed { i, (x, y) ->
            specialsFuzz(level, seed = 100 + i, steps = steps, abilities = x to y) { prev, out ->
                val s = out.state
                for (e in out.events) {
                    count(e::class.simpleName!!)
                    if (e is SpecialStarted) count("Special ${e.ability}")
                    if (e is Hit && e.fromAbove && s[e.attacker]!!.ability == Ability.JUMP) count("stomp or high hit")
                }
                for (f in listOf(s.a, s.b)) {
                    val st = f.body.state
                    val before = prev[f.id]!!
                    val all = listOf(st.pos.u, st.pos.v, st.h, f.knock.u, f.knock.v, f.dashDir.u, f.dashDir.v, f.body.verticalVelocity)
                    assertTrue(all.all { it.isFinite() }, "non-finite fighter ${f.id} at ${s.timeMs} ms: $f")
                    assertTrue(listOf(f.stunMs, f.dashMs, f.dashCooldownMs, f.specialMs, f.specialCooldownMs, f.counterMs).all { it >= 0L }, "negative timer: $f")
                    assertTrue(f.dashMs > 0L || f.dashKind == DashKind.PLAIN, "a dash kind without a dash: $f")
                    if (DuelPhysics.isLifted(f)) count("lifted steps")
                    if (DuelPhysics.isDodging(f)) count("dodging steps")
                    val ground = grounded(f)
                    val inside = level.objects.firstOrNull {
                        Physics.blocks(it, st.h) && (it.footprint.contains(st.pos) || distanceToPolygon(st.pos, it.footprint) < st.radiusM - 0.001f)
                    }
                    assertEquals(null, inside?.id, "${f.id} overlaps ${inside?.id} at ${s.timeMs} ms: $f")
                    if (ground) assertTrue(!Physics.isOverCliff(level, st.pos), "${f.id} stands past the edge at ${s.timeMs} ms: $st")
                    if (st.mode == Mode.PAUSED) assertEquals(before, f, "a paused fighter is frozen")
                    val boundary = level.boundary!!
                    val wasOn = boundary.contains(before.pos)
                    val isOn = boundary.contains(st.pos)
                    val rang = out.events.of<RingOut>(f.id)
                    if (wasOn && !isOn) assertEquals(1, rang.size, "${f.id} left the table at ${s.timeMs} ms without a RingOut: $before -> $f")
                    if (!wasOn && isOn) {
                        assertTrue(ground && st.pos.distanceTo(level.spawn) <= 4 * R, "${f.id} came back onto the table without a respawn at ${s.timeMs} ms: $before -> $f")
                    }
                    if (rang.isNotEmpty()) assertTrue(wasOn && !isOn && rang.size == 1, "${f.id} RingOut without leaving at ${s.timeMs} ms: $before -> $f")
                }
                if (grounded(s.a) && grounded(s.b)) {
                    val dist = s.a.pos.distanceTo(s.b.pos)
                    assertTrue(dist >= 2 * R - 1e-4f, "fighters overlap by ${2 * R - dist} at ${s.timeMs} ms: ${s.a} ${s.b}")
                }
            }
        }
        println("specials fuzz: ${pairs.size} x $steps steps, $counts")
        for (key in listOf("Hit", "Clash", "WallSlam", "RingOut", "stomp or high hit", "lifted steps", "dodging steps") + Ability.entries.map { "Special $it" }) {
            assertTrue((counts[key] ?: 0) > 0, "the fuzz never produced $key: $counts")
        }

        // Deterministic, and timed without the checks (best of two replays).
        var best = Double.MAX_VALUE
        repeat(2) {
            val mark = TimeSource.Monotonic.markNow()
            val again = pairs.mapIndexed { i, ab -> specialsFuzz(level, seed = 100 + i, steps = steps, abilities = ab) { _, _ -> } }
            best = minOf(best, mark.elapsedNow().inWholeMicroseconds / 1e6)
            assertEquals(ends, again)
        }
        println("specials fuzz: ${(pairs.size * steps / best).toLong()} steps/s")
    }

    /** T42's random play (frames of 16..50 ms, plans, host pauses) with abilities and specials. Returns the end state. */
    private fun specialsFuzz(
        level: Level,
        seed: Int,
        steps: Int,
        abilities: Pair<Ability, Ability>,
        check: (prev: DuelState, out: DuelStep) -> Unit,
    ): DuelState {
        val rnd = Random(seed)
        fun randomStats(): FighterStats {
            val s = IntArray(4) { FighterStats.MIN }
            repeat(FighterStats.BUDGET - 4 * FighterStats.MIN) {
                var i = rnd.nextInt(4)
                while (s[i] >= FighterStats.MAX) i = (i + 1) % 4
                s[i]++
            }
            return FighterStats(s[0], s[1], s[2], s[3])
        }
        var state = DuelState(
            gifted("a", Vec2(-0.1f, -0.05f), abilities.first, randomStats()),
            gifted("b", Vec2(0.1f, -0.02f), abilities.second, randomStats(), facing = Vec2(-1f, 0f)),
        )
        var paused = false
        val plan = intArrayOf(0, 1)
        val wander = arrayOf(Vec2(0.5f, 0f), Vec2(-0.5f, 0.1f))
        repeat(steps) {
            if (!paused && rnd.nextInt(1000) < 3) {
                paused = true
                state = state.copy(a = state.a.copy(body = Physics.pause(state.a.body)), b = state.b.copy(body = Physics.pause(state.b.body)))
            } else if (paused && rnd.nextInt(100) < 5) {
                paused = false
                state = state.copy(a = state.a.copy(body = Physics.resume(state.a.body)), b = state.b.copy(body = Physics.resume(state.b.body)))
            }
            val inputs = HashMap<String, DuelInput>()
            for ((k, pair) in listOf(state.a to state.b, state.b to state.a).withIndex()) {
                val (me, other) = pair
                if (rnd.nextInt(100) < 3) {
                    plan[k] = rnd.nextInt(3)
                    val reach = if (rnd.nextInt(4) == 0) 0.6f else 0.3f
                    wander[k] = Vec2((rnd.nextFloat() * 2f - 1f) * reach, (rnd.nextFloat() * 2f - 1f) * reach * 0.7f)
                }
                val toOther = other.pos - me.pos
                val roll = rnd.nextInt(100)
                val input: DuelInput? = when {
                    roll < 3 -> Jump
                    roll < 6 -> Special
                    roll < 7 -> Dash(if (rnd.nextBoolean()) null else Vec2(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f))
                    // Near the other: a special on purpose (a stomp beside it, a dodge of its dash, a ram at it).
                    plan[k] == 0 && toOther.length < 0.1f && roll < 25 && DuelPhysics.canSpecial(me, level) -> Special
                    plan[k] == 0 && toOther.length < 0.12f && DuelPhysics.canDash(me, level) -> Dash(toOther)
                    plan[k] == 0 && roll < 40 -> MoveTo(other.pos)
                    plan[k] == 1 && roll < 15 -> MoveTo(wander[k])
                    else -> null
                }
                if (input != null) inputs[me.id] = input
            }
            val prev = state
            val dt = rnd.nextLong(16L, 51L)
            val out = DuelPhysics.step(state, level, dt, inputs)
            assertEquals(state.timeMs + dt, out.state.timeMs)
            check(prev, out)
            state = out.state
        }
        return state
    }
}
