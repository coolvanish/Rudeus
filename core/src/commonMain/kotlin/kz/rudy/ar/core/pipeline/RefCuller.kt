package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.io.RasterImage

/** One style-reference candidate: a stable file name and its decoded pixels. */
data class RefCandidate(val name: String, val image: RasterImage)

/** Outcome of [RefCuller.cull]: names kept (best first) and why each other candidate was dropped. */
data class CullResult(val kept: List<String>, val dropped: List<Dropped>) {
    data class Dropped(val name: String, val reason: String)
}

/**
 * Picks the [keep] most mutually consistent style references from a larger candidate set (Day 2: "8 culled
 * style refs"). Deterministic: same candidates in, same names out.
 *
 * CONTRACT STUB (Day 2 freeze): keeps the first [keep] names in sorted order. T09 replaces the body.
 */
object RefCuller {
    fun cull(candidates: List<RefCandidate>, keep: Int = 8): CullResult {
        require(keep > 0) { "keep must be positive" }
        val sorted = candidates.sortedBy { it.name }
        return CullResult(
            kept = sorted.take(keep).map { it.name },
            dropped = sorted.drop(keep).map { CullResult.Dropped(it.name, "stub: beyond keep=$keep") },
        )
    }
}
