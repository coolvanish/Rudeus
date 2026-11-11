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
 * Crash rule ("after a crash [id] is either complete or absent"; a replaced id is the old entry or the new one):
 * - write: the entry is built in `.writing`, then published with one rename. Replacing an id renames the old entry to
 *   `.replaced-<id>` first (a rename cannot land on a non-empty folder), then publishes, then deletes the aside; an
 *   exception while publishing renames the old entry back.
 * - delete: the entry is renamed to `.deleting-<id>` first, then its files are deleted.
 * - recovery, once, on the first call of any method: a `.replaced-<id>` whose `<id>` is missing is renamed back (the
 *   process died between the two renames), one whose `<id>` exists is deleted, and `.writing` / `.deleting-*` are
 *   deleted. So after any process death every id reads as the old entry or the new one, whole.
 * Not covered: power loss. Nothing is fsync'ed, so a rename can reach the disk before the file data (as `File*`).
 *
 * Names starting with `.writing`, `.replaced-` or `.deleting-` are never ids: [ids] skips them, [write] rejects them,
 * reads of them are empty. Renames go through [renameAtomically] (platform rename), everything else through [fs].
 */
class IoCharacterFolder internal constructor(
    private val root: Path,
    private val fs: FileSystem,
    /**
     * Called before every file-system step with `"<op> <file name>"` (e.g. `"sink sheet.json"`, `"write sheet.json"`,
     * `"atomicMove .writing"`); a no-op outside tests. kotlinx-io's [FileSystem] is sealed, so the tests inject I/O
     * failures and simulated process deaths here instead of through a wrapping file system.
     */
    private val beforeStep: (String) -> Unit,
) : CharacterFolder {
    constructor(root: Path, fs: FileSystem = SystemFileSystem) : this(root, fs, {})

    private var recovered = false

    override fun ids(): List<String> = try {
        recoverQuietly()
        if (!isDirectory(root)) emptyList() else list(root).filter { isId(it.name) && isDirectory(it) }.map { it.name }
    } catch (e: IOException) {
        emptyList() // as File.listFiles(): an unreadable root lists nothing
    }

    override fun write(id: String, sprite: ByteArray, sheet: String, choreo: String?, frames: Map<String, ByteArray>) {
        require(isId(id)) { "id must be bare and not reserved: $id" }
        for (name in frames.keys) require(isBare(name)) { "frame name must be bare: $name" }
        recover()
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
        publish(scratch, id)
    }

    override fun readSprite(id: String): ByteArray? = entryFile(id, SPRITE)?.let { readBytes(it) }

    override fun readSheet(id: String): String? = entryFile(id, SHEET)?.let { readText(it) }

    override fun readChoreo(id: String): String? = entryFile(id, CHOREO)?.let { readText(it) }

    override fun readFrames(id: String): Map<String, ByteArray> {
        if (!isId(id)) return emptyMap()
        recoverQuietly()
        val dir = Path(root, id, FRAMES)
        if (!isDirectory(dir)) return emptyMap()
        return list(dir).filter { isFile(it) }.sortedBy { it.name }.associate { it.name to readBytes(it) }
    }

    override fun delete(id: String) {
        if (!isId(id)) return
        try {
            recover()
            val target = Path(root, id)
            if (metadata(target) == null) return
            val aside = Path(root, DELETING + id)
            deleteTree(aside)
            move(target, aside)
            deleteTree(aside)
        } catch (e: IOException) {
            // as File.deleteRecursively(): a delete that cannot finish is not an error for the caller; a renamed-aside
            // remainder is cleaned by the next recovery
        }
    }

    /** `.writing` becomes `<root>/<id>` in one rename; an existing entry is renamed aside first and back on failure. */
    private fun publish(scratch: Path, id: String) {
        val target = Path(root, id)
        if (metadata(target) == null) {
            move(scratch, target)
            return
        }
        val old = Path(root, REPLACED + id)
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
            // the new entry is published; the aside is cleaned by the next recovery
        }
    }

    /** Settles what a process death left behind (see the class doc). Runs once per instance, on the first call. */
    private fun recover() {
        if (recovered) return
        if (isDirectory(root)) {
            for (child in list(root)) {
                val name = child.name
                when {
                    name.startsWith(REPLACED) -> {
                        val id = name.removePrefix(REPLACED)
                        if (isId(id) && metadata(Path(root, id)) == null) move(child, Path(root, id)) else deleteTree(child)
                    }
                    name.startsWith(SCRATCH) || name.startsWith(DELETING) -> deleteTree(child)
                }
            }
        }
        recovered = true
    }

    /** Reads must not throw for what is merely unrecoverable right now; recovery is retried on the next call. */
    private fun recoverQuietly() {
        try { recover() } catch (e: IOException) { }
    }

    private fun entryFile(id: String, name: String): Path? {
        if (!isId(id)) return null
        recoverQuietly()
        return Path(root, id, name).takeIf { isFile(it) }
    }

    // Every file-system call goes through one of these, so [beforeStep] sees each step once.

    private fun metadata(p: Path): FileMetadata? { beforeStep("metadata ${p.name}"); return fs.metadataOrNull(p) }

    private fun list(p: Path): Collection<Path> { beforeStep("list ${p.name}"); return fs.list(p) }

    private fun move(from: Path, to: Path) { beforeStep("atomicMove ${from.name}"); renameAtomically(from, to) }

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

    internal companion object {
        const val SCRATCH = ".writing"
        const val REPLACED = ".replaced-"
        const val DELETING = ".deleting-"
        private const val SPRITE = "sprite.png"
        private const val SHEET = "sheet.json"
        private const val CHOREO = "choreo.json"
        private const val FRAMES = "frames"

        fun isBare(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')

        /** A bare name that is not one of the folder's own scratch names. */
        fun isId(name: String): Boolean =
            isBare(name) && !name.startsWith(SCRATCH) && !name.startsWith(REPLACED) && !name.startsWith(DELETING)
    }
}
