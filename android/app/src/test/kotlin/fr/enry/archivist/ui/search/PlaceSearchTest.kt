package fr.enry.archivist.ui.search

import fr.enry.archivist.data.remote.PlaceCountryDto
import fr.enry.archivist.data.remote.PlaceLocalityDto
import fr.enry.archivist.data.remote.PlaceRegionDto
import fr.enry.archivist.data.remote.PlacesResponse
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Geolocation plan step 8: [searchPlaces]'s matching and ranking, against
 * `sample-data.md`'s place vocabulary (Kyoto, Hokkaido, Stanley) plus a few places that
 * exercise accents, localized country names and the prefix rule. */
class PlaceSearchTest {
    private val en = Locale.ENGLISH
    private val fr = Locale.FRENCH

    private val vocabulary =
        PlacesResponse(
            countries =
                listOf(
                    PlaceCountryDto("JP", 3),
                    PlaceCountryDto("FK", 1),
                    PlaceCountryDto("CA", 4),
                    PlaceCountryDto("DE", 6),
                    PlaceCountryDto("FR", 3201),
                ),
            regions =
                listOf(
                    PlaceRegionDto("JP.22", "Kyoto", "JP", 2),
                    PlaceRegionDto("JP.12", "Hokkaido", "JP", 1),
                    PlaceRegionDto("CA.10", "Québec", "CA", 4),
                    PlaceRegionDto("FR.93", "Provence-Alpes-Côte d'Azur", "FR", 41),
                ),
            localities =
                listOf(
                    PlaceLocalityDto("1857910", "Kyoto", "Kyoto", "JP", 2),
                    PlaceLocalityDto("3426691", "Stanley", null, "FK", 1),
                    PlaceLocalityDto("2950159", "Berlin", "Land Berlin", "DE", 6),
                    PlaceLocalityDto("3020035", "Fréjus", "Provence-Alpes-Côte d'Azur", "FR", 41),
                    // More photos than either Kyoto, but "kyo" is mid-word here.
                    PlaceLocalityDto("1850147", "Tokyo", "Tokyo", "JP", 9),
                ),
            total = 3209,
            version = 4,
        )

    private fun texts(
        query: String,
        locale: Locale = en,
    ) = searchPlaces(vocabulary, query, locale).map { it.text(locale) }

    @Test
    fun `kyo finds the town and the region as prefix matches, before a bigger mid-word match`() {
        assertEquals(
            listOf("Kyoto · Japan · 2", "Kyoto (region) · Japan · 2", "Tokyo · Japan · 9"),
            texts("kyo"),
        )
    }

    @Test
    fun `KYO matches exactly like kyo`() {
        assertEquals(texts("kyo"), texts("KYO"))
        assertEquals(texts("kyo"), texts("  Kyo "))
    }

    @Test
    fun `quebec finds the accented Québec region`() {
        val found = searchPlaces(vocabulary, "quebec", en)
        assertEquals(listOf("Québec (region) · Canada · 4"), found.map { it.text(en) })
        assertEquals(PlaceLevel.REGION, found.single().level)
        assertEquals("REGION", found.single().level.facetType)
        assertEquals("CA.10", found.single().facetValue)
    }

    @Test
    fun `allemagne finds DE under a French locale, named in French`() {
        val found = searchPlaces(vocabulary, "allemagne", fr)
        assertEquals(listOf("Allemagne · 6"), found.map { it.text(fr) })
        assertEquals("COUNTRY", found.single().level.facetType)
        assertEquals("DE", found.single().facetValue)
        // Not a name an English device knows.
        assertEquals(emptyList<String>(), texts("allemagne", en))
    }

    @Test
    fun `germany finds DE in English, and on a French phone too`() {
        assertEquals(listOf("Germany · 6"), texts("germany", en))
        assertEquals(listOf("Allemagne · 6"), texts("germany", fr))
        assertEquals(listOf("Japon · 3"), texts("japan", fr))
        assertEquals(listOf("Japon · 3"), texts("japon", fr))
    }

    @Test
    fun `a town matches on its own name only, not its region's or country's`() {
        // "Land Berlin" is Berlin's region name, but only the town itself is a match.
        assertEquals(listOf("Berlin · Land Berlin, Germany · 6"), texts("berlin"))
        assertEquals(listOf("Japan · 3"), texts("japan"))
    }

    @Test
    fun `a town with no region names only the town and the country`() {
        assertEquals(listOf("Stanley · Falkland Islands · 1"), texts("stan"))
        val stanley = searchPlaces(vocabulary, "stanley", en).single()
        assertEquals(PlaceLevel.LOCALITY, stanley.level)
        assertEquals("PLACE", stanley.level.facetType)
        assertEquals("3426691", stanley.facetValue)
        assertEquals("Falkland Islands", stanley.context)
    }

    @Test
    fun `a town's region is shown unless it repeats the town's name`() {
        assertEquals(listOf("Fréjus · Provence-Alpes-Côte d'Azur, France · 41"), texts("frej"))
        // Kyoto in Kyoto: the region adds nothing.
        assertEquals("Japan", searchPlaces(vocabulary, "kyoto", en).first { it.level == PlaceLevel.LOCALITY }.context)
    }

    @Test
    fun `counts are grouped in the device locale`() {
        assertEquals(listOf("France · 3,201"), texts("france"))
    }

    @Test
    fun `prefix matches rank above count, and count above name`() {
        // "o": every name containing it. Word-start "o" matches none of these, so all are
        // substring matches ordered by count. Then a word-start match beats them all.
        val v =
            PlacesResponse(
                localities =
                    listOf(
                        PlaceLocalityDto("1", "Toronto", null, "CA", 100),
                        PlaceLocalityDto("2", "Bordeaux", null, "FR", 50),
                        PlaceLocalityDto("3", "Osaka", null, "JP", 1),
                        PlaceLocalityDto("4", "Rio de Janeiro", null, "BR", 1),
                    ),
            )
        assertEquals(listOf("Osaka", "Toronto", "Bordeaux", "Rio de Janeiro"), searchPlaces(v, "o", en).map { it.title })
        // A word start inside a name counts as a prefix: "york" in "New York".
        val w =
            PlacesResponse(
                localities =
                    listOf(
                        PlaceLocalityDto("5", "Yorkshire Dales Village", null, "GB", 1),
                        PlaceLocalityDto("6", "Newyorkton", null, "US", 90),
                        PlaceLocalityDto("7", "New York", null, "US", 2),
                    ),
            )
        assertEquals(listOf("New York", "Yorkshire Dales Village", "Newyorkton"), searchPlaces(w, "york", en).map { it.title })
    }

    @Test
    fun `ties on prefix and count fall back to the name`() {
        val v =
            PlacesResponse(
                localities =
                    listOf(
                        PlaceLocalityDto("1", "Saint-Malo", null, "FR", 3),
                        PlaceLocalityDto("2", "Saint-Denis", null, "FR", 3),
                        PlaceLocalityDto("3", "Saintes", null, "FR", 3),
                    ),
            )
        assertEquals(listOf("Saint-Denis", "Saint-Malo", "Saintes"), searchPlaces(v, "saint", en).map { it.title })
    }

    @Test
    fun `an empty query lists the top places by count, all levels mixed`() {
        val all = searchPlaces(vocabulary, "", en)
        assertEquals(vocabulary.countries.size + vocabulary.regions.size + vocabulary.localities.size, all.size)
        assertEquals(listOf("France", "Fréjus", "Provence-Alpes-Côte d'Azur (region)", "Tokyo"), all.take(4).map { it.title })
        assertTrue(all.zipWithNext().all { (a, b) -> a.n >= b.n })
        assertEquals(all, searchPlaces(vocabulary, "   ", en))
    }

    @Test
    fun `suggestions are capped`() {
        val many = PlacesResponse(localities = (1..80).map { PlaceLocalityDto("$it", "Town $it", null, "FR", it) })
        assertEquals(MAX_PLACE_SUGGESTIONS, searchPlaces(many, "town", en).size)
        assertEquals("Town 80", searchPlaces(many, "town", en).first().title)
        assertEquals(MAX_PLACE_SUGGESTIONS, searchPlaces(many, "", en).size)
    }

    @Test
    fun `no match gives no suggestions`() {
        assertEquals(emptyList<String>(), texts("zzz"))
        assertEquals(emptyList<PlaceSuggestion>(), searchPlaces(PlacesResponse(), "kyo", en))
    }
}
