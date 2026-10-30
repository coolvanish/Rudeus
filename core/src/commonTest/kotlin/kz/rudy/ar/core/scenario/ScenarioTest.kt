package kz.rudy.ar.core.scenario

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.CharacterEvent.BLOCKED
import kz.rudy.ar.core.domain.CharacterEvent.FELL
import kz.rudy.ar.core.domain.CharacterEvent.JUMPED
import kz.rudy.ar.core.domain.CharacterEvent.LANDED
import kz.rudy.ar.core.domain.CharacterEvent.RESPAWNED
import kz.rudy.ar.core.domain.CharacterEvent.STARTED_WALKING
import kz.rudy.ar.core.domain.CharacterEvent.STEPPED_UP
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.physics.Physics
import kz.rudy.ar.core.physics.PhysicsBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T19: the Day 5 script replayed on the JVM through Physics; the phone replays the same [Scenarios.DAY5] (T21). */
class ScenarioTest {
    private val dtMs = 16L
    private val level = Scenarios.day5Level()
    private val book = level.objects.single { it.label == "book" }

    @Test
    fun day5RunsThreeTimesIdentically() {
        val traces = List(3) { ScenarioRunner.run(Scenarios.DAY5, Scenarios.day5Level(), Ability.JUMP, dtMs = dtMs) }
        assertTrue(traces[0].events.isNotEmpty())
        assertEquals(traces[0], traces[1])
        assertEquals(traces[0], traces[2])
    }

    @Test
    fun day5JumpVariantHitsEveryBeat() {
        val trace = ScenarioRunner.run(Scenarios.DAY5, level, Ability.JUMP, dtMs = dtMs)
        println("T19 JUMP trace:\n" + describe(trace))
        val names = trace.eventNames()
        assertInOrder(listOf(STARTED_WALKING, BLOCKED, STEPPED_UP, JUMPED, LANDED, JUMPED, FELL, RESPAWNED), names)
        assertEquals(1, names.count { it == BLOCKED }, "BLOCKED: $names")

        val (upAt, _) = trace.events.single { it.second == STEPPED_UP }
        val onBook = bodyAt(trace, upAt)
        assertEquals(book.heightM, onBook.state.h, 0.002f)
        assertTrue(book.footprint.contains(onBook.state.pos), "stepped up at ${onBook.state.pos}")
        assertEquals(0.02f, onBook.state.h, 0.002f)

        val (fellAt, _) = trace.events.single { it.second == FELL }
        val fell = bodyAt(trace, fellAt)
        assertTrue(Physics.isOverCliff(level, fell.state.pos), "FELL at ${fell.state.pos} is not over the cliff (hop mistimed)")

        assertTrue(bodyAt(trace, Scenarios.DAY5.durationMs) === trace.final)
        val end = trace.final.state
        assertEquals(Mode.IDLE, end.mode)
        assertEquals(level.spawn, end.pos)
        assertEquals(0f, end.h)
        assertTrue(Scenarios.DAY5.durationMs <= 30_000)
        assertTrue(trace.events.all { it.first <= Scenarios.DAY5.durationMs }, "events past the end: ${trace.events}")
    }

    @Test
    fun day5FlyVariantCrossesTheGap() {
        val trace = ScenarioRunner.run(Scenarios.DAY5, level, Ability.FLY, dtMs = dtMs)
        println("T19 FLY trace:\n" + describe(trace))
        val names = trace.eventNames()
        val jumped = names.indexOf(JUMPED)
        val landed = names.indexOf(LANDED)
        assertTrue(jumped >= 0 && landed > jumped, "JUMPED then LANDED: $names")
        val landing = bodyAt(trace, trace.events[landed].first)
        assertTrue(landing.state.pos.u < -0.25f, "landed at ${landing.state.pos}")
        assertTrue(trace.bodies.any { it.state.mode == Mode.JUMPING && Physics.isOverCliff(level, it.state.pos) }, "over the gap airborne")
        assertTrue(FELL !in names, "FELL: $names")
        assertTrue(RESPAWNED !in names, "RESPAWNED: $names")
    }

    @Test
    fun playerIssuesEachStepOnceInOrder() {
        val scenario = Scenarios.DAY5
        val player = ScenarioPlayer(scenario, startedAtMs = 0L)
        val batches = listOf(0L, 1_000L, 20_000L, 40_000L).map { now -> player.due(now) to player.finished }
        assertEquals(scenario.steps, batches.flatMap { it.first })
        assertEquals(listOf(scenario.steps.first()), batches[0].first)
        assertEquals(emptyList(), batches[1].first)
        assertEquals(listOf(false, false, false, true), batches.map { it.second })
        assertEquals(scenario.steps.size, player.issued)
        assertEquals(emptyList(), player.due(50_000L), "nothing is issued twice")
    }

    @Test
    fun scenarioJsonRoundTrip() {
        val json = Scenarios.DAY5.toJson()
        println("T19 DAY5 json: $json")
        assertEquals(Scenarios.DAY5, Scenario.fromJson(json))
        assertTrue("\"type\":\"walk\"" in json, json)
        assertTrue("\"type\":\"jump\"" in json, json)
    }

    // ---------------------------------------------------------------- helpers

    /** The body produced by the tick that started at [tMs] (ticks are `i * dtMs`; the last tick is clamped to `durationMs`). */
    private fun bodyAt(trace: ScenarioTrace, tMs: Long): PhysicsBody =
        if (tMs >= Scenarios.DAY5.durationMs) trace.bodies.last() else trace.bodies[(tMs / dtMs).toInt()]

    /** [expected] appears in [actual] as a subsequence (other events may sit in between). */
    private fun assertInOrder(expected: List<CharacterEvent>, actual: List<CharacterEvent>) {
        var i = 0
        for (e in actual) if (i < expected.size && e == expected[i]) i++
        assertEquals(expected.size, i, "expected $expected in order within $actual")
    }

    private fun describe(trace: ScenarioTrace): String = trace.events.joinToString("\n") { (t, e) ->
        val s = bodyAt(trace, t).state
        "  $t ms $e pos=(${s.pos.u}, ${s.pos.v}) h=${s.h} ${s.mode}"
    }
}
