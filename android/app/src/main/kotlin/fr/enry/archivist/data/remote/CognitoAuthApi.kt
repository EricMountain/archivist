package fr.enry.archivist.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Cognito's user-facing API is plain unsigned HTTPS JSON-RPC — no SigV4, no AWS SDK
 * (see "Not using AWS Amplify" in `docs/design/android.md`). Every action is a `POST`
 * to the pool's regional endpoint with an `X-Amz-Target` header naming the action;
 * **the `application/x-amz-json-1.1` content type is load-bearing**, not cosmetic —
 * verified live against the real pool: `application/json` gets a 200 with
 * `UnknownOperationException` in the body, silently ignoring `X-Amz-Target` entirely.
 * That's why this uses its own [Retrofit][retrofit2.Retrofit]/converter in
 * [CognitoNetworkModule] rather than reusing [DiscoveryApi]'s.
 */
interface CognitoAuthApi {
    @Headers("X-Amz-Target: AWSCognitoIdentityProviderService.InitiateAuth")
    @POST
    suspend fun initiateAuth(
        @Url url: String,
        @Body request: InitiateAuthRequest,
    ): AuthChallengeResponse

    @Headers("X-Amz-Target: AWSCognitoIdentityProviderService.RespondToAuthChallenge")
    @POST
    suspend fun respondToAuthChallenge(
        @Url url: String,
        @Body request: RespondToAuthChallengeRequest,
    ): AuthChallengeResponse

    /** Requires a signed-in user's access token with the `aws.cognito.signin.user.admin`
     * scope — confirmed live via `aws cognito-idp start-web-authn-registration help`. */
    @Headers("X-Amz-Target: AWSCognitoIdentityProviderService.StartWebAuthnRegistration")
    @POST
    suspend fun startWebAuthnRegistration(
        @Url url: String,
        @Body request: StartWebAuthnRegistrationRequest,
    ): StartWebAuthnRegistrationResponse

    @Headers("X-Amz-Target: AWSCognitoIdentityProviderService.CompleteWebAuthnRegistration")
    @POST
    suspend fun completeWebAuthnRegistration(
        @Url url: String,
        @Body request: CompleteWebAuthnRegistrationRequest,
    ): JsonObject

    /** Invalidates a refresh token — called on sign-out so a copy left in old app
     * storage (or a leaked backup) can't mint new access tokens later. */
    @Headers("X-Amz-Target: AWSCognitoIdentityProviderService.RevokeToken")
    @POST
    suspend fun revokeToken(
        @Url url: String,
        @Body request: RevokeTokenRequest,
    ): JsonObject
}

/** `<https://cognito-idp.<region>.amazonaws.com/>` — the fixed regional endpoint for
 * the Cognito Identity Provider service, not a per-instance domain. `region` comes
 * from the discovery document. */
internal fun cognitoIdpUrl(region: String): String = "https://cognito-idp.$region.amazonaws.com/"

@Serializable
data class InitiateAuthRequest(
    @SerialName("AuthFlow") val authFlow: String,
    @SerialName("ClientId") val clientId: String,
    @SerialName("AuthParameters") val authParameters: Map<String, String>,
)

@Serializable
data class RespondToAuthChallengeRequest(
    @SerialName("ClientId") val clientId: String,
    @SerialName("ChallengeName") val challengeName: String,
    @SerialName("Session") val session: String,
    @SerialName("ChallengeResponses") val challengeResponses: Map<String, String>,
)

/** Shared response shape for both `InitiateAuth` and `RespondToAuthChallenge` — either
 * carries a completed [authenticationResult], or a further [challengeName] to answer. */
@Serializable
data class AuthChallengeResponse(
    @SerialName("ChallengeName") val challengeName: String? = null,
    @SerialName("Session") val session: String? = null,
    // Confirmed live: always string values, even for nested JSON like
    // CREDENTIAL_REQUEST_OPTIONS — callers re-parse those themselves.
    @SerialName("ChallengeParameters") val challengeParameters: Map<String, String> = emptyMap(),
    @SerialName("AvailableChallenges") val availableChallenges: List<String> = emptyList(),
    @SerialName("AuthenticationResult") val authenticationResult: AuthenticationResult? = null,
)

@Serializable
data class AuthenticationResult(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("IdToken") val idToken: String,
    // Absent when this AuthenticationResult came from a REFRESH_TOKEN_AUTH call —
    // confirmed live — since Cognito doesn't rotate refresh tokens by default.
    @SerialName("RefreshToken") val refreshToken: String? = null,
    @SerialName("ExpiresIn") val expiresIn: Int,
    @SerialName("TokenType") val tokenType: String,
)

@Serializable
data class StartWebAuthnRegistrationRequest(
    @SerialName("AccessToken") val accessToken: String,
)

/** [credentialCreationOptions] arrives as a real nested JSON object (Cognito's
 * "document" type), not a string — confirmed live. It's handed to
 * `CreatePublicKeyCredentialRequest` verbatim, re-serialized back to a string. */
@Serializable
data class StartWebAuthnRegistrationResponse(
    @SerialName("CredentialCreationOptions") val credentialCreationOptions: JsonObject,
)

@Serializable
data class CompleteWebAuthnRegistrationRequest(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("Credential") val credential: JsonObject,
)

@Serializable
data class RevokeTokenRequest(
    @SerialName("ClientId") val clientId: String,
    @SerialName("Token") val token: String,
)

/** AWS's standard JSON-RPC error body — confirmed live: HTTP 400 with this shape,
 * e.g. `{"__type":"NotAuthorizedException","message":"Incorrect username or password."}`. */
@Serializable
data class CognitoErrorBody(
    @SerialName("__type") val type: String? = null,
    @SerialName("message") val message: String? = null,
)
