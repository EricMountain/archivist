package fr.enry.archivist.data.remote

import java.io.IOException
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import retrofit2.HttpException

/** Outcome of any Cognito call that can end in a further challenge, not just success
 * or failure — mirrors [DiscoveryResult]'s "distinguish, don't collapse" shape. */
sealed interface CognitoAuthResult {
    data class SignedIn(val result: AuthenticationResult) : CognitoAuthResult

    data class NewPasswordRequired(val session: String) : CognitoAuthResult

    /** [requestOptionsJson] is the raw `CREDENTIAL_REQUEST_OPTIONS` JSON string,
     * ready to hand to Credential Manager's `GetPublicKeyCredentialOption`. */
    data class PasskeyChallenge(val session: String, val requestOptionsJson: String) : CognitoAuthResult

    /** Confirmed live: requesting `PREFERRED_CHALLENGE=WEB_AUTHN` for an account with
     * no registered passkey doesn't error — it returns `ChallengeName: SELECT_CHALLENGE`
     * with only fallback factors in `AvailableChallenges`. This is that case. */
    data object NoPasskeyAvailable : CognitoAuthResult

    /** `NotAuthorizedException` — wrong password, or (indistinguishably, since
     * `prevent_user_existence_errors = ENABLED`) no such user. Cognito deliberately
     * collapses these to stop account enumeration; don't try to tell them apart. */
    data object InvalidCredentials : CognitoAuthResult

    data object NetworkError : CognitoAuthResult

    data class UnexpectedError(val type: String?, val message: String?) : CognitoAuthResult
}

sealed interface PasskeyRegistrationStart {
    data class Options(val creationOptionsJson: String) : PasskeyRegistrationStart

    data object NetworkError : PasskeyRegistrationStart

    data class Failed(val type: String?, val message: String?) : PasskeyRegistrationStart
}

sealed interface PasskeyRegistrationComplete {
    data object Success : PasskeyRegistrationComplete

    data object NetworkError : PasskeyRegistrationComplete

    data class Failed(val type: String?, val message: String?) : PasskeyRegistrationComplete
}

class CognitoAuthClient
    @Inject
    constructor(
        private val api: CognitoAuthApi,
        private val json: Json,
    ) {
        suspend fun signInWithPassword(
            region: String,
            clientId: String,
            username: String,
            password: String,
        ): CognitoAuthResult =
            challengeCall {
                api.initiateAuth(
                    cognitoIdpUrl(region),
                    InitiateAuthRequest(
                        authFlow = "USER_AUTH",
                        clientId = clientId,
                        authParameters =
                            mapOf(
                                "USERNAME" to username,
                                "PASSWORD" to password,
                                "PREFERRED_CHALLENGE" to "PASSWORD",
                            ),
                    ),
                )
            }

        suspend fun completeNewPassword(
            region: String,
            clientId: String,
            username: String,
            newPassword: String,
            session: String,
        ): CognitoAuthResult =
            challengeCall {
                api.respondToAuthChallenge(
                    cognitoIdpUrl(region),
                    RespondToAuthChallengeRequest(
                        clientId = clientId,
                        challengeName = "NEW_PASSWORD_REQUIRED",
                        session = session,
                        challengeResponses = mapOf("USERNAME" to username, "NEW_PASSWORD" to newPassword),
                    ),
                )
            }

        suspend fun startPasskeySignIn(
            region: String,
            clientId: String,
            username: String,
        ): CognitoAuthResult =
            challengeCall {
                api.initiateAuth(
                    cognitoIdpUrl(region),
                    InitiateAuthRequest(
                        authFlow = "USER_AUTH",
                        clientId = clientId,
                        authParameters = mapOf("USERNAME" to username, "PREFERRED_CHALLENGE" to "WEB_AUTHN"),
                    ),
                )
            }

        /** [assertionJson] is Credential Manager's `GetCredentialResponse` credential
         * JSON, passed straight through as Cognito's `CREDENTIAL` challenge response. */
        suspend fun completePasskeySignIn(
            region: String,
            clientId: String,
            username: String,
            session: String,
            assertionJson: String,
        ): CognitoAuthResult =
            challengeCall {
                api.respondToAuthChallenge(
                    cognitoIdpUrl(region),
                    RespondToAuthChallengeRequest(
                        clientId = clientId,
                        challengeName = "WEB_AUTHN",
                        session = session,
                        challengeResponses = mapOf("USERNAME" to username, "CREDENTIAL" to assertionJson),
                    ),
                )
            }

        suspend fun refresh(
            region: String,
            clientId: String,
            refreshToken: String,
        ): CognitoAuthResult =
            challengeCall {
                api.initiateAuth(
                    cognitoIdpUrl(region),
                    InitiateAuthRequest(
                        authFlow = "REFRESH_TOKEN_AUTH",
                        clientId = clientId,
                        authParameters = mapOf("REFRESH_TOKEN" to refreshToken),
                    ),
                )
            }

        /** Best-effort: sign-out proceeds locally regardless of whether this succeeds
         * (the refresh token is being discarded either way — this only prevents a
         * *copy* of it, e.g. in a backup, from being replayed later). */
        suspend fun revoke(
            region: String,
            clientId: String,
            refreshToken: String,
        ) {
            runCatching { api.revokeToken(cognitoIdpUrl(region), RevokeTokenRequest(clientId, refreshToken)) }
        }

        suspend fun startPasskeyRegistration(
            region: String,
            accessToken: String,
        ): PasskeyRegistrationStart {
            val response =
                try {
                    api.startWebAuthnRegistration(cognitoIdpUrl(region), StartWebAuthnRegistrationRequest(accessToken))
                } catch (e: IOException) {
                    return PasskeyRegistrationStart.NetworkError
                } catch (e: HttpException) {
                    val (type, message) = parseError(e)
                    return PasskeyRegistrationStart.Failed(type, message)
                }
            return PasskeyRegistrationStart.Options(response.credentialCreationOptions.toString())
        }

        /** [credentialJson] is Credential Manager's `CreatePublicKeyCredentialResponse`
         * registration JSON, passed straight through as Cognito's `Credential`. */
        suspend fun completePasskeyRegistration(
            region: String,
            accessToken: String,
            credentialJson: String,
        ): PasskeyRegistrationComplete {
            val credential =
                runCatching { json.parseToJsonElement(credentialJson) as? JsonObject }.getOrNull()
                    ?: return PasskeyRegistrationComplete.Failed(null, "malformed credential JSON")
            try {
                api.completeWebAuthnRegistration(
                    cognitoIdpUrl(region),
                    CompleteWebAuthnRegistrationRequest(accessToken, credential),
                )
            } catch (e: IOException) {
                return PasskeyRegistrationComplete.NetworkError
            } catch (e: HttpException) {
                val (type, message) = parseError(e)
                return PasskeyRegistrationComplete.Failed(type, message)
            }
            return PasskeyRegistrationComplete.Success
        }

        private suspend fun challengeCall(block: suspend () -> AuthChallengeResponse): CognitoAuthResult {
            val response =
                try {
                    block()
                } catch (e: IOException) {
                    return CognitoAuthResult.NetworkError
                } catch (e: HttpException) {
                    val (type, message) = parseError(e)
                    return if (type == "NotAuthorizedException") {
                        CognitoAuthResult.InvalidCredentials
                    } else {
                        CognitoAuthResult.UnexpectedError(type, message)
                    }
                }
            return mapChallengeResponse(response)
        }

        private fun mapChallengeResponse(response: AuthChallengeResponse): CognitoAuthResult {
            val result = response.authenticationResult
            if (result != null) return CognitoAuthResult.SignedIn(result)

            return when (response.challengeName) {
                "NEW_PASSWORD_REQUIRED" ->
                    response.session?.let { CognitoAuthResult.NewPasswordRequired(it) }
                        ?: CognitoAuthResult.UnexpectedError("NEW_PASSWORD_REQUIRED", "missing Session")

                "WEB_AUTHN" -> {
                    val session = response.session
                    val optionsJson = response.challengeParameters["CREDENTIAL_REQUEST_OPTIONS"]
                    if (session != null && optionsJson != null) {
                        CognitoAuthResult.PasskeyChallenge(session, optionsJson)
                    } else {
                        CognitoAuthResult.UnexpectedError("WEB_AUTHN", "missing Session or CREDENTIAL_REQUEST_OPTIONS")
                    }
                }

                "SELECT_CHALLENGE" -> CognitoAuthResult.NoPasskeyAvailable

                else -> CognitoAuthResult.UnexpectedError(response.challengeName, "unhandled challenge")
            }
        }

        private fun parseError(e: HttpException): Pair<String?, String?> {
            val body = e.response()?.errorBody()?.string()
            val parsed =
                body?.let {
                    runCatching { json.decodeFromString(CognitoErrorBody.serializer(), it) }.getOrNull()
                }
            return parsed?.type to parsed?.message
        }
    }
