package fr.enry.archivist.ui.preview

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.repo.PreviewCache
import fr.enry.archivist.data.repo.PreviewRef
import java.io.File

/** Provided once at the activity root (`MainActivity`) so grid cells and the detail pager
 * can reach the [PreviewCache] without every ViewModel growing a constructor parameter.
 * `null` (the default) means "no previews here" — used by previews/tests that don't set
 * it up, and every caller treats it as "keep showing the still". */
val LocalPreviewCache = staticCompositionLocalOf<PreviewCache?> { null }

/** The preview clip of [this] as something [PreviewCache] can fetch, or `null` for a
 * still, a video with no preview, or a locked/host-less state. The URL is under the
 * `thumbs` CloudFront behavior, same as a thumbnail's (`EncryptedThumbRef.url`). */
fun PhotoEntity.previewRef(host: String?): PreviewRef? {
    val entry = preview ?: return null
    if (host == null) return null
    return PreviewRef(photoId = photoId, url = "https://$host/thumbs/${entry.key}", iv = entry.iv, encDek = encDek)
}

/**
 * Plays a video's preview clip (design.md, "Video preview clip") muted and looping, over
 * whatever is drawn beneath it (the still thumbnail). Draws nothing over the still until the clip has
 * been fetched, decrypted, and its first frame drawn, so the still stays visible the
 * whole time and any failure (a missing object, a decrypt error, no decoder) is simply
 * invisible — the caller never has to handle one. (A [TextureView] is transparent until
 * the first frame is drawn, which is what keeps the still visible; see the comment where
 * it is created for why it must not be hidden instead.)
 *
 * At the end of each loop the picture fades to black over the last [PREVIEW_FADE_MS],
 * then restarts from the beginning ([fadeOutAlpha]). The fade is a black overlay drawn by
 * this composable, not baked into the file.
 *
 * [cover] crops to fill the bounds (grid cell); otherwise the clip is letterboxed to fit
 * (detail view). The clip's own aspect ratio comes from the player, not from the asset's
 * stored width/height, so a portrait video can't be stretched by a mismatch there.
 *
 * A [TextureView] rather than ExoPlayer's default `SurfaceView`: it composites correctly
 * inside a scrolling `LazyVerticalGrid`, which a surface does not.
 */
@Composable
fun VideoPreview(
    ref: PreviewRef,
    modifier: Modifier = Modifier,
    cover: Boolean = true,
) {
    val cache = LocalPreviewCache.current ?: return
    val file: File? by produceState<File?>(initialValue = null, ref) { value = cache.get(ref) }
    val playable = file ?: return

    val context = LocalContext.current
    val player =
        remember(playable) {
            ExoPlayer.Builder(context).build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItem(MediaItem.fromUri(Uri.fromFile(playable)))
                prepare()
                playWhenReady = true
            }
        }

    var firstFrameRendered by remember(player) { mutableStateOf(false) }
    var aspect by remember(player) { mutableFloatStateOf(0f) }
    var fade by remember(player) { mutableFloatStateOf(0f) }

    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    firstFrameRendered = true
                }

                /** The aspect ratio comes from the *track format*, which is known as soon as
                 * the media is prepared. It must not come from [onVideoSizeChanged] alone:
                 * ExoPlayer only reports a video size once it has rendered to a real surface,
                 * and the surface is the [TextureView] this composable only creates once it
                 * knows the aspect ratio -- each waiting on the other, so nothing ever played
                 * (found on a real emulator: decoders initialised, state READY, loops ticking
                 * over, no first frame, no video size). */
                override fun onTracksChanged(tracks: Tracks) {
                    val format =
                        tracks.groups
                            .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
                            ?.getTrackFormat(0)
                            ?: return
                    if (format.width > 0 && format.height > 0) {
                        val swap = format.rotationDegrees == 90 || format.rotationDegrees == 270
                        val w = if (swap) format.height else format.width
                        val h = if (swap) format.width else format.height
                        aspect = w * format.pixelWidthHeightRatio / h
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    }
                }
            }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, firstFrameRendered) {
        if (!firstFrameRendered) return@LaunchedEffect
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            fade = fadeOutAlpha(player.currentPosition, player.duration)
        }
    }

    BoxWithConstraints(modifier.clipToBounds()) {
        if (aspect > 0f) {
            val boundsAspect = maxWidth / maxHeight
            // `cover` fills the bounds (overflow is clipped); otherwise fit inside them.
            val widthLimited = if (cover) boundsAspect > aspect else boundsAspect <= aspect
            val width = if (widthLimited) maxWidth else maxHeight * aspect
            val height = if (widthLimited) maxWidth / aspect else maxHeight

            // Deliberately NOT hidden (`alpha(0f)`) until the first frame: a TextureView that
            // is never drawn never gets its SurfaceTexture, so the player never has a surface
            // to render to, so `onRenderedFirstFrame` never fires -- a deadlock (found on a
            // real emulator: four players created, no pixel ever changed). A TextureView is
            // transparent until it has content, so the still beneath shows through anyway.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .requiredSize(width, height),
            ) {
                AndroidView(
                    factory = { TextureView(it) },
                    update = { player.setVideoTextureView(it) },
                    modifier = Modifier.matchParentSize(),
                )
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = fade)))
            }
        }
    }
}
