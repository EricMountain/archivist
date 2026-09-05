package fr.enry.archivist.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp

/** Stateless: takes the current [ConnectUiState.NeedsConnection] and a callback, so it
 * doesn't need a ViewModel to preview or test. */
@Composable
fun ConnectScreen(
    state: ConnectUiState.NeedsConnection,
    onConnect: (String) -> Unit,
    onPreviewWithoutAccount: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var hostInput by remember(state.prefillHost) { mutableStateOf(state.prefillHost ?: "") }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Connect to your instance",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Archivist requires your own AWS account — this app has no server of its own.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        OutlinedTextField(
            value = hostInput,
            onValueChange = { hostInput = it },
            label = { Text("Server address") },
            placeholder = { Text("photos.example.com") },
            singleLine = true,
            enabled = !state.isConnecting,
            isError = state.error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.error != null) {
            Text(
                text = errorMessage(state.error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(
            onClick = { onConnect(hostInput) },
            enabled = !state.isConnecting && hostInput.isNotBlank(),
            modifier = Modifier.padding(top = 16.dp).align(Alignment.End),
        ) {
            if (state.isConnecting) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(16.dp))
            }
            Text("Connect")
        }
        // Plan step 2.17: the answer to Play Console's account-access requirement —
        // full functionality reachable with no account, rather than a dummy instance
        // maintained just for reviewers. Deliberately a plain text button below the
        // primary CTA: visible enough for a reviewer to find without instructions, but
        // subordinate to "connect to your own instance", which is still the point of
        // the app.
        TextButton(
            onClick = onPreviewWithoutAccount,
            enabled = !state.isConnecting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Preview without an account")
        }
    }
}

private fun errorMessage(error: ConnectError): String =
    when (error) {
        ConnectError.InvalidHost -> "Enter a server address without http:// — Archivist only connects over HTTPS."
        ConnectError.HostNotFound -> "Couldn't reach that address. Check for typos and that you're online."
        ConnectError.NotArchivist -> "That address answered, but doesn't look like an Archivist server."
        is ConnectError.ServerTooNew ->
            "This server speaks a newer format (v${error.serverVersion}) than this app supports " +
                "(v${error.supportedVersion}). Update Archivist to use it."
    }
