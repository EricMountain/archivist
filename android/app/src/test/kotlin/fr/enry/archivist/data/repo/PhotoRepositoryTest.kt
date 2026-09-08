package fr.enry.archivist.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.testutil.FakeCognitoAuthApi
import fr.enry.archivist.testutil.FakeSharedPreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [PhotoRepository.refreshLatest]'s own doc explains why it exists: unlike
 * [TimelineRemoteMediator]'s `REFRESH` (covered by [TimelineRemoteMediatorTest]), it must
 * *not* clear the table first -- that's what made the timeline grid jump back to the top
 * on every finished upload (STATUS.md, plan step 2.11, 2026-09-08 entry).
 */
class PhotoRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private lateinit var instanceStore: InstanceStore
    private lateinit var repository: PhotoRepository

    private val json = Json { ignoreUnknownKeys = true }
    private val host = "photos.example.com"

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("photo-repository-test").toFile()

        val dataStore = PreferenceDataStoreFactory.create(produceFile = { File(tempDir, "instances.preferences_pb") })
        instanceStore = InstanceStore(dataStore, json)
        db = buildTestDatabase()

        val archivistApiFactory =
            ArchivistApiFactory(
                baseOkHttpClient = OkHttpClient.Builder().build(),
                json = json,
                tokenStore = TokenStore(FakeSharedPreferences(), json),
                cognitoAuthClient = CognitoAuthClient(FakeCognitoAuthApi(), json),
            )

        repository = PhotoRepository(db, instanceStore, archivistApiFactory)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
        db.close()
    }

    private suspend fun connectInstance() {
        instanceStore.save(
            host,
            DiscoveryDocument(
                apiBase = server.url("/api").toString().trimEnd('/'),
                region = "eu-west-1",
                cognito = DiscoveryDocument.CognitoConfig(userPoolId = "eu-west-1_XXXXXXXXX", clientId = "client-id"),
                cryptoVersion = 1,
                instanceName = "Home photos",
            ),
        )
    }

    private fun photoJson(
        photoId: String,
        takenAt: String,
    ) = """{"photoId":"$photoId","takenAt":"$takenAt","thumbs":{"256":{"bucket":"derived","key":"th/o1/$photoId/256","iv":"aXY=","bytes":100}},""" +
        """"encDek":"ZGVr","encKeyId":"mk-1","width":10,"height":10,"mime":"image/jpeg","tzOffsetMin":0,"status":"ready"}"""

    @Test
    fun `refreshLatest upserts the newest page without clearing older cached rows`() =
        runTest {
            connectInstance()
            db.photoDao().upsertAll(
                listOf(
                    PhotoEntity(
                        "old", "2020-01-01T00:00:00.000Z", 0, "image/jpeg", 1, 1,
                        AssetStatus.READY, emptyMap(), "dek", "mk-1",
                    ),
                ),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[${photoJson("new", "2024-01-01T00:00:00.000Z")}]}"""))

            repository.refreshLatest()

            assertNotNull(db.photoDao().getByPhotoId("old"))
            assertNotNull(db.photoDao().getByPhotoId("new"))
        }

    @Test
    fun `refreshLatest updates a row that already exists`() =
        runTest {
            connectInstance()
            db.photoDao().upsertAll(
                listOf(
                    PhotoEntity(
                        "p1", "2024-01-01T00:00:00.000Z", 0, "image/jpeg", 1, 1,
                        AssetStatus.PROCESSING, emptyMap(), "dek", "mk-1",
                    ),
                ),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[${photoJson("p1", "2024-01-01T00:00:00.000Z")}]}"""))

            repository.refreshLatest()

            assertEquals(AssetStatus.READY, db.photoDao().getByPhotoId("p1")?.status)
        }

    @Test
    fun `refreshLatest with no connected instance is a no-op, not a crash`() =
        runTest {
            repository.refreshLatest()
        }
}
