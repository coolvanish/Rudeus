package kz.rudy.ar.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.LocalReducedMotion
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

enum class BannerKind { INFO, SUCCESS, ERROR }

private fun BannerKind.dot(): Color = when (this) {
    BannerKind.INFO -> RudeusColors.Sky
    BannerKind.SUCCESS -> RudeusColors.Leaf
    BannerKind.ERROR -> RudeusColors.Tomato
}

/**
 * Status card: Paper with a 1 dp Line edge, a status dot, a title, an optional hint and an optional single action
 * (Skip, Retry). Slides 24 dp from the top edge and fades (DESIGN.md "Motion").
 */
@Composable
fun StatusBanner(
    visible: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    kind: BannerKind = BannerKind.INFO,
    action: (@Composable () -> Unit)? = null,
) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduced) fadeIn(tween(RudeusMotion.REDUCED_MS)) else
            slideInVertically(tween(RudeusMotion.BANNER_ENTER_MS, easing = RudeusMotion.Emphasised)) { -24.dp.value.toInt() } +
                fadeIn(tween(RudeusMotion.BANNER_ENTER_MS)),
        exit = if (reduced) fadeOut(tween(RudeusMotion.REDUCED_MS)) else
            slideOutVertically(tween(RudeusMotion.BANNER_EXIT_MS, easing = RudeusMotion.Standard)) { -24.dp.value.toInt() } +
                fadeOut(tween(RudeusMotion.BANNER_EXIT_MS)),
    ) {
        val shape = RoundedCornerShape(RudeusRadius.card)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(RudeusColors.Paper)
                .border(RudeusSpacing.hairline, RudeusColors.Line, shape)
                .padding(horizontal = RudeusSpacing.md, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.smd),
        ) {
            StatusDot(kind.dot(), Modifier.padding(top = 6.dp), size = 12.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp), color = RudeusColors.Ink)
                if (hint != null) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
                        color = RudeusColors.InkSoft,
                    )
                }
            }
            if (action != null) Box(Modifier.align(Alignment.CenterVertically)) { action() }
        }
    }
}

/**
 * The paper pill by its old name: the AR host (:androidApp, `RudeusArView`) draws its "wait" hint with this, and that
 * zone stays untouched in T26.
 */
@Composable
fun ScrimPill(text: String, modifier: Modifier = Modifier) {
    Pill(text, modifier)
}

/**
 * Full-screen ScrimStrong shown while tracking is lost / Mode.PAUSED. No button, and it takes the touches: it leaves
 * when tracking resumes. Physics is frozen by :core and nothing resets (CLAUDE.md).
 */
@Composable
fun PausedOverlay(visible: Boolean, modifier: Modifier = Modifier, hint: StringId = StringId.paused_hint) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(if (reduced) RudeusMotion.REDUCED_MS else RudeusMotion.BANNER_ENTER_MS)),
        exit = fadeOut(tween(RudeusMotion.BANNER_EXIT_MS)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(RudeusColors.ScrimStrong)
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .padding(start = 28.dp, end = 28.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(76.dp).clip(CircleShape).background(RudeusColors.Paper), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.PAUSE, RudeusColors.Ink, size = 34.dp, strokeWidth = 3.4f)
            }
            Spacer(Modifier.height(22.dp))
            Text(t(StringId.paused_title), style = MaterialTheme.typography.displayLarge, color = RudeusColors.Paper)
            Spacer(Modifier.height(RudeusSpacing.smd))
            Text(t(hint), style = MaterialTheme.typography.bodyLarge, color = RudeusColors.CreamDeep)
        }
    }
}
