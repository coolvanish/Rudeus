package kz.rudy.ar.core.meta

/**
 * Receives the measured cost of one real model call, computed from the reply's `usage` block and the published
 * prices. [job] is a [Jobs] name ([Jobs.SAM_CUTOUT] for every SAM call made by the character pipeline). May be
 * called from any thread; implementations must be safe for concurrent calls.
 */
fun interface CostMeter {
    fun record(job: String, ref: String, usd: Double)
}
