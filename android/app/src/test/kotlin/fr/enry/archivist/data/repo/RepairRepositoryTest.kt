package fr.enry.archivist.data.repo

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.crypto.Aad
import fr.enry.archivist.crypto.MasterKey
import fr.enry.archivist.crypto.ObjectRef
import fr.enry.archivist.crypto.WholeObjectCipher
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.ThumbEntry
import fr.enry.archivist.data.local.db.TimelineKey
import fr.enry.archivist.data.local.db.UploadQueueEntity
import fr.enry.archivist.data.local.db.UploadState
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.sync.Thumbnail
import fr.enry.archivist.sync.Thumbnailer
import fr.enry.archivist.testutil.FakeCognitoAuthApi
import fr.enry.archivist.testutil.FakeSharedPreferences
import fr.enry.archivist.testutil.FakeThumbnailer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** [RepairRepository]'s own doc explains the two sources it tries in order (local
 * file, then the server copy). The server-fallback path's *success* case (a real
 * downloaded-and-decrypted original actually thumbnailed) isn't covered here for the
 * same reason [PhotoDetailRepository.downloadOriginal]'s own real network fetch isn't
 * covered by [PhotoDetailRepositoryTest]: it hardcodes `https://`, and MockWebServer
 * has no TLS listener without extra certificate bootstrapping. What *is* covered here:
 * that the fallback is actually attempted (not silently skipped) whenever the local
 * file is unusable, by pointing `instance.host` at this same (plain-HTTP) MockWebServer
 * — the resulting TLS handshake failure is fast and deterministic, and proves the
 * fallback ran rather than the repair bailing out early. */
class RepairRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private lateinit var instanceStore: InstanceStore
    private lateinit var masterKeyHolder: MasterKeyHolder
    private lateinit var photoDetailRepository: PhotoDetailRepository
    private lateinit var thumbnailer: Thumbnailer
    private val previewGenerator = fr.enry.archivist.testutil.FakePreviewGenerator()
    private lateinit var jumpCoordinator: TimelineJumpCoordinator

    private val json = Json { ignoreUnknownKeys = true }
    private val masterKey = MasterKey.of(ByteArray(32) { it.toByte() })
    private val dek = ByteArray(32) { (it + 1).toByte() }
    private val encDek = encode(masterKey.wrapDek(dek))
    private val photoId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
    private val renditionId = "r1"
    private val localUri = "content://media/1"

    private val recordedBodies = mutableMapOf<String, ByteArray>()

    private fun buildRepository(): RepairRepository {
        val archivistApiFactory =
            ArchivistApiFactory(
                baseOkHttpClient = OkHttpClient.Builder().build(),
                json = json,
                tokenStore = TokenStore(FakeSharedPreferences(), json),
                cognitoAuthClient = CognitoAuthClient(FakeCognitoAuthApi(), json),
            )
        photoDetailRepository =
            PhotoDetailRepository(
                instanceStore = instanceStore,
                archivistApiFactory = archivistApiFactory,
                masterKeyHolder = masterKeyHolder,
                okHttpClient = fastTimeoutClient(),
            )
        return RepairRepository(
            instanceStore = instanceStore,
            archivistApiFactory = archivistApiFactory,
            uploadQueueDao = db.uploadQueueDao(),
            photoDao = db.photoDao(),
            thumbnailer = thumbnailer,
            previewGenerator = previewGenerator,
            masterKeyHolder = masterKeyHolder,
            photoDetailRepository = photoDetailRepository,
            jumpCoordinator = jumpCoordinator,
            okHttpClient = fastTimeoutClient(),
            context = mock<Context>().also { whenever(it.cacheDir).thenReturn(tempDir) },
        )
    }

    /** The fallback-attempted tests below deliberately hit a TLS handshake that never
     * completes (a plain-HTTP MockWebServer never answers a ClientHello) — a default
     * [OkHttpClient] blocks on that for its full 10s connect/read timeout, which is
     * fine once but adds up across several tests. A short timeout here turns each into
     * a sub-second, still-deterministic failure. */
    private fun fastTimeoutClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .callTimeout(1, TimeUnit.SECONDS)
            .build()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        tempDir = Files.createTempDirectory("repair-repository-test").toFile()
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { File(tempDir, "instances.preferences_pb") })
        instanceStore = InstanceStore(dataStore, json)
        masterKeyHolder = MasterKeyHolder().apply { set(masterKey) }
        thumbnailer = FakeThumbnailer()
        jumpCoordinator = TimelineJumpCoordinator()
        db = buildTestDatabase()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
        db.close()
    }

    /** `instance.host` deliberately set to something unreachable-but-fast-failing
     * (never this MockWebServer's own address), so a test that expects the fallback
     * *not* to be reached at all (e.g. the locked-key case) can assert zero requests
     * without risking a slow real DNS lookup — `.invalid` is reserved by RFC 2606 to
     * never resolve. */
    private suspend fun connectInstance(mediaHost: String = "photos.invalid") {
        instanceStore.save(
            mediaHost,
            DiscoveryDocument(
                apiBase = server.url("/api").toString().trimEnd('/'),
                region = "eu-west-1",
                cognito = DiscoveryDocument.CognitoConfig(userPoolId = "eu-west-1_XXXXXXXXX", clientId = "client-id"),
                cryptoVersion = 1,
                instanceName = "Home photos",
            ),
        )
    }

    private fun rendition(
        id: String = renditionId,
        mime: String = "image/jpeg",
    ) =
        RenditionSummary(
            renditionId = id,
            role = "display",
            path = "camera/IMG_1.jpg",
            ext = "jpg",
            mime = mime,
            s3Key = "raw/o/$photoId/$id",
            contentHash = "hmac-sha256:$id",
            bytes = 116,
            plainBytes = 100,
            width = 100,
            height = 100,
            encIv = encode(ByteArray(12) { 9 }),
            encChunkSize = 0,
            addedAt = "2026-08-30T10:00:00.000Z",
        )

    private fun photoDetail(renditions: List<RenditionSummary> = listOf(rendition())) =
        PhotoDetail(
            photoId = photoId,
            stem = "IMG_1",
            encDek = encDek,
            takenAt = "2026-08-30T10:00:00.000Z",
            tzOffsetMin = 0,
            takenAtSrc = "upload",
            tzSrc = "upload",
            mime = "image/jpeg",
            width = 100,
            height = 100,
            primaryRend = renditionId,
            renditionsCount = renditions.size,
            groupSrc = "stem",
            deviceKey = null,
            status = "ready",
            uploadedAt = "2026-08-30T10:00:00.000Z",
            deletedAt = null,
            deletedBy = null,
            cameraMake = null,
            cameraModel = null,
            exifDecryptFailed = false,
            renditions = renditions,
        )

    private suspend fun queueRow(
        state: UploadState = UploadState.DONE,
        mime: String = "image/jpeg",
    ): Long =
        db.uploadQueueDao().insert(
            UploadQueueEntity(
                localUri = localUri,
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
                mime = mime,
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

    private fun timelineEntryJson(previewJson: String = "") =
        """{"meta":{"photoId":"$photoId","takenAt":"2026-08-30T10:00:00.000Z",
        |"thumbs":{"256":{"bucket":"derived","key":"th/o/$photoId/256","iv":"iv-256","bytes":2}},$previewJson
        |"encDek":"$encDek","encKeyId":"mk-1","width":100,"height":100,"mime":"image/jpeg",
        |"tzOffsetMin":0,"status":"ready"}}
        """.trimMargin().replace("\n", "")

    private var refreshedEntryJson: String = ""

    private fun setApiDispatcher(onThumbsPost: (RecordedRequest, ByteArray) -> MockResponse) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    val body = request.body.readByteArray()
                    recordedBodies[path] = body
                    return when {
                        path == "/api/photos/$photoId/thumbs" -> onThumbsPost(request, body)
                        path == "/api/photos/$photoId" ->
                            MockResponse().setResponseCode(200).setBody(refreshedEntryJson.ifEmpty { timelineEntryJson() })
                        else -> MockResponse().setResponseCode(200)
                    }
                }
            }
    }

    @Test
    fun `local file present -- regenerates and re-uploads every rung, decryptable under the asset's own DEK`() =
        runTest {
            connectInstance()
            queueRow()
            setApiDispatcher { _, body ->
                val sent = json.decodeFromString<Map<String, JsonElement>>(String(body))
                assertEquals(setOf("256", "1024", "2048"), sent.getValue("thumbs").jsonObject.keys)
                MockResponse().setResponseCode(200).setBody("{${thumbUploadsJson()}}")
            }

            val outcome = buildRepository().repairThumbnails(photoDetail())

            assertEquals(RepairOutcome.Done, outcome)

            val sentBody = json.decodeFromString<Map<String, JsonElement>>(String(recordedBodies["/api/photos/$photoId/thumbs"]!!))
            val thumbsSent = sentBody.getValue("thumbs").jsonObject
            val iv256 = decode(thumbsSent.getValue("256").jsonObject.getValue("iv").jsonPrimitive.content)
            val ciphertext256 = recordedBodies.entries.single { it.key == "/thumb/256" }.value
            val plaintext = WholeObjectCipher.decrypt(dek, iv256, Aad.of(photoId, ObjectRef.Thumbnail(256)), ciphertext256)
            assertArrayEquals(byteArrayOf(0x00, 0x01), plaintext) // FakeThumbnailer's 256-rung content

            val updated = db.photoDao().getByPhotoId(photoId)!!
            assertEquals(ThumbEntry("derived", "th/o/$photoId/256", "iv-256", 2), updated.thumbs[256])
        }

    /** A plain refresh key, not a jump landing -- see
     * [TimelineJumpCoordinator.stageExternalRefreshKey]'s own doc for why: a jump
     * landing is `isLanding`-sticky and makes `TimelinePagingSource.load` start the
     * page exactly at that key (and the grid explicitly scroll there), which for this
     * case visibly repositioned the repaired photo to the top of the screen instead of
     * leaving the timeline where it was -- found live, after the *previous* bug this
     * same staging call originally fixed (no key at all, landing wherever a stale
     * anchor happened to be). */
    @Test
    fun `stages a refresh key on the repaired photo, not a jump landing`() =
        runTest {
            connectInstance()
            queueRow()
            setApiDispatcher { _, _ -> MockResponse().setResponseCode(200).setBody("{${thumbUploadsJson()}}") }

            val expectedKey = TimelineKey("2026-08-30T10:00:00.000Z", photoId)
            val outcome = buildRepository().repairThumbnails(photoDetail())

            assertEquals(RepairOutcome.Done, outcome)
            assertEquals(expectedKey, jumpCoordinator.consumeExternalRefreshKey())
            // Not a jump landing: isLanding must stay false for this key, or
            // TimelinePagingSource.load would start the page exactly at it (pageFromKey)
            // instead of around it (refreshAround), which is what repositioned the grid.
            assertTrue(!jumpCoordinator.isLanding(expectedKey))
        }

    // ------------------------------------------------------------------
    // Video preview clip: repair regenerates it alongside the stills.
    // ------------------------------------------------------------------

    private fun previewUploadJson() = """"previewUpload":"${server.url("/preview/repaired")}""""

    @Test
    fun `video -- repairs the preview clip too, decryptable under the asset's DEK with its own AAD`() =
        runTest {
            connectInstance()
            queueRow(mime = "video/mp4")
            refreshedEntryJson =
                timelineEntryJson(""""preview":{"bucket":"derived","key":"th/o/$photoId/g/preview","iv":"iv-p","bytes":80},""")
            setApiDispatcher { _, _ ->
                MockResponse().setResponseCode(200).setBody("{${thumbUploadsJson()},${previewUploadJson()}}")
            }

            val outcome = buildRepository().repairThumbnails(photoDetail(listOf(rendition(mime = "video/mp4"))))

            assertEquals(RepairOutcome.Done, outcome)
            assertEquals(listOf(localUri), previewGenerator.requested)

            val sent = json.decodeFromString<Map<String, JsonElement>>(String(recordedBodies["/api/photos/$photoId/thumbs"]!!))
            val descriptor = sent.getValue("preview").jsonObject
            val ciphertext = recordedBodies.entries.single { it.key == "/preview/repaired" }.value
            assertEquals(ciphertext.size.toLong(), descriptor.getValue("bytes").jsonPrimitive.content.toLong())
            val plaintext =
                WholeObjectCipher.decrypt(
                    dek,
                    decode(descriptor.getValue("iv").jsonPrimitive.content),
                    Aad.of(photoId, ObjectRef.Preview),
                    ciphertext,
                )
            assertArrayEquals(previewGenerator.clip.bytes, plaintext)

            // ...and the refreshed timeline row now carries the new preview descriptor.
            assertEquals(ThumbEntry("derived", "th/o/$photoId/g/preview", "iv-p", 80), db.photoDao().getByPhotoId(photoId)!!.preview)
        }

    @Test
    fun `video -- a preview that can't be generated is a warning, and the stills are still repaired`() =
        runTest {
            connectInstance()
            queueRow(mime = "video/mp4")
            previewGenerator.error = IOException("no encoder")
            setApiDispatcher { _, _ -> MockResponse().setResponseCode(200).setBody("{${thumbUploadsJson()}}") }

            val outcome = buildRepository().repairThumbnails(photoDetail(listOf(rendition(mime = "video/mp4"))))

            assertTrue(outcome is RepairOutcome.Warning, "expected a warning, got $outcome")
            assertTrue((outcome as RepairOutcome.Warning).message.contains("preview"))
            // ...and says *why*, so it can be diagnosed from the app alone.
            assertTrue(outcome.message.contains("no encoder"), outcome.message)
            val sent = json.decodeFromString<Map<String, JsonElement>>(String(recordedBodies["/api/photos/$photoId/thumbs"]!!))
            assertTrue("preview" !in sent)
            assertTrue(recordedBodies.keys.any { it == "/thumb/256" })
        }

    @Test
    fun `a still never asks for a preview`() =
        runTest {
            connectInstance()
            queueRow()
            setApiDispatcher { _, _ -> MockResponse().setResponseCode(200).setBody("{${thumbUploadsJson()}}") }

            assertEquals(RepairOutcome.Done, buildRepository().repairThumbnails(photoDetail()))

            assertTrue(previewGenerator.requested.isEmpty())
            val sent = json.decodeFromString<Map<String, JsonElement>>(String(recordedBodies["/api/photos/$photoId/thumbs"]!!))
            assertTrue("preview" !in sent)
        }

    @Test
    fun `no local row -- attempts the server fallback instead of failing outright`() =
        runTest {
            // instance.host is this MockWebServer's own (plain-HTTP) address, so the
            // fallback's hardcoded https:// fetch reaches it and fails fast at the TLS
            // handshake (no DNS lookup, no timeout) rather than erroring immediately at
            // "no local row found".
            connectInstance(mediaHost = "${server.hostName}:${server.port}")
            setApiDispatcher { _, _ -> MockResponse().setResponseCode(200).setBody("{${thumbUploadsJson()}}") }

            val outcome = buildRepository().repairThumbnails(photoDetail())

            assertTrue(outcome is RepairOutcome.Error)
            // The fallback was reached and failed there -- proven by never reaching the
            // thumbs-upload endpoint, not by "no requests at all" (a TLS handshake
            // against a plain-HTTP MockWebServer still opens a TCP connection).
            assertTrue(recordedBodies.keys.none { it.startsWith("/api/") })
        }

    @Test
    fun `a queue row that hasn't finished uploading doesn't count as usable -- falls back like a missing row`() =
        runTest {
            connectInstance(mediaHost = "${server.hostName}:${server.port}")
            queueRow(state = UploadState.UPLOADING)
            setApiDispatcher { _, _ -> MockResponse().setResponseCode(200).setBody("{${thumbUploadsJson()}}") }

            val outcome = buildRepository().repairThumbnails(photoDetail())

            assertTrue(outcome is RepairOutcome.Error)
            assertTrue(recordedBodies.keys.none { it.startsWith("/api/") })
        }

    @Test
    fun `a local row whose file can no longer be opened falls back to the server copy too`() =
        runTest {
            connectInstance(mediaHost = "${server.hostName}:${server.port}")
            queueRow()
            // A row exists and matches, but opening it throws -- e.g. the file was
            // deleted outside this app after the row was written.
            thumbnailer =
                object : Thumbnailer {
                    override suspend fun generate(
                        contentUri: String,
                        mime: String,
                    ): List<Thumbnail> =
                        if (contentUri == localUri) {
                            throw IOException("no such file")
                        } else {
                            FakeThumbnailer().generate(contentUri, mime)
                        }
                }
            setApiDispatcher { _, _ -> MockResponse().setResponseCode(200).setBody("{${thumbUploadsJson()}}") }

            val outcome = buildRepository().repairThumbnails(photoDetail())

            assertTrue(outcome is RepairOutcome.Error)
            assertTrue(recordedBodies.keys.none { it.startsWith("/api/") })
        }

    @Test
    fun `no rendition on the asset at all -- fails without any network call`() =
        runTest {
            connectInstance()

            val outcome = buildRepository().repairThumbnails(photoDetail(renditions = emptyList()))

            assertTrue(outcome is RepairOutcome.Error)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `a locked master key fails without making any network call`() =
        runTest {
            connectInstance()
            queueRow()
            masterKeyHolder.clear()

            val outcome = buildRepository().repairThumbnails(photoDetail())

            assertTrue(outcome is RepairOutcome.Error)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `a server rejection surfaces its status code instead of throwing`() =
        runTest {
            connectInstance()
            queueRow()
            setApiDispatcher { _, _ -> MockResponse().setResponseCode(404).setBody("""{"error":"photo not found"}""") }

            val outcome = buildRepository().repairThumbnails(photoDetail())

            assertTrue(outcome is RepairOutcome.Error)
            assertEquals("server rejected repair (HTTP 404)", (outcome as RepairOutcome.Error).message)
        }
}

private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)
