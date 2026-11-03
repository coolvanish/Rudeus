package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Semantics
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.geometry.BooleanMask
import kz.rudy.ar.core.geometry.projectToPixel

/** One scan's masks -> the level to swap in, deciding what a too-steep view or a failed prompt means for it. */
object TableScan {
    /**
     * @property level null = nothing could be built and a real object was seen too steeply: the level in play stays.
     * @property carried objects of [previous][build] kept because this scan could not see them properly.
     * @property tooSteep labels of every mask left out, for the log.
     * @property missed real objects seen too steeply with no previous object standing in: the level does not have them.
     * @property cutByFrame labels of every mask left out because the frame cuts it, for the log.
     */
    data class Result(
        val level: Level?,
        val carried: List<LevelObject>,
        val tooSteep: List<String>,
        val missed: Int,
        val cutByFrame: List<String> = emptyList(),
    )

    /** A mask this close to the frame's edge is cut off by it, in px. SAM can stop a pixel short of the edge. */
    private const val FRAME_MARGIN_PX = 2

    /**
     * Too-steep masks never reach [SceneBuilder]. A prompt whose request failed (429, timeout) or an object under a
     * too-steep mask must not vanish: that previous object is carried over unchanged. Too steep is judged per object,
     * so a cup that moved away keeps no ghost behind it. DYNAMIC objects (a hand) are never carried: they are re-placed
     * every scan. A hand low in the frame or a speck of noise is not a reason to tell the player to move the phone.
     *
     * A mask the frame cuts off is half a silhouette, and on the Pixel a finger over the lens came back as a "laptop" and a
     * "remote", walls nobody can see. So it stays out too: it keeps the previous object it covers, like a too-steep mask,
     * and is no reason to speak to the player. Two exceptions. A hand always comes in from the edge. And a compact object
     * nothing is known about yet is built anyway: 2 of 11 real scans had a real cup or rabbit at the edge, its capped
     * footprint was within 2.5 cm of the whole one (the cup narrow), and that beats a character walking through it.
     */
    fun build(input: ScanInput, failedPrompts: List<String>, previous: Level?, profile: SurfaceProfile): Result {
        fun LabeledMask.standsFor(o: LevelObject) =
            (label == o.label || label.lowercase() in ScanMasks.GENERIC_LABELS) && covers(mask, o, input)
        val known = previous?.objects.orEmpty().filter { it.kind != ObjectKind.DYNAMIC }
        val (cut, whole) = input.masks.partition { m ->
            isCutByFrame(m, profile) && (!Semantics.isCompact(m.label) || known.any { m.standsFor(it) })
        }
        val view = ScanAngle.split(input.copy(masks = whole))
        val builder = SceneBuilder()
        val steepLabels = view.tooSteep.map { it.label }
        val steepObjects = view.tooSteep.filter {
            Semantics.kindFor(it.label, 0f, profile) != ObjectKind.DYNAMIC && builder.extract(it.mask, input, it.label) != null
        }
        val built = builder.build(view.usable, previous, profile)
        val cutLabels = cut.map { it.label }
        if (built.objects.isEmpty() && steepObjects.isNotEmpty()) return Result(null, emptyList(), steepLabels, steepObjects.size, cutLabels)

        val taken = built.objects.mapTo(HashSet()) { it.id }
        val candidates = known.filter { it.id !in taken }
        val carried = candidates.filter { o -> o.label in failedPrompts || (steepObjects + cut).any { it.standsFor(o) } }
        val missed = steepObjects.count { m -> candidates.none { m.standsFor(it) } }
        val level = if (carried.isEmpty()) built else built.copy(objects = built.objects + carried)
        return Result(level, carried, steepLabels, missed, cutLabels)
    }

    private fun isCutByFrame(m: LabeledMask, profile: SurfaceProfile): Boolean {
        if (Semantics.kindFor(m.label, 0f, profile) == ObjectKind.DYNAMIC) return false
        val box = m.mask.largestComponent().boundingBox() ?: return false
        return box.minX <= FRAME_MARGIN_PX || box.minY <= FRAME_MARGIN_PX ||
            box.maxX >= m.mask.width - 1 - FRAME_MARGIN_PX || box.maxY >= m.mask.height - 1 - FRAME_MARGIN_PX
    }

    /** The middle of [o] projects into [mask]'s bounding box: a few centimetres of old error must not lose it. */
    private fun covers(mask: BooleanMask, o: LevelObject, input: ScanInput): Boolean {
        val box = mask.boundingBox() ?: return false
        val middle = input.plane.fromPlane(o.centroid, o.heightM / 2f)
        val px = projectToPixel(middle, input.intrinsics, input.cameraPose) ?: return false
        return px.u >= box.minX && px.u <= box.maxX + 1 && px.v >= box.minY && px.v <= box.maxY + 1
    }
}
