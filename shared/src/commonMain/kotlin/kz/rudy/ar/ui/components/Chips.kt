package kz.rudy.ar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.ui.Lang
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.Strings
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

/**
 * A 48 dp paper chip inside a 56 dp hit area. Glyph + 1-3 word label.
 * [accent] colours the glyph and outline (Sky when "on", Ink otherwise).
 */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    accent: Color = RudeusColors.Ink,
    container: Color = RudeusColors.Paper,
    contentColor: Color = RudeusColors.Ink,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(RudeusRadius.full)
    Box(
        modifier
            .sizeIn(minHeight = RudeusSpacing.touchTarget, minWidth = RudeusSpacing.touchTarget)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(48.dp)
                .clip(shape)
                .background(container)
                .border(RudeusSpacing.stroke, accent, shape)
                .padding(horizontal = RudeusSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (glyph != null) {
                GlyphIcon(glyph, accent, size = 24.dp)
                Spacer(Modifier.width(RudeusSpacing.sm))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = contentColor, maxLines = 1)
        }
    }
}

/** The character's one ability, shown top-left during play. Tapping it makes the character say its line. */
@Composable
fun AbilityChip(ability: Ability, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Chip(
        text = t(Strings.abilityName(ability)),
        glyph = ability.glyph(),
        accent = RudeusColors.Sky,
        modifier = modifier,
        onClick = onClick,
    )
}

/** Leaf badge, shown when the API is unreachable and bundled characters are in use. */
@Composable
fun OfflineBadge(modifier: Modifier = Modifier) {
    Chip(
        text = t(StringId.offline_badge),
        container = RudeusColors.Leaf,
        accent = RudeusColors.Leaf,
        contentColor = RudeusColors.Paper,
        modifier = modifier,
    )
}

/** Language switch; lives on the Capture screen only. */
@Composable
fun LangSwitch(lang: Lang, onChange: (Lang) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(RudeusRadius.full)
    Row(
        modifier
            .clip(shape)
            .background(RudeusColors.Paper)
            .border(RudeusSpacing.stroke, RudeusColors.Ink, shape)
            .padding(RudeusSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.xs),
    ) {
        Lang.entries.forEach { l ->
            val selected = l == lang
            val label = when (l) {
                Lang.KK -> StringId.lang_kk
                Lang.RU -> StringId.lang_ru
                Lang.EN -> StringId.lang_en
            }
            Box(
                Modifier
                    .sizeIn(minWidth = 56.dp, minHeight = 48.dp)
                    .clip(shape)
                    .background(if (selected) RudeusColors.Sun else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onChange(l) }
                    .padding(horizontal = RudeusSpacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(label.get(l), style = MaterialTheme.typography.labelLarge, color = RudeusColors.Ink)
            }
        }
    }
}
