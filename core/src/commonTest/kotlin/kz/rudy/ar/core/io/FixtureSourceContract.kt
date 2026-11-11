package kz.rudy.ar.core.io

import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The [FixtureSource] contract, same assertions for [MapFixtureSource], [IoFixtureSource] and (jvmTest) `FileFixtureSource`. */
abstract class FixtureSourceContract {
    /** A source holding exactly [files] ('/'-separated relative paths); on disk an empty map means a missing root. */
    protected abstract fun sourceWith(files: Map<String, ByteArray>): FixtureSource

    protected val files: Map<String, ByteArray> = mapOf(
        "probe/a.json" to """{"text":"Сәлем! Привет!"}""".encodeToByteArray(),
        "probe/b.png" to byteArrayOf(0, -1, 7, -119, 80),
        "top.txt" to "top".encodeToByteArray(),
    )

    @Test
    fun readTextAndBytesRoundTrip() {
        val source = sourceWith(files)
        assertEquals("""{"text":"Сәлем! Привет!"}""", source.readText("probe/a.json"))
        assertContentEquals(files.getValue("probe/b.png"), source.readBytes("probe/b.png"))
        assertContentEquals(files.getValue("probe/a.json"), source.readBytes("probe/a.json"))
        assertEquals("top", source.readText("top.txt"))
    }

    @Test
    fun listGivesTheSortedFileNamesOfADirectory() {
        assertEquals(listOf("a.json", "b.png"), sourceWith(files).list("probe"))
    }

    @Test
    fun existsIsTrueForAFileOnly() {
        val source = sourceWith(files)
        assertTrue(source.exists("probe/a.json"))
        assertTrue(source.exists("top.txt"))
        assertFalse(source.exists("probe/nope.json"))
    }

    @Test
    fun aLeadingSlashReadsFromTheRoot() {
        val source = sourceWith(files)
        assertEquals(source.readText("probe/a.json"), source.readText("/probe/a.json"))
        assertEquals(listOf("a.json", "b.png"), source.list("/probe"))
        assertTrue(source.exists("/top.txt"))
    }

    @Test
    fun readsOfAMissingPathAreNull() {
        val source = sourceWith(files)
        assertNull(source.readText("probe/nope.json"))
        assertNull(source.readBytes("nope/b.png"))
        assertEquals(emptyList(), source.list("nope"))
    }

    @Test
    fun aMissingRootReadsAsNothing() {
        val source = sourceWith(emptyMap())
        assertNull(source.readText("probe/a.json"))
        assertNull(source.readBytes("probe/a.json"))
        assertEquals(emptyList(), source.list("probe"))
        assertEquals(emptyList(), source.list(""))
        assertFalse(source.exists("probe/a.json"))
    }
}

/** The on-disk half: directories exist (but are never read as files) and paths are tolerated the way `java.io.File` does. */
abstract class OnDiskFixtureSourceContract : FixtureSourceContract() {
    private val base: Path = TestDisk.freshBase()
    protected val root: Path = Path(base, "fixtures")

    protected abstract fun sourceAt(root: Path): FixtureSource

    override fun sourceWith(files: Map<String, ByteArray>): FixtureSource {
        for ((path, bytes) in files) TestDisk.writeFile(Path(root, *path.split('/').toTypedArray()), bytes)
        return sourceAt(root)
    }

    @AfterTest
    fun cleanUp() = TestDisk.deleteTree(base)

    @Test
    fun aDirectoryExistsAndIsListedButIsNeverRead() {
        val source = sourceWith(files)
        assertTrue(source.exists("probe"))
        assertNull(source.readText("probe"))
        assertNull(source.readBytes("probe"))
        assertEquals(listOf("probe", "top.txt"), source.list(""))
        assertEquals(listOf("probe", "top.txt"), source.list("/"))
    }

    @Test
    fun listOfAFileIsEmpty() {
        assertEquals(emptyList(), sourceWith(files).list("top.txt"))
    }

    @Test
    fun emptySegmentsAndATrailingSlashAreIgnored() {
        val source = sourceWith(files)
        assertEquals("top", source.readText("//top.txt"))
        assertContentEquals(files.getValue("probe/b.png"), source.readBytes("probe//b.png"))
        assertEquals(listOf("a.json", "b.png"), source.list("probe/"))
    }

    @Test
    fun aSourceAtTheFileSystemRootReadsAbsolutePaths() {
        sourceWith(files)
        val atSlash = sourceAt(Path("/")) // how the CLI uses it: FixtureApi joins an absolute fixtures root
        assertEquals("top", atSlash.readText("$root/top.txt"))
        assertEquals(listOf("a.json", "b.png"), atSlash.list("$root/probe"))
    }
}
