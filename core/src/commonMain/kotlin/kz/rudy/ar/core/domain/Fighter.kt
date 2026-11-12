package kz.rudy.ar.core.domain

import kotlinx.serialization.Serializable

/** The four numbers a duel reads from a character (ai/build-plan-v2.md, "Stats"). */
@Serializable
enum class Stat {
    /** How hard a dash knocks the other fighter back. */
    POWER,

    /** Walking speed. */
    SPEED,

    /** Resistance to knockback. */
    GUARD,

    /** Cooldowns and stun recovery. */
    AGILITY,
}

/**
 * A character's duel stats. A well-formed value has every stat in [MIN]..[MAX] and [total] == [BUDGET]: every fighter
 * gets the same budget, so a drawing trades one stat for another and none is simply weaker. The class does not
 * enforce it (a hand-edited saved sheet must still load); [isBalanced] says whether a value keeps the rule, and
 * [CharacterSheet.fighter] rebalances a value that does not ([StatBudget.normalise]) before the duel reads it.
 */
@Serializable
data class FighterStats(
    val power: Int,
    val speed: Int,
    val guard: Int,
    val agility: Int,
) {
    operator fun get(stat: Stat): Int = when (stat) {
        Stat.POWER -> power
        Stat.SPEED -> speed
        Stat.GUARD -> guard
        Stat.AGILITY -> agility
    }

    val total: Int get() = power + speed + guard + agility

    /** Every stat in [MIN]..[MAX] and [total] == [BUDGET]. */
    val isBalanced: Boolean get() = Stat.entries.all { this[it] in MIN..MAX } && total == BUDGET

    companion object {
        const val MIN = 1
        const val MAX = 10
        const val BUDGET = 20

        /** 5 / 5 / 5 / 5. */
        val BALANCED = FighterStats(5, 5, 5, 5)

        /** Builds a value from a per-stat function, in [Stat] order. */
        fun of(value: (Stat) -> Int): FighterStats =
            FighterStats(value(Stat.POWER), value(Stat.SPEED), value(Stat.GUARD), value(Stat.AGILITY))
    }
}

/**
 * The stats of a character whose sheet has none (every v1.0 character): one fixed, balanced archetype per ability,
 * power / speed / guard / agility. JUMP is the all-rounder, FLY is fast and nimble (T57: 4/6/4/6, was 3/7/3/7, which
 * won only 3..44 % of its matches against the field in the duel), ROLL rams, HIDE dodges.
 */
fun FighterStats.Companion.defaultFor(ability: Ability): FighterStats = when (ability) {
    Ability.JUMP -> FighterStats(5, 5, 5, 5)
    Ability.FLY -> FighterStats(4, 6, 4, 6)
    Ability.ROLL -> FighterStats(7, 6, 5, 2)
    Ability.HIDE -> FighterStats(4, 5, 4, 7)
}

/**
 * What the duel reads, one rule however the sheet was loaded (model reply, `Bundle.json`, `SheetParser` on a saved
 * sheet): [CharacterSheet.stats] when balanced; else those stats taken as raw scores and rebalanced
 * ([StatBudget.normalise]: a hand-edited 10/10/10/10 fights as 5/5/5/5); else (no stats, or no usable score in them)
 * the archetype of the ability.
 */
val CharacterSheet.fighter: FighterStats
    get() {
        val s = stats ?: return FighterStats.defaultFor(ability)
        if (s.isBalanced) return s
        return StatBudget.normalise(Stat.entries.associateWith { s[it].toFloat() }) ?: FighterStats.defaultFor(ability)
    }

/**
 * Turns the model's raw stat scores into a [FighterStats] that keeps the budget rule. The model is asked for 1..10
 * per stat but is free to be generous, stingy or out of range: this, not the model, is what guarantees that every
 * fighter has exactly [FighterStats.BUDGET] points.
 */
object StatBudget {
    private const val SEARCH_STEPS = 200

    /**
     * Raw model scores (any finite, non-negative numbers; missing stat = 0) -> a balanced FighterStats:
     * every stat in MIN..MAX, total == BUDGET. Proportional to the raw scores, largest remainder for the
     * rounding, ties broken by Stat ordinal, then clamped and re-spread so the bounds hold.
     * All zeros (or NaN / negative everywhere) -> null: the caller falls back to the archetype.
     *
     * How: the real-valued share of stat i is `clamp(k * raw_i, MIN, MAX)` with the one scale `k` that makes the shares
     * sum to BUDGET (a clamped stat's surplus or deficit is re-spread over the others by the same `k`). When a single
     * stat has a positive score it cannot hold more than MAX, so the rest of the budget is spread evenly over the others.
     * Each share is then floored and the points still missing go to the largest fractional parts; equal fractions go
     * to the larger raw score, then to the lower [Stat] ordinal. A share is never rounded past a bound (a share at MAX
     * has no fraction), and a larger raw score never ends up with fewer points than a smaller one.
     * Infinite and NaN scores count as missing.
     */
    fun normalise(raw: Map<Stat, Float>): FighterStats? {
        val stats = Stat.entries
        val r = DoubleArray(stats.size) { i -> raw[stats[i]]?.toDouble()?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0 }
        val positive = r.count { it > 0.0 }
        if (positive == 0) return null
        val min = FighterStats.MIN.toDouble()
        val max = FighterStats.MAX.toDouble()
        val budget = FighterStats.BUDGET.toDouble()

        val share: DoubleArray = if (positive * max + (stats.size - positive) * min < budget) {
            // One stat alone cannot take the budget: it gets MAX, the others split what is left evenly.
            val rest = (budget - positive * max) / (stats.size - positive)
            DoubleArray(stats.size) { i -> if (r[i] > 0.0) max else rest }
        } else {
            fun shares(k: Double) = DoubleArray(stats.size) { i -> (k * r[i]).coerceIn(min, max) }
            // sum(shares(k)) is continuous and non-decreasing in k: MIN * size (< BUDGET) at k = MIN / largest raw,
            // >= BUDGET at k = MAX / smallest positive raw (every positive stat at MAX). Bisect geometrically between
            // the two, so raw scores many orders of magnitude apart (1e30 next to 1e-30) still converge.
            var lo = min / r.max()
            var hi = max / r.fold(Double.MAX_VALUE) { m, x -> if (x > 0.0 && x < m) x else m }
            repeat(SEARCH_STEPS) {
                val mid = kotlin.math.sqrt(lo) * kotlin.math.sqrt(hi)
                if (mid <= lo || mid >= hi) return@repeat
                if (shares(mid).sum() < budget) lo = mid else hi = mid
            }
            shares(hi)
        }

        val out = IntArray(stats.size) { i -> kotlin.math.floor(share[i]).toInt() }
        var missing = FighterStats.BUDGET - out.sum()
        val byRemainder = stats.indices.sortedWith(
            compareByDescending<Int> { share[it] - out[it] }.thenByDescending { r[it] }.thenBy { it },
        )
        // The shares sum to BUDGET (bisection stops on the >= side, a hair over at most), so the floors never exceed
        // it and at least `missing` shares have a fraction: every missing point lands on a share below MAX.
        for (i in byRemainder) {
            if (missing <= 0) break
            if (out[i] < FighterStats.MAX) { out[i]++; missing-- }
        }
        return FighterStats.of { out[it.ordinal] }
    }
}
