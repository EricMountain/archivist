package fr.enry.archivist.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.enry.archivist.data.local.db.DeviceEntity

/**
 * Plan step 2.14's Settings > Devices section: "list, edit timezone defaults, remove".
 * Every camera this library has ever ingested a photo from, one row each — tapping a
 * row opens the edit dialog; the trailing "Remove" text is a second tap, not a swipe,
 * since removal here is cosmetic (see [DeviceEntity]/`DELETE /devices/{deviceKey}`'s
 * own doc: the device just re-registers, offset-less, next time it's seen).
 */
@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<DeviceEntity?>(null) }

    Column(modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text(
            "Devices",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            "A default timezone for cameras whose photos don't otherwise carry one — set once here, " +
                "it applies to every future photo from that camera.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        when (val s = state) {
            DevicesUiState.Loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

            is DevicesUiState.Loaded -> {
                s.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                }
                if (s.devices.isEmpty()) {
                    Text(
                        "No devices seen yet — they appear here once a photo from them is backed up.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn {
                        items(s.devices, key = { it.deviceKey }) { device ->
                            DeviceRow(device, onClick = { editing = device })
                        }
                    }
                }
            }
        }
    }

    editing?.let { device ->
        EditDeviceDialog(
            device = device,
            onDismiss = { editing = null },
            onSave = { label, tzOffsetMin ->
                viewModel.update(device.deviceKey, label, tzOffsetMin)
                editing = null
            },
            onRemove = {
                viewModel.remove(device.deviceKey)
                editing = null
            },
        )
    }
}

@Composable
private fun DeviceRow(
    device: DeviceEntity,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(device.label, style = MaterialTheme.typography.bodyLarge)
            Text(if (device.photoCount == 1) "1 photo" else "${device.photoCount} photos", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            device.tzOffsetMin?.let { "Default: UTC${formatOffset(it)}" } ?: "No default set",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun formatOffset(minutes: Int): String {
    val sign = if (minutes < 0) "-" else "+"
    val abs = kotlin.math.abs(minutes)
    return "$sign${abs / 60}:${(abs % 60).toString().padStart(2, '0')}"
}

@Composable
private fun EditDeviceDialog(
    device: DeviceEntity,
    onDismiss: () -> Unit,
    onSave: (label: String, tzOffsetMin: Int?) -> Unit,
    onRemove: () -> Unit,
) {
    var label by remember { mutableStateOf(device.label) }
    var offsetText by remember { mutableStateOf(device.tzOffsetMin?.toString() ?: "") }
    var confirmingRemove by remember { mutableStateOf(false) }

    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text("Remove this device?") },
            text = { Text("It'll reappear, with no default, the next time a photo from it is backed up.") },
            confirmButton = { TextButton(onClick = onRemove) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmingRemove = false }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(device.deviceKey) },
        text = {
            Column {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    value = offsetText,
                    onValueChange = { offsetText = it },
                    label = { Text("UTC offset, in minutes (e.g. 540 for UTC+9)") },
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = { offsetText = "" }, modifier = Modifier.padding(top = 4.dp)) { Text("Clear default") }
                TextButton(onClick = { confirmingRemove = true }, modifier = Modifier.padding(top = 4.dp)) { Text("Remove device") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(label, offsetText.trim().toIntOrNull()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
