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
 *
 * [lastResolvedKey] is a third, separate piece of state — see its own doc for the
 * "climbs forward on its own" bug it exists to fix.
 */
@Singleton
class TimelineJumpCoordinator
    @Inject
    constructor() {
        @Volatile private var landOn: TimelineKey? = null

        /** The most recent landing, for [isLanding]. Not cleared by [consumeLanding]. */
        @Volatile var landing: TimelineKey? = null
            private set

        /**
         * The most recent key [TimelinePagingSource.getRefreshKey] resolved to, by any
         * means (a staged landing or `state.anchorPosition`) — [recordResolvedKey]'s own
         * doc has the failure this is for.
         */
        @Volatile private var lastKey: TimelineKey? = null

        fun stageLanding(key: TimelineKey?) {
            landOn = key
            landing = key
            if (key != null) lastKey = key
        }

        fun consumeLanding(): TimelineKey? = landOn.also { landOn = null }

        fun isLanding(key: TimelineKey): Boolean = key == landing

        /** A one-shot key for [TimelinePagingSource.getRefreshKey] to consume on its
         * *next* call, deliberately **not** a [stageLanding]/[isLanding] "jump landing" —
         * a jump wants its target to become index 0 (`load`'s `pageFromKey` branch) and
         * the grid explicitly scrolled there, both correct for "the user asked to go
         * here" but wrong for this case. This is for a write that disrupts the grid from
         * *outside* the normal jump flow — currently only
         * [fr.enry.archivist.data.repo.RepairRepository] — where nothing about the
         * user's own position should change; only the stale data underneath it needs
         * replacing. Consuming it still routes through `getRefreshKey`'s ordinary
         * `refreshAround` path (not `isLanding`'s `pageFromKey`), so the reload keeps
         * lead context above the key and `LazyVerticalGrid`'s own key-based position
         * restoration holds the viewport still — exactly [TimelinePagingSource.refreshAround]'s
         * documented "the view stays exactly where it was" guarantee, just fed a key
         * that's correct despite the grid being unmounted (not composed, therefore
         * reporting no `anchorPosition`) when the write actually happened.
         *
         * **Found live, in two stages.** First: with nothing staged here at all, the
         * write's restart fell back to whatever anchor was last recorded *before*
         * `DetailScreen` opened — reported as the grid "shifting wildly" on the way
         * back out. Fixed by staging via [stageLanding] instead — but that reused the
         * jump-landing path wholesale, including `pageFromKey` and an explicit
         * scroll-to-index-0, which visibly repositioned the repaired photo to the top
         * of the screen — correct for a jump, wrong here, reported as "positions the
         * photo that was repaired at the top of the screen" instead of leaving the
         * timeline exactly where it was. This key exists so the *data* gets the
         * accurate anchor a jump-landing gives it without inheriting either of the
         * jump-specific behaviors that came bundled with it. */
        @Volatile private var externalRefreshKey: TimelineKey? = null

        fun stageExternalRefreshKey(key: TimelineKey) {
            externalRefreshKey = key
        }

        fun consumeExternalRefreshKey(): TimelineKey? = externalRefreshKey.also { externalRefreshKey = null }

        /**
         * Reproduced live: after a jump lands correctly, leaving the grid untouched for a
         * few seconds still walks it forward through time on its own, with `PREPEND`
         * (`loadNewerThanCache`) firing repeatedly with no scroll at all. Each successful
         * `PREPEND` writes to `photos`, which invalidates [TimelinePagingSource] and
         * starts a new generation — same as every other write to that table (see
         * [TimelineRemoteMediator]'s own doc) — and *that* part is by design. What isn't:
         * a generation restart with no `anchorPosition` yet established (plausible when
         * restarts arrive back-to-back, faster than the grid gets a chance to report one)
         * fell through `getRefreshKey` to `null`, and a `null` keyed refresh loads
         * [fr.enry.archivist.data.local.db.PhotoDao.pageFromStart] — the literal newest
         * page in the table, with none of [TimelinePagingSource.refreshAround]'s
         * protection for keeping the displayed photo in the reloaded window. Each such
         * restart therefore snapped the grid to whatever `PREPEND` had most recently
         * fetched, which is indistinguishable from "climbing toward the present" once it
         * happens a few times in a row.
         *
         * Recording the key every successful [TimelinePagingSource.getRefreshKey] call
         * resolves to — whether from a staged landing or a real anchor — and falling back
         * to it instead of `null` means a restart with no anchor yet re-centers on
         * wherever the grid actually was, the same as every other restart does. A
         * genuine cold start (nothing ever resolved) is unaffected: [lastResolvedKey]
         * is null then too, so `getRefreshKey` still falls through to the newest page,
         * exactly as before.
         */
        fun recordResolvedKey(key: TimelineKey) {
            lastKey = key
        }

        fun lastResolvedKey(): TimelineKey? = lastKey
    }
