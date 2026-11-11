package kz.rudy.ar.core.io

import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The [CharacterFolder] contract, run with the same assertions against [MemoryCharacterFolder], [IoCharacterFolder]
 * and (jvmTest) `FileCharacterFolder`. A difference between implementations is a finding: it is pinned by a named
 * flag below and reported, never loosened away.
 */
abstract class CharacterFolderContract {
    /** A new, empty folder (on disk: its root does not exist yet). */
    protected abstract fun newFolder(): CharacterFolder

    /**
     * FINDING (T39): false only for `FileCharacterFolder`, whose `File.renameTo` cannot land on an existing, non-empty
     * entry, so writing an id that is already there throws ("cannot publish") and keeps the old entry. The tests that
     * rewrite an id then assert exactly that, so fixing the File copy turns them red and the flag goes.
     */
    protected open val replacesAnExistingId: Boolean get() = true

    protected class Entry(val sprite: ByteArray, val sheet: String, val choreo: String?, val frames: Map<String, ByteArray>)

    protected val first = Entry(
        byteArrayOf(1, 2, 3),
        """{"name":"Мұрат","trait":"батыл"}""",
        """{"emotes":["wave"]}""",
        mapOf("wave-0.png" to byteArrayOf(4), "wave-1.png" to byteArrayOf(5, 6), "jump-0.png" to byteArrayOf(0, -1)),
    )
    protected val second = Entry(byteArrayOf(9, 8), """{"name":"Әйгерім"}""", """{"emotes":["spin"]}""", mapOf("spin-0.png" to byteArrayOf(7)))

    protected fun CharacterFolder.write(id: String, e: Entry) = write(id, e.sprite, e.sheet, e.choreo, e.frames)

    protected fun assertEntry(folder: CharacterFolder, id: String, e: Entry) {
        assertContentEquals(e.sprite, folder.readSprite(id), "sprite of $id")
        assertEquals(e.sheet, folder.readSheet(id), "sheet of $id")
        assertEquals(e.choreo, folder.readChoreo(id), "choreo of $id")
        assertEquals(contentOf(e.frames), contentOf(folder.readFrames(id)), "frames of $id")
    }

    protected fun assertAbsent(folder: CharacterFolder, id: String) {
        assertNull(folder.readSprite(id))
        assertNull(folder.readSheet(id))
        assertNull(folder.readChoreo(id))
        assertEquals(emptyMap(), folder.readFrames(id))
        assertFalse(id in folder.ids(), "$id in ${folder.ids()}")
    }

    /** Rewrites [ID] (holding [first]) with [next]; where the implementation cannot replace, pins that and stops. */
    private fun rewrite(folder: CharacterFolder, next: Entry): Boolean {
        folder.write(ID, first)
        if (!replacesAnExistingId) {
            assertFails { folder.write(ID, next) }
            assertEntry(folder, ID, first)
            return false
        }
        folder.write(ID, next)
        return true
    }

    @Test
    fun writeThenReadRoundTrips() {
        val folder = newFolder()
        folder.write(ID, first)
        assertEntry(folder, ID, first)
        assertEquals(listOf(ID), folder.ids())
    }

    @Test
    fun everyWrittenIdIsListed() {
        val folder = newFolder()
        folder.write(ID, first)
        folder.write(OTHER, second)
        assertEquals(listOf(ID, OTHER), folder.ids().sorted())
        assertEntry(folder, ID, first)
        assertEntry(folder, OTHER, second)
    }

    @Test
    fun writeReplacesAnExistingId() {
        val folder = newFolder()
        if (!rewrite(folder, second)) return
        assertEntry(folder, ID, second)
        assertEquals(listOf(ID), folder.ids())
    }

    @Test
    fun writeWithoutChoreoRemovesIt() {
        val folder = newFolder()
        if (!rewrite(folder, Entry(second.sprite, second.sheet, null, second.frames))) return
        assertNull(folder.readChoreo(ID))
        assertEquals(second.sheet, folder.readSheet(ID))
    }

    @Test
    fun writeWithoutFramesRemovesTheFolder() {
        val folder = newFolder()
        if (!rewrite(folder, Entry(second.sprite, second.sheet, second.choreo, emptyMap()))) return
        assertEquals(emptyMap(), folder.readFrames(ID))
        assertEquals(second.choreo, folder.readChoreo(ID))
    }

    @Test
    fun writeWithoutChoreoOrFramesStoresNeither() {
        val folder = newFolder()
        folder.write(ID, Entry(first.sprite, first.sheet, null, emptyMap()))
        assertContentEquals(first.sprite, folder.readSprite(ID))
        assertNull(folder.readChoreo(ID))
        assertEquals(emptyMap(), folder.readFrames(ID))
    }

    @Test
    fun idsOfAMissingRootIsEmpty() {
        assertEquals(emptyList(), newFolder().ids())
    }

    @Test
    fun readsOfAMissingIdAreNull() {
        assertAbsent(newFolder(), "nobody")
        val folder = newFolder()
        folder.write(ID, first)
        assertAbsent(folder, "nobody")
    }

    @Test
    fun deleteOfAMissingIdIsANoOp() {
        newFolder().delete("nobody")
        val folder = newFolder()
        folder.write(ID, first)
        folder.delete("nobody")
        assertEntry(folder, ID, first)
    }

    @Test
    fun deleteRemovesTheWholeEntry() {
        val folder = newFolder()
        folder.write(ID, first)
        folder.write(OTHER, second)
        folder.delete(ID)
        assertAbsent(folder, ID)
        assertEntry(folder, OTHER, second)
    }

    protected companion object {
        const val ID = "saved-1"
        const val OTHER = "saved-2"

        fun contentOf(frames: Map<String, ByteArray>): Map<String, List<Byte>> = frames.mapValues { it.value.toList() }
    }
}

/**
 * The on-disk half of the contract, for `FileCharacterFolder` and [IoCharacterFolder] on a fresh temp root: the
 * bundle layout, the crash rule of [CharacterFolder.write], and the edge cases only a real folder can have.
 */
abstract class OnDiskCharacterFolderContract : CharacterFolderContract() {
    private val base: Path = TestDisk.freshBase()

    /** Not created: the first write makes it. */
    protected val root: Path = Path(base, "characters")

    protected abstract fun folderAt(root: Path): CharacterFolder

    /** A folder over the same [root] whose next write fails with an I/O error in its temp step (before publishing). */
    protected abstract fun failingFolderAt(root: Path): CharacterFolder

    override fun newFolder(): CharacterFolder = folderAt(root)

    @AfterTest
    fun cleanUp() = TestDisk.deleteTree(base)

    @Test
    fun writeLaysOutTheBundleFiles() {
        newFolder().write(ID, first)
        assertContentEquals(first.sprite, TestDisk.readFile(Path(root, ID, "sprite.png")))
        assertEquals(first.sheet, TestDisk.readFile(Path(root, ID, "sheet.json"))?.decodeToString())
        assertEquals(first.choreo, TestDisk.readFile(Path(root, ID, "choreo.json"))?.decodeToString())
        assertContentEquals(first.frames.getValue("wave-1.png"), TestDisk.readFile(Path(root, ID, "frames", "wave-1.png")))
        assertFalse(TestDisk.exists(Path(root, ".writing")), "the scratch folder is published, not left behind")
    }

    @Test
    fun aFailedWriteLeavesTheOldEntryIntact() {
        folderAt(root).write(ID, first)
        assertFails { failingFolderAt(root).write(ID, second) }
        assertEntry(folderAt(root), ID, first)
    }

    @Test
    fun aFailedFirstWriteLeavesNoEntry() {
        assertFails { failingFolderAt(root).write(ID, first) }
        val folder = folderAt(root)
        assertNull(folder.readSprite(ID))
        assertNull(folder.readSheet(ID))
        assertFalse(ID in folder.ids())
    }

    @Test
    fun aLeftoverScratchIsClearedByTheNextWrite() {
        TestDisk.writeFile(Path(root, ".writing", "sprite.png"), byteArrayOf(9)) // a write killed before its sheet
        assertEquals(listOf(".writing"), folderAt(root).ids())
        assertNull(folderAt(root).readSheet(".writing"), "a half write has no sheet")

        newFolder().write(ID, first)
        assertFalse(TestDisk.exists(Path(root, ".writing")))
        assertEntry(newFolder(), ID, first)
        assertEquals(emptyMap(), newFolder().readFrames(".writing"))
    }

    @Test
    fun namesThatAreNotIdsShowUpInIdsButFilesDoNot() {
        TestDisk.writeFile(Path(root, "notes", "todo.txt"), byteArrayOf(1))
        TestDisk.writeFile(Path(root, "readme.txt"), byteArrayOf(1))
        val folder = newFolder()
        folder.write(ID, first)
        assertEquals(listOf("notes", ID), folder.ids().sorted())
        assertAbsent(folder, "readme.txt")
    }

    @Test
    fun readsSkipWhatIsNotARegularFile() {
        TestDisk.writeFile(Path(root, ID, "sheet.json", "inner"), byteArrayOf(1)) // a folder where the sheet should be
        TestDisk.writeFile(Path(root, ID, "frames", "nested", "x.png"), byteArrayOf(1))
        TestDisk.writeFile(Path(root, ID, "frames", "wave-0.png"), byteArrayOf(4))
        val folder = newFolder()
        assertNull(folder.readSheet(ID))
        assertNull(folder.readSprite(ID))
        assertEquals(mapOf("wave-0.png" to listOf<Byte>(4)), contentOf(folder.readFrames(ID)))
    }

    @Test
    fun barePathsOnly() {
        val folder = newFolder()
        assertFailsWith<IllegalArgumentException> { folder.write("a/b", first) }
        assertFailsWith<IllegalArgumentException> { folder.write(ID, Entry(first.sprite, first.sheet, null, mapOf("x/y.png" to byteArrayOf(1)))) }
        assertNull(folder.readSheet(ID))
    }
}
