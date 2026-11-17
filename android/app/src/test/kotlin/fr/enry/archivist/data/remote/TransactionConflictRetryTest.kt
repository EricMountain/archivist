package fr.enry.archivist.data.remote

import kotlin.random.Random
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response

class TransactionConflictRetryTest {
    private val txBody = """{"error":"another write to the same items was in progress; retry","code":"TRANSACTION_CONFLICT"}"""

    // ------------------------------------------------------------------
    // Classification.
    // ------------------------------------------------------------------

    @Test
    fun `only a 409 carrying code TRANSACTION_CONFLICT is retryable`() {
        assertTrue(TransactionConflictRetry.isTransactionConflict(409, txBody))
        // A genuine conflict, and what an older server sends for every conflict.
        assertFalse(TransactionConflictRetry.isTransactionConflict(409, """{"error":"asset is already trashed"}"""))
        assertFalse(TransactionConflictRetry.isTransactionConflict(409, null))
        assertFalse(TransactionConflictRetry.isTransactionConflict(409, ""))
        assertFalse(TransactionConflictRetry.isTransactionConflict(409, "<html>conflict</html>"))
        assertFalse(TransactionConflictRetry.isTransactionConflict(409, """["TRANSACTION_CONFLICT"]"""))
        assertFalse(TransactionConflictRetry.isTransactionConflict(409, """{"code":{"x":1}}"""))
        assertFalse(TransactionConflictRetry.isTransactionConflict(409, """{"code":"OTHER"}"""))
        // The code only means something on a 409.
        assertFalse(TransactionConflictRetry.isTransactionConflict(400, txBody))
        assertFalse(TransactionConflictRetry.isTransactionConflict(500, txBody))
    }

    // ------------------------------------------------------------------
    // Backoff schedule.
    // ------------------------------------------------------------------

    /** nextLong(bound) always returns 0 (the low edge) or bound - 1 (the high edge). */
    private class EdgeRandom(private val high: Boolean) : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextLong(until: Long): Long = if (high) until - 1 else 0
    }

    @Test
    fun `delays double from the base, with jitter in the upper half, up to the cap`() {
        val policy = TransactionConflictRetry.Policy(maxAttempts = 10, baseDelayMs = 200, maxDelayMs = 3_000)
        val low = (1..7).map { TransactionConflictRetry.delayBeforeRetryMs(it, policy, EdgeRandom(high = false)) }
        val high = (1..7).map { TransactionConflictRetry.delayBeforeRetryMs(it, policy, EdgeRandom(high = true)) }
        assertEquals(listOf(100L, 200L, 400L, 800L, 1_500L, 1_500L, 1_500L), low)
        assertEquals(listOf(200L, 400L, 800L, 1_600L, 3_000L, 3_000L, 3_000L), high)
    }

    @Test
    fun `a huge retry number stays at the cap instead of overflowing`() {
        val d = TransactionConflictRetry.delayBeforeRetryMs(1_000, random = EdgeRandom(high = true))
        assertEquals(TransactionConflictRetry.DEFAULT.maxDelayMs, d)
    }

    @Test
    fun `real jitter stays inside the bounds`() {
        val random = Random(42)
        repeat(1_000) {
            val retry = it % 8 + 1
            val exp = minOf(3_000L, 200L shl (retry - 1))
            val d = TransactionConflictRetry.delayBeforeRetryMs(retry, random = random)
            assertTrue(d in exp / 2..exp, "retry $retry: $d")
        }
    }

    // ------------------------------------------------------------------
    // The loop, in virtual time.
    // ------------------------------------------------------------------

    private fun conflict(): Response<String> = Response.error(409, txBody.toResponseBody("application/json".toMediaType()))

    private fun plainConflict(): Response<String> =
        Response.error(409, """{"error":"stem taken"}""".toResponseBody("application/json".toMediaType()))

    @Test
    fun `retries a transaction conflict, sleeping the schedule, until it succeeds`() =
        runTest {
            val responses = ArrayDeque(listOf(conflict(), conflict(), Response.success("ok")))
            var calls = 0

            val result =
                TransactionConflictRetry.run(random = EdgeRandom(high = true)) {
                    calls++
                    responses.removeFirst()
                }

            assertTrue(result.response.isSuccessful)
            assertFalse(result.transactionConflict)
            assertEquals(3, calls)
            assertEquals(3, result.attempts)
            assertEquals(200L + 400L, currentTime)
        }

    @Test
    fun `gives up after maxAttempts and says it was a transaction conflict`() =
        runTest {
            var calls = 0

            val result =
                TransactionConflictRetry.run(random = EdgeRandom(high = true)) {
                    calls++
                    conflict()
                }

            assertTrue(result.transactionConflict)
            assertEquals(409, result.response.code())
            assertEquals(5, calls)
            // Four sleeps between five attempts: 200 + 400 + 800 + 1600.
            assertEquals(3_000L, currentTime)
        }

    @Test
    fun `a plain 409 or any other failure is returned at once`() =
        runTest {
            for (response in listOf(plainConflict(), Response.error(500, "".toResponseBody()), Response.error<String>(400, "{}".toResponseBody()))) {
                var calls = 0
                val result =
                    TransactionConflictRetry.run {
                        calls++
                        response
                    }
                assertEquals(1, calls)
                assertFalse(result.transactionConflict)
                assertEquals(response.code(), result.response.code())
            }
            assertEquals(0L, currentTime)
        }
}
