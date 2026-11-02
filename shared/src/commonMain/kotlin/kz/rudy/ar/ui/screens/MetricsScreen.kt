package kz.rudy.ar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.rudy.ar.core.metrics.MetricsRun
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.BannerKind
import kz.rudy.ar.ui.components.GhostButton
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.PrimaryButton
import kz.rudy.ar.ui.components.SecondaryButton
import kz.rudy.ar.ui.components.StatusBanner
import kz.rudy.ar.ui.components.Tag
import kz.rudy.ar.ui.components.TagTone
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMono
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

/** Shown in a table cell that has no value (a cancelled character's score and retries). */
private const val NO_VALUE = "—"

enum class ExportStatus { IDLE, SAVING, SAVED, FAILED }

/**
 * Metrics: this phone's run (`CaptureHost.latestRun()`, core's [MetricsRun] as written to runs/<runId>.json, see
 * .claude/skills/metrics) and Export JSON. A dev screen, so denser than the rest. [exportLocation] is the user-visible
 * place the last export landed. [canExport] is false without a host (the demo shell has nowhere to write).
 */
@Composable
fun MetricsScreen(
    run: MetricsRun?,
    exportStatus: ExportStatus,
    onExportJson: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    exportLocation: String? = null,
    canExport: Boolean = true,
) {
    Box(modifier.fillMaxSize().background(RudeusColors.Paper)) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(start = RudeusSpacing.screenEdge, end = RudeusSpacing.screenEdge, bottom = RudeusSpacing.smd),
        ) {
            Box(Modifier.height(52.dp), contentAlignment = Alignment.CenterStart) {
                GhostButton(t(StringId.back), onClick = onBack, glyph = Glyph.BACK, modifier = Modifier.offset(x = (-14).dp))
            }
            Text(t(StringId.metrics_title), style = MaterialTheme.typography.titleLarge, color = RudeusColors.Ink)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (run == null) {
                    Spacer(Modifier.height(RudeusSpacing.md))
                    EmptyCard(t(StringId.metrics_empty), hint = t(StringId.metrics_empty_hint))
                } else {
                    RunBody(run)
                }
            }
            Spacer(Modifier.height(RudeusSpacing.smd))
            StatusBanner(
                visible = exportStatus == ExportStatus.FAILED,
                title = t(StringId.metrics_export_failed),
                kind = BannerKind.ERROR,
                action = { SecondaryButton(t(StringId.retry), onClick = onExportJson, glyph = Glyph.RETRY, compact = true) },
            )
            if (exportStatus == ExportStatus.FAILED) Spacer(Modifier.height(RudeusSpacing.sm))
            if (exportStatus == ExportStatus.SAVED) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.sm)) {
                    Tag(t(StringId.metrics_exported), tone = TagTone.LEAF, glyph = Glyph.CHECK)
                    if (exportLocation != null) {
                        Text(
                            "${t(StringId.metrics_export_where)}: $exportLocation",
                            style = MONO_SMALL,
                            color = RudeusColors.InkSoft,
                        )
                    }
                }
                Spacer(Modifier.height(RudeusSpacing.sm))
            }
            PrimaryButton(
                text = t(StringId.metrics_export),
                glyph = Glyph.EXPORT,
                enabled = run != null && canExport && exportStatus != ExportStatus.SAVING,
                onClick = onExportJson,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val MONO_SMALL = RudeusMono.copy(fontSize = 12.sp, lineHeight = 17.sp)

/** Retries is the longest header (ҚАЙТАЛАУ); a scan's accuracy cell lists one number per object. */
private val CHARACTER_COLUMNS = listOf(1.4f, 1f, 1.2f, 1f, 0.9f)
private val SCAN_COLUMNS = listOf(0.8f, 0.8f, 2.6f)

@Composable
private fun RunBody(run: MetricsRun) {
    Spacer(Modifier.height(6.dp))
    Text(
        "${t(StringId.metrics_run)} ${run.runId}\n${run.source} · ${run.provider} · ${run.device}",
        style = MONO_SMALL,
        color = RudeusColors.InkSoft,
    )
    run.summary.takeIf { it.n > 0 }?.let { s ->
        Spacer(Modifier.height(RudeusSpacing.md))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tile(t(StringId.metrics_pass_rate), formatPercent(s.gatePassRateFirstTry.toDouble()), Modifier.weight(1f))
            Tile(t(StringId.metrics_p50), formatSeconds(s.p50Ms), Modifier.weight(1f))
            Tile(t(StringId.metrics_p90), formatSeconds(s.p90Ms), Modifier.weight(1f))
            Tile(t(StringId.metrics_cost), formatCents(s.costPerCharacterUsd), Modifier.weight(1f))
        }
    }
    // The header counts finished characters, like summary.n; cancelled ones (CharacterMetrics.aborted) are listed apart.
    val cancelled = run.characters.count { it.aborted }
    val finished = run.characters.size - cancelled
    SectionHeader(
        t(StringId.metrics_characters),
        if (cancelled > 0) "$finished · $cancelled ${t(StringId.metrics_cancelled)}" else "$finished",
    )
    TableRow(
        listOf(t(StringId.metrics_col_input), t(StringId.metrics_col_score), t(StringId.metrics_col_retries), t(StringId.metrics_col_time), t(StringId.metrics_col_cost)),
        CHARACTER_COLUMNS,
        header = true,
    )
    run.characters.forEachIndexed { i, c ->
        // A cancelled character has no verdict: no score, no retries, muted (not a red "failed").
        TableRow(
            listOf(
                c.input.substringAfterLast('/').substringBeforeLast('.'),
                if (c.aborted) NO_VALUE else formatFixed(c.gateScore, 3),
                if (c.aborted) NO_VALUE else c.retries.toString(),
                formatSecondsCell(c.totalMs),
                formatUsdCell(c.totalCostUsd),
            ),
            CHARACTER_COLUMNS,
            striped = i % 2 == 1,
            muted = c.aborted,
        )
    }
    if (run.scans.isNotEmpty()) {
        SectionHeader(t(StringId.metrics_scans), "${run.scans.size}")
        TableRow(listOf(t(StringId.metrics_col_objects), t(StringId.metrics_col_time), t(StringId.metrics_col_within_cm)), SCAN_COLUMNS, header = true)
        run.scans.forEachIndexed { i, s ->
            TableRow(
                listOf(s.objects.toString(), formatSecondsCell(s.ms), s.withinCm.joinToString(" ") { formatFixed(it, 1) }),
                SCAN_COLUMNS,
                striped = i % 2 == 1,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: String) {
    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.sm)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = RudeusColors.Ink)
        Text(count, style = MaterialTheme.typography.bodySmall, color = RudeusColors.InkSoft)
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(RudeusRadius.tile))
            .background(RudeusColors.Cream)
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, lineHeight = 16.sp),
            color = RudeusColors.InkSoft,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = RudeusMono.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp),
            color = RudeusColors.Ink,
            maxLines = 1,
        )
    }
}

/** The first column reads as text, the rest are right-aligned numbers. */
@Composable
private fun TableRow(
    cells: List<String>,
    weights: List<Float>,
    header: Boolean = false,
    striped: Boolean = false,
    muted: Boolean = false,
) {
    val color = if (muted) RudeusColors.InkMuted else RudeusColors.Ink
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (striped) RudeusColors.Cream else RudeusColors.Paper)
                .padding(vertical = if (header) 6.dp else 7.dp, horizontal = RudeusSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.xs),
        ) {
            cells.forEachIndexed { i, cell ->
                val style = when {
                    header -> MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.66.sp)
                    i == 0 -> MaterialTheme.typography.bodyLarge.copy(fontSize = 13.5.sp, lineHeight = 18.sp)
                    else -> RudeusMono.copy(fontSize = 13.5.sp)
                }
                Text(
                    if (header) cell.uppercase() else cell,
                    style = style,
                    color = if (header) RudeusColors.InkSoft else color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                    modifier = Modifier.weight(weights[i]),
                )
            }
        }
        if (header) Box(Modifier.fillMaxWidth().height(RudeusSpacing.hairline).background(RudeusColors.Line))
    }
}
