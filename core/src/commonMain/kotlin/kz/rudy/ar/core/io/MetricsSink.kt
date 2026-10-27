package kz.rudy.ar.core.io

/** Where `runs/<runId>.json` lands. `FileMetricsSink` (jvmMain) writes files; [MemoryMetricsSink] keeps them for tests. */
interface MetricsSink {
    /** Writes [text] under [fileName] (a bare file name, no directories). Returns the location it was written to. */
    fun write(fileName: String, text: String): String
}

/** In-memory [MetricsSink]. */
class MemoryMetricsSink : MetricsSink {
    val files: MutableMap<String, String> = linkedMapOf()
    override fun write(fileName: String, text: String): String {
        files[fileName] = text
        return "memory:$fileName"
    }
}
