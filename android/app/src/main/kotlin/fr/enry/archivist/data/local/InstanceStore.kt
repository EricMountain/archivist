package fr.enry.archivist.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.enry.archivist.data.remote.DiscoveryDocument
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

data class StoredInstance(
    val host: String,
    val document: DiscoveryDocument,
)

/**
 * Persists discovery documents keyed by host, plus which one is "current" — a plain
 * map rather than a singleton, per plan step 2.3 ("store per-instance so a second
 * instance is possible later"), even though only one is read back today.
 */
class InstanceStore
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val json: Json,
    ) {
        val current: Flow<StoredInstance?> =
            dataStore.data.map { prefs ->
                val host = prefs[CURRENT_HOST_KEY] ?: return@map null
                val serialized = prefs[instanceKey(host)] ?: return@map null
                runCatching { json.decodeFromString(DiscoveryDocument.serializer(), serialized) }
                    .getOrNull()
                    ?.let { StoredInstance(host, it) }
            }

        suspend fun save(
            host: String,
            document: DiscoveryDocument,
        ) {
            dataStore.edit { prefs ->
                prefs[instanceKey(host)] = json.encodeToString(DiscoveryDocument.serializer(), document)
                prefs[CURRENT_HOST_KEY] = host
            }
        }

        /** Plan step 2.17: a single flag, independent of the per-host instance map
         * above — reviewer preview never connects to any host, so it has no
         * [StoredInstance] of its own to persist. */
        val reviewerPreviewEnabled: Flow<Boolean> =
            dataStore.data.map { prefs -> prefs[REVIEWER_PREVIEW_KEY] ?: false }

        suspend fun setReviewerPreviewEnabled(enabled: Boolean) {
            dataStore.edit { prefs ->
                if (enabled) prefs[REVIEWER_PREVIEW_KEY] = true else prefs.remove(REVIEWER_PREVIEW_KEY)
            }
        }

        private companion object {
            val CURRENT_HOST_KEY = stringPreferencesKey("current_host")
            val REVIEWER_PREVIEW_KEY = booleanPreferencesKey("reviewer_preview_enabled")

            fun instanceKey(host: String) = stringPreferencesKey("instance_$host")
        }
    }
