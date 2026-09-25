package fr.enry.archivist.sync

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The video preview clip (design.md, "Video preview clip"): a muted, low-resolution,
 * H.264 copy of a video's first minute, uploaded encrypted next to the still thumbnails
 * and played by default in the grid and the detail view.
 *
 * An interface for the same reason [Thumbnailer] is one -- [androidx.media3.transformer.Transformer]
 * has no fake on a bare JVM, so the upload/repair repositories are tested against a fake.
 * Strictly best-effort: callers must treat a failure as "no preview", never as a reason
 * to fail an upload or a repair.
 */
interface PreviewGenerator {
    /** [contentUri] is anything [MediaMetadataRetriever] and the transformer can open (a
     * MediaStore `content://` URI, or a `file://` one for a repair's temp copy). Throws
     * [IOException] when it can't be transcoded. */
    suspend fun generate(contentUri: String): PreviewClip
}

/** [bytes] is the finished MP4, plaintext; [width]/[height] are what was actually
 * produced. */
class PreviewClip(
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
)

/** Longest edge in pixels. Same "longest edge, aspect preserved, never upscale"
 * convention as the still ladder ([targetDimensions]). */
const val PREVIEW_MAX_EDGE = 200

/** The preview covers at most the first minute; a shorter video is previewed whole. */
const val PREVIEW_MAX_DURATION_MS = 60_000L

/** Target video bitrate. With the 60 s cap this bounds a preview at roughly 1.9 MB. The
 * exact value is a measured-on-real-footage question -- see design.md open question 5. */
const val PREVIEW_VIDEO_BITRATE = 250_000

/** Tone-mapping methods to try, in order. OpenGL first: it doesn't depend on the device's
 * MediaCodec supporting tone-mapping. See `TransformerPreviewGenerator.transcodeTonemapping`. */
internal fun hdrModeName(mode: Int): String =
    when (mode) {
        Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL -> "tonemap-gl"
        Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC -> "tonemap-mediacodec"
        else -> "hdr-mode-$mode"
    }

internal val HDR_TONE_MAP_MODES =
    listOf(
        Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
        Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC,
    )

/** The dimensions of the preview for a [width]x[height] *displayed* (rotation already
 * applied) source: [targetDimensions] at [PREVIEW_MAX_EDGE], then each edge rounded down
 * to an even number, since H.264 chroma subsampling needs it. Pure and Android-free so
 * it is covered by a plain JVM test. */
internal fun previewDimensions(
    width: Int,
    height: Int,
): Pair<Int, Int> {
    val (w, h) = targetDimensions(width, height, PREVIEW_MAX_EDGE)
    return evenDown(w) to evenDown(h)
}

private fun evenDown(v: Int): Int = maxOf(2, v - v % 2)

/** Whether a source of [durationMs] needs trimming to [PREVIEW_MAX_DURATION_MS]. Pure. A
 * source with unknown or zero duration is left untrimmed. */
internal fun needsTrim(durationMs: Long?): Boolean = (durationMs ?: 0L) > PREVIEW_MAX_DURATION_MS

class TransformerPreviewGenerator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : PreviewGenerator {
        override suspend fun generate(contentUri: String): PreviewClip =
            withContext(Dispatchers.Default) {
                val uri = Uri.parse(contentUri)
                val probe = probe(uri)
                val (width, height) = previewDimensions(probe.displayWidth, probe.displayHeight)

                // The OS may purge cacheDir under storage pressure, directory included.
                context.cacheDir.mkdirs()
                val out = File.createTempFile("preview-", ".mp4", context.cacheDir)
                try {
                    transcodeTonemapping(uri, out, width, height, trim = needsTrim(probe.durationMs))
                    val bytes = withContext(Dispatchers.IO) { out.readBytes() }
                    if (bytes.isEmpty()) throw IOException("preview transcode produced no output for $contentUri")
                    PreviewClip(width, height, bytes)
                } finally {
                    out.delete()
                }
            }

        private class Probe(val displayWidth: Int, val displayHeight: Int, val durationMs: Long?)

        /** [MediaMetadataRetriever.release], not `.use {}` -- only [AutoCloseable] since
         * API 29, below minSdk 28. Width/height are swapped for a 90/270 degree rotation
         * so [previewDimensions] sees the orientation the viewer sees. */
        private fun probe(uri: Uri): Probe {
            val retriever = MediaMetadataRetriever()
            try {
                try {
                    retriever.setDataSource(context, uri)
                } catch (e: RuntimeException) {
                    // A missing or unreadable source throws IllegalArgumentException /
                    // RuntimeException, not IOException -- normalised, since this
                    // interface's contract is "IOException when it can't be transcoded".
                    throw IOException("cannot read $uri: ${e.message}", e)
                }

                fun meta(key: Int) = retriever.extractMetadata(key)?.toIntOrNull()
                val w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                if (w == null || h == null || w <= 0 || h <= 0) throw IOException("no video dimensions for $uri")
                val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val swap = rotation == 90 || rotation == 270
                return Probe(if (swap) h else w, if (swap) w else h, duration)
            } finally {
                retriever.release()
            }
        }

        /** Transformer must be started, and cancelled, on a thread with a [Looper]; the
         * main thread is the only one guaranteed to have one here. The transcode itself
         * runs on the transformer's own threads, so this doesn't block the UI. */
        /** Tries each of [HDR_TONE_MAP_MODES] in turn, so an HDR source (10-bit HEVC HLG/HDR10,
         * what many phones record by default) still yields an 8-bit SDR H.264 preview: the
         * transformer's default is to *keep* HDR, which an H.264 encoder can't take, and the
         * export fails with `ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED` (reproduced with a
         * 10-bit HLG HEVC clip on an emulator; a real user's repair of a phone video hit
         * "preview clip couldn't be generated"). The setting is a no-op for SDR sources, so
         * an SDR video succeeds on the first attempt; only a failure pays for a retry, with
         * the other tone-mapping method, since which one works is device-dependent. */
        private suspend fun transcodeTonemapping(
            uri: Uri,
            out: File,
            width: Int,
            height: Int,
            trim: Boolean,
        ) {
            val failures = mutableListOf<String>()
            var first: IOException? = null
            for (mode in HDR_TONE_MAP_MODES) {
                out.delete()
                try {
                    transcode(uri, out, width, height, trim, mode)
                    return
                } catch (e: IOException) {
                    first = first ?: e
                    failures += "${hdrModeName(mode)}: ${e.message}"
                }
            }
            // Every attempt's reason, not just the last: which mode failed how is exactly what
            // says whether a given phone can't decode the source or can't tone-map it.
            throw IOException(failures.joinToString("; "), first)
        }

        private suspend fun transcode(
            uri: Uri,
            out: File,
            width: Int,
            height: Int,
            trim: Boolean,
            hdrMode: Int,
        ) = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val mediaItem =
                    MediaItem.Builder()
                        .setUri(uri)
                        .apply {
                            if (trim) {
                                setClippingConfiguration(
                                    MediaItem.ClippingConfiguration.Builder().setEndPositionMs(PREVIEW_MAX_DURATION_MS).build(),
                                )
                            }
                        }.build()

                val videoEffects: List<Effect> =
                    listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))
                val edited =
                    EditedMediaItem.Builder(mediaItem)
                        .setRemoveAudio(true)
                        .setEffects(Effects(emptyList(), videoEffects))
                        .build()

                val encoderFactory =
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder()
                                .setBitrate(PREVIEW_VIDEO_BITRATE)
                                // Constant bitrate: the 60 s cap only bounds the *size* if the
                                // encoder actually holds the bitrate. A variable-bitrate
                                // encoder overshot a 250 kbps target by ~4x on detailed
                                // content (measured on an emulator's software encoder).
                                .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                                .build(),
                        )
                        .build()

                val transformer =
                    Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setEncoderFactory(encoderFactory)
                        // Not "streamable" output. By default the MP4 muxer writes `moov` at
                        // the *start* of the file, which means reserving a `free` box for it
                        // before it knows how big it'll be: a 4 s preview came out 525 KB,
                        // ~400 KB of it empty padding around 125 KB of actual video (found on
                        // a real emulator; see PreviewGeneratorInstrumentedTest). The preview
                        // is downloaded whole and played from a local file, so it has no use
                        // for progressive-download layout, and every byte here is encrypted,
                        // uploaded, and downloaded again per grid cell.
                        .setMuxerFactory(InAppMp4Muxer.Factory().setAttemptStreamableOutputEnabled(false))
                        .addListener(
                            object : Transformer.Listener {
                                override fun onCompleted(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                ) {
                                    if (continuation.isActive) continuation.resume(Unit)
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException,
                                ) {
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(
                                            IOException(
                                                // The error code name (ENCODER_INIT_FAILED, DECODING_FORMAT_UNSUPPORTED,
                                                // ...) is what actually says what went wrong on a given device.
                                                "${exportException.errorCodeName} (${exportException.message.orEmpty().take(110)})",
                                                exportException,
                                            ),
                                        )
                                    }
                                }
                            },
                        ).build()

                continuation.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post { transformer.cancel() }
                }
                val composition =
                    Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
                        .setHdrMode(hdrMode)
                        .build()
                transformer.start(composition, out.absolutePath)
            }
        }
    }
