package kz.rudy.ar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.AbilityChip
import kz.rudy.ar.ui.components.Chip
import kz.rudy.ar.ui.components.FootprintMiniMap
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.GlyphIcon
import kz.rudy.ar.ui.components.OfflineBadge
import kz.rudy.ar.ui.components.Pill
import kz.rudy.ar.ui.components.PausedOverlay
import kz.rudy.ar.ui.components.PrimaryButton
import kz.rudy.ar.ui.components.ScanOverlay
import kz.rudy.ar.ui.components.SecondaryButton
import kz.rudy.ar.ui.components.glyph
import kz.rudy.ar.ui.components.SpeechBubble
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusSpacing

/** T24 push-to-talk: idle, recording while the mic is held, or transcribing after release. */
enum class VoicePhase { IDLE, LISTENING, THINKING }

/** Everything the HUD shows that is not already in [CharacterState]. */
data class HudState(
    /** Current speech line (already resolved for the language); null hides the bubble. */
    val speech: String? = null,
    val scanning: Boolean = false,
    val scanProgress: Float = 0f,
    val showFootprints: Boolean = false,
    /** The host's last model call failed on the network: offline pill + the offline note above the buttons (Scan stays enabled: a tap is the retry). */
    val offline: Boolean = false,
    /** "Tap - it walks" pill: on after `ArSlot.onAnchorPlaced`, off 5 s after the first STARTED_WALKING. */
    val showTapHint: Boolean = false,
    /** True when the AR host reports tracking lost; the character's Mode.PAUSED also triggers the overlay. */
    val trackingLost: Boolean = false,
    /** A scan has run in this AR session: the bottom button reads "Rescan" instead of "Scan". */
    val scanned: Boolean = false,
    /** Table or floor/outdoors (the Paused hint names it). */
    val surface: Surface = Surface.TABLE,
    /** T24: the mic's phase; LISTENING / THINKING show the "Listening…" / "Thinking…" pill beside it. */
    val voice: VoicePhase = VoicePhase.IDLE,
    /** T24: the bubble on screen is a voice transcript (or "Didn't catch that"): a mic glyph sits beside it. */
    val speechFromVoice: Boolean = false,
)

/**
 * Play: the AR view (slot from :androidApp, composed once by App underneath, so [arView] is normally a no-op here)
 * with the HUD in a Box on top.
 * Stateless: physics lives in :core, rendering in :androidApp; this only draws chrome.
 *
 * [onJump] backs the Sun primary, bottom-right (Jump for JUMP and HIDE, Fly for FLY). ROLL has no ability button, so
 * Scan / Rescan takes the primary's place; otherwise it is the secondary bottom-left. With footprints on (debug) the
 * top-right column adds Metrics and, when [onScenario] is given, "Scenario". There is no on-screen Back: the system
 * back gesture returns to Capture.
 *
 * T24: [onVoiceDown], when given (a `VoiceHost` exists and the character is placed), adds the round hold-to-talk mic
 * under the ability chip: press = [onVoiceDown], release (or the press being cancelled) = [onVoiceUp]. Disabled while
 * offline (the offline pill already says why), while tracking is lost, and while the last phrase is still THINKING.
 */
@Composable
fun PlayScreen(
    arView: @Composable (Modifier) -> Unit,
    character: CharacterState,
    sheet: CharacterSheet,
    level: Level?,
    hud: HudState,
    onScan: () -> Unit,
    onJump: () -> Unit,
    onToggleFootprints: () -> Unit,
    onAbilityTap: () -> Unit,
    onOpenMetrics: () -> Unit,
    modifier: Modifier = Modifier,
    onScenario: (() -> Unit)? = null,
    onVoiceDown: (() -> Unit)? = null,
    onVoiceUp: () -> Unit = {},
) {
    val paused = hud.trackingLost || character.mode == Mode.PAUSED
    Box(modifier.fillMaxSize()) {
        arView(Modifier.fillMaxSize())
        ScanOverlay(active = hud.scanning, progress = hud.scanProgress)
        if (!paused) Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = RudeusSpacing.md, vertical = RudeusSpacing.smd)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AbilityChip(character.ability, onClick = onAbilityTap)
                    if (onVoiceDown != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MicButton(
                                listening = hud.voice == VoicePhase.LISTENING,
                                enabled = !hud.offline && !hud.trackingLost && hud.voice != VoicePhase.THINKING,
                                onDown = onVoiceDown,
                                onUp = onVoiceUp,
                            )
                            val pill = when (hud.voice) {
                                VoicePhase.LISTENING -> StringId.voice_listening
                                VoicePhase.THINKING -> StringId.voice_thinking
                                VoicePhase.IDLE -> null
                            }
                            if (pill != null) {
                                Spacer(Modifier.width(RudeusSpacing.sm))
                                Pill(t(pill))
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (hud.offline) OfflineBadge()
                    Chip(
                        text = t(StringId.footprints),
                        glyph = Glyph.FOOTPRINTS,
                        isOn = hud.showFootprints,
                        onClick = onToggleFootprints,
                    )
                    if (hud.showFootprints) {
                        SecondaryButton(t(StringId.metrics_title), glyph = Glyph.METRICS, onClick = onOpenMetrics)
                        if (onScenario != null) SecondaryButton(t(StringId.play_scenario), onClick = onScenario)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // T24: a voice bubble is prefixed with the mic glyph, so the child sees it heard them.
                    if (hud.speechFromVoice && hud.speech != null) {
                        GlyphIcon(Glyph.MIC, RudeusColors.Sky, Modifier.clip(CircleShape).background(RudeusColors.Paper).padding(RudeusSpacing.xs))
                        Spacer(Modifier.width(RudeusSpacing.xs))
                    }
                    SpeechBubble(text = hud.speech)
                }
            }
            Spacer(Modifier.height(RudeusSpacing.sm))
            if (hud.showFootprints && level != null) {
                FootprintMiniMap(level, character)
                Spacer(Modifier.height(RudeusSpacing.sm))
            }
            // Scan needs the network: the offline note says why a tap may fail, instead of pretending there is no table.
            val hint = when {
                hud.scanning -> StringId.scanning
                hud.offline -> StringId.capture_offline_note
                hud.showTapHint && character.mode == Mode.IDLE -> StringId.play_tap_to_walk
                else -> null
            }
            if (hint != null) {
                Pill(t(hint), Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(14.dp))
            }
            val scanLabel = t(if (hud.scanned) StringId.rescan else StringId.scan_button)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.smd, Alignment.End),
            ) {
                if (character.ability == Ability.ROLL) {
                    PrimaryButton(text = scanLabel, glyph = Glyph.EYE, enabled = !hud.scanning, onClick = onScan, modifier = Modifier.widthIn(min = 156.dp))
                } else {
                    SecondaryButton(text = scanLabel, glyph = Glyph.EYE, enabled = !hud.scanning, onClick = onScan)
                    Spacer(Modifier.weight(1f))
                    PrimaryButton(
                        text = t(if (character.ability == Ability.FLY) StringId.play_fly else StringId.play_jump),
                        glyph = character.ability.glyph(),
                        enabled = !hud.trackingLost,
                        onClick = onJump,
                        modifier = Modifier.widthIn(min = 156.dp),
                    )
                }
            }
        }
        PausedOverlay(
            visible = paused,
            hint = if (hud.surface == Surface.FLOOR) StringId.paused_hint_floor else StringId.paused_hint,
        )
    }
}

/**
 * T24: the round hold-to-talk control: Paper fill, 2 dp Sky stroke and glyph; Sky-filled with a Paper glyph while
 * [listening]. A press calls [onDown]; the release, or the press being cancelled (finger slid off, the
 * control left composition or got disabled), calls [onUp] exactly once.
 */
@Composable
private fun MicButton(listening: Boolean, enabled: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    val label = t(StringId.voice_hold)
    val outline = if (enabled) RudeusColors.Sky else RudeusColors.InkMuted
    val fill = if (listening) RudeusColors.Sky else RudeusColors.Paper
    val tint = when {
        listening -> RudeusColors.Paper
        enabled -> RudeusColors.Sky
        else -> RudeusColors.InkMuted
    }
    Box(
        Modifier
            .size(RudeusSpacing.touchTarget)
            .semantics { contentDescription = label; role = Role.Button }
            .clip(CircleShape)
            .background(fill)
            .border(RudeusSpacing.stroke, outline, CircleShape)
            .then(
                if (enabled) Modifier.pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        down()
                        try {
                            tryAwaitRelease()
                        } finally {
                            up()
                        }
                    })
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(Glyph.MIC, tint, size = 22.dp)
    }
}
