package fr.enry.archivist.ui.search

import fr.enry.archivist.data.remote.PlaceCountryDto
import fr.enry.archivist.data.remote.PlaceLocalityDto
import fr.enry.archivist.data.remote.PlaceRegionDto
import fr.enry.archivist.data.remote.PlacesResponse
import fr.enry.archivist.data.repo.PlaceRepository
import fr.enry.archivist.ui.search.OverlayEffect.APPLY_NOW
import fr.enry.archivist.ui.search.OverlayEffect.CLEAR_FILTER
import fr.enry.archivist.ui.search.OverlayEffect.FOCUS_FIELD
import fr.enry.archivist.ui.search.OverlayEffect.HIDE_KEYBOARD
import fr.enry.archivist.ui.search.OverlayEffect.OPEN_SETTINGS
import fr.enry.archivist.ui.search.OverlayEffect.REVALIDATE
import fr.enry.archivist.ui.search.OverlayMode.COLLAPSED
import fr.enry.archivist.ui.search.OverlayMode.EXPANDED
import fr.enry.archivist.ui.search.OverlayMode.SEARCHING
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever

private val vocabulary =
    PlacesResponse(
        countries = listOf(PlaceCountryDto("FR", 20), PlaceCountryDto("US", 8)),
        regions = listOf(PlaceRegionDto("US.TX", "Texas", "US", 8)),
        localities =
        listOf(
            PlaceLocalityDto("1", "Paris", null, "FR", 12),
            PlaceLocalityDto("2", "Paris", "Texas", "US", 8),
        ),
    )

/**
 * Timeline place filter step 4: the `⋮` menu's states and the plan's "Back and dismissing"
 * table, one test per row ([reduceOverlay], no Compose), plus [PlaceSearchViewModel]'s
 * selection and vocabulary status.
 */
class PlaceSearchStateTest {
    private fun t(
        mode: OverlayMode,
        effects: Set<OverlayEffect> = emptySet(),
        consumed: Boolean = true,
    ) = OverlayTransition(mode, effects, consumed)

    @Test
    fun `tapping the menu button expands the card, and again collapses it`() {
        assertEquals(t(EXPANDED), reduceOverlay(COLLAPSED, false, OverlayEvent.MenuButton))
        assertEquals(t(COLLAPSED), reduceOverlay(EXPANDED, false, OverlayEvent.MenuButton))
    }

    @Test
    fun `tapping the field in B starts searching, focused, and revalidates the vocabulary`() {
        assertEquals(t(SEARCHING, setOf(FOCUS_FIELD, REVALIDATE)), reduceOverlay(EXPANDED, false, OverlayEvent.FieldTapped))
        // The field gaining focus again inside C changes nothing.
        assertEquals(t(SEARCHING, consumed = false), reduceOverlay(SEARCHING, true, OverlayEvent.FieldTapped))
    }

    @Test
    fun `C with the keyboard open - Back is the keyboard's, and IME Search applies at once and goes to A`() {
        val back = reduceOverlay(SEARCHING, true, OverlayEvent.Back(keyboardOpen = true))
        assertEquals(SEARCHING, back.mode)
        assertFalse(back.consumed)
        assertFalse(CLEAR_FILTER in back.effects)
        assertEquals(t(COLLAPSED, setOf(APPLY_NOW, HIDE_KEYBOARD)), reduceOverlay(SEARCHING, true, OverlayEvent.ImeSearch))
    }

    @Test
    fun `C with the keyboard closed - Back goes to A and keeps the filter`() {
        val back = reduceOverlay(SEARCHING, true, OverlayEvent.Back(keyboardOpen = false))
        assertEquals(t(COLLAPSED, setOf(HIDE_KEYBOARD)), back)
        assertFalse(CLEAR_FILTER in back.effects)
    }

    @Test
    fun `a tap on the grid in C dismisses to A, is consumed so no photo opens, and keeps the filter`() {
        val tap = reduceOverlay(SEARCHING, true, OverlayEvent.GridTap)
        assertEquals(COLLAPSED, tap.mode)
        assertTrue(tap.consumed)
        assertFalse(CLEAR_FILTER in tap.effects)
        assertFalse(APPLY_NOW in tap.effects)
    }

    @Test
    fun `B - Back or an outside tap collapses to A, consumed`() {
        assertEquals(t(COLLAPSED), reduceOverlay(EXPANDED, false, OverlayEvent.Back(keyboardOpen = false)))
        assertEquals(t(COLLAPSED), reduceOverlay(EXPANDED, true, OverlayEvent.OutsideTap))
        assertTrue(overlayHandlesBack(EXPANDED, filterActive = false))
    }

    @Test
    fun `A with a filter - Back clears it`() {
        assertTrue(overlayHandlesBack(COLLAPSED, filterActive = true))
        assertEquals(t(COLLAPSED, setOf(CLEAR_FILTER)), reduceOverlay(COLLAPSED, true, OverlayEvent.Back(keyboardOpen = false)))
    }

    @Test
    fun `A with no filter - Back is not handled, so it leaves the app`() {
        assertFalse(overlayHandlesBack(COLLAPSED, filterActive = false))
        assertFalse(reduceOverlay(COLLAPSED, false, OverlayEvent.Back(keyboardOpen = false)).consumed)
    }

    @Test
    fun `the chip - its body reopens C, its cross clears`() {
        assertEquals(t(SEARCHING, setOf(FOCUS_FIELD, REVALIDATE)), reduceOverlay(COLLAPSED, true, OverlayEvent.ChipBody))
        assertEquals(t(COLLAPSED, setOf(CLEAR_FILTER)), reduceOverlay(COLLAPSED, true, OverlayEvent.ChipClear))
        // No filter, no chip: nothing to reopen or clear.
        assertFalse(reduceOverlay(COLLAPSED, false, OverlayEvent.ChipBody).consumed)
    }

    @Test
    fun `Settings in B collapses the card and opens Settings`() {
        assertEquals(t(COLLAPSED, setOf(OPEN_SETTINGS)), reduceOverlay(EXPANDED, false, OverlayEvent.SettingsPicked))
    }

    @Test
    fun `the vocabulary status`() {
        assertEquals(VocabularyStatus.LOADING, vocabularyStatus(loaded = false, cached = false, syncing = false, failed = false))
        assertEquals(VocabularyStatus.LOADING, vocabularyStatus(loaded = true, cached = false, syncing = true, failed = false))
        assertEquals(VocabularyStatus.FAILED, vocabularyStatus(loaded = true, cached = false, syncing = false, failed = true))
        assertEquals(VocabularyStatus.READY, vocabularyStatus(loaded = true, cached = true, syncing = false, failed = true))
    }

    @Test
    fun `the status line counts the maximal places and their photos`() {
        val paris = SelectionState.initial("paris", selectionTreeFor(vocabulary, "paris", Locale.ENGLISH))
        assertEquals("20 photos in 2 places", selectionStatusLine(paris, Locale.ENGLISH))
        assertEquals("12 photos in 1 place", selectionStatusLine(paris.toggle("PLACE#2"), Locale.ENGLISH))
        assertEquals("No places selected", selectionStatusLine(paris.toggle("PLACE#2").toggle("PLACE#1"), Locale.ENGLISH))
        assertNull(selectionStatusLine(SelectionState.initial("zzz", selectionTreeFor(vocabulary, "zzz", Locale.ENGLISH)), Locale.ENGLISH))
        assertNull(selectionStatusLine(SelectionState.initial("", selectionTreeFor(vocabulary, "", Locale.ENGLISH)), Locale.ENGLISH))
    }

}

/** [PlaceSearchViewModel]: the selection outlives the overlay, a revalidation re-matches. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSearchViewModelTest {
        private val dispatcher = StandardTestDispatcher()
        private val cache = MutableStateFlow<PlacesResponse?>(null)
        private lateinit var repo: PlaceRepository

        @BeforeEach
        fun setUp() {
            Dispatchers.setMain(dispatcher)
            repo =
                mock<PlaceRepository>().also {
                    whenever(it.vocabulary()).thenReturn(cache)
                    it.stub { onBlocking { syncVocabulary() } doReturn Unit }
                }
        }

        @AfterEach
        fun tearDown() = Dispatchers.resetMain()

        @Test
        fun `reopening keeps the query and ticks, a reset starts over, and a revalidated vocabulary keeps the ticks`() =
            runTest(dispatcher) {
                cache.value = vocabulary
                val vm = PlaceSearchViewModel(repo)
                advanceUntilIdle()
                vm.onQueryChange("paris")
                vm.toggle("PLACE#2")
                assertEquals(listOf("PLACE#1"), vm.selection.value.maximal.map { it.key })

                // A vocabulary that arrives again (a revalidation) re-matches, keeping ticks.
                cache.value = vocabulary.copy(countries = listOf(PlaceCountryDto("FR", 21), PlaceCountryDto("US", 8)))
                advanceUntilIdle()
                // Nothing about leaving and reopening the overlay touches the state.
                assertEquals("paris", vm.selection.value.query)
                assertEquals(listOf("PLACE#1"), vm.selection.value.maximal.map { it.key })

                vm.reset()
                assertEquals("", vm.selection.value.query)
                assertNull(vm.selection.value.toFilter())
            }
    }
