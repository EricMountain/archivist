package fr.enry.archivist.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import fr.enry.archivist.data.repo.DeleteMode

/**
 * Plan step 2.13's three-way prompt — see `docs/design/android.md`'s "Deleting on the
 * phone". "Remove from archive" is the confirm action (the default choice per that
 * table), not "Remove from both": the phone is one of the independent copies the whole
 * design leans on, so the more destructive option never gets the visually-primary
 * button. Material3's [AlertDialog] only has two named button slots, so the third
 * ("Cancel") shares [dismissButton] with "Remove from both" rather than getting its own.
 */
@Composable
fun DeleteDialog(
    onDismiss: () -> Unit,
    onConfirm: (DeleteMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove this photo?") },
        text = {
            Text(
                "\"Remove from archive\" keeps the file on this phone. \"Remove from both\" " +
                    "also deletes it from this phone's gallery.",
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(DeleteMode.ARCHIVE_ONLY) }) { Text("Remove from archive") }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { onConfirm(DeleteMode.BOTH) }) { Text("Remove from both") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
