package kz.rudy.ar.core.io

import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

/**
 * Writes `runs/<runId>.json` files into [dir] (created on demand) in common code (kotlinx-io), the same semantics as
 * `FileMetricsSink` (androidMain / jvmMain): a bare file name, an existing file is overwritten in place (not atomic,
 * like the File copy), and the returned location is `<dir>/<fileName>`.
 */
class IoMetricsSink(
    private val dir: Path,
    private val fs: FileSystem = SystemFileSystem,
) : MetricsSink {
    constructor(dirPath: String) : this(Path(dirPath))

    override fun write(fileName: String, text: String): String {
        require(!fileName.contains('/') && !fileName.contains('\\')) { "fileName must be bare: $fileName" }
        fs.createDirectories(dir)
        val f = Path(dir, fileName)
        fs.sink(f).buffered().use { it.writeString(text) }
        return f.toString()
    }
}
