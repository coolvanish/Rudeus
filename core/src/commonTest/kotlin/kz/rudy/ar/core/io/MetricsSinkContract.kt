package kz.rudy.ar.core.io

import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The [MetricsSink] contract, same assertions for [MemoryMetricsSink], [IoMetricsSink] and (jvmTest) `FileMetricsSink`. */
abstract class MetricsSinkContract {
    /** A new, empty sink (on disk: its directory does not exist yet). */
    protected abstract fun newSink(): MetricsSink

    /** What the last [newSink] holds under [fileName], or null. */
    protected abstract fun readBack(fileName: String): String?

    protected val run = """{"runId":"r-1","steps":[{"name":"spark-sheet","ms":812}],"note":"Сәлем"}"""

    @Test
    fun writeThenReadRoundTrips() {
        newSink().write("r-1.json", run)
        assertEquals(run, readBack("r-1.json"))
    }

    @Test
    fun writeReplacesAnExistingName() {
        val sink = newSink()
        sink.write("r-1.json", run)
        sink.write("r-1.json", "{}")
        assertEquals("{}", readBack("r-1.json"))
    }

    @Test
    fun eachNameIsItsOwnFile() {
        val sink = newSink()
        sink.write("r-1.json", run)
        sink.write("r-2.json", "{}")
        assertEquals(run, readBack("r-1.json"))
        assertEquals("{}", readBack("r-2.json"))
    }

    @Test
    fun aNameNeverWrittenReadsAsNothing() {
        newSink().write("r-1.json", run)
        assertEquals(null, readBack("r-2.json"))
    }
}

/** The on-disk half: the directory is made on demand, the location is the real path, a bad target throws. */
abstract class OnDiskMetricsSinkContract : MetricsSinkContract() {
    private val base: Path = TestDisk.freshBase()
    protected val dir: Path = Path(base, "out", "runs")

    protected abstract fun sinkAt(dir: Path): MetricsSink

    override fun newSink(): MetricsSink = sinkAt(dir)

    override fun readBack(fileName: String): String? = TestDisk.readFile(Path(dir, fileName))?.decodeToString()

    @AfterTest
    fun cleanUp() = TestDisk.deleteTree(base)

    @Test
    fun writeCreatesAMissingDirectoryAndReturnsThePath() {
        val location = newSink().write("r-1.json", run)
        assertEquals(Path(dir, "r-1.json").toString(), location)
        assertEquals(run, TestDisk.readFile(Path(location))?.decodeToString())
    }

    @Test
    fun aNameWithASeparatorIsRejected() {
        val sink = newSink()
        assertFailsWith<IllegalArgumentException> { sink.write("sub/r-1.json", run) }
        assertFailsWith<IllegalArgumentException> { sink.write("sub\\r-1.json", run) }
    }

    @Test
    fun aDirectoryThatIsAFileMakesWriteThrow() {
        TestDisk.writeFile(dir, byteArrayOf(1))
        assertFails { newSink().write("r-1.json", run) }
        assertTrue(TestDisk.exists(dir))
    }
}
