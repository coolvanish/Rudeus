package kz.rudy.ar.core.io

import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.random.Random

/** Real-disk helpers for the contract tests (kotlinx-io, so they run on jvm and ios alike). */
internal object TestDisk {
    /** A fresh, not yet created folder under the system temp directory. */
    fun freshBase(): Path = Path(SystemTemporaryDirectory, "rudeus-t39-${Random.nextLong().toULong().toString(16)}")

    fun writeFile(p: Path, bytes: ByteArray) {
        p.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(p).buffered().use { it.write(bytes) }
    }

    fun readFile(p: Path): ByteArray? =
        if (SystemFileSystem.metadataOrNull(p)?.isRegularFile == true) SystemFileSystem.source(p).buffered().use { it.readByteArray() } else null

    fun exists(p: Path): Boolean = SystemFileSystem.exists(p)

    /** Names directly inside [dir], sorted; empty when it is missing. */
    fun names(dir: Path): List<String> =
        if (SystemFileSystem.metadataOrNull(dir)?.isDirectory == true) SystemFileSystem.list(dir).map { it.name }.sorted() else emptyList()

    /** Copies the tree at [from] (if any) to [to], replacing what was there. */
    fun copyTree(from: Path, to: Path) {
        deleteTree(to)
        val meta = SystemFileSystem.metadataOrNull(from) ?: return
        if (meta.isDirectory) {
            SystemFileSystem.createDirectories(to)
            SystemFileSystem.list(from).forEach { copyTree(it, Path(to, it.name)) }
        } else {
            writeFile(to, readFile(from) ?: ByteArray(0))
        }
    }

    fun deleteTree(p: Path) {
        val meta = SystemFileSystem.metadataOrNull(p) ?: return
        if (meta.isDirectory) SystemFileSystem.list(p).forEach { deleteTree(it) }
        SystemFileSystem.delete(p, mustExist = false)
    }
}

/**
 * An I/O failure injected once into [IoCharacterFolder]'s step hook: at the [failAt]-th step (1-based, 0 = never) or
 * at the first step whose description (`"<op> <file name>"`, e.g. `"sink sheet.json"`) matches [failWhen].
 * kotlinx-io's `FileSystem` is sealed, so a wrapping file system cannot be written; the hook sees the same calls.
 */
internal class Faults(private val failAt: Int = 0, private val failWhen: (String) -> Boolean = { false }) {
    var steps: Int = 0
        private set
    var failed: String? = null
        private set

    fun step(what: String) {
        steps++
        if (failed == null && (steps == failAt || failWhen(what))) {
            failed = what
            throw IOException("injected failure at step $steps: $what")
        }
    }
}
