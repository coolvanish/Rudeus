package kz.rudy.ar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kz.rudy.ar.core.domain.Ability

/**
 * Canvas-drawn glyphs (DESIGN.md: no icon dependency, no emoji in the chrome).
 * Every glyph sits next to a text label; the glyph itself is decorative.
 */
enum class Glyph { CAMERA, EYE, JUMP, FLY, ROLL, HIDE, BACK, PAUSE, FOOTPRINTS, EXPORT, CHECK, CROSS, METRICS, MIC }

fun Ability.glyph(): Glyph = when (this) {
    Ability.JUMP -> Glyph.JUMP
    Ability.FLY -> Glyph.FLY
    Ability.ROLL -> Glyph.ROLL
    Ability.HIDE -> Glyph.HIDE
}

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, tint) }
}

fun DrawScope.drawGlyph(glyph: Glyph, tint: Color) {
    val w = size.width
    val h = size.height
    val s = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(w * x, h * y)
    when (glyph) {
        Glyph.CAMERA -> {
            drawRoundRect(tint, p(0.08f, 0.28f), Size(w * 0.84f, h * 0.56f), androidx.compose.ui.geometry.CornerRadius(w * 0.12f), s)
            drawLine(tint, p(0.35f, 0.28f), p(0.42f, 0.14f), s.width, StrokeCap.Round)
            drawLine(tint, p(0.42f, 0.14f), p(0.58f, 0.14f), s.width, StrokeCap.Round)
            drawLine(tint, p(0.58f, 0.14f), p(0.65f, 0.28f), s.width, StrokeCap.Round)
            drawCircle(tint, w * 0.16f, p(0.5f, 0.56f), style = s)
        }
        Glyph.EYE -> {
            val path = Path().apply {
                moveTo(w * 0.06f, h * 0.5f)
                quadraticTo(w * 0.5f, h * 0.05f, w * 0.94f, h * 0.5f)
                quadraticTo(w * 0.5f, h * 0.95f, w * 0.06f, h * 0.5f)
                close()
            }
            drawPath(path, tint, style = s)
            drawCircle(tint, w * 0.14f, p(0.5f, 0.5f))
        }
        Glyph.JUMP -> {
            val path = Path().apply {
                moveTo(w * 0.1f, h * 0.85f)
                quadraticTo(w * 0.5f, h * -0.3f, w * 0.9f, h * 0.85f)
            }
            drawPath(path, tint, style = s)
            drawLine(tint, p(0.9f, 0.85f), p(0.72f, 0.72f), s.width, StrokeCap.Round)
            drawLine(tint, p(0.9f, 0.85f), p(0.84f, 0.62f), s.width, StrokeCap.Round)
        }
        Glyph.FLY -> {
            val path = Path().apply {
                moveTo(w * 0.05f, h * 0.55f)
                quadraticTo(w * 0.28f, h * 0.2f, w * 0.5f, h * 0.55f)
                quadraticTo(w * 0.72f, h * 0.2f, w * 0.95f, h * 0.55f)
            }
            drawPath(path, tint, style = s)
            drawCircle(tint, w * 0.07f, p(0.5f, 0.78f))
        }
        Glyph.ROLL -> {
            drawCircle(tint, w * 0.36f, p(0.5f, 0.5f), style = s)
            drawLine(tint, p(0.5f, 0.5f), p(0.72f, 0.3f), s.width, StrokeCap.Round)
            drawLine(tint, p(0.5f, 0.5f), p(0.5f, 0.82f), s.width, StrokeCap.Round)
        }
        Glyph.HIDE -> {
            drawRoundRect(tint, p(0.12f, 0.35f), Size(w * 0.76f, h * 0.5f), androidx.compose.ui.geometry.CornerRadius(w * 0.1f), s)
            drawCircle(tint, w * 0.18f, p(0.5f, 0.35f), style = s)
        }
        Glyph.BACK -> {
            drawLine(tint, p(0.62f, 0.15f), p(0.28f, 0.5f), s.width, StrokeCap.Round)
            drawLine(tint, p(0.28f, 0.5f), p(0.62f, 0.85f), s.width, StrokeCap.Round)
        }
        Glyph.PAUSE -> {
            drawRoundRect(tint, p(0.2f, 0.15f), Size(w * 0.2f, h * 0.7f), androidx.compose.ui.geometry.CornerRadius(w * 0.06f))
            drawRoundRect(tint, p(0.6f, 0.15f), Size(w * 0.2f, h * 0.7f), androidx.compose.ui.geometry.CornerRadius(w * 0.06f))
        }
        Glyph.FOOTPRINTS -> {
            drawOval(tint, p(0.15f, 0.45f), Size(w * 0.28f, h * 0.42f))
            drawOval(tint, p(0.57f, 0.12f), Size(w * 0.28f, h * 0.42f))
            drawCircle(tint, w * 0.06f, p(0.22f, 0.38f))
            drawCircle(tint, w * 0.06f, p(0.36f, 0.38f))
            drawCircle(tint, w * 0.06f, p(0.64f, 0.06f))
            drawCircle(tint, w * 0.06f, p(0.78f, 0.06f))
        }
        Glyph.EXPORT -> {
            drawLine(tint, p(0.5f, 0.12f), p(0.5f, 0.62f), s.width, StrokeCap.Round)
            drawLine(tint, p(0.5f, 0.12f), p(0.3f, 0.32f), s.width, StrokeCap.Round)
            drawLine(tint, p(0.5f, 0.12f), p(0.7f, 0.32f), s.width, StrokeCap.Round)
            val tray = Path().apply {
                moveTo(w * 0.15f, h * 0.6f); lineTo(w * 0.15f, h * 0.88f); lineTo(w * 0.85f, h * 0.88f); lineTo(w * 0.85f, h * 0.6f)
            }
            drawPath(tray, tint, style = s)
        }
        Glyph.CHECK -> {
            drawLine(tint, p(0.15f, 0.55f), p(0.4f, 0.8f), s.width, StrokeCap.Round)
            drawLine(tint, p(0.4f, 0.8f), p(0.87f, 0.25f), s.width, StrokeCap.Round)
        }
        Glyph.CROSS -> {
            drawLine(tint, p(0.2f, 0.2f), p(0.8f, 0.8f), s.width, StrokeCap.Round)
            drawLine(tint, p(0.8f, 0.2f), p(0.2f, 0.8f), s.width, StrokeCap.Round)
        }
        Glyph.METRICS -> {
            drawLine(tint, p(0.2f, 0.85f), p(0.2f, 0.5f), s.width, StrokeCap.Round)
            drawLine(tint, p(0.5f, 0.85f), p(0.5f, 0.2f), s.width, StrokeCap.Round)
            drawLine(tint, p(0.8f, 0.85f), p(0.8f, 0.4f), s.width, StrokeCap.Round)
        }
        Glyph.MIC -> {
            // T24 push-to-talk: capsule, the cradle under it, the stand and its foot.
            drawRoundRect(tint, p(0.36f, 0.08f), Size(w * 0.28f, h * 0.46f), androidx.compose.ui.geometry.CornerRadius(w * 0.14f), s)
            val cradle = Path().apply {
                moveTo(w * 0.2f, h * 0.42f)
                quadraticTo(w * 0.5f, h * 0.88f, w * 0.8f, h * 0.42f)
            }
            drawPath(cradle, tint, style = s)
            drawLine(tint, p(0.5f, 0.66f), p(0.5f, 0.88f), s.width, StrokeCap.Round)
            drawLine(tint, p(0.32f, 0.88f), p(0.68f, 0.88f), s.width, StrokeCap.Round)
        }
    }
}
