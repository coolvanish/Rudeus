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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.BannerKind
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.PausedOverlay
import kz.rudy.ar.ui.components.Pill
import kz.rudy.ar.ui.components.PrimaryButton
import kz.rudy.ar.ui.components.ScanOverlay
import kz.rudy.ar.ui.components.SecondaryButton
import kz.rudy.ar.ui.components.SegmentedControl
import kz.rudy.ar.ui.components.SpeechBubble
import kz.rudy.ar.ui.components.StatusBanner
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.RudeusSpacing

/** [NO_PLANE] = the scan failed (no table found); [EMPTY] = the table was found but nothing is on it. */
enum class ScanStatus { IDLE, SCANNING, NO_PLANE, EMPTY, DONE, PAUSED }

/** Plain state for the Scanning screen. [progress] is 0..1 over the real scan duration (3-5 s). */
data class ScanState(
    val status: ScanStatus = ScanStatus.IDLE,
    val progress: Float = 0f,
    val objectsFound: Int = 0,
    /** Consecutive "no plane" results; Skip (2D fallback) appears in the banner from the second one. An EMPTY table is not a failure. */
    val failures: Int = 0,
    /** The AR host has a settled horizontal plane: a tap would place the character (`ArSlot.onSurfaceReady`). */
    val surfaceReady: Boolean = false,
    /** The session anchor exists and the character stands on the surface (`ArSlot.onAnchorPlaced`). */
    val anchored: Boolean = false,
    /** Table or floor/outdoors; chosen with the segmented control until the anchor is placed. */
    val surface: Surface = Surface.TABLE,
    /** The host's last model call failed on the network: the NO_PLANE card names the network, not the table. */
    val offline: Boolean = false,
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
        if (state.status != ScanStatus.PAUSED) Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = RudeusSpacing.md, vertical = RudeusSpacing.smd)) {
            if (!state.anchored) {
                SegmentedControl(
                    options = listOf(Surface.TABLE, Surface.FLOOR),
                    selected = state.surface,
                    onSelect = onSurfaceChange,
                    label = { t(if (it == Surface.FLOOR) StringId.surface_floor else StringId.surface_table) },
                    check = true,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(14.dp))
            }
            if (state.status == ScanStatus.IDLE && !state.anchored) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(RudeusSpacing.sm),
                ) {
                    if (!state.surfaceReady) {
                        Pill(t(if (floor) StringId.scan_title_floor else StringId.scan_title))
                        Pill(t(StringId.scan_hint), quiet = true)
                    } else {
                        Pill(t(if (floor) StringId.scan_tap_to_place_floor else StringId.scan_tap_to_place))
                    }
                }
            }
            StatusBanner(
                visible = state.status == ScanStatus.NO_PLANE,
                title = t(if (state.offline) StringId.scan_offline_title else if (floor) StringId.scan_no_plane_title_floor else StringId.scan_no_plane_title),
                hint = t(if (state.offline) StringId.processing_failed_hint else if (floor) StringId.scan_no_plane_hint_floor else StringId.scan_no_plane_hint),
                kind = BannerKind.ERROR,
                // Skip only after two failures: a first miss is worth another try.
                action = if (state.failures >= 2) ({ SecondaryButton(t(StringId.skip), onClick = onSkip, compact = true) }) else null,
            )
            StatusBanner(
                visible = state.status == ScanStatus.EMPTY,
                title = t(if (floor) StringId.scan_empty_title_floor else StringId.scan_empty_title),
                hint = t(if (floor) StringId.scan_empty_hint_floor else StringId.scan_empty_hint),
                kind = BannerKind.INFO,
                // The table is real, so "Play anyway" is offered at once.
                action = { SecondaryButton(t(StringId.scan_play_anyway), onClick = onSkip, compact = true) },
            )
            StatusBanner(
                visible = state.status == ScanStatus.DONE,
                title = t(if (floor) StringId.scan_done_floor else StringId.scan_done),
                // Honest: no count until the real scan (T04) finds objects.
                hint = if (state.objectsFound > 0) "${t(StringId.scan_objects_found)}: ${state.objectsFound}" else null,
                kind = BannerKind.SUCCESS,
            )
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                SpeechBubble(text = speech)
            }
            if (state.status == ScanStatus.SCANNING) {
                Spacer(Modifier.height(RudeusSpacing.sm))
                Pill(t(StringId.scanning), Modifier.align(Alignment.CenterHorizontally))
            }
            Spacer(Modifier.height(RudeusSpacing.md))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.smd),
            ) {
                SecondaryButton(t(StringId.back), onClick = onBack, glyph = Glyph.BACK)
                PrimaryButton(
                    text = t(StringId.scan_button),
                    glyph = Glyph.EYE,
                    enabled = state.anchored && (state.status == ScanStatus.IDLE || state.status == ScanStatus.NO_PLANE || state.status == ScanStatus.EMPTY),
                    onClick = onScan,
                    modifier = Modifier.weight(1f),
                )
                // No banner to hold Skip (tracking came back, or the next scan is running): it must not vanish after two failures.
                if (state.failures >= 2 && state.status != ScanStatus.NO_PLANE && state.status != ScanStatus.EMPTY) {
                    SecondaryButton(t(StringId.skip), onClick = onSkip)
                }
            }
        }
        PausedOverlay(
            visible = state.status == ScanStatus.PAUSED,
            hint = if (floor) StringId.paused_hint_floor else StringId.paused_hint,
        )
    }
}
