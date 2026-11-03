package kz.rudy.ar.core.meta

import kz.rudy.ar.core.bundle.Bundle
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterSheet
import kotlin.test.Test
import kotlin.test.assertEquals

class SheetNamesTest {
    private val lines = """"lines": {"kk": ["Сәлем!"], "ru": ["Привет!"]}"""

    @Test
    fun aReplyWithThreeNamesGivesEachLanguageItsOwn() {
        val sheet = SheetParser.parse(
            """{"names": {"kk": "Жылдам", "ru": "Быстрый", "en": "Speedy"}, "personality": "", "ability": "roll", $lines}""",
        )
        assertEquals("Жылдам", sheet.name)
        assertEquals("Жылдам", sheet.nameIn("kk"))
        assertEquals("Быстрый", sheet.nameIn("ru"))
        assertEquals("Speedy", sheet.nameIn("en"))
    }

    @Test
    fun aReplyRecordedWithOneNameShowsItInEveryLanguage() {
        val sheet = SheetParser.parse("""{"name": "Жылдам", "personality": "", "ability": "roll", $lines}""")
        assertEquals("Жылдам", sheet.name)
        assertEquals("Жылдам", sheet.nameIn("en"))
    }

    @Test
    fun aBlankNameFallsBackInsteadOfShowingNothing() {
        val sheet = SheetParser.parse(
            """{"names": {"kk": "Жылдам", "ru": "  ", "en": ""}, "personality": "", "ability": "roll", $lines}""",
        )
        assertEquals("Жылдам", sheet.nameIn("ru"))
        assertEquals("Жылдам", sheet.nameIn("en"))
    }

    @Test
    fun aMissingKazakhNameFallsBackToAnotherOne() {
        val sheet = SheetParser.parse("""{"names": {"ru": "Быстрый", "en": "Speedy"}, "personality": "", "ability": "roll", $lines}""")
        assertEquals("Быстрый", sheet.name)
        assertEquals("Быстрый", sheet.nameIn("kk"))
    }

    @Test
    fun englishLinesAreKeptAndAReplyWithoutThemGetsGenericEnglishOnes() {
        val given = listOf("One", "Two", "Three", "Four", "Five")
        val withEnglish = SheetParser.parse(
            """{"name": "Жылдам", "personality": "", "ability": "roll", "lines": {"kk": [], "ru": [], "en": ["One", "Two", "Three", "Four", "Five"]}}""",
        )
        assertEquals(given, withEnglish.lines["en"])
        val without = SheetParser.parse("""{"name": "Жылдам", "personality": "", "ability": "roll", $lines}""")
        assertEquals(SheetParser.FALLBACK_LINES["en"], without.lines["en"])
    }

    @Test
    fun aCharacterSavedBeforeNamesStillLoads() {
        val sheet = Bundle.decodeSheet("""{"name": "Жылдам", "personality": "", "ability": "ROLL", "lines": {"kk": ["Сәлем!"]}}""")
        assertEquals(emptyMap(), sheet.names)
        assertEquals("Жылдам", sheet.nameIn("ru"))
    }

    @Test
    fun namesSurviveSavingAndRecording() {
        val sheet = CharacterSheet(
            "Жылдам",
            "",
            Ability.ROLL,
            mapOf("kk" to listOf("Сәлем!")),
            mapOf("kk" to "Жылдам", "ru" to "Быстрый", "en" to "Speedy"),
        )
        assertEquals("Быстрый", Bundle.decodeSheet(Bundle.encodeSheet(sheet)).nameIn("ru"))
        assertEquals("Speedy", SheetParser.fromJson(RecordingModels.sheetJson(sheet)).nameIn("en"))
    }
}
