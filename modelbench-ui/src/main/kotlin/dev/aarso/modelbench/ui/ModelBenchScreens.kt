package dev.aarso.modelbench.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aarso.modelbench.BenchmarkRun
import dev.aarso.modelbench.ModelBenchReport

/**
 * Lists completed benchmark runs, newest-looking-first order left to the caller (this composable
 * renders [reports] as given). v1 scaffolding: no ViewModel, no persistence, no Hyle — the host
 * app owns loading `modelbench-report.v1` files and wiring [onSelectReport]. Plain
 * `androidx.compose.foundation` only (no material3), same rule `:cell-shell` follows, so
 * embedding this screen never forces a design system on the host.
 */
@Composable
fun ModelBenchRunListScreen(
    reports: List<ModelBenchReport>,
    onSelectReport: (ModelBenchReport) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (reports.isEmpty()) {
        Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
            BasicText("No benchmark runs yet.")
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(reports, key = { it.reportId }) { report ->
            ModelBenchRunListRow(report = report, onClick = { onSelectReport(report) })
        }
    }
}

@Composable
private fun ModelBenchRunListRow(report: ModelBenchReport, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        BasicText("${report.model.ggufName} (${report.model.quant})")
        BasicText("${report.engine.name} ${report.engine.version} · ${report.device.deviceModel}")
        BasicText("${report.runs.size} sweep point(s) · ${report.generatedAt}")
    }
}

/** Shows one report's full sweep: per-point metrics, in run order. */
@Composable
fun ModelBenchRunDetailScreen(report: ModelBenchReport, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(modifier = Modifier.padding(16.dp)) {
                BasicText("${report.model.ggufName} (${report.model.quant})")
                BasicText("${report.engine.name} ${report.engine.version}")
                BasicText("Device: ${report.device.deviceModel}")
            }
        }
        items(report.runs, key = { it.contextLength }) { run ->
            ModelBenchRunRow(run)
        }
    }
}

@Composable
private fun ModelBenchRunRow(run: BenchmarkRun) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        BasicText("context ${run.contextLength} — prompt ${run.promptTokens}tok, generated ${run.generatedTokens}tok")
        val ttft = run.metrics.timeToFirstTokenMs?.let { "%.1f ms".format(it) } ?: "n/a"
        BasicText("TTFT $ttft · prefill ${"%.1f".format(run.metrics.promptProcessingTokPerSec)} tok/s · decode ${"%.1f".format(run.metrics.decodeTokPerSec)} tok/s")
        run.metrics.peakRssDeltaBytes?.let { BasicText("peak RSS delta: ${it / (1024 * 1024)} MB") }
        run.thermal?.let { BasicText("thermal: ${it.status}${it.batteryPercent?.let { pct -> " ($pct%)" } ?: ""}") }
    }
}
