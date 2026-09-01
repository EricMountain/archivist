package fr.enry.archivist.crypto

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
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
 * rather than producing a wrong-but-decodable image.
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
 * errors. */
class ImageLockedException : IOException("master key is not available -- app is locked")

/**
 * Plan step 2.11's Coil `Fetcher`: fetch ciphertext from CloudFront → unwrap the asset
 * DEK with the in-memory master key → decrypt → hand Coil the plaintext WebP bytes,
 * which Coil's own built-in decoder turns into a bitmap the same as any other image
 * source. See "Decrypting for display" in android.md. Coil's disk cache sits in front
 * of this (configured in `ArchivistApplication.newImageLoader`) and holds the
 * **plaintext** result, so a cache hit never re-fetches or re-decrypts at all.
 */
class EncryptedImageFetcher(
    private val ref: EncryptedThumbRef,
    private val okHttpClient: OkHttpClient,
    private val masterKeyHolder: MasterKeyHolder,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
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

        return SourceFetchResult(
            source = ImageSource(Buffer().write(plaintext), FileSystem.SYSTEM),
            mimeType = "image/webp",
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(
        private val okHttpClient: OkHttpClient,
        private val masterKeyHolder: MasterKeyHolder,
    ) : Fetcher.Factory<EncryptedThumbRef> {
        override fun create(
            data: EncryptedThumbRef,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = EncryptedImageFetcher(data, okHttpClient, masterKeyHolder)
    }
}

private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)
