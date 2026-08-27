package fr.enry.archivist.ui.onboarding

import android.app.Activity
import android.app.KeyguardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Plan step 2.5. Shown after [SignInScreen] and before anything that needs to decrypt.
 * `Unlocked` is a terminal state the parent reacts to via [onUnlocked] — this
 * composable never renders anything for it itself, matching [SignInScreen]'s handling
 * of `SignedIn`.
 */
@Composable
fun EnrolmentScreen(
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EnrolmentViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is EnrolmentUiState.Unlocked) onUnlocked()
    }

    when (val s = state) {
        EnrolmentUiState.Checking, EnrolmentUiState.Unlocked -> Centered(modifier) { CircularProgressIndicator() }

        is EnrolmentUiState.EnterRecoveryCode ->
            RecoveryCodeForm(s, onSubmit = viewModel::submitRecoveryCode, modifier = modifier)

        is EnrolmentUiState.ShowRecoveryCode ->
            ShowRecoveryCodeView(s, onSaved = viewModel::proceedToConfirmation, modifier = modifier)

        is EnrolmentUiState.ConfirmRecoveryCode ->
            ConfirmRecoveryCodeForm(
                s,
                onSubmit = viewModel::confirmTypedCode,
                onShowCodeAgain = viewModel::showCodeAgain,
                modifier = modifier,
            )

        is EnrolmentUiState.DeviceKeystoreUnsupported ->
            MessageScreen(
                title = "This device can't be enrolled",
                body =
                    "Storing a device key securely needs Android 12 or later. " +
                        "This device is running an older version (API ${s.sdkInt}).",
                modifier = modifier,
            )

        EnrolmentUiState.NeedsDeviceUnlock -> {
            val context = LocalContext.current
            val launcher =
                rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    if (result.resultCode == Activity.RESULT_OK) viewModel.checkStep()
                }
            MessageScreen(
                title = "Unlock your device",
                body = "Confirm your PIN, pattern or biometric to continue — your device key needs a recent unlock to use.",
                retryLabel = "Unlock",
                onRetry = {
                    val keyguardManager = context.getSystemService(KeyguardManager::class.java)
                    // Deprecated in favor of androidx.biometric's BiometricPrompt, but
                    // that pulls in a whole new dependency for something this simple:
                    // no CryptoObject is needed here (unlike the actual device-key
                    // unwrap this unlocks) -- this call just needs *a* successful
                    // authentication to happen, to satisfy DeviceKeystore's time-based
                    // auth window. Still functions correctly; not worth the dependency.
                    @Suppress("DEPRECATION")
                    val intent =
                        keyguardManager?.createConfirmDeviceCredentialIntent(
                            "Unlock Archivist",
                            "Confirm your device credential to continue",
                        )
                    if (intent != null) launcher.launch(intent) else viewModel.checkStep()
                },
                modifier = modifier,
            )
        }

        EnrolmentUiState.NetworkError ->
            MessageScreen(
                title = "Couldn't reach the server",
                body = "Check your connection and try again.",
                onRetry = viewModel::checkStep,
                modifier = modifier,
            )

        is EnrolmentUiState.Failed -> MessageScreen(title = "Something went wrong", body = s.message, modifier = modifier)
    }
}

@Composable
private fun RecoveryCodeForm(
    state: EnrolmentUiState.EnterRecoveryCode,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var code by remember { mutableStateOf("") }
    Column(modifier = modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Enter your recovery code", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (state.reenrolling) {
                "Your device's screen lock changed, which invalidated its key. " +
                    "Enter your recovery code to set this device up again."
            } else {
                "This library already has a recovery code from another device. Enter it to unlock here."
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Recovery code") },
            singleLine = true,
            enabled = !state.isSubmitting,
            isError = state.error != null,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.error != null) ErrorText(state.error)
        Button(
            onClick = { onSubmit(code) },
            enabled = !state.isSubmitting && code.isNotBlank(),
            modifier = Modifier.padding(top = 16.dp).align(Alignment.End),
        ) { Text("Continue") }
    }
}

@Composable
private fun ShowRecoveryCodeView(
    state: EnrolmentUiState.ShowRecoveryCode,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Save your recovery code", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This is the only way back into your library if you lose every enrolled device. " +
                "There is no other way to recover it — write it down somewhere durable, now.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Text(
            state.formattedCode,
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 24.dp),
        )
        Button(onClick = onSaved, modifier = Modifier.align(Alignment.End)) { Text("I've saved it") }
    }
}

@Composable
private fun ConfirmRecoveryCodeForm(
    state: EnrolmentUiState.ConfirmRecoveryCode,
    onSubmit: (String) -> Unit,
    onShowCodeAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var typed by remember { mutableStateOf("") }
    Column(modifier = modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Type your recovery code back", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Confirm you've saved it correctly — there's no support path to recover it later.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("Recovery code") },
            singleLine = true,
            enabled = !state.isSubmitting,
            isError = state.error != null,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.error != null) ErrorText(state.error)
        TextButton(onClick = onShowCodeAgain, enabled = !state.isSubmitting, modifier = Modifier.padding(top = 8.dp)) {
            Text("Show the code again")
        }
        Button(
            onClick = { onSubmit(typed) },
            enabled = !state.isSubmitting && typed.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp).align(Alignment.End),
        ) { Text("Confirm") }
    }
}

@Composable
private fun MessageScreen(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    retryLabel: String = "Try again",
    onRetry: (() -> Unit)? = null,
) {
    Centered(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            if (onRetry != null) Button(onClick = onRetry) { Text(retryLabel) }
        }
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
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
