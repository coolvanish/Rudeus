package kz.rudy.ar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing
import kotlin.math.max
import kotlin.math.min

/**
 * Debug 2D view of the [Level] in plane coordinates (u, v) with the per-kind footprint tints
 * from DESIGN.md. The AR footprints are drawn by :androidApp with the same [RudeusColors.footprint] tints.
 */
@Composable
fun FootprintMiniMap(level: Level, character: CharacterState?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(RudeusRadius.card)
    Box(
        modifier
            .size(120.dp)
            .clip(shape)
            .background(RudeusColors.Paper)
            .border(RudeusSpacing.stroke, RudeusColors.Ink, shape)
            .padding(RudeusSpacing.sm),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val all = level.objects.flatMap { it.footprint.points } +
                (level.boundary?.points ?: emptyList()) +
                listOfNotNull(character?.pos) + level.spawn
            var minU = Float.MAX_VALUE; var minV = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
            for (p in all) { minU = min(minU, p.u); minV = min(minV, p.v); maxU = max(maxU, p.u); maxV = max(maxV, p.v) }
            val span = max(maxU - minU, maxV - minV).coerceAtLeast(0.2f)
            val scale = size.minDimension / span
            val ox = (size.width - (maxU - minU) * scale) / 2f
            val oy = (size.height - (maxV - minV) * scale) / 2f
            fun map(p: Vec2) = Offset(ox + (p.u - minU) * scale, oy + (p.v - minV) * scale)
            fun path(poly: Polygon) = Path().apply {
                poly.points.forEachIndexed { i, p -> val o = map(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
                close()
            }
            val outline = RudeusSpacing.stroke.toPx() * 0.7f
            level.boundary?.let { b ->
                val tint = RudeusColors.footprint(ObjectKind.CLIFF)
                drawPath(
                    path(b), tint.copy(alpha = RudeusColors.FootOutlineAlpha),
                    style = Stroke(outline, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                )
            }
            for (o in level.objects) {
                val tint = RudeusColors.footprint(o.kind)
                val p = path(o.footprint)
                drawPath(p, tint.copy(alpha = RudeusColors.FootFillAlpha))
                drawPath(p, tint.copy(alpha = RudeusColors.FootOutlineAlpha), style = Stroke(outline))
            }
            drawCircle(RudeusColors.Sun, 3.dp.toPx(), map(level.spawn), style = Stroke(outline))
            character?.let { c ->
                drawCircle(RudeusColors.Sky.copy(alpha = 0.35f), (c.radiusM * scale).coerceAtLeast(4.dp.toPx()), map(c.pos))
                drawCircle(RudeusColors.Sky, 3.dp.toPx(), map(c.pos))
            }
        }
    }
}
