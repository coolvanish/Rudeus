package kz.rudy.ar.scan

import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.meta.SegmentResult

// HAND-REVIEW: T04 — the host-only seam between the AR view and the network. :shared's ArSlot only carries the
// scan request and the answer (count / level); the SAM call and the metrics stay in :androidApp (AndroidCaptureHost
// owns MetaApi + MetricsLog and implements this). MainActivity hands the same host object to RudeusArView.

/** What the AR view needs from the app host to turn one camera frame into a level. */
interface TableScanner {
    /** `MetaModels.segment` on the scan JPEG: one mask per prompt, instances per detection (T15). Throws on failure. */
    suspend fun segment(ref: String, jpeg: ByteArray, prompts: List<String>): SegmentResult

    /** A successful scan for the Day 4 gate: `MetricsLog.recordScan(objects, ms, withinCm = [])`, then the run file. */
    suspend fun recordScan(objects: Int, ms: Long)
}

/** SAM prompts per surface (ai/build-plan.md Day 4; the floor adds the brief's four). Specific labels first. */
object ScanPrompts {
    val TABLE: List<String> = listOf("cup", "book", "phone", "bottle", "hand", "object")
    val FLOOR: List<String> = TABLE + listOf("box", "shoe", "bag", "ball")

    fun of(surface: Surface): List<String> = when (surface) {
        Surface.TABLE -> TABLE
        Surface.FLOOR -> FLOOR
    }
}
