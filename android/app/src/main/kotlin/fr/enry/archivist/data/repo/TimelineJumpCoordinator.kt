package fr.enry.archivist.data.repo

import fr.enry.archivist.data.local.db.TimelineKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries a fast-scroll jump's landing photo from whoever resolved it — the mediator's
 * reseed, or the repository answering from Room — to the paging source that has to start
 * there.
 *
 * Two readers, two lifetimes. [consumeLanding] is one-shot, for
 * [TimelinePagingSource.getRefreshKey]: the next generation after a jump starts at the
 * landing, but every generation after that must go back to following the user's own
 * anchor, or scrolling away would keep snapping back. [isLanding] is sticky, for
 * [TimelinePagingSource.load]: a refresh *at* the landing loads from it exactly rather
 * than around it, so the landing is index 0 and "go to the top" lands on it. It has to be
 * sticky because two pagers can each run that refresh — the outgoing one, invalidated by
 * the reseed's own write, and the rebuilt one — and whichever runs second would otherwise
 * find the flag already spent.
 */
@Singleton
class TimelineJumpCoordinator
    @Inject
    constructor() {
        @Volatile private var landOn: TimelineKey? = null

        /** The most recent landing, for [isLanding]. Not cleared by [consumeLanding]. */
        @Volatile var landing: TimelineKey? = null
            private set

        fun stageLanding(key: TimelineKey?) {
            landOn = key
            landing = key
        }

        fun consumeLanding(): TimelineKey? = landOn.also { landOn = null }

        fun isLanding(key: TimelineKey): Boolean = key == landing
    }
