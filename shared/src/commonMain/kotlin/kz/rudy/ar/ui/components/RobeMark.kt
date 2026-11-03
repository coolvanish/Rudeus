package kz.rudy.ar.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import kz.rudy.ar.ui.theme.RudeusColors

private class RobePiece(d: String, val fill: Color?, val stroke: Color?, val width: Float) {
    val path: Path = PathParser().parsePathString(d).toPath()
}

/** The launcher icon's shapes, 108 grid (res/drawable-v24/ic_launcher_foreground.xml); eyes and sparkles are drawn apart. */
private val RobePieces by lazy {
    listOf(
        RobePiece("M40 53C30 58 20 66 16 78C13 88 12 98 11 108H97C96 98 95 88 92 78C88 66 78 58 68 53Z", Color(0xFF8F929C), Color(0xFF2B1D16), 1.9f),
        RobePiece("M16 78C13 88 12 98 11 108H26C26 96 27 84 30 72C25 73 20 75 16 78Z", Color(0xFF6C6F7A), null, 0f),
        RobePiece("M92 78C95 88 96 98 97 108H82C82 96 81 84 78 72C83 73 88 75 92 78Z", Color(0xFF6C6F7A), null, 0f),
        RobePiece("M30 72C27 84 26 96 26 108", null, Color(0xFF2B1D16), 1.9f),
        RobePiece("M78 72C81 84 82 96 82 108", null, Color(0xFF2B1D16), 1.9f),
        RobePiece("M54 68L66 108H42Z", Color(0xFF3A3944), Color(0xFF2B1D16), 1.9f),
        RobePiece("M54 68L42 108H35L48 66Z", Color(0xFFAEB1BA), Color(0xFF2B1D16), 1.9f),
        RobePiece("M54 68L66 108H73L60 66Z", Color(0xFFAEB1BA), Color(0xFF2B1D16), 1.9f),
        RobePiece("M44 82.5H64V86.5H43Z", Color(0xFF4A3226), Color(0xFF2B1D16), 1.52f),
        RobePiece("M41 94.5H67V98.5H40Z", Color(0xFF4A3226), Color(0xFF2B1D16), 1.52f),
        RobePiece("M51.5 81.6H56.5V87.4H51.5Z", Color(0xFFF3EAD3), Color(0xFF2B1D16), 1.33f),
        RobePiece("M51.5 93.6H56.5V99.4H51.5Z", Color(0xFFF3EAD3), Color(0xFF2B1D16), 1.33f),
        RobePiece("M54 19C60 23 70 27 74 38C77 47 75 56 69 62C64 67 59 71 54 74C49 71 44 67 39 62C33 56 31 47 34 38C38 27 48 23 54 19Z", Color(0xFF8F929C), Color(0xFF2B1D16), 1.9f),
        RobePiece("M38 37C41 31 46 28 51 25.5C46 30 42 34 40.5 41C39.3 47 40 52 43 57C38 54 35.5 45 38 37Z", Color(0xFFAEB1BA), null, 0f),
        RobePiece("M38 57C42 62 47 66 52 68.5", null, Color(0xFF2B1D16), 1.33f),
        RobePiece("M70 57C66 62 61 66 56 68.5", null, Color(0xFF2B1D16), 1.33f),
        RobePiece("M54 28C60 31 66.5 36 66.5 44C66.5 52 60 58 54 64C48 58 41.5 52 41.5 44C41.5 36 48 31 54 28Z", Color(0xFF26252E), Color(0xFF2B1D16), 1.9f),
        RobePiece("M45.6 38.4C47.5 33.5 51 30.6 54 29.6C57 30.6 60.5 33.5 62.4 38.4C60.6 39.4 58.8 39 57.6 37.6C56.8 39.2 55.2 40 53.6 39.6C52.6 39.2 51.8 38.4 51.4 37.4C50 39.4 47.6 39.8 45.6 38.4Z", Color(0xFF9A5B2C), Color(0xFF2B1D16), 1.14f),
    )
}

private val Sparkle by lazy { PathParser().parsePathString("M0 -5Q0.6 -0.6 5 0Q0.6 0.6 0 5Q-0.6 0.6 -5 0Q-0.6 -0.6 0 -5Z").toPath() }

/**
 * The launcher icon as a round badge: Sun disc, robe clipped to it. A [radius] of 96 dp at the window centre sits exactly
 * on the system splash's icon, so the intro takes over without a jump. [blink] 1 = eyes open, 0 = shut; [twinkle] scales
 * the two sparkles.
 */
internal fun DrawScope.drawRobeBadge(center: Offset, radius: Float, blink: Float = 1f, twinkle: Float = 1f) {
    val k = radius / 36f // the launcher mask shows the middle 72 of the 108 grid
    val disc = Path().apply { addOval(Rect(center, radius)) }
    clipPath(disc) {
        drawRect(RudeusColors.Sun, topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2))
        withTransform({
            translate(center.x - 54f * k, center.y - 54f * k)
            scale(k, k, Offset.Zero)
        }) {
            RobePieces.forEach { p ->
                p.fill?.let { drawPath(p.path, it) }
                p.stroke?.let { drawPath(p.path, it, style = Stroke(p.width, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            }
            robeEye(48.6f, 48.9f, 49.8f, blink)
            robeEye(59.4f, 59.1f, 60f, blink)
            robeSparkle(81f, 34.5f, 0.9f * twinkle)
            robeSparkle(30.5f, 34f, 0.6f * twinkle)
        }
    }
}

private fun DrawScope.robeEye(x: Float, pupilX: Float, glintX: Float, blink: Float) {
    val ry = 4.5f * blink.coerceAtLeast(0.1f)
    drawOval(RudeusColors.Paper, topLeft = Offset(x - 3.7f, 46.5f - ry), size = Size(7.4f, ry * 2))
    if (blink > 0.4f) {
        drawCircle(Color(0xFF14110E), 2.4f * blink, Offset(pupilX, 47f))
        drawCircle(RudeusColors.Paper, 0.9f * blink, Offset(glintX, 45.8f))
    }
}

private fun DrawScope.robeSparkle(x: Float, y: Float, s: Float) {
    withTransform({
        translate(x, y)
        scale(s, s, Offset.Zero)
    }) { drawPath(Sparkle, RudeusColors.Paper) }
}
