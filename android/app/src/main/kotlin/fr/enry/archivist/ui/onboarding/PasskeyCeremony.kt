package fr.enry.archivist.ui.onboarding

import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import javax.inject.Inject

/**
 * The one part of 2.4 that cannot be unit tested here: Credential Manager's
 * `createCredential`/`getCredential` need a real Activity, a real passkey provider,
 * and — for `createCredential` specifically — the relying party domain
 * (`var.domain_name`, per `terraform/cognito.tf`) to serve a Digital Asset Links file
 * associating it with this app's package and signing certificate. **That file does
 * not exist yet** — see the open question in `design.md` this discovery produced.
 * Until it does, `register()` is expected to fail on a real device even with
 * everything else here correct. Neither method has been exercised outside reading
 * the API surface, for that reason and for lack of a device in this environment.
 */
class PasskeyCeremony
    @Inject
    constructor() {
        suspend fun register(
            context: Context,
            creationOptionsJson: String,
        ): Result<String> =
            runCatching {
                val credentialManager = CredentialManager.create(context)
                val request = CreatePublicKeyCredentialRequest(creationOptionsJson)
                val response = credentialManager.createCredential(context, request) as CreatePublicKeyCredentialResponse
                response.registrationResponseJson
            }

        suspend fun authenticate(
            context: Context,
            requestOptionsJson: String,
        ): Result<String> =
            runCatching {
                val credentialManager = CredentialManager.create(context)
                val option = GetPublicKeyCredentialOption(requestOptionsJson)
                val response = credentialManager.getCredential(context, GetCredentialRequest(listOf(option)))
                val credential = response.credential as PublicKeyCredential
                credential.authenticationResponseJson
            }
    }
