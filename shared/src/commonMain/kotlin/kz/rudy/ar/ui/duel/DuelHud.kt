package kz.rudy.ar.ui.duel

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.GlyphIcon
import kz.rudy.ar.ui.components.Pill
import kz.rudy.ar.ui.components.StatusDot
import kz.rudy.ar.ui.components.hitArea
import kz.rudy.ar.ui.components.hitSlack
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.LocalReducedMotion
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

/**
 * The duel's HUD over the AR view: score pips and the clock on top, the one large banner below them, Special / Jump /
 * Dash along the bottom (Dash, the Sun primary, bottom-right under the thumb). Stateless: [model] is what
 * [duelHud] made from the match state; the callbacks are the three inputs. Empty areas take no touches, so a tap still
 * reaches the AR view. DESIGN.md "Duel".
 */
@Composable
fun DuelHud(model: DuelHudModel, onDash: () -> Unit, onJump: () -> Unit, onSpecial: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = RudeusSpacing.md, vertical = RudeusSpacing.smd)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.sm), verticalAlignment = Alignment.Top) {
            SideCard(model.me, model.pointsToWin, Modifier.weight(1f), Alignment.Start)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(RudeusSpacing.xs)) {
                model.clockSeconds?.let { Pill(clockText(it)) }
                Pill("${t(StringId.duel_round)} ${model.round}", quiet = true)
            }
            SideCard(model.foe, model.pointsToWin, Modifier.weight(1f), Alignment.End)
        }
        // The banner lives in the band between the top row and the buttons (so never over them), near its top so the
        // middle of the screen, where the fighters stand, stays clear.
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = RudeusSpacing.lg), contentAlignment = Alignment.TopCenter) {
            BannerView(model.banner)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.sm), verticalAlignment = Alignment.Bottom) {
            if (model.special != null) {
                DuelActionButton(model.special, specialGlyph(model.special.label), primary = false, onClick = onSpecial, modifier = Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            DuelActionButton(model.jump, Glyph.JUMP, primary = false, onClick = onJump, modifier = Modifier.weight(1f))
            DuelActionButton(model.dash, Glyph.RIGHT, primary = true, onClick = onDash, modifier = Modifier.weight(1.25f))
        }
    }
}

/** "m:ss" for the round clock. */
internal fun clockText(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** The words a banner says; [DuelBanner.Count] says its number instead (null here). */
internal fun bannerLabel(banner: DuelBanner): StringId? = when (banner) {
    is DuelBanner.Count -> null
    DuelBanner.Go -> StringId.duel_go
    is DuelBanner.Point -> when (banner.mine) {
        true -> StringId.duel_point_mine
        false -> StringId.duel_point_foe
        null -> StringId.duel_replay
    }
    is DuelBanner.Result -> when (banner.won) {
        true -> StringId.duel_won
        false -> StringId.duel_lost
        null -> StringId.duel_draw
    }
}

private fun specialGlyph(label: StringId): Glyph = when (label) {
    StringId.special_stomp -> Glyph.DOWN
    StringId.special_lift -> Glyph.FLY
    StringId.special_ram -> Glyph.ROLL
    else -> Glyph.HIDE
}

/** A fighter's name and its points as pips (filled Ink = scored); a hovering or dodging fighter shows a Sky shield. */
@Composable
private fun SideCard(side: SideModel, pointsToWin: Int, modifier: Modifier, align: Alignment.Horizontal) {
    val shape = RoundedCornerShape(RudeusRadius.tile)
    Column(
        modifier
            .clip(shape)
            .background(if (side.immune) RudeusColors.SkyTint else RudeusColors.Paper)
            .border(RudeusSpacing.hairline, if (side.immune) RudeusColors.Sky else RudeusColors.Line, shape)
            .padding(horizontal = RudeusSpacing.smd, vertical = RudeusSpacing.sm),
        horizontalAlignment = align,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (side.immune) GlyphIcon(Glyph.SHIELD, RudeusColors.SkyDeep, size = 18.dp, strokeWidth = 2.6f)
            Text(
                side.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (side.out) RudeusColors.InkMuted else RudeusColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(pointsToWin) { i ->
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (i < side.score) RudeusColors.Ink else RudeusColors.CreamDeep),
                )
            }
        }
    }
}

/** The banner: a Paper card with the words (or the countdown digit) large; fades, pops in unless motion is reduced. */
@Composable
private fun BannerView(banner: DuelBanner?) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = banner,
        modifier = Modifier,
        contentAlignment = Alignment.TopCenter,
        transitionSpec = {
            val enter = if (reduced) fadeIn(tween(RudeusMotion.REDUCED_MS)) else scaleIn(RudeusMotion.pop(), initialScale = 0.6f) + fadeIn(tween(RudeusMotion.BANNER_ENTER_MS))
            ContentTransform(enter, fadeOut(tween(RudeusMotion.BANNER_EXIT_MS)), sizeTransform = SizeTransform(clip = false) { _, _ -> snap() })
        },
        label = "banner",
    ) { shown ->
        if (shown != null) BannerCard(shown)
    }
}

@Composable
private fun BannerCard(banner: DuelBanner) {
    val shape = RoundedCornerShape(RudeusRadius.hero)
    val count = (banner as? DuelBanner.Count)?.n
    val dot = (banner as? DuelBanner.Point)?.mine?.let { if (it) RudeusColors.Leaf else RudeusColors.Sky }
    Row(
        Modifier
            .clip(shape)
            .background(RudeusColors.Paper)
            .border(RudeusSpacing.hairline, RudeusColors.Line, shape)
            .padding(horizontal = RudeusSpacing.xl, vertical = RudeusSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.smd),
    ) {
        if (dot != null) StatusDot(dot, size = 14.dp)
        Text(
            text = count?.toString() ?: bannerLabel(banner)?.let { t(it) }.orEmpty(),
            style = MaterialTheme.typography.displayLarge.copy(fontSize = if (count != null) 72.sp else 36.sp, lineHeight = if (count != null) 80.sp else 42.sp),
            color = RudeusColors.Ink,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A 64 dp thumb button. Ready: Sun (Dash, [primary]) or Paper with a 2 dp Ink stroke. Not ready: SunTint / Paper with
 * InkMuted stroke and text. While it recharges the ready colour grows from the left in step with the cooldown, so a
 * child sees when it is back; it is the one thing besides the label that says why it does nothing.
 */
@Composable
private fun DuelActionButton(button: ActionButton, glyph: Glyph, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced && button.enabled) 0.96f else 1f,
        animationSpec = if (pressed) tween(RudeusMotion.BUTTON_PRESS_MS, easing = RudeusMotion.Standard) else RudeusMotion.pop(),
        label = "press",
    )
    val recharging = button.cooldown > 0f
    val ink = if (button.enabled || recharging) RudeusColors.Ink else RudeusColors.InkMuted
    val base = when {
        button.enabled && primary -> if (pressed) RudeusColors.SunDeep else RudeusColors.Sun
        button.enabled -> if (pressed) RudeusColors.CreamDeep else RudeusColors.Paper
        primary -> RudeusColors.SunTint
        else -> RudeusColors.Paper
    }
    val charge = if (primary) RudeusColors.Sun else RudeusColors.CreamDeep
    val ready = (1f - button.cooldown).coerceIn(0f, 1f)
    val height = RudeusSpacing.primaryButton
    val shape = RoundedCornerShape(RudeusRadius.full)
    Row(
        modifier
            .height(height)
            .hitArea(height)
            .clickable(interactionSource = interaction, indication = null, enabled = button.enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = hitSlack(height))
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(base)
            .drawBehind { if (recharging) drawRect(charge, size = Size(size.width * ready, size.height)) }
            .border(RudeusSpacing.stroke, ink, shape)
            .padding(horizontal = RudeusSpacing.smd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        GlyphIcon(glyph, ink, size = 22.dp)
        Text(
            t(button.label),
            style = MaterialTheme.typography.labelLarge.copy(fontSize = if (primary) 18.sp else 16.sp),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
