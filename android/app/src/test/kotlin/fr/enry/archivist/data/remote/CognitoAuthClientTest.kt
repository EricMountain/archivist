package fr.enry.archivist.data.remote

import fr.enry.archivist.testutil.FakeCognitoAuthApi
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response

class CognitoAuthClientTest {
    private val fakeApi = FakeCognitoAuthApi()
    private val json = Json { ignoreUnknownKeys = true }
    private val client = CognitoAuthClient(fakeApi, json)

    private fun authResult() =
        AuthenticationResult(
            accessToken = "access",
            idToken = "id",
            refreshToken = "refresh",
            expiresIn = 3600,
            tokenType = "Bearer",
        )

    private fun cognitoError(
        code: Int,
        type: String,
        message: String,
    ) = HttpException(
        Response.error<Any>(
            code,
            """{"__type":"$type","message":"$message"}""".toResponseBody("application/json".toMediaType()),
        ),
    )

    @Test
    fun `signInWithPassword success maps to SignedIn`() =
        runTest {
            fakeApi.initiateAuthResponse = AuthChallengeResponse(authenticationResult = authResult())

            val result = client.signInWithPassword("eu-west-1", "client-id", "a@example.com", "pw")

            assertEquals(CognitoAuthResult.SignedIn(authResult()), result)
            assertEquals("USER_AUTH", fakeApi.lastInitiateAuthRequest?.authFlow)
            assertEquals("PASSWORD", fakeApi.lastInitiateAuthRequest?.authParameters?.get("PREFERRED_CHALLENGE"))
        }

    @Test
    fun `signInWithPassword NEW_PASSWORD_REQUIRED maps to NewPasswordRequired`() =
        runTest {
            fakeApi.initiateAuthResponse =
                AuthChallengeResponse(challengeName = "NEW_PASSWORD_REQUIRED", session = "sess-1")

            val result = client.signInWithPassword("eu-west-1", "client-id", "a@example.com", "pw")

            assertEquals(CognitoAuthResult.NewPasswordRequired("sess-1"), result)
        }

    @Test
    fun `wrong password maps to InvalidCredentials, not a generic error`() =
        runTest {
            fakeApi.initiateAuthError = cognitoError(400, "NotAuthorizedException", "Incorrect username or password.")

            val result = client.signInWithPassword("eu-west-1", "client-id", "a@example.com", "wrong")

            assertEquals(CognitoAuthResult.InvalidCredentials, result)
        }

    @Test
    fun `a network failure maps to NetworkError`() =
        runTest {
            fakeApi.initiateAuthError = IOException("no route to host")

            val result = client.signInWithPassword("eu-west-1", "client-id", "a@example.com", "pw")

            assertEquals(CognitoAuthResult.NetworkError, result)
        }

    @Test
    fun `an unrecognised Cognito exception is surfaced, not silently swallowed`() =
        runTest {
            fakeApi.initiateAuthError = cognitoError(400, "TooManyRequestsException", "Slow down.")

            val result = client.signInWithPassword("eu-west-1", "client-id", "a@example.com", "pw")

            assertEquals(CognitoAuthResult.UnexpectedError("TooManyRequestsException", "Slow down."), result)
        }

    @Test
    fun `WEB_AUTHN challenge maps to PasskeyChallenge with its request options`() =
        runTest {
            fakeApi.initiateAuthResponse =
                AuthChallengeResponse(
                    challengeName = "WEB_AUTHN",
                    session = "sess-2",
                    challengeParameters = mapOf("CREDENTIAL_REQUEST_OPTIONS" to """{"challenge":"abc"}"""),
                )

            val result = client.startPasskeySignIn("eu-west-1", "client-id", "a@example.com")

            assertEquals(CognitoAuthResult.PasskeyChallenge("sess-2", """{"challenge":"abc"}"""), result)
        }

    @Test
    fun `SELECT_CHALLENGE with no WEB_AUTHN available maps to NoPasskeyAvailable`() =
        runTest {
            // Confirmed live: this is exactly what a WEB_AUTHN request produces for an
            // account with no registered passkey — not an error.
            fakeApi.initiateAuthResponse =
                AuthChallengeResponse(
                    challengeName = "SELECT_CHALLENGE",
                    session = "sess-3",
                    availableChallenges = listOf("PASSWORD_SRP", "PASSWORD"),
                )

            val result = client.startPasskeySignIn("eu-west-1", "client-id", "a@example.com")

            assertEquals(CognitoAuthResult.NoPasskeyAvailable, result)
        }

    @Test
    fun `completePasskeySignIn sends the assertion as the CREDENTIAL challenge response`() =
        runTest {
            fakeApi.respondToAuthChallengeResponse = AuthChallengeResponse(authenticationResult = authResult())

            client.completePasskeySignIn("eu-west-1", "client-id", "a@example.com", "sess-2", """{"id":"cred"}""")

            assertEquals("WEB_AUTHN", fakeApi.lastRespondToAuthChallengeRequest?.challengeName)
            assertEquals(
                """{"id":"cred"}""",
                fakeApi.lastRespondToAuthChallengeRequest?.challengeResponses?.get("CREDENTIAL"),
            )
        }

    @Test
    fun `refresh omits PASSWORD or WEB_AUTHN parameters entirely`() =
        runTest {
            fakeApi.initiateAuthResponse =
                AuthChallengeResponse(authenticationResult = authResult().copy(refreshToken = null))

            client.refresh("eu-west-1", "client-id", "the-refresh-token")

            assertEquals("REFRESH_TOKEN_AUTH", fakeApi.lastInitiateAuthRequest?.authFlow)
            assertEquals(mapOf("REFRESH_TOKEN" to "the-refresh-token"), fakeApi.lastInitiateAuthRequest?.authParameters)
        }

    @Test
    fun `startPasskeyRegistration returns the creation options as a JSON string`() =
        runTest {
            val options = buildJsonObject { put("challenge", "xyz") }
            fakeApi.startWebAuthnRegistrationResponse = StartWebAuthnRegistrationResponse(options)

            val result = client.startPasskeyRegistration("eu-west-1", "access-token")

            assertTrue(result is PasskeyRegistrationStart.Options)
            assertEquals(options.toString(), (result as PasskeyRegistrationStart.Options).creationOptionsJson)
            assertEquals("access-token", fakeApi.lastStartWebAuthnRegistrationRequest?.accessToken)
        }

    @Test
    fun `startPasskeyRegistration failure is reported, not thrown`() =
        runTest {
            fakeApi.startWebAuthnRegistrationError = cognitoError(400, "NotAuthorizedException", "expired token")

            val result = client.startPasskeyRegistration("eu-west-1", "stale-token")

            assertEquals(PasskeyRegistrationStart.Failed("NotAuthorizedException", "expired token"), result)
        }

    @Test
    fun `completePasskeyRegistration succeeds and forwards the parsed credential`() =
        runTest {
            val result = client.completePasskeyRegistration("eu-west-1", "access-token", """{"id":"new-cred"}""")

            assertEquals(PasskeyRegistrationComplete.Success, result)
            val credentialId = fakeApi.lastCompleteWebAuthnRegistrationRequest?.credential?.get("id")?.jsonPrimitive?.content
            assertEquals("new-cred", credentialId)
        }

    @Test
    fun `completePasskeyRegistration rejects malformed JSON without calling the network`() =
        runTest {
            val result = client.completePasskeyRegistration("eu-west-1", "access-token", "not json")

            assertTrue(result is PasskeyRegistrationComplete.Failed)
            assertEquals(null, fakeApi.lastCompleteWebAuthnRegistrationRequest)
        }

    @Test
    fun `revoke swallows failures — sign-out proceeds locally regardless`() =
        runTest {
            fakeApi.revokeTokenError = IOException("network down")

            client.revoke("eu-west-1", "client-id", "refresh-token")

            assertEquals("refresh-token", fakeApi.lastRevokeTokenRequest?.token)
        }
}
