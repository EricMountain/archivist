package fr.enry.archivist.ui.search

import fr.enry.archivist.data.remote.PlaceCountryDto
import fr.enry.archivist.data.remote.PlaceLocalityDto
import fr.enry.archivist.data.remote.PlaceRegionDto
import fr.enry.archivist.data.remote.PlacesResponse
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Timeline place filter step 1: grouping, ticks, implied entries and the live-apply
 * floor, over a small vocabulary with the awkward cases (arrondissements, a same-named
 * town abroad, a region and town both called Kyoto, a town with no region, an ambiguous
 * region name). */
class PlaceSelectionTest {
    private val en = Locale.ENGLISH

    private val vocabulary =
        PlacesResponse(
            countries =
                listOf(
                    PlaceCountryDto("FR", 600),
                    PlaceCountryDto("US", 3),
                    PlaceCountryDto("JP", 4),
                    PlaceCountryDto("FK", 1),
                    PlaceCountryDto("GH", 7),
                ),
            regions =
                listOf(
                    PlaceRegionDto("FR.11", "Île-de-France", "FR", 539),
                    PlaceRegionDto("US.TX", "Texas", "US", 3),
                    PlaceRegionDto("JP.22", "Kyoto", "JP", 4),
                    // Two regions with one name in one country: a town naming it can't be linked.
                    PlaceRegionDto("GH.04", "Central", "GH", 5),
                    PlaceRegionDto("GH.99", "Central", "GH", 2),
                ),
            localities =
                listOf(
                    PlaceLocalityDto("2988506", "Paris 01 Louvre", "Île-de-France", "FR", 412),
                    PlaceLocalityDto("2968815", "Paris 04 Hôtel-de-Ville", "Île-de-France", "FR", 96),
                    PlaceLocalityDto("2988621", "Paris 16 Passy", "Île-de-France", "FR", 31),
                    PlaceLocalityDto("4717560", "Paris", "Texas", "US", 3),
                    PlaceLocalityDto("1857910", "Kyoto", "Kyoto", "JP", 4),
                    PlaceLocalityDto("3426691", "Stanley", null, "FK", 1),
                    PlaceLocalityDto("2302357", "Cape Coast", "Central", "GH", 5),
                ),
        )

    private fun state(query: String) = SelectionState.initial(query, selectionTreeFor(vocabulary, query, en))

    /** Typing on from [this]: the next query's tree, with the selection carried over. */
    private fun SelectionState.type(query: String) = withQuery(query, selectionTreeFor(vocabulary, query, en))

    private fun keys(vararg ids: String) = ids.toSet()

    @Test
    fun `paris groups France over an Île-de-France header and the US over Texas, all ticked`() {
        val s = state("paris")
        val tree = s.tree
        assertEquals(listOf("FR", "US"), tree.countries.map { it.cc })

        val france = tree.countries[0]
        assertNull(france.entry, "France didn't match: a header")
        assertEquals("France", france.title)
        val idf = france.children.single() as RegionGroup
        assertEquals("FR.11", idf.id)
        assertEquals("Île-de-France", idf.title)
        assertNull(idf.entry, "Île-de-France didn't match: a header")
        assertEquals(listOf("Paris 01 Louvre", "Paris 04 Hôtel-de-Ville", "Paris 16 Passy"), idf.towns.map { it.title })

        val us = tree.countries[1]
        assertEquals("United States", us.title)
        val texas = us.children.single() as RegionGroup
        assertEquals("Texas", texas.title)
        assertEquals(listOf("Paris"), texas.towns.map { it.title })

        assertEquals(keys("PLACE#2988506", "PLACE#2968815", "PLACE#2988621", "PLACE#4717560"), s.ticked)
        assertEquals(emptySet<String>(), s.implied)
        assertEquals(4, s.maximal.size)
        assertEquals(412 + 96 + 31 + 3, s.photoCount)
        val filter = s.toFilter()!!
        assertEquals("paris", filter.query)
        assertEquals(542, filter.photoCount)
        assertEquals(s.maximal.map { it.key }.toSet(), filter.streamKeys)
    }

    @Test
    fun `kyoto ticks the region and implies the town, which is the only thing not a stream`() {
        val s = state("kyoto")
        val japan = s.tree.countries.single()
        val region = japan.children.single() as RegionGroup
        assertEquals("Kyoto (region)", region.entry?.title)
        assertEquals(listOf("Kyoto"), region.towns.map { it.title })

        assertEquals(keys("REGION#JP.22", "PLACE#1857910"), s.ticked)
        assertEquals(keys("PLACE#1857910"), s.implied)
        assertFalse(s.isEnabled("PLACE#1857910"))
        assertTrue(s.isEnabled("REGION#JP.22"))
        assertEquals(listOf("REGION#JP.22"), s.maximal.map { it.key })
        assertEquals(4, s.photoCount)
        // Toggling an implied entry does nothing.
        assertEquals(s, s.toggle("PLACE#1857910"))
    }

    @Test
    fun `unticking the region leaves the town ticked and enabled`() {
        val s = state("kyoto").toggle("REGION#JP.22")
        assertEquals(keys("PLACE#1857910"), s.ticked)
        assertEquals(emptySet<String>(), s.implied)
        assertTrue(s.isEnabled("PLACE#1857910"))
        assertEquals(listOf("PLACE#1857910"), s.maximal.map { it.key })
        assertEquals(4, s.photoCount)
    }

    @Test
    fun `typing on keeps an exclusion, and a new match arrives ticked`() {
        // "pa" matches the arrondissements, Paris TX, and none of the rest.
        val unticked = state("pa").toggle("PLACE#2988621")
        assertFalse("PLACE#2988621" in unticked.ticked)

        val narrowed = unticked.type("paris 1")
        assertEquals(listOf("Paris 16 Passy"), narrowed.tree.shown.map { it.title })
        assertEquals(emptySet<String>(), narrowed.ticked, "Passy stays unticked")
        assertFalse(narrowed.touched, "a keystroke isn't a tick")

        // Widening again: Passy still unticked, the rest ticked.
        val widened = narrowed.type("paris")
        assertEquals(keys("PLACE#2988506", "PLACE#2968815", "PLACE#4717560"), widened.ticked)

        // Places that first appear on a later keystroke arrive ticked; the earlier
        // untick still holds.
        val early = state("paris 0").toggle("PLACE#2968815")
        assertEquals(keys("PLACE#2988506"), early.ticked)
        val later = early.type("paris")
        assertEquals(keys("PLACE#2988506", "PLACE#2988621", "PLACE#4717560"), later.ticked)
    }

    @Test
    fun `an empty query lists quick picks unticked, and ticking one picks it`() {
        val empty = state("")
        assertTrue(empty.isEmptyQuery)
        assertEquals(vocabulary.countries.size + vocabulary.regions.size + vocabulary.localities.size, empty.tree.shown.size)
        assertEquals(emptySet<String>(), empty.ticked)
        assertNull(empty.toFilter(), "an empty query with nothing ticked is no filter")

        val picked = empty.toggle("PLACE#3426691")
        assertEquals(keys("PLACE#3426691"), picked.ticked)
        assertEquals(1, picked.photoCount)
        assertEquals(listOf("PLACE#3426691"), picked.toFilter()!!.places.map { it.key })

        // A quick pick doesn't survive typing; clearing the query unticks everything.
        val typed = picked.type("stanley")
        assertEquals(emptySet<String>(), typed.quickPicks)
        assertEquals(emptySet<String>(), typed.type("").ticked)
    }

    @Test
    fun `All and None on an unmatched header act on the entries under it`() {
        val s = state("paris")
        val none = s.setAll("REGION#FR.11", ticked = false)
        assertEquals(keys("PLACE#4717560"), none.ticked)
        assertTrue(none.touched)
        val all = none.setAll("REGION#FR.11", ticked = true)
        assertEquals(s.ticked, all.ticked)

        val franceNone = s.setAll("COUNTRY#FR", ticked = false)
        assertEquals(keys("PLACE#4717560"), franceNone.ticked)

        // Under the empty query All picks them.
        val quick = state("").setAll("REGION#FR.11", ticked = true)
        assertTrue(quick.ticked.containsAll(keys("PLACE#2988506", "PLACE#2968815", "PLACE#2988621")))
        // FR.11 is itself shown (as a quick pick) and is under FR, not FR.11.
        assertFalse("REGION#FR.11" in quick.ticked)
    }

    @Test
    fun `Stanley, a town with no region, sits under its country`() {
        val falklands = state("stanley").tree.countries.single()
        assertEquals("FK", falklands.cc)
        assertEquals(listOf("Stanley"), falklands.children.map { (it as TownRow).entry.title })
    }

    @Test
    fun `an ambiguous region name falls back to the country`() {
        val ghana = state("cape coast").tree.countries.single()
        val town = ghana.children.single()
        assertTrue(town is TownRow)
        assertEquals("Cape Coast", (town as TownRow).entry.title)
    }

    @Test
    fun `a country ticked implies its matched regions and towns`() {
        val v = vocabulary.copy(localities = vocabulary.localities + PlaceLocalityDto("1", "Japan Town", "Kyoto", "JP", 1))
        val s = SelectionState.initial("ja", selectionTreeFor(v, "ja", en))
        assertEquals(listOf("COUNTRY#JP"), s.maximal.map { it.key })
        assertEquals(keys("PLACE#1"), s.implied)
    }

    @Test
    fun `more than 200 matches shows 200, counts all of them, and filters only what's shown`() {
        val many = PlacesResponse(localities = (1..250).map { PlaceLocalityDto("$it", "Town $it", null, "FR", 1) })
        val s = SelectionState.initial("town", selectionTreeFor(many, "town", en))
        assertEquals(MAX_PLACE_SUGGESTIONS, s.tree.shown.size)
        assertEquals(250, s.tree.total)
        assertTrue(s.tree.truncated)
        assertEquals(MAX_PLACE_SUGGESTIONS, s.maximal.size)
        assertEquals(MAX_PLACE_SUGGESTIONS, s.photoCount)
        assertEquals(s.tree.shown.map { it.key }, s.maximal.map { it.key })
    }

    @Test
    fun `live apply needs two folded characters, or a tick`() {
        assertFalse(state("p").liveApplicable)
        assertFalse(state(" P ").liveApplicable)
        assertTrue(state("pa").liveApplicable)
        assertTrue(state("p").toggle("PLACE#4717560").liveApplicable)
        assertTrue(state("p").setAll("COUNTRY#FR", ticked = false).liveApplicable)
        // Clearing the field applies (back to no filter): nothing fans out.
        assertTrue(state("").liveApplicable)
    }

    @Test
    fun `a query that matches nothing is an active filter with no places, not no filter`() {
        val s = state("zzz")
        assertEquals(emptyList<CountryGroup>(), s.tree.countries)
        val filter = s.toFilter()!!
        assertTrue(filter.isEmptySelection)
        assertEquals(0, filter.photoCount)
        // Everything unticked is the same.
        assertTrue(state("stanley").toggle("PLACE#3426691").toFilter()!!.isEmptySelection)
    }
}
