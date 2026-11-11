package kz.rudy.ar.core.emote

import kz.rudy.ar.core.domain.CharacterSheet
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
         * The one rule for a character's base view and direction, fresh from the pipeline or reloaded from disk: the
         * [sheet]'s `baseView ?: SIDE` and `facesRight ?: false` (a sheet without them, every v1.0 one, gives [NONE] when
         * nothing is drawn).
         */
        fun of(sheet: CharacterSheet, drawn: Map<SpriteView, ByteArray> = emptyMap()): SpriteViews =
            of(sheet.baseView ?: SpriteView.SIDE, sheet.facesRight ?: false, drawn)

        /** [fromFiles] with the base view and direction of [sheet], as [of] (sheet) takes them. */
        fun fromFiles(sheet: CharacterSheet, files: Map<String, ByteArray>): SpriteViews =
            fromFiles(sheet.baseView ?: SpriteView.SIDE, sheet.facesRight ?: false, files)

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

/**
 * What the host shows: the picture of [view] (the base sprite when [view] is [SpriteViews.base]), flipped horizontally
 * when [mirrored]. [sector] is where the character's bearing is (BACK / SIDE / FRONT, hysteresis applied), whatever
 * picture ends up standing in for it; [ViewPicker] carries it to the next frame, so hand the last choice back as
 * `previous`.
 */
data class ViewChoice(val view: SpriteView, val mirrored: Boolean, val sector: SpriteView)

/**
 * Which of a character's pictures to show (T41). Pure plane-coordinate maths, called by the AR host once per frame per
 * character: scalar arithmetic only, and the result is one of 18 shared [ViewChoice] instances (or [previous] itself),
 * so a call allocates nothing.
 *
 * **Frame and handedness.** Plane coordinates are the right-handed frame of the session anchor: u = anchor-local X,
 * v = anchor-local Z, h = Y up (androidApp `ar/PlaneCoords.kt`; an iOS host must map ARKit's anchor-local X / Z to
 * u / v the same way). [toCamera] is the camera position minus the character position in that plane. The picker
 * derives its own basis from it: forward F = -toCamera normalised, the direction we look at THIS character along (not
 * the optical axis: at the screen edge they differ by up to half the field of view, more than the hysteresis), and
 * screen-right R = (-F.v, F.u). A camera looking along +v has screen-right -u. That is the vector the Android host
 * flips by today (`right = (cos y, -sin y)`, `y = atan2(toCam.u, toCam.v)`, androidApp `ar/CharacterNode.kt`).
 *
 * **Sector.** θ = atan2(f · R, f · F), the facing f seen from the camera: |θ| <= 45° BACK (it walks away from us),
 * |θ| >= 135° FRONT (it walks towards us), else SIDE. The previous choice's [ViewChoice.sector] is widened by
 * [HYSTERESIS_DEG], boundaries inclusive: BACK holds to 55°, FRONT down to 125°, SIDE from 35° to 145°. The sector
 * never depends on which pictures exist, so a missing view cannot make it flicker.
 *
 * **Picture.** The sector's view when the character has it, else SIDE, else the base sprite.
 *
 * **Mirror.** A real FRONT or BACK picture (drawn, or the base when it is that view) is never mirrored. A SIDE picture,
 * and the base whenever it stands in for another sector, is mirrored when the direction it was drawn looking (a drawn
 * SIDE looks right, the base [SpriteViews.baseFacesRight]) differs from the screen direction (screen-right when
 * f · R > 0). When |f̂ · R| <= [MIRROR_DEADBAND] the screen direction is unclear and the previous flag holds if the same
 * picture was showing, else it is not mirrored: the host's flip rule (`FLIP_DEADBAND = 0.15`, starting unflipped), so a
 * character with [SpriteViews.NONE] flips exactly as today.
 *
 * **Degenerate input** (a zero or NaN [facing], or [toCamera] shorter than [MIN_TO_CAMERA_M]: the camera straight above
 * the character) returns [previous], else the base sprite unmirrored (sector = the base view).
 */
object ViewPicker {
    const val HYSTERESIS_DEG = 10f

    /** Half-width of the BACK and FRONT sectors around the line of sight. */
    const val SECTOR_DEG = 45f

    /** |f̂ · R| at or below this keeps the previous mirror flag; equals the host's `FLIP_DEADBAND`. */
    const val MIRROR_DEADBAND = 0.15f

    /** A camera closer than this in the plane (1 cm) is straight above the character: no bearing. */
    const val MIN_TO_CAMERA_M = 0.01f

    /** Squared length below which a facing counts as zero. */
    private const val EPS_SQ = 1e-12f

    private const val RAD_TO_DEG = (180.0 / PI).toFloat()

    private val VIEWS = SpriteView.entries

    // The 18 possible answers, shared: index = (sector * 3 + view) * 2 + mirrored.
    private val CHOICES: Array<ViewChoice> = Array(VIEWS.size * VIEWS.size * 2) {
        ViewChoice(view = VIEWS[(it / 2) % VIEWS.size], mirrored = it % 2 == 1, sector = VIEWS[it / (2 * VIEWS.size)])
    }

    private fun choice(view: SpriteView, mirrored: Boolean, sector: SpriteView): ViewChoice =
        CHOICES[(sector.ordinal * VIEWS.size + view.ordinal) * 2 + if (mirrored) 1 else 0]

    fun pick(facing: Vec2, toCamera: Vec2, previous: ViewChoice?, views: SpriteViews): ViewChoice {
        val ff = facing.lengthSquared
        val tc = toCamera.lengthSquared
        // Written so that NaN lands in the degenerate branch too.
        if (!(ff > EPS_SQ && tc >= MIN_TO_CAMERA_M * MIN_TO_CAMERA_M)) return previous ?: choice(views.base, false, views.base)
        val len = sqrt(tc)
        // F = -toCamera / len; R = (-F.v, F.u) = (toCamera.v, -toCamera.u) / len.
        val along = -(facing.u * toCamera.u + facing.v * toCamera.v) / len // |f| cos θ
        val across = (facing.u * toCamera.v - facing.v * toCamera.u) / len // |f| sin θ, > 0 = screen-right
        val deg = abs(atan2(across, along)) * RAD_TO_DEG // 0 = away from us, 180 = towards us

        val sector = sectorOf(deg, previous?.sector)
        val shown = when {
            views.has(sector) -> sector
            views.has(SpriteView.SIDE) -> SpriteView.SIDE
            else -> views.base
        }
        // A real FRONT or BACK picture, shown for its own sector, keeps its orientation.
        if (shown == sector && shown != SpriteView.SIDE) return choice(shown, false, sector)

        val drawnRight = if (shown == views.base) views.baseFacesRight else true
        val sinNorm = across / sqrt(ff)
        val mirrored = if (abs(sinNorm) > MIRROR_DEADBAND) (sinNorm > 0f) != drawnRight
        else previous?.takeIf { it.view == shown }?.mirrored ?: false
        return choice(shown, mirrored, sector)
    }

    /** The sector of [deg] (0..180), with the [previous] sector widened by [HYSTERESIS_DEG]. */
    private fun sectorOf(deg: Float, previous: SpriteView?): SpriteView {
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
