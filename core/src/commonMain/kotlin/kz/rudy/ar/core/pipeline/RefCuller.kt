package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.io.ArrayRaster
import kz.rudy.ar.core.io.RasterImage
import kz.rudy.ar.core.io.alpha
import kotlin.math.abs
import kotlin.math.min
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
 *  1. hard rejects: a side under [MIN_SIDE], foreground share outside [MIN_FOREGROUND]..[MAX_FOREGROUND];
 *  2. duplicate names among the survivors: the first by content key (a hash of the sampled pixels) wins;
 *  3. near-duplicates — the same picture, judged by a [SIGNATURE_GRID]² layout signature over the foreground
 *     bounding box (distance < [DUPLICATE_DISTANCE]) — drop the later name;
 *  4. style features: foreground palette ([PALETTE_SIZE] colours) and outline fraction, via [StyleGate];
 *     pairwise style distance `w × (1 − symmetric paletteScore) + (1 − w) × (1 − outlineScore)`,
 *     w = [StyleGate.PALETTE_WEIGHT];
 *  5. greedy elimination of the highest mean style distance until [keep] remain (ties: later name goes).
 * Deterministic regardless of input order: everything runs on the list sorted by (name, content key).
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

    /** Cells per side of the content signature laid over the foreground bounding box. */
    const val SIGNATURE_GRID = 16

    /**
     * Mean per-cell signature difference (0..1) below which two candidates are the same picture; the later
     * name is dropped. Independent of style: same palette and line weight with another subject is kept.
     * Measured on the synthetic set: a 1 px-shifted, slightly recoloured copy ≈ 0.008; the closest pair of
     * different layouts ≈ 0.10.
     */
    const val DUPLICATE_DISTANCE = 0.03f

    fun cull(candidates: List<RefCandidate>, keep: Int = 8): CullResult {
        require(keep > 0) { "keep must be positive" }
        val dropped = mutableListOf<CullResult.Dropped>()
        val seen = HashSet<String>()
        val alive = mutableListOf<Features>()
        // (name, content key) is order-independent; equal keys mean identical samples, so either may win.
        val sampled = candidates.map { sample(it) }.sortedWith(compareBy<Sampled> { it.name }.thenBy { it.key })
        for (s in sampled) {
            val reason = reject(s) ?: if (!seen.add(s.name)) "duplicate name" else null
            if (reason != null) { dropped += CullResult.Dropped(s.name, reason); continue }
            alive += Features(s.name, StyleGate.palette(s.fg, PALETTE_SIZE), StyleGate.outlineFraction(s.fg), signature(s.fg))
        }

        // Near-duplicates: each later name is checked against the earlier survivors.
        val n = alive.size
        val live = BooleanArray(n) { true }
        for (j in 0 until n) {
            val original = (0 until j).firstOrNull {
                live[it] && signatureDistance(alive[it].signature, alive[j].signature) < DUPLICATE_DISTANCE
            } ?: continue
            live[j] = false
            dropped += CullResult.Dropped(alive[j].name, "near-duplicate of ${alive[original].name}")
        }

        val dist = Array(n) { FloatArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            if (!live[i] || !live[j]) continue
            val d = distance(alive[i], alive[j])
            dist[i][j] = d; dist[j][i] = d
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

    private class Features(val name: String, val palette: List<PaletteEntry>, val outline: Float, val signature: FloatArray)

    /** A candidate after sampling: [fg] holds only drawing pixels (page = alpha 0); [key] hashes the raw samples. */
    private class Sampled(val name: String, val width: Int, val height: Int, val fg: ArrayRaster, val share: Float, val key: Long)

    private fun reject(s: Sampled): String? = when {
        s.width < MIN_SIDE || s.height < MIN_SIDE -> "too small: ${s.width}x${s.height}, need $MIN_SIDE px a side"
        s.share < MIN_FOREGROUND -> "empty: foreground ${percent(s.share)}"
        s.share > MAX_FOREGROUND -> "no background: foreground ${percent(s.share)}"
        else -> null
    }

    /**
     * Stride-samples [c] to at most [MAX_SAMPLE_SIDE] on the long side and blanks the page (alpha 0), so
     * [StyleGate.palette] and [StyleGate.outlineFraction] see only the drawing. The content key is FNV-1a
     * over the original size and every raw sample.
     */
    private fun sample(c: RefCandidate): Sampled {
        val img = c.image
        val stride = maxOf(1, (maxOf(img.width, img.height) + MAX_SAMPLE_SIDE - 1) / MAX_SAMPLE_SIDE)
        val w = (img.width + stride - 1) / stride
        val h = (img.height + stride - 1) / stride
        val out = ArrayRaster(w, h)
        var key = fnv(fnv(FNV_OFFSET, img.width), img.height)
        var fg = 0
        for (y in 0 until h) for (x in 0 until w) {
            val p = img.argb(x * stride, y * stride)
            key = fnv(key, p)
            if (isBackground(p)) continue
            out.set(x, y, p); fg++
        }
        val share = if (w * h == 0) 0f else fg.toFloat() / (w * h)
        return Sampled(c.name, img.width, img.height, out, share, key)
    }

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L

    private fun fnv(h: Long, v: Int): Long {
        var r = h
        for (shift in 0 until 32 step 8) r = (r xor ((v ushr shift) and 0xFF).toLong()) * FNV_PRIME
        return r
    }

    private fun isBackground(p: Int): Boolean {
        if (p.alpha < StyleGate.OPAQUE_ALPHA) return true
        val lab = Lab.fromArgb(p)
        return lab.l > BACKGROUND_MIN_L && sqrt(lab.a * lab.a + lab.b * lab.b) < BACKGROUND_MAX_CHROMA
    }

    /**
     * Layout signature: a [SIGNATURE_GRID]² grid over the foreground bounding box; per cell 4 floats —
     * foreground share, then mean L*, a*, b* of its foreground pixels. Bounding-box relative, so a shifted
     * copy matches. Callers guarantee some foreground (the "empty" reject runs first).
     */
    private fun signature(fg: ArrayRaster): FloatArray {
        var x0 = fg.width; var y0 = fg.height; var x1 = -1; var y1 = -1
        for (y in 0 until fg.height) for (x in 0 until fg.width) {
            if (fg.argb(x, y).alpha == 0) continue
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        val g = SIGNATURE_GRID
        val total = IntArray(g * g)
        val acc = FloatArray(g * g * 4) // per cell: count, sumL, sumA, sumB
        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
        for (y in y0..y1) for (x in x0..x1) {
            val cell = ((y - y0) * g / bh) * g + (x - x0) * g / bw
            total[cell]++
            val p = fg.argb(x, y)
            if (p.alpha == 0) continue
            val lab = Lab.fromArgb(p)
            acc[cell * 4] += 1f; acc[cell * 4 + 1] += lab.l; acc[cell * 4 + 2] += lab.a; acc[cell * 4 + 3] += lab.b
        }
        val sig = FloatArray(g * g * 4)
        for (cell in 0 until g * g) {
            val count = acc[cell * 4]
            if (total[cell] == 0 || count == 0f) continue
            sig[cell * 4] = count / total[cell]
            for (k in 1..3) sig[cell * 4 + k] = acc[cell * 4 + k] / count
        }
        return sig
    }

    /**
     * Mean over cells of `|shareA − shareB| + min(shareA, shareB) × min(1, ΔE / MAX_DELTA_E)`: silhouette
     * difference plus colour difference where both have ink. 0 = same picture, 1 = disjoint.
     */
    private fun signatureDistance(a: FloatArray, b: FloatArray): Float {
        val cells = a.size / 4
        var sum = 0f
        for (cell in 0 until cells) {
            val i = cell * 4
            val sa = a[i]; val sb = b[i]
            var d = abs(sa - sb)
            if (sa > 0f && sb > 0f) {
                val de = Lab(a[i + 1], a[i + 2], a[i + 3]).deltaE(Lab(b[i + 1], b[i + 2], b[i + 3]))
                d += min(sa, sb) * min(1f, de / StyleGate.MAX_DELTA_E)
            }
            sum += d
        }
        return sum / cells
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
