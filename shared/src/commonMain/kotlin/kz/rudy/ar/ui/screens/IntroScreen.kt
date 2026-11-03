package kz.rudy.ar.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.GhostButton
import kz.rudy.ar.ui.components.drawRobeBadge
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.LocalReducedMotion
import kz.rudy.ar.ui.theme.RudeusColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The first thing a player sees (DESIGN.md "Intro"): the robe badge, then one continuous story in four captions.
 * A drawing is traced on paper, it peels off as a sticker and comes alive, a table slides in and gets scanned, and the
 * hero hops over it the way the game plays: up onto the book, off the cup, stopping at the edge.
 *
 * Everything on screen is a pure function of one time value. Reduced motion uses that directly: it holds five still
 * poses and crossfades between them instead of playing the clock. A tap anywhere (or Skip, or Back) ends it.
 */
@Composable
internal fun IntroScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val done by rememberUpdatedState(onDone)
    var clock by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduced) {
        val total = if (reduced) REDUCED_POSES.size * REDUCED_HOLD_MS else INTRO_MS
        val t0 = withFrameMillis { it }
        while (clock < total) withFrameMillis { clock = (it - t0).toFloat() }
        done()
    }
    Box(
        modifier
            .fillMaxSize()
            .background(RudeusColors.Paper)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDone),
    ) {
        if (reduced) {
            // A crossfade between stills. The first still is the system splash's own picture, so it never fades in.
            fun page(): Int = (clock / REDUCED_HOLD_MS).toInt().coerceAtMost(REDUCED_POSES.lastIndex)
            fun fade(): Float = seg(clock - page() * REDUCED_HOLD_MS, 0f, REDUCED_FADE_MS)
            IntroPicture(
                pose = { REDUCED_POSES[(page() - 1).coerceAtLeast(0)] },
                modifier = Modifier.graphicsLayer { alpha = if (page() == 0) 0f else 1f - fade() },
            )
            IntroPicture(
                pose = { REDUCED_POSES[page()] },
                modifier = Modifier.graphicsLayer { alpha = if (page() == 0) 1f else fade() },
            )
        } else {
            IntroPicture(pose = { clock * STORY_MS / INTRO_MS })
        }

        GhostButton(
            text = t(StringId.skip),
            onClick = onDone,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
        )
    }
}

/** The stage, the wordmark and the captions at one time value. [pose] is read only while drawing, so no recomposition. */
@Composable
private fun IntroPicture(pose: () -> Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) { drawIntro(pose()) }

        Text(
            t(StringId.app_name),
            style = MaterialTheme.typography.displayLarge,
            color = RudeusColors.Ink,
            modifier = Modifier.align(Alignment.Center).offset(y = 132.dp).graphicsLayer {
                val p = pose()
                val s = backOut(seg(p, 420f, 720f))
                alpha = seg(p, 420f, 560f) * (1f - seg(p, 1000f, 1200f))
                scaleX = s
                scaleY = s
            },
        )

        CAPTIONS.forEach { c ->
            Text(
                t(c.text),
                style = MaterialTheme.typography.displayMedium,
                color = RudeusColors.Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 104.dp)
                    .graphicsLayer {
                        val p = pose()
                        alpha = seg(p, c.from, c.from + 220f) * (1f - seg(p, c.to - 150f, c.to))
                        translationY = (1f - easeOut(seg(p, c.from, c.from + 320f))) * 18.dp.toPx()
                    },
            )
        }
    }
}

/** How long the intro plays. Every time below is on the story's own 8000 ms timeline, so this one number sets the pace. */
private const val INTRO_MS = 12000f
private const val STORY_MS = 8000f
private const val REVEAL_START = 1000f
private const val REVEAL_END = 1450f
private const val REDUCED_HOLD_MS = 2400f
private const val REDUCED_FADE_MS = 150f
private val REDUCED_POSES = floatArrayOf(950f, 3285f, 4580f, 5760f, 6300f).toList()

private class Caption(val text: StringId, val from: Float, val to: Float)

private val CAPTIONS = listOf(
    Caption(StringId.intro_draw, 1750f, 3400f),
    Caption(StringId.intro_alive, 3450f, 4760f),
    Caption(StringId.intro_table, 4760f, 6100f),
    Caption(StringId.play_tap_to_walk, 6100f, 8200f),
)

// ---- time helpers

private fun seg(t: Float, a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)
private fun lerp(a: Float, b: Float, x: Float): Float = a + (b - a) * x
private fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)
private fun easeIn(x: Float): Float = x * x * x
private fun bump(x: Float): Float = sin(x * PI.toFloat())

/** Overshoots to ~1.1 and settles: the DESIGN.md `Pop` feel, as a pure function of time. */
private fun backOut(x: Float): Float {
    val c = 1.70158f
    val m = x - 1f
    return 1f + (c + 1f) * m * m * m + c * m * m
}

// ---- the hero's path, in stage units

private class Hop(val t0: Float, val t1: Float, val ax: Float, val ay: Float, val bx: Float, val by: Float, val height: Float)

private val HOPS = listOf(
    Hop(3750f, 4150f, 0f, -10f, 0f, -10f, 70f),
    Hop(4150f, 4560f, 0f, -10f, 0f, -10f, 42f),
    Hop(4640f, 5000f, 0f, -10f, 20f, 72f, 40f),
    Hop(5800f, 6200f, 20f, 72f, -70f, 32f, 70f), // onto the book
    Hop(6350f, 6680f, -70f, 32f, 56f, 30f, 55f), // at the cup
    Hop(6680f, 6880f, 56f, 30f, 38f, 38f, 16f), // pushed back
    Hop(7000f, 7350f, 38f, 38f, 30f, 96f, 38f), // to the edge
    Hop(7600f, 7960f, 30f, 96f, 10f, 70f, 60f), // and back from it
)

private class Ripple(val t: Float, val x: Float, val y: Float)

private val RIPPLES = listOf(Ripple(5720f, -70f, 38f), Ripple(6270f, 56f, 36f), Ripple(6920f, 30f, 100f))

private class HeroPose(val x: Float, val y: Float, val sx: Float, val sy: Float)

private fun heroPose(t: Float): HeroPose {
    val hop = HOPS.lastOrNull { t >= it.t0 } ?: HOPS.first()
    val x = seg(t, hop.t0, hop.t1)
    val px = lerp(hop.ax, hop.bx, x)
    val py = lerp(hop.ay, hop.by, x) - hop.height * bump(x)
    val stretch = if (x in 0f..0.999f && t >= hop.t0) 0.12f * bump(x) else 0f
    val land = if (t >= hop.t1) 0.24f * bump(seg(t, hop.t1, hop.t1 + 150f)) else 0f
    return HeroPose(px, py, 1f - stretch + land, 1f + stretch - land)
}

/** The hero squashes on its feet: its scale pivots here, in star units, and not at the star's centre. */
private const val HERO_PIVOT_Y = 40f

private fun heroTilt(t: Float): Float = lerp(-4f, 0f, seg(t, 3300f, 3480f))

/** Where a point of the star (50 radius) lands on the stage: the same transform [drawHero] draws with. */
private fun heroToStage(p: Offset, pose: HeroPose, scale: Float, degrees: Float): Offset {
    val x = p.x * scale * pose.sx
    val y = HERO_PIVOT_Y + (p.y - HERO_PIVOT_Y) * scale * pose.sy
    val a = degrees * PI.toFloat() / 180f
    return Offset(x * cos(a) - y * sin(a) + pose.x, x * sin(a) + y * cos(a) + pose.y)
}

private fun heroScale(t: Float): Float = when {
    t < 3300f -> 1.6f
    t < 4640f -> lerp(1.6f, 1.95f, backOut(seg(t, 3300f, 3620f)))
    else -> lerp(1.95f, 0.52f, easeOut(seg(t, 4640f, 5000f)))
}

// ---- the drawn hero (a soft five-point star, 50 radius)

private val StarPath by lazy {
    val pts = (0 until 10).map { i ->
        val r = if (i % 2 == 0) 50f else 25f
        val a = (-90f + i * 36f) * PI.toFloat() / 180f
        Offset(r * cos(a), r * sin(a))
    }
    fun mid(a: Offset, b: Offset) = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
    val start = mid(pts[9], pts[0])
    val d = StringBuilder("M${start.x} ${start.y}")
    pts.forEachIndexed { i, p ->
        val m = mid(p, pts[(i + 1) % 10])
        d.append("Q${p.x} ${p.y} ${m.x} ${m.y}")
    }
    PathParser().parsePathString(d.append("Z").toString()).toPath()
}

/** The crayon scribble that fills the star. */
private val ScribblePath by lazy {
    val d = StringBuilder("M-52 -46")
    var x = -52f
    var down = true
    while (x < 60f) {
        x += 9f
        d.append("L$x ${if (down) 50f else -50f}")
        down = !down
    }
    PathParser().parsePathString(d.toString()).toPath()
}

private fun DrawScope.drawHero(t: Float) {
    val trace = seg(t, 1800f, 2650f)
    val fill = seg(t, 2650f, 3150f)
    if (t < 1700f) return
    val pose = heroPose(t)
    val s = heroScale(t)
    val sticker = seg(t, 3300f, 3480f)
    val tilt = heroTilt(t)
    val eyes = seg(t, 3380f, 3520f)
    val look = when {
        t in 5000f..5720f -> 3f * sin((t - 5000f) / 720f * 2f * PI.toFloat())
        t in 7350f..7600f -> 2.5f
        else -> 0f
    }
    val teeter = if (t in 7350f..7600f) 9f * sin((t - 7350f) / 250f * 2f * PI.toFloat()) else 0f

    withTransform({
        translate(pose.x, pose.y)
        rotate(tilt + teeter, Offset.Zero)
        scale(s * pose.sx, s * pose.sy, Offset(0f, HERO_PIVOT_Y))
    }) {
        if (sticker > 0f) {
            drawPath(StarPath, RudeusColors.Ink.copy(alpha = sticker), style = Stroke(19f, join = StrokeJoin.Round))
            drawPath(StarPath, RudeusColors.Paper.copy(alpha = sticker), style = Stroke(15f, join = StrokeJoin.Round))
            drawPath(StarPath, RudeusColors.Paper)
        }
        if (fill > 0f) {
            clipPath(StarPath) {
                drawPath(StarPath, RudeusColors.SunTint.copy(alpha = fill))
                drawPath(trimmed(ScribblePath, fill), RudeusColors.Sun, style = Stroke(10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        drawPath(trimmed(StarPath, trace), Line, style = Stroke(4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (eyes <= 0f) {
            val shut = seg(t, 3050f, 3180f)
            if (shut > 0f) {
                listOf(-13f, 13f).forEach { x ->
                    drawArc(Line.copy(alpha = shut), 20f, 140f, false, Offset(x - 7f, -10f), Size(14f, 9f), style = Stroke(3f, cap = StrokeCap.Round))
                }
            }
        } else {
            listOf(-13f, 13f).forEach { x ->
                val ry = 9.5f * backOut(eyes).coerceAtLeast(0.1f)
                drawOval(Color.White, Offset(x - 7.5f, -6f - ry), Size(15f, ry * 2))
                drawOval(Line, Offset(x - 7.5f, -6f - ry), Size(15f, ry * 2), style = Stroke(2f))
                drawCircle(RudeusColors.Sky, 5f * eyes, Offset(x + look, -5f))
                drawCircle(Color(0xFF14110E), 2.9f * eyes, Offset(x + look, -5f))
                drawCircle(Color.White, 1.3f * eyes, Offset(x + look + 1.4f, -6.8f))
            }
            drawArc(Line, 25f, 130f, false, Offset(-8f, 3f), Size(16f, 11f), style = Stroke(3f * eyes, cap = StrokeCap.Round))
        }
    }

    // The crayon follows the line, then the scribble.
    val tip = when {
        trace < 1f -> positionOn(StarPath, trace)
        fill < 1f -> positionOn(ScribblePath, fill)
        else -> null
    }
    val inOut = seg(t, 1700f, 1800f) * (1f - seg(t, 3150f, 3280f))
    if (inOut > 0f) drawCrayon(heroToStage(tip ?: Offset(44f, -40f), pose, s, tilt), inOut)
}

private val Line = Color(0xFF2B1D16)

private fun trimmed(path: Path, x: Float): Path {
    if (x >= 1f) return path
    val m = PathMeasure().apply { setPath(path, false) }
    return Path().also { m.getSegment(0f, m.length * x, it, true) }
}

private fun positionOn(path: Path, x: Float): Offset {
    val m = PathMeasure().apply { setPath(path, false) }
    return m.getPosition(m.length * x)
}

private fun DrawScope.drawCrayon(tip: Offset, alpha: Float) {
    withTransform({
        translate(tip.x, tip.y)
        rotate(32f, Offset.Zero)
    }) {
        val body = Path().apply {
            moveTo(0f, 0f); lineTo(-8f, -18f); lineTo(-8f, -74f); lineTo(8f, -74f); lineTo(8f, -18f); close()
        }
        drawPath(body, RudeusColors.Sky.copy(alpha = alpha))
        val cone = Path().apply { moveTo(0f, 0f); lineTo(-8f, -18f); lineTo(8f, -18f); close() }
        drawPath(cone, RudeusColors.Cream.copy(alpha = alpha))
        drawPath(body, Line.copy(alpha = alpha), style = Stroke(3f, join = StrokeJoin.Round))
        drawLine(Line.copy(alpha = alpha), Offset(-8f, -18f), Offset(8f, -18f), 3f)
    }
}

// ---- the paper sheet

private fun DrawScope.drawSheet(t: Float) {
    val enter = easeOut(seg(t, 1400f, 1780f))
    val leave = easeIn(seg(t, 3300f, 3680f))
    if (enter <= 0f || leave >= 1f) return
    withTransform({
        translate(0f, lerp(620f, -10f, enter) + 640f * leave)
        rotate(-4f + 12f * leave, Offset.Zero)
    }) {
        val w = 262f
        val h = 306f
        drawRoundRect(Line, Offset(-w / 2 + 3f, -h / 2 + 5f), Size(w, h), CornerRadius(12f))
        drawRoundRect(Color.White, Offset(-w / 2, -h / 2), Size(w, h), CornerRadius(12f))
        drawRoundRect(Line, Offset(-w / 2, -h / 2), Size(w, h), CornerRadius(12f), style = Stroke(3f))
        var y = -h / 2 + 38f
        while (y < h / 2 - 16f) {
            drawLine(RudeusColors.Line, Offset(-w / 2 + 14f, y), Offset(w / 2 - 14f, y), 2f)
            y += 34f
        }
    }
}

/** Short comic ticks when the sticker jumps off the page (no confetti, DESIGN.md (a)). */
private fun DrawScope.drawPopTicks(t: Float) {
    val x = seg(t, 3320f, 3720f)
    if (x <= 0f || x >= 1f) return
    val r0 = lerp(106f, 150f, easeOut(x))
    val r1 = lerp(118f, 158f, easeOut(x))
    val center = heroToStage(Offset.Zero, heroPose(t), heroScale(t), heroTilt(t))
    (0 until 10).forEach { i ->
        val a = i * 36f * PI.toFloat() / 180f - PI.toFloat() / 2f
        val c = Offset(cos(a), sin(a))
        drawLine(Line.copy(alpha = 1f - x), center + c * r0, center + c * r1, 5f, cap = StrokeCap.Round)
    }
}

// ---- the table

private val Dash = PathEffect.dashPathEffect(floatArrayOf(12f, 9f))

private fun DrawScope.drawTable(t: Float) {
    val e = easeOut(seg(t, 4600f, 5000f))
    if (e <= 0f) return
    val scan = seg(t, 5000f, 5700f)
    val cupFound = seg(scan, 0.45f, 0.6f)
    val bookFound = seg(scan, 0.6f, 0.75f)
    val edgeFound = seg(scan, 0.9f, 1f)
    withTransform({ translate(0f, (1f - e) * 560f) }) {
        // Top and front of the table.
        val top = Path().apply { moveTo(-120f, -52f); lineTo(120f, -52f); lineTo(172f, 106f); lineTo(-172f, 106f); close() }
        drawPath(top, RudeusColors.CreamDeep)
        drawPath(top, Line, style = Stroke(3.5f, join = StrokeJoin.Round))
        val frontTop = 106f
        drawRect(RudeusColors.Line, Offset(-172f, frontTop), Size(344f, 14f))
        drawRect(Line, Offset(-172f, frontTop), Size(344f, 14f), style = Stroke(3.5f, join = StrokeJoin.Round))

        // The edge: hazard dashes, brighter while the hero stands at it.
        if (edgeFound > 0f) {
            val hot = if (t in 7300f..7620f) 1f else 0.75f
            drawLine(RudeusColors.Hazard.copy(alpha = edgeFound * hot), Offset(-168f, 104f), Offset(168f, 104f), 6f, pathEffect = Dash)
        }

        // Book: a platform, Leaf once found.
        val bookTop = Path().apply { moveTo(-110f, 20f); lineTo(-34f, 20f); lineTo(-26f, 58f); lineTo(-118f, 58f); close() }
        drawRect(Color.White, Offset(-118f, 58f), Size(92f, 14f))
        drawRect(Line, Offset(-118f, 58f), Size(92f, 14f), style = Stroke(3f))
        drawPath(bookTop, RudeusColors.Sky)
        if (bookFound > 0f) drawPath(bookTop, RudeusColors.Leaf.copy(alpha = 0.75f * bookFound))
        drawPath(bookTop, Line, style = Stroke(3f, join = StrokeJoin.Round))

        // Cup: an obstacle, Tomato footprint once found.
        if (cupFound > 0f) {
            drawOval(RudeusColors.Tomato.copy(alpha = 0.35f * cupFound), Offset(52f, 6f), Size(68f, 28f))
            drawOval(RudeusColors.Tomato.copy(alpha = 0.9f * cupFound), Offset(52f, 6f), Size(68f, 28f), style = Stroke(3f))
        }
        drawOval(Line, Offset(102f, -26f), Size(26f, 30f), style = Stroke(5f))
        val cup = Path().apply {
            moveTo(62f, -36f); lineTo(110f, -36f); lineTo(106f, 12f)
            cubicTo(100f, 22f, 72f, 22f, 66f, 12f); close()
        }
        drawPath(cup, RudeusColors.Grape)
        drawPath(cup, Line, style = Stroke(3f, join = StrokeJoin.Round))
        drawOval(RudeusColors.GrapeTint, Offset(62f, -44f), Size(48f, 16f))
        drawOval(Line, Offset(62f, -44f), Size(48f, 16f), style = Stroke(3f))
    }
    drawScanFrame(t, scan)
}

private fun DrawScope.drawScanFrame(t: Float, scan: Float) {
    val alpha = seg(t, 4920f, 5040f) * (1f - seg(t, 5700f, 5820f))
    if (alpha <= 0f) return
    val left = -190f
    val right = 190f
    val top = -86f
    val bottom = 136f
    val arm = 34f
    val breathe = 1f + 0.02f * sin(t / 900f * 2f * PI.toFloat())
    withTransform({ scale(breathe, breathe, Offset(0f, (top + bottom) / 2)) }) {
        listOf(Offset(left, top) to Offset(1f, 1f), Offset(right, top) to Offset(-1f, 1f), Offset(left, bottom) to Offset(1f, -1f), Offset(right, bottom) to Offset(-1f, -1f))
            .forEach { (c, d) ->
                val p = Path().apply {
                    moveTo(c.x + d.x * arm, c.y); lineTo(c.x, c.y); lineTo(c.x, c.y + d.y * arm)
                }
                drawPath(p, Line.copy(alpha = alpha), style = Stroke(11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(p, RudeusColors.Sun.copy(alpha = alpha), style = Stroke(7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
    }
    if (scan in 0.001f..0.999f) {
        val y = lerp(top, bottom, scan)
        drawLine(Line.copy(alpha = alpha), Offset(left + 8f, y), Offset(right - 8f, y), 8f, cap = StrokeCap.Round)
        drawLine(RudeusColors.Sun.copy(alpha = alpha), Offset(left + 8f, y), Offset(right - 8f, y), 5f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawRipples(t: Float) {
    RIPPLES.forEach { r ->
        val x = seg(t, r.t, r.t + 460f)
        if (t >= r.t && x < 1f) {
            val radius = lerp(6f, 30f, easeOut(x))
            val a = 1f - x
            drawOval(Line.copy(alpha = a), Offset(r.x - radius, r.y - radius * 0.55f), Size(radius * 2, radius * 1.1f), style = Stroke(7f))
            drawOval(RudeusColors.Sun.copy(alpha = a), Offset(r.x - radius, r.y - radius * 0.55f), Size(radius * 2, radius * 1.1f), style = Stroke(4f))
        }
    }
}

// ---- the whole picture

private fun DrawScope.drawIntro(t: Float) {
    val w = size.width
    val h = size.height
    val center = Offset(w / 2, h / 2)

    if (t < REVEAL_END) {
        drawRect(RudeusColors.Sun)
        val reveal = easeOut(seg(t, REVEAL_START, REVEAL_END))
        if (reveal > 0f) drawCircle(RudeusColors.Paper, reveal * hypot(w, h) / 2, center)
        val blink = 1f - 0.9f * bump(seg(t, 380f, 500f))
        val away = easeIn(seg(t, REVEAL_START, REVEAL_START + 280f))
        val beat = 0.05f * bump(seg(t, 200f, 520f))
        val radius = 96.dp.toPx() * (1f + beat) * (1f - away)
        if (radius > 0f) drawRobeBadge(center, radius, blink, twinkle = 0.75f + 0.25f * sin(t / 300f))
    }

    // The story sits on a 400-unit-wide stage, centred a little above the middle; captions keep the bottom.
    val u = min(w / 400f, h * 0.72f / 400f)
    withTransform({
        translate(w / 2, h * 0.46f)
        scale(u, u, Offset.Zero)
    }) {
        drawTable(t)
        drawRipples(t)
        drawSheet(t)
        drawHero(t)
        drawPopTicks(t)
    }

    if (t >= REVEAL_END) {
        val current = CAPTIONS.indexOfLast { t >= it.from }
        val y = h - 72.dp.toPx()
        val gap = 20.dp.toPx()
        CAPTIONS.indices.forEach { i ->
            val c = Offset(w / 2 + (i - 1.5f) * gap, y)
            val r = 5.dp.toPx()
            if (i == current) drawCircle(RudeusColors.Sun, r, c) else drawCircle(RudeusColors.CreamDeep, r, c)
            drawCircle(RudeusColors.Ink, r, c, style = Stroke(1.5f.dp.toPx()))
        }
    }
}
