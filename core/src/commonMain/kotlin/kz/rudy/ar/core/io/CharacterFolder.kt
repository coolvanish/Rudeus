package kz.rudy.ar.core.io

/**
 * Where saved characters land: one entry per id, holding the sprite PNG and the sheet JSON. `FileCharacterFolder`
 * (jvmMain / androidMain) writes `<dir>/<id>/sprite.png` + `sheet.json`; [MemoryCharacterFolder] keeps them for tests.
 */
interface CharacterFolder {
    /** Every entry name found, in no particular order; may hold names that are not character ids. */
    fun ids(): List<String>

    /** Stores both files as one step: after a crash [id] is either complete or absent. Throws on an I/O failure. */
    fun write(id: String, sprite: ByteArray, sheet: String)

    /** The stored sprite of [id], or null when it is missing. */
    fun readSprite(id: String): ByteArray?

    /** The stored sheet of [id], or null when it is missing. */
    fun readSheet(id: String): String?

    fun delete(id: String)
}

/** In-memory [CharacterFolder]. */
class MemoryCharacterFolder : CharacterFolder {
    val sprites: MutableMap<String, ByteArray> = linkedMapOf()
    val sheets: MutableMap<String, String> = linkedMapOf()

    override fun ids(): List<String> = (sprites.keys + sheets.keys).toList()

    override fun write(id: String, sprite: ByteArray, sheet: String) {
        sprites[id] = sprite
        sheets[id] = sheet
    }

    override fun readSprite(id: String): ByteArray? = sprites[id]

    override fun readSheet(id: String): String? = sheets[id]

    override fun delete(id: String) {
        sprites.remove(id)
        sheets.remove(id)
    }
}
