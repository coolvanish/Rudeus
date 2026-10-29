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
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.BannerKind
import kz.rudy.ar.ui.components.Chip
import kz.rudy.ar.ui.components.GhostButton
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.PausedOverlay
import kz.rudy.ar.ui.components.PrimaryButton
import kz.rudy.ar.ui.components.ScanOverlay
import kz.rudy.ar.ui.components.ScrimPill
import kz.rudy.ar.ui.components.SpeechBubble
import kz.rudy.ar.ui.components.StatusBanner
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusSpacing

enum class ScanStatus { IDLE, SCANNING, NO_PLANE, DONE, PAUSED }

/** Plain state for the Scanning screen. [progress] is 0..1 over the real scan duration (3-5 s). */
data class ScanState(
    val status: ScanStatus = ScanStatus.IDLE,
    val progress: Float = 0f,
    val objectsFound: Int = 0,
    /** Consecutive "no plane" results; the Skip (2D fallback) button appears from the second one. */
    val failures: Int = 0,
    /** The AR host has a settled horizontal plane: a tap would place the character (`ArSlot.onSurfaceReady`). */
    val surfaceReady: Boolean = false,
    /** The session anchor exists and the character stands on the surface (`ArSlot.onAnchorPlaced`). */
    val anchored: Boolean = false,
    /** Table or floor/outdoors; chosen with the chips until the anchor is placed. */
    val surface: Surface = Surface.TABLE,
)

/**
 * Scanning: the AR view (composed once by App underneath, so [arView] is normally a no-op here) fills the screen.
 * Flow: look at the surface -> tap it to place the character -> one explicit Scan button -> "Table ready!".
 * Scan is never per-frame (CLAUDE.md) and stays disabled until the anchor exists.
 */
@Composable
fun ScanningScreen(
    state: ScanState,
    arView: @Composable (Modifier) -> Unit,
    onScan: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSurfaceChange: (Surface) -> Unit = {},
    /** The character's current line (placed, blocked, ...); null hides the bubble. */
    speech: String? = null,
) {
    val floor = state.surface == Surface.FLOOR
    Box(modifier.fillMaxSize()) {
        arView(Modifier.fillMaxSize())
        ScanOverlay(active = state.status == ScanStatus.SCANNING, progress = state.progress)
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(RudeusSpacing.md)) {
            if (!state.anchored) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.sm, Alignment.CenterHorizontally)) {
                    SurfaceChip(StringId.surface_table, selected = !floor, onClick = { onSurfaceChange(Surface.TABLE) })
                    SurfaceChip(StringId.surface_floor, selected = floor, onClick = { onSurfaceChange(Surface.FLOOR) })
                }
                Spacer(Modifier.height(RudeusSpacing.sm))
            }
            if (state.status == ScanStatus.IDLE && !state.anchored) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!state.surfaceReady) {
                        ScrimPill(t(if (floor) StringId.scan_title_floor else StringId.scan_title))
                        Spacer(Modifier.height(RudeusSpacing.sm))
                        ScrimPill(t(StringId.scan_hint))
                    } else {
                        ScrimPill(t(if (floor) StringId.scan_tap_to_place_floor else StringId.scan_tap_to_place))
                    }
                }
            }
            StatusBanner(
                visible = state.status == ScanStatus.NO_PLANE,
                title = t(if (floor) StringId.scan_no_plane_title_floor else StringId.scan_no_plane_title),
                hint = t(if (floor) StringId.scan_no_plane_hint_floor else StringId.scan_no_plane_hint),
                kind = BannerKind.ERROR,
                glyph = Glyph.CROSS,
            )
            StatusBanner(
                visible = state.status == ScanStatus.DONE,
                title = t(if (floor) StringId.scan_done_floor else StringId.scan_done),
                // Honest: no count until the real scan (T04) finds objects.
                hint = if (state.objectsFound > 0) "${t(StringId.scan_objects_found)}: ${state.objectsFound}" else null,
                kind = BannerKind.SUCCESS,
                glyph = Glyph.CHECK,
            )
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                SpeechBubble(text = speech)
            }
            Spacer(Modifier.height(RudeusSpacing.md))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                GhostButton(t(StringId.back), onClick = onBack, glyph = Glyph.BACK)
                Spacer(Modifier.width(RudeusSpacing.md))
                PrimaryButton(
                    text = t(StringId.scan_button),
                    glyph = Glyph.EYE,
                    enabled = state.anchored && (state.status == ScanStatus.IDLE || state.status == ScanStatus.NO_PLANE),
                    onClick = onScan,
                    modifier = Modifier.weight(1f),
                )
                if (state.failures >= 2) {
                    Spacer(Modifier.width(RudeusSpacing.md))
                    GhostButton(t(StringId.skip), onClick = onSkip)
                }
            }
        }
        PausedOverlay(
            visible = state.status == ScanStatus.PAUSED,
            hint = if (floor) StringId.paused_hint_floor else StringId.paused_hint,
        )
    }
}

/** Table | Floor choice: paper chips, the selected one outlined in Sky (Sun stays the Scan button's). */
@Composable
private fun SurfaceChip(label: StringId, selected: Boolean, onClick: () -> Unit) {
    Chip(
        text = t(label),
        glyph = if (selected) Glyph.CHECK else null,
        accent = if (selected) RudeusColors.Sky else RudeusColors.Ink,
        container = if (selected) RudeusColors.Paper else RudeusColors.PaperDeep,
        onClick = onClick,
    )
}
