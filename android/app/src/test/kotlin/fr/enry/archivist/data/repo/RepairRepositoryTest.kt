package fr.enry.archivist.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.crypto.Aad
import fr.enry.archivist.crypto.MasterKey
import fr.enry.archivist.crypto.ObjectRef
import fr.enry.archivist.crypto.WholeObjectCipher
import fr.enry.archivist.data.local.EnrolmentStore
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.ThumbEntry
import fr.enry.archivist.data.local.db.UploadQueueEntity
import fr.enry.archivist.data.local.db.UploadState
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.testutil.FakeCognitoAuthApi
import fr.enry.archivist.testutil.FakeSharedPreferences
import fr.enry.archivist.testutil.FakeThumbnailer
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** [RepairRepository]'s own doc explains why this only ever exercises the local-file
 * path: the class has no server-download fallback, deliberately. */
class RepairRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private lateinit var instanceStore: InstanceStore
    private lateinit var masterKeyHolder: MasterKeyHolder
    private lateinit var repository: RepairRepository

    private val json = Json { ignoreUnknownKeys = true }
    private val host = "photos.example.com"
    private val masterKey = MasterKey.of(ByteArray(32) { it.toByte() })
    private val dek = ByteArray(32) { (it + 1).toByte() }
    private val encDek = encode(masterKey.wrapDek(dek))
    private val photoId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
    private val renditionId = "r1"

    private val recordedBodies = mutableMapOf<String, ByteArray>()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        tempDir = Files.createTempDirectory("repair-repository-test").toFile()
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { File(tempDir, "instances.preferences_pb") })
        instanceStore = InstanceStore(dataStore, json)
        masterKeyHolder = MasterKeyHolder().apply { set(masterKey) }
        db = buildTestDatabase()

        val archivistApiFactory =
            ArchivistApiFactory(
                baseOkHttpClient = OkHttpClient.Builder().build(),
                json = json,
                tokenStore = TokenStore(FakeSharedPreferences(), json),
                cognitoAuthClient = CognitoAuthClient(FakeCognitoAuthApi(), json),
            )

        repository =
            RepairRepository(
                instanceStore = instanceStore,
                archivistApiFactory = archivistApiFactory,
                uploadQueueDao = db.uploadQueueDao(),
                photoDao = db.photoDao(),
                thumbnailer = FakeThumbnailer(),
                masterKeyHolder = masterKeyHolder,
                okHttpClient = OkHttpClient.Builder().build(),
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

    private suspend fun photoRow(): PhotoEntity {
        val entity =
            PhotoEntity(
                photoId = photoId,
                takenAt = "2026-08-30T10:00:00.000Z",
                tzOffsetMin = 0,
                mime = "image/jpeg",
                width = 100,
                height = 100,
                status = AssetStatus.READY,
                thumbs = emptyMap(),
                encDek = encDek,
                encKeyId = "mk-1",
            )
        db.photoDao().upsertAll(listOf(entity))
        return entity
    }

    private suspend fun queueRow(state: UploadState = UploadState.DONE): Long =
        db.uploadQueueDao().insert(
            UploadQueueEntity(
                localUri = "content://media/1",
                displayName = "IMG_1.jpg",
                folderUri = "camera",
                contentHash = "hmac-sha256:test",
                state = state,
                plainBytes = 100,
                fileMtimeEpochSec = 0L,
                takenAt = "2026-08-30T10:00:00.000Z",
                tzOffsetMin = 0,
                takenAtSrc = "upload",
                tzSrc = "assumed-utc",
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

    private fun thumbUploadsJson() =
        """"thumbUploads":{"256":"${server.url("/thumb/256")}","1024":"${server.url("/thumb/1024")}","2048":"${server.url("/thumb/2048")}"}"""

    private fun timelineEntryJson(thumbs: String = "{}") =
        """{"meta":{"photoId":"$photoId","takenAt":"2026-08-30T10:00:00.000Z","thumbs":$thumbs,
        |"encDek":"$encDek","encKeyId":"mk-1","width":100,"height":100,"mime":"image/jpeg",
        |"tzOffsetMin":0,"status":"ready"}}
        """.trimMargin().replace("\n", "")

    private fun setDispatcher(onThumbsPost: (RecordedRequest, ByteArray) -> MockResponse) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    val body = request.body.readByteArray()
                    recordedBodies[path] = body
                    return when {
                        path == "/api/photos/$photoId/thumbs" -> onThumbsPost(request, body)
                        path == "/api/photos/$photoId" ->
                            MockResponse().setResponseCode(200).setBody(
                                timelineEntryJson(
                                    """{"256":{"bucket":"derived","key":"th/o/$photoId/256","iv":"iv-256","bytes":2},
                                    |"1024":{"bucket":"derived","key":"th/o/$photoId/1024","iv":"iv-1024","bytes":2},
                                    |"2048":{"bucket":"derived","key":"th/o/$photoId/2048","iv":"iv-2048","bytes":2}}
                                    """.trimMargin().replace("\n", ""),
                                ),
                            )
                        else -> MockResponse().setResponseCode(200)
                    }
                }
            }
    }

    @Test
    fun `regenerates and re-uploads every rung, decryptable under the asset's own DEK`() =
        runTest {
            connectInstance()
            photoRow()
            queueRow()

            setDispatcher { _, body ->
                val sent = json.decodeFromString<Map<String, JsonElement>>(String(body))
                assertEquals(setOf("256", "1024", "2048"), sent.getValue("thumbs").jsonObject.keys)
                MockResponse().setResponseCode(200).setBody("{${thumbUploadsJson()}}")
            }

            val outcome = repository.repairThumbnails(photoId, primaryRenditionId = renditionId, encDek = encDek)

            assertEquals(RepairOutcome.Done, outcome)

            val sentBody = json.decodeFromString<Map<String, JsonElement>>(String(recordedBodies["/api/photos/$photoId/thumbs"]!!))
            val thumbsSent = sentBody.getValue("thumbs").jsonObject
            val iv256 = decode(thumbsSent.getValue("256").jsonObject.getValue("iv").jsonPrimitive.content)
            val ciphertext256 = recordedBodies.entries.single { it.key == "/thumb/256" }.value
            val plaintext = WholeObjectCipher.decrypt(dek, iv256, Aad.of(photoId, ObjectRef.Thumbnail(256)), ciphertext256)
            assertArrayEquals(byteArrayOf(0x00, 0x01), plaintext) // FakeThumbnailer's 256-rung content

            // The refetch-and-upsert half: the local row now reflects the server's
            // fresh #META.thumbs rather than staying at the empty map it had before.
            val updated = db.photoDao().getByPhotoId(photoId)!!
            assertEquals(3, updated.thumbs.size)
            assertEquals(ThumbEntry("derived", "th/o/$photoId/256", "iv-256", 2), updated.thumbs[256])
        }

    @Test
    fun `no local file for this photo -- fails without making any network call`() =
        runTest {
            connectInstance()
            photoRow()
            // No queueRow() -- this device never uploaded this photo.

            val outcome = repository.repairThumbnails(photoId, primaryRenditionId = renditionId, encDek = encDek)

            assertTrue(outcome is RepairOutcome.Error)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `a queue row that hasn't finished uploading doesn't count as a usable local file`() =
        runTest {
            connectInstance()
            photoRow()
            queueRow(state = UploadState.UPLOADING)

            val outcome = repository.repairThumbnails(photoId, primaryRenditionId = renditionId, encDek = encDek)

            assertTrue(outcome is RepairOutcome.Error)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `a locked master key fails without making any network call`() =
        runTest {
            connectInstance()
            photoRow()
            queueRow()
            masterKeyHolder.clear()

            val outcome = repository.repairThumbnails(photoId, primaryRenditionId = renditionId, encDek = encDek)

            assertTrue(outcome is RepairOutcome.Error)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `a server rejection surfaces its status code instead of throwing`() =
        runTest {
            connectInstance()
            photoRow()
            queueRow()
            setDispatcher { _, _ -> MockResponse().setResponseCode(404).setBody("""{"error":"photo not found"}""") }

            val outcome = repository.repairThumbnails(photoId, primaryRenditionId = renditionId, encDek = encDek)

            assertTrue(outcome is RepairOutcome.Error)
            assertEquals("server rejected repair (HTTP 404)", (outcome as RepairOutcome.Error).message)
        }
}

private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)
