package fr.enry.archivist.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.enry.archivist.data.repo.DeleteMode

/**
 * Plan step 2.13's three-way prompt — see `docs/design/android.md`'s "Deleting on the
 * phone". "Remove from archive" is the default/primary choice per that table (listed
 * first), not "Remove from both": the phone is one of the independent copies the whole
 * design leans on, so the more destructive option doesn't get top billing. Material3's
 * [AlertDialog] only has two named button slots (confirmButton/dismissButton), and it
 * lays them out in a `FlowRow` — with three buttons that wraps into a lopsided two-line
 * layout instead of a clean stack. So all three buttons live together in [text] as a
 * single end-aligned [Column], and [confirmButton] is left empty.
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
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "\"Remove from archive\" keeps the file on this phone. \"Remove from both\" " +
                        "also deletes it from this phone's gallery.",
                )
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = { onConfirm(DeleteMode.ARCHIVE_ONLY) }) { Text("Remove from archive") }
                TextButton(onClick = { onConfirm(DeleteMode.BOTH) }) { Text("Remove from both") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        confirmButton = {},
    )
}
