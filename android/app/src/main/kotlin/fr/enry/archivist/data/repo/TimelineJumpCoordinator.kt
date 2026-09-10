package fr.enry.archivist.data.repo

import javax.inject.Inject
import javax.inject.Singleton

/**
 * One flag, shared between [TimelineRemoteMediator] (which sets it) and
 * [TimelinePagingSource] (which consumes it): the cache was just replaced wholesale, so
 * the next generation must land on the new window's top rather than re-anchor on the
 * previous generation's scroll position.
 *
 * In-memory only, and deliberately not a `StateFlow` — it's consumed exactly once, by
 * the one `getRefreshKey` call that follows the reseed's own write, and a replayed value
 * would wrongly pin a later, unrelated refresh to the top of the list.
 */
@Singleton
class TimelineJumpCoordinator
    @Inject
    constructor() {
        @Volatile private var justReset: Boolean = false

        /** Called by [TimelineRemoteMediator.reseedAt] immediately before its write. */
        fun markJustReset() {
            justReset = true
        }

        /** [TimelinePagingSource.getRefreshKey]: get-and-clear. */
        fun consumeJustReset(): Boolean = justReset.also { justReset = false }
    }
