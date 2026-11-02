package kz.rudy.ar.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import kz.rudy.ar.ui.theme.LocalReducedMotion
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

/** How far a control drawn [visual] tall falls short of the 56 dp touch area, per side. */
internal fun hitSlack(visual: Dp): Dp = ((RudeusSpacing.touchTarget - visual) / 2).coerceAtLeast(0.dp)

/**
 * Gives what follows in the chain a real 56 dp touch area while the control keeps its [visual] height in the layout:
 * `.height(h).hitArea(h).clickable(..).padding(vertical = hitSlack(h))`, then the visuals. Compose's own touch-bounds
 * extension is no use here: it loses to any direct hit, and over the camera the AR view underneath is always one.
 */
internal fun Modifier.hitArea(visual: Dp): Modifier = layout { measurable, constraints ->
    val slack = hitSlack(visual).roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = slack * 2))
    layout(placeable.width, placeable.height - slack * 2) { placeable.place(0, -slack) }
}

/** The one primary action per screen: Sun under Ink text, 64 dp. Disabled has no alpha, so it holds over the camera. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    trailingGlyph: Glyph? = null,
    enabled: Boolean = true,
) {
    val tint = if (enabled) RudeusColors.Ink else RudeusColors.InkMuted
    ButtonSurface(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 18.sp),
        onClick = onClick,
        modifier = modifier,
        height = RudeusSpacing.primaryButton,
        sidePadding = 28.dp,
        glyph = glyph,
        trailingGlyph = trailingGlyph,
        glyphSize = 24.dp,
        enabled = enabled,
        fill = if (enabled) RudeusColors.Sun else RudeusColors.SunTint,
        pressedFill = RudeusColors.SunDeep,
        stroke = tint,
        content = tint,
    )
}

/**
 * Paper with a 2 dp Ink stroke, 48 dp: readable over the feed because it is paper.
 * [compact] is the 40 dp variant that sits inside a banner.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val tint = if (enabled) RudeusColors.Ink else RudeusColors.InkMuted
    ButtonSurface(
        text = text,
        style = if (compact) MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp) else MaterialTheme.typography.labelLarge,
        onClick = onClick,
        modifier = modifier,
        height = if (compact) 40.dp else RudeusSpacing.secondaryButton,
        sidePadding = if (compact) 14.dp else RudeusSpacing.screenEdge,
        glyph = glyph,
        glyphSize = 22.dp,
        enabled = enabled,
        fill = RudeusColors.Paper,
        pressedFill = RudeusColors.CreamDeep,
        stroke = tint,
        content = tint,
    )
}

/** Quiet action on a paper screen: no stroke, no fill until pressed. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
) {
    ButtonSurface(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        onClick = onClick,
        modifier = modifier,
        height = RudeusSpacing.secondaryButton,
        sidePadding = 14.dp,
        glyph = glyph,
        glyphSize = 22.dp,
        enabled = true,
        fill = Color.Transparent,
        pressedFill = RudeusColors.CreamDeep,
        stroke = null,
        content = RudeusColors.Ink,
    )
}

@Composable
private fun ButtonSurface(
    text: String,
    style: TextStyle,
    onClick: () -> Unit,
    modifier: Modifier,
    height: Dp,
    sidePadding: Dp,
    glyph: Glyph?,
    glyphSize: Dp,
    enabled: Boolean,
    fill: Color,
    pressedFill: Color,
    stroke: Color?,
    content: Color,
    trailingGlyph: Glyph? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) 0.96f else 1f,
        animationSpec = if (pressed) tween(RudeusMotion.BUTTON_PRESS_MS, easing = RudeusMotion.Standard) else RudeusMotion.pop(),
        label = "press",
    )
    val shape = RoundedCornerShape(RudeusRadius.full)
    Row(
        modifier
            .height(height)
            .hitArea(height)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = hitSlack(height))
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (pressed) pressedFill else fill)
            .then(if (stroke != null) Modifier.border(RudeusSpacing.stroke, stroke, shape) else Modifier)
            .padding(horizontal = sidePadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (glyph != null) GlyphIcon(glyph, content, size = glyphSize)
        Text(text, style = style, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailingGlyph != null) GlyphIcon(trailingGlyph, content, size = glyphSize)
    }
}
