package fr.enry.archivist.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Owns the passkey ceremony's Activity dependency (via [LocalContext]) so
 * [SignInViewModel] itself stays plain-JVM-testable. `AwaitingPasskeyAssertion` and
 * `AwaitingPasskeyRegistration` both trigger [PasskeyCeremony] as a side effect the
 * moment they're entered — see the class doc there for why this can't be verified in
 * this environment (no device, and the relying-party domain has no Digital Asset
 * Links file yet).
 */
@Composable
fun SignInScreen(
    onSignedIn: () -> Unit,
    onChangeServer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SignInViewModel = hiltViewModel(),
    passkeyCeremony: PasskeyCeremony = remember { PasskeyCeremony() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state) {
        when (val s = state) {
            is SignInUiState.AwaitingPasskeyAssertion ->
                viewModel.onPasskeyAssertionResult(passkeyCeremony.authenticate(context, s.requestOptionsJson))

            is SignInUiState.AwaitingPasskeyRegistration ->
                viewModel.onPasskeyRegistrationResult(passkeyCeremony.register(context, s.creationOptionsJson))

            SignInUiState.SignedIn -> onSignedIn()

            else -> Unit
        }
    }

    when (val s = state) {
        SignInUiState.CheckingExistingSession, SignInUiState.SignedIn ->
            Centered(modifier) { CircularProgressIndicator() }

        is SignInUiState.EnterUsername ->
            UsernameForm(
                s,
                onContinue = viewModel::continueWithUsername,
                onChangeServer = onChangeServer,
                modifier = modifier,
            )

        is SignInUiState.EnterPassword ->
            PasswordForm(s, onSubmit = viewModel::signInWithPassword, modifier = modifier)

        is SignInUiState.SetNewPassword ->
            NewPasswordForm(s, onSubmit = viewModel::setNewPassword, modifier = modifier)

        is SignInUiState.AwaitingPasskeyAssertion ->
            Centered(modifier) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Waiting for your passkey…", modifier = Modifier.padding(top = 16.dp))
                }
            }

        is SignInUiState.AwaitingPasskeyRegistration ->
            Centered(modifier) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        if (s.isOptional) "Setting up a passkey for next time…" else "Setting up your passkey…",
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    if (s.isOptional) {
                        TextButton(onClick = viewModel::skipPasskeyRegistration) {
                            Text("Skip")
                        }
                    }
                }
            }
    }
}

@Composable
private fun UsernameForm(
    state: SignInUiState.EnterUsername,
    onContinue: (String) -> Unit,
    onChangeServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var username by remember { mutableStateOf("") }
    Column(modifier = modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Sign in", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Email") },
            singleLine = true,
            enabled = !state.isSubmitting,
            isError = state.error != null,
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        if (state.error != null) ErrorText(state.error)
        TextButton(
            onClick = onChangeServer,
            enabled = !state.isSubmitting,
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Change server") }
        Button(
            onClick = { onContinue(username) },
            enabled = !state.isSubmitting && username.isNotBlank(),
            modifier = Modifier.padding(top = 16.dp).align(Alignment.End),
        ) { Text("Continue") }
    }
}

@Composable
private fun PasswordForm(
    state: SignInUiState.EnterPassword,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by remember { mutableStateOf("") }
    Column(modifier = modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Sign in with your password", style = MaterialTheme.typography.headlineSmall)
        Text(
            "No passkey found for ${state.username} yet. Use the password you were given.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !state.isSubmitting,
            isError = state.error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.error != null) ErrorText(state.error)
        Button(
            onClick = { onSubmit(password) },
            enabled = !state.isSubmitting && password.isNotBlank(),
            modifier = Modifier.padding(top = 16.dp).align(Alignment.End),
        ) { Text("Sign in") }
    }
}

@Composable
private fun NewPasswordForm(
    state: SignInUiState.SetNewPassword,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newPassword by remember { mutableStateOf("") }
    Column(modifier = modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Choose a password", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This is your first sign-in — set a permanent password. You'll be offered a passkey next.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        OutlinedTextField(
            value = newPassword,
            onValueChange = { newPassword = it },
            label = { Text("New password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !state.isSubmitting,
            isError = state.error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.error != null) ErrorText(state.error)
        Button(
            onClick = { onSubmit(newPassword) },
            enabled = !state.isSubmitting && newPassword.isNotBlank(),
            modifier = Modifier.padding(top = 16.dp).align(Alignment.End),
        ) { Text("Continue") }
    }
}

@Composable
private fun ErrorText(error: SignInError) {
    Text(
        text =
            when (error) {
                SignInError.InvalidCredentials -> "That email or password isn't right."
                SignInError.NetworkError -> "Couldn't reach the server. Check your connection."
                is SignInError.Other -> error.detail ?: "Something went wrong."
            },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Centered(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
