package kz.rudy.ar.core.session

import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.CharacterEvents
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.duel.BotTuning
import kz.rudy.ar.core.duel.Contestant
import kz.rudy.ar.core.duel.DuelArena
import kz.rudy.ar.core.duel.DuelArenas
import kz.rudy.ar.core.duel.DuelBot
import kz.rudy.ar.core.duel.DuelInput
import kz.rudy.ar.core.duel.DuelMatch
import kz.rudy.ar.core.duel.DuelMatchState
import kz.rudy.ar.core.duel.DuelParams
import kz.rudy.ar.core.duel.DuelRules
import kz.rudy.ar.core.duel.DuelRunner
import kz.rudy.ar.core.duel.MatchEvent
import kz.rudy.ar.core.duel.MatchPhase
import kz.rudy.ar.core.duel.RunnerState
import kz.rudy.ar.core.emote.EmotePose
import kz.rudy.ar.core.physics.PhysicsBody
import kz.rudy.ar.core.physics.PhysicsParams
import kz.rudy.ar.core.pipeline.SurfaceBoundary
import kotlin.time.TimeSource

// T58 — a duel as the table engine's second stage. The match is DuelMatch stepped by DuelRunner, as the simulator steps
// it: fixed sub-steps on simulated time, the bots on their tick grid with their own seeds, so a frame's length changes
// nothing. This file only feeds it: the frame clock (dt clamped to MAX_STEP_DT_MS, forgotten on a tracking loss as
// GameLoop forgets it: PAUSED freezes, never resets), the child's input (a table tap is a MoveTo, Jump / Dash / Special
// are commands), a level written between rounds, and what the slot hears (the bodies' transitions, the match, the log).
// Everything that decides reads DuelMatchState, never a per-action event: an event on the frame back from a loss is
// lost (T47's finding). The fighters are members of the walking loop's cast; the loop is not stepped while the duel
// runs, so every member gets its body back when the duel ends.

/** At most this often, host clock, a running duel reports its state to the slot (the cast-state report's period). */
internal const val DUEL_REPORT_PERIOD_MS = 100L

/**
 * The match [setup] asks for, on the level that was scanned when it started: the child's fighter is slot `a`, the
 * opponent slot `b` played by a [DuelBot]; a watched match ([DuelSetup.mineBot]) gives slot `a` a bot too. Built by
 * [open] (the engine's `syncDuel`), which answers null when the level has no room for two. Main thread only.
 */
internal class DuelStage private constructor(
    private val setup: DuelSetup,
    private val loop: GameLoop,
    private val current: () -> TableSlot,
    private val profile: SurfaceProfile,
    private val port: ArPort,
    private val log: (String) -> Unit,
    private val walkDepthSamples: Int,
    arena: Level,
    start: DuelMatchState,
    /** The physics numbers the match runs with, read once at the start (a size change reaches the loop only). */
    val params: DuelParams,
    private val physics: PhysicsParams,
    private val radiusM: Float,
    private val separationM: Float,
    /** How long the marks search took at the start, wall clock: the `duel_start` line's `marks_ms=` (Pixel evidence). */
    val marksMs: Long,
) : Stage {
    val serial: Int get() = setup.serial
    val mineId: String get() = setup.mineId
    val foeId: String get() = setup.foeId

    /** The match rules: the default ones (`ai/v2-human.md` §4.3 decides nothing here). */
    val rules: DuelRules = DuelRules.DEFAULT
    private val tuning = BotTuning()

    /** Where the match is fought: the level at the start, or the last one a rescan moved it to ([levelArrived]). */
    var arena: Level = arena
        private set

    private var runner = RunnerState(
        start,
        botA = setup.mineBot?.let { DuelBot.mind(it, 2L * setup.seed) },
        botB = DuelBot.mind(setup.foeLevel, 2L * setup.seed + 1L),
    )

    /** The match as it stands. */
    val match: DuelMatchState get() = runner.match

    private var lastMs: Long? = null // the frame clock: the last tracked frame's host time; null = empty
    private var move: DuelInput? = null // the last table tap since the last frame that sent one
    private var action: DuelInput? = null // the last Jump / Dash / Special since the last frame that sent one
    private var arrived: Level? = null // a level written while the match runs, not yet the arena
    private var wasTracking = true

    // The fighters' host-side clocks, as GameLoop keeps them per member: a = mine, b = the opponent.
    private var walkA = 0L
    private var walkB = 0L
    private var landedA = NEVER_LANDED
    private var landedB = NEVER_LANDED

    private var reported: DuelMatchState? = null // the instance last handed to onDuel
    private var reportedAtMs = 0L
    private var reportNow = true // the start reports on its own frame

    /** T58 decision 9: a level written while the duel runs; it becomes the arena between rounds (step 2). */
    fun levelArrived(level: Level) {
        arrived = level
    }

    /** No finger picks, grabs or leads anyone in a duel: every touch stream is a tap. */
    override val fingers: Boolean get() = false

    /**
     * A tap is a MoveTo for my fighter, in FIGHT only (else the port is asked nothing): refused past a depth edge as a
     * free-play tap is, in the same order, then clamped into the arena (a tap can neither grow the arena nor walk the
     * fighter off it). Nothing is picked, and a refusal says nothing to the HUD. Kept for the next frame.
     */
    override fun tap(xPx: Float, yPx: Float, uv: Vec2, depthGapM: () -> Float?) {
        if (setup.mineBot != null) return
        val m = runner.match
        if (m.phase != MatchPhase.FIGHT) return
        val from = m.duel.a.body.state.pos
        val depthGap = depthGapM()
        val gaps = port.depthGapsOnWay(from, uv, walkDepthSamples) + depthGap
        if (SurfaceBoundary.crossesEdge(gaps, profile)) {
            log("duel_tap_past_edge u=${uv.u} v=${uv.v}")
            return
        }
        move = DuelInput.MoveTo(arena.nearestInside(uv))
    }

    /** Jump, Dash (along the fighter's facing) and Special for my fighter; the last pressed wins. Anything else is logged. */
    override fun command(c: CharacterCommand, from: TableSlot) {
        val input = if (c.id != setup.mineId || setup.mineBot != null) null else when (c.action) {
            CharacterAction.Jump -> DuelInput.Jump
            CharacterAction.Dash -> DuelInput.Dash()
            CharacterAction.Special -> DuelInput.Special
            CharacterAction.WalkForward, is CharacterAction.Emote -> null
        }
        if (input == null) log("duel_command_ignored serial=${c.serial} id=${c.id} duel=true") else action = input
    }

    override fun step(input: FrameInput) {
        val now = input.nowMs
        val beforeA = runner.match.duel.a.body
        val beforeB = runner.match.duel.b.body
        var events: List<MatchEvent> = emptyList()
        var applied = false
        var rearranged = false
        var back = false
        var dt = 0L
        if (!input.tracking) {
            // 1. Both bodies PAUSED, no clock, no bot; the frame clock forgotten, the kept inputs dropped.
            runner = DuelRunner.step(runner, arena, 0L, tracking = false, rules = rules, params = params, physics = physics, tuning = tuning).state
            lastMs = null
            move = null
            action = null
            wasTracking = false
        } else {
            back = !wasTracking
            wasTracking = true
            // 2. A level written since the start becomes the arena on the first tracked frame between rounds.
            val pending = arrived
            val phase = runner.match.phase
            if (pending != null && (phase == MatchPhase.COUNTDOWN || phase == MatchPhase.POINT)) {
                arrived = null
                rearranged = rearrange(pending)
            }
            // 3. The frame clock, clamped as GameLoop clamps it; a held countdown does not run.
            dt = lastMs?.let { (now - it).coerceIn(0L, MAX_STEP_DT_MS) } ?: 0L
            lastMs = now
            if (current().duel?.hold == true && runner.match.phase == MatchPhase.COUNTDOWN) dt = 0L
            // 4. One input for my fighter, on a frame that steps (a dt = 0 call only resumes): the action first.
            val inputs: Map<String, DuelInput> = if (dt <= 0L || setup.mineBot != null) emptyMap() else takeInput()
            applied = inputs.isNotEmpty()
            val out = DuelRunner.step(runner, arena, dt, inputs, tracking = true, rules = rules, params = params, physics = physics, tuning = tuning)
            runner = out.state
            events = out.events
        }
        // 5. The bodies' transitions to the slot, mine first; the walk and landing clocks as GameLoop keeps them.
        val a = runner.match.duel.a.body
        val b = runner.match.duel.b.body
        if (a.state.mode == Mode.WALKING) walkA += dt
        if (b.state.mode == Mode.WALKING) walkB += dt
        if (a !== beforeA) for (e in CharacterEvents.between(beforeA, a)) {
            if (e == CharacterEvent.LANDED) landedA = now
            current().onCastEvent(setup.mineId, e)
        }
        if (b !== beforeB) for (e in CharacterEvents.between(beforeB, b)) {
            if (e == CharacterEvent.LANDED) landedB = now
            current().onCastEvent(setup.foeId, e)
        }
        // 6. One line per match event (Pixel evidence; nothing reads them).
        if (events.isNotEmpty()) logEvents(events)
        // 7. The report: at most every DUEL_REPORT_PERIOD_MS, at once on a change a child reads; never one instance twice.
        val m = runner.match
        val last = reported
        if (m === last) return
        val atOnce = reportNow || back || applied || rearranged || last == null ||
            m.phase != last.phase || m.round != last.round || m.scoreA != last.scoreA || m.scoreB != last.scoreB
        if (atOnce || now - reportedAtMs >= DUEL_REPORT_PERIOD_MS) {
            reported = m
            reportedAtMs = now
            reportNow = false
            current().onDuel(DuelReport.Running(setup.serial, m, arena, rules, params))
        }
    }

    /** The kept action if there is one (the move then waits a frame), else the kept move; each sent once. */
    private fun takeInput(): Map<String, DuelInput> {
        val a = action
        if (a != null) {
            action = null
            return mapOf(setup.mineId to a)
        }
        val m = move ?: return emptyMap()
        move = null
        return mapOf(setup.mineId to m)
    }

    /**
     * The match moves to [level] between rounds: its marks for the same fighters, each fighter on the new mark nearer
     * its old one (the pairing with the smaller sum of distances; a tie keeps the search's order). No boundary, no
     * marks or marks the match refuses: the old arena stays. True when the arena changed.
     */
    private fun rearrange(level: Level): Boolean {
        if (level.boundary == null) {
            log("duel_arena_kept reason=${DuelRefusal.NO_BOUNDARY}")
            return false
        }
        val clock = TimeSource.Monotonic.markNow()
        val marks = try {
            DuelArena.marks(level, radiusM, separationM, physics)
        } catch (e: IllegalArgumentException) {
            null
        }
        val ms = clock.elapsedNow().inWholeMilliseconds
        if (marks == null) {
            log("duel_arena_kept reason=${DuelRefusal.NO_ROOM} marks_ms=$ms")
            return false
        }
        val m = runner.match
        val (p, q) = marks
        val keep = p.distanceTo(m.startA) + q.distanceTo(m.startB) <= q.distanceTo(m.startA) + p.distanceTo(m.startB)
        val ab = if (keep) p to q else q to p
        val moved = try {
            DuelMatch.rearrange(m, level, ab, physics)
        } catch (e: IllegalArgumentException) {
            log("duel_arena_kept reason=${DuelRefusal.NO_ROOM} marks_ms=$ms")
            return false
        }
        runner = runner.copy(match = moved)
        arena = level
        log("duel_arena marks=${ab.first.u},${ab.first.v},${ab.second.u},${ab.second.v} phase=${m.phase} marks_ms=$ms")
        return true
    }

    private fun logEvents(events: List<MatchEvent>) {
        val m = runner.match
        for (e in events) log(
            when (e) {
                is MatchEvent.RoundStarted -> "duel_round n=${e.round} at_ms=${e.atMs}"
                is MatchEvent.Point ->
                    "duel_point round=${m.lastPoint?.round ?: m.round} winner=${e.winner} reason=${e.reason} at_ms=${e.atMs} " +
                        "score=${m.scoreA}:${m.scoreB}"
                is MatchEvent.MatchOver -> "duel_over winner=${e.winner} at_ms=${e.atMs} rounds=${m.round}"
                is MatchEvent.Fight -> "duel_fight ${e.event}"
            },
        )
    }

    /** Every member in cast order: a fighter with its duel state, everyone else as the loop holds them. */
    override fun standing(): List<CharacterState> =
        loop.cast.members.map { (fighterBody(it.id) ?: it.body).state }.ifEmpty { listOf(loop.state) }

    /** A fighter is drawn unless it is RESPAWNING (out of sight in POINT); everyone else is hidden. */
    override fun shows(id: String): Boolean = fighterBody(id)?.let { it.state.mode != Mode.RESPAWNING } ?: false

    override fun body(id: String): PhysicsBody {
        fighterBody(id)?.let { return it }
        val members = loop.cast.members
        for (i in members.indices) if (members[i].id == id) return members[i].body
        error("member $id is not in the loop") // the pose loop asks only for the loop's own members
    }

    override fun walkPhaseMs(id: String): Long = when (id) {
        setup.mineId -> walkA
        setup.foeId -> walkB
        else -> loop.walkPhaseMs(id)
    }

    override fun landedAtMs(id: String): Long = when (id) {
        setup.mineId -> landedA
        setup.foeId -> landedB
        else -> loop.landedAtMs(id)
    }

    /** No emote plays in a duel. */
    override fun emotePose(id: String): EmotePose? = null

    private fun fighterBody(id: String): PhysicsBody? = when (id) {
        setup.mineId -> runner.match.duel.a.body
        setup.foeId -> runner.match.duel.b.body
        else -> null
    }

    companion object {
        /**
         * Starts the match [setup] asks for on the loop's level (which has a boundary, and both fighters are in the
         * loop and in the slot: the engine checked): marks for the loop's radius and the slot's size, my fighter on the
         * one nearer [camera] (plane coords; a tie = the first), the stats and abilities of the slot's members, the
         * default rules, the size's duel params and the loop's physics. Null when the level has no room for two
         * (no marks, or marks or numbers the match refuses): never throws.
         */
        fun open(
            setup: DuelSetup,
            loop: GameLoop,
            current: () -> TableSlot,
            profile: SurfaceProfile,
            port: ArPort,
            log: (String) -> Unit,
            walkDepthSamples: Int,
            camera: Vec2,
        ): DuelStage? {
            val slot = current()
            val mine = slot.members.firstOrNull { it.id == setup.mineId } ?: return null
            val foe = slot.members.firstOrNull { it.id == setup.foeId } ?: return null
            val arena = loop.level
            val size = slot.characterSize
            val radius = loop.radiusM
            val physics = loop.params
            val separation = DuelArenas.SEPARATION_M * profile.scale * size
            return try {
                val params = DuelParams.forProfile(profile, size)
                val clock = TimeSource.Monotonic.markNow()
                val marks = DuelArena.marks(arena, radius, separation, physics)
                val ms = clock.elapsedNow().inWholeMilliseconds
                if (marks == null) {
                    null
                } else {
                    val (p, q) = marks
                    val ab = if (q.distanceTo(camera) < p.distanceTo(camera)) q to p else p to q
                    val start = DuelMatch.start(
                        Contestant(mine.id, mine.stats, mine.ability),
                        Contestant(foe.id, foe.stats, foe.ability),
                        arena, ab, DuelRules.DEFAULT, params, physics, radius,
                    )
                    DuelStage(setup, loop, current, profile, port, log, walkDepthSamples, arena, start, params, physics, radius, separation, ms)
                }
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}
