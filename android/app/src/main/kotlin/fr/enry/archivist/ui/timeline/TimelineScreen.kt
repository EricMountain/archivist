package fr.enry.archivist.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
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
import fr.enry.archivist.data.repo.TimelineBounds
import fr.enry.archivist.ui.detail.DetailScreen
import fr.enry.archivist.ui.onboarding.EnrolmentScreen
import fr.enry.archivist.ui.onboarding.EnrolmentViewModel
import fr.enry.archivist.ui.settings.SettingsScreen
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
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
    onSessionEnded: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val locked by viewModel.locked.collectAsStateWithLifecycle()
    if (locked) {
        // hiltViewModel() here resolves to the *same* EnrolmentViewModel instance the
        // original sign-in flow created -- this app has no navigation library, so every
        // call site is keyed only by class name against MainActivity's own
        // ViewModelStore (see AGENTS.md's "hiltViewModel() ... resolves to the
        // Activity's own ViewModelStore" note, same bug class as DetailViewModel's
        // dismissDelete()). Its uiState can therefore still read Unlocked from *before*
        // this lock, and init{} won't rerun on a cached instance -- so this screen has
        // to force a fresh checkStep() itself rather than trust the stale state, or the
        // app hangs on a spinner forever with nothing left to re-check it. checkStep()
        // is what actually re-populates MasterKeyHolder; onUnlocked is deliberately a
        // no-op here since TimelineViewModel.locked flipping back to false (once
        // checkStep() succeeds) is what un-mounts this branch on its own.
        val enrolmentViewModel: EnrolmentViewModel = hiltViewModel()
        LaunchedEffect(Unit) { enrolmentViewModel.checkStep() }
        EnrolmentScreen(onUnlocked = {}, modifier = modifier, viewModel = enrolmentViewModel)
        return
    }

    val items = viewModel.timeline.collectAsLazyPagingItems()
    val host by viewModel.cdnHost.collectAsStateWithLifecycle()
    val bounds by viewModel.bounds.collectAsStateWithLifecycle()

    // Hoisted above the selectedPhotoId branch below (rather than left for
    // LazyVerticalGrid to create its own default one down in TimelineItemGrid) so it
    // survives a round trip through DetailScreen: a composable that leaves composition
    // entirely -- which TimelineGrid does whenever DetailScreen is showing, since the
    // `return` below skips over it -- has its own `remember`ed state discarded and
    // recreated from scratch next time, which without this hoist reset scroll position
    // to the top on every "open a photo, then back out".
    val gridState = rememberLazyGridState()

    // The grid lands on a jumped-to window only once TimelineViewModel says it is
    // actually committed to Room, and on the photo at the requested instant rather than
    // on index 0 — a jump across a gap in the library lands on the boundary, with later
    // photos still above it. Deliberately a one-shot event rather than a LaunchedEffect
    // keyed on load state: keying on items.itemCount re-ran this on every page that
    // loaded afterwards, yanking the grid back to the top mid-scroll.
    LaunchedEffect(Unit) {
        viewModel.jumpCompleted.collect { landOnPhotoId ->
            val index = landOnPhotoId?.let { awaitPhotoIndex(items, it) } ?: 0
            gridState.scrollToItem(index)
        }
    }

    // Plan step 2.12: which photo the detail screen is open on, if any. Plain local
    // state, not a nav-library back stack -- this app has none yet (see MainActivity's
    // own note), same pattern every other screen transition here already uses.
    var selectedPhotoId by remember { mutableStateOf<String?>(null) }
    val openPhotoId = selectedPhotoId
    if (openPhotoId != null) {
        DetailScreen(initialPhotoId = openPhotoId, onBack = { selectedPhotoId = null }, modifier = modifier)
        return
    }

    // Plan step 2.14: Settings (which now also hosts Trash — see its own doc) is the
    // permanent entry point 2.13 deferred. Same "standalone screen, plain local
    // toggle" pattern as selectedPhotoId above.
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false }, onSessionEnded = onSessionEnded, modifier = modifier)
        return
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showSettings = true }) { Text("Settings") }
        }
        TimelineGrid(
            items = items,
            host = host,
            gridState = gridState,
            bounds = bounds,
            onPhotoClick = { selectedPhotoId = it },
            onJump = viewModel::onJumpRequested,
            modifier = Modifier.weight(1f),
        )
    }
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
    gridState: LazyGridState,
    bounds: TimelineBounds?,
    onPhotoClick: (String) -> Unit,
    onJump: (Instant?) -> Unit,
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

        else ->
            Box(modifier.fillMaxSize()) {
                // The host view draws its own fading scroll indicator over any scrollable
                // content, which has nothing to do with (and doesn't agree with) the
                // time-based one below — Compose exposes no way to opt a single lazy
                // layout out of it, so it's turned off at the View that actually draws it.
                val view = LocalView.current
                LaunchedEffect(view) {
                    view.isVerticalScrollBarEnabled = false
                    view.isHorizontalScrollBarEnabled = false
                }
                TimelineItemGrid(items, host, gridState, onPhotoClick, Modifier.fillMaxSize())
                TimelineScrollbar(
                    gridState = gridState,
                    items = items,
                    bounds = bounds,
                    onJump = onJump,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
    }
}

@Composable
private fun TimelineItemGrid(
    items: LazyPagingItems<TimelineItem>,
    host: String?,
    gridState: LazyGridState,
    onPhotoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        state = gridState,
        modifier = modifier,
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

/**
 * The reseeded window arrives asynchronously — Room's invalidation, a new `Pager`
 * generation, then composition — so the photo to land on isn't in [items] the instant
 * the jump reports success. Polls for it rather than keying off `itemCount`, which can
 * coincidentally match the outgoing window's and never signal at all. Gives up rather
 * than hanging if the photo never shows (a jump whose window was immediately replaced).
 */
private suspend fun awaitPhotoIndex(
    items: LazyPagingItems<TimelineItem>,
    photoId: String,
): Int? {
    repeat(60) {
        indexOfPhoto(items, photoId)?.let { return it }
        delay(50)
    }
    return null
}

/** Lands on the day header above the photo when there is one, so the date the jump was
 * aimed at is the first thing on screen rather than the row under it. */
private fun indexOfPhoto(
    items: LazyPagingItems<TimelineItem>,
    photoId: String,
): Int? {
    for (i in 0 until items.itemCount) {
        val item = items.peek(i)
        if (item is TimelineItem.Photo && item.photo.photoId == photoId) {
            return if (i > 0 && items.peek(i - 1) is TimelineItem.Header) i - 1 else i
        }
    }
    return null
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
