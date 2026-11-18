package fr.enry.archivist.ui.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import fr.enry.archivist.data.remote.PlaceCountryDto
import fr.enry.archivist.data.remote.PlaceLocalityDto
import fr.enry.archivist.data.remote.PlaceRegionDto
import fr.enry.archivist.data.remote.PlacesResponse
import java.util.Locale
import org.junit.Rule
import org.junit.Test

/**
 * Timeline place filter step 4: [SuggestionPanel] over a fake vocabulary — grouped, ticked
 * rows, the status-line count, unticking, and an implied town's disabled box. Runs on the
 * `pixel2api30` managed device (`./gradlew :app:pixel2api30DebugAndroidTest`); the local
 * AVDs can't run Compose UI tests (`android/AGENTS.md`).
 */
class SearchOverlayUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val vocabulary =
        PlacesResponse(
            countries = listOf(PlaceCountryDto("FR", 539), PlaceCountryDto("US", 3), PlaceCountryDto("JP", 4)),
            regions = listOf(PlaceRegionDto("FR.11", "Île-de-France", "FR", 539), PlaceRegionDto("JP.22", "Kyoto", "JP", 4)),
            localities =
                listOf(
                    PlaceLocalityDto("2988507", "Paris", "Île-de-France", "FR", 412),
                    PlaceLocalityDto("6618610", "Paris 04 Hôtel-de-Ville", "Île-de-France", "FR", 96),
                    PlaceLocalityDto("6618622", "Paris 16 Passy", "Île-de-France", "FR", 31),
                    PlaceLocalityDto("4717560", "Paris", null, "US", 3),
                    PlaceLocalityDto("1857910", "Kyoto", "Kyoto", "JP", 4),
                ),
        )

    private fun show(query: String) {
        composeRule.setContent {
            MaterialTheme {
                var selection by remember { mutableStateOf(SelectionState.initial(query, selectionTreeFor(vocabulary, query, Locale.ENGLISH))) }
                SuggestionPanel(
                    selection = selection,
                    status = VocabularyStatus.READY,
                    hasPlaces = true,
                    loading = false,
                    onToggle = { selection = selection.toggle(it) },
                    onSetAll = { key, ticked -> selection = selection.setAll(key, ticked) },
                    onRetry = {},
                    maxHeight = 600.dp,
                )
            }
        }
    }

    @Test
    fun parisShowsGroupedTickedRowsAndUntickingLowersTheCount() {
        show("paris")
        composeRule.onNodeWithText("France").assertExists()
        composeRule.onNodeWithText("Île-de-France").assertExists()
        composeRule.onNodeWithText("United States").assertExists()
        composeRule.onNodeWithTag(SearchOverlayTags.row("PLACE#2988507")).assertIsOn()
        composeRule.onNodeWithTag(SearchOverlayTags.row("PLACE#4717560")).assertIsOn()
        composeRule.onNodeWithTag(SearchOverlayTags.STATUS).assertTextEquals("542 photos in 4 places")

        composeRule.onNodeWithTag(SearchOverlayTags.row("PLACE#6618610")).performClick()
        composeRule.onNodeWithTag(SearchOverlayTags.row("PLACE#6618610")).assertIsOff()
        composeRule.onNodeWithTag(SearchOverlayTags.STATUS).assertTextEquals("446 photos in 3 places")
    }

    @Test
    fun anImpliedTownIsTickedAndDisabled() {
        show("kyoto")
        composeRule.onNodeWithTag(SearchOverlayTags.row("REGION#JP.22")).assertIsOn()
        composeRule.onNodeWithTag(SearchOverlayTags.row("PLACE#1857910")).assertIsOn().assertIsNotEnabled()
        composeRule.onNodeWithTag(SearchOverlayTags.STATUS).assertTextEquals("4 photos in 1 place")
    }
}
