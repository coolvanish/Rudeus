package kz.rudy.ar

import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Surface

/**
 * Everything the shared UI hands to the AR view slot and everything it wants back. No ARCore/SceneView types:
 * :androidApp implements `arView: @Composable (Modifier, ArSlot) -> Unit` and :shared only reacts through the callbacks.
 *
 * The slot is composed ONCE for the whole Scanning -> Play stretch (App keeps it under both screens), so the ARCore
 * session and the single anchor survive the screen switch. Callbacks may arrive on the main thread from the AR frame
 * loop; implementations must be cheap (a state write) and must not throw.
 */
data class ArSlot(
    /** The character's matted PNG (`CharacterResult.sprite` or a bundled sprite); null = the host's built-in dino. */
    val spritePng: ByteArray?,
    /** Table or floor/outdoors (character size, walk speed, tap tolerance come from `SurfaceProfile.of`). */
    val surface: Surface,
    /** Debug chrome on (footprints toggle): the host may show its own dev controls (e.g. the ARCore Rec button). */
    val debug: Boolean = false,
    /** True once a settled horizontal plane exists and a tap would place the character; false when none does. */
    val onSurfaceReady: (Boolean) -> Unit = {},
    /** The session anchor was created by a tap: the character now stands on the surface. */
    val onAnchorPlaced: () -> Unit = {},
    /** Camera + anchor TRACKING (true) or lost (false). The HUD shows the Paused scrim while false. */
    val onTracking: (Boolean) -> Unit = {},
    /** Physics transitions from `CharacterEvents.between`, once per step; drives speech bubbles. */
    val onCharacterEvent: (CharacterEvent) -> Unit = {},
    /**
     * T04: the explicit Scan button. Each increment asks the host for ONE table scan (never per frame): it grabs the
     * next camera frame, runs segmentation on IO and swaps the new `Level` in. 0 = nothing requested yet. A request
     * while a scan is still running, or before the anchor exists, is answered with `onScanned(null)`.
     */
    val scanRequest: Int = 0,
    /**
     * The scan finished: the number of objects it found (0 = an empty table is still a level with a boundary), or
     * null when it failed (no frame, network, missing key, timeout). Exactly one call per [scanRequest].
     */
    val onScanned: (objects: Int?) -> Unit = {},
    /** The level now driving physics (after every successful scan); the HUD's FootprintMiniMap draws it. */
    val onLevel: (Level) -> Unit = {},
) {
    // ByteArray is compared by identity; the slot is a plain carrier, so structural equality is not needed.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
