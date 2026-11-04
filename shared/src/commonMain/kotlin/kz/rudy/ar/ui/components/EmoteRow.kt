package kz.rudy.ar.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.rudy.ar.core.emote.Emote
import kz.rudy.ar.ui.Strings
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.LocalReducedMotion
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

/** Each emote's column: wide enough for "Сау бол" / "Крутись" under a 56 dp button. */
private val EMOTE_COLUMN = 64.dp

/**
 * T31: the five emote buttons in `Emote` order, centred above the Scan / Jump row: round 56 dp, Paper fill with a
 * 2 dp Ink stroke and glyph (InkMuted when disabled), one word on a Paper label under each. Press = scale 0.96 like the
 * other buttons; reduced motion keeps the scale at 1 (the pressed fill is the only change).
 */
@Composable
fun EmoteRow(enabled: Boolean, onEmote: (Emote) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.xs), verticalAlignment = Alignment.Top) {
        Emote.entries.forEach { e -> EmoteButton(e, enabled) { onEmote(e) } }
    }
}

@Composable
private fun EmoteButton(emote: Emote, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) 0.96f else 1f,
        animationSpec = if (pressed) tween(RudeusMotion.BUTTON_PRESS_MS, easing = RudeusMotion.Standard) else RudeusMotion.pop(),
        label = "press",
    )
    val tint = if (enabled) RudeusColors.Ink else RudeusColors.InkMuted
    Column(
        Modifier
            .width(EMOTE_COLUMN)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RudeusSpacing.xs),
    ) {
        Box(
            Modifier
                .size(RudeusSpacing.touchTarget)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(if (pressed) RudeusColors.CreamDeep else RudeusColors.Paper)
                .border(RudeusSpacing.stroke, tint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(emote.glyph(), tint, size = 24.dp)
        }
        Text(
            t(Strings.emoteName(emote)),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(RudeusRadius.tag))
                .background(RudeusColors.Paper)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
