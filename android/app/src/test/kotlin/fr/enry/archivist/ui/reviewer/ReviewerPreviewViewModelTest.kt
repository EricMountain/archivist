package fr.enry.archivist.ui.reviewer

import fr.enry.archivist.testutil.FakeMediaStoreSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewerPreviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val mediaStoreSource = FakeMediaStoreSource()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an empty device reports Empty, not a substitute`() =
        runTest(dispatcher) {
            val viewModel = ReviewerPreviewViewModel(mediaStoreSource)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(ReviewerPreviewUiState.Empty, viewModel.uiState.value)
        }

    @Test
    fun `a device with photos lists them`() =
        runTest(dispatcher) {
            mediaStoreSource.addFile("bucket1", "Camera", "content://1", "older.jpg", byteArrayOf(1))
            mediaStoreSource.addFile("bucket1", "Camera", "content://2", "newer.jpg", byteArrayOf(2))

            val viewModel = ReviewerPreviewViewModel(mediaStoreSource)
            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value as ReviewerPreviewUiState.Loaded
            assertEquals(2, state.files.size)
        }
}
