package fr.enry.archivist.ui.detail

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileOutputStream

/**
 * Plays a video original whose whole plaintext [bytes] were already decrypted by
 * [fr.enry.archivist.data.repo.PhotoDetailRepository.downloadOriginal] — see
 * [OriginalUiState.Ready]. ExoPlayer plays from a [Uri], not a byte array, so this writes
 * the plaintext once to a private cache file rather than adding a custom `DataSource`
 * just to avoid that; matches [OriginalOverlay]'s own image path, which likewise decodes
 * the whole in-memory byte array synchronously (`BitmapFactory.decodeByteArray`) rather
 * than streaming. The file lives under `cacheDir` (never `filesDir`/external storage) and
 * is deleted as soon as the player is released, so a decrypted plaintext frame never
 * outlives this screen on disk.
 */
@Composable
internal fun VideoPlayer(
    bytes: ByteArray,
    ext: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val file =
        remember(bytes) {
            File.createTempFile("original-", ".$ext", context.cacheDir).apply {
                FileOutputStream(this).use { it.write(bytes) }
            }
        }
    val exoPlayer =
        remember(file) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                playWhenReady = true
            }
        }

    DisposableEffect(exoPlayer, file) {
        onDispose {
            exoPlayer.release()
            file.delete()
        }
    }

    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer } },
        modifier = modifier,
    )
}
