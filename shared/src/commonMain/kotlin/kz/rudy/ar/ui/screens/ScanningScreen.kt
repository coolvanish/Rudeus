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
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.BannerKind
import kz.rudy.ar.ui.components.GhostButton
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.PausedOverlay
import kz.rudy.ar.ui.components.PrimaryButton
import kz.rudy.ar.ui.components.ScanOverlay
import kz.rudy.ar.ui.components.ScrimPill
import kz.rudy.ar.ui.components.StatusBanner
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.RudeusSpacing

enum class ScanStatus { IDLE, SCANNING, NO_PLANE, DONE, PAUSED }

/** Plain state for the Scanning screen. [progress] is 0..1 over the real scan duration (3-5 s). */
data class ScanState(
    val status: ScanStatus = ScanStatus.IDLE,
    val progress: Float = 0f,
    val objectsFound: Int = 0,
    /** Consecutive "no plane" results; the Skip (2D fallback) button appears from the second one. */
    val failures: Int = 0,
)

/**
 * Scanning: the AR view fills the screen; one explicit Scan button; the looking-around overlay while scanning.
 * Scan is never per-frame (CLAUDE.md).
 */
@Composable
fun ScanningScreen(
    state: ScanState,
    arView: @Composable (Modifier) -> Unit,
    onScan: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        arView(Modifier.fillMaxSize())
        ScanOverlay(active = state.status == ScanStatus.SCANNING, progress = state.progress)
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(RudeusSpacing.md)) {
            if (state.status == ScanStatus.IDLE) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ScrimPill(t(StringId.scan_title))
                    Spacer(Modifier.height(RudeusSpacing.sm))
                    ScrimPill(t(StringId.scan_hint))
                }
            }
            StatusBanner(
                visible = state.status == ScanStatus.NO_PLANE,
                title = t(StringId.scan_no_plane_title),
                hint = t(StringId.scan_no_plane_hint),
                kind = BannerKind.ERROR,
                glyph = Glyph.CROSS,
            )
            StatusBanner(
                visible = state.status == ScanStatus.DONE,
                title = t(StringId.scan_done),
                hint = "${t(StringId.scan_objects_found)}: ${state.objectsFound}",
                kind = BannerKind.SUCCESS,
                glyph = Glyph.CHECK,
            )
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                GhostButton(t(StringId.back), onClick = onBack, glyph = Glyph.BACK)
                Spacer(Modifier.width(RudeusSpacing.md))
                PrimaryButton(
                    text = t(StringId.scan_button),
                    glyph = Glyph.EYE,
                    enabled = state.status == ScanStatus.IDLE || state.status == ScanStatus.NO_PLANE,
                    onClick = onScan,
                    modifier = Modifier.weight(1f),
                )
                if (state.failures >= 2) {
                    Spacer(Modifier.width(RudeusSpacing.md))
                    GhostButton(t(StringId.skip), onClick = onSkip)
                }
            }
        }
        PausedOverlay(visible = state.status == ScanStatus.PAUSED)
    }
}
