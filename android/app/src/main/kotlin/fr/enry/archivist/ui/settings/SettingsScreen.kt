package fr.enry.archivist.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.enry.archivist.ui.trash.TrashScreen

private enum class SettingsDestination { SYNC, DEVICES, KEYS, STORAGE, ACCOUNT, TRASH }

/**
 * Plan step 2.14: "the minimum that isn't hostile" — a plain menu over the five
 * sections the plan names (Sync, Devices, Keys, Storage, Account), plus the Trash
 * entry point plan step 2.13 deferred here (its own STATUS.md note: "plan step 2.14 is
 * Settings, which is where a permanent entry point belongs"). Local `destination`
 * state, not a nav library — same "standalone screen, plain local toggle" convention
 * [TimelineScreen][fr.enry.archivist.ui.timeline.TimelineScreen] already uses for
 * [fr.enry.archivist.ui.detail.DetailScreen] and this same Trash screen.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSessionEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var destination by remember { mutableStateOf<SettingsDestination?>(null) }

    when (destination) {
        SettingsDestination.SYNC -> SyncScreen(onBack = { destination = null }, modifier = modifier)
        SettingsDestination.DEVICES -> DevicesScreen(onBack = { destination = null }, modifier = modifier)
        SettingsDestination.KEYS -> KeysScreen(onBack = { destination = null }, modifier = modifier)
        SettingsDestination.STORAGE -> StorageScreen(onBack = { destination = null }, modifier = modifier)
        SettingsDestination.ACCOUNT ->
            AccountScreen(onBack = { destination = null }, onSessionEnded = onSessionEnded, modifier = modifier)
        SettingsDestination.TRASH -> TrashScreen(onBack = { destination = null }, modifier = modifier)
        null -> SettingsMenu(onBack = onBack, onSelect = { destination = it }, modifier = modifier)
    }
}

@Composable
private fun SettingsMenu(
    onBack: () -> Unit,
    onSelect: (SettingsDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        MenuRow("Sync", "Folders, network policy, charging") { onSelect(SettingsDestination.SYNC) }
        HorizontalDivider()
        MenuRow("Devices", "Timezone defaults for your cameras") { onSelect(SettingsDestination.DEVICES) }
        HorizontalDivider()
        MenuRow("Keys", "Enrolled devices, recovery code") { onSelect(SettingsDestination.KEYS) }
        HorizontalDivider()
        MenuRow("Storage", "Thumbnail cache") { onSelect(SettingsDestination.STORAGE) }
        HorizontalDivider()
        MenuRow("Trash", "Recently deleted photos") { onSelect(SettingsDestination.TRASH) }
        HorizontalDivider()
        MenuRow("Account", "Sign out, delete account") { onSelect(SettingsDestination.ACCOUNT) }
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
