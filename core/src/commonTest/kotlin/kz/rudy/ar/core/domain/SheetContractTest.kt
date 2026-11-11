package kz.rudy.ar.core.domain

import kz.rudy.ar.core.bundle.Bundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The v2 contract types (ai/build-plan-v2.md): what T40, T41 and T42 build on. */
class SheetContractTest {
    private val legacy = CharacterSheet("Dos", "kind", Ability.JUMP, mapOf("kk" to listOf("Сәлем!")))

    @Test
    fun sheetWithoutTheNewFieldsWritesNoneOfThem() {
        val json = Bundle.json.encodeToString(CharacterSheet.serializer(), legacy)
        for (key in listOf("stats", "statReasons", "baseView", "facesRight")) assertFalse("\"$key\"" in json, "$key leaked into $json")
    }

    @Test
    fun sheetWithTheNewFieldsRoundTrips() {
        val sheet = legacy.copy(
            stats = FighterStats(7, 3, 8, 2),
            statReasons = mapOf("en" to mapOf(Stat.POWER to "big claws")),
            baseView = SpriteView.SIDE,
            facesRight = true,
        )
        val json = Bundle.json.encodeToString(CharacterSheet.serializer(), sheet)
        assertEquals(sheet, Bundle.json.decodeFromString(CharacterSheet.serializer(), json))
    }

    @Test
    fun balancedMeansInRangeAndOnBudget() {
        assertTrue(FighterStats.BALANCED.isBalanced)
        assertTrue(FighterStats(7, 3, 8, 2).isBalanced)
        assertFalse(FighterStats(10, 10, 10, 10).isBalanced)
        assertFalse(FighterStats(0, 10, 5, 5).isBalanced)
        assertEquals(8, FighterStats(7, 3, 8, 2)[Stat.GUARD])
    }
}
