package fr.enry.archivist.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Plan step 2.14's Settings > Sync section. Both settings default to `false`
 * (`Constraints.Builder()`'s previous hardcoded behavior — `NetworkType.UNMETERED`,
 * no charging requirement — must be exactly what a fresh install still gets). Doesn't
 * attempt to reopen the same on-disk file through a second `DataStore` instance to
 * simulate a real app restart — `androidx.datastore` itself refuses more than one live
 * `DataStore` per file per process (confirmed the hard way: `IllegalStateException`),
 * same reason [InstanceStoreTest][fr.enry.archivist.data.local.InstanceStoreTest]
 * doesn't either. Persistence to disk is the library's own guarantee, not this class's;
 * what's worth testing here is that read/write map to the right keys.
 */
class SyncSettingsStoreTest {
    private lateinit var tempDir: File
    private lateinit var file: File

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("sync-settings-store-test").toFile()
        file = File(tempDir, "sync_settings.preferences_pb")
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun newStore() = SyncSettingsStore(PreferenceDataStoreFactory.create(produceFile = { file }))

    @Test
    fun `defaults match what the upload worker previously hardcoded`() =
        runTest {
            val settings = newStore().settings.first()
            assertFalse(settings.allowMeteredNetwork)
            assertFalse(settings.requiresCharging)
        }

    @Test
    fun `both settings round-trip through the same store`() =
        runTest {
            val store = newStore()
            store.setAllowMeteredNetwork(true)
            store.setRequiresCharging(true)

            assertEquals(SyncSettings(allowMeteredNetwork = true, requiresCharging = true), store.settings.first())
        }

    @Test
    fun `the two settings are independent`() =
        runTest {
            val store = newStore()
            store.setAllowMeteredNetwork(true)

            val settings = store.settings.first()
            assertEquals(true, settings.allowMeteredNetwork)
            assertFalse(settings.requiresCharging)
        }
}
