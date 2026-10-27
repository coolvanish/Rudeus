package kz.rudy.ar.core.io

import java.io.File

/** [FixtureSource] over a directory tree (app files dir / extracted assets). Paths are relative to [root]. */
class FileFixtureSource(private val root: File) : FixtureSource {
    constructor(rootPath: String) : this(File(rootPath))

    private fun file(path: String): File = File(root, path.trimStart('/'))

    override fun readText(path: String): String? = file(path).takeIf { it.isFile }?.readText()
    override fun readBytes(path: String): ByteArray? = file(path).takeIf { it.isFile }?.readBytes()
    override fun exists(path: String): Boolean = file(path).exists()
    override fun list(dir: String): List<String> =
        file(dir).takeIf { it.isDirectory }?.list()?.sorted().orEmpty()
}
