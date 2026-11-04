package kz.rudy.ar.core.bundle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.emote.ChoreographyParser
import kz.rudy.ar.core.emote.Emote
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.emote.PoseFrames
import kz.rudy.ar.core.pipeline.CharacterResult

/*
 * Bundled demo characters (Day 2 output, loaded by Day 6 offline mode):
 *
 *   <bundle>/index.json          BundleIndex, characters sorted by ref
 *   <bundle>/<ref>/sprite.png    the stylized sprite
 *   <bundle>/<ref>/sheet.json    CharacterSheet JSON
 *   <bundle>/<ref>/choreo.json   the five emote choreographies (ChoreographyParser.toJson; absent before T29)
 *   <bundle>/<ref>/frames/<emote>-<n>.png   the drawn pose frames (T33; listed in the entry's `frames`, absent when none)
 *
 * Paths inside the index are relative to the bundle dir, '/'-separated. Keep this format boring.
 */

@Serializable
data class BundleEntry(
    val ref: String,
    val name: String,
    val ability: Ability,
    val gateScore: Float,
    val gatePassed: Boolean,
    /** Stylize+gate attempts (1 = first try). */
    val attempts: Int,
    /** Relative path of the sprite PNG, e.g. "cat-01/sprite.png". */
    val sprite: String,
    /** Relative path of the sheet JSON, e.g. "cat-01/sheet.json". */
    val sheet: String,
    /** "meta" | "fixture" | "fireworks" — fixture bundles are smoke tests, not demo characters. */
    val provider: String,
    /** The runs/<runId>.json that produced this character. */
    val runId: String,
    /** Relative path of the choreographies JSON, e.g. "cat-01/choreo.json"; null in indexes written before T29. */
    val choreo: String? = null,
    /**
     * T33: relative paths of the drawn pose frames per emote, in frame-index order, e.g.
     * `{"HELLO": ["cat-01/frames/hello-1.png", "cat-01/frames/hello-2.png"], "ROAR": [...]}`; null = none drawn.
     */
    val frames: Map<Emote, List<String>>? = null,
)

@Serializable
data class BundleIndex(
    val schema: Int = SCHEMA,
    val characters: List<BundleEntry> = emptyList(),
) {
    /** Replaces the entry with the same ref (or adds it) and keeps the list sorted by ref. */
    fun upsert(entry: BundleEntry): BundleIndex =
        copy(characters = (characters.filter { it.ref != entry.ref } + entry).sortedBy { it.ref })

    companion object { const val SCHEMA = 1 }
}

object Bundle {
    const val INDEX = "index.json"

    val json: Json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    fun spritePath(ref: String): String = "$ref/$SPRITE"
    fun sheetPath(ref: String): String = "$ref/$SHEET"
    fun choreoPath(ref: String): String = "$ref/$CHOREO"

    /** `<ref>/frames/<emote>-<n>.png` ([PoseFrames.fileName]). */
    fun framePath(ref: String, emote: Emote, index: Int): String = "$ref/$FRAMES/${PoseFrames.fileName(emote, index)}"

    /** [BundleEntry.frames] for [frames]: null when there are none. */
    fun framePaths(ref: String, frames: PoseFrames): Map<Emote, List<String>>? =
        frames.byEmote.mapValues { (e, list) -> list.indices.map { framePath(ref, e, it) } }.takeIf { it.isNotEmpty() }

    fun entry(result: CharacterResult, provider: String, runId: String): BundleEntry = BundleEntry(
        ref = result.ref,
        name = result.sheet.name,
        ability = result.sheet.ability,
        gateScore = result.gateScore,
        gatePassed = result.gatePassed,
        attempts = result.attempts,
        sprite = spritePath(result.ref),
        sheet = sheetPath(result.ref),
        provider = provider,
        runId = runId,
        choreo = choreoPath(result.ref),
        frames = framePaths(result.ref, result.frames),
    )

    /** Parses an existing index; null text (no index yet) yields an empty one. */
    fun decodeIndex(text: String?): BundleIndex =
        if (text.isNullOrBlank()) BundleIndex() else json.decodeFromString(BundleIndex.serializer(), text)

    fun encodeIndex(index: BundleIndex): String = json.encodeToString(BundleIndex.serializer(), index) + "\n"

    fun encodeSheet(sheet: CharacterSheet): String = json.encodeToString(CharacterSheet.serializer(), sheet) + "\n"

    fun decodeSheet(text: String): CharacterSheet = json.decodeFromString(CharacterSheet.serializer(), text)

    fun encodeEmotes(emotes: EmoteSet): String = ChoreographyParser.toJson(emotes)

    /** Never throws: unreadable text yields the defaults. */
    fun decodeEmotes(text: String): EmoteSet = ChoreographyParser.fromJson(text)

    private const val SPRITE = "sprite.png"
    private const val SHEET = "sheet.json"
    private const val CHOREO = "choreo.json"
    private const val FRAMES = "frames"
}
