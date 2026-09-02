package fr.enry.archivist.ui.detail

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import fr.enry.archivist.crypto.EncryptedThumbRef
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.repo.PhotoDetail
import fr.enry.archivist.data.repo.RenditionSummary
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Plan step 2.12: full-screen single-photo view reached by tapping a grid cell in
 * [fr.enry.archivist.ui.timeline.TimelineScreen]. Swiping is over
 * [DetailViewModel.photos] (Room's plain timeline order), not the grid's own `Paging`
 * flow — see that field's doc.
 */
@Composable
fun DetailScreen(
    initialPhotoId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val originals by viewModel.originals.collectAsStateWithLifecycle()
    val host by viewModel.cdnHost.collectAsStateWithLifecycle()

    if (photos.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    // Computed once, from the list as it stood when the screen opened -- a photo
    // arriving or leaving mid-session (a concurrent upload, a delete on another
    // device) can shift indices under the pager, same known limitation as any plain
    // index-based swipe view; not something plan step 2.12's "Done when" calls out.
    val initialPage = remember { photos.indexOfFirst { it.photoId == initialPhotoId }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = initialPage) { photos.size }
    val currentPhoto = photos.getOrNull(pagerState.currentPage.coerceIn(0, photos.lastIndex))

    LaunchedEffect(pagerState.currentPage, photos.size) {
        photos.getOrNull(pagerState.currentPage)?.let { viewModel.ensureDetail(it.photoId) }
    }

    Column(modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("← Back") }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            key = { photos[it].photoId },
        ) { page ->
            ZoomableThumb(photo = photos[page], host = host)
        }

        currentPhoto?.let { photo ->
            MetadataPanel(
                state = details[photo.photoId],
                photo = photo,
                onViewOriginal = { rendition, encDek -> viewModel.viewOriginal(photo.photoId, encDek, rendition) },
                originals = originals,
            )
        }
    }

    val currentDetail = currentPhoto?.let { photo -> (details[photo.photoId] as? PhotoDetailUiState.Loaded)?.detail }
    val activeOriginal = currentDetail?.renditions?.firstNotNullOfOrNull { r -> originals[r.renditionId] }
    if (activeOriginal != null) {
        OriginalOverlay(
            state = activeOriginal,
            onDismiss = { currentDetail.renditions.forEach { viewModel.dismissOriginal(it.renditionId) } },
        )
    }
}

/** Pinch-zoom over the 2048 rung — the "2048 thumbnail, pinch-zoom" of plan step 2.12's
 * own "Details". Zoom/pan state is local to this composable and resets whenever
 * [HorizontalPager] recomposes it for a different page (each page gets its own
 * composition slot, so there's nothing to reset explicitly).
 *
 * **Deliberately not `detectTransformGestures`** — that function starts consuming pan
 * deltas from a *single* pointer (it treats a one-finger drag as pan, not just
 * two-finger pinch), which swallows the exact single-finger drag [HorizontalPager]
 * needs to swipe between photos. Confirmed live, not from documentation: with
 * `detectTransformGestures`, a real one-finger swipe across the image did nothing —
 * the pager below it never saw the gesture. The loop here only claims events (and only
 * then calls `consume()`, which is what actually blocks the pager from also seeing
 * them) once a *second* pointer joins, so an ordinary one-finger swipe reaches the
 * pager exactly as if this modifier weren't here at all. */
@Composable
private fun ZoomableThumb(
    photo: PhotoEntity,
    host: String?,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val longestEdge = photo.thumbs.keys.maxOrNull()
    val entry = longestEdge?.let { photo.thumbs[it] }
    if (host == null || entry == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }

    AsyncImage(
        model =
            EncryptedThumbRef(
                photoId = photo.photoId,
                longestEdge = longestEdge,
                url = "https://$host/thumbs/${entry.key}",
                iv = entry.iv,
                encDek = photo.encDek,
            ),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        var event = awaitPointerEvent()
                        while (event.changes.any { it.pressed }) {
                            if (event.changes.size >= 2) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val newScale = (scale * zoomChange).coerceIn(1f, 6f)
                                scale = newScale
                                offset = if (newScale <= 1f) Offset.Zero else offset + panChange
                                event.changes.forEach { it.consume() }
                            }
                            event = awaitPointerEvent()
                        }
                    }
                }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
    )
}

@Composable
private fun MetadataPanel(
    state: PhotoDetailUiState?,
    photo: PhotoEntity,
    onViewOriginal: (RenditionSummary, String) -> Unit,
    originals: Map<String, OriginalUiState>,
) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (state) {
            null, PhotoDetailUiState.Loading ->
                Text(formatDate(photo.takenAt, photo.tzOffsetMin, approximate = false))

            is PhotoDetailUiState.Error ->
                Text("Couldn't load full details: ${state.message}", style = MaterialTheme.typography.bodySmall)

            is PhotoDetailUiState.Loaded -> {
                val detail = state.detail
                Text(
                    formatDate(detail.takenAt, detail.tzOffsetMin, approximate = detail.takenAtSrc != "exif"),
                    style = MaterialTheme.typography.titleSmall,
                )
                val camera = listOfNotNull(detail.cameraMake, detail.cameraModel).joinToString(" ")
                if (camera.isNotBlank()) Text(camera, style = MaterialTheme.typography.bodySmall)
                Text("${detail.width} × ${detail.height}", style = MaterialTheme.typography.bodySmall)
                if (detail.renditions.isNotEmpty()) {
                    val sizeBytes = primarySizeBytes(detail)
                    Text(
                        detail.renditions.joinToString(" · ") { it.label() } +
                            (sizeBytes?.let { " · ${formatBytes(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    detail.renditions.forEach { rendition ->
                        val busy = originals[rendition.renditionId] is OriginalUiState.Loading
                        TextButton(
                            onClick = { onViewOriginal(rendition, detail.encDek) },
                            enabled = !busy,
                        ) { Text(if (busy) "Loading original…" else "View original (${rendition.label()})") }
                    }
                }
            }
        }
    }
}

@Composable
private fun OriginalOverlay(
    state: OriginalUiState,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is OriginalUiState.Loading -> CircularProgressIndicator()
            is OriginalUiState.Error ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load the original: ${state.message}")
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            is OriginalUiState.Ready -> {
                val bitmap = remember(state.bytes) { BitmapFactory.decodeByteArray(state.bytes, 0, state.bytes.size) }
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    // statusBarsPadding() only on the button, not the whole Column --
                    // the image itself stays full-bleed under the status bar (ordinary
                    // photo-viewer UX), but the tap target needs to clear it. Without
                    // this the button rendered directly under the clock/battery icons
                    // at the very top edge -- confirmed live (Pixel 8a emulator): taps
                    // there landed inconsistently even though the button was visibly
                    // (if partially) on screen, unlike this screen's own "Back" button
                    // one level up, which sits below the status bar for free via
                    // MainActivity's Scaffold `innerPadding` -- OriginalOverlay draws
                    // outside that padded modifier chain on purpose (a full-bleed
                    // Box(Modifier.fillMaxSize())), so it doesn't inherit that safety.
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End).statusBarsPadding(),
                    ) { Text("Close") }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        // A RAW/sidecar rendition (CR3, DNG, XMP...) has no format
                        // Android's own Bitmap decoder understands -- this is a real,
                        // expected outcome for those, not a bug. design.md doesn't
                        // specify an in-app RAW viewer, so this is as far as this step
                        // goes for that case.
                        Text("This file's format can't be previewed in-app.")
                    }
                }
            }
        }
    }
}

internal fun RenditionSummary.label(): String = if (role == "raw") "RAW" else extensionLabels[ext.lowercase()] ?: ext.uppercase()

internal val extensionLabels =
    mapOf(
        "jpg" to "JPEG",
        "jpeg" to "JPEG",
        "heic" to "HEIC",
        "heif" to "HEIF",
        "png" to "PNG",
        "webp" to "WebP",
        "mp4" to "MP4",
        "mov" to "MOV",
    )

/** The primary rendition's size when known, else the largest rendition's -- there's no
 * single obviously-right answer for "the" size of a multi-rendition asset, and the
 * primary is the one the grid/detail image itself actually shows. */
internal fun primarySizeBytes(detail: PhotoDetail): Long? =
    detail.renditions.find { it.renditionId == detail.primaryRend }?.plainBytes
        ?: detail.renditions.maxByOrNull { it.plainBytes }?.plainBytes

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
}

internal val DATE_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())

/** Plan step 2.12's own "Done when": an approximate-date marker whenever the date
 * didn't come from EXIF ([approximate]). Local time via [tzOffsetMin], matching the
 * grid's own local-day grouping (`TimelineViewModel.localDate`), not UTC. */
internal fun formatDate(
    takenAt: String,
    tzOffsetMin: Int,
    approximate: Boolean,
): String {
    val formatted = Instant.parse(takenAt).atOffset(ZoneOffset.ofTotalSeconds(tzOffsetMin * 60)).format(DATE_FORMATTER)
    return if (approximate) "$formatted (approximate)" else formatted
}
