package fr.enry.archivist.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** API 33+ has dedicated media permissions; below it, the one blanket storage
 * permission covers the same ground — see the manifest entries' own comment. */
private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

private fun hasPermission(context: android.content.Context): Boolean =
    requiredPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/**
 * Plan step 2.7. Owns the runtime-permission ceremony itself (rationale, then request)
 * — unlike this app's other screens, that's inherently platform/Activity-bound, not
 * something a plain state+callback composable can express, so it lives here rather
 * than being pushed into [FoldersViewModel]. Not yet reachable from anywhere in the
 * app's navigation — that's plan step 2.14's job; this screen is complete and tested
 * on its own ahead of that wiring.
 */
@Composable
fun FoldersScreen(
    modifier: Modifier = Modifier,
    viewModel: FoldersViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPermission(context)) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            granted = results.values.all { it }
        }

    LaunchedEffect(granted) {
        if (granted) viewModel.onPermissionGranted()
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (!granted) {
        PermissionRationale(onRequestPermission = { launcher.launch(requiredPermissions()) }, modifier = modifier)
        return
    }

    when (val state = uiState) {
        FoldersUiState.NeedsPermission, FoldersUiState.Loading ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        is FoldersUiState.Loaded ->
            FolderList(
                state = state,
                onToggleFolder = viewModel::setFolderEnabled,
                modifier = modifier,
            )
    }
}

@Composable
private fun PermissionRationale(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Back up your photos", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Archivist backs up the folders you choose below — nothing is uploaded until " +
                "you select one, and it only ever reads the folders you've selected.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = onRequestPermission) { Text("Grant access") }
    }
}

@Composable
private fun FolderList(
    state: FoldersUiState.Loaded,
    onToggleFolder: (FolderUiItem, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Folders", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Deselecting a folder stops future uploads — it doesn't touch anything already backed up.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (state.isScanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        }
        state.lastScanQueued?.let { count ->
            Text(
                if (count == 1) "Queued 1 file." else "Queued $count files.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        LazyColumn {
            items(state.folders, key = { it.bucketId }) { folder ->
                FolderRow(folder, onToggle = { enabled -> onToggleFolder(folder, enabled) })
            }
        }
    }
}

@Composable
private fun FolderRow(
    folder: FolderUiItem,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(folder.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (folder.itemCount == 1) "1 item" else "${folder.itemCount} items",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = folder.enabled, onCheckedChange = onToggle)
    }
}
