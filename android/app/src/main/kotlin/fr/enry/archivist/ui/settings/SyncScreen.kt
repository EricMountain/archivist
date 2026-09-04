package fr.enry.archivist.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Plan step 2.14's Settings > Sync section — network policy and charging requirement on
 * top, [FoldersScreen] (plan step 2.7, previously built but unreachable — see its own
 * doc) underneath. One screen, since the plan groups them under a single "Sync" bullet.
 */
@Composable
fun SyncScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("← Back") }
        SettingsSwitchRow(
            title = "Upload on any network",
            subtitle = "Otherwise uploads wait for Wi-Fi",
            checked = settings.allowMeteredNetwork,
            onCheckedChange = viewModel::setAllowMeteredNetwork,
        )
        SettingsSwitchRow(
            title = "Only upload while charging",
            subtitle = null,
            checked = settings.requiresCharging,
            onCheckedChange = viewModel::setRequiresCharging,
        )
        HorizontalDivider()
        // Weighted, not fillMaxSize -- FoldersScreen's own FolderList wraps a
        // LazyColumn, which needs a bounded height from its parent (this Column
        // isn't one on its own) or Compose throws at layout time.
        FoldersScreen(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
