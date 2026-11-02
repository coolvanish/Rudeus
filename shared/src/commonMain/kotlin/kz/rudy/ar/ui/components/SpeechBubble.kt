package kz.rudy.ar.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.rudy.ar.ui.theme.LocalReducedMotion
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

/**
 * Paper bubble with a 1 dp Line edge, a Sun tail pointing down (towards the character) and a Sun underline.
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
        val shape = RoundedCornerShape(RudeusRadius.bubble)
        Box(
            Modifier
                .padding(bottom = TAIL_HEIGHT)
                .widthIn(max = 260.dp)
                .drawWithContent {
                    drawContent()
                    val inset = 18.dp.toPx()
                    val line = 4.dp.toPx()
                    drawRoundRect(
                        RudeusColors.Sun,
                        topLeft = Offset(inset, size.height - line + 1.dp.toPx()),
                        size = Size(size.width - inset * 2, line),
                        cornerRadius = CornerRadius(line / 2),
                    )
                    val half = 11.dp.toPx()
                    val tail = Path().apply {
                        moveTo(size.width / 2 - half, size.height - 1.dp.toPx())
                        lineTo(size.width / 2 + half, size.height - 1.dp.toPx())
                        lineTo(size.width / 2, size.height + TAIL_HEIGHT.toPx())
                        close()
                    }
                    drawPath(tail, RudeusColors.Sun)
                }
                .clip(shape)
                .background(RudeusColors.Paper)
                .border(RudeusSpacing.hairline, RudeusColors.Line, shape)
                .padding(horizontal = 18.dp, vertical = RudeusSpacing.smd),
        ) {
            Crossfade(targetState = shown, animationSpec = tween(RudeusMotion.BANNER_EXIT_MS), label = "line") { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
                    color = RudeusColors.Ink,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private val TAIL_HEIGHT = 13.dp
