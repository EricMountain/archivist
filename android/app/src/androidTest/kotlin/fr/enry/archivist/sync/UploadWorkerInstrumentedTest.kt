package fr.enry.archivist.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.enry.archivist.TestEntryPoint
import fr.enry.archivist.crypto.Aad
import fr.enry.archivist.crypto.MasterKey
import fr.enry.archivist.crypto.ObjectRef
import fr.enry.archivist.crypto.WholeObjectCipher
import fr.enry.archivist.data.local.StoredInstance
import fr.enry.archivist.data.local.SyncSettings
import fr.enry.archivist.data.local.db.UploadQueueEntity
import fr.enry.archivist.data.local.db.UploadState
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.data.remote.KeyWrapDto
import fr.enry.archivist.data.remote.KeysResponse
import fr.enry.archivist.testutil.InsertedMedia
import fr.enry.archivist.testutil.MediaStoreFixtures
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plan step 2.10's end-to-end instrumented test: a real [UploadWorker] running inside
 * real WorkManager (Hilt-injected via the app's own `HiltWorkerFactory`, foreground
 * notification and all), driving [fr.enry.archivist.data.repo.UploadRepository] against
 * a real MediaStore file, only the network boundary faked (`MockWebServer`, same
 * technique `UploadRepositoryTest` uses on the JVM). Nothing here needs
 * `hilt-android-testing` — see `TestEntryPoint`'s doc for why.
 *
 * **Safety.** This process is the *real, currently-installed app* — on a device that's
 * previously been enrolled against a real instance, overwriting [fr.enry.archivist.data.local.InstanceStore]'s
 * "current host" pointer or clobbering a *live* unlocked master key would corrupt real
 * session state, not just test state. So: [assumeTrue] refuses to run at all unless the
 * master key is currently locked (nothing live in memory to clobber), a collision-proof
 * host string keeps this test's `InstanceStore`/`EnrolmentStore` entries away from
 * whatever real host is connected, and `@After` restores the real "current host"
 * pointer — the one piece of state here that's actually global rather than per-host.
 */
@RunWith(AndroidJUnit4::class)
class UploadWorkerInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val entryPoint by lazy { TestEntryPoint.from(context) }
    private val json = Json { ignoreUnknownKeys = true }
    private val testHost = "upload-worker-instrumented-test.invalid"
    private val wrapId = "instrumented-test-wrap"
    private val masterKey = MasterKey.of(ByteArray(32) { it.toByte() })

    private lateinit var server: MockWebServer
    private var previousInstance: StoredInstance? = null
    private val inserted = mutableListOf<InsertedMedia>()
    private var queueId: Long? = null

    private val recordedBodies = mutableMapOf<String, ByteArray>()

    @Before
    fun setUp() {
        runBlocking {
            // Refuses to run against a device someone is actively using -- see the
            // class doc's "Safety" note. A locked (post-onTrimMemory, or never
            // unlocked this process) app is the only state this test considers safe.
            assumeTrue(
                "refusing to run: this device's master key is currently unlocked (a live session, not idle) -- see this test's Safety note",
                entryPoint.masterKeyHolder().current.value == null,
            )

            previousInstance = entryPoint.instanceStore().current.first()

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
                                        KeysResponse(listOf(KeyWrapDto(wrapId, "device", "Instrumented test device", "mk-1"))),
                                    ),
                                )
                            else -> MockResponse().setResponseCode(200)
                        }
                    }
                }
            server.start()

            entryPoint.instanceStore().save(
                testHost,
                DiscoveryDocument(
                    apiBase = server.url("/api").toString().trimEnd('/'),
                    region = "eu-west-1",
                    cognito = DiscoveryDocument.CognitoConfig(userPoolId = "eu-west-1_TEST", clientId = "test-client"),
                    cryptoVersion = 1,
                    instanceName = "Instrumented test instance",
                ),
            )
            entryPoint.enrolmentStore().saveDeviceWrapId(testHost, wrapId)
            entryPoint.masterKeyHolder().set(masterKey)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            queueId?.let { entryPoint.uploadQueueDao().deleteById(it) }
            inserted.forEach { MediaStoreFixtures.delete(context, it) }
            if (::server.isInitialized) server.shutdown()

            // The one genuinely global piece of state this test touched -- put it
            // back exactly as found, whether or not a real instance was connected.
            entryPoint.masterKeyHolder().clear()
            entryPoint.hashSecretHolder().clear()
            previousInstance?.let { entryPoint.instanceStore().save(it.host, it.document) }
        }
    }

    @Test
    fun aRealWorkManagerJobUploadsARealFileAndTheServerCanDecryptWhatLanded() =
        runBlocking {
            val media = MediaStoreFixtures.insertJpeg(context, "upload_worker_instrumented_${System.nanoTime()}.jpg")
            inserted += media

            val id =
                entryPoint.uploadQueueDao().insert(
                    UploadQueueEntity(
                        localUri = media.contentUri,
                        displayName = "upload_worker_instrumented.jpg",
                        folderUri = media.bucketId,
                        contentHash = "hmac-sha256:instrumented-test",
                        state = UploadState.PENDING,
                        plainBytes = media.plainBytes.toLong(),
                        fileMtimeEpochSec = System.currentTimeMillis() / 1000,
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
            queueId = id

            // created:true, echoing back whatever candidate photoId/encDek this
            // request sent -- captured from the recorded body, exactly like
            // UploadRepositoryTest's JVM equivalent, since they're random per run.
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
                                        KeysResponse(listOf(KeyWrapDto(wrapId, "device", "Instrumented test device", "mk-1"))),
                                    ),
                                )
                            path == "/api/uploads" -> {
                                val sent = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(String(bodyBytes))
                                val photoId = sent.getValue("photoId").let { it as kotlinx.serialization.json.JsonPrimitive }.content
                                val encDek = sent.getValue("encDek").let { it as kotlinx.serialization.json.JsonPrimitive }.content
                                val body =
                                    """{"photoId":"$photoId","renditionId":"r1","created":true,"encDek":"$encDek","encKeyId":"mk-1",
                                    |"originalUpload":{"url":"${server.url("/media/orig")}"},
                                    |"thumbUploads":{"256":"${server.url("/thumb/256")}","1024":"${server.url("/thumb/1024")}","2048":"${server.url("/thumb/2048")}"}}
                                    """.trimMargin().replace("\n", "")
                                MockResponse().setResponseCode(200).setBody(body)
                            }
                            else -> MockResponse().setResponseCode(200)
                        }
                    }
                }

            UploadWorker.enqueue(context, id, SyncSettings())

            val finalState = awaitTerminalState(id, timeoutMs = 60_000)
            assertEquals(UploadState.DONE, finalState)

            val sentBody = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(String(recordedBodies["/api/uploads"]!!))
            val photoId = (sentBody.getValue("photoId") as kotlinx.serialization.json.JsonPrimitive).content
            val encDek = decode((sentBody.getValue("encDek") as kotlinx.serialization.json.JsonPrimitive).content)
            val encIv = decode((sentBody.getValue("encIv") as kotlinx.serialization.json.JsonPrimitive).content)
            val dek = masterKey.unwrapDek(encDek)

            val originalCiphertext = recordedBodies.entries.single { it.key.startsWith("/media/orig") }.value
            val plaintext = WholeObjectCipher.decrypt(dek, encIv, Aad.of(photoId, ObjectRef.Rendition("r1")), originalCiphertext)
            // A real on-device JPEG round-tripped through the real worker, real
            // WorkManager execution, and real crypto -- the JPEG SOI marker survives
            // the trip, not just "some bytes came back."
            assertTrue("decrypted original should be a non-trivial real JPEG", plaintext.size > 100)
            assertEquals(0xFF.toByte(), plaintext[0])
            assertEquals(0xD8.toByte(), plaintext[1])
        }

    private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)

    private suspend fun awaitTerminalState(
        id: Long,
        timeoutMs: Long,
    ): UploadState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = entryPoint.uploadQueueDao().getById(id)?.state
            if (state == UploadState.DONE || state == UploadState.FAILED) return state
            kotlinx.coroutines.delay(500)
        }
        error("upload_queue row $id never reached a terminal state within ${timeoutMs}ms")
    }
}
