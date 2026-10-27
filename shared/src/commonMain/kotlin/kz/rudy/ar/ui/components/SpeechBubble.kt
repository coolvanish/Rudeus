package kz.rudy.ar.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kz.rudy.ar.ui.theme.LocalReducedMotion
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

/**
 * Paper bubble with a crayon outline and a tail pointing down (towards the character).
 * Pops in with the Pop spring from the tail; a new [text] crossfades without exiting.
 * The caller owns timing (DESIGN.md: visible >= 2500 ms).
 */
@Composable
fun SpeechBubble(text: String?, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val origin = TransformOrigin(0.5f, 1f)
    AnimatedVisibility(
        visible = text != null,
        modifier = modifier,
        enter = if (reduced) fadeIn(tween(RudeusMotion.REDUCED_MS)) else
            scaleIn(RudeusMotion.pop(), initialScale = 0.6f, transformOrigin = origin) + fadeIn(tween(RudeusMotion.BUBBLE_FADE_MS)),
        exit = if (reduced) fadeOut(tween(RudeusMotion.REDUCED_MS)) else
            scaleOut(tween(RudeusMotion.BANNER_EXIT_MS, easing = RudeusMotion.Standard), targetScale = 0.9f, transformOrigin = origin) +
                fadeOut(tween(RudeusMotion.BANNER_EXIT_MS)),
    ) {
        val shown = text ?: ""
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val shape = RoundedCornerShape(RudeusRadius.bubble)
            Box(
                Modifier
                    .widthIn(max = 320.dp)
                    .clip(shape)
                    .background(RudeusColors.Paper)
                    .border(RudeusSpacing.stroke, RudeusColors.Ink, shape)
                    .padding(horizontal = RudeusSpacing.lg, vertical = RudeusSpacing.md),
            ) {
                Crossfade(targetState = shown, animationSpec = tween(RudeusMotion.BANNER_EXIT_MS), label = "line") { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.titleLarge,
                        color = RudeusColors.Ink,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            BubbleTail()
        }
    }
}

@Composable
private fun BubbleTail() {
    Canvas(Modifier.size(28.dp, 18.dp)) {
        val stroke = RudeusSpacing.stroke.toPx()
        val path = Path().apply {
            moveTo(size.width * 0.2f, 0f)
            lineTo(size.width * 0.5f, size.height - stroke)
            lineTo(size.width * 0.8f, 0f)
            close()
        }
        drawPath(path, RudeusColors.Paper)
        drawLine(RudeusColors.Ink, Offset(size.width * 0.2f, 0f), Offset(size.width * 0.5f, size.height - stroke), stroke)
        drawLine(RudeusColors.Ink, Offset(size.width * 0.8f, 0f), Offset(size.width * 0.5f, size.height - stroke), stroke)
        // Hide the bubble's bottom outline where the tail joins.
        drawLine(RudeusColors.Paper, Offset(size.width * 0.2f + stroke, 0f), Offset(size.width * 0.8f - stroke, 0f), stroke * 1.2f)
    }
}

