package fr.enry.archivist.data.repo

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.domain.GeoPack
import fr.enry.archivist.domain.GeoPackSet
import fr.enry.archivist.domain.GeoTiles
import fr.enry.archivist.domain.Place
import fr.enry.archivist.domain.ReverseGeocoder
import fr.enry.archivist.domain.combinePlace
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** `https://<domain>/geo/manifest.json` (tools/geopack/README.md, "Manifest"): the tiles
 * of one set, keyed by grid square. A tile is at `<set>/<key>.bin` beside it. */
@Serializable
data class GeoManifest(
    val format: Int,
    val set: String,
    val attribution: String? = null,
    val tiles: Map<String, GeoManifestTile> = emptyMap(),
)

@Serializable
data class GeoManifestTile(
    val bytes: Long,
    val sha256: String,
)

/** What the instance's region packs say about one point. */
sealed interface GeoPacks {
    /** The instance publishes none: the gazetteer alone, with its own `src`. */
    data object None : GeoPacks

    /** Packs exist (or may) but the ones needed couldn't be had: the gazetteer alone,
     * with its own `src`, so a later re-geocode finds and redoes the photo. */
    data object Unavailable : GeoPacks

    /** A tile set exists, and no tile's square contains the point: nothing in reach. */
    data class Uncovered(val set: String) : GeoPacks

    /** The tile whose square contains the point. */
    data class Covered(val set: String, val packs: GeoPackSet) : GeoPacks
}

/** On-device region data, for Settings > Storage and sign-out. */
interface RegionTileCache {
    /** Bytes on disk: downloaded tiles and saved manifests. */
    suspend fun sizeBytes(): Long

    /** Deletes everything; tiles are fetched again when a photo next needs one. */
    suspend fun clear()
}

/**
 * The connected instance's region tiles (design.md "Detailed places: OpenStreetMap region
 * packs"): the manifest, revalidated at most every [MANIFEST_TTL_MS] and kept on disk for
 * when the phone is offline, and the one tile a photo's point falls in, fetched when a
 * photo first needs it, checked against the manifest's size and sha256, and cached by
 * sha256 under `noBackupFilesDir/geo/`. At most [MAX_TILES_IN_MEMORY] decoded tiles stay
 * in memory (a tile is capped at about 1 MB on disk, a few MB decoded). Every fetch goes
 * to the user's own instance, never a third party. Serialised by a mutex: uploads geocode
 * concurrently.
 *
 * Tiles don't pile up: after each successful manifest fetch (at most every
 * [MANIFEST_TTL_MS]), a tile the manifest no longer names (a rebuilt or withdrawn set) is
 * deleted, and so is one unused for [UNUSED_TILE_MS]: a trip's tiles go a few months
 * after the trip. A tile's file time is its last use. Settings > Storage shows the total
 * and can clear it ([RegionTileCache]); signing out clears it too.
 */
@Singleton
class GeoPackRepository
    @Inject
    constructor(
        private val instanceStore: InstanceStore,
        private val okHttpClient: OkHttpClient,
        @ApplicationContext private val context: Context,
    ) : RegionTileCache {
        private val json = Json { ignoreUnknownKeys = true }
        private val mutex = Mutex()
        private var manifestHost: String? = null
        private var manifest: GeoManifest? = null
        private var manifestFetchedAt = 0L
        // Decoded tiles by sha256, least recently used first.
        private val tiles = LinkedHashMap<String, GeoPackSet>(8, 0.75f, true)

        private val dir: File get() = context.noBackupFilesDir.resolve("geo")

        suspend fun packsFor(
            lat: Double,
            lon: Double,
        ): GeoPacks =
            mutex.withLock {
                try {
                    val host = instanceStore.current.first()?.host ?: return GeoPacks.None
                    val m = manifestFor(host) ?: return GeoPacks.None
                    val key = GeoTiles.find(m.tiles.keys, lat, lon) ?: return GeoPacks.Uncovered(m.set)
                    val entry = m.tiles.getValue(key)
                    val set = tiles[entry.sha256] ?: load(host, m, key, entry)
                    GeoPacks.Covered(m.set, set)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("GeoPackRepository", "region tile unavailable, using the gazetteer alone", e)
                    GeoPacks.Unavailable
                } catch (e: OutOfMemoryError) {
                    // Tiles are capped so this shouldn't happen; if it does, the upload goes
                    // on with the gazetteer rather than crashing, and a re-geocode redoes it.
                    tiles.clear()
                    Log.w("GeoPackRepository", "out of memory reading a region tile, using the gazetteer alone", e)
                    GeoPacks.Unavailable
                }
            }

        /** Null when the instance publishes no packs (403/404). Throws when that can't be
         * decided (offline with no saved manifest). */
        private suspend fun manifestFor(host: String): GeoManifest? {
            val now = System.currentTimeMillis()
            if (host == manifestHost && now - manifestFetchedAt < MANIFEST_TTL_MS) return manifest
            val saved = dir.resolve("manifest-${sha256(host.toByteArray())}.json")
            var fetched = false
            val fresh: GeoManifest? =
                try {
                    withContext(Dispatchers.IO) {
                        val request = Request.Builder().url("https://$host/geo/manifest.json").build()
                        okHttpClient.newCall(request).execute().use { response ->
                            fetched = response.code == 403 || response.code == 404 || response.isSuccessful
                            when {
                                response.code == 403 || response.code == 404 -> {
                                    saved.delete()
                                    null
                                }
                                !response.isSuccessful -> throw IOException("GET manifest: HTTP ${response.code}")
                                else -> {
                                    val text = response.body?.string() ?: throw IOException("empty manifest")
                                    validate(json.decodeFromString(GeoManifest.serializer(), text)).also {
                                        dir.mkdirs()
                                        saved.writeText(text)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (!saved.exists()) throw e
                    Log.w("GeoPackRepository", "manifest fetch failed, using the saved copy", e)
                    validate(json.decodeFromString(GeoManifest.serializer(), saved.readText()))
                }
            manifestHost = host
            manifest = fresh
            manifestFetchedAt = now
            // Only on what the instance just said, never on a saved copy: offline, nothing
            // is known to be superseded.
            if (fetched) prune(fresh?.tiles?.values?.mapTo(HashSet()) { it.sha256 } ?: emptySet(), now)
            return fresh
        }

        private suspend fun load(
            host: String,
            m: GeoManifest,
            key: String,
            entry: GeoManifestTile,
        ): GeoPackSet {
            val file = dir.resolve("packs").resolve("${entry.sha256}.bin")
            val bytes =
                withContext(Dispatchers.IO) {
                    val cached = if (file.exists()) file.readBytes() else null
                    if (cached != null && sha256(cached) == entry.sha256) {
                        cached
                    } else {
                        val url = "https://$host/geo/${m.set}/$key.bin"
                        val body =
                            okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                                if (!response.isSuccessful) throw IOException("GET $url failed: HTTP ${response.code}")
                                response.body?.bytes() ?: throw IOException("empty response body for $url")
                            }
                        if (body.size.toLong() != entry.bytes || sha256(body) != entry.sha256) {
                            throw IOException("$url: size or sha256 differs from the manifest")
                        }
                        file.parentFile?.mkdirs()
                        val tmp = File(file.path + ".tmp")
                        tmp.writeBytes(body)
                        if (!tmp.renameTo(file)) throw IOException("couldn't store ${file.name}")
                        body
                    }
                }
            val set =
                withContext(Dispatchers.Default) {
                    val pack = GeoPack.read(bytes)
                    if (pack.set != m.set || pack.name != key) {
                        throw IOException("tile $key says it is ${pack.set}/${pack.name}")
                    }
                    GeoPackSet(listOf(pack))
                }
            tiles[entry.sha256] = set
            while (tiles.size > MAX_TILES_IN_MEMORY) tiles.remove(tiles.keys.first())
            withContext(Dispatchers.IO) { file.setLastModified(System.currentTimeMillis()) } // last use
            return set
        }

        /** Deletes cached tiles the current manifest doesn't name, and ones unused for
         * [UNUSED_TILE_MS], except those decoded in memory right now. */
        private suspend fun prune(
            keep: Set<String>,
            now: Long,
        ) {
            tiles.keys.retainAll(keep)
            withContext(Dispatchers.IO) {
                val files = dir.resolve("packs").listFiles() ?: return@withContext
                for (f in files) {
                    val sha = f.name.removeSuffix(".bin")
                    val superseded = f.name.endsWith(".tmp") || sha !in keep
                    val unused = now - f.lastModified() > UNUSED_TILE_MS
                    if ((superseded || unused) && sha !in tiles) f.delete()
                }
            }
        }

        override suspend fun sizeBytes(): Long =
            withContext(Dispatchers.IO) {
                dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }

        override suspend fun clear() {
            mutex.withLock {
                tiles.clear()
                manifestHost = null
                manifest = null
                manifestFetchedAt = 0L
                withContext(Dispatchers.IO) { dir.deleteRecursively() }
            }
        }

        private fun validate(m: GeoManifest): GeoManifest {
            require(m.format == 2) { "unsupported region-pack manifest format ${m.format}" }
            require(SET_RE.matches(m.set)) { "bad manifest set" }
            for ((key, t) in m.tiles) {
                require(GeoTiles.isKey(key)) { "bad tile key" }
                require(SHA_RE.matches(t.sha256)) { "bad sha256" }
            }
            val keys = m.tiles.keys.sorted()
            for (i in 1 until keys.size) require(!keys[i].startsWith(keys[i - 1])) { "nested tiles" }
            return m
        }

        private companion object {
            const val MANIFEST_TTL_MS = 5 * 60 * 1000L
            const val MAX_TILES_IN_MEMORY = 4
            const val UNUSED_TILE_MS = 90L * 24 * 60 * 60 * 1000
            val SET_RE = Regex("^[0-9A-Za-z._-]{1,20}$")
            val SHA_RE = Regex("^[0-9a-f]{64}$")

            fun sha256(bytes: ByteArray): String =
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }

/**
 * The `place` an upload or "Update location" stores: the bundled gazetteer, plus the
 * instance's region packs where they cover the photo (tools/geopack/README.md, "Combining
 * with the gazetteer"). Same rules as `tools/import-from-local/places.py`, so both clients
 * give one photo the same place.
 */
@Singleton
class PlaceGeocoder internal constructor(
    private val reverseGeocoder: ReverseGeocoder,
    private val packsFor: suspend (Double, Double) -> GeoPacks,
) {
    @Inject
    constructor(reverseGeocoder: ReverseGeocoder, geoPacks: GeoPackRepository) :
        this(reverseGeocoder, geoPacks::packsFor)

    suspend fun lookup(
        lat: Double,
        lon: Double,
    ): Place? {
        val base = reverseGeocoder.lookup(lat, lon) ?: return null
        return when (val p = packsFor(lat, lon)) {
            GeoPacks.None, GeoPacks.Unavailable -> base
            is GeoPacks.Uncovered -> combinePlace(base, p.set, null)
            is GeoPacks.Covered -> combinePlace(base, p.set, p.packs.lookup(lat, lon))
        }
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class RegionTileCacheModule {
    @dagger.Binds
    abstract fun bindRegionTileCache(impl: GeoPackRepository): RegionTileCache
}
