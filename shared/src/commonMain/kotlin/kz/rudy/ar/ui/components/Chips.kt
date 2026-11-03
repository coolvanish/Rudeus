package kz.rudy.ar.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.Strings
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.LocalReducedMotion
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

/** A 48 dp HUD toggle: Paper with a 2 dp Ink stroke; [isOn] = SkyTint fill, Sky stroke, SkyDeep text. */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    isOn: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(RudeusRadius.full)
    val content = if (isOn) RudeusColors.SkyDeep else RudeusColors.Ink
    val height = RudeusSpacing.secondaryButton
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .height(height)
            .then(
                if (onClick != null) {
                    Modifier
                        .hitArea(height)
                        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                        .padding(vertical = hitSlack(height))
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(
                when {
                    pressed -> RudeusColors.CreamDeep
                    isOn -> RudeusColors.SkyTint
                    else -> RudeusColors.Paper
                },
            )
            .border(RudeusSpacing.stroke, if (isOn) RudeusColors.Sky else RudeusColors.Ink, shape)
            .padding(start = if (glyph != null) RudeusSpacing.smd else RudeusSpacing.md, end = RudeusSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.sm),
    ) {
        if (glyph != null) GlyphIcon(glyph, content, size = 22.dp)
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/** The character's one ability, shown top-left during play. Tapping it makes the character say its line. */
@Composable
fun AbilityChip(ability: Ability, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Chip(text = t(Strings.abilityName(ability)), glyph = ability.glyph(), modifier = modifier, onClick = onClick)
}

enum class TagTone(internal val fill: Color, internal val content: Color) {
    NEUTRAL(RudeusColors.CreamDeep, RudeusColors.InkSoft),
    SKY(RudeusColors.SkyTint, RudeusColors.SkyDeep),
    LEAF(RudeusColors.LeafTint, RudeusColors.Leaf),
    TOMATO(RudeusColors.TomatoTint, RudeusColors.Tomato),
}

/** Small non-interactive label, 24 dp. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, tone: TagTone = TagTone.NEUTRAL, glyph: Glyph? = null) {
    Row(
        modifier
            .height(24.dp)
            .clip(RoundedCornerShape(RudeusRadius.tag))
            .background(tone.fill)
            .padding(horizontal = RudeusSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.xs),
    ) {
        if (glyph != null) GlyphIcon(glyph, tone.content, size = 14.dp, strokeWidth = 2.6f)
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 0.24.sp),
            color = tone.content,
            maxLines = 1,
        )
    }
}

/** The ability as a small Sky tag on cards. */
@Composable
fun AbilityTag(ability: Ability, modifier: Modifier = Modifier) {
    Tag(t(Strings.abilityName(ability)), modifier, TagTone.SKY, ability.glyph())
}

/** A status dot; always next to a word (DESIGN.md: colour is never the only signal). */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** HUD status over the camera: Paper with a 1 dp Line edge, 40 dp. [quiet] is the second, softer line. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, quiet: Boolean = false, dot: Color? = null) {
    val shape = RoundedCornerShape(RudeusRadius.full)
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(RudeusColors.Paper)
            .border(RudeusSpacing.hairline, RudeusColors.Line, shape)
            .padding(horizontal = RudeusSpacing.md, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.sm),
    ) {
        if (dot != null) StatusDot(dot)
        Text(
            text,
            style = if (quiet) MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.labelLarge,
            color = if (quiet) RudeusColors.InkSoft else RudeusColors.Ink,
            textAlign = TextAlign.Center,
        )
    }
}

/** Leaf-dot pill, shown when the API is unreachable and bundled characters are in use. */
@Composable
fun OfflineBadge(modifier: Modifier = Modifier) {
    Pill(t(StringId.offline_badge), modifier, dot = RudeusColors.Leaf)
}

/**
 * Paper pill with a 2 dp Ink stroke and a 4 dp inset; the [selected] option fills Ink with Paper text.
 * [check] adds a check glyph to the selected option (Table | Floor); [fill] stretches the options over the full width.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    check: Boolean = false,
    fill: Boolean = false,
) {
    val shape = RoundedCornerShape(RudeusRadius.full)
    Row(
        modifier
            // The frame is part of the control: a tap on it must not fall through to the AR view and place the character.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .clip(shape)
            .background(RudeusColors.Paper)
            .border(RudeusSpacing.stroke, RudeusColors.Ink, shape)
            .padding(RudeusSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.xs),
    ) {
        options.forEach { option ->
            val on = option == selected
            val content = if (on) RudeusColors.Paper else RudeusColors.Ink
            val height = 40.dp
            Row(
                (if (fill) Modifier.weight(1f) else Modifier)
                    .height(height)
                    .hitArea(height)
                    .selectable(selected = on, interactionSource = null, indication = null, role = Role.RadioButton) { onSelect(option) }
                    .padding(vertical = hitSlack(height))
                    .clip(shape)
                    .background(if (on) RudeusColors.Ink else Color.Transparent)
                    .padding(horizontal = RudeusSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                if (check && on) GlyphIcon(Glyph.CHECK, content, size = 18.dp, strokeWidth = 2.6f)
                Text(label(option), style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = content, maxLines = 1)
            }
        }
    }
}

/**
 * On/off, 56×34; on fills Sky. Drawn only: the row around it owns the toggle and its 56+ dp hit area.
 */
@Composable
fun Switch(checked: Boolean, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val knob by animateDpAsState(
        targetValue = if (checked) 22.dp else 0.dp,
        animationSpec = if (reduced) tween(RudeusMotion.REDUCED_MS) else RudeusMotion.pop(),
        label = "knob",
    )
    val shape = RoundedCornerShape(RudeusRadius.full)
    Box(
        modifier
            .size(56.dp, 34.dp)
            .clip(shape)
            .background(if (checked) RudeusColors.Sky else RudeusColors.Paper)
            .border(RudeusSpacing.stroke, if (checked) RudeusColors.Sky else RudeusColors.Ink, shape)
            .padding(6.dp),
    ) {
        Box(Modifier.offset(x = knob).size(22.dp).clip(CircleShape).background(if (checked) RudeusColors.Paper else RudeusColors.Ink))
    }
}
