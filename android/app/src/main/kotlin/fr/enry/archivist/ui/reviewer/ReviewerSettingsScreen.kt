package fr.enry.archivist.ui.reviewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.enry.archivist.sync.DeviceFolder
import fr.enry.archivist.ui.settings.StorageScreen

private enum class ReviewerSettingsDestination { SYNC, QUEUE, DEVICES, KEYS, STORAGE, TRASH, ACCOUNT }

/**
 * Plan step 2.17's follow-up: a reviewer should be able to reach every Settings section,
 * not just the timeline. Same menu, same section labels as the real
 * `ui/settings/SettingsScreen.kt`, so a reviewer sees the app's real information
 * architecture — but every section here is either genuinely local (Storage reuses the
 * real screen outright, since its cache lives on disk and touches no account; Sync's
 * folder list reads real device folders via [ReviewerSettingsViewModel]) or a plain
 * explanation of what would be here with a real instance connected. Nothing here
 * constructs `AuthRepository`, `DeviceRepository`, `EnrolmentRepository` or any other
 * network-capable type — the real Devices/Keys/Trash/Account/Queue sections are wired to
 * exactly those, which is what would make them crash (no session, no instance) rather
 * than just not apply.
 */
@Composable
fun ReviewerSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var destination by remember { mutableStateOf<ReviewerSettingsDestination?>(null) }

    when (destination) {
        ReviewerSettingsDestination.SYNC -> ReviewerSyncSection(onBack = { destination = null }, modifier = modifier)
        ReviewerSettingsDestination.QUEUE ->
            InertSection(
                title = "Upload queue",
                body = "Nothing queued — preview mode never uploads anything.",
                onBack = { destination = null },
                modifier = modifier,
            )
        ReviewerSettingsDestination.DEVICES ->
            InertSection(
                title = "Devices",
                body = "The cameras you back up from appear here, with editable timezone defaults, once " +
                    "they've uploaded a photo to a real instance. There's nothing to show without one.",
                onBack = { destination = null },
                modifier = modifier,
            )
        ReviewerSettingsDestination.KEYS ->
            InertSection(
                title = "Keys",
                body = "Enrolled devices and your recovery code live here once you've connected to a real " +
                    "instance and enrolled one. Preview mode doesn't create either.",
                onBack = { destination = null },
                modifier = modifier,
            )
        ReviewerSettingsDestination.STORAGE -> StorageScreen(onBack = { destination = null }, modifier = modifier)
        ReviewerSettingsDestination.TRASH ->
            InertSection(
                title = "Trash",
                body = "Deleted photos stay here for a while on a real instance so they can be restored. " +
                    "There's no server in preview mode to hold them.",
                onBack = { destination = null },
                modifier = modifier,
            )
        ReviewerSettingsDestination.ACCOUNT ->
            InertSection(
                title = "Account",
                body = "There's no account in preview mode — nothing to sign out of or delete. Exit preview " +
                    "to connect to your own instance and sign in.",
                onBack = { destination = null },
                modifier = modifier,
            )
        null -> ReviewerSettingsMenu(onBack = onBack, onSelect = { destination = it }, modifier = modifier)
    }
}

@Composable
private fun ReviewerSettingsMenu(
    onBack: () -> Unit,
    onSelect: (ReviewerSettingsDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        MenuRow("Sync", "Folders, network policy, charging") { onSelect(ReviewerSettingsDestination.SYNC) }
        HorizontalDivider()
        MenuRow("Upload queue", "Progress, errors and retry") { onSelect(ReviewerSettingsDestination.QUEUE) }
        HorizontalDivider()
        MenuRow("Devices", "Timezone defaults for your cameras") { onSelect(ReviewerSettingsDestination.DEVICES) }
        HorizontalDivider()
        MenuRow("Keys", "Enrolled devices, recovery code") { onSelect(ReviewerSettingsDestination.KEYS) }
        HorizontalDivider()
        MenuRow("Storage", "Thumbnail cache") { onSelect(ReviewerSettingsDestination.STORAGE) }
        HorizontalDivider()
        MenuRow("Trash", "Recently deleted photos") { onSelect(ReviewerSettingsDestination.TRASH) }
        HorizontalDivider()
        MenuRow("Account", "Sign out, delete account") { onSelect(ReviewerSettingsDestination.ACCOUNT) }
    }
}

@Composable
private fun MenuRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 16.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The one section with real, if ephemeral, content: the device's actual folder names
 * (via [ReviewerSettingsViewModel]) and switches that visibly flip. None of it is
 * persisted and none of it starts anything — selecting a folder in the real app queues
 * uploads via `Scanner`/`UploadScheduler`, both of which need a signed-in session this
 * mode never has.
 */
@Composable
private fun ReviewerSyncSection(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReviewerSettingsViewModel = hiltViewModel(),
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    var allowMeteredNetwork by remember { mutableStateOf(false) }
    var requiresCharging by remember { mutableStateOf(false) }
    var selectedFolders by remember(folders) { mutableStateOf(folders.map { it.bucketId }.toSet()) }

    Column(modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text("Sync", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Text(
            "Preview only — nothing below is saved, and selecting a folder doesn't queue an upload.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        SwitchRow("Upload on any network", "Otherwise uploads wait for Wi-Fi", allowMeteredNetwork) { allowMeteredNetwork = it }
        SwitchRow("Only upload while charging", null, requiresCharging) { requiresCharging = it }
        HorizontalDivider()
        if (folders.isEmpty()) {
            Text(
                "No folders found on this device.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(folders, key = { it.bucketId }) { folder ->
                    FolderRow(
                        folder = folder,
                        checked = folder.bucketId in selectedFolders,
                        onCheckedChange = { checked ->
                            selectedFolders =
                                if (checked) selectedFolders + folder.bucketId else selectedFolders - folder.bucketId
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    folder: DeviceFolder,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(folder.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (folder.itemCount == 1) "1 item" else "${folder.itemCount} items",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** The shape shared by every Settings section that has nothing to show without a real
 * instance — same "← Back, heading, one paragraph" structure as the real screens, minus
 * any control that would need a session to act on. */
@Composable
private fun InertSection(
    title: String,
    body: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
