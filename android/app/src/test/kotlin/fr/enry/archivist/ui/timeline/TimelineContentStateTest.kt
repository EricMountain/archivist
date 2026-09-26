package fr.enry.archivist.ui.timeline

import androidx.paging.LoadState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Coverage for [timelineContentState] -- the pure decision table behind
 * [TimelineGrid]'s spinner/error/empty/content branching, tested with no Compose
 * harness in the loop (this project has none -- see `android/AGENTS.md`). */
class TimelineContentStateTest {
    private val notLoading = LoadState.NotLoading(endOfPaginationReached = false)
    private val error = LoadState.Error(RuntimeException("boom"))

    @Test
    fun `nothing observed yet shows loading even though source already reports NotLoading`() {
        // LazyPagingItems' own initial value before the Flow is first collected --
        // indistinguishable from a verified-empty library by source/mediator state
        // alone. This is the cold-start "No photos" flash this test guards against.
        assertEquals(
            TimelineContentState.LOADING,
            timelineContentState(itemCount = 0, sourceRefresh = notLoading, mediatorRefresh = null, hasStartedLoading = false),
        )
    }

    @Test
    fun `source loading shows loading`() {
        assertEquals(
            TimelineContentState.LOADING,
            timelineContentState(itemCount = 0, sourceRefresh = LoadState.Loading, mediatorRefresh = null, hasStartedLoading = true),
        )
    }

    @Test
    fun `mediator still loading shows loading even though source already resolved to NotLoading`() {
        // The actual bug this test guards against: Room's own local query (source)
        // resolves fast, often to zero rows, before the RemoteMediator's network
        // REFRESH has even reported a state for this LoadType -- CombinedLoadStates'
        // own convenience `refresh` field falls back to source alone whenever
        // mediator == null, which briefly looks exactly like a verified-empty library
        // while the real fetch is still in flight. Checking mediatorRefresh directly
        // (not the combined field) is the fix.
        assertEquals(
            TimelineContentState.LOADING,
            timelineContentState(
                itemCount = 0,
                sourceRefresh = notLoading,
                mediatorRefresh = LoadState.Loading,
                hasStartedLoading = true,
            ),
        )
    }

    @Test
    fun `both source and mediator resolved empty after a real load shows empty`() {
        assertEquals(
            TimelineContentState.EMPTY,
            timelineContentState(itemCount = 0, sourceRefresh = notLoading, mediatorRefresh = notLoading, hasStartedLoading = true),
        )
    }

    @Test
    fun `no mediator present and source resolved empty after a real load shows empty`() {
        assertEquals(
            TimelineContentState.EMPTY,
            timelineContentState(itemCount = 0, sourceRefresh = notLoading, mediatorRefresh = null, hasStartedLoading = true),
        )
    }

    @Test
    fun `source error after a real load shows error`() {
        assertEquals(
            TimelineContentState.ERROR,
            timelineContentState(itemCount = 0, sourceRefresh = error, mediatorRefresh = null, hasStartedLoading = true),
        )
    }

    @Test
    fun `mediator error after a real load shows error even though source resolved to NotLoading`() {
        assertEquals(
            TimelineContentState.ERROR,
            timelineContentState(itemCount = 0, sourceRefresh = notLoading, mediatorRefresh = error, hasStartedLoading = true),
        )
    }

    @Test
    fun `any items present shows content regardless of refresh or hasStartedLoading`() {
        assertEquals(
            TimelineContentState.CONTENT,
            timelineContentState(itemCount = 1, sourceRefresh = LoadState.Loading, mediatorRefresh = LoadState.Loading, hasStartedLoading = false),
        )
        assertEquals(
            TimelineContentState.CONTENT,
            timelineContentState(itemCount = 1, sourceRefresh = error, mediatorRefresh = error, hasStartedLoading = true),
        )
    }
}
