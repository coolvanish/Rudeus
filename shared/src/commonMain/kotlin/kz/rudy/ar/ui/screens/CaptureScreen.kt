package kz.rudy.ar.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.CharacterCard
import kz.rudy.ar.ui.components.GhostButton
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.GlyphIcon
import kz.rudy.ar.ui.components.Pill
import kz.rudy.ar.ui.components.PrimaryButton
import kz.rudy.ar.ui.components.SpriteImage
import kz.rudy.ar.ui.components.drawCornerBrackets
import kz.rudy.ar.ui.components.hitArea
import kz.rudy.ar.ui.components.rememberSpriteBitmap
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.LocalReducedMotion
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing
import kz.rudy.ar.ui.theme.rudeusWordmark
import org.jetbrains.compose.resources.painterResource
import rudeus.shared.generated.resources.Res
import rudeus.shared.generated.resources.capture_example

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

/** The hero's viewfinder corner: 32 dp, 4 dp stroke, on a dp grid. */
private val VIEWFINDER by lazy { PathParser().parsePathString("M2 32V12A10 10 0 0 1 12 2H32").toPath() }

/**
 * Capture: take a photo of the drawing/toy, or pick a bundled character. The "Rudeus ⌄" wordmark opens the Settings
 * sheet, where the language switch and Metrics live.
 * [onPickPhoto] (T03) adds a "from gallery" button under the camera one when the host offers a photo picker.
 */
@Composable
fun CaptureScreen(
    bundled: List<BundledCharacter>,
    offline: Boolean,
    onOpenSettings: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickBundled: (BundledCharacter) -> Unit,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    onPickPhoto: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(RudeusColors.Paper)) {
        val rows = if (maxHeight >= TWO_ROWS_MIN_SCREEN_HEIGHT) 2 else 1
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = RudeusSpacing.smd)) {
            Column(Modifier.weight(1f).padding(horizontal = RudeusSpacing.screenEdge)) {
                Wordmark(onOpenSettings)
                Spacer(Modifier.height(RudeusSpacing.xs))
                Text(t(StringId.capture_title), style = MaterialTheme.typography.displayLarge, color = RudeusColors.Ink)
                Spacer(Modifier.height(6.dp))
                Text(t(StringId.capture_hint), style = MaterialTheme.typography.bodyLarge, color = RudeusColors.InkSoft)
                Spacer(Modifier.height(RudeusSpacing.screenEdge))
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(RudeusRadius.hero))
                        .background(RudeusColors.Cream)
                        .padding(RudeusSpacing.smd),
                    verticalArrangement = Arrangement.spacedBy(RudeusSpacing.sm),
                ) {
                    ExampleWell(Modifier.weight(1f).heightIn(min = 96.dp).fillMaxWidth())
                    PrimaryButton(
                        text = t(StringId.capture_take_photo),
                        glyph = Glyph.CAMERA,
                        enabled = true, // offline: still enabled, a tap is the retry (the note below says why)
                        onClick = onTakePhoto,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (onPickPhoto != null) {
                        GhostButton(
                            text = t(StringId.capture_pick_gallery),
                            glyph = Glyph.GALLERY,
                            onClick = onPickPhoto,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        t(StringId.capture_privacy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = RudeusColors.InkSoft,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(22.dp))
                if (offline) {
                    Pill(t(StringId.capture_offline_note), dot = RudeusColors.Leaf)
                    Spacer(Modifier.height(RudeusSpacing.sm))
                }
                Text(t(StringId.capture_or_pick), style = MaterialTheme.typography.titleMedium, color = RudeusColors.Ink)
                Spacer(Modifier.height(RudeusSpacing.smd))
            }
            if (bundled.isEmpty()) {
                EmptyCard(t(StringId.capture_empty), modifier = Modifier.padding(horizontal = RudeusSpacing.screenEdge))
            } else {
                CharacterGrid(bundled, selectedId, rows, onPickBundled)
            }
        }
    }
}

/** Below this the second row would squeeze the hero's example drawing under its 96 dp. */
private val TWO_ROWS_MIN_SCREEN_HEIGHT = 960.dp

/** Until a card has been measured; its real height follows the font scale. */
private val CARD_HEIGHT_GUESS = 176.dp
private val CARD_ROW_GAP = 10.dp

private const val ENTRANCE_STAGGER_MS = 40L
private const val ENTRANCE_MAX_STAGGER = 8

/**
 * The shelf: [rows] rows scrolling sideways, bleeding off the right edge so it reads as "there is more". A card pops in
 * the first time it shows on this visit, one after another while the grid is still; the others make room with a spring
 * when a card is added in front.
 */
@Composable
private fun CharacterGrid(characters: List<BundledCharacter>, selectedId: String?, rows: Int, onPick: (BundledCharacter) -> Unit) {
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val grid = rememberLazyGridState()
    val entered = remember { mutableSetOf<String>() }
    var cardHeight by remember { mutableStateOf<Dp?>(null) }
    // A keyed grid holds its scroll on the old first card, so a card added in front would sit off-screen left.
    LaunchedEffect(characters.first().id) {
        if (reduced) grid.scrollToItem(0) else grid.animateScrollToItem(0)
    }
    LazyHorizontalGrid(
        rows = GridCells.Fixed(rows),
        modifier = Modifier.fillMaxWidth().height((cardHeight ?: CARD_HEIGHT_GUESS) * rows + CARD_ROW_GAP * (rows - 1)),
        state = grid,
        contentPadding = PaddingValues(horizontal = RudeusSpacing.screenEdge),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(CARD_ROW_GAP),
    ) {
        itemsIndexed(characters, key = { _, c -> c.id }) { index, c ->
            val entranceDelayMs = remember {
                when {
                    !entered.add(c.id) -> null
                    reduced || grid.isScrollInProgress -> 0L
                    else -> (index - grid.firstVisibleItemIndex).coerceIn(0, ENTRANCE_MAX_STAGGER) * ENTRANCE_STAGGER_MS
                }
            }
            BundledCard(
                c,
                selected = c.id == selectedId,
                entranceDelayMs = entranceDelayMs,
                onClick = { onPick(c) },
                // The card's own entrance replaces the fade in.
                modifier = (if (reduced) Modifier.animateItem(fadeInSpec = null, placementSpec = null) else Modifier.animateItem(fadeInSpec = null))
                    // A cell has the grid's height, so the card measures itself free of it and the grid follows.
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .onSizeChanged { size ->
                        val measured = with(density) { size.height.toDp() }
                        if (measured > (cardHeight ?: 0.dp)) cardHeight = measured
                    },
            )
        }
    }
}

private val WORDMARK_HEIGHT = 52.dp

/** "Rudeus ⌄": the one way into Settings. The -10 dp keeps the word on the screen edge while its hit area spills out. */
@Composable
private fun Wordmark(onClick: () -> Unit) {
    val label = t(StringId.settings_title)
    Box(Modifier.height(WORDMARK_HEIGHT), contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier
                .offset(x = (-10).dp)
                .height(WORDMARK_HEIGHT)
                .hitArea(WORDMARK_HEIGHT)
                .clip(RoundedCornerShape(14.dp))
                .semantics { contentDescription = label }
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(t(StringId.app_name), style = rudeusWordmark(), color = RudeusColors.Ink)
            // Sky so the arrow, the only colour up here, reads as "this opens something".
            GlyphIcon(Glyph.DOWN, RudeusColors.Sky, Modifier.padding(top = 3.dp), size = 20.dp, strokeWidth = 2.8f)
        }
    }
}

/** An example drawing under Paper viewfinder brackets: shows what "take a photo" means. */
@Composable
private fun ExampleWell(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(RudeusRadius.card))) {
        Image(
            painterResource(Res.drawable.capture_example),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = BiasAlignment(0f, -0.56f),
        )
        Canvas(Modifier.fillMaxSize()) {
            drawCornerBrackets(VIEWFINDER, 14.dp.toPx(), 1.dp.toPx(), RudeusColors.Paper, 4f, StrokeCap.Butt)
        }
    }
}

/** [entranceDelayMs] null = already shown on this visit, so no entrance. Reduced motion: a fade and no scaling. */
@Composable
private fun BundledCard(
    c: BundledCharacter,
    selected: Boolean,
    entranceDelayMs: Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
    CharacterCard(
        name = c.sheet.name,
        ability = c.sheet.ability,
        selected = selected,
        onClick = onClick,
        modifier = modifier.graphicsLayer {
            val pop = if (reduced) 1f else 0.6f + 0.4f * entrance.value
            scaleX = pop * pressScale
            scaleY = pop * pressScale
            alpha = entrance.value.coerceIn(0f, 1f)
        },
        interactionSource = interaction,
    ) {
        val sprite = rememberSpriteBitmap(c.spritePng)
        val thumb = c.thumbnail
        val art = Modifier.fillMaxSize().graphicsLayer {
            val scale = breath?.value ?: 1f
            scaleX = scale
            scaleY = scale
        }
        when {
            sprite != null -> SpriteImage(sprite, contentDescription = null, modifier = art)
            thumb != null -> thumb(art)
            else -> GlyphIcon(Glyph.HIDE, RudeusColors.InkMuted, size = 48.dp)
        }
    }
}

@Composable
internal fun EmptyCard(text: String, modifier: Modifier = Modifier, hint: String? = null) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RudeusRadius.card))
            .background(RudeusColors.Cream)
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
