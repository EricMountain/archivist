package fr.enry.archivist

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fr.enry.archivist.data.local.EnrolmentStore
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.FolderSelectionDao
import fr.enry.archivist.data.local.db.LocalTombstoneDao
import fr.enry.archivist.data.local.db.PhotoDao
import fr.enry.archivist.data.local.db.UploadQueueDao
import fr.enry.archivist.data.repo.EnrolmentRepository
import fr.enry.archivist.data.repo.HashSecretHolder
import fr.enry.archivist.data.repo.MasterKeyHolder
import fr.enry.archivist.sync.Scanner

/**
 * Reaches the real app's Hilt-provided singletons from instrumented test code, without
 * `hilt-android-testing`'s heavier machinery (`HiltTestApplication`, `@UninstallModules`,
 * a custom test runner) — nothing plan step 2.10's instrumented tests need *replaces* a
 * production binding, they only seed a few (a connected instance, an unlocked master
 * key) and read state back afterward, so the same `EntryPointAccessors` pattern
 * [ArchivistApplication]'s own `onTrimMemory` already uses is enough.
 *
 * **Lives in `main`, not `androidTest`, even though it's only ever used from
 * `androidTest`.** Hilt aggregates every `@InstallIn(SingletonComponent::class)`
 * declaration at the compilation that generates the *real* `SingletonComponent`
 * implementation — that's `:app`'s main variant, not the separate `androidTest` APK's
 * own (different) Hilt component. An `@EntryPoint` declared in `androidTest` compiles
 * fine but throws `ClassCastException` at runtime: confirmed by trying it first, not
 * assumed. Costs nothing in the shipped app (an unused interface with no runtime
 * footprint of its own), and ships no behavior — only getters onto what's already
 * provided elsewhere.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TestEntryPoint {
    fun instanceStore(): InstanceStore

    fun enrolmentStore(): EnrolmentStore

    fun masterKeyHolder(): MasterKeyHolder

    fun hashSecretHolder(): HashSecretHolder

    fun uploadQueueDao(): UploadQueueDao

    fun localTombstoneDao(): LocalTombstoneDao

    /** Added for the ad hoc load-test harness (`LoadTestInstrumentedTest`) that
     * populates a real `dev` account with many photos to exercise plan step 2.11's
     * "1,000 photos scroll smoothly" — reads real app state, same as everything else
     * here, nothing replaced. */
    fun folderSelectionDao(): FolderSelectionDao

    fun photoDao(): PhotoDao

    fun scanner(): Scanner

    fun enrolmentRepository(): EnrolmentRepository

    companion object {
        fun from(context: android.content.Context): TestEntryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, TestEntryPoint::class.java)
    }
}
