package kz.rudy.ar.ui.screens

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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kz.rudy.ar.core.metrics.MetricsRun
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.BannerKind
import kz.rudy.ar.ui.components.Chip
import kz.rudy.ar.ui.components.GhostButton
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.PrimaryButton
import kz.rudy.ar.ui.components.StatusBanner
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

enum class ExportStatus { IDLE, SAVING, SAVED, FAILED }

/**
 * Metrics: this phone's run (`CaptureHost.latestRun()`, core's [MetricsRun] as written to runs/<runId>.json, see
 * .claude/skills/metrics) and Export JSON. [exportLocation] is the user-visible place the last export landed.
 * [canExport] is false without a host (the demo shell has nowhere to write).
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
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(RudeusSpacing.md)) {
            Text(t(StringId.metrics_title), style = MaterialTheme.typography.displayLarge, color = RudeusColors.Ink)
            Spacer(Modifier.height(RudeusSpacing.md))
            StatusBanner(
                visible = exportStatus == ExportStatus.FAILED,
                title = t(StringId.metrics_export_failed),
                kind = BannerKind.ERROR,
                glyph = Glyph.CROSS,
                action = { GhostButton(t(StringId.retry), onClick = onExportJson) },
            )
            if (exportStatus == ExportStatus.SAVED) {
                Chip(
                    text = t(StringId.metrics_exported),
                    glyph = Glyph.CHECK,
                    container = RudeusColors.Leaf,
                    accent = RudeusColors.Leaf,
                    contentColor = RudeusColors.Paper,
                )
                if (exportLocation != null) {
                    Text(
                        "${t(StringId.metrics_export_where)}: $exportLocation",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RudeusColors.InkSoft,
                    )
                }
                Spacer(Modifier.height(RudeusSpacing.sm))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (run == null) {
                    EmptyCard(t(StringId.metrics_empty), t(StringId.metrics_empty_hint))
                } else {
                    RunBody(run)
                }
            }
            Spacer(Modifier.height(RudeusSpacing.md))
            PrimaryButton(
                text = t(StringId.metrics_export),
                glyph = Glyph.EXPORT,
                enabled = run != null && canExport && exportStatus != ExportStatus.SAVING,
                onClick = onExportJson,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(RudeusSpacing.sm))
            GhostButton(t(StringId.back), onClick = onBack, glyph = Glyph.BACK)
        }
    }
}

@Composable
private fun RunBody(run: MetricsRun) {
    Text(
        "${t(StringId.metrics_run)} ${run.runId} · ${run.source} · ${run.provider} · ${run.device}",
        style = MaterialTheme.typography.bodyMedium,
        color = RudeusColors.InkSoft,
    )
    Spacer(Modifier.height(RudeusSpacing.md))
    run.summary.takeIf { it.n > 0 }?.let { s ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.sm)) {
            Tile(t(StringId.metrics_pass_rate), formatPercent(s.gatePassRateFirstTry.toDouble()), Modifier.weight(1f))
            Tile(t(StringId.metrics_p50), formatSeconds(s.p50Ms), Modifier.weight(1f))
        }
        Spacer(Modifier.height(RudeusSpacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.sm)) {
            Tile(t(StringId.metrics_p90), formatSeconds(s.p90Ms), Modifier.weight(1f))
            Tile(t(StringId.metrics_cost), formatUsd(s.costPerCharacterUsd), Modifier.weight(1f))
        }
        Spacer(Modifier.height(RudeusSpacing.lg))
    }
    Text("${t(StringId.metrics_characters)} (${run.characters.size})", style = MaterialTheme.typography.titleLarge, color = RudeusColors.Ink)
    Spacer(Modifier.height(RudeusSpacing.sm))
    TableRow(
        listOf(t(StringId.metrics_col_input), t(StringId.metrics_col_score), t(StringId.metrics_col_retries), t(StringId.metrics_col_time), t(StringId.metrics_col_cost)),
        header = true,
    )
    run.characters.forEachIndexed { i, c ->
        TableRow(
            listOf(c.input.substringAfterLast('/'), formatFixed(c.gateScore, 2), c.retries.toString(), formatSeconds(c.totalMs), formatUsd(c.totalCostUsd)),
            striped = i % 2 == 1,
            accent = if (c.gatePassed) RudeusColors.Leaf else RudeusColors.Tomato,
        )
    }
    if (run.scans.isNotEmpty()) {
        Spacer(Modifier.height(RudeusSpacing.lg))
        Text("${t(StringId.metrics_scans)} (${run.scans.size})", style = MaterialTheme.typography.titleLarge, color = RudeusColors.Ink)
        Spacer(Modifier.height(RudeusSpacing.sm))
        TableRow(listOf(t(StringId.metrics_col_objects), t(StringId.metrics_col_time), t(StringId.metrics_col_within_cm)), header = true)
        run.scans.forEachIndexed { i, s ->
            TableRow(
                listOf(s.objects.toString(), formatSeconds(s.ms), s.withinCm.joinToString(" ") { formatFixed(it, 1) }),
                striped = i % 2 == 1,
            )
        }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(RudeusRadius.card)
    Column(
        modifier
            .clip(shape)
            .background(RudeusColors.PaperDeep)
            .border(RudeusSpacing.stroke, RudeusColors.Ink, shape)
            .padding(RudeusSpacing.md),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = RudeusColors.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(RudeusSpacing.xs))
        Text(value, style = MaterialTheme.typography.titleLarge, color = RudeusColors.Ink, maxLines = 1)
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    header: Boolean = false,
    striped: Boolean = false,
    accent: androidx.compose.ui.graphics.Color? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (striped) RudeusColors.PaperDeep else RudeusColors.Paper)
            .padding(vertical = RudeusSpacing.sm, horizontal = RudeusSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (accent != null) {
            Box(Modifier.width(RudeusSpacing.xs).height(20.dp).background(accent))
            Spacer(Modifier.width(RudeusSpacing.xs))
        }
        cells.forEachIndexed { i, cell ->
            Text(
                cell,
                style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                color = if (header) RudeusColors.InkSoft else RudeusColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(if (i == 0) 1.6f else 1f),
            )
        }
    }
}

