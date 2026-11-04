package kz.rudy.ar

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.CharacterSize
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.emote.PoseFrames

/** How one scan request ended. */
sealed interface ScanAnswer {
    /**
     * A new level is in: [objects] objects on it (0 = an empty table is still a level with a boundary). [missed] = a
     * real object was seen too steeply and is not in the level, so the character would walk through it.
     */
    data class Found(val objects: Int, val missed: Boolean = false) : ScanAnswer

    /** No frame, network, missing key, timeout. The old level stays. */
    data object Failed : ScanAnswer

    /** Seen from too far above to trust the geometry (`ScanAngle`). The old level stays; another angle is the fix. */
    data object TooSteep : ScanAnswer
}

/**
 * T31: one character the host puts on the table. [id] is the saved id, the bundled ref, or "photo-<ref>" for a fresh
 * pipeline result. [name] is already in the player's language. [frames] (T33): the drawn pose frames its [emotes]
 * are keyed for; the host shows them on the quad while an emote key holds one, and the base sprite otherwise.
 */
data class ArCharacter(
    val id: String,
    val name: String,
    val spritePng: ByteArray?,
    val ability: Ability,
    val emotes: EmoteSet,
    val frames: PoseFrames = PoseFrames.EMPTY,
) {
    // ByteArray is compared by identity; like the slot, a plain carrier.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** T31: what one [CharacterCommand] asks of one character. */
sealed interface CharacterAction {
    data object Jump : CharacterAction
    data object WalkForward : CharacterAction
    data class Emote(val emote: kz.rudy.ar.core.emote.Emote) : CharacterAction
}

/** T31: edge-triggered on [serial] (strictly rising within a session; the host applies each new serial once). */
data class CharacterCommand(val serial: Int, val id: String, val action: CharacterAction)

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
    /** The Settings sheet's Sounds toggle: the host plays character sounds only while true. Events fire either way. */
    val soundsOn: Boolean = true,
    /**
     * T36: background scans. True (default) lets the host scan on its own ONLY after the first button scan of the session,
     * when the camera first settles in a sector around the anchor it has not scanned yet (`AutoScanPolicy`: <= 4 per
     * session, >= 8 s apart, never steeper than 75°, never while another scan runs). A background scan shows no overlay and
     * never calls [onScanned]; [onLevel] still fires. False = today's behaviour: the button only. The HUD switch is the
     * player's privacy switch: frames leave the phone only when they press Scan.
     */
    val autoScan: Boolean = true,
    /** True once a settled horizontal plane exists and a tap would place the character; false when none does, or while the host already holds an early tap for one. */
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
     * while a scan is still running joins it; one before the anchor exists is answered with [ScanAnswer.Failed].
     */
    val scanRequest: Int = 0,
    /** The scan finished. Exactly one call per [scanRequest]. */
    val onScanned: (ScanAnswer) -> Unit = {},
    /** The level now driving physics (after every successful scan and every tap that grows its boundary); the HUD's FootprintMiniMap draws it. */
    val onLevel: (Level) -> Unit = {},
    /**
     * T20: the character's ability from its sheet. The host sets `CharacterState.ability` before the anchor and again
     * when it changes (a new character), so physics knows how the character moves (ROLL rolls fast, FLY hovers).
     */
    val ability: Ability = Ability.JUMP,
    /**
     * T20: the Play HUD's Jump / Fly button. Each increment = one `Physics.jump` on the host (physics ignores it while
     * airborne and for ROLL). 0 = none yet; like [scanRequest] it resets to 0 when the AR session ends.
     */
    val jumpRequest: Int = 0,
    /**
     * T20 debug (footprints on): each increment replays `Scenarios.DAY5` from spawn (the body is put back at `level.spawn`, IDLE, before the first step; the clock is simulated physics time).
     * 0 = none yet; resets to 0 with the session like [scanRequest].
     */
    val scenarioRequest: Int = 0,
    /**
     * T24: the voice command "go". Each increment = walk 10 cm x `SurfaceProfile.scale` along the character's
     * `facing` (skipped when the target is outside the scanned boundary). 0 = none yet; resets with [scanRequest].
     */
    val walkForwardRequest: Int = 0,
    /**
     * T25: the Play HUD's Bigger / Smaller choice, a [CharacterSize] step on top of the surface's character. State, not
     * a request: the host applies it to the billboard, the collision circle and the walking speed whenever it changes.
     */
    val characterSize: Float = CharacterSize.DEFAULT,
    /**
     * T31: the cast in order (at most `Cast.MAX`); the first is the one Play started with. The legacy single-character
     * fields above ([spritePng], [ability], [jumpRequest], [walkForwardRequest]) mirror the [activeId] member, so a
     * v1.0 host keeps working; a T32 host reads these and ignores those. A member added before the anchor exists is
     * placed by the host on the frame the anchor appears.
     */
    val characters: List<ArCharacter> = emptyList(),
    /** T31: whose taps and buttons; the first member until a chip or a finger ([onActiveChanged]) picks another. */
    val activeId: String? = null,
    /** T31: host -> UI: a finger landed on this character; the HUD makes it the active one. */
    val onActiveChanged: (String) -> Unit = {},
    /** T31: the last command (null when nothing is pending); resets to null when the AR session ends. */
    val command: CharacterCommand? = null,
    /** T31: physics transitions per member (id, event). A T32 host calls this and NEVER [onCharacterEvent]. */
    val onCastEvent: (String, CharacterEvent) -> Unit = { _, _ -> },
    /**
     * Every member's physics state (id -> state, plane coordinates), reported by the host from its frame loop while
     * [debug] is on, a few times a second (never per frame): the HUD's FootprintMiniMap moves the dots with it.
     * Empty when nobody stands on the table yet.
     */
    val onCastState: (Map<String, CharacterState>) -> Unit = {},
) {
    // ByteArray is compared by identity; the slot is a plain carrier, so structural equality is not needed.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
