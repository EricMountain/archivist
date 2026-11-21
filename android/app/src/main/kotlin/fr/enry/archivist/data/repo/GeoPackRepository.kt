package fr.enry.archivist.data.repo

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.domain.GeoPack
import fr.enry.archivist.domain.GeoPackSet
import fr.enry.archivist.domain.Place
import fr.enry.archivist.domain.ReverseGeocoder
import fr.enry.archivist.domain.combinePlace
import fr.enry.archivist.domain.geoPackCovers
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

/** `https://<domain>/geo/manifest.json` (tools/geopack/README.md, "Manifest"). */
@Serializable
data class GeoManifest(
    val format: Int,
    val set: String,
    val attribution: String? = null,
    val packs: List<GeoManifestPack> = emptyList(),
)

@Serializable
data class GeoManifestPack(
    val name: String,
    val path: String,
    val bytes: Long,
    val sha256: String,
    /** minLat, minLon, maxLat, maxLon, micro-degrees. */
    val bbox: List<Int>,
)

/** What the instance's region packs say about one point. */
sealed interface GeoPacks {
    /** The instance publishes none: the gazetteer alone, with its own `src`. */
    data object None : GeoPacks

    /** Packs exist (or may) but the ones needed couldn't be had: the gazetteer alone,
     * with its own `src`, so a later re-geocode finds and redoes the photo. */
    data object Unavailable : GeoPacks

    /** A pack set exists, and none of it covers the point. */
    data class Uncovered(val set: String) : GeoPacks

    /** Every pack covering the point, in manifest order. */
    data class Covered(val set: String, val packs: GeoPackSet) : GeoPacks
}

/**
 * The connected instance's region packs (design.md "Detailed places: OpenStreetMap region
 * packs"): the manifest, revalidated at most every [MANIFEST_TTL_MS] and kept on disk for
 * when the phone is offline, and the packs themselves, fetched when a photo first needs
 * them, checked against the manifest's size and sha256, and cached by sha256 under
 * `noBackupFilesDir/geo/`. Every fetch goes to the user's own instance, never a third
 * party. Serialised by a mutex: uploads geocode concurrently.
 */
@Singleton
class GeoPackRepository
    @Inject
    constructor(
        private val instanceStore: InstanceStore,
        private val okHttpClient: OkHttpClient,
        @ApplicationContext private val context: Context,
    ) {
        private val json = Json { ignoreUnknownKeys = true }
        private val mutex = Mutex()
        private var manifestHost: String? = null
        private var manifest: GeoManifest? = null
        private var manifestFetchedAt = 0L
        private val packs = HashMap<String, GeoPack>() // by sha256
        private val sets = HashMap<List<String>, GeoPackSet>() // by covering packs' sha256s

        private val dir: File get() = context.noBackupFilesDir.resolve("geo")

        suspend fun packsFor(
            lat: Double,
            lon: Double,
        ): GeoPacks =
            mutex.withLock {
                try {
                    val host = instanceStore.current.first()?.host ?: return GeoPacks.None
                    val m = manifestFor(host) ?: return GeoPacks.None
                    val covering = m.packs.filter { geoPackCovers(it.bbox.toIntArray(), lat, lon) }
                    if (covering.isEmpty()) return GeoPacks.Uncovered(m.set)
                    val key = covering.map { it.sha256 }
                    val set =
                        sets.getOrPut(key) {
                            GeoPackSet(covering.map { load(host, m, it) })
                        }
                    GeoPacks.Covered(m.set, set)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("GeoPackRepository", "region packs unavailable, using the gazetteer alone", e)
                    GeoPacks.Unavailable
                }
            }

        /** Null when the instance publishes no packs (403/404). Throws when that can't be
         * decided (offline with no saved manifest). */
        private suspend fun manifestFor(host: String): GeoManifest? {
            val now = System.currentTimeMillis()
            if (host == manifestHost && now - manifestFetchedAt < MANIFEST_TTL_MS) return manifest
            val saved = dir.resolve("manifest-${sha256(host.toByteArray())}.json")
            val fresh: GeoManifest? =
                try {
                    withContext(Dispatchers.IO) {
                        val request = Request.Builder().url("https://$host/geo/manifest.json").build()
                        okHttpClient.newCall(request).execute().use { response ->
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
            return fresh
        }

        private suspend fun load(
            host: String,
            m: GeoManifest,
            entry: GeoManifestPack,
        ): GeoPack {
            packs[entry.sha256]?.let { return it }
            val file = dir.resolve("packs").resolve("${entry.sha256}.bin")
            val bytes =
                withContext(Dispatchers.IO) {
                    val cached = if (file.exists()) file.readBytes() else null
                    if (cached != null && sha256(cached) == entry.sha256) {
                        cached
                    } else {
                        val url = "https://$host/geo/${entry.path}"
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
            val pack = withContext(Dispatchers.Default) { GeoPack.read(bytes) }
            if (pack.set != m.set) throw IOException("pack ${entry.name} is from set ${pack.set}, not ${m.set}")
            packs[entry.sha256] = pack
            return pack
        }

        private fun validate(m: GeoManifest): GeoManifest {
            require(m.format == 1) { "unsupported region-pack manifest format ${m.format}" }
            require(SET_RE.matches(m.set)) { "bad manifest set" }
            for (p in m.packs) {
                require(NAME_RE.matches(p.name)) { "bad pack name" }
                require(SHA_RE.matches(p.sha256)) { "bad sha256" }
                require(p.bbox.size == 4 && p.bbox[0] <= p.bbox[2] && p.bbox[1] <= p.bbox[3]) { "bad bbox" }
                require(!p.path.startsWith("/") && ".." !in p.path) { "bad path" }
            }
            require(m.packs.map { it.name }.toSet().size == m.packs.size) { "duplicate pack name" }
            return m
        }

        private companion object {
            const val MANIFEST_TTL_MS = 5 * 60 * 1000L
            val SET_RE = Regex("^[0-9A-Za-z._-]{1,20}$")
            val NAME_RE = Regex("^[a-z0-9-]{1,64}$")
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
