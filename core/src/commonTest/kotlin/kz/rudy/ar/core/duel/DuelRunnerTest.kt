package kz.rudy.ar.core.duel

import kotlinx.serialization.json.Json
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.duel.DuelInput.Dash
import kz.rudy.ar.core.duel.DuelInput.Jump
import kz.rudy.ar.core.duel.DuelInput.MoveTo
import kz.rudy.ar.core.duel.DuelInput.Special
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val TUNING = BotTuning()

/** Two drawings with different stats and specials, so a mirror cannot hide a mistake. */
private val RED = Contestant("red", FighterStats(power = 7, speed = 6, guard = 5, agility = 2), Ability.ROLL)
private val BLUE = Contestant("blue", FighterStats(power = 4, speed = 5, guard = 4, agility = 7), Ability.HIDE)

/** A bot-vs-bot match on [arena] before its first countdown. */
internal fun botMatch(
    a: Contestant,
    b: Contestant,
    arena: Level,
    levelA: BotLevel,
    levelB: BotLevel,
    seed: Long,
    rules: DuelRules = DuelRules(),
): RunnerState = RunnerState(
    DuelMatch.start(a, b, arena, DuelArenas.marks(arena), rules),
    DuelBot.mind(levelA, 2L * seed),
    DuelBot.mind(levelB, 2L * seed + 1L),
)

/** Everything a runner run produced. */
internal class RunnerTrace(val states: List<RunnerState>, val events: List<MatchEvent>, val decisions: List<BotDecision>) {
    val last: RunnerState get() = states.last()
}

/** Steps [start] with frames from [frame] until RESULT or [maxMs] of match time; [inputs] gives the caller's inputs. */
internal fun runRunner(
    start: RunnerState,
    arena: Level,
    maxMs: Long = 600_000L,
    rules: DuelRules = DuelRules(),
    params: DuelParams = DuelParams.DEFAULT,
    frame: () -> Long = { 33L },
    inputs: (RunnerState) -> Map<String, DuelInput> = { emptyMap() },
): RunnerTrace {
    val states = arrayListOf(start)
    val events = ArrayList<MatchEvent>()
    val decisions = ArrayList<BotDecision>()
    var s = start
    while (s.match.phase != MatchPhase.RESULT && s.match.duel.timeMs < maxMs) {
        val dt = minOf(frame(), maxMs - s.match.duel.timeMs)
        val out = DuelRunner.step(s, arena, dt, inputs(s), rules = rules, params = params, tuning = TUNING)
        s = out.state
        states += s
        events += out.events
        decisions += out.decisions
    }
    return RunnerTrace(states, events, decisions)
}

class DuelRunnerTest {

    @Test
    fun runnerWithoutBotsIsDuelMatch() {
        // The same random frames (some too long, some empty), inputs and tracking losses, through DuelMatch.step and
        // through the runner without minds: the very same states and events at every step, to RESULT and past it.
        val arena = DuelArenas.CUP_AND_BOOK
        val start = DuelMatch.start(RED, BLUE, arena, DuelArenas.marks(arena))
        val rnd = Random(3)
        val verbs = listOf<(DuelMatchState) -> DuelInput>(
            { Dash() }, { Special }, { Jump }, { s -> MoveTo(s.duel.b.body.state.pos) }, { s -> MoveTo(s.duel.a.body.state.pos) },
            { MoveTo(Vec2(0.5f, 0f)) }, { Dash(Vec2(0f, -1f)) },
        )
        var m = start
        var r = RunnerState(start)
        var steps = 0
        var results = 0
        while (results < 3) {
            val dt = when (rnd.nextInt(20)) {
                0 -> 400L
                1 -> 0L
                2 -> -5L
                else -> rnd.nextLong(1L, 60L)
            }
            val tracking = rnd.nextInt(30) != 0
            val inputs = HashMap<String, DuelInput>()
            if (rnd.nextInt(4) == 0) inputs["red"] = verbs[rnd.nextInt(verbs.size)](m)
            if (rnd.nextInt(4) == 0) inputs["blue"] = verbs[rnd.nextInt(verbs.size)](m)
            if (rnd.nextInt(10) == 0) inputs["nobody"] = Dash()
            val a = DuelMatch.step(m, arena, dt, inputs, tracking)
            val b = DuelRunner.step(r, arena, dt, inputs, tracking)
            assertEquals(a.state, b.state.match, "step $steps")
            assertEquals(a.events, b.events, "step $steps")
            assertTrue(b.decisions.isEmpty())
            assertEquals(null to null, b.state.botA to b.state.botB)
            m = a.state
            r = b.state
            steps++
            if (m.phase == MatchPhase.RESULT) results++
        }
        println("runnerWithoutBotsIsDuelMatch: $steps steps, ${m.scoreA}:${m.scoreB} at ${m.duel.timeMs} ms")
    }

    @Test
    fun humanSideWithABotIsExactlyDuelMatch() {
        // What the AR host relies on: with a real bot on one side that never wants anything (its next decision is at
        // Long.MAX_VALUE, so the whole bot path runs: the resume, the cut at every tick, the asking, and it answers None),
        // the runner is DuelMatch.step for the child's side, step for step: random frames (empty, negative, on the tick,
        // longer than maxStepMs), tracking losses, inputs for the child, for the bot's side and for nobody.
        val asleep = BotMind(BotLevel.HARD, rng = 42L, nextThinkMs = Long.MAX_VALUE)
        val ruleSets = listOf(
            DuelRules(),
            DuelRules(countdownMs = 0L, roundMs = 2_000L, pointPauseMs = 0L),
            DuelRules(countdownMs = 1_000L, roundMs = 1_500L, pointPauseMs = 100L, maxStepMs = 77L),
        )
        val listed = longArrayOf(0L, -5L, 8L, 48L, 96L, 400L, 1_000L)
        var steps = 0
        var results = 0
        val phases = mutableSetOf<MatchPhase>()
        for ((k, arena) in DuelArenas.ALL.values.withIndex()) for ((j, rules) in ruleSets.withIndex()) for (botSide in 0..1) {
            val rnd = Random(100 * k + 10 * j + botSide)
            val (botId, childId) = if (botSide == 0) "red" to "blue" else "blue" to "red"
            fun fresh(): Pair<DuelMatchState, RunnerState> {
                val m = DuelMatch.start(RED, BLUE, arena, DuelArenas.marks(arena), rules)
                return m to if (botSide == 0) RunnerState(m, botA = asleep) else RunnerState(m, botB = asleep)
            }
            var (m, r) = fresh()
            repeat(700) {
                val dt = if (rnd.nextInt(3) == 0) listed[rnd.nextInt(listed.size)] else rnd.nextLong(1L, 60L)
                val tracking = rnd.nextInt(25) != 0
                val child = m.duel[childId]!!.body.state.pos
                val bot = m.duel[botId]!!.body.state.pos
                val inputs = HashMap<String, DuelInput>()
                when (rnd.nextInt(8)) {
                    0 -> inputs[childId] = Dash(bot - child)
                    1 -> inputs[childId] = MoveTo(bot)
                    2 -> inputs[childId] = Special
                    3 -> inputs[childId] = Jump
                    4 -> inputs[childId] = MoveTo(Vec2(0.5f, 0.1f))
                    else -> Unit
                }
                if (rnd.nextInt(3) == 0) inputs[botId] = Dash(child - bot)
                if (rnd.nextInt(8) == 0) inputs["nobody"] = Special
                val a = DuelMatch.step(m, arena, dt, inputs - botId, tracking, rules)
                val b = DuelRunner.step(r, arena, dt, inputs, tracking, rules)
                assertEquals(a.state, b.state.match, "arena $k, rules $j, bot side $botSide, step $it")
                assertEquals(a.events, b.events, "arena $k, rules $j, bot side $botSide, step $it")
                assertTrue(b.decisions.isEmpty())
                assertEquals(asleep, b.state.botOf(botId))
                assertEquals(null, b.state.botOf(childId))
                m = a.state
                r = b.state
                phases += m.phase
                steps++
                if (m.phase == MatchPhase.RESULT && rnd.nextInt(4) == 0) {
                    results++
                    val (m2, r2) = fresh()
                    m = m2
                    r = r2
                }
            }
        }
        println("humanSideWithABotIsExactlyDuelMatch: $steps steps equal, $results matches to RESULT, phases $phases")
        assertEquals(MatchPhase.entries.toSet(), phases)
    }

    @Test
    fun aHumanInputForABotSideIsIgnored() {
        val arena = DuelArenas.EMPTY
        val oneBot = botMatch(RED, BLUE, arena, BotLevel.NORMAL, BotLevel.NORMAL, 4L).copy(botB = null)
        // Into the fight; the child plays blue and keeps still.
        var s = runRunner(oneBot, arena, maxMs = 3_504L).last
        assertEquals(MatchPhase.FIGHT, s.match.phase)
        // Inputs for red (the bot's side) change nothing; blue's own input is applied at the start of the call.
        val noise = mapOf<String, DuelInput>("red" to Dash(Vec2(0f, 1f)), "blue" to Dash(Vec2(0f, -1f)))
        val withNoise = DuelRunner.step(s, arena, 33L, noise)
        val clean = DuelRunner.step(s, arena, 33L, mapOf("blue" to Dash(Vec2(0f, -1f))))
        assertEquals(clean, withNoise)
        val started = withNoise.events.filterIsInstance<MatchEvent.Fight>().map { it.event }.filterIsInstance<DuelEvent.DashStarted>()
        assertEquals(listOf("blue"), started.map { it.id }, "${withNoise.events}")
        assertEquals(Vec2(0f, -1f), withNoise.state.match.duel.b.dashDir)
        // Over a whole match, red's noise never changes a thing: same as a match where nobody presses red.
        val noisy = runRunner(oneBot, arena, maxMs = 30_000L) { mapOf("red" to Special, "nobody" to Jump) }
        val quiet = runRunner(oneBot, arena, maxMs = 30_000L)
        assertEquals(quiet.last, noisy.last)
        assertEquals(quiet.events, noisy.events)
        assertTrue(quiet.decisions.isNotEmpty() && quiet.decisions.all { it.id == "red" })
        // Two bots: every input is ignored.
        val twoBots = botMatch(RED, BLUE, arena, BotLevel.EASY, BotLevel.HARD, 5L)
        val pressed = runRunner(twoBots, arena, maxMs = 20_000L) { mapOf("red" to Dash(), "blue" to MoveTo(Vec2(0.5f, 0.5f))) }
        val alone = runRunner(twoBots, arena, maxMs = 20_000L)
        assertEquals(alone.last, pressed.last)
        assertEquals(alone.events, pressed.events)
        s = alone.last
        assertNotNull(s.botA)
    }

    @Test
    fun trackingLossFreezesTheBotsToo() {
        val arena = DuelArenas.CUP_AND_BOOK
        val start = botMatch(RED, BLUE, arena, BotLevel.HARD, BotLevel.NORMAL, 9L)
        val reference = runRunner(start, arena, frame = { 16L })
        assertEquals(MatchPhase.RESULT, reference.last.match.phase)
        // The same match in 33 ms frames with 5 s of tracking loss (inputs pressed all the while) in the countdown, in
        // the fight both on a tick and off it, and in the point pause.
        val pointAt = reference.events.filterIsInstance<MatchEvent.Point>().first().atMs
        val onTick = 48L * 70L // 3360: 360 ms into the first fight
        assertTrue(pointAt > onTick + 500L, "the first round lasts: $pointAt")
        val gapsAt = ArrayDeque(listOf(1_000L, onTick, onTick + 117L, pointAt + 200L).sorted())
        var s = start
        val events = ArrayList<MatchEvent>()
        val decisions = ArrayList<BotDecision>()
        val phases = mutableSetOf<MatchPhase>()
        while (s.match.phase != MatchPhase.RESULT) {
            val next = gapsAt.firstOrNull()
            if (next != null && s.match.duel.timeMs == next) {
                gapsAt.removeFirst()
                val before = s
                phases += s.match.phase
                repeat(152) {
                    val out = DuelRunner.step(s, arena, 33L, mapOf("red" to Dash(), "blue" to Special), tracking = false)
                    assertTrue(out.events.isEmpty() && out.decisions.isEmpty())
                    s = out.state
                    assertEquals(before.botA to before.botB, s.botA to s.botB, "no bot decides")
                    val m = s.match
                    assertEquals(before.match.duel.timeMs to before.match.phaseLeftMs, m.duel.timeMs to m.phaseLeftMs, "no clock runs")
                    assertTrue(m.duel.a.body.state.mode == Mode.PAUSED && m.duel.b.body.state.mode == Mode.PAUSED)
                    assertEquals(before.match.duel.a.body.state.pos, m.duel.a.body.state.pos)
                }
            }
            var dt = 33L
            if (next != null && next > s.match.duel.timeMs) dt = minOf(dt, next - s.match.duel.timeMs)
            val out = DuelRunner.step(s, arena, dt)
            s = out.state
            events += out.events
            decisions += out.decisions
        }
        assertEquals(setOf(MatchPhase.COUNTDOWN, MatchPhase.FIGHT, MatchPhase.POINT), phases)
        assertEquals(reference.last, s, "the same match, as if tracking was never lost")
        assertEquals(reference.events, events)
        assertEquals(reference.decisions, decisions)
    }

    @Test
    fun botMatchesAreIdenticalAtAnyFrameLength() {
        // G4's "30 Hz and 60 Hz agree", exactly: the same seeded bot-vs-bot match at 16, 33, 50 and random 5..50 ms frames
        // (and random 1..250 ms) is the same match: equal final states, equal events, equal decisions.
        var compared = 0
        for ((k, arena) in DuelArenas.ALL.values.withIndex()) {
            for ((la, lb) in listOf(BotLevel.EASY to BotLevel.HARD, BotLevel.NORMAL to BotLevel.NORMAL, BotLevel.HARD to BotLevel.HARD)) {
                val a = RED.copy(ability = Ability.entries[k])
                val b = BLUE.copy(ability = Ability.entries[(k + 2 + la.ordinal) % 4])
                val start = botMatch(a, b, arena, la, lb, 40L + k)
                val sixteen = runRunner(start, arena, frame = { 16L })
                assertEquals(MatchPhase.RESULT, sixteen.last.match.phase)
                val rnd = Random(k * 10 + la.ordinal)
                for ((name, frame) in listOf<Pair<String, () -> Long>>(
                    "33 ms" to { 33L },
                    "50 ms" to { 50L },
                    "random 5..50 ms" to { rnd.nextLong(5L, 51L) },
                    "random 1..250 ms" to { rnd.nextLong(1L, 251L) },
                )) {
                    val other = runRunner(start, arena, frame = frame)
                    assertEquals(sixteen.last, other.last, "$name, $la v $lb on arena $k")
                    assertEquals(sixteen.events, other.events, "$name, $la v $lb on arena $k")
                    assertEquals(sixteen.decisions, other.decisions, "$name, $la v $lb on arena $k")
                    compared++
                }
                assertTrue(sixteen.decisions.all { it.atMs % TUNING.tickMs == 0L }, "decisions on the tick grid")
            }
        }
        println("botMatchesAreIdenticalAtAnyFrameLength: $compared runs equal to their 16 ms match")
    }

    @Test
    fun botMindSurvivesJson() {
        // A match saved mid-fight (RunnerState through JSON, as a host would keep it) continues to the same result.
        val arena = DuelArenas.CUP_AND_BOOK
        val start = botMatch(RED, BLUE, arena, BotLevel.NORMAL, BotLevel.EASY, 12L)
        val whole = runRunner(start, arena)
        val half = runRunner(start, arena, maxMs = 3_000L + 4_321L)
        val mid = half.last
        assertEquals(MatchPhase.FIGHT, mid.match.phase)
        assertNotEquals(start.botA, mid.botA, "the mind has moved on")
        for (json in listOf(Json, Json { encodeDefaults = true })) {
            val text = json.encodeToString(RunnerState.serializer(), mid)
            val back = json.decodeFromString(RunnerState.serializer(), text)
            assertEquals(mid, back)
            val rest = runRunner(back, arena)
            assertEquals(whole.last, rest.last)
            assertEquals(whole.events, half.events + rest.events)
        }
        // A step's output round-trips too (logs).
        val step = DuelRunner.step(mid, arena, 200L)
        assertEquals(step, Json.decodeFromString(RunnerStep.serializer(), Json.encodeToString(RunnerStep.serializer(), step)))
        // RESULT is terminal for the runner as for the match.
        val end = whole.last
        assertSame(end, DuelRunner.step(end, arena, 33L).state)
    }
}
