package fr.enry.archivist.testutil

import fr.enry.archivist.data.remote.AuthChallengeResponse
import fr.enry.archivist.data.remote.CognitoAuthApi
import fr.enry.archivist.data.remote.CompleteWebAuthnRegistrationRequest
import fr.enry.archivist.data.remote.InitiateAuthRequest
import fr.enry.archivist.data.remote.RespondToAuthChallengeRequest
import fr.enry.archivist.data.remote.RevokeTokenRequest
import fr.enry.archivist.data.remote.StartWebAuthnRegistrationRequest
import fr.enry.archivist.data.remote.StartWebAuthnRegistrationResponse
import kotlinx.serialization.json.JsonObject

/** Test double for [CognitoAuthApi]: each action has its own configurable canned
 * response/error, and every call is recorded for assertions. */
class FakeCognitoAuthApi : CognitoAuthApi {
    var initiateAuthResponse: AuthChallengeResponse? = null
    var initiateAuthError: Throwable? = null
    var lastInitiateAuthRequest: InitiateAuthRequest? = null
    var initiateAuthCallCount: Int = 0
        private set

    var respondToAuthChallengeResponse: AuthChallengeResponse? = null
    var respondToAuthChallengeError: Throwable? = null
    var lastRespondToAuthChallengeRequest: RespondToAuthChallengeRequest? = null

    var startWebAuthnRegistrationResponse: StartWebAuthnRegistrationResponse? = null
    var startWebAuthnRegistrationError: Throwable? = null
    var lastStartWebAuthnRegistrationRequest: StartWebAuthnRegistrationRequest? = null

    var completeWebAuthnRegistrationError: Throwable? = null
    var lastCompleteWebAuthnRegistrationRequest: CompleteWebAuthnRegistrationRequest? = null

    var revokeTokenError: Throwable? = null
    var lastRevokeTokenRequest: RevokeTokenRequest? = null

    override suspend fun initiateAuth(
        url: String,
        request: InitiateAuthRequest,
    ): AuthChallengeResponse {
        lastInitiateAuthRequest = request
        initiateAuthCallCount++
        initiateAuthError?.let { throw it }
        return initiateAuthResponse ?: error("FakeCognitoAuthApi.initiateAuth not configured")
    }

    override suspend fun respondToAuthChallenge(
        url: String,
        request: RespondToAuthChallengeRequest,
    ): AuthChallengeResponse {
        lastRespondToAuthChallengeRequest = request
        respondToAuthChallengeError?.let { throw it }
        return respondToAuthChallengeResponse ?: error("FakeCognitoAuthApi.respondToAuthChallenge not configured")
    }

    override suspend fun startWebAuthnRegistration(
        url: String,
        request: StartWebAuthnRegistrationRequest,
    ): StartWebAuthnRegistrationResponse {
        lastStartWebAuthnRegistrationRequest = request
        startWebAuthnRegistrationError?.let { throw it }
        return startWebAuthnRegistrationResponse ?: error("FakeCognitoAuthApi.startWebAuthnRegistration not configured")
    }

    override suspend fun completeWebAuthnRegistration(
        url: String,
        request: CompleteWebAuthnRegistrationRequest,
    ): JsonObject {
        lastCompleteWebAuthnRegistrationRequest = request
        completeWebAuthnRegistrationError?.let { throw it }
        return JsonObject(emptyMap())
    }

    override suspend fun revokeToken(
        url: String,
        request: RevokeTokenRequest,
    ): JsonObject {
        lastRevokeTokenRequest = request
        revokeTokenError?.let { throw it }
        return JsonObject(emptyMap())
    }
}
