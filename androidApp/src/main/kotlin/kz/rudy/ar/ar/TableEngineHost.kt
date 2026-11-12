package kz.rudy.ar.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kz.rudy.ar.ArCharacter
import kz.rudy.ar.ArSlot
import kz.rudy.ar.core.emote.EmoteSound

// HAND-REVIEW: T56 — host-local copy for one brief: shared/.../TableEngineHost.kt made by sed (package, `internal`), plus
// the four new slot fields, the two Pixel numbers and the scenario effect. Deleted in commit C for the :shared file.
// T53 — moved here from :androidApp (ar/TableEngineHost.kt) as it was: the Compose half of the table engine, so a host
// on any platform turns ArSlot edges into engine calls the same way. The four LaunchedEffects the Android AR view had
// (size, active id, abilities, command) keep their keys; they feed a TableEngine remembered without keys, so a new
// ArSlot never recreates it and it dies with the AR view's composition. Not tested (Compose); its proof is the move.
// T56 — a fifth effect, the scenario request, moved here from RudeusArView with its key. The slot's size, debug flag and
// its tracking and cast-state callbacks reach the engine through [toTableSlot]; the two Pixel numbers come in from the host.

/** [members] with the slot's command, active id, sounds flag, size, debug flag and its four callbacks, as the engine reads them. */
internal fun ArSlot.toTableSlot(members: List<ArCharacter>): TableSlot = TableSlot(
    members = members.map { Member(it.id, it.ability, it.emotes) },
    activeId = activeId,
    command = command,
    soundsOn = soundsOn,
    onAnchorPlaced = onAnchorPlaced,
    onCastEvent = onCastEvent,
    characterSize = characterSize,
    debug = debug,
    onTracking = onTracking,
    onCastState = onCastState,
)

/**
 * The engine of one AR session and the five effects that feed it. Call it once, before anything reads the surface.
 * [members] = who the host renders (`slot.characters`; the Android host still has its v1.0 legacy member).
 * [feetDepthBias] and [reappearAfterTrackingMs] are the host's Pixel numbers (FEET_DEPTH_BIAS, REAPPEAR_AFTER_TRACKING_MS).
 * Compose decides when (the effect keys), the engine decides what.
 */
@Composable
internal fun rememberTableEngine(
    slot: ArSlot,
    members: List<ArCharacter>,
    playEmoteSound: (EmoteSound) -> Unit,
    onMemberRemoved: (String) -> Unit,
    log: (String) -> Unit,
    feetDepthBias: Float,
    reappearAfterTrackingMs: Long,
    separate: Boolean = true,
): TableEngine {
    val view = remember(slot, members) { slot.toTableSlot(members) }
    val currentView by rememberUpdatedState(view)
    val currentPlay by rememberUpdatedState(playEmoteSound)
    val currentRemoved by rememberUpdatedState(onMemberRemoved)
    val currentLog by rememberUpdatedState(log)
    val game = remember {
        TableEngine(
            current = { currentView },
            surface = slot.surface,
            playEmoteSound = { currentPlay(it) },
            onMemberRemoved = { currentRemoved(it) },
            log = { currentLog(it) },
            feetDepthBias = feetDepthBias,
            reappearAfterTrackingMs = reappearAfterTrackingMs,
            separate = separate,
        )
    }
    game.offerSurface(slot.surface)
    // The effects keep the keys they had in RudeusArView, so "once per change" is Compose's, as before. The command
    // effect hands over the slot of its own composition: an emote uses the members and the sounds flag of the slot
    // that sent it.
    LaunchedEffect(game.surface, slot.characterSize) { game.configure(slot.characterSize) }
    LaunchedEffect(slot.activeId) { game.activeOffered(slot.activeId) }
    LaunchedEffect(members) { game.abilities(view.members) }
    LaunchedEffect(slot.command?.serial) { game.command(view) }
    LaunchedEffect(slot.scenarioRequest) { game.scenario(slot.scenarioRequest) }
    return game
}
