package kz.rudy.ar.ui.components

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.LocalReducedMotion
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusSpacing

/**
 * The 3-5 s "looking around" scan animation (DESIGN.md "Motion"):
 * breathing Sun corner brackets, a pair of eyes whose pupils sweep left-right with a blink,
 * and a linear progress arc driven by [progress] (0..1, from the real scan duration).
 * Reduced motion: brackets and eyes are static, only the arc animates.
 */
@Composable
fun ScanOverlay(active: Boolean, progress: Float, modifier: Modifier = Modifier) {
    if (!active) return
    val reduced = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "scan")
    val pupil by transition.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(RudeusMotion.PUPIL_SWEEP_MS, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "pupil",
    )
    val breathe by transition.animateFloat(
        initialValue = 1f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(RudeusMotion.BRACKET_BREATHE_MS, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "breathe",
    )
    val blink by transition.animateFloat(
        initialValue = 1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 2800
                1f at 0
                1f at 2600
                0.1f at 2660 using LinearEasing
                1f at 2720
            },
        ),
        label = "blink",
    )
    val arc by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(RudeusMotion.STAGE_PROGRESS_MS, easing = LinearEasing),
        label = "arc",
    )
    val pupilX = if (reduced) 0f else pupil
    val bracketScale = if (reduced) 1f else breathe
    val eyeOpen = if (reduced) 1f else blink

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(RudeusSpacing.xl)) {
            val stroke = Stroke(RudeusSpacing.stroke.toPx() * 1.5f, cap = StrokeCap.Round)
            val len = size.minDimension * 0.12f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val hw = size.width / 2f * bracketScale
            val hh = size.height / 2f * bracketScale
            val corners = listOf(
                Offset(cx - hw, cy - hh) to Offset(1f, 1f),
                Offset(cx + hw, cy - hh) to Offset(-1f, 1f),
                Offset(cx - hw, cy + hh) to Offset(1f, -1f),
                Offset(cx + hw, cy + hh) to Offset(-1f, -1f),
            )
            for ((c, d) in corners) {
                drawLine(RudeusColors.Sun, c, Offset(c.x + d.x * len, c.y), stroke.width, StrokeCap.Round)
                drawLine(RudeusColors.Sun, c, Offset(c.x, c.y + d.y * len), stroke.width, StrokeCap.Round)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(160.dp)) {
                val r = size.minDimension / 2f
                val ringStroke = RudeusSpacing.stroke.toPx() * 2f
                drawCircle(RudeusColors.Scrim, r)
                drawArc(
                    RudeusColors.Paper.copy(alpha = 0.3f), -90f, 360f, false,
                    topLeft = Offset(ringStroke, ringStroke), size = Size(size.width - ringStroke * 2, size.height - ringStroke * 2),
                    style = Stroke(ringStroke, cap = StrokeCap.Round),
                )
                drawArc(
                    RudeusColors.Sun, -90f, 360f * arc, false,
                    topLeft = Offset(ringStroke, ringStroke), size = Size(size.width - ringStroke * 2, size.height - ringStroke * 2),
                    style = Stroke(ringStroke, cap = StrokeCap.Round),
                )
                val eyeR = r * 0.26f
                val pupilR = eyeR * 0.45f
                for (side in listOf(-1f, 1f)) {
                    val ec = Offset(center.x + side * r * 0.36f, center.y)
                    drawOval(
                        RudeusColors.Paper,
                        topLeft = Offset(ec.x - eyeR, ec.y - eyeR * eyeOpen),
                        size = Size(eyeR * 2, eyeR * 2 * eyeOpen),
                    )
                    if (eyeOpen > 0.3f) {
                        drawCircle(RudeusColors.Ink, pupilR, Offset(ec.x + pupilX * (eyeR - pupilR) * 0.8f, ec.y))
                    }
                }
            }
            Spacer(Modifier.height(RudeusSpacing.md))
            ScrimPill(t(StringId.scanning))
        }
    }
}
