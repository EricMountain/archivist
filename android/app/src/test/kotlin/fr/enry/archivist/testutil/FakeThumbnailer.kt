package fr.enry.archivist.testutil

import fr.enry.archivist.sync.Thumbnail
import fr.enry.archivist.sync.Thumbnailer

/** Stands in for [fr.enry.archivist.sync.AndroidThumbnailer] — no JVM unit test
 * environment has a real `ImageDecoder`. Returns fixed, tiny "thumbnails" regardless of
 * what [generate] is asked to decode. */
class FakeThumbnailer(
    private val sizesAndContent: Map<Int, ByteArray> =
        Thumbnailer.SIZES.associateWith { size -> byteArrayOf(size.toByte(), 0x01) },
) : Thumbnailer {
    override suspend fun generate(contentUri: String): List<Thumbnail> =
        sizesAndContent.map { (size, bytes) -> Thumbnail(size, size, size, bytes) }
}
