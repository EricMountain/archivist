package fr.enry.archivist.data.repo

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.enry.archivist.crypto.Aad
import fr.enry.archivist.crypto.ObjectRef
import fr.enry.archivist.crypto.WholeObjectCipher
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Everything [PreviewCache] needs to fetch and decrypt one video preview clip. [url] is
 * under the `thumbs` CloudFront behavior (unauthenticated at the CDN — the protection is
 * an unguessable ciphertext key, see api.md), and doubles as the cache key: a repair mints
 * a new S3 key, so a repaired preview is a new URL and never collides with a stale copy. */
data class PreviewRef(
    val photoId: String,
    val url: String,
    val iv: String,
    val encDek: String,
)

/** A preview larger than this is refused outright. The design targets ~2 MB (a 60 s clip
 * at a few hundred kbps); this is generous headroom so a bug can't make the grid pull
 * something enormous per cell. */
internal const val MAX_PREVIEW_DOWNLOAD_BYTES = 16L * 1024 * 1024

/** Total on-disk budget for decrypted previews. ~2 MB each, so on the order of a hundred
 * clips. Least-recently-used files go first. */
internal const val PREVIEW_CACHE_MAX_BYTES = 200L * 1024 * 1024

/** A file touched within this window is never evicted — a player may be looping it right
 * now, and unlinking it out from under ExoPlayer would break the next loop. */
internal const val PREVIEW_EVICTION_GRACE_MS = 60_000L

/**
 * Downloads a video preview clip (design.md, "Video preview clip"), decrypts it with the
 * asset's DEK under [ObjectRef.Preview]'s AAD, and hands back a plaintext MP4 [File] an
 * ExoPlayer can play from a URI.
 *
 * The plaintext lives under `cacheDir` — the same policy as Coil's disk cache of decrypted
 * thumbnails ([fr.enry.archivist.crypto.EncryptedImageFetcher]) — so a cache hit needs
 * neither the network nor the master key, and [clear] wipes it (account deletion).
 *
 * **Never throws for an ordinary failure.** A preview is strictly best-effort: a missing
 * object (a dangling `#META.preview`, e.g. a client that died mid-upload), a network
 * error, or a decrypt failure all yield `null`, and the caller keeps showing the still. A
 * failure is remembered for the life of the process so scrolling a grid doesn't re-request
 * a broken preview on every pass. A *locked* app is not a failure and is not remembered.
 */
@Singleton
class PreviewCache
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val okHttpClient: OkHttpClient,
        private val masterKeyHolder: MasterKeyHolder,
    ) {
        /** Re-created on every access: the OS may purge `cacheDir` (directory included)
         * under storage pressure while the process is alive. */
        private val dir: File get() = File(context.cacheDir, "previews").apply { mkdirs() }
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

        suspend fun get(ref: PreviewRef): File? {
            val file = File(dir, fileName(ref.url))
            if (file.isFile) {
                file.setLastModified(System.currentTimeMillis())
                return file
            }
            if (ref.url in failed) return null
            val masterKey = masterKeyHolder.current.value ?: return null

            return locks.getOrPut(file.name) { Mutex() }.withLock {
                // Someone else may have finished it while this call waited for the lock.
                if (file.isFile) return@withLock file
                try {
                    val ciphertext = download(ref.url)
                    val dek = masterKey.unwrapDek(decode(ref.encDek))
                    val plaintext =
                        WholeObjectCipher.decrypt(dek, decode(ref.iv), Aad.of(ref.photoId, ObjectRef.Preview), ciphertext)
                    withContext(Dispatchers.IO) {
                        // Written to a temp name and renamed, so a concurrent reader (or a
                        // crash mid-write) never sees a half-written clip.
                        val tmp = File(dir, "${file.name}.tmp")
                        tmp.writeBytes(plaintext)
                        if (!tmp.renameTo(file)) {
                            tmp.delete()
                            throw IOException("could not move the decrypted preview into place")
                        }
                        evictIfNeeded()
                    }
                    file
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Best-effort by design (the still stays), but never silent: a preview that
                    // "just doesn't play" is otherwise undiagnosable. Logged with the photoId
                    // and the failure class only -- never the URL, key material or bytes.
                    Log.w("PreviewCache", "preview for ${ref.photoId} unavailable: ${e::class.simpleName}: ${e.message}")
                    failed += ref.url
                    null
                }
            }
        }

        /** Deletes every cached (decrypted) preview and forgets remembered failures. */
        suspend fun clear() {
            withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
            failed.clear()
        }

        private suspend fun download(url: String): ByteArray =
            withContext(Dispatchers.IO) {
                okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("GET $url failed: HTTP ${response.code}")
                    val body = response.body ?: throw IOException("empty response body for $url")
                    if (body.contentLength() > MAX_PREVIEW_DOWNLOAD_BYTES) throw IOException("preview at $url is too large")
                    body.bytes().also { if (it.size > MAX_PREVIEW_DOWNLOAD_BYTES) throw IOException("preview at $url is too large") }
                }
            }

        private fun evictIfNeeded() {
            val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") } ?: return
            val entries = files.map { CacheEntry(it.name, it.length(), it.lastModified()) }
            for (name in selectEvictions(entries, PREVIEW_CACHE_MAX_BYTES, System.currentTimeMillis(), PREVIEW_EVICTION_GRACE_MS)) {
                File(dir, name).delete()
            }
        }
    }

internal class CacheEntry(val name: String, val bytes: Long, val lastModifiedMs: Long)

/** Which entries to delete to bring the total under [maxBytes]: oldest first, never one
 * touched within [graceMs] of [nowMs] (it may be playing right now). Pure, so the policy
 * is JVM-testable without a 200 MB cache. Returns nothing when already under budget; may
 * leave the cache over budget if everything left is inside the grace window. */
internal fun selectEvictions(
    entries: List<CacheEntry>,
    maxBytes: Long,
    nowMs: Long,
    graceMs: Long,
): List<String> {
    var total = entries.sumOf { it.bytes }
    if (total <= maxBytes) return emptyList()
    val victims = mutableListOf<String>()
    for (e in entries.sortedBy { it.lastModifiedMs }) {
        if (total <= maxBytes) break
        if (nowMs - e.lastModifiedMs < graceMs) continue
        total -= e.bytes
        victims += e.name
    }
    return victims
}

private fun fileName(url: String): String =
    MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".mp4"

private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)
