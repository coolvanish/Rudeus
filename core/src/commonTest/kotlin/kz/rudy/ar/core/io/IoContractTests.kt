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
        assertTrue(!TestDisk.exists(Path(root, ".replaced")) && !TestDisk.exists(Path(root, ".writing")))
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
