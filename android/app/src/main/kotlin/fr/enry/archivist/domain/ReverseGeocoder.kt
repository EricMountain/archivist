package fr.enry.archivist.domain

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A GeoNames identifier plus its display name: a region (`"JP.22"`, "Kyoto") or a
 * locality (`"1857910"`, "Kyoto"). design.md "Location": ids go in keys, names are for
 * display and search. */
data class PlaceRef(val id: String, val name: String)

/**
 * The `place` object design.md "Location" defines. [region] is null when the nearest place
 * has no known admin1 region; [locality] is null when it is 25–100 km away. [src] is the
 * gazetteer's snapshot, e.g. `geonames-cities500@2026-11-13`.
 */
data class Place(
    val cc: String,
    val region: PlaceRef?,
    val locality: PlaceRef?,
    val src: String,
)

/** The one place the geocoder's distances live, matching `tools/gazetteer/reference.py`
 * (and `vectors.json`'s `localityMaxKm`/`regionMaxKm`). */
object PlaceThresholds {
    /** Nearest place at most this far away: full place, locality included. */
    const val LOCALITY_MAX_KM = 25.0

    /** Nearest place at most this far away: country and region only. Beyond: no place. */
    const val REGION_MAX_KM = 100.0

    const val EARTH_RADIUS_KM = 6371.0
}

/**
 * Where `gazetteer.bin`'s bytes come from: a memory-mapped asset on the device
 * (`AssetGazetteerSource`), or the same file from disk in JVM tests ([FileGazetteerSource]).
 * Called at most once per [ReverseGeocoder], on its first lookup.
 */
fun interface GazetteerSource {
    fun open(): ByteBuffer
}

/** Memory-maps a `gazetteer.bin` on the filesystem. */
class FileGazetteerSource(private val file: File) : GazetteerSource {
    override fun open(): ByteBuffer =
        RandomAccessFile(file, "r").use { raf ->
            // The mapping stays valid after the channel is closed.
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }
}

/**
 * Offline reverse geocoder over the bundled GeoNames gazetteer (geolocation plan step 3).
 * The binary format and the lookup are specified in `tools/gazetteer/README.md`; this is a
 * port of `tools/gazetteer/reference.py`'s `Gazetteer.lookup()`, and must reproduce
 * `tools/gazetteer/vectors.json` exactly (plan decision D10).
 *
 * Nothing is parsed up front: the first [lookup] maps the file and checks its 32-byte
 * header; after that each lookup binary-searches the cell index and decodes only the
 * cells it visits. Thread-safe: initialisation is a synchronized lazy, and every read
 * afterwards is an absolute `ByteBuffer` get, which touches no buffer state.
 */
class ReverseGeocoder internal constructor(
    source: GazetteerSource,
    pruneRows: Boolean,
) {
    constructor(source: GazetteerSource) : this(source, pruneRows = true)

    /** [pruneRows] false is reference.py's search exactly; tests compare the two. */
    private val gazetteer by lazy { Gazetteer(source.open(), pruneRows) }

    /** The gazetteer's snapshot id, the `src` of every [Place] it returns. */
    val src: String get() = gazetteer.src

    /**
     * The place for ([lat], [lon]) in degrees, or null when no populated place lies within
     * [PlaceThresholds.REGION_MAX_KM]. Longitude may be any finite value; it is wrapped
     * into [-180, 180). A latitude outside [-90, 90] or a non-finite input also gives null
     * (the reference reader doesn't define those; real EXIF can still contain them).
     */
    fun lookup(lat: Double, lon: Double): Place? {
        if (!lat.isFinite() || !lon.isFinite() || lat < -90.0 || lat > 90.0) return null
        return gazetteer.lookup(lat, lon)
    }
}

/** The decoded view of one `gazetteer.bin`, format version 1. */
private class Gazetteer(buffer: ByteBuffer, private val pruneRows: Boolean) {
    private val buf: ByteBuffer = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    private val regionIndexOffset: Int
    private val cellCount: Int
    private val cellIndexOffset: Int
    val src: String

    init {
        val size = buf.limit()
        if (size < 32 ||
            buf.get(0) != 'A'.code.toByte() || buf.get(1) != 'G'.code.toByte() ||
            buf.get(2) != 'A'.code.toByte() || buf.get(3) != 'Z'.code.toByte()
        ) {
            throw IllegalStateException("not a gazetteer.bin")
        }
        val version = u16(4)
        check(version == 1) { "unsupported gazetteer.bin version $version" }
        val fileLength = u32(28)
        check(fileLength == size.toLong()) { "gazetteer.bin is $size bytes, header says $fileLength" }
        regionIndexOffset = u32(20).toInt()
        cellCount = u32(16).toInt()
        cellIndexOffset = u32(24).toInt()
        src = String(bytes(32, u16(6)), Charsets.US_ASCII)
    }

    fun lookup(lat: Double, rawLon: Double): Place? {
        val lon = (rawLon + 180.0).mod(360.0) - 180.0
        val qLat = min(floor(lat).toInt(), 89)
        val qLon = floor(lon).toInt()
        val best = Best()
        var r = 0
        while (true) {
            for (la in maxOf(qLat - r, -90)..minOf(qLat + r, 89)) {
                if (pruneRows && rowCannotMatter(la, lat, best)) continue
                if (abs(la - qLat) == r || 2 * r + 1 >= 360) {
                    // A whole row of the ring, each column once even when it wraps all the
                    // way round. Visiting order doesn't matter: (distance, id) is a total order.
                    val n = minOf(2 * r + 1, 360)
                    for (i in 0 until n) scanCell(la, wrapCell(qLon - r + i), lat, lon, best)
                } else {
                    // Only the two side columns; 2r < 359 here, so they never coincide.
                    scanCell(la, wrapCell(qLon - r), lat, lon, best)
                    scanCell(la, wrapCell(qLon + r), lat, lon, best)
                }
            }
            val bound = outsideBoundKm(lat, lon, qLat, qLon, r)
            val limit = min(best.distance, PlaceThresholds.REGION_MAX_KM)
            if (bound > limit) return toPlace(best)
            r++
        }
    }

    /**
     * The one addition to reference.py's search, which the README allows ("any correct
     * search is conforming"): skip a row of cells whose latitude band alone puts every place
     * in it beyond `min(best, 100 km)`, since a great-circle distance is at least the
     * latitude difference times R. A skipped row could hold neither a nearer place nor an
     * equal-distance tie, nor anything within 100 km, so the result can't change. It
     * matters near the poles: above about 89° the reference's east/west bound never exceeds
     * 100 km, so its rings grow until they wrap the globe and decode nearly every place
     * (over a second in reference.py; tens of ms here). With this, those rings only look
     * at the polar rows.
     */
    private fun rowCannotMatter(latCell: Int, lat: Double, best: Best): Boolean {
        // Places in row latCell have latitudes in [latCell, latCell + 1].
        val gapDegrees =
            when {
                lat < latCell -> latCell - lat
                lat > latCell + 1 -> lat - (latCell + 1)
                else -> return false
            }
        val limit = min(best.distance, PlaceThresholds.REGION_MAX_KM)
        // The 1 m slack keeps rounding in the bound from ever skipping a tie.
        return radians(gapDegrees) * PlaceThresholds.EARTH_RADIUS_KM > limit + 0.001
    }

    private fun toPlace(best: Best): Place? {
        if (best.distance > PlaceThresholds.REGION_MAX_KM) return null // also: nothing found
        var pos = u32(regionIndexOffset + 4 * best.region).toInt()
        val keyLen = varint(pos)
        val key = utf8(next(keyLen), value(keyLen).toInt())
        pos = next(keyLen) + value(keyLen).toInt()
        val nameLen = varint(pos)
        val regionName = utf8(next(nameLen), value(nameLen).toInt())
        return Place(
            cc = key.substring(0, 2),
            region = if (key.length > 2) PlaceRef(key, regionName) else null,
            locality =
                if (best.distance <= PlaceThresholds.LOCALITY_MAX_KM) {
                    PlaceRef(best.id.toString(), utf8(best.nameOffset, best.nameLength))
                } else {
                    null
                },
            src = src,
        )
    }

    /** Folds every record of one cell into [best]. Names are skipped, not decoded. */
    private fun scanCell(latCell: Int, lonCell: Int, lat: Double, lon: Double, best: Best) {
        val entry = findCell((latCell + 90) * 360 + (lonCell + 180))
        if (entry < 0) return
        val count = u16(entry + 2)
        var pos = u32(entry + 4).toInt()
        var id = 0L
        val latBase = latCell.toLong() * MICRO
        val lonBase = lonCell.toLong() * MICRO
        repeat(count) {
            var v = varint(pos)
            id += value(v)
            v = varint(next(v))
            val latMicro = latBase + value(v)
            v = varint(next(v))
            val lonMicro = lonBase + value(v)
            v = varint(next(v))
            val region = value(v).toInt()
            v = varint(next(v))
            val nameOffset = next(v)
            val nameLength = value(v).toInt()
            pos = nameOffset + nameLength

            val d = haversineKm(lat, lon, latMicro.toDouble() / MICRO_D, lonMicro.toDouble() / MICRO_D)
            if (d < best.distance || (d == best.distance && id < best.id)) {
                best.distance = d
                best.id = id
                best.region = region
                best.nameOffset = nameOffset
                best.nameLength = nameLength
            }
        }
    }

    /** Binary search of the cell index; returns the entry's byte offset, or -1. */
    private fun findCell(key: Int): Int {
        var lo = 0
        var hi = cellCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val entry = cellIndexOffset + 8 * mid
            val k = u16(entry)
            when {
                k < key -> lo = mid + 1
                k > key -> hi = mid - 1
                else -> return entry
            }
        }
        return -1
    }

    private class Best {
        var distance = Double.POSITIVE_INFINITY
        var id = Long.MAX_VALUE
        var region = 0
        var nameOffset = 0
        var nameLength = 0
    }

    // -- raw reads (all absolute, so the shared buffer is never mutated) ------------------

    private fun u16(at: Int): Int = buf.getShort(at).toInt() and 0xFFFF

    private fun u32(at: Int): Long = buf.getInt(at).toLong() and 0xFFFF_FFFFL

    /**
     * An unsigned LEB128 varint at [at], packed as `value shl 32 or nextOffset` so the hot
     * loop allocates nothing; unpack with [value] and [next]. Every varint in a v1 file
     * (geonameid deltas, micro-degree offsets, region indices, string lengths) is below
     * 2^31, so the value always fits in the upper half.
     */
    private fun varint(at: Int): Long {
        var pos = at
        var value = 0L
        var shift = 0
        while (true) {
            val b = buf.get(pos++).toInt() and 0xFF
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) {
                check(value < 0x8000_0000L) { "varint out of range at offset $at" }
                return (value shl 32) or pos.toLong()
            }
            shift += 7
        }
    }

    private fun value(packed: Long): Long = packed ushr 32

    private fun next(packed: Long): Int = packed.toInt()

    private fun bytes(at: Int, length: Int): ByteArray = ByteArray(length) { buf.get(at + it) }

    private fun utf8(at: Int, length: Int): String = String(bytes(at, length), Charsets.UTF_8)

    private companion object {
        const val MICRO = 1_000_000L
        const val MICRO_D = 1_000_000.0
    }
}

private fun wrapCell(lonCell: Int): Int = (lonCell + 180).mod(360) - 180

/** `radians(x) = x × (π / 180)`, exactly as the README and Python's `math.radians` define it. */
private fun radians(degrees: Double): Double = degrees * (PI / 180.0)

internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = radians(lat1)
    val p2 = radians(lat2)
    val dp = p2 - p1
    val dl = radians(lon2 - lon1)
    val s1 = sin(dp / 2)
    val s2 = sin(dl / 2)
    // Same operation order as reference.py, so the roundings match too.
    val a = s1 * s1 + cos(p1) * cos(p2) * (s2 * s2)
    return 2 * PlaceThresholds.EARTH_RADIUS_KM * asin(min(1.0, sqrt(a)))
}

/** Lower bound on the distance from (lat, lon) to anything outside the block of cells
 * within ring [r] of ([qLat], [qLon]); README "Lookup", step 3 of the search. */
private fun outsideBoundKm(lat: Double, lon: Double, qLat: Int, qLon: Int, r: Int): Double {
    var bound = Double.POSITIVE_INFINITY
    val north = qLat + r + 1
    val south = qLat - r
    if (north < 90) bound = min(bound, radians(north - lat) * PlaceThresholds.EARTH_RADIUS_KM)
    if (south > -90) bound = min(bound, radians(lat - south) * PlaceThresholds.EARTH_RADIUS_KM)
    if (2 * r + 1 < 360) {
        val dLon = min(lon - (qLon - r), (qLon + r + 1) - lon)
        bound =
            if (dLon >= 90) {
                min(bound, (PI / 2 - radians(abs(lat))) * PlaceThresholds.EARTH_RADIUS_KM)
            } else {
                val x = sin(radians(dLon)) * cos(radians(lat))
                min(bound, asin(min(1.0, x)) * PlaceThresholds.EARTH_RADIUS_KM)
            }
    }
    return bound
}
