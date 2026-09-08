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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.enry.archivist.R

/**
 * Plan step 2.14's Settings > Sync section — network policy and charging requirement on
 * top, [FoldersScreen] (plan step 2.7, previously built but unreachable — see its own
 * doc) underneath. One screen, since the plan groups them under a single "Sync" bullet.
 *
 * **"Pause uploads" (2026-09-08, per the user's explicit request)** is deliberately the
 * first row, ahead of the network/charging constraints it otherwise reads a lot like —
 * unlike those, it's not a device condition uploads wait out on their own, it's a
 * manual stop [SyncViewModel.setUploadsPaused] actively cancels and resumes.
 */
@Composable
fun SyncScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val queueDepth by viewModel.queueDepth.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text(
            text =
                if (queueDepth == 0) {
                    "All photos uploaded"
                } else {
                    pluralStringResource(R.plurals.upload_queue_depth, queueDepth, queueDepth)
                },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        HorizontalDivider()
        SettingsSwitchRow(
            title = "Pause uploads",
            subtitle = "Stops queued and in-progress uploads until turned back off",
            checked = settings.uploadsPaused,
            onCheckedChange = viewModel::setUploadsPaused,
        )
        HorizontalDivider()
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
        SettingsSwitchRow(
            title = "Notify when uploads need unlocking",
            subtitle = "Low-priority notification if a queued upload is waiting on the app being opened",
            checked = settings.notifyWhenUploadNeedsUnlock,
            onCheckedChange = viewModel::setNotifyWhenUploadNeedsUnlock,
        )
        SettingsSwitchRow(
            title = "Run uploads as a foreground service",
            subtitle = "More reliable for large files — Android is much less likely to defer or kill " +
                "a foreground job under memory pressure. Requires a persistent notification while " +
                "an upload is running; that's an OS requirement, not optional.",
            checked = settings.uploadAsForegroundService,
            onCheckedChange = viewModel::setUploadAsForegroundService,
        )
        SettingsSwitchRow(
            title = "Show upload progress notification",
            subtitle =
                if (settings.uploadAsForegroundService) {
                    "Always shown while running as a foreground service, above"
                } else {
                    "Optional while running in the background — off just means one less notification"
                },
            checked = settings.uploadAsForegroundService || settings.showUploadProgressNotification,
            enabled = !settings.uploadAsForegroundService,
            onCheckedChange = viewModel::setShowUploadProgressNotification,
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
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // weight(1f) is load-bearing, not decoration -- without it this Column measures
        // at its unconstrained natural width before the Switch is placed, so a subtitle
        // long enough to wrap onto a second line renders underneath the Switch instead
        // of stopping short of it. Same root cause as the reviewer preview banner's own
        // fix (see its doc), mirrored onto the other child this time.
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
