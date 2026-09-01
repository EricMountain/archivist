package fr.enry.archivist.crypto

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import fr.enry.archivist.data.repo.MasterKeyHolder
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.FileSystem

/**
 * What `AsyncImage`'s `model` is set to for a grid cell — everything
 * [EncryptedImageFetcher] needs to fetch one CloudFront thumbnail and decrypt it.
 * [url] is under the `/thumbs` CloudFront behavior, unauthenticated at the CDN layer per api.md (the protection is
 * an unguessable ciphertext key, not access control), so this fetcher makes a plain
 * unauthenticated GET rather than going through [fr.enry.archivist.data.remote.ArchivistApi].
 * [longestEdge] must be the exact size key the thumbnail was encrypted under — it's
 * embedded in the AAD (`crypto-format.md`), so a mismatch fails decryption outright
 * rather than producing a wrong-but-decodable image. [url] doubles as the disk cache
 * key (see [EncryptedImageFetcher]) — stable across app restarts since it's built
 * straight from `PhotoEntity`/`ThumbEntry` fields already persisted in Room.
 */
data class EncryptedThumbRef(
    val photoId: String,
    val longestEdge: Int,
    val url: String,
    val iv: String,
    val encDek: String,
)

/** Thrown when the master key isn't in memory. Defensive fallback, not the primary
 * lock gate — `fr.enry.archivist.ui.timeline.TimelineViewModel` already keeps the
 * whole grid off-screen while locked (see "Locked state" in android.md), specifically
 * so a user sees one explicit unlock prompt rather than a grid of per-cell decode
 * errors. Never thrown on a disk-cache hit — see [EncryptedImageFetcher.fetch]. */
class ImageLockedException : IOException("master key is not available -- app is locked")

/**
 * Plan step 2.11's Coil `Fetcher`: fetch ciphertext from CloudFront → unwrap the asset
 * DEK with the in-memory master key → decrypt → hand Coil the plaintext WebP bytes,
 * which Coil's own built-in decoder turns into a bitmap the same as any other image
 * source. See "Decrypting for display" in android.md.
 *
 * **Disk caching is this class's own job, not something Coil does automatically for a
 * custom [Fetcher].** Confirmed by reading Coil 3.3.0's own `EngineInterceptor`/
 * `NetworkFetcher` source after a live 1,100-photo test found the disk cache directory
 * staying completely empty despite hundreds of successful fetches: a [SourceFetchResult]
 * only gets a `diskCacheKey` (and therefore ever gets written to the cache at all) when
 * its [ImageSource] is already file-backed with one attached — a plain
 * `ImageSource(BufferedSource, FileSystem)` like this class returned before is invisible
 * to Coil's own cache-write step. `NetworkFetcher` is Coil's own reference
 * implementation for "read the disk cache first, write to it after a successful fetch";
 * this class follows the same shape, reading/writing the **decrypted plaintext**
 * (never the ciphertext) so a cache hit needs neither the network nor the master key —
 * matching android.md's "a cache hit never re-fetches or re-decrypts at all" exactly,
 * including for a device that's currently locked.
 */
class EncryptedImageFetcher(
    private val ref: EncryptedThumbRef,
    private val okHttpClient: OkHttpClient,
    private val masterKeyHolder: MasterKeyHolder,
    private val diskCache: DiskCache?,
) : Fetcher {
    /** [EncryptedThumbRef.url] is already a stable, unique-per-thumbnail string
     * (derived from persisted `PhotoEntity`/`ThumbEntry` fields), so it doubles as the
     * disk cache key with no extra bookkeeping needed. */
    private val diskCacheKey: String
        get() = ref.url

    override suspend fun fetch(): FetchResult {
        readFromDiskCache()?.let { return it }

        val masterKey = masterKeyHolder.current.value ?: throw ImageLockedException()

        val ciphertext =
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(ref.url).build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("GET ${ref.url} failed: HTTP ${response.code}")
                    response.body?.bytes() ?: throw IOException("empty response body for ${ref.url}")
                }
            }

        val dek = masterKey.unwrapDek(decode(ref.encDek))
        val plaintext =
            WholeObjectCipher.decrypt(
                dek,
                decode(ref.iv),
                Aad.of(ref.photoId, ObjectRef.Thumbnail(ref.longestEdge)),
                ciphertext,
            )

        writeToDiskCache(plaintext)?.let { return it }

        // No disk cache configured, or writing to it failed (e.g. a concurrent editor
        // already open for this same key) -- still render this once from memory rather
        // than failing the whole load over a caching problem.
        return SourceFetchResult(
            source = ImageSource(Buffer().write(plaintext), FileSystem.SYSTEM),
            mimeType = "image/webp",
            dataSource = DataSource.NETWORK,
        )
    }

    private fun readFromDiskCache(): SourceFetchResult? {
        val snapshot = diskCache?.openSnapshot(diskCacheKey) ?: return null
        return SourceFetchResult(
            source = ImageSource(file = snapshot.data, fileSystem = diskCache.fileSystem, diskCacheKey = diskCacheKey, closeable = snapshot),
            mimeType = "image/webp",
            dataSource = DataSource.DISK,
        )
    }

    private fun writeToDiskCache(plaintext: ByteArray): SourceFetchResult? {
        val cache = diskCache ?: return null
        val editor = cache.openEditor(diskCacheKey) ?: return null
        return try {
            cache.fileSystem.write(editor.data) { write(plaintext) }
            val snapshot = editor.commitAndOpenSnapshot() ?: return null
            SourceFetchResult(
                source = ImageSource(file = snapshot.data, fileSystem = cache.fileSystem, diskCacheKey = diskCacheKey, closeable = snapshot),
                mimeType = "image/webp",
                dataSource = DataSource.NETWORK,
            )
        } catch (e: Exception) {
            editor.abort()
            null
        }
    }

    class Factory(
        private val okHttpClient: OkHttpClient,
        private val masterKeyHolder: MasterKeyHolder,
    ) : Fetcher.Factory<EncryptedThumbRef> {
        override fun create(
            data: EncryptedThumbRef,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = EncryptedImageFetcher(data, okHttpClient, masterKeyHolder, imageLoader.diskCache)
    }
}

private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)
