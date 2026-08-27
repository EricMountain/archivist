package fr.enry.archivist

import android.app.Application
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import fr.enry.archivist.data.repo.MasterKeyHolder

@HiltAndroidApp
class ArchivistApplication : Application() {
    /** `Application` isn't itself a Hilt field-injection target — this is the
     * standard way to reach a `SingletonComponent` binding from here. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MasterKeyHolderEntryPoint {
        fun masterKeyHolder(): MasterKeyHolder
    }

    /** Per [MasterKey][fr.enry.archivist.crypto.MasterKey]'s own contract: "call clear
     * from onTrimMemory and whenever the app locks." No level threshold — any trim
     * signal clears it; re-unlocking is just another (cheap) Keystore biometric
     * prompt. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        EntryPointAccessors.fromApplication(this, MasterKeyHolderEntryPoint::class.java).masterKeyHolder().clear()
    }
}
