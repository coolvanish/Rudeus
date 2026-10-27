package kz.rudy.ar.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kz.rudy.ar.ui.theme.LocalReducedMotion
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

/**
 * The one primary action per screen. Sun on Ink, 64 dp tall, full radius, press scale 0.96.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) 0.96f else 1f,
        animationSpec = if (pressed) tween(RudeusMotion.BUTTON_PRESS_MS, easing = RudeusMotion.Standard) else RudeusMotion.pop(),
        label = "press",
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(RudeusRadius.full),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (pressed) RudeusColors.SunDeep else RudeusColors.Sun,
            contentColor = RudeusColors.Ink,
            disabledContainerColor = RudeusColors.Sun.copy(alpha = 0.5f),
            disabledContentColor = RudeusColors.Ink.copy(alpha = 0.6f),
        ),
        border = BorderStroke(RudeusSpacing.stroke, RudeusColors.Ink),
        contentPadding = PaddingValues(horizontal = RudeusSpacing.lg, vertical = 0.dp),
        modifier = modifier
            .height(RudeusSpacing.primaryButton)
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        ButtonContent(text, glyph, RudeusColors.Ink)
    }
}

/** Secondary, low-emphasis action: paper outline, Ink text. Readable over the feed because it is paper. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(RudeusRadius.full),
        colors = ButtonDefaults.buttonColors(
            containerColor = RudeusColors.Paper,
            contentColor = RudeusColors.Ink,
            disabledContainerColor = RudeusColors.Paper.copy(alpha = 0.6f),
            disabledContentColor = RudeusColors.InkSoft,
        ),
        border = BorderStroke(RudeusSpacing.stroke, RudeusColors.Ink),
        contentPadding = PaddingValues(horizontal = RudeusSpacing.md, vertical = 0.dp),
        modifier = modifier.heightIn(min = RudeusSpacing.touchTarget),
    ) {
        ButtonContent(text, glyph, RudeusColors.Ink)
    }
}

@Composable
private fun ButtonContent(text: String, glyph: Glyph?, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (glyph != null) {
            GlyphIcon(glyph, tint)
            Spacer(Modifier.width(RudeusSpacing.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

