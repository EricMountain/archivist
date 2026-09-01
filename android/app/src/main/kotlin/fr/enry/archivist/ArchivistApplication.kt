package fr.enry.archivist

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import fr.enry.archivist.crypto.EncryptedImageFetcher
import fr.enry.archivist.data.repo.HashSecretHolder
import fr.enry.archivist.data.repo.MasterKeyHolder
import fr.enry.archivist.data.local.db.UploadQueueDao
import fr.enry.archivist.sync.UploadScheduler
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@HiltAndroidApp
class ArchivistApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    /** Plain `@Inject` fields on a `@HiltAndroidApp` class work fine (Hilt injects them
     * during `attachBaseContext`, before [onCreate]/[workManagerConfiguration] can run)
     * — [workerFactory] below uses that directly. [EntryPointAccessors] is only needed
     * where a *plain, non-Hilt* caller (a `CoroutineWorker` isn't one, but the pattern
     * predates this file's WorkManager wiring) needs a `SingletonComponent` binding
     * without its own injection point — [newImageLoader] is another one: Coil calls it
     * before Hilt's field injection into this Application necessarily happens. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MasterKeyHolderEntryPoint {
        fun masterKeyHolder(): MasterKeyHolder

        fun hashSecretHolder(): HashSecretHolder

        fun uploadQueueDao(): UploadQueueDao

        fun uploadScheduler(): UploadScheduler

        fun baseOkHttpClient(): OkHttpClient
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Re-enqueue anything a process death dropped before WorkManager itself could
        // persist the enqueue — see UploadQueueDao.getActiveIds's doc. Cheap and
        // idempotent either way: enqueueUniqueWork/KEEP is a no-op for anything
        // WorkManager already knows about.
        CoroutineScope(Dispatchers.Default).launch {
            val holders = EntryPointAccessors.fromApplication(this@ArchivistApplication, MasterKeyHolderEntryPoint::class.java)
            holders.uploadScheduler().enqueueAll(holders.uploadQueueDao().getActiveIds())
        }
    }

    /** Per [MasterKey][fr.enry.archivist.crypto.MasterKey]'s own contract: "call clear
     * from onTrimMemory and whenever the app locks." No level threshold — any trim
     * signal clears it; re-unlocking is just another (cheap) Keystore biometric
     * prompt. [HashSecretHolder] gets the same treatment for the same reason — see its
     * own doc. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val holders = EntryPointAccessors.fromApplication(this, MasterKeyHolderEntryPoint::class.java)
        holders.masterKeyHolder().clear()
        holders.hashSecretHolder().clear()
    }

    /** Plan step 2.11: registers [EncryptedImageFetcher] so `AsyncImage(model =
     * EncryptedThumbRef(...))` resolves through it, and roots the disk cache in
     * [getNoBackupFilesDir] — it holds **decrypted plaintext** thumbnails, which must
     * never land in a Google cloud backup (see "Decrypting for display" in android.md).
     * Called by Coil itself the first time the singleton `ImageLoader` is needed, which
     * can be before Hilt's own field injection into this Application completes — same
     * reasoning as [MasterKeyHolderEntryPoint] above, [EntryPointAccessors] rather than
     * an `@Inject` field. */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val holders = EntryPointAccessors.fromApplication(this, MasterKeyHolderEntryPoint::class.java)
        return ImageLoader.Builder(context)
            .components { add(EncryptedImageFetcher.Factory(holders.baseOkHttpClient(), holders.masterKeyHolder())) }
            .diskCache {
                DiskCache.Builder()
                    .directory(noBackupFilesDir.resolve("thumbnail_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .build()
    }
}
