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
import fr.enry.archivist.data.local.db.UploadQueueEntity
import fr.enry.archivist.data.local.db.UploadState
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.data.remote.KeyWrapDto
import fr.enry.archivist.data.remote.KeysResponse
import fr.enry.archivist.testutil.FakeCognitoAuthApi
import fr.enry.archivist.testutil.FakeMediaStoreSource
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UploadRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private lateinit var instanceStore: InstanceStore
    private lateinit var enrolmentStore: EnrolmentStore
    private lateinit var masterKeyHolder: MasterKeyHolder
    private lateinit var mediaStoreSource: FakeMediaStoreSource
    private lateinit var repository: UploadRepository

    private val json = Json { ignoreUnknownKeys = true }
    private val host = "photos.example.com"
    private val wrapId = "w1"
    private val masterKey = MasterKey.of(ByteArray(32) { it.toByte() })
    private val fileContent = "hello world, this is a test photo".toByteArray()

    /** Every recorded request body, keyed by path — how each test inspects exactly
     * what ciphertext was PUT, to decrypt and verify it rather than just checking that
     * *a* PUT happened. */
    private val recordedBodies = mutableMapOf<String, ByteArray>()

    /** Set by a test before calling [uploadOne] to control `POST /uploads`'s response. */
    private var uploadResponseBody: String = ""

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    recordedBodies[path] = request.body.readByteArray()
                    return when {
                        path.startsWith("/api/keys") ->
                            MockResponse().setResponseCode(200).setBody(
                                json.encodeToString(
                                    KeysResponse.serializer(),
                                    KeysResponse(
                                        listOf(
                                            KeyWrapDto(
                                                wrapId = wrapId,
                                                kind = "device",
                                                label = "Test device",
                                                masterKeyVer = "mk-1",
                                            ),
                                        ),
                                    ),
                                ),
                            )
                        path == "/api/uploads" -> MockResponse().setResponseCode(200).setBody(uploadResponseBody)
                        else -> MockResponse().setResponseCode(200)
                    }
                }
            }
        server.start()
        tempDir = Files.createTempDirectory("upload-repository-test").toFile()

        val dataStore = PreferenceDataStoreFactory.create(produceFile = { File(tempDir, "instances.preferences_pb") })
        instanceStore = InstanceStore(dataStore, json)
        enrolmentStore = EnrolmentStore(FakeSharedPreferences()).apply { saveDeviceWrapId(host, wrapId) }
        masterKeyHolder = MasterKeyHolder().apply { set(masterKey) }
        mediaStoreSource = FakeMediaStoreSource().apply { addFile("camera", "Camera", "content://media/1", "IMG_1.jpg", fileContent) }
        db = buildTestDatabase()

        val archivistApiFactory =
            ArchivistApiFactory(
                baseOkHttpClient = OkHttpClient.Builder().build(),
                json = json,
                tokenStore = TokenStore(FakeSharedPreferences(), json),
                cognitoAuthClient = CognitoAuthClient(FakeCognitoAuthApi(), json),
            )

        repository =
            UploadRepository(
                uploadQueueDao = db.uploadQueueDao(),
                localTombstoneDao = db.localTombstoneDao(),
                mediaStoreSource = mediaStoreSource,
                thumbnailer = FakeThumbnailer(),
                instanceStore = instanceStore,
                archivistApiFactory = archivistApiFactory,
                enrolmentStore = enrolmentStore,
                masterKeyHolder = masterKeyHolder,
                baseOkHttpClient = OkHttpClient.Builder().build(),
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

    private suspend fun queueRow(): Long =
        db.uploadQueueDao().insert(
            UploadQueueEntity(
                localUri = "content://media/1",
                displayName = "IMG_1.jpg",
                folderUri = "camera",
                contentHash = "hmac-sha256:test",
                state = UploadState.PENDING,
                plainBytes = fileContent.size.toLong(),
                fileMtimeEpochSec = 0L,
                takenAt = null,
                tzOffsetMin = null,
                takenAtSrc = null,
                tzSrc = null,
                mime = null,
                width = null,
                height = null,
                photoId = null,
                renditionId = null,
                attempts = 0,
                lastError = null,
                createdAt = "2026-08-30T10:00:00.000Z",
                updatedAt = "2026-08-30T10:00:00.000Z",
            ),
        )

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)

    private fun originalUploadJson(pathSuffix: String) = """"originalUpload":{"url":"${server.url("/media/$pathSuffix")}"}"""

    private fun thumbUploadsJson(pathSuffix: String) =
        """"thumbUploads":{"256":"${server.url("/thumb/256/$pathSuffix")}","1024":"${server.url("/thumb/1024/$pathSuffix")}","2048":"${server.url("/thumb/2048/$pathSuffix")}"}"""

    // ------------------------------------------------------------------
    // created: true -- a brand-new asset, the candidate DEK survives.
    // ------------------------------------------------------------------

    @Test
    fun `created -- PUTs the original and every thumbnail, all decryptable under the candidate DEK`() =
        runTest {
            connectInstance()
            val queueId = queueRow()

            // created:true means the server used whatever candidate photoId/encDek the
            // client sent -- but this test doesn't know those in advance (they're
            // random per run), so it captures them from the recorded /api/uploads
            // request body instead of hardcoding them into the response.
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.path.orEmpty()
                        val bodyBytes = request.body.readByteArray()
                        recordedBodies[path] = bodyBytes
                        return when {
                            path.startsWith("/api/keys") ->
                                MockResponse().setResponseCode(200).setBody(
                                    json.encodeToString(
                                        KeysResponse.serializer(),
                                        KeysResponse(listOf(KeyWrapDto(wrapId, "device", "Test device", "mk-1"))),
                                    ),
                                )
                            path == "/api/uploads" -> {
                                val sent = json.decodeFromString<Map<String, JsonElement>>(String(bodyBytes))
                                val photoId = sent.string("photoId")
                                val encDek = sent.string("encDek")
                                val body =
                                    """{"photoId":"$photoId","renditionId":"r1","created":true,"encDek":"$encDek","encKeyId":"mk-1",
                                    |${originalUploadJson("orig")},${thumbUploadsJson("t")}}
                                    """.trimMargin().replace("\n", "")
                                MockResponse().setResponseCode(200).setBody(body)
                            }
                            else -> MockResponse().setResponseCode(200)
                        }
                    }
                }

            val outcome = repository.uploadOne(queueId)

            assertEquals(UploadOutcome.Success, outcome)
            val row = db.uploadQueueDao().getById(queueId)!!
            assertEquals(UploadState.DONE, row.state)
            assertEquals("r1", row.renditionId)

            // Decrypt what actually landed in S3 using the DEK the client itself
            // generated, unwrapped via the same master key it wrapped it with --
            // proves the PUT body really is this asset's DEK/AAD, not just "some PUT
            // happened".
            val sentBody = json.decodeFromString<Map<String, JsonElement>>(String(recordedBodies["/api/uploads"]!!))
            val photoId = sentBody.string("photoId")
            val dek = masterKey.unwrapDek(decode(sentBody.string("encDek")))
            val encIv = decode(sentBody.string("encIv"))

            val originalCiphertext = recordedBodies.entries.single { it.key.startsWith("/media/orig") }.value
            val plaintext =
                WholeObjectCipher.decrypt(dek, encIv, Aad.of(photoId, ObjectRef.Rendition("r1")), originalCiphertext)
            assertArrayEquals(fileContent, plaintext)

            // A thumbnail too, same DEK, its own (per-thumbnail, independently
            // generated) IV -- read back from what this same request told the server.
            val thumbsSent = sentBody["thumbs"]!!.jsonObject
            val thumbIv = decode(thumbsSent["256"]!!.jsonObject.string("iv"))
            val thumbCiphertext = recordedBodies.entries.single { it.key.startsWith("/thumb/256") }.value
            val thumbPlaintext =
                WholeObjectCipher.decrypt(dek, thumbIv, Aad.of(photoId, ObjectRef.Thumbnail(256)), thumbCiphertext)
            assertArrayEquals(byteArrayOf(0x00, 0x01), thumbPlaintext) // FakeThumbnailer's 256-rung content
        }

    // ------------------------------------------------------------------
    // resumed: true -- the client's own asset, already committed by an earlier
    // attempt; the real encDek/encIv/encChunkSize come back and must be reused.
    // ------------------------------------------------------------------

    @Test
    fun `resumed -- discards the candidate DEK, reuses the real encIv, and re-uploads thumbnails`() =
        runTest {
            connectInstance()
            val queueId = queueRow()

            val realDek = ByteArray(32) { (it + 1).toByte() }
            val realEncDek = encode(masterKey.wrapDek(realDek))
            val realIv = ByteArray(12) { 7 }
            val photoId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"

            uploadResponseBody =
                """{"photoId":"$photoId","renditionId":"r1","created":false,"resumed":true,
                |"encDek":"$realEncDek","encKeyId":"mk-1","encIv":"${encode(realIv)}","encChunkSize":0,
                |${originalUploadJson("orig")},${thumbUploadsJson("t")}}
                """.trimMargin().replace("\n", "")

            val outcome = repository.uploadOne(queueId)

            assertEquals(UploadOutcome.Success, outcome)
            val row = db.uploadQueueDao().getById(queueId)!!
            assertEquals(UploadState.DONE, row.state)
            assertEquals(photoId, row.photoId)

            val originalCiphertext = recordedBodies.entries.single { it.key.startsWith("/media/orig") }.value
            val plaintext = WholeObjectCipher.decrypt(realDek, realIv, Aad.of(photoId, ObjectRef.Rendition("r1")), originalCiphertext)
            assertArrayEquals(fileContent, plaintext)

            // Thumbnails DO get re-uploaded on a resume (unlike a plain attach) --
            // #META.thumbs is re-recorded to match on every `resumed` response, so
            // this is safe.
            assertTrue(recordedBodies.keys.any { it.startsWith("/thumb/256") })
        }

    // ------------------------------------------------------------------
    // attached to a different, already-existing asset: thumbnails must NOT be
    // re-uploaded (the server doesn't re-record #META.thumbs for a plain attach).
    // ------------------------------------------------------------------

    @Test
    fun `attach -- re-encrypts and PUTs the original only, never the thumbnails`() =
        runTest {
            connectInstance()
            val queueId = queueRow()

            val realDek = ByteArray(32) { (it + 2).toByte() }
            val realEncDek = encode(masterKey.wrapDek(realDek))
            val photoId = "01ARZ3NDEKTSV4RRFFQ69G5FAX"

            uploadResponseBody =
                """{"photoId":"$photoId","renditionId":"r2","created":false,
                |"encDek":"$realEncDek","encKeyId":"mk-1",
                |${originalUploadJson("orig")},${thumbUploadsJson("t")}}
                """.trimMargin().replace("\n", "")

            val outcome = repository.uploadOne(queueId)

            assertEquals(UploadOutcome.Success, outcome)
            val row = db.uploadQueueDao().getById(queueId)!!
            assertEquals(UploadState.DONE, row.state)

            assertTrue(recordedBodies.keys.any { it.startsWith("/media/orig") })
            assertTrue(recordedBodies.keys.none { it.startsWith("/thumb/") })

            // The rendition item this attach just created records encIv straight off
            // *this* request's body regardless of create-vs-attach (uploads.ts's
            // buildRendition) -- so the PUT must be decryptable under the real DEK and
            // the *candidate* IV this same request sent, not some other IV.
            val sentBody = json.decodeFromString<Map<String, JsonElement>>(String(recordedBodies["/api/uploads"]!!))
            val candidateIv = decode(sentBody.string("encIv"))
            val originalCiphertext = recordedBodies.entries.single { it.key.startsWith("/media/orig") }.value
            val plaintext = WholeObjectCipher.decrypt(realDek, candidateIv, Aad.of(photoId, ObjectRef.Rendition("r2")), originalCiphertext)
            assertArrayEquals(fileContent, plaintext)
        }

    // ------------------------------------------------------------------
    // skipped: true (a purge tombstone) -- no PUT at all, a local tombstone instead.
    // ------------------------------------------------------------------

    @Test
    fun `skipped -- writes a local tombstone and marks the row done without uploading anything`() =
        runTest {
            connectInstance()
            val queueId = queueRow()
            uploadResponseBody = """{"skipped":true}"""

            val outcome = repository.uploadOne(queueId)

            assertEquals(UploadOutcome.Success, outcome)
            val row = db.uploadQueueDao().getById(queueId)!!
            assertEquals(UploadState.DONE, row.state)
            assertNull(row.photoId)
            assertTrue(db.localTombstoneDao().exists("hmac-sha256:test"))
            assertTrue(recordedBodies.keys.none { it.startsWith("/media/") || it.startsWith("/thumb/") })
        }

    // ------------------------------------------------------------------
    // duplicate: true, status already ready -- no presigned URLs, nothing to upload.
    // ------------------------------------------------------------------

    @Test
    fun `bare duplicate -- marks done without uploading, since there's no presigned URL to use`() =
        runTest {
            connectInstance()
            val queueId = queueRow()
            uploadResponseBody = """{"photoId":"p1","renditionId":"r1","duplicate":true}"""

            val outcome = repository.uploadOne(queueId)

            assertEquals(UploadOutcome.Success, outcome)
            val row = db.uploadQueueDao().getById(queueId)!!
            assertEquals(UploadState.DONE, row.state)
            assertEquals("p1", row.photoId)
        }

    // ------------------------------------------------------------------
    // Failure classification.
    // ------------------------------------------------------------------

    @Test
    fun `a 400 from POST uploads fails permanently rather than retrying forever`() =
        runTest {
            connectInstance()
            val queueId = queueRow()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.path.orEmpty().startsWith("/api/keys")) {
                            MockResponse().setResponseCode(200).setBody(
                                json.encodeToString(
                                    KeysResponse.serializer(),
                                    KeysResponse(listOf(KeyWrapDto(wrapId, "device", "Test device", "mk-1"))),
                                ),
                            )
                        } else {
                            MockResponse().setResponseCode(400).setBody("""{"error":"bad request"}""")
                        }
                }

            val outcome = repository.uploadOne(queueId)

            assertTrue(outcome is UploadOutcome.PermanentFailure)
            assertEquals(UploadState.FAILED, db.uploadQueueDao().getById(queueId)!!.state)
        }

    @Test
    fun `a 500 from POST uploads retries rather than giving up`() =
        runTest {
            connectInstance()
            val queueId = queueRow()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.path.orEmpty().startsWith("/api/keys")) {
                            MockResponse().setResponseCode(200).setBody(
                                json.encodeToString(
                                    KeysResponse.serializer(),
                                    KeysResponse(listOf(KeyWrapDto(wrapId, "device", "Test device", "mk-1"))),
                                ),
                            )
                        } else {
                            MockResponse().setResponseCode(500)
                        }
                }

            val outcome = repository.uploadOne(queueId)

            assertEquals(UploadOutcome.Retry, outcome)
            val row = db.uploadQueueDao().getById(queueId)!!
            assertEquals(UploadState.UPLOADING, row.state) // unchanged -- not FAILED
            assertEquals(1, row.attempts)
        }

    @Test
    fun `a locked master key retries without making any network call at all`() =
        runTest {
            connectInstance()
            val queueId = queueRow()
            masterKeyHolder.clear()

            val outcome = repository.uploadOne(queueId)

            assertEquals(UploadOutcome.Retry, outcome)
            assertEquals(0, server.requestCount)
        }
}

private fun Map<String, JsonElement>.string(key: String): String = getValue(key).jsonPrimitive.content
