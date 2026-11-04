package kz.rudy.ar.core.io

/**
 * Where saved characters land: one entry per id, holding the sprite PNG, the sheet JSON and (since T29) the emote
 * choreographies JSON. `FileCharacterFolder` (jvmMain / androidMain) writes `<dir>/<id>/sprite.png` + `sheet.json`
 * (+ `choreo.json`); [MemoryCharacterFolder] keeps them for tests.
 */
interface CharacterFolder {
    /** Every entry name found, in no particular order; may hold names that are not character ids. */
    fun ids(): List<String>

    /**
     * Stores the files as one step: after a crash [id] is either complete or absent. [choreo] null = no choreo file.
     * Throws on an I/O failure.
     */
    fun write(id: String, sprite: ByteArray, sheet: String, choreo: String? = null)

    /** The stored sprite of [id], or null when it is missing. */
    fun readSprite(id: String): ByteArray?

    /** The stored sheet of [id], or null when it is missing. */
    fun readSheet(id: String): String?

    /** The stored choreographies of [id], or null when there are none (an entry saved before T29). */
    fun readChoreo(id: String): String?

    fun delete(id: String)
}

/** In-memory [CharacterFolder]. */
class MemoryCharacterFolder : CharacterFolder {
    val sprites: MutableMap<String, ByteArray> = linkedMapOf()
    val sheets: MutableMap<String, String> = linkedMapOf()
    val choreos: MutableMap<String, String> = linkedMapOf()

    override fun ids(): List<String> = (sprites.keys + sheets.keys).toList()

    override fun write(id: String, sprite: ByteArray, sheet: String, choreo: String?) {
        sprites[id] = sprite
        sheets[id] = sheet
        if (choreo != null) choreos[id] = choreo else choreos.remove(id)
    }

    override fun readSprite(id: String): ByteArray? = sprites[id]

    override fun readSheet(id: String): String? = sheets[id]

    override fun readChoreo(id: String): String? = choreos[id]

    override fun delete(id: String) {
        sprites.remove(id)
        sheets.remove(id)
        choreos.remove(id)
    }
}
