package kz.rudy.ar.core.bundle

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.io.MemoryCharacterFolder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SavedCharactersTest {
    private fun sheet(name: String) = CharacterSheet(
        name = name,
        personality = "батыл",
        ability = Ability.FLY,
        lines = mapOf("kk" to listOf("Сәлем!"), "ru" to listOf("Привет!")),
    )

    private fun sprite(n: Int) = byteArrayOf(n.toByte(), 1, 2)

    @Test
    fun savedCharacterComesBackWhenTheAppStartsAgain() {
        val folder = MemoryCharacterFolder()
        val id = SavedCharacters(folder, clock = { 1000L }).save(sprite(7), sheet("Мұрат")).id

        val loaded = SavedCharacters(folder).load().single()

        assertEquals(id, loaded.id)
        assertEquals(sheet("Мұрат"), loaded.sheet)
        assertContentEquals(sprite(7), loaded.sprite)
    }

    @Test
    fun newestComesFirstWhenTheClockStandsStillOrGoesBack() {
        var now = 5000L
        val saved = SavedCharacters(MemoryCharacterFolder(), clock = { now })
        saved.save(sprite(1), sheet("first"))
        saved.save(sprite(2), sheet("same ms"))
        now = 10L
        saved.save(sprite(3), sheet("clock went back"))

        assertEquals(listOf("clock went back", "same ms", "first"), saved.load().map { it.sheet.name })
    }

    @Test
    fun oldestGoesPastTheCapAndItsFilesWithIt() {
        val folder = MemoryCharacterFolder()
        val saved = SavedCharacters(folder, maxKept = 2)
        saved.save(sprite(1), sheet("a"))
        saved.save(sprite(2), sheet("b"))
        saved.save(sprite(3), sheet("c"))

        assertEquals(listOf("c", "b"), saved.load().map { it.sheet.name })
        assertEquals(2, folder.sprites.size)
        assertEquals(2, folder.sheets.size)
    }

    @Test
    fun unreadableEntriesAreSkippedAndTheRestStillLoad() {
        val folder = MemoryCharacterFolder()
        val saved = SavedCharacters(folder)
        saved.save(sprite(1), sheet("good"))
        folder.sheets["saved-1"] = "{ not json"
        folder.sprites["saved-1"] = sprite(2)
        folder.sheets["saved-2"] = Bundle.encodeSheet(sheet("no sprite"))
        folder.sprites["saved-3"] = sprite(3) // no sheet
        folder.write("saved-4", ByteArray(0), Bundle.encodeSheet(sheet("empty sprite")))

        assertEquals(listOf("good"), saved.load().map { it.sheet.name })
    }

    @Test
    fun foldersThatAreNotCharactersAreLeftAlone() {
        val folder = MemoryCharacterFolder()
        folder.write(".writing", sprite(1), Bundle.encodeSheet(sheet("half")))
        folder.write("saved-abc", sprite(1), Bundle.encodeSheet(sheet("odd")))

        val saved = SavedCharacters(folder, maxKept = 1)
        saved.save(sprite(2), sheet("real"))
        saved.save(sprite(3), sheet("newer"))

        assertEquals(listOf("newer"), saved.load().map { it.sheet.name })
        assertEquals(setOf(".writing", "saved-abc"), folder.ids().toSet() - saved.load().map { it.id }.toSet())
    }
}
