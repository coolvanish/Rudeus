package kz.rudy.ar.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kz.rudy.ar.core.pipeline.BatchImport
import kz.rudy.ar.core.pipeline.CharacterResult
import kz.rudy.ar.ui.LocalLang
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.Chip
import kz.rudy.ar.ui.components.GhostButton
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.GlyphIcon
import kz.rudy.ar.ui.components.PrimaryButton
import kz.rudy.ar.ui.components.SpriteImage
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

/** T38: where one photo of a batch stands. */
enum class ImportStatus { QUEUED, RUNNING, PASSED, BELOW_GATE, FAILED }

/**
 * T38: one photo of a batch. Its pipeline's events fold into [state] through [ProcessingState.reduce] exactly as the
 * single run's do (so the stage labels and the honest score are the same); [result] is the pipeline's answer at any
 * score, [savedId] the shelf card once it is on disk. [keep]: below the gate, the child wants it on the shelf anyway.
 */
data class ImportItem(
    val index: Int,
    val photo: ByteArray,
    val started: Boolean = false,
    val state: ProcessingState = ProcessingState(maxAttempts = BatchImport.MAX_ATTEMPTS),
    val result: CharacterResult? = null,
    val keep: Boolean = false,
    val savedId: String? = null,
) {
    val status: ImportStatus
        get() = when {
            savedId != null -> ImportStatus.PASSED
            state.status == ProcessingStatus.ERROR -> ImportStatus.FAILED
            result == null -> if (started) ImportStatus.RUNNING else ImportStatus.QUEUED
            result.gatePassed -> ImportStatus.PASSED
            else -> ImportStatus.BELOW_GATE
        }

    /** 0..1 of this photo's pipeline; a finished or failed one counts as whole. */
    val fraction: Float
        get() = when (status) {
            ImportStatus.QUEUED -> 0f
            ImportStatus.RUNNING -> state.fraction
            else -> 1f
        }
}

/** T38: the batch on the Import screen. [running] is false once every photo ended, whatever the outcome. */
data class ImportState(val items: List<ImportItem> = emptyList(), val running: Boolean = false) {
    /** Cards already on the shelf (passed the gate and saved). */
    val added: Int get() = items.count { it.savedId != null }

    /** Saved when the child taps Done: below the gate and ticked, or passed but not on disk yet (its save failed once). */
    val kept: List<ImportItem>
        get() = items.filter { it.savedId == null && it.result != null && (it.result.gatePassed || (it.keep && it.status == ImportStatus.BELOW_GATE)) }

    val fraction: Float get() = if (items.isEmpty()) 0f else items.map { it.fraction }.sum() / items.size

    val allFailed: Boolean get() = items.isNotEmpty() && items.all { it.status == ImportStatus.FAILED }
}

private val TILE_MIN_WIDTH = 124.dp
private val TILE_GAP = 10.dp
private val TILE_ART_HEIGHT = 96.dp
private val TILE_ART_MAX_WIDTH = 190.dp

/**
 * T38 Import: a tile per photo of the batch; the photo until Muse's sprite replaces it, the stage under it, then the
 * name and the gate's honest verdict. Back cancels what still runs (what is saved stays); once every photo ended the
 * title says how many joined the shelf and the one Sun button, Done, returns to Capture. A photo below the gate shows
 * its score and a Keep chip, off by default, so a bad batch leaves nothing behind unless the child says so.
 */
@Composable
fun ImportScreen(
    state: ImportState,
    onToggleKeep: (ImportItem) -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(RudeusColors.Paper)) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(start = RudeusSpacing.screenEdge, end = RudeusSpacing.screenEdge, bottom = RudeusSpacing.smd),
        ) {
            Box(Modifier.height(52.dp), contentAlignment = Alignment.CenterStart) {
                if (state.running) GhostButton(t(StringId.back), onClick = onBack, glyph = Glyph.BACK, modifier = Modifier.offset(x = (-14).dp))
            }
            if (state.running) {
                Text(t(StringId.import_title), style = MaterialTheme.typography.titleLarge, color = RudeusColors.Ink)
                Spacer(Modifier.height(6.dp))
                Text(t(StringId.import_hint), style = MaterialTheme.typography.bodyLarge, color = RudeusColors.InkSoft)
            } else {
                val willAdd = state.added + state.kept.size
                Text(
                    t(if (willAdd > 0) StringId.processing_done else StringId.import_none_added),
                    style = MaterialTheme.typography.displayLarge,
                    color = RudeusColors.Ink,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (state.allFailed) t(StringId.processing_failed_hint) else "$willAdd ${t(StringId.import_added)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = RudeusColors.InkSoft,
                )
            }
            Spacer(Modifier.height(RudeusSpacing.md))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(TILE_MIN_WIDTH),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = RudeusSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
                verticalArrangement = Arrangement.spacedBy(TILE_GAP),
            ) {
                items(state.items, key = { it.index }) { item -> Tile(item, onToggleKeep = { onToggleKeep(item) }) }
            }
            Spacer(Modifier.height(RudeusSpacing.sm))
            if (state.running) {
                BatchProgress(state.fraction)
            } else {
                PrimaryButton(t(StringId.import_finish), onClick = onFinish, glyph = Glyph.CHECK, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Tile(item: ImportItem, onToggleKeep: () -> Unit) {
    val density = LocalDensity.current
    val maxW = with(density) { TILE_ART_MAX_WIDTH.roundToPx() }
    val maxH = with(density) { TILE_ART_HEIGHT.roundToPx() }
    val sprite = rememberSpriteBitmap(item.state.sprite ?: item.result?.sprite, maxW, maxH)
    val photo = rememberSpriteBitmap(item.photo, maxW, maxH)
    val status = item.status
    val lang = LocalLang.current.code
    val name = item.result?.sheet?.nameIn(lang) ?: item.state.sheet?.nameIn(lang)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RudeusRadius.card))
            .background(RudeusColors.Cream)
            .padding(RudeusSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(TILE_ART_HEIGHT)
                .clip(RoundedCornerShape(RudeusRadius.card))
                .background(RudeusColors.Paper),
            contentAlignment = Alignment.Center,
        ) {
            // The photo dims while it waits or paints, so the sprite reads as the result when it lands.
            val art = sprite ?: photo
            val dim = status == ImportStatus.QUEUED || (status == ImportStatus.RUNNING && sprite == null) || status == ImportStatus.FAILED
            when {
                art != null -> SpriteImage(art, contentDescription = name, modifier = Modifier.fillMaxSize().padding(6.dp).alpha(if (dim) 0.45f else 1f))
                else -> GlyphIcon(Glyph.GALLERY, RudeusColors.InkMuted, size = 32.dp)
            }
            if (status == ImportStatus.FAILED) GlyphIcon(Glyph.CLOSE, RudeusColors.Tomato, size = 32.dp)
        }
        Text(
            when (status) {
                ImportStatus.QUEUED -> t(StringId.import_queued)
                ImportStatus.RUNNING -> item.state.stage?.let { stage ->
                    if (item.state.attempt > 1) "${t(stage.label)} · ${item.state.attempt}/${item.state.maxAttempts}" else t(stage.label)
                } ?: t(StringId.import_title)
                ImportStatus.FAILED -> t(StringId.processing_failed_title)
                else -> name ?: ""
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (status == ImportStatus.QUEUED) RudeusColors.InkMuted else RudeusColors.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when (status) {
            ImportStatus.PASSED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(t(StringId.gate_passed), tone = TagTone.LEAF, glyph = Glyph.CHECK)
                item.result?.let { Text(formatFixed(it.gateScore, 2), style = RudeusMono, color = RudeusColors.InkSoft) }
            }
            ImportStatus.BELOW_GATE -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag(t(StringId.gate_failed), tone = TagTone.TOMATO)
                    item.result?.let { Text(formatFixed(it.gateScore, 2), style = RudeusMono, color = RudeusColors.InkSoft) }
                }
                Chip(
                    t(if (item.keep) StringId.import_kept else StringId.import_keep),
                    glyph = if (item.keep) Glyph.CHECK else Glyph.PLUS,
                    isOn = item.keep,
                    onClick = onToggleKeep,
                )
            }
            ImportStatus.RUNNING -> TileProgress(item.state.fraction)
            ImportStatus.QUEUED, ImportStatus.FAILED -> Spacer(Modifier.height(8.dp))
        }
    }
}

/** The tile's own bar: 8 dp, Sun on Paper. */
@Composable
private fun TileProgress(fraction: Float) {
    val reduced = LocalReducedMotion.current
    val progress by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(if (reduced) RudeusMotion.REDUCED_MS else RudeusMotion.STAGE_PROGRESS_MS, easing = RudeusMotion.Standard),
        label = "tileProgress",
    )
    val shape = RoundedCornerShape(RudeusRadius.full)
    Box(Modifier.fillMaxWidth().height(8.dp).clip(shape).background(RudeusColors.Paper)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(RudeusColors.Sun))
    }
}

/** The batch's bar, the Processing bar's shape: 14 dp with a 2 dp Ink edge, the Sun fill ending in its own edge. */
@Composable
private fun BatchProgress(fraction: Float) {
    val reduced = LocalReducedMotion.current
    val progress by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(if (reduced) RudeusMotion.REDUCED_MS else RudeusMotion.STAGE_PROGRESS_MS, easing = RudeusMotion.Standard),
        label = "batchProgress",
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
