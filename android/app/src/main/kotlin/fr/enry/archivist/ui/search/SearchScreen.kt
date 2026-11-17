package fr.enry.archivist.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.ui.detail.DetailScreen
import fr.enry.archivist.ui.timeline.PhotoCell
import fr.enry.archivist.ui.timeline.PlaceholderCell

/** The same minimum cell width as the timeline grid. */
private val RESULT_MIN_CELL = 96.dp

/**
 * Geolocation plan step 8: place search. Typing filters the owner's place vocabulary on
 * the device ([searchPlaces]); picking a suggestion shows that place's photos, newest
 * first, in a grid of the timeline's own cells; tapping one opens [DetailScreen], which
 * then swipes over the loaded results. Back steps out one level at a time: detail →
 * results → suggestions → the timeline.
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.onOpened() }

    val query by viewModel.query.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val hasPlaces by viewModel.hasPlaces.collectAsStateWithLifecycle()
    val host by viewModel.cdnHost.collectAsStateWithLifecycle()
    // Hoisted above the detail branch so the results and their scroll position survive
    // opening a photo and coming back (the same reason TimelineScreen hoists its own).
    val results = viewModel.results.collectAsLazyPagingItems()
    val gridState = rememberLazyGridState()

    var openPhoto by remember { mutableStateOf<PhotoEntity?>(null) }
    var swipeOrder by remember { mutableStateOf<List<PhotoEntity>>(emptyList()) }
    openPhoto?.let { photo ->
        DetailScreen(
            initialPhoto = photo,
            onBack = {
                openPhoto = null
                // A delete from the detail screen removes the photo server-side; the
                // result list has no cache to drop it from, so re-fetch.
                results.refresh()
            },
            swipeOrder = swipeOrder,
            modifier = modifier,
        )
        return
    }

    BackHandler {
        if (selected != null) viewModel.clearSelection() else onBack()
    }

    val place = selected
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (place != null) viewModel.clearSelection() else onBack() }) { Text("← Back") }
            if (place != null) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(place.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        place.subtitle(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (place == null) {
            SuggestionPane(
                query = query,
                onQueryChange = viewModel::onQueryChange,
                suggestions = suggestions,
                status = status,
                hasPlaces = hasPlaces,
                onRetry = viewModel::refresh,
                onPick = viewModel::select,
            )
        } else {
            val refresh = results.loadState.refresh
            // LazyPagingItems reports NotLoading with no items until collection starts, the
            // same shape as a real empty result; latch the first Loading per place so that
            // gap shows the spinner (see TimelineScreen's hasStartedLoading).
            var started by remember(place) { mutableStateOf(false) }
            if (refresh is LoadState.Loading) started = true
            when {
                results.itemCount == 0 && (refresh is LoadState.Loading || !started) ->
                    Centered { CircularProgressIndicator() }
                results.itemCount == 0 && refresh is LoadState.Error ->
                    Centered {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Couldn't load these photos", style = MaterialTheme.typography.titleMedium)
                            Text("Check your connection and try again.", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = results::retry) { Text("Retry") }
                        }
                    }
                results.itemCount == 0 ->
                    Centered { Text("No photos here any more.", style = MaterialTheme.typography.bodyLarge) }
                else ->
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(RESULT_MIN_CELL),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            count = results.itemCount,
                            key = results.itemKey { it.photoId },
                            contentType = results.itemContentType { "photo" },
                        ) { index ->
                            val photo = results[index]
                            if (photo == null) {
                                PlaceholderCell()
                            } else {
                                PhotoCell(photo, host, playPreview = false, onClick = {
                                    swipeOrder = results.itemSnapshotList.items
                                    openPhoto = photo
                                })
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun SuggestionPane(
    query: String,
    onQueryChange: (String) -> Unit,
    suggestions: List<PlaceSuggestion>,
    status: VocabularyStatus,
    hasPlaces: Boolean,
    onRetry: () -> Unit,
    onPick: (PlaceSuggestion) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Country, region or town") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focusRequester),
    )
    when {
        status == VocabularyStatus.LOADING -> Centered { CircularProgressIndicator() }
        status == VocabularyStatus.FAILED ->
            Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Couldn't load your places", style = MaterialTheme.typography.titleMedium)
                    Text("Check your connection and try again.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onRetry) { Text("Retry") }
                }
            }
        !hasPlaces -> Centered { Text("No photos with a location yet.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center) }
        suggestions.isEmpty() -> Centered { Text("No places match.", style = MaterialTheme.typography.bodyLarge) }
        else ->
            LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                items(suggestions, key = { "${it.level}:${it.facetValue}" }) { s ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                focusManager.clearFocus()
                                onPick(s)
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            s.subtitle(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider()
                }
            }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { content() }
}
