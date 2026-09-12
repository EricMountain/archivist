package fr.enry.archivist.ui.timeline

import androidx.paging.LoadState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Coverage for [timelineContentState] -- the pure decision table behind
 * [TimelineGrid]'s spinner/error/empty/content branching, tested with no Compose
 * harness in the loop (this project has none -- see `android/AGENTS.md`). */
class TimelineContentStateTest {
    @Test
    fun `refresh not started yet shows loading even though refresh already reports NotLoading`() {
        // LazyPagingItems' own initial value before the Flow is first collected --
        // indistinguishable from a verified-empty library by refresh state alone. This
        // is the cold-start "No photos" flash this test guards against.
        assertEquals(
            TimelineContentState.LOADING,
            timelineContentState(itemCount = 0, refresh = LoadState.NotLoading(endOfPaginationReached = false), hasStartedLoading = false),
        )
    }

    @Test
    fun `refresh loading shows loading`() {
        assertEquals(
            TimelineContentState.LOADING,
            timelineContentState(itemCount = 0, refresh = LoadState.Loading, hasStartedLoading = true),
        )
    }

    @Test
    fun `refresh resolved empty after a real load shows empty`() {
        assertEquals(
            TimelineContentState.EMPTY,
            timelineContentState(itemCount = 0, refresh = LoadState.NotLoading(endOfPaginationReached = false), hasStartedLoading = true),
        )
    }

    @Test
    fun `refresh error after a real load shows error`() {
        assertEquals(
            TimelineContentState.ERROR,
            timelineContentState(
                itemCount = 0,
                refresh = LoadState.Error(RuntimeException("boom")),
                hasStartedLoading = true,
            ),
        )
    }

    @Test
    fun `any items present shows content regardless of refresh or hasStartedLoading`() {
        assertEquals(
            TimelineContentState.CONTENT,
            timelineContentState(itemCount = 1, refresh = LoadState.Loading, hasStartedLoading = false),
        )
        assertEquals(
            TimelineContentState.CONTENT,
            timelineContentState(itemCount = 1, refresh = LoadState.Error(RuntimeException("boom")), hasStartedLoading = true),
        )
    }
}
