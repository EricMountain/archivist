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

/**
 * Plays a video original whose plaintext was streamed to [file] by
 * [fr.enry.archivist.data.repo.PhotoDetailRepository.downloadOriginalToFile] -- see
 * [OriginalUiState.Ready]. The file lives under `cacheDir` (never `filesDir`/external storage)
 * and is deleted as soon as the player is released, so a decrypted plaintext frame never
 * outlives this screen on disk. (This used to take the whole plaintext as a `ByteArray` and
 * write it out itself, which is what ran the app out of memory on a large video.)
 */
@Composable
internal fun VideoPlayer(
    file: File,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

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
