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
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Mode
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
    val offline: Boolean = false,
    /** "Tap - it walks" pill, shown for the first seconds after placement. */
    val showTapHint: Boolean = true,
    /** True when the AR host reports tracking lost; the character's Mode.PAUSED also triggers the overlay. */
    val trackingLost: Boolean = false,
)

/**
 * Play: the AR view (slot from :androidApp) with the HUD in a Box on top.
 * Stateless: physics lives in :core, rendering in :androidApp; this only draws chrome.
 */
@Composable
fun PlayScreen(
    arView: @Composable (Modifier) -> Unit,
    character: CharacterState,
    sheet: CharacterSheet,
    level: Level?,
    hud: HudState,
    onScan: () -> Unit,
    onToggleFootprints: () -> Unit,
    onAbilityTap: () -> Unit,
    onOpenMetrics: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
                    if (hud.showTapHint && character.mode == Mode.IDLE) {
                        ScrimPill(t(StringId.play_tap_to_walk))
                        Spacer(Modifier.height(RudeusSpacing.sm))
                    }
                    PrimaryButton(
                        text = t(StringId.rescan),
                        glyph = Glyph.EYE,
                        enabled = !hud.scanning,
                        onClick = onScan,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        PausedOverlay(visible = hud.trackingLost || character.mode == Mode.PAUSED)
    }
}
