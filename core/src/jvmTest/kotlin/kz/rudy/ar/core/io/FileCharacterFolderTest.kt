package kz.rudy.ar.core.io

import kz.rudy.ar.core.bundle.SavedCharacters
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterSheet
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileCharacterFolderTest {
    private val sheet = CharacterSheet("Мұрат", "батыл", Ability.JUMP, mapOf("kk" to listOf("Сәлем!")))

    private fun tempDir(): File = Files.createTempDirectory("rudeus-saved").toFile().also { it.deleteOnExit() }

    @Test
    fun characterSurvivesInRealFilesInTheBundleLayout() {
        val dir = File(tempDir(), "characters") // not created yet: the first save makes it
        val id = SavedCharacters(FileCharacterFolder(dir)).save(byteArrayOf(1, 2, 3), sheet).id

        assertContentEquals(byteArrayOf(1, 2, 3), File(dir, "$id/sprite.png").readBytes())
        assertTrue(File(dir, "$id/sheet.json").isFile)
        val loaded = SavedCharacters(FileCharacterFolder(dir)).load().single()
        assertEquals(sheet, loaded.sheet)
        assertContentEquals(byteArrayOf(1, 2, 3), loaded.sprite)
    }

    @Test
    fun aWriteKilledHalfwayLeavesNoCharacterAndTheNextWriteClearsIt() {
        val dir = tempDir()
        File(dir, ".writing").mkdirs()
        File(dir, ".writing/sprite.png").writeBytes(byteArrayOf(9)) // the sheet never got written
        val saved = SavedCharacters(FileCharacterFolder(dir))

        assertEquals(emptyList(), saved.load().map { it.id })

        saved.save(byteArrayOf(1), sheet)
        assertEquals(1, saved.load().size)
        assertFalse(File(dir, ".writing").exists())
    }

    @Test
    fun evictionRemovesTheWholeFolder() {
        val dir = tempDir()
        val saved = SavedCharacters(FileCharacterFolder(dir), maxKept = 1)
        val old = saved.save(byteArrayOf(1), sheet).id
        val new = saved.save(byteArrayOf(2), sheet).id

        assertFalse(File(dir, old).exists())
        assertEquals(listOf(new), saved.load().map { it.id })
    }

    @Test
    fun aMissingFolderHasNoCharacters() {
        assertEquals(emptyList(), SavedCharacters(FileCharacterFolder(File(tempDir(), "nope"))).load().map { it.id })
    }
}
