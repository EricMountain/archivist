package fr.enry.archivist.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale

/**
 * Timeline place filter step 4: the suggestion overlay's pieces. The field sits in the
 * `⋮` card (`MenuPanel`) in states B and C; [SuggestionPanel] is what C shows below it.
 * Every tick and keystroke is live (F6); there is no apply button.
 */

/** Test tags, shared with `SearchOverlayUiTest`. */
object SearchOverlayTags {
    const val FIELD = "place-search-field"
    const val STATUS = "place-search-status"
    const val LIST = "place-search-list"

    fun row(key: String) = "place-row-$key"
}

/**
 * The "Search places" field. [onFocused] fires when it gains focus (tapping it in B is how
 * C starts); the IME's Search action is [onImeSearch]. The × clears the query, which also
 * unticks everything (F7: the grid goes back to unfiltered).
 */
@Composable
fun PlaceSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onFocused: () -> Unit,
    onImeSearch: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search places") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onImeSearch() }),
        trailingIcon =
            if (query.isEmpty()) {
                null
            } else {
                {
                    // A text glyph: the project has no material-icons dependency.
                    TextButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.semantics { contentDescription = "Clear search" },
                    ) { Text("×", style = MaterialTheme.typography.titleLarge) }
                }
            },
        modifier =
            modifier
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) onFocused() }
                .testTag(SearchOverlayTags.FIELD),
    )
}

/**
 * The status line above the suggestions ("1,912 photos in 21 places"), or null when there
 * is nothing to count: the quick picks with nothing ticked, or a query matching nothing
 * (the list says "No places match." then).
 */
fun selectionStatusLine(
    selection: SelectionState,
    locale: Locale = Locale.getDefault(),
): String? {
    val format = NumberFormat.getIntegerInstance(locale)
    val places = selection.maximal.size
    return when {
        places > 0 -> {
            val photos = selection.photoCount
            "${format.format(photos)} ${if (photos == 1) "photo" else "photos"} in ${format.format(places)} ${if (places == 1) "place" else "places"}"
        }
        selection.isEmptyQuery -> null
        selection.tree.shown.isEmpty() -> null
        else -> "No places selected"
    }
}

/** The footer for more matches than the cap: "Showing 200 of 340 — type more to narrow". */
fun truncationFooter(
    tree: SelectionTree,
    locale: Locale = Locale.getDefault(),
): String? {
    if (!tree.truncated) return null
    val format = NumberFormat.getIntegerInstance(locale)
    return "Showing ${format.format(tree.shown.size)} of ${format.format(tree.total)} — type more to narrow"
}

/**
 * State C's panel: the status line, then the matches grouped country → region → town with
 * a checkbox each ("The suggestion panel"), or one of the vocabulary's states. At most
 * [maxHeight] tall (45 % of the height above the keyboard), scrolling inside.
 *
 * [loading]: the filtered grid's first pages are on their way; the status line shows a
 * small progress indicator.
 */
@Composable
fun SuggestionPanel(
    selection: SelectionState,
    status: VocabularyStatus,
    hasPlaces: Boolean,
    loading: Boolean,
    onToggle: (String) -> Unit,
    onSetAll: (groupKey: String, ticked: Boolean) -> Unit,
    onRetry: () -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        when {
            status == VocabularyStatus.LOADING ->
                PanelMessage { CircularProgressIndicator(Modifier.size(24.dp)) }
            status == VocabularyStatus.FAILED ->
                PanelMessage {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Couldn't load your places", style = MaterialTheme.typography.titleSmall)
                        Button(onClick = onRetry) { Text("Retry") }
                    }
                }
            !hasPlaces ->
                PanelMessage { Text("No located photos yet.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
            else -> {
                val line = selectionStatusLine(selection)
                if (line != null || loading) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            line.orEmpty(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).testTag(SearchOverlayTags.STATUS),
                        )
                        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
                if (selection.tree.shown.isEmpty()) {
                    PanelMessage { Text("No places match.", style = MaterialTheme.typography.bodyMedium) }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().testTag(SearchOverlayTags.LIST)) {
                        suggestionRows(selection, onToggle, onSetAll)
                        truncationFooter(selection.tree)?.let { footer ->
                            item(key = "footer") {
                                Text(
                                    footer,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The grouped rows, in the tree's order. Indent: country 0, region 1, town 2 (a town
 * with no linked region sits at 1, under its country). */
private fun LazyListScope.suggestionRows(
    selection: SelectionState,
    onToggle: (String) -> Unit,
    onSetAll: (String, Boolean) -> Unit,
) {
    for (country in selection.tree.countries) {
        item(key = country.key) {
            val entry = country.entry
            if (entry != null) {
                PlaceRow(entry, selection, indent = 0, onToggle)
            } else {
                GroupHeader(country.title, indent = 0) { ticked -> onSetAll(country.key, ticked) }
            }
        }
        for (child in country.children) {
            when (child) {
                is TownRow -> item(key = child.entry.key) { PlaceRow(child.entry, selection, indent = 1, onToggle) }
                is RegionGroup -> {
                    item(key = child.key) {
                        val entry = child.entry
                        if (entry != null) {
                            PlaceRow(entry, selection, indent = 1, onToggle)
                        } else {
                            GroupHeader(child.title, indent = 1) { ticked -> onSetAll(child.key, ticked) }
                        }
                    }
                    for (town in child.towns) item(key = town.key) { PlaceRow(town, selection, indent = 2, onToggle) }
                }
            }
        }
    }
}

private val INDENT = 16.dp

/**
 * One matched place with its checkbox. The whole row is the toggle (role Checkbox), so
 * TalkBack reads it as one control: "Paris, Île-de-France, France · 412, checked". An
 * implied entry (F4) is drawn ticked and disabled, captioned "included".
 */
@Composable
private fun PlaceRow(
    entry: PlaceSuggestion,
    selection: SelectionState,
    indent: Int,
    onToggle: (String) -> Unit,
) {
    val ticked = entry.key in selection.ticked
    val enabled = selection.isEnabled(entry.key)
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = ticked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle(entry.key) })
            .padding(start = 4.dp + INDENT * indent, end = 16.dp)
            .testTag(SearchOverlayTags.row(entry.key)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Null: the row's toggleable handles the tap and the semantics.
        Checkbox(checked = ticked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val caption = if (enabled) entry.subtitle() else "${entry.subtitle()} · included"
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A country or region that didn't match itself but holds matches: a plain header with
 * "All · None" over the entries under it. */
@Composable
private fun GroupHeader(
    title: String,
    indent: Int,
    onSetAll: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp + INDENT * indent, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        TextButton(onClick = { onSetAll(true) }, modifier = Modifier.semantics { contentDescription = "Select all in $title" }) { Text("All") }
        Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { onSetAll(false) }, modifier = Modifier.semantics { contentDescription = "Select none in $title" }) { Text("None") }
    }
}

@Composable
private fun PanelMessage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}
