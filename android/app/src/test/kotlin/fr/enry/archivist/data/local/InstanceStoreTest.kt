package fr.enry.archivist.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.data.remote.DiscoveryDocument
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InstanceStoreTest {
    private lateinit var tempDir: File
    private lateinit var store: InstanceStore

    private val document =
        DiscoveryDocument(
            apiBase = "https://photos.example.com/api",
            region = "eu-west-1",
            cognito = DiscoveryDocument.CognitoConfig(userPoolId = "eu-west-1_XXXXXXXXX", clientId = "abc123"),
            cryptoVersion = 1,
            instanceName = "Home photos",
        )

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("instance-store-test").toFile()
        val dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "instances.preferences_pb") },
            )
        store = InstanceStore(dataStore, Json { ignoreUnknownKeys = true })
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `no instance persisted yet returns null`() =
        runTest {
            assertNull(store.current.first())
        }

    @Test
    fun `saving an instance persists it as current`() =
        runTest {
            store.save("photos.example.com", document)

            val stored = store.current.first()

            assertEquals("photos.example.com", stored?.host)
            assertEquals(document, stored?.document)
        }

    @Test
    fun `saving a second instance switches which one is current`() =
        runTest {
            store.save("photos.example.com", document)
            val second = document.copy(instanceName = "Second instance")
            store.save("photos2.example.com", second)

            val stored = store.current.first()

            assertEquals("photos2.example.com", stored?.host)
            assertEquals("Second instance", stored?.document?.instanceName)
        }

    @Test
    fun `reviewer preview defaults to disabled`() =
        runTest {
            assertFalse(store.reviewerPreviewEnabled.first())
        }

    @Test
    fun `reviewer preview flag round-trips and can be cleared`() =
        runTest {
            store.setReviewerPreviewEnabled(true)
            assertTrue(store.reviewerPreviewEnabled.first())

            store.setReviewerPreviewEnabled(false)
            assertFalse(store.reviewerPreviewEnabled.first())
        }
}
