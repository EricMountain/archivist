package fr.enry.archivist.data.repo

import fr.enry.archivist.data.local.db.TimelineKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries a fast-scroll jump's landing point from [TimelineRemoteMediator] (which knows
 * which photo sits at the requested instant) to [TimelinePagingSource] (which decides
 * where the next generation starts).
 *
 * Staging a *key* rather than scrolling to an index afterwards is the whole point. The
 * first version searched the presented list for the landing photo and scrolled to its
 * index — but a jump's target usually overlaps the window already on screen, so the
 * search found that photo in the **outgoing** list and scrolled to an index that meant
 * something else entirely once the reseeded window arrived (confirmed live: index 127 of
 * a 332-item stale list, for a window that was only ~215 items). Landing deep in the
 * list then triggered repeated appends, walking further into the past — the "lots of
 * stepping, ending nowhere near the date I picked" this feature shipped with twice.
 *
 * Making the new generation *start* at the landing photo means the correct position is
 * index 0, which no amount of asynchronous loading can invalidate.
 *
 * In-memory only, and consumed exactly once: a replayed value would pin a later,
 * unrelated refresh to a stale key.
 */
@Singleton
class TimelineJumpCoordinator
    @Inject
    constructor() {
        @Volatile private var landOn: TimelineKey? = null

        /** Called by [TimelineRemoteMediator.reseedAt] immediately before its write. */
        fun stageLanding(key: TimelineKey?) {
            landOn = key
        }

        /** [TimelinePagingSource.getRefreshKey]: get-and-clear. A null result means this
         * is an ordinary refresh, which re-anchors on the previous scroll position. */
        fun consumeLanding(): TimelineKey? = landOn.also { landOn = null }
    }
