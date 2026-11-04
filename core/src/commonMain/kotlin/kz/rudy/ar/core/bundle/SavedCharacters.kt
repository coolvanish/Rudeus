package kz.rudy.ar.core.bundle

import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.emote.PoseFrames
import kz.rudy.ar.core.io.CharacterFolder
import kotlin.time.Clock

/**
 * A character made on this phone and kept on disk. [emotes]: the defaults when its choreo.json is missing or unreadable;
 * [frames] (T33): its drawn pose frames, empty when it has none.
 */
class SavedCharacter(
    val id: String,
    val sheet: CharacterSheet,
    val sprite: ByteArray,
    val emotes: EmoteSet = EmoteSet.defaults(),
    val frames: PoseFrames = PoseFrames.EMPTY,
)

/**
 * Characters made on this phone, so a good one can be played again without a new photo and a new pipeline run. Ids
 * are `saved-<epoch ms>`, strictly rising even if the clock stands still or goes back, and [load] returns newest first.
 * An entry that cannot be read is skipped; a folder that is not a character is ignored. Past [maxKept] the oldest
 * goes, which bounds what [load] brings into memory. One call at a time; both do file I/O, so call them off the main
 * thread.
 */
class SavedCharacters(
    private val folder: CharacterFolder,
    private val maxKept: Int = MAX_KEPT,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    init { require(maxKept > 0) }

    /** Throws when the folder cannot be written; nothing is evicted then. */
    fun save(sprite: ByteArray, sheet: CharacterSheet, emotes: EmoteSet = EmoteSet.defaults(), frames: PoseFrames = PoseFrames.EMPTY): SavedCharacter {
        val stamps = stamps()
        val id = idOf(maxOf(clock(), (stamps.firstOrNull() ?: 0L) + 1))
        folder.write(id, sprite, Bundle.encodeSheet(sheet), Bundle.encodeEmotes(emotes), frames.files())
        stamps.drop(maxKept - 1).forEach { folder.delete(idOf(it)) }
        return SavedCharacter(id, sheet, sprite, emotes, frames)
    }

    fun load(): List<SavedCharacter> = stamps().mapNotNull { read(idOf(it)) }

    private fun read(id: String): SavedCharacter? = try {
        val sprite = folder.readSprite(id)?.takeIf { it.isNotEmpty() }
        val sheet = folder.readSheet(id)
        if (sprite == null || sheet == null) null else SavedCharacter(id, Bundle.decodeSheet(sheet), sprite, emotesOf(id), framesOf(id))
    } catch (e: Exception) {
        null
    }

    /** A missing or unreadable choreo.json never drops the character: it plays the defaults. */
    private fun emotesOf(id: String): EmoteSet =
        try { folder.readChoreo(id)?.let(Bundle::decodeEmotes) } catch (e: Exception) { null } ?: EmoteSet.defaults()

    /** Missing or unreadable frames never drop the character either: it plays its emotes on the base sprite. */
    private fun framesOf(id: String): PoseFrames =
        try { PoseFrames.fromFiles(folder.readFrames(id)) } catch (e: Exception) { PoseFrames.EMPTY }

    /** The stamps of every character in the folder, newest first. */
    private fun stamps(): List<Long> =
        folder.ids().mapNotNull { if (it.startsWith(PREFIX)) it.removePrefix(PREFIX).toLongOrNull() else null }.sortedDescending()

    private fun idOf(stamp: Long) = "$PREFIX$stamp"

    companion object {
        /** About 25 MB at most at the bundled sprite size. */
        const val MAX_KEPT = 30

        /** Same bound as the bundled sprites: a ~6 cm billboard on the table; the pipeline's full size is 2-5 MB. */
        const val SPRITE_MAX_SIDE = 768

        private const val PREFIX = "saved-"
    }
}
