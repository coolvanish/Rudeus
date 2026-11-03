package kz.rudy.ar

import kz.rudy.ar.ui.Lang
import kotlin.test.Test
import kotlin.test.assertEquals

class LangTest {

    @Test
    fun topSystemLanguageWinsAndKeepsItsOrder() {
        assertEquals(listOf(Lang.RU, Lang.KK, Lang.EN), Lang.preferred(listOf("ru", "kk")))
        assertEquals(listOf(Lang.KK, Lang.RU, Lang.EN), Lang.preferred(listOf("kk", "ru", "en")))
    }

    @Test
    fun unsupportedLanguagesAreSkipped() {
        assertEquals(Lang.RU, Lang.preferred(listOf("fr", "ru", "en")).first())
    }

    @Test
    fun noSupportedLanguageMeansEnglish() {
        assertEquals(Lang.EN, Lang.preferred(listOf("fr", "de")).first())
        assertEquals(Lang.EN, Lang.preferred(emptyList()).first())
    }

    @Test
    fun everyLanguageIsListedOnce() {
        assertEquals(Lang.entries.toSet(), Lang.preferred(listOf("en", "en", "fr")).toSet())
        assertEquals(Lang.entries.size, Lang.preferred(listOf("en", "en", "fr")).size)
    }
}
