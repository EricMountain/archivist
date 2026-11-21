package fr.enry.archivist.data.repo

import fr.enry.archivist.domain.FileGazetteerSource
import fr.enry.archivist.domain.GeoPack
import fr.enry.archivist.domain.GeoPackSet
import fr.enry.archivist.domain.PlaceRef
import fr.enry.archivist.domain.ReverseGeocoder
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** [PlaceGeocoder]: tools/geopack/README.md "Combining with the gazetteer", with the real
 * gazetteer and the synthetic fixture packs. */
class PlaceGeocoderTest {
    private val gazetteer by lazy {
        ReverseGeocoder(FileGazetteerSource(File(requireNotNull(System.getProperty("archivist.gazetteer.bin")))))
    }
    private val fixture by lazy {
        val dir = File(requireNotNull(System.getProperty("archivist.geopack.dir"))).resolve("testdata")
        GeoPackSet(listOf("fixture-a.bin", "fixture-b.bin").map { GeoPack.read(dir.resolve(it).readBytes()) })
    }

    // The fixture sits near 45°N 5°E, in the Isère; the gazetteer has real towns there.
    private val lat = 45.0110
    private val lon = 5.0110

    @Test
    fun `no packs or unavailable packs give the gazetteer's place unchanged`() =
        runTest {
            val base = gazetteer.lookup(lat, lon)!!
            assertEquals(base, PlaceGeocoder(gazetteer) { _, _ -> GeoPacks.None }.lookup(lat, lon))
            assertEquals(base, PlaceGeocoder(gazetteer) { _, _ -> GeoPacks.Unavailable }.lookup(lat, lon))
        }

    @Test
    fun `an uncovered point keeps the gazetteer's levels under the set's src`() =
        runTest {
            val base = gazetteer.lookup(lat, lon)!!
            val place = PlaceGeocoder(gazetteer) { _, _ -> GeoPacks.Uncovered("fixture") }.lookup(lat, lon)
            assertEquals(base.copy(src = "osm@fixture;${base.src}"), place)
        }

    @Test
    fun `a covered point gets the pack's levels, country and region from the gazetteer`() =
        runTest {
            val base = gazetteer.lookup(lat, lon)!!
            val place = PlaceGeocoder(gazetteer) { _, _ -> GeoPacks.Covered("fixture", fixture) }.lookup(lat, lon)!!
            assertEquals(base.cc, place.cc)
            assertEquals(base.region, place.region)
            assertEquals(PlaceRef("r101", "Alpha"), place.locality)
            assertEquals(PlaceRef("n9003", "Brook"), place.sub)
            assertEquals(PlaceRef("n9005", "Nook"), place.spot)
            assertEquals(listOf("Great Park", "Wide Zone"), place.areas.map { it.name })
            assertEquals("osm@fixture;${base.src}", place.src)
        }

    @Test
    fun `no gazetteer place means no place, whatever the packs say`() =
        runTest {
            assertNull(PlaceGeocoder(gazetteer) { _, _ -> GeoPacks.Covered("fixture", fixture) }.lookup(0.0, -30.0))
        }
}
