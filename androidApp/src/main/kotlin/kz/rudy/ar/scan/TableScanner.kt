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

    /**
     * A successful scan for the Day 4 gate: `MetricsLog.recordScan(objects, ms, withinCm = [])`, then the run file.
     * HAND-REVIEW: T37 — [background] = a quiet scan the host started itself (AutoScanPolicy), not the button; the run
     * file marks it so README figures stay over manual scans.
     */
    suspend fun recordScan(objects: Int, ms: Long, background: Boolean = false)
}

/** SAM prompts per surface (ai/build-plan.md Day 4; the floor adds the brief's four). Specific labels first. */
object ScanPrompts {
    // HAND-REVIEW: prompt list — SAM finds only what is named, and "object" matched nothing in 7/7 live scans, so it
    // is replaced by "toy" (ai/day4-human.md decision 2) plus common table things. Unlisted labels become obstacles
    // (Semantics.kindFor). Floor stays at 11 prompts (table 12): each 6 prompts add a round at samConcurrency = 6 (scan gate < 6 s).
    private val COMMON = listOf("cup", "book", "phone", "bottle", "hand", "toy")
    // HAND-REVIEW: Day 10 probe (2026-11-04, docs/api-samples/sam-table/table3-day10): "object" found nothing again, but
    // "small object" found the speaker and the bread no named prompt had, without duplicating the named ones ("item" also
    // pulled the sofa behind the table). It is 12th so the table stays at two rounds; ScanMasks.GENERIC_LABELS knows it.
    val TABLE: List<String> = COMMON + listOf("laptop", "remote", "plate", "bowl", "keyboard", "small object")
    val FLOOR: List<String> = COMMON + listOf("box", "shoe", "bag", "ball", "small object")

    fun of(surface: Surface): List<String> = when (surface) {
        Surface.TABLE -> TABLE
        Surface.FLOOR -> FLOOR
    }
}
