package fr.enry.archivist.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.RemoteMediator.InitializeAction
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.ThumbEntry
import fr.enry.archivist.data.local.db.TimelineKey
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
import org.junit.jupiter.api.Assertions.assertNotNull
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
    private lateinit var jumpCoordinator: TimelineJumpCoordinator
    private lateinit var mediator: TimelineRemoteMediator

    private val json = Json { ignoreUnknownKeys = true }
    private val host = "photos.example.com"
    private val config = PagingConfig(pageSize = 60)

    /** Set by a test before calling [load] to control `GET /photos`'s response, and
     * read back afterwards to check what query params the mediator actually sent. */
    private var photosResponseBody = """{"items":[]}"""
    private var lastRequest: RecordedRequest? = null

    /** A jump fetches both directions around its target, so assertions have to name the
     * request they mean rather than trusting whichever landed last. */
    private val requests = mutableListOf<RecordedRequest>()

    private fun requestWith(param: String) = requests.lastOrNull { it.requestUrl?.queryParameter(param) != null }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    lastRequest = request
                    requests += request
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

        jumpCoordinator = TimelineJumpCoordinator()
        mediator = TimelineRemoteMediator(instanceStore, archivistApiFactory, db, jumpCoordinator)
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

    private fun emptyState() = PagingState<TimelineKey, PhotoEntity>(pages = emptyList(), anchorPosition = null, config = config, leadingPlaceholderCount = 0)

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
    fun `reseedAt a target bounds the fetch with it and clears the old cache`() =
        runTest {
            connectInstance()
            db.photoDao().upsertAll(
                listOf(
                    PhotoEntity(
                        "stale", "2024-01-01T00:00:00.000Z", 0, "image/jpeg", 1, 1,
                        AssetStatus.READY, emptyMap(), "dek", "mk-1",
                    ),
                ),
            )
            photosResponseBody = """{"items":[${photoJson("p1", "2021-06-01T00:00:00.000Z")}]}"""

            mediator.reseedAt("2021-06-15T00:00:00.000Z")

            // The older half: everything up to the target, newest-first.
            assertEquals(1, requests.size, "a jump is one bounded fetch, nothing more")
            assertEquals("2021-06-15T00:00:00.000Z", lastRequest?.requestUrl?.queryParameter("to"))
            assertEquals(EPOCH_ISO, lastRequest?.requestUrl?.queryParameter("from"))

            assertNull(db.photoDao().getByPhotoId("stale"))
            assertEquals("2021-06-01T00:00:00.000Z", db.photoDao().getByPhotoId("p1")?.takenAt)
            assertEquals(TimelineKey("2021-06-01T00:00:00.000Z", "p1"), jumpCoordinator.consumeLanding())
        }

    /**
     * An empty timeline is a dead end — no row for `PREPEND` to work back from, no cursor
     * for `APPEND` to follow — so the grid sits on "No photos yet" until the process is
     * restarted. A jump that matches nothing therefore keeps the cache it already had
     * rather than committing an empty one over it. (A drag to the very bottom of the rail
     * used to produce exactly such a jump; [jumpTargetFor] no longer can, but a jump is a
     * whole-cache replacement and isn't worth leaving one bad bound away from it.)
     */
    @Test
    fun `a jump that matches nothing keeps the existing cache instead of emptying it`() =
        runTest {
            connectInstance()
            db.photoDao().upsertAll(
                listOf(
                    PhotoEntity(
                        "kept", "2024-01-01T00:00:00.000Z", 0, "image/jpeg", 1, 1,
                        AssetStatus.READY, emptyMap(), "dek", "mk-1",
                    ),
                ),
            )
            photosResponseBody = """{"items":[]}"""

            val result = mediator.reseedAt("1999-01-01T00:00:00.000Z")

            assertTrue(result is RemoteMediator.MediatorResult.Error)
            assertNotNull(db.photoDao().getByPhotoId("kept"))
        }

    /** A *plain* refresh is exempt from the guard above: there, empty genuinely means an
     * empty library, and refusing to commit it would leave deleted photos on screen. */
    @Test
    fun `a plain refresh that returns nothing does clear the cache`() =
        runTest {
            connectInstance()
            db.photoDao().upsertAll(
                listOf(
                    PhotoEntity(
                        "gone", "2024-01-01T00:00:00.000Z", 0, "image/jpeg", 1, 1,
                        AssetStatus.READY, emptyMap(), "dek", "mk-1",
                    ),
                ),
            )
            photosResponseBody = """{"items":[]}"""

            mediator.reseedAt(null)

            assertNull(db.photoDao().getByPhotoId("gone"))
        }

    /** The paging source starts the next generation at this key rather than re-anchoring
     * on wherever the user was scrolled before the jump — see its own `getRefreshKey`. */
    @Test
    fun `reseedAt stages its landing key before its write, for either kind of reseed`() =
        runTest {
            connectInstance()
            photosResponseBody = """{"items":[${photoJson("p1", "2021-06-01T00:00:00.000Z")}]}"""

            mediator.reseedAt("2021-06-15T00:00:00.000Z")
            assertEquals(TimelineKey("2021-06-01T00:00:00.000Z", "p1"), jumpCoordinator.consumeLanding())

            mediator.reseedAt(null)
            assertEquals(TimelineKey("2021-06-01T00:00:00.000Z", "p1"), jumpCoordinator.consumeLanding())
        }

    /** "Back to the present" — the top of the fast-scroll rail, and the way out of a
     * jumped-to position — is a plain unbounded fetch, not a window bounded at `newest`. */
    @Test
    fun `reseedAt null sends no range bound at all`() =
        runTest {
            connectInstance()
            photosResponseBody = """{"items":[]}"""

            mediator.reseedAt(null)

            assertEquals(1, requests.size, "back-to-the-present needs no context fetch")
            assertNull(lastRequest?.requestUrl?.queryParameter("to"))
            assertNull(lastRequest?.requestUrl?.queryParameter("from"))
        }

    /** What makes a jumped-to position escapable: an ascending range starting at the
     * newest cached photo (design.md pattern 3b). Without it the timeline can only travel
     * further into the past from wherever a jump left it. */
    @Test
    fun `prepend fetches the ascending page starting at the newest cached photo`() =
        runTest {
            connectInstance()
            db.photoDao().upsertAll(
                listOf(
                    PhotoEntity(
                        "anchor", "2021-06-01T00:00:00.000Z", 0, "image/jpeg", 1, 1,
                        AssetStatus.READY, emptyMap(), "dek", "mk-1",
                    ),
                ),
            )
            photosResponseBody = """{"items":[${photoJson("anchor", "2021-06-01T00:00:00.000Z")},${photoJson("newer", "2021-06-02T00:00:00.000Z")}]}"""

            val result = mediator.load(LoadType.PREPEND, emptyState())

            assertEquals("2021-06-01T00:00:00.000Z", lastRequest?.requestUrl?.queryParameter("from"))
            assertEquals(FAR_FUTURE_ISO, lastRequest?.requestUrl?.queryParameter("to"))
            assertEquals("asc", lastRequest?.requestUrl?.queryParameter("order"))
            assertEquals(false, (result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertNotNull(db.photoDao().getByPhotoId("newer"))
            // The older-direction cursor belongs to APPEND and must survive untouched.
            assertNull(db.timelineCursorDao().observe().first())
        }

    /** `from` is inclusive, so the boundary photo always comes back. A page containing
     * nothing *but* it is what "already at the present" looks like — and not recognising
     * that would leave PREPEND asking for the same page forever. */
    @Test
    fun `prepend that returns only the boundary photo reports end of pagination`() =
        runTest {
            connectInstance()
            db.photoDao().upsertAll(
                listOf(
                    PhotoEntity(
                        "anchor", "2021-06-01T00:00:00.000Z", 0, "image/jpeg", 1, 1,
                        AssetStatus.READY, emptyMap(), "dek", "mk-1",
                    ),
                ),
            )
            photosResponseBody = """{"items":[${photoJson("anchor", "2021-06-01T00:00:00.000Z")}]}"""

            val result = mediator.load(LoadType.PREPEND, emptyState())

            assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
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
    fun `prepend with an empty cache reports end of pagination without a network call`() =
        runTest {
            connectInstance()

            val result = mediator.load(LoadType.PREPEND, emptyState())

            assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertNull(lastRequest)
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

    @Test
    fun `initialize launches a refresh when the cache is empty`() =
        runTest {
            assertEquals(InitializeAction.LAUNCH_INITIAL_REFRESH, mediator.initialize())
        }

    @Test
    fun `initialize skips a refresh once photos are cached, avoiding a self-triggered clear`() =
        runTest {
            db.photoDao().upsertAll(
                listOf(
                    PhotoEntity(
                        "p1", "2024-01-01T00:00:00.000Z", 0, "image/jpeg", 1, 1,
                        AssetStatus.READY, emptyMap(), "dek", "mk-1",
                    ),
                ),
            )

            assertEquals(InitializeAction.SKIP_INITIAL_REFRESH, mediator.initialize())
        }
}
