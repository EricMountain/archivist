package fr.enry.archivist.ui.detail

import android.content.Context
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.remote.PlaceDto
import fr.enry.archivist.data.remote.PlaceRefDto
import fr.enry.archivist.data.repo.DeleteRepository
import fr.enry.archivist.data.repo.LocationUpdateOutcome
import fr.enry.archivist.data.repo.LocationUpdateRepository
import fr.enry.archivist.data.repo.PhotoDetail
import fr.enry.archivist.data.repo.PhotoDetailRepository
import fr.enry.archivist.data.repo.PhotoRepository
import fr.enry.archivist.data.repo.PlaceFilterRepository
import fr.enry.archivist.data.repo.RepairRepository
import fr.enry.archivist.data.repo.RotateRepository
import fr.enry.archivist.data.repo.TakenAtRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever

/** Geolocation plan step 9: [DetailViewModel]'s "Update location" state transitions, over a
 * mocked [LocationUpdateRepository] (whose own behaviour [LocationUpdateRepositoryTest]
 * covers). No Room or network: every other dependency is an unstubbed mock. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelLocationTest {
    private val dispatcher = StandardTestDispatcher()
    private val locationRepo = mock<LocationUpdateRepository>()

    private fun viewModel(): DetailViewModel {
        val photoRepository = mock<PhotoRepository>().also { whenever(it.observeTimeline()).thenReturn(flowOf(emptyList())) }
        val instanceStore = mock<InstanceStore>().also { whenever(it.current).thenReturn(flowOf(null)) }
        return DetailViewModel(
            photoRepository = photoRepository,
            photoDetailRepository = mock<PhotoDetailRepository>(),
            deleteRepository = mock<DeleteRepository>(),
            repairRepository = mock<RepairRepository>(),
            takenAtRepository = mock<TakenAtRepository>(),
            rotateRepository = mock<RotateRepository>(),
            locationUpdateRepository = locationRepo,
            placeFilterRepository = mock<PlaceFilterRepository>(),
            instanceStore = instanceStore,
            context = mock<Context>(),
        )
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val detail =
        PhotoDetail(
            photoId = "p1",
            stem = "IMG_1",
            encDek = "dek",
            takenAt = "2026-08-30T10:00:00.000Z",
            tzOffsetMin = 0,
            takenAtSrc = "exif",
            tzSrc = "exif-offset",
            mime = "image/jpeg",
            width = 1,
            height = 1,
            primaryRend = "r1",
            renditionsCount = 1,
            groupSrc = "stem",
            deviceKey = null,
            status = "ready",
            uploadedAt = "2026-08-30T10:00:00.000Z",
            deletedAt = null,
            deletedBy = null,
            cameraMake = null,
            cameraModel = null,
            exifDecryptFailed = false,
            renditions = emptyList(),
        )

    private val located =
        detail.copy(place = PlaceDto(cc = "JP", locality = PlaceRefDto("1857910", "Kyoto"), src = "test"))

    @Test
    fun `Idle, InProgress, then Updated with the refreshed detail swapped in`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            locationRepo.stub {
                onBlocking { updateLocation(any()) } doSuspendableAnswer {
                    gate.await()
                    LocationUpdateOutcome.Updated(located)
                }
            }
            val vm = viewModel()
            assertEquals(LocationUiState.Idle, vm.locationState.value)

            vm.updateLocation(detail)
            assertEquals(LocationUiState.InProgress, vm.locationState.value)
            advanceUntilIdle()
            assertEquals(LocationUiState.InProgress, vm.locationState.value)

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(LocationUiState.Updated, vm.locationState.value)
            assertEquals(PhotoDetailUiState.Loaded(located), vm.details.value["p1"])

            vm.dismissLocation()
            assertEquals(LocationUiState.Idle, vm.locationState.value)
        }

    @Test
    fun `Updated with no place is NoPlaceNearby, the coordinates-only detail swapped in`() =
        runTest(dispatcher) {
            locationRepo.stub { onBlocking { updateLocation(any()) } doReturn LocationUpdateOutcome.Updated(detail) }
            val vm = viewModel()

            vm.updateLocation(detail)
            advanceUntilIdle()
            assertEquals(LocationUiState.NoPlaceNearby, vm.locationState.value)
            assertEquals(PhotoDetailUiState.Loaded(detail), vm.details.value["p1"])
        }

    @Test
    fun `NoLocation also swaps in the refreshed detail`() =
        runTest(dispatcher) {
            locationRepo.stub { onBlocking { updateLocation(any()) } doReturn LocationUpdateOutcome.NoLocation(detail) }
            val vm = viewModel()

            vm.updateLocation(located)
            advanceUntilIdle()

            assertEquals(LocationUiState.NoLocation, vm.locationState.value)
            assertEquals(PhotoDetailUiState.Loaded(detail), vm.details.value["p1"])
        }

    @Test
    fun `an error leaves the cached detail alone`() =
        runTest(dispatcher) {
            locationRepo.stub { onBlocking { updateLocation(any()) } doReturn LocationUpdateOutcome.Error("HTTP 500") }
            val vm = viewModel()

            vm.updateLocation(detail)
            advanceUntilIdle()

            assertEquals(LocationUiState.Error("HTTP 500"), vm.locationState.value)
            assertTrue(vm.details.value.isEmpty())
        }

    @Test
    fun `the action stays hidden until the setting is read, and while strip is on`() =
        runTest(dispatcher) {
            locationRepo.stub { onBlocking { isAvailable() } doReturn false }
            val vm = viewModel()
            assertFalse(vm.locationAvailable.value)

            vm.refreshLocationAvailability()
            advanceUntilIdle()
            assertFalse(vm.locationAvailable.value)

            locationRepo.stub { onBlocking { isAvailable() } doReturn true }
            vm.refreshLocationAvailability()
            advanceUntilIdle()
            assertTrue(vm.locationAvailable.value)
        }
}
