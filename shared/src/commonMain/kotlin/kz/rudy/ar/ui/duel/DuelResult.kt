package kz.rudy.ar.ui.duel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.PrimaryButton
import kz.rudy.ar.ui.components.SecondaryButton
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

/** True = the player won, false = lost, null = a draw. From the RESULT banner; from the points when the match is not over yet. */
internal fun resultWon(model: DuelHudModel): Boolean? = when (val b = model.banner) {
    is DuelBanner.Result -> b.won
    else -> when {
        model.me.score > model.foe.score -> true
        model.me.score < model.foe.score -> false
        else -> null
    }
}

/**
 * The end of the match: who won (or a draw), the score with both names, "Again" (Sun) and "Done" over a Scrim that takes
 * the touches (the AR view under it stays quiet). Nobody is beaten: the loser's line is "Good try". Stateless.
 */
@Composable
fun DuelResult(model: DuelHudModel, onRematch: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(RudeusRadius.hero)
    val verdict = when (resultWon(model)) {
        true -> StringId.duel_won
        false -> StringId.duel_lost
        null -> StringId.duel_draw
    }
    Box(
        modifier
            .fillMaxSize()
            // The scrim is part of the screen: a tap on it must not fall through to the AR view.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .background(RudeusColors.Scrim)
            .safeDrawingPadding()
            .padding(RudeusSpacing.screenEdge),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(RudeusColors.Paper)
                .border(RudeusSpacing.hairline, RudeusColors.Line, shape)
                .padding(RudeusSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RudeusSpacing.md),
        ) {
            Text(t(verdict), style = MaterialTheme.typography.displayLarge, color = RudeusColors.Ink, textAlign = TextAlign.Center)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.smd)) {
                Text(
                    model.me.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = RudeusColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${model.me.score} : ${model.foe.score}",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 44.sp, lineHeight = 50.sp),
                    color = RudeusColors.Ink,
                )
                Text(
                    model.foe.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = RudeusColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            PrimaryButton(t(StringId.duel_again), onRematch, Modifier.fillMaxWidth(), glyph = Glyph.RETRY)
            SecondaryButton(t(StringId.duel_done), onDone, Modifier.fillMaxWidth(), glyph = Glyph.CHECK)
        }
    }
}
