package fr.enry.archivist.domain

import java.security.SecureRandom

/**
 * Mints a ULID client-side — needed starting with plan step 2.10, which must pick a
 * candidate `photoId` *before* calling `POST /uploads` (see "Why the client gets to
 * propose a photoId" in design.md: the AAD that binds thumbnails/EXIF is chosen before
 * the server would otherwise mint one). Crockford base32, 48-bit millisecond timestamp
 * followed by 80 bits of randomness — matches `src/core/ids.ts`'s `ULID_RE` exactly, but
 * isn't required to match its *implementation*: nothing server-side ever decodes a
 * client-minted candidate's timestamp, it only checks the regex.
 */
object Ulid {
    private const val ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val TIME_LEN = 10
    private const val RANDOM_LEN = 16
    private val random = SecureRandom()

    fun generate(now: Long = System.currentTimeMillis()): String = encodeTime(now) + encodeRandom()

    private fun encodeTime(time: Long): String {
        var t = time
        val chars = CharArray(TIME_LEN)
        for (i in TIME_LEN - 1 downTo 0) {
            chars[i] = ENCODING[(t % 32).toInt()]
            t /= 32
        }
        return String(chars)
    }

    private fun encodeRandom(): String {
        val chars = CharArray(RANDOM_LEN)
        for (i in 0 until RANDOM_LEN) {
            chars[i] = ENCODING[random.nextInt(32)]
        }
        return String(chars)
    }
}
