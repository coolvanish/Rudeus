package kz.rudy.ar.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kz.rudy.ar.ui.components.GhostButton
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.OfflineBadge
import kz.rudy.ar.ui.components.PausedOverlay
import kz.rudy.ar.ui.components.PrimaryButton
import kz.rudy.ar.ui.components.ScanOverlay
import kz.rudy.ar.ui.components.ScrimPill
import kz.rudy.ar.ui.components.glyph
import kz.rudy.ar.ui.components.SpeechBubble
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusSpacing

/** Everything the HUD shows that is not already in [CharacterState]. */
data class HudState(
    /** Current speech line (already resolved for the language); null hides the bubble. */
    val speech: String? = null,
    val scanning: Boolean = false,
    val scanProgress: Float = 0f,
    val showFootprints: Boolean = false,
    /** The host's last model call failed on the network: Leaf badge + the offline note above Scan (Scan stays enabled: a tap is the retry). */
    val offline: Boolean = false,
    /** "Tap - it walks" pill: on after `ArSlot.onAnchorPlaced`, off 5 s after the first STARTED_WALKING. */
    val showTapHint: Boolean = false,
    /** True when the AR host reports tracking lost; the character's Mode.PAUSED also triggers the overlay. */
    val trackingLost: Boolean = false,
    /** A scan has run in this AR session: the bottom button reads "Rescan" instead of "Scan". */
    val scanned: Boolean = false,
    /** Table or floor/outdoors (the Paused hint names it). */
    val surface: Surface = Surface.TABLE,
)

/**
 * Play: the AR view (slot from :androidApp, composed once by App underneath, so [arView] is normally a no-op here)
 * with the HUD in a Box on top.
 * Stateless: physics lives in :core, rendering in :androidApp; this only draws chrome.
 *
 * [onJump] backs the Jump / Fly button right of Scan (JUMP, FLY, HIDE; none for ROLL). [onScenario], when given and
 * footprints are on, adds the debug "Scenario" ghost button under Metrics.
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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onScenario: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize()) {
        arView(Modifier.fillMaxSize())
        ScanOverlay(active = hud.scanning, progress = hud.scanProgress)
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(RudeusSpacing.md)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                AbilityChip(character.ability, onClick = onAbilityTap)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(RudeusSpacing.xs)) {
                    if (hud.offline) OfflineBadge()
                    Chip(
                        text = t(StringId.footprints),
                        glyph = Glyph.FOOTPRINTS,
                        accent = if (hud.showFootprints) RudeusColors.Sky else RudeusColors.Ink,
                        onClick = onToggleFootprints,
                    )
                    GhostButton(t(StringId.metrics_button), glyph = Glyph.METRICS, onClick = onOpenMetrics)
                    if (hud.showFootprints && onScenario != null) {
                        GhostButton(t(StringId.play_scenario), onClick = onScenario)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                SpeechBubble(text = hud.speech)
            }
            Spacer(Modifier.height(RudeusSpacing.md))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                if (hud.showFootprints && level != null) {
                    FootprintMiniMap(level, character)
                } else {
                    GhostButton(t(StringId.back), onClick = onBack, glyph = Glyph.BACK)
                }
                Spacer(Modifier.width(RudeusSpacing.md))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (hud.offline) {
                        // Scan needs the network: say why it is disabled instead of pretending there is no table.
                        ScrimPill(t(StringId.capture_offline_note))
                        Spacer(Modifier.height(RudeusSpacing.sm))
                    } else if (hud.showTapHint && character.mode == Mode.IDLE) {
                        ScrimPill(t(StringId.play_tap_to_walk))
                        Spacer(Modifier.height(RudeusSpacing.sm))
                    }
                    PrimaryButton(
                        text = t(if (hud.scanned) StringId.rescan else StringId.scan_button),
                        glyph = Glyph.EYE,
                        enabled = !hud.scanning,
                        onClick = onScan,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // The ability's action: secondary (Paper + Sky), so Scan stays the one Sun button. ROLL has none.
                if (character.ability != Ability.ROLL) {
                    Spacer(Modifier.width(RudeusSpacing.md))
                    GhostButton(
                        text = t(if (character.ability == Ability.FLY) StringId.play_fly else StringId.play_jump),
                        glyph = character.ability.glyph(),
                        accent = RudeusColors.Sky,
                        enabled = !hud.trackingLost,
                        onClick = onJump,
                        modifier = Modifier.height(RudeusSpacing.primaryButton),
                    )
                }
            }
        }
        PausedOverlay(
            visible = hud.trackingLost || character.mode == Mode.PAUSED,
            hint = if (hud.surface == Surface.FLOOR) StringId.paused_hint_floor else StringId.paused_hint,
        )
    }
}
