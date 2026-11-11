package kz.rudy.ar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Stat
import kz.rudy.ar.core.domain.fighter
import kz.rudy.ar.ui.Lang
import kz.rudy.ar.ui.LocalLang
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

/** One row of the card: pure data, built without Compose so it can be tested. */
data class StatRow(val stat: Stat, val label: StringId, val value: Int, val reason: String?)

/** The copy-table id of a stat's name. */
fun statLabel(stat: Stat): StringId = when (stat) {
    Stat.POWER -> StringId.stat_power
    Stat.SPEED -> StringId.stat_speed
    Stat.GUARD -> StringId.stat_guard
    Stat.AGILITY -> StringId.stat_agility
}

/**
 * The four rows in [Stat] order for [sheet] in [lang]: values from `sheet.fighter`, reasons from
 * `sheet.statReasons[lang.code]`, falling back to "en" (per stat), then none. A sheet without stats has no reasons:
 * its numbers are the ability's archetype, not something Spark saw in the picture. A blank reason counts as none.
 */
fun fighterRows(sheet: CharacterSheet, lang: Lang): List<StatRow> {
    val fighter = sheet.fighter
    val drawn = hasDrawnStats(sheet)
    return Stat.entries.map { stat ->
        val reason = if (!drawn) {
            null
        } else {
            (sheet.statReasons[lang.code]?.get(stat)?.takeIf { it.isNotBlank() }
                ?: sheet.statReasons[Lang.EN.code]?.get(stat)?.takeIf { it.isNotBlank() })?.trim()
        }
        StatRow(stat, statLabel(stat), fighter[stat], reason)
    }
}

/** True when the numbers came from the picture (sheet.stats != null), false when they are the ability's archetype. */
fun hasDrawnStats(sheet: CharacterSheet): Boolean = sheet.stats != null

/**
 * The fighter's four duel stats: a Paper card (radius `card`, no stroke), a heading, then per stat a label, a
 * 10-segment bar, the number and (unless [compact]) Spark's reason on a second line. One bar colour (Sky): the
 * tokens have no four colours that read on Paper without borrowing a status colour (Tomato = error, Hazard = cliff,
 * Sun = the one primary), and the label and number carry the meaning anyway. Stateless; shown on the Processing
 * screen's done state and, twice side by side with [compact] = true, on the duel's VS screen.
 */
@Composable
fun FighterStatsCard(sheet: CharacterSheet, modifier: Modifier = Modifier, compact: Boolean = false) {
    val lang = LocalLang.current
    val rows = fighterRows(sheet, lang)
    val pad = if (compact) RudeusSpacing.smd else RudeusSpacing.md
    val labelWidth = if (compact) 76.dp else 100.dp
    val textStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelLarge
    Column(
        modifier
            .clip(RoundedCornerShape(RudeusRadius.card))
            .background(RudeusColors.Paper)
            .padding(pad),
        verticalArrangement = Arrangement.spacedBy(if (compact) RudeusSpacing.sm else RudeusSpacing.smd),
    ) {
        Text(
            t(StringId.stats_title),
            style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
            color = RudeusColors.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        rows.forEach { row ->
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.sm),
                ) {
                    Text(
                        t(row.label),
                        style = textStyle,
                        color = RudeusColors.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(labelWidth),
                    )
                    StatBar(row.value, Modifier.weight(1f))
                    Text(
                        row.value.toString(),
                        style = textStyle,
                        color = RudeusColors.Ink,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(if (compact) 20.dp else 24.dp),
                    )
                }
                if (!compact && row.reason != null) {
                    Text(
                        row.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = RudeusColors.InkSoft,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = labelWidth + RudeusSpacing.sm),
                    )
                }
            }
        }
        if (hasDrawnStats(sheet)) {
            Text(
                t(StringId.stats_from_drawing),
                style = MaterialTheme.typography.bodySmall,
                color = RudeusColors.InkSoft,
            )
        }
    }
}

/** [FighterStats.MAX] segments, the first [value] filled Sky, the rest CreamDeep (the track token). */
@Composable
private fun StatBar(value: Int, modifier: Modifier = Modifier) {
    Row(modifier.height(10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(FighterStats.MAX) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(RudeusRadius.tag / 4))
                    .background(if (i < value) RudeusColors.Sky else RudeusColors.CreamDeep),
            )
        }
    }
}
