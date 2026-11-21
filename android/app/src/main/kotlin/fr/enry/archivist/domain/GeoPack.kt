package fr.enry.archivist.domain

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Region packs: per-region OpenStreetMap extracts an instance can publish (design.md
 * "Detailed places: OpenStreetMap region packs"). The binary format and the lookup are
 * normative in `tools/geopack/README.md`; this is a port of `tools/geopack/reference.py`
 * and must reproduce `tools/geopack/vectors.json` exactly (`GeoPackTest`).
 *
 * A pack is a few MB at most and is decoded whole. Pure Kotlin, no Android types.
 */
class GeoPack private constructor(
    val set: String,
    val name: String,
    val attribution: String,
    /** minLat, minLon, maxLat, maxLon, micro-degrees. */
    val bbox: IntArray,
    val entities: List<GeoEntity>,
) {
    companion object {
        const val ADMIN = 1
        const val PLACE = 2
        const val PROTECTED = 3
        const val FEATURE = 4
        const val POINT = 1
        const val LINES = 2
        const val POLYGONS = 3

        /** Throws [IllegalArgumentException] for anything that isn't a well-formed v1 pack. */
        fun read(bytes: ByteArray): GeoPack =
            try {
                Reader(bytes).pack()
            } catch (e: IndexOutOfBoundsException) {
                throw IllegalArgumentException("truncated region pack", e)
            }
    }

    private class Reader(private val bytes: ByteArray) {
        private var pos = 0

        fun pack(): GeoPack {
            val head = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size >= 32) { "not a region pack" }
            require(bytes[0] == 'A'.code.toByte() && bytes[1] == 'G'.code.toByte() &&
                bytes[2] == 'E'.code.toByte() && bytes[3] == 'O'.code.toByte()) { "not a region pack" }
            require(head.getShort(4).toInt() == 1) { "unsupported region pack version" }
            val count = head.getInt(8)
            require(head.getInt(12) == bytes.size) { "region pack length mismatch" }
            val bbox = IntArray(4) { head.getInt(16 + 4 * it) }
            pos = 32
            val set = vstr()
            val name = vstr()
            val attribution = vstr()
            val entities = ArrayList<GeoEntity>(count)
            repeat(count) { entities.add(entity()) }
            require(pos == bytes.size) { "trailing bytes in region pack" }
            return GeoPack(set, name, attribution, bbox, entities)
        }

        private fun u8(): Int = bytes[pos++].toInt() and 0xFF

        private fun varint(): Long {
            var v = 0L
            var shift = 0
            while (true) {
                val b = u8()
                v = v or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return v
                shift += 7
                require(shift < 64) { "varint too long" }
            }
        }

        private fun zigzag(): Int {
            val v = varint()
            return ((v ushr 1) xor -(v and 1)).toInt()
        }

        private fun vstr(): String {
            val n = varint().toInt()
            val s = String(bytes, pos, n, Charsets.UTF_8)
            pos += n
            return s
        }

        private fun entity(): GeoEntity {
            val layer = u8()
            val otype = u8().toChar()
            require(otype == 'n' || otype == 'w' || otype == 'r') { "bad osm type" }
            val oid = varint()
            val name = vstr()
            var level = 0
            var label = 0L
            var kind = 0
            var rank = 0
            var area = 0L
            when (layer) {
                ADMIN -> {
                    level = u8()
                    area = varint()
                    label = varint()
                }
                PLACE -> kind = u8()
                PROTECTED -> {
                    rank = u8()
                    area = varint()
                }
                FEATURE -> {
                    kind = u8()
                    area = varint()
                }
                else -> throw IllegalArgumentException("unknown layer $layer")
            }
            val geomType = u8()
            val parts: List<IntArray>
            if (geomType == POINT) {
                val la = zigzag()
                val lo = zigzag()
                parts = listOf(intArrayOf(la, lo))
            } else {
                require(geomType == LINES || geomType == POLYGONS) { "bad geometry type" }
                val nParts = varint().toInt()
                var la = 0
                var lo = 0
                parts =
                    List(nParts) {
                        val n = varint().toInt()
                        val pts = IntArray(2 * n)
                        for (i in 0 until n) {
                            la += zigzag()
                            lo += zigzag()
                            pts[2 * i] = la
                            pts[2 * i + 1] = lo
                        }
                        pts
                    }
            }
            return GeoEntity(layer, otype, oid, name, level, label, kind, rank, area, geomType, parts)
        }
    }
}

/** One pack entity. [parts] hold interleaved (lat, lon) micro-degree pairs. */
class GeoEntity(
    val layer: Int,
    val otype: Char,
    val oid: Long,
    val name: String,
    val level: Int,
    val label: Long,
    val kind: Int,
    val rank: Int,
    val area: Long,
    val geomType: Int,
    val parts: List<IntArray>,
) {
    val id: String get() = "$otype$oid"

    /** README: ids order n < w < r, then numerically. */
    internal val typeOrder: Int = when (otype) {
        'n' -> 0
        'w' -> 1
        else -> 2
    }

    val minLat: Int
    val minLon: Int
    val maxLat: Int
    val maxLon: Int

    init {
        var a = Int.MAX_VALUE
        var b = Int.MAX_VALUE
        var c = Int.MIN_VALUE
        var d = Int.MIN_VALUE
        for (p in parts) {
            var i = 0
            while (i < p.size) {
                a = min(a, p[i])
                c = max(c, p[i])
                b = min(b, p[i + 1])
                d = max(d, p[i + 1])
                i += 2
            }
        }
        minLat = a
        minLon = b
        maxLat = c
        maxLon = d
    }

    internal fun idLess(other: GeoEntity): Int =
        if (typeOrder != other.typeOrder) typeOrder.compareTo(other.typeOrder) else oid.compareTo(other.oid)
}

/** The pack layers of a place: what [GeoPackSet.lookup] returns. */
data class PackPlace(
    val locality: PlaceRef? = null,
    val sub: PlaceRef? = null,
    val spot: PlaceRef? = null,
    val areas: List<PlaceRef> = emptyList(),
    val feature: PlaceRef? = null,
)

/** tools/geopack/README.md, "Tiles": the grid a tile set is cut on. */
object GeoTiles {
    private const val GRID_BITS = 29
    private const val ORIGIN = 1L shl 28
    private val KEY_RE = Regex("^[0-3]{1,29}$")

    fun isKey(key: String): Boolean = KEY_RE.matches(key)

    /** The key of the grid square at [depth] containing the point: one digit per depth,
     * 2 × the latitude bit + the longitude bit, most significant first. */
    fun pointKey(
        lat: Double,
        lon: Double,
        depth: Int = GRID_BITS,
    ): String {
        val y = floor(lat * 1e6).toLong() + ORIGIN
        val x = floor(lon * 1e6).toLong() + ORIGIN
        val sb = StringBuilder(depth)
        for (d in 1..depth) {
            val shift = GRID_BITS - d
            sb.append((((y shr shift) and 1L) * 2 + ((x shr shift) and 1L)).toInt())
        }
        return sb.toString()
    }

    /** The one key in [keys] (prefix-free, as a manifest's are) that is a prefix of the
     * point's key, or null: no tile, nothing in reach. */
    fun find(
        keys: Set<String>,
        lat: Double,
        lon: Double,
    ): String? {
        val longest = keys.maxOfOrNull { it.length } ?: return null
        val full = pointKey(lat, lon, longest)
        for (d in 1..longest) {
            val k = full.substring(0, d)
            if (k in keys) return k
        }
        return null
    }
}

/**
 * One or more packs (in order; an entity in several is taken from the first), indexed on
 * a 0.1° grid. The app uses one tile at a time; several packs are for the conformance
 * tests' region packs. Thread-safe after construction.
 */
class GeoPackSet(packs: List<GeoPack>) {
    private val entities: List<GeoEntity>
    private val grid = HashMap<Long, MutableList<GeoEntity>>()

    init {
        val seen = HashSet<Triple<Int, Char, Long>>()
        val out = ArrayList<GeoEntity>()
        for (p in packs) for (e in p.entities) if (seen.add(Triple(e.layer, e.otype, e.oid))) out.add(e)
        entities = out
        val latPad = (MAX_REACH_M / K * 1e6).toInt() + 1
        for (e in entities) {
            val poleward = min(max(abs(e.minLat), abs(e.maxLat)) / 1e6 + latPad / 1e6, 89.9)
            val lonPad = (MAX_REACH_M / (K * max(cos(Math.toRadians(poleward)), 0.01)) * 1e6).toInt() + 1
            for (cy in Math.floorDiv(e.minLat - latPad, CELL)..Math.floorDiv(e.maxLat + latPad, CELL)) {
                for (cx in Math.floorDiv(e.minLon - lonPad, CELL)..Math.floorDiv(e.maxLon + lonPad, CELL)) {
                    grid.getOrPut(cellKey(cy, cx)) { ArrayList() }.add(e)
                }
            }
        }
    }

    fun lookup(
        lat: Double,
        lon: Double,
    ): PackPlace {
        val cy = Math.floorDiv(floor(lat * 1e6).toLong(), CELL.toLong()).toInt()
        val cx = Math.floorDiv(floor(lon * 1e6).toLong(), CELL.toLong()).toInt()
        return resolve(lat, lon, grid[cellKey(cy, cx)] ?: emptyList())
    }

    /** The oracle: every entity is a candidate. */
    internal fun brute(
        lat: Double,
        lon: Double,
    ): PackPlace = resolve(lat, lon, entities)

    private fun cellKey(
        cy: Int,
        cx: Int,
    ): Long = (cy.toLong() shl 32) or (cx.toLong() and 0xFFFFFFFFL)

    private class Plane(val lat: Double, val lon: Double) {
        val kc = K * cos(lat * PI / 180.0)

        fun x(lonM: Int): Double {
            var dLon = lonM / 1e6 - lon
            if (dLon >= 180.0) dLon -= 360.0
            if (dLon < -180.0) dLon += 360.0
            return dLon * kc
        }

        fun y(latM: Int): Double = (latM / 1e6 - lat) * K
    }

    private fun segDist(
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        val den = dx * dx + dy * dy
        if (den == 0.0) return hypot(ax, ay)
        var t = -(ax * dx + ay * dy) / den
        if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
        return hypot(ax + t * dx, ay + t * dy)
    }

    private fun inside(
        pl: Plane,
        e: GeoEntity,
    ): Boolean {
        var res = false
        for (p in e.parts) {
            val n = p.size / 2
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ax = pl.x(p[2 * i + 1])
                val ay = pl.y(p[2 * i])
                val bx = pl.x(p[2 * j + 1])
                val by = pl.y(p[2 * j])
                if ((ay > 0.0) != (by > 0.0)) {
                    if (ax + (0.0 - ay) * (bx - ax) / (by - ay) > 0.0) res = !res
                }
            }
        }
        return res
    }

    private fun dist(
        pl: Plane,
        e: GeoEntity,
    ): Double {
        if (e.geomType == GeoPack.POINT) return hypot(pl.x(e.parts[0][1]), pl.y(e.parts[0][0]))
        val closed = e.geomType == GeoPack.POLYGONS
        if (closed && inside(pl, e)) return 0.0
        var best = Double.POSITIVE_INFINITY
        for (p in e.parts) {
            val n = p.size / 2
            val edges = if (closed) n else n - 1
            for (i in 0 until edges) {
                val j = (i + 1) % n
                val d = segDist(pl.x(p[2 * i + 1]), pl.y(p[2 * i]), pl.x(p[2 * j + 1]), pl.y(p[2 * j]))
                if (d < best) best = d
            }
        }
        return best
    }

    private fun ref(e: GeoEntity) = PlaceRef(e.id, e.name)

    /** README "Lookup", "Rules", over [cands] (a superset of what can matter). */
    private fun resolve(
        lat: Double,
        lon: Double,
        cands: List<GeoEntity>,
    ): PackPlace {
        val pl = Plane(lat, lon)
        val admins = cands.filter { it.layer == GeoPack.ADMIN }
        val containing = admins.filter { inside(pl, it) }
        val inner = containing.filter { it.level in 6..8 }
        val loc: GeoEntity? =
            if (inner.isNotEmpty()) {
                inner.minWithOrNull(
                    Comparator { a, b ->
                        when {
                            a.level != b.level -> b.level.compareTo(a.level)
                            a.area != b.area -> a.area.compareTo(b.area)
                            else -> a.idLess(b)
                        }
                    },
                )
            } else {
                admins.filter { it.level in 6..8 }
                    .map { it to dist(pl, it) }
                    .filter { it.second <= SEA_FALLBACK_M }
                    .minWithOrNull(
                        Comparator { (a, da), (b, db) ->
                            when {
                                da != db -> da.compareTo(db)
                                a.level != b.level -> b.level.compareTo(a.level)
                                a.area != b.area -> a.area.compareTo(b.area)
                                else -> a.idLess(b)
                            }
                        },
                    )?.first
            }
        val suppressed = HashSet<Long>()
        for (e in containing) if (e.label != 0L) suppressed.add(e.label)
        if (loc != null && loc.label != 0L) suppressed.add(loc.label)
        val locName = loc?.name
        // When the locality contains the point, sub and spot must lie inside it too: a
        // town across a river or a commune border is a different place, however close.
        val bound = if (inner.isNotEmpty()) loc else null

        fun nearestPlace(
            radii: Map<Int, Double>,
            otherName: String?,
        ): GeoEntity? {
            var best: GeoEntity? = null
            var bestScore = 0.0
            for (e in cands) {
                if (e.layer != GeoPack.PLACE) continue
                val r = radii[e.kind] ?: continue
                if (e.otype == 'n' && e.oid in suppressed) continue
                if (e.name == locName || e.name == otherName) continue
                val d = dist(pl, e)
                if (d <= r && (bound == null || inside(Plane(e.parts[0][0] / 1e6, e.parts[0][1] / 1e6), bound))) {
                    val score = d / r
                    if (best == null || score < bestScore || (score == bestScore && e.idLess(best) < 0)) {
                        best = e
                        bestScore = score
                    }
                }
            }
            return best
        }

        val sub = nearestPlace(SUB_RADIUS, null)
        val spot = nearestPlace(SPOT_RADIUS, sub?.name)

        val areas =
            cands.filter { it.layer == GeoPack.PROTECTED && inside(pl, it) }
                .sortedWith(
                    Comparator { a, b ->
                        when {
                            a.rank != b.rank -> a.rank.compareTo(b.rank)
                            a.area != b.area -> a.area.compareTo(b.area)
                            else -> a.idLess(b)
                        }
                    },
                ).take(MAX_AREAS)

        var feature: GeoEntity? = null
        var bestScore = 0.0
        for (e in cands) {
            if (e.layer != GeoPack.FEATURE || e.geomType == GeoPack.POLYGONS) continue
            val r = REACH[e.kind] ?: continue
            val d = dist(pl, e)
            if (d <= r) {
                val score = d / r
                val f = feature
                if (f == null || score < bestScore || (score == bestScore && e.idLess(f) < 0)) {
                    feature = e
                    bestScore = score
                }
            }
        }
        if (feature == null) {
            for (e in cands) {
                if (e.layer != GeoPack.FEATURE || e.geomType != GeoPack.POLYGONS) continue
                if (loc != null) {
                    if (!(e.area * 2 < loc.area)) continue
                } else if (e.area > NO_LOCALITY_AREA_CAP) {
                    continue
                }
                val m = MARGIN[e.kind] ?: 0.0
                val ok = if (m == 0.0) inside(pl, e) else dist(pl, e) <= m
                if (!ok) continue
                val f = feature
                if (f == null || e.area < f.area || (e.area == f.area && e.idLess(f) < 0)) feature = e
            }
        }
        return PackPlace(
            locality = loc?.let(::ref),
            sub = sub?.let(::ref),
            spot = spot?.let(::ref),
            areas = areas.map(::ref),
            feature = feature?.let(::ref),
        )
    }

    private companion object {
        const val K = 6371008.8 * PI / 180.0
        const val CELL = 100_000
        const val SEA_FALLBACK_M = 2000.0
        val SUB_RADIUS = mapOf(1 to 4000.0, 2 to 2500.0, 3 to 1500.0, 4 to 1200.0)
        val SPOT_RADIUS = mapOf(5 to 800.0, 6 to 800.0, 7 to 500.0, 8 to 400.0)
        val REACH = mapOf(1 to 600.0, 2 to 600.0, 3 to 300.0, 4 to 500.0, 5 to 400.0, 6 to 300.0, 7 to 200.0, 8 to 300.0, 9 to 300.0)
        val MARGIN = mapOf(13 to 150.0, 15 to 100.0)
        const val NO_LOCALITY_AREA_CAP = 10_000_000L
        const val MAX_AREAS = 3
        val MAX_REACH_M = 4000.0
    }
}

/**
 * tools/geopack/README.md "Combining with the gazetteer", steps 3–4: [base] is the
 * gazetteer's place (country and region always stay its); with no pack covering the point
 * [layers] is null. `src` becomes `osm@<set>;<gazetteer src>` either way.
 */
fun combinePlace(
    base: Place,
    set: String,
    layers: PackPlace?,
): Place =
    base.copy(
        locality = layers?.locality ?: base.locality,
        sub = layers?.sub,
        spot = layers?.spot,
        areas = layers?.areas.orEmpty(),
        feature = layers?.feature,
        src = "osm@$set;${base.src}",
    )
