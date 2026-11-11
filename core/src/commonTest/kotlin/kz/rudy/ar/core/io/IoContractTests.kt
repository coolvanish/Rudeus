package kz.rudy.ar.core.io

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class IoCharacterFolderTest : OnDiskCharacterFolderContract() {
    override fun folderAt(root: Path): CharacterFolder = IoCharacterFolder(root)

    /** The temp step: writing the sheet into `.writing` fails. */
    override fun failingFolderAt(root: Path): CharacterFolder =
        IoCharacterFolder(root, SystemFileSystem, Faults(failWhen = { it == "write sheet.json" })::step)

    /**
     * The crash rule at every step, not just one: fail the n-th file-system step of a write for n = 1, 2, ... until a
     * write runs without reaching n. Each time the entry is the old one (write threw) or the new one (write returned),
     * whole, and the next plain write still works.
     */
    @Test
    fun everyFailingCallLeavesTheOldEntryOrTheNewOne() {
        for (replacing in listOf(false, true)) {
            var n = 1
            while (true) {
                TestDisk.deleteTree(root)
                if (replacing) folderAt(root).write(ID, first)
                val faults = Faults(failAt = n)
                val threw = runCatching { IoCharacterFolder(root, SystemFileSystem, faults::step).write(ID, second) }.isFailure
                val plain = folderAt(root)
                when {
                    !threw -> assertEntry(plain, ID, second)
                    replacing -> assertEntry(plain, ID, first)
                    else -> assertAbsent(plain, ID)
                }
                plain.write(OTHER, first)
                assertEntry(plain, OTHER, first)
                if (faults.failed == null) break // the write needed fewer than n steps: every step has been failed once
                if (n++ > 500) fail("a write should not take 500 file-system steps")
            }
            assertTrue(n > 10, "covered only $n steps (replacing=$replacing)")
        }
    }

    @Test
    fun aFailedPublishOfAReplacementPutsTheOldEntryBack() {
        folderAt(root).write(ID, first)
        val faults = Faults(failWhen = { it == "atomicMove .writing" })
        assertFails { IoCharacterFolder(root, SystemFileSystem, faults::step).write(ID, second) }
        assertEquals("atomicMove .writing", faults.failed)
        assertEntry(folderAt(root), ID, first)
    }

    @Test
    fun aReplacementLeavesNoScratchBehind() {
        val folder = folderAt(root)
        folder.write(ID, first)
        folder.write(ID, second)
        assertEquals(listOf(ID), folder.ids())
        assertEquals(listOf(ID), TestDisk.names(root))
    }

    /**
     * Runs [action] on a folder over [root] that "dies" before its [n]-th file-system step: at that step the whole root
     * is copied to [snapshot] (what a killed process leaves on disk), then the action finishes normally. Returns false
     * when the action took fewer than [n] steps.
     */
    private fun dieAt(n: Int, snapshot: Path, action: (CharacterFolder) -> Unit): Boolean {
        var steps = 0
        var died = false
        val folder = IoCharacterFolder(root, SystemFileSystem) {
            if (++steps == n) {
                TestDisk.copyTree(root, snapshot)
                died = true
            }
        }
        action(folder)
        return died
    }

    /**
     * Process death, not an exception: for every step n of a write (fresh, then replacing an id), a FRESH folder on
     * what the disk held at step n recovers to the old entry or the new one, whole (a fresh write: absent or new), a
     * bystander id is untouched, no scratch name is left, and the folder takes the next write.
     */
    @Test
    fun aProcessDeathAtAnyStepOfAWriteLeavesTheOldEntryOrTheNewOne() {
        for (replacing in listOf(false, true)) {
            var n = 1
            while (true) {
                TestDisk.deleteTree(base)
                folderAt(root).write(OTHER, first)
                if (replacing) folderAt(root).write(ID, first)
                val snapshot = Path(base, "snapshot")
                if (!dieAt(n, snapshot) { it.write(ID, second) }) break
                val after = folderAt(snapshot)
                val got = seen(after, ID)
                val allowed = if (replacing) setOf(seenOf(first), seenOf(second)) else setOf(null, seenOf(second))
                assertTrue(got in allowed, "died before step $n (replacing=$replacing): $got")
                assertEntry(after, OTHER, first)
                assertEquals(listOfNotNull(got?.let { ID }, OTHER).sorted(), after.ids().sorted())
                assertEquals(after.ids().sorted(), TestDisk.names(snapshot), "no scratch left after recovery")
                after.write(ID, first)
                assertEntry(folderAt(snapshot), ID, first)
                if (n++ > 500) fail("a write should not take 500 file-system steps")
            }
            assertTrue(n > 10, "covered only $n steps (replacing=$replacing)")
        }
    }

    @Test
    fun aProcessDeathAtAnyStepOfADeleteLeavesTheWholeEntryOrNone() {
        var n = 1
        while (true) {
            TestDisk.deleteTree(base)
            folderAt(root).write(ID, first)
            folderAt(root).write(OTHER, second)
            val snapshot = Path(base, "snapshot")
            if (!dieAt(n, snapshot) { it.delete(ID) }) break
            val after = folderAt(snapshot)
            val got = seen(after, ID)
            assertTrue(got == null || got == seenOf(first), "died before step $n of a delete: $got")
            assertEntry(after, OTHER, second)
            assertEquals(after.ids().sorted(), TestDisk.names(snapshot), "no scratch left after recovery")
            if (n++ > 500) fail("a delete should not take 500 file-system steps")
        }
        assertTrue(n > 5, "covered only $n steps")
        assertAbsent(folderAt(root), ID)
    }

    @Test
    fun recoveryPutsAStrandedEntryBackAndClearsLeftovers() {
        folderAt(root).write(ID, first)
        folderAt(root).write(OTHER, second)
        // died between the two renames of a replacing write: the old entry is aside, the new one complete in .writing
        SystemFileSystem.atomicMove(Path(root, ID), Path(root, ".replaced-$ID"))
        TestDisk.writeFile(Path(root, ".writing", "sheet.json"), second.sheet.encodeToByteArray())
        // died after publishing OTHER again, before deleting its aside
        TestDisk.writeFile(Path(root, ".replaced-$OTHER", "sheet.json"), byteArrayOf(1))
        // died in the middle of a delete
        TestDisk.writeFile(Path(root, ".deleting-saved-9", "frames", "x.png"), byteArrayOf(1))

        val folder = folderAt(root)
        assertEquals(listOf(ID, OTHER), folder.ids().sorted())
        assertEntry(folder, ID, first)
        assertEntry(folder, OTHER, second)
        assertEquals(listOf(ID, OTHER), TestDisk.names(root))
    }
}

class IoFixtureSourceTest : OnDiskFixtureSourceContract() {
    override fun sourceAt(root: Path): FixtureSource = IoFixtureSource(root)

    @Test
    fun theStringConstructorIsTheSameRoot() {
        sourceWith(files)
        assertNotNull(IoFixtureSource(root.toString()).readText("probe/a.json"))
    }
}

class IoMetricsSinkTest : OnDiskMetricsSinkContract() {
    override fun sinkAt(dir: Path): MetricsSink = IoMetricsSink(dir)

    @Test
    fun theStringConstructorIsTheSameDirectory() {
        assertEquals(Path(dir, "r-1.json").toString(), IoMetricsSink(dir.toString()).write("r-1.json", run))
        assertEquals(run, readBack("r-1.json"))
    }
}
