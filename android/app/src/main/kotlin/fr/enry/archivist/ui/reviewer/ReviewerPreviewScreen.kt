package fr.enry.archivist.ui.reviewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import fr.enry.archivist.sync.DeviceMediaFile

/**
 * Plan step 2.17: Play reviewer preview mode. Reachable from [fr.enry.archivist.ui.onboarding.ConnectScreen]
 * before any instance, sign-in or key unlock, and never constructs anything that could
 * reach the network — see [ReviewerPreviewViewModel]'s own doc for the actual guarantee,
 * which extends to [ReviewerSettingsScreen] too (its one exception, `StorageScreen`
 * reused as-is, is verified network-free — see `StorageRepository`'s own doc: it only
 * ever touches Coil's on-disk cache).
 *
 * No intro dialog here — the explanation lives on `ConnectScreen`, below its own
 * "Preview without an account" button, so the choice is made with the explanation
 * already in view rather than sprung on the user as a popup after tapping through.
 */
@Composable
fun ReviewerPreviewScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReviewerPreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<DeviceMediaFile?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        PreviewBanner(onExit, onSettings = { showSettings = true })

        val current = selected
        when {
            showSettings -> ReviewerSettingsScreen(onBack = { showSettings = false })
            current != null -> ReviewerDetail(file = current, onClose = { selected = null })
            state is ReviewerPreviewUiState.Loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state is ReviewerPreviewUiState.Empty ->
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No photos on this device", style = MaterialTheme.typography.bodyLarge)
                }
            else -> {
                val loaded = state as ReviewerPreviewUiState.Loaded
                ReviewerGrid(loaded.files, onItemClick = { selected = it })
            }
        }
    }
}

@Composable
private fun PreviewBanner(
    onExit: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // weight(1f) + a single line with ellipsis, not the two TextButtons below,
        // absorbs any width pressure -- without it, an unweighted Row squeezes
        // whichever child it measures last (here, "Exit preview") down to less than
        // its own text needs, wrapping it into an unreadable vertical sliver. Found
        // live on a Pixel 8a emulator, not from a layout preview.
        Text(
            "Preview — no account, nothing uploaded",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).align(Alignment.CenterVertically),
        )
        Row {
            TextButton(onClick = onSettings) { Text("Settings") }
            TextButton(onClick = onExit) { Text("Exit preview") }
        }
    }
}

@Composable
private fun ReviewerGrid(
    files: List<DeviceMediaFile>,
    onItemClick: (DeviceMediaFile) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(files, key = { it.contentUri }) { file ->
            Box(
                Modifier
                    .aspectRatio(1f)
                    .clickable { onItemClick(file) },
            ) {
                AsyncImage(
                    model = file.contentUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Full-screen pinch-zoom view of one file. A self-contained gesture, not a reuse of
 * `ui/detail/DetailScreen.kt`'s `ZoomableThumb` — that one is coupled to the encrypted
 * timeline's `PhotoEntity`/CDN-host model, and pulling it in here is exactly the kind of
 * reuse [ReviewerPreviewViewModel]'s doc warns against. */
@Composable
private fun ReviewerDetail(
    file: DeviceMediaFile,
    onClose: () -> Unit,
) {
    var scale by remember(file.contentUri) { mutableFloatStateOf(1f) }
    var offset by remember(file.contentUri) { mutableStateOf(Offset.Zero) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose) { Text("Close") }
        }
        Image(
            painter = rememberAsyncImagePainter(model = file.contentUri),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(file.contentUri) {
                        detectPinchZoom { zoomChange, panChange ->
                            val newScale = (scale * zoomChange).coerceIn(1f, 6f)
                            scale = newScale
                            offset = if (newScale <= 1f) Offset.Zero else offset + panChange
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
        DeviceFileInfo(file)
    }
}

@Composable
private fun DeviceFileInfo(file: DeviceMediaFile) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(file.displayName, style = MaterialTheme.typography.titleSmall)
        Text(
            "${file.size / 1024} KB",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Same two-pointer-only pinch-zoom detection as `DetailScreen.kt`'s `detectPinchZoom`
 * — see that one's doc for why not `detectTransformGestures`. Duplicated rather than
 * shared because sharing it would mean either this screen importing from `ui/detail`
 * (coupling a network-free screen to one that isn't) or hoisting it somewhere both can
 * reach for a four-line gesture loop, which isn't worth the indirection. */
private suspend fun PointerInputScope.detectPinchZoom(onTransform: (zoomChange: Float, panChange: Offset) -> Unit) {
    awaitEachGesture {
        var event = awaitPointerEvent()
        while (event.changes.any { it.pressed }) {
            if (event.changes.size >= 2) {
                onTransform(event.calculateZoom(), event.calculatePan())
                event.changes.forEach { it.consume() }
            }
            event = awaitPointerEvent()
        }
    }
}
