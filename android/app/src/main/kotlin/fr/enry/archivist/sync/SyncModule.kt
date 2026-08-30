package fr.enry.archivist.sync

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds
    @Singleton
    abstract fun bindMediaStoreSource(impl: AndroidMediaStoreSource): MediaStoreSource

    @Binds
    @Singleton
    abstract fun bindThumbnailer(impl: AndroidThumbnailer): Thumbnailer

    @Binds
    @Singleton
    abstract fun bindUploadScheduler(impl: WorkManagerUploadScheduler): UploadScheduler
}
