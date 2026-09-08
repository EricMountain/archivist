package fr.enry.archivist.sync

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UploadConcurrencyLimiterTest {
    @Test
    fun `no more than MAX_CONCURRENT_UPLOADS run their guarded section at once`() =
        runTest {
            val limiter = UploadConcurrencyLimiter()
            val concurrent = AtomicInteger(0)
            val maxObserved = AtomicInteger(0)

            val jobs =
                (1..10).map {
                    async {
                        limiter.semaphore.withPermit {
                            val now = concurrent.incrementAndGet()
                            maxObserved.updateAndGet { max -> maxOf(max, now) }
                            yield()
                            concurrent.decrementAndGet()
                        }
                    }
                }
            jobs.forEach { it.await() }

            assertTrue(
                maxObserved.get() <= UploadConcurrencyLimiter.MAX_CONCURRENT_UPLOADS,
                "expected at most ${UploadConcurrencyLimiter.MAX_CONCURRENT_UPLOADS} concurrent, saw ${maxObserved.get()}",
            )
            assertEquals(0, concurrent.get())
        }
}
