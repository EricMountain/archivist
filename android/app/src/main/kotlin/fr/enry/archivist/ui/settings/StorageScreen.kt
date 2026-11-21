package fr.enry.archivist.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun StorageScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text("Storage", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
        Text(
            "Decrypted thumbnails are cached on disk so the timeline loads instantly and works offline. " +
                "Clearing it frees space; nothing is re-uploaded or lost, just re-fetched and re-decrypted next time.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Text(
            "Cache size: ${state.cacheSizeBytes?.let(::formatBytes) ?: "…"}",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Button(onClick = viewModel::clearCache, enabled = !state.isClearing) {
            Text(if (state.isClearing) "Clearing…" else "Clear cache")
        }
        Text(
            "Place data",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 32.dp, bottom = 8.dp),
        )
        Text(
            "Map data from your server (© OpenStreetMap contributors) that names the village, " +
                "park or peak a photo was taken at. It's downloaded a small area at a time, only for " +
                "places your uploads need, and an area unused for 90 days is removed by itself. " +
                "Clearing it is harmless: it's downloaded again when a photo needs it.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Text(
            "Place data: ${state.placeDataBytes?.let(::formatBytes) ?: "…"}",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Button(
            onClick = viewModel::clearPlaceData,
            enabled = !state.isClearingPlaceData && (state.placeDataBytes ?: 0L) > 0L,
        ) {
            Text(if (state.isClearingPlaceData) "Clearing…" else "Clear place data")
        }
    }
}

internal fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }
