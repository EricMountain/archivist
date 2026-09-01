package fr.enry.archivist.data.local

import android.content.SharedPreferences
import androidx.core.content.edit
import javax.inject.Inject

/** The device's own wrapped master-key material — just enough to unwrap it via
 * [fr.enry.archivist.crypto.KeyCustody.unwrapForDevice] without a network round trip. */
data class CachedDeviceWrap(val epk: String, val wrappedKey: String)

/**
 * The one thing this device needs to remember about its own key enrolment: which
 * `W#` wrapping is its own, so `GET /keys?wrapId=` can ask for full material on the
 * next app start. Not secret — an unwrapped `wrappedKey`/`epk` pair is useless without
 * the Keystore-resident private key that never leaves this device — but stored in the
 * same encrypted prefs file as [TokenStore] anyway, since there's no reason not to.
 *
 * [cachedDeviceWrap] is that same "not secret" material itself, cached from the last
 * successful `GET /keys` so a cold start can still silently unlock **offline** — see
 * "opens offline showing cached thumbnails" in plan step 2.11's own "Done when", found
 * to be unreachable in practice because [EnrolmentRepository.trySilentUnlock] fetched
 * this fresh from the server on *every* launch with no fallback, live-tested against
 * the `dev` instance 2026-09-01. Same non-secrecy argument as `deviceWrapId` applies —
 * caching it doesn't change what an attacker without this device's Keystore key can do
 * with it (nothing). **Not rotation-safe**: if the owner's master key is ever rotated,
 * a cached wrap could be stale relative to the current version; harmless today since
 * nothing in this codebase rotates yet, but worth revisiting if that changes — see
 * design.md's own notes on rotation.
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

        fun cachedDeviceWrap(host: String): CachedDeviceWrap? {
            val epk = preferences.getString(wrapEpkKey(host), null) ?: return null
            val wrappedKey = preferences.getString(wrapKeyKey(host), null) ?: return null
            return CachedDeviceWrap(epk, wrappedKey)
        }

        fun saveCachedDeviceWrap(
            host: String,
            wrap: CachedDeviceWrap,
        ) {
            preferences.edit {
                putString(wrapEpkKey(host), wrap.epk)
                putString(wrapKeyKey(host), wrap.wrappedKey)
            }
        }

        fun clearCachedDeviceWrap(host: String) {
            preferences.edit {
                remove(wrapEpkKey(host))
                remove(wrapKeyKey(host))
            }
        }

        private fun key(host: String) = "device_wrap_id_$host"

        private fun wrapEpkKey(host: String) = "device_wrap_epk_$host"

        private fun wrapKeyKey(host: String) = "device_wrap_key_$host"
    }
