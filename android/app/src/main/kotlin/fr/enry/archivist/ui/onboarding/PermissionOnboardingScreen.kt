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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private enum class OnboardingStep { MEDIA_LIBRARY, PARTIAL_MEDIA_ACCESS, MEDIA_LOCATION, NOTIFICATIONS }

private enum class MediaAccessState { FULL, PARTIAL, NONE }

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

/** `NONE`/`FULL` exist below API 34 too; `PARTIAL` (`READ_MEDIA_VISUAL_USER_SELECTED`)
 * is only ever grantable as an alternative outcome of the API 34+ system dialog — see
 * the manifest's own doc on that permission. */
private fun mediaAccessState(context: Context): MediaAccessState =
    when {
        mediaLibraryPermissions().all { granted(context, it) } -> MediaAccessState.FULL
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccessState.PARTIAL
        else -> MediaAccessState.NONE
    }

/**
 * Plan step 2.19. Gates [content] behind the runtime permissions backup needs,
 * recomputed once per entry into this composable rather than persisted anywhere — a
 * denial here (explicit, or via the system dialog) must be free to ask again on a
 * later, genuinely fresh entry rather than being remembered as "already asked" forever.
 * That matters most for [ConnectUiState.ReviewerPreview]: a Play reviewer sees these
 * same screens (see `MainActivity`'s wiring), and their answers must not count against
 * whatever a real user sees after actually registering later on the same device. Every
 * step shows in both flows, notifications included — preview mode never fires one, but
 * a reviewer should still see the real, complete set of prompts this app can show, not
 * just the subset one particular mode happens to use.
 */
@Composable
fun PermissionOnboardingScreen(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var remaining by remember { mutableStateOf(initialSteps(context)) }

    when (remaining.firstOrNull()) {
        OnboardingStep.MEDIA_LIBRARY ->
            MediaLibraryStep(
                modifier = modifier,
                onDone = {
                    val rest = remaining.drop(1)
                    remaining =
                        if (mediaAccessState(context) == MediaAccessState.PARTIAL) {
                            listOf(OnboardingStep.PARTIAL_MEDIA_ACCESS) + rest
                        } else {
                            rest
                        }
                },
            )

        OnboardingStep.PARTIAL_MEDIA_ACCESS ->
            PartialMediaAccessStep(modifier = modifier, onDone = { remaining = remaining.drop(1) })

        OnboardingStep.MEDIA_LOCATION ->
            MediaLocationStep(modifier = modifier, onDone = { remaining = remaining.drop(1) })

        OnboardingStep.NOTIFICATIONS ->
            NotificationsStep(modifier = modifier, onDone = { remaining = remaining.drop(1) })

        null -> content()
    }
}

private fun initialSteps(context: Context): List<OnboardingStep> =
    buildList {
        when (mediaAccessState(context)) {
            MediaAccessState.NONE -> add(OnboardingStep.MEDIA_LIBRARY)
            MediaAccessState.PARTIAL -> add(OnboardingStep.PARTIAL_MEDIA_ACCESS)
            MediaAccessState.FULL -> {}
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)
        ) {
            add(OnboardingStep.MEDIA_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
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
                "without asking about each one — that's what this permission is for. " +
                "Without it, backup and syncing your library won't work, but the rest of " +
                "the app still will.",
        confirmLabel = "Allow",
        onConfirm = { launcher.launch(permissions) },
        dismissLabel = "Not now",
        onDismiss = onDone,
        modifier = modifier,
    )
}

/**
 * API 34+ only — reached when the system's own three-way dialog was answered with
 * "Select photos and videos…" instead of "Allow all", either just now or in an earlier
 * session (`initialSteps` reaches this directly in that case, skipping the request
 * screen above — there's nothing left to ask for). There's no separate intent for
 * revising the selection: re-requesting the same permissions, with the
 * already-partially-granted `READ_MEDIA_VISUAL_USER_SELECTED` included in the array, is
 * what the platform documents for reopening the system's own reselection UI.
 */
@Composable
private fun PartialMediaAccessStep(
    modifier: Modifier = Modifier,
    onDone: () -> Unit,
) {
    val permissions = remember { mediaLibraryPermissions() + Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onDone() }
    RationaleScreen(
        title = "Only some photos selected",
        body =
            "Archivist can only see the photos and videos you selected — folders you back " +
                "up will only pick up files from among those. Choose \"Add more\" to reopen " +
                "the selection, or continue and add more later from Settings > Sync.",
        confirmLabel = "Add more",
        onConfirm = { launcher.launch(permissions) },
        dismissLabel = "Not now",
        onDismiss = onDone,
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
                "allow\" instead and Android will hand Archivist copies with location " +
                "already removed, for every photo and video it reads, with nothing " +
                "further for Archivist to do — this only affects what Archivist itself " +
                "can see; another app you've separately granted this to would still see " +
                "the original. It's also independent of \"Strip location from uploads\" " +
                "in Settings > Privacy, which controls what leaves this phone once a " +
                "photo already carries a location, not what this device can read in the " +
                "first place.",
        confirmLabel = "Allow",
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
                "can let you know if it's ever waiting on you to unlock your key. " +
                "Android only asks once for permission to show notifications at all — " +
                "you can turn either of these two off independently afterwards, in " +
                "Settings > Sync.",
        confirmLabel = "Allow",
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
