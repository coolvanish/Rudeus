package kz.rudy.ar.ui.duel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kz.rudy.ar.ui.LocalLang
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.CharacterCard
import kz.rudy.ar.ui.components.Chip
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.PrimaryButton
import kz.rudy.ar.ui.components.SecondaryButton
import kz.rudy.ar.ui.screens.BundledCharacter
import kz.rudy.ar.ui.screens.EmptyCard
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusSpacing

/** The difficulty chip's label. */
internal fun difficultyLabel(difficulty: DuelDifficulty): StringId = when (difficulty) {
    DuelDifficulty.EASY -> StringId.difficulty_easy
    DuelDifficulty.NORMAL -> StringId.difficulty_normal
    DuelDifficulty.HARD -> StringId.difficulty_hard
}

/**
 * Who do you play: the shelf's cards without the player's own character ([mineId]), three difficulty chips, a primary
 * "Play" (enabled once a card is picked) and a secondary "Watch" (the bots play each other; it needs the pick too, to
 * know who plays). Stateless: [selectedId] and [difficulty] come from the caller. A Paper screen like Capture.
 */
@Composable
fun OpponentPicker(
    characters: List<BundledCharacter>,
    mineId: String,
    selectedId: String?,
    difficulty: DuelDifficulty,
    onPick: (String) -> Unit,
    onDifficulty: (DuelDifficulty) -> Unit,
    onStart: () -> Unit,
    onWatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lang = LocalLang.current
    val others = characters.filter { it.id != mineId }
    val picked = selectedId != null && others.any { it.id == selectedId }
    Column(
        modifier
            .fillMaxSize()
            .background(RudeusColors.Paper)
            .safeDrawingPadding()
            .padding(horizontal = RudeusSpacing.screenEdge, vertical = RudeusSpacing.md),
        verticalArrangement = Arrangement.spacedBy(RudeusSpacing.smd),
    ) {
        Text(t(StringId.duel_title), style = MaterialTheme.typography.displayLarge, color = RudeusColors.Ink)
        Text(t(StringId.duel_pick_opponent), style = MaterialTheme.typography.titleMedium, color = RudeusColors.InkSoft)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(124.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.smd),
            verticalArrangement = Arrangement.spacedBy(RudeusSpacing.smd),
        ) {
            if (others.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyCard(t(StringId.capture_empty)) }
            }
            items(others, key = { it.id }) { c ->
                CharacterCard(
                    name = c.sheet.nameIn(lang.code),
                    ability = c.sheet.ability,
                    selected = c.id == selectedId,
                    onClick = { onPick(c.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) { FighterArt(c) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.sm)) {
            DuelDifficulty.entries.forEach { d ->
                Chip(t(difficultyLabel(d)), Modifier.weight(1f), isOn = d == difficulty, onClick = { onDifficulty(d) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(t(StringId.duel_watch), onWatch, Modifier.weight(1f), glyph = Glyph.EYE, enabled = picked)
            PrimaryButton(t(StringId.duel_fight), onStart, Modifier.weight(1.5f), enabled = picked)
        }
        Spacer(Modifier.height(RudeusSpacing.xs))
    }
}
