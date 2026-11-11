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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.ui.LocalLang
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.AbilityTag
import kz.rudy.ar.ui.components.BottomSheet
import kz.rudy.ar.ui.components.FighterStatsCard
import kz.rudy.ar.ui.components.GhostButton
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.GlyphIcon
import kz.rudy.ar.ui.components.PrimaryButton
import kz.rudy.ar.ui.components.SpriteImage
import kz.rudy.ar.ui.components.StatusDot
import kz.rudy.ar.ui.components.Tag
import kz.rudy.ar.ui.components.TagTone
import kz.rudy.ar.ui.components.rememberSpriteBitmap
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.LocalReducedMotion
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMono
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

/** Pipeline stages in order (mirrors core CharacterPipeline: cutout -> stylize -> gate -> sheet). */
enum class PipelineStage(val label: StringId, internal val glyph: Glyph) {
    CUTOUT(StringId.stage_cutout, Glyph.CUT),
    STYLIZE(StringId.stage_stylize, Glyph.PAINT),
    GATE(StringId.stage_gate, Glyph.SHIELD),
    SHEET(StringId.stage_sheet, Glyph.NAME),
}

/** REVIEW: below the gate with attempts left, the run waits on the child. GATE_FAILED: the same question, after the last attempt. */
enum class ProcessingStatus { RUNNING, REVIEW, GATE_FAILED, ERROR, DONE }

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
    /** What Muse sent for this attempt, at any score; null until it arrives and while a new attempt paints. */
    val sprite: ByteArray? = null,
    /** Optional cutout/stylized preview slot drawn by the host. */
    val preview: (@Composable (Modifier) -> Unit)? = null,
) {
    val fraction: Float
        get() = when (status) {
            ProcessingStatus.DONE -> 1f
            else -> completed.size / PipelineStage.entries.size.toFloat() + (if (stage != null) 0.5f / PipelineStage.entries.size else 0f)
        }
}

/** Long side the hero's image is kept at: enough for a phone-wide well, and a 2048x1365 photo costs 4.4 MB here instead of 11 MB. */
private const val HERO_MAX_SIDE_PX = 1280

@Composable
fun ProcessingScreen(
    state: ProcessingState,
    onRetry: () -> Unit,
    onShipAnyway: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The photo going through the pipeline: the hero until Muse's sprite ([ProcessingState.sprite]) is there. */
    photo: ByteArray? = null,
) {
    val asking = state.status == ProcessingStatus.REVIEW || state.status == ProcessingStatus.GATE_FAILED
    val finished = state.status == ProcessingStatus.DONE || asking
    val spriteBitmap = rememberSpriteBitmap(state.sprite, HERO_MAX_SIDE_PX, HERO_MAX_SIDE_PX)
    val photoBitmap = rememberSpriteBitmap(photo, HERO_MAX_SIDE_PX, HERO_MAX_SIDE_PX)
    Box(modifier.fillMaxSize().background(RudeusColors.Paper)) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(start = RudeusSpacing.screenEdge, end = RudeusSpacing.screenEdge, bottom = RudeusSpacing.smd),
        ) {
            Box(Modifier.height(52.dp), contentAlignment = Alignment.CenterStart) {
                if (!finished) GhostButton(t(StringId.back), onClick = onBack, glyph = Glyph.BACK, modifier = Modifier.offset(x = (-14).dp))
            }
            if (state.status == ProcessingStatus.DONE) {
                Text(t(StringId.processing_done), style = MaterialTheme.typography.displayLarge, color = RudeusColors.Ink)
            } else {
                Text(t(if (asking) StringId.review_title else StringId.processing_title), style = MaterialTheme.typography.titleLarge, color = RudeusColors.Ink)
            }
            Spacer(Modifier.height(RudeusSpacing.md))
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(RudeusRadius.hero))
                    .background(RudeusColors.Cream)
                    .padding(RudeusSpacing.smd),
                verticalArrangement = Arrangement.spacedBy(RudeusSpacing.sm),
            ) {
                HeroWell(
                    image = spriteBitmap ?: photoBitmap,
                    description = if (spriteBitmap != null) state.sheet?.nameIn(LocalLang.current.code) else null,
                    preview = state.preview,
                    modifier = Modifier.weight(1f).heightIn(min = 96.dp).fillMaxWidth(),
                )
                if (finished) ResultLines(state)
            }
            if (finished) {
                Spacer(Modifier.height(RudeusSpacing.screenEdge))
                if (state.status == ProcessingStatus.DONE) {
                    PrimaryButton(t(StringId.next), onClick = onContinue, trailingGlyph = Glyph.RIGHT, modifier = Modifier.fillMaxWidth())
                } else {
                    PrimaryButton(t(StringId.review_yes), onClick = onShipAnyway, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(RudeusSpacing.sm))
                    GhostButton(t(StringId.retry), onClick = onRetry, glyph = Glyph.RETRY, modifier = Modifier.fillMaxWidth())
                }
            } else {
                Spacer(Modifier.height(RudeusSpacing.screenEdge))
                ProgressBar(state.fraction)
                Spacer(Modifier.height(14.dp))
                Stages(state)
                state.gateScore?.let { score ->
                    Spacer(Modifier.height(RudeusSpacing.md))
                    GateScore(score, passed = score >= state.gateThreshold, attempt = state.attempt, maxAttempts = state.maxAttempts)
                }
            }
        }
        BottomSheet(visible = state.status == ProcessingStatus.ERROR) {
            Text(t(StringId.processing_failed_title), style = MaterialTheme.typography.titleLarge, color = RudeusColors.Ink)
            Text(t(StringId.processing_failed_hint), style = MaterialTheme.typography.bodyLarge, color = RudeusColors.InkSoft)
            PrimaryButton(t(StringId.retry), onClick = onRetry, glyph = Glyph.RETRY, modifier = Modifier.fillMaxWidth())
            // T03 (f): offline / no key / HTTP error -> back to Capture, where the ready characters are.
            GhostButton(t(StringId.processing_pick_ready), onClick = onBack, glyph = Glyph.BACK, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The Paper well: [image] (finished sprite, else the photo), else the host's preview. */
@Composable
private fun HeroWell(
    image: ImageBitmap?,
    description: String?,
    preview: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier,
) {
    Box(modifier.clip(RoundedCornerShape(RudeusRadius.card)).background(RudeusColors.Paper)) {
        val inset = Modifier.fillMaxSize().padding(18.dp)
        when {
            image != null -> SpriteImage(image, contentDescription = description, modifier = inset)
            preview != null -> preview(inset)
            else -> GlyphIcon(Glyph.CAMERA, RudeusColors.InkMuted, Modifier.align(Alignment.Center), size = 48.dp)
        }
    }
}

/** Under the finished hero: name + ability tag, then the honest score line with a status dot. */
@Composable
private fun ResultLines(state: ProcessingState) {
    state.sheet?.let { sheet ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                sheet.nameIn(LocalLang.current.code),
                style = MaterialTheme.typography.titleLarge,
                color = RudeusColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            AbilityTag(sheet.ability)
        }
        // T44: why the drawing got its numbers (Done only; a card the child is asked about does not carry it).
        if (state.status == ProcessingStatus.DONE) FighterStatsCard(sheet, Modifier.fillMaxWidth())
    }
    state.gateScore?.let { score ->
        // The gate's verdict, not the status: a sprite the child took is Done with a score that did not pass.
        val passed = score >= state.gateThreshold
        Row(
            Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.sm),
        ) {
            StatusDot(if (passed) RudeusColors.Leaf else RudeusColors.Tomato)
            Text(t(StringId.gate_score), style = MaterialTheme.typography.bodySmall, color = RudeusColors.InkSoft)
            Text(formatFixed(score, 2), style = RudeusMono, color = RudeusColors.InkSoft)
            Text(
                "· ${t(if (passed) StringId.gate_passed else StringId.gate_failed)}",
                style = MaterialTheme.typography.bodySmall,
                color = RudeusColors.InkSoft,
            )
        }
    }
}

/** 14 dp bar with a 2 dp Ink edge; the Sun fill ends in its own Ink edge. */
@Composable
private fun ProgressBar(fraction: Float) {
    val reduced = LocalReducedMotion.current
    val progress by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(if (reduced) RudeusMotion.REDUCED_MS else RudeusMotion.STAGE_PROGRESS_MS, easing = RudeusMotion.Standard),
        label = "progress",
    )
    val shape = RoundedCornerShape(RudeusRadius.full)
    Box(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(shape)
            .background(RudeusColors.Paper)
            .border(RudeusSpacing.stroke, RudeusColors.Ink, shape)
            .padding(RudeusSpacing.stroke),
    ) {
        Row(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(RudeusColors.Sun)) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxHeight().width(RudeusSpacing.stroke).background(RudeusColors.Ink))
        }
    }
}

/** The 4-column stepper: done = Leaf tint + check, active = Sun + Ink ring, failed = Tomato tint + cross. */
@Composable
private fun Stages(state: ProcessingState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.xs)) {
        PipelineStage.entries.forEach { stage ->
            val done = stage in state.completed
            val active = stage == state.stage && state.status == ProcessingStatus.RUNNING
            val failed = stage == state.stage && state.status == ProcessingStatus.ERROR
            val fill = when {
                failed -> RudeusColors.TomatoTint
                done -> RudeusColors.LeafTint
                active -> RudeusColors.Sun
                else -> RudeusColors.Cream
            }
            val tint = when {
                failed -> RudeusColors.Tomato
                done -> RudeusColors.Leaf
                active -> RudeusColors.Ink
                else -> RudeusColors.InkMuted
            }
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(fill)
                        .then(if (active) Modifier.border(RudeusSpacing.stroke, RudeusColors.Ink, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    val glyph = when {
                        failed -> Glyph.CLOSE
                        done -> Glyph.CHECK
                        else -> stage.glyph
                    }
                    GlyphIcon(glyph, tint, size = 20.dp)
                }
                Text(
                    t(stage.label),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, lineHeight = 15.sp),
                    color = when {
                        active || failed -> RudeusColors.Ink
                        done -> RudeusColors.InkSoft
                        else -> RudeusColors.InkMuted
                    },
                    textAlign = TextAlign.Center,
                )
            }
        }
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
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RudeusRadius.card))
            .background(RudeusColors.Cream)
            .padding(RudeusSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            formatFixed(shown, 2),
            style = RudeusMono.copy(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp),
            color = RudeusColors.Ink,
        )
        Column(Modifier.weight(1f)) {
            Text(t(StringId.gate_score), style = MaterialTheme.typography.labelLarge, color = RudeusColors.Ink)
            Text(
                "${t(StringId.gate_attempt)} $attempt / $maxAttempts",
                style = MaterialTheme.typography.bodySmall,
                color = RudeusColors.InkSoft,
            )
        }
        if (passed) {
            Tag(t(StringId.gate_passed), tone = TagTone.LEAF, glyph = Glyph.CHECK)
        } else {
            Tag(t(StringId.gate_failed), tone = TagTone.TOMATO)
        }
    }
}
