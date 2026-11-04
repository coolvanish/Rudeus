package kz.rudy.ar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kz.rudy.ar.core.domain.Ability

private const val GRID = 24f

private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) =
    "M${cx - rx} $cy a$rx $ry 0 1 0 ${2 * rx} 0 a$rx $ry 0 1 0 ${-2 * rx} 0 "

private fun circle(cx: Float, cy: Float, r: Float) = ellipse(cx, cy, r, r)

/**
 * Line glyphs on a 24 grid (DESIGN.md: no icon dependency, no emoji in the chrome); the paths are those of
 * docs/design-v2/foundations/icons.html. Every glyph sits next to a text label; the glyph itself is decorative.
 */
enum class Glyph(internal val svg: String) {
    CAMERA("M6 7h12a3 3 0 0 1 3 3v7a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3v-7a3 3 0 0 1 3-3z M8.5 7l1.5-3h4l1.5 3 " + circle(12f, 13.5f, 3.5f)),
    GALLERY("M6 4h12a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3z " + circle(9f, 9.5f, 1.8f) + "M3.5 17l5-4.5 4 3.5 3-2.5 5 4"),
    EYE("M2 12s3.6-6.5 10-6.5S22 12 22 12s-3.6 6.5-10 6.5S2 12 2 12z " + circle(12f, 12f, 3f)),
    CHECK("M5 12.5l4.5 4.5L19 7.5"),
    DOWN("M6 9.5l6 6 6-6"),
    RIGHT("M9.5 6l6 6-6 6"),
    BACK("M15 5l-7 7 7 7"),
    JUMP("M4 20c1.5-8 4.5-13 8-13s6.5 5 8 13 M16.5 17.5l3.5 2.5 1.5-4"),
    FLY("M2.5 13.5c3-4.5 6.5-4.5 9.5 0 3-4.5 6.5-4.5 9.5 0 M12 13.5v4"),
    ROLL(circle(12f, 12f, 8.5f) + "M12 12l4.5-4 " + circle(12f, 12f, 1.2f)),
    HIDE("M3 19h18 M6.5 19a5.5 5.5 0 0 1 11 0 M12 5v4M6 7.5l2 2.5M18 7.5l-2 2.5"),
    MIC("M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v3"),
    PAUSE("M8.5 5.5v13M15.5 5.5v13"),
    FOOTPRINTS(ellipse(8f, 15.5f, 2.6f, 3.5f) + ellipse(16f, 8.5f, 2.6f, 3.5f)),
    METRICS("M6 20v-7M12 20V5M18 20v-10"),
    EXPORT("M12 15V4M7.5 8.5L12 4l4.5 4.5 M4.5 14v4.5a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V14"),
    SOUND("M4 9.5h3.5L12 5.5v13l-4.5-4H4z M15.5 9a4.5 4.5 0 0 1 0 6M18.5 6.5a8 8 0 0 1 0 11"),
    MOTION(circle(12f, 12f, 8.5f) + "M8 12h8"),
    GLOBE(circle(12f, 12f, 8.5f) + "M3.5 12h17M12 3.5c3 3 3 14 0 17M12 3.5c-3 3-3 14 0 17"),
    INFO(circle(12f, 12f, 8.5f) + "M12 11v5.5M12 7.8v.2"),
    RETRY("M19.5 12a7.5 7.5 0 1 1-2.2-5.3 M19.5 4.5v4.5H15"),
    CUT(circle(6.5f, 7f, 2.8f) + circle(6.5f, 17f, 2.8f) + "M8.8 8.6L20 18.5M8.8 15.4L20 5.5"),
    PAINT("M14.5 3.5l6 6-8.5 8.5H6v-6z M11.5 6.5l6 6 M4 21h5"),
    SHIELD("M12 3l7.5 3v6c0 4.6-3.3 7.8-7.5 9-4.2-1.2-7.5-4.4-7.5-9V6z M8.5 12l2.5 2.5 4.5-5"),
    NAME("M4 12.5V4h8.5l8 8-8.5 8.5z " + circle(8.5f, 8.5f, 1.3f)),
    CLOSE("M6 6l12 12M18 6L6 18"),
    PLUS("M12 5v14M5 12h14"),
    MINUS("M5 12h14"),
    /** T31 emotes: an open palm (Hi). */
    HAND("M8 12.5V6a1.5 1.5 0 0 1 3 0v5 M11 11V4.5a1.5 1.5 0 0 1 3 0V11 M14 11V5.5a1.5 1.5 0 0 1 3 0V12 M17 12V8.5a1.5 1.5 0 0 1 3 0V14c0 4-2.8 6.5-6.5 6.5-2.6 0-4.2-1.2-5.6-3.2L4.5 13.6a1.5 1.5 0 0 1 2.4-1.8L8 13.2"),
    /** A figure bent in a bow (Bye). */
    BOW(circle(6.5f, 8.5f, 2f) + "M9.3 10.2l6.7 2.3 M16 12.5V20 M12 11.2l-.6 5.3 M16 20h2.5"),
    /** A music note (Dance). */
    NOTE(ellipse(9f, 17.5f, 3f, 2.3f) + "M12 17.5V4 M12 4c2.2 1.4 5 2.6 5 6.2"),
    /** A round arrow, one turn (Spin). */
    SPIN("M12 4.5a7.5 7.5 0 1 0 7.5 7.5 M17 14l2.5-2.5L22 14"),
    /** An open mouth with teeth and sound waves (Roar). */
    ROAR(ellipse(8f, 12f, 4.5f, 5f) + "M4.6 9.5l1.2 1.5 1.2-1.5 1.2 1.5 1.2-1.5 1.2 1.5 1.1-1.5 M15.5 9a4 4 0 0 1 0 6 M18.5 6.5a8 8 0 0 1 0 11"),
}

/** T31: the emote row's glyph for [emote]. */
fun kz.rudy.ar.core.emote.Emote.glyph(): Glyph = when (this) {
    kz.rudy.ar.core.emote.Emote.HELLO -> Glyph.HAND
    kz.rudy.ar.core.emote.Emote.BYE -> Glyph.BOW
    kz.rudy.ar.core.emote.Emote.DANCE -> Glyph.NOTE
    kz.rudy.ar.core.emote.Emote.SPIN -> Glyph.SPIN
    kz.rudy.ar.core.emote.Emote.ROAR -> Glyph.ROAR
}

fun Ability.glyph(): Glyph = when (this) {
    Ability.JUMP -> Glyph.JUMP
    Ability.FLY -> Glyph.FLY
    Ability.ROLL -> Glyph.ROLL
    Ability.HIDE -> Glyph.HIDE
}

private val glyphPaths: Map<Glyph, Path> by lazy {
    Glyph.entries.associateWith { PathParser().parsePathString(it.svg).toPath() }
}

/** [strokeWidth] is in grid units (24 grid), so it scales with [size]. */
@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp, strokeWidth: Float = 2.2f) {
    Canvas(modifier.size(size)) {
        scale(this.size.width / GRID, this.size.height / GRID, pivot = Offset.Zero) {
            drawPath(glyphPaths.getValue(glyph), tint, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * Draws [corner], a top-left bracket in [unit]-sized steps, [insetPx] in from the edges, mirrored into all four corners
 * of this canvas. [strokeWidth] is in the same units as the path.
 */
internal fun DrawScope.drawCornerBrackets(corner: Path, insetPx: Float, unit: Float, color: Color, strokeWidth: Float, cap: StrokeCap) {
    for (sx in listOf(1f, -1f)) for (sy in listOf(1f, -1f)) {
        withTransform({
            scale(sx, sy, pivot = center)
            translate(insetPx, insetPx)
            scale(unit, unit, pivot = Offset.Zero)
        }) { drawPath(corner, color, style = Stroke(strokeWidth, cap = cap, join = StrokeJoin.Round)) }
    }
}
