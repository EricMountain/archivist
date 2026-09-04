package fr.enry.archivist.data.repo

import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plan step 2.14's Settings > Storage section — "thumbnail cache size and a clear-cache
 * action". There's exactly one disk cache in this app: the decrypted-plaintext
 * thumbnail cache [fr.enry.archivist.ArchivistApplication.newImageLoader] roots under
 * `noBackupFilesDir` (plan step 2.11) — this class reads/clears that same
 * `SingletonImageLoader` instance rather than owning a second one.
 */
@Singleton
class StorageRepository
    @Inject
    constructor(
        @ApplicationContext private val context: PlatformContext,
    ) {
        /** Bytes currently on disk. `DiskCache.size`/`clear()` are plain synchronous
         * file-system reads, not network — a `Dispatchers.IO` hop is enough, no
         * `Result`/exception handling needed the way a network call would. */
        suspend fun cacheSizeBytes(): Long =
            withContext(Dispatchers.IO) {
                SingletonImageLoader.get(context).diskCache?.size ?: 0L
            }

        suspend fun clearCache() {
            withContext(Dispatchers.IO) {
                SingletonImageLoader.get(context).diskCache?.clear()
            }
        }
    }
