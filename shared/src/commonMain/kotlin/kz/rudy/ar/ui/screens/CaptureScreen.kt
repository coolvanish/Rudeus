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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import kotlin.math.max
import kotlinx.coroutines.delay
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.CharacterCard
import kz.rudy.ar.ui.components.Chip
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
 * Capture: take a photo of the drawing/toy, or pick a character. One vertical grid is the whole screen: the hero fills the
 * first view with the shelf's headline peeking below it, and the All / Mine / Ready filter chips pin to the top over the
 * cards. The "Rudeus ⌄" wordmark opens the Settings sheet, where the language switch and Metrics live.
 * [saved] are the characters from the child's photos, [bundled] the ones shipped in the APK.
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
    saved: List<BundledCharacter> = emptyList(),
    selectedId: String? = null,
    onPickPhoto: (() -> Unit)? = null,
) {
    // Inside the insets, so the pinned row never slides under the status bar.
    BoxWithConstraints(modifier.fillMaxSize().background(RudeusColors.Paper).safeDrawingPadding()) {
        val heroHeight = max(maxHeight - SHELF_PEEK, HERO_MIN_HEIGHT * LocalDensity.current.fontScale)
        CaptureGrid(
            bundled = bundled,
            saved = saved,
            selectedId = selectedId,
            onPick = onPickBundled,
            hero = {
                Hero(offline, onOpenSettings, onTakePhoto, onPickPhoto, Modifier.height(heroHeight))
            },
        )
    }
}

/** What the shelf shows; the chips pinned over the cards switch it. */
private enum class Shelf(val label: StringId) {
    ALL(StringId.capture_shelf_all),
    MINE(StringId.capture_shelf_mine),
    READY(StringId.capture_shelf_ready),
}

/** How much of the shelf shows under the hero, so the first view reads as "there is more below". */
private val SHELF_PEEK = 44.dp

/** Below this (times the font scale) the hero stops shrinking and scrolls instead, keeping its example drawing visible. */
private val HERO_MIN_HEIGHT = 560.dp

private val CARD_MIN_WIDTH = 124.dp
private val CARD_GAP = 10.dp

private const val ENTRANCE_STAGGER_MS = 40L
private const val ENTRANCE_MAX_STAGGER = 8

private const val HERO_KEY = "capture_hero"
private const val HEADLINE_KEY = "capture_headline"
private const val FILTER_CHIPS_KEY = "capture_filter_chips"
private const val EMPTY_KEY = "capture_empty"

/** The items above the cards: hero, headline, filter chips. */
private const val ITEMS_ABOVE_CARDS = 3

/**
 * One grid for every shelf, so a filter change moves the cards it keeps with a spring, fades the ones it drops and pops the
 * new ones in one after another.
 */
@Composable
private fun CaptureGrid(
    bundled: List<BundledCharacter>,
    saved: List<BundledCharacter>,
    selectedId: String?,
    onPick: (BundledCharacter) -> Unit,
    hero: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val grid = rememberLazyGridState()
    val entered = remember { mutableSetOf<String>() }
    var shelf by remember { mutableStateOf(Shelf.ALL) }
    val characters = when (shelf) {
        Shelf.ALL -> saved + bundled
        Shelf.MINE -> saved
        Shelf.READY -> bundled
    }
    fun show(next: Shelf) {
        // A card that comes back pops in again; the ones both shelves share stay composed and only move.
        if (next != shelf) entered.clear()
        shelf = next
    }
    var chipsHeight by remember { mutableIntStateOf(0) }
    var cardHeight by remember { mutableIntStateOf(0) }
    val shown by rememberUpdatedState(characters.size)
    // A short shelf still has to scroll up to the pinned chips, or switching to it would make them jump.
    val bottomPadding by remember(grid, density) {
        derivedStateOf {
            val info = grid.layoutInfo
            val columns = max(1, info.maxSpan)
            val rows = (shown + columns - 1) / columns
            val free = info.viewportSize.height - chipsHeight - rows * (cardHeight + with(density) { CARD_GAP.roundToPx() })
            with(density) { max(RudeusSpacing.md.roundToPx(), free).toDp() }
        }
    }
    DisposableEffect(shelf) {
        grid.requestScrollToChipsIfScrolledPast()
        onDispose { }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CARD_MIN_WIDTH),
        modifier = Modifier.fillMaxSize(),
        state = grid,
        contentPadding = PaddingValues(start = RudeusSpacing.screenEdge, end = RudeusSpacing.screenEdge, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
    ) {
        fullWidthItem(HERO_KEY) { hero() }
        fullWidthItem(HEADLINE_KEY) {
            Text(t(StringId.capture_or_pick), style = MaterialTheme.typography.titleMedium, color = RudeusColors.Ink)
        }
        stickyHeader(key = FILTER_CHIPS_KEY) {
            Row(
                Modifier
                    .onSizeChanged { chipsHeight = it.height }
                    .fillMaxWidth()
                    .background(RudeusColors.Paper)
                    .padding(vertical = RudeusSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.sm),
            ) {
                Shelf.entries.forEach { filter ->
                    Chip(t(filter.label), isOn = filter == shelf, onClick = { show(filter) })
                }
            }
        }
        if (characters.isEmpty()) {
            item(key = EMPTY_KEY, span = { GridItemSpan(maxLineSpan) }) {
                EmptyCard(
                    t(StringId.capture_empty),
                    hint = if (shelf == Shelf.MINE) t(StringId.capture_empty_mine_hint) else null,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        itemsIndexed(characters, key = { _, c -> c.id }) { index, c ->
            val entranceDelayMs = remember {
                when {
                    !entered.add(c.id) -> null
                    reduced || grid.isScrollInProgress -> 0L
                    else -> (index + ITEMS_ABOVE_CARDS - grid.firstVisibleItemIndex).coerceIn(0, ENTRANCE_MAX_STAGGER) * ENTRANCE_STAGGER_MS
                }
            }
            BundledCard(
                c,
                selected = c.id == selectedId,
                entranceDelayMs = entranceDelayMs,
                onClick = { onPick(c) },
                // The card's own entrance replaces the fade in.
                modifier = (if (reduced) Modifier.animateItem(fadeInSpec = null, placementSpec = null) else Modifier.animateItem(fadeInSpec = null))
                    .fillMaxWidth()
                    .onSizeChanged { cardHeight = it.height },
            )
        }
    }
}

private fun LazyGridState.requestScrollToChipsIfScrolledPast() {
    val chips = layoutInfo.visibleItemsInfo.firstOrNull { it.key == FILTER_CHIPS_KEY } ?: return
    if (firstVisibleItemIndex > chips.index) {
        requestScrollToItem(index = chips.index, scrollOffset = -layoutInfo.viewportStartOffset)
    }
}

private fun LazyGridScope.fullWidthItem(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/** Wordmark, title and the hero card; the example drawing takes all the height the rest leaves in [modifier]. */
@Composable
private fun Hero(
    offline: Boolean,
    onOpenSettings: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
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
        Spacer(Modifier.height(RudeusSpacing.md))
        if (offline) {
            Pill(t(StringId.capture_offline_note), dot = RudeusColors.Leaf)
            Spacer(Modifier.height(RudeusSpacing.sm))
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
