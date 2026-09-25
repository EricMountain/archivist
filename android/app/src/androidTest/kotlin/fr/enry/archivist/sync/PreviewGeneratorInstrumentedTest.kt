package fr.enry.archivist.sync

import android.graphics.SurfaceTexture
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [TransformerPreviewGenerator] against the real media3 transformer and a real hardware/
 * software encoder — the part of the video preview clip (design.md, "Video preview clip")
 * no JVM test can reach. `PreviewGeneratorTest` (JVM) covers the sizing arithmetic.
 *
 * Sources are three small fixtures under `androidTest/assets` (generated with ffmpeg):
 * a 1280x720 clip **with an audio track**, a 640x360 clip carrying a 90 degree rotation
 * flag (displayed 360x640), and a 75 s clip (longer than the 60 s cap).
 *
 * Safe to run with `am instrument` against a device holding a real session — writes only
 * under the app's `cacheDir`. **Don't run it via `connectedDebugAndroidTest`**: that
 * uninstalls the app afterwards, taking its data with it (android/AGENTS.md).
 */
@RunWith(AndroidJUnit4::class)
class PreviewGeneratorInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val generator = TransformerPreviewGenerator(context)
    private val cleanup = mutableListOf<File>()

    @After
    fun tearDown() {
        cleanup.forEach { it.delete() }
    }

    private fun asset(name: String): String {
        context.cacheDir.mkdirs()
        val file = File(context.cacheDir, "test-$name").also { cleanup += it }
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(file).toString()
    }

    private fun write(clip: PreviewClip): File = File(context.cacheDir.also { it.mkdirs() }, "test-out-${System.nanoTime()}.mp4").also {
        it.writeBytes(clip.bytes)
        cleanup += it
    }

    private class Meta(val width: Int, val height: Int, val durationMs: Long, val hasAudio: Boolean, val rotation: Int)

    private fun meta(file: File): Meta {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            fun i(k: Int) = r.extractMetadata(k)?.toInt() ?: 0
            return Meta(
                width = i(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                height = i(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong(),
                hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null,
                rotation = i(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
            )
        } finally {
            r.release()
        }
    }

    private fun displayed(m: Meta) = if (m.rotation == 90 || m.rotation == 270) m.height to m.width else m.width to m.height

    @Test
    fun landscapeSourceBecomesAMutedH264ClipAt200pxLongestEdge() =
        runBlocking {
            val clip = generator.generate(asset("preview-src-landscape.mp4"))
            val file = write(clip)
            val m = meta(file)

            assertEquals("reported dimensions", 200 to 112, clip.width to clip.height)
            assertEquals("actual dimensions of the file", 200 to 112, displayed(m))
            assertTrue("no audio track: ${m.hasAudio}", !m.hasAudio)
            assertTrue("whole 4 s clip kept, was ${m.durationMs} ms", m.durationMs in 3_500..4_600)
            // 250 kbps for 4 s is ~125 KB. The bound is deliberately tight enough to catch the
            // muxer's empty `moov` reservation, which once made this file 525 KB.
            assertTrue("preview is ${clip.bytes.size} bytes", clip.bytes.size in 1_000..200_000)

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                assertEquals("exactly one track", 1, extractor.trackCount)
                assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
            } finally {
                extractor.release()
            }
        }

    @Test
    fun aRotatedSourceComesOutTheRightWayUp() =
        runBlocking {
            // Stored 640x360 with a 90 degree rotation flag: displayed 360x640 (portrait).
            val clip = generator.generate(asset("preview-src-rotated.mp4"))
            val m = meta(write(clip))

            assertEquals("reported dimensions are the displayed (portrait) ones", 112 to 200, clip.width to clip.height)
            assertEquals("the file displays portrait, whether by pixels or by a rotation flag", 112 to 200, displayed(m))
        }

    @Test
    fun aSourceLongerThanAMinuteIsTrimmedToItsFirstMinute() =
        runBlocking {
            val clip = generator.generate(asset("preview-src-long.mp4"))
            val m = meta(write(clip))

            assertTrue("75 s source should come out ~60 s, was ${m.durationMs} ms", m.durationMs in 58_000..62_000)
            // The whole point of the cap: 60 s at ~250 kbps is ~1.9 MB, not the many MB an
            // unbounded muxer reservation or a runaway encoder would produce.
            assertTrue("60 s preview is ${clip.bytes.size} bytes", clip.bytes.size in 1_000..2_600_000)
        }

    @Test
    fun theFramesAreNotBlack() =
        runBlocking {
            val file = write(generator.generate(asset("preview-src-landscape.mp4")))
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                val frame = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
                val pixels = IntArray(16 * 16)
                android.graphics.Bitmap.createScaledBitmap(frame, 16, 16, true).getPixels(pixels, 0, 16, 0, 0, 16, 16)
                assertTrue("mean luma ${meanLuma(pixels)}", meanLuma(pixels) > DARK_FRAME_LUMA)
            } finally {
                r.release()
            }
        }

    /** "Strip location on upload" (design.md) exists to keep GPS out of what an upload
     * stores. The preview is a *new* transcoded file, and the media3 muxer can carry a
     * source's MP4 location metadata across — so it must be proven not to, for both the
     * `mdta` keys style and the older `udta` `xyz` style. The coordinates are a distinctive
     * string, present in each source (asserted below), that must be absent from the output. */
    @Test
    fun theSourcesLocationDoesNotLeakIntoThePreview() =
        runBlocking {
            for (name in listOf("preview-src-location.mp4", "preview-src-location-udta.mp4")) {
                val source = File(asset(name).removePrefix("file://")).readBytes()
                assertTrue("$name should carry the coordinates (fixture check)", String(source, Charsets.ISO_8859_1).contains("+48.8584"))

                val out = String(generator.generate(asset(name)).bytes, Charsets.ISO_8859_1)
                assertTrue("$name: coordinates leaked into the preview", !out.contains("+48.8584"))
                assertTrue("$name: a location box leaked into the preview", !out.contains("\u00a9xyz") && !out.contains("loci"))
            }
        }

    /** Real phone footage is not an ffmpeg test pattern: modern phones record 10-bit HDR HEVC
     * (HLG) and 60 fps. A user's repair of a real video reported "the preview clip couldn't be
     * generated" while every synthetic H.264 fixture above worked. */
    @Test
    fun tenBitHdrHevcSourceProducesAnSdrH264Preview() =
        runBlocking {
            val clip =
                try {
                    generator.generate(asset("preview-src-hevc-hdr.mp4"))
                } catch (e: IOException) {
                    // An emulator's software decoder can't decode 10-bit HLG at all, so
                    // neither tone-mapping mode can be exercised there. That is a device
                    // limitation, not a verdict on the code -- skipped, loudly, not passed.
                    // Any *other* failure still fails this test.
                    org.junit.Assume.assumeFalse(
                        "this device cannot decode 10-bit HDR HEVC: ${e.message}",
                        e.message.orEmpty().contains("DECODING_FORMAT_UNSUPPORTED"),
                    )
                    throw e
                }
            val m = meta(write(clip))
            assertEquals(200 to 112, displayed(m))
            assertTrue("preview is ${clip.bytes.size} bytes", clip.bytes.size in 1_000..200_000)
        }

    @Test
    fun sixtyFpsSourceProducesAPreview() =
        runBlocking {
            val clip = generator.generate(asset("preview-src-h264-60fps.mp4"))
            assertEquals(200 to 112, displayed(meta(write(clip))))
        }

    @Test
    fun aMissingSourceFailsWithAnIOExceptionInsteadOfHanging() {
        try {
            runBlocking { generator.generate(Uri.fromFile(File(context.cacheDir, "does-not-exist.mp4")).toString()) }
            fail("expected an IOException")
        } catch (expected: IOException) {
            // fine
        }
    }

    /** The point of the whole exercise: the produced file decodes and plays, muted, and
     * loops. ExoPlayer is pointed at a throwaway off-screen surface — enough for the
     * decoder to run and for `onRenderedFirstFrame` to fire. */
    @Test
    fun theProducedClipPlaysMutedAndLoops() {
        val clip = runBlocking { generator.generate(asset("preview-src-landscape.mp4")) }
        val file = write(clip)

        val firstFrame = CountDownLatch(1)
        val looped = CountDownLatch(1)
        var videoSize: VideoSize? = null
        var volume = -1f
        var repeat = -1
        var player: ExoPlayer? = null
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)

        instrumentation.runOnMainSync {
            player =
                ExoPlayer.Builder(context).build().apply {
                    this.volume = 0f
                    repeatMode = Player.REPEAT_MODE_ONE
                    setVideoSurface(surface)
                    addListener(
                        object : Player.Listener {
                            override fun onRenderedFirstFrame() = firstFrame.countDown()

                            override fun onVideoSizeChanged(size: VideoSize) {
                                videoSize = size
                            }

                            override fun onPositionDiscontinuity(
                                oldPosition: Player.PositionInfo,
                                newPosition: Player.PositionInfo,
                                reason: Int,
                            ) {
                                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) looped.countDown()
                            }
                        },
                    )
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    prepare()
                    playWhenReady = true
                }
        }
        try {
            assertTrue("no first frame within 10 s", firstFrame.await(10, TimeUnit.SECONDS))
            assertTrue("no loop within 10 s of a ~4 s clip", looped.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                volume = player!!.volume
                repeat = player!!.repeatMode
            }
            assertEquals(0f, volume, 0f)
            assertEquals(Player.REPEAT_MODE_ONE, repeat)
            assertEquals(200 to 112, videoSize!!.width to videoSize!!.height)
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            surface.release()
            texture.release()
        }
    }
}
