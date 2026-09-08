package fr.enry.archivist.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import fr.enry.archivist.TestEntryPoint
import fr.enry.archivist.crypto.MasterKey
import fr.enry.archivist.data.local.StoredInstance
import fr.enry.archivist.data.local.db.UploadQueueEntity
import fr.enry.archivist.data.local.db.UploadState
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.data.remote.KeyWrapDto
import fr.enry.archivist.data.remote.KeysResponse
import fr.enry.archivist.testutil.InsertedMedia
import fr.enry.archivist.testutil.MediaStoreFixtures
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * Regression coverage for the "Pause uploads" toggle, written live against a real
 * device while investigating the user's report that pausing "doesn't work, it's
 * ignored" — exercises the exact same seam the real Settings > Sync toggle does
 * (`TestEntryPoint.uploadScheduler()`/`syncSettingsStore()`), not `UploadWorker`'s raw
 * static functions the way [UploadWorkerInstrumentedTest] does. Same safety pattern as
 * that class — see its own doc.
 *
 * **What this ruled out, live, on a real device (`docs/plans/STATUS.md` has the full
 * account):** cancelling a mid-flight worker (both during the JSON metadata call and
 * during the raw byte `PUT`) and resuming afterward all work correctly. The one real
 * bug this run turned up was upstream of any of that — `UploadRepository.uploadOne`'s
 * broad `catch (e: Exception)` was swallowing the `CancellationException` a pause
 * produces, recording it as an ordinary failed attempt instead of letting it propagate;
 * fixed in that class, not here.
 */
@RunWith(AndroidJUnit4::class)
class UploadPauseInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val entryPoint by lazy { TestEntryPoint.from(context) }
    private val json = Json { ignoreUnknownKeys = true }
    private val testHost = "upload-pause-instrumented-test.invalid"
    private val wrapId = "pause-test-wrap"
    private val masterKey = MasterKey.of(ByteArray(32) { it.toByte() })

    private lateinit var server: MockWebServer
    private var previousInstance: StoredInstance? = null
    private val inserted = mutableListOf<InsertedMedia>()
    private var queueId: Long? = null

    /** Held open until the test releases it -- guarantees the worker is genuinely
     * blocked inside its `POST /uploads` call (not just "enqueued") at the moment
     * pause is toggled, the scenario the user's report actually describes. */
    private val uploadsLatch = CountDownLatch(1)

    /** Same idea, for the second test -- blocks the raw `PUT` of the original's bytes
     * (a plain blocking `OkHttp.execute()`, not a suspend Retrofit call) rather than
     * the JSON metadata call. */
    private val putLatch = CountDownLatch(1)
    private val putReachedLatch = CountDownLatch(1)

    /** true for the first test (blocks in `POST /uploads`), false for the second and
     * third (which either block `/media/orig` instead, via [putShouldBlock], or don't
     * block at all) -- see each test's own setup. */
    @Volatile
    private var uploadsShouldBlock = true

    /** Only the raw-PUT test sets this true. Left false elsewhere so the resume test's
     * second (post-pause) attempt actually completes instead of blocking on a latch
     * nothing in that test ever releases. */
    @Volatile
    private var putShouldBlock = false

    @Before
    fun setUp() {
        runBlocking {
            assumeTrue(
                "refusing to run: this device's master key is currently unlocked (a live session, not idle)",
                entryPoint.masterKeyHolder().current.value == null,
            )

            previousInstance = entryPoint.instanceStore().current.first()
            entryPoint.syncSettingsStore().setUploadsPaused(false)

            server = MockWebServer()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.path.orEmpty()
                        return when {
                            path.startsWith("/api/keys") ->
                                MockResponse().setResponseCode(200).setBody(
                                    json.encodeToString(
                                        KeysResponse.serializer(),
                                        KeysResponse(listOf(KeyWrapDto(wrapId, "device", "Pause test device", "mk-1"))),
                                    ),
                                )
                            path == "/api/settings" ->
                                MockResponse().setResponseCode(200)
                                    .setBody("""{"homeTz":"Etc/UTC","stripLocationOnUpload":false}""")
                            path == "/api/uploads" && !uploadsShouldBlock -> {
                                MockResponse().setResponseCode(200).setBody(
                                    """{"photoId":"unused","renditionId":"r1","created":true,
                                    |"originalUpload":{"url":"${server.url("/media/orig")}"}}
                                    """.trimMargin().replace("\n", ""),
                                )
                            }
                            path == "/api/uploads" -> {
                                // Blocks here until the test lets go -- if a fully
                                // stopped WorkManager job somehow still completes this
                                // call (the swallowed-cancellation theory), the request
                                // will hang until the latch fires, then this response
                                // would land on a worker that should no longer exist.
                                uploadsLatch.await(20, TimeUnit.SECONDS)
                                MockResponse().setResponseCode(200).setBody(
                                    """{"photoId":"unused","renditionId":"r1","created":true,
                                    |"originalUpload":{"url":"${server.url("/media/orig")}"}}
                                    """.trimMargin().replace("\n", ""),
                                )
                            }
                            path == "/media/orig" && putShouldBlock -> {
                                // The raw byte PUT -- a plain blocking OkHttp call in
                                // UploadRepository.put(), not a suspend Retrofit call.
                                putReachedLatch.countDown()
                                putLatch.await(20, TimeUnit.SECONDS)
                                MockResponse().setResponseCode(200)
                            }
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
                    instanceName = "Pause instrumented test instance",
                ),
            )
            entryPoint.enrolmentStore().saveDeviceWrapId(testHost, wrapId)
            entryPoint.masterKeyHolder().set(masterKey)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            uploadsLatch.countDown()
            putLatch.countDown()
            entryPoint.syncSettingsStore().setUploadsPaused(false)
            queueId?.let {
                WorkManager.getInstance(context).cancelUniqueWork("upload-$it")
                entryPoint.uploadQueueDao().deleteById(it)
            }
            inserted.forEach { MediaStoreFixtures.delete(context, it) }
            if (::server.isInitialized) server.shutdown()
            entryPoint.masterKeyHolder().clear()
            entryPoint.hashSecretHolder().clear()
            previousInstance?.let { entryPoint.instanceStore().save(it.host, it.document) }
        }
    }

    @Test
    fun pausingWhileAnUploadIsMidFlightActuallyStopsIt() =
        runBlocking {
            val media = MediaStoreFixtures.insertJpeg(context, "upload_pause_instrumented_${System.nanoTime()}.jpg")
            inserted += media

            val id =
                entryPoint.uploadQueueDao().insert(
                    UploadQueueEntity(
                        localUri = media.contentUri,
                        displayName = "upload_pause_instrumented.jpg",
                        folderUri = media.bucketId,
                        contentHash = "hmac-sha256:pause-instrumented-test",
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
                        createdAt = "2026-09-08T00:00:00.000Z",
                        updatedAt = "2026-09-08T00:00:00.000Z",
                    ),
                )
            queueId = id

            entryPoint.uploadScheduler().enqueueAll(listOf(id))

            // Give the worker time to reach the blocked POST /uploads call -- polling
            // for UploadState.UPLOADING (set before the network call) rather than a
            // fixed sleep.
            awaitState(15_000) { entryPoint.uploadQueueDao().getById(id)?.state == UploadState.UPLOADING }
            val rowBeforePause = entryPoint.uploadQueueDao().getById(id)
            assertEquals("worker never reached UPLOADING before pause -- test setup is broken, not the feature", UploadState.UPLOADING, rowBeforePause?.state)

            // The action under test: exactly what SyncViewModel.setUploadsPaused(true) does.
            entryPoint.syncSettingsStore().setUploadsPaused(true)
            entryPoint.uploadScheduler().cancelAll()

            val info = awaitWorkInfo(id, timeoutMs = 10_000)
            assertEquals("cancelAll() did not cancel the mid-flight worker", WorkInfo.State.CANCELLED, info?.state)

            // Real regression check for the "pause is ignored" report: while still
            // paused, ask to enqueue again (exactly what a fresh scan/app-startup
            // re-enqueue would do) and confirm no new work actually starts.
            entryPoint.uploadScheduler().enqueueAll(listOf(id))
            Thread.sleep(1_000)
            val infoAfterReenqueueAttempt = WorkManager.getInstance(context).getWorkInfosForUniqueWork("upload-$id").get().firstOrNull()
            assertTrue(
                "enqueueAll() started new work for a paused row -- the pause gate is not holding",
                infoAfterReenqueueAttempt == null || infoAfterReenqueueAttempt.state == WorkInfo.State.CANCELLED,
            )

            // Never let the row reach DONE while paused -- the mock server's blocked
            // handler releasing the latch on countDown() in tearDown() must not race a
            // still-alive worker into completing after this assertion.
            assertEquals(UploadState.UPLOADING, entryPoint.uploadQueueDao().getById(id)?.state)
        }

    /**
     * The scenario a real user actually triggers pause during: not the small JSON
     * metadata call (already covered above, and already a suspend-cancellable Retrofit
     * call), but the raw byte transfer -- `UploadRepository.put()` is a plain blocking
     * `OkHttpClient.newCall(request).execute()`, not wrapped in anything that makes it
     * respond to coroutine cancellation. If cancelling the WorkManager job doesn't
     * actually abort that blocking call, a real large-photo/video upload already
     * mid-transfer keeps running to completion no matter how many times "Pause
     * uploads" is toggled -- which is exactly what "it's ignored" would look like from
     * the outside.
     */
    @Test
    fun pausingDuringTheRawByteTransferDoesNotStopTheTransfer() =
        runBlocking {
            uploadsShouldBlock = false
            putShouldBlock = true
            val media = MediaStoreFixtures.insertJpeg(context, "upload_pause_instrumented_put_${System.nanoTime()}.jpg")
            inserted += media

            val id =
                entryPoint.uploadQueueDao().insert(
                    UploadQueueEntity(
                        localUri = media.contentUri,
                        displayName = "upload_pause_instrumented_put.jpg",
                        folderUri = media.bucketId,
                        contentHash = "hmac-sha256:pause-instrumented-put-test",
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
                        createdAt = "2026-09-08T00:00:00.000Z",
                        updatedAt = "2026-09-08T00:00:00.000Z",
                    ),
                )
            queueId = id

            entryPoint.uploadScheduler().enqueueAll(listOf(id))

            assertTrue(
                "worker never reached the raw PUT -- test setup is broken, not the feature",
                putReachedLatch.await(15, TimeUnit.SECONDS),
            )

            entryPoint.syncSettingsStore().setUploadsPaused(true)
            entryPoint.uploadScheduler().cancelAll()

            // Give WorkManager every reasonable chance to reflect the cancellation --
            // real "Pause uploads" usage would consider several seconds of a large
            // transfer continuing regardless as the bug, not a false positive.
            val info = awaitWorkInfo(id, timeoutMs = 5_000)
            assertEquals(
                "cancelAll() did not stop a worker blocked inside the raw byte PUT -- " +
                    "the transfer keeps running regardless of pause, matching the user's report",
                WorkInfo.State.CANCELLED,
                info?.state,
            )
        }

    /**
     * The other half of the cycle: does turning "Pause uploads" back off actually
     * restart a row whose WorkManager job was cancelled while paused? `enqueueUniqueWork`
     * with `ExistingWorkPolicy.KEEP` (what [fr.enry.archivist.sync.UploadWorker.enqueue]
     * uses) is documented as "do nothing if unfinished work already exists under this
     * name" -- if WorkManager's bookkeeping treats a just-cancelled-but-not-yet-pruned
     * unique work as still "existing" for this check, resuming would silently no-op
     * forever, which from the user's side looks identical to "pausing broke uploads
     * for good," not just "pausing did nothing."
     */
    @Test
    fun resumingAfterACancelledPauseActuallyRestartsTheUpload() =
        runBlocking {
            val media = MediaStoreFixtures.insertJpeg(context, "upload_pause_instrumented_resume_${System.nanoTime()}.jpg")
            inserted += media

            val id =
                entryPoint.uploadQueueDao().insert(
                    UploadQueueEntity(
                        localUri = media.contentUri,
                        displayName = "upload_pause_instrumented_resume.jpg",
                        folderUri = media.bucketId,
                        contentHash = "hmac-sha256:pause-instrumented-resume-test",
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
                        createdAt = "2026-09-08T00:00:00.000Z",
                        updatedAt = "2026-09-08T00:00:00.000Z",
                    ),
                )
            queueId = id

            entryPoint.uploadScheduler().enqueueAll(listOf(id))
            awaitState(15_000) { entryPoint.uploadQueueDao().getById(id)?.state == UploadState.UPLOADING }

            entryPoint.syncSettingsStore().setUploadsPaused(true)
            entryPoint.uploadScheduler().cancelAll()
            val cancelled = awaitWorkInfo(id, timeoutMs = 10_000)
            assertEquals("setup: pause didn't cancel the job", WorkInfo.State.CANCELLED, cancelled?.state)

            // Switch the dispatcher to the non-blocking branch for the resumed attempt
            // -- countDown() alone would also unblock the *old*, already-cancelled
            // worker's still-pending dispatch() call at the same moment, and racing
            // that zombie response against the new attempt's on the same MockWebServer
            // would muddy which one this test is actually observing.
            uploadsShouldBlock = false
            uploadsLatch.countDown()

            entryPoint.syncSettingsStore().setUploadsPaused(false)
            entryPoint.uploadScheduler().enqueueAll(listOf(id))

            val finalState = awaitTerminalState(id, timeoutMs = 15_000)
            assertEquals(
                "resuming after a paused-and-cancelled row did not actually restart the upload " +
                    "-- likely WorkManager's ExistingWorkPolicy.KEEP treating the cancelled work as still present",
                UploadState.DONE,
                finalState,
            )
        }

    private suspend fun awaitTerminalState(
        id: Long,
        timeoutMs: Long,
    ): UploadState? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = entryPoint.uploadQueueDao().getById(id)?.state
            if (state == UploadState.DONE || state == UploadState.FAILED) return state
            kotlinx.coroutines.delay(100)
        }
        return entryPoint.uploadQueueDao().getById(id)?.state
    }

    private suspend fun awaitState(
        timeoutMs: Long,
        predicate: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            kotlinx.coroutines.delay(50)
        }
    }

    private suspend fun awaitWorkInfo(
        id: Long,
        timeoutMs: Long,
    ): WorkInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: WorkInfo? = null
        while (System.currentTimeMillis() < deadline) {
            last = WorkManager.getInstance(context).getWorkInfosForUniqueWork("upload-$id").get().firstOrNull()
            if (last?.state?.isFinished == true) return last
            kotlinx.coroutines.delay(100)
        }
        return last
    }
}
