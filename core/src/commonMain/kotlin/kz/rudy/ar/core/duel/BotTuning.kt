package kz.rudy.ar.core.duel

import kotlinx.serialization.Serializable

/**
 * Every number of the duel bot ([DuelBot]); the CLI (T48) searches them. Distances are in fighter radii (a bot plays a
 * floor duel as it plays the table), reaches come from [DuelParams] (a dash goes `dashSpeedMps * dashMs`), times are
 * ms of match time, angles are cosines: the bot uses no trigonometry, so for a given state it gives the same answer on
 * every platform. (A whole match is another matter: the physics uses `exp`, and a level may be built with cos / sin, as
 * [DuelArenas.CUP_AND_BOOK]'s cup is.)
 *
 * @property tickMs the decision grid: bots decide only at match times that are multiples of it ([DuelRunner]). A
 *   multiple of [DuelParams.subStepMs], so a decision lands on a physics sub-step.
 * @property edgeMarginRadii every walk target keeps at least this far inside the boundary (and as far from a hole).
 * @property dashEndMarginRadii a dash or a ram is started only if it would end at least this far inside the boundary,
 *   unless the opponent is in its path first ([inPathLateralRadii], [inPathReachShare]).
 * @property inPathLateralRadii the opponent is "in the path" of a dash when its centre is at most this far from the line.
 * @property inPathReachShare ... and the dash meets it within this share of its reach.
 * @property standoffGapRadii the spot a bot walks to leaves this gap between the two circles, the opponent between it
 *   and the edge it is pushed to.
 * @property clearanceRadii a detour round the opponent or an object keeps this much table between them.
 * @property detourStepCos / [detourStepSin]: going round something, the next waypoint is this far round its circle (45°).
 * @property threatSlackRadii a dash is "coming at" a bot when its line passes within two radii plus this of the bot's
 *   centre (a stomp: when the bot is within the stomp's reach plus this). The T57 review: 1.0 (was 0.5), so a dash from
 *   a fighter-width off its line is seen in time for a lift-off or a dodge.
 * @property sidestepRadii a sidestep on foot goes this far across the incoming dash.
 * @property stompReachShare a stomp is tried with the opponent within this share of the stomp's reach.
 * @property ramNearEdgeRadii a ram is tried with the opponent at most this far inside the boundary ("near an edge"; a
 *   [BotSkill.safeRamsOnly] bot rams wherever a miss still ends on the table). T48: 8 (was 5), so a NORMAL ROLL rams
 *   on most of a 120 x 60 cm table too, not only on the small ones (where every spot is near an edge). The T57 review:
 *   20, every spot of every table: a ram spends the dash too, and a ROLL waiting for the edge left it unused...
 * @property ramRoomRadii ... and the roller itself at least this far inside ("room behind itself").
 * @property walkCost a bot that weighs push directions ([BotSkill.weighsPush]) scores each as the distance the opponent
 *   would have to be pushed plus this x the walk to the spot (and round the opponent)...
 * @property switchCostRadii ... plus this for a direction other than the one it settled on (no dithering)...
 * @property highGroundBonusRadii ... minus this for a spot on a PLATFORM above the opponent's feet (a stronger hit).
 *   Only such a spot can replace the push toward the opponent's nearest edge.
 * @property leadShare a bot that leads its aim ([BotSkill.leadsAim]) aims this share of the way to where a walking
 *   opponent will be when the dash meets it...
 * @property knockLeadShare ... counting this share of the opponent's knockback velocity as motion too.
 * @property lineOfSightRadii a dash needs a clear line: nothing that blocks within this many radii of it.
 * @property sameDirectionCos two push directions closer than this (cosine, about 11°) are the one it settled on.
 * @property blockedPushRadii a push that something stops before the edge (a wall slam, not a ring-out) scores this many
 *   radii longer.
 * @property jumpMarginMs T57: a jump over a dash or a stomp is tried only if the feet are above the hit's reach this long
 *   before the hit can land and stay there this long after it has passed (the sub-steps round the jump's arc).
 * @property ramAlignCos T57 review: with the shared cooldown and the height reach on, a ram may start with the roller
 *   facing within this of the opponent and of the push (cosine, 0.5 = 60°; a bot's own `alignCos` when that is looser).
 *   The ram goes along the facing its last walk left it with; its in-path checks still decide whether it can hit.
 * @property turnStepRadii T57: a ROLL whose ram would be ready if it faced the opponent walks this far at it first (the
 *   walk turns its facing) and rams on its next thought.
 * @property wholeDetour T60: going round the opponent or an object, when another object that blocks closes the shorter
 *   way round (a hole does not count: its bounding circle is too coarse), a bot takes the other side if its whole way
 *   round is open, else walks straight at its goal if nothing is in the way; otherwise (and always, with false) it picks
 *   a side by its next waypoint alone, as before T60. Without it a
 *   NORMAL or HARD bot goes to and fro for a whole round beside a fighter standing on its mark of
 *   [DuelArenas.CUP_AND_BOOK] (the way to the spot it pushes from runs through the gap between that fighter and the cup)
 *   and never dashes: the fighter that does nothing wins 2:0 on the clock. With it the bot wins those matches 2:0 by
 *   ring-outs, and nothing changes on a table without objects. **Off by default** (the T60 Result): on, NORMAL plays the
 *   ROLL mirror on cup-and-book about as well as HARD (it went to and fro beside a HARD bot standing by the cup too), and
 *   `normalIsBetweenEasyAndHard`'s floor fails there (HARD ROLL over NORMAL ROLL 214 of 400 for 220). A human decides.
 *   Draws nothing from the bot's generator either way.
 */
@Serializable
data class BotTuning(
    val tickMs: Long = 48,
    val edgeMarginRadii: Float = 2f,
    val dashEndMarginRadii: Float = 1f,
    val inPathLateralRadii: Float = 1f,
    val inPathReachShare: Float = 0.5f,
    val standoffGapRadii: Float = 0.6f,
    val clearanceRadii: Float = 0.6f,
    val detourStepCos: Float = 0.70710677f,
    val detourStepSin: Float = 0.70710677f,
    val threatSlackRadii: Float = 1.0f,
    val sidestepRadii: Float = 2f,
    val stompReachShare: Float = 0.8f,
    val ramNearEdgeRadii: Float = 20f,
    val ramRoomRadii: Float = 3f,
    val walkCost: Float = 0.5f,
    val switchCostRadii: Float = 1f,
    val highGroundBonusRadii: Float = 3f,
    val leadShare: Float = 1f,
    val knockLeadShare: Float = 0.5f,
    val lineOfSightRadii: Float = 0.8f,
    val sameDirectionCos: Float = 0.98f,
    val blockedPushRadii: Float = 10f,
    val jumpMarginMs: Long = 16,
    val ramAlignCos: Float = 0.5f,
    val turnStepRadii: Float = 1f,
    val wholeDetour: Boolean = false,
    val easy: BotSkill = BotSkill.EASY,
    val normal: BotSkill = BotSkill.NORMAL,
    val hard: BotSkill = BotSkill.HARD,
) {
    init {
        require(tickMs > 0L) { "tickMs must be > 0, got $tickMs" }
        require(jumpMarginMs >= 0L) { "jumpMarginMs must be >= 0, got $jumpMarginMs" }
        for ((name, v) in listOf(
            "edgeMarginRadii" to edgeMarginRadii, "dashEndMarginRadii" to dashEndMarginRadii,
            "inPathLateralRadii" to inPathLateralRadii, "inPathReachShare" to inPathReachShare,
            "standoffGapRadii" to standoffGapRadii, "clearanceRadii" to clearanceRadii, "threatSlackRadii" to threatSlackRadii,
            "sidestepRadii" to sidestepRadii, "stompReachShare" to stompReachShare, "ramNearEdgeRadii" to ramNearEdgeRadii,
            "ramRoomRadii" to ramRoomRadii, "walkCost" to walkCost, "switchCostRadii" to switchCostRadii,
            "highGroundBonusRadii" to highGroundBonusRadii, "leadShare" to leadShare, "knockLeadShare" to knockLeadShare,
            "lineOfSightRadii" to lineOfSightRadii, "blockedPushRadii" to blockedPushRadii, "turnStepRadii" to turnStepRadii,
        )) require(v >= 0f && v.isFinite()) { "$name must be >= 0 and finite, got $v" }
        require(sameDirectionCos >= -1f && sameDirectionCos <= 1f) { "sameDirectionCos must be in -1..1, got $sameDirectionCos" }
        require(ramAlignCos >= -1f && ramAlignCos <= 1f) { "ramAlignCos must be in -1..1, got $ramAlignCos" }
        val turn = detourStepCos * detourStepCos + detourStepSin * detourStepSin
        require(detourStepSin > 0f && turn > 0.999f && turn < 1.001f) { "detourStepCos / detourStepSin must be a unit turn, got $detourStepCos, $detourStepSin" }
    }

    /** The skill of [level]. */
    fun skill(level: BotLevel): BotSkill = when (level) {
        BotLevel.EASY -> easy
        BotLevel.NORMAL -> normal
        BotLevel.HARD -> hard
    }

    /** [tickMs] checked against the physics: a decision must land on a sub-step. */
    fun requireFits(params: DuelParams) {
        require(tickMs % params.subStepMs == 0L) { "tickMs ($tickMs) must be a multiple of the physics sub-step (${params.subStepMs} ms)" }
    }
}

/**
 * What one [BotLevel] can do, see [DuelBot]. Times in ms of match time, cosines for angles.
 *
 * @property thinkMs time between two decisions (its reaction time); between them the bot sends nothing and its last
 *   orders (a walk) stand. Rounded up to the [BotTuning.tickMs] grid by the runner.
 * @property thinkJitterTicks each wait gets 0..this many extra ticks at random.
 * @property dashRangeShare dashes when the gap between the circles is at most this share of a dash's reach...
 * @property alignCos ... and the dash pushes the opponent within this of the direction it wants it to go (cosine; -1 =
 *   whatever the alignment).
 * @property aimErrorShare a random sideways error on every dash, up to this share of the aim (0.2 = up to about 11°).
 * @property wrongWayChance chance that a dash goes in a random direction instead (still never off the table).
 * @property wanderChance chance, at each decision, to start wandering to a random spot for [wanderMs].
 * @property leadsAim aims where a walking opponent will be ([BotTuning.leadShare]), not where it is.
 * @property weighsPush prefers the high ground: pushes the opponent from a spot on a PLATFORM above it, up to 45° off
 *   the push toward the opponent's nearest edge, when that spot is as deep inside as the opponent and scores better
 *   ([BotTuning.walkCost], [BotTuning.switchCostRadii], [BotTuning.highGroundBonusRadii]). Measured: letting any of
 *   those directions win on its score alone made HARD lose to NORMAL (it walked along its own edge, or round the cup).
 * @property usesSpecial uses its special as a player would (the T57 review): stomp an opponent in reach that stands and
 *   cannot get out of the way by a jump (a FLY's float can), ram when the facing lines up with the opponent and the push
 *   (turning to it first), lift off or dodge when a dash or a stomp coming at it would land (within [nearEdgeRadii] of
 *   an edge; a [dodgesAnywhere] HIDE anywhere), and jump a dash as a stomp when the stomp is ready (JUMP).
 * @property nearEdgeRadii "near an edge" for the lift-off and the dodge: at most this far inside.
 * @property sidestepEdgeRadii "near an edge" for a [sidesteps] bot's sidestep: at most this far inside (T48 review: a
 *   number of its own, so a HARD FLY can lift off wherever a NORMAL one does while a HARD JUMP keeps fighting in
 *   mid-table; the same as [nearEdgeRadii] unless given).
 * @property sidesteps gets out of the way of a dash coming at it near an edge (a dash across it, else a step aside;
 *   in mid-table a hit costs a few centimetres and leaves the attacker on cooldown, so it takes it), and dodges (HIDE)
 *   wherever it stands, for the counter.
 * @property counters dashes at the opponent while its counter window is open (HIDE, after a dodge let a hit through),
 *   whatever the alignment.
 * @property safeRamsOnly rams only when the ram would end on the table even if it missed, and then wherever the
 *   opponent stands (not only near an edge); since the T57 review (rules on) also at an opponent in its path that cannot
 *   get out of it: too close to jump over it in time, and no lift-off or dodge ready.
 * @property jumpChance T57 (height matters for hits): when a dash or a stomp is coming at it and a jump now would carry
 *   its feet out of the hit's reach in time (and keep them there until the hit has passed), it jumps with this chance (a
 *   ROLL body cannot jump). 0 = never (EASY).
 * @property reflexes T57: it looks for such a dash or stomp on every tick, not only when it thinks ([thinkMs]): the jump
 *   comes in time whenever it can.
 * @property closesInOnJumpers T57: against an opponent that can jump over a dash, it dashes only from close enough that
 *   the opponent, reacting on the next tick, could not get its feet out of reach before the dash lands.
 * @property dodgesAnywhere a HIDE dodges a dash or a stomp coming at it wherever it stands (for the counter), not only
 *   within [nearEdgeRadii] of an edge; before T57 this was what [sidesteps] meant for a HIDE, and it still defaults to it.
 * @property reflexChance T57: a bot without [reflexes] notices a dash or a stomp coming at it on a tick it does not think
 *   on with this chance, and answers it then (a jump, a lift-off, a dodge). 0 = only when it thinks.
 * @property impatientAfterMs T61: after this much FIGHT without attacking (a dash, a stomp or a ram; counted between its
 *   own decisions, from every round start and every attack: [BotMind.calmMs]) the bot attacks from where it stands: it
 *   walks at the opponent and dashes whatever the alignment, as EASY does, until it attacks, whatever stopped its plan
 *   (straight at it when that line touches no object's body; round objects by their bodies, without the clearance).
 *   Only at an opponent it can hit within a dash's height reach. 0 = never (EASY, which dashes at any alignment anyway,
 *   and the bot before T61, bit for bit). NORMAL and HARD: 16 000 (the T61 Result: between bots a stretch that long
 *   without an attack is rare, 0.34 % of NORMAL and 0.06 % of HARD matches, and the 24 golden matches' longest is 15.55
 *   s; 30 of the matrix's 320 cells move, by at most 1 point).
 */
@Serializable
data class BotSkill(
    val thinkMs: Long,
    val thinkJitterTicks: Int,
    val dashRangeShare: Float,
    val alignCos: Float,
    val aimErrorShare: Float,
    val wrongWayChance: Float,
    val wanderChance: Float,
    val wanderMs: Long,
    val leadsAim: Boolean,
    val weighsPush: Boolean,
    val usesSpecial: Boolean,
    val nearEdgeRadii: Float,
    val sidesteps: Boolean,
    val counters: Boolean,
    val safeRamsOnly: Boolean,
    val sidestepEdgeRadii: Float = nearEdgeRadii,
    val jumpChance: Float = 0f,
    val reflexes: Boolean = false,
    val closesInOnJumpers: Boolean = false,
    val dodgesAnywhere: Boolean = sidesteps,
    val reflexChance: Float = 0f,
    val impatientAfterMs: Long = 0L,
) {
    init {
        require(impatientAfterMs >= 0L) { "impatientAfterMs must be >= 0, got $impatientAfterMs" }
        require(reflexChance in 0f..1f) { "reflexChance must be in 0..1, got $reflexChance" }
        require(jumpChance in 0f..1f) { "jumpChance must be in 0..1, got $jumpChance" }
        require(thinkMs >= 0L) { "thinkMs must be >= 0, got $thinkMs" }
        require(thinkJitterTicks >= 0) { "thinkJitterTicks must be >= 0, got $thinkJitterTicks" }
        require(wanderMs >= 0L) { "wanderMs must be >= 0, got $wanderMs" }
        require(dashRangeShare >= 0f && dashRangeShare.isFinite()) { "dashRangeShare must be >= 0, got $dashRangeShare" }
        require(alignCos >= -1f && alignCos <= 1f) { "alignCos must be in -1..1, got $alignCos" }
        require(aimErrorShare >= 0f && aimErrorShare.isFinite()) { "aimErrorShare must be >= 0, got $aimErrorShare" }
        require(wrongWayChance in 0f..1f) { "wrongWayChance must be in 0..1, got $wrongWayChance" }
        require(wanderChance in 0f..1f) { "wanderChance must be in 0..1, got $wanderChance" }
        require(nearEdgeRadii >= 0f && nearEdgeRadii.isFinite()) { "nearEdgeRadii must be >= 0, got $nearEdgeRadii" }
        require(sidestepEdgeRadii >= 0f && sidestepEdgeRadii.isFinite()) { "sidestepEdgeRadii must be >= 0, got $sidestepEdgeRadii" }
    }

    companion object {
        /** Slow (about half a second), wanders, dashes when close whatever the alignment, sometimes the wrong way, no special, never jumps a dash. */
        val EASY = BotSkill(
            thinkMs = 384, thinkJitterTicks = 4, dashRangeShare = 0.7f, alignCos = -1f, aimErrorShare = 0.35f,
            wrongWayChance = 0.25f, wanderChance = 0.2f, wanderMs = 1200, leadsAim = false, weighsPush = false,
            usesSpecial = false, nearEdgeRadii = 0f, sidesteps = false, counters = false, safeRamsOnly = false,
        )

        /**
         * The shared plan at a fifth of a second, a small aim error, the special as a player would use it; T57: jumps over
         * a dash it sees coming in time half of the time. The T57 review: lifts off or dodges wherever a hit that would
         * land comes at it (`nearEdgeRadii` 20 again; pass 1's 2 left FLY and HIDE with under one special a match between
         * bots), and notices a hit coming on a tick it does not think on 30 % of the time (`reflexChance` 0.3: on its
         * thinking ticks alone it saw a dash too late to lift off or dodge it). T48: `nearEdgeRadii` 20 (was 4): a FLY
         * lifts off and a HIDE dodges whenever a dash or a stomp comes at it, not only near an edge (in mid-table they had
         * no special at all: equal stats, JUMP beat FLY 80 / 20).
         */
        val NORMAL = BotSkill(
            thinkMs = 144, thinkJitterTicks = 2, dashRangeShare = 0.8f, alignCos = 0.8660254f, aimErrorShare = 0.1f,
            wrongWayChance = 0f, wanderChance = 0f, wanderMs = 0, leadsAim = false, weighsPush = false,
            usesSpecial = true, nearEdgeRadii = 20f, sidesteps = false, counters = false, safeRamsOnly = false,
            jumpChance = 0.5f, reflexChance = 0.3f, impatientAfterMs = 16_000L,
        )

        /**
         * NORMAL deciding every tick or a few, leading its aim, weighing where to push (high ground), sidestepping near an
         * edge, countering; T57: jumps over every dash a jump can clear (it watches on every tick), and against an opponent
         * that can jump it dashes only from close. The jitter matters: two HARD bots deciding on the same ticks mirror each
         * other on a symmetric arena (with one tick: every round a tied timeout, every match a draw; with none, measured in
         * the T57 review, a HARD ROLL dithered and lost 47 % to a NORMAL one). T48: `thinkJitterTicks` 3 (was 1): with one,
         * two HARD bots of one ability still dashed together and clashed in mid-table (JUMP v JUMP on the 120 cm table: 52
         * % of the rounds to the clock, 34 hits per ring-out); `nearEdgeRadii` 8 (was 4) for the lift-off and the dodge,
         * `sidestepEdgeRadii` 4 (as before T48) for the sidestep: with 8 for both, a HARD JUMP sidestepped so often that
         * it lost to a NORMAL one (34 % on the empty table; 78 % with the sidestep at 4). The T57 review: lift-off and dodge
         * wherever a hit that would land comes at it (`nearEdgeRadii` 20, `dodgesAnywhere` true; pass 1 had 2 and false,
         * and FLY and HIDE used their specials under once a match between bots).
         */
        val HARD = BotSkill(
            thinkMs = 48, thinkJitterTicks = 3, dashRangeShare = 0.85f, alignCos = 0.8660254f, aimErrorShare = 0f,
            wrongWayChance = 0f, wanderChance = 0f, wanderMs = 0, leadsAim = true, weighsPush = true,
            usesSpecial = true, nearEdgeRadii = 20f, sidesteps = true, counters = true, safeRamsOnly = true,
            sidestepEdgeRadii = 4f, jumpChance = 1f, reflexes = true, closesInOnJumpers = true, impatientAfterMs = 16_000L,
        )
    }
}
