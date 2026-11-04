package kz.rudy.ar.core.emote

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/** The Play HUD's five buttons, in this order. */
@Serializable
enum class Emote { HELLO, BYE, DANCE, SPIN, ROAR }

/** The sound an emote plays when it starts (the host owns the audio files). */
@Serializable
enum class EmoteSound { NONE, HELLO, ROAR, DANCE }

/**
 * The quad's pose at one instant, relative to the physics pose: the host multiplies scaleX/scaleY into the node scale,
 * adds yawDeg to the billboard yaw (a full turn = a spin), tilts the quad by tiltDeg about its own Z (a bow, a wave),
 * and raises it by hop * character height (hop is in character heights, so one choreography reads the same on the
 * table and the floor). Physics never sees any of it.
 */
@Serializable
data class EmotePose(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val yawDeg: Float = 0f,
    val tiltDeg: Float = 0f,
    val hop: Float = 0f,
) {
    companion object {
        val REST = EmotePose()
    }
}

/**
 * One key of a choreography: [pose] reached at [atMs] from the emote's start. [frame] (T33): the model-drawn frame of
 * this emote ([PoseFrames.frame], index 0 = `<emote>-1.png`) the quad shows from this key until the next key, null =
 * the base sprite; a character without that frame shows the base sprite, so the key is harmless without frames.
 */
@Serializable
data class EmoteKey(val atMs: Long, val pose: EmotePose = EmotePose.REST, val frame: Int? = null)

/** [line] = index (0..4) into CharacterSheet.lines[lang]: the line said when the emote starts. */
@Serializable
data class Choreography(
    val emote: Emote,
    val durationMs: Long,
    val keys: List<EmoteKey>,
    val line: Int = 0,
    val sound: EmoteSound = EmoteSound.NONE,
)

/** The five choreographies of one character. [source] = SOURCE_SPARK or SOURCE_DEFAULT (what the logcat key shows). */
@Serializable
data class EmoteSet(val source: String, val choreographies: Map<Emote, Choreography>) {
    /** Never null: [Choreographies.DEFAULT] fills an emote the set does not hold. */
    operator fun get(emote: Emote): Choreography = choreographies[emote] ?: Choreographies.DEFAULT.getValue(emote)

    /** Whether any choreography points at a drawn frame. */
    val hasFrames: Boolean get() = choreographies.values.any { c -> c.keys.any { it.frame != null } }

    /**
     * T33: every choreography keyed for the drawn frames a character has ([counts] = [PoseFrames.counts]), through
     * [Choreographies.withFrames]; an emote with no count loses its frame keys. The source stays.
     */
    fun withFrames(counts: Map<Emote, Int>): EmoteSet =
        copy(choreographies = Emote.entries.associateWith { e -> Choreographies.withFrames(this[e], counts[e] ?: 0) })

    companion object {
        const val SOURCE_SPARK = "spark"
        const val SOURCE_DEFAULT = "default"

        fun defaults(): EmoteSet = EmoteSet(SOURCE_DEFAULT, Choreographies.DEFAULT)
    }
}

/** Hard bounds every choreography is held to, whoever wrote it (Spark, a hand, an old file). */
object ChoreographyRules {
    val DURATION_MS = 300L..4000L
    const val MAX_KEYS = 16
    val SCALE = 0.6f..1.5f
    const val MAX_TILT_DEG = 35f

    /** Between two consecutive keys. */
    const val MAX_YAW_STEP_DEG = 360f
    val HOP = 0f..0.5f
    const val MAX_LINE = 4

    /** [EmoteKey.frame] is kept in 0..this; anything else becomes null (the base sprite). */
    const val MAX_FRAME = PoseFrames.MAX_PER_EMOTE - 1

    /**
     * null when structurally unusable (no keys, durationMs outside DURATION_MS, more than MAX_KEYS keys after dedupe,
     * the REST key at 0 included when one has to be inserted); else: keys sorted by atMs (equal times keep the last),
     * atMs clamped to 0..durationMs, every field clamped to its range (a non-finite value becomes the rest value),
     * yaw steps clamped to MAX_YAW_STEP_DEG (the first key's from 0), a REST key inserted at 0 when no key is at 0,
     * and the LAST key forced to durationMs with pose REST except yaw, which is rounded to the nearest multiple of 360
     * within MAX_YAW_STEP_DEG of the key before it (the quad faces the camera again); a lone key at 0 gets that final
     * key appended instead. line clamped to 0..MAX_LINE. A frame outside 0..MAX_FRAME becomes null; the inserted key
     * at 0 and the last key never carry one (an emote ends on the base sprite). sanitize(sanitize(x)) == sanitize(x).
     */
    fun sanitize(c: Choreography): Choreography? {
        if (c.keys.isEmpty() || c.durationMs !in DURATION_MS) return null
        val d = c.durationMs
        // Clamp times first, then a stable sort; among equal times the last one in input order wins.
        val byTime = LinkedHashMap<Long, EmoteKey>()
        c.keys.map { EmoteKey(it.atMs.coerceIn(0L, d), it.pose, it.frame) }.sortedBy { it.atMs }.forEach { byTime[it.atMs] = it }
        if (byTime.size > MAX_KEYS) return null

        val keys = ArrayList<EmoteKey>(byTime.size + 2)
        if (0L !in byTime) keys += EmoteKey(0L, EmotePose.REST)
        byTime.forEach { (_, k) -> keys += k }
        if (keys.size == 1) keys += EmoteKey(d, keys[0].pose)
        if (keys.size > MAX_KEYS) return null

        var prevYaw = 0f
        val out = ArrayList<EmoteKey>(keys.size)
        for ((i, k) in keys.withIndex()) {
            val yaw = clampStep(prevYaw, k.pose.yawDeg)
            out += if (i == keys.lastIndex) {
                EmoteKey(d, EmotePose.REST.copy(yawDeg = wholeTurns(prevYaw, yaw)))
            } else {
                EmoteKey(k.atMs, clampPose(k.pose, yaw), k.frame?.takeIf { it in 0..MAX_FRAME })
            }
            prevYaw = yaw
        }
        return Choreography(c.emote, d, out, c.line.coerceIn(0, MAX_LINE), c.sound)
    }

    private fun clampPose(p: EmotePose, yaw: Float) = EmotePose(
        scaleX = p.scaleX.orIfNotFinite(1f).coerceIn(SCALE.start, SCALE.endInclusive),
        scaleY = p.scaleY.orIfNotFinite(1f).coerceIn(SCALE.start, SCALE.endInclusive),
        yawDeg = yaw,
        tiltDeg = p.tiltDeg.orIfNotFinite(0f).coerceIn(-MAX_TILT_DEG, MAX_TILT_DEG),
        hop = p.hop.orIfNotFinite(0f).coerceIn(HOP.start, HOP.endInclusive),
    )

    /** [yaw] unless it is more than [MAX_YAW_STEP_DEG] away from [prev] (or not finite); untouched values stay bit-exact. */
    private fun clampStep(prev: Float, yaw: Float): Float {
        if (!yaw.isFinite()) return prev
        if (abs(yaw - prev) <= MAX_YAW_STEP_DEG) return yaw
        return if (yaw > prev) prev + MAX_YAW_STEP_DEG else prev - MAX_YAW_STEP_DEG
    }

    /**
     * The multiple of 360 nearest [yaw] among those within [MAX_YAW_STEP_DEG] of [prev] (the 720-degree window always
     * holds at least two), so the turn back to the camera is never a bigger step than any other.
     */
    private fun wholeTurns(prev: Float, yaw: Float): Float {
        val lo = ceil((prev - MAX_YAW_STEP_DEG) / 360f)
        val hi = floor((prev + MAX_YAW_STEP_DEG) / 360f)
        return round(yaw / 360f).coerceIn(lo, hi) * 360f + 0f // + 0f: never -0
    }

    private fun Float.orIfNotFinite(v: Float) = if (isFinite()) this else v
}
