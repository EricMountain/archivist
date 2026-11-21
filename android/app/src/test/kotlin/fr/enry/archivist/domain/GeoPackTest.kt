package fr.enry.archivist.domain

import java.io.File
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * [GeoPack] and [GeoPackSet] against the shared region-pack vectors
 * (`tools/geopack/vectors.json`) over the synthetic fixture packs, read from the repo
 * through the system property `app/build.gradle.kts` sets. The same vectors
 * `tools/import-from-local/tests/test_geopack.py` passes.
 */
class GeoPackTest {
    private val dir by lazy { File(requireNotNull(System.getProperty("archivist.geopack.dir"))) }
    private val vectors: JsonObject by lazy { Json.parseToJsonElement(dir.resolve("vectors.json").readText()).jsonObject }
    private val manifest: JsonObject by lazy {
        Json.parseToJsonElement(dir.resolve(vectors.str("manifest")).readText()).jsonObject
    }
    private val entries by lazy { manifest["packs"]!!.jsonArray.map { it.jsonObject } }
    private val packs: List<GeoPack> by lazy {
        val base = dir.resolve(vectors.str("manifest")).parentFile!!
        entries.map { GeoPack.read(base.resolve(it.str("path")).readBytes()) }
    }

    /** README: the packs whose manifest bbox covers the point, in manifest order. */
    private fun lookup(
        lat: Double,
        lon: Double,
    ): PackPlace {
        val chosen = entries.zip(packs).filter { (e, _) -> geoPackCovers(e.bbox(), lat, lon) }.map { it.second }
        return GeoPackSet(chosen).lookup(lat, lon)
    }

    @TestFactory
    fun `every shared vector`(): List<DynamicTest> {
        val all = vectors["vectors"]!!.jsonArray.map { it.jsonObject }
        assertTrue(all.size >= 32, "expected the full vector set, got ${all.size}")
        return all.map { v ->
            DynamicTest.dynamicTest(v.str("name")) {
                assertEquals(expected(v["expect"]!!.jsonObject), lookup(v.dbl("lat"), v.dbl("lon")), v.str("why"))
            }
        }
    }

    @Test
    fun `header fields`() {
        val a = packs[0]
        assertEquals("fixture", a.set)
        assertEquals("fixture-a", a.name)
        assertEquals("© OpenStreetMap contributors, ODbL 1.0", a.attribution)
        assertEquals(entries[0].bbox().toList(), a.bbox.toList())
    }

    @Test
    fun `index agrees with brute force`() {
        val set = GeoPackSet(packs)
        val rnd = Random(7)
        repeat(500) {
            val lat = 44.9 + rnd.nextDouble() * 0.25
            val lon = 4.9 + rnd.nextDouble() * 0.65
            assertEquals(set.brute(lat, lon), set.lookup(lat, lon), "$lat,$lon")
        }
    }

    @Test
    fun `rejects a damaged pack`() {
        val bytes = dir.resolve("testdata/fixture-b.bin").readBytes()
        assertThrows<IllegalArgumentException> { GeoPack.read(bytes.copyOf(bytes.size - 1)) }
        assertThrows<IllegalArgumentException> { GeoPack.read(bytes.copyOf().also { it[4] = 2 }) }
        assertThrows<IllegalArgumentException> { GeoPack.read(ByteArray(10)) }
    }

    @Test
    fun `combinePlace keeps country and region, takes the pack's levels, names the set`() {
        val base = Place("FR", PlaceRef("FR.84", "Auvergne-Rhône-Alpes"), PlaceRef("2971001", "Vallorcine"), "geonames-cities500@2026-11-13")
        val layers = PackPlace(locality = PlaceRef("r100101", "Chamonix-Mont-Blanc"), sub = PlaceRef("n200202", "Argentière"))
        assertEquals(
            base.copy(
                locality = PlaceRef("r100101", "Chamonix-Mont-Blanc"),
                sub = PlaceRef("n200202", "Argentière"),
                src = "osm@2026-11-19;geonames-cities500@2026-11-13",
            ),
            combinePlace(base, "2026-11-19", layers),
        )
        // No pack locality (or no covering pack): the gazetteer's town stays.
        assertEquals(base.copy(src = "osm@s;geonames-cities500@2026-11-13"), combinePlace(base, "s", PackPlace()))
        assertEquals(base.copy(src = "osm@s;geonames-cities500@2026-11-13"), combinePlace(base, "s", null))
    }

    private fun expected(o: JsonObject): PackPlace =
        PackPlace(
            locality = o["locality"]?.ref(),
            sub = o["sub"]?.ref(),
            spot = o["spot"]?.ref(),
            areas = o["areas"]?.jsonArray?.map { it.ref() }.orEmpty(),
            feature = o["feature"]?.ref(),
        )

    private fun JsonElement.ref() = jsonObject.let { PlaceRef(it.str("id"), it.str("name")) }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    private fun JsonObject.dbl(k: String) = this[k]!!.jsonPrimitive.double

    private fun JsonObject.bbox() = this["bbox"]!!.jsonArray.map { it.jsonPrimitive.int }.toIntArray()
}
