package kz.rudy.ar.core.emote

/**
 * Model-drawn pose frames of one character (T33): per emote, matted PNGs in frame-index order, the index an
 * [EmoteKey.frame] points at. While a key holds frame i of the running emote the quad shows that picture instead of
 * the base sprite; a character without that frame (none drawn, or fewer than the key asks for) keeps the base sprite,
 * so a choreography keyed for frames plays unchanged on a character that has none. Bounded: at most [MAX_PER_EMOTE]
 * per emote and [MAX_PER_CHARACTER] in all, each at most [MAX_SIDE] px on the long side (the host keeps one texture
 * per frame). Equality is by content, so a saved character round-trips in tests.
 */
class PoseFrames private constructor(val byEmote: Map<Emote, List<ByteArray>>) {
    val count: Int get() = byEmote.values.sumOf { it.size }
    val isEmpty: Boolean get() = count == 0

    /** Frame [index] of [emote], or null when the character has no such frame (the base sprite then). */
    fun frame(emote: Emote, index: Int?): ByteArray? =
        if (index == null || index < 0) null else byEmote[emote]?.getOrNull(index)

    fun count(emote: Emote): Int = byEmote[emote]?.size ?: 0

    /** Frames per emote (emotes with none left out), what [EmoteSet.withFrames] keys a choreography for. */
    fun counts(): Map<Emote, Int> = byEmote.filterValues { it.isNotEmpty() }.mapValues { it.value.size }

    /** `<emote>-<n>.png` -> bytes: the files of a bundle entry's or a saved character's `frames/` folder. */
    fun files(): Map<String, ByteArray> = buildMap {
        for ((e, list) in byEmote) list.forEachIndexed { i, bytes -> put(fileName(e, i), bytes) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PoseFrames || byEmote.keys != other.byEmote.keys) return false
        return byEmote.all { (e, list) ->
            val o = other.byEmote.getValue(e)
            list.size == o.size && list.indices.all { list[it].contentEquals(o[it]) }
        }
    }

    override fun hashCode(): Int = byEmote.entries.fold(0) { h, (e, list) -> h * 31 + e.hashCode() * 17 + list.sumOf { it.contentHashCode() } }

    override fun toString(): String = "PoseFrames(" + byEmote.entries.joinToString { "${it.key}=${it.value.size}" } + ")"

    companion object {
        val EMPTY = PoseFrames(emptyMap())

        /** Two per emote: the T30 spike drew two HELLO and two ROAR frames the user judged usable. */
        const val MAX_PER_EMOTE = 2

        /** Four per character: about 4 x 2.3 MB of texture at [MAX_SIDE] on the phone, next to the 768 px sprite. */
        const val MAX_PER_CHARACTER = 4

        /** Same bound as the sprite ([kz.rudy.ar.core.bundle.SavedCharacters.SPRITE_MAX_SIDE]). */
        const val MAX_SIDE = 768

        /** The emotes the pipeline and the CLI draw frames for, in this order. */
        val DRAWN: List<Emote> = listOf(Emote.HELLO, Emote.ROAR)

        /** `hello-1.png` for frame index 0 of HELLO (1-based in the file name, like the T30 spike's files). */
        fun fileName(emote: Emote, index: Int): String = "${emote.name.lowercase()}-${index + 1}.png"

        /** The inverse of [fileName]; null for any other name. */
        fun parseFileName(name: String): Pair<Emote, Int>? {
            val stem = name.removeSuffix(".png").takeIf { it.length < name.length } ?: return null
            val dash = stem.lastIndexOf('-')
            if (dash <= 0) return null
            val emote = Emote.entries.firstOrNull { it.name.lowercase() == stem.substring(0, dash) } ?: return null
            val n = stem.substring(dash + 1).toIntOrNull() ?: return null
            return if (n >= 1) emote to n - 1 else null
        }

        /**
         * [frames] bounded: emotes in [Emote] order, empty byte arrays dropped, each list cut at [MAX_PER_EMOTE] and the
         * whole at [MAX_PER_CHARACTER] (later emotes lose theirs first).
         */
        fun of(frames: Map<Emote, List<ByteArray>>): PoseFrames {
            var budget = MAX_PER_CHARACTER
            val out = LinkedHashMap<Emote, List<ByteArray>>()
            for (e in Emote.entries) {
                val list = frames[e].orEmpty().filter { it.isNotEmpty() }.take(minOf(MAX_PER_EMOTE, budget))
                if (list.isEmpty()) continue
                out[e] = list
                budget -= list.size
            }
            return if (out.isEmpty()) EMPTY else PoseFrames(out)
        }

        /**
         * The inverse of [files]: names [parseFileName] reads, each emote's frames taken from index 0 while contiguous
         * (`hello-1` and `hello-3` without `hello-2` keep only the first), then bounded like [of]. Other names are ignored.
         */
        fun fromFiles(files: Map<String, ByteArray>): PoseFrames {
            val byEmote = HashMap<Emote, HashMap<Int, ByteArray>>()
            for ((name, bytes) in files) {
                val (e, i) = parseFileName(name) ?: continue
                byEmote.getOrPut(e) { HashMap() }[i] = bytes
            }
            return of(byEmote.mapValues { (_, m) -> generateSequence(0) { it + 1 }.map { m[it] }.takeWhile { it != null }.filterNotNull().toList() })
        }
    }
}

/** One frame to draw: frame [index] of [emote]. [name] = `hello-1`, the stem of [PoseFrames.fileName]. */
data class PoseSpec(val emote: Emote, val index: Int) {
    val name: String get() = PoseFrames.fileName(emote, index).removeSuffix(".png")

    companion object {
        /** Every frame the pipeline asks for: [PoseFrames.DRAWN] x [PoseFrames.MAX_PER_EMOTE], HELLO first. */
        val DRAWN: List<PoseSpec> = PoseFrames.DRAWN.flatMap { e -> (0 until PoseFrames.MAX_PER_EMOTE).map { PoseSpec(e, it) } }
    }
}
