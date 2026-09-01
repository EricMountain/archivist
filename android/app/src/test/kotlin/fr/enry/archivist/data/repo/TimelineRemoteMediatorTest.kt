package fr.enry.archivist.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.ThumbEntry
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.testutil.FakeCognitoAuthApi
import fr.enry.archivist.testutil.FakeSharedPreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
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

@OptIn(ExperimentalPagingApi::class)
class TimelineRemoteMediatorTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private lateinit var instanceStore: InstanceStore
    private lateinit var mediator: RemoteMediator<Int, PhotoEntity>

    private val json = Json { ignoreUnknownKeys = true }
    private val host = "photos.example.com"
    private val config = PagingConfig(pageSize = 60)

    /** Set by a test before calling [load] to control `GET /photos`'s response, and
     * read back afterwards to check what query params the mediator actually sent. */
    private var photosResponseBody = """{"items":[]}"""
    private var lastRequest: RecordedRequest? = null

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    lastRequest = request
                    return if (request.path.orEmpty().startsWith("/api/photos")) {
                        MockResponse().setResponseCode(200).setBody(photosResponseBody)
                    } else {
                        MockResponse().setResponseCode(404)
                    }
                }
            }
        server.start()
        tempDir = Files.createTempDirectory("timeline-remote-mediator-test").toFile()

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

        mediator = TimelineRemoteMediator(instanceStore, archivistApiFactory, db)
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

    private fun emptyState() = PagingState<Int, PhotoEntity>(pages = emptyList(), anchorPosition = null, config = config, leadingPlaceholderCount = 0)

    private fun photoJson(
        photoId: String,
        takenAt: String,
    ) = """{"photoId":"$photoId","takenAt":"$takenAt","thumbs":{"256":{"bucket":"derived","key":"th/o1/$photoId/256","iv":"aXY=","bytes":100}},""" +
        """"encDek":"ZGVr","encKeyId":"mk-1","width":10,"height":10,"mime":"image/jpeg","tzOffsetMin":0,"status":"ready"}"""

    @Test
    fun `refresh upserts the returned page and stores the cursor`() =
        runTest {
            connectInstance()
            photosResponseBody = """{"items":[${photoJson("p1", "2024-01-01T00:00:00.000Z")}],"cursor":"next-page"}"""

            val result = mediator.load(LoadType.REFRESH, emptyState())

            assertTrue(result is RemoteMediator.MediatorResult.Success)
            assertEquals(false, (result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            val stored = db.photoDao().getByPhotoId("p1")
            assertEquals(AssetStatus.READY, stored?.status)
            assertEquals(ThumbEntry("derived", "th/o1/p1/256", "aXY=", 100), stored?.thumbs?.get(256))
            assertEquals("next-page", db.timelineCursorDao().observe().first()?.cursor)
        }

    @Test
    fun `refresh with no cursor reaches end of pagination and clears any stored cursor`() =
        runTest {
            connectInstance()
            photosResponseBody = """{"items":[${photoJson("p1", "2024-01-01T00:00:00.000Z")}]}"""

            val result = mediator.load(LoadType.REFRESH, emptyState()) as RemoteMediator.MediatorResult.Success
            assertTrue(result.endOfPaginationReached)
            assertNull(db.timelineCursorDao().observe().first())
        }

    @Test
    fun `refresh clears whatever was cached before re-populating`() =
        runTest {
            connectInstance()
            db.photoDao().upsertAll(
                listOf(
                    PhotoEntity(
                        "stale", "2020-01-01T00:00:00.000Z", 0, "image/jpeg", 1, 1,
                        AssetStatus.READY, emptyMap(), "dek", "mk-1",
                    ),
                ),
            )
            photosResponseBody = """{"items":[${photoJson("p1", "2024-01-01T00:00:00.000Z")}]}"""

            mediator.load(LoadType.REFRESH, emptyState())

            assertNull(db.photoDao().getByPhotoId("stale"))
        }

    @Test
    fun `append sends the cursor a prior load stored`() =
        runTest {
            connectInstance()
            db.timelineCursorDao().set(fr.enry.archivist.data.local.db.TimelineCursorEntity(cursor = "resume-here", updatedAt = "now"))
            photosResponseBody = """{"items":[]}"""

            mediator.load(LoadType.APPEND, emptyState())

            assertEquals("resume-here", lastRequest?.requestUrl?.queryParameter("cursor"))
        }

    @Test
    fun `append with no stored cursor reaches end of pagination without a network call`() =
        runTest {
            connectInstance()

            val result = mediator.load(LoadType.APPEND, emptyState())

            assertTrue(result is RemoteMediator.MediatorResult.Success)
            assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertNull(lastRequest)
        }

    @Test
    fun `prepend always reports end of pagination`() =
        runTest {
            val result = mediator.load(LoadType.PREPEND, emptyState())
            assertTrue(result is RemoteMediator.MediatorResult.Success)
            assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        }

    @Test
    fun `a server error is reported as MediatorResult Error, not thrown`() =
        runTest {
            connectInstance()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
                }

            val result = mediator.load(LoadType.REFRESH, emptyState())

            assertTrue(result is RemoteMediator.MediatorResult.Error)
        }

    @Test
    fun `no connected instance is reported as MediatorResult Error, not thrown`() =
        runTest {
            val result = mediator.load(LoadType.REFRESH, emptyState())
            assertTrue(result is RemoteMediator.MediatorResult.Error)
        }
}
