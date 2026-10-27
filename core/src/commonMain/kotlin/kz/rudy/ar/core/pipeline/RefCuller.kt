package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.io.ArrayRaster
import kz.rudy.ar.core.io.RasterImage
import kz.rudy.ar.core.io.alpha
import kotlin.math.round
import kotlin.math.sqrt

/** One style-reference candidate: a stable file name and its decoded pixels. */
data class RefCandidate(val name: String, val image: RasterImage)

/**
 * Outcome of [RefCuller.cull]: names kept (best first) and why each other candidate was dropped.
 * [meanDistance] is each kept name's mean distance (0..1) to the other kept refs.
 */
data class CullResult(
    val kept: List<String>,
    val dropped: List<Dropped>,
    val meanDistance: Map<String, Float> = emptyMap(),
) {
    data class Dropped(val name: String, val reason: String)
}

/**
 * Picks the [keep] most mutually consistent style references from a larger candidate set (Day 2: "8 culled
 * style refs"). Only the foreground counts: transparent or near-white (high L*, low chroma) pixels are page.
 *  1. hard rejects: duplicate name (first wins), a side under [MIN_SIDE], foreground share outside
 *     [MIN_FOREGROUND]..[MAX_FOREGROUND];
 *  2. features: foreground palette ([PALETTE_SIZE] colours) and outline fraction, via [StyleGate];
 *  3. pairwise distance `w × (1 − symmetric paletteScore) + (1 − w) × (1 − outlineScore)`, w = [StyleGate.PALETTE_WEIGHT];
 *  4. near-duplicates (distance < [DUPLICATE_DISTANCE]) drop the later name;
 *  5. greedy elimination of the highest mean distance until [keep] remain (ties: later name goes).
 * Deterministic regardless of input order: everything runs on the name-sorted list.
 */
object RefCuller {
    /** Smallest accepted width and height, px. */
    const val MIN_SIDE = 256

    /** Long side the culler samples down to (stride sampling) before measuring. */
    const val MAX_SAMPLE_SIDE = 256

    /** Foreground share below this is "empty". */
    const val MIN_FOREGROUND = 0.03f

    /** Foreground share above this is "no background". */
    const val MAX_FOREGROUND = 0.90f

    /** Opaque pixels with L* above this and chroma below [BACKGROUND_MAX_CHROMA] are page, not drawing. */
    const val BACKGROUND_MIN_L = 90f

    /** Chroma (√(a*² + b*²)) below which a bright pixel counts as page. */
    const val BACKGROUND_MAX_CHROMA = 10f

    /** Dominant colours per candidate, as in [StyleGate.paletteSize]. */
    const val PALETTE_SIZE = 6

    /** Pairs closer than this are copies; the later name is dropped. */
    const val DUPLICATE_DISTANCE = 0.02f

    fun cull(candidates: List<RefCandidate>, keep: Int = 8): CullResult {
        require(keep > 0) { "keep must be positive" }
        val dropped = mutableListOf<CullResult.Dropped>()
        val seen = HashSet<String>()
        val alive = mutableListOf<Features>()
        // Stable sort: among equal names the first in input order is seen first and kept.
        for (c in candidates.sortedBy { it.name }) {
            val reason = if (!seen.add(c.name)) "duplicate name" else reject(c.image)
            if (reason != null) { dropped += CullResult.Dropped(c.name, reason); continue }
            val fg = foreground(c.image)
            when {
                fg.share < MIN_FOREGROUND -> dropped += CullResult.Dropped(c.name, "empty: foreground ${percent(fg.share)}")
                fg.share > MAX_FOREGROUND -> dropped += CullResult.Dropped(c.name, "no background: foreground ${percent(fg.share)}")
                else -> alive += Features(c.name, StyleGate.palette(fg.raster, PALETTE_SIZE), StyleGate.outlineFraction(fg.raster))
            }
        }

        val n = alive.size
        val dist = Array(n) { FloatArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val d = distance(alive[i], alive[j])
            dist[i][j] = d; dist[j][i] = d
        }

        // Near-duplicates: each later name is checked against the earlier survivors.
        val live = BooleanArray(n) { true }
        for (j in 0 until n) {
            val original = (0 until j).firstOrNull { live[it] && dist[it][j] < DUPLICATE_DISTANCE } ?: continue
            live[j] = false
            dropped += CullResult.Dropped(alive[j].name, "near-duplicate of ${alive[original].name}")
        }

        // Greedy elimination; `>=` over the name-sorted indices makes the later name lose a tie.
        var remaining = (0 until n).filter { live[it] }
        while (remaining.size > keep) {
            val means = meanDistances(remaining, dist)
            var worst = 0
            for (k in means.indices) if (means[k] >= means[worst]) worst = k
            dropped += CullResult.Dropped(alive[remaining[worst]].name, "outlier: mean distance ${twoDecimals(means[worst])}")
            remaining = remaining.filterIndexed { k, _ -> k != worst }
        }

        val means = meanDistances(remaining, dist)
        val order = remaining.indices.sortedWith(compareBy<Int> { means[it] }.thenBy { alive[remaining[it]].name })
        return CullResult(
            kept = order.map { alive[remaining[it]].name },
            dropped = dropped,
            meanDistance = order.associate { alive[remaining[it]].name to means[it] },
        )
    }

    private class Features(val name: String, val palette: List<PaletteEntry>, val outline: Float)

    private class Foreground(val raster: RasterImage, val share: Float)

    private fun reject(img: RasterImage): String? =
        if (img.width < MIN_SIDE || img.height < MIN_SIDE) "too small: ${img.width}x${img.height}, need $MIN_SIDE px a side" else null

    /**
     * Stride-samples [img] to at most [MAX_SAMPLE_SIDE] on the long side and blanks the page (alpha 0), so
     * [StyleGate.palette] and [StyleGate.outlineFraction] see only the drawing.
     */
    private fun foreground(img: RasterImage): Foreground {
        val stride = (maxOf(img.width, img.height) + MAX_SAMPLE_SIDE - 1) / MAX_SAMPLE_SIDE
        val w = (img.width + stride - 1) / stride
        val h = (img.height + stride - 1) / stride
        val out = ArrayRaster(w, h)
        var fg = 0
        for (y in 0 until h) for (x in 0 until w) {
            val p = img.argb(x * stride, y * stride)
            if (isBackground(p)) continue
            out.set(x, y, p); fg++
        }
        return Foreground(out, fg.toFloat() / (w * h))
    }

    private fun isBackground(p: Int): Boolean {
        if (p.alpha < StyleGate.OPAQUE_ALPHA) return true
        val lab = Lab.fromArgb(p)
        return lab.l > BACKGROUND_MIN_L && sqrt(lab.a * lab.a + lab.b * lab.b) < BACKGROUND_MAX_CHROMA
    }

    /** 0 = same style, 1 = nothing in common. Symmetric by construction. */
    private fun distance(a: Features, b: Features): Float {
        val palette = (StyleGate.paletteScore(a.palette, b.palette) + StyleGate.paletteScore(b.palette, a.palette)) / 2f
        val outline = StyleGate.outlineScore(a.outline, b.outline)
        val w = StyleGate.PALETTE_WEIGHT
        return (w * (1f - palette) + (1f - w) * (1f - outline)).coerceIn(0f, 1f)
    }

    /** Mean distance of each index in [idx] to the others in [idx]; 0 for a lone survivor. */
    private fun meanDistances(idx: List<Int>, dist: Array<FloatArray>): FloatArray = FloatArray(idx.size) { k ->
        if (idx.size < 2) 0f else idx.sumOf { dist[idx[k]][it].toDouble() }.toFloat() / (idx.size - 1)
    }

    private fun percent(share: Float): String = "${round(share * 100f).toInt()}%"

    private fun twoDecimals(x: Float): String {
        val r = round(x * 100f).toInt()
        return "${r / 100}.${(r % 100).toString().padStart(2, '0')}"
    }
}
