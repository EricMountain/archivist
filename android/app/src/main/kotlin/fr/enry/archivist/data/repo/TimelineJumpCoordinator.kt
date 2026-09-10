package fr.enry.archivist.data.repo

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The handshake between a fast-scroll jump request (`PhotoRepository.requestJump`) and
 * the two pieces of the `Pager` that need to know about it — [TimelineRemoteMediator]
 * (which fetches the jumped-to window) and [TimelinePagingSource] (which has to land
 * the grid on it rather than trust a stale scroll anchor). In-memory only, scoped to
 * this process: nothing here survives a process death, which is fine — a jump target
 * that never gets acted on because the app died mid-gesture just means the grid stays
 * wherever it already was.
 *
 * Two separate flags rather than one, because they're consumed by two different
 * classes at two different times in the same jump: [consumePendingTarget] once, by the
 * mediator, when it decides what to fetch; [consumeJustReset] once, by the paging
 * source, when it decides how to re-anchor afterwards. See [TimelinePagingSource
 * .getRefreshKey]'s own doc for why the second flag exists at all — the obvious
 * alternative (trust the anchor-based refresh key unconditionally) silently mis-lands
 * a *forward* jump that doesn't reach all the way to "now".
 */
@Singleton
class TimelineJumpCoordinator
    @Inject
    constructor() {
        @Volatile private var pendingTargetIso: String? = null

        @Volatile private var justReset: Boolean = false

        /** Called from the UI layer when a fast-scroll drag is released. [targetIso] is
         * an ISO-8601 UTC instant — the point the grid should land on. */
        fun requestJump(targetIso: String) {
            pendingTargetIso = targetIso
        }

        /** [TimelineRemoteMediator]'s `REFRESH` branch: get-and-clear, since this must
         * only ever seed one fetch. */
        fun consumePendingTarget(): String? = pendingTargetIso?.also { pendingTargetIso = null }

        /** Set by the mediator once a jump-seeded `REFRESH` has actually committed. */
        fun markJustReset() {
            justReset = true
        }

        /** [TimelinePagingSource.getRefreshKey]: get-and-clear. */
        fun consumeJustReset(): Boolean = justReset.also { justReset = false }
    }
