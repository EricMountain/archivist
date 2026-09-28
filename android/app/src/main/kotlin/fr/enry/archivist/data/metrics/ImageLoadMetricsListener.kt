package fr.enry.archivist.data.metrics

import coil3.EventListener
import coil3.decode.DataSource
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import fr.enry.archivist.crypto.EncryptedThumbRef
import fr.enry.archivist.ui.timeline.GRID_THUMB_SIZE

/**
 * Feeds [ImageLoadMetrics] from Coil: one listener per request (Coil's own
 * [EventListener.Factory] contract), timing from [onStart] to the request's end. Only
 * encrypted thumbnails are counted; anything else Coil loads gets [EventListener.NONE].
 */
class ImageLoadMetricsListenerFactory(
    private val metrics: ImageLoadMetrics,
) : EventListener.Factory {
    override fun create(request: ImageRequest): EventListener {
        val ref = request.data as? EncryptedThumbRef ?: return EventListener.NONE
        // The grid asks for GRID_THUMB_SIZE (or a smaller rung when that's missing); the
        // detail screen asks for the largest rung there is.
        val kind = if (ref.longestEdge <= GRID_THUMB_SIZE) ImageKind.GRID else ImageKind.DETAIL
        return Listener(kind, metrics)
    }

    private class Listener(
        private val kind: ImageKind,
        private val metrics: ImageLoadMetrics,
    ) : EventListener() {
        private var startNanos = 0L

        private fun elapsedMs() = (System.nanoTime() - startNanos) / 1_000_000

        override fun onStart(request: ImageRequest) {
            startNanos = System.nanoTime()
        }

        override fun onSuccess(
            request: ImageRequest,
            result: SuccessResult,
        ) {
            val outcome =
                when (result.dataSource) {
                    DataSource.MEMORY_CACHE, DataSource.MEMORY -> LoadOutcome.MEMORY
                    DataSource.DISK -> LoadOutcome.DISK
                    DataSource.NETWORK -> LoadOutcome.NETWORK
                }
            metrics.record(kind, outcome, elapsedMs())
        }

        override fun onError(
            request: ImageRequest,
            result: ErrorResult,
        ) = metrics.record(kind, LoadOutcome.ERROR, elapsedMs())

        override fun onCancel(request: ImageRequest) = metrics.record(kind, LoadOutcome.CANCELLED, elapsedMs())
    }
}
