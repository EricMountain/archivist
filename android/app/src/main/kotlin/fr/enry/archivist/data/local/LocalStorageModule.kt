package fr.enry.archivist.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Module
import fr.enry.archivist.crypto.DeviceKeyProvider
import fr.enry.archivist.crypto.DeviceKeystore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.FolderSelectionDao
import fr.enry.archivist.data.local.db.LocalTombstoneDao
import fr.enry.archivist.data.local.db.PhotoDao
import fr.enry.archivist.data.local.db.RenditionDao
import fr.enry.archivist.data.local.db.TimelineCursorDao
import fr.enry.archivist.data.local.db.UploadQueueDao
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocalStorageModule {
    @Provides
    @Singleton
    fun provideInstanceDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("instances") },
        )

    /** Backs [TokenStore] — encrypted at rest via a hardware-backed (where available)
     * Keystore key, so tokens survive an `adb backup`/device-transfer only as
     * ciphertext tied to this device's Keystore.
     *
     * `EncryptedSharedPreferences`/`MasterKey` were deprecated in
     * `androidx.security:security-crypto` 1.1.0 with no replacement shipped in a
     * stable release since — the deprecation notice points at rolling your own Tink
     * `AndroidKeysetManager` wiring instead. Not worth that risk for one preferences
     * file; suppressed rather than silently living with the warning. */
    @Suppress("DEPRECATION")
    @Provides
    @Singleton
    fun provideTokenPreferences(
        @ApplicationContext context: Context,
    ): SharedPreferences {
        val masterKey =
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        return EncryptedSharedPreferences.create(
            context,
            "auth_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** `:core:crypto` has no `javax.inject`/Hilt dependency of its own (deliberately —
     * it's a plain crypto module, not an Android-app-framework one), so
     * [DeviceKeystore]'s constructor can't carry `@Inject` itself; provided here
     * instead, bound to the [DeviceKeyProvider] interface so tests can substitute a
     * fake — see that interface's doc for why. */
    @Provides
    @Singleton
    fun provideDeviceKeyProvider(): DeviceKeyProvider = DeviceKeystore()

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "archivist.db")
            .build()

    @Provides
    fun providePhotoDao(database: AppDatabase): PhotoDao = database.photoDao()

    @Provides
    fun provideRenditionDao(database: AppDatabase): RenditionDao = database.renditionDao()

    @Provides
    fun provideUploadQueueDao(database: AppDatabase): UploadQueueDao = database.uploadQueueDao()

    @Provides
    fun provideLocalTombstoneDao(database: AppDatabase): LocalTombstoneDao = database.localTombstoneDao()

    @Provides
    fun provideFolderSelectionDao(database: AppDatabase): FolderSelectionDao = database.folderSelectionDao()

    @Provides
    fun provideTimelineCursorDao(database: AppDatabase): TimelineCursorDao = database.timelineCursorDao()
}
