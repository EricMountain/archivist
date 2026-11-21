package fr.enry.archivist.data.repo

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.crypto.MasterKey
import fr.enry.archivist.crypto.WholeObjectCipher
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.data.remote.TransactionConflictRetry
import fr.enry.archivist.domain.ExifBlob
import fr.enry.archivist.domain.FileGazetteerSource
import fr.enry.archivist.domain.ReverseGeocoder
import fr.enry.archivist.testutil.FakeCognitoAuthApi
import fr.enry.archivist.testutil.FakeMediaStoreSource
import fr.enry.archivist.testutil.FakeSharedPreferences
import fr.enry.archivist.testutil.FakeVideoLocationReader
import fr.enry.archivist.testutil.SyntheticMp4
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Geolocation plan step 9: [LocationUpdateRepository] against a real Retrofit stack
 * (MockWebServer for `GET /settings`, `GET /photos/{id}` and `PUT …/location`), the real
 * gazetteer, a real Room database, and a fake [OriginalDownloader] that writes a fixture's
 * plaintext (the real one fetches from an `https://` media host MockWebServer can't serve;
 * its decrypt half is [DecryptToFileTest]'s).
 */
class LocationUpdateRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var cacheDir: File
    private lateinit var db: AppDatabase
    private lateinit var instanceStore: InstanceStore
    private lateinit var masterKeyHolder: MasterKeyHolder
    private lateinit var photoDetailRepository: PhotoDetailRepository
    private lateinit var repository: LocationUpdateRepository

    private val json = Json { ignoreUnknownKeys = true }
    private val photoId = "01K5A2Q8ZCV1000000000000"
    private val masterKey = MasterKey.of(ByteArray(32) { it.toByte() })
    private val dek = ByteArray(32) { (it + 1).toByte() }

    // Server state.
    private var stripOn = false
    private var exifBlob: ExifBlob? = null
    private var renditionMime = "image/jpeg"
    private var renditionExt = "jpg"
    private val putCodes = ArrayDeque<Int>()
    private val putBodies = mutableListOf<JsonObject>()
    private var detailGets = 0

    // The original the fake downloader "fetches".
    private var originalBytes = ByteArray(0)
    private val downloads = mutableListOf<RenditionSummary>()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    return when {
                        path == "/api/settings" ->
                            MockResponse().setBody("""{"homeTz":"Europe/Paris","stripLocationOnUpload":$stripOn}""")
                        request.method == "GET" && path == "/api/photos/$photoId" -> {
                            detailGets++
                            MockResponse().setBody(detailJson())
                        }
                        request.method == "PUT" && path == "/api/photos/$photoId/location" -> {
                            val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                            putBodies += body
                            val code = putCodes.removeFirstOrNull() ?: 200
                            if (code == TX_CONFLICT) {
                                return MockResponse().setResponseCode(409)
                                    .setBody("""{"error":"busy","code":"TRANSACTION_CONFLICT"}""")
                            }
                            if (code != 200) return MockResponse().setResponseCode(code).setBody("""{"error":"conflict"}""")
                            // Behave like the server: store what was sent.
                            body["exifEnc"]?.let { enc ->
                                exifBlob = decryptBlob(enc.jsonPrimitive.content, body.getValue("exifIv").jsonPrimitive.content)
                            }
                            storedPlace = body.getValue("place")
                            MockResponse().setBody("""{"place":${body.getValue("place")}}""")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        server.start()
        tempDir = Files.createTempDirectory("location-update-test").toFile()
        cacheDir = File(tempDir, "cache").apply { mkdirs() }
        db = buildTestDatabase()
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { File(tempDir, "instances.preferences_pb") })
        instanceStore = InstanceStore(dataStore, json)
        masterKeyHolder = MasterKeyHolder().apply { set(masterKey) }
        val apiFactory =
            ArchivistApiFactory(
                baseOkHttpClient = OkHttpClient.Builder().build(),
                json = json,
                tokenStore = TokenStore(FakeSharedPreferences(), json),
                cognitoAuthClient = CognitoAuthClient(FakeCognitoAuthApi(), json),
            )
        photoDetailRepository = PhotoDetailRepository(instanceStore, apiFactory, masterKeyHolder, OkHttpClient.Builder().build())
        val downloader =
            OriginalDownloader { _, _, rendition, target ->
                downloads += rendition
                target.writeBytes(originalBytes)
            }
        repository =
            LocationUpdateRepository(
                instanceStore = instanceStore,
                archivistApiFactory = apiFactory,
                masterKeyHolder = masterKeyHolder,
                photoDetailRepository = photoDetailRepository,
                originalDownloader = downloader,
                ownerSettingsRepository = OwnerSettingsRepository(instanceStore, apiFactory),
                placeGeocoder = PlaceGeocoder(geocoder) { _, _ -> GeoPacks.None },
                videoLocationReader = FakeVideoLocationReader(FakeMediaStoreSource()),
                photoDao = db.photoDao(),
                placeRepository = PlaceRepository(db, instanceStore, apiFactory, json),
                placeFilterRepository = placeFilterFor(db),
                context = mock<Context>().also { whenever(it.cacheDir).thenReturn(cacheDir) },
            )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        db.close()
        tempDir.deleteRecursively()
    }

    private var storedPlace: JsonElement = JsonNull

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
        db.photoDao().upsertAll(
            listOf(
                PhotoEntity(
                    photoId = photoId,
                    takenAt = "2026-08-30T10:00:00.000Z",
                    tzOffsetMin = 540,
                    mime = renditionMime,
                    width = 4000,
                    height = 3000,
                    status = AssetStatus.READY,
                    thumbs = emptyMap(),
                    encDek = encode(masterKey.wrapDek(dek)),
                    encKeyId = "mk-1",
                    placeCc = "FR",
                    placeName = "Arles",
                    placeKey = "3037854",
                ),
            ),
        )
        db.placeVocabularyDao().set("""{"countries":[]}""", "\"pl-3\"")
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)

    private fun decryptBlob(
        enc: String,
        iv: String,
    ): ExifBlob = ExifCrypto.decrypt(dek, photoId, decode(iv), decode(enc))

    private fun detailJson(): String {
        val enc = exifBlob?.let { ExifCrypto.encrypt(dek, photoId, it) }
        return """
            {
              "meta": {
                "photoId": "$photoId",
                "stem": "IMG_1",
                "primaryRend": "r1",
                "renditions": 1,
                "mime": "$renditionMime",
                "width": 4000,
                "height": 3000,
                "encDek": "${encode(masterKey.wrapDek(dek))}",
                "encKeyId": "mk-1",
                "takenAt": "2026-08-30T10:00:00.000Z",
                "tzOffsetMin": 540,
                "tzSrc": "exif-offset",
                "takenAtSrc": "exif",
                "uploadedAt": "2026-08-30T10:05:00.000Z",
                ${enc?.let { "\"exifEnc\": \"${encode(it.ciphertext)}\", \"exifIv\": \"${encode(it.iv)}\"," } ?: ""}
                "place": $storedPlace,
                "groupSrc": "stem",
                "status": "ready"
              },
              "renditions": [
                {
                  "renditionId": "r1",
                  "role": "display",
                  "path": "Camera/IMG_1.$renditionExt",
                  "ext": "$renditionExt",
                  "mime": "$renditionMime",
                  "s3Key": "raw/owner/$photoId/r1",
                  "contentHash": "hmac-sha256:test",
                  "bytes": 1040,
                  "plainBytes": 1024,
                  "width": 4000,
                  "height": 3000,
                  "encIv": "${encode(ByteArray(WholeObjectCipher.IV_LEN))}",
                  "encChunkSize": 0,
                  "addedAt": "2026-08-30T10:05:00.000Z"
                }
              ],
              "facets": []
            }
            """.trimIndent()
    }

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("exif-fixtures/$name")?.readBytes()) {
            "missing test fixture exif-fixtures/$name"
        }

    private val kyotoPlace =
        """{"cc":"JP","region":{"id":"JP.22","name":"Kyoto"},"locality":{"id":"1857910","name":"Kyoto"},"src":"geonames-cities500@2026-11-13"}"""

    private suspend fun assertRoomRowIsKyoto() {
        val row = db.photoDao().getByPhotoId(photoId)!!
        assertEquals("JP", row.placeCc)
        assertEquals("Kyoto", row.placeName)
        assertEquals("1857910", row.placeKey)
    }

    /**
     * Place filter step 7 (F13): the filter's cached rows of the photo follow the update.
     * A row whose stream still holds the new place keeps the stream's own place (F12); one
     * whose stream it has left stays in the cache for the session (the detail pager keeps
     * its photo) but shows the photo's real place now.
     */
    @Test
    fun `the place filter's cached rows are patched, and a row the photo has left keeps it`() =
        runTest {
            exifBlob = ExifBlob(cameraModel = "Pixel 8a", gpsLat = 35.01161, gpsLon = 135.76804)
            connect()
            db.seedFilterRow("REGION#JP.22", photoId, placeCc = "JP", placeName = "Kyoto (region)", placeKey = "JP.22", specificity = 1)
            db.seedFilterRow("PLACE#3037854", photoId, placeCc = "FR", placeName = "Arles", placeKey = "3037854")
            db.seedFilterRow("COUNTRY#FR", photoId, placeCc = "FR", specificity = 0)
            db.seedFilterRow("PLACE#3037854", "other-photo", placeCc = "FR", placeName = "Arles", placeKey = "3037854")
            val detail = photoDetailRepository.fetchDetail(photoId)

            assertTrue(repository.updateLocation(detail) is LocationUpdateOutcome.Updated)

            val rows = db.facetStreamDao().rowsOfPhoto(photoId).associateBy { it.streamKey }
            assertEquals(setOf("REGION#JP.22", "PLACE#3037854", "COUNTRY#FR"), rows.keys)
            // Still in the region stream: its own place, as F12 gives it.
            assertEquals(Triple("JP", "Kyoto (region)", "JP.22"), rows.getValue("REGION#JP.22").let { Triple(it.placeCc, it.placeName, it.placeKey) })
            // Left Arles and France: the photo's real place, flattened as photos does.
            for (key in listOf("PLACE#3037854", "COUNTRY#FR")) {
                assertEquals(Triple("JP", "Kyoto", "1857910"), rows.getValue(key).let { Triple(it.placeCc, it.placeName, it.placeKey) })
            }
            // Another photo's row is untouched.
            assertEquals("Arles", db.facetStreamDao().rowsOfPhoto("other-photo").single().placeName)
            assertRoomRowIsKyoto()
        }

    @Test
    fun `coordinates already in the blob - no download, no exifEnc in the PUT`() =
        runTest {
            exifBlob = ExifBlob(cameraModel = "Pixel 8a", gpsLat = 35.01161, gpsLon = 135.76804)
            connect()
            val detail = photoDetailRepository.fetchDetail(photoId)

            val outcome = repository.updateLocation(detail)

            assertTrue(outcome is LocationUpdateOutcome.Updated, "$outcome")
            assertTrue(downloads.isEmpty())
            val put = putBodies.single()
            assertEquals(json.parseToJsonElement(kyotoPlace), put["place"])
            assertFalse("exifEnc" in put)
            assertFalse("exifIv" in put)
            assertEquals("JP", (outcome as LocationUpdateOutcome.Updated).detail.place?.cc)
            assertRoomRowIsKyoto()
        }

    @Test
    fun `no coordinates in the blob - downloads the original and sends a re-encrypted blob`() =
        runTest {
            val before =
                ExifBlob(
                    cameraMake = "Google",
                    cameraModel = "Pixel 8a",
                    cameraSerial = "SN1",
                    lens = "Main",
                    dateTimeOriginal = "2026:08:30 19:00:00",
                    offsetTimeOriginal = "+09:00",
                    gpsDateTimeUtc = "2026-08-30T10:00:00Z",
                )
            exifBlob = before
            originalBytes = fixture("kyoto-gps.jpg")
            connect()
            val detail = photoDetailRepository.fetchDetail(photoId)

            val outcome = repository.updateLocation(detail)

            assertTrue(outcome is LocationUpdateOutcome.Updated, "$outcome")
            assertEquals(listOf("r1"), downloads.map { it.renditionId })
            val put = putBodies.single()
            assertEquals(json.parseToJsonElement(kyotoPlace), put["place"])
            val sent = decryptBlob(put.getValue("exifEnc").jsonPrimitive.content, put.getValue("exifIv").jsonPrimitive.content)
            assertEquals(before, sent.copy(gpsLat = null, gpsLon = null, gpsAltM = null))
            assertEquals(35.01161, sent.gpsLat!!, 1e-9)
            assertEquals(135.76804, sent.gpsLon!!, 1e-9)
            assertEquals(12.5, sent.gpsAltM!!, 1e-9)
            // A fresh IV, not the one the old blob had.
            assertEquals(WholeObjectCipher.IV_LEN, decode(put.getValue("exifIv").jsonPrimitive.content).size)
            // The refreshed detail now has the coordinates, so a second run needs no download.
            val refreshed = (outcome as LocationUpdateOutcome.Updated).detail
            assertEquals(35.01161, refreshed.exif?.gpsLat!!, 1e-9)
            assertRoomRowIsKyoto()
            assertTrue(cacheDir.listFiles()!!.isEmpty(), "temp file left behind")

            repository.updateLocation(refreshed)
            assertEquals(1, downloads.size)
            assertFalse("exifEnc" in putBodies.last())
        }

    @Test
    fun `an asset with no blob at all gets one holding only the coordinates`() =
        runTest {
            exifBlob = null
            originalBytes = fixture("kyoto-gps.jpg")
            connect()

            repository.updateLocation(photoDetailRepository.fetchDetail(photoId))

            val put = putBodies.single()
            val sent = decryptBlob(put.getValue("exifEnc").jsonPrimitive.content, put.getValue("exifIv").jsonPrimitive.content)
            assertEquals(ExifBlob(gpsLat = sent.gpsLat, gpsLon = sent.gpsLon, gpsAltM = sent.gpsAltM), sent)
            assertEquals(35.01161, sent.gpsLat!!, 1e-9)
        }

    @Test
    fun `no GPS anywhere - place null, stale place cleared`() =
        runTest {
            exifBlob = ExifBlob(cameraModel = "Pixel 8a")
            storedPlace = json.parseToJsonElement("""{"cc":"FR","locality":{"id":"3037854","name":"Arles"},"src":"x"}""")
            originalBytes = fixture("kyoto-gps-stripped.jpg")
            connect()

            val outcome = repository.updateLocation(photoDetailRepository.fetchDetail(photoId))

            assertTrue(outcome is LocationUpdateOutcome.NoLocation, "$outcome")
            val put = putBodies.single()
            assertEquals(JsonNull, put["place"]) // present, and null
            assertFalse("exifEnc" in put)
            assertNull((outcome as LocationUpdateOutcome.NoLocation).detail.place)
            val row = db.photoDao().getByPhotoId(photoId)!!
            assertNull(row.placeCc)
            assertNull(row.placeName)
            assertNull(row.placeKey)
            assertTrue(cacheDir.listFiles()!!.isEmpty())
        }

    @Test
    fun `a zeroed GPS block counts as none, in the blob and in the original`() =
        runTest {
            exifBlob = ExifBlob(gpsLat = 0.0, gpsLon = 0.0)
            originalBytes = fixture("gps-zeroed.jpg")
            connect()

            val outcome = repository.updateLocation(photoDetailRepository.fetchDetail(photoId))

            assertTrue(outcome is LocationUpdateOutcome.NoLocation, "$outcome")
            assertEquals(1, downloads.size)
            assertEquals(JsonNull, putBodies.single()["place"])
        }

    @Test
    fun `a video's location comes from its container`() =
        runTest {
            renditionMime = "video/mp4"
            renditionExt = "mp4"
            exifBlob = null
            val text = "+35.0116+135.7681+012.5/".toByteArray(Charsets.US_ASCII)
            val payload = byteArrayOf((text.size shr 8).toByte(), text.size.toByte(), 0x15, 0xC7.toByte()) + text
            originalBytes = SyntheticMp4.file(SyntheticMp4.moovBox(SyntheticMp4.udtaBox(SyntheticMp4.box("©xyz", payload))))
            connect()

            val outcome = repository.updateLocation(photoDetailRepository.fetchDetail(photoId))

            assertTrue(outcome is LocationUpdateOutcome.Updated, "$outcome")
            val put = putBodies.single()
            assertEquals(json.parseToJsonElement(kyotoPlace), put["place"])
            val sent = decryptBlob(put.getValue("exifEnc").jsonPrimitive.content, put.getValue("exifIv").jsonPrimitive.content)
            assertEquals(ExifBlob(gpsLat = 35.0116, gpsLon = 135.7681, gpsAltM = 12.5), sent)
            assertTrue(cacheDir.listFiles()!!.isEmpty())
        }

    @Test
    fun `a 409 refetches the detail and retries once, without downloading again`() =
        runTest {
            exifBlob = ExifBlob(cameraModel = "Pixel 8a")
            originalBytes = fixture("kyoto-gps.jpg")
            connect()
            val detail = photoDetailRepository.fetchDetail(photoId)
            val getsBefore = detailGets
            putCodes += 409

            val outcome = repository.updateLocation(detail)

            assertTrue(outcome is LocationUpdateOutcome.Updated, "$outcome")
            assertEquals(2, putBodies.size)
            assertEquals(1, downloads.size)
            // One refetch for the retry, one for the refreshed detail.
            assertEquals(getsBefore + 2, detailGets)
            assertEquals(putBodies[0]["place"], putBodies[1]["place"])
            assertTrue("exifEnc" in putBodies[1])
            assertRoomRowIsKyoto()
        }

    @Test
    fun `a 409 TRANSACTION_CONFLICT resends the same PUT without refetching`() =
        runTest {
            exifBlob = ExifBlob(gpsLat = 35.01161, gpsLon = 135.76804)
            connect()
            val detail = photoDetailRepository.fetchDetail(photoId)
            val getsBefore = detailGets
            putCodes += listOf(TX_CONFLICT, TX_CONFLICT)

            val outcome = repository.updateLocation(detail)

            assertTrue(outcome is LocationUpdateOutcome.Updated, "$outcome")
            assertEquals(3, putBodies.size)
            assertEquals(putBodies[0], putBodies[2])
            // Only the refreshed detail after success: a transaction conflict says
            // nothing about the photo itself, so there's nothing to re-read.
            assertEquals(getsBefore + 1, detailGets)
            assertRoomRowIsKyoto()
        }

    @Test
    fun `a TRANSACTION_CONFLICT that never clears gives up after the capped attempts`() =
        runTest {
            exifBlob = ExifBlob(gpsLat = 35.01161, gpsLon = 135.76804)
            connect()
            val detail = photoDetailRepository.fetchDetail(photoId)
            val getsBefore = detailGets
            putCodes += List(10) { TX_CONFLICT }

            val outcome = repository.updateLocation(detail)

            assertTrue(outcome is LocationUpdateOutcome.Error, "$outcome")
            assertEquals(TransactionConflictRetry.DEFAULT.maxAttempts, putBodies.size)
            assertEquals(getsBefore, detailGets)
            assertEquals("FR", db.photoDao().getByPhotoId(photoId)!!.placeCc) // untouched
        }

    @Test
    fun `a second 409 gives up with an error`() =
        runTest {
            exifBlob = ExifBlob(gpsLat = 35.01161, gpsLon = 135.76804)
            connect()
            putCodes += listOf(409, 409)

            val outcome = repository.updateLocation(photoDetailRepository.fetchDetail(photoId))

            assertTrue(outcome is LocationUpdateOutcome.Error, "$outcome")
            assertEquals(2, putBodies.size)
            assertEquals("FR", db.photoDao().getByPhotoId(photoId)!!.placeCc) // untouched
        }

    @Test
    fun `a success drops the vocabulary ETag but keeps its body`() =
        runTest {
            exifBlob = ExifBlob(gpsLat = 35.01161, gpsLon = 135.76804)
            connect()

            repository.updateLocation(photoDetailRepository.fetchDetail(photoId))

            val row = db.placeVocabularyDao().get()!!
            assertNull(row.etag)
            assertEquals("""{"countries":[]}""", row.body)
        }

    @Test
    fun `strip-on hides the action and refuses to run`() =
        runTest {
            exifBlob = ExifBlob(gpsLat = 35.01161, gpsLon = 135.76804)
            connect()
            assertTrue(repository.isAvailable())

            stripOn = true

            assertFalse(repository.isAvailable())
            val outcome = repository.updateLocation(photoDetailRepository.fetchDetail(photoId))
            assertTrue(outcome is LocationUpdateOutcome.Error, "$outcome")
            assertTrue(putBodies.isEmpty())
        }

    @Test
    fun `a blob that failed to decrypt is not overwritten`() =
        runTest {
            exifBlob = ExifBlob(cameraModel = "Pixel 8a")
            originalBytes = fixture("kyoto-gps.jpg")
            connect()
            val detail = photoDetailRepository.fetchDetail(photoId).copy(exif = null, exifDecryptFailed = true)

            val outcome = repository.updateLocation(detail)

            assertTrue(outcome is LocationUpdateOutcome.Error, "$outcome")
            assertTrue(putBodies.isEmpty())
            assertTrue(downloads.isEmpty())
        }

    private companion object {
        /** The real bundled gazetteer, located by the system property `app/build.gradle.kts`
         * sets for `ReverseGeocoderTest`. */
        val geocoder by lazy {
            ReverseGeocoder(FileGazetteerSource(File(requireNotNull(System.getProperty("archivist.gazetteer.bin")))))
        }
    }
}

/** A `putCodes` entry answered with 409 and `code: "TRANSACTION_CONFLICT"`. */
private const val TX_CONFLICT = 4091
