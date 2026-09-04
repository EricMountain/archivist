package fr.enry.archivist.testutil

import fr.enry.archivist.sync.UploadScheduler

/** Stands in for [fr.enry.archivist.sync.WorkManagerUploadScheduler] — no JVM unit
 * test environment has a real WorkManager to enqueue against. */
class FakeUploadScheduler : UploadScheduler {
    val enqueuedCalls = mutableListOf<List<Long>>()
    val cancelledIds = mutableListOf<Long>()

    override suspend fun enqueueAll(queueIds: List<Long>) {
        enqueuedCalls.add(queueIds)
    }

    override fun cancel(queueId: Long) {
        cancelledIds.add(queueId)
    }
}
