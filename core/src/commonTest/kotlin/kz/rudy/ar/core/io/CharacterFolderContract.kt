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
    protected val base: Path = TestDisk.freshBase()

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

    /**
     * FINDING (T39, F2): false only for `FileCharacterFolder`. [IoCharacterFolder] rejects an empty / `.` / `..` id,
     * its own scratch names (`.writing*`, `.replaced-*`, `.deleting-*`) and bad frame names before any I/O, never lists
     * scratch names, and ignores a delete of a non-id. The File copy checks frame names mid-write (leaving `.writing`),
     * fails an empty or dot id only at the rename, accepts and lists scratch names, and `delete("")` removes the whole
     * root. The tests below assert exactly the File behaviour while this is false.
     */
    protected open val checksNamesFirst: Boolean get() = true

    @Test
    fun aFailedWriteLeavesTheOldEntryIntact() {
        folderAt(root).write(OTHER, second)
        if (replacesAnExistingId) {
            folderAt(root).write(ID, first)
            assertFails { failingFolderAt(root).write(ID, second) }
            assertEntry(folderAt(root), ID, first)
        } else {
            // F1: rewriting an id throws anyway, so a failed rewrite would prove nothing; fail a write of a new id.
            assertFails { failingFolderAt(root).write(ID, first) }
            assertAbsent(folderAt(root), ID)
        }
        assertEntry(folderAt(root), OTHER, second)
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

    @Test
    fun emptyAndDotIdsAreRejected() {
        for (bad in listOf("", ".", "..")) {
            if (checksNamesFirst) {
                assertFailsWith<IllegalArgumentException>("id '$bad'") { newFolder().write(bad, first) }
                assertFalse(TestDisk.exists(root), "nothing touched the disk for id '$bad'")
            } else {
                assertFails("id '$bad'") { newFolder().write(bad, first) } // File: "cannot publish", at the rename
                assertTrue(TestDisk.exists(Path(root, ".writing")), "File fills .writing before it fails on id '$bad'")
            }
        }
        assertEquals(emptyList(), newFolder().ids().filterNot { it.startsWith(".") })
    }

    @Test
    fun scratchNamesAreNeverIds() {
        val reserved = listOf(".writing", ".writing-2", ".replaced-x", ".deleting-x")
        if (checksNamesFirst) {
            for (name in reserved) assertFailsWith<IllegalArgumentException>("id '$name'") { newFolder().write(name, first) }
            assertFalse(TestDisk.exists(root))
            TestDisk.writeFile(Path(root, ".writing", "sheet.json"), byteArrayOf(1))
            TestDisk.writeFile(Path(root, ".deleting-x", "sheet.json"), byteArrayOf(1))
            assertEquals(emptyList(), newFolder().ids())
            assertNull(newFolder().readSheet(".deleting-x"))
        } else {
            val folder = newFolder()
            folder.write(".replaced-x", first) // File: a scratch-looking name is just a name
            assertEntry(folder, ".replaced-x", first)
            TestDisk.writeFile(Path(root, ".writing", "sprite.png"), byteArrayOf(9)) // a write killed halfway
            assertEquals(listOf(".replaced-x", ".writing"), folder.ids().sorted())
        }
    }

    @Test
    fun badFrameNamesAreRejected() {
        for (bad in listOf("x/y.png", "a\\b.png", "", ".", "..")) {
            TestDisk.deleteTree(root)
            val entry = Entry(first.sprite, first.sheet, null, mapOf("ok.png" to byteArrayOf(1), bad to byteArrayOf(2)))
            if (checksNamesFirst) {
                assertFailsWith<IllegalArgumentException>("frame '$bad'") { newFolder().write(ID, entry) }
                assertFalse(TestDisk.exists(root), "nothing touched the disk for frame '$bad'")
            } else {
                assertFails("frame '$bad'") { newFolder().write(ID, entry) } // File: mid-write, after the sprite and sheet
                assertTrue(TestDisk.exists(Path(root, ".writing", "sheet.json")), "File wrote before it failed on frame '$bad'")
            }
            assertAbsent(newFolder(), ID)
        }
    }

    @Test
    fun deleteOfANonIdLeavesTheFolderAlone() {
        val folder = newFolder()
        folder.write(ID, first)
        if (checksNamesFirst) {
            for (bad in listOf("", ".", "..", ".writing", "a/b")) folder.delete(bad)
            assertEntry(newFolder(), ID, first)
        } else {
            folder.delete("") // File: File(dir, "") is the root itself, so every character goes
            assertFalse(TestDisk.exists(root))
        }
    }

    /** One entry as read back, comparable as a whole; null when nothing of [id] is there. */
    protected data class Seen(val sprite: List<Byte>?, val sheet: String?, val choreo: String?, val frames: Map<String, List<Byte>>)

    protected fun seen(folder: CharacterFolder, id: String): Seen? =
        Seen(folder.readSprite(id)?.toList(), folder.readSheet(id), folder.readChoreo(id), contentOf(folder.readFrames(id)))
            .takeUnless { it.sprite == null && it.sheet == null && it.choreo == null && it.frames.isEmpty() }

    protected fun seenOf(e: Entry): Seen = Seen(e.sprite.toList(), e.sheet, e.choreo, contentOf(e.frames))
}
