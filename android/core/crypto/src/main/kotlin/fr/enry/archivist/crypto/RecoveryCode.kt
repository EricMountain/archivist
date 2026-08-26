package fr.enry.archivist.crypto

import java.security.SecureRandom

/**
 * The 26-character recovery code: 25 characters of entropy, one check symbol.
 * Crockford base32, omitting I, L, O, U. See crypto-format.md "The code" / "Verification".
 */
object RecoveryCode {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val ENTROPY_LEN = 25
    const val CODE_LEN = 26

    data class Generated(val entropy: String, val code: String)

    fun generate(random: SecureRandom = SecureRandom()): Generated {
        val entropy = buildString(ENTROPY_LEN) {
            repeat(ENTROPY_LEN) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
        return Generated(entropy, entropy + checkSymbol(entropy))
    }

    fun checkSymbol(entropy25: String): Char {
        require(entropy25.length == ENTROPY_LEN) { "entropy must be $ENTROPY_LEN characters" }
        var total = 0
        for (i in entropy25.indices) {
            total += (2 * i + 1) * ALPHABET.indexOf(entropy25[i])
        }
        return ALPHABET[total % 32]
    }

    /** Uppercase; drop everything outside the alphabet (hyphens, whitespace, `U`, ...);
     * map `I`, `L` -> `1` and `O` -> `0`. */
    fun normalise(raw: String): String {
        val out = StringBuilder(CODE_LEN)
        for (ch in raw.uppercase()) {
            when {
                ALPHABET.indexOf(ch) >= 0 -> out.append(ch)
                ch == 'I' || ch == 'L' -> out.append('1')
                ch == 'O' -> out.append('0')
                // else: dropped
            }
        }
        return out.toString()
    }

    /** The Argon2id password is the first 25 characters of the normalised code, or
     * `null` if `raw` doesn't normalise to a 26-character string with a valid check symbol. */
    fun verify(raw: String): String? {
        val normalised = normalise(raw)
        if (normalised.length != CODE_LEN) return null
        val entropy = normalised.substring(0, ENTROPY_LEN)
        return if (checkSymbol(entropy) == normalised[ENTROPY_LEN]) entropy else null
    }

    fun format(code: String): String {
        require(code.length == CODE_LEN)
        return "${code.substring(0, 5)}-${code.substring(5, 10)}-${code.substring(10, 15)}-" +
            "${code.substring(15, 20)}-${code.substring(20, 26)}"
    }
}
