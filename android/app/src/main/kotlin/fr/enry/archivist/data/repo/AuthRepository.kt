package fr.enry.archivist.data.repo

import fr.enry.archivist.data.local.AuthSession
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.StoredInstance
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.AuthenticationResult
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.CognitoAuthResult
import fr.enry.archivist.data.remote.PasskeyRegistrationComplete
import fr.enry.archivist.data.remote.PasskeyRegistrationStart
import fr.enry.archivist.data.remote.SessionBootstrapRequest
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Ties the current instance (host, region, Cognito client ID, apiBase — from
 * [InstanceStore]) to authentication. `POST /session/bootstrap` (plan step 1.7) is
 * idempotent server-side (confirmed live: a second call returns the same ids and
 * `created: false`), so this calls it after every successful sign-in rather than
 * tracking "is this the first one" client-side.
 */
class AuthRepository
    @Inject
    constructor(
        private val instanceStore: InstanceStore,
        private val cognitoAuthClient: CognitoAuthClient,
        private val tokenStore: TokenStore,
        private val archivistApiFactory: ArchivistApiFactory,
    ) {
        fun currentSession(host: String): AuthSession? = tokenStore.get(host)

        suspend fun signInWithPassword(
            username: String,
            password: String,
        ): CognitoAuthResult {
            val instance = currentInstanceOrThrow()
            val result =
                cognitoAuthClient.signInWithPassword(
                    instance.document.region,
                    instance.document.cognito.clientId,
                    username,
                    password,
                )
            if (result is CognitoAuthResult.SignedIn) persistAndBootstrap(instance, username, result.result)
            return result
        }

        suspend fun completeNewPassword(
            username: String,
            newPassword: String,
            session: String,
        ): CognitoAuthResult {
            val instance = currentInstanceOrThrow()
            val result =
                cognitoAuthClient.completeNewPassword(
                    instance.document.region,
                    instance.document.cognito.clientId,
                    username,
                    newPassword,
                    session,
                )
            if (result is CognitoAuthResult.SignedIn) persistAndBootstrap(instance, username, result.result)
            return result
        }

        suspend fun startPasskeySignIn(username: String): CognitoAuthResult {
            val instance = currentInstanceOrThrow()
            return cognitoAuthClient.startPasskeySignIn(
                instance.document.region,
                instance.document.cognito.clientId,
                username,
            )
        }

        suspend fun completePasskeySignIn(
            username: String,
            session: String,
            assertionJson: String,
        ): CognitoAuthResult {
            val instance = currentInstanceOrThrow()
            val result =
                cognitoAuthClient.completePasskeySignIn(
                    instance.document.region,
                    instance.document.cognito.clientId,
                    username,
                    session,
                    assertionJson,
                )
            if (result is CognitoAuthResult.SignedIn) persistAndBootstrap(instance, username, result.result)
            return result
        }

        /** Only meaningful once already signed in — needs the access token
         * [startPasskeyRegistration][fr.enry.archivist.data.remote.CognitoAuthApi.startWebAuthnRegistration]
         * requires. */
        suspend fun startPasskeyRegistration(): PasskeyRegistrationStart {
            val instance = currentInstanceOrThrow()
            val session = tokenStore.get(instance.host)
            return session?.let { cognitoAuthClient.startPasskeyRegistration(instance.document.region, it.accessToken) }
                ?: PasskeyRegistrationStart.Failed(null, "not signed in")
        }

        suspend fun completePasskeyRegistration(credentialJson: String): PasskeyRegistrationComplete {
            val instance = currentInstanceOrThrow()
            val session = tokenStore.get(instance.host)
            return session?.let {
                cognitoAuthClient.completePasskeyRegistration(instance.document.region, it.accessToken, credentialJson)
            } ?: PasskeyRegistrationComplete.Failed(null, "not signed in")
        }

        suspend fun signOut() {
            val instance = currentInstanceOrThrow()
            val session = tokenStore.get(instance.host) ?: return
            cognitoAuthClient.revoke(instance.document.region, instance.document.cognito.clientId, session.refreshToken)
            tokenStore.clear(instance.host)
        }

        private suspend fun persistAndBootstrap(
            instance: StoredInstance,
            username: String,
            result: AuthenticationResult,
        ) {
            val refreshToken =
                result.refreshToken ?: error("no RefreshToken on a first sign-in — Cognito protocol violation")
            val session =
                AuthSession(
                    username = username,
                    accessToken = result.accessToken,
                    idToken = result.idToken,
                    refreshToken = refreshToken,
                    accessTokenExpiresAt = System.currentTimeMillis() + result.expiresIn * 1000L,
                )
            tokenStore.save(instance.host, session)

            val api =
                archivistApiFactory.create(
                    instance.host,
                    instance.document.region,
                    instance.document.cognito.clientId,
                )
            api.postSessionBootstrap(bootstrapUrl(instance.document.apiBase), SessionBootstrapRequest())
        }

        private suspend fun currentInstanceOrThrow(): StoredInstance =
            instanceStore.current.first() ?: error("no connected instance — 2.3 must complete before 2.4 can run")
    }

private fun bootstrapUrl(apiBase: String): String = "$apiBase/session/bootstrap"
