package fr.enry.archivist.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import fr.enry.archivist.crypto.EncryptedThumbRef
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.ui.detail.DetailScreen
import fr.enry.archivist.ui.onboarding.EnrolmentScreen
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The rung shown in the grid — matches [fr.enry.archivist.sync.Thumbnailer]'s smallest
 * rung, per android.md's "load the 256 thumbnail for instant paint". */
private const val GRID_THUMB_SIZE = 256

/**
 * Plan step 2.11: the justified-grid timeline, Paging 3 over Room. Reuses
 * [EnrolmentScreen] wholesale for the locked state's "unlock action" (its own
 * `determineStep()` already tries a silent unlock first) rather than building a second
 * unlock ceremony — see [TimelineViewModel.locked]'s doc for why this screen is what
 * actually checks the master key continuously, unlike `MainActivity`'s own gate.
 */
@Composable
fun TimelineScreen(
    modifier: Modifier = Modifier,
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val locked by viewModel.locked.collectAsStateWithLifecycle()
    if (locked) {
        EnrolmentScreen(onUnlocked = {}, modifier = modifier)
        return
    }

    val items = viewModel.timeline.collectAsLazyPagingItems()
    val host by viewModel.cdnHost.collectAsStateWithLifecycle()

    // Plan step 2.12: which photo the detail screen is open on, if any. Plain local
    // state, not a nav-library back stack -- this app has none yet (see MainActivity's
    // own note), same pattern every other screen transition here already uses.
    var selectedPhotoId by remember { mutableStateOf<String?>(null) }
    val openPhotoId = selectedPhotoId
    if (openPhotoId != null) {
        DetailScreen(initialPhotoId = openPhotoId, onBack = { selectedPhotoId = null }, modifier = modifier)
        return
    }

    TimelineGrid(items = items, host = host, onPhotoClick = { selectedPhotoId = it }, modifier = modifier)
}

/**
 * A brand-new library (nothing uploaded yet — the ordinary state right after signing in
 * on a fresh device, per this session's own live check against the `dev` instance's
 * DynamoDB table) looks identical to a stuck loading spinner or a silently-failed
 * `RemoteMediator` unless the three are told apart explicitly. `LazyPagingItems.loadState.refresh`
 * is the only signal that distinguishes "still loading page one" from "loaded, and
 * there's truly nothing" from "the fetch failed" — `itemCount == 0` alone can't.
 */
@Composable
private fun TimelineGrid(
    items: LazyPagingItems<TimelineItem>,
    host: String?,
    onPhotoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val refreshState = items.loadState.refresh
    when {
        items.itemCount == 0 && refreshState is LoadState.Loading ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        items.itemCount == 0 && refreshState is LoadState.Error ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Couldn't load your photos", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Check your connection and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = items::retry) { Text("Retry") }
                }
            }

        items.itemCount == 0 ->
            Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No photos yet. Back up a folder in Settings to get started.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }

        else -> TimelineItemGrid(items, host, onPhotoClick, modifier)
    }
}

@Composable
private fun TimelineItemGrid(
    items: LazyPagingItems<TimelineItem>,
    host: String?,
    onPhotoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(
            count = items.itemCount,
            // A Header's date alone isn't a unique key: the same calendar date can
            // recur non-adjacently in the list (UTC sort order vs. local-day
            // grouping aren't monotonic once tzOffsetMin varies between photos) --
            // see TimelineItem.Header's own doc for why anchorPhotoId exists.
            key = items.itemKey { item -> if (item is TimelineItem.Header) "header-${item.anchorPhotoId}" else (item as TimelineItem.Photo).photo.photoId },
            contentType = items.itemContentType { item -> if (item is TimelineItem.Header) "header" else "photo" },
            span = { index -> if (items.peek(index) is TimelineItem.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
        ) { index ->
            when (val item = items[index]) {
                is TimelineItem.Header -> DateHeader(item)
                is TimelineItem.Photo -> PhotoCell(item.photo, host, onClick = { onPhotoClick(item.photo.photoId) })
                null -> PlaceholderCell()
            }
        }
    }
}

@Composable
private fun DateHeader(header: TimelineItem.Header) {
    Text(
        text = header.date.format(HEADER_FORMATTER),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Prefers [GRID_THUMB_SIZE]; falls back to the smallest available rung for an asset
 * that's missing it — e.g. a plain-attach rendition, which (per plan step 1.9's own
 * known gap) never gets thumbnails uploaded at all, leaving [PhotoEntity.thumbs] empty. */
@Composable
private fun PhotoCell(
    photo: PhotoEntity,
    host: String?,
    onClick: () -> Unit,
) {
    val chosen =
        photo.thumbs[GRID_THUMB_SIZE]?.let { GRID_THUMB_SIZE to it }
            ?: photo.thumbs.entries.minByOrNull { it.key }?.let { it.key to it.value }
    if (host == null || chosen == null) {
        PlaceholderCell()
        return
    }
    val (longestEdge, entry) = chosen
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
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable(onClick = onClick),
    )
}

@Composable
private fun PlaceholderCell() {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant))
}

private val HEADER_FORMATTER = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
