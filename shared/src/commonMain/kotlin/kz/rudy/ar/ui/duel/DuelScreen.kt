package kz.rudy.ar.ui.duel

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kz.rudy.ar.core.duel.MatchPhase
import kz.rudy.ar.core.session.DuelReport
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.PausedOverlay
import kz.rudy.ar.ui.components.ScanOverlay
import kz.rudy.ar.ui.components.SecondaryButton
import kz.rudy.ar.ui.screens.BundledCharacter
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusSpacing

/**
 * T59: the duel over Play, by [DuelUi.step]. Stateless over [ui] and [report]; composes T49's four composables as they are.
 * - PICK: [OpponentPicker] (its own Paper screen) over [opponents]; Play and Watch call [onStart].
 * - VERSUS: a Paper screen with [VersusCard] of [mine] and [foe]; [onVersusDone] after [DuelFlow.VERSUS_MS] or a tap.
 * - FIGHT: [DuelHud] of the engine's [report] (nothing until the first one arrives), the scan arc, the Paused scrim, and
 *   [DuelResult] once the match is over. Between rounds (COUNTDOWN, POINT) [onRescan], when given, backs a Rescan button
 *   centred just above the HUD's buttons while no scan runs; App holds the countdown while the scan runs.
 * PICK and VERSUS take every touch, so nothing reaches the AR view under them; the HUD's empty areas let a tap through
 * to the table. [report] is read here only: a report (up to ten a second) recomposes this screen, never App.
 */
@Composable
fun DuelScreen(
    ui: DuelUi,
    report: State<DuelReport?>,
    mine: BundledCharacter,
    mineId: String,
    opponents: List<BundledCharacter>,
    foe: BundledCharacter?,
    nameOf: (String) -> String,
    paused: Boolean,
    pausedHint: StringId,
    scanning: Boolean,
    scanProgress: Float,
    onPick: (String) -> Unit,
    onDifficulty: (DuelDifficulty) -> Unit,
    onStart: (watch: Boolean) -> Unit,
    onVersusDone: () -> Unit,
    onDash: () -> Unit,
    onJump: () -> Unit,
    onSpecial: () -> Unit,
    onRescan: (() -> Unit)?,
    onRematch: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (ui.step) {
        DuelStep.PICK -> OpponentPicker(
            characters = opponents,
            mineId = mineId,
            selectedId = ui.foeId,
            difficulty = ui.difficulty,
            onPick = onPick,
            onDifficulty = onDifficulty,
            onStart = { onStart(false) },
            onWatch = { onStart(true) },
            modifier = modifier.takeAllTouches(),
        )
        DuelStep.VERSUS -> Versus(mine, foe, onVersusDone, modifier)
        DuelStep.FIGHT -> Fight(
            report, mineId, ui.watch, nameOf, paused, pausedHint, scanning, scanProgress,
            onDash, onJump, onSpecial, onRescan, onRematch, onDone, modifier,
        )
    }
}

/** The VS card on Paper, centred; gone after [DuelFlow.VERSUS_MS] or at a tap anywhere (both may fire: App takes the first). */
@Composable
private fun Versus(mine: BundledCharacter, foe: BundledCharacter?, onDone: () -> Unit, modifier: Modifier) {
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        delay(DuelFlow.VERSUS_MS)
        done()
    }
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { done() } }
            .background(RudeusColors.Paper)
            .safeDrawingPadding()
            .padding(horizontal = RudeusSpacing.screenEdge, vertical = RudeusSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        if (foe != null) VersusCard(mine, foe)
    }
}

/** The match on the table: its own recompose scope, so only this reads the report. */
@Composable
private fun Fight(
    report: State<DuelReport?>,
    mineId: String,
    watch: Boolean,
    nameOf: (String) -> String,
    paused: Boolean,
    pausedHint: StringId,
    scanning: Boolean,
    scanProgress: Float,
    onDash: () -> Unit,
    onJump: () -> Unit,
    onSpecial: () -> Unit,
    onRescan: (() -> Unit)?,
    onRematch: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    val model = DuelFlow.hud(report.value, mineId, watch, nameOf)
    Box(modifier.fillMaxSize()) {
        if (model != null) DuelHud(model, onDash = onDash, onJump = onJump, onSpecial = onSpecial)
        ScanOverlay(active = scanning, progress = scanProgress)
        val betweenRounds = model != null && (model.phase == MatchPhase.COUNTDOWN || model.phase == MatchPhase.POINT)
        if (onRescan != null && betweenRounds && !scanning) {
            // The HUD's insets and padding, lifted by its 64 dp button row: just above the buttons, below the banner's band.
            Box(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = RudeusSpacing.md, vertical = RudeusSpacing.smd)
                    .padding(bottom = RudeusSpacing.primaryButton + RudeusSpacing.smd),
                contentAlignment = Alignment.BottomCenter,
            ) {
                SecondaryButton(t(StringId.rescan), onRescan, glyph = Glyph.EYE)
            }
        }
        PausedOverlay(visible = paused, hint = pausedHint)
        if (model != null && model.phase == MatchPhase.RESULT) DuelResult(model, onRematch = onRematch, onDone = onDone)
    }
}

/** A full-screen paper step is the whole screen: no tap falls through to the AR view composed under it. */
private fun Modifier.takeAllTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}
