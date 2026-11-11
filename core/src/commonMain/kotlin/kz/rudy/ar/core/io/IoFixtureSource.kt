package kz.rudy.ar.core.io

import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString

/**
 * [FixtureSource] over a directory tree in common code (kotlinx-io), the same semantics as `FileFixtureSource`
 * (androidMain / jvmMain): paths are '/'-separated and relative to [root], a leading '/' is dropped (so a source at
 * the file-system root reads absolute paths), empty segments are ignored, and only regular files are read.
 */
class IoFixtureSource(
    private val root: Path,
    private val fs: FileSystem = SystemFileSystem,
) : FixtureSource {
    constructor(rootPath: String) : this(Path(rootPath))

    private fun path(p: String): Path {
        val segments = p.split('/').filter { it.isNotEmpty() }
        return if (segments.isEmpty()) root else Path(root, *segments.toTypedArray())
    }

    private fun regularFile(path: String): Path? = path(path).takeIf { fs.metadataOrNull(it)?.isRegularFile == true }

    override fun readText(path: String): String? = regularFile(path)?.let { p -> fs.source(p).buffered().use { it.readString() } }

    override fun readBytes(path: String): ByteArray? = regularFile(path)?.let { p -> fs.source(p).buffered().use { it.readByteArray() } }

    override fun exists(path: String): Boolean = fs.exists(path(path))

    override fun list(dir: String): List<String> {
        val p = path(dir)
        if (fs.metadataOrNull(p)?.isDirectory != true) return emptyList()
        return fs.list(p).map { it.name }.sorted()
    }
}
