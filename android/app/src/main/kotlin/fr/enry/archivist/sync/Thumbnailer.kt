package fr.enry.archivist.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
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
     * practice. [mime] picks the decode path: a MIME starting with `video` extracts a
     * poster frame via [android.media.MediaMetadataRetriever] instead of decoding
     * [contentUri] as a still image. Throws if the source can't be decoded; RAW files
     * have no sibling-free decode path here at all (design.md: "Android can't decode
     * CR3 or ARW") -- callers are expected to have already skipped those, same as
     * [Scanner] does for hashing. */
    suspend fun generate(
        contentUri: String,
        mime: String,
    ): List<Thumbnail>

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

/** Which instant (microseconds, for [MediaMetadataRetriever.getFrameAtTime]) to pull
 * the poster frame from, given the video's [durationMs] (null/unknown treated as 0).
 * Halfway into the first second rather than frame zero -- many encoders' very first
 * frame is black or a keyframe-less blank, and this is cheap to keep inside the clip
 * for anything shorter than two seconds. Pure and Android-free so it's covered by a
 * plain JVM test, same reasoning as [targetDimensions]/[sampleSizeFor]. */
internal fun posterFrameTimeUs(durationMs: Long?): Long {
    val duration = durationMs?.takeIf { it > 0 } ?: return 0L
    return minOf(duration / 2, 1_000L) * 1_000L
}

/** Instants (microseconds) to try for the poster frame, best guess first: the
 * [posterFrameTimeUs] instant, then a quarter, half and three quarters of the way
 * through. A clip that fades in from black, or opens on a dark title card, is common
 * enough that the first guess alone leaves a black thumbnail -- see
 * [isMostlyDark]. Unknown/zero duration has nothing to probe beyond frame zero. Pure
 * and Android-free, same reasoning as [posterFrameTimeUs]. */
internal fun posterFrameCandidatesUs(durationMs: Long?): List<Long> {
    val duration = durationMs?.takeIf { it > 0 } ?: return listOf(0L)
    return listOf(
        posterFrameTimeUs(duration),
        duration * 250L,
        duration * 500L,
        duration * 750L,
    ).distinct()
}

/** Mean luma (0-255, Rec. 601 weights) of ARGB [pixels]; 0 for an empty array. Pure so
 * the darkness heuristic is JVM-testable -- callers hand it a tiny downscaled sample,
 * not a full frame. */
internal fun meanLuma(pixels: IntArray): Double {
    if (pixels.isEmpty()) return 0.0
    var sum = 0.0
    for (p in pixels) {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        sum += 0.299 * r + 0.587 * g + 0.114 * b
    }
    return sum / pixels.size
}

/** Below this mean luma a frame counts as "black" for poster purposes. Deliberately
 * low: a genuinely dark night scene sits well above it, while a fade-in/black first
 * frame is essentially 0-8. If every candidate is darker, the brightest wins anyway. */
internal const val DARK_FRAME_LUMA = 16.0

internal fun isMostlyDark(pixels: IntArray): Boolean = meanLuma(pixels) < DARK_FRAME_LUMA

/**
 * Decodes the source exactly once, at a sample size chosen for the *largest* rung
 * ([Thumbnailer.SIZES] max) -- a 50 MP original never exists as a full-size bitmap in
 * memory. The two smaller rungs are then derived from that single already-downsampled
 * bitmap via [Bitmap.createScaledBitmap], which is cheap precisely because the source
 * for it is already thumbnail-sized, not the original.
 *
 * Video (see the private video decode path below) shares that same "decode once,
 * derive smaller rungs from it" shape via [thumbnailsFrom] -- the only difference is
 * what produces the first bitmap: [ImageDecoder] for a still,
 * [MediaMetadataRetriever]'s poster frame for video. A single video frame's full-size
 * decode, uncapped, is nowhere near the memory a 50 MP still would be -- even 8K video
 * is under 100 MP -- so it skips [ImageDecoder]'s sample-size dance rather than needing
 * an equivalent for it.
 */
class AndroidThumbnailer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : Thumbnailer {
        override suspend fun generate(
            contentUri: String,
            mime: String,
        ): List<Thumbnail> =
            withContext(Dispatchers.Default) {
                if (mime.startsWith("video/")) {
                    generateVideoThumbnails(contentUri)
                } else {
                    generateImageThumbnails(contentUri)
                }
            }

        /** [MediaMetadataRetriever.release], not `.use {}` -- it's only been
         * [AutoCloseable] since API 29, below this app's minSdk 28. */
        private fun generateVideoThumbnails(contentUri: String): List<Thumbnail> {
            val uri = Uri.parse(contentUri)
            val retriever = MediaMetadataRetriever()
            val frame =
                try {
                    retriever.setDataSource(context, uri)
                    val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    pickPosterFrame(retriever, posterFrameCandidatesUs(durationMs))
                } finally {
                    retriever.release()
                }
            val base = frame ?: throw IOException("could not decode a poster frame from $contentUri")
            return thumbnailsFrom(base, base.width, base.height)
        }

        /** Tries each of [candidatesUs] in order and returns the first frame that
         * isn't [isMostlyDark]; if all are dark, the brightest one seen. Uses
         * `OPTION_CLOSEST` (an exact frame), not `OPTION_CLOSEST_SYNC`: the latter snaps
         * to a keyframe, and a clip whose only early keyframe is frame zero returned
         * that (often black) frame for every requested instant. Losing frames are
         * recycled; null only if no candidate decoded at all. */
        private fun pickPosterFrame(
            retriever: MediaMetadataRetriever,
            candidatesUs: List<Long>,
        ): Bitmap? {
            var best: Bitmap? = null
            var bestLuma = -1.0
            for (timeUs in candidatesUs) {
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                val luma = sampleLuma(frame)
                if (luma >= DARK_FRAME_LUMA) {
                    best?.recycle()
                    return frame
                }
                if (luma > bestLuma) {
                    best?.recycle()
                    best = frame
                    bestLuma = luma
                } else {
                    frame.recycle()
                }
            }
            return best
        }

        /** Mean luma of a tiny downscale of [frame] -- cheap regardless of video size. */
        private fun sampleLuma(frame: Bitmap): Double {
            val sample = Bitmap.createScaledBitmap(frame, LUMA_SAMPLE_EDGE, LUMA_SAMPLE_EDGE, true)
            return try {
                val pixels = IntArray(LUMA_SAMPLE_EDGE * LUMA_SAMPLE_EDGE)
                sample.getPixels(pixels, 0, LUMA_SAMPLE_EDGE, 0, 0, LUMA_SAMPLE_EDGE, LUMA_SAMPLE_EDGE)
                meanLuma(pixels)
            } finally {
                if (sample !== frame) sample.recycle()
            }
        }

        private fun generateImageThumbnails(contentUri: String): List<Thumbnail> {
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
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }

            return thumbnailsFrom(base, srcWidth, srcHeight)
        }

        /** The rung-derivation half both decode paths share: scale [base] down to each
         * of [Thumbnailer.SIZES], aspect preserved against the true source
         * [srcWidth]/[srcHeight] (which may differ slightly from [base]'s own
         * dimensions when [base] came from a sample-size decode, not an exact one),
         * WebP-encode each rung, and recycle every bitmap besides the caller's. */
        private fun thumbnailsFrom(
            base: Bitmap,
            srcWidth: Int,
            srcHeight: Int,
        ): List<Thumbnail> =
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

        private fun encodeWebp(bitmap: Bitmap): ByteArray = fr.enry.archivist.sync.encodeWebp(bitmap)
    }

private const val LUMA_SAMPLE_EDGE = 16

/** Pulled out of [AndroidThumbnailer] (`internal`, not `private`) so
 * [fr.enry.archivist.data.repo.RotateRepository] can produce byte-identical thumbnail
 * encoding from a bitmap it decoded and rotated itself, rather than duplicating the
 * WebP-version dance or drifting from this quality setting. */
internal const val WEBP_QUALITY = 82

internal fun encodeWebp(bitmap: Bitmap): ByteArray {
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
