package fr.enry.archivist.domain

import java.io.File
import java.nio.ByteBuffer
import kotlin.math.asin
import kotlin.random.Random
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * Runs the real `assets/gazetteer.bin` against the shared `tools/gazetteer/vectors.json`
 * (geolocation plan D10). Both are read from the repo at test time, through system
 * properties `app/build.gradle.kts` sets, so a regenerated gazetteer and vector file need
 * no change here, only a re-run.
 */
class ReverseGeocoderTest {
    private val vectors: JsonObject by lazy {
        Json.parseToJsonElement(repoFile("archivist.gazetteer.vectors").readText()).jsonObject
    }

    private fun vector(id: String): JsonObject =
        vectors["vectors"]!!.jsonArray.map { it.jsonObject }.single { it.str("id") == id }

    private fun lookup(id: String): Place? = vector(id).let { geocoder.lookup(it.dbl("lat"), it.dbl("lon")) }

    @TestFactory
    fun `every shared vector`(): List<DynamicTest> {
        val all = vectors["vectors"]!!.jsonArray.map { it.jsonObject }
        assertTrue(all.size >= 30, "expected the full vector set, got ${all.size}")
        return all.map { v ->
            DynamicTest.dynamicTest(v.str("id")) {
                val actual = geocoder.lookup(v.dbl("lat"), v.dbl("lon"))
                assertEquals(expectedPlace(v["place"]!!), actual, v.str("note"))
            }
        }
    }

    @Test
    fun `thresholds and src match the vector file`() {
        assertEquals(vectors.dbl("localityMaxKm"), PlaceThresholds.LOCALITY_MAX_KM)
        assertEquals(vectors.dbl("regionMaxKm"), PlaceThresholds.REGION_MAX_KM)
        assertEquals(vectors.str("src"), geocoder.src)
    }

    // -- The plan's named fixtures. Expectations come from the vectors, so they follow a
    // regeneration; the assertions pin the *kind* of result each case must produce. -------

    @Test
    fun `city centres resolve to a locality`() {
        assertEquals(PlaceRef("1857910", "Kyoto"), lookup("kyoto-centre")!!.locality)
        assertEquals(PlaceRef("JP.22", "Kyoto"), lookup("kyoto-centre")!!.region)
        assertEquals("FR", lookup("paris-centre")!!.cc)
        assertNotNull(lookup("paris-centre")!!.locality)
        assertEquals("IS", lookup("reykjavik-centre")!!.cc)
        assertEquals("Reykjavík", lookup("reykjavik-centre")!!.locality!!.name)
    }

    @Test
    fun `a village is a locality`() {
        val place = lookup("village-luberon")!!
        assertEquals("FR", place.cc)
        assertNotNull(place.locality)
    }

    @Test
    fun `25 to 100 km gives a region only`() {
        val place = lookup("region-only-hokkaido")!!
        assertEquals(PlaceRef("JP.12", "Hokkaido"), place.region)
        assertNull(place.locality)
    }

    @Test
    fun `open ocean is null`() {
        assertNull(lookup("atlantic"))
        assertNull(lookup("null-island"))
    }

    @Test
    fun `both sides of the antimeridian agree`() {
        val east = lookup("taveuni-east")
        val west = lookup("taveuni-west")
        assertNotNull(east)
        assertEquals("FJ", east!!.cc)
        assertEquals(east, west)
        assertEquals(east, lookup("antimeridian-plus180"))
        assertEquals(east, lookup("antimeridian-minus180"))
        // Wrapping is by value: the same point a full turn away gives the same answer.
        assertEquals(east, geocoder.lookup(-16.85, -179.99 + 720.0))
        assertEquals("RU", lookup("chukotka-wrap")!!.cc)
    }

    @Test
    fun `border pair`() {
        // Recorded behaviour, not a goal: nearest town wins, even across a border
        // (design.md open question 6).
        assertEquals("CH", lookup("border-geneva-centre")!!.cc)
        assertEquals("FR", lookup("border-annemasse-centre")!!.cc)
        assertEquals("DE", lookup("border-strasbourg-port-du-rhin")!!.cc)
    }

    @Test
    fun `out-of-domain input gives null rather than throwing`() {
        assertNull(geocoder.lookup(Double.NaN, 0.0))
        assertNull(geocoder.lookup(0.0, Double.POSITIVE_INFINITY))
        assertNull(geocoder.lookup(90.5, 0.0))
    }

    @Test
    fun `nothing is read before the first lookup, then only once`() {
        var opens = 0
        val bytes = gazetteerBuffer()
        val lazy = ReverseGeocoder { opens++; bytes }
        assertEquals(0, opens)
        lazy.lookup(35.0, 135.0)
        lazy.lookup(48.0, 2.0)
        assertEquals(1, opens)
    }

    @Test
    fun `rejects a corrupt file`() {
        val bytes = ByteArray(64)
        assertThrows<IllegalStateException> { ReverseGeocoder { ByteBuffer.wrap(bytes) }.lookup(0.0, 0.0) }
        val truncated = gazetteerBuffer().let { b -> ByteArray(b.limit() - 1) { b.get(it) } }
        assertThrows<IllegalStateException> { ReverseGeocoder { ByteBuffer.wrap(truncated) }.lookup(0.0, 0.0) }
    }

    @Test
    fun `concurrent lookups agree with sequential ones`() {
        val fresh = ReverseGeocoder(FileGazetteerSource(repoFile("archivist.gazetteer.bin")))
        val points = randomPoints(Random(7), 400)
        val expected = points.map { (lat, lon) -> geocoder.lookup(lat, lon) }
        val results = arrayOfNulls<List<Place?>>(8)
        val threads =
            (0 until 8).map { t ->
                Thread { results[t] = points.map { (lat, lon) -> fresh.lookup(lat, lon) } }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        results.forEach { assertEquals(expected, it) }
    }

    @Test
    fun `row pruning never changes a result`() {
        // pruneRows = false is a line-for-line port of reference.py's lookup(). Slow near
        // the poles, so the polar sample is small.
        val exact = ReverseGeocoder(FileGazetteerSource(repoFile("archivist.gazetteer.bin")), pruneRows = false)
        val random = Random(11)
        val polar = List(40) { (if (random.nextBoolean()) 1 else -1) * random.nextDouble(86.0, 90.0) to random.nextDouble(-180.0, 180.0) }
        val edges = listOf(89.0 to 0.0, 90.0 to 15.0, -90.0 to 0.0, 88.0 to -179.5, 78.2232 to 15.6267, 82.5 to -62.3)
        val points = polar + edges + randomPoints(random, 2_000)
        for ((lat, lon) in points) {
            assertEquals(exact.lookup(lat, lon), geocoder.lookup(lat, lon), "at $lat, $lon")
        }
    }

    /** Plan step 3: p99 under 5 ms after warm-up. Prints the measured numbers. */
    @Test
    fun `lookup p99 is under 5 ms`() {
        val random = Random(42)
        repeat(3_000) { random.nextPoint().let { (lat, lon) -> geocoder.lookup(lat, lon) } }
        val points = randomPoints(random, 5_000)
        val nanos =
            points.map { (lat, lon) ->
                val start = System.nanoTime()
                geocoder.lookup(lat, lon)
                System.nanoTime() - start
            }.sorted()
        val p50 = nanos[nanos.size / 2] / 1e6
        val p99 = nanos[(nanos.size * 99) / 100] / 1e6
        val max = nanos.last() / 1e6
        println("ReverseGeocoder lookup over ${nanos.size} points: p50 %.3f ms, p99 %.3f ms, max %.3f ms"
            .format(p50, p99, max))
        assertTrue(p99 < 5.0, "p99 was $p99 ms")
    }

    private fun randomPoints(random: Random, n: Int) = List(n) { random.nextPoint() }

    /** Half uniform on the sphere (mostly ocean, plus the polar rings), half within ±2° of
     * a vector, which are mostly dense places (Europe, Japan). */
    private fun Random.nextPoint(): Pair<Double, Double> {
        if (nextBoolean()) {
            val lat = Math.toDegrees(asin(nextDouble(-1.0, 1.0)))
            return lat to nextDouble(-180.0, 180.0)
        }
        val anchors = vectors["vectors"]!!.jsonArray
        val v = anchors[nextInt(anchors.size)].jsonObject
        val lat = (v.dbl("lat") + nextDouble(-2.0, 2.0)).coerceIn(-90.0, 90.0)
        return lat to v.dbl("lon") + nextDouble(-2.0, 2.0)
    }

    companion object {
        private fun repoFile(property: String): File {
            val path =
                requireNotNull(System.getProperty(property)) {
                    "system property $property is unset; it comes from app/build.gradle.kts"
                }
            return File(path).also { require(it.isFile) { "$it does not exist" } }
        }

        private fun gazetteerBuffer(): ByteBuffer = FileGazetteerSource(repoFile("archivist.gazetteer.bin")).open()

        private val geocoder by lazy { ReverseGeocoder(FileGazetteerSource(repoFile("archivist.gazetteer.bin"))) }

        private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

        private fun JsonObject.dbl(key: String) = this[key]!!.jsonPrimitive.double

        private fun ref(e: JsonElement?): PlaceRef? =
            e?.jsonObject?.let { PlaceRef(it.str("id"), it.str("name")) }

        /** A vector's `place`: absent keys mean null, and no other keys may appear. */
        private fun expectedPlace(e: JsonElement): Place? {
            if (e is JsonNull) return null
            val o = e.jsonObject
            val unknown = o.keys - setOf("cc", "region", "locality", "src")
            require(unknown.isEmpty()) { "unexpected place keys $unknown" }
            return Place(o.str("cc"), ref(o["region"]), ref(o["locality"]), o.str("src"))
        }
    }
}
