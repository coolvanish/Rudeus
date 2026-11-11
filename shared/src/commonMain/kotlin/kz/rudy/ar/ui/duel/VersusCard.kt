package kz.rudy.ar.ui.duel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.domain.SpriteView
import kz.rudy.ar.ui.LocalLang
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.AbilityTag
import kz.rudy.ar.ui.components.FighterStatsCard
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.GlyphIcon
import kz.rudy.ar.ui.components.SpriteImage
import kz.rudy.ar.ui.components.rememberSpriteBitmap
import kz.rudy.ar.ui.screens.BundledCharacter
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

/**
 * The VS screen: the two sprites facing each other with "VS" between them, each with its name and ability, and under
 * them the two stats cards side by side (`FighterStatsCard(compact = true)`). Stateless. [mine] stands on the left and
 * looks right, [foe] on the right and looks left: a sprite drawn the other way round is mirrored.
 */
@Composable
fun VersusCard(mine: BundledCharacter, foe: BundledCharacter, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RudeusSpacing.smd)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.sm)) {
            Fighter(mine, mirror = looksRight(mine.sheet) == false, modifier = Modifier.weight(1f))
            Text(t(StringId.duel_vs), style = MaterialTheme.typography.displayLarge, color = RudeusColors.Ink, textAlign = TextAlign.Center)
            Fighter(foe, mirror = looksRight(foe.sheet) == true, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.sm)) {
            FighterStatsCard(mine.sheet, Modifier.weight(1f), compact = true)
            FighterStatsCard(foe.sheet, Modifier.weight(1f), compact = true)
        }
    }
}

/**
 * Which way the sprite looks: a SIDE view drawn to its own right (`facesRight`, false when the sheet does not say, as
 * everywhere else). A front or back view looks at the camera: it has no side to mirror.
 */
private fun looksRight(sheet: CharacterSheet): Boolean? =
    if ((sheet.baseView ?: SpriteView.SIDE) == SpriteView.SIDE) sheet.facesRight ?: false else null

/** The sprite on a Paper well in a Cream card, then the name and the ability tag. */
@Composable
private fun Fighter(c: BundledCharacter, mirror: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(RudeusRadius.card))
            .background(RudeusColors.Cream)
            .padding(RudeusSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(RudeusColors.Paper)
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) { FighterArt(c, mirror) }
        Text(
            c.sheet.nameIn(LocalLang.current.code),
            style = MaterialTheme.typography.titleMedium,
            color = RudeusColors.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AbilityTag(c.sheet.ability)
    }
}

/** The character's sprite (else the host's thumbnail, else a glyph), filling its box; [mirror] flips it left-right. */
@Composable
internal fun BoxScope.FighterArt(c: BundledCharacter, mirror: Boolean = false) {
    val density = LocalDensity.current
    val sprite = rememberSpriteBitmap(c.spritePng, maxWidthPx = with(density) { 220.dp.roundToPx() }, maxHeightPx = with(density) { 220.dp.roundToPx() })
    val art = Modifier.fillMaxSize().graphicsLayer { scaleX = if (mirror) -1f else 1f }
    val thumb = c.thumbnail
    when {
        sprite != null -> SpriteImage(sprite, contentDescription = null, modifier = art)
        thumb != null -> thumb(art)
        else -> GlyphIcon(Glyph.HIDE, RudeusColors.InkMuted, size = 48.dp)
    }
}
