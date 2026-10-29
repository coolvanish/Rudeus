package kz.rudy.ar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

/**
 * A character shipped in the APK (CLI-generated, DESIGN.md "Capture"). The card draws [spritePng] when present,
 * else the host's [thumbnail], else a glyph.
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
                enabled = !offline,
                onClick = onTakePhoto,
                modifier = Modifier.fillMaxWidth(),
            )
            if (onPickPhoto != null && !offline) {
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
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.md)) {
                    items(bundled, key = { it.id }) { c ->
                        CharacterCard(c, selected = c.id == selectedId, onClick = { onPickBundled(c) })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            GhostButton(text = t(StringId.metrics_button), glyph = Glyph.METRICS, onClick = onOpenMetrics)
        }
    }
}

/**
 * Bundled card width. The compact ability chip needs ~8 + 86 + 8 dp for the longest label ("Жасырыну", 16 sp bold
 * + tracking) plus the 3 dp outline; 144 dp leaves 128 dp inside the card's 8 dp padding.
 */
private val CARD_WIDTH = 144.dp

@Composable
private fun CharacterCard(c: BundledCharacter, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(RudeusRadius.card)
    Column(
        Modifier
            .width(CARD_WIDTH)
            .clip(shape)
            .background(RudeusColors.PaperDeep)
            .border(RudeusSpacing.stroke, if (selected) RudeusColors.Sun else RudeusColors.Ink, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(RudeusSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
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
