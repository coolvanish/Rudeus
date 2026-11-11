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
 * enforce it (a hand-edited saved sheet must still load); [isBalanced] says whether a value keeps the rule.
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
    }
}
