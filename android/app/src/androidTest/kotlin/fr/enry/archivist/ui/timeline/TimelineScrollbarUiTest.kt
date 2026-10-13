package fr.enry.archivist.ui.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.repo.TimelineBounds
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented Compose UI tests for [TimelineScrollbar]'s fast-scroll gesture — the one
 * part of this file the JVM unit test (`TimelineScrollbarTest`) explicitly documents as
 * "manual-verification-only", since driving a real `PointerInputScope` gesture with
 * actual long-press timing needs a Compose test harness. This module gained one
 * specifically for this; see `android/AGENTS.md`'s Compose UI test entry for why it runs
 * as an ordinary instrumented test (JUnit4/`AndroidJUnit4`-style, real device or the
 * `pixel2api30` managed device) rather than via Robolectric.
 *
 * Deliberately holds still rather than dragging: the magnifier (`lensWarp`/`chaseAnchor`)
 * distorts a *moving* touch's selection in a way that depends on real elapsed wall-clock
 * time between samples — exactly what a JVM unit test can't drive (that's
 * `TimelineScrollbarTest`'s own job, with fabricated nanosecond deltas instead). A press
 * held without moving never engages the lens at all — `lensAnchor` is seeded at the touch
 * point in `onStart` and nothing afterward moves it — so the committed day is a direct,
 * assertable function of the touch position, while still exercising the real
 * `detectFastScrollGesture` pipeline end-to-end: long-press confirmation, `onStart`,
 * `onEnd`, and `settledSample`'s own real-time settle window.
 */
class TimelineScrollbarUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone: ZoneId = ZoneId.systemDefault()
    private val bounds =
        TimelineBounds(
            oldest = Instant.parse("2011-03-02T19:44:10.000Z"),
            newest = Instant.parse("2026-08-02T16:05:33.000Z"),
        )

    private fun setContentWithScrollbar(onCommit: (RailPosition) -> Unit) {
        composeRule.setContent {
            MaterialTheme {
                val gridState = rememberLazyGridState()
                val items = flowOf(PagingData.empty<PhotoEntity>()).collectAsLazyPagingItems()
                // No histogram, so TimelineScrollbar falls back to a TimeScale over
                // [bounds] — the same scale this test computes its own expectation from
                // below, in the viewer's own zone (railScale's own default).
                Box(Modifier.height(400.dp)) {
                    TimelineScrollbar(
                        gridState = gridState,
                        items = items,
                        bounds = bounds,
                        histogram = null,
                        lead = 0,
                        onScrub = {},
                        onCommit = onCommit,
                        modifier = Modifier.testTag(TAG),
                        content = {},
                    )
                }
            }
        }
    }

    /** A finger held still past the long-press threshold commits to exactly the day its
     * own position names — the real end-to-end path a plain unit test can't drive. */
    @Test
    fun longPressHeldStillCommitsTheHeldFractionsDay() {
        var committed: RailPosition? = null
        var commitCount = 0
        setContentWithScrollbar(onCommit = {
            committed = it
            commitCount++
        })

        val fraction = 0.7f
        composeRule.onNodeWithTag(TAG).performTouchInput {
            // 10dp in from the right edge -- inside the touch strip (the rightmost
            // HIT_TARGET_WIDTH of the wider RAIL_WIDTH box this node itself measures),
            // not the transparent, non-interactive margin to its left.
            down(Offset(x = width - 10.dp.toPx(), y = height * fraction))
        }
        // Hold on the test's own virtual clock rather than sleeping: the long-press
        // confirmation is a coroutine timeout, which the compose test dispatcher drives,
        // so advancing the main clock past LONG_PRESS_MS (250ms) deterministically
        // confirms it with no dependence on real device/emulator scheduling.
        // advanceEventTime keeps the injected pointer events' timestamps in step.
        // Release's RELEASE_SETTLE_MS filter reads System.nanoTime(), which this can't
        // move -- harmless here, as with no movement the history holds a single sample
        // and settledSample falls back to it.
        composeRule.mainClock.advanceTimeBy(HOLD_MS)
        composeRule.onNodeWithTag(TAG).performTouchInput {
            advanceEventTime(HOLD_MS)
            up()
        }
        composeRule.waitForIdle()

        val expected = TimeScale(bounds, zone).dayAt(fraction)
        assertEquals(1, commitCount)
        assertEquals(expected, committed?.day)
    }

    /** An ordinary tap — released before the long-press threshold — must resolve as a
     * plain scroll gesture, not a fast-scroll jump: `detectFastScrollGesture` should
     * never call `onStart`/`onEnd` at all, so `onCommit` never fires. */
    @Test
    fun aQuickTapNeverTriggersFastScroll() {
        var commitCount = 0
        setContentWithScrollbar(onCommit = { commitCount++ })

        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(Offset(x = width - 10.dp.toPx(), y = height * 0.5f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals(0, commitCount)
    }

    private companion object {
        const val TAG = "timeline-scrollbar"

        // Comfortably past TimelineScrollbar's private LONG_PRESS_MS (250ms).
        const val HOLD_MS = 600L
    }
}
