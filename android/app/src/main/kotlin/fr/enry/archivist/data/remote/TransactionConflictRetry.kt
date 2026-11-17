package fr.enry.archivist.data.remote

import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.Response

/**
 * Retries a write the server cancelled with `409` + `code: "TRANSACTION_CONFLICT"`
 * (api.md "Errors"): DynamoDB refused the transaction because another one was writing
 * a shared counter row (`HIST`, `#PLACES`) at that moment. Nothing was written, so the
 * identical request is safe to send again, and a few hundred milliseconds later it
 * almost always goes through. Several uploads running at once hit this routinely.
 *
 * A `409` *without* that code is a genuine conflict (a claimed path, an asset already
 * trashed, a place that changed under the request) and is returned on the first try,
 * exactly as before — that is also what an older server sends for every conflict, so
 * this degrades to today's behavior against one.
 *
 * Backoff is exponential with "equal jitter": before retry `n` (1-based) the delay is
 * half of `min(maxDelayMs, baseDelayMs * 2^(n-1))` plus a random amount up to the other
 * half. The jitter matters because the requests that collided were in flight together
 * and would otherwise collide again on the same schedule. `delay` is the coroutine
 * one, so tests run it in virtual time.
 */
object TransactionConflictRetry {
    const val CODE = "TRANSACTION_CONFLICT"
    private const val HTTP_CONFLICT = 409

    data class Policy(
        /** Total requests, the first one included. */
        val maxAttempts: Int = 5,
        val baseDelayMs: Long = 200,
        val maxDelayMs: Long = 3_000,
    )

    val DEFAULT = Policy()

    /** What [run] ended with. [transactionConflict] is true only when the attempts ran
     * out on a retryable conflict, so a caller can treat it as transient rather than as
     * a refusal. It is computed here because the error body has already been read. */
    data class Attempted<T>(
        val response: Response<T>,
        val transactionConflict: Boolean,
        val attempts: Int,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Whether a response is the retryable kind of `409`. [errorBody] is the raw JSON
     * error body (`{"error": ..., "code": ...}`), or null when there was none. */
    fun isTransactionConflict(
        httpCode: Int,
        errorBody: String?,
    ): Boolean {
        if (httpCode != HTTP_CONFLICT || errorBody.isNullOrBlank()) return false
        return try {
            json.parseToJsonElement(errorBody).jsonObject["code"]?.jsonPrimitive?.contentOrNull == CODE
        } catch (e: IllegalArgumentException) {
            // Not JSON, or not an object, or code not a primitive: not ours.
            false
        }
    }

    /** The delay before retry number [retry] (1 = the first retry). */
    fun delayBeforeRetryMs(
        retry: Int,
        policy: Policy = DEFAULT,
        random: Random = Random.Default,
    ): Long {
        require(retry >= 1) { "retry is 1-based" }
        // Shift capped so a large retry count can't overflow before min() applies.
        val exp = min(policy.maxDelayMs, policy.baseDelayMs shl min(retry - 1, 30))
        val half = exp / 2
        return half + random.nextLong(exp - half + 1)
    }

    suspend fun <T> run(
        policy: Policy = DEFAULT,
        random: Random = Random.Default,
        call: suspend () -> Response<T>,
    ): Attempted<T> {
        var attempt = 1
        while (true) {
            val response = call()
            val conflict =
                response.code() == HTTP_CONFLICT &&
                    isTransactionConflict(response.code(), response.errorBody()?.string())
            if (!conflict) return Attempted(response, transactionConflict = false, attempts = attempt)
            if (attempt >= policy.maxAttempts) return Attempted(response, transactionConflict = true, attempts = attempt)
            delay(delayBeforeRetryMs(attempt, policy, random))
            attempt++
        }
    }
}
