package kz.rudy.ar.core.bundle

import kz.rudy.ar.core.meta.DiskFixtureSource
import kz.rudy.ar.core.meta.SheetParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The shipped characters, which a bundle regenerated from fixtures recorded before `names` and `en` lines would break. */
class BundledLanguagesTest {
    private val dir = "androidApp/src/main/assets/bundled"

    @Test
    fun everyBundledCharacterHasItsOwnNameAndLinesInEachLanguage() {
        val repo = DiskFixtureSource.repoRoot()
        val index = Bundle.decodeIndex(repo.readText("$dir/${Bundle.INDEX}"))
        assertTrue(index.characters.isNotEmpty())
        for (entry in index.characters) {
            val sheet = SheetParser.parse(repo.readText("$dir/${entry.sheet}")!!)
            assertEquals(setOf("kk", "ru", "en"), sheet.names.keys, entry.ref)
            assertEquals(entry.name, sheet.nameIn("kk"), entry.ref)
            for (lang in SheetParser.LANGUAGES) {
                assertNotEquals(SheetParser.FALLBACK_LINES.getValue(lang), sheet.lines.getValue(lang), "${entry.ref} $lang")
            }
        }
    }
}
