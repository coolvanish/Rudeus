package kz.rudy.ar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

/** Borderless Cream picker card, 124 dp: [art] on a Paper well, name, ability tag. Selected = Ink ring + Sun check. */
@Composable
fun CharacterCard(
    name: String,
    ability: Ability,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    art: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(RudeusRadius.card)
    Box(modifier.width(124.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(RudeusColors.Cream)
                .then(if (selected) Modifier.border(RudeusSpacing.stroke, RudeusColors.Ink, shape) else Modifier)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = RudeusSpacing.sm, end = RudeusSpacing.sm, top = RudeusSpacing.sm, bottom = RudeusSpacing.smd),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(RudeusColors.Paper)
                    .padding(7.dp),
                contentAlignment = Alignment.Center,
                content = art,
            )
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = RudeusColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = RudeusSpacing.xs),
            )
            AbilityTag(ability, Modifier.padding(start = RudeusSpacing.xs))
        }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(RudeusColors.Sun)
                    .border(RudeusSpacing.stroke, RudeusColors.Ink, CircleShape),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.CHECK, RudeusColors.Ink, size = 16.dp, strokeWidth = 3f) }
        }
    }
}
