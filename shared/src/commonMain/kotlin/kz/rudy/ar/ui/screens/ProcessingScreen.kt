package kz.rudy.ar.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.AbilityChip
import kz.rudy.ar.ui.components.GhostButton
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.GlyphIcon
import kz.rudy.ar.ui.components.PrimaryButton
import kz.rudy.ar.ui.components.SpriteImage
import kz.rudy.ar.ui.components.rememberSpriteBitmap
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.LocalReducedMotion
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

/** Pipeline stages in order (mirrors core CharacterPipeline: cutout -> stylize -> gate -> sheet). */
enum class PipelineStage(val label: StringId) {
    CUTOUT(StringId.stage_cutout),
    STYLIZE(StringId.stage_stylize),
    GATE(StringId.stage_gate),
    SHEET(StringId.stage_sheet),
}

enum class ProcessingStatus { RUNNING, GATE_FAILED, ERROR, DONE }

/** Plain progress snapshot for the Processing screen, folded from core's `PipelineEvent`s by [reduce]. */
data class ProcessingState(
    val status: ProcessingStatus = ProcessingStatus.RUNNING,
    /** Stage currently running; null when finished or failed before start. */
    val stage: PipelineStage? = PipelineStage.CUTOUT,
    val completed: Set<PipelineStage> = emptySet(),
    /** Spark vision style score (0..1) once the gate stage ran; shown honestly. */
    val gateScore: Float? = null,
    val gateThreshold: Float = 0.7f,
    val attempt: Int = 1,
    val maxAttempts: Int = 3,
    val sheet: CharacterSheet? = null,
    /** Optional cutout/stylized preview slot drawn by the host. */
    val preview: (@Composable (Modifier) -> Unit)? = null,
) {
    val fraction: Float
        get() = when (status) {
            ProcessingStatus.DONE -> 1f
            else -> completed.size / PipelineStage.entries.size.toFloat() + (if (stage != null) 0.5f / PipelineStage.entries.size else 0f)
        }
}

@Composable
fun ProcessingScreen(
    state: ProcessingState,
    onRetry: () -> Unit,
    onShipAnyway: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The finished character's sprite PNG (`CharacterResult.sprite`); shown at 160 dp once the run is done. */
    sprite: ByteArray? = null,
) {
    val finished = state.status == ProcessingStatus.DONE || state.status == ProcessingStatus.GATE_FAILED
    val spriteBitmap = rememberSpriteBitmap(if (finished) sprite else null)
    val reduced = LocalReducedMotion.current
    val progress by animateFloatAsState(
        targetValue = state.fraction,
        animationSpec = tween(if (reduced) RudeusMotion.REDUCED_MS else RudeusMotion.STAGE_PROGRESS_MS, easing = RudeusMotion.Standard),
        label = "progress",
    )
    Box(modifier.fillMaxSize().background(RudeusColors.Paper)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(RudeusSpacing.md)) {
            Text(
                if (state.status == ProcessingStatus.DONE) t(StringId.processing_done) else t(StringId.processing_title),
                style = MaterialTheme.typography.displayLarge,
                color = RudeusColors.Ink,
            )
            Spacer(Modifier.height(RudeusSpacing.lg))
            // Scrolls on short screens once the sprite, the score and the name are all shown.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                val preview: (@Composable (Modifier) -> Unit)? = spriteBitmap?.let { bmp ->
                    { m -> SpriteImage(bmp, contentDescription = state.sheet?.name, modifier = m.padding(RudeusSpacing.sm)) }
                } ?: state.preview
                preview?.let { content ->
                    val shape = RoundedCornerShape(RudeusRadius.card)
                    Box(
                        Modifier
                            .size(160.dp)
                            .align(Alignment.CenterHorizontally)
                            .clip(shape)
                            .background(RudeusColors.PaperDeep)
                            .border(RudeusSpacing.stroke, RudeusColors.Ink, shape),
                    ) { content(Modifier.fillMaxSize()) }
                    Spacer(Modifier.height(RudeusSpacing.lg))
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(RudeusRadius.full)),
                    color = RudeusColors.Sun,
                    trackColor = RudeusColors.PaperDeep,
                )
                Spacer(Modifier.height(RudeusSpacing.lg))
                PipelineStage.entries.forEach { stage ->
                    StageRow(
                        label = t(stage.label),
                        done = stage in state.completed,
                        active = stage == state.stage && state.status == ProcessingStatus.RUNNING,
                        failed = stage == state.stage && state.status == ProcessingStatus.ERROR,
                    )
                }
                state.gateScore?.let { score ->
                    Spacer(Modifier.height(RudeusSpacing.lg))
                    GateScore(score, passed = score >= state.gateThreshold, attempt = state.attempt, maxAttempts = state.maxAttempts)
                }
                if (state.status == ProcessingStatus.DONE && state.sheet != null) {
                    Spacer(Modifier.height(RudeusSpacing.lg))
                    Text(state.sheet.name, style = MaterialTheme.typography.titleLarge, color = RudeusColors.Ink)
                    Spacer(Modifier.height(RudeusSpacing.sm))
                    AbilityChip(state.sheet.ability)
                }
            }
            Spacer(Modifier.height(RudeusSpacing.md))
            when (state.status) {
                ProcessingStatus.RUNNING -> GhostButton(t(StringId.cancel), onClick = onBack, glyph = Glyph.BACK)
                ProcessingStatus.DONE -> PrimaryButton(t(StringId.next), onClick = onContinue, modifier = Modifier.fillMaxWidth())
                ProcessingStatus.GATE_FAILED -> Column {
                    Text(t(StringId.ship_anyway_note), style = MaterialTheme.typography.bodyLarge, color = RudeusColors.InkSoft)
                    Spacer(Modifier.height(RudeusSpacing.md))
                    PrimaryButton(t(StringId.ship_anyway), onClick = onShipAnyway, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(RudeusSpacing.sm))
                    GhostButton(t(StringId.retry), onClick = onRetry)
                }
                ProcessingStatus.ERROR -> Unit
            }
        }
        if (state.status == ProcessingStatus.ERROR) {
            ErrorSheet(onRetry = onRetry, onBack = onBack, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun StageRow(label: String, done: Boolean, active: Boolean, failed: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(RudeusSpacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            when {
                done -> GlyphIcon(Glyph.CHECK, RudeusColors.Leaf)
                failed -> GlyphIcon(Glyph.CROSS, RudeusColors.Tomato)
                active -> Box(Modifier.size(16.dp).clip(RoundedCornerShape(RudeusRadius.full)).background(RudeusColors.Sun))
                else -> Box(Modifier.size(12.dp).clip(RoundedCornerShape(RudeusRadius.full)).background(RudeusColors.PaperDeep))
            }
        }
        Spacer(Modifier.width(RudeusSpacing.md))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (done || active || failed) RudeusColors.Ink else RudeusColors.InkSoft,
        )
    }
}

@Composable
private fun GateScore(score: Float, passed: Boolean, attempt: Int, maxAttempts: Int) {
    val reduced = LocalReducedMotion.current
    val shown by animateFloatAsState(
        targetValue = score,
        animationSpec = tween(if (reduced) 0 else RudeusMotion.SCORE_COUNT_MS, easing = RudeusMotion.Standard),
        label = "score",
    )
    val accent: Color = if (passed) RudeusColors.Leaf else RudeusColors.Tomato
    val shape = RoundedCornerShape(RudeusRadius.card)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RudeusColors.PaperDeep)
            .border(RudeusSpacing.stroke, accent, shape)
            .padding(RudeusSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(t(StringId.gate_score), style = MaterialTheme.typography.labelLarge, color = RudeusColors.InkSoft)
            Text(formatFixed(shown, 2), style = MaterialTheme.typography.displayLarge, color = RudeusColors.Ink)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (passed) t(StringId.gate_passed) else t(StringId.gate_failed),
                style = MaterialTheme.typography.titleLarge,
                color = accent,
            )
            Text(
                "${t(StringId.gate_attempt)} $attempt / $maxAttempts",
                style = MaterialTheme.typography.bodyMedium,
                color = RudeusColors.InkSoft,
            )
        }
    }
}

@Composable
private fun ErrorSheet(onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(topStart = RudeusRadius.bubble, topEnd = RudeusRadius.bubble)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RudeusColors.ScrimStrong)
            .safeDrawingPadding()
            .padding(RudeusSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(t(StringId.processing_failed_title), style = MaterialTheme.typography.titleLarge, color = RudeusColors.Paper, textAlign = TextAlign.Center)
        Spacer(Modifier.height(RudeusSpacing.xs))
        Text(t(StringId.processing_failed_hint), style = MaterialTheme.typography.bodyLarge, color = RudeusColors.Paper, textAlign = TextAlign.Center)
        Spacer(Modifier.height(RudeusSpacing.md))
        PrimaryButton(t(StringId.retry), onClick = onRetry, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(RudeusSpacing.sm))
        // T03 (f): offline / no key / HTTP error -> back to Capture, where the ready characters are.
        GhostButton(t(StringId.processing_pick_ready), onClick = onBack, glyph = Glyph.BACK)
    }
}
