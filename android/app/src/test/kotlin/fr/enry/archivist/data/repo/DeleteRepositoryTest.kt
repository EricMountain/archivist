package fr.enry.archivist.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.UploadQueueEntity
import fr.enry.archivist.data.local.db.UploadState
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.sync.MediaDeleteOutcome
import fr.enry.archivist.testutil.FakeCognitoAuthApi
import fr.enry.archivist.testutil.FakeMediaStoreSource
import fr.enry.archivist.testutil.FakeSharedPreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Plan step 2.13. Exercises [DeleteRepository.delete] against a real Retrofit stack
 * (MockWebServer) and a real in-memory Room database, following [PhotoDetailRepositoryTest]/
 * [fr.enry.archivist.data.repo.UploadRepositoryTest]'s own pattern of asserting on what
 * actually happened (the request sent, the rows left behind) rather than just that no
 * exception was thrown.
 */
class DeleteRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var instanceStore: InstanceStore
    private lateinit var db: AppDatabase
    private lateinit var mediaStoreSource: FakeMediaStoreSource
    private lateinit var repository: DeleteRepository

    private val json = Json { ignoreUnknownKeys = true }
    private val host = "photos.example.com"
    private val photoId = "01K5A2Q8ZCV1000000000000"

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("delete-repository-test").toFile()

        val dataStore = PreferenceDataStoreFactory.create(produceFile = { File(tempDir, "instances.preferences_pb") })
        instanceStore = InstanceStore(dataStore, json)
        db = buildTestDatabase()
        mediaStoreSource = FakeMediaStoreSource()

        val archivistApiFactory =
            ArchivistApiFactory(
                baseOkHttpClient = OkHttpClient.Builder().build(),
                json = json,
                tokenStore = TokenStore(FakeSharedPreferences(), json),
                cognitoAuthClient = CognitoAuthClient(FakeCognitoAuthApi(), json),
            )

        repository =
            DeleteRepository(
                instanceStore = instanceStore,
                archivistApiFactory = archivistApiFactory,
                photoDao = db.photoDao(),
                uploadQueueDao = db.uploadQueueDao(),
                localTombstoneDao = db.localTombstoneDao(),
                mediaStoreSource = mediaStoreSource,
            )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
        db.close()
    }

    private suspend fun connectInstance() {
        instanceStore.save(
            host,
            DiscoveryDocument(
                apiBase = server.url("/api").toString().trimEnd('/'),
                region = "eu-west-1",
                cognito = DiscoveryDocument.CognitoConfig(userPoolId = "eu-west-1_XXXXXXXXX", clientId = "client-id"),
                cryptoVersion = 1,
                instanceName = "Home photos",
            ),
        )
    }

    private suspend fun seedPhoto() {
        db.photoDao().upsertOne(
            photoId = photoId,
            takenAt = "2026-08-30T10:00:00.000Z",
            tzOffsetMin = 0,
            mime = "image/jpeg",
            width = 100,
            height = 100,
            status = AssetStatus.READY,
            thumbs = emptyMap(),
            encDek = "dek",
            encKeyId = "mk-1",
        )
    }

    private suspend fun seedUploadQueueRow(
        localUri: String,
        contentHash: String,
        renditionId: String,
    ) {
        db.uploadQueueDao().insert(
            UploadQueueEntity(
                localUri = localUri,
                displayName = localUri.substringAfterLast('/'),
                folderUri = "content://media/external/images/media",
                contentHash = contentHash,
                state = UploadState.DONE,
                plainBytes = 1024,
                fileMtimeEpochSec = 0,
                takenAt = "2026-08-30T10:00:00.000Z",
                tzOffsetMin = 0,
                takenAtSrc = "exif",
                tzSrc = "exif-offset",
                mime = "image/jpeg",
                width = 100,
                height = 100,
                photoId = photoId,
                renditionId = renditionId,
                attempts = 0,
                lastError = null,
                createdAt = "2026-08-30T10:00:00.000Z",
                updatedAt = "2026-08-30T10:00:00.000Z",
            ),
        )
    }

    @Test
    fun `archive-only delete removes the Room row and tombstones every local rendition`() =
        runTest {
            connectInstance()
            seedPhoto()
            seedUploadQueueRow("content://media/1", contentHash = "hash-jpg", renditionId = "r1")
            seedUploadQueueRow("content://media/2", contentHash = "hash-raw", renditionId = "r2")
            server.enqueue(MockResponse().setResponseCode(204))

            val outcome = repository.delete(photoId, DeleteMode.ARCHIVE_ONLY)

            assertEquals(DeleteOutcome.Done, outcome)
            assertNull(db.photoDao().getByPhotoId(photoId))
            assertTrue(db.localTombstoneDao().exists("hash-jpg"))
            assertTrue(db.localTombstoneDao().exists("hash-raw"))
            // Archive-only: the local files (and their upload_queue record of them) are
            // untouched -- "leaves it in the gallery" per android.md.
            assertEquals(2, db.uploadQueueDao().getByPhotoId(photoId).size)
            assertTrue(mediaStoreSource.deleteRequests.isEmpty())

            val request: RecordedRequest = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertTrue(request.path?.endsWith("/photos/$photoId") == true)
        }

    @Test
    fun `both-removal deletes local files and forgets the upload_queue rows once MediaStore confirms`() =
        runTest {
            connectInstance()
            seedPhoto()
            seedUploadQueueRow("content://media/1", contentHash = "hash-jpg", renditionId = "r1")
            mediaStoreSource.deleteOutcome = MediaDeleteOutcome.Deleted
            server.enqueue(MockResponse().setResponseCode(204))

            val outcome = repository.delete(photoId, DeleteMode.BOTH)

            assertEquals(DeleteOutcome.Done, outcome)
            assertTrue(db.localTombstoneDao().exists("hash-jpg"))
            assertEquals(listOf(listOf("content://media/1")), mediaStoreSource.deleteRequests)
            assertTrue(db.uploadQueueDao().getByPhotoId(photoId).isEmpty())
        }

    @Test
    fun `both-removal surfaces a confirmation request without touching upload_queue yet`() =
        runTest {
            connectInstance()
            seedPhoto()
            seedUploadQueueRow("content://media/1", contentHash = "hash-jpg", renditionId = "r1")
            val intentSender = mockIntentSender()
            mediaStoreSource.deleteOutcome = MediaDeleteOutcome.NeedsConfirmation(intentSender)
            server.enqueue(MockResponse().setResponseCode(204))

            val outcome = repository.delete(photoId, DeleteMode.BOTH)

            assertTrue(outcome is DeleteOutcome.NeedsMediaConfirmation)
            outcome as DeleteOutcome.NeedsMediaConfirmation
            assertEquals(photoId, outcome.photoId)
            // The archive-side effects already happened -- only the local file removal
            // is still pending the user's confirmation.
            assertNull(db.photoDao().getByPhotoId(photoId))
            assertTrue(db.localTombstoneDao().exists("hash-jpg"))
            assertEquals(1, db.uploadQueueDao().getByPhotoId(photoId).size)

            repository.finishMediaDelete(photoId)
            assertTrue(db.uploadQueueDao().getByPhotoId(photoId).isEmpty())
        }

    @Test
    fun `a photo never uploaded from this device is deleted with nothing to tombstone`() =
        runTest {
            connectInstance()
            seedPhoto()
            server.enqueue(MockResponse().setResponseCode(204))

            val outcome = repository.delete(photoId, DeleteMode.ARCHIVE_ONLY)

            assertEquals(DeleteOutcome.Done, outcome)
            assertNull(db.photoDao().getByPhotoId(photoId))
        }

    @Test
    fun `a failed archive delete leaves the Room row and upload_queue untouched`() =
        runTest {
            connectInstance()
            seedPhoto()
            seedUploadQueueRow("content://media/1", contentHash = "hash-jpg", renditionId = "r1")
            server.enqueue(MockResponse().setResponseCode(404))

            val outcome = repository.delete(photoId, DeleteMode.ARCHIVE_ONLY)

            assertTrue(outcome is DeleteOutcome.Error)
            assertNotNull(db.photoDao().getByPhotoId(photoId))
            assertEquals(1, db.uploadQueueDao().getByPhotoId(photoId).size)
            assertTrue(db.localTombstoneDao().exists("hash-jpg").not())
        }

    private fun mockIntentSender(): android.content.IntentSender = org.mockito.kotlin.mock()
}
