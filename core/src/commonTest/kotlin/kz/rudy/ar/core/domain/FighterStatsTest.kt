package kz.rudy.ar.core.domain

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T40: [StatBudget.normalise] is what guarantees every fighter the same 20 points, whatever the model returned, so it
 * is pinned on a few hundred seeded random inputs, not only on hand-picked ones.
 */
class FighterStatsTest {
    private fun raw(power: Float? = null, speed: Float? = null, guard: Float? = null, agility: Float? = null): Map<Stat, Float> =
        buildMap {
            power?.let { put(Stat.POWER, it) }
            speed?.let { put(Stat.SPEED, it) }
            guard?.let { put(Stat.GUARD, it) }
            agility?.let { put(Stat.AGILITY, it) }
        }

    /** What the model might send for one stat: in range, out of range, far out of range, zero, missing, garbage. */
    private fun Random.score(): Float? = when (nextInt(10)) {
        0 -> null
        1 -> 0f
        2 -> nextFloat() * 1000f
        3 -> 10f.pow(nextInt(-30, 30)) * (0.5f + nextFloat())
        4 -> if (nextBoolean()) -nextFloat() * 10f else Float.NaN
        5 -> nextInt(1, 11).toFloat()
        else -> nextFloat() * 10f
    }

    private fun Float.pow(n: Int): Float = kotlin.math.exp(n * kotlin.math.ln(this.toDouble())).toFloat()

    private fun Random.rawMap(): Map<Stat, Float> =
        buildMap { for (s in Stat.entries) score()?.let { put(s, it) } }

    /** The score [StatBudget.normalise] sees: missing, NaN, infinite and negative are 0. */
    private fun Map<Stat, Float>.usable(s: Stat): Float = this[s]?.takeIf { it.isFinite() && it > 0f } ?: 0f

    private fun assertBalanced(out: FighterStats?, input: Any) {
        assertNotNull(out, "null for $input")
        assertEquals(FighterStats.BUDGET, out.total, "total of $out for $input")
        for (s in Stat.entries) assertTrue(out[s] in FighterStats.MIN..FighterStats.MAX, "$s of $out for $input")
        assertTrue(out.isBalanced)
    }

    @Test
    fun normaliseAlwaysSumsToBudgetWithinBounds() {
        val random = Random(40)
        var checked = 0
        repeat(500) {
            val input = random.rawMap()
            val out = StatBudget.normalise(input)
            if (Stat.entries.none { input.usable(it) > 0f }) {
                assertNull(out, "nothing usable in $input")
            } else {
                assertBalanced(out, input)
                checked++
            }
        }
        assertTrue(checked > 300, "most random maps carry a usable score: $checked")

        // Extremes.
        assertEquals(FighterStats(10, 4, 3, 3), StatBudget.normalise(raw(1e30f, 1e-30f, 1e-30f, 1e-30f)), "one huge, three tiny")
        assertEquals(FighterStats(4, 3, 3, 10), StatBudget.normalise(raw(1e-30f, 1e-30f, 1e-30f, 3e38f)))
        assertEquals(FighterStats.BALANCED, StatBudget.normalise(raw(3f, 3f, 3f, 3f)), "all equal")
        assertEquals(FighterStats.BALANCED, StatBudget.normalise(raw(10f, 10f, 10f, 10f)), "all 10")
        assertEquals(FighterStats.BALANCED, StatBudget.normalise(raw(1e-38f, 1e-38f, 1e-38f, 1e-38f)), "all tiny")
        assertEquals(FighterStats(7, 3, 9, 1), StatBudget.normalise(raw(7f, 3f, 8f)), "agility missing: MIN, the rest proportional")
        assertEquals(FighterStats(10, 4, 3, 3), StatBudget.normalise(raw(power = 5f)), "a single score: MAX, the rest spread")
        assertEquals(FighterStats(10, 8, 1, 1), StatBudget.normalise(raw(100f, 50f)), "the clamped surplus is re-spread")
        assertEquals(FighterStats(7, 3, 8, 2), StatBudget.normalise(raw(7f, 3f, 8f, 2f)), "already on budget: unchanged")
        assertEquals(FighterStats(7, 3, 8, 2), StatBudget.normalise(raw(14f, 6f, 16f, 4f)), "proportional")
        assertEquals(FighterStats(5, 5, 3, 7), StatBudget.normalise(raw(6f, 5f, 3f, 8f)), "largest remainder")
        assertEquals(FighterStats(1, 5, 10, 4), StatBudget.normalise(raw(Float.POSITIVE_INFINITY, 3f, 8f, 2f)), "infinite counts as missing")
        assertBalanced(StatBudget.normalise(raw(Float.MAX_VALUE, Float.MIN_VALUE, 1f, 0f)), "MAX_VALUE next to MIN_VALUE")
        assertBalanced(StatBudget.normalise(raw(-5f, Float.NaN, 2f, Float.POSITIVE_INFINITY)), "one usable among garbage")
    }

    @Test
    fun normaliseKeepsAValueThatIsAlreadyBalanced() {
        val random = Random(41)
        var seen = 0
        while (seen < 200) {
            val s = FighterStats.of { random.nextInt(FighterStats.MIN, FighterStats.MAX + 1) }
            if (!s.isBalanced) continue
            seen++
            assertEquals(s, StatBudget.normalise(Stat.entries.associateWith { s[it].toFloat() }), "$s")
        }
    }

    @Test
    fun normaliseKeepsTheOrderOfRawScores() {
        val random = Random(42)
        repeat(500) {
            val input = random.rawMap()
            val out = StatBudget.normalise(input) ?: return@repeat
            for (a in Stat.entries) for (b in Stat.entries) {
                val ra = input.usable(a)
                val rb = input.usable(b)
                // Strictly larger raw -> never fewer points. Equal raws -> the lower ordinal never has fewer.
                if (ra > rb || (ra == rb && a.ordinal < b.ordinal)) {
                    assertTrue(out[a] >= out[b], "$a=$ra -> ${out[a]} but $b=$rb -> ${out[b]} ($input -> $out)")
                }
            }
        }
    }

    @Test
    fun normaliseIsDeterministicOnTies() {
        // Three equal scores share 19 points: 6.33 each, the one point left goes to the lowest ordinal.
        assertEquals(FighterStats(7, 6, 6, 1), StatBudget.normalise(raw(10f, 10f, 10f)))
        assertEquals(FighterStats(1, 7, 6, 6), StatBudget.normalise(raw(speed = 4f, guard = 4f, agility = 4f)))
        // A single score: the three others split 10 points, 3.33 each.
        assertEquals(FighterStats(4, 3, 3, 10), StatBudget.normalise(raw(agility = 9f)))
        assertEquals(FighterStats(10, 4, 3, 3), StatBudget.normalise(raw(power = 9f, speed = 0f)))
        // Insertion order of the map and repeated calls change nothing.
        val forward = raw(2f, 2f, 5f, 5f)
        val backward = LinkedHashMap<Stat, Float>().apply { for (s in Stat.entries.reversed()) put(s, forward.getValue(s)) }
        val first = StatBudget.normalise(forward)
        assertEquals(FighterStats(3, 3, 7, 7), first)
        repeat(10) {
            assertEquals(first, StatBudget.normalise(forward))
            assertEquals(first, StatBudget.normalise(backward))
        }
    }

    @Test
    fun normaliseOfNothingIsNull() {
        assertNull(StatBudget.normalise(emptyMap()), "empty")
        assertNull(StatBudget.normalise(raw(0f, 0f, 0f, 0f)), "zeros")
        assertNull(StatBudget.normalise(raw(Float.NaN, Float.NaN, Float.NaN, Float.NaN)), "NaN")
        assertNull(StatBudget.normalise(raw(-1f, -10f, -0.5f, -3f)), "negatives")
        assertNull(StatBudget.normalise(raw(-0f, Float.NaN, -2f)), "a mix of nothing")
        assertNull(StatBudget.normalise(raw(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)), "infinities are not scores")
    }

    @Test
    fun everyAbilityArchetypeIsBalanced() {
        for (a in Ability.entries) assertTrue(FighterStats.defaultFor(a).isBalanced, "$a ${FighterStats.defaultFor(a)}")
        assertEquals(
            mapOf(
                Ability.JUMP to FighterStats(5, 5, 5, 5),
                Ability.FLY to FighterStats(4, 6, 4, 6), // T57 (3/7/3/7 before)
                Ability.ROLL to FighterStats(7, 6, 5, 2),
                Ability.HIDE to FighterStats(4, 5, 4, 7),
            ),
            Ability.entries.associateWith { FighterStats.defaultFor(it) },
        )
        assertEquals(Ability.entries.size, Ability.entries.map { FighterStats.defaultFor(it) }.toSet().size, "one archetype each")
    }

    @Test
    fun theDuelReadsBalancedStatsElseRebalancedElseTheArchetype() {
        val sheet = CharacterSheet("Dos", "", Ability.FLY, emptyMap())
        assertEquals(FighterStats(4, 6, 4, 6), sheet.fighter, "no stats")
        assertEquals(FighterStats(7, 3, 8, 2), sheet.copy(stats = FighterStats(7, 3, 8, 2)).fighter)
        assertEquals(FighterStats.BALANCED, sheet.copy(stats = FighterStats(10, 10, 10, 10)).fighter, "a hand-edited sheet over budget")
        assertEquals(FighterStats(1, 9, 5, 5), sheet.copy(stats = FighterStats(0, 10, 5, 5)).fighter, "a stat out of range")
        assertEquals(FighterStats(4, 6, 4, 6), sheet.copy(stats = FighterStats(0, 0, 0, 0)).fighter, "nothing usable")
        assertEquals(FighterStats(4, 6, 4, 6), sheet.copy(stats = FighterStats(-1, -2, -3, -4)).fighter, "nothing usable")
    }
}
