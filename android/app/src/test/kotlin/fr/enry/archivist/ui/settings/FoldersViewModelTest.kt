package fr.enry.archivist.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.data.local.EnrolmentStore
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.repo.EnrolmentRepository
import fr.enry.archivist.data.repo.HashSecretHolder
import fr.enry.archivist.data.repo.MasterKeyHolder
import fr.enry.archivist.sync.Scanner
import fr.enry.archivist.sync.UploadScheduler
import fr.enry.archivist.testutil.FakeCognitoAuthApi
import fr.enry.archivist.testutil.FakeDeviceKeyProvider
import fr.enry.archivist.testutil.FakeMediaStoreSource
import fr.enry.archivist.testutil.FakeSharedPreferences
import fr.enry.archivist.testutil.FakeUploadScheduler
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FoldersViewModelTest {
    // An explicit scheduler, not the no-arg StandardTestDispatcher() -- the no-arg
    // form tries to detect and reuse whatever's already installed as Dispatchers.Main,
    // which means reading Dispatchers.Main before this class's own setUp() has had a
    // chance to install one. The very first such read in the whole test JVM attempts
    // to resolve a *real* Android Main dispatcher as a fallback and crashes with
    // "Looper.getMainLooper() not mocked" -- order-dependent on which test class
    // happens to touch Dispatchers.Main first in a given JVM worker. An explicit
    // scheduler sidesteps the detection entirely.
    private val dispatcher = StandardTestDispatcher(TestCoroutineScheduler())
    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private lateinit var mediaStoreSource: FakeMediaStoreSource
    private lateinit var uploadScheduler: FakeUploadScheduler
    private lateinit var viewModel: FoldersViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tempDir = Files.createTempDirectory("folders-viewmodel-test").toFile()
        db = buildTestDatabase()
        mediaStoreSource = FakeMediaStoreSource()

        val json = Json { ignoreUnknownKeys = true }
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(dispatcher),
                produceFile = { File(tempDir, "instances.preferences_pb") },
            )
        // Both the repository and the scanner share one HashSecretHolder, already
        // populated -- ensureHashSecret's fast path never touches the network, so this
        // repository never needs a real connected instance or server, same reasoning
        // as EnrolmentRepositoryTest's cached-value case.
        val hashSecretHolder = HashSecretHolder().apply { set(ByteArray(32) { it.toByte() }) }
        val enrolmentRepository =
            EnrolmentRepository(
                instanceStore = InstanceStore(dataStore, json),
                archivistApiFactory =
                    ArchivistApiFactory(
                        baseOkHttpClient = OkHttpClient.Builder().build(),
                        json = json,
                        tokenStore = TokenStore(FakeSharedPreferences(), json),
                        cognitoAuthClient = CognitoAuthClient(FakeCognitoAuthApi(), json),
                    ),
                enrolmentStore = EnrolmentStore(FakeSharedPreferences()),
                deviceKeystore = FakeDeviceKeyProvider(),
                masterKeyHolder = MasterKeyHolder(),
                hashSecretHolder = hashSecretHolder,
            )

        val scanner =
            Scanner(
                mediaStoreSource = mediaStoreSource,
                folderSelectionDao = db.folderSelectionDao(),
                uploadQueueDao = db.uploadQueueDao(),
                renditionDao = db.renditionDao(),
                localTombstoneDao = db.localTombstoneDao(),
                hashSecretHolder = hashSecretHolder,
            )

        uploadScheduler = FakeUploadScheduler()
        viewModel =
            FoldersViewModel(
                mediaStoreSource = mediaStoreSource,
                folderSelectionDao = db.folderSelectionDao(),
                scanner = scanner,
                enrolmentRepository = enrolmentRepository,
                uploadQueueDao = db.uploadQueueDao(),
                uploadScheduler = uploadScheduler,
            )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        tempDir.deleteRecursively()
    }

    /** See `EnrolmentRepositoryTest`/`SignInViewModelTest`'s identical helper:
     * `advanceUntilIdle()` alone can't wait for work that resumes on a real thread
     * rather than the virtual test dispatcher — here, Room's Bundled-driver queries,
     * which run on Room's own internal connection-pool executor regardless of the
     * calling coroutine's dispatcher. See `android/AGENTS.md`. */
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
    fun `starts out needing permission`() {
        assertEquals(FoldersUiState.NeedsPermission, viewModel.uiState.value)
    }

    @Test
    fun `granting permission lists device folders, none selected`() =
        runTest(dispatcher) {
            mediaStoreSource.addFile("camera", "Camera", "content://media/1", "IMG_1.jpg", byteArrayOf(1))

            viewModel.onPermissionGranted()
            awaitState { viewModel.uiState.value is FoldersUiState.Loaded }

            val loaded = viewModel.uiState.value as FoldersUiState.Loaded
            assertEquals(1, loaded.folders.size)
            assertEquals("camera", loaded.folders.single().bucketId)
            assertEquals(false, loaded.folders.single().enabled)
        }

    @Test
    fun `selecting a folder queues its unsynced files`() =
        runTest(dispatcher) {
            mediaStoreSource.addFile("camera", "Camera", "content://media/1", "IMG_1.jpg", byteArrayOf(1))
            viewModel.onPermissionGranted()
            awaitState { viewModel.uiState.value is FoldersUiState.Loaded }
            val folder = (viewModel.uiState.value as FoldersUiState.Loaded).folders.single()

            viewModel.setFolderEnabled(folder, true)
            awaitState { (viewModel.uiState.value as? FoldersUiState.Loaded)?.lastScanQueued != null }

            val state = viewModel.uiState.value as FoldersUiState.Loaded
            assertTrue(state.folders.single().enabled)
            assertEquals(1, state.lastScanQueued)
            assertEquals(false, state.isScanning)
            // The newly queued row was handed to the scheduler -- plan step 2.10's
            // worker is what actually acts on it.
            assertEquals(1, uploadScheduler.enqueuedCalls.flatten().size)
        }

    @Test
    fun `deselecting a folder doesn't trigger a scan`() =
        runTest(dispatcher) {
            mediaStoreSource.addFile("camera", "Camera", "content://media/1", "IMG_1.jpg", byteArrayOf(1))
            viewModel.onPermissionGranted()
            awaitState { viewModel.uiState.value is FoldersUiState.Loaded }
            val folder = (viewModel.uiState.value as FoldersUiState.Loaded).folders.single()
            viewModel.setFolderEnabled(folder, true)
            awaitState { (viewModel.uiState.value as? FoldersUiState.Loaded)?.lastScanQueued != null }

            viewModel.setFolderEnabled(folder, false)
            awaitState { (viewModel.uiState.value as? FoldersUiState.Loaded)?.folders?.single()?.enabled == false }

            val state = viewModel.uiState.value as FoldersUiState.Loaded
            assertEquals(false, state.folders.single().enabled)
            // Still 1 from the first (enabling) scan -- disabling didn't scan again,
            // and definitely didn't touch the row already queued.
            assertEquals(1, state.lastScanQueued)
            assertEquals(1, db.uploadQueueDao().observeAll().first().size)
        }
}
