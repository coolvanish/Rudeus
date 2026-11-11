package kz.rudy.ar.core.duel

import kotlinx.serialization.json.Json
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.duel.DuelEvent.DashStarted
import kz.rudy.ar.core.duel.DuelEvent.Hit
import kz.rudy.ar.core.duel.DuelEvent.RingOut
import kz.rudy.ar.core.duel.DuelEvent.SpecialStarted
import kz.rudy.ar.core.duel.DuelEvent.WallSlam
import kz.rudy.ar.core.duel.DuelInput.Dash
import kz.rudy.ar.core.duel.DuelInput.Jump
import kz.rudy.ar.core.duel.DuelInput.MoveTo
import kz.rudy.ar.core.duel.DuelInput.Special
import kz.rudy.ar.core.duel.MatchEvent.Fight
import kz.rudy.ar.core.duel.MatchEvent.MatchOver
import kz.rudy.ar.core.duel.MatchEvent.Point
import kz.rudy.ar.core.duel.MatchEvent.RoundStarted
import kz.rudy.ar.core.physics.Physics
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 28 x 40 cm table, edges at u = +-0.14: a POWER 10 dash knocks a GUARD 1 fighter off it from its mark. */
private val NARROW = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.28f, 0.4f))

/** 40 x 40 cm table, edges at +-0.2. */
private val SQUARE = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.4f, 0.4f))

private val LEFT = Vec2(-0.06f, 0f)
private val RIGHT = Vec2(0.06f, 0f)
private val MARKS = LEFT to RIGHT

private val RED = Contestant("red", FighterStats(power = 10, speed = 4, guard = 3, agility = 3), Ability.JUMP)
private val BLUE = Contestant("blue", FighterStats(power = 4, speed = 6, guard = 1, agility = 9), Ability.HIDE)

private val RULES = DuelRules()

/** T42's fuzz table: a cup, a bottle, a book, a low lid, a tall box, an irregular edge. */
private val FUZZ_TABLE = Level(
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
private val FUZZ_MARKS = DuelArena.marks(FUZZ_TABLE, R, 0.16f)!!

/** A rule length as the match runs it: whole 8 ms sub-steps. */
private fun aligned(ms: Long) = (ms + 7L) / 8L * 8L

private fun m(p: Vec2) = Vec2(-p.u, p.v)

/** Match states after every step (index 0 = the start) and each step's events (index i produced states[i]). */
private class MatchTrace(val states: List<DuelMatchState>, val events: List<List<MatchEvent>>) {
    val all: List<MatchEvent> get() = events.flatten()
    val last: DuelMatchState get() = states.last()
    val points: List<Point> get() = all.filterIsInstance<Point>()
    fun fight(): List<DuelEvent> = all.filterIsInstance<Fight>().map { it.event }
}

/**
 * Plays [start] until RESULT or [maxMs] of match time. Frames come from [frame], cut so that every FIGHT start and every
 * instant of [script] (by round: ms since its FIGHT began -> inputs) is a frame boundary: any frame source gives every
 * input at the same simulated time, which is what "any split of the same time" means.
 */
private fun play(
    start: DuelMatchState,
    arena: Level,
    rules: DuelRules = RULES,
    maxMs: Long = 400_000L,
    script: (round: Int) -> Map<Long, Map<String, DuelInput>> = { emptyMap() },
    frame: () -> Long = { 16L },
): MatchTrace {
    val states = arrayListOf(start)
    val events = arrayListOf<List<MatchEvent>>(emptyList())
    val roundLen = aligned(rules.roundMs)
    var s = start
    while (s.phase != MatchPhase.RESULT && s.duel.timeMs < maxMs) {
        var dt = frame()
        var inputs: Map<String, DuelInput> = emptyMap()
        if (s.phase == MatchPhase.FIGHT) {
            val since = roundLen - s.phaseLeftMs
            val plan = script(s.round)
            inputs = plan[since] ?: emptyMap()
            plan.keys.filter { it > since }.minOrNull()?.let { dt = minOf(dt, it - since) }
        } else {
            dt = minOf(dt, s.phaseLeftMs)
        }
        dt = minOf(dt, maxMs - s.duel.timeMs)
        val out = DuelMatch.step(s, arena, dt, inputs, rules = rules)
        assertEquals(s.duel.timeMs + dt, out.state.duel.timeMs, "the clock runs by exactly dt")
        s = out.state
        states += s
        events += out.events
    }
    return MatchTrace(states, events)
}

/** Round script: red dashes at blue the moment the fight starts. */
private val redDashes: (Int) -> Map<Long, Map<String, DuelInput>> = { mapOf(0L to inputs("red" to Dash())) }

class DuelMatchTest {

    @Test
    fun countdownIgnoresInputThenTheRoundStarts() {
        val start = DuelMatch.start(RED, BLUE, NARROW, MARKS)
        assertEquals(MatchPhase.COUNTDOWN, start.phase)
        assertEquals(3000L, start.phaseLeftMs)
        assertEquals(1, start.round)
        assertEquals(0L, start.duel.timeMs)
        // On the marks, facing each other, standing, with their abilities.
        assertEquals(LEFT, start.duel.a.pos)
        assertEquals(RIGHT, start.duel.b.pos)
        assertEquals(Vec2(1f, 0f), start.duel.a.body.state.facing)
        assertEquals(Vec2(-1f, 0f), start.duel.b.body.state.facing)
        assertEquals(Ability.JUMP to Ability.HIDE, start.duel.a.ability to start.duel.b.ability)
        assertEquals(Ability.HIDE, start.duel.b.body.state.ability)

        val noise = listOf(MoveTo(Vec2(0.1f, 0.1f)), Dash(), Jump, Special)
        var s = start
        var i = 0
        while (s.phase == MatchPhase.COUNTDOWN) {
            val t = s.duel.timeMs
            val out = DuelMatch.step(s, NARROW, 16L, inputs("red" to noise[i % 4], "blue" to noise[(i + 1) % 4]))
            i++
            if (out.state.phase == MatchPhase.COUNTDOWN) {
                assertTrue(out.events.isEmpty(), "at $t ms: ${out.events}")
                assertEquals(start.duel.a, out.state.duel.a, "red moved in the countdown at $t ms")
                assertEquals(start.duel.b, out.state.duel.b)
                assertEquals(3000L - out.state.duel.timeMs, out.state.phaseLeftMs)
            } else {
                // The frame 2992..3008 crosses the start: 8 ms of fight; its Dash was given in the countdown, so ignored.
                assertEquals(2992L, t)
                assertEquals(listOf<MatchEvent>(RoundStarted(1, 3000L)), out.events)
                assertEquals(MatchPhase.FIGHT, out.state.phase)
                assertEquals(60_000L - 8L, out.state.phaseLeftMs)
                assertEquals(0L, out.state.duel.a.dashMs)
            }
            s = out.state
        }
        // In the fight inputs work.
        val moved = DuelMatch.step(s, NARROW, 16L, inputs("red" to MoveTo(Vec2(-0.03f, 0.05f)), "blue" to Dash()))
        assertEquals(Vec2(-0.03f, 0.05f), moved.state.duel.a.body.state.target)
        assertEquals(listOf<MatchEvent>(Fight(DashStarted("blue", 3008L))), moved.events)
    }

    @Test
    fun ringOutScoresOnceAndBothReturnToTheirMarks() {
        val start = DuelMatch.start(RED, BLUE, NARROW, MARKS)
        // Round 1 only (red dashes blue off), then idle until the next countdown is under way.
        val t = play(start, NARROW, maxMs = 7_000L, script = { r -> if (r == 1) redDashes(r) else emptyMap() })
        assertEquals(listOf(Point("red", PointReason.RING_OUT, t.points.single().atMs)), t.points, "${t.all}")
        val at = t.points.single().atMs
        val ring = t.fight().filterIsInstance<RingOut>().first()
        assertEquals("blue", ring.id)
        assertEquals(ring.atMs, at, "the point is the ring-out's sub-step")
        val k = t.events.indexOfFirst { e -> e.any { it is Point } }
        val pointState = t.states[k]
        assertEquals(MatchPhase.POINT, pointState.phase)
        assertEquals(1 to 0, pointState.scoreA to pointState.scoreB)
        assertEquals(1, pointState.scoreOf("red"))
        assertEquals(PointResult(1, "red", PointReason.RING_OUT), pointState.lastPoint)
        assertEquals(aligned(RULES.pointPauseMs) - (pointState.duel.timeMs - at), pointState.phaseLeftMs)
        // During POINT the fall plays out (blue goes down past the edge), red stays put, and nothing else scores.
        val pause = t.states.filter { it.phase == MatchPhase.POINT }
        assertTrue(pause.any { it.duel.b.body.state.mode == Mode.FALLING && it.duel.b.body.state.h < 0f }, "the fall is shown")
        assertTrue(pause.all { it.duel.a.dashMs == 0L && it.duel.a.body.state.target == null })
        // After the pause: round 2's countdown, both rebuilt on their marks exactly as at the start.
        val next = t.states.first { it.round == 2 }
        assertEquals(MatchPhase.COUNTDOWN, next.phase)
        assertEquals(at + aligned(RULES.pointPauseMs), next.duel.timeMs - (aligned(RULES.countdownMs) - next.phaseLeftMs))
        assertEquals(start.duel.a, next.duel.a)
        assertEquals(start.duel.b, next.duel.b)
        assertEquals(1 to 0, next.scoreA to next.scoreB)
        assertEquals(1, t.points.size)
        assertEquals(listOf(RoundStarted(1, 3000L)), t.all.filterIsInstance<RoundStarted>())
    }

    @Test
    fun bothOutInTheSameSubStepReplaysTheRound() {
        // T42's mirrored POWER 10 clash on a 20 cm table: both fly off in the same sub-step.
        val tiny = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.2f, 0.3f), spawn = Vec2(0f, 0.1f))
        val st = FighterStats(10, 1, 1, 8)
        val start = DuelMatch.start(Contestant("a", st, Ability.JUMP), Contestant("b", st, Ability.JUMP), tiny, Vec2(-0.04f, 0f) to Vec2(0.04f, 0f))
        val t = play(start, tiny, maxMs = 9_000L, script = { r -> if (r == 1) mapOf(0L to inputs("a" to Dash(), "b" to Dash())) else emptyMap() })
        val rings = t.fight().filterIsInstance<RingOut>()
        assertEquals(listOf("a", "b"), rings.map { it.id })
        assertEquals(rings[0].atMs, rings[1].atMs)
        assertEquals(listOf(Point(null, PointReason.RING_OUT, rings[0].atMs)), t.points)
        val next = t.states.first { it.round == 2 }
        assertEquals(0 to 0, next.scoreA to next.scoreB)
        assertEquals(PointResult(1, null, PointReason.RING_OUT), next.lastPoint)
        assertEquals(start.duel.a, next.duel.a)
        assertEquals(start.duel.b, next.duel.b)
    }

    @Test
    fun theEarlierRingOutLosesAtAnyFrameRateAndInEitherSlot() {
        // A clash on the 20 cm table: y, a little weaker and 0.6 mm closer to its edge, leaves the table two sub-steps
        // before x does (376 and 392 ms), so in a 33 ms frame both ring-outs arrive in the same DuelPhysics call. The
        // earlier one loses, whatever the frames or the slots. (x's mark was -0.04 before T48's stat factors moved the
        // two ring-outs 40 ms apart; -0.0414 puts them back two sub-steps apart in one call.)
        val tiny = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.2f, 0.3f), spawn = Vec2(0f, 0.1f))
        val x = Contestant("x", FighterStats(10, 1, 1, 8), Ability.JUMP)
        val y = Contestant("y", FighterStats(8, 1, 2, 9), Ability.JUMP)
        val marks = Vec2(-0.0414f, 0f) to Vec2(0.042f, 0f)

        // The physics alone (no match): both fly off, y first, 16 ms apart, in one 33 ms call.
        val raw = DuelMatch.start(x, y, tiny, marks).duel
        val free = runDuel(raw, tiny, 1500L, dtMs = 33L, script = atStart("x" to Dash(), "y" to Dash()))
        val rings = free.all.filterIsInstance<RingOut>()
        assertEquals(listOf("y", "x"), rings.map { it.id }, "both ring out: ${free.all}")
        val (first, second) = rings
        assertEquals(16L, second.atMs - first.atMs, "$rings")
        assertTrue(free.events.any { e -> e.filterIsInstance<RingOut>().size == 2 }, "one 33 ms call carries both: ${free.events.filter { it.isNotEmpty() }}")
        val loser = "y"
        val winner = "x"

        val script = { r: Int -> if (r == 1) mapOf(0L to inputs("x" to Dash(), "y" to Dash())) else emptyMap() }
        val rnd = Random(11)
        val frames = listOf<() -> Long>({ 16L }, { 33L }, { 50L }, { 5L + rnd.nextLong(46L) })
        val results = frames.flatMap { frame ->
            listOf(
                play(DuelMatch.start(x, y, tiny, marks), tiny, maxMs = 5_000L, script = script, frame = frame),
                play(DuelMatch.start(y, x, tiny, marks.second to marks.first), tiny, maxMs = 5_000L, script = script, frame = frame),
            )
        }
        for (t in results) {
            assertEquals(Point(winner, PointReason.RING_OUT, first.atMs + 3000L), t.points.first(), "${t.points}")
            // The winner, which the physics alone would carry off 16 ms later, is frozen on the table in POINT instead.
            val falls = t.fight().filterIsInstance<RingOut>().map { it.id to it.atMs }
            assertEquals(listOf(loser to first.atMs + 3000L), falls)
            val afterRound = t.states.first { it.round == 2 }
            assertEquals(1 to 0, afterRound.scoreOf(winner) to afterRound.scoreOf(loser))
            assertEquals(1, t.points.count { it.atMs < afterRound.duel.timeMs })
        }
        // Same events everywhere (the slot swap only changes which side scoreA counts).
        for (t in results.drop(1)) assertEquals(results[0].all, t.all)
    }

    @Test
    fun theWinnerCannotFallDuringPoint() {
        // The case above: at the point x is still sliding toward its edge, 16 ms from falling off. In POINT it is frozen
        // where it stands (no motion, no fall, no event), and stands on its mark when the phase ends.
        val tiny = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.2f, 0.3f), spawn = Vec2(0f, 0.1f))
        val x = Contestant("x", FighterStats(10, 1, 1, 8), Ability.JUMP)
        val y = Contestant("y", FighterStats(8, 1, 2, 9), Ability.JUMP)
        val marks = Vec2(-0.04f, 0f) to Vec2(0.042f, 0f)
        val t = play(DuelMatch.start(x, y, tiny, marks), tiny, maxMs = 5_000L, script = { r ->
            if (r == 1) mapOf(0L to inputs("x" to Dash(), "y" to Dash())) else emptyMap()
        })
        val k = t.events.indexOfFirst { e -> e.any { it is Point } }
        val atPoint = t.states[k].duel["x"]!!
        assertTrue(atPoint.knock.length > 0.05f && tiny.boundary!!.contains(atPoint.pos), "still flying, still on the table: $atPoint")
        val pause = t.states.filter { it.phase == MatchPhase.POINT }
        assertTrue(pause.size > 50)
        for (s in pause) assertEquals(atPoint, s.duel["x"], "frozen at ${s.duel.timeMs} ms")
        val all = t.all
        assertEquals(emptyList(), all.drop(all.indexOfFirst { it is Point } + 1), "nothing comes out of POINT")
        assertEquals(marks.first, t.states.first { it.round == 2 }.duel["x"]!!.pos)
    }

    @Test
    fun theLoserReappearsOnItsMarkNeverOnTheSpawn() {
        // A cup stands on the level's spawn point, where Physics would respawn the loser. In POINT the loser falls, then
        // stands on its own mark, frozen; never on the spawn, never in the cup. The winner does not move. (Both walk up
        // first, so red's dash ends away from blue's mark: a winner standing over it would keep blue down to the end.)
        val onSpawn = cup("cup", Vec2(-0.05f, -0.13f), radius = 0.03f)
        val arena = NARROW.copy(objects = listOf(onSpawn), spawn = Vec2(-0.05f, -0.13f))
        val start = DuelMatch.start(RED, BLUE, arena, MARKS)
        val t = play(start, arena, maxMs = 8_000L, script = { r ->
            if (r == 1) {
                mapOf(0L to inputs("blue" to MoveTo(Vec2(0.08f, 0.1f)), "red" to MoveTo(Vec2(0.02f, 0.1f))), 1600L to inputs("red" to Dash(Vec2(1f, 0f))))
            } else {
                emptyMap()
            }
        })
        assertEquals(listOf("red"), t.points.map { it.winner })
        val pause = t.states.filter { it.phase == MatchPhase.POINT }
        val blue = pause.map { it.duel.b }
        val standing = blue.indexOfFirst { it.pos == RIGHT }
        assertTrue(standing > 5, "falls first, then stands: $standing of ${blue.size}")
        assertTrue(blue.take(standing).all { DuelPhysics.isOut(it, arena) })
        assertTrue(blue.take(standing).any { it.body.state.h < 0f && it.body.state.mode == Mode.FALLING }, "the fall is shown")
        for (f in blue.drop(standing)) assertEquals(start.duel.b, f, "on its mark, as built")
        assertTrue(t.states.none { it.duel.b.pos.distanceTo(arena.spawn) < 0.01f }, "never at the spawn")
        for (s in pause) assertEquals(pause.first().duel.a, s.duel.a, "red frozen at ${s.duel.timeMs} ms")
        for (s in t.states) for (f in listOf(s.duel.a, s.duel.b)) {
            val st = f.body.state
            assertTrue(!Physics.blocks(onSpawn, st.h) || distanceToPolygon(st.pos, onSpawn.footprint) >= st.radiusM - 0.001f, "${f.id} in the cup")
        }
    }

    @Test
    fun rearrangeMovesTheMarksBetweenRounds() {
        // The child put a cup on red's mark between rounds and one Scan rebuilt the arena.
        val moved = NARROW.copy(objects = listOf(cup("cup", LEFT)))
        val newMarks = assertNotNull(DuelArena.marks(moved, R, 0.12f))
        assertTrue(DuelArena.startProblem(moved, MARKS, R) != null, "the old marks no longer do")

        // In COUNTDOWN both stand on the new marks at once, facing each other; the countdown runs on.
        val start = DuelMatch.start(RED, BLUE, NARROW, MARKS)
        val counting = DuelMatch.step(start, NARROW, 200L).state
        val re = DuelMatch.rearrange(counting, moved, newMarks)
        assertEquals(newMarks, re.startA to re.startB)
        assertEquals(newMarks, re.duel.a.pos to re.duel.b.pos)
        assertTrue(re.duel.a.body.state.facing.distanceTo((newMarks.second - newMarks.first).normalized()) < 1e-6f)
        assertEquals(counting.phaseLeftMs to counting.duel.timeMs, re.phaseLeftMs to re.duel.timeMs)
        assertFailsWith<IllegalArgumentException> { DuelMatch.rearrange(counting, moved, MARKS) }
        // Paused (tracking lost) bodies stay paused.
        val paused = DuelMatch.step(counting, NARROW, 16L, tracking = false).state
        assertTrue(DuelMatch.rearrange(paused, moved, newMarks).duel.a.body.state.mode == Mode.PAUSED)

        // In POINT the marks are taken at once and used: blue, still falling, reappears on its new mark, and both start
        // round 2 on them.
        val t = play(start, NARROW, maxMs = 4_000L, script = redDashes)
        val point = t.states.first { it.phase == MatchPhase.POINT }
        assertTrue(DuelPhysics.isOut(point.duel.b, NARROW))
        var s = DuelMatch.rearrange(point, moved, newMarks)
        assertEquals(point.duel, s.duel, "nobody moves in POINT")
        while (s.phase == MatchPhase.POINT) {
            s = DuelMatch.step(s, moved, 16L).state
            if (s.phase == MatchPhase.POINT && !DuelPhysics.isOut(s.duel.b, moved)) assertEquals(newMarks.second, s.duel.b.pos)
        }
        assertEquals(MatchPhase.COUNTDOWN, s.phase)
        assertEquals(newMarks, s.duel.a.pos to s.duel.b.pos)

        // Not in FIGHT, not in RESULT.
        val fighting = play(start, NARROW, maxMs = 3_100L).last
        assertEquals(MatchPhase.FIGHT, fighting.phase)
        assertFailsWith<IllegalArgumentException> { DuelMatch.rearrange(fighting, moved, newMarks) }
        val over = play(start, NARROW, script = redDashes).last
        assertEquals(MatchPhase.RESULT, over.phase)
        assertFailsWith<IllegalArgumentException> { DuelMatch.rearrange(over, moved, newMarks) }
    }

    @Test
    fun aMatchMidFightRoundTripsThroughJson() {
        // 200 ms into round 1: red (JUMP) in the air on a stomp, blue (HIDE) dodging; then a counter window, a point.
        val t = play(DuelMatch.start(RED, BLUE, NARROW, MARKS), NARROW, maxMs = 3_200L, script = { mapOf(0L to inputs("red" to Special, "blue" to Special)) })
        val mid = t.last
        assertTrue(mid.phase == MatchPhase.FIGHT && mid.duel.a.stomping && DuelPhysics.isDodging(mid.duel.b), "$mid")
        val json = Json { encodeDefaults = true }
        assertEquals(mid, json.decodeFromString(DuelMatchState.serializer(), json.encodeToString(DuelMatchState.serializer(), mid)))
        val step = MatchStep(mid, t.all)
        assertEquals(step, Json.decodeFromString(MatchStep.serializer(), Json.encodeToString(MatchStep.serializer(), step)))
        val later = play(mid, NARROW, maxMs = 6_000L, script = { mapOf(1000L to inputs("red" to Dash())) })
        val inPoint = later.states.first { it.phase == MatchPhase.POINT }
        assertEquals(inPoint, Json.decodeFromString(DuelMatchState.serializer(), Json.encodeToString(DuelMatchState.serializer(), inPoint)))
    }

    @Test
    fun timeoutGoesToTheFighterFartherInsideAndATieReplays() {
        val rules = DuelRules(roundMs = 2000L)
        fun round(redTo: Vec2?): MatchTrace {
            val start = DuelMatch.start(RED, BLUE, SQUARE, MARKS)
            return play(start, SQUARE, rules = rules, maxMs = 3000L + 2000L + 8L, script = { r ->
                if (r == 1 && redTo != null) mapOf(0L to inputs("red" to MoveTo(redTo))) else emptyMap()
            })
        }
        // Red walks 4 cm toward the middle: 18 cm inside against blue's 14 cm.
        val won = round(Vec2(-0.02f, 0f))
        assertEquals(listOf(Point("red", PointReason.TIMEOUT, 5000L)), won.points)
        assertEquals(PointResult(1, "red", PointReason.TIMEOUT), won.last.lastPoint)
        // Nobody moves: the marks are equally far inside, a replay.
        assertEquals(listOf(Point(null, PointReason.TIMEOUT, 5000L)), round(null).points)
        // Half a millimetre is still a tie; two millimetres is not.
        assertEquals(listOf(Point(null, PointReason.TIMEOUT, 5000L)), round(Vec2(-0.0595f, 0f)).points)
        assertEquals(listOf(Point("red", PointReason.TIMEOUT, 5000L)), round(Vec2(-0.058f, 0f)).points)
        // Blue walking toward its own edge loses it.
        val start = DuelMatch.start(RED, BLUE, SQUARE, MARKS)
        val lost = play(start, SQUARE, rules = rules, maxMs = 5008L, script = { mapOf(0L to inputs("blue" to MoveTo(Vec2(0.12f, 0f)))) })
        assertEquals(listOf(Point("red", PointReason.TIMEOUT, 5000L)), lost.points)
    }

    @Test
    fun firstToTwoWinsAndResultIsTerminal() {
        val start = DuelMatch.start(RED, BLUE, NARROW, MARKS)
        val t = play(start, NARROW, script = redDashes)
        val end = t.last
        assertEquals(MatchPhase.RESULT, end.phase)
        assertEquals("red", end.winner)
        assertEquals(2 to 0, end.scoreA to end.scoreB)
        assertEquals(2, end.round)
        assertEquals(0L, end.phaseLeftMs)
        assertEquals(listOf("red", "red"), t.points.map { it.winner })
        assertEquals(listOf(RoundStarted(1, 3000L), RoundStarted(2, t.all.filterIsInstance<RoundStarted>()[1].atMs)), t.all.filterIsInstance<RoundStarted>())
        val over = t.all.filterIsInstance<MatchOver>().single()
        assertEquals(MatchOver("red", t.points.last().atMs + aligned(RULES.pointPauseMs)), over)
        assertEquals(over, t.all.last(), "MatchOver is the last event")
        assertEquals(end.duel.timeMs, over.atMs)
        // On the marks for the result screen.
        assertEquals(LEFT, end.duel.a.pos)
        assertEquals(RIGHT, end.duel.b.pos)
        // Terminal: any step returns the very same state and nothing else.
        for (tracking in listOf(true, false)) {
            for (dt in listOf(16L, 250L, 5000L, 0L, -3L)) {
                val out = DuelMatch.step(end, NARROW, dt, inputs("red" to Dash(), "blue" to Special), tracking = tracking)
                assertSame(end, out.state)
                assertTrue(out.events.isEmpty())
            }
        }
        // And the whole thing round-trips through JSON (T49's HUD and T55's logs read it).
        val step = MatchStep(end, t.all.take(6) + over)
        assertEquals(step, Json.decodeFromString(MatchStep.serializer(), Json.encodeToString(MatchStep.serializer(), step)))
    }

    @Test
    fun aMatchWithoutAWinnerAfterMaxRoundsIsADraw() {
        // Nobody moves: five tied timeouts (replays count as rounds), then a draw.
        val rules = DuelRules(roundMs = 1000L)
        val t = play(DuelMatch.start(RED, BLUE, SQUARE, MARKS), SQUARE, rules = rules)
        assertEquals(MatchPhase.RESULT, t.last.phase)
        assertNull(t.last.winner)
        assertEquals(5, t.last.round)
        assertEquals(List(5) { null }, t.points.map { it.winner })
        assertEquals(listOf(MatchOver(null, 5L * (3000L + 1000L + 1504L))), t.all.filterIsInstance<MatchOver>())

        // A point each, then a replay, with three rounds at most: a 1-1 draw.
        val three = DuelRules(roundMs = 1000L, maxRounds = 3)
        val script = { r: Int ->
            when (r) {
                1 -> mapOf(0L to inputs("red" to MoveTo(Vec2(-0.03f, 0f))))
                2 -> mapOf(0L to inputs("blue" to MoveTo(Vec2(0.03f, 0f))))
                else -> emptyMap()
            }
        }
        val even = play(DuelMatch.start(RED, BLUE, SQUARE, MARKS), SQUARE, rules = three, script = script)
        assertEquals(listOf("red", "blue", null), even.points.map { it.winner })
        assertEquals(MatchPhase.RESULT, even.last.phase)
        assertNull(even.last.winner)
        assertEquals(1 to 1, even.last.scoreA to even.last.scoreB)
    }

    @Test
    fun trackingLossFreezesEveryClockAndNothingIsLost() {
        val start = DuelMatch.start(RED, BLUE, NARROW, MARKS)
        val reference = play(start, NARROW, script = redDashes)
        // The same match with 10 s of tracking loss (frames of 33 ms, inputs pressed all the while) in the countdown,
        // mid-fight while blue is flying back from red's hit, and in the point pause while blue falls.
        val hitAt = reference.all.filterIsInstance<Fight>().map { it.event }.filterIsInstance<Hit>().first().atMs
        val pointAt = reference.points.first().atMs
        val gaps = mutableSetOf<MatchPhase>()
        var s = start
        val events = ArrayList<MatchEvent>()
        val roundLen = aligned(RULES.roundMs)
        val wanted = ArrayDeque(listOf(1504L, hitAt + 40L, pointAt + 100L))
        while (s.phase != MatchPhase.RESULT) {
            if (wanted.isNotEmpty() && s.duel.timeMs == wanted.first()) {
                wanted.removeFirst()
                val before = s
                repeat(303) {
                    val out = DuelMatch.step(s, NARROW, 33L, inputs("red" to Dash(), "blue" to Special), tracking = false)
                    assertTrue(out.events.isEmpty())
                    s = out.state
                    assertEquals(before.phase, s.phase)
                    assertEquals(before.phaseLeftMs, s.phaseLeftMs)
                    assertEquals(before.duel.timeMs, s.duel.timeMs, "no clock runs")
                    for ((f, g) in listOf(before.duel.a to s.duel.a, before.duel.b to s.duel.b)) {
                        assertEquals(f.pos, g.pos)
                        assertEquals(f.body.state.h, g.body.state.h)
                        assertEquals(Mode.PAUSED, g.body.state.mode, "both paused")
                        assertEquals(f.copy(body = g.body), g, "every timer frozen")
                    }
                }
                gaps += s.phase
            }
            val since = roundLen - s.phaseLeftMs
            val input = if (s.phase == MatchPhase.FIGHT) redDashes(s.round)[since] ?: emptyMap() else emptyMap()
            var dt = 16L
            if (s.phase != MatchPhase.FIGHT) dt = minOf(dt, s.phaseLeftMs)
            wanted.firstOrNull()?.let { if (it > s.duel.timeMs) dt = minOf(dt, it - s.duel.timeMs) }
            val out = DuelMatch.step(s, NARROW, dt, input)
            events += out.events
            s = out.state
        }
        assertEquals(setOf(MatchPhase.COUNTDOWN, MatchPhase.FIGHT, MatchPhase.POINT), gaps, "a gap in every phase")
        assertEquals(reference.last, s, "the same match, as if the gaps never happened")
        assertEquals(reference.all, events)
    }

    @Test
    fun anySplitOfTheSameTimeGivesTheSameMatch() {
        // A scripted match with every verb: red (JUMP) dashes, stomps, walks; blue (HIDE) dodges and counters, walks,
        // jumps. A cup on the table for wall slams.
        val arena = NARROW.copy(objects = listOf(LevelObject("cup-1", "cup", ObjectKind.OBSTACLE, Polygon.rect(Vec2(0f, 0.12f), 0.05f, 0.05f), 0.09f)))
        val script = { r: Int ->
            when (r % 3) {
                1 -> mapOf(0L to inputs("red" to Dash()))
                2 -> mapOf(
                    0L to inputs("red" to Dash(), "blue" to Special),
                    600L to inputs("blue" to Dash()),
                    1200L to inputs("red" to Special, "blue" to MoveTo(Vec2(0.05f, 0.05f))),
                    2504L to inputs("blue" to Jump, "red" to MoveTo(Vec2(0f, 0.06f))),
                    4000L to inputs("red" to Dash(Vec2(0.3f, 1f)), "blue" to MoveTo(Vec2(0.1f, -0.1f))),
                    6000L to inputs("blue" to Dash(Vec2(-1f, 0f)), "red" to Special),
                )
                else -> mapOf(
                    0L to inputs("blue" to Dash(), "red" to Jump),
                    1000L to inputs("red" to Special),
                    1800L to inputs("red" to Dash(Vec2(1f, 0f)), "blue" to Special),
                    2500L to inputs("blue" to Dash(Vec2(-1f, 0.2f))),
                )
            }
        }
        val rules = DuelRules(roundMs = 10_000L, pointsToWin = 3, maxRounds = 6)
        fun run(frame: () -> Long) = play(DuelMatch.start(RED, BLUE, arena, MARKS), arena, rules = rules, script = script, frame = frame)
        val sixteen = run { 16L }
        val kinds = sixteen.all.map { if (it is Fight) it.event::class.simpleName else it::class.simpleName }.toSet()
        assertTrue(kinds.containsAll(listOf("RoundStarted", "Point", "MatchOver", "DashStarted", "SpecialStarted", "Hit", "RingOut")), "$kinds")
        assertEquals(MatchPhase.RESULT, sixteen.last.phase)
        println("anySplit: ${sixteen.points}, ${sixteen.last.duel.timeMs} ms, ${sixteen.all.size} events")

        val rnd = Random(5)
        for ((name, frame) in listOf<Pair<String, () -> Long>>(
            "33 ms" to { 33L },
            "random 5..50 ms" to { 5L + rnd.nextLong(46L) },
            "random 1..250 ms" to { 1L + rnd.nextLong(250L) },
        )) {
            val other = run(frame)
            assertEquals(sixteen.last, other.last, name)
            assertEquals(sixteen.all, other.all, name)
            // Every state both runs share a time with is the same state.
            val byTime = other.states.associateBy { it.duel.timeMs to it.phase }
            for (s in sixteen.states) byTime[s.duel.timeMs to s.phase]?.let { assertEquals(s, it, "$name at ${s.duel.timeMs} ms") }
        }
    }

    @Test
    fun randomMatchesAlwaysEnd() {
        // Seeded random play on T42's cluttered fuzz table: every ability against every other, frames of 16..50 ms,
        // tracking losses. Every match reaches RESULT within maxRounds x (countdown + round + pause) of tracked time,
        // the clock runs only while tracked, and the score always adds up.
        val rules = DuelRules(roundMs = 20_000L)
        val lengths = mapOf(
            MatchPhase.COUNTDOWN to aligned(rules.countdownMs),
            MatchPhase.FIGHT to aligned(rules.roundMs),
            MatchPhase.POINT to aligned(rules.pointPauseMs),
            MatchPhase.RESULT to 0L,
        )
        val bound = rules.maxRounds * lengths.values.sum()
        val endings = HashMap<String, Int>()
        fun count(key: String) { endings[key] = (endings[key] ?: 0) + 1 }
        var steps = 0L
        val ends = (0 until 16).map { seed ->
            randomMatch(seed, rules) { s, out, tracking, dt ->
                val n = out.state
                steps++
                if (!tracking) {
                    assertTrue(out.events.isEmpty())
                    assertEquals(s.duel.timeMs, n.duel.timeMs)
                    assertTrue(n.duel.a.body.state.mode == Mode.PAUSED && n.duel.b.body.state.mode == Mode.PAUSED)
                } else if (n.phase != MatchPhase.RESULT) {
                    assertEquals(s.duel.timeMs + dt, n.duel.timeMs)
                }
                // The books: one point per Point event, never more than pointsToWin, never more rounds than allowed.
                val scored = out.events.filterIsInstance<Point>()
                for (p in scored) count("point ${p.reason}${if (p.winner == null) " replay" else ""}")
                assertEquals(s.scoreA + scored.count { it.winner == "a" }, n.scoreA)
                assertEquals(s.scoreB + scored.count { it.winner == "b" }, n.scoreB)
                assertTrue(n.scoreA <= rules.pointsToWin && n.scoreB <= rules.pointsToWin && n.scoreA + n.scoreB <= n.round)
                assertTrue(n.round in 1..rules.maxRounds)
                assertTrue(n.phaseLeftMs in 0L..lengths.getValue(n.phase), "${n.phase} ${n.phaseLeftMs}")
                if (n.phase == MatchPhase.COUNTDOWN) assertEquals(n.startA to n.startB, n.duel.a.pos to n.duel.b.pos)
                if (n.phase == MatchPhase.POINT && s.phase == MatchPhase.POINT) assertTrue(out.events.isEmpty(), "an event in POINT: ${out.events}")
                // T42's invariants, in every phase: no circle inside what blocks at its feet, standing fighters apart.
                for (f in listOf(n.duel.a, n.duel.b)) {
                    val st = f.body.state
                    assertTrue(st.pos.u.isFinite() && st.pos.v.isFinite() && st.h.isFinite())
                    val inside = FUZZ_TABLE.objects.firstOrNull {
                        Physics.blocks(it, st.h) && (it.footprint.contains(st.pos) || distanceToPolygon(st.pos, it.footprint) < st.radiusM - 0.001f)
                    }
                    assertNull(inside?.id, "${f.id} in ${inside?.id} in ${n.phase} at ${n.duel.timeMs} ms: $st")
                }
                val ground = setOf(Mode.IDLE, Mode.WALKING)
                if (n.duel.a.body.state.mode in ground && n.duel.b.body.state.mode in ground) {
                    assertTrue(n.duel.a.pos.distanceTo(n.duel.b.pos) >= 2 * R - 1e-4f, "overlap in ${n.phase} at ${n.duel.timeMs} ms")
                }
                assertTrue(n.duel.timeMs <= bound, "a match ran past $bound ms")
            }
        }
        for (s in ends) {
            assertEquals(MatchPhase.RESULT, s.phase)
            val w = s.winner
            assertTrue(if (w == null) s.round == rules.maxRounds else s.scoreOf(w) == rules.pointsToWin, "$s")
            assertSame(s, DuelMatch.step(s, FUZZ_TABLE, 16L, inputs("a" to Dash())).state)
            count("${s.lastPoint?.reason}/${if (w == null) "draw" else "win"}")
        }
        println("random matches: ${ends.size} ended, $endings, $steps steps, ${ends.sumOf { it.duel.timeMs } / 1000} s of matches")
        assertTrue((endings["point RING_OUT"] ?: 0) > 0 && (endings["point TIMEOUT"] ?: 0) > 0, "both endings: $endings")

        // Deterministic, and timed without the checks (best of two replays).
        var best = Double.MAX_VALUE
        repeat(2) {
            val mark = TimeSource.Monotonic.markNow()
            val again = (0 until 16).map { seed -> randomMatch(seed, rules) { _, _, _, _ -> } }
            best = minOf(best, mark.elapsedNow().inWholeMicroseconds / 1e6)
            assertEquals(ends, again)
        }
        println("random matches: ${(steps / best).toLong()} match steps/s, ${(ends.size / best).toLong()} matches/s")
    }

    /** One seeded random match on [FUZZ_TABLE] (abilities by seed: all 16 pairs in seeds 0..15); returns its RESULT state. */
    private fun randomMatch(
        seed: Int,
        rules: DuelRules,
        check: (prev: DuelMatchState, out: MatchStep, tracking: Boolean, dtMs: Long) -> Unit,
    ): DuelMatchState {
        val rnd = Random(seed)
        fun randomStats(): FighterStats {
            val st = IntArray(4) { FighterStats.MIN }
            repeat(FighterStats.BUDGET - 4 * FighterStats.MIN) {
                var i = rnd.nextInt(4)
                while (st[i] >= FighterStats.MAX) i = (i + 1) % 4
                st[i]++
            }
            return FighterStats(st[0], st[1], st[2], st[3])
        }
        val a = Contestant("a", randomStats(), Ability.entries[seed % 4])
        val b = Contestant("b", randomStats(), Ability.entries[(seed / 4) % 4])
        var s = DuelMatch.start(a, b, FUZZ_TABLE, FUZZ_MARKS, rules)
        // T42's fuzz plans: 0 chase the other (dash when close), 1 wander to a spot (a quarter of them off the table),
        // 2 stand; random jumps and specials on top.
        val plan = intArrayOf(0, 0)
        val wander = arrayOf(Vec2.ZERO, Vec2.ZERO)
        while (s.phase != MatchPhase.RESULT) {
            val tracking = rnd.nextInt(100) >= 3
            val inputs = HashMap<String, DuelInput>()
            for ((k, pair) in listOf(s.duel.a to s.duel.b, s.duel.b to s.duel.a).withIndex()) {
                val (me, other) = pair
                if (rnd.nextInt(100) < 3) {
                    plan[k] = rnd.nextInt(3)
                    val reach = if (rnd.nextInt(4) == 0) 0.6f else 0.3f
                    wander[k] = Vec2((rnd.nextFloat() * 2f - 1f) * reach, (rnd.nextFloat() * 2f - 1f) * reach * 0.7f)
                }
                val to = other.pos - me.pos
                val roll = rnd.nextInt(100)
                val input: DuelInput? = when {
                    roll < 3 -> Special
                    roll < 5 -> Jump
                    plan[k] == 0 && to.length < 0.12f && roll < 30 -> Dash(to)
                    plan[k] == 0 && roll < 40 -> MoveTo(other.pos)
                    plan[k] == 1 && roll < 15 -> MoveTo(wander[k])
                    else -> null
                }
                if (input != null) inputs[me.id] = input
            }
            val dt = rnd.nextLong(16L, 51L)
            val out = DuelMatch.step(s, FUZZ_TABLE, dt, inputs, tracking = tracking, rules = rules)
            check(s, out, tracking, dt)
            s = out.state
        }
        return s
    }

    @Test
    fun mirroredMatchesGetMirroredResults() {
        // Mirror the whole match across the v axis and swap the slots: red now starts on the right, in slot b, every
        // input mirrored. Same winner, same events (WallSlam objects mirrored), every position mirrored.
        val arena = Level(
            listOf(
                LevelObject("cup-l", "cup", ObjectKind.OBSTACLE, Polygon.rect(Vec2(-0.08f, 0.12f), 0.05f, 0.05f), 0.09f),
                LevelObject("cup-r", "cup", ObjectKind.OBSTACLE, Polygon.rect(Vec2(0.08f, 0.12f), 0.05f, 0.05f), 0.09f),
            ),
            boundary = Polygon.rect(Vec2.ZERO, 0.28f, 0.4f),
        )
        val red = RED.copy(ability = Ability.ROLL)
        val blue = BLUE.copy(ability = Ability.FLY)
        val script = { r: Int ->
            when (r) {
                1 -> mapOf(0L to inputs("blue" to Special), 400L to inputs("red" to Special), 1600L to inputs("blue" to MoveTo(Vec2(0.02f, 0.06f))))
                2 -> mapOf(0L to inputs("red" to Dash(Vec2(1f, 0.5f)), "blue" to MoveTo(Vec2(0.09f, -0.1f))), 2000L to inputs("red" to MoveTo(Vec2(0.05f, -0.05f))), 3000L to inputs("red" to Special))
                else -> mapOf(0L to inputs("red" to Special))
            }
        }
        fun mirrored(i: Map<String, DuelInput>) = i.mapValues { (_, v) ->
            when (v) {
                is MoveTo -> MoveTo(m(v.target))
                is Dash -> Dash(v.dir?.let(::m))
                else -> v
            }
        }
        val rules = DuelRules(roundMs = 6000L)
        val x = play(DuelMatch.start(red, blue, arena, MARKS), arena, rules = rules, script = script)
        val y = play(DuelMatch.start(blue, red, arena, m(RIGHT) to m(LEFT)), arena, rules = rules, script = { r -> script(r).mapValues { mirrored(it.value) } })
        assertEquals(MatchPhase.RESULT, x.last.phase)
        assertTrue(x.fight().any { it is SpecialStarted } && x.points.isNotEmpty(), "${x.all}")
        println("mirrored: ${x.points} winner ${x.last.winner}")
        assertEquals(x.last.winner, y.last.winner)
        assertEquals(x.last.scoreOf("red"), y.last.scoreOf("red"))
        assertEquals(x.last.scoreOf("blue"), y.last.scoreOf("blue"))
        val mirrorEvents = x.all.map { e ->
            if (e is Fight && e.event is WallSlam) Fight((e.event as WallSlam).let { it.copy(objectId = if (it.objectId == "cup-l") "cup-r" else "cup-l") }) else e
        }
        assertEquals(mirrorEvents, y.all)
        assertEquals(x.states.size, y.states.size)
        for ((i, s) in x.states.withIndex()) {
            val r = y.states[i]
            assertEquals(s.duel.timeMs, r.duel.timeMs)
            assertEquals(s.phase to s.phaseLeftMs, r.phase to r.phaseLeftMs)
            for (id in listOf("red", "blue")) {
                val f = s.duel[id]!!
                val g = r.duel[id]!!
                assertTrue(m(f.pos).distanceTo(g.pos) < 1e-5f, "$id at ${s.duel.timeMs} ms: ${f.pos} vs mirrored ${g.pos}")
                assertEquals(f.body.state.mode, g.body.state.mode, "$id mode at ${s.duel.timeMs} ms")
                assertEquals(listOf(f.stunMs, f.dashMs, f.specialMs, f.specialCooldownMs), listOf(g.stunMs, g.dashMs, g.specialMs, g.specialCooldownMs))
            }
        }
    }
}
