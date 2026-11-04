package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Semantics
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.geometry.BooleanMask
import kz.rudy.ar.core.geometry.PixelRect
import kz.rudy.ar.core.geometry.projectToPixel

/** One scan's masks merged into the level in play: what a too-steep view, a failed prompt or an empty place means for it. */
object TableScan {
    /**
     * @property level null = nothing could be built and a real object was seen too steeply: the level in play stays.
     * @property carried objects of [previous][build] not rebuilt by this scan that stay in the level (out of the frame,
     *   covered, stood on, a failed prompt, or empty once so far with [LevelObject.unseen] raised).
     * @property tooSteep labels of every mask left out, for the log.
     * @property missed real objects seen too steeply with no previous object standing in: the level does not have them.
     * @property cutByFrame labels of every mask left out because the frame cuts it, for the log.
     * @property removed ids of previous objects dropped: their place was in the frame, uncovered and empty twice in a row.
     * @property replaced ids of previous objects whose footprint this scan saw better: the built object took their place.
     * @property kept ids of previous objects this scan saw again but kept: a worse view, or a character stands on them.
     */
    data class Result(
        val level: Level?,
        val carried: List<LevelObject>,
        val tooSteep: List<String>,
        val missed: Int,
        val cutByFrame: List<String> = emptyList(),
        val removed: List<String> = emptyList(),
        val replaced: List<String> = emptyList(),
        val kept: List<String> = emptyList(),
    )

    /** A mask this close to the frame's edge is cut off by it, in px. SAM can stop a pixel short of the edge. */
    private const val FRAME_MARGIN_PX = 2

    /**
     * A worse view keeps the known footprint only while the two agree on where the object is, metres between centroids.
     * Farther apart the object moved (SceneBuilder still matches up to [SceneParams.rematchMaxDistM], 5 cm), and where it
     * is beats how well it was seen: a footprint kept at a stale place would lose the match on the next nudge.
     */
    const val KEEP_MAX_SHIFT_M = 0.02f

    /**
     * A new object of a known object's label, with that object's middle under its mask, is a sighting of it up to this
     * far between centroids, metres: twice [SceneParams.rematchMaxDistM]. Farther it is another one in front.
     */
    const val ADOPT_MAX_DIST_M = 0.10f

    /** Scans in a row that must find an object's place empty before it is dropped: one bad mask must not delete a cup. */
    private const val FORGET_AFTER_SCANS = 2

    /**
     * The quality of a real sighting is at least this, so it always beats the unknown 0 of a fixture or an old level
     * ([ViewQuality] gives 0 to a view at or below 20°, which still built an object).
     */
    private const val MIN_SIGHTING_QUALITY = 1e-3f

    /**
     * Merges this scan into [previous], the level in play, instead of replacing it. An object disappears only when its
     * place was in the frame, nothing covered it and it was empty [FORGET_AFTER_SCANS] scans in a row; a new object
     * appears at once; a known footprint is replaced only by a better view ([ViewQuality]); an object one of [standing]
     * (where the characters are, plane coordinates) is on never changes. For every non-DYNAMIC previous object, in order:
     * 1. Rebuilt (SceneBuilder gave it its id, or a new object overlapping it took its id: [adoptIds]): stood on -> kept
     *    byte for byte (only `unseen` back to 0); moved more than [KEEP_MAX_SHIFT_M] or seen better (a higher
     *    [ViewQuality]) -> the built object; else the previous one with `unseen = 0`.
     * 2. Out of the frame (a footprint vertex at the base or the top projects outside the frame inset by
     *    [FRAME_MARGIN_PX], or behind the camera) -> carried unchanged.
     * 3. Covered (its middle projects into the bounding box of any mask of this scan: usable, too steep or cut) -> carried
     *    unchanged. A too-steep or cut mask is never built, and a cup in front hides a book behind it.
     * 4. Stood on -> carried unchanged.
     * 5. Empty -> `unseen + 1`; dropped once that reaches [FORGET_AFTER_SCANS]. A scan with no mask pixel at all (a dark
     *    frame, every prompt failed) saw nothing: every object is carried unchanged.
     * The object of a prompt whose request failed (429, timeout) is carried unchanged, as before. DYNAMIC objects (a hand)
     * are never carried: they are re-placed every scan. Rules 2 to 4 and a failed prompt all carry the object unchanged,
     * so they are checked cheapest first.
     *
     * Too-steep masks never reach [SceneBuilder]: a hand low in the frame or a speck of noise is not a reason to tell the
     * player to move the phone, a real object is ([Result.missed], and a null level when nothing else was built).
     *
     * A mask the frame cuts off is half a silhouette, and on the Pixel a finger over the lens came back as a "laptop" and a
     * "remote", walls nobody can see. So it stays out too and is no reason to speak to the player. Two exceptions. A hand
     * always comes in from the edge. And a compact object nothing is known about yet is built anyway: 2 of 11 real scans
     * had a real cup or rabbit at the edge, its capped footprint was within 2.5 cm of the whole one (the cup narrow), and
     * that beats a character walking through it.
     */
    fun build(
        input: ScanInput,
        failedPrompts: List<String>,
        previous: Level?,
        profile: SurfaceProfile,
        standing: List<Vec2> = emptyList(),
    ): Result {
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

        fun stoodOn(o: LevelObject) = standing.any { o.footprint.contains(it) }
        val byId = known.associateBy { it.id }
        val replaced = ArrayList<String>()
        val kept = ArrayList<String>()
        val previousIds = previous?.objects.orEmpty().mapTo(HashSet()) { it.id }
        val sources = sourcesOf(built.objects, view.usable, builder)
        val withQuality = built.objects.mapIndexed { i, o ->
            val q = sources[i]?.let { ViewQuality.of(it.mask, view.usable) } ?: 0f
            o.copy(quality = q.coerceAtLeast(MIN_SIGHTING_QUALITY))
        }
        val sighted = adoptIds(withQuality, sources, known, previousIds, input).map { b ->
            val o = byId[b.id] ?: return@map b
            when {
                // Seen, so its count of empty scans starts again: a sighting between two empty scans is no "two in a row".
                stoodOn(o) -> (if (o.unseen == 0) o else o.copy(unseen = 0)).also { kept.add(o.id) }
                b.centroid.distanceTo(o.centroid) > KEEP_MAX_SHIFT_M || b.quality > o.quality -> b.also { replaced.add(o.id) }
                else -> o.copy(unseen = 0).also { kept.add(o.id) }
            }
        }

        val taken = sighted.mapTo(HashSet()) { it.id }
        val candidates = known.filter { it.id !in taken }
        // No mask with a pixel in it (a dark frame, every prompt failed): the scan saw nothing, so nothing is seen empty.
        val sawNothing = input.masks.all { it.mask.isEmpty }
        val boxes by lazy { input.masks.mapNotNull { it.mask.boundingBox() } }
        val carried = ArrayList<LevelObject>()
        val removed = ArrayList<String>()
        for (o in candidates) {
            when {
                sawNothing || o.label in failedPrompts || stoodOn(o) || !inFrame(o, input) || boxes.any { covers(it, o, input) } -> carried.add(o)
                o.unseen + 1 >= FORGET_AFTER_SCANS -> removed.add(o.id)
                else -> carried.add(o.copy(unseen = o.unseen + 1))
            }
        }
        val missed = steepObjects.count { m -> candidates.none { m.standsFor(it) } }
        val level = built.copy(objects = sighted + carried)
        return Result(level, carried, steepLabels, missed, cutLabels, removed, replaced, kept)
    }

    /**
     * [objects] where a non-DYNAMIC object with a fresh id (none of [previousIds]) takes the id of a [known] object
     * SceneBuilder matched to nothing, when it is a sighting of it: SAM called the cup a bowl, or it lies past
     * [SceneParams.rematchMaxDistM] (nudged, or a far-side view). Left fresh, the old object would sit under the new
     * mask, "covered", for ever: two obstacles in one place. A sighting is either
     * - footprints that overlap, whatever the labels (either centroid inside the other, or the outlines touch), or
     * - the same label (or a generic one, as in [ScanMasks.GENERIC_LABELS]), centroids within [ADOPT_MAX_DIST_M], and
     *   the old object's middle under the new object's own mask ([sources]): a nudge along the view squeezes two
     *   footprints apart that the mask still joins.
     * A different object in front of a known one leaves it covered and carried. Nearest centroids first, each id once.
     */
    private fun adoptIds(
        objects: List<LevelObject>,
        sources: List<LabeledMask?>,
        known: List<LevelObject>,
        previousIds: Set<String>,
        input: ScanInput,
    ): List<LevelObject> {
        val matched = objects.mapTo(HashSet()) { it.id }
        val orphans = known.filter { it.id !in matched }
        if (orphans.isEmpty()) return objects
        class Claim(val bi: Int, val old: LevelObject, val dist: Float)
        val claims = ArrayList<Claim>()
        for ((bi, b) in objects.withIndex()) {
            if (b.id in previousIds || b.kind == ObjectKind.DYNAMIC) continue
            val box by lazy { sources[bi]?.mask?.boundingBox() }
            for (o in orphans) {
                val dist = b.centroid.distanceTo(o.centroid)
                val sameKind = b.label == o.label || b.label.lowercase() in ScanMasks.GENERIC_LABELS
                val underItsMask = sameKind && dist <= ADOPT_MAX_DIST_M && box?.let { covers(it, o, input) } == true
                if (underItsMask || overlaps(b.footprint, o.footprint)) claims.add(Claim(bi, o, dist))
            }
        }
        if (claims.isEmpty()) return objects
        claims.sortBy { it.dist }
        val out = objects.toMutableList()
        val adopted = HashSet<Int>()
        val usedIds = HashSet<String>()
        for (p in claims) {
            if (p.bi in adopted || p.old.id in usedIds) continue
            out[p.bi] = out[p.bi].copy(id = p.old.id)
            adopted.add(p.bi)
            usedIds.add(p.old.id)
        }
        return out
    }

    /** Either centroid lies inside the other footprint, or the two outlines touch: a vertex inside or two edges crossing. */
    private fun overlaps(a: Polygon, b: Polygon): Boolean {
        if (a.points.maxOf { it.u } < b.points.minOf { it.u } || b.points.maxOf { it.u } < a.points.minOf { it.u } ||
            a.points.maxOf { it.v } < b.points.minOf { it.v } || b.points.maxOf { it.v } < a.points.minOf { it.v }
        ) return false
        if (a.contains(b.centroid()) || b.contains(a.centroid())) return true
        if (a.points.any(b::contains) || b.points.any(a::contains)) return true
        for ((p, q) in a.edges()) for ((r, t) in b.edges()) if (crosses(p, q, r, t)) return true
        return false
    }

    /** Segments p-q and r-t cross at a point inside both (touching ends do not count). */
    private fun crosses(p: Vec2, q: Vec2, r: Vec2, t: Vec2): Boolean {
        val d1 = (q - p).cross(r - p); val d2 = (q - p).cross(t - p)
        val d3 = (t - r).cross(p - r); val d4 = (t - r).cross(q - r)
        return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) && ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
    }

    /**
     * The mask of [usable] each of [objects] came from (SceneBuilder builds one object per mask that yields one, in mask
     * order); null if that pairing ever failed. When every mask yielded an object the order alone pairs them; otherwise
     * the masks that yield one are found again with [SceneBuilder.extract], which is what [SceneBuilder.build] ran on them.
     */
    private fun sourcesOf(objects: List<LevelObject>, usable: ScanInput, builder: SceneBuilder): List<LabeledMask?> {
        val masks = if (objects.size == usable.masks.size) usable.masks
            else usable.masks.filter { builder.extract(it.mask, usable, it.label) != null }
        return objects.mapIndexed { i, o -> masks.getOrNull(i)?.takeIf { it.label == o.label } }
    }

    /** Every footprint vertex of [o], at its base and at its top, projects inside the frame inset by [FRAME_MARGIN_PX]. */
    private fun inFrame(o: LevelObject, input: ScanInput): Boolean {
        val m = FRAME_MARGIN_PX.toFloat()
        val maxU = input.intrinsics.width - m
        val maxV = input.intrinsics.height - m
        for (p in o.footprint.points) for (h in floatArrayOf(0f, o.heightM)) {
            val px = projectToPixel(input.plane.fromPlane(p, h), input.intrinsics, input.cameraPose) ?: return false
            if (!(px.u >= m && px.u <= maxU && px.v >= m && px.v <= maxV)) return false
        }
        return true
    }

    private fun isCutByFrame(m: LabeledMask, profile: SurfaceProfile): Boolean {
        if (Semantics.kindFor(m.label, 0f, profile) == ObjectKind.DYNAMIC) return false
        val box = m.mask.largestComponent().boundingBox() ?: return false
        return box.minX <= FRAME_MARGIN_PX || box.minY <= FRAME_MARGIN_PX ||
            box.maxX >= m.mask.width - 1 - FRAME_MARGIN_PX || box.maxY >= m.mask.height - 1 - FRAME_MARGIN_PX
    }

    /** The middle of [o] projects into [mask]'s bounding box: a few centimetres of old error must not lose it. */
    private fun covers(mask: BooleanMask, o: LevelObject, input: ScanInput): Boolean =
        mask.boundingBox()?.let { covers(it, o, input) } ?: false

    private fun covers(box: PixelRect, o: LevelObject, input: ScanInput): Boolean {
        val middle = input.plane.fromPlane(o.centroid, o.heightM / 2f)
        val px = projectToPixel(middle, input.intrinsics, input.cameraPose) ?: return false
        return px.u >= box.minX && px.u <= box.maxX + 1 && px.v >= box.minY && px.v <= box.maxY + 1
    }
}
