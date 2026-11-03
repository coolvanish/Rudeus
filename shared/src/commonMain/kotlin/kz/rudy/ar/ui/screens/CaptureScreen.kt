package kz.rudy.ar.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.ui.Lang
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.AbilityChip
import kz.rudy.ar.ui.components.Chip
import kz.rudy.ar.ui.components.GhostButton
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.GlyphIcon
import kz.rudy.ar.ui.components.LangSwitch
import kz.rudy.ar.ui.components.PrimaryButton
import kz.rudy.ar.ui.components.SpriteImage
import kz.rudy.ar.ui.components.rememberSpriteBitmap
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.LocalReducedMotion
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

/**
 * A ready character: shipped in the APK (CLI-generated, DESIGN.md "Capture") or kept from an earlier photo (T26). The
 * card draws [spritePng] when present, else the host's [thumbnail], else a glyph.
 */
data class BundledCharacter(
    val id: String,
    val sheet: CharacterSheet,
    /** Optional 96 dp thumbnail slot drawn by the host (e.g. from app assets). */
    val thumbnail: (@Composable (Modifier) -> Unit)? = null,
    /** The matted sprite PNG that Play shows for this character; null = the host's default sprite. */
    val spritePng: ByteArray? = null,
)

/**
 * Capture: take a photo of the drawing/toy, or pick a bundled character.
 * [preview] is an optional camera preview slot from :androidApp; without it the screen is paper.
 * [onPickPhoto] (T03) adds a "from gallery" button under the camera one when the host offers a photo picker.
 */
@Composable
fun CaptureScreen(
    bundled: List<BundledCharacter>,
    offline: Boolean,
    lang: Lang,
    onLangChange: (Lang) -> Unit,
    onTakePhoto: () -> Unit,
    onPickBundled: (BundledCharacter) -> Unit,
    onOpenMetrics: () -> Unit,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    preview: (@Composable (Modifier) -> Unit)? = null,
    onPickPhoto: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().background(RudeusColors.Paper)) {
        preview?.invoke(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(RudeusSpacing.md),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                LangSwitch(lang, onLangChange)
            }
            Spacer(Modifier.height(RudeusSpacing.lg))
            Text(t(StringId.capture_title), style = MaterialTheme.typography.displayLarge, color = RudeusColors.Ink)
            Spacer(Modifier.height(RudeusSpacing.sm))
            Text(t(StringId.capture_hint), style = MaterialTheme.typography.bodyLarge, color = RudeusColors.InkSoft)
            Spacer(Modifier.height(RudeusSpacing.lg))
            PrimaryButton(
                text = t(StringId.capture_take_photo),
                glyph = Glyph.CAMERA,
                enabled = true, // offline: still enabled, a tap is the retry (the note below says why)
                onClick = onTakePhoto,
                modifier = Modifier.fillMaxWidth(),
            )
            if (onPickPhoto != null) {
                Spacer(Modifier.height(RudeusSpacing.sm))
                GhostButton(text = t(StringId.capture_pick_gallery), onClick = onPickPhoto, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(RudeusSpacing.xl))
            if (offline) {
                Chip(
                    text = t(StringId.capture_offline_note),
                    container = RudeusColors.Leaf,
                    accent = RudeusColors.Leaf,
                    contentColor = RudeusColors.Paper,
                )
                Spacer(Modifier.height(RudeusSpacing.sm))
            }
            Text(t(StringId.capture_or_pick), style = MaterialTheme.typography.titleLarge, color = RudeusColors.Ink)
            Spacer(Modifier.height(RudeusSpacing.md))
            if (bundled.isEmpty()) {
                EmptyCard(t(StringId.capture_empty))
                Spacer(Modifier.weight(1f))
            } else {
                CharacterGrid(bundled, selectedId, onPickBundled, Modifier.weight(1f))
                Spacer(Modifier.height(RudeusSpacing.md))
            }
            GhostButton(text = t(StringId.metrics_button), glyph = Glyph.METRICS, onClick = onOpenMetrics)
        }
    }
}

/**
 * Bundled card width. The compact ability chip needs ~8 + 86 + 8 dp for the longest label ("Жасырыну", 16 sp bold
 * + tracking) plus the 3 dp outline; 144 dp leaves 128 dp inside the card's 8 dp padding.
 */
private val CARD_WIDTH = 144.dp

/** A grid row needs this much: the sprite stays at least 72 dp above the name and the chip. */
private val CARD_MIN_HEIGHT = 168.dp

/** With a single tall row the card stops here, where the sprite has filled the card's width. */
private val CARD_MAX_HEIGHT = 224.dp

private const val ENTRANCE_STAGGER_MS = 40L
private const val ENTRANCE_MAX_STAGGER = 8

/**
 * As many rows as fit in the height left, scrolling sideways. A card pops in the first time it shows on this visit,
 * one after another while the row is still; the others make room with a spring when a card is added in front.
 */
@Composable
private fun CharacterGrid(
    characters: List<BundledCharacter>,
    selectedId: String?,
    onPick: (BundledCharacter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val grid = rememberLazyGridState()
    val entered = remember { mutableSetOf<String>() }
    // A keyed grid holds its scroll on the old first card, so a card added in front would sit off-screen left.
    LaunchedEffect(characters.first().id) {
        if (reduced) grid.scrollToItem(0) else grid.animateScrollToItem(0)
    }
    LazyHorizontalGrid(
        rows = GridCells.Adaptive(CARD_MIN_HEIGHT),
        modifier = modifier.fillMaxWidth(),
        state = grid,
        horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.md),
        verticalArrangement = Arrangement.spacedBy(RudeusSpacing.md),
    ) {
        itemsIndexed(characters, key = { _, c -> c.id }) { index, c ->
            val entranceDelayMs = remember {
                when {
                    !entered.add(c.id) -> null
                    reduced || grid.isScrollInProgress -> 0L
                    else -> (index - grid.firstVisibleItemIndex).coerceIn(0, ENTRANCE_MAX_STAGGER) * ENTRANCE_STAGGER_MS
                }
            }
            CharacterCard(
                c,
                selected = c.id == selectedId,
                entranceDelayMs = entranceDelayMs,
                onClick = { onPick(c) },
                // The card's own entrance replaces the fade in.
                modifier = if (reduced) Modifier.animateItem(fadeInSpec = null, placementSpec = null) else Modifier.animateItem(fadeInSpec = null),
            )
        }
    }
}

/** [entranceDelayMs] null = already shown on this visit, so no entrance. Reduced motion: a fade and no scaling. */
@Composable
private fun CharacterCard(
    c: BundledCharacter,
    selected: Boolean,
    entranceDelayMs: Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(RudeusRadius.card)
    val reduced = LocalReducedMotion.current
    val entrance = remember { Animatable(if (entranceDelayMs == null) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (entranceDelayMs != null) {
            delay(entranceDelayMs)
            entrance.animateTo(1f, if (reduced) tween(RudeusMotion.REDUCED_MS) else RudeusMotion.pop())
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reduced) 0.96f else 1f,
        animationSpec = if (pressed) tween(RudeusMotion.BUTTON_PRESS_MS, easing = RudeusMotion.Standard) else RudeusMotion.pop(),
        label = "press",
    )
    val outline by animateColorAsState(
        targetValue = if (selected) RudeusColors.Sun else RudeusColors.Ink,
        animationSpec = tween(RudeusMotion.REDUCED_MS, easing = RudeusMotion.Standard),
        label = "outline",
    )
    Column(
        modifier
            .wrapContentHeight(Alignment.Top)
            .heightIn(max = CARD_MAX_HEIGHT)
            .width(CARD_WIDTH)
            .graphicsLayer {
                val pop = if (reduced) 1f else 0.6f + 0.4f * entrance.value
                scaleX = pop * pressScale
                scaleY = pop * pressScale
                alpha = entrance.value.coerceIn(0f, 1f)
            }
            .clip(shape)
            .background(RudeusColors.PaperDeep)
            .border(RudeusSpacing.stroke, outline, shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(RudeusSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The picked character breathes like the scan brackets; a loop, so reduced motion keeps it still.
        val breath = if (selected && !reduced) {
            rememberInfiniteTransition(label = "breath").animateFloat(
                initialValue = 1f,
                targetValue = 1.04f,
                animationSpec = infiniteRepeatable(tween(RudeusMotion.BRACKET_BREATHE_MS, easing = EaseInOutSine), RepeatMode.Reverse),
                label = "breathScale",
            )
        } else {
            null
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .graphicsLayer {
                    val scale = breath?.value ?: 1f
                    scaleX = scale
                    scaleY = scale
                },
            contentAlignment = Alignment.Center,
        ) {
            val sprite = rememberSpriteBitmap(c.spritePng)
            val thumb = c.thumbnail
            when {
                sprite != null -> SpriteImage(sprite, contentDescription = null, modifier = Modifier.fillMaxSize())
                thumb != null -> thumb(Modifier.fillMaxSize())
                else -> GlyphIcon(Glyph.HIDE, RudeusColors.InkSoft, size = 48.dp)
            }
        }
        Spacer(Modifier.height(RudeusSpacing.xs))
        Text(
            c.sheet.name,
            style = MaterialTheme.typography.labelLarge,
            color = RudeusColors.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(RudeusSpacing.xs))
        AbilityChip(c.sheet.ability, compact = true)
    }
}

@Composable
internal fun EmptyCard(text: String, hint: String? = null) {
    val shape = RoundedCornerShape(RudeusRadius.card)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RudeusColors.PaperDeep)
            .border(RudeusSpacing.stroke, RudeusColors.InkSoft, shape)
            .padding(RudeusSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = RudeusColors.InkSoft, textAlign = TextAlign.Center)
        if (hint != null) {
            Spacer(Modifier.height(RudeusSpacing.xs))
            Text(hint, style = MaterialTheme.typography.bodyLarge, color = RudeusColors.InkSoft, textAlign = TextAlign.Center)
        }
    }
}
