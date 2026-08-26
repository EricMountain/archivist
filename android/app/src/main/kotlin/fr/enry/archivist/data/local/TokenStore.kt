package fr.enry.archivist.data.local

import android.content.SharedPreferences
import androidx.core.content.edit
import javax.inject.Inject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One signed-in session, per instance host. Depends on plain [SharedPreferences]
 * rather than DataStore on purpose: the OkHttp `Authenticator` that refreshes an
 * expired token (see [fr.enry.archivist.data.remote.ArchivistAuthenticator]) runs
 * synchronously on a background thread, and a blocking synchronous read is a much
 * better fit there than bridging into a `Flow`. [SharedPreferences] is what
 * `EncryptedSharedPreferences` implements in production — see [TokenStorageModule].
 */
@Serializable
data class AuthSession(
    @SerialName("username") val username: String,
    @SerialName("accessToken") val accessToken: String,
    @SerialName("idToken") val idToken: String,
    @SerialName("refreshToken") val refreshToken: String,
    /** Epoch millis. Checked with a safety margin, not down to the second — see
     * [fr.enry.archivist.data.repo.AuthRepository]'s proactive-refresh logic. */
    @SerialName("accessTokenExpiresAt") val accessTokenExpiresAt: Long,
)

class TokenStore
    @Inject
    constructor(
        private val preferences: SharedPreferences,
        private val json: Json,
    ) {
        fun get(host: String): AuthSession? {
            val raw = preferences.getString(sessionKey(host), null) ?: return null
            return runCatching { json.decodeFromString(AuthSession.serializer(), raw) }.getOrNull()
        }

        fun save(
            host: String,
            session: AuthSession,
        ) {
            preferences.edit {
                putString(sessionKey(host), json.encodeToString(AuthSession.serializer(), session))
            }
        }

        fun clear(host: String) {
            preferences.edit { remove(sessionKey(host)) }
        }

        private fun sessionKey(host: String) = "session_$host"
    }
