package fr.enry.archivist.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import fr.enry.archivist.crypto.EncryptedThumbRef
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The rung shown in the trash list — same choice as the grid's own cell. */
private const val THUMB_SIZE = 256

/**
 * Plan step 2.13: trashed assets pending purge, each warning if a source keeps
 * re-offering the file (`GET /trash`'s `blockedAttempts`/`lastAttemptBy`) — see
 * `TrashViewModel`'s own doc for why this isn't paginated. Not yet reachable from any
 * navigation structure this app has (there is none — plan step 2.14 is Settings, which
 * is where a permanent entry point belongs); `TimelineScreen` links to it directly in
 * the meantime, the same "build it standalone, wire it up properly later" approach
 * plan step 2.7's `FoldersScreen` already used.
 */
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val host by viewModel.cdnHost.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    Column(modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text("Trash", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp))

        when (val state = uiState) {
            TrashUiState.Loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

            is TrashUiState.Error ->
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Couldn't load the trash: ${state.message}")
                        Button(onClick = viewModel::load) { Text("Retry") }
                    }
                }

            is TrashUiState.Loaded ->
                if (state.items.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("Nothing in the trash.")
                    }
                } else {
                    LazyColumn {
                        items(state.items, key = { it.photoId }) { item ->
                            TrashRow(item, host)
                            HorizontalDivider()
                        }
                    }
                }
        }
    }
}

@Composable
private fun TrashRow(
    item: TrashItem,
    host: String?,
) {
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val entry = item.thumbs[THUMB_SIZE] ?: item.thumbs.entries.minByOrNull { it.key }?.value
        if (host != null && entry != null) {
            AsyncImage(
                model =
                    EncryptedThumbRef(
                        photoId = item.photoId,
                        longestEdge = THUMB_SIZE,
                        url = "https://$host/thumbs/${entry.key}",
                        iv = entry.iv,
                        encDek = item.encDek,
                    ),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp),
            )
        } else {
            Box(Modifier.size(64.dp))
        }

        Column {
            Text(formatTrashDate(item.takenAt, item.tzOffsetMin), style = MaterialTheme.typography.bodyMedium)
            val attempts = item.blockedAttempts
            if (attempts != null && attempts > 0) {
                Text(
                    blockedAttemptWarning(attempts, item.lastAttemptBy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** The literal warning `design.md`'s "Tombstones expire, and blocked attempts are
 * surfaced" calls for: *"3 attempts to re-upload this from home-server — delete it
 * there too, or it returns."* [lastAttemptBy] is the `deviceKey` the re-upload's own
 * `POST /uploads` sent — absent only if the pointer predates that field, in which case
 * this falls back to "another device" rather than showing nothing. */
internal fun blockedAttemptWarning(
    attempts: Int,
    lastAttemptBy: String?,
): String {
    val times = if (attempts == 1) "1 attempt" else "$attempts attempts"
    val source = lastAttemptBy ?: "another device"
    return "$times to re-upload this from $source — delete it there too, or it returns."
}

private val TRASH_DATE_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())

internal fun formatTrashDate(
    takenAt: String,
    tzOffsetMin: Int,
): String = Instant.parse(takenAt).atOffset(ZoneOffset.ofTotalSeconds(tzOffsetMin * 60)).format(TRASH_DATE_FORMATTER)
