package fr.enry.archivist.data.local

import android.content.SharedPreferences
import androidx.core.content.edit
import javax.inject.Inject

/**
 * The one thing this device needs to remember about its own key enrolment: which
 * `W#` wrapping is its own, so `GET /keys?wrapId=` can ask for full material on the
 * next app start. Not secret — an unwrapped `wrappedKey`/`epk` pair is useless without
 * the Keystore-resident private key that never leaves this device — but stored in the
 * same encrypted prefs file as [TokenStore] anyway, since there's no reason not to.
 */
class EnrolmentStore
    @Inject
    constructor(
        private val preferences: SharedPreferences,
    ) {
        fun deviceWrapId(host: String): String? = preferences.getString(key(host), null)

        fun saveDeviceWrapId(
            host: String,
            wrapId: String,
        ) {
            preferences.edit { putString(key(host), wrapId) }
        }

        fun clearDeviceWrapId(host: String) {
            preferences.edit { remove(key(host)) }
        }

        private fun key(host: String) = "device_wrap_id_$host"
    }
