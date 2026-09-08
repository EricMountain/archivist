package fr.enry.archivist.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private enum class OnboardingStep { MEDIA_LIBRARY, MEDIA_LOCATION, NOTIFICATIONS }

/** The permissions photo/video backup actually needs, split by API level the same way
 * the manifest is — `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` don't exist before API 33. */
private fun mediaLibraryPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

private fun granted(
    context: Context,
    permission: String,
): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Plan step 2.19. Gates [content] behind the runtime permissions backup needs,
 * recomputed once per entry into this composable rather than persisted anywhere — a
 * denial here (explicit, or via the system dialog) must be free to ask again on a
 * later, genuinely fresh entry rather than being remembered as "already asked" forever.
 * That matters most for [ConnectUiState.ReviewerPreview]: a Play reviewer sees these
 * same screens (see `MainActivity`'s wiring), and their answers must not count against
 * whatever a real user sees after actually registering later on the same device.
 *
 * Reached both from the real sign-in flow, with [includeNotifications] true, and from
 * reviewer preview mode with it false — preview mode never uploads anything, so there
 * is nothing for a notification to report.
 */
@Composable
fun PermissionOnboardingScreen(
    includeNotifications: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var remaining by remember { mutableStateOf(pendingSteps(context, includeNotifications)) }

    when (remaining.firstOrNull()) {
        OnboardingStep.MEDIA_LIBRARY ->
            MediaLibraryStep(modifier = modifier, onDone = { remaining = remaining.drop(1) })

        OnboardingStep.MEDIA_LOCATION ->
            MediaLocationStep(modifier = modifier, onDone = { remaining = remaining.drop(1) })

        OnboardingStep.NOTIFICATIONS ->
            NotificationsStep(modifier = modifier, onDone = { remaining = remaining.drop(1) })

        null -> content()
    }
}

private fun pendingSteps(
    context: Context,
    includeNotifications: Boolean,
): List<OnboardingStep> =
    buildList {
        if (!mediaLibraryPermissions().all { granted(context, it) }) add(OnboardingStep.MEDIA_LIBRARY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)
        ) {
            add(OnboardingStep.MEDIA_LOCATION)
        }
        if (includeNotifications &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(context, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            add(OnboardingStep.NOTIFICATIONS)
        }
    }

@Composable
private fun MediaLibraryStep(
    modifier: Modifier = Modifier,
    onDone: () -> Unit,
) {
    val permissions = remember { mediaLibraryPermissions() }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onDone() }
    RationaleScreen(
        title = "Photos & videos",
        body =
            "Backing up the folders you choose next means reading your photos and videos " +
                "without asking about each one — that's what this permission is for.",
        confirmLabel = "Continue",
        onConfirm = { launcher.launch(permissions) },
        modifier = modifier,
    )
}

@Composable
private fun MediaLocationStep(
    modifier: Modifier = Modifier,
    onDone: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone() }
    RationaleScreen(
        title = "Photo & video location",
        body =
            "Allowing this lets Archivist read exactly where a photo or video was taken, " +
                "which helps place it in the timeline as well as on a map. Choose \"Don't " +
                "allow\" instead and Android will hand every app on this device — this " +
                "one included — copies with location already removed, for every photo " +
                "and video, with nothing further for Archivist to do. That's independent " +
                "of \"Strip location from uploads\" in Settings > Privacy, which controls " +
                "what leaves this phone once a photo already carries a location, not " +
                "what this device can read in the first place.",
        confirmLabel = "Allow access",
        onConfirm = { launcher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) },
        dismissLabel = "Don't allow",
        onDismiss = onDone,
        modifier = modifier,
    )
}

@Composable
private fun NotificationsStep(
    modifier: Modifier = Modifier,
    onDone: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone() }
    RationaleScreen(
        title = "Notifications",
        body =
            "Archivist shows a notification while it's uploading in the background, and " +
                "can let you know if it's ever waiting on you to unlock your key.",
        confirmLabel = "Continue",
        onConfirm = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        dismissLabel = "Not now",
        onDismiss = onDone,
        modifier = modifier,
    )
}

@Composable
private fun RationaleScreen(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = onConfirm, modifier = Modifier.align(Alignment.End)) { Text(confirmLabel) }
        if (dismissLabel != null && onDismiss != null) {
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(dismissLabel) }
        }
    }
}
