package kz.rudy.ar.core.duel

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.duel.DuelEvent.Clash
import kz.rudy.ar.core.duel.DuelEvent.DashStarted
import kz.rudy.ar.core.duel.DuelEvent.Hit
import kz.rudy.ar.core.duel.DuelEvent.Missed
import kz.rudy.ar.core.duel.DuelEvent.RingOut
import kz.rudy.ar.core.duel.DuelEvent.SpecialStarted
import kz.rudy.ar.core.duel.DuelInput.Dash
import kz.rudy.ar.core.duel.DuelInput.Jump
import kz.rudy.ar.core.duel.DuelInput.MoveTo
import kz.rudy.ar.core.duel.DuelInput.Special
import kotlinx.serialization.json.Json
import kz.rudy.ar.core.physics.Physics
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The rules on with the brief's starting numbers, on top of whatever the other defaults are. */
private val ON = DuelParams.DEFAULT.copy(
    specialsShareDashCooldown = true, dashReachBase = 0.75f, dashReachPerSpeed = 0.05f, hitReachUpM = 0.02f, hitReachDownM = 0.04f,
    stompReachUpM = 0.02f,
)

/** The same numbers with T57's rules switched off. */
private val OFF = DuelParams.DEFAULT.copy(
    specialsShareDashCooldown = false, dashReachBase = 1f, dashReachPerSpeed = 0f,
    hitReachUpM = DuelParams.NO_REACH_LIMIT, hitReachDownM = DuelParams.NO_REACH_LIMIT, stompReachUpM = DuelParams.NO_REACH_LIMIT,
)

/** A 3 cm book (PLATFORM) [w] x 10 cm centred at u = [cu], v = 0. */
private fun ledge(cu: Float, w: Float = 0.1f) = book("book", Vec2(cu, 0f), w, 0.1f, 0.03f)

/** [f] with its feet at [h] (standing on something that high). */
private fun DuelFighter.at(h: Float) = copy(body = body.copy(state = body.state.copy(h = h)))

/** T57: the three rules of 2026-11-12 (shared cooldown, SPEED lengthens the dash, height matters for hits). */
class DuelNewRulesTest {

    // -- rule 3: height matters for hits ----------------------------------------------------------------------------

    @Test
    fun aJumpOverADashIsADodgeAndTheDashCarriesOn() {
        // b (a JUMP body) 10 cm ahead of a jumps the moment a dashes at it: when the circles meet (136 ms) its feet are
        // 5 cm up, out of the 2 cm reach. No hit, the dash runs its whole reach under it, b is neither knocked, stunned
        // nor wobbly. Before T57 the same exchange is a hit.
        val start = DuelState(fighter("a", Vec2.ZERO), fighter("b", Vec2(0.1f, 0f), facing = Vec2(-1f, 0f)))
        val script = atStart("a" to Dash(), "b" to Jump)
        val t = runDuel(start, OPEN, 1500L, dtMs = 8L, params = ON, script = script)
        assertTrue(t.all.none { it is Hit || it is Clash }, "${t.all}")
        val overlapping = t.states.filter { it.a.dashMs > 0L && it.a.pos.distanceTo(it.b.pos) < 2 * R }
        assertTrue(overlapping.size > 10, "the dash ran under b for ${overlapping.size} sub-steps")
        for (s in overlapping) assertTrue(s.b.body.state.h - s.a.body.state.h > ON.hitReachUpM, "in reach at ${s.timeMs} ms: ${s.b.body.state.h}")
        val ended = t.states.first { it.timeMs == (ON.dashMs + 7L) / 8L * 8L }
        assertEquals(0L, ended.a.dashMs)
        assertEquals(ON.dashReachMFor(FighterStats.BALANCED), ended.a.pos.u, 1e-4f, "the dash carried on to its full reach")
        assertTrue(t.states.all { it.b.knock == Vec2.ZERO && it.b.wobble == 0f && it.b.stunMs == 0L })
        assertEquals(Mode.IDLE, t.last.b.body.state.mode, "b came down")
        assertTrue(t.last.a.pos.distanceTo(t.last.b.pos) >= 2 * R - 1e-4f, "and stands beside a")
        // The miss is told once (the T57 review: Missed, for the matrix's misses by height), at the first contact.
        assertEquals(listOf<DuelEvent>(Missed("a", "b", stomp = false, atMs = 136L)), t.all.filterIsInstance<Missed>())
        // Before T57 (reach unlimited) the jump did not help.
        val before = runDuel(start, OPEN, 1500L, dtMs = 8L, params = OFF, script = script)
        assertEquals(listOf("a" to "b"), before.all.filterIsInstance<Hit>().map { it.attacker to it.victim })
    }

    @Test
    fun aLateJumpIsStillHit() {
        // The same dash; b jumps 16 ms before the circles meet: its feet are under 1 cm up there, within reach.
        val start = DuelState(fighter("a", Vec2.ZERO), fighter("b", Vec2(0.1f, 0f), facing = Vec2(-1f, 0f)))
        val t = runDuel(start, OPEN, 1500L, dtMs = 8L, params = ON) { s ->
            when (s.timeMs) {
                0L -> inputs("a" to Dash())
                120L -> inputs("b" to Jump)
                else -> emptyMap()
            }
        }
        val hit = t.all.filterIsInstance<Hit>().single()
        assertEquals("a" to "b", hit.attacker to hit.victim)
        val at = t.states[t.indexOf { it is Hit }]
        assertEquals(Mode.JUMPING, at.b.body.state.mode, "hit in the air")
        assertTrue(at.b.body.state.h in 0.001f..ON.hitReachUpM, "${at.b.body.state.h}")
        assertEquals(ON.wobblePerHit, at.b.wobble)
    }

    @Test
    fun aFighterClearlyHigherIsOutOfReachFromBelow() {
        // b stands on a 3 cm book, 2 cm in from its edge; a dashes at it from the table. 3 cm up is out of the 2 cm reach:
        // no hit, and the miss does not shove b across the book either (a stops against it, as against a dodge).
        val level = Level(listOf(ledge(0.13f)))
        val start = DuelState(fighter("a", Vec2.ZERO), fighter("b", Vec2(0.1f, 0f), facing = Vec2(-1f, 0f)).at(0.03f))
        val t = runDuel(start, level, 1500L, dtMs = 8L, params = ON, script = atStart("a" to Dash()))
        assertTrue(t.all.none { it is Hit || it is Clash }, "${t.all}")
        for (s in t.states) {
            assertEquals(start.b.pos, s.b.pos, "b stands firm at ${s.timeMs} ms")
            assertEquals(0.03f, s.b.body.state.h)
            assertTrue(s.b.knock == Vec2.ZERO && s.b.wobble == 0f && !DuelPhysics.isStunned(s.b, ON))
            assertEquals(0f, s.a.body.state.h, "a never got onto the book")
            assertTrue(s.a.pos.distanceTo(s.b.pos) >= 2 * R - 1e-4f, "no overlap at ${s.timeMs} ms")
        }
        assertTrue(t.states.any { it.a.dashMs > 0L && it.a.pos.distanceTo(it.b.pos) < 2 * R + 1e-3f }, "the dash reached it")
        // Before T57 the fighter on the book was hit from the table.
        val before = runDuel(start, level, 1500L, dtMs = 8L, params = OFF, script = atStart("a" to Dash()))
        assertEquals(1, before.all.count { it is Hit })
    }

    @Test
    fun aDashFromTheTableDoesNotShoveAFighterOnABook() {
        // The T57 review's case, with the defaults (2.8 cm up): b on a 3 cm book (u 0.08..0.18), 2 cm in from its edge; a
        // dashes at it from the table, straight and 10 degrees either side. b stays where it stood to the bit; a never
        // gets inside b nor onto the book, and ends its dash touching b. One Missed, no hit.
        val level = Level(listOf(ledge(0.13f)))
        val book = level.objects.single().footprint
        val start = DuelState(fighter("a", Vec2.ZERO), fighter("b", Vec2(0.1f, 0f), facing = Vec2(-1f, 0f)).at(0.03f))
        val p = DuelParams.DEFAULT
        assertFalse(p.inReach(0f, 0.03f), "3 cm up is out of the default reach")
        for (deg in listOf(0f, 10f, -10f)) {
            val rad = deg * kotlin.math.PI.toFloat() / 180f
            val dir = Vec2(kotlin.math.cos(rad), kotlin.math.sin(rad))
            val t = runDuel(start, level, 1500L, dtMs = 8L, script = atStart("a" to Dash(dir)))
            assertTrue(t.all.none { it is Hit || it is Clash }, "$deg: ${t.all}")
            assertEquals(listOf(Missed("a", "b", stomp = false, atMs = 0L)), t.all.filterIsInstance<Missed>().map { it.copy(atMs = 0L) }, "$deg")
            for (st in t.states) {
                assertEquals(start.b.pos, st.b.pos, "$deg: b stands firm at ${st.timeMs} ms")
                assertEquals(0.03f, st.b.body.state.h)
                assertEquals(0f, st.a.body.state.h, "$deg: a stays on the table at ${st.timeMs} ms")
                assertFalse(book.contains(st.a.pos), "$deg: a over the book at ${st.timeMs} ms")
                assertTrue(st.a.pos.distanceTo(st.b.pos) >= 2 * R - 1e-4f, "$deg: a inside b at ${st.timeMs} ms")
            }
            val ended = t.states.first { it.timeMs > 0L && it.a.dashMs == 0L }
            assertEquals(2 * R, ended.a.pos.distanceTo(ended.b.pos), 1e-4f, "$deg: the dash ends touching b")
        }
    }

    @Test
    fun theDasherStepsUpAndThenHits() {
        // b 2 cm in from the book's far edge (u 0.16, the book 0.08..0.18): a's dash from the table crosses the book's near
        // edge (a 3 cm step, under the 4 cm step-up) before it meets b, so it meets b on the book: a plain hit, no miss.
        val level = Level(listOf(ledge(0.13f)))
        val start = DuelState(fighter("a", Vec2(0.04f, 0f)), fighter("b", Vec2(0.16f, 0f), facing = Vec2(-1f, 0f)).at(0.03f))
        val t = runDuel(start, level, 1500L, dtMs = 8L, script = atStart("a" to Dash()))
        val hit = t.all.filterIsInstance<Hit>().single()
        assertEquals(Hit("a", "b", DuelParams.DEFAULT.knockSpeedMpsFor(FighterStats.BALANCED, FighterStats.BALANCED), fromAbove = false, atMs = hit.atMs), hit)
        assertTrue(t.all.none { it is Missed }, "${t.all}")
        val k = t.indexOf { it is Hit }
        assertEquals(0.03f, t.states[k].a.body.state.h, "a on the book when it hit")
        val up = t.states.indexOfFirst { it.a.body.state.h == 0.03f }
        assertTrue(up in 1 until k, "a stepped up (step $up) before the hit (step $k)")
        assertTrue(t.states.take(up).all { it.a.body.state.h == 0f })
    }

    @Test
    fun aDashFromABookDoesNotShoveAFighterOutOfTheDownReach() {
        // The reverse: a on a 3 cm book, b on the table right beside it, with 2 cm of down reach (the default's 4 cm reaches
        // it: the control). a's dash meets b 3 cm down, out of reach: b stays where it stood to the bit, a stays on the book
        // touching b (it goes round b, back where it came from) and never inside it. One Missed, no hit.
        val level = Level(listOf(ledge(-0.06f)))
        val start = DuelState(fighter("a", Vec2(-0.03f, 0f)).at(0.03f), fighter("b", Vec2(-0.03f + NEAR, 0f)))
        val short = DuelParams.DEFAULT.copy(hitReachDownM = 0.02f)
        val t = runDuel(start, level, 1500L, dtMs = 8L, params = short, script = atStart("a" to Dash()))
        assertTrue(t.all.none { it is Hit || it is Clash }, "${t.all}")
        assertEquals(1, t.all.count { it is Missed && !it.stomp }, "${t.all}")
        for (st in t.states) {
            assertEquals(start.b.pos, st.b.pos, "b stands firm at ${st.timeMs} ms")
            assertEquals(0f, st.b.body.state.h)
            assertTrue(st.b.knock == Vec2.ZERO && st.b.wobble == 0f && !DuelPhysics.isStunned(st.b, short))
            assertEquals(0.03f, st.a.body.state.h, "a stays on the book at ${st.timeMs} ms")
            assertTrue(st.a.pos.distanceTo(st.b.pos) >= 2 * R - 1e-4f, "a inside b at ${st.timeMs} ms")
        }
        val ended = t.states.first { it.timeMs > 0L && it.a.dashMs == 0L }
        assertEquals(2 * R, ended.a.pos.distanceTo(ended.b.pos), 1e-4f, "the dash ends touching b")
        // With the default 4 cm down reach the same dash hits, from above.
        val control = runDuel(start, level, 1500L, dtMs = 8L, script = atStart("a" to Dash()))
        assertEquals(listOf(true), control.all.filterIsInstance<Hit>().map { it.fromAbove })
    }

    @Test
    fun anAttackerOnTheSameBookHits() {
        // Once the attacker has climbed (both on one book) the hit is a plain one.
        val level = Level(listOf(ledge(0.03f, w = 0.3f)))
        val start = DuelState(fighter("a", Vec2.ZERO).at(0.03f), fighter("b", Vec2(NEAR, 0f)).at(0.03f))
        val t = runDuel(start, level, 1500L, params = ON, script = atStart("a" to Dash()))
        val hit = t.all.filterIsInstance<Hit>().single()
        assertEquals(Hit("a", "b", ON.knockSpeedMpsFor(FighterStats.BALANCED, FighterStats.BALANCED), fromAbove = false, atMs = hit.atMs), hit)
        // So does one that walks up onto it from the table and then dashes.
        val climber = DuelState(fighter("a", Vec2(-0.15f, 0f)), fighter("b", Vec2(0.03f, 0f)).at(0.03f))
        var dashed = false
        val walked = runDuel(climber, level, 4000L, params = ON) { s ->
            when {
                s.timeMs == 0L -> inputs("a" to MoveTo(Vec2(-0.04f, 0f)))
                !dashed && s.a.body.state.h == 0.03f && s.a.body.state.mode == Mode.IDLE -> { dashed = true; inputs("a" to Dash(Vec2(1f, 0f))) }
                else -> emptyMap()
            }
        }
        assertTrue(dashed, "a climbed onto the book")
        assertEquals(listOf("a" to "b"), walked.all.filterIsInstance<Hit>().map { it.attacker to it.victim })
    }

    @Test
    fun fromAboveStillHitsDownWithinReach() {
        // The attacker on a 3 cm book, the victim on the table beside it: 3 cm down is within the 4 cm reach, with the
        // high ground's bonus.
        val level = Level(listOf(ledge(-0.06f)))
        val start = DuelState(fighter("a", Vec2(-0.03f, 0f)).at(0.03f), fighter("b", Vec2(-0.03f + NEAR, 0f)))
        val t = runDuel(start, level, 1500L, dtMs = 8L, params = ON, script = atStart("a" to Dash()))
        val hit = t.all.filterIsInstance<Hit>().single()
        assertTrue(hit.fromAbove)
        assertEquals(ON.knockSpeedMpsFor(FighterStats.BALANCED, FighterStats.BALANCED, fromAbove = true), hit.speedMps, 1e-6f)
        assertEquals(8L, hit.atMs, "at once")
        // With only 2 cm of reach downwards the first contact (3 cm down) is a miss, and b stands firm:
        // [aDashFromABookDoesNotShoveAFighterOutOfTheDownReach]. (T57 pass 1 said here that the dash carried a off the
        // book to hit on the way down; it does not: a goes round b and stays on the book, and the check it had never ran.)
        assertEquals(1, runDuel(start, level, 1500L, dtMs = 8L, params = ON.copy(hitReachDownM = 0.02f), script = atStart("a" to Dash())).all.count { it is Missed })
    }

    @Test
    fun theStompMissesAFighterInTheAir() {
        fun stomp(victim: DuelFighter, level: Level = OPEN, attackerH: Float = 0f, script: (DuelState) -> Map<String, DuelInput> = atStart("a" to Special)) =
            runDuel(DuelState(gifted("a", Vec2.ZERO, Ability.JUMP).at(attackerH), victim), level, 2500L, params = ON, script = script)
        // Standing beside the landing: hit (the control).
        assertEquals(1, stomp(fighter("b", Vec2(2 * R, 0f))).all.count { it is Hit })
        // A FLY that jumped with it floats far above the landing; a HIDE that jumped 0.4 s later is 8 cm up: both missed.
        val fly = stomp(fighter("b", Vec2(2 * R, 0f), ability = Ability.FLY), script = atStart("a" to Special, "b" to Jump))
        assertTrue(fly.all.none { it is Hit }, "${fly.all}")
        val hide = stomp(fighter("b", Vec2(2 * R, 0f), ability = Ability.HIDE)) { s ->
            when (s.timeMs) {
                0L -> inputs("a" to Special)
                400L -> inputs("b" to Jump)
                else -> emptyMap()
            }
        }
        assertTrue(hide.all.none { it is Hit }, "${hide.all}")
        val landing = hide.states.first { it.timeMs > 0L && !it.a.stomping }
        assertTrue(landing.b.body.state.h > ON.hitReachUpM, "b was ${landing.b.body.state.h} m up at the landing")
        // On a 3 cm book beside a landing on the table: out of reach. The stomper on the book, the victim below: a hit.
        val onBook = stomp(fighter("b", Vec2(0.07f, 0f)).at(0.03f), level = Level(listOf(ledge(0.1f))))
        assertTrue(onBook.all.none { it is Hit }, "${onBook.all}")
        assertEquals(listOf(Missed("a", "b", stomp = true, atMs = 0L)), onBook.all.filterIsInstance<Missed>().map { it.copy(atMs = 0L) })
        assertEquals(listOf(Missed("a", "b", stomp = true, atMs = 0L)), fly.all.filterIsInstance<Missed>().map { it.copy(atMs = 0L) })
        // With the defaults the stomp reaches 9 cm up (above a jump's 8.3 cm apex): the HIDE that jumped is hit after all,
        // the floating FLY is not.
        val p = DuelParams.DEFAULT
        val hideDefault = runDuel(DuelState(gifted("a", Vec2.ZERO, Ability.JUMP), fighter("b", Vec2(2 * R, 0f), ability = Ability.HIDE)), OPEN, 2500L) { st ->
            when (st.timeMs) {
                0L -> inputs("a" to Special)
                400L -> inputs("b" to Jump)
                else -> emptyMap()
            }
        }
        assertEquals(1, hideDefault.all.count { it is Hit }, "${hideDefault.all}")
        assertTrue(hideDefault.states[hideDefault.indexOf { it is Hit }].b.body.state.h <= p.stompReachUpM)
        val flyDefault = runDuel(DuelState(gifted("a", Vec2.ZERO, Ability.JUMP), fighter("b", Vec2(2 * R, 0f), ability = Ability.FLY)), OPEN, 2500L,
            script = atStart("a" to Special, "b" to Jump))
        assertTrue(flyDefault.all.none { it is Hit }, "${flyDefault.all}")
        val fromBook = stomp(fighter("b", Vec2(0.07f, 0f)), level = Level(listOf(ledge(-0.04f))), attackerH = 0.03f)
        assertEquals(1, fromBook.all.count { it is Hit })
    }

    @Test
    fun aMissAddsNoWobble() {
        // A dash jumped over and a stomp floated over add nothing; the hit that lands afterwards adds one.
        val start = DuelState(fighter("a", Vec2.ZERO), fighter("b", Vec2(0.1f, 0f), facing = Vec2(-1f, 0f)))
        val again = (ON.cooldownMsFor(FighterStats.BALANCED) + 15L) / 16L * 16L
        val t = runDuel(start, OPEN, again + 1000L, params = ON) { s ->
            when (s.timeMs) {
                0L -> inputs("a" to Dash(), "b" to Jump)
                again -> inputs("a" to Dash(s.b.pos - s.a.pos))
                else -> emptyMap()
            }
        }
        assertTrue(t.states.filter { it.timeMs < again }.all { it.b.wobble == 0f })
        assertEquals(1, t.all.count { it is Hit })
        assertTrue(t.all.filterIsInstance<Hit>().single().atMs > again)
        assertEquals(ON.wobblePerHit, t.last.b.wobble)
        val stomp = runDuel(DuelState(gifted("a", Vec2.ZERO, Ability.JUMP), fighter("b", Vec2(2 * R, 0f), ability = Ability.FLY)), OPEN, 2500L, params = ON,
            script = atStart("a" to Special, "b" to Jump))
        assertTrue(stomp.all.none { it is Hit })
        assertTrue(stomp.states.all { it.b.wobble == 0f && it.b.knock == Vec2.ZERO })
    }

    // -- rule 1: the stomp and the ram share the dash cooldown -------------------------------------------------------

    @Test
    fun stompAndRamNeedTheDashAndSpendIt() {
        val far = fighter("b", Vec2(0f, 0.5f))
        for (ability in listOf(Ability.JUMP, Ability.ROLL)) {
            val f = gifted("a", Vec2.ZERO, ability)
            assertTrue(ON.sharesDashCooldown(ability) && !OFF.sharesDashCooldown(ability))
            assertTrue(DuelPhysics.canSpecial(f, OPEN, ON), "$ability fresh")
            // The dash recharging: no special (before T57 the dash did not matter), and the input does nothing at all.
            val cooling = f.copy(dashCooldownMs = 8L)
            assertFalse(DuelPhysics.canSpecial(cooling, OPEN, ON), "$ability")
            assertTrue(DuelPhysics.canSpecial(cooling, OPEN, OFF), "$ability")
            val s = DuelState(cooling, far)
            assertEquals(DuelPhysics.step(s, OPEN, 16L, params = ON), DuelPhysics.step(s, OPEN, 16L, inputs("a" to Special), ON), "$ability")
            // The button's ring shows the longer wait.
            val justDashed = f.copy(dashCooldownMs = ON.cooldownMsFor(f.stats))
            assertEquals(1f, DuelPhysics.specialWaitLeft(justDashed, ON), "$ability")
            assertEquals(0f, DuelPhysics.specialWaitLeft(justDashed, OFF), "$ability")
            // The special spends the dash: with Dash pressed on every frame after it, the first dash starts once the dash
            // cooldown (counted from the special) is over. Before T57: as soon as the body may dash (ram over, stomp landed).
            fun firstDash(params: DuelParams): Long {
                val t = runDuel(DuelState(f, far), OPEN, 4000L, params = params) { st ->
                    if (st.timeMs == 0L) inputs("a" to Special) else inputs("a" to Dash())
                }
                assertEquals(listOf(0L), t.all.filterIsInstance<SpecialStarted>().map { it.atMs }, "$ability")
                return t.all.filterIsInstance<DashStarted>().first().atMs
            }
            val cooldown = ON.cooldownMsFor(f.stats)
            val shared = firstDash(ON)
            assertTrue(shared in cooldown..cooldown + 16L, "$ability: first dash at $shared")
            assertTrue(firstDash(OFF) < 1000L, "$ability: before T57 the dash was free right after")
            // And the dash spends the special: a Special pressed on every frame after a dash waits for the dash cooldown.
            val t = runDuel(DuelState(f, far), OPEN, 4000L, params = ON) { st ->
                if (st.timeMs == 0L) inputs("a" to Dash()) else inputs("a" to Special)
            }
            val special = t.all.filterIsInstance<SpecialStarted>().first().atMs
            assertTrue(special in cooldown..cooldown + 16L, "$ability: special at $special")
        }
    }

    @Test
    fun liftAndDodgeDoNotTouchTheDashCooldown() {
        for (ability in listOf(Ability.FLY, Ability.HIDE)) {
            assertFalse(ON.sharesDashCooldown(ability))
            val f = gifted("a", Vec2.ZERO, ability).copy(dashCooldownMs = 800L)
            assertTrue(DuelPhysics.canSpecial(f, OPEN, ON), "$ability with the dash recharging")
            val out = DuelPhysics.step(DuelState(f, fighter("b", Vec2(0f, 0.5f))), OPEN, 16L, inputs("a" to Special), ON)
            assertEquals(listOf<DuelEvent>(SpecialStarted("a", ability, 0L)), out.events)
            assertEquals(800L - 16L, out.state.a.dashCooldownMs, "$ability: the dash cooldown just ran on")
            val fresh = DuelPhysics.step(DuelState(f.copy(dashCooldownMs = 0L), fighter("b", Vec2(0f, 0.5f))), OPEN, 16L, inputs("a" to Special), ON)
            assertEquals(0L, fresh.state.a.dashCooldownMs, "$ability: the special starts no dash cooldown")
            assertEquals(DuelPhysics.specialCooldownLeft(f, ON), DuelPhysics.specialWaitLeft(f, ON))
        }
    }

    // -- rule 2: SPEED lengthens the dash ---------------------------------------------------------------------------

    @Test
    fun speedLengthensTheDashNotItsDuration() {
        /** How far a dash (or a ram) of a SPEED [speed] fighter carries it, and when it is over. */
        fun run(speed: Int, params: DuelParams, ability: Ability = Ability.JUMP): Pair<Float, Long> {
            val a = gifted("a", Vec2.ZERO, ability, stats(speed = speed))
            val t = runDuel(DuelState(a, fighter("b", Vec2(0f, 0.5f))), OPEN, 800L, dtMs = 8L, params = params,
                script = atStart("a" to if (ability == Ability.ROLL) Special else Dash()))
            return t.last.a.pos.u to t.states.first { it.timeMs > 0L && it.a.dashMs == 0L }.timeMs
        }
        val plain = (1..10).map { run(it, ON) }
        val rams = (1..10).map { run(it, ON, Ability.ROLL) }
        for (sp in 1..10) {
            assertEquals(ON.dashReachMFor(stats(speed = sp)), plain[sp - 1].first, 1e-4f, "SPEED $sp")
            assertEquals(ON.ramReachMFor(stats(speed = sp)), rams[sp - 1].first, 1e-4f, "SPEED $sp ram")
            assertEquals((ON.dashMs + 7L) / 8L * 8L, plain[sp - 1].second, "SPEED $sp: the dash lasts as long")
            assertEquals((ON.ramDashMs + 7L) / 8L * 8L, rams[sp - 1].second, "SPEED $sp: the ram lasts as long")
        }
        for (i in 1 until 10) {
            assertTrue(plain[i].first > plain[i - 1].first + 1e-3f, "SPEED ${i + 1}: ${plain.map { it.first }}")
            assertTrue(rams[i].first > rams[i - 1].first + 1e-3f, "SPEED ${i + 1} ram: ${rams.map { it.first }}")
        }
        // 0.75 + 0.05 SPEED: SPEED 5 dashes as far as before, SPEED 1 to 10 0.8 to 1.25 times that.
        assertEquals(OFF.dashSpeedMps * OFF.dashMs / 1000f, plain[4].first, 1e-4f)
        assertEquals(1.25f / 0.8f, plain[9].first / plain[0].first, 1e-3f)
        // Off: every SPEED dashes as far.
        assertEquals(1, (1..10).map { run(it, OFF).first }.distinct().size)
        // A stat factor: refused unless positive at SPEED 1 and 10, by name.
        for (make in listOf({ DuelParams(dashReachBase = 0f, dashReachPerSpeed = 0f) }, { DuelParams(dashReachBase = 0.5f, dashReachPerSpeed = -0.1f) })) {
            val e = assertFailsWith<IllegalArgumentException> { make() }
            assertTrue("dashReachBase" in (e.message ?: ""), "${e.message}")
        }
    }

    // -- the numbers' bounds ------------------------------------------------------------------------------------------

    @Test
    fun theNewNumbersRefuseNegativeNaNAndInfinite() {
        // Every number T57 and its review added: negative, NaN and infinite values are refused, by name (an infinite
        // reach is NO_REACH_LIMIT, the one finite "no limit" JSON can write); the edge values are taken.
        fun refused(name: String, make: () -> Any) {
            val e = assertFailsWith<IllegalArgumentException>(name) { make() }
            assertTrue(name in (e.message ?: ""), "$name: ${e.message}")
        }
        val p = DuelParams.DEFAULT
        val t = BotTuning()
        val k = BotSkill.NORMAL
        val inf = Float.POSITIVE_INFINITY
        for (v in listOf(-0.001f, Float.NaN, inf, Float.NEGATIVE_INFINITY)) {
            refused("hitReachUpM") { p.copy(hitReachUpM = v) }
            refused("hitReachDownM") { p.copy(hitReachDownM = v) }
            refused("stompReachUpM") { p.copy(stompReachUpM = v) }
            refused("stompCooldownScale") { p.copy(stompCooldownScale = v) }
            refused("liftCooldownScale") { p.copy(liftCooldownScale = v) }
            refused("ramCooldownScale") { p.copy(ramCooldownScale = v) }
            refused("dodgeCooldownScale") { p.copy(dodgeCooldownScale = v) }
            refused("wobblePerHit") { p.copy(wobblePerHit = v) }
            refused("wobbleKnockScale") { p.copy(wobbleKnockScale = v) }
            refused("wobbleMax") { p.copy(wobbleMax = v) }
            refused("wobbleDecayPerS") { p.copy(wobbleDecayPerS = v) }
            refused("stompScale") { p.copy(stompScale = v) }
            refused("counterKnockScale") { p.copy(counterKnockScale = v) }
            refused("turnStepRadii") { t.copy(turnStepRadii = v) }
            refused("threatSlackRadii") { t.copy(threatSlackRadii = v) }
            refused("ramNearEdgeRadii") { t.copy(ramNearEdgeRadii = v) }
        }
        // Rule 2's factor is judged as a whole (a negative per-SPEED step is fine while the factor stays positive), so
        // only NaN and infinities here; the sign is [speedLengthensTheDashNotItsDuration]'s.
        for (v in listOf(Float.NaN, inf, Float.NEGATIVE_INFINITY)) {
            refused("dashReachBase") { p.copy(dashReachBase = v) }
            refused("dashReachBase") { p.copy(dashReachPerSpeed = v) }
        }
        for (v in listOf(-1.001f, 1.001f, Float.NaN, inf)) refused("ramAlignCos") { t.copy(ramAlignCos = v) }
        for (v in listOf(-0.001f, 1.001f, Float.NaN, inf)) {
            refused("jumpChance") { k.copy(jumpChance = v) }
            refused("reflexChance") { k.copy(reflexChance = v) }
        }
        refused("jumpMarginMs") { t.copy(jumpMarginMs = -1L) }
        // A cooldown scale of 0 would make that special free: refused too.
        refused("liftCooldownScale") { p.copy(liftCooldownScale = 0f) }
        // The edges are taken.
        p.copy(hitReachUpM = 0f, hitReachDownM = 0f, stompReachUpM = 0f)
        p.copy(hitReachUpM = DuelParams.NO_REACH_LIMIT, hitReachDownM = DuelParams.NO_REACH_LIMIT, stompReachUpM = DuelParams.NO_REACH_LIMIT)
        p.copy(wobblePerHit = 0f, wobbleKnockScale = 0f, wobbleMax = 0f, wobbleDecayPerS = 0f)
        t.copy(ramAlignCos = -1f, turnStepRadii = 0f, jumpMarginMs = 0L)
        t.copy(ramAlignCos = 1f)
        k.copy(jumpChance = 0f, reflexChance = 0f)
        k.copy(jumpChance = 1f, reflexChance = 1f)
    }

    // -- the off switches, and the invariants with everything on -----------------------------------------------------

    @Test
    fun theNewRulesSwitchedOffAreThePreviousPhysics() {
        // B9: the pre-T57 numbers with the three rules switched off play the very matches the pre-T57 code played: 24
        // seeded bot matches (four arenas, every ability, three bot levels) and 16 random fights pressing Jump, Dash and
        // Special (a jump on purpose whenever the other dashes at it): the same results, events, decisions and final
        // states, bit for bit, as recorded on commit 207acc6.
        for (i in 0 until GOLDEN_MATCHES) {
            val (r, fp) = goldenMatch(i, PRE_T57_PARAMS, PRE_T57_TUNING)
            val (summary, expected) = GOLDEN_MATCH_PRINTS[i]
            assertEquals(summary, "${r.winner}/${r.scoreA}/${r.scoreB}/${r.rounds}/${r.matchMs}/${r.ringOuts}/${r.timeouts}/${r.hits}/${r.wallSlams}/${r.specials}", "match $i")
            assertEquals(expected, fp, "match $i: the same events, decisions and final state")
        }
        for (i in 0 until GOLDEN_FUZZ) assertEquals(GOLDEN_FUZZ_PRINTS[i], goldenFuzz(i, PRE_T57_PARAMS), "fuzz $i")
        // The off positions survive JSON, and NO_REACH_LIMIT survives scaling to the floor.
        assertEquals(PRE_T57_PARAMS, Json.decodeFromString(DuelParams.serializer(), Json.encodeToString(DuelParams.serializer(), PRE_T57_PARAMS)))
        val floor = PRE_T57_PARAMS.scaled(SurfaceProfile.FLOOR)
        assertEquals(DuelParams.NO_REACH_LIMIT to DuelParams.NO_REACH_LIMIT, floor.hitReachUpM to floor.hitReachDownM)
        assertFalse(PRE_T57_PARAMS.reachLimited)
        assertTrue(ON.reachLimited)
        assertEquals(ON.hitReachUpM * SurfaceProfile.FLOOR.scale, ON.scaled(SurfaceProfile.FLOOR).hitReachUpM, 1e-6f)
        assertEquals(ON.hitReachUpM, ON.scaled(SurfaceProfile.TABLE, 2f).hitReachUpM, "a heights, not the character's size")
    }

    @Test
    fun fuzzWithTheNewRulesKeepsTheInvariants() {
        // Random play with every rule on (ON, and the defaults): every ability pair, pressing Jump (on purpose when the
        // other dashes at it), Dash and Special. Nobody inside what blocks, every exit from the table is one ring-out,
        // grounded fighters never overlap, nothing turns NaN or negative; and the rules happen (dashes that miss by height,
        // specials waiting for the shared dash).
        val counts = HashMap<String, Int>()
        fun count(k: String) { counts[k] = (counts[k] ?: 0) + 1 }
        // On T42's fuzz table and (the T57 review) on CUP_AND_BOOK.
        for (level in listOf(RULES_TABLE, DuelArenas.CUP_AND_BOOK)) for (params in listOf(ON, DuelParams.DEFAULT)) for (i in 0 until GOLDEN_FUZZ) {
            goldenFuzz(i, params, steps = 1500, level = level) { prev, out ->
                val s = out.state
                for (e in out.events) {
                    count(e::class.simpleName!!)
                    if (level !== RULES_TABLE) count("cup-and-book ${e::class.simpleName}")
                }
                val (a, b) = s.a to s.b
                val overlap = a.pos.distanceTo(b.pos) < 2 * R
                if (overlap && a.dashMs > 0L && !params.inReach(a.body.state.h, b.body.state.h)) count("miss by height")
                if (overlap && b.dashMs > 0L && !params.inReach(b.body.state.h, a.body.state.h)) count("miss by height")
                for (f in listOf(a, b)) {
                    val st = f.body.state
                    val before = prev[f.id]!!
                    assertTrue(listOf(st.pos.u, st.pos.v, st.h, f.knock.u, f.knock.v, f.wobble, f.body.verticalVelocity).all { it.isFinite() }, "non-finite $f")
                    assertTrue(listOf(f.stunMs, f.dashMs, f.dashCooldownMs, f.specialMs, f.specialCooldownMs, f.counterMs).all { it >= 0L }, "negative timer: $f")
                    val inside = level.objects.firstOrNull {
                        Physics.blocks(it, st.h) && (it.footprint.contains(st.pos) || distanceToPolygon(st.pos, it.footprint) < st.radiusM - 0.001f)
                    }
                    assertEquals(null, inside?.id, "${f.id} overlaps ${inside?.id} at ${s.timeMs} ms: $f")
                    val boundary = level.boundary!!
                    val rang = out.events.of<RingOut>(f.id)
                    val left = boundary.contains(before.pos) && !boundary.contains(st.pos)
                    if (left) assertEquals(1, rang.size, "${f.id} left without a RingOut at ${s.timeMs} ms")
                    if (rang.isNotEmpty()) assertTrue(left && rang.size == 1, "${f.id} RingOut without leaving at ${s.timeMs} ms")
                    if (st.mode == Mode.PAUSED) assertEquals(before, f, "a paused fighter is frozen")
                    if (params.sharesDashCooldown(f.ability) && f.dashCooldownMs > 0L && f.specialCooldownMs == 0L) count("special waits for the dash")
                }
                val ground = setOf(Mode.IDLE, Mode.WALKING)
                if (a.body.state.mode in ground && b.body.state.mode in ground) {
                    assertTrue(a.pos.distanceTo(b.pos) >= 2 * R - 1e-4f, "overlap at ${s.timeMs} ms")
                }
            }
        }
        println("fuzzWithTheNewRulesKeepsTheInvariants: $counts")
        val wanted = listOf("Hit", "Clash", "RingOut", "WallSlam", "SpecialStarted", "Missed", "miss by height", "special waits for the dash")
        for (k in wanted + listOf("Hit", "RingOut", "SpecialStarted", "Missed").map { "cup-and-book $it" }) {
            assertTrue((counts[k] ?: 0) > 0, "the fuzz never produced $k: $counts")
        }
    }
}

/** What the pre-T57 code (207acc6) played in [goldenMatch]: the result's numbers and the fingerprint. */
private val GOLDEN_MATCH_PRINTS: List<Pair<String, Long>> = listOf(
    "b/1/2/3/43528/3/0/17/0/7" to 4104114877862284986L,
    "a/2/0/2/25736/2/0/9/3/0" to 803484369219129656L,
    "a/2/0/2/13904/2/0/5/0/2" to 2344913772151540646L,
    "a/2/1/3/47432/3/0/16/0/2" to 8226424473228531809L,
    "b/1/2/3/48808/3/0/21/0/3" to -8061239518130554430L,
    "b/1/2/3/41584/3/0/20/3/3" to -3903296719962105549L,
    "b/0/2/2/17792/2/0/5/0/3" to -4392538614701458077L,
    "a/2/0/2/37976/2/0/14/0/3" to -1150066588972447254L,
    "b/0/2/2/17232/2/0/7/0/2" to 2010226878268495641L,
    "b/1/2/3/54944/3/0/22/6/1" to 7255160481749189614L,
    "a/2/0/2/13568/2/0/5/0/2" to -8042886886459718436L,
    "a/2/0/2/43376/2/0/17/0/0" to 805544186975406041L,
    "b/0/2/2/37376/2/0/16/0/2" to -3675649248004583425L,
    "b/0/2/2/25384/2/0/11/1/1" to -422833270780347528L,
    "a/2/0/2/19976/2/0/6/0/3" to 2644732709347045713L,
    "b/1/2/3/50960/3/0/18/0/3" to 8464969488671206040L,
    "b/0/2/2/26296/2/0/11/0/3" to -1843492357376715319L,
    "a/2/0/2/26736/2/0/10/4/1" to 2365757805932275053L,
    "a/2/0/3/22936/3/0/8/0/4" to -1518458474273321213L,
    "a/2/0/2/34760/2/0/14/0/0" to 5078052890878914163L,
    "b/0/2/2/30752/2/0/13/0/2" to -1558446156774448863L,
    "b/0/2/2/13680/2/0/6/0/2" to 5813716888746225602L,
    "b/1/2/3/20904/3/0/6/0/4" to -3242851005669681350L,
    "a/2/1/3/48432/3/0/17/0/6" to 357832917641488682L,
)

/** What the pre-T57 code (207acc6) played in [goldenFuzz]. */
private val GOLDEN_FUZZ_PRINTS: List<Long> = listOf(
    -6974276851308326270L, -6635351258794651848L, -4895846785212161510L, 5971587496192212551L, -5646407542656427990L,
    2496423501188037303L, 7713924083388679278L, -3521534083669872603L, -3384636924156685606L, 4385623175419765128L,
    -4075022968635638784L, -3442992311089249777L, -6304245086919618032L, -2634897638539240266L, 7155000693449852703L,
    -7177238307364566135L,
)

// ---------------------------------------------------------------------------------------------------------------------
// The pre-T57 game, written out number by number (T48's defaults), and a fingerprint of seeded matches played with it:
// what `theNewRulesSwitchedOffAreThePreviousPhysics` compares against values recorded on the pre-T57 code (207acc6).
// ---------------------------------------------------------------------------------------------------------------------

/** Every DuelParams number as T48 left it (commit 207acc6), and T57's rules switched off. */
internal val PRE_T57_PARAMS = DuelParams(
    duelSpeedScale = 2f, speedBase = 0.50f, speedPerPoint = 0.10f, rollSpeedScale = 1f,
    dashMs = 250, dashSpeedMps = 0.30f, dashCooldownMs = 2500, cooldownBase = 1.15f, cooldownPerAgility = 0.03f,
    knockSpeedMps = 0.25f, powerBase = 0.60f, powerPerPoint = 0.08f, guardBase = 0.60f, guardPerPoint = 0.08f,
    fromAboveScale = 1.25f, fromAboveMinM = 0.01f, knockTauMs = 180, knockStunMps = 0.02f, knockStopMps = 0.003f,
    hitStunMs = 300, stunBase = 1.30f, stunPerAgility = 0.06f, wallSlamStunMs = 400, wallSlamMinMps = 0.02f,
    subStepMs = 8, specialCooldownMs = 9000, stompRadiusRadii = 3f, stompScale = 0.85f, liftMs = 1200,
    liftSpeedScale = 1.5f, liftHeightRadii = 0.5f, ramDashScale = 1.3f, ramKnockScale = 1.1f, dodgeMs = 900,
    counterMs = 1500, counterKnockScale = 2.0f, wobblePerHit = 1f, wobbleKnockScale = 0.7f, wobbleMax = 4f,
    stompCooldownScale = 1f, liftCooldownScale = 1f, ramCooldownScale = 1f, dodgeCooldownScale = 1f,
    wobbleDecayPerS = 0f,
    specialsShareDashCooldown = false, dashReachBase = 1f, dashReachPerSpeed = 0f,
    hitReachUpM = DuelParams.NO_REACH_LIMIT, hitReachDownM = DuelParams.NO_REACH_LIMIT, stompReachUpM = DuelParams.NO_REACH_LIMIT,
)

/** Every BotTuning number as T48 left it (commit 207acc6). */
internal val PRE_T57_TUNING = BotTuning(
    tickMs = 48, edgeMarginRadii = 2f, dashEndMarginRadii = 1f, inPathLateralRadii = 1f, inPathReachShare = 0.5f,
    standoffGapRadii = 0.6f, clearanceRadii = 0.6f, detourStepCos = 0.70710677f, detourStepSin = 0.70710677f,
    threatSlackRadii = 0.5f, sidestepRadii = 2f, stompReachShare = 0.8f, ramNearEdgeRadii = 8f, ramRoomRadii = 3f,
    walkCost = 0.5f, switchCostRadii = 1f, highGroundBonusRadii = 3f, leadShare = 1f, knockLeadShare = 0.5f,
    lineOfSightRadii = 0.8f, sameDirectionCos = 0.98f, blockedPushRadii = 10f,
    easy = BotSkill(
        thinkMs = 384, thinkJitterTicks = 4, dashRangeShare = 0.7f, alignCos = -1f, aimErrorShare = 0.35f,
        wrongWayChance = 0.25f, wanderChance = 0.2f, wanderMs = 1200, leadsAim = false, weighsPush = false,
        usesSpecial = false, nearEdgeRadii = 0f, sidesteps = false, counters = false, safeRamsOnly = false,
    ),
    normal = BotSkill(
        thinkMs = 144, thinkJitterTicks = 2, dashRangeShare = 0.8f, alignCos = 0.8660254f, aimErrorShare = 0.1f,
        wrongWayChance = 0f, wanderChance = 0f, wanderMs = 0, leadsAim = false, weighsPush = false,
        usesSpecial = true, nearEdgeRadii = 20f, sidesteps = false, counters = false, safeRamsOnly = false,
    ),
    hard = BotSkill(
        thinkMs = 48, thinkJitterTicks = 3, dashRangeShare = 0.85f, alignCos = 0.8660254f, aimErrorShare = 0f,
        wrongWayChance = 0f, wanderChance = 0f, wanderMs = 0, leadsAim = true, weighsPush = true,
        usesSpecial = true, nearEdgeRadii = 8f, sidesteps = true, counters = true, safeRamsOnly = true,
        sidestepEdgeRadii = 4f,
    ),
)

/** The archetypes as T48 left them (`FighterStats.defaultFor`; T57 changed FLY's from 3/7/3/7 to 4/6/4/6). */
internal val PRE_T57_ARCHETYPE: Map<Ability, FighterStats> = mapOf(
    Ability.JUMP to FighterStats(5, 5, 5, 5), Ability.FLY to FighterStats(3, 7, 3, 7),
    Ability.ROLL to FighterStats(7, 6, 5, 2), Ability.HIDE to FighterStats(4, 5, 4, 7),
)

/** FNV-1a style fold of longs: a fingerprint that is the same on every run and platform (no identity hashes). */
internal class Fingerprint {
    var value: Long = -3750763034362895579L
        private set

    fun add(x: Long): Fingerprint {
        value = (value xor x) * 1099511628211L
        return this
    }

    fun add(x: Int) = add(x.toLong())
    fun add(x: Boolean) = add(if (x) 1L else 0L)
    fun add(x: Float) = add(x.toRawBits().toLong())
    fun add(s: String?) = if (s == null) add(-1L) else s.fold(add(s.length)) { f, c -> f.add(c.code) }
    fun add(p: Vec2?) = if (p == null) add(-2L) else add(p.u).add(p.v)

    fun add(f: DuelFighter): Fingerprint {
        val s = f.body.state
        add(f.id).add(s.pos).add(s.h).add(s.target).add(s.mode.ordinal).add(s.ability.ordinal).add(s.radiusM).add(s.facing)
        add(f.body.verticalVelocity).add(f.body.modeBeforePause?.ordinal ?: -1).add(f.body.blocked)
        add(f.stats.power).add(f.stats.speed).add(f.stats.guard).add(f.stats.agility)
        add(f.knock).add(f.stunMs).add(f.dashMs).add(f.dashDir).add(f.dashCooldownMs).add(f.ability?.ordinal ?: -1)
        return add(f.specialMs).add(f.specialCooldownMs).add(f.stomping).add(f.counterMs).add(f.dashKind.ordinal).add(f.wobble)
    }

    fun add(m: BotMind?): Fingerprint {
        if (m == null) return add(-3L)
        return add(m.level.ordinal).add(m.rng).add(m.nextThinkMs).add(m.wander).add(m.wanderUntilMs).add(m.push)
    }

    fun add(e: DuelEvent): Fingerprint = when (e) {
        is DuelEvent.DashStarted -> add(0).add(e.id).add(e.atMs)
        is DuelEvent.SpecialStarted -> add(1).add(e.id).add(e.ability.ordinal).add(e.atMs)
        is DuelEvent.Hit -> add(2).add(e.attacker).add(e.victim).add(e.speedMps).add(e.fromAbove).add(e.atMs)
        is DuelEvent.Clash -> add(3).add(e.a).add(e.b).add(e.atMs)
        is DuelEvent.Missed -> add(6).add(e.attacker).add(e.victim).add(e.stomp).add(e.atMs)
        is DuelEvent.WallSlam -> add(4).add(e.id).add(e.objectId).add(e.atMs)
        is DuelEvent.RingOut -> add(5).add(e.id).add(e.atMs)
    }

    fun add(e: MatchEvent): Fingerprint = when (e) {
        is MatchEvent.RoundStarted -> add(10).add(e.round).add(e.atMs)
        is MatchEvent.Point -> add(11).add(e.winner).add(e.reason.ordinal).add(e.atMs)
        is MatchEvent.MatchOver -> add(12).add(e.winner).add(e.atMs)
        is MatchEvent.Fight -> add(13).add(e.event)
    }

    fun add(i: DuelInput): Fingerprint = when (i) {
        DuelInput.None -> add(20)
        is DuelInput.MoveTo -> add(21).add(i.target)
        is DuelInput.Dash -> add(22).add(i.dir)
        DuelInput.Jump -> add(23)
        DuelInput.Special -> add(24)
    }

    fun add(s: DuelState): Fingerprint = add(s.timeMs).add(s.a).add(s.b)

    fun add(r: RunnerState): Fingerprint {
        val m = r.match
        add(m.duel).add(m.phase.ordinal).add(m.phaseLeftMs).add(m.round).add(m.scoreA).add(m.scoreB).add(m.winner)
        add(m.startA).add(m.startB).add(m.lastPoint?.round ?: -1).add(m.lastPoint?.winner).add(m.lastPoint?.reason?.ordinal ?: -1)
        return add(r.botA).add(r.botB)
    }
}

/** How many seeded bot matches [goldenMatch] plays, and how many physics fuzz runs [goldenFuzz]. */
internal const val GOLDEN_MATCHES = 24
internal const val GOLDEN_FUZZ = 16

private val GOLDEN_LEVELS = listOf(
    BotLevel.NORMAL to BotLevel.NORMAL, BotLevel.HARD to BotLevel.HARD, BotLevel.EASY to BotLevel.HARD, BotLevel.HARD to BotLevel.NORMAL,
)

/**
 * Seeded bot match [i] of the sample (the four matrix arenas, every ability on each side, archetype and specialist stats,
 * four level pairs), stepped at 33 ms to RESULT: its result and a fingerprint of every event and of the final state.
 */
internal fun goldenMatch(i: Int, params: DuelParams, tuning: BotTuning): Pair<SimResult, Long> {
    val arena = DuelArenas.MATRIX.values.toList()[i % 4]
    val xa = Ability.entries[i % 4]
    val xb = Ability.entries[(i / 4 + i) % 4]
    val sa = if (i % 3 == 0) PRE_T57_ARCHETYPE.getValue(xa) else FighterStats(8, 4, 4, 4)
    val sb = if (i % 5 == 0) FighterStats(4, 8, 4, 4) else PRE_T57_ARCHETYPE.getValue(xb)
    val (la, lb) = GOLDEN_LEVELS[(i / 2) % 4]
    val a = Contestant("a", sa, xa)
    val b = Contestant("b", sb, xb)
    val seed = 500L + i
    val rules = DuelRules()
    val marks = DuelArenas.marks(arena)
    var state = RunnerState(DuelMatch.start(a, b, arena, if (i % 2 == 0) marks else marks.second to marks.first, rules, params), DuelBot.mind(la, 2L * seed), DuelBot.mind(lb, 2L * seed + 1L))
    val fp = Fingerprint()
    var hits = 0
    var ringOuts = 0
    var timeouts = 0
    var replays = 0
    var slams = 0
    var specials = 0
    while (state.match.phase != MatchPhase.RESULT && state.match.duel.timeMs < 600_000L) {
        val out = DuelRunner.step(state, arena, 33L, rules = rules, params = params, tuning = tuning)
        for (e in out.events) {
            fp.add(e)
            when (e) {
                is MatchEvent.Point -> {
                    if (e.reason == PointReason.RING_OUT) ringOuts++ else timeouts++
                    if (e.winner == null) replays++
                }
                is MatchEvent.Fight -> when (e.event) {
                    is DuelEvent.Hit, is DuelEvent.Clash -> hits++
                    is DuelEvent.WallSlam -> slams++
                    is DuelEvent.SpecialStarted -> specials++
                    else -> Unit
                }
                else -> Unit
            }
        }
        for (d in out.decisions) fp.add(d.id).add(d.atMs).add(d.input)
        state = out.state
    }
    val m = state.match
    val r = SimResult(m.winner, m.scoreA, m.scoreB, m.round, m.phase == MatchPhase.RESULT, m.duel.timeMs, ringOuts, timeouts, replays, hits, slams, specials)
    return r to fp.add(state).value
}

/** T42's fuzz table (a cup, a bottle, a book, a low lid, a tall box, an irregular edge). */
internal val RULES_TABLE = Level(
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

/**
 * Random play [i] on [RULES_TABLE] (ability pair i of the 16, random balanced stats, frames of 16..50 ms, host pauses),
 * pressing Jump, Dash and Special at random and on purpose: a jump when the other dashes at it, a special or a dash
 * when close. [check] sees every step. Returns a fingerprint of every event and of the final state.
 */
internal fun goldenFuzz(i: Int, params: DuelParams, steps: Int = 1500, level: Level = RULES_TABLE, check: (DuelState, DuelStep) -> Unit = { _, _ -> }): Long {
    val rnd = Random(900 + i)
    fun randomStats(): FighterStats {
        val s = IntArray(4) { FighterStats.MIN }
        repeat(FighterStats.BUDGET - 4 * FighterStats.MIN) {
            var k = rnd.nextInt(4)
            while (s[k] >= FighterStats.MAX) k = (k + 1) % 4
            s[k]++
        }
        return FighterStats(s[0], s[1], s[2], s[3])
    }
    val (xa, xb) = Ability.entries[i % 4] to Ability.entries[(i / 4) % 4]
    var state = DuelState(
        fighter("a", Vec2(-0.1f, -0.05f), randomStats(), ability = xa).copy(ability = xa),
        fighter("b", Vec2(0.1f, -0.02f), randomStats(), facing = Vec2(-1f, 0f), ability = xb).copy(ability = xb),
    )
    val fp = Fingerprint()
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
                roll < 3 -> DuelInput.Jump
                roll < 6 -> DuelInput.Special
                roll < 7 -> DuelInput.Dash(if (rnd.nextBoolean()) null else Vec2(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f))
                other.dashMs > 0L && toOther.length < 0.12f && roll < 40 -> DuelInput.Jump
                plan[k] == 0 && toOther.length < 0.1f && roll < 25 && DuelPhysics.canSpecial(me, level, params) -> DuelInput.Special
                plan[k] == 0 && toOther.length < 0.12f && DuelPhysics.canDash(me, level, params) -> DuelInput.Dash(toOther)
                plan[k] == 0 && roll < 40 -> DuelInput.MoveTo(other.pos)
                plan[k] == 1 && roll < 15 -> DuelInput.MoveTo(wander[k])
                else -> null
            }
            if (input != null) inputs[me.id] = input
        }
        val prev = state
        val out = DuelPhysics.step(state, level, rnd.nextLong(16L, 51L), inputs, params)
        check(prev, out)
        for (e in out.events) fp.add(e)
        state = out.state
    }
    return fp.add(state).value
}
