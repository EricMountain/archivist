package fr.enry.archivist.ui.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.enry.archivist.data.local.db.UploadState
import fr.enry.archivist.sync.QueueIdleReason

/**
 * Plan step 2.15: pending/in-progress/failed uploads with per-item errors and retry,
 * plus the idle-reason banner its own "Done when" calls out by name ("pausing on a
 * metered network shows the reason rather than nothing"). Not yet reachable from
 * anywhere but `SettingsScreen`'s menu -- same "permanent entry point lives in
 * Settings" convention plan step 2.14 established for Trash.
 */
@Composable
fun QueueScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QueueViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text("Upload queue", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp))

        idleReasonLabel(uiState.idleReason)?.let { reason ->
            Text(
                reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (uiState.items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Nothing queued.")
            }
        } else {
            LazyColumn {
                items(uiState.items, key = { it.id }) { item ->
                    QueueRow(item, onRetry = { viewModel.retry(item.id) }, onCancel = { viewModel.cancel(item.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(stateLabel(item.state), style = MaterialTheme.typography.bodySmall)
            if (item.state == UploadState.FAILED && item.lastError != null) {
                Text(item.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (item.state == UploadState.FAILED) {
            TextButton(onClick = onRetry) { Text("Retry") }
        } else {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

private fun stateLabel(state: UploadState): String =
    when (state) {
        UploadState.PENDING -> "Waiting"
        UploadState.EXTRACTING -> "Reading metadata"
        UploadState.THUMBNAILING -> "Generating thumbnails"
        UploadState.UPLOADING -> "Uploading"
        UploadState.DONE -> "Done"
        UploadState.FAILED -> "Failed"
    }

private fun idleReasonLabel(reason: QueueIdleReason): String? =
    when (reason) {
        QueueIdleReason.NONE -> null
        QueueIdleReason.NO_NETWORK -> "Waiting for a network connection"
        QueueIdleReason.WAITING_FOR_WIFI -> "Waiting for Wi-Fi"
        QueueIdleReason.WAITING_TO_CHARGE -> "Waiting to charge"
        QueueIdleReason.WAITING_FOR_BATTERY -> "Waiting for battery"
    }
