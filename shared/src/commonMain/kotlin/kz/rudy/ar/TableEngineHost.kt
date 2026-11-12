package kz.rudy.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kz.rudy.ar.core.emote.EmoteSound
import kz.rudy.ar.core.session.Member
import kz.rudy.ar.core.session.TableEngine
import kz.rudy.ar.core.session.TableSlot

// T53 — moved here from :androidApp (ar/TableEngineHost.kt) as it was: the Compose half of the table engine, so a host
// on any platform turns ArSlot edges into engine calls the same way. The four LaunchedEffects the Android AR view had
// (size, active id, abilities, command) keep their keys; they feed a TableEngine remembered without keys, so a new
// ArSlot never recreates it and it dies with the AR view's composition. Not tested (Compose); its proof is the move.

/** [members] with the slot's command, active id, sounds flag and its two callbacks, as the engine reads them. */
fun ArSlot.toTableSlot(members: List<ArCharacter>): TableSlot = TableSlot(
    members = members.map { Member(it.id, it.ability, it.emotes) },
    activeId = activeId,
    command = command,
    soundsOn = soundsOn,
    onAnchorPlaced = onAnchorPlaced,
    onCastEvent = onCastEvent,
)

/**
 * The engine of one AR session and the four effects that feed it. Call it once, before anything reads the surface.
 * [members] = who the host renders (`slot.characters`; the Android host still has its v1.0 legacy member).
 * Compose decides when (the effect keys), the engine decides what.
 */
@Composable
fun rememberTableEngine(
    slot: ArSlot,
    members: List<ArCharacter>,
    playEmoteSound: (EmoteSound) -> Unit,
    onMemberRemoved: (String) -> Unit,
    log: (String) -> Unit,
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
    return game
}
