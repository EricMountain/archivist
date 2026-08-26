package fr.enry.archivist.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

        private companion object {
            val CURRENT_HOST_KEY = stringPreferencesKey("current_host")

            fun instanceKey(host: String) = stringPreferencesKey("instance_$host")
        }
    }
