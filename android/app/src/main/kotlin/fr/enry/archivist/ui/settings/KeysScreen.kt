package fr.enry.archivist.ui.settings

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
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.enry.archivist.data.remote.KeyWrapDto

/**
 * Plan step 2.14's Settings > Keys section. Every `W#` wrapping — devices, passkeys,
 * the recovery code — listed with a remove action, plus "regenerate recovery code"
 * ([RecoveryRegenDialogs]), which reuses the show-then-confirm shape plan step 2.5's
 * first-enrolment screen already established.
 */
@Composable
fun KeysScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KeysViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val regenState by viewModel.regenState.collectAsStateWithLifecycle()
    var confirmingRemove by remember { mutableStateOf<KeyWrapDto?>(null) }

    Column(modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text("Keys", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        when (val s = state) {
            KeysUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

            is KeysUiState.Loaded -> {
                s.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(s.wraps, key = { it.wrapId }) { wrap ->
                        KeyRow(wrap, onRemove = { confirmingRemove = wrap })
                    }
                }
                TextButton(onClick = viewModel::beginRecoveryRegeneration, modifier = Modifier.padding(16.dp)) {
                    Text("Regenerate recovery code")
                }
            }
        }
    }

    confirmingRemove?.let { wrap ->
        AlertDialog(
            onDismissRequest = { confirmingRemove = null },
            title = { Text("Remove \"${wrap.label}\"?") },
            text = {
                Text(
                    if (wrap.kind == "recovery") {
                        "This is a recovery code — removing it only works if another one still exists."
                    } else {
                        "That device or browser will no longer be able to unlock this library."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeKey(wrap.wrapId)
                    confirmingRemove = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmingRemove = null }) { Text("Cancel") } },
        )
    }

    RecoveryRegenDialogs(regenState, viewModel)
}

@Composable
private fun KeyRow(
    wrap: KeyWrapDto,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(wrap.label, style = MaterialTheme.typography.bodyLarge)
            Text(wrap.kind, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

@Composable
private fun RecoveryRegenDialogs(
    state: RecoveryRegenState,
    viewModel: KeysViewModel,
) {
    when (state) {
        RecoveryRegenState.Hidden -> return

        is RecoveryRegenState.ShowingCode ->
            AlertDialog(
                onDismissRequest = viewModel::cancelRecoveryRegeneration,
                title = { Text("Your new recovery code") },
                text = {
                    Column {
                        Text(
                            "This replaces your old recovery code — write down the new one before continuing. " +
                                "There is no way to see it again after this.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            state.formattedCode,
                            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                },
                confirmButton = { Button(onClick = viewModel::proceedToConfirmation) { Text("I've saved it") } },
                dismissButton = { TextButton(onClick = viewModel::cancelRecoveryRegeneration) { Text("Cancel") } },
            )

        is RecoveryRegenState.Confirming -> {
            var typed by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = viewModel::cancelRecoveryRegeneration,
                title = { Text("Type your new code back") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            singleLine = true,
                            enabled = !state.isSubmitting,
                            isError = state.error != null,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        state.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                        }
                        TextButton(onClick = viewModel::showCodeAgain, enabled = !state.isSubmitting) { Text("Show the code again") }
                    }
                },
                confirmButton = {
                    Button(onClick = { viewModel.confirmTypedCode(typed) }, enabled = !state.isSubmitting && typed.isNotBlank()) {
                        Text("Confirm")
                    }
                },
                dismissButton = { TextButton(onClick = viewModel::cancelRecoveryRegeneration, enabled = !state.isSubmitting) { Text("Cancel") } },
            )
        }
    }
}
