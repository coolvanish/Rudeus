package kz.rudy.ar.core.io

import java.io.File

/** Writes `runs/<runId>.json` files into [dir] (created on demand). */
class FileMetricsSink(private val dir: File) : MetricsSink {
    constructor(dirPath: String) : this(File(dirPath))

    override fun write(fileName: String, text: String): String {
        require(!fileName.contains('/') && !fileName.contains('\\')) { "fileName must be bare: $fileName" }
        dir.mkdirs()
        val f = File(dir, fileName)
        f.writeText(text)
        return f.path
    }
}
