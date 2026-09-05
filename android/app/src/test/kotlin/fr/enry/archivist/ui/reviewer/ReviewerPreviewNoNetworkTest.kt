package fr.enry.archivist.ui.reviewer

import fr.enry.archivist.sync.MediaStoreSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Plan step 2.17's actual guarantee, checked mechanically rather than left to code
 * review: [ReviewerPreviewViewModel] cannot reach the network because nothing in its
 * constructor is capable of one. If a future change adds a second constructor
 * parameter, this test forces a decision about whether that parameter belongs here at
 * all — the same way the guard is meant to work.
 */
class ReviewerPreviewNoNetworkTest {
    private val networkCapableTypeNames =
        setOf(
            "fr.enry.archivist.data.remote.ArchivistApi",
            "fr.enry.archivist.data.remote.ArchivistApiFactory",
            "fr.enry.archivist.data.remote.CognitoAuthApi",
            "fr.enry.archivist.data.remote.CognitoAuthClient",
            "fr.enry.archivist.data.remote.DiscoveryApi",
            "fr.enry.archivist.data.remote.DiscoveryClient",
            "fr.enry.archivist.data.repo.AccountRepository",
            "fr.enry.archivist.data.repo.AuthRepository",
            "fr.enry.archivist.data.repo.DeleteRepository",
            "fr.enry.archivist.data.repo.DeviceRepository",
            "fr.enry.archivist.data.repo.EnrolmentRepository",
            "fr.enry.archivist.data.repo.InstanceRepository",
            "fr.enry.archivist.data.repo.PhotoDetailRepository",
            "fr.enry.archivist.data.repo.PhotoRepository",
            "fr.enry.archivist.data.repo.StorageRepository",
            "fr.enry.archivist.data.repo.TimelineRemoteMediator",
            "fr.enry.archivist.data.repo.UploadRepository",
            "okhttp3.OkHttpClient",
            "retrofit2.Retrofit",
        )

    @Test
    fun `ReviewerPreviewViewModel's constructor is exactly MediaStoreSource, nothing network-capable`() {
        assertIsExactlyMediaStoreSource(ReviewerPreviewViewModel::class.java)
    }

    @Test
    fun `ReviewerSettingsViewModel's constructor is exactly MediaStoreSource, nothing network-capable`() {
        assertIsExactlyMediaStoreSource(ReviewerSettingsViewModel::class.java)
    }

    private fun assertIsExactlyMediaStoreSource(viewModelClass: Class<*>) {
        val constructor = viewModelClass.declaredConstructors.single()
        val paramTypeNames = constructor.parameterTypes.map { it.name }.toSet()

        assertEquals(setOf(MediaStoreSource::class.java.name), paramTypeNames)
        assertFalse(paramTypeNames.any { it in networkCapableTypeNames })
    }
}
