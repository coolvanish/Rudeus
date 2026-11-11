package kz.rudy.ar.core.duel

import kotlinx.serialization.Serializable

/**
 * Every number of the duel bot ([DuelBot]); the CLI (T48) searches them. Distances are in fighter radii (a bot plays a
 * floor duel as it plays the table), reaches come from [DuelParams] (a dash goes `dashSpeedMps * dashMs`), times are
 * ms of match time, angles are cosines: the bot uses no trigonometry, so it computes the same bits on every platform.
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
 *   centre (a stomp: when the bot is within the stomp's reach plus this).
 * @property sidestepRadii a sidestep on foot goes this far across the incoming dash.
 * @property stompReachShare a stomp is tried with the opponent within this share of the stomp's reach.
 * @property ramNearEdgeRadii a ram is tried with the opponent at most this far inside the boundary ("near an edge"; a
 *   [BotSkill.safeRamsOnly] bot rams wherever a miss still ends on the table)...
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
    val threatSlackRadii: Float = 0.5f,
    val sidestepRadii: Float = 2f,
    val stompReachShare: Float = 0.8f,
    val ramNearEdgeRadii: Float = 5f,
    val ramRoomRadii: Float = 3f,
    val walkCost: Float = 0.5f,
    val switchCostRadii: Float = 1f,
    val highGroundBonusRadii: Float = 3f,
    val leadShare: Float = 1f,
    val knockLeadShare: Float = 0.5f,
    val lineOfSightRadii: Float = 0.8f,
    val sameDirectionCos: Float = 0.98f,
    val blockedPushRadii: Float = 10f,
    val easy: BotSkill = BotSkill.EASY,
    val normal: BotSkill = BotSkill.NORMAL,
    val hard: BotSkill = BotSkill.HARD,
) {
    init {
        require(tickMs > 0L) { "tickMs must be > 0, got $tickMs" }
        for ((name, v) in listOf(
            "edgeMarginRadii" to edgeMarginRadii, "dashEndMarginRadii" to dashEndMarginRadii,
            "inPathLateralRadii" to inPathLateralRadii, "inPathReachShare" to inPathReachShare,
            "standoffGapRadii" to standoffGapRadii, "clearanceRadii" to clearanceRadii, "threatSlackRadii" to threatSlackRadii,
            "sidestepRadii" to sidestepRadii, "stompReachShare" to stompReachShare, "ramNearEdgeRadii" to ramNearEdgeRadii,
            "ramRoomRadii" to ramRoomRadii, "walkCost" to walkCost, "switchCostRadii" to switchCostRadii,
            "highGroundBonusRadii" to highGroundBonusRadii, "leadShare" to leadShare, "knockLeadShare" to knockLeadShare,
            "lineOfSightRadii" to lineOfSightRadii, "blockedPushRadii" to blockedPushRadii,
        )) require(v >= 0f && v.isFinite()) { "$name must be >= 0 and finite, got $v" }
        require(sameDirectionCos >= -1f && sameDirectionCos <= 1f) { "sameDirectionCos must be in -1..1, got $sameDirectionCos" }
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
 * @property usesSpecial uses its special in its obvious situation: stomp with the opponent in reach, ram when aligned
 *   with the opponent near an edge, lift-off or dodge when a dash or a stomp is coming at it near an edge.
 * @property nearEdgeRadii "near an edge" for the lift-off, the dodge and the sidestep: at most this far inside.
 * @property sidesteps gets out of the way of a dash coming at it near an edge (a dash across it, else a step aside;
 *   in mid-table a hit costs a few centimetres and leaves the attacker on cooldown, so it takes it), and dodges (HIDE)
 *   wherever it stands, for the counter.
 * @property counters dashes at the opponent while its counter window is open (HIDE, after a dodge let a hit through),
 *   whatever the alignment.
 * @property safeRamsOnly rams only when the ram would end on the table even if it missed, and then wherever the
 *   opponent stands (not only near an edge).
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
) {
    init {
        require(thinkMs >= 0L) { "thinkMs must be >= 0, got $thinkMs" }
        require(thinkJitterTicks >= 0) { "thinkJitterTicks must be >= 0, got $thinkJitterTicks" }
        require(wanderMs >= 0L) { "wanderMs must be >= 0, got $wanderMs" }
        require(dashRangeShare >= 0f && dashRangeShare.isFinite()) { "dashRangeShare must be >= 0, got $dashRangeShare" }
        require(alignCos >= -1f && alignCos <= 1f) { "alignCos must be in -1..1, got $alignCos" }
        require(aimErrorShare >= 0f && aimErrorShare.isFinite()) { "aimErrorShare must be >= 0, got $aimErrorShare" }
        require(wrongWayChance in 0f..1f) { "wrongWayChance must be in 0..1, got $wrongWayChance" }
        require(wanderChance in 0f..1f) { "wanderChance must be in 0..1, got $wanderChance" }
        require(nearEdgeRadii >= 0f && nearEdgeRadii.isFinite()) { "nearEdgeRadii must be >= 0, got $nearEdgeRadii" }
    }

    companion object {
        /** Slow (about half a second), wanders, dashes when close whatever the alignment, sometimes the wrong way, no special. */
        val EASY = BotSkill(
            thinkMs = 384, thinkJitterTicks = 4, dashRangeShare = 0.7f, alignCos = -1f, aimErrorShare = 0.35f,
            wrongWayChance = 0.25f, wanderChance = 0.2f, wanderMs = 1200, leadsAim = false, weighsPush = false,
            usesSpecial = false, nearEdgeRadii = 0f, sidesteps = false, counters = false, safeRamsOnly = false,
        )

        /** The shared plan at a fifth of a second, a small aim error, the special in its obvious situation. */
        val NORMAL = BotSkill(
            thinkMs = 144, thinkJitterTicks = 2, dashRangeShare = 0.8f, alignCos = 0.8660254f, aimErrorShare = 0.1f,
            wrongWayChance = 0f, wanderChance = 0f, wanderMs = 0, leadsAim = false, weighsPush = false,
            usesSpecial = true, nearEdgeRadii = 4f, sidesteps = false, counters = false, safeRamsOnly = false,
        )

        /**
         * NORMAL deciding every tick or two, leading its aim, weighing where to push (high ground), sidestepping near an
         * edge, countering. The one tick of jitter matters: two HARD bots deciding on the same ticks mirror each other
         * exactly on a symmetric arena (every round a tied timeout, every match a draw).
         */
        val HARD = BotSkill(
            thinkMs = 48, thinkJitterTicks = 1, dashRangeShare = 0.85f, alignCos = 0.8660254f, aimErrorShare = 0f,
            wrongWayChance = 0f, wanderChance = 0f, wanderMs = 0, leadsAim = true, weighsPush = true,
            usesSpecial = true, nearEdgeRadii = 4f, sidesteps = true, counters = true, safeRamsOnly = true,
        )
    }
}
