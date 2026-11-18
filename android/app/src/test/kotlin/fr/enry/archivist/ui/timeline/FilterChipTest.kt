package fr.enry.archivist.ui.timeline

import androidx.paging.LoadState
import fr.enry.archivist.ui.search.OverlayMode
import fr.enry.archivist.ui.search.PlaceFilter
import fr.enry.archivist.ui.search.PlaceLevel
import fr.enry.archivist.ui.search.PlaceSuggestion
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Timeline place filter step 5: the chip's text and visibility (F8, F9), and the
 * filtered grid's states. */
class FilterChipTest {
    private fun place(
        id: String,
        name: String,
        n: Int,
    ) = PlaceSuggestion(PlaceLevel.LOCALITY, id, name, "France", n, "FR", name)

    @Test
    fun `chip text for one place, several, and none`() {
        assertEquals("Kyoto · 4", chipLabel(PlaceFilter("kyo", listOf(place("1", "Kyoto", 4)), 4), Locale.ENGLISH))
        assertEquals(
            "paris · 21 places · 1,912",
            chipLabel(PlaceFilter("paris", (1..21).map { place("$it", "P$it", 1) }, 1912), Locale.ENGLISH),
        )
        assertEquals("zzz · no places", chipLabel(PlaceFilter("zzz", emptyList(), 0), Locale.ENGLISH))
    }

    @Test
    fun `the chip shows only with a filter, in A, with the grid at rest`() {
        assertTrue(chipVisible(filterActive = true, OverlayMode.COLLAPSED, scrollActive = false, railHeld = false))
        assertFalse(chipVisible(filterActive = false, OverlayMode.COLLAPSED, scrollActive = false, railHeld = false))
        assertFalse(chipVisible(filterActive = true, OverlayMode.EXPANDED, scrollActive = false, railHeld = false))
        assertFalse(chipVisible(filterActive = true, OverlayMode.SEARCHING, scrollActive = false, railHeld = false))
        // Scrolling, or the bubbles lingering after it (one shared state).
        assertFalse(chipVisible(filterActive = true, OverlayMode.COLLAPSED, scrollActive = true, railHeld = false))
        assertFalse(chipVisible(filterActive = true, OverlayMode.COLLAPSED, scrollActive = false, railHeld = true))
    }

    @Test
    fun `the filtered grid's states`() {
        val loading = LoadState.Loading
        val idle = LoadState.NotLoading(endOfPaginationReached = false)
        val error = LoadState.Error(RuntimeException())
        val some = PlaceFilter("kyo", listOf(place("1", "Kyoto", 4)), 4)
        assertEquals(FilteredContentState.NO_PLACES, filteredContentState(PlaceFilter("zzz", emptyList(), 0), 0, idle, null, true))
        assertEquals(FilteredContentState.LOADING, filteredContentState(some, 0, idle, null, hasStartedLoading = false))
        assertEquals(FilteredContentState.LOADING, filteredContentState(some, 0, idle, loading, true))
        assertEquals(FilteredContentState.ERROR, filteredContentState(some, 0, idle, error, true))
        assertEquals(FilteredContentState.EMPTY, filteredContentState(some, 0, idle, idle, true))
        assertEquals(FilteredContentState.CONTENT, filteredContentState(some, 3, idle, error, true))
    }
}
