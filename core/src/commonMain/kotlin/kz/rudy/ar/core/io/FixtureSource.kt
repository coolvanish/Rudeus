package kz.rudy.ar.core.io

/**
 * Read-only access to fixture files (saved API responses, sample images, style refs).
 * Paths are '/'-separated and relative to the source's root. Implementations: [MapFixtureSource]
 * (in-memory, tests), `FileFixtureSource` (jvmMain / androidMain, backed by java.io.File).
 */
interface FixtureSource {
    /** UTF-8 text of the file at [path], or null when it does not exist. */
    fun readText(path: String): String?

    /** Raw bytes of the file at [path], or null when it does not exist. */
    fun readBytes(path: String): ByteArray?

    /** File names (not paths) directly inside [dir], sorted; empty when [dir] does not exist. */
    fun list(dir: String): List<String>

    fun exists(path: String): Boolean
}

/** In-memory [FixtureSource] for tests. Keys are '/'-separated relative paths. */
class MapFixtureSource(files: Map<String, ByteArray> = emptyMap()) : FixtureSource {
    private val files: MutableMap<String, ByteArray> = files.mapKeys { normalise(it.key) }.toMutableMap()

    constructor(vararg textFiles: Pair<String, String>) : this(textFiles.associate { it.first to it.second.encodeToByteArray() })

    fun put(path: String, bytes: ByteArray) { files[normalise(path)] = bytes }
    fun putText(path: String, text: String) = put(path, text.encodeToByteArray())

    override fun readText(path: String): String? = files[normalise(path)]?.decodeToString()
    override fun readBytes(path: String): ByteArray? = files[normalise(path)]
    override fun exists(path: String): Boolean = files.containsKey(normalise(path))

    override fun list(dir: String): List<String> {
        val prefix = normalise(dir).let { if (it.isEmpty()) "" else "$it/" }
        return files.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { it.isNotEmpty() && !it.contains('/') }
            .sorted()
    }

    private fun normalise(p: String): String = p.trim().trimStart('/').trimEnd('/').replace("//", "/")
}

/** Joins path segments with '/', skipping empty ones (so an empty root works). */
fun joinPath(vararg parts: String): String =
    parts.filter { it.isNotEmpty() }.joinToString("/") { it.trimEnd('/') }
