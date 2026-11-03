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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import kz.rudy.ar.ui.theme.LocalReducedMotion
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMotion

/** A top-left bracket on a 48 grid, from the mockups. */
private val BRACKET by lazy { PathParser().parsePathString("M6 42V18A12 12 0 0 1 18 6h24").toPath() }

/**
 * The 3-5 s "looking around" scan animation (DESIGN.md "Motion"): breathing Sun corner brackets, a pair of eyes whose
 * pupils sweep left-right with a blink, and a linear progress arc driven by [progress] (0..1, from the real scan
 * duration). Everything carries an Ink edge so it reads on a white table. The "Looking…" pill is the screen's, above
 * its buttons. Reduced motion: brackets and eyes are static, only the arc animates.
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
        Canvas(Modifier.size(260.dp)) {
            val unit = 52.dp.toPx() / 48f
            scale(bracketScale, pivot = center) {
                drawCornerBrackets(BRACKET, 0f, unit, RudeusColors.Ink, 10f, StrokeCap.Round)
                drawCornerBrackets(BRACKET, 0f, unit, RudeusColors.Sun, 5f, StrokeCap.Round)
            }

            val r = 66.dp.toPx()
            val ringTopLeft = Offset(center.x - r, center.y - r)
            val ringSize = Size(r * 2, r * 2)
            drawCircle(RudeusColors.Ink.copy(alpha = 0.9f), r, center, style = Stroke(13.dp.toPx()))
            drawCircle(RudeusColors.Paper, r, center, style = Stroke(8.dp.toPx()))
            drawArc(
                RudeusColors.Sun, -90f, 360f * arc, false,
                topLeft = ringTopLeft, size = ringSize,
                style = Stroke(8.dp.toPx(), cap = StrokeCap.Round),
            )

            val eyeW = 34.dp.toPx()
            val eyeH = 44.dp.toPx() * eyeOpen
            val edge = 2.5.dp.toPx()
            val pupilR = 7.dp.toPx()
            for (side in listOf(-1f, 1f)) {
                val ec = Offset(center.x + side * 22.dp.toPx(), center.y)
                val topLeft = Offset(ec.x - eyeW / 2, ec.y - eyeH / 2)
                drawOval(RudeusColors.Paper, topLeft, Size(eyeW, eyeH))
                drawOval(RudeusColors.Ink, topLeft, Size(eyeW, eyeH), style = Stroke(edge))
                if (eyeOpen > 0.3f) {
                    drawCircle(RudeusColors.Ink, pupilR, Offset(ec.x + pupilX * (eyeW / 2 - pupilR - edge), ec.y))
                }
            }
        }
    }
}
