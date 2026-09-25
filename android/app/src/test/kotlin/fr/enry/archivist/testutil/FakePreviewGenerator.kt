package fr.enry.archivist.testutil

import fr.enry.archivist.sync.PreviewClip
import fr.enry.archivist.sync.PreviewGenerator

/** Stands in for [fr.enry.archivist.sync.TransformerPreviewGenerator] — a bare JVM has
 * no media transformer. Returns fixed bytes, or throws [error] when set, and records
 * every URI it was asked about so a test can assert a still never reaches it. */
class FakePreviewGenerator(
    var clip: PreviewClip = PreviewClip(width = 112, height = 200, bytes = ByteArray(64) { (it * 3).toByte() }),
    var error: Exception? = null,
) : PreviewGenerator {
    val requested = mutableListOf<String>()

    override suspend fun generate(contentUri: String): PreviewClip {
        requested += contentUri
        error?.let { throw it }
        return clip
    }
}
