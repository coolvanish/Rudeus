package kz.rudy.ar

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Stat
import kz.rudy.ar.core.domain.defaultFor
import kz.rudy.ar.ui.Lang
import kz.rudy.ar.ui.components.fighterRows
import kz.rudy.ar.ui.components.hasDrawnStats
import kz.rudy.ar.ui.components.statLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FighterStatsUiTest {

    private fun sheet(
        stats: FighterStats? = null,
        reasons: Map<String, Map<Stat, String>> = emptyMap(),
        ability: Ability = Ability.JUMP,
    ) = CharacterSheet(
        name = "Мұрат",
        personality = "",
        ability = ability,
        lines = emptyMap(),
        stats = stats,
        statReasons = reasons,
    )

    @Test
    fun rowsFollowStatOrderAndUseFighterValues() {
        val s = sheet(stats = FighterStats(power = 8, speed = 2, guard = 6, agility = 4))
        val rows = fighterRows(s, Lang.EN)
        assertEquals(Stat.entries, rows.map { it.stat })
        assertEquals(listOf(8, 2, 6, 4), rows.map { it.value })
        assertEquals(20, rows.sumOf { it.value })
        assertTrue(hasDrawnStats(s))

        // An off-budget sheet shows what the duel reads (rebalanced), not the raw numbers.
        val loose = sheet(stats = FighterStats(10, 10, 10, 10))
        assertEquals(listOf(5, 5, 5, 5), fighterRows(loose, Lang.EN).map { it.value })
    }

    @Test
    fun reasonsFallBackToEnglishThenNone() {
        val s = sheet(
            stats = FighterStats(7, 3, 5, 5),
            reasons = mapOf(
                "kk" to mapOf(Stat.POWER to "үлкен тістер"),
                "en" to mapOf(Stat.POWER to "big teeth", Stat.SPEED to "heavy shell", Stat.GUARD to "  "),
            ),
        )
        val kk = fighterRows(s, Lang.KK).associateBy { it.stat }
        assertEquals("үлкен тістер", kk.getValue(Stat.POWER).reason)
        assertEquals("heavy shell", kk.getValue(Stat.SPEED).reason) // kk lacks the stat: English
        assertNull(kk.getValue(Stat.GUARD).reason) // blank counts as none
        assertNull(kk.getValue(Stat.AGILITY).reason) // nobody has it

        // A language the sheet lacks entirely falls back to English as a whole.
        val ru = fighterRows(s, Lang.RU).associateBy { it.stat }
        assertEquals("big teeth", ru.getValue(Stat.POWER).reason)

        // No reasons at all: none, and the values are still there.
        val bare = fighterRows(sheet(stats = FighterStats(7, 3, 5, 5)), Lang.RU)
        assertTrue(bare.all { it.reason == null })
        assertEquals(listOf(7, 3, 5, 5), bare.map { it.value })
    }

    @Test
    fun sheetWithoutStatsShowsItsArchetypeAndNoReasons() {
        // Reasons on a sheet that has no stats are not shown: the numbers are the archetype, not what Spark saw.
        val s = sheet(stats = null, reasons = mapOf("en" to mapOf(Stat.POWER to "big teeth")), ability = Ability.FLY)
        assertFalse(hasDrawnStats(s))
        val rows = fighterRows(s, Lang.EN)
        val archetype = FighterStats.defaultFor(Ability.FLY)
        assertEquals(Stat.entries.map { archetype[it] }, rows.map { it.value })
        assertTrue(rows.all { it.reason == null })
    }

    @Test
    fun everyStatHasALabelInThreeLanguages() {
        val labels = Stat.entries.map { statLabel(it) }
        assertEquals(Stat.entries.size, labels.toSet().size)
        for (id in labels) {
            for (lang in Lang.entries) assertTrue(id.get(lang).isNotBlank(), "${id.name} in ${lang.code}")
        }
        assertEquals(listOf("Күш", "Сила", "Power"), listOf(Lang.KK, Lang.RU, Lang.EN).map { statLabel(Stat.POWER).get(it) })
    }
}
