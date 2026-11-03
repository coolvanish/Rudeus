package kz.rudy.ar.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import kz.rudy.ar.ui.theme.RudeusColors
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
    Box(modifier.fillMaxSize().background(RudeusColors.Paper)) {
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
                val row = rememberLazyListState()
                // A keyed row holds its scroll on the old first card, so a card added in front would sit off-screen left.
                LaunchedEffect(bundled.first().id) { row.scrollToItem(0) }
                // Bleeds off the right edge so the row reads as "there is more".
                LazyRow(
                    state = row,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = RudeusSpacing.screenEdge),
                ) {
                    items(bundled, key = { it.id }) { c ->
                        BundledCard(c, selected = c.id == selectedId, onClick = { onPickBundled(c) })
                    }
                }
            }
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

@Composable
private fun BundledCard(c: BundledCharacter, selected: Boolean, onClick: () -> Unit) {
    CharacterCard(name = c.sheet.name, ability = c.sheet.ability, selected = selected, onClick = onClick) {
        val sprite = rememberSpriteBitmap(c.spritePng)
        val thumb = c.thumbnail
        when {
            sprite != null -> SpriteImage(sprite, contentDescription = null, modifier = Modifier.fillMaxSize())
            thumb != null -> thumb(Modifier.fillMaxSize())
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
