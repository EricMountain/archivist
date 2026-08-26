package fr.enry.archivist.crypto

import java.nio.charset.StandardCharsets

/** The object context string bound into every encryption. See crypto-format.md. */
sealed class ObjectRef {
    data class Rendition(val renditionId: String) : ObjectRef()
    data class Thumbnail(val longestEdge: Int) : ObjectRef()
    data object Exif : ObjectRef()

    internal fun encode(): String = when (this) {
        is Rendition -> "r:$renditionId"
        is Thumbnail -> "t:$longestEdge"
        is Exif -> "x"
    }
}

object Aad {
    const val VERSION = 1

    /** `archivist:<version>:<photoId>:<objectRef>`, UTF-8, no trailing newline. */
    fun of(photoId: String, objectRef: ObjectRef): ByteArray =
        "archivist:$VERSION:$photoId:${objectRef.encode()}".toByteArray(StandardCharsets.UTF_8)
}
