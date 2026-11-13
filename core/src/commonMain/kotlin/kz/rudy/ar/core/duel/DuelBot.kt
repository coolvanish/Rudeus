package kz.rudy.ar.core.duel

import kotlinx.serialization.Serializable
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.physics.Physics
import kz.rudy.ar.core.physics.PhysicsParams
import kotlin.math.abs
import kotlin.math.sqrt

/** How well a [DuelBot] plays ([BotSkill]): EASY loses to a child who taps and dashes, HARD seems to think. */
@Serializable
enum class BotLevel { EASY, NORMAL, HARD }

/**
 * Everything a bot remembers between decisions. Immutable and serializable: a saved match resumes with the same bot.
 *
 * @property rng the state of the bot's own random generator (SplitMix64: the same sequence on every platform, and a
 *   plain number in JSON).
 * @property nextThinkMs match time of its next decision; until then it answers [DuelInput.None] and its last orders
 *   (a walk) stand. This is its reaction time.
 * @property wander EASY: the spot it is wandering to until [wanderUntilMs]; null = not wandering.
 * @property push the direction it settled on pushing the opponent (a unit vector), kept so it does not dither; null
 *   before its first decision.
 * @property calmMs T61 ([BotSkill.impatientAfterMs]): FIGHT time since the bot last attacked (a dash, a stomp or a ram
 *   it sent) or since its round began, as of its decision at [calmAtMs] in round [calmRound]; only kept by a level with
 *   impatience (else 0, -1, 0 for ever). It advances only between two of its own decisions in one round: a countdown, a
 *   point pause or a tracking loss does not count.
 */
@Serializable
data class BotMind(
    val level: BotLevel,
    val rng: Long,
    val nextThinkMs: Long = 0L,
    val wander: Vec2? = null,
    val wanderUntilMs: Long = 0L,
    val push: Vec2? = null,
    val calmMs: Long = 0L,
    val calmAtMs: Long = -1L,
    val calmRound: Int = 0,
)

/**
 * The duel's bot: reads a [DuelMatchState] (state only, never events: they can be lost on the first frame after a
 * tracking gap) and answers one [DuelInput] for its fighter. Pure: the same arguments give the same answer on every
 * platform (no clock, no `kotlin.random`, no trigonometry; its randomness is the generator in [BotMind]); that is a
 * promise about one decision, not about a whole match, whose physics uses `exp`. Call it through [DuelRunner], which
 * asks it only on the [BotTuning.tickMs] grid of match time, so a match does not depend on the frame rate. Every
 * number is in [BotTuning] / [BotSkill].
 *
 * **The plan every level shares.** Push the opponent toward the edge nearest to it: stand so that the opponent is
 * between the bot and that edge ([BotTuning.standoffGapRadii] off it), walking round the opponent and round whatever
 * blocks (and every hole) rather than into them; dash when in range ([BotSkill.dashRangeShare] of a dash's reach) and
 * aligned ([BotSkill.alignCos]); keep away from the edge itself (every walk target is [BotTuning.edgeMarginRadii]
 * inside). Outside FIGHT it answers None.
 *
 * **It never gives the game away.** A [DuelInput.MoveTo] target is always inside the boundary by the margin (the
 * boundary is the convex hull a scan builds, so the walk there stays on the table) and off every hole; a dash or a ram
 * is started only when it would end [BotTuning.dashEndMarginRadii] inside with its whole path off every hole, or when
 * the opponent is in its path first with no hole on the way to it; no input carries a non-finite number. (A path is
 * judged against a hole's outline; an end point or a walk target against its bounding circle: safe, coarse for a long
 * strip.)
 *
 * **Levels** ([BotSkill]): EASY is slow to react, wanders, dashes when close whatever the alignment, sometimes the wrong
 * way, never uses the special. NORMAL plays the plan and uses its special as a player would (the T57 review): stomp an
 * opponent in reach that stands and cannot get out of the way by a jump, ram when its facing lines up with the opponent
 * and the push (a step at the opponent turns it first) with room behind itself, lift-off or dodge when a dash or a stomp
 * coming at it would land, wherever it stands, noticing it on a tick it does not think on with
 * [BotSkill.reflexChance]. HARD is NORMAL deciding every tick or a few, leading its aim, preferring the high ground of
 * a PLATFORM, getting out of the way of a dash coming at it near an edge, dodging (HIDE) anywhere and countering after
 * it, and ramming only where a miss still ends on the table or at an opponent that cannot get out of its path. Measured
 * on the tuning arenas with equal stats and ability ([DuelSim]): HARD beats EASY and NORMAL, NORMAL beats EASY, in every
 * ability (`DuelBotTest`).
 *
 * **T57's rules** (each part inert with its rule off, so the bot plays the pre-T57 game bit for bit there). Height
 * matters for hits: it jumps over a dash or a stomp coming at it when a jump gets its feet out of reach in time and keeps
 * them there until the hit has passed ([BotSkill.jumpChance]: EASY never, NORMAL half the time, HARD always, watching
 * on every tick, [BotSkill.reflexes]; a ROLL cannot jump); it never dashes, counters, rams or stomps at a fighter out of
 * height reach (feet predicted along a jump's arc); when the spot behind the other is out of reach (a fighter on a book)
 * it pushes from the first spot round it that is not, else walks up at it; a HARD bot dashes at a fighter that could jump
 * only from close enough that it cannot ([BotSkill.closesInOnJumpers]). The stomp and the ram share the dash: with both
 * ready and its situation there, the special goes first; a stomp only at a fighter on the ground that no jump gets out
 * of in time ([DuelParams.stompReachUpM] is above a jump's apex, so only a FLY's float, unless it is stunned or dashing
 * through the landing; a lift-off or a dodge is the stomp's risk, not a reason not to try); a JUMP whose stomp is ready
 * jumps a dash as a stomp. A lift-off or a dodge only against a hit that would land. SPEED lengthens the dash: every
 * reach is the bot's own. Its walk is planned at the ground it will come down on, with the rules off too (the pre-T57
 * code planned a bot in the air at its feet: the one part not behind a switch; the recorded pre-T57 matches of
 * `theNewRulesSwitchedOffAreThePreviousPhysics` do not tell the two apart).
 *
 * **T61: impatience** ([BotSkill.impatientAfterMs], NORMAL and HARD). A bot that has not attacked for that long of
 * FIGHT ([BotMind.calmMs]) attacks from where it stands, whatever stops its plan (a closed gap, a spot inside a cup's
 * circle, a push into an obstacle): while the opponent can be hit and is within a dash's height reach, it walks at the
 * opponent and dashes whatever the alignment, as EASY does, until it attacks; then it plays its plan again. Every safety
 * rule above still holds (no dash off the table or over a hole); against a fighter out of reach (on a book) it keeps its
 * plan. While impatient it walks straight at the opponent when that line touches no object's body, and keeps out of
 * objects by their bodies only (no clearance), so a gap its body fits through is a way. NORMAL and HARD after 16 s;
 * off (0, EASY) it is the bot before T61, decision for decision.
 */
object DuelBot {

    /** A fresh mind for [level]; [seed] starts its generator (two bots in one match need two seeds). */
    fun mind(level: BotLevel, seed: Long): BotMind = BotMind(level, rng = seed)

    /**
     * What fighter [me] wants at `state.duel.timeMs`, and the mind to ask next time. Pure: the same arguments give the
     * same answer on every platform. [me] not in the match, a level without a boundary, or a phase other than FIGHT:
     * [DuelInput.None] and the mind unchanged. Pass the [params] and [physics] the match runs with.
     */
    fun decide(
        mind: BotMind,
        me: String,
        state: DuelMatchState,
        arena: Level,
        params: DuelParams = DuelParams.DEFAULT,
        physics: PhysicsParams = PhysicsParams.DEFAULT,
        tuning: BotTuning = BotTuning(),
    ): Pair<BotMind, DuelInput> {
        if (state.phase != MatchPhase.FIGHT) return mind to DuelInput.None
        val duel = state.duel
        val self = duel[me] ?: return mind to DuelInput.None
        val foe = if (duel.a.id == me) duel.b else duel.a
        val boundary = arena.boundary ?: return mind to DuelInput.None
        val now = duel.timeMs
        if (now < mind.nextThinkMs) {
            // T57: a bot with reflexes watches for a dash or a stomp it can jump over on every tick, thinking or not.
            val skill = tuning.skill(mind.level)
            if ((!skill.reflexes && skill.reflexChance <= 0f) || !params.reachLimited || (foe.dashMs <= 0L && !foe.stomping)) {
                return mind to DuelInput.None
            }
            val rng = Rng(mind.rng)
            val answer = Brain(self, foe, arena, boundary, params, physics, tuning, skill, rng, mind, now).reflex() ?: return mind to DuelInput.None
            val thought = mind.copy(rng = rng.state)
            // T61: a stomp-jump on a reflex tick is an attack too (the clock is the bot's own, kept only with impatience).
            return (if (skill.impatientAfterMs > 0L && attacks(answer, self)) thought.copy(calmMs = 0L, calmAtMs = now, calmRound = state.round) else thought) to answer
        }
        val skill = tuning.skill(mind.level)
        val rng = Rng(mind.rng)
        val wait = skill.thinkMs + tuning.tickMs * rng.int(skill.thinkJitterTicks + 1)
        // T61: FIGHT time since its last attack, counted between its own decisions of this round.
        val patience = skill.impatientAfterMs
        val calm = if (patience > 0L && mind.calmRound == state.round && mind.calmAtMs >= 0L) mind.calmMs + (now - mind.calmAtMs) else 0L
        val brain = Brain(self, foe, arena, boundary, params, physics, tuning, skill, rng, mind, now, impatient = patience > 0L && calm >= patience)
        val input = brain.checked(brain.think())
        var next = mind.copy(
            rng = rng.state,
            nextThinkMs = now + maxOf(wait, 1L),
            wander = brain.wander,
            wanderUntilMs = brain.wanderUntilMs,
            push = brain.push ?: mind.push,
        )
        if (patience > 0L) next = next.copy(calmMs = if (attacks(input, self)) 0L else calm, calmAtMs = now, calmRound = state.round)
        return next to input
    }

    /** T61: [input] from [self] starts an attack: a dash (a counter too), or the special of a JUMP (stomp) or a ROLL (ram). */
    private fun attacks(input: DuelInput, self: DuelFighter): Boolean =
        input is DuelInput.Dash || (input == DuelInput.Special && (self.ability == Ability.JUMP || self.ability == Ability.ROLL))
}

/** SplitMix64 (Steele, Lea, Flood 2014): a few lines, wraps the same way on every platform. Local scratch only. */
private class Rng(var state: Long) {
    fun next(): Long {
        state += GOLDEN
        var z = state
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1), 24 bits. */
    fun float(): Float = (next() ushr 40).toFloat() / 16_777_216f

    /** Uniform in [-1, 1). */
    fun signed(): Float = float() * 2f - 1f

    /** True with probability [p]; draws nothing when [p] is 0. */
    fun chance(p: Float): Boolean = p > 0f && float() < p

    /** Uniform in 0 until [n]; draws nothing when [n] <= 1. */
    fun int(n: Int): Int = if (n <= 1) 0 else ((next() ushr 33) % n).toInt()

    private companion object {
        const val GOLDEN = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        const val MIX1 = -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        const val MIX2 = -0x6b2fb644ecceee15L // 0x94D049BB133111EB
    }
}

/**
 * A circle the bot keeps out of: a blocking object or a hole, as its bounding circle ([bare] radius) grown by what it
 * needs ([r]). A hole also keeps its [outline], for the exact test of a dash's path.
 */
private class Keep(val c: Vec2, val r: Float, val hole: Boolean, val bare: Float, val outline: Polygon? = null)

/** The boundary as edge lines with inward normals (exact inside distances for the convex hull a scan builds). */
private class Board(val poly: Polygon) {
    val n = poly.size
    private val ax = FloatArray(n)
    private val ay = FloatArray(n)
    private val nx = FloatArray(n)
    private val ny = FloatArray(n)
    private val used = BooleanArray(n)

    init {
        val ccw = poly.signedArea() >= 0f
        val pts = poly.points
        for (i in 0 until n) {
            val a = pts[i]
            val b = pts[(i + 1) % n]
            val eu = b.u - a.u
            val ev = b.v - a.v
            val len = sqrt(eu * eu + ev * ev)
            ax[i] = a.u
            ay[i] = a.v
            if (len > Vec2.EPS) {
                used[i] = true
                nx[i] = if (ccw) -ev / len else ev / len
                ny[i] = if (ccw) eu / len else -eu / len
            }
        }
    }

    private fun line(i: Int, u: Float, v: Float): Float = (u - ax[i]) * nx[i] + (v - ay[i]) * ny[i]

    /** The edge whose line is nearest inside (the one [p] would cross first going straight out). */
    fun nearest(p: Vec2): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in 0 until n) {
            if (!used[i]) continue
            val d = line(i, p.u, p.v)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /** Distance inside the boundary (negative outside), by the edge lines. */
    fun inside(p: Vec2): Float {
        val i = nearest(p)
        return if (i < 0) 0f else line(i, p.u, p.v)
    }

    /** The outward normal of edge [i]: the way that edge's side of the table ends. */
    fun outward(i: Int): Vec2 = Vec2(-nx[i], -ny[i])

    /** How far [p] can go along unit [d] before it leaves the board. */
    fun exit(p: Vec2, d: Vec2): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until n) {
            if (!used[i]) continue
            val rate = d.u * nx[i] + d.v * ny[i]
            if (rate >= 0f) continue
            val t = line(i, p.u, p.v) / -rate
            if (t < best) best = t
        }
        return if (best == Float.MAX_VALUE) 0f else best.coerceAtLeast(0f)
    }

    /** [p] moved straight in until it is at least [margin] inside every edge line (a few passes for the corners). */
    fun clamp(p: Vec2, margin: Float): Vec2 {
        var u = p.u
        var v = p.v
        repeat(4) {
            var moved = false
            for (i in 0 until n) {
                if (!used[i]) continue
                val d = line(i, u, v)
                if (d < margin) {
                    u += nx[i] * (margin - d)
                    v += ny[i] * (margin - d)
                    moved = true
                }
            }
            if (!moved) return Vec2(u, v)
        }
        return Vec2(u, v)
    }
}

/** One decision: the context and the plan of [DuelBot]. Local scratch, never visible outside [DuelBot.decide]. */
private class Brain(
    val self: DuelFighter,
    val foe: DuelFighter,
    val arena: Level,
    val boundary: Polygon,
    val params: DuelParams,
    val physics: PhysicsParams,
    val t: BotTuning,
    val skill: BotSkill,
    val rng: Rng,
    mind: BotMind,
    val now: Long,
    /** T61: the bot has not attacked for [BotSkill.impatientAfterMs] of FIGHT. */
    private val impatient: Boolean = false,
) {
    var wander: Vec2? = mind.wander
    var wanderUntilMs: Long = mind.wanderUntilMs
    var push: Vec2? = null
    private val lastPush: Vec2? = mind.push

    val me: Vec2 = self.body.state.pos
    val them: Vec2 = foe.body.state.pos
    val r: Float = self.body.state.radiusM
    private val contact = r + foe.body.state.radiusM
    private val board = Board(boundary)

    /** T57 rule 2: this bot's own dash speed and reaches (SPEED lengthens them); the opponent's dash speed. */
    private val dashSpeed = params.dashSpeedMpsFor(self.stats)
    private val foeDashSpeed = params.dashSpeedMpsFor(foe.stats)
    private val dashReach = dashSpeed * params.dashMs / 1000f
    private val ramReach = dashSpeed * params.ramDashMs / 1000f
    private val toFoe = them - me
    private val dist = toFoe.length
    private val gap = dist - contact
    private val foeHittable = !DuelPhysics.isImmune(foe) && !DuelPhysics.isOut(foe, arena) && foe.body.state.mode != Mode.PAUSED

    /** My feet now (a dash starts only on the ground, so they stay there through it unless a step changes them). */
    private val myH = self.body.state.h

    /** Walk targets keep this far inside (less on a table too small for it). */
    private val margin: Float = minOf(t.edgeMarginRadii * r, 0.5f * board.inside(boundary.centroid()).coerceAtLeast(0f))

    /**
     * The feet height its walk is planned at: where it stands, or, in a jump or a fall (T57: a bot jumps over dashes now;
     * a FLY's float goes higher than a cup), the ground under it, where it will come down and walk on.
     */
    private val planH: Float = self.body.state.let { s ->
        if ((s.mode == Mode.JUMPING || s.mode == Mode.FALLING) && self.body.verticalVelocity != 0f) {
            Physics.groundHeight(arena, s.pos, s.h, physics) ?: s.h
        } else {
            s.h
        }
    }

    /**
     * T61: impatient ([BotSkill.impatientAfterMs]) at an opponent it can hit and that is within a dash's height reach (a
     * fighter on a book out of reach is left to the plan: a dash at it could only miss).
     */
    private val urge: Boolean = impatient && foeHittable && reachable(dashSpeed, params.dashMs)

    /**
     * What it keeps out of: every hole (its bounding circle plus [margin]) and every object that blocks at [planH] (its
     * bounding circle plus the bot's radius and [BotTuning.clearanceRadii]; T61: while it [urge]s, without the clearance,
     * so that it goes through a gap its body fits through, as between two cups).
     */
    private val keeps: List<Keep> = arena.objects.mapNotNull { o ->
        val hole = o.kind == ObjectKind.CLIFF
        if (!hole && !Physics.blocks(o, planH, physics)) return@mapNotNull null
        val c = o.footprint.centroid()
        var bare = 0f
        for (p in o.footprint.points) bare = maxOf(bare, p.distanceTo(c))
        Keep(c, bare + if (hole) margin else r + (if (urge) 0f else t.clearanceRadii * r), hole, bare, if (hole) o.footprint else null)
    }

    fun think(): DuelInput {
        if (!DuelPhysics.acceptsInput(self, arena, params) || DuelPhysics.isOut(foe, arena)) return DuelInput.None
        val canDash = DuelPhysics.canDash(self, arena, params)
        val canSpecial = skill.usesSpecial && DuelPhysics.canSpecial(self, arena, params)
        val ability = self.ability

        // 1. Something coming at it.
        incoming()?.let { threat ->
            val (dir, stomp) = threat
            val nearEdge = board.inside(me) <= skill.nearEdgeRadii * r
            // T57: a lift-off or a dodge only against a hit that would land (not one out of height reach).
            val defend = canSpecial && (ability == Ability.FLY || ability == Ability.HIDE) && threatLands() &&
                (nearEdge || (skill.dodgesAnywhere && ability == Ability.HIDE))
            // A HIDE's dodge comes first (it opens the counter); then a jump that clears it (T57: it costs nothing, a
            // FLY keeps its lift for later; a JUMP whose stomp is ready jumps as a stomp, the same dodge with a hit on
            // landing); then the lift-off.
            if (defend && ability == Ability.HIDE) return DuelInput.Special
            jumpOver()?.let { jump -> return if (ability == Ability.JUMP && canSpecial) DuelInput.Special else jump }
            if (defend) return DuelInput.Special
            // Away from the edge a hit costs a few centimetres and leaves the attacker on cooldown: take it.
            if (skill.sidesteps && board.inside(me) <= skill.sidestepEdgeRadii * r) evade(dir, stomp, canDash)?.let { return it }
        }

        // 2. A counter while the window is open.
        if (skill.counters && self.counterMs > 0L && canDash && foeHittable && gap <= dashReach && clearLine(me, them) && reachable(dashSpeed, params.dashMs)) {
            val dir = aim()
            if (dashSafe(dir, dashReach)) return DuelInput.Dash(dir)
        }

        // T57 rule 3: a push from a spot out of height reach of the opponent (a fighter on a book, the spot behind it on
        // the table) cannot be made: push from a spot round it that can, the way that spot faces.
        val push = climb(choosePush())
        this.push = push

        // T57 rule 1: a stomp or a ram spends the dash too, so when it is ready and has its situation it goes first (a
        // plain dash now would put it off for a whole dash cooldown); else the dash, as before.
        val shared = params.sharesDashCooldown(ability)
        if (shared && canSpecial && foeHittable) special(push)?.let { return it }

        // 3. The dash: not at a fighter out of reach in height (T57 rule 3), and against one that could jump over it,
        // for a bot that knows, only from close. T61: an impatient bot dashes whatever the alignment.
        if (canDash && foeHittable && gap <= dashReach * skill.dashRangeShare && clearLine(me, them) &&
            reachable(dashSpeed, params.dashMs) && !jumpable()
        ) {
            var dir = aim()
            if (skill.alignCos <= -1f || urge || dir.dot(push) >= skill.alignCos) {
                if (skill.aimErrorShare > 0f) dir = (dir + dir.perp() * (rng.signed() * skill.aimErrorShare)).normalized()
                if (rng.chance(skill.wrongWayChance)) dir = randomDirection()
                if (dir != Vec2.ZERO && dashSafe(dir, dashReach)) return DuelInput.Dash(dir)
            }
        }

        // 4. The special in its obvious situation.
        if (!shared && canSpecial && foeHittable) special(push)?.let { return it }

        // 5. Walk: to the spot behind the opponent, or wherever it wanders; when no spot round it is within height reach
        // (T57), at it, up onto whatever it stands on. T61: an impatient bot walks at it, straight when that line touches
        // nothing that blocks (the keep-out circles' margins would send it round a cup it stands next to).
        if (urge && keeps.none { k -> near(me, them, k.c, k.bare + r) }) return DuelInput.MoveTo(safe(them))
        val behind = them - push * (contact + t.standoffGapRadii * r)
        val goal = wanderGoal() ?: if (urge || (climbing && !fromSpot(behind))) them else behind
        return DuelInput.MoveTo(route(goal))
    }

    /** The stomp or the ram, when its obvious situation holds ([think]'s step 4); null otherwise. */
    private fun special(push: Vec2): DuelInput? {
        when (self.ability) {
            Ability.JUMP -> {
                val reach = params.stompRadiusRadii * r * t.stompReachShare
                if (dist <= reach && (dist < Vec2.EPS || (toFoe / dist).dot(push) >= 0f) && stompReaches() && stompHasAChance()) {
                    return DuelInput.Special
                }
            }
            Ability.ROLL -> {
                if (ramReady(push)) return DuelInput.Special
                // T57: the ram goes along the facing the last walk left, and a ram spends the dash too: when it would be
                // ready facing the opponent, a short step at it turns the roller first, and it rams on its next thought.
                if (params.sharesDashCooldown(Ability.ROLL) && params.reachLimited && dist > Vec2.EPS) {
                    val at = toFoe / dist
                    if (ramReady(push, at)) return DuelInput.MoveTo(me + at * (t.turnStepRadii * r))
                }
            }
            else -> Unit
        }
        return null
    }

    /**
     * T57: on a tick it does not think on ([BotSkill.reflexes]), only this: a jump over a dash or a stomp coming at it,
     * when one clears it ([jumpOver]); null = nothing.
     */
    fun reflex(): DuelInput? {
        if (!DuelPhysics.acceptsInput(self, arena, params) || DuelPhysics.isOut(foe, arena)) return null
        if (incoming() == null) return null
        // A bot without full reflexes (NORMAL) notices it on this tick only with [BotSkill.reflexChance].
        if (!skill.reflexes && !rng.chance(skill.reflexChance)) return null
        val ability = self.ability
        val canSpecial = skill.usesSpecial && DuelPhysics.canSpecial(self, arena, params)
        val defend = canSpecial && (ability == Ability.FLY || ability == Ability.HIDE) && threatLands() &&
            (board.inside(me) <= skill.nearEdgeRadii * r || (skill.dodgesAnywhere && ability == Ability.HIDE))
        // The order of [think]'s step 1: a HIDE's dodge, a jump (a JUMP's as a stomp), a FLY's lift-off.
        if (defend && ability == Ability.HIDE) return DuelInput.Special
        jumpOver()?.let { jump -> return if (ability == Ability.JUMP && canSpecial) DuelInput.Special else jump }
        return if (defend) DuelInput.Special else null
    }

    /**
     * [input] as sent: finite, a walk target inside by the margin and off every hole ([fitTarget]), a unit dash
     * direction; anything else is None.
     */
    fun checked(input: DuelInput): DuelInput = when (input) {
        is DuelInput.MoveTo -> if (fitTarget(input.target)) input else DuelInput.None
        is DuelInput.Dash -> {
            val d = input.dir
            if (d != null && finite(d) && abs(d.length - 1f) < 1e-3f) input else DuelInput.None
        }
        else -> input
    }

    // -- reading the opponent ------------------------------------------------------------------------------------

    /** The direction a hit coming at the bot would push it, and whether it is a stomp; null when nothing comes. */
    private fun incoming(): Pair<Vec2, Boolean>? {
        if (DuelPhysics.isImmune(self)) return null
        val slack = t.threatSlackRadii * r
        if (foe.dashMs > 0L) {
            val d = foe.dashDir
            val rel = me - them
            val along = rel.dot(d)
            val lateral = abs(rel.dot(d.perp()))
            val left = foeDashSpeed * foe.dashMs / 1000f
            if (along > 0f && lateral < contact + slack && along - contact <= left + slack) return d to false
        }
        if (foe.stomping && dist <= params.stompRadiusRadii * foe.body.state.radiusM + slack) {
            val away = if (dist > Vec2.EPS) (me - them) / dist else Vec2(1f, 0f)
            return away to true
        }
        return null
    }

    /** Unit aim at the opponent; a bot that leads aims where a walking opponent will be when the dash meets it. */
    private fun aim(): Vec2 {
        var at = them
        if (skill.leadsAim && foe.dashMs == 0L) {
            val s = foe.body.state
            val target = s.target
            var v = foe.knock * t.knockLeadShare
            if (target != null) {
                val d = target - s.pos
                val len = d.length
                if (len > Vec2.EPS) {
                    var speed = params.walkSpeedMpsFor(foe.stats, physics)
                    if (DuelPhysics.isLifted(foe)) speed *= params.liftSpeedScale
                    else if (s.mode == Mode.JUMPING) speed *= physics.jumpSpeedScale
                    v += d / len * minOf(speed, len * 1000f / maxOf(1f, params.dashMs.toFloat()))
                }
            }
            val ahead = gap.coerceAtLeast(0f) / maxOf(dashSpeed, 1e-3f) * t.leadShare
            at = them + v * ahead
        }
        val d = at - me
        return if (d.length > Vec2.EPS) d.normalized() else (if (dist > Vec2.EPS) toFoe / dist else self.body.state.facing.normalized())
    }

    // -- where to push -------------------------------------------------------------------------------------------

    /**
     * The direction to push the opponent: toward its nearest edge; a bot that weighs ([BotSkill.weighsPush]) picks the
     * best of that direction, the ones [TURN_COS] lists off it and the one it settled on before, among those whose
     * spot keeps the bot at least as far inside as the opponent (so the opponent stays between it and the edge). A wider
     * choice made a bot that decides every tick dither round the opponent, or walk along its own edge, and lose.
     */
    private fun choosePush(): Vec2 {
        val edge = board.nearest(them)
        val nearest = if (edge < 0) Vec2(1f, 0f) else board.outward(edge)
        if (!skill.weighsPush) return nearest
        val standoff = contact + t.standoffGapRadii * r
        val ring = contact + t.clearanceRadii * r
        val bearing = if (dist > Vec2.EPS) (me - them) / dist else Vec2.ZERO
        val foeDepth = board.inside(them)
        var best = nearest
        var bestCost = Float.MAX_VALUE
        // The nearest-edge push stands (as NORMAL's does, its spot clamped on the way); only a spot with the high ground,
        // as deep inside as the opponent, may replace it.
        for (k in 0 until TURN_COS.size + 1) {
            val d = if (k < TURN_COS.size) {
                Vec2(nearest.u * TURN_COS[k] - nearest.v * TURN_SIN[k], nearest.u * TURN_SIN[k] + nearest.v * TURN_COS[k])
            } else {
                lastPush?.takeIf { it.dot(nearest) >= TURN_COS.last() } ?: continue
            }
            val spot = them - d * standoff
            val depth = board.inside(spot)
            if (k != 0 && (depth < margin || depth < foeDepth || keeps.any { k -> spot.distanceTo(k.c) < k.r })) continue
            var exit = board.exit(them, d)
            // Something in the way stops the push before the edge (a wall slam, not a ring-out).
            val end = them + d * exit
            val blocked = keeps.any { k -> !k.hole && near(them, end, k.c, k.bare + contact * 0.5f) }
            if (blocked) exit += t.blockedPushRadii * r
            val ground = Physics.groundHeight(arena, spot, self.body.state.h, physics)
            val high = ground != null && ground - foe.body.state.h > params.fromAboveMinM
            if (k != 0 && !high) continue
            val walk = spot.distanceTo(me) + (1f - bearing.dot(-d)) * ring
            var cost = exit + t.walkCost * walk
            if (high) cost -= t.highGroundBonusRadii * r
            val settled = lastPush
            if (settled != null && d.dot(settled) < t.sameDirectionCos) cost += t.switchCostRadii * r
            if (cost < bestCost) {
                bestCost = cost
                best = d
            }
        }
        return best
    }

    // -- the special ----------------------------------------------------------------------------------------------

    /** ROLL: aligned along its facing with the opponent near an edge ahead, and room behind itself. */
    private fun ramReady(push: Vec2, facing: Vec2 = self.body.state.facing.normalized()): Boolean {
        if (facing == Vec2.ZERO || dist < Vec2.EPS) return false
        val ahead = toFoe / dist
        // T57: a ram spends the dash too, and goes along the facing a walk left it with: with the rules on it may start
        // within [BotTuning.ramAlignCos] of the opponent (the in-path checks below still decide whether it can hit).
        val align = if (params.sharesDashCooldown(Ability.ROLL) && params.reachLimited) minOf(skill.alignCos, t.ramAlignCos) else skill.alignCos
        if (facing.dot(ahead) < align || facing.dot(push) < align) return false
        // A bot that only rams when a miss would still end on the table rams wherever that holds; the others wait for the
        // opponent near an edge (where a ram that misses carries them off, unless the opponent is in its path).
        if (!skill.safeRamsOnly && board.inside(them) > t.ramNearEdgeRadii * r) return false
        if (board.inside(me) < t.ramRoomRadii * r) return false
        if (gap > ramReach * skill.dashRangeShare || abs(toFoe.dot(facing.perp())) > t.inPathLateralRadii * r) return false
        if (!clearLine(me, them) || !reachable(dashSpeed, params.ramDashMs)) return false
        // The checks of a plain dash, holes included: a miss runs the whole ram.
        if (!skill.safeRamsOnly) return dashSafe(facing, ramReach)
        // T57: a bot that rams only where a miss stays on the table also rams a fighter in its path that cannot get out of
        // it (too close to jump over it, no lift-off or dodge ready): that ram cannot miss. (With the rules off: as before.)
        return runsOnTable(facing, ramReach) || (params.reachLimited && params.sharesDashCooldown(Ability.ROLL) &&
            inPath(facing, ramReach) && !jumpable(always = true) && !foeCanDodge())
    }

    /** The opponent has its lift-off or dodge ready (FLY, HIDE): a hit at it may pass. */
    private fun foeCanDodge(): Boolean =
        (foe.ability == Ability.FLY || foe.ability == Ability.HIDE) && foe.specialCooldownMs <= 0L && foe.specialMs <= 0L


    // -- getting out of the way -----------------------------------------------------------------------------------

    /** HARD: a dash across an incoming dash (away from a stomp), else a step aside; null when there is no safe way. */
    private fun evade(dir: Vec2, stomp: Boolean, canDash: Boolean): DuelInput? {
        val rel = me - them
        val side = if (rel.dot(dir.perp()) >= 0f) 1f else -1f
        val across = dir.perp() * side
        val ways = if (stomp) {
            listOf(dir, (dir + across).normalized(), (dir - across).normalized())
        } else {
            listOf(across, -across, (across + dir * -0.5f).normalized(), (-across + dir * -0.5f).normalized())
        }
        if (canDash) {
            for (w in ways) {
                if (w == Vec2.ZERO) continue
                // Not into the attacker, and never off the table (no "opponent in the path" excuse here).
                if (w.dot(toFoe) > 0f && abs(toFoe.dot(w.perp())) < contact) continue
                if (runsOnTable(w, dashReach) && clearLine(me, me + w * dashReach)) return DuelInput.Dash(w)
            }
        }
        for (w in ways.take(2)) {
            val spot = safe(me + w * (t.sidestepRadii * r))
            if (spot.distanceTo(me) >= r) return DuelInput.MoveTo(spot)
        }
        return null
    }

    // -- height (T57 rule 3) ----------------------------------------------------------------------------------------

    /** Gravity on [f]'s body (the walking game's; a FLY's is scaled down). */
    private fun gravity(f: DuelFighter): Float =
        physics.gravityMps2 * if (f.body.state.ability == Ability.FLY) physics.flyGravityScale else 1f

    /**
     * [f]'s feet [tS] seconds from now by its vertical motion alone: a jump or a fall follows its arc down to the ground
     * under it (over a hole it goes on falling); a lifted FLY hovers; anything else stays where it is.
     */
    private fun feetIn(f: DuelFighter, tS: Float): Float {
        val s = f.body.state
        if ((s.mode != Mode.JUMPING && s.mode != Mode.FALLING) || DuelPhysics.isLifted(f)) return s.h
        val vz = f.body.verticalVelocity
        val g = gravity(f)
        val h = s.h + vz * tS - 0.5f * g * tS * tS
        val ground = Physics.groundHeight(arena, s.pos, s.h, physics) ?: return h
        return if (h <= ground && vz - g * tS <= 0f) ground else h
    }

    /**
     * A dash of this bot at [speed] lasting [durationMs] would find the opponent within height reach at some point of it:
     * at the first contact, half way from there to the dash's end, or at its end (a jumper may be coming down). Always
     * true with the reach unlimited.
     */
    private fun reachable(speed: Float, durationMs: Long): Boolean {
        if (!params.reachLimited) return true
        val first = gap.coerceAtLeast(0f) / maxOf(speed, 1e-3f)
        val end = maxOf(first, durationMs / 1000f)
        for (tS in floatArrayOf(first, (first + end) * 0.5f, end)) if (params.inReach(myH, feetIn(foe, tS))) return true
        return false
    }

    /**
     * The seconds after a jump of [f] starts (from its feet now) between which its feet are more than [above] m higher;
     * null when a jump never gets that high or [f]'s body cannot jump (ROLL).
     */
    private fun jumpWindow(f: DuelFighter, above: Float): Pair<Float, Float>? {
        if (f.body.state.ability == Ability.ROLL) return null
        val v0 = physics.jumpVelocityMps
        val g = gravity(f)
        if (!(g > 0f)) return null
        if (above <= 0f) return 0f to 2f * v0 / g
        val disc = v0 * v0 - 2f * g * above
        if (!(disc > 0f)) return null
        val root = sqrt(disc)
        return (v0 - root) / g to (v0 + root) / g
    }

    /** When a hit coming at this bot could land ([from] .. [until], seconds from now), the attacker's feet then, its up-reach. */
    private class Threat(val from: Float, val until: Float, val attackerH: Float, val upM: Float)

    /**
     * When the hit coming at this bot could land: a dash from its first contact to where its circle has passed or the
     * dash ends, a stomp at its landing; with the attacker's feet then and the hit's up-reach. Null when nothing comes.
     */
    private fun threatWindow(): Threat? {
        if (foe.dashMs > 0L) {
            val d = foe.dashDir
            val rel = me - them
            val along = rel.dot(d)
            val lateral = abs(rel.dot(d.perp()))
            if (along <= 0f || lateral >= contact) return null
            val half = sqrt(contact * contact - lateral * lateral)
            val speed = maxOf(foeDashSpeed, 1e-3f)
            val end = foe.dashMs / 1000f
            val from = (along - half) / speed
            if (from > end) return null
            return Threat(from, minOf((along + half) / speed, end), foe.body.state.h, params.hitReachUpM)
        }
        if (foe.stomping) {
            if (dist > params.stompRadiusRadii * foe.body.state.radiusM + t.threatSlackRadii * r) return null
            val s = foe.body.state
            val ground = Physics.groundHeight(arena, s.pos, s.h, physics) ?: return null
            val g = gravity(foe)
            val vz = foe.body.verticalVelocity
            val disc = vz * vz + 2f * g * (s.h - ground)
            if (!(g > 0f) || disc < 0f) return null
            val land = (vz + sqrt(disc)) / g
            return Threat(land, land, ground, params.stompReachUpM)
        }
        return null
    }

    /**
     * A dash or a stomp is coming at this bot and a jump now gets its feet out of the hit's reach before the hit can land
     * and keeps them out until it has passed ([BotTuning.jumpMarginMs] either side): [DuelInput.Jump], with
     * [BotSkill.jumpChance]. Null (and no random draw) when the reach is unlimited, the body cannot jump or is not on the
     * ground, or a jump would not clear it.
     */
    private fun jumpOver(): DuelInput? {
        if (!params.reachLimited || skill.jumpChance <= 0f) return null
        val s = self.body.state
        if (s.ability == Ability.ROLL || (s.mode != Mode.IDLE && s.mode != Mode.WALKING)) return null
        val w = threatWindow() ?: return null
        val from = w.from
        val until = w.until
        val (up, down) = jumpWindow(self, w.attackerH + w.upM - myH) ?: return null
        val margin = t.jumpMarginMs / 1000f
        if (from < up + margin || until > down - margin) return null
        return if (rng.chance(skill.jumpChance)) DuelInput.Jump else null
    }

    /**
     * [BotSkill.closesInOnJumpers]: the opponent could jump over a dash started now: its body can jump, it stands and
     * takes input, and reacting on the next tick its feet would be out of reach before the dash meets it. Always false
     * with the reach unlimited, or for a bot that does not know.
     */
    private fun jumpable(always: Boolean = false): Boolean {
        if ((!always && !skill.closesInOnJumpers) || !params.reachLimited) return false
        val s = foe.body.state
        if (s.ability == Ability.ROLL || (s.mode != Mode.IDLE && s.mode != Mode.WALKING) || !DuelPhysics.acceptsInput(foe, arena, params)) return false
        val (up, _) = jumpWindow(foe, myH + params.hitReachUpM - s.h) ?: return false
        val meet = gap.coerceAtLeast(0f) / maxOf(dashSpeed, 1e-3f)
        return meet > (t.tickMs + t.jumpMarginMs) / 1000f + up
    }

    /** JUMP: the stomp's landing, one jump from now where it stands, would find the opponent within height reach. */
    private fun stompReaches(): Boolean {
        if (!params.reachLimited) return true
        val air = 2f * physics.jumpVelocityMps / maxOf(gravity(self), 1e-3f)
        return params.inReach(myH, feetIn(foe, air), params.stompReachUpM)
    }

    /**
     * JUMP, T57: the stomp (it spends the dash too) has a real chance to land: the opponent stands on the ground and its
     * body cannot jump clear of the stomp's reach in time (`stompReachUpM` above a jump's apex leaves that to a FLY's float,
     * and to nobody stunned or dashing until too close to the landing). A lift-off or a dodge may still get out of its way:
     * that is the stomp's risk (and costs the other its special), not a reason not to try. Always true with either rule off
     * (the stomp as before T57).
     */
    private fun stompHasAChance(): Boolean {
        if (!params.reachLimited || !params.sharesDashCooldown(Ability.JUMP)) return true
        val s = foe.body.state
        if (s.mode != Mode.IDLE && s.mode != Mode.WALKING) return false
        val air = 2f * physics.jumpVelocityMps / maxOf(gravity(self), 1e-3f)
        val (up, down) = jumpWindow(foe, myH + params.stompReachUpM - foeFeet) ?: return true
        // A jump started from when it can act (its stun or dash over) clears the landing if high by then and not yet down.
        val busy = maxOf(foe.stunMs, foe.dashMs) / 1000f
        val margin = t.jumpMarginMs / 1000f
        val latest = air - up - margin
        val earliest = air - down + margin
        return busy > latest || latest < earliest
    }

    /** T57: the hit coming at this bot would reach its feet (always with the reach unlimited, or when unknown). */
    private fun threatLands(): Boolean {
        val w = threatWindow() ?: return true
        return params.inReach(w.attackerH, myH, w.upM)
    }

    /** The opponent's feet where it stands, or the ground it will come down on. */
    private val foeFeet: Float by lazy(LazyThreadSafetyMode.NONE) {
        val s = foe.body.state
        if (s.mode == Mode.IDLE || s.mode == Mode.WALKING) s.h else Physics.groundHeight(arena, s.pos, s.h, physics) ?: s.h
    }

    /** From the ground at [p] the opponent's feet are within height reach (always, with the reach unlimited). */
    private fun fromSpot(p: Vec2): Boolean {
        if (!params.reachLimited) return true
        val ground = Physics.groundHeight(arena, p, myH, physics) ?: return false
        return params.inReach(ground, foeFeet)
    }

    /** Set by [climb]: the push it was given cannot be made from the spot behind the opponent. */
    private var climbing = false

    /**
     * [push] when from the spot behind the opponent ([BotTuning.standoffGapRadii] off it, against [push]) the opponent
     * is within height reach; else the push from the first spot round it at that distance (from that one, turning both
     * ways, every 22.5°) from which it is and that is a fit walk target; else [push] (and the walk goes at the opponent).
     * [push] as it is with the reach unlimited.
     */
    private fun climb(push: Vec2): Vec2 {
        if (!params.reachLimited) return push
        val standoff = contact + t.standoffGapRadii * r
        if (fromSpot(them - push * standoff)) return push
        climbing = true
        val back = -push
        for (k in 1 until CLIMB_COS.size) {
            val d = Vec2(back.u * CLIMB_COS[k] - back.v * CLIMB_SIN[k], back.u * CLIMB_SIN[k] + back.v * CLIMB_COS[k])
            val p = them + d * standoff
            if (fromSpot(p) && fitTarget(p) && keeps.none { p.distanceTo(it.c) < it.r }) return -d
        }
        return push
    }

    // -- safety ---------------------------------------------------------------------------------------------------

    /**
     * A dash along unit [dir] of [reach] is safe: it ends on the table and its whole path stays off every hole, or the
     * opponent is in its path first (and the path up to it stays off every hole).
     */
    private fun dashSafe(dir: Vec2, reach: Float): Boolean = runsOnTable(dir, reach) || inPath(dir, reach)

    /** A dash along unit [dir] of [reach] that hits nothing ends on the table, its path clear of every hole. */
    private fun runsOnTable(dir: Vec2, reach: Float): Boolean {
        val end = me + dir * reach
        return endsOnTable(end) && clearOfHoles(me, end)
    }

    private fun endsOnTable(end: Vec2): Boolean =
        finite(end) && boundary.contains(end) && board.inside(end) >= t.dashEndMarginRadii * r && keeps.none { it.hole && end.distanceTo(it.c) < it.bare + r }

    /** The centre's straight path from [from] to [to] neither touches nor crosses a hole's outline (a body falls when its centre is over one). */
    private fun clearOfHoles(from: Vec2, to: Vec2): Boolean = keeps.none { it.hole && it.outline != null && touches(from, to, it.outline) }

    /** [p] keeps out of every hole as a walk target must: outside each hole's circle (bounding circle + margin). */
    private fun offHoles(p: Vec2): Boolean = keeps.none { it.hole && p.distanceTo(it.c) < it.r - 1e-5f }

    /**
     * The opponent can be hit and a dash along unit [dir] meets it early, within [BotTuning.inPathReachShare] of
     * [reach], with no hole on the way to it.
     */
    private fun inPath(dir: Vec2, reach: Float): Boolean {
        // (A fighter out of reach in height is no excuse: the dash would pass it and carry on.)
        if (!foeHittable || !reachable(dashSpeed, if (reach > dashReach) params.ramDashMs else params.dashMs)) return false
        val along = toFoe.dot(dir)
        val lateral = abs(toFoe.dot(dir.perp()))
        if (along <= 0f || lateral > t.inPathLateralRadii * r || lateral >= contact) return false
        val meet = along - sqrt(contact * contact - lateral * lateral)
        return meet <= reach * t.inPathReachShare && clearOfHoles(me, me + dir * meet)
    }

    /** Nothing that blocks (at the bot's feet) stands between [from] and [to]. */
    private fun clearLine(from: Vec2, to: Vec2): Boolean = keeps.none { !it.hole && near(from, to, it.c, it.bare + r * t.lineOfSightRadii) }

    // -- walking --------------------------------------------------------------------------------------------------

    /** EASY: the spot it wanders to, picking a new one now and then; null = no wandering now. */
    private fun wanderGoal(): Vec2? {
        val w = wander
        if (w != null && now < wanderUntilMs) return w
        wander = null
        if (!rng.chance(skill.wanderChance)) return null
        val box = bounds()
        var spot: Vec2? = null
        for (i in 0 until 6) {
            val p = Vec2(box[0] + (box[1] - box[0]) * rng.float(), box[2] + (box[3] - box[2]) * rng.float())
            if (board.inside(p) >= margin && keeps.none { p.distanceTo(it.c) < it.r }) {
                spot = p
                break
            }
        }
        val goal = spot ?: safe(boundary.centroid())
        wander = goal
        wanderUntilMs = now + skill.wanderMs
        return goal
    }

    private fun bounds(): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE)
        for (p in boundary.points) {
            b[0] = minOf(b[0], p.u); b[1] = maxOf(b[1], p.u)
            b[2] = minOf(b[2], p.v); b[3] = maxOf(b[3], p.v)
        }
        return b
    }

    /** The next walk target toward [goal]: round the opponent and round what blocks, always a [safe] spot. */
    private fun route(goal: Vec2): Vec2 {
        var target = safe(goal)
        val ring = contact + t.clearanceRadii * r
        if (target.distanceTo(them) >= ring * 0.9f && near(me, target, them, ring * 0.9f)) target = around(them, ring, target, ring * 0.9f, contact)
        for (k in keeps) {
            if (near(me, target, k.c, k.r * 0.95f)) {
                target = around(k.c, k.r, target, k.r * 0.95f, k.bare + r)
                break
            }
        }
        return safe(target)
    }

    /**
     * A waypoint going round the circle ([c], [rho]) from the bot toward [goal], on the side that is the shorter way and
     * safe. T60 ([BotTuning.wholeDetour]): when the shorter way round is closed by the circle of another object that blocks
     * (not the edge, nor a hole: [misfit], [wayRound]; [route] goes straight on once the line to [goal] clears [pass] round
     * [c]), the other side if its whole way round is open; else, when the straight line to [goal] touches nothing (it keeps
     * [core], the circle's body, from [c] and out of every other circle), [goal] itself; only else as before. (Before T60
     * the side was picked by its next waypoint alone, and in a gap too narrow to pass, as between a fighter and the cup,
     * the two halves of that rule disagreed: off the ring the waypoint into the gap was refused, on the ring its first
     * step was not, and the bot went to and fro beside the other for a whole round. And the ring's last waypoint before a
     * spot against the cup lies inside the cup's circle when the spot does not.)
     */
    private fun around(c: Vec2, rho: Float, goal: Vec2, pass: Float, core: Float): Vec2 {
        val rel = me - c
        val len = rel.length
        val un = if (len > Vec2.EPS) rel / len else (goal - c).normalized().perp().takeIf { it != Vec2.ZERO } ?: Vec2(1f, 0f)
        val preferred = if (un.cross(goal - c) >= 0f) 1f else -1f
        if (t.wholeDetour && wayRound(c, rho, detourPoint(c, rho, un, len, preferred), goal, preferred, pass) == WAY_OBJECT) {
            val other = detourPoint(c, rho, un, len, -preferred)
            if (wayRound(c, rho, other, goal, -preferred, pass) == WAY_OPEN) return other
            if (!near(me, goal, c, core) && keeps.none { k -> k.c != c && near(me, goal, k.c, k.r * 0.95f) }) return goal
        }
        var fallback: Vec2? = null
        for (side in floatArrayOf(preferred, -preferred)) {
            val w = detourPoint(c, rho, un, len, side)
            if (board.inside(w) >= margin && keeps.none { k -> k.c != c && w.distanceTo(k.c) < k.r }) return w
            if (fallback == null) fallback = w
        }
        return fallback ?: goal
    }

    /** [around]'s next waypoint on [side] for the bot [len] from [c] along [un]. */
    private fun detourPoint(c: Vec2, rho: Float, un: Vec2, len: Float, side: Float): Vec2 = if (len > rho * 1.2f) {
        // Outside: head for the tangent point, and a little past it along the tangent.
        val cosA = rho / len
        val sinA = sqrt((1f - cosA * cosA).coerceAtLeast(0f))
        val dirT = un * cosA + un.perp() * (side * sinA)
        val touch = c + dirT * rho
        touch + (touch - me).normalized() * (rho * 0.5f)
    } else {
        // On the ring: one step round it.
        val s = t.detourStepSin * side
        val turned = Vec2(un.u * t.detourStepCos - un.v * s, un.u * s + un.v * t.detourStepCos)
        c + turned * (rho * 1.1f)
    }

    /**
     * T60: whether waypoint [p] round [c] may be sent ([WAY_OPEN]), or what refuses it: the circle of another object that
     * blocks ([WAY_OBJECT]), else the edge margin or a hole's circle ([WAY_EDGE]). A hole counts with the edge, not as an
     * object: its circle is the bounding one (21 cm round a 4 x 30 cm strip), too coarse to say a way is closed.
     */
    private fun misfit(p: Vec2, c: Vec2): Int = when {
        keeps.any { k -> !k.hole && k.c != c && p.distanceTo(k.c) < k.r } -> WAY_OBJECT
        board.inside(p) < margin || keeps.any { k -> k.hole && k.c != c && p.distanceTo(k.c) < k.r } -> WAY_EDGE
        else -> WAY_OPEN
    }

    /**
     * T60: the way round the circle ([c], [rho]) on [side] from waypoint [from], one detour step at a time (as [around]
     * goes on the ring): [WAY_OPEN] when it reaches a waypoint whose straight line to [goal] passes [pass] or more from
     * [c] (where [route] stops going round) before one it may not be sent to; else what closed it first ([misfit]), and
     * [WAY_EDGE] after 8 steps (a whole turn at the default 45°).
     */
    private fun wayRound(c: Vec2, rho: Float, from: Vec2, goal: Vec2, side: Float, pass: Float): Int {
        misfit(from, c).let { if (it != WAY_OPEN) return it }
        var b = (from - c).normalized()
        if (b == Vec2.ZERO) return WAY_EDGE
        var p = from
        val s = t.detourStepSin * side
        repeat(8) {
            if (!near(p, goal, c, pass)) return WAY_OPEN
            b = Vec2(b.u * t.detourStepCos - b.v * s, b.u * s + b.v * t.detourStepCos)
            p = c + b * (rho * 1.1f)
            misfit(p, c).let { if (it != WAY_OPEN) return it }
        }
        return WAY_EDGE
    }

    /** A walk target the bot may send: finite, [margin] inside the boundary, off every hole ([offHoles]). */
    private fun fitTarget(p: Vec2): Boolean = finite(p) && boundary.contains(p) && board.inside(p) >= margin - 1e-5f && offHoles(p)

    /**
     * [p] made a walk target: [margin] inside the boundary, out of every circle the bot keeps out of. When the edge and
     * a hole near it push the point back and forth (a hole at the rim), or the boundary is not convex, the point goes
     * halfway to the middle until it fits, else the bot's own place; what still does not fit [checked] turns into None.
     */
    fun safe(p: Vec2): Vec2 {
        var q = if (finite(p)) p else boundary.centroid()
        repeat(3) {
            q = board.clamp(q, margin)
            for (k in keeps) {
                val d = q - k.c
                val len = d.length
                if (len < k.r) q = k.c + (if (len > Vec2.EPS) d / len else Vec2(1f, 0f)) * k.r
            }
        }
        q = board.clamp(q, margin)
        if (fitTarget(q)) return q
        val middle = boundary.centroid()
        repeat(8) {
            q = (q + middle) * 0.5f
            if (fitTarget(q)) return q
        }
        return if (fitTarget(me)) me else middle
    }

    private fun randomDirection(): Vec2 {
        repeat(4) {
            val d = Vec2(rng.signed(), rng.signed())
            val len = d.length
            if (len > 0.1f && len <= 1f) return d / len
        }
        return Vec2(1f, 0f)
    }

    private companion object {
        /** The turns off the nearest-edge push a weighing bot considers: 0, +-22.5°, +-45°. */
        val TURN_COS = floatArrayOf(1f, C22, C22, C45, C45)
        val TURN_SIN = floatArrayOf(0f, S22, -S22, C45, -C45)

        /** T57, [climb]: every 22.5° round the opponent, nearest to the spot behind it first. */
        val CLIMB_COS = floatArrayOf(1f, C22, C22, C45, C45, S22, S22, 0f, 0f, -S22, -S22, -C45, -C45, -C22, -C22, -1f)
        val CLIMB_SIN = floatArrayOf(0f, S22, -S22, C45, -C45, C22, -C22, 1f, -1f, C22, -C22, C45, -C45, S22, -S22, 0f)
    }
}

/** T60, `Brain.wayRound`: the way round is open, closed by the edge margin (or never opens), closed by another circle. */
private const val WAY_OPEN = 0
private const val WAY_EDGE = 1
private const val WAY_OBJECT = 2

private const val C22 = 0.9238795f
private const val S22 = 0.38268343f
private const val C45 = 0.70710677f

private fun finite(p: Vec2) = p.u.isFinite() && p.v.isFinite()

/** The segment [a]-[b] touches or crosses [poly]: an end inside it, or an edge met (touching counts). */
private fun touches(a: Vec2, b: Vec2, poly: Polygon): Boolean {
    if (poly.contains(a) || poly.contains(b)) return true
    val pts = poly.points
    for (i in pts.indices) if (segmentsTouch(a, b, pts[i], pts[(i + 1) % pts.size])) return true
    return false
}

private fun segmentsTouch(p1: Vec2, p2: Vec2, p3: Vec2, p4: Vec2): Boolean {
    val d1 = orient(p3, p4, p1)
    val d2 = orient(p3, p4, p2)
    val d3 = orient(p1, p2, p3)
    val d4 = orient(p1, p2, p4)
    if (((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) && ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))) return true
    return (d1 == 0f && within(p3, p4, p1)) || (d2 == 0f && within(p3, p4, p2)) ||
        (d3 == 0f && within(p1, p2, p3)) || (d4 == 0f && within(p1, p2, p4))
}

private fun orient(a: Vec2, b: Vec2, c: Vec2): Float = (b.u - a.u) * (c.v - a.v) - (b.v - a.v) * (c.u - a.u)

private fun within(a: Vec2, b: Vec2, p: Vec2): Boolean =
    p.u >= minOf(a.u, b.u) && p.u <= maxOf(a.u, b.u) && p.v >= minOf(a.v, b.v) && p.v <= maxOf(a.v, b.v)

/** The segment [a]-[b] passes closer than [rho] to [c]. */
private fun near(a: Vec2, b: Vec2, c: Vec2, rho: Float): Boolean {
    val ab = b - a
    val len2 = ab.lengthSquared
    val s = if (len2 < Vec2.EPS * Vec2.EPS) 0f else ((c - a).dot(ab) / len2).coerceIn(0f, 1f)
    val q = a + ab * s
    return (c - q).lengthSquared < rho * rho
}
