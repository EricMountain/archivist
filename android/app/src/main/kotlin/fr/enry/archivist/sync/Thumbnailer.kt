package fr.enry.archivist.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plan step 2.9: the fixed 256/1024/2048 (longest edge) WebP ladder generated on
 * device before upload -- see "Thumbnail ladder: 256, 1024, 2048" in design.md. All
 * three sizes are always produced together: the server can never re-derive a size that
 * wasn't uploaded, and adding one later means a re-upload from the originals.
 *
 * An interface for the same reason [fr.enry.archivist.sync.MediaStoreSource] is one --
 * [ImageDecoder] has no fake in this JVM test environment, so plan step 2.10's upload
 * worker tests against a fake implementation of this instead.
 */
interface Thumbnailer {
    /** [contentUri] is anything [android.content.ContentResolver.openInputStream] (via
     * [ImageDecoder.createSource]) can open -- a MediaStore `content://` URI in
     * practice. Throws if the source can't be decoded as an image; RAW files have no
     * sibling-free decode path here at all (design.md: "Android can't decode CR3 or
     * ARW") -- callers are expected to have already skipped those, same as [Scanner]
     * does for hashing. */
    suspend fun generate(contentUri: String): List<Thumbnail>

    companion object {
        /** Longest edge in pixels, one WebP per entry. */
        val SIZES = listOf(256, 1024, 2048)
    }
}

/** One rung of the ladder: WebP-encoded bytes plus the dimensions actually produced.
 * [width]/[height] can be smaller than [longestEdge] asks for -- a source already
 * smaller than the rung is never upscaled. */
class Thumbnail(
    val longestEdge: Int,
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
)

/** Given a source [width]x[height], the dimensions of the longest-edge-[longestEdge]
 * thumbnail, aspect preserved, never upscaling. Pure and Android-free so it's covered
 * by a plain JVM test -- [sampleSizeFor] is the decode-time counterpart. */
internal fun targetDimensions(
    width: Int,
    height: Int,
    longestEdge: Int,
): Pair<Int, Int> {
    val srcLongest = maxOf(width, height)
    if (srcLongest <= longestEdge) return width to height
    val scale = longestEdge.toDouble() / srcLongest
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}

/** The `ImageDecoder.Decoder.setTargetSampleSize` divisor that brings a
 * [width]x[height] source down to *at least* [longestEdge] on its longest edge without
 * going under it. The precise final size then comes from a cheap
 * [Bitmap.createScaledBitmap] on the already-downsampled bitmap -- this is the
 * "downsample during decode, never decode full-size then scale" half of the plan's
 * requirement; [targetDimensions] is the other half. */
internal fun sampleSizeFor(
    width: Int,
    height: Int,
    longestEdge: Int,
): Int {
    val srcLongest = maxOf(width, height)
    if (srcLongest <= longestEdge) return 1
    return (srcLongest / longestEdge).coerceAtLeast(1)
}

/**
 * Decodes the source exactly once, at a sample size chosen for the *largest* rung
 * ([Thumbnailer.SIZES] max) -- a 50 MP original never exists as a full-size bitmap in
 * memory. The two smaller rungs are then derived from that single already-downsampled
 * bitmap via [Bitmap.createScaledBitmap], which is cheap precisely because the source
 * for it is already thumbnail-sized, not the original.
 */
class AndroidThumbnailer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : Thumbnailer {
        override suspend fun generate(contentUri: String): List<Thumbnail> =
            withContext(Dispatchers.Default) {
                val uri = Uri.parse(contentUri)
                val maxEdge = Thumbnailer.SIZES.max()

                var srcWidth = 0
                var srcHeight = 0
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val base =
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        srcWidth = info.size.width
                        srcHeight = info.size.height
                        val sampleSize = sampleSizeFor(srcWidth, srcHeight, maxEdge)
                        if (sampleSize > 1) decoder.setTargetSampleSize(sampleSize)
                        // Hardware bitmaps can't be fed to Bitmap.compress on every API
                        // level this app supports (minSdk 28) -- force software so
                        // every rung's compress() below is reliable.
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }

                try {
                    Thumbnailer.SIZES.map { longestEdge ->
                        val (targetWidth, targetHeight) = targetDimensions(srcWidth, srcHeight, longestEdge)
                        val scaled =
                            if (targetWidth == base.width && targetHeight == base.height) {
                                base
                            } else {
                                Bitmap.createScaledBitmap(base, targetWidth, targetHeight, true)
                            }
                        try {
                            Thumbnail(longestEdge, targetWidth, targetHeight, encodeWebp(scaled))
                        } finally {
                            if (scaled !== base) scaled.recycle()
                        }
                    }
                } finally {
                    base.recycle()
                }
            }

        private fun encodeWebp(bitmap: Bitmap): ByteArray {
            val out = ByteArrayOutputStream()
            val format =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            bitmap.compress(format, WEBP_QUALITY, out)
            return out.toByteArray()
        }

        private companion object {
            const val WEBP_QUALITY = 82
        }
    }
