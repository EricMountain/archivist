package fr.enry.archivist.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.enry.archivist.ui.search.OverlayEvent
import fr.enry.archivist.ui.search.OverlayMode
import fr.enry.archivist.ui.search.PlaceFilter
import fr.enry.archivist.ui.search.PlaceSearchField
import java.text.NumberFormat
import java.util.Locale

/** The `⋮` button's size and its margin from the screen's edges; the chip and the rail's
 * track top are laid out against them. */
internal val MENU_MARGIN = 8.dp
internal val MENU_BUTTON_SIZE = 40.dp

/** The card's width in B (plan "States"): `min(width − 16dp, 360dp)`. */
private val MENU_CARD_MAX_WIDTH = 360.dp

/**
 * Timeline place filter step 4: the floating `⋮` menu (states A–C, see
 * [fr.enry.archivist.ui.search.reduceOverlay]). In A it is the 40 dp `⋮` button. In B the
 * button grows into a card anchored top-end: the "Search places" field, unfocused, then
 * the menu entries (Settings). In C the field has focus, the entries fade out (150 ms),
 * the card widens to the whole top band, and [panel] (the suggestions) shows below the
 * field.
 *
 * [availableWidth] is the width of the band the card can use (the screen less its
 * margins). The field is one composable across B and C, so focusing it doesn't recreate
 * it; tapping it in B is what moves to C ([OverlayEvent.FieldTapped]).
 */
@Composable
internal fun MenuPanel(
    mode: OverlayMode,
    query: String,
    availableWidth: Dp,
    focusRequester: FocusRequester,
    onEvent: (OverlayEvent) -> Unit,
    onQueryChange: (String) -> Unit,
    panel: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (mode == OverlayMode.COLLAPSED) {
        // A text glyph: the project has no material-icons dependency.
        Box(
            modifier
                .size(MENU_BUTTON_SIZE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                .clickable(role = Role.Button) { onEvent(OverlayEvent.MenuButton) }
                .semantics { contentDescription = "More options" },
            contentAlignment = Alignment.Center,
        ) {
            Text("⋮", style = MaterialTheme.typography.titleLarge)
        }
        return
    }

    val searching = mode == OverlayMode.SEARCHING
    val cardWidth by animateDpAsState(
        targetValue = if (searching) availableWidth else minOf(availableWidth, MENU_CARD_MAX_WIDTH),
        animationSpec = tween(150),
        label = "menuCardWidth",
    )
    val keyboard = LocalSoftwareKeyboardController.current
    // Entering C from the chip's body (the field isn't focused then), or from B's field.
    LaunchedEffect(searching) {
        if (searching) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        // First for TalkBack: the field, then the entries or suggestions, then the grid.
        modifier = modifier.width(cardWidth).semantics { traversalIndex = -1f },
    ) {
        Column(Modifier.animateContentSize(tween(150))) {
            PlaceSearchField(
                query = query,
                onQueryChange = onQueryChange,
                onFocused = { onEvent(OverlayEvent.FieldTapped) },
                onImeSearch = { onEvent(OverlayEvent.ImeSearch) },
                focusRequester = focusRequester,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            )
            AnimatedVisibility(
                visible = !searching,
                enter = fadeIn(tween(150)) + expandVertically(tween(150)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(150)),
            ) {
                Column {
                    MenuEntry("Settings") { onEvent(OverlayEvent.SettingsPicked) }
                }
            }
            if (searching) panel()
        }
    }
}

@Composable
private fun MenuEntry(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
    )
}

/**
 * The active filter's chip text (F8), without the ✕: "Kyoto · 4" for one place,
 * "paris · 21 places · 1,912" for several (the query, how many places, Σ `n`), and
 * "zzz · no places" for a filter that selects nothing.
 */
internal fun chipLabel(
    filter: PlaceFilter,
    locale: Locale = Locale.getDefault(),
): String {
    val format = NumberFormat.getIntegerInstance(locale)
    return when (filter.places.size) {
        0 -> "${filter.query} · no places"
        1 -> "${filter.places.single().name} · ${format.format(filter.photoCount)}"
        else -> "${filter.query} · ${format.format(filter.places.size)} places · ${format.format(filter.photoCount)}"
    }
}

/**
 * The filter chip (F8), in the top band left of `⋮`: its body reopens the overlay with the
 * filter's query and ticks ([OverlayEvent.ChipBody]); ✕ clears the filter
 * ([OverlayEvent.ChipClear]). Both are separate TalkBack targets, with labels.
 */
@Composable
internal fun PlaceFilterChip(
    filter: PlaceFilter,
    onEvent: (OverlayEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = chipLabel(filter)
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shadowElevation = 2.dp,
        modifier = modifier.height(MENU_BUTTON_SIZE),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .clickable(role = Role.Button, onClickLabel = "Edit place filter") { onEvent(OverlayEvent.ChipBody) }
                        .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
                        .semantics { contentDescription = "Place filter: $label" },
            )
            Box(
                Modifier
                    .size(MENU_BUTTON_SIZE)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onEvent(OverlayEvent.ChipClear) }
                    .semantics { contentDescription = "Clear place filter" },
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
