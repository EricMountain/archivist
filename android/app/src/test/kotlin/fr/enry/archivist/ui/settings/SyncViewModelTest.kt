package fr.enry.archivist.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.data.local.SyncSettingsStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.UploadQueueEntity
import fr.enry.archivist.data.local.db.UploadState
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.testutil.FakeUploadScheduler
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The "Pause uploads" toggle (2026-09-08, per the user's explicit request) — the two
 * halves live in [SyncSettingsStore] (what every enqueue seam,
 * [fr.enry.archivist.sync.WorkManagerUploadScheduler.enqueueAll], reads) and here (what
 * actively cancels or resumes work the moment the toggle flips, rather than waiting for
 * whatever's already running to notice on its own).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {
    // Same explicit-scheduler reasoning as FoldersViewModelTest's own comment: the
    // no-arg StandardTestDispatcher() detects and reuses whatever Dispatchers.Main
    // already is, which crashes on the first test in the JVM worker to touch it.
    private val dispatcher = StandardTestDispatcher(TestCoroutineScheduler())
    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private lateinit var store: SyncSettingsStore
    private lateinit var uploadScheduler: FakeUploadScheduler
    private lateinit var viewModel: SyncViewModel

    private fun entry(
        localUri: String,
        state: UploadState,
    ) = UploadQueueEntity(
        localUri = localUri,
        displayName = localUri.substringAfterLast('/'),
        folderUri = "content://media/external/images/media",
        contentHash = null,
        state = state,
        plainBytes = null,
        fileMtimeEpochSec = null,
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
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tempDir = Files.createTempDirectory("sync-viewmodel-test").toFile()
        db = buildTestDatabase()
        uploadScheduler = FakeUploadScheduler()
        store = SyncSettingsStore(PreferenceDataStoreFactory.create(produceFile = { File(tempDir, "sync_settings.preferences_pb") }))
        viewModel = SyncViewModel(store = store, uploadQueueDao = db.uploadQueueDao(), uploadScheduler = uploadScheduler)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        tempDir.deleteRecursively()
    }

    // Same bounded real-time poll as FoldersViewModelTest's own awaitState -- a
    // ViewModel-launched Room suspend call (here, getActiveIds()) can resume on
    // Room's own internal executor thread, not this test's dispatcher, so a bare
    // advanceUntilIdle() alone can race it. See android/AGENTS.md's matching entry.
    private fun awaitState(
        timeoutMs: Long = 2000,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            dispatcher.scheduler.advanceUntilIdle()
            if (predicate()) return
            Thread.sleep(5)
        }
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `pausing persists the setting and cancels everything without touching the queue`() =
        runTest(dispatcher) {
            val id = db.uploadQueueDao().insert(entry("content://media/1", UploadState.UPLOADING))

            viewModel.setUploadsPaused(true)
            awaitState { uploadScheduler.cancelAllCallCount > 0 }

            assertTrue(store.settings.first().uploadsPaused)
            assertEquals(1, uploadScheduler.cancelAllCallCount)
            assertEquals(UploadState.UPLOADING, db.uploadQueueDao().getById(id)?.state)
        }

    @Test
    fun `resuming persists the setting and re-enqueues every active row`() =
        runTest(dispatcher) {
            val pending = db.uploadQueueDao().insert(entry("content://media/1", UploadState.PENDING))
            db.uploadQueueDao().insert(entry("content://media/2", UploadState.DONE))
            runBlocking { store.setUploadsPaused(true) }

            viewModel.setUploadsPaused(false)
            awaitState { uploadScheduler.enqueuedCalls.isNotEmpty() }

            assertFalse(store.settings.first().uploadsPaused)
            assertEquals(listOf(listOf(pending)), uploadScheduler.enqueuedCalls)
        }
}
