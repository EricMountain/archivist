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
import kotlinx.coroutines.flow.first
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
    private lateinit var jumpCoordinator: TimelineJumpCoordinator
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

        jumpCoordinator = TimelineJumpCoordinator()
        repository = PhotoRepository(db, instanceStore, archivistApiFactory, jumpCoordinator)
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

    @Test
    fun `jumpTo replaces the cache with a window bounded at the target`() =
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
            // One bounded fetch, newest-first, ending at the target -- not two. Fetching
            // the *newer* side as well is what ran away into an ANR; see
            // TimelineRemoteMediator.loadNewerThanCache's own doc.
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[${photoJson("jumped", "2021-06-01T00:00:00.000Z")}]}"""))

            val outcome = repository.jumpTo(java.time.LocalDate.parse("2021-06-15"))

            assertEquals(TimelineKey("2021-06-01T00:00:00.000Z", "jumped"), outcome?.landing)
            assertEquals(1, server.requestCount, "a jump is one bounded fetch, nothing more")
            assertEquals("2021-06-16T11:59:59.999Z", server.takeRequest().requestUrl?.queryParameter("to"))
            assertNotNull(db.photoDao().getByPhotoId("jumped"))
            assertEquals(null, db.photoDao().getByPhotoId("stale"))
        }

    @Test
    fun `jumpTo reports failure rather than throwing when the fetch fails`() =
        runTest {
            connectInstance()
            server.enqueue(MockResponse().setResponseCode(500))

            assertEquals(null, repository.jumpTo(java.time.LocalDate.parse("2021-06-15")))
        }

    /** The point of tracking the cached window: a day the cache already covers is
     * answered from Room, so scrubbing back and forth over ground already loaded costs
     * nothing at all. */
    @Test
    fun `a jump to a day the cache already covers makes no request`() =
        runTest {
            connectInstance()
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[${photoJson("jumped", "2021-06-01T00:00:00.000Z")}]}"""))
            repository.jumpTo(java.time.LocalDate.parse("2021-06-15"))
            assertEquals(1, server.requestCount)

            // Same day again, and a nearby one inside the same window.
            val again = repository.jumpTo(java.time.LocalDate.parse("2021-06-15"))
            val nearby = repository.jumpTo(java.time.LocalDate.parse("2021-06-10"))

            assertEquals(TimelineKey("2021-06-01T00:00:00.000Z", "jumped"), again?.landing)
            assertEquals(TimelineKey("2021-06-01T00:00:00.000Z", "jumped"), nearby?.landing)
            assertEquals(1, server.requestCount, "neither should have reached the server")
        }

    /** ...and the limit of that: a day past the edge of what the window is known complete
     * through can't be answered locally, because the photos that would decide it were
     * never fetched. */
    @Test
    fun `a jump past the cached window's upper edge does reach the server`() =
        runTest {
            connectInstance()
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[${photoJson("jumped", "2021-06-01T00:00:00.000Z")}]}"""))
            repository.jumpTo(java.time.LocalDate.parse("2021-06-15"))

            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[${photoJson("later", "2021-09-01T00:00:00.000Z")}]}"""))
            repository.jumpTo(java.time.LocalDate.parse("2021-09-15"))

            assertEquals(2, server.requestCount)
        }

    /** A scrub mid-drag is superseded within a fraction of a second, so it asks for a
     * screenful rather than a full window — DynamoDB bills by items read. */
    @Test
    fun `a scrub asks for a smaller page than a committed jump`() =
        runTest {
            connectInstance()
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[${photoJson("a", "2021-06-01T00:00:00.000Z")}]}"""))
            repository.jumpTo(java.time.LocalDate.parse("2021-06-15"), scrub = true)
            assertEquals("40", server.takeRequest().requestUrl?.queryParameter("limit"))

            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[${photoJson("b", "2020-06-01T00:00:00.000Z")}]}"""))
            repository.jumpTo(java.time.LocalDate.parse("2020-06-15"), scrub = false)
            assertEquals("120", server.takeRequest().requestUrl?.queryParameter("limit"))
        }

    /**
     * The bug this invariant exists to prevent. `refreshLatest` runs on every finished
     * upload; dropping the newest photos into a cache anchored months in the past leaves
     * a hole, and keyset paging scrolls straight across a hole — silently skipping
     * everything between.
     */
    @Test
    fun `refreshLatest leaves a past window alone rather than punching a hole in it`() =
        runTest {
            connectInstance()
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[${photoJson("old", "2021-06-01T00:00:00.000Z")}]}"""))
            repository.jumpTo(java.time.LocalDate.parse("2021-06-15"))
            val afterJump = server.requestCount

            repository.refreshLatest()

            assertEquals(afterJump, server.requestCount, "a past window must not be topped up")
            assertEquals(1, db.photoDao().pageFromStart(10).size)
        }

    /** At the present it does fold them in — but only what is actually newer than the
     * cache, oldest-first from its edge, which for one finished upload is one item rather
     * than the sixty a "newest page" re-read. */
    @Test
    fun `refreshLatest at the present fetches only what is newer than the cache`() =
        runTest {
            connectInstance()
            db.photoDao().upsertAll(
                listOf(
                    PhotoEntity(
                        "cached", "2024-01-01T00:00:00.000Z", 0, "image/jpeg", 1, 1,
                        AssetStatus.READY, emptyMap(), "dek", "mk-1",
                    ),
                ),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[${photoJson("fresh", "2024-01-02T00:00:00.000Z")}]}"""))

            repository.refreshLatest()

            val request = server.takeRequest().requestUrl!!
            assertEquals("2024-01-01T00:00:00.000Z", request.queryParameter("from"))
            assertEquals("asc", request.queryParameter("order"))
            assertNotNull(db.photoDao().getByPhotoId("fresh"))
            assertNotNull(db.photoDao().getByPhotoId("cached"))
        }

    /** The histogram is cached with its ETag and revalidated conditionally; a 304 keeps
     * what is already stored instead of clearing it. */
    @Test
    fun `syncHistogram caches the body, then replays its etag and honours a 304`() =
        runTest {
            connectInstance()
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("etag", "\"tl-9\"")
                    .setBody("""{"days":{"2026-07-14":3},"total":3,"version":9}"""),
            )

            repository.syncHistogram()
            assertEquals(mapOf("2026-07-14" to 3), repository.histogram().first()?.days)
            assertEquals(null, server.takeRequest().getHeader("If-None-Match"))

            server.enqueue(MockResponse().setResponseCode(304))
            repository.syncHistogram()

            assertEquals("\"tl-9\"", server.takeRequest().getHeader("If-None-Match"))
            assertEquals(mapOf("2026-07-14" to 3), repository.histogram().first()?.days)
        }

    @Test
    fun `fetchTimelineBounds parses the oldest and newest instants`() =
        runTest {
            connectInstance()
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"oldest":"2011-03-02T19:44:10.000Z","newest":"2026-08-02T16:05:33.000Z"}"""),
            )

            val bounds = repository.fetchTimelineBounds()

            assertEquals(java.time.Instant.parse("2011-03-02T19:44:10.000Z"), bounds?.oldest)
            assertEquals(java.time.Instant.parse("2026-08-02T16:05:33.000Z"), bounds?.newest)
        }

    @Test
    fun `fetchTimelineBounds returns null for an owner with no photos`() =
        runTest {
            connectInstance()
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

            assertEquals(null, repository.fetchTimelineBounds())
        }

    @Test
    fun `fetchTimelineBounds with no connected instance returns null, not a crash`() =
        runTest {
            assertEquals(null, repository.fetchTimelineBounds())
        }
}
