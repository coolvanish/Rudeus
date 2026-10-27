package kz.rudy.ar.core.meta

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kz.rudy.ar.core.io.FixtureSource

/**
 * Test-only [FixtureSource] over the real repository files, so tests can load the committed probe fixtures.
 * Uses kotlinx-io (already on the classpath through Ktor's `ktor-io`; no new dependency). The JVM test task runs
 * in the module dir (`core/`), so the repository root is found by walking up to the folder that has docs/api-samples.
 */
class DiskFixtureSource(private val base: String) : FixtureSource {
    private fun path(p: String) = Path(if (base.isEmpty()) p else "$base/$p")

    override fun readBytes(path: String): ByteArray? {
        val p = path(path)
        if (!SystemFileSystem.exists(p) || SystemFileSystem.metadataOrNull(p)?.isRegularFile != true) return null
        return SystemFileSystem.source(p).buffered().use { it.readByteArray() }
    }

    override fun readText(path: String): String? = readBytes(path)?.decodeToString()

    override fun list(dir: String): List<String> {
        val p = path(dir)
        if (!SystemFileSystem.exists(p)) return emptyList()
        return SystemFileSystem.list(p).map { it.name }.sorted()
    }

    override fun exists(path: String): Boolean = SystemFileSystem.exists(path(path))

    companion object {
        /** A source rooted at the repository root (the first of `.`, `..`, `../..` that holds docs/api-samples). */
        fun repoRoot(): DiskFixtureSource {
            val root = listOf(".", "..", "../..").firstOrNull { SystemFileSystem.exists(Path("$it/docs/api-samples")) }
                ?: error("docs/api-samples not found from the test working directory")
            return DiskFixtureSource(root)
        }
    }
}
