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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.LocalReducedMotion
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

enum class BannerKind { INFO, SUCCESS, ERROR }

private fun BannerKind.accent(): Color = when (this) {
    BannerKind.INFO -> RudeusColors.Sky
    BannerKind.SUCCESS -> RudeusColors.Leaf
    BannerKind.ERROR -> RudeusColors.Tomato
}

/**
 * Text over the camera feed always sits on a scrim. A banner is a scrim card with a title, an optional hint
 * and an optional single action. Slides 24 dp from the top edge and fades (DESIGN.md "Motion").
 */
@Composable
fun StatusBanner(
    visible: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    kind: BannerKind = BannerKind.INFO,
    glyph: Glyph? = null,
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
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(RudeusColors.Scrim)
                .border(RudeusSpacing.stroke, kind.accent(), shape)
                .padding(RudeusSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (glyph != null) {
                    GlyphIcon(glyph, kind.accent())
                    Spacer(Modifier.width(RudeusSpacing.sm))
                }
                Text(title, style = MaterialTheme.typography.titleLarge, color = RudeusColors.Paper, textAlign = TextAlign.Center)
            }
            if (hint != null) {
                Spacer(Modifier.height(RudeusSpacing.xs))
                Text(hint, style = MaterialTheme.typography.bodyLarge, color = RudeusColors.Paper, textAlign = TextAlign.Center)
            }
            if (action != null) {
                Spacer(Modifier.height(RudeusSpacing.md))
                action()
            }
        }
    }
}

/** Small scrim pill for a one-line hint over the feed ("Tap - it walks", "Looking..."). */
@Composable
fun ScrimPill(text: String, modifier: Modifier = Modifier, color: Color = RudeusColors.Paper) {
    Box(
        modifier
            .clip(RoundedCornerShape(RudeusRadius.full))
            .background(RudeusColors.Scrim)
            .padding(horizontal = RudeusSpacing.md, vertical = RudeusSpacing.sm),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color, textAlign = TextAlign.Center)
    }
}

/**
 * Full-screen ScrimStrong shown while tracking is lost / Mode.PAUSED. No button: it leaves when tracking
 * resumes. Physics is frozen by :core and nothing resets (CLAUDE.md).
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
            Modifier.fillMaxSize().background(RudeusColors.ScrimStrong).padding(RudeusSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GlyphIcon(Glyph.PAUSE, RudeusColors.Sun, size = 64.dp)
            Spacer(Modifier.height(RudeusSpacing.md))
            Text(t(StringId.paused_title), style = MaterialTheme.typography.displayLarge, color = RudeusColors.Paper, textAlign = TextAlign.Center)
            Spacer(Modifier.height(RudeusSpacing.sm))
            Text(t(hint), style = MaterialTheme.typography.bodyLarge, color = RudeusColors.Paper, textAlign = TextAlign.Center)
        }
    }
}
