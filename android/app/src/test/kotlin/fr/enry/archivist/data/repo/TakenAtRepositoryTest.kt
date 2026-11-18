package fr.enry.archivist.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.TimelineKey
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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** [TakenAtRepository] over MockWebServer and the JVM Room driver: the corrected photo in
 * `photos`, and (place filter step 7, F13) in the filter's cached rows. */
class TakenAtRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private lateinit var instanceStore: InstanceStore
    private lateinit var jumpCoordinator: TimelineJumpCoordinator
    private lateinit var placeFilter: PlaceFilterRepository
    private lateinit var repository: TakenAtRepository

    private val json = Json { ignoreUnknownKeys = true }
    private val photoId = "01K5A2Q8ZCV1000000000000"

    /** The corrected date: 23:30 UTC at +09:00 is the next local day. */
    private val corrected = "2026-08-30T23:30:00.000Z"
    private val patchBodies = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        request.method == "PATCH" && path == "/api/photos/$photoId" -> {
                            patchBodies += request.body.readUtf8()
                            MockResponse().setResponseCode(200).setBody("{}")
                        }
                        request.method == "GET" && path == "/api/photos/$photoId" ->
                            MockResponse().setResponseCode(200).setBody(
                                """{"meta":{"photoId":"$photoId","takenAt":"$corrected","thumbs":{},"encDek":"dek",""" +
                                    """"encKeyId":"mk-1","width":100,"height":100,"mime":"image/jpeg","tzOffsetMin":540,"status":"ready"}}""",
                            )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        server.start()
        tempDir = Files.createTempDirectory("taken-at-repository-test").toFile()
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { File(tempDir, "instances.preferences_pb") })
        instanceStore = InstanceStore(dataStore, json)
        db = buildTestDatabase()
        jumpCoordinator = TimelineJumpCoordinator()
        placeFilter = placeFilterFor(db)
        val factory =
            ArchivistApiFactory(
                baseOkHttpClient = OkHttpClient.Builder().build(),
                json = json,
                tokenStore = TokenStore(FakeSharedPreferences(), json),
                cognitoAuthClient = CognitoAuthClient(FakeCognitoAuthApi(), json),
            )
        repository = TakenAtRepository(instanceStore, factory, db.photoDao(), jumpCoordinator, placeFilter)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
        db.close()
    }

    private suspend fun connect() {
        instanceStore.save(
            "photos.example.com",
            DiscoveryDocument(
                apiBase = server.url("/api").toString().trimEnd('/'),
                region = "eu-west-1",
                cognito = DiscoveryDocument.CognitoConfig(userPoolId = "eu-west-1_XXXXXXXXX", clientId = "client-id"),
                cryptoVersion = 1,
                instanceName = "Home photos",
            ),
        )
    }

    private suspend fun seedTimelineRow() {
        db.photoDao().upsertAll(
            listOf(
                PhotoEntity(
                    photoId = photoId,
                    takenAt = "2026-08-30T10:00:00.000Z",
                    tzOffsetMin = 540,
                    mime = "image/jpeg",
                    width = 100,
                    height = 100,
                    status = AssetStatus.READY,
                    thumbs = emptyMap(),
                    encDek = "dek",
                    encKeyId = "mk-1",
                    placeCc = "JP",
                    placeName = "Kyoto",
                    placeKey = "1857910",
                ),
            ),
        )
    }

    @Test
    fun `from the timeline - photos takes the corrected date and the refresh key is staged`() =
        runTest {
            connect()
            seedTimelineRow()

            assertEquals(TakenAtOutcome.Done, repository.correct(photoId, corrected, 540))

            assertTrue(patchBodies.single().contains(corrected))
            assertEquals(corrected, db.photoDao().getByPhotoId(photoId)!!.takenAt)
            assertEquals(TimelineKey(corrected, photoId), jumpCoordinator.consumeExternalRefreshKey())
        }

    /** F13: the filter's cached rows take the new date and offset, with `localDay`
     * recomputed in the photo's own offset (the rail's histogram and local jumps read it),
     * and keep the stream's own place (F12). */
    @Test
    fun `the place filter's cached rows take the corrected date, localDay recomputed`() =
        runTest {
            connect()
            seedTimelineRow()
            placeFilter.startFilterSessionForTest()
            db.seedFilterRow("REGION#JP.22", photoId, tzOffsetMin = 540, placeCc = "JP", placeName = "Kyoto (region)", placeKey = "JP.22", specificity = 1)

            assertEquals(TakenAtOutcome.Done, repository.correct(photoId, corrected, 540))

            val row = db.facetStreamDao().rowsOfPhoto(photoId).single()
            assertEquals(corrected, row.takenAt)
            assertEquals(540, row.tzOffsetMin)
            assertEquals("2026-08-31", row.localDay)
            assertEquals("Kyoto (region)", row.placeName)
            // In a filter session the hidden timeline keeps its own anchor (F10).
            assertEquals(corrected, db.photoDao().getByPhotoId(photoId)!!.takenAt)
            assertNull(jumpCoordinator.consumeExternalRefreshKey())
        }

    @Test
    fun `a photo only in the filter's cache is not inserted into photos`() =
        runTest {
            connect()
            placeFilter.startFilterSessionForTest()
            db.seedFilterRow("PLACE#1857910", photoId, tzOffsetMin = 540)

            assertEquals(TakenAtOutcome.Done, repository.correct(photoId, corrected, 540))

            assertNull(db.photoDao().getByPhotoId(photoId))
            assertEquals(corrected, db.facetStreamDao().rowsOfPhoto(photoId).single().takenAt)
        }
}
