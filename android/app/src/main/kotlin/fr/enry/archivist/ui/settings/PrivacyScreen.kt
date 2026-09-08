package fr.enry.archivist.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Plan step 2.18's Settings > Privacy section. The explanatory copy below the switch
 * is load-bearing, not decoration — see design.md's "Settings copy" and android.md's
 * "Stripping location": read the other way, "strip location" sounds like it edits the
 * photo sitting in the phone's own gallery, which this never does.
 */
@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PrivacyViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text(
            "Privacy",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        when (val s = state) {
            PrivacyUiState.Loading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            is PrivacyUiState.Loaded -> {
                s.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                PrivacySwitchRow(
                    checked = s.stripLocationOnUpload,
                    enabled = !s.isSaving,
                    onCheckedChange = viewModel::setStripLocationOnUpload,
                )
                Text(
                    "The original file on this device is never touched — only the uploaded copy has its " +
                        "location removed. Covers photos and MP4/MOV video; other video formats and RAW " +
                        "files aren't supported yet and upload with their location intact. Applies to " +
                        "every device backing up to this library, not just this one.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaLocationAlternative()
            }
        }
    }
}

/**
 * Plan step 2.19. The switch above is a server-side, every-device policy; denying this
 * device's own "Photos and videos: location" permission is a separate, per-device
 * choice that Android enforces below the app entirely (see `AndroidMediaStoreSource
 * .openInputStream`'s doc) — mentioned here because it's the more foolproof option for
 * someone who only cares about their own phone, but it can't substitute for the switch
 * above: it says nothing about other devices backing up to the same library, and it
 * also means this device can never resolve a photo's timestamp from its GPS fix.
 */
@Composable
private fun MediaLocationAlternative(context: Context = LocalContext.current) {
    val granted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "There's also a stronger, per-device option: deny this device's \"Photos and videos: " +
                "location\" permission entirely (in system Settings, not the switch above), and " +
                "Android hands this app — and every other app — copies with location already " +
                "removed, for every photo and video it reads, regardless of the switch above. " +
                "Unlike that switch, this is per-device rather than shared across your library, " +
                "and it also means this device can't use a photo's GPS fix to help place it in " +
                "the timeline.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (granted) "This device currently allows it." else "This device currently denies it.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        TextButton(onClick = { openAppPermissionSettings(context) }) { Text("Open app permission settings") }
    }
}

private fun openAppPermissionSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}

@Composable
private fun PrivacySwitchRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Strip location from uploads", style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
