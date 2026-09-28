package fr.enry.archivist.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.enry.archivist.data.metrics.ImageKind
import fr.enry.archivist.data.metrics.KindStats
import fr.enry.archivist.data.metrics.LoadOutcome
import fr.enry.archivist.data.metrics.Percentiles
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Settings > Stats — see [StatsViewModel] and [fr.enry.archivist.data.metrics.ImageLoadMetrics]. */
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text("Stats", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
        Text(
            "Measured on this device only and never sent anywhere. Load counts and times reset when the app restarts.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        Section("Cache")
        state.cache?.let { cache ->
            StatRow("Disk", "${formatBytes(cache.diskBytes)} of ${formatBytes(cache.diskMaxBytes)}")
            StatRow("Memory", "${formatBytes(cache.memoryBytes)} of ${formatBytes(cache.memoryMaxBytes)}")
        } ?: Text("…")

        val metrics = state.metrics
        if (metrics != null) {
            Text(
                "Since ${SINCE_FORMATTER.format(Instant.ofEpochMilli(metrics.sinceMillis).atZone(ZoneId.systemDefault()))}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            KindSection("Grid thumbnails", metrics.byKind.getValue(ImageKind.GRID))
            KindSection("Detail images", metrics.byKind.getValue(ImageKind.DETAIL))
        }

        OutlinedButton(onClick = viewModel::reset, modifier = Modifier.padding(top = 16.dp)) { Text("Reset load stats") }
    }
}

@Composable
private fun KindSection(
    title: String,
    stats: KindStats,
) {
    Section(title)
    StatRow("Cache hit rate", stats.hitRate?.let { "%.1f%%".format(it * 100) } ?: "–")
    StatRow(
        "Loads",
        "${stats.counts[LoadOutcome.MEMORY]} memory · ${stats.counts[LoadOutcome.DISK]} disk · ${stats.counts[LoadOutcome.NETWORK]} network",
    )
    StatRow("Cancelled · failed", "${stats.counts[LoadOutcome.CANCELLED]} · ${stats.counts[LoadOutcome.ERROR]}")
    Text(
        "Time to image (ms)   p50 / p95 / p99",
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    StatRow("All", stats.overall.format())
    StatRow("Memory", stats.bySource[LoadOutcome.MEMORY].format())
    StatRow("Disk", stats.bySource[LoadOutcome.DISK].format())
    StatRow("Network", stats.bySource[LoadOutcome.NETWORK].format())
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun StatRow(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

private fun Percentiles?.format(): String = this?.let { "$p50 / $p95 / $p99  (n=$count)" } ?: "–"

private val SINCE_FORMATTER = DateTimeFormatter.ofPattern("d MMM HH:mm:ss")
