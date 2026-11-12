package kz.rudy.ar.core.duel

import kotlinx.serialization.Serializable
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterSize
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.physics.PhysicsParams
import kotlin.math.roundToLong

/**
 * Every tunable number of the duel ([DuelPhysics]), defaults sized for the table (a 6 cm fighter, radius 3 cm).
 * Distances in metres, speeds in m/s, times in ms. The walking-game numbers (gravity, jump, push-out, step-up) are not
 * here: they come from the [PhysicsParams] passed next to these, scaled by [PhysicsParams.forProfile].
 *
 * A stat factor reads `base + perPoint * stat`, with the stat clamped to [FighterStats.MIN]..[FighterStats.MAX] so a
 * hand-edited sheet cannot produce a negative speed or a reversed knockback.
 */
@Serializable
data class DuelParams(
    /** Duel walk speed multiplier on [PhysicsParams.walkSpeedMps] (the walking game strolls; a duel hurries). */
    val duelSpeedScale: Float = 2f,
    /**
     * Walk speed factor = [speedBase] + [speedPerPoint] * SPEED (0.60 .. 1.50; T48: was 0.70 + 0.06, worth too little
     * against the POWER, GUARD and AGILITY a fast drawing gives up).
     */
    val speedBase: Float = 0.50f,
    val speedPerPoint: Float = 0.10f,
    /**
     * Replaces [PhysicsParams.rollSpeedScale] inside a duel. 1 = the SPEED formula alone sets the walk speed for every
     * ability (the walking game's ROLL x 2 would otherwise stack on top of the stats). T57: 1.1 (was 1): a ROLL cannot jump
     * over a dash, so it gets a little more pace to take the spot behind the other first.
     */
    val rollSpeedScale: Float = 1.1f,

    /** How long a dash lasts. */
    val dashMs: Long = 250,
    /** Dash speed along the dash direction (replaces walking while it lasts). 250 ms x 0.30 m/s = 7.5 cm. */
    val dashSpeedMps: Float = 0.30f,
    /**
     * Base dash cooldown, counted from the dash start: [dashCooldownMs] * ([cooldownBase] - [cooldownPerAgility] * AGILITY)
     * (2800 .. 2125 ms, 2500 at AGILITY 5; T48: was 1500 * (1.30 - 0.06 AGILITY): a slower beat, so a round is a few
     * exchanges, and AGILITY worth as much a point as the others).
     */
    val dashCooldownMs: Long = 2500,
    val cooldownBase: Float = 1.15f,
    val cooldownPerAgility: Float = 0.03f,

    /** Knockback speed given by a POWER 5 attacker to a GUARD 5 victim (with no wobble: 4.5 cm of slide). */
    val knockSpeedMps: Float = 0.25f,
    /** Attacker factor = [powerBase] + [powerPerPoint] * POWER (0.68 .. 1.40; T48: was 0.55 + 0.09). */
    val powerBase: Float = 0.60f,
    val powerPerPoint: Float = 0.08f,
    /** Victim divisor = [guardBase] + [guardPerPoint] * GUARD (T48: was 0.55 + 0.09). */
    val guardBase: Float = 0.60f,
    val guardPerPoint: Float = 0.08f,
    /** Knockback multiplier when the attacker's feet are more than [fromAboveMinM] above the victim's (high ground). */
    val fromAboveScale: Float = 1.25f,
    val fromAboveMinM: Float = 0.01f,
    /** Knockback velocity decays as exp(-t / tau): a hit at v0 slides the victim about v0 * tau. */
    val knockTauMs: Long = 180,
    /** Above this knockback speed the fighter is still stunned; the stun timer only runs once it is below. */
    val knockStunMps: Float = 0.02f,
    /** Below this knockback speed the knockback is dropped to zero (a numerical cut-off, about 0.5 mm of slide left). */
    val knockStopMps: Float = 0.003f,

    /** Base stun after a hit: [hitStunMs] * ([stunBase] - [stunPerAgility] * AGILITY), counted once the knockback is spent. */
    val hitStunMs: Long = 300,
    val stunBase: Float = 1.30f,
    val stunPerAgility: Float = 0.06f,
    /** Stun added by slamming into an object that blocks the knockback. */
    val wallSlamStunMs: Long = 400,
    /**
     * A knockback pressing into a blocking object at least this fast (its component into the surface) is a wall slam;
     * slower contact slides along the surface instead.
     */
    val wallSlamMinMps: Float = 0.02f,

    /**
     * Internal fixed sub-step, 1..8 ms. Sub-steps sit on a fixed grid of `DuelState.timeMs`, so any split of the same
     * time into frames runs exactly the same sub-steps (see [DuelPhysics.step]).
     */
    val subStepMs: Long = 8,

    // Specials (T45): one per Ability, started by DuelInput.Special. Only times and dimensionless numbers (sizes in
    // fighter radii), so they follow the surface and the character size without being in [scaled].

    /**
     * Base special cooldown, counted from the special's start and scaled by agility like the dash:
     * [specialCooldownMs] * ([cooldownBase] - [cooldownPerAgility] * AGILITY) (10 080 .. 7650 ms, 9000 at AGILITY 5;
     * T48: was 6000. The stomp and the ram are hits outside the dash cooldown, and with wobble every extra hit counts).
     */
    val specialCooldownMs: Long = 9000,
    /** JUMP stomp: an opponent whose centre is within this many of the stomper's radii of the landing point is hit. */
    val stompRadiusRadii: Float = 3f,
    /**
     * JUMP stomp: knockback = [stompScale] x the plain knockback (the high-ground bonus does not stack on it). T57: 1.5
     * (T48: 0.85, before 1.2): since T57 it spends the dash too and a jump gets out of its way, so a bot stomps only a
     * fighter that cannot (a ROLL, or one stunned through the landing); at 2.0 x that made JUMP v ROLL up to 92 / 8.
     */
    val stompScale: Float = 1.5f,
    /**
     * FLY lift-off: how long the fighter hovers, cannot be hit and walks at [liftSpeedScale] x its walk speed. T57: 600
     * (was 1200; a lift is a dodge of one dash either way, and a FLY's own jump now floats out of reach for 2 s).
     */
    val liftMs: Long = 600,
    val liftSpeedScale: Float = 1.5f,
    /** FLY lift-off: hover height of the feet above the ground under them, in fighter radii (0.5 = 1.5 cm on the table). */
    val liftHeightRadii: Float = 0.5f,
    /**
     * ROLL ram: a dash [ramDashScale] x as long as [dashMs] (325 ms, 9.75 cm at SPEED 5) hitting with [ramKnockScale] x
     * the knockback (T48: 2.2 x and 1.5 x -> 1.3 x and 1.1 x, a ram was a hit outside the dash cooldown; T57: 1.3 x and
     * 2.0 x, it spends the dash now and a ROLL cannot jump over the other's).
     */
    val ramDashScale: Float = 1.3f,
    val ramKnockScale: Float = 2.0f,
    /** HIDE dodge: for [dodgeMs] hits pass through the fighter (a dash of its own ends the dodge at once; T48: 500 -> 900; T57: 700). */
    val dodgeMs: Long = 700,
    /**
     * HIDE counter: a hit the dodge let through opens a window of [counterMs] from that sub-step; the first dash started
     * in it hits with [counterKnockScale] x (T48: 1.5 -> 2.0; T57: back to 1.5). A dodge that dodged nothing gives no
     * counter.
     */
    val counterMs: Long = 1500,
    val counterKnockScale: Float = 1.5f,

    // Wobble (T48): every hit a fighter takes in a round makes the next one push it farther, so a ring-out takes a few
    // hits on a large table without one hit being a ring-out on a small one. Dimensionless and per second, so they
    // follow the surface without being in [scaled]. wobblePerHit = 0 is the physics without it, exactly. Defaults: hit n
    // of a round knocks 1, 1.8, 2.6, 3.4, then 4.2 x (4.5, 8.1, 11.7, 15.3, 18.9 cm at 5 v 5; T48's 0.7: 1 .. 3.8 x).

    /** Wobble a fighter gains with every hit it takes (a dash, a ram, a counter, a stomp; each side of a clash). */
    val wobblePerHit: Float = 1f,
    /** A hit on a fighter with wobble w knocks it (1 + [wobbleKnockScale] * w) x the plain knockback (T57: 0.8, was 0.7). */
    val wobbleKnockScale: Float = 0.8f,
    /** Wobble never exceeds this. */
    val wobbleMax: Float = 4f,
    /** Wobble lost per second of fight (0 = it stays until the round ends). */
    val wobbleDecayPerS: Float = 0f,

    // T57: the user's rule decisions of 2026-11-12, on by default. Each has an off position that is the physics before
    // T57, bit for bit (`theNewRulesSwitchedOffAreThePreviousPhysics`): false; 1 and 0; [NO_REACH_LIMIT] for both reaches.

    /**
     * Rule 1: the JUMP stomp and the ROLL ram are hits, so they share the dash cooldown: [DuelInput.Special] for those two
     * needs the dash ready as well as the special ([DuelPhysics.canSpecial]) and starts both cooldowns. FLY's lift-off and
     * HIDE's dodge are defensive and never touch the dash. false = the specials leave the dash alone (before T57).
     */
    val specialsShareDashCooldown: Boolean = true,
    /**
     * Rule 2: SPEED lengthens the dash. Every dash (plain, counter, ram) moves at [dashSpeedMps] x
     * ([dashReachBase] + [dashReachPerSpeed] * SPEED), so its reach grows with SPEED and its duration stays. 1 and 0 =
     * every SPEED dashes as far (before T57). SPEED keeps setting the walk speed too.
     */
    val dashReachBase: Float = 0.75f,
    val dashReachPerSpeed: Float = 0.05f,
    /**
     * Rule 3: height matters for hits. A dash or a stomp hits only a victim whose feet are at most [hitReachUpM] above the
     * attacker's feet and at most [hitReachDownM] below them, judged at the sub-step the hit is judged in: a jump over a
     * dash is a dodge, a floating FLY is out of reach of the ground, a fighter clearly higher is safe from below until the
     * attacker climbs. A miss uses nothing up: the dash carries on, no wobble, no stun, no counter for a dodge. Heights
     * (scaled with the surface like [fromAboveMinM], not with the character's size). [NO_REACH_LIMIT] = no limit (before
     * T57); +infinity reads the same but does not survive JSON. Tuned: 2.8 cm up (the brief's starting point 2 cm), so a
     * 3 cm book still puts its fighter out of reach of the table; 4 cm down (the platform height, as the brief).
     */
    val hitReachUpM: Float = 0.028f,
    val hitReachDownM: Float = 0.04f,
) {
    init {
        // T48 searches these numbers: refuse any set that could divide by zero, reverse a push or stun forever.
        require(subStepMs in 1L..8L) { "subStepMs must be 1..8 ms, got $subStepMs" }
        require(knockTauMs > 0L) { "knockTauMs must be > 0, got $knockTauMs" }
        require(dashMs >= 0L) { "dashMs must be >= 0, got $dashMs" }
        require(dashCooldownMs >= 0L) { "dashCooldownMs must be >= 0, got $dashCooldownMs" }
        require(hitStunMs >= 0L) { "hitStunMs must be >= 0, got $hitStunMs" }
        require(wallSlamStunMs >= 0L) { "wallSlamStunMs must be >= 0, got $wallSlamStunMs" }
        positive("duelSpeedScale", duelSpeedScale)
        positive("rollSpeedScale", rollSpeedScale)
        positive("fromAboveScale", fromAboveScale)
        positive("knockStopMps", knockStopMps)
        nonNegative("dashSpeedMps", dashSpeedMps)
        nonNegative("knockSpeedMps", knockSpeedMps)
        nonNegative("knockStunMps", knockStunMps)
        nonNegative("wallSlamMinMps", wallSlamMinMps)
        nonNegative("fromAboveMinM", fromAboveMinM)
        statFactor("speedBase + speedPerPoint * SPEED", speedBase, speedPerPoint)
        statFactor("powerBase + powerPerPoint * POWER", powerBase, powerPerPoint)
        statFactor("guardBase + guardPerPoint * GUARD", guardBase, guardPerPoint)
        statFactor("cooldownBase - cooldownPerAgility * AGILITY", cooldownBase, -cooldownPerAgility)
        statFactor("stunBase - stunPerAgility * AGILITY", stunBase, -stunPerAgility)
        require(specialCooldownMs >= 0L) { "specialCooldownMs must be >= 0, got $specialCooldownMs" }
        require(liftMs >= 0L) { "liftMs must be >= 0, got $liftMs" }
        require(dodgeMs >= 0L) { "dodgeMs must be >= 0, got $dodgeMs" }
        require(counterMs >= 0L) { "counterMs must be >= 0, got $counterMs" }
        nonNegative("stompRadiusRadii", stompRadiusRadii)
        positive("stompScale", stompScale)
        positive("liftSpeedScale", liftSpeedScale)
        // 0 would put a hovering body on the ground with no vertical speed, which Physics launches as a full jump.
        positive("liftHeightRadii", liftHeightRadii)
        positive("ramDashScale", ramDashScale)
        positive("ramKnockScale", ramKnockScale)
        positive("counterKnockScale", counterKnockScale)
        require((dashMs * ramDashScale).isFinite()) { "ramDashScale must be finite, got $ramDashScale" }
        for ((name, v) in listOf(
            "wobblePerHit" to wobblePerHit, "wobbleKnockScale" to wobbleKnockScale, "wobbleMax" to wobbleMax, "wobbleDecayPerS" to wobbleDecayPerS,
        )) require(v >= 0f && v.isFinite()) { "$name must be >= 0 and finite, got $v" }
        val most = 1f + wobbleKnockScale * wobbleMax
        require(most <= MAX_WOBBLE_FACTOR) { "1 + wobbleKnockScale * wobbleMax must be <= $MAX_WOBBLE_FACTOR, got $most" }
        statFactor("dashReachBase + dashReachPerSpeed * SPEED", dashReachBase, dashReachPerSpeed)
        nonNegative("hitReachUpM", hitReachUpM)
        nonNegative("hitReachDownM", hitReachDownM)
    }

    /**
     * Rule 1, the one place that says it: a Special of [ability] needs the dash ready and starts the dash cooldown too
     * (the JUMP stomp and the ROLL ram when [specialsShareDashCooldown]). [DuelPhysics.canSpecial], the HUD and the bot
     * read it here.
     */
    fun sharesDashCooldown(ability: Ability?): Boolean =
        specialsShareDashCooldown && (ability == Ability.JUMP || ability == Ability.ROLL)

    /** Rule 2: the dash speed of a fighter with [stats], m/s: `dashSpeedMps * (dashReachBase + dashReachPerSpeed * SPEED)`. */
    fun dashSpeedMpsFor(stats: FighterStats): Float =
        dashSpeedMps * (dashReachBase + dashReachPerSpeed * clampStat(stats.speed))

    /** How far a plain dash of a fighter with [stats] carries it when it meets nobody: [dashSpeedMpsFor] x [dashMs]. */
    fun dashReachMFor(stats: FighterStats): Float = dashSpeedMpsFor(stats) * dashMs / 1000f

    /** How far a ROLL ram of a fighter with [stats] carries it when it meets nobody: [dashSpeedMpsFor] x [ramDashMs]. */
    fun ramReachMFor(stats: FighterStats): Float = dashSpeedMpsFor(stats) * ramDashMs / 1000f

    /**
     * Rule 3: a hit by an attacker whose feet are at [attackerH] can reach a victim whose feet are at [victimH]: at most
     * [hitReachUpM] above, at most [hitReachDownM] below. Always true with [NO_REACH_LIMIT] (heights are finite).
     */
    fun inReach(attackerH: Float, victimH: Float): Boolean {
        val dh = victimH - attackerH
        return dh <= hitReachUpM && -dh <= hitReachDownM
    }

    /** Rule 3 is on: some height difference puts a victim out of reach. */
    val reachLimited: Boolean get() = hitReachUpM < NO_REACH_LIMIT || hitReachDownM < NO_REACH_LIMIT

    /** The knockback factor of a hit on a fighter with [wobble]: `1 + wobbleKnockScale * wobble` (wobble clamped to 0..[wobbleMax]). */
    fun wobbleFactor(wobble: Float): Float = 1f + wobbleKnockScale * wobble.coerceIn(0f, wobbleMax)

    /** Walk speed of a fighter with [stats]: `physics.walkSpeedMps * duelSpeedScale * (speedBase + speedPerPoint * SPEED)`. */
    fun walkSpeedMpsFor(stats: FighterStats, physics: PhysicsParams = PhysicsParams.DEFAULT): Float =
        physics.walkSpeedMps * duelSpeedScale * (speedBase + speedPerPoint * clampStat(stats.speed))

    /** Dash cooldown of a fighter with [stats], counted from the dash start (>= 0). */
    fun cooldownMsFor(stats: FighterStats): Long =
        (dashCooldownMs * (cooldownBase - cooldownPerAgility * clampStat(stats.agility))).roundToLong().coerceAtLeast(0L)

    /** Special cooldown of a fighter with [stats], counted from the special's start (>= 0). */
    fun specialCooldownMsFor(stats: FighterStats): Long =
        (specialCooldownMs * (cooldownBase - cooldownPerAgility * clampStat(stats.agility))).roundToLong().coerceAtLeast(0L)

    /** How long a ROLL ram lasts: [dashMs] * [ramDashScale]. */
    val ramDashMs: Long get() = (dashMs * ramDashScale).roundToLong()

    /** Stun timer a hit gives a victim with [victim] stats (>= 0); it starts once the knockback is below [knockStunMps]. */
    fun hitStunMsFor(victim: FighterStats): Long =
        (hitStunMs * (stunBase - stunPerAgility * clampStat(victim.agility))).roundToLong().coerceAtLeast(0L)

    /**
     * Knockback speed an [attacker] gives a [victim]:
     * `knockSpeedMps * (powerBase + powerPerPoint * POWER) / (guardBase + guardPerPoint * GUARD)`, x [fromAboveScale]
     * when [fromAbove].
     */
    fun knockSpeedMpsFor(attacker: FighterStats, victim: FighterStats, fromAbove: Boolean = false): Float {
        val push = powerBase + powerPerPoint * clampStat(attacker.power)
        val guard = guardBase + guardPerPoint * clampStat(victim.guard)
        val v = knockSpeedMps * push / guard
        return if (fromAbove) v * fromAboveScale else v
    }

    /**
     * This set scaled for [profile] at character [size] ([CharacterSize]), like [PhysicsParams.forProfile]: speeds
     * scale with `profile.scale * size` (horizontal motion), the heights ([fromAboveMinM], [hitReachUpM],
     * [hitReachDownM]) with `profile.scale` only (heights stay the surface's at any size; [NO_REACH_LIMIT] stays itself).
     * Times, stat factors and other dimensionless numbers stay.
     */
    fun scaled(profile: SurfaceProfile, size: Float = CharacterSize.DEFAULT): DuelParams {
        val horizontal = profile.scale * size
        fun height(v: Float) = if (v >= NO_REACH_LIMIT) v else (v * profile.scale).coerceAtMost(NO_REACH_LIMIT)
        return copy(
            dashSpeedMps = dashSpeedMps * horizontal,
            knockSpeedMps = knockSpeedMps * horizontal,
            knockStunMps = knockStunMps * horizontal,
            knockStopMps = knockStopMps * horizontal,
            wallSlamMinMps = wallSlamMinMps * horizontal,
            fromAboveMinM = fromAboveMinM * profile.scale,
            hitReachUpM = height(hitReachUpM),
            hitReachDownM = height(hitReachDownM),
        )
    }

    companion object {
        /**
         * The most wobble may multiply a knockback by, `1 + wobbleKnockScale * wobbleMax` (3.8 by default): a sanity bound
         * on a `--params` file. The physics is swept, so even this cannot carry a fighter through a thin object.
         */
        const val MAX_WOBBLE_FACTOR = 10f

        /**
         * T57: "no limit" for [hitReachUpM] / [hitReachDownM] ([Float.MAX_VALUE], about 3.4e38 m): every height
         * difference is within it, so a hit reaches as before T57. A plain number, so it survives JSON (+infinity does
         * not), and [scaled] keeps it as it is.
         */
        const val NO_REACH_LIMIT = Float.MAX_VALUE

        val DEFAULT = DuelParams()

        /** [DEFAULT] scaled for [profile] and [size]; `forProfile(SurfaceProfile.TABLE) == DEFAULT`. */
        fun forProfile(profile: SurfaceProfile, size: Float = CharacterSize.DEFAULT): DuelParams = DEFAULT.scaled(profile, size)

        fun forSurface(surface: Surface, size: Float = CharacterSize.DEFAULT): DuelParams =
            forProfile(SurfaceProfile.of(surface), size)

        private fun clampStat(v: Int): Float = v.coerceIn(FighterStats.MIN, FighterStats.MAX).toFloat()
    }
}

// Validation helpers for DuelParams.init (top level, so DEFAULT's construction never depends on the companion).
// NaN fails every check (comparisons with NaN are false).
private fun positive(name: String, v: Float) = require(v > 0f) { "$name must be > 0, got $v" }

private fun nonNegative(name: String, v: Float) = require(v >= 0f) { "$name must be >= 0, got $v" }

/** `base + perPoint * stat` is linear in the stat: > 0 at both ends means > 0 for every stat. */
private fun statFactor(name: String, base: Float, perPoint: Float) {
    for (s in intArrayOf(FighterStats.MIN, FighterStats.MAX)) {
        val f = base + perPoint * s
        require(f > 0f) { "$name must be > 0 for every stat, got $f at $s" }
    }
}
