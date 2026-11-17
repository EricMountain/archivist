package fr.enry.archivist.domain

import java.text.Normalizer
import java.util.Locale

/**
 * The case- and diacritic-insensitive form place search matches on (geolocation plan D6):
 * "  São   Paulo " and "sao paulo" fold to the same string, as do "Québec" and "QUEBEC".
 */
object PlaceFold {
    private val combiningMarks = Regex("\\p{M}+")

    /** NFKD, drop combining marks, lowercase (root locale), collapse whitespace, trim. */
    fun fold(s: String): String {
        val stripped =
            combiningMarks.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "")
                .lowercase(Locale.ROOT)
        // Whitespace by Character's own definition rather than a regex `\s`, whose meaning
        // differs between the JVM (ASCII only) and Android (ICU, Unicode-aware).
        val out = StringBuilder(stripped.length)
        var pendingSpace = false
        for (c in stripped) {
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                pendingSpace = out.isNotEmpty()
            } else {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.append(c)
            }
        }
        return out.toString()
    }
}
