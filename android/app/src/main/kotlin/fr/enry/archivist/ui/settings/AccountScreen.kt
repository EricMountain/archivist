package fr.enry.archivist.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val DELETE_CONFIRMATION_WORD = "DELETE"

/**
 * Plan step 2.14's Settings > Account section. Deleting an account is irreversible
 * ("no reset link and no recovery on our side" — deployment.md) and erases the whole
 * library server-side, so it's gated behind typing a literal word rather than a plain
 * two-button dialog — the same bar this app already sets for photo deletion isn't
 * enough for something with no undo at all.
 */
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    onSessionEnded: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmingSignOut by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.sessionEnded) {
        if (state.sessionEnded) {
            onSessionEnded()
            // Reset the one-shot flag where it's consumed -- this ViewModel is
            // Activity-scoped (no nav library) and gets reused on a later visit,
            // including after signing back in. See AccountViewModel's own doc.
            viewModel.acknowledgeSessionEnded()
        }
    }

    Column(modifier.padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text("Account", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 16.dp))
        }
        OutlinedButton(onClick = { confirmingSignOut = true }, enabled = !state.isWorking) { Text("Sign out") }
        Button(
            onClick = { confirmingDelete = true },
            enabled = !state.isWorking,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.padding(top = 24.dp),
        ) { Text("Delete account") }
    }

    if (confirmingSignOut) {
        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("This device stays enrolled — signing back in unlocks your library the same way.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingSignOut = false
                    viewModel.signOut()
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmingSignOut = false }) { Text("Cancel") } },
        )
    }

    if (confirmingDelete) {
        DeleteAccountDialog(
            isWorking = state.isWorking,
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                confirmingDelete = false
                viewModel.deleteAccount()
            },
        )
    }
}

@Composable
private fun DeleteAccountDialog(
    isWorking: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete your account?") },
        text = {
            Column {
                Text(
                    "This permanently erases your entire library — every photo, both here and in AWS. " +
                        "There is no undo and no support path to get it back.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Type $DELETE_CONFIRMATION_WORD to confirm") },
                    singleLine = true,
                    enabled = !isWorking,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isWorking && typed == DELETE_CONFIRMATION_WORD,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete forever") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isWorking) { Text("Cancel") } },
    )
}
