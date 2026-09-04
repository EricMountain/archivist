package fr.enry.archivist.ui.queue

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.data.local.SyncSettingsStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.UploadQueueEntity
import fr.enry.archivist.data.local.db.UploadState
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.sync.DeviceState
import fr.enry.archivist.sync.QueueIdleReason
import fr.enry.archivist.testutil.FakeDeviceStateMonitor
import fr.enry.archivist.testutil.FakeUploadScheduler
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [QueueViewModel.uiState] is a `combine(...).stateIn(viewModelScope, WhileSubscribed(...))`
 * over a Room `Flow` — it only starts collecting once something subscribes, and Room's
 * own query executor resumes that collection on a real thread the test dispatcher
 * doesn't control. Every test here launches a `backgroundScope` collector to hold the
 * subscription open, then polls `.value` the same bounded real-time way
 * `FoldersViewModelTest.awaitState` does (`advanceUntilIdle()` + a short real sleep) —
 * see `android/AGENTS.md`'s "A Room suspend DAO call can resume on a real thread" entry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModelTest {
    private val dispatcher = StandardTestDispatcher(TestCoroutineScheduler())
    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private lateinit var deviceStateMonitor: FakeDeviceStateMonitor
    private lateinit var uploadScheduler: FakeUploadScheduler
    private lateinit var viewModel: QueueViewModel

    private fun entry(
        localUri: String,
        displayName: String = localUri.substringAfterLast('/'),
        state: UploadState = UploadState.PENDING,
        attempts: Int = 0,
        lastError: String? = null,
    ) = UploadQueueEntity(
        localUri = localUri,
        displayName = displayName,
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
        attempts = attempts,
        lastError = lastError,
        createdAt = "2026-09-05T00:00:00.000Z",
        updatedAt = "2026-09-05T00:00:00.000Z",
    )

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

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tempDir = Files.createTempDirectory("queue-viewmodel-test").toFile()
        db = buildTestDatabase()
        deviceStateMonitor = FakeDeviceStateMonitor()
        uploadScheduler = FakeUploadScheduler()
        val syncSettingsStore =
            SyncSettingsStore(PreferenceDataStoreFactory.create(produceFile = { File(tempDir, "sync_settings.preferences_pb") }))
        viewModel =
            QueueViewModel(
                uploadQueueDao = db.uploadQueueDao(),
                syncSettingsStore = syncSettingsStore,
                deviceStateMonitor = deviceStateMonitor,
                uploadScheduler = uploadScheduler,
            )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `DONE rows never appear in the queue`() =
        runTest(dispatcher) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            db.uploadQueueDao().insert(entry("content://media/1", state = UploadState.PENDING))
            db.uploadQueueDao().insert(entry("content://media/2", state = UploadState.DONE))

            awaitState { viewModel.uiState.value.items.isNotEmpty() }

            assertEquals(listOf("1"), viewModel.uiState.value.items.map { it.displayName })
        }

    @Test
    fun `a failed row carries its error and no idle reason is shown once everything is failed`() =
        runTest(dispatcher) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            deviceStateMonitor.set(DeviceState(isConnected = false, isMetered = true, isCharging = false, isBatteryLow = false))
            db.uploadQueueDao().insert(entry("content://media/1", state = UploadState.FAILED, attempts = 3, lastError = "HTTP 400"))

            awaitState { viewModel.uiState.value.items.isNotEmpty() }

            val state = viewModel.uiState.value
            assertEquals(UploadState.FAILED, state.items.single().state)
            assertEquals("HTTP 400", state.items.single().lastError)
            assertEquals(QueueIdleReason.NONE, state.idleReason)
        }

    @Test
    fun `an active row surfaces the live idle reason`() =
        runTest(dispatcher) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            deviceStateMonitor.set(DeviceState(isConnected = true, isMetered = true, isCharging = false, isBatteryLow = false))
            db.uploadQueueDao().insert(entry("content://media/1", state = UploadState.PENDING))

            awaitState { viewModel.uiState.value.idleReason != QueueIdleReason.NONE }

            assertEquals(QueueIdleReason.WAITING_FOR_WIFI, viewModel.uiState.value.idleReason)
        }

    @Test
    fun `retry resets a failed row to PENDING and re-enqueues it`() =
        runTest(dispatcher) {
            val id = db.uploadQueueDao().insert(entry("content://media/1", state = UploadState.FAILED, lastError = "server error"))

            viewModel.retry(id)
            awaitState { uploadScheduler.enqueuedCalls.isNotEmpty() }

            val row = db.uploadQueueDao().getById(id)
            assertEquals(UploadState.PENDING, row?.state)
            assertNull(row?.lastError)
            assertEquals(listOf(id), uploadScheduler.enqueuedCalls.single())
        }

    @Test
    fun `cancel removes the row and cancels its work`() =
        runTest(dispatcher) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            val id = db.uploadQueueDao().insert(entry("content://media/1", state = UploadState.UPLOADING))
            awaitState { viewModel.uiState.value.items.isNotEmpty() }

            viewModel.cancel(id)
            awaitState { viewModel.uiState.value.items.isEmpty() }

            assertEquals(listOf(id), uploadScheduler.cancelledIds)
            assertNull(db.uploadQueueDao().getById(id))
        }
}
