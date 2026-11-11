package kz.rudy.ar.core.io

import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * [CharacterFolder] in common code over kotlinx-io: `<root>/<id>/sprite.png` + `sheet.json` (+ `choreo.json`,
 * + `frames/<emote>-<n>.png`), the same layout as `FileCharacterFolder` (androidMain / jvmMain), so android, jvm and
 * ios can share one tested implementation. The host passes [root] (Android `filesDir/characters`; iOS later, T51).
 *
 * A write builds the entry in the sibling scratch folder `.writing` (cleared first, so a write killed halfway is
 * cleared by the next one) and publishes it with one [FileSystem.atomicMove]. Replacing an existing id moves the old
 * entry aside to `.replaced` first (a rename cannot land on a non-empty folder) and moves it back if publishing
 * fails; a process killed between those two renames leaves [id] absent, never half written. Both scratch names show
 * up in [ids] while they exist, which the interface allows (names that are not ids).
 */
class IoCharacterFolder internal constructor(
    private val root: Path,
    private val fs: FileSystem,
    /**
     * Called before every file-system step with `"<op> <file name>"` (e.g. `"sink sheet.json"`, `"write sheet.json"`);
     * a no-op outside tests. kotlinx-io's [FileSystem] is sealed, so the tests inject I/O failures here instead of
     * through a wrapping file system.
     */
    private val beforeStep: (String) -> Unit,
) : CharacterFolder {
    constructor(root: Path, fs: FileSystem = SystemFileSystem) : this(root, fs, {})

    override fun ids(): List<String> = try {
        if (!isDirectory(root)) emptyList() else list(root).filter { isDirectory(it) }.map { it.name }
    } catch (e: IOException) {
        emptyList() // as File.listFiles(): an unreadable root lists nothing
    }

    override fun write(id: String, sprite: ByteArray, sheet: String, choreo: String?, frames: Map<String, ByteArray>) {
        require(isBare(id) && id != SCRATCH && id != REPLACED) { "id must be bare: $id" }
        for (name in frames.keys) require(isBare(name)) { "frame name must be bare: $name" }
        val scratch = Path(root, SCRATCH)
        deleteTree(scratch)
        createDirectories(scratch)
        writeBytes(Path(scratch, SPRITE), sprite)
        writeText(Path(scratch, SHEET), sheet)
        if (choreo != null) writeText(Path(scratch, CHOREO), choreo)
        if (frames.isNotEmpty()) {
            val dir = Path(scratch, FRAMES)
            createDirectories(dir)
            for ((name, bytes) in frames) writeBytes(Path(dir, name), bytes)
        }
        publish(scratch, Path(root, id))
    }

    override fun readSprite(id: String): ByteArray? = Path(root, id, SPRITE).takeIf { isFile(it) }?.let { readBytes(it) }

    override fun readSheet(id: String): String? = Path(root, id, SHEET).takeIf { isFile(it) }?.let { readText(it) }

    override fun readChoreo(id: String): String? = Path(root, id, CHOREO).takeIf { isFile(it) }?.let { readText(it) }

    override fun readFrames(id: String): Map<String, ByteArray> {
        val dir = Path(root, id, FRAMES)
        if (!isDirectory(dir)) return emptyMap()
        return list(dir).filter { isFile(it) }.sortedBy { it.name }.associate { it.name to readBytes(it) }
    }

    override fun delete(id: String) {
        try {
            deleteTree(Path(root, id))
        } catch (e: IOException) {
            // as File.deleteRecursively(): a delete that cannot finish is not an error for the caller
        }
    }

    /** [scratch] becomes [target] in one rename; an existing [target] is swapped out first and restored on failure. */
    private fun publish(scratch: Path, target: Path) {
        if (metadata(target) == null) {
            move(scratch, target)
            return
        }
        val old = Path(root, REPLACED)
        deleteTree(old)
        move(target, old)
        try {
            move(scratch, target)
        } catch (e: Throwable) {
            try { move(old, target) } catch (restore: Throwable) { e.addSuppressed(restore) }
            throw e
        }
        try {
            deleteTree(old)
        } catch (e: IOException) {
            // the new entry is published; a leftover .replaced is cleared by the next replacing write
        }
    }

    // Every file-system call goes through one of these, so [beforeStep] sees each step once.

    private fun metadata(p: Path): FileMetadata? { beforeStep("metadata ${p.name}"); return fs.metadataOrNull(p) }

    private fun list(p: Path): Collection<Path> { beforeStep("list ${p.name}"); return fs.list(p) }

    private fun move(from: Path, to: Path) { beforeStep("atomicMove ${from.name}"); fs.atomicMove(from, to) }

    private fun isDirectory(p: Path): Boolean = metadata(p)?.isDirectory == true

    private fun isFile(p: Path): Boolean = metadata(p)?.isRegularFile == true

    /** kotlinx-io's JVM `createDirectories` returns quietly when it cannot create the folder; fail here instead. */
    private fun createDirectories(p: Path) {
        beforeStep("createDirectories ${p.name}")
        fs.createDirectories(p)
        if (!isDirectory(p)) throw IOException("cannot create $p")
    }

    private fun deleteTree(p: Path) {
        val meta = metadata(p) ?: return
        if (meta.isDirectory) for (child in list(p)) deleteTree(child)
        beforeStep("delete ${p.name}")
        fs.delete(p, mustExist = false)
    }

    private fun writeBytes(p: Path, bytes: ByteArray) {
        beforeStep("sink ${p.name}")
        fs.sink(p).buffered().use { beforeStep("write ${p.name}"); it.write(bytes) }
    }

    private fun writeText(p: Path, text: String) {
        beforeStep("sink ${p.name}")
        fs.sink(p).buffered().use { beforeStep("write ${p.name}"); it.writeString(text) }
    }

    private fun readBytes(p: Path): ByteArray { beforeStep("source ${p.name}"); return fs.source(p).buffered().use { it.readByteArray() } }

    private fun readText(p: Path): String { beforeStep("source ${p.name}"); return fs.source(p).buffered().use { it.readString() } }

    private companion object {
        const val SCRATCH = ".writing"
        const val REPLACED = ".replaced"
        const val SPRITE = "sprite.png"
        const val SHEET = "sheet.json"
        const val CHOREO = "choreo.json"
        const val FRAMES = "frames"

        fun isBare(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')
    }
}
