package kz.rudy.ar.core.io

/**
 * Where saved characters land: one entry per id, holding the sprite PNG, the sheet JSON, (since T29) the emote
 * choreographies JSON and (since T33) the drawn pose frames. `FileCharacterFolder` (jvmMain / androidMain) writes
 * `<dir>/<id>/sprite.png` + `sheet.json` (+ `choreo.json`, + `frames/<emote>-<n>.png`); [MemoryCharacterFolder]
 * keeps them for tests.
 */
interface CharacterFolder {
    /** Every entry name found, in no particular order; may hold names that are not character ids. */
    fun ids(): List<String>

    /**
     * Stores the files as one step: after a crash [id] is either complete or absent. [choreo] null = no choreo file;
     * [frames] = file name (`PoseFrames.fileName`) -> PNG bytes under `frames/`, empty = no frames folder.
     * Throws on an I/O failure.
     */
    fun write(id: String, sprite: ByteArray, sheet: String, choreo: String? = null, frames: Map<String, ByteArray> = emptyMap())

    /** The stored sprite of [id], or null when it is missing. */
    fun readSprite(id: String): ByteArray?

    /** The stored sheet of [id], or null when it is missing. */
    fun readSheet(id: String): String?

    /** The stored choreographies of [id], or null when there are none (an entry saved before T29). */
    fun readChoreo(id: String): String?

    /** The stored frames of [id] by file name; empty when there are none (an entry saved before T33). */
    fun readFrames(id: String): Map<String, ByteArray>

    fun delete(id: String)
}

/** In-memory [CharacterFolder]. */
class MemoryCharacterFolder : CharacterFolder {
    val sprites: MutableMap<String, ByteArray> = linkedMapOf()
    val sheets: MutableMap<String, String> = linkedMapOf()
    val choreos: MutableMap<String, String> = linkedMapOf()
    val frames: MutableMap<String, Map<String, ByteArray>> = linkedMapOf()

    override fun ids(): List<String> = (sprites.keys + sheets.keys).toList()

    override fun write(id: String, sprite: ByteArray, sheet: String, choreo: String?, frames: Map<String, ByteArray>) {
        sprites[id] = sprite
        sheets[id] = sheet
        if (choreo != null) choreos[id] = choreo else choreos.remove(id)
        if (frames.isNotEmpty()) this.frames[id] = frames.toMap() else this.frames.remove(id)
    }

    override fun readSprite(id: String): ByteArray? = sprites[id]

    override fun readSheet(id: String): String? = sheets[id]

    override fun readChoreo(id: String): String? = choreos[id]

    override fun readFrames(id: String): Map<String, ByteArray> = frames[id].orEmpty()

    override fun delete(id: String) {
        sprites.remove(id)
        sheets.remove(id)
        choreos.remove(id)
        frames.remove(id)
    }
}
