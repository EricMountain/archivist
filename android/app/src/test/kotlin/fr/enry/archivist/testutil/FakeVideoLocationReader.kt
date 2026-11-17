package fr.enry.archivist.testutil

import fr.enry.archivist.sync.MediaStoreSource
import fr.enry.archivist.sync.VideoLocationReader
import java.io.File

/**
 * Stands in for [fr.enry.archivist.sync.AndroidVideoLocationReader]: no JVM test has a real
 * `MediaMetadataRetriever`. Reads the same bytes the real one would (the stripped copy when
 * there is one, else the [MediaStoreSource] content) and returns the ISO 6709 string of the
 * first `©xyz` box, so a box `LocationStripper` retyped to `free` is genuinely gone. Accepts
 * both a real QuickTime payload (u16 length + u16 language, then the string) and
 * [SyntheticMp4.quicktimeXyzBox]'s bare string.
 */
class FakeVideoLocationReader(
    private val mediaStoreSource: MediaStoreSource,
) : VideoLocationReader {
    val reads = mutableListOf<Pair<String, File?>>()

    override suspend fun read(
        contentUri: String,
        strippedFile: File?,
    ): String? {
        reads += contentUri to strippedFile
        val bytes = strippedFile?.readBytes() ?: mediaStoreSource.openInputStream(contentUri).use { it.readBytes() }
        val type = "©xyz".toByteArray(Charsets.ISO_8859_1)
        val at = (0..bytes.size - type.size).firstOrNull { i -> type.indices.all { bytes[i + it] == type[it] } } ?: return null
        val size = ((bytes[at - 4].toInt() and 0xFF) shl 24) or ((bytes[at - 3].toInt() and 0xFF) shl 16) or
            ((bytes[at - 2].toInt() and 0xFF) shl 8) or (bytes[at - 1].toInt() and 0xFF)
        val payload = String(bytes, at + 4, size - 8, Charsets.ISO_8859_1)
        return payload.substring(payload.indexOfFirst { it == '+' || it == '-' }.coerceAtLeast(0))
    }
}
