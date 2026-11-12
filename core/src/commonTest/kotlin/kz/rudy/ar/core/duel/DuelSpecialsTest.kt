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
        assertEquals(0.5f, hit.speedMps, 1e-6f) // T57 review: 0.25 x 2.0 (T57 pass 1: x 1.5; T48: x 0.85; x 1.2 before)
        val k = side.indexOf { it is Hit }
        assertEquals(Mode.JUMPING, side.states[k - 1].a.body.state.mode, "still in the air the step before")
        assertTrue(grounded(side.states[k].a), "the hit is the landing")
        assertTrue(hit.atMs in 600L..720L, "a 0.67 s jump: landed at ${hit.atMs} ms")
        assertTrue(side.states.subList(0, k).all { it.b == side.states[0].b }, "b untouched before the landing")
        assertTrue(side.states.subList(1, k).all { it.a.stomping } && !side.states[k].a.stomping)
        val pushed = side.states[k].b
        assertTrue(pushed.knock.normalized().distanceTo(Vec2(1f, 0f)) < 1e-5f, "straight away: ${pushed.knock}")
        assertTrue(DuelPhysics.isStunned(pushed))
        assertTrue(side.last.b.pos.u - 2 * R > 0.03f, "slid ${side.last.b.pos.u - 2 * R} m (0.5 m/s, braking)")

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
        // A lifted FLY or a dodging HIDE next to the landing is not hit. (The FLY lifts at 512 ms, so that its 200 ms lift
        // covers the landing; T57 pass 1 lifted at 160 for 600 ms, and the 1.2 s lift before covered it from 0.)
        val lifted = stomp(gifted("b", Vec2(2 * R, 0f), Ability.FLY)) { s ->
            when (s.timeMs) {
                0L -> inputs("a" to Special)
                512L -> inputs("b" to Special)
                else -> emptyMap()
            }
        }
        assertEquals(2, lifted.all.count { it is SpecialStarted })
        assertTrue(lifted.all.none { it is Hit }, "${lifted.all}")
        val dodging = stomp(gifted("b", Vec2(2 * R, 0f), Ability.HIDE)) { s ->
            when (s.timeMs) {
                0L -> inputs("a" to Special)
                480L -> inputs("b" to Special) // dodging 480..780 ms, over the landing (the T57 review's 300 ms dodge; 320 for 700 before)
                else -> emptyMap()
            }
        }
        assertEquals(2, dodging.all.count { it is SpecialStarted })
        assertTrue(dodging.all.none { it is Hit }, "${dodging.all}")
    }

    @Test
    fun liftedFighterCannotBeHitAndTheDashCarriesOn() {
        val start = DuelState(fighter("a", Vec2.ZERO), gifted("b", Vec2(NEAR, 0f), Ability.FLY, facing = Vec2(-1f, 0f)))
        // A 600 ms lift (T57 pass 1's): lifted together with a dash that starts touching it, the default 200 ms one (the
        // T57 review) ends while the 250 ms dash is still under it; a bot lifts on a dash coming from a gap.
        val lp = P.copy(liftMs = 600L)
        // a's second dash: on the first 16 ms frame after its cooldown (2000 ms before T48 made the cooldown 2500).
        val again = (P.cooldownMsFor(FighterStats.BALANCED) + 15L) / 16L * 16L
        val script = mapOf(0L to inputs("a" to Dash(), "b" to Special), again to inputs("a" to Dash(Vec2(-1f, 0f))))
        val t = runDuel(start, OPEN, again + 1000L, params = lp) { s -> script[s.timeMs] ?: emptyMap() }
        assertEquals(SpecialStarted("b", Ability.FLY, 0L), t.all.filterIsInstance<SpecialStarted>().single())
        assertTrue(t.all.filter { it.atMs < again }.none { it is Hit || it is Clash }, "a dash passes under: ${t.all}")

        // Lifted: hovering 1.5 cm up (JUMPING, no vertical speed), in place, unknocked; on its last sub-step it is back on
        // the table.
        for (s in t.states.filter { it.timeMs in 16L until lp.liftMs }) {
            val b = s.b.body
            assertEquals(Mode.JUMPING, b.state.mode, "at ${s.timeMs} ms")
            assertEquals(P.liftHeightRadii * R, b.state.h, 1e-6f, "hover height at ${s.timeMs} ms")
            assertEquals(0f, b.verticalVelocity)
            assertEquals(start.b.pos, s.b.pos)
            assertEquals(Vec2.ZERO, s.b.knock)
            assertTrue(DuelPhysics.isLifted(s.b) == (s.timeMs < lp.liftMs) && DuelPhysics.isImmune(s.b) == DuelPhysics.isLifted(s.b))
        }
        // The dash carried on for its full 7.5 cm: a ends past b's centre, under it.
        assertEquals(P.dashSpeedMps * P.dashMs / 1000f, t.at(256L).a.pos.u, 1e-4f)
        assertTrue(t.at(256L).a.pos.u > start.b.pos.u)
        // When the lift ends b stands on the table on top of a, and separation parts them in that same sub-step.
        val landed = t.states.indexOfFirst { it.timeMs > 0L && grounded(it.b) }
        assertEquals((lp.liftMs + 15L) / 16L * 16L, t.states[landed].timeMs, "back on the table when the lift ends (the first 16 ms frame after it)")
        assertEquals(0f, t.states[landed].b.body.state.h)
        assertTrue(t.states[landed - 1].b.body.state.h > 0f)
        for (s in t.states.drop(landed)) assertTrue(s.a.pos.distanceTo(s.b.pos) >= 2 * R - 1e-4f, "overlap at ${s.timeMs} ms")
        // Once down it can be hit again.
        val second = t.all.filterIsInstance<Hit>().single()
        assertEquals("a" to "b", second.attacker to second.victim)
        assertTrue(second.atMs > again)

        // Lifted it walks at [DuelParams.liftSpeedScale] x (1.0 since the T57 review, 1.5 before), cannot dash, and can
        // still walk off the edge (u = 0.15). (A 1.8 s lift: at 1.0 x the 15 cm walk to the edge takes 1.5 s, which the
        // 1.2 s lift before T57 covered at 1.5 x; the 200 ms default ends long before.)
        val long = P.copy(liftMs = 1800L)
        val level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.3f, 0.4f), spawn = Vec2(-0.1f, 0f))
        val walker = DuelState(gifted("a", Vec2.ZERO, Ability.FLY), fighter("b", Vec2(-0.1f, 0.15f)))
        val w = runDuel(walker, level, 3000L, params = long) { s ->
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
        assertTrue(DuelPhysics.isLifted(w.states[k - 1].a) && out.atMs < 16L + long.liftMs, "walked off while lifted, at ${out.atMs} ms")
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
        assertEquals(0.5f, ramHit.speedMps, 1e-6f) // T57: 0.25 x 2.0 (T48: 0.25 x 1.1; x 1.5 = 0.375 before)
        assertTrue(ramSlide > 1.05f * plainSlide, "ram slide $ramSlide vs dash $plainSlide")
        assertEquals(listOf<DuelEvent>(SpecialStarted("a", Ability.ROLL, 0L)), ram.all.filter { it is SpecialStarted || it is DashStarted })
        assertTrue(DuelPhysics.isStunned(ram.states[ram.indexOf { it is Hit }].b))
        assertEquals(0L, ram.states[ram.indexOf { it is Hit }].a.dashMs, "the hit ends the ram")

        // A ram that meets nobody: 325 ms, 9.75 cm at SPEED 5 (T48; 550 ms, 16.5 cm before), and nothing steers it.
        // Since T57 it spends the dash cooldown too, so a dash right after it is ignored (before T57 it started at once).
        // `end` is the first 16 ms frame after it.
        assertEquals(325L, P.ramDashMs)
        val end = (P.ramDashMs + 15L) / 16L * 16L
        val alone = DuelState(gifted("a", Vec2.ZERO, Ability.ROLL), fighter("b", Vec2(0f, 0.5f)))
        fun ramThenDash(params: DuelParams) = runDuel(alone, OPEN, 1000L, params = params) { s ->
            when {
                s.timeMs == 0L -> inputs("a" to Special)
                s.timeMs < end -> inputs("a" to MoveTo(Vec2(-0.3f, 0.3f)))
                s.timeMs == end -> inputs("a" to Dash())
                else -> emptyMap()
            }
        }
        val miss = ramThenDash(P)
        assertEquals(P.ramReachMFor(FighterStats.BALANCED), miss.at(end).a.pos.u, 1e-4f)
        assertEquals(0f, miss.at(end).a.pos.v, 1e-6f)
        assertTrue(miss.states.filter { it.timeMs in 16L..(end - 16L) }.all { DuelPhysics.isRamming(it.a) })
        assertEquals(DashKind.PLAIN, miss.at(end).a.dashKind)
        assertEquals(emptyList(), miss.all.filterIsInstance<DashStarted>(), "the ram spent the dash: ${miss.all}")
        val unshared = ramThenDash(P.copy(specialsShareDashCooldown = false))
        assertEquals(listOf<DuelEvent>(dashStarted("a")), unshared.all.filterIsInstance<DashStarted>().untimed(), "a dash right after the ram")

        // 9 cm from the edge (12 cm before T48 shortened the ram): a dash (7.5 cm) stops on the table, a ram (9.75 cm)
        // carries the roller off it.
        val level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.3f, 0.3f), spawn = Vec2(-0.1f, 0f))
        val nearEdge = DuelState(gifted("a", Vec2(0.06f, 0f), Ability.ROLL), fighter("b", Vec2(-0.1f, 0.1f)))
        val rolled = runDuel(nearEdge, level, 2500L, script = atStart("a" to Special))
        val fell = rolled.all.filterIsInstance<RingOut>().single()
        assertEquals("a", fell.id)
        assertTrue(abs(fell.atMs - 300L) <= 2 * P.subStepMs, "off the edge 9 cm later at 0.3 m/s: ${fell.atMs} ms")
        val dashed = runDuel(nearEdge, level, 2500L, script = atStart("a" to Dash()))
        assertTrue(dashed.all.none { it is RingOut })
        assertEquals(0.06f + P.dashReachMFor(FighterStats.BALANCED), dashed.last.a.pos.u, 1e-4f) // 0.135
        assertTrue(0.06f + P.ramReachMFor(FighterStats.BALANCED) > 0.15f && 0.06f + P.dashReachMFor(FighterStats.BALANCED) < 0.15f)
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
        // The dodged dash opened b's counter window: from its first contact, held full while a pressed into b (until
        // a's dash ended at 256 ms), then counterMs (1.5 s) running down.
        assertEquals(0L, t.at(0L).b.counterMs)
        assertEquals(P.counterMs, t.at(16L).b.counterMs, "the first contact opens it")
        assertEquals(P.counterMs, t.at(256L).b.counterMs, "a's dash pressed in until 256 ms")
        assertEquals(P.counterMs - 96L, t.at(352L).b.counterMs)
        // The counter, at 608 ms: 1.5x the knockback.
        val counter = t.all.filterIsInstance<Hit>().single()
        assertEquals("b" to "a", counter.attacker to counter.victim)
        assertEquals(P.knockSpeedMpsFor(FighterStats.BALANCED, FighterStats.BALANCED) * P.counterKnockScale, counter.speedMps, 1e-6f)
        val countering = DuelPhysics.step(t.at(608L), OPEN, 4L, inputs("b" to Dash())).state.b // no sub-step ends in 4 ms
        assertEquals(DashKind.COUNTER, countering.dashKind)
        assertEquals(0L, countering.counterMs, "a counter is used once")
        assertEquals(DashKind.PLAIN, t.at(624L).b.dashKind, "the hit ended it")

        // The window runs counterMs from the last sub-step the dodge let a hit through (256 ms): a dash at 1744 ms still
        // counters, one at 1760 ms is plain. And once the dodge is over b can be hit.
        fun dashAfterDodge(at: Long): Hit {
            val s = runDuel(start, OPEN, at + 600L) { st ->
                when (st.timeMs) {
                    0L -> inputs("a" to Dash(), "b" to Special)
                    at -> inputs("b" to Dash())
                    else -> emptyMap()
                }
            }
            return s.all.filterIsInstance<Hit>().single()
        }
        assertEquals(0.25f * P.counterKnockScale, dashAfterDodge(1744L).speedMps, 1e-6f) // 0.5 (T48; 0.375 before)
        assertEquals(0.25f, dashAfterDodge(1760L).speedMps, 1e-6f)
        // The first 16 ms frame after the dodge (512 ms before T48 made it 900 ms).
        val afterDodge = (P.dodgeMs + 15L) / 16L * 16L
        val late = runDuel(start, OPEN, afterDodge + 1000L) { st ->
            when (st.timeMs) {
                0L -> inputs("b" to Special)
                afterDodge -> inputs("a" to Dash())
                else -> emptyMap()
            }
        }
        assertEquals(listOf("a"), late.all.filterIsInstance<Hit>().map { it.attacker }, "hit right after the dodge: ${late.all}")
    }

    @Test
    fun dodgeThenDashIsNoBetterThanADash() {
        // The review's case: a at 0, b (HIDE) 10 cm away facing it, 8 ms frames.
        val start = DuelState(fighter("a", Vec2.ZERO), gifted("b", Vec2(0.1f, 0f), Ability.HIDE, facing = Vec2(-1f, 0f)))
        fun play(script: Map<Long, Map<String, DuelInput>>) = runDuel(start, OPEN, 1500L, dtMs = 8L) { s -> script[s.timeMs] ?: emptyMap() }
        val plain = play(mapOf(8L to inputs("a" to Dash(), "b" to Dash())))
        assertEquals(listOf<DuelEvent>(Clash("a", "b", 80L)), plain.all.filter { it is Clash || it is Hit })

        // b dodges at 0, both dash at 8: b's dash ends its dodge, so it is the very same clash, and the special is spent.
        val dodged = play(mapOf(0L to inputs("b" to Special), 8L to inputs("a" to Dash(), "b" to Dash())))
        assertEquals(plain.all, dodged.all.filter { it !is SpecialStarted }, "the same exchange")
        assertEquals(plain.last.a, dodged.last.a)
        assertEquals(plain.last.b, dodged.last.b.copy(specialCooldownMs = 0L))
        assertTrue(dodged.states.all { it.b.counterMs == 0L }, "nothing was dodged, no counter")

        // b first: it dodges at 0 and dashes at 8 (ending the dodge), a dashes at 40. They meet both dashing: a clash.
        val bFirst = play(mapOf(0L to inputs("b" to Special), 8L to inputs("b" to Dash()), 40L to inputs("a" to Dash())))
        assertEquals(listOf("Clash"), bFirst.all.filter { it is Clash || it is Hit }.map { it::class.simpleName }, "${bFirst.all}")
        // a first: a dashes at 8 into b's dodge (it passes), b waits and dashes at 600, still inside its 900 ms dodge
        // (its dash ends the dodge; before T48 the 500 ms dodge was over by then): a counter, a takes it. This is what
        // the dodge is for: a read of the other's dash.
        val aFirst = play(mapOf(0L to inputs("b" to Special), 8L to inputs("a" to Dash()), 600L to inputs("b" to Dash())))
        val hit = aFirst.all.filterIsInstance<Hit>().single()
        assertEquals(Hit("b", "a", 0.25f * P.counterKnockScale, fromAbove = false, atMs = hit.atMs), hit) // 0.5 (T48; 0.375 before)
        assertTrue(aFirst.all.none { it is Clash })
        // a dashes after the dodge has run out (at 600 ms before T48 made it 900 ms): a plain hit on b.
        val afterIt = play(mapOf(0L to inputs("b" to Special), (P.dodgeMs + 7L) / 8L * 8L + 8L to inputs("a" to Dash())))
        assertEquals(listOf("a" to 0.25f), afterIt.all.filterIsInstance<Hit>().map { it.attacker to it.speedMps })
    }

    @Test
    fun counterOnlyAfterADodgedHit() {
        // A dodge with nothing coming gives no counter: b's dash after it is plain.
        val start = DuelState(fighter("a", Vec2.ZERO), gifted("b", Vec2(NEAR, 0f), Ability.HIDE, facing = Vec2(-1f, 0f)))
        val idle = runDuel(start, OPEN, 1300L) { s ->
            when (s.timeMs) {
                0L -> inputs("b" to Special)
                608L -> inputs("b" to Dash())
                else -> emptyMap()
            }
        }
        assertTrue(idle.states.all { it.b.counterMs == 0L })
        assertEquals(listOf(0.25f), idle.all.filterIsInstance<Hit>().map { it.speedMps })

        // A stomp the dodge let through opens it too: a (JUMP) stomps beside b, b dodges over the landing, then counters.
        val stomper = DuelState(gifted("a", Vec2.ZERO, Ability.JUMP), gifted("b", Vec2(2 * R, 0f), Ability.HIDE, facing = Vec2(-1f, 0f)))
        val s = runDuel(stomper, OPEN, 1800L) { st ->
            when (st.timeMs) {
                0L -> inputs("a" to Special)
                480L -> inputs("b" to Special) // dodging 480..780 ms, over the landing (320 before the 300 ms dodge)
                1008L -> inputs("b" to Dash())
                else -> emptyMap()
            }
        }
        val landed = s.states.indexOfFirst { it.timeMs > 0L && !it.a.stomping }
        assertTrue(s.states[landed - 1].b.counterMs == 0L && s.states[landed].b.counterMs > 0L, "opened at the landing")
        assertEquals(listOf(Hit("b", "a", 0.25f * P.counterKnockScale, false, 0L)), s.all.filterIsInstance<Hit>().map { it.copy(atMs = 0L) }) // 0.5 (T48; 0.375 before)

        // A FLY's lift lets a dash pass too, but a lift is not a dodge: no counter. (A 600 ms lift, as in
        // [liftedFighterCannotBeHitAndTheDashCarriesOn]: the 200 ms default, lifted with a dash that starts touching it,
        // ends under the dash.)
        val flyer = start.copy(b = gifted("b", Vec2(NEAR, 0f), Ability.FLY, facing = Vec2(-1f, 0f)))
        val lifted = runDuel(flyer, OPEN, 1000L, params = P.copy(liftMs = 600L), script = atStart("a" to Dash(), "b" to Special))
        assertTrue(lifted.all.none { it is Hit })
        assertTrue(lifted.states.all { it.b.counterMs == 0L })
    }

    @Test
    fun dashingEndsTheDodge() {
        // b (HIDE) dodges at 0. At 16 ms a dashes at it: alone, b's dodge lets it through; if b starts a dash of its own
        // (sideways) at 16 too, the dodge is over at once and the two clash. (96 ms before the T57 review's 300 ms dodge:
        // a's dash from 96 would last past it.)
        val start = DuelState(fighter("a", Vec2.ZERO), gifted("b", Vec2(NEAR, 0f), Ability.HIDE, facing = Vec2(0f, 1f)))
        val through = runDuel(start, OPEN, 600L) { s ->
            when (s.timeMs) {
                0L -> inputs("b" to Special)
                16L -> inputs("a" to Dash())
                else -> emptyMap()
            }
        }
        assertTrue(through.all.none { it is Hit || it is Clash }, "${through.all}")
        val both = runDuel(start, OPEN, 600L) { s ->
            when (s.timeMs) {
                0L -> inputs("b" to Special)
                16L -> inputs("a" to Dash(), "b" to Dash())
                else -> emptyMap()
            }
        }
        assertEquals(listOf("Clash"), both.all.filter { it is Clash || it is Hit }.map { it::class.simpleName }, "${both.all}")
        // The dodge ends the moment the dash starts: no special time left, not dodging, the special still cooling down.
        val dashing = DuelPhysics.step(both.at(16L), OPEN, 4L, inputs("a" to Dash(), "b" to Dash())).state.b
        assertTrue(dashing.dashMs > 0L)
        assertEquals(0L, dashing.specialMs)
        assertFalse(DuelPhysics.isDodging(dashing) || DuelPhysics.isImmune(dashing))
        assertTrue(dashing.specialCooldownMs > 0L)
    }

    @Test
    fun liftedFighterClimbsNoHigherThanAWalker() {
        // A 5 cm platform blocks a walker (4 cm step-up); lifted 1.5 cm, a FLY still meets it as a walker would. (A 1.2 s
        // lift, as before T57, so that it is still lifted where the walk meets the box and the book.)
        val long = DuelParams.DEFAULT.copy(liftMs = 1200L)
        val box = LevelObject("box", "box", ObjectKind.PLATFORM, Polygon.rect(Vec2(0.1f, 0f), 0.06f, 0.06f), 0.05f)
        val level = Level(listOf(box), boundary = Polygon.rect(Vec2.ZERO, 0.6f, 0.4f), spawn = Vec2(-0.2f, 0f))
        fun walk(lift: Boolean) = runDuel(DuelState(gifted("a", Vec2.ZERO, Ability.FLY), fighter("b", Vec2(-0.2f, 0.15f))), level, 2500L, params = long) { s ->
            when (s.timeMs) {
                0L -> inputs("a" to MoveTo(Vec2(0.2f, 0f)))
                16L -> if (lift) inputs("a" to Special) else emptyMap()
                else -> emptyMap()
            }
        }
        for (lift in listOf(true, false)) {
            val t = walk(lift)
            assertTrue(t.states.none { box.footprint.contains(it.a.pos) }, "lift $lift: on the box")
            assertTrue(t.states.all { distanceToPolygon(it.a.pos, box.footprint) >= R - 1e-4f }, "lift $lift: into the box")
            assertEquals(0f, t.last.a.body.state.h, "lift $lift: on the table")
            assertEquals(0.04f, t.last.a.pos.u, 1e-4f, "lift $lift: stopped by the box")
            if (lift) assertTrue(t.states.filter { DuelPhysics.isLifted(it.a) }.all { it.a.body.state.h == P.liftHeightRadii * R })
        }
        // A 3 cm book is a step a walker takes: lifted, the FLY takes it too, hovering 1.5 cm above it, and lands on it.
        val bookLevel = Level(listOf(book("book", Vec2(0.15f, 0f))), boundary = Polygon.rect(Vec2.ZERO, 0.6f, 0.4f), spawn = Vec2(-0.2f, 0f))
        val onto = runDuel(DuelState(gifted("a", Vec2.ZERO, Ability.FLY), fighter("b", Vec2(-0.2f, 0.15f))), bookLevel, 2500L, params = long) { s ->
            when (s.timeMs) {
                0L -> inputs("a" to MoveTo(Vec2(0.15f, 0f)))
                16L -> inputs("a" to Special)
                else -> emptyMap()
            }
        }
        assertEquals(0.03f + P.liftHeightRadii * R, onto.at(1200L).a.body.state.h, 1e-6f, "hovering above the book")
        assertEquals(Vec2(0.15f, 0f), onto.last.a.pos)
        assertEquals(0.03f, onto.last.a.body.state.h)
        assertEquals(Mode.IDLE, onto.last.a.body.state.mode)
    }

    @Test
    fun specialOnCooldownOrWhileBusyIsIgnored() {
        // Pressed on every frame: each special starts at 0 and again exactly when its cooldown is over (9 s since T48, 6 s
        // before; the T57 review: the lift 2.2 x = 19.8 s, the dodge 1.6 x = 14.4 s, the stomp and the ram 9 s).
        for (ability in Ability.entries) {
            val cooldown = P.specialCooldownMsFor(FighterStats.BALANCED, ability)
            val t = runDuel(DuelState(gifted("a", Vec2.ZERO, ability), fighter("b", Vec2(0f, 0.5f))), OPEN, cooldown + 1000L) {
                inputs("a" to Special)
            }
            // On the first 16 ms frame once it is over (9000 is no multiple of 16: 9008; 6000 was).
            val again = (cooldown + 15L) / 16L * 16L
            assertEquals(listOf(0L, again), t.all.filterIsInstance<SpecialStarted>().map { it.atMs }, "$ability")
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
        // T48: 9000 * (1.15 - 0.03 AGILITY) (6000 * (1.30 - 0.06 AGILITY) = 7440 / 6000 / 4200 before).
        assertEquals(10_080L, cooldowns[0])
        assertEquals(9000L, cooldowns[4])
        assertEquals(7650L, cooldowns[9])
        // The T57 review: each special's own scale on top (the stomp and the ram 1, the lift 2.2, the dodge 1.6).
        val five = stats(agility = 5)
        assertEquals(9000L, P.specialCooldownMsFor(five, Ability.JUMP))
        assertEquals(19_800L, P.specialCooldownMsFor(five, Ability.FLY))
        assertEquals(9000L, P.specialCooldownMsFor(five, Ability.ROLL))
        assertEquals(14_400L, P.specialCooldownMsFor(five, Ability.HIDE))
        val unscaled = P.copy(stompCooldownScale = 1f, liftCooldownScale = 1f, ramCooldownScale = 1f, dodgeCooldownScale = 1f)
        for (ability in Ability.entries) assertEquals(9000L, unscaled.specialCooldownMsFor(five, ability), "$ability unscaled")
        // Behaviour: pressed on every 8 ms frame, the second special starts one cooldown after the first, on the 8 ms
        // frame where it is over (a HIDE's since the T57 review: 16128, 14400, 12240 ms at AGILITY 1, 5, 10).
        for (agility in listOf(1, 5, 10)) {
            val st = stats(agility = agility)
            val cooldown = P.specialCooldownMsFor(st, Ability.HIDE)
            val t = runDuel(DuelState(gifted("a", Vec2.ZERO, Ability.HIDE, st), fighter("b", Vec2(0f, 0.5f))), OPEN, cooldown + 1000L, dtMs = 8L) {
                inputs("a" to Special)
            }
            val times = t.all.filterIsInstance<SpecialStarted>().map { it.atMs }
            assertEquals((cooldown + 7L) / 8L * 8L, times[1] - times[0], "agility $agility: $times")
        }
    }

    @Test
    fun hudReadsCooldownsAsFractions() {
        val start = DuelState(gifted("a", Vec2.ZERO, Ability.HIDE), fighter("b", Vec2(0f, 0.5f)))
        val dodgeCooldown = P.specialCooldownMsFor(FighterStats.BALANCED, Ability.HIDE)
        val t = runDuel(start, OPEN, dodgeCooldown + 1000L) { s ->
            when (s.timeMs) {
                0L -> inputs("a" to Special)
                16L -> inputs("a" to Dash())
                else -> emptyMap()
            }
        }
        assertEquals(0f, DuelPhysics.dashCooldownLeft(start.a))
        assertEquals(0f, DuelPhysics.specialCooldownLeft(start.a))
        val at = t.at(768L).a
        // T48: 2500 and 9000 ms cooldowns (1500 and 6000 before); the T57 review: a dodge's is 1.6 x, 14400 ms.
        assertEquals(14_400L, dodgeCooldown)
        assertEquals((2500f - 752f) / 2500f, DuelPhysics.dashCooldownLeft(at), 1e-6f)
        assertEquals((14_400f - 768f) / 14_400f, DuelPhysics.specialCooldownLeft(at), 1e-6f)
        val fractions = t.states.map { DuelPhysics.dashCooldownLeft(it.a) to DuelPhysics.specialCooldownLeft(it.a) }
        assertTrue(fractions.drop(2).zipWithNext().all { (x, y) -> y.first <= x.first && y.second <= x.second }, "they only run down")
        assertTrue(fractions.all { (d, s) -> d in 0f..1f && s in 0f..1f })
        assertEquals(0f to 0f, fractions.last())
        // The params in use decide the length: twice the cooldown, twice the fraction left.
        val slow = P.copy(specialCooldownMs = 12_000L)
        val slowFull = slow.specialCooldownMsFor(FighterStats.BALANCED, Ability.HIDE) // 19200
        assertEquals(1f - 768f / slowFull, DuelPhysics.specialCooldownLeft(at.copy(specialCooldownMs = slowFull - 768L), slow), 1e-6f)
    }

    @Test
    fun fuzzWithSpecialsKeepsTheInvariants() {
        // T42's fuzz level and checks, with every fighter given a special it presses at random (and on purpose near the
        // other one): all 16 ordered ability pairs, same-ability pairs included.
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
        val pairs = Ability.entries.flatMap { x -> Ability.entries.map { y -> x to y } }
        val steps = 3_000
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
