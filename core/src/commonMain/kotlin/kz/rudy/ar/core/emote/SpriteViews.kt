package kz.rudy.ar.core.emote

import kz.rudy.ar.core.domain.SpriteView
import kz.rudy.ar.core.domain.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Up to three pictures of one character (T41, ai/build-plan-v2.md "2.5D views"). [base] says which side the pipeline's
 * sprite shows; the sprite itself is not stored here. [drawn] holds the matted PNGs Muse drew of the other views
 * ([kz.rudy.ar.core.meta.MetaModels.drawView]) and never contains [base]. A drawn [SpriteView.SIDE] always looks to
 * the picture's right (the prompt asks for that); a SIDE base looks right when [baseFacesRight]. Each picture is at
 * most [MAX_SIDE] px on the long side (the pipeline bounds them, as it does pose frames).
 *
 * Equality is by content, so a saved character round-trips in tests.
 */
class SpriteViews private constructor(
    val base: SpriteView,
    val baseFacesRight: Boolean,
    val drawn: Map<SpriteView, ByteArray>,
) {
    /** The views this character can show: [drawn] keys plus [base]. */
    val available: Set<SpriteView> = buildSet {
        add(base)
        addAll(drawn.keys)
    }

    /** True when [view] is [base] or drawn. Allocation-free, for the per-frame [ViewPicker]. */
    fun has(view: SpriteView): Boolean = view == base || drawn.containsKey(view)

    /** The drawn picture of [view]; null for [base] (that is the sprite) and for a view not drawn. */
    fun picture(view: SpriteView): ByteArray? = drawn[view]

    /** `view-front.png` / `view-side.png` / `view-back.png` -> bytes, the drawn views only. */
    fun files(): Map<String, ByteArray> = buildMap { for ((v, bytes) in drawn) put(fileName(v), bytes) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpriteViews) return false
        if (base != other.base || baseFacesRight != other.baseFacesRight || drawn.keys != other.drawn.keys) return false
        return drawn.all { (v, bytes) -> bytes.contentEquals(other.drawn.getValue(v)) }
    }

    override fun hashCode(): Int =
        drawn.entries.fold(base.hashCode() * 31 + baseFacesRight.hashCode()) { h, (v, bytes) -> h * 31 + v.hashCode() * 17 + bytes.contentHashCode() }

    override fun toString(): String =
        "SpriteViews(base=$base, baseFacesRight=$baseFacesRight, drawn=" + drawn.entries.joinToString(prefix = "[", postfix = "]") { "${it.key}:${it.value.size}B" } + ")"

    companion object {
        /** Base SIDE looking left, nothing drawn: the v1.0 billboard (the host's `GENERATED_SPRITE_FACES_RIGHT = false`). */
        val NONE: SpriteViews = SpriteViews(SpriteView.SIDE, false, emptyMap())

        /** Same bound as the sprite and the pose frames ([PoseFrames.MAX_SIDE]). */
        const val MAX_SIDE = 768

        /** The order views are asked for and the cut order backwards: BACK first, FRONT last (cut first if Muse fails). */
        private val PRIORITY = listOf(SpriteView.BACK, SpriteView.SIDE, SpriteView.FRONT)

        /**
         * [drawn] without [base] and without empty byte arrays, in [SpriteView] order; [NONE] itself when that is what
         * remains of a SIDE base looking left.
         */
        fun of(base: SpriteView, baseFacesRight: Boolean, drawn: Map<SpriteView, ByteArray>): SpriteViews {
            val kept = LinkedHashMap<SpriteView, ByteArray>()
            for (v in SpriteView.entries) {
                if (v == base) continue
                val bytes = drawn[v] ?: continue
                if (bytes.isNotEmpty()) kept[v] = bytes
            }
            if (kept.isEmpty() && base == NONE.base && baseFacesRight == NONE.baseFacesRight) return NONE
            return SpriteViews(base, baseFacesRight, kept)
        }

        /**
         * The inverse of [files]: names [parseFileName] reads, then [of] (so a file of the [base] view is dropped).
         * Other names (pose frames `hello-1.png`, anything else) are ignored.
         */
        fun fromFiles(base: SpriteView, baseFacesRight: Boolean, files: Map<String, ByteArray>): SpriteViews {
            val drawn = HashMap<SpriteView, ByteArray>()
            for ((name, bytes) in files) {
                val v = parseFileName(name) ?: continue
                drawn[v] = bytes
            }
            return of(base, baseFacesRight, drawn)
        }

        /** `view-back.png` for [SpriteView.BACK]. Disjoint from [PoseFrames.fileName] (`<emote>-<n>.png`). */
        fun fileName(view: SpriteView): String = "$PREFIX${view.name.lowercase()}.png"

        /** The inverse of [fileName]; null for any other name. */
        fun parseFileName(name: String): SpriteView? = SpriteView.entries.firstOrNull { fileName(it) == name }

        /** The views to ask Muse for when the sprite shows [base]: the other two, BACK first (SIDE before FRONT). */
        fun missing(base: SpriteView): List<SpriteView> = PRIORITY.filter { it != base }

        private const val PREFIX = "view-"
    }
}

/** What the host shows: the picture of [view] (the base sprite when [view] is [SpriteViews.base]), flipped horizontally when [mirrored]. */
data class ViewChoice(val view: SpriteView, val mirrored: Boolean)

/**
 * Which of a character's pictures to show for a camera (T41). Pure plane-coordinate maths, called by the AR host once
 * per frame per character: scalar arithmetic only, and the result is one of six shared [ViewChoice] instances, so a
 * call allocates nothing.
 *
 * The bearing: θ = atan2(f · R̂, f · F̂), the facing [f] seen in the camera's frame (F̂ = cameraForward, R̂ =
 * cameraRight, both normalised; they need not be perpendicular). |θ| <= 45° -> BACK (it walks away from us),
 * |θ| >= 135° -> FRONT (it walks towards us), else SIDE. [HYSTERESIS_DEG] widens the sector of the previous view
 * (the one shown, fallbacks included), boundaries inclusive: BACK holds up to 55°, FRONT down to 125°, SIDE from 35°
 * to 145°; past that the plain sectors decide.
 *
 * A wanted view the character lacks falls back to SIDE, then to the base sprite. A real FRONT or BACK picture (drawn,
 * or the base when it is that view) is never mirrored. A SIDE picture, and the base whenever it stands in for a view the
 * character lacks, is mirrored when the direction it was drawn looking (a drawn SIDE looks right, the base
 * [SpriteViews.baseFacesRight]) differs from the screen direction: screen-right when f · R̂ > 0. When |f̂ · R̂| <=
 * [MIRROR_DEADBAND] the screen direction is unclear (it walks almost straight at or away from us) and the previous
 * mirror flag holds if the same picture was showing, else it is not mirrored. That is the host's flip rule
 * (`FLIP_DEADBAND = 0.15` in androidApp CharacterNode.kt), so a character with [SpriteViews.NONE] gets exactly the
 * flip it gets today.
 *
 * Degenerate input (a zero facing, or a zero camera vector: the camera straight above the table) returns [previous],
 * or the base sprite unmirrored when there is none.
 */
object ViewPicker {
    const val HYSTERESIS_DEG = 10f

    /** Half-width of the BACK and FRONT sectors around the camera axis. */
    const val SECTOR_DEG = 45f

    /** |f̂ · R̂| at or below this keeps the previous mirror flag; equals the host's `FLIP_DEADBAND`. */
    const val MIRROR_DEADBAND = 0.15f

    /** Squared length below which a vector counts as zero. */
    private const val EPS_SQ = 1e-12f

    private const val RAD_TO_DEG = (180.0 / PI).toFloat()

    // The six possible answers, shared: index = view.ordinal * 2 + (mirrored ? 1 : 0).
    private val CHOICES: Array<ViewChoice> = Array(SpriteView.entries.size * 2) { ViewChoice(SpriteView.entries[it / 2], it % 2 == 1) }

    private fun choice(view: SpriteView, mirrored: Boolean): ViewChoice = CHOICES[view.ordinal * 2 + if (mirrored) 1 else 0]

    fun pick(facing: Vec2, cameraForward: Vec2, cameraRight: Vec2, previous: ViewChoice?, views: SpriteViews): ViewChoice {
        val ff = facing.lengthSquared
        val fwd = cameraForward.lengthSquared
        val rgt = cameraRight.lengthSquared
        if (!(ff > EPS_SQ && fwd > EPS_SQ && rgt > EPS_SQ)) return previous ?: choice(views.base, false) // NaN lands here too
        val along = facing.dot(cameraForward) / sqrt(fwd) // |f| cos θ
        val across = facing.dot(cameraRight) / sqrt(rgt) // |f| sin θ, > 0 = screen-right
        if (along * along + across * across <= EPS_SQ * ff) return previous ?: choice(views.base, false) // F ∥ R
        val deg = abs(atan2(across, along)) * RAD_TO_DEG // 0 = away from us, 180 = towards us

        val wanted = wantedView(deg, previous?.view)
        val shown = when {
            views.has(wanted) -> wanted
            views.has(SpriteView.SIDE) -> SpriteView.SIDE
            else -> views.base
        }
        // A real FRONT or BACK picture, shown for itself, keeps its orientation.
        if (shown == wanted && shown != SpriteView.SIDE) return choice(shown, false)

        val drawnRight = if (shown == views.base) views.baseFacesRight else true
        val sinNorm = across / sqrt(ff)
        val mirrored = if (abs(sinNorm) > MIRROR_DEADBAND) (sinNorm > 0f) != drawnRight
        else previous?.takeIf { it.view == shown }?.mirrored ?: false
        return choice(shown, mirrored)
    }

    /** The sector of [deg] (0..180), with the previous view's sector widened by [HYSTERESIS_DEG]. */
    private fun wantedView(deg: Float, previous: SpriteView?): SpriteView {
        val back = SECTOR_DEG
        val front = 180f - SECTOR_DEG
        when (previous) {
            SpriteView.BACK -> if (deg <= back + HYSTERESIS_DEG) return SpriteView.BACK
            SpriteView.FRONT -> if (deg >= front - HYSTERESIS_DEG) return SpriteView.FRONT
            SpriteView.SIDE -> if (deg >= back - HYSTERESIS_DEG && deg <= front + HYSTERESIS_DEG) return SpriteView.SIDE
            null -> {}
        }
        return when {
            deg <= back -> SpriteView.BACK
            deg >= front -> SpriteView.FRONT
            else -> SpriteView.SIDE
        }
    }
}
