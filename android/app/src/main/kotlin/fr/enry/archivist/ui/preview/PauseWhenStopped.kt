package fr.enry.archivist.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player

/**
 * Pauses [player] while the screen hosting it is stopped (app in the background, screen
 * off), and resumes it on return only if it was playing when it stopped -- so a video the
 * user paused in the detail view stays paused.
 *
 * Leaving the app (Home, switching apps) stops the activity without disposing its
 * composition, so a player released only in `onDispose` keeps decoding indefinitely: a
 * looping grid preview did exactly that on a real phone, burning ~30% CPU with no UI, and
 * Android then killed the whole process for "excessive CPU" / binder calls into a frozen
 * app -- taking any in-flight [fr.enry.archivist.sync.UploadWorker] with it, which is how
 * video uploads came to stall for hours.
 */
@Composable
fun PauseWhenStopped(player: Player) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        var resumeOnStart = player.playWhenReady
        // Created while already stopped: hold it until the first ON_START.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) player.playWhenReady = false
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        resumeOnStart = player.playWhenReady
                        player.playWhenReady = false
                    }
                    Lifecycle.Event.ON_START -> if (resumeOnStart) player.playWhenReady = true
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
